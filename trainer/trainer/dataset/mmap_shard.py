"""Memory-mapped binary shard format (PRD Trainer Requirements: "memory-mapped
binary shards + numpy batching, specified up front -- Python-side loading is the
known bottleneck at the 50-100M position scale").

This is a format-conversion utility, not a DatasetProvider -- any provider's
PositionRecord stream can be written to a shard here and read back later without
re-parsing the original source. It is deliberately independent of which
DatasetProvider produced the records (works the same for Stage 1/2/3), matching
docs/architecture/NNUE_TRAINER_ARCHITECTURE.md Section 3's DatasetProvider isolation
Invariant -- this module is not a DatasetProvider itself and does not import one.

`search_depth`/`search_nodes` were added in D-8 (issue #199, Stage 2 Stockfish
labeling) -- exactly the extension this module's own D-2-era docstring anticipated
("extending SHARD_DTYPE when Stage 2/3 land is expected, not a design flaw being
deferred"). `game_id` remains unstored -- no consumer needs it yet.

`wdl` was added in Phase 4-WDL (research doc RQ-4, `docs/architecture/research/nnue/
phase4c-reranking-wdl-audit.md`). Unlike the D-8 extension, one real shard already
existed on disk under the old layout (`outputs/datasets/stage2-quiet-sf/shard-0.bin`,
20,000 records, gitignored build output -- confirmed via `find -L . -name "*.bin"`
and `git ls-files`, no committed `.bin` fixtures exist anywhere in this repo) --
**this is a breaking format change for that file**: its byte layout no longer matches
`SHARD_DTYPE`, so `read_shard()` against it now raises `ValueError` (`np.memmap`'s own
itemsize-mismatch check, verified directly this session -- a loud failure, not a
silent misparse). `trainer/scripts/backfill_stage2_wdl.py` migrates it to a new
directory (`stage2-quiet-sf-wdl/`) under the new layout, backfilling `wdl` via a pure
FEN join against `data/quiet-labeled.epd` -- the original `stage2-quiet-sf/` directory
is left untouched (non-destructive; any future non-WDL work can still use it, though
`read_shard()` itself can no longer open it without going through the same migration).
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
        ("wdl", "f4"),
        ("has_wdl", "?"),
        ("ply", "i4"),
        ("has_ply", "?"),
        ("search_depth", "i4"),
        ("has_search_depth", "?"),
        # i8: a node budget can exceed int32 range at high search depth/time.
        ("search_nodes", "i8"),
        ("has_search_nodes", "?"),
    ]
)


def _encode(record: PositionRecord) -> tuple:
    fen_bytes = record.fen.encode("ascii")
    if len(fen_bytes) > _FEN_BYTES:
        raise ValueError(f"FEN exceeds {_FEN_BYTES}-byte shard field: {record.fen!r}")
    label = record.label
    metadata = record.metadata
    # No "at least one label field present" guard needed here: PositionLabel's own
    # __post_init__ already enforces that invariant at construction time, and every
    # field this format stores (eval_cp, eval_mate, wdl) now has a has_* flag, so a
    # wdl-only label round-trips correctly -- unlike before this module's Phase 4-WDL
    # extension, when a wdl-only label had no representable field at all.
    return (
        fen_bytes,
        label.eval_cp if label.eval_cp is not None else 0,
        label.eval_cp is not None,
        label.eval_mate if label.eval_mate is not None else 0,
        label.eval_mate is not None,
        label.wdl if label.wdl is not None else 0.0,
        label.wdl is not None,
        metadata.ply if metadata.ply is not None else 0,
        metadata.ply is not None,
        metadata.search_depth if metadata.search_depth is not None else 0,
        metadata.search_depth is not None,
        metadata.search_nodes if metadata.search_nodes is not None else 0,
        metadata.search_nodes is not None,
    )


def _decode(row: np.void) -> PositionRecord:
    fen = bytes(row["fen"]).rstrip(b"\x00").decode("ascii")
    label = PositionLabel(
        eval_cp=int(row["eval_cp"]) if bool(row["has_eval_cp"]) else None,
        eval_mate=int(row["eval_mate"]) if bool(row["has_eval_mate"]) else None,
        wdl=float(row["wdl"]) if bool(row["has_wdl"]) else None,
    )
    metadata = PositionMetadata(
        ply=int(row["ply"]) if bool(row["has_ply"]) else None,
        search_depth=int(row["search_depth"]) if bool(row["has_search_depth"]) else None,
        search_nodes=int(row["search_nodes"]) if bool(row["has_search_nodes"]) else None,
    )
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
