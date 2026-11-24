"""Issue #265: the checkpoint audit entry point.

Every checkpoint here is a synthetic, freshly initialised `NnueNet` written to a temp directory.
That exercises the loader, the arithmetic and the output record. It is not a measurement of any
trained network, and no real checkpoint exists in this repository.
"""

import dataclasses
import json

import pytest
import torch

from scripts import audit_checkpoint_quantization as audit
from tests.quantization._parity_data import corpus_fens
from trainer.model.network import NnueNet
from trainer.model.train import TrainingConfig

HIDDEN = 8
QA, QB, SCALE = 127, 64, 400


def _write_checkpoint(path, seed=7):
    torch.manual_seed(seed)
    model = NnueNet(HIDDEN, QA, QB, SCALE)
    config = dataclasses.asdict(TrainingConfig(
        hidden_width=HIDDEN, qa=QA, qb=QB, output_scale=SCALE,
        k=2.773456, learning_rate=0.01, seed=seed, steps=1, batch_size=2))
    torch.save({"model_state_dict": model.state_dict(), "config": config,
                "experiment_metadata": {"trainer_commit": "abc1234", "seed": seed}}, path)


@pytest.fixture
def fen_file(tmp_path):
    path = tmp_path / "sample.epd"
    path.write_text("# header\n" + "\n".join(corpus_fens()[:25]) + "\n")
    return path


def test_main_is_not_run_on_import():
    assert callable(audit.main) and audit.__name__ == "scripts.audit_checkpoint_quantization"


def test_record_has_parity_utilization_and_provenance(tmp_path, fen_file):
    checkpoint = tmp_path / "model.pt"
    _write_checkpoint(checkpoint)
    record = audit.audit_checkpoint(checkpoint, audit.read_fens(fen_file), [fen_file.name])

    assert record["checkpoint_file"] == "model.pt"
    assert len(record["checkpoint_sha256"]) == 64
    assert record["trainer_commit"] == "abc1234" and record["training_seed"] == 7
    assert record["parity"]["position_count"] == 25
    assert record["scale_utilization"]["position_count"] == 25
    assert record["violations"] == [] and record["thresholds"] is None
    # an untrained default-initialised net is the poorly scaled regime: O(1) FT weights
    assert record["scale_utilization"]["ft_weight_rms"] < 2.0
    assert str(tmp_path) not in json.dumps(record), "no absolute path may be recorded"


def test_report_only_exit_status_is_zero_and_output_is_written(tmp_path, fen_file, capsys):
    checkpoint = tmp_path / "model.pt"
    _write_checkpoint(checkpoint)
    out = tmp_path / "audit.json"

    status = audit.main([str(checkpoint), "--fens", str(fen_file), "--output", str(out)])

    assert status == 0
    written = json.loads(out.read_text())
    assert written == json.loads(capsys.readouterr().out)
    assert written["fen_sources"] == ["sample.epd"]


def test_a_bound_turns_the_audit_into_a_gate(tmp_path, fen_file):
    checkpoint = tmp_path / "model.pt"
    _write_checkpoint(checkpoint)
    args = [str(checkpoint), "--fens", str(fen_file)]

    measured = audit.audit_checkpoint(checkpoint, audit.read_fens(fen_file), ["sample.epd"])["parity"]["mae_cp"]
    assert audit.main(args + ["--max-mae-cp", str(measured + 1.0)]) == 0
    assert audit.main(args + ["--max-mae-cp", str(measured / 2)]) == 1


def test_default_corpus_is_the_committed_bench_set():
    fens, sources = audit.default_corpus()
    assert len(fens) == 393
    assert sources[0] == "bench/nnue-corpus/tactical.epd"
