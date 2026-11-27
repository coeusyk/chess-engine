"""Verification of a repaired Stage 1 directory against its original (issue #263). Synthetic fixtures only: the
real datasets are gitignored research artifacts and are never touched by tests.
"""

import json
import shutil
from pathlib import Path

import pytest

from scripts import train_candidate_net as tcn
from scripts.repair_stage1_perspective import repair
from scripts.verify_stage1_repair import main, split_membership, split_membership_invariance, verify
from tests.scripts.test_repair_stage1_perspective import HISTORICAL_ROWS, _make_historical, _tree_digest
from trainer.contracts import PositionLabel, PositionMetadata, PositionRecord, ShardRef


def _repaired(tmp_path: Path, rows=HISTORICAL_ROWS):
    original = _make_historical(tmp_path, rows=rows)
    repaired = tmp_path / "stage1-lichess-v2"
    repair(original, repaired, "stage1-lichess-v2", confirm_white_perspective=True)
    return original, repaired


def _lines(directory: Path):
    return (directory / "stage1.csv").read_bytes().decode("ascii").split("\r\n")[:-1]


def _write_lines(directory: Path, lines):
    (directory / "stage1.csv").write_bytes(("\r\n".join(lines) + "\r\n").encode("ascii"))


def test_a_faithful_repair_passes_every_check(tmp_path):
    original, repaired = _repaired(tmp_path)

    report = verify(original, repaired)

    assert report["all_checks_pass"], report
    assert (report["rows_original"], report["rows_repaired"], report["black_to_move_rows"]) == (6, 6, 3)
    assert report["fen_column_sha256_original"] == report["fen_column_sha256_repaired"]
    assert report["original_refused_by_require_mover_relative"] is True


@pytest.mark.parametrize("name, mutate, failed_key", [
    ("white row altered", lambda ls: [ls[0].replace(",900,", ",901,")] + ls[1:], "white_rows_byte_identical"),
    ("black row not flipped", lambda ls: ls[:1] + [ls[1].replace(",900,", ",-900,")] + ls[2:], "black_rows_exactly_negated"),
    ("black row flipped wrongly", lambda ls: ls[:1] + [ls[1].replace(",900,", ",901,")] + ls[2:], "black_rows_exactly_negated"),
    ("row dropped", lambda ls: ls[:2] + ls[3:], "rows_equal"),
    ("row dropped, identity", lambda ls: ls[:2] + ls[3:], "source_record_identity"),
    ("rows swapped", lambda ls: [ls[1], ls[0]] + ls[2:], "row_order_identical"),
    ("extra column changed", lambda ls: ls[:4] + [ls[4].replace(",17", ",18")] + ls[5:], "extra_columns_identical"),
    ("fen changed", lambda ls: [ls[0].replace("w - -", "w - - 0 1")] + ls[1:], "source_record_identity"),
])
def test_each_broken_copy_fails_the_named_check(tmp_path, name, mutate, failed_key):
    original, repaired = _repaired(tmp_path)
    _write_lines(repaired, mutate(_lines(repaired)))

    report = verify(original, repaired)

    assert report[failed_key] is False, name
    assert report["all_checks_pass"] is False


def test_a_swapped_pair_keeps_source_record_identity_but_not_order(tmp_path):
    original, repaired = _repaired(tmp_path)
    lines = _lines(repaired)
    _write_lines(repaired, [lines[1], lines[0]] + lines[2:])

    report = verify(original, repaired)

    assert report["source_record_identity"] is True
    assert report["row_order_identical"] is False


def test_a_missing_marker_fails_the_marker_and_the_production_guard(tmp_path):
    original, repaired = _repaired(tmp_path)
    manifest = json.loads((repaired / "manifest.json").read_text())
    del manifest["label_perspective"]
    (repaired / "manifest.json").write_text(json.dumps(manifest))

    report = verify(original, repaired)

    assert report["marker_is_mover_relative"] is False
    assert report["accepted_by_require_mover_relative"] is False


def test_a_missing_repaired_from_block_fails_provenance(tmp_path):
    original, repaired = _repaired(tmp_path)
    manifest = json.loads((repaired / "manifest.json").read_text())
    del manifest["repaired_from"]
    (repaired / "manifest.json").write_text(json.dumps(manifest))

    report = verify(original, repaired)

    assert report["original_sha256_unchanged"] is False
    assert report["repaired_from_matches_original"] is False


