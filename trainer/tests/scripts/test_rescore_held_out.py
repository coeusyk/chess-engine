"""Rescoring checkpoints against original and corrected labels (plan #329). Synthetic datasets and a small random
network only: the real datasets and checkpoints are never touched by tests."""

import json

import pytest
import torch

from scripts import train_candidate_net as tcn
from scripts.rescore_held_out import build_held_out, groups, load_model, main, rescore_network
from tests.export.test_identity import checkpoint
from tests.scripts.test_verify_stage1_repair import _large_rows, _repaired
from trainer.contracts import PositionLabel, PositionMetadata, PositionRecord, ShardRef


class _Stage2:
    """A Stage 2 stand-in with both sides to move and varied mover-relative labels."""
    FENS = [f"4k3/8/8/8/8/8/p{i}{7 - i}/4K3 {side} - - 0 1" for i in range(1, 7) for side in "wb"] * 3

    def __init__(self, directory, identifier, source_ref):
        pass

    def metadata(self):
        return type("M", (), {"identifier": "stage2-fake", "stage": "sf-labeled"})()

    def shards(self):
        yield ShardRef(locator="fake")

    def positions(self, shard):
        for n, fen in enumerate(self.FENS):
            yield PositionRecord(fen=fen, label=PositionLabel(eval_cp=37 * (n % 9) - 120), metadata=PositionMetadata())


@pytest.fixture
def datasets(tmp_path, monkeypatch):
    monkeypatch.setattr(tcn, "StockfishLabeledProvider", _Stage2)
    original, repaired = _repaired(tmp_path, rows=_large_rows(count_per_piece=6) * 3)
    return original, repaired, tmp_path / "stage2"


def net(tmp_path, seed=1, name="net.pt"):
    payload = checkpoint(seed=seed)
    g = torch.Generator().manual_seed(seed)
    payload["model_state_dict"]["output_layer.weight"] = torch.randn(payload["model_state_dict"]["output_layer.weight"].shape, generator=g) * 20
    payload["model_state_dict"]["ft.weight"] = torch.randn(payload["model_state_dict"]["ft.weight"].shape, generator=g) * 5
    path = tmp_path / name
    torch.save(payload, path)
    return path


def test_the_original_and_corrected_held_out_records_differ_only_in_their_labels(datasets):
    original, repaired, stage2 = datasets

    held = build_held_out(original, repaired, stage2, seed=42)

    assert len(held.original) == len(held.corrected) > 20
    for a, b, source in zip(held.original, held.corrected, held.sources):
        assert a.fen == b.fen and a.metadata == b.metadata
        if source == "sf-labeled":
            assert a.label == b.label, "Stage 2 labels are the same in both sets"
        elif a.fen.split()[1] == "w":
            assert a.label == b.label, "White-to-move Stage 1 labels are unchanged by the repair"
        else:
            assert (b.label.eval_cp, b.label.eval_mate) == (None if a.label.eval_cp is None else -a.label.eval_cp,
                                                           None if a.label.eval_mate is None else -a.label.eval_mate)
    assert any(a.label != b.label for a, b in zip(held.original, held.corrected))


def test_the_held_out_set_is_the_one_combine_and_split_builds(datasets):
    original, repaired, stage2 = datasets
    for drop in (False, True):
        held = build_held_out(original, repaired, stage2, seed=42, drop_duplicates=drop)
        _, held_out, _ = tcn.combine_and_split(repaired, stage2, seed=42, drop_duplicates=drop)
        assert held.fens == [r.fen for r in held_out]
        assert [r.label for r in held.corrected] == [r.label for r in held_out]


def test_predictions_are_identical_and_only_the_reference_labels_differ(datasets, tmp_path):
    original, repaired, stage2 = datasets
    held = build_held_out(original, repaired, stage2, seed=42)
    model, k = load_model(torch.load(net(tmp_path), weights_only=True))

    result = rescore_network(model, k, held)

    assert len(result["predictions_sha256"]) == 64
    g = result["groups"]
    # Stage 2 and White-to-move Stage 1 have identical labels, so identical metrics from identical predictions.
    for name in ("sf-labeled", "sf-labeled/white_to_move", "sf-labeled/black_to_move", "public/white_to_move"):
        assert g[name]["labels_differing"] == 0, name
    assert g["sf-labeled"]["original_labels"] == g["sf-labeled"]["corrected_labels"]
    assert g["public/white_to_move"]["original_labels"] == g["public/white_to_move"]["corrected_labels"]
    # The differing reference labels sit entirely in the Stage 1 Black-to-move group and change its correlation.
    black = g["public/black_to_move"]
    assert black["labels_differing"] == black["original_labels"]["records"] > 0
    assert black["original_labels"]["correlation"] != black["corrected_labels"]["correlation"]
    assert g["overall"]["labels_differing"] == black["labels_differing"]


