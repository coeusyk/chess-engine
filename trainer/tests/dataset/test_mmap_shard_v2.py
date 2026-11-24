"""#275 -- shard format version 2 (100-byte FEN) and version 1 read compatibility.

The version 1 files here are built by hand with `SHARD_DTYPE_V1` and a packed header, not by
`write_shard`, so they are independent of the writer under test (which now writes version 2).
Every consumer entry point that opens shards is exercised against both versions: `read_shard`,
`StockfishLabeledProvider`, `SelfPlayProvider` and `train_candidate_net._load_all`, which is how
the phase scripts reach shards. No real dataset is opened or migrated.
"""

import struct
from pathlib import Path

import numpy as np
import pytest

from trainer.contracts import PositionLabel, PositionMetadata, PositionRecord, ShardRef
from trainer.dataset.mmap_shard import (
    HEADER_SIZE,
    SHARD_DTYPE,
    SHARD_DTYPE_V1,
    ShardFormatError,
    _HEADER_STRUCT,
    _MAGIC,
    read_shard,
    write_shard,
)
from trainer.dataset.selfplay_provider import SelfPlayProvider
from trainer.dataset.stockfish_provider import StockfishLabeledProvider
from scripts.train_candidate_net import _load_all

FEN = "4k3/8/8/8/8/8/4Q3/4K3 w - - 0 1"


def _fen_of_length(n: int) -> str:
    """A syntactically FEN-shaped string of exactly n bytes (the shard stores bytes, not chess)."""
    assert n >= len(FEN)
    return FEN + "x" * (n - len(FEN))


def _record(fen: str = FEN, cp: int = 7, game_id=None) -> PositionRecord:
    return PositionRecord(
        fen=fen, label=PositionLabel(eval_cp=cp), metadata=PositionMetadata(game_id=game_id, ply=3)
    )


def _header(version: int, record_size: int, count: int) -> bytes:
    return _HEADER_STRUCT.pack(_MAGIC, version, HEADER_SIZE, record_size, count)


def _hand_built_v1(path: Path, records) -> Path:
    rows = np.zeros(len(records), dtype=SHARD_DTYPE_V1)
    for i, r in enumerate(records):
        rows[i]["fen"] = r.fen.encode("ascii")
        rows[i]["eval_cp"] = r.label.eval_cp
        rows[i]["has_eval_cp"] = True
        rows[i]["ply"] = r.metadata.ply
        rows[i]["has_ply"] = True
    with open(path, "wb") as f:
        f.write(_header(1, SHARD_DTYPE_V1.itemsize, len(records)))
        rows.tofile(f)
    return path


def _read(path: Path):
    return list(read_shard(ShardRef(locator=str(path))))


def test_the_two_layouts_differ_only_in_fen_width():
    assert SHARD_DTYPE_V1["fen"].itemsize == 90
    assert SHARD_DTYPE["fen"].itemsize == 100
    assert SHARD_DTYPE.itemsize == SHARD_DTYPE_V1.itemsize + 10


def test_write_shard_writes_version_2_and_a_100_byte_fen_round_trips(tmp_path):
    fen = _fen_of_length(100)
    path = tmp_path / "v2.bin"
    write_shard([_record(fen)], path)

    magic, version, header_size, record_size, count = _HEADER_STRUCT.unpack(path.read_bytes()[:HEADER_SIZE])
    assert (magic, version, header_size, record_size, count) == (
        _MAGIC, 2, HEADER_SIZE, SHARD_DTYPE.itemsize, 1,
    )
    assert _read(path)[0].fen == fen


@pytest.mark.parametrize("length", [91, 99, 100])
def test_fens_longer_than_the_old_90_byte_field_now_fit(tmp_path, length):
    fen = _fen_of_length(length)
    path = tmp_path / "v2.bin"
    write_shard([_record(fen)], path)
    assert _read(path)[0].fen == fen


@pytest.mark.parametrize(
    "bad_fen, message",
    [
        (_fen_of_length(101), "exceeds 100-byte"),
        (FEN.replace("w", "é"), "not ASCII"),
        (FEN + "\x00", "NUL byte"),
    ],
)
def test_a_fen_that_does_not_fit_is_rejected_not_truncated(tmp_path, bad_fen, message):
    path = tmp_path / "v2.bin"
    with pytest.raises(ValueError, match=message):
        write_shard([_record(), _record(bad_fen)], path)
    assert not path.exists(), "a failed write must not publish a shard"
    assert list(tmp_path.iterdir()) == [], "the temp file is cleaned up too"


