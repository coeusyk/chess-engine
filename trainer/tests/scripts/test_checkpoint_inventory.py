"""Checkpoint inventory by content (#265, plan #329). Synthetic checkpoints only."""

import json

import torch

from scripts.checkpoint_inventory import build_inventory, main
from tests.export.test_identity import checkpoint


def _save(tmp_path, name, **kw):
    path = tmp_path / name
    torch.save(checkpoint(**kw), path)
    return path


def test_equal_tensors_with_different_metadata_are_duplicates_while_file_hashes_differ(tmp_path):
    first = _save(tmp_path, "p1-g04.pt", seed=3, metadata={"seed": 3, "trainer_commit": "a", "started_at_epoch_seconds": 1, "config": {}})
    second = _save(tmp_path, "m1-s42.pt", seed=3, metadata={"seed": 42, "trainer_commit": "b", "started_at_epoch_seconds": 9, "config": {}})
    other = _save(tmp_path, "other.pt", seed=4)

    records = build_inventory([("P1-G04", first), ("M1-s42-final", second), ("OTHER", other)])

    assert records[0]["checkpoint_sha256"] != records[1]["checkpoint_sha256"]
    assert records[0]["tensor_sha256"] == records[1]["tensor_sha256"]
    assert records[0]["network_content_sha256"] == records[1]["network_content_sha256"]
    assert [r["duplicate_of"] for r in records] == [None, "P1-G04", None]
    assert records[1]["training_seed"] == 42 and records[1]["trainer_commit"] == "b"


def test_records_hold_base_names_only_and_no_path(tmp_path):
    path = _save(tmp_path, "net.pt", seed=1)
    records = build_inventory([("X", path)])
    assert records[0]["checkpoint_file"] == "net.pt"
    assert str(tmp_path) not in json.dumps(records)


def test_cli_writes_an_inventory_and_the_shortlist_and_refuses_to_overwrite(tmp_path, capsys):
    _save(tmp_path, "a.pt", seed=1)
    labels = tmp_path / "labels.json"
    labels.write_text(json.dumps({"E-3": "a.pt"}))
    out = tmp_path / "inv.json"

    assert main(["--input", str(labels), "--root", str(tmp_path), "--output", str(out), "--shortlist"]) == 0

    data = json.loads(out.read_text())
    assert data["records"][0]["label"] == "E-3"
    assert data["shortlist"]["selected"][0]["group"] == "anchor-e3"
    assert any(e["reason"] == "missing" for e in data["shortlist"]["excluded"])
    try:
        main(["--input", str(labels), "--root", str(tmp_path), "--output", str(out)])
    except SystemExit as exit_:
        assert "already exists" in str(exit_)
    else:
        raise AssertionError("must refuse to overwrite an existing output")


def test_a_checkpoint_needing_full_unpickling_is_refused_unless_trusted(tmp_path):
    from trainer.model.train import TrainingConfig  # a non-tensor pickled type
    payload = checkpoint(seed=2)
    payload["pickled_config_object"] = TrainingConfig(hidden_width=8, qa=127, qb=64, output_scale=400, k=2.773456,
                                                      learning_rate=0.01, seed=1, steps=1, batch_size=2)
    path = tmp_path / "needs-trust.pt"
    torch.save(payload, path)
    try:
        build_inventory([("T", path)])
    except SystemExit as exit_:
        assert "needs full unpickling" in str(exit_)
    else:
        raise AssertionError("weights_only loading must refuse a pickled object")
    assert build_inventory([("T", path)], trust_checkpoint=True)[0]["loaded_with_trust"] is True
