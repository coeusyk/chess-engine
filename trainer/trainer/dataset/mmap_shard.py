"""Memory-mapped binary shard format (PRD Trainer Requirements: "memory-mapped
binary shards + numpy batching, specified up front -- Python-side loading is the
known bottleneck at the 50-100M position scale").

This is a format-conversion utility, not a DatasetProvider -- any provider's
PositionRecord stream can be written to a shard here and read back later without
re-parsing the original source. It is deliberately independent of which
DatasetProvider produced the records (works the same for Stage 1/2/3), matching
docs/architecture/NNUE_TRAINER_ARCHITECTURE.md Section 3's DatasetProvider isolation
Invariant -- this module is not a DatasetProvider itself and does not import one.

Only the fields Stage 1 actually populates (fen, eval_cp, eval_mate, ply) are stored.
This is not a claim that Stage 2/3 will reuse this exact layout unmodified -- wdl/
game_id/search_depth/search_nodes are real PositionMetadata/PositionLabel fields
(docs/architecture/NNUE_TRAINER_ARCHITECTURE.md's DatasetProvider contract already
carries them) that this shard format does not yet need to store, since nothing writes
them yet; extending SHARD_DTYPE when Stage 2/3 land is expected, not a design flaw
being deferred.
"""

from __future__ import annotations

from pathlib import Path
from typing import Iterable, Iterator

import numpy as np

from trainer.contracts import PositionLabel, PositionMetadata, PositionRecord, ShardRef

# Longest realistic FEN (castling rights, en passant, full move counters) is well
# under 90 bytes; 90 gives headroom without being large enough to matter for shard
# size at the 50-100M position scale this format targets.
_FEN_BYTES = 90

SHARD_DTYPE = np.dtype(
    [
        ("fen", f"S{_FEN_BYTES}"),
        ("eval_cp", "i4"),
        ("has_eval_cp", "?"),
        ("eval_mate", "i4"),
        ("has_eval_mate", "?"),
        ("ply", "i4"),
        ("has_ply", "?"),
    ]
)


def _encode(record: PositionRecord) -> tuple:
    fen_bytes = record.fen.encode("ascii")
    if len(fen_bytes) > _FEN_BYTES:
        raise ValueError(f"FEN exceeds {_FEN_BYTES}-byte shard field: {record.fen!r}")
    label = record.label
    metadata = record.metadata
    if label.eval_cp is None and label.eval_mate is None:
        # SHARD_DTYPE has no wdl field yet (module docstring: extend it when a
        # wdl-producing source needs this writer). Silently dropping a wdl-only
        # label here would write a record that reads back as an invalid
        # PositionLabel (ValueError on decode) -- fail loudly at write time instead.
        raise ValueError(
            f"write_shard cannot represent a wdl-only PositionLabel for {record.fen!r} "
            "-- SHARD_DTYPE has no wdl field; extend it before writing wdl-sourced data"
        )
    return (
        fen_bytes,
        label.eval_cp if label.eval_cp is not None else 0,
        label.eval_cp is not None,
        label.eval_mate if label.eval_mate is not None else 0,
        label.eval_mate is not None,
        metadata.ply if metadata.ply is not None else 0,
        metadata.ply is not None,
    )


def _decode(row: np.void) -> PositionRecord:
    fen = bytes(row["fen"]).rstrip(b"\x00").decode("ascii")
    label = PositionLabel(
        eval_cp=int(row["eval_cp"]) if bool(row["has_eval_cp"]) else None,
        eval_mate=int(row["eval_mate"]) if bool(row["has_eval_mate"]) else None,
    )
    metadata = PositionMetadata(ply=int(row["ply"]) if bool(row["has_ply"]) else None)
    return PositionRecord(fen=fen, label=label, metadata=metadata)


# Bounds peak memory during write to one batch, not the full input -- the PRD's own
# stated reason this format exists ("Python-side loading is the known bottleneck at
# the 50-100M position scale") applies just as much to writing as to reading.
_WRITE_BATCH_SIZE = 10_000


def write_shard(records: Iterable[PositionRecord], path: Path) -> ShardRef:
    """Write `records` to a binary shard at `path`, streaming in bounded batches --
    never materializes the full input in memory at once.
    """
    count = 0
    batch: list = []
    with open(path, "wb") as f:
        for record in records:
            batch.append(_encode(record))
            count += 1
            if len(batch) >= _WRITE_BATCH_SIZE:
                np.array(batch, dtype=SHARD_DTYPE).tofile(f)
                batch.clear()
        if batch:
            np.array(batch, dtype=SHARD_DTYPE).tofile(f)
    return ShardRef(locator=str(path), position_count=count)


def read_shard(shard: ShardRef) -> Iterator[PositionRecord]:
    """Stream `PositionRecord`s from a shard written by `write_shard`, via
    `np.memmap` -- the shard's bytes are not loaded into memory all at once.
    """
    mapped = np.memmap(shard.locator, dtype=SHARD_DTYPE, mode="r")
    for row in mapped:
        yield _decode(row)
