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


def test_an_engine_failure_that_echoes_the_absolute_path_is_redacted_in_the_sidecar(tmp_path):
    """The strict engine names the EvalFile it could not load. The record must not carry the local layout."""
    def bad(path, uuid, sha):
        resolved = path.resolve()
        raise RuntimeError(f"engine: cannot load EvalFile '{resolved}'")

    import scripts.export_screening_net as mod
    real_gate = mod.engine_loading_gate

    class FakePre:
        class PreflightError(Exception):
            def __init__(self, code, text):
                super().__init__(text)
                self.code = code

        class EngineConfig:
            def __init__(self, *a):
                pass

        class Pins:
            def __init__(self, *a):
                pass

        @staticmethod
        def preflight(config, pins):
            raise FakePre.PreflightError(4, "NNUE strict mode: cannot load EvalFile '" + str(FakePre.nnue) + "': corrupt")

    out = tmp_path / "out"
    ckpt = _ckpt(tmp_path, seed=9)
    mod._load_preflight, saved = (lambda: FakePre), mod._load_preflight
    try:
        gate = real_gate(tmp_path / "engine.jar", "java")

        def wrapped(path, uuid, sha):
            FakePre.nnue = path.resolve()
            return gate(path, uuid, sha)

        result = run(tmp_path, ckpt, out, engine_gate=wrapped)
    finally:
        mod._load_preflight = saved

    assert result["exit_status"] == 3 and result["hard_gates"]["loader_safety_engine"].startswith("failed")
    assert str(tmp_path) not in (out / f"{result['uuid']}{SIDECAR_SUFFIX}").read_text()


def test_a_network_the_loader_rejects_gives_exit_3_through_the_real_path_without_a_traceback(tmp_path):
    payload = checkpoint(seed=3)
    payload["model_state_dict"]["ft.weight"] = torch.full_like(payload["model_state_dict"]["ft.weight"], 1500.0)
    path = tmp_path / "huge.pt"
    torch.save(payload, path)

    result = run(tmp_path, path, tmp_path / "out")

    assert result["exit_status"] == 3 and result["exported"] is False
    assert "accumulator" in result["error"]
    assert not (tmp_path / "out").exists()


def test_a_crash_in_the_read_back_still_leaves_a_sidecar_that_says_failed(tmp_path, monkeypatch):
    import scripts.export_screening_net as mod
    monkeypatch.setattr(mod, "read_nnue", lambda path: (_ for _ in ()).throw(OSError("disk")))
    out = tmp_path / "out"

    result = run(tmp_path, _ckpt(tmp_path, seed=10), out)

    assert result["exit_status"] == 3 and result["hard_gates"]["identity"] == "failed"
    assert len(list(out.glob("*.screening.json"))) == 1


def test_an_engine_gate_that_cannot_start_the_engine_is_a_failed_gate_not_a_crash(tmp_path):
    def missing(path, uuid, sha):
        raise FileNotFoundError("java")

    result = run(tmp_path, _ckpt(tmp_path, seed=11), tmp_path / "out", engine_gate=missing)

    assert result["exit_status"] == 3 and len(list((tmp_path / "out").glob("*.screening.json"))) == 1


def test_a_failed_export_does_not_block_exporting_the_same_network_again(tmp_path):
    def bad(path, uuid, sha):
        raise RuntimeError("status 4: wrong jar")

    out = tmp_path / "out"
    assert run(tmp_path, _ckpt(tmp_path, seed=12), out, engine_gate=bad)["exit_status"] == 3

    again = run(tmp_path, _ckpt(tmp_path, "again.pt", seed=12), out)

    assert again["exit_status"] == 0 and again["duplicate_of"] is None
