"""Memory-mapped binary shard format (PRD Trainer Requirements: "memory-mapped
binary shards + numpy batching, specified up front -- Python-side loading is the
known bottleneck at the 50-100M position scale").

This is a format-conversion utility, not a DatasetProvider -- any provider's
PositionRecord stream can be written to a shard here and read back later without
re-parsing the original source. It is deliberately independent of which
DatasetProvider produced the records (works the same for Stage 1/2/3), matching
docs/architecture/NNUE_TRAINER_ARCHITECTURE.md Section 3's DatasetProvider isolation
Invariant -- this module is not a DatasetProvider itself and does not import one.

## Format versioning (#207)

Every shard file now begins with a fixed-size V1 envelope header (magic, format
version, header size, record size/stride, record count) followed by
`record_count` fixed-stride records laid out per `SHARD_DTYPE`. The header is
authoritative for physical layout -- `read_shard()` validates it exactly
(magic/version/header_size/record_size match, total file size agrees with
`header_size + record_count * record_size`) and rejects anything that doesn't,
rather than inferring record count from file size. This is deliberately
trainer-internal: it need not (and does not) match VSPR's own wire format
(`trainer/trainer/vspr.py`) -- the two formats solve unrelated problems (a
Stage-3 self-play frame log vs. a flat labeled-position table) and have no
reason to share a byte layout.

Byte order is explicit little-endian (`<`) on every multi-byte field in both the
header and `SHARD_DTYPE`, rather than numpy's native/unspecified default. This
was a real, verified gap in the pre-V1 format: `np.dtype([("eval_cp", "i4"), ...])`
resolves its typestrs to the *native* byte order at construction time (confirmed
this session -- `SHARD_DTYPE.descr` showed `<i4` etc. on this x86_64 host, silently
picking up the host's endianness). A shard is a file that can outlive the process
that wrote it and may be read on a different machine; pinning the byte order
explicitly makes that portable and makes the on-disk layout legible from the
dtype declaration alone, at zero runtime cost (x86_64/ARM are both little-endian,
so this costs nothing here and only pays off if it ever doesn't).

Versioning is exact-match-or-reject, not forward/backward compatible: there is
exactly one current format version (1) and no speculative extension mechanism
for hypothetical future versions. When a V2 is actually needed, add it then, with
a real second format to design against -- not now, against a format that doesn't
exist yet.

## Legacy formats and migration (#207)

Two legacy (pre-header, pre-versioned) layouts exist, reconstructed from source
history rather than from prose (`rtk git show 17c6ec3:trainer/trainer/dataset/mmap_shard.py`
plus a live `itemsize` check against the layout committed just before this one):

- **legacy119**: the D-8 (#199) layout, before WDL was added. Fields: fen(90),
  eval_cp/has_eval_cp, eval_mate/has_eval_mate, ply/has_ply,
  search_depth/has_search_depth, search_nodes/has_search_nodes. itemsize == 119.
- **legacy124**: the pre-#207 layout on this branch (the `SHARD_DTYPE` immediately
  above this change) -- legacy119 plus wdl/has_wdl inserted after
  eval_mate/has_eval_mate. itemsize == 124.

Both are real: `trainer/outputs/datasets/stage2-quiet-sf/shard-0.bin` (legacy119,
2,380,000 bytes = 20,000 * 119) and `trainer/outputs/datasets/stage2-quiet-sf-wdl/
shard-0.bin` (legacy124, 2,480,000 bytes = 20,000 * 124) are both real local
artifacts, not hypothetical.

`migrate_legacy119_to_v1()` and `migrate_legacy124_to_v1()` are the only places
either legacy layout is known to this module -- the normal `read_shard()` path
never sniffs or guesses a legacy layout; a file that isn't valid V1 is simply
rejected. Migration is explicit (the caller states which legacy layout the source
is in -- file-size divisibility by itemsize is checked only as a documented,
admittedly-imperfect sanity pre-check, since a legacy119 file's size can also
happen to be divisible by 124's stride only by numeric coincidence, not because
it's semantically ambiguous). Migration reads the source and writes a *new*
destination; it never modifies the source in place, never overwrites an existing
destination, and never fabricates a `game_id` for rows that never had one
(`has_game_id=False` for every migrated row).

`game_id` (new in #207) uses the same explicit-presence-flag pattern already
established for `eval_cp`/`eval_mate`/`wdl`/`ply`/`search_depth`/`search_nodes`:
`has_game_id: bool` + `game_id: int64`, so `game_id=0` is a valid, present ID and
there is no sentinel value overloaded to mean "missing". Per the #209/#220
contract, a shard's `game_id` is unique only within its own dataset/run --
never globally -- so this module stores only the bare numeric ID and leaves
`(dataset/run identity, game_id)` as the caller's job to track.

`wdl` was added in Phase 4-WDL (research doc RQ-4, `docs/architecture/research/nnue/
phase4c-reranking-wdl-audit.md`); `search_depth`/`search_nodes` in D-8 (#199).
`trainer/scripts/backfill_stage2_wdl.py` is the historical legacy119->legacy124
migration script that predates this module's own header/versioning -- it is left
as-is (its job, producing `stage2-quiet-sf-wdl/`, is already done, and it carries
its own frozen `_LEGACY_SHARD_DTYPE_NO_WDL` rather than importing this module's
legacy dtypes) and continues to work: it calls `write_shard()`, which now
transparently produces V1 output.
"""

