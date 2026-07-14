from pathlib import Path

import pytest
import torch

from trainer.dataset.text_provider import TextDatasetProvider
from trainer.model.network import NnueNet
from trainer.model.train import TrainingConfig, train
from trainer.validation.validator import ClassicalEvalRecord, eval_scale_check, evaluate_held_out

FIXTURE_DIR = Path(__file__).parent.parent / "fixtures"

TINY_CONFIG = TrainingConfig(
    hidden_width=4,
    qa=127,
    qb=64,
    output_scale=400,
    k=1.0,
    learning_rate=0.01,
    seed=42,
    steps=3,
    batch_size=2,
)


def _records():
    provider = TextDatasetProvider(directory=FIXTURE_DIR, identifier="x", source_ref="x")
    shard = next(iter(provider.shards()))
    return list(provider.positions(shard))


def _trained_model(tmp_path: Path) -> NnueNet:
    train(TINY_CONFIG, _records(), tmp_path / "checkpoint.pt")
    checkpoint = torch.load(tmp_path / "checkpoint.pt", weights_only=False)
    model = NnueNet(TINY_CONFIG.hidden_width, TINY_CONFIG.qa, TINY_CONFIG.qb, TINY_CONFIG.output_scale)
    model.load_state_dict(checkpoint["model_state_dict"])
    return model


def test_evaluate_held_out_returns_finite_metrics(tmp_path: Path):
    model = _trained_model(tmp_path)
    report = evaluate_held_out(model, _records(), k=TINY_CONFIG.k)

    assert report.position_count == len(_records())
    assert report.held_out_loss == pytest.approx(report.held_out_loss)  # finite, no NaN
    assert -1.0 <= report.label_correlation <= 1.0 or report.label_correlation == 0.0


def test_evaluate_held_out_rejects_empty_records(tmp_path: Path):
    model = _trained_model(tmp_path)
    with pytest.raises(ValueError, match="at least one record"):
        evaluate_held_out(model, [], k=1.0)


def test_eval_scale_check_is_near_zero_when_classical_matches_predicted(tmp_path: Path):
    model = _trained_model(tmp_path)
    records = _records()
    fens = [r.fen for r in records]

    # Derive classical_eval_cp from the model's own predicted output, to test the
    # comparison plumbing itself (not a real classical-vs-NNUE agreement claim --
    # no classical-eval corpus exists yet, see validator.py's module docstring).
    from trainer.model.batching import encode_fens

    batch = encode_fens(fens)
    with torch.no_grad():
        predicted = model(batch.us_indices, batch.us_offsets, batch.them_indices, batch.them_offsets)

    classical_records = [
        ClassicalEvalRecord(fen=fen, classical_eval_cp=round(value))
        for fen, value in zip(fens, predicted.tolist())
    ]

    result = eval_scale_check(model, classical_records)
    assert result.position_count == len(fens)
    assert result.mean_absolute_difference_cp < 1.0  # only rounding error


def test_eval_scale_check_flags_large_deviation(tmp_path: Path):
    model = _trained_model(tmp_path)
    fens = [r.fen for r in _records()]
    classical_records = [ClassicalEvalRecord(fen=fen, classical_eval_cp=1_000_000) for fen in fens]

    result = eval_scale_check(model, classical_records)
    assert result.mean_absolute_difference_cp > 100_000


def test_eval_scale_check_rejects_empty_records(tmp_path: Path):
    model = _trained_model(tmp_path)
    with pytest.raises(ValueError, match="at least one record"):
        eval_scale_check(model, [])