def test_a_hand_built_version_1_file_reads_identically_to_the_same_records_in_version_2(tmp_path):
    records = [_record(FEN, cp=11), _record("8/8/8/8/8/8/8/K6k b - - 3 40", cp=-250)]
    v1 = _hand_built_v1(tmp_path / "v1.bin", records)
    v2 = tmp_path / "v2.bin"
    write_shard(records, v2)

    from_v1, from_v2 = _read(v1), _read(v2)
    assert [r.fen for r in from_v1] == [r.fen for r in records]
    assert [r.label.eval_cp for r in from_v1] == [11, -250]
    assert [(r.fen, r.label, r.metadata.ply) for r in from_v1] == [
        (r.fen, r.label, r.metadata.ply) for r in from_v2
    ]


@pytest.mark.parametrize("version", [0, 3, 255])
def test_unsupported_versions_are_rejected_by_name(tmp_path, version):
    path = tmp_path / "bad.bin"
    write_shard([_record()], path)
    raw = bytearray(path.read_bytes())
    raw[:HEADER_SIZE] = _header(version, SHARD_DTYPE.itemsize, 1)
    path.write_bytes(bytes(raw))
    with pytest.raises(ShardFormatError, match=f"unsupported format_version {version}"):
        _read(path)


def test_a_version_1_header_with_the_version_2_record_size_is_rejected(tmp_path):
    path = tmp_path / "mixed.bin"
    write_shard([_record()], path)
    raw = bytearray(path.read_bytes())
    raw[:HEADER_SIZE] = _header(1, SHARD_DTYPE.itemsize, 1)
    path.write_bytes(bytes(raw))
    with pytest.raises(ShardFormatError, match="record_size"):
        _read(path)


def test_a_version_2_header_with_the_version_1_record_size_is_rejected(tmp_path):
    path = _hand_built_v1(tmp_path / "mixed.bin", [_record()])
    raw = bytearray(path.read_bytes())
    raw[:HEADER_SIZE] = _header(2, SHARD_DTYPE_V1.itemsize, 1)
    path.write_bytes(bytes(raw))
    with pytest.raises(ShardFormatError, match="record_size"):
        _read(path)


@pytest.mark.parametrize("build", ["v1", "v2"])
def test_truncated_body_stray_byte_and_miscounted_header_are_rejected_for_both_versions(tmp_path, build):
    made = []

    def fresh() -> Path:
        p = tmp_path / f"{build}-{len(made)}.bin"
        made.append(p)
        if build == "v1":
            return _hand_built_v1(p, [_record(), _record()])
        write_shard([_record(), _record()], p)
        return p

    truncated = fresh()
    with open(truncated, "r+b") as f:
        f.truncate(truncated.stat().st_size - 1)
    with pytest.raises(ShardFormatError):
        _read(truncated)

    stray = fresh()
    with open(stray, "ab") as f:
        f.write(b"\x00")
    with pytest.raises(ShardFormatError):
        _read(stray)

    miscounted = fresh()
    raw = bytearray(miscounted.read_bytes())
    version, record_size = struct.unpack_from("<II", raw, 8)[0], struct.unpack_from("<II", raw, 12)[0]
    raw[:HEADER_SIZE] = _header(version, record_size, 3)
    miscounted.write_bytes(bytes(raw))
    with pytest.raises(ShardFormatError):
        _read(miscounted)


# ---- every consumer entry point opens both versions ------------------------------------------


@pytest.fixture
def mixed_directory(tmp_path):
    directory = tmp_path / "shards"
    directory.mkdir()
    _hand_built_v1(directory / "a-v1.bin", [_record(FEN, cp=1)])
    write_shard([_record(_fen_of_length(100), cp=2)], directory / "b-v2.bin")
    return directory


def test_stockfish_labeled_provider_reads_a_directory_holding_both_versions(mixed_directory):
    provider = StockfishLabeledProvider(mixed_directory, "mixed", "test")
    assert [r.label.eval_cp for r in _load_all(provider)] == [1, 2]


def test_selfplay_provider_reads_a_directory_holding_both_versions(mixed_directory):
    provider = SelfPlayProvider(mixed_directory, "mixed", "test")
    assert [r.label.eval_cp for r in _load_all(provider)] == [1, 2]


def test_a_malformed_shard_in_the_directory_fails_the_whole_load(mixed_directory):
    (mixed_directory / "c-bad.bin").write_bytes(b"\x00" * 10)
    provider = StockfishLabeledProvider(mixed_directory, "mixed", "test")
    with pytest.raises(ShardFormatError):
        _load_all(provider)
