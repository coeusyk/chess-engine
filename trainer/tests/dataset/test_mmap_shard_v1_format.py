"""#207 -- V1 shard envelope: header validation, game_id, and legacy migration.
Complements test_mmap_shard.py (which covers the field-level round-trip contract
unaffected by versioning). Deterministic, in-memory-sized fixtures throughout --
file-size divisibility cannot prove semantic layout identity by itself (see
mmap_shard.py's own migration docstrings), so several cases here construct exact
byte-level corruption rather than relying on size alone.
"""

import struct
from pathlib import Path

import numpy as np
import pytest

from trainer.contracts import PositionLabel, PositionMetadata, PositionRecord, ShardRef
from trainer.dataset.mmap_shard import (
    HEADER_SIZE,
    SHARD_DTYPE,
    ShardFormatError,
    _HEADER_STRUCT,
    _LEGACY119_DTYPE,
    _LEGACY124_DTYPE,
    _MAGIC,
    migrate_legacy119_to_v1,
    migrate_legacy124_to_v1,
    read_shard,
    write_shard,
)

FEN = "4k3/8/8/8/8/8/4Q3/4K3 w - - 0 1"


def _record(**metadata_kwargs) -> PositionRecord:
    return PositionRecord(
        fen=FEN, label=PositionLabel(eval_cp=1), metadata=PositionMetadata(**metadata_kwargs)
    )


def _valid_shard(tmp_path: Path, n: int = 2) -> Path:
    path = tmp_path / "shard.bin"
    write_shard([_record() for _ in range(n)], path)
    return path


def _corrupt_header(path: Path, **overrides) -> None:
    with open(path, "rb") as f:
        magic, format_version, header_size, record_size, record_count = _HEADER_STRUCT.unpack(
            f.read(HEADER_SIZE)
        )
    fields = dict(
        magic=magic,
        format_version=format_version,
        header_size=header_size,
        record_size=record_size,
        record_count=record_count,
    )
    fields.update(overrides)
    with open(path, "r+b") as f:
        f.seek(0)
        f.write(
            _HEADER_STRUCT.pack(
                fields["magic"],
                fields["format_version"],
                fields["header_size"],
                fields["record_size"],
                fields["record_count"],
            )
        )


# ==========================================================================
# Header validation -- fail closed
# ==========================================================================


def test_valid_v1_shard_round_trips(tmp_path):
    path = _valid_shard(tmp_path, n=3)
    restored = list(read_shard(ShardRef(locator=str(path))))
    assert len(restored) == 3


def test_bad_magic_rejected(tmp_path):
    path = _valid_shard(tmp_path)
    _corrupt_header(path, magic=b"NOTASHRD")
    with pytest.raises(ShardFormatError):
        list(read_shard(ShardRef(locator=str(path))))


def test_wrong_format_version_rejected(tmp_path):
    path = _valid_shard(tmp_path)
    _corrupt_header(path, format_version=2)
    with pytest.raises(ShardFormatError):
        list(read_shard(ShardRef(locator=str(path))))


def test_wrong_header_size_rejected(tmp_path):
    path = _valid_shard(tmp_path)
    _corrupt_header(path, header_size=HEADER_SIZE + 1)
    with pytest.raises(ShardFormatError):
        list(read_shard(ShardRef(locator=str(path))))


def test_wrong_record_size_rejected(tmp_path):
    path = _valid_shard(tmp_path)
    _corrupt_header(path, record_size=SHARD_DTYPE.itemsize + 1)
    with pytest.raises(ShardFormatError):
        list(read_shard(ShardRef(locator=str(path))))


def test_record_count_disagreeing_with_file_size_rejected(tmp_path):
    path = _valid_shard(tmp_path, n=2)
    _corrupt_header(path, record_count=3)  # claims a 3rd record that isn't there
    with pytest.raises(ShardFormatError):
        list(read_shard(ShardRef(locator=str(path))))