def test_corrected_labels_negate_the_black_group_correlation_exactly(datasets, tmp_path):
    """The original labels of the Black-to-move Stage 1 rows are the exact negation of the corrected ones, so the
    Pearson correlation of the same predictions against them is exactly negated. This ties the two label sets to
    the one prediction vector."""
    original, repaired, stage2 = datasets
    held = build_held_out(original, repaired, stage2, seed=42)
    model, k = load_model(torch.load(net(tmp_path, seed=3), weights_only=True))

    black = rescore_network(model, k, held)["groups"]["public/black_to_move"]

    assert black["original_labels"]["correlation"] == pytest.approx(-black["corrected_labels"]["correlation"], abs=1e-6)


def test_group_counts_partition_the_held_out_set(datasets):
    original, repaired, stage2 = datasets
    held = build_held_out(original, repaired, stage2, seed=42)
    g = groups(held)
    n = len(held.fens)
    assert len(g["overall"]) == n
    assert len(g["white_to_move"]) + len(g["black_to_move"]) == n
    assert len(g["public"]) + len(g["sf-labeled"]) == n
    assert sum(len(g[f"{s}/{x}"]) for s in ("public", "sf-labeled") for x in ("white_to_move", "black_to_move")) == n


def test_a_different_ordered_fen_sequence_is_refused(datasets):
    original, repaired, stage2 = datasets
    lines = (repaired / "stage1.csv").read_bytes().decode("ascii").split("\r\n")
    (repaired / "stage1.csv").write_bytes("\r\n".join([lines[1], lines[0]] + lines[2:]).encode("ascii"))
    with pytest.raises(ValueError, match="same ordered FEN sequence"):
        build_held_out(original, repaired, stage2, seed=42)


def test_the_identity_hashes_are_stable_and_change_with_the_seed_or_the_labels(datasets):
    original, repaired, stage2 = datasets
    a = build_held_out(original, repaired, stage2, seed=42).identity()
    assert a == build_held_out(original, repaired, stage2, seed=42).identity()
    b = build_held_out(original, repaired, stage2, seed=7).identity()
    assert a["record_identity_sha256"] != b["record_identity_sha256"]
    assert a["original_labels_sha256"] != a["corrected_labels_sha256"]
    assert a["fen_order_sha256"] and a["held_out_records"] == len(build_held_out(original, repaired, stage2, seed=42).fens)


def test_cli_writes_a_report_with_hashes_and_no_local_path_and_refuses_to_overwrite(datasets, tmp_path, capsys):
    original, repaired, stage2 = datasets
    net(tmp_path, seed=1, name="a.pt")
    net(tmp_path, seed=2, name="b.pt")
    (tmp_path / "labels.json").write_text(json.dumps({"A": "a.pt", "B-copy": "b.pt", "B": "b.pt"}))
    (tmp_path / "inventory.json").write_text(json.dumps({"shortlist": {"networks": [
        {"network_content_sha256": "n1", "labels": ["A"]}, {"network_content_sha256": "n2", "labels": ["B", "B-copy"]}]}}))
    out = tmp_path / "rescore.json"
    argv = ["--inventory", str(tmp_path / "inventory.json"), "--labels", str(tmp_path / "labels.json"), "--root", str(tmp_path),
            "--original-stage1", str(original), "--corrected-stage1", str(repaired), "--stage2-dir", str(stage2),
            "--seed", "42", "--output", str(out)]

    assert main(argv) == 0

    report = json.loads(out.read_text())
    assert [n["label"] for n in report["networks"]] == ["A", "B"]
    assert report["networks"][0]["checkpoint_sha256"] != report["networks"][1]["checkpoint_sha256"]
    assert report["held_out"]["record_identity_sha256"] and report["datasets"]["corrected_stage1"]["manifest_sha256"]
    assert str(tmp_path) not in out.read_text()
    with pytest.raises(SystemExit, match="already exists"):
        main(argv)


def test_differing_predictions_for_the_two_label_sets_are_refused(datasets, tmp_path, monkeypatch):
    import numpy as np
    import scripts.rescore_held_out as mod
    original, repaired, stage2 = datasets
    held = build_held_out(original, repaired, stage2, seed=42)
    model, k = load_model(torch.load(net(tmp_path), weights_only=True))
    calls = iter([np.zeros(len(held.fens), dtype=np.float32), np.ones(len(held.fens), dtype=np.float32)])
    monkeypatch.setattr(mod, "predict", lambda m, records: next(calls))

    with pytest.raises(ValueError, match="predictions differ"):
        rescore_network(model, k, held)