from __future__ import annotations

import os
import struct
import tempfile
from dataclasses import dataclass
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
        ("eval_cp", "<i4"),
        ("has_eval_cp", "?"),
        ("eval_mate", "<i4"),
        ("has_eval_mate", "?"),
        ("wdl", "<f4"),
        ("has_wdl", "?"),
        ("ply", "<i4"),
        ("has_ply", "?"),
        ("search_depth", "<i4"),
        ("has_search_depth", "?"),
        # i8: a node budget can exceed int32 range at high search depth/time.
        ("search_nodes", "<i8"),
        ("has_search_nodes", "?"),
        ("game_id", "<i8"),
        ("has_game_id", "?"),
    ]
)

# ==========================================================================
# V1 envelope header -- fixed size, explicit little-endian, exact-match-or-reject.
# ==========================================================================

_MAGIC = b"VEXSHRD\x00"  # 8 bytes, NUL-padded -- not itself ASCII-printable text.
_FORMAT_VERSION = 1

# "<8sIIIQ": magic(8s) | format_version(u32) | header_size(u32) | record_size(u32)
# | record_count(u64). Explicit "<" prefix -- no native padding, no ambiguity.
_HEADER_STRUCT = struct.Struct("<8sIIIQ")
HEADER_SIZE = _HEADER_STRUCT.size  # 28 bytes.

# Sanity ceiling on record_count so a corrupted/adversarial header is rejected
# before any size arithmetic is trusted -- not a real capacity limit (Python ints
# are arbitrary precision, so header_size + record_count * record_size never
# overflows), just a fail-fast bound well above any realistic shard.
_MAX_RECORD_COUNT = 2**40


class ShardFormatError(ValueError):
    """A shard file's header or layout does not match the V1 format exactly."""


@dataclass(frozen=True)
class ShardHeader:
    format_version: int
    header_size: int
    record_size: int
    record_count: int


def _write_header(f, record_count: int) -> None:
    f.write(
        _HEADER_STRUCT.pack(_MAGIC, _FORMAT_VERSION, HEADER_SIZE, SHARD_DTYPE.itemsize, record_count)
    )


def _read_header(path: Path) -> ShardHeader:
    file_size = os.path.getsize(path)
    if file_size < HEADER_SIZE:
        raise ShardFormatError(f"{path}: file too small to contain a V1 header ({file_size} bytes)")

    with open(path, "rb") as f:
        raw = f.read(HEADER_SIZE)

    magic, format_version, header_size, record_size, record_count = _HEADER_STRUCT.unpack(raw)

    if magic != _MAGIC:
        raise ShardFormatError(f"{path}: bad magic {magic!r}")
    if format_version != _FORMAT_VERSION:
        raise ShardFormatError(
            f"{path}: unsupported format_version {format_version} (expected {_FORMAT_VERSION})"
        )
    if header_size != HEADER_SIZE:
        raise ShardFormatError(f"{path}: header_size {header_size} != expected {HEADER_SIZE}")
    if record_size != SHARD_DTYPE.itemsize:
        raise ShardFormatError(
            f"{path}: record_size {record_size} != SHARD_DTYPE.itemsize {SHARD_DTYPE.itemsize}"
        )
    if record_count > _MAX_RECORD_COUNT:
        raise ShardFormatError(f"{path}: record_count {record_count} exceeds sanity bound")

    # Guarded before any allocation/mapping: Python ints are arbitrary-precision,
    # so this can't silently wrap the way fixed-width arithmetic could.
    expected_size = header_size + record_count * record_size
    if file_size != expected_size:
        raise ShardFormatError(
            f"{path}: file size {file_size} != header_size + record_count*record_size "
            f"({expected_size})"
        )

    return ShardHeader(
        format_version=format_version,
        header_size=header_size,
        record_size=record_size,
        record_count=record_count,
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
        metadata.game_id if metadata.game_id is not None else 0,
        metadata.game_id is not None,
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
        game_id=int(row["game_id"]) if bool(row["has_game_id"]) else None,
    )
    return PositionRecord(fen=fen, label=label, metadata=metadata)