def test_truncated_payload_rejected(tmp_path):
    path = _valid_shard(tmp_path, n=2)
    with open(path, "r+b") as f:
        f.truncate(HEADER_SIZE + SHARD_DTYPE.itemsize)  # only 1 of 2 records present
    with pytest.raises(ShardFormatError):
        list(read_shard(ShardRef(locator=str(path))))


def test_trailing_garbage_bytes_rejected(tmp_path):
    path = _valid_shard(tmp_path, n=2)
    with open(path, "ab") as f:
        f.write(b"\x00" * 5)
    with pytest.raises(ShardFormatError):
        list(read_shard(ShardRef(locator=str(path))))


def test_absurd_record_count_rejected_before_allocation(tmp_path):
    path = _valid_shard(tmp_path, n=1)
    _corrupt_header(path, record_count=2**60)
    with pytest.raises(ShardFormatError):
        list(read_shard(ShardRef(locator=str(path))))


def test_file_too_small_for_header_rejected(tmp_path):
    path = tmp_path / "tiny.bin"
    path.write_bytes(b"\x00" * (HEADER_SIZE - 1))
    with pytest.raises(ShardFormatError):
        list(read_shard(ShardRef(locator=str(path))))


def test_empty_shard_is_valid_zero_records(tmp_path):
    path = tmp_path / "empty.bin"
    ref = write_shard([], path)
    assert ref.position_count == 0
    assert list(read_shard(ref)) == []


# ==========================================================================
# game_id semantics
# ==========================================================================


def test_game_id_zero_round_trips_as_present_not_missing(tmp_path):
    record = _record(game_id=0)
    ref = write_shard([record], tmp_path / "s.bin")
    restored = list(read_shard(ref))[0]
    assert restored.metadata.game_id == 0


def test_game_id_max_int64_round_trips(tmp_path):
    max_i64 = 2**63 - 1
    record = _record(game_id=max_i64)
    ref = write_shard([record], tmp_path / "s.bin")
    restored = list(read_shard(ref))[0]
    assert restored.metadata.game_id == max_i64


def test_absent_game_id_round_trips_as_none(tmp_path):
    record = _record()
    ref = write_shard([record], tmp_path / "s.bin")
    restored = list(read_shard(ref))[0]
    assert restored.metadata.game_id is None


def test_mixed_present_and_absent_game_id_in_same_shard(tmp_path):
    records = [_record(game_id=0), _record(), _record(game_id=42)]
    ref = write_shard(records, tmp_path / "s.bin")
    restored = list(read_shard(ref))
    assert [r.metadata.game_id for r in restored] == [0, None, 42]


# ==========================================================================
# Legacy migration
# ==========================================================================


def _write_legacy119(records, path: Path) -> None:
    rows = []
    for r in records:
        rows.append(
            (
                r.fen.encode("ascii"),
                r.label.eval_cp or 0,
                r.label.eval_cp is not None,
                r.label.eval_mate or 0,
                r.label.eval_mate is not None,
                r.metadata.ply or 0,
                r.metadata.ply is not None,
                r.metadata.search_depth or 0,
                r.metadata.search_depth is not None,
                r.metadata.search_nodes or 0,
                r.metadata.search_nodes is not None,
            )
        )
    np.array(rows, dtype=_LEGACY119_DTYPE).tofile(path)


def _write_legacy124(records, path: Path) -> None:
    rows = []
    for r in records:
        rows.append(
            (
                r.fen.encode("ascii"),
                r.label.eval_cp or 0,
                r.label.eval_cp is not None,
                r.label.eval_mate or 0,
                r.label.eval_mate is not None,
                r.label.wdl or 0.0,
                r.label.wdl is not None,
                r.metadata.ply or 0,
                r.metadata.ply is not None,
                r.metadata.search_depth or 0,
                r.metadata.search_depth is not None,
                r.metadata.search_nodes or 0,
                r.metadata.search_nodes is not None,
            )
        )
    np.array(rows, dtype=_LEGACY124_DTYPE).tofile(path)


