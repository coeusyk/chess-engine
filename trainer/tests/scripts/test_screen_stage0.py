"""Stage 0 screening of one exported network (#265, plan #329). A fake engine scorer stands in for the real one."""

import json

import numpy as np
import torch

from scripts.export_screening_net import export_screening_net
from scripts.screen_stage0 import main, stage0
from tests.export.test_identity import checkpoint
from trainer.export.nnue_reader import read_nnue
from trainer.quantization.inference import eval_quantized_fens
from trainer.validation.orientation import load_controlled_positions

FENS = ["rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq -", "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq -",
        "4k3/8/8/8/8/8/4Q3/4K3 w - -", "4k3/8/8/8/8/8/4q3/4K3 b - -"]


def exported(tmp_path, seed=1):
    ckpt = tmp_path / f"c{seed}.pt"
    torch.save(checkpoint(seed=seed), ckpt)
    result = export_screening_net(ckpt, tmp_path / "out", fens=FENS, fen_sources=["unit"])
    return tmp_path / "out" / result["nnue_file"]


def python_scorer(net):
    network, _ = read_nnue(net)
    return lambda fens: [int(s) for s in eval_quantized_fens(network, list(fens))]


def test_parity_passes_when_the_engine_equals_the_reference_exactly(tmp_path):
    net = exported(tmp_path)

    report = stage0(net, engine_scorer=python_scorer(net), fens=FENS)

    assert report["hard_gates"]["java_python_parity"] == "pass"
    assert report["java_python_parity"]["mismatches"] == 0 and report["java_python_parity"]["positions"] == len(FENS) + 24


def test_one_changed_engine_score_is_a_failed_gate_with_the_position_reported(tmp_path):
    net = exported(tmp_path)
    reference = python_scorer(net)

    def off_by_one(fens):
        scores = reference(fens)
        scores[2] += 1
        return scores

    report = stage0(net, engine_scorer=off_by_one, fens=FENS)

    assert report["hard_gates"]["java_python_parity"] == "failed"
    assert report["java_python_parity"]["mismatches"] == 1 and report["java_python_parity"]["max_abs_difference"] == 1
    assert report["java_python_parity"]["first_mismatches"][0]["fen"] == FENS[2]


def test_without_an_engine_parity_is_not_run_and_never_counted_as_a_pass(tmp_path):
    report = stage0(exported(tmp_path), fens=FENS)

    assert report["hard_gates"]["java_python_parity"] == "not run"
    assert report["java_python_parity"] == {"status": "not run"}


def test_the_orientation_gate_is_reported_with_the_failing_positions(tmp_path):
    report = stage0(exported(tmp_path), fens=FENS)  # an untrained random network does not know material

    assert report["hard_gates"]["controlled_orientation"] in ("pass", "failed")
    assert report["controlled_orientation"]["checked"] == 24
    if report["hard_gates"]["controlled_orientation"] == "failed":
        assert report["controlled_orientation"]["failures"]


def test_diagnostics_are_present_and_carry_no_verdict(tmp_path):
    report = stage0(exported(tmp_path), fens=FENS)

    assert set(report["diagnostics"]) >= {"white_to_move", "black_to_move", "side_gap", "mirror_difference_cp"}
    assert not any(k in report["diagnostics"] for k in ("passed", "verdict"))
    assert str(tmp_path) not in json.dumps(report) and report["nnue_file"].endswith(".nnue")


def test_cli_refuses_to_overwrite_and_exits_one_on_a_failed_gate(tmp_path, monkeypatch, capsys):
    import scripts.screen_stage0 as mod
    net = exported(tmp_path)
    monkeypatch.setattr(mod, "default_corpus", lambda: (FENS, ["unit"]))
    out = tmp_path / "stage0.json"
    code = main([str(net), "--output", str(out)])
    data = json.loads(out.read_text())
    assert code == (1 if "failed" in data["hard_gates"].values() else 0)
    assert "no Java parity is claimed" in capsys.readouterr().err or code == 1
    try:
        main([str(net), "--output", str(out)])
    except SystemExit as exit_:
        assert "already exists" in str(exit_)
    else:
        raise AssertionError("must refuse to overwrite")