# Bounds peak memory during write to one batch, not the full input -- the PRD's own
# stated reason this format exists ("Python-side loading is the known bottleneck at
# the 50-100M position scale") applies just as much to writing as to reading.
_WRITE_BATCH_SIZE = 10_000


def _write_v1_atomic(path: Path, encoded_batches: Iterable[list]) -> int:
    """Write a placeholder header, stream `encoded_batches` (each a list of
    `_encode()`-shaped tuples), patch in the real record_count, fsync, verify, and
    atomically rename into place. `path`'s parent directory must already exist.
    Never leaves a partially-written file at `path` itself -- failures only ever
    leave (or fail to clean up) the temp file. Returns the record count written.
    """
    count = 0
    fd, tmp_name = tempfile.mkstemp(dir=path.parent, prefix=f".{path.name}.", suffix=".tmp")
    try:
        with os.fdopen(fd, "wb") as f:
            _write_header(f, record_count=0)  # placeholder, patched below
            for batch in encoded_batches:
                if not batch:
                    continue
                np.array(batch, dtype=SHARD_DTYPE).tofile(f)
                count += len(batch)
            f.flush()
            f.seek(0)
            _write_header(f, record_count=count)
            f.flush()
            os.fsync(f.fileno())

        # Verify before publishing: a reader must never see a destination that
        # looks like valid, complete V1 after a failed/truncated write.
        expected_size = HEADER_SIZE + count * SHARD_DTYPE.itemsize
        actual_size = os.path.getsize(tmp_name)
        if actual_size != expected_size:
            raise ShardFormatError(
                f"{tmp_name}: post-write size {actual_size} != expected {expected_size}"
            )

        os.replace(tmp_name, path)  # atomic on POSIX
    except BaseException:
        Path(tmp_name).unlink(missing_ok=True)
        raise
    return count


def _batched(records: Iterable[tuple], batch_size: int) -> Iterator[list]:
    batch: list = []
    for record in records:
        batch.append(record)
        if len(batch) >= batch_size:
            yield batch
            batch = []
    if batch:
        yield batch


def write_shard(records: Iterable[PositionRecord], path: Path) -> ShardRef:
    """Write `records` to a V1 binary shard at `path`, streaming in bounded
    batches -- never materializes the full input in memory at once. Overwrites
    an existing file at `path` (matching this function's pre-#207 behavior);
    use `migrate_legacy119_to_v1`/`migrate_legacy124_to_v1` when the destination
    must not be silently overwritten.
    """
    path = Path(path)
    encoded = (_encode(r) for r in records)
    count = _write_v1_atomic(path, _batched(encoded, _WRITE_BATCH_SIZE))
    return ShardRef(locator=str(path), position_count=count)


def read_shard(shard: ShardRef) -> Iterator[PositionRecord]:
    """Stream `PositionRecord`s from a V1 shard written by `write_shard`, via
    `np.memmap` at the header's fixed offset -- the shard's bytes are not loaded
    into memory all at once. Raises `ShardFormatError` for anything that isn't
    exactly a valid V1 shard (wrong magic/version/stride, size mismatch, ...);
    never guesses at a legacy layout.
    """
    path = Path(shard.locator)
    header = _read_header(path)
    mapped = np.memmap(
        path, dtype=SHARD_DTYPE, mode="r", offset=header.header_size, shape=(header.record_count,)
    )
    for row in mapped:
        yield _decode(row)


# ==========================================================================
# Legacy layout migration (#207) -- legacy dtypes are known ONLY here, never in
# the steady-state read_shard()/write_shard() path above.
# ==========================================================================

