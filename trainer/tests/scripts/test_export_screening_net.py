"""The screening export wrapper (#265, plan #329): a thin composition of the public exporter with identity,
duplicate refusal and hard gates. Synthetic checkpoints and a stub engine gate only."""

import json
from pathlib import Path

import pytest
import torch

from scripts.export_screening_net import SIDECAR_SUFFIX, export_screening_net, main
from tests.export.test_identity import checkpoint

# A small fixed corpus keeps the tests fast; the real default is the committed 393-position corpus.
FENS = ["rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq -",
        "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq -",
        "4k3/8/8/8/8/8/4Q3/4K3 w - -", "4k3/8/8/8/8/8/4q3/4K3 b - -"]


def _ckpt(tmp_path, name="net.pt", **kw):
    path = tmp_path / name
    torch.save(checkpoint(**kw), path)
    return path


def run(tmp_path, ckpt, out, **kw):
    return export_screening_net(ckpt, out, fens=FENS, fen_sources=["unit"], **kw)


def test_first_export_writes_nnue_manifest_and_sidecar_with_every_gate(tmp_path):
    out = tmp_path / "out"

    result = run(tmp_path, _ckpt(tmp_path, seed=1), out)

    assert result["exit_status"] == 0 and result["result"] == "exported"
    assert sorted(p.suffix for p in out.iterdir()) == [".json", ".json", ".nnue"]
    sidecar = json.loads((out / f"{result['uuid']}{SIDECAR_SUFFIX}").read_text())
    assert sidecar["hard_gates"]["identity"] == "pass"
    assert sidecar["hard_gates"]["loader_safety_python"] == "pass"
    assert sidecar["hard_gates"]["loader_safety_engine"] == "not run"
    assert sidecar["hard_gates"]["java_python_parity"] == "pending-stage0"
    assert sidecar["source_checkpoint"] == "net.pt" and len(sidecar["network_content_sha256"]) == 64
    assert str(tmp_path) not in json.dumps(sidecar)


def test_a_second_export_of_the_same_network_is_refused_and_writes_nothing(tmp_path):
    out = tmp_path / "out"
    first = run(tmp_path, _ckpt(tmp_path, "a.pt", seed=2, metadata={"seed": 1, "trainer_commit": "a", "started_at_epoch_seconds": 1, "config": {}}), out)
    before = sorted(p.name for p in out.iterdir())

    second = run(tmp_path, _ckpt(tmp_path, "b.pt", seed=2, metadata={"seed": 7, "trainer_commit": "b", "started_at_epoch_seconds": 2, "config": {}}), out)

    assert second["exit_status"] == 2 and second["duplicate_of"] == f"{first['uuid']}{SIDECAR_SUFFIX}"
    assert sorted(p.name for p in out.iterdir()) == before


def test_allow_duplicate_exports_again_and_records_the_reason(tmp_path):
    out = tmp_path / "out"
    run(tmp_path, _ckpt(tmp_path, seed=2), out)

    again = run(tmp_path, _ckpt(tmp_path, "again.pt", seed=2), out, allow_duplicate_reason="re-export for a fixed corpus")

    assert again["exit_status"] == 0 and again["duplicate_override_reason"] == "re-export for a fixed corpus"
    assert len(list(out.glob("*.nnue"))) == 2


def test_a_network_the_java_loader_would_reject_is_not_exported(tmp_path, monkeypatch):
    import scripts.export_screening_net as mod
    monkeypatch.setattr(mod, "assert_java_loadable", lambda network: (_ for _ in ()).throw(ValueError("accumulator overflow risk")))
    out = tmp_path / "out"

    result = run(tmp_path, _ckpt(tmp_path, seed=3), out)

    assert result["exit_status"] == 3 and result["exported"] is False
    assert not out.exists() or not any(out.iterdir())


def test_a_corrupted_written_file_fails_the_identity_gate(tmp_path, monkeypatch):
    import scripts.export_screening_net as mod
    real = mod.read_nnue

    def corrupted(path):
        network, uuid = real(path)
        network.ft_weights.setflags(write=True)
        broken = network.ft_weights.copy()
        broken[0, 0] += 1
        import dataclasses
        return dataclasses.replace(network, ft_weights=broken), uuid

    monkeypatch.setattr(mod, "read_nnue", corrupted)

    result = run(tmp_path, _ckpt(tmp_path, seed=4), tmp_path / "out")

    assert result["hard_gates"]["identity"] == "failed" and result["exit_status"] == 3 and result["result"] == "failed"


def test_the_engine_gate_runs_with_the_pinned_identity_and_a_failure_gives_exit_3(tmp_path):
    seen = {}

    def good(path, uuid, sha):
        seen.update(path=path.name, uuid=uuid, sha=sha)
        return {"result": "pass", "uuid_reported": uuid}

    ok = run(tmp_path, _ckpt(tmp_path, seed=5), tmp_path / "ok", engine_gate=good)

    assert ok["hard_gates"]["loader_safety_engine"] == "pass" and ok["exit_status"] == 0
    assert seen["uuid"] == ok["uuid"] and seen["sha"] == ok["nnue_sha256"] and seen["path"].endswith(".nnue")

    def bad(path, uuid, sha):
        raise RuntimeError("status 4: NNUE strict mode")

    failed = run(tmp_path, _ckpt(tmp_path, seed=6), tmp_path / "bad", engine_gate=bad)
    assert failed["exit_status"] == 3 and failed["hard_gates"]["loader_safety_engine"].startswith("failed")
    assert len(list((tmp_path / "bad").glob("*.nnue"))) == 1, "files are kept for inspection"


def test_a_checkpoint_without_experiment_metadata_is_not_exported(tmp_path):
    path = tmp_path / "bare.pt"
    payload = checkpoint(seed=1)
    del payload["experiment_metadata"]
    torch.save(payload, path)

    assert run(tmp_path, path, tmp_path / "out")["exit_status"] == 3


def test_the_exporter_and_quantizer_files_are_untouched_by_this_slice():
    """The wrapper composes the public exporter. This guards against a later edit that forks it."""
    import inspect
    import scripts.export_screening_net as mod
    source = inspect.getsource(mod)
    assert "def quantize" not in source and "def _nnue_bytes" not in source


def test_cli_reports_a_refusal_and_the_no_java_claim(tmp_path, capsys, monkeypatch):
    import scripts.export_screening_net as mod
    monkeypatch.setattr(mod, "default_corpus", lambda: (FENS, ["unit"]))
    ckpt = _ckpt(tmp_path, seed=8)
    out = tmp_path / "out"

    assert main([str(ckpt), "--out-dir", str(out)]) == 0
    captured = capsys.readouterr()
    assert "no Java loadability is claimed" in captured.err
    assert main([str(ckpt), "--out-dir", str(out)]) == 2
    assert "already exported" in capsys.readouterr().err