def test_an_original_changed_after_the_repair_is_detected(tmp_path):
    original, repaired = _repaired(tmp_path)
    lines = _lines(original)
    _write_lines(original, [lines[0].replace(",900,", ",901,")] + lines[1:])

    assert verify(original, repaired)["original_sha256_unchanged"] is False


def test_verifying_a_directory_against_itself_fails(tmp_path):
    original, _ = _repaired(tmp_path)

    report = verify(original, original)

    assert report["all_checks_pass"] is False
    assert report["identifier_differs"] is False
    assert report["marker_is_mover_relative"] is False


def test_verify_never_writes_to_either_directory(tmp_path):
    original, repaired = _repaired(tmp_path)
    before = _tree_digest(tmp_path)

    verify(original, repaired)

    assert _tree_digest(tmp_path) == before


# --- split membership ------------------------------------------------------------------------------------

def _large_rows(count_per_piece=6):
    rows = []
    for piece in "PNBRQ":
        for i in range(1, count_per_piece + 1):
            for side in "wb":
                rows.append(f"4k3/8/8/8/8/8/{piece}{i}{7 - i}/4K3 {side} - -,{100 * i},")
    return rows


class _FakeStage2:
    """Stands in for the real Stage 2 shard reader: a fixed list of mover-relative records."""
    FENS = [f"4k3/8/8/8/8/8/p{i}{7 - i}/4K3 w - - 0 1" for i in range(1, 7)] * 4

    def __init__(self, directory, identifier, source_ref):
        pass

    def metadata(self):
        return type("M", (), {"identifier": "stage2-fake", "stage": "sf-labeled"})()

    def shards(self):
        yield ShardRef(locator="fake")

    def positions(self, shard):
        for fen in self.FENS:
            yield PositionRecord(fen=fen, label=PositionLabel(eval_cp=10), metadata=PositionMetadata())


@pytest.fixture
def fake_stage2(monkeypatch):
    monkeypatch.setattr(tcn, "StockfishLabeledProvider", _FakeStage2)


def test_repair_invariance_holds_for_a_faithful_repair(tmp_path, fake_stage2):
    original, repaired = _repaired(tmp_path, rows=_large_rows())

    result = split_membership_invariance(original, repaired, tmp_path / "stage2", seed=42)

    assert result["split_membership_invariant"] is True
    assert result["held_out_count"] > 0 and result["training_count"] > 0
    assert result["held_out_order_sha256"] == result["held_out_order_sha256_original"]


def test_repair_invariance_fails_when_a_position_is_missing(tmp_path, fake_stage2):
    original, repaired = _repaired(tmp_path, rows=_large_rows())
    _write_lines(repaired, _lines(repaired)[1:])

    assert split_membership_invariance(original, repaired, tmp_path / "stage2", seed=42)["split_membership_invariant"] is False


def test_split_recipe_matches_the_production_combine_and_split(tmp_path, fake_stage2):
    """Any drift between this module's three-line recipe and the production function fails here."""
    _, repaired = _repaired(tmp_path, rows=_large_rows())

    ours = split_membership(repaired, tmp_path / "stage2", seed=42)
    training, held_out, _ = tcn.combine_and_split(repaired, tmp_path / "stage2", seed=42)

    assert ours["held_out_fens"] == [r.fen for r in held_out]
    assert ours["training_fens"] == [r.fen for r in training]


def test_the_legacy_original_can_be_split_but_production_still_refuses_it(tmp_path, fake_stage2):
    original, _ = _repaired(tmp_path, rows=_large_rows())

    assert split_membership(original, tmp_path / "stage2", seed=1)["held_out_count"] > 0
    with pytest.raises(Exception, match="legacy White-perspective"):
        tcn.combine_and_split(original, tmp_path / "stage2", seed=1)


def test_main_reports_a_historical_hash_mismatch_without_failing_the_repair(tmp_path, fake_stage2, capsys):
    original, repaired = _repaired(tmp_path, rows=_large_rows())

    code = main([str(original), str(repaired), "--stage2-dir", str(tmp_path / "stage2"), "--seed", "42",
                 "--expect-held-out-sha256", "0" * 64])

    report = json.loads(capsys.readouterr().out)
    assert code == 0
    assert report["split"]["historical_held_out_reproduced"] is False
    assert report["repair"]["all_checks_pass"] is True


def test_main_exits_nonzero_on_a_failed_check(tmp_path, capsys):
    original, repaired = _repaired(tmp_path)
    shutil.rmtree(repaired)
    other = tmp_path / "bogus"
    shutil.copytree(original, other)

    assert main([str(original), str(other)]) == 1