_LEGACY119_DTYPE = np.dtype(
    [
        ("fen", f"S{_FEN_BYTES}"),
        ("eval_cp", "<i4"),
        ("has_eval_cp", "?"),
        ("eval_mate", "<i4"),
        ("has_eval_mate", "?"),
        ("ply", "<i4"),
        ("has_ply", "?"),
        ("search_depth", "<i4"),
        ("has_search_depth", "?"),
        ("search_nodes", "<i8"),
        ("has_search_nodes", "?"),
    ]
)
assert _LEGACY119_DTYPE.itemsize == 119

_LEGACY124_DTYPE = np.dtype(
    [
        ("fen", f"S{_FEN_BYTES}"),
        ("eval_cp", "<i4"),
        ("has_eval_cp", "?"),
        ("eval_mate", "<i4"),
        ("has_eval_mate", "?"),
        ("wdl", "<f4"),
        ("has_wdl", "?"),
        ("ply", "<i4"),
        ("has_ply", "?"),
        ("search_depth", "<i4"),
        ("has_search_depth", "?"),
        ("search_nodes", "<i8"),
        ("has_search_nodes", "?"),
    ]
)
assert _LEGACY124_DTYPE.itemsize == 124


def _encode_from_legacy_row(row: np.void, *, has_wdl_field: bool) -> tuple:
    return (
        bytes(row["fen"]),
        int(row["eval_cp"]),
        bool(row["has_eval_cp"]),
        int(row["eval_mate"]),
        bool(row["has_eval_mate"]),
        float(row["wdl"]) if has_wdl_field else 0.0,
        bool(row["has_wdl"]) if has_wdl_field else False,
        int(row["ply"]),
        bool(row["has_ply"]),
        int(row["search_depth"]),
        bool(row["has_search_depth"]),
        int(row["search_nodes"]),
        bool(row["has_search_nodes"]),
        0,  # game_id: legacy layouts never stored one.
        False,  # has_game_id: never fabricated.
    )


def _migrate_legacy(source: Path, dest: Path, *, legacy_dtype: np.dtype, has_wdl_field: bool) -> ShardRef:
    source = Path(source)
    dest = Path(dest)
    if dest.exists():
        raise FileExistsError(f"migration destination already exists, refusing to overwrite: {dest}")

    source_size = os.path.getsize(source)
    if source_size % legacy_dtype.itemsize != 0:
        # Divisibility alone can't prove semantic layout identity -- this only
        # catches gross mismatches (wrong file, wrong mode entirely); the caller
        # choosing the correct source layout is the actual trust boundary here.
        raise ShardFormatError(
            f"{source}: size {source_size} is not a multiple of legacy record size "
            f"{legacy_dtype.itemsize} -- wrong source layout selected?"
        )

    mapped = np.memmap(source, dtype=legacy_dtype, mode="r")

    def batches() -> Iterator[list]:
        batch: list = []
        for row in mapped:
            batch.append(_encode_from_legacy_row(row, has_wdl_field=has_wdl_field))
            if len(batch) >= _WRITE_BATCH_SIZE:
                yield batch
                batch = []
        if batch:
            yield batch

    count = _write_v1_atomic(dest, batches())
    return ShardRef(locator=str(dest), position_count=count)


def migrate_legacy119_to_v1(source: Path, dest: Path) -> ShardRef:
    """Migrate a pre-WDL (#199-era, itemsize 119) legacy shard to V1. Non-
    destructive: `source` is only read, never modified; `dest` must not already
    exist. Every migrated record gets `has_wdl=False` and `has_game_id=False` --
    this layout never had either field, so none is fabricated.
    """
    return _migrate_legacy(source, dest, legacy_dtype=_LEGACY119_DTYPE, has_wdl_field=False)


def migrate_legacy124_to_v1(source: Path, dest: Path) -> ShardRef:
    """Migrate a post-WDL-backfill (pre-#207, itemsize 124) legacy shard to V1.
    Non-destructive: `source` is only read, never modified; `dest` must not
    already exist. `wdl`/`has_wdl` are preserved exactly from the source;
    `has_game_id=False` for every record -- this layout never had one.
    """
    return _migrate_legacy(source, dest, legacy_dtype=_LEGACY124_DTYPE, has_wdl_field=True)
