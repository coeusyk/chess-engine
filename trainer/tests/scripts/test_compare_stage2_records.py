"""The Stage 2 record comparison (plan #329): legacy and v1 synthetic fixtures, and the three failure kinds kept apart."""

import hashlib
import json

import numpy as np
import pytest

from scripts.compare_stage2_records import compare_directories, main, read_records
from trainer.contracts import PositionLabel, PositionMetadata, PositionRecord, ShardRef
from trainer.dataset.mmap_shard import (
    _LEGACY119_DTYPE,
    _LEGACY124_DTYPE,
    migrate_legacy119_to_v1,
    migrate_legacy124_to_v1,
    write_shard,
)

FENS = ["4k3/8/8/8/8/8/4Q3/4K3 w - - 0 1", "4k3/8/8/8/8/8/4q3/4K3 b - - 0 1", "4k3/8/8/8/8/8/3PP3/4K3 w - - 0 1",
        "r3k3/8/8/8/8/8/8/4K2R w K - 0 1"]


def records(n=4, wdl=False):
    return [PositionRecord(fen=FENS[i], label=PositionLabel(eval_cp=10 * (i + 1) if i % 2 == 0 else None,
                                                            eval_mate=None if i % 2 == 0 else i, wdl=(0.25 * i if i else None) if wdl else None),
                           metadata=PositionMetadata(ply=i, search_depth=10, search_nodes=1000 + i)) for i in range(n)]


def write_legacy119(rs, path):
    np.array([(r.fen.encode("ascii"), r.label.eval_cp or 0, r.label.eval_cp is not None, r.label.eval_mate or 0,
               r.label.eval_mate is not None, r.metadata.ply or 0, r.metadata.ply is not None, r.metadata.search_depth or 0,
               r.metadata.search_depth is not None, r.metadata.search_nodes or 0, r.metadata.search_nodes is not None)
              for r in rs], dtype=_LEGACY119_DTYPE).tofile(path)


def write_legacy124(rs, path):
    np.array([(r.fen.encode("ascii"), r.label.eval_cp or 0, r.label.eval_cp is not None, r.label.eval_mate or 0,
               r.label.eval_mate is not None, r.label.wdl or 0.0, r.label.wdl is not None, r.metadata.ply or 0,
               r.metadata.ply is not None, r.metadata.search_depth or 0, r.metadata.search_depth is not None,
               r.metadata.search_nodes or 0, r.metadata.search_nodes is not None) for r in rs], dtype=_LEGACY124_DTYPE).tofile(path)


@pytest.fixture
def pair(tmp_path):
    """A legacy119 directory and a v1 directory that is its migration (the real situation of stage2-quiet-sf)."""
    legacy, v1 = tmp_path / "legacy", tmp_path / "v1"
    legacy.mkdir()
    v1.mkdir()
    write_legacy119(records(), legacy / "shard-0.bin")
    migrate_legacy119_to_v1(legacy / "shard-0.bin", v1 / "shard-0.bin")
    return legacy, v1


def rewrite_v1(v1, rs):
    (v1 / "shard-0.bin").unlink()
    write_shard(rs, v1 / "shard-0.bin")


def test_a_legacy119_directory_and_its_migration_are_identical(pair):
    legacy, v1 = pair
    report = compare_directories(legacy, "legacy119", v1, "v1")
    assert report["outcome"] == "identical" and report["records_a"] == report["records_b"] == 4


def test_a_legacy124_directory_and_its_migration_are_identical(tmp_path):
    legacy, v1 = tmp_path / "legacy", tmp_path / "v1"
    legacy.mkdir()
    v1.mkdir()
    write_legacy124(records(wdl=True), legacy / "shard-0.bin")
    migrate_legacy124_to_v1(legacy / "shard-0.bin", v1 / "shard-0.bin")
    assert compare_directories(legacy, "legacy124", v1, "v1")["outcome"] == "identical"


def test_a_changed_record_is_a_content_mismatch(pair):
    legacy, v1 = pair
    rs = records()
    rs[2] = PositionRecord(fen=rs[2].fen, label=PositionLabel(eval_cp=999), metadata=rs[2].metadata)
    rewrite_v1(v1, rs)
    report = compare_directories(legacy, "legacy119", v1, "v1")
    assert report["outcome"] == "content-mismatch" and report["records_only_in_a"] == 1 and report["records_only_in_b"] == 1


def test_a_dropped_record_is_a_content_mismatch_with_the_counts(pair):
    legacy, v1 = pair
    rewrite_v1(v1, records()[:3])
    report = compare_directories(legacy, "legacy119", v1, "v1")
    assert report["outcome"] == "content-mismatch" and (report["records_a"], report["records_b"]) == (4, 3)
    assert report["records_only_in_a"] == 1 and report["records_only_in_b"] == 0


def test_the_same_records_in_a_different_order_is_an_ordering_mismatch_not_a_content_mismatch(pair):
    legacy, v1 = pair
    rs = records()
    rs[0], rs[1] = rs[1], rs[0]
    rewrite_v1(v1, rs)
    report = compare_directories(legacy, "legacy119", v1, "v1")
    assert report["outcome"] == "ordering-mismatch" and report["first_difference_index"] == 0


def test_a_legacy_file_read_as_v1_is_a_reader_failure_on_that_side(pair):
    legacy, v1 = pair
    report = compare_directories(legacy, "v1", v1, "v1")
    assert report["outcome"] == "reader-failure" and report["side"] == "a" and "shard-0.bin" in report["error"]


def test_the_wrong_legacy_layout_and_an_empty_directory_are_reader_failures(pair, tmp_path):
    legacy, v1 = pair
    wrong = compare_directories(legacy, "legacy124", v1, "v1")  # 4 * 119 bytes is not a multiple of 124
    assert wrong["outcome"] == "reader-failure" and wrong["side"] == "a"
    empty = tmp_path / "empty"
    empty.mkdir()
    gone = compare_directories(legacy, "legacy119", empty, "v1")
    assert gone["outcome"] == "reader-failure" and gone["side"] == "b" and "no *.bin shards" in gone["error"]


def test_a_reader_failure_is_not_reported_as_a_mismatch(pair):
    legacy, v1 = pair
    (v1 / "shard-0.bin").write_bytes(b"not a shard")
    assert compare_directories(legacy, "legacy119", v1, "v1")["outcome"] == "reader-failure"


def test_the_sources_are_never_modified_and_the_report_holds_no_local_path(pair, tmp_path, capsys):
    legacy, v1 = pair
    digest = lambda d: {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(d.glob("*"))}
    before = (digest(legacy), digest(v1))

    code = main([str(legacy), "legacy119", str(v1), "v1"])

    out = capsys.readouterr().out
    assert code == 0 and (digest(legacy), digest(v1)) == before
    assert json.loads(out)["outcome"] == "identical" and str(tmp_path) not in out


def test_exit_status_is_nonzero_for_every_outcome_but_identical(pair, capsys):
    legacy, v1 = pair
    rewrite_v1(v1, records()[:2])
    assert main([str(legacy), "legacy119", str(v1), "v1"]) == 1
    assert main([str(legacy), "v1", str(v1), "v1"]) == 1