def test_migrate_legacy119_preserves_fields_and_marks_no_wdl_no_game_id(tmp_path):
    source_records = [
        PositionRecord(
            fen=FEN,
            label=PositionLabel(eval_cp=100, eval_mate=None),
            metadata=PositionMetadata(ply=5, search_depth=10, search_nodes=123456),
        ),
        PositionRecord(
            fen="8/8/8/8/8/8/8/4K3 w - - 0 1", label=PositionLabel(eval_mate=2), metadata=PositionMetadata()
        ),
    ]
    source = tmp_path / "legacy119.bin"
    _write_legacy119(source_records, source)
    source_bytes_before = source.read_bytes()

    dest = tmp_path / "v1.bin"
    ref = migrate_legacy119_to_v1(source, dest)
    assert ref.position_count == 2

    restored = list(read_shard(ref))
    assert restored[0].label.eval_cp == 100
    assert restored[0].metadata.ply == 5
    assert restored[0].metadata.search_depth == 10
    assert restored[0].metadata.search_nodes == 123456
    assert restored[0].label.wdl is None
    assert restored[0].metadata.game_id is None
    assert restored[1].label.eval_mate == 2
    assert restored[1].label.wdl is None
    assert restored[1].metadata.game_id is None

    # Source untouched.
    assert source.read_bytes() == source_bytes_before


def test_migrate_legacy124_preserves_wdl_and_marks_no_game_id(tmp_path):
    source_records = [
        PositionRecord(
            fen=FEN, label=PositionLabel(eval_cp=100, wdl=0.75), metadata=PositionMetadata(ply=5)
        ),
        PositionRecord(
            fen="8/8/8/8/8/8/8/4K3 w - - 0 1", label=PositionLabel(eval_cp=1), metadata=PositionMetadata()
        ),
    ]
    source = tmp_path / "legacy124.bin"
    _write_legacy124(source_records, source)

    dest = tmp_path / "v1.bin"
    ref = migrate_legacy124_to_v1(source, dest)
    restored = list(read_shard(ref))

    assert restored[0].label.wdl == 0.75
    assert restored[0].metadata.ply == 5
    assert restored[0].metadata.game_id is None
    assert restored[1].label.wdl is None
    assert restored[1].metadata.game_id is None


def test_migration_refuses_to_overwrite_existing_destination(tmp_path):
    source = tmp_path / "legacy119.bin"
    _write_legacy119([_record()], source)
    dest = tmp_path / "v1.bin"
    dest.write_bytes(b"already here")

    with pytest.raises(FileExistsError):
        migrate_legacy119_to_v1(source, dest)


def test_migration_never_modifies_source_on_success(tmp_path):
    source = tmp_path / "legacy119.bin"
    _write_legacy119([_record(), _record()], source)
    before = source.read_bytes()
    migrate_legacy119_to_v1(source, tmp_path / "v1.bin")
    assert source.read_bytes() == before


def test_migrate_wrong_legacy_mode_selected_is_rejected(tmp_path):
    # A real legacy124 file fed to the legacy119 migration: its size is not a
    # multiple of 119, so the divisibility pre-check catches this specific case.
    # (Divisibility alone can't prove layout identity in general -- see module
    # docstring -- but this file's size does happen to make the mismatch visible.)
    source = tmp_path / "legacy124.bin"
    _write_legacy124([_record(), _record(), _record()], source)  # 3 * 124 = 372, not a multiple of 119
    assert (source.stat().st_size % 119) != 0

    with pytest.raises(ShardFormatError):
        migrate_legacy119_to_v1(source, tmp_path / "v1.bin")


def test_migrated_v1_shard_passes_full_header_validation(tmp_path):
    source = tmp_path / "legacy119.bin"
    _write_legacy119([_record(), _record()], source)
    dest = tmp_path / "v1.bin"
    migrate_legacy119_to_v1(source, dest)

    with open(dest, "rb") as f:
        magic, version, header_size, record_size, record_count = _HEADER_STRUCT.unpack(
            f.read(HEADER_SIZE)
        )
    assert magic == _MAGIC
    assert version == 1
    assert header_size == HEADER_SIZE
    assert record_size == SHARD_DTYPE.itemsize
    assert record_count == 2
