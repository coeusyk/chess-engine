from pathlib import Path

import pytest
import torch

from trainer.contracts import PositionLabel, PositionMetadata, PositionRecord
from trainer.dataset.text_provider import TextDatasetProvider
from trainer.model.network import derive_weight_clip_bounds
from trainer.model.train import TrainingConfig, _learning_rate_at_step, load_config, texel_sigmoid, train

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


def test_texel_sigmoid_matches_kfinder_java_formula():
    # sigma(0) = 0.5 regardless of K -- the same fixed point KFinder.java's own
    # sigmoid(eval=0, k) has.
    assert texel_sigmoid(torch.tensor(0.0), k=1.0).item() == 0.5


def test_train_produces_a_loadable_checkpoint(tmp_path):
    checkpoint_path = tmp_path / "checkpoint.pt"
    result = train(TINY_CONFIG, _records(), checkpoint_path)

    assert checkpoint_path.exists()
    assert len(result["losses"]) == TINY_CONFIG.steps

    checkpoint = torch.load(checkpoint_path, weights_only=False)
    assert set(checkpoint.keys()) == {
        "model_state_dict",
        "optimizer_state_dict",
        "config",
        "experiment_metadata",
        "final_loss",
    }
    assert checkpoint["config"]["hidden_width"] == 4
    assert checkpoint["experiment_metadata"]["seed"] == 42


def test_train_is_reproducible_given_the_same_seed(tmp_path):
    train(TINY_CONFIG, _records(), tmp_path / "a.pt")
    train(TINY_CONFIG, _records(), tmp_path / "b.pt")

    checkpoint_a = torch.load(tmp_path / "a.pt", weights_only=False)
    checkpoint_b = torch.load(tmp_path / "b.pt", weights_only=False)

    for key in checkpoint_a["model_state_dict"]:
        assert torch.equal(checkpoint_a["model_state_dict"][key], checkpoint_b["model_state_dict"][key])
    assert checkpoint_a["final_loss"] == checkpoint_b["final_loss"]


def test_train_enforces_weight_clipping_bound(tmp_path):
    train(TINY_CONFIG, _records(), tmp_path / "checkpoint.pt")
    checkpoint = torch.load(tmp_path / "checkpoint.pt", weights_only=False)

    bias_clip, weight_clip = derive_weight_clip_bounds(TINY_CONFIG.qa)
    ft_weight = checkpoint["model_state_dict"]["ft.weight"]
    ft_bias = checkpoint["model_state_dict"]["ft_bias"]

    assert torch.all(ft_weight.abs() <= weight_clip + 1e-4)
    assert torch.all(ft_bias.abs() <= bias_clip + 1e-4)


def test_train_rejects_wdl_only_labels_loudly(tmp_path):
    # No DatasetProvider reachable from D-1/D-2/D-3 ever populates wdl (Stage 1 text
    # data has none) -- this pins that train() fails loudly rather than silently
    # treating the PRD's eval/WDL blend as satisfied when it isn't.
    wdl_only_record = PositionRecord(
        fen="4k3/8/8/8/8/8/8/4K3 w - - 0 1",
        label=PositionLabel(wdl=1.0),
        metadata=PositionMetadata(),
    )
    with pytest.raises(ValueError, match="WDL-only labels"):
        train(TINY_CONFIG, [wdl_only_record], tmp_path / "checkpoint.pt")


def test_load_config_reads_all_fields_from_json(tmp_path):
    config_path = tmp_path / "config.json"
    config_path.write_text(
        '{"hidden_width": 4, "qa": 127, "qb": 64, "output_scale": 400, "k": 1.0, '
        '"learning_rate": 0.01, "seed": 42, "steps": 3, "batch_size": 2}'
    )
    config = load_config(config_path)
    assert config == TINY_CONFIG


def test_train_without_held_out_records_logs_diagnostics_with_no_held_out_loss(tmp_path):
    result = train(TINY_CONFIG, _records(), tmp_path / "checkpoint.pt", log_interval=1)

    assert len(result["diagnostics"]) == TINY_CONFIG.steps  # log_interval=1: every step
    for diagnostic in result["diagnostics"]:
        assert diagnostic.held_out_loss is None
        assert diagnostic.learning_rate == TINY_CONFIG.learning_rate
        assert diagnostic.gradient_norm >= 0.0


def test_train_with_held_out_records_populates_held_out_loss(tmp_path):
    result = train(TINY_CONFIG, _records(), tmp_path / "checkpoint.pt", held_out_records=_records(), log_interval=1)

    assert all(d.held_out_loss is not None for d in result["diagnostics"])


def test_train_log_interval_controls_diagnostic_count_and_always_logs_final_step(tmp_path):
    config = TrainingConfig(**{**vars(TINY_CONFIG), "steps": 5})
    result = train(config, _records(), tmp_path / "checkpoint.pt", log_interval=2)

    # steps are 0-indexed: logged at step 1 (2nd step), 3 (4th step), and 4 (final, steps-1)
    assert [d.step for d in result["diagnostics"]] == [1, 3, 4]


def test_train_diagnostics_do_not_affect_reproducibility(tmp_path):
    # Same assertion as test_train_is_reproducible_given_the_same_seed, but with
    # held_out_records + a tight log_interval exercised -- confirms the new
    # instrumentation (gradient-norm read, intermediate evaluate_held_out calls)
    # doesn't perturb the training RNG stream or resulting weights.
    train(TINY_CONFIG, _records(), tmp_path / "a.pt", held_out_records=_records(), log_interval=1)
    train(TINY_CONFIG, _records(), tmp_path / "b.pt", held_out_records=_records(), log_interval=1)

    checkpoint_a = torch.load(tmp_path / "a.pt", weights_only=False)
    checkpoint_b = torch.load(tmp_path / "b.pt", weights_only=False)
    for key in checkpoint_a["model_state_dict"]:
        assert torch.equal(checkpoint_a["model_state_dict"][key], checkpoint_b["model_state_dict"][key])
    assert checkpoint_a["final_loss"] == checkpoint_b["final_loss"]


def test_train_reshuffle_across_many_epochs_remains_seed_reproducible(tmp_path):
    # Phase 1 (research doc §24.4/§26.1): per-epoch reshuffling. 5 records, batch_size=2,
    # steps=20 forces several epoch wraps (several reshuffles) within one run -- still
    # must be bit-identical given the same seed, since the reshuffle is itself seeded.
    config = TrainingConfig(**{**vars(TINY_CONFIG), "steps": 20})
    train(config, _records(), tmp_path / "a.pt")
    train(config, _records(), tmp_path / "b.pt")

    checkpoint_a = torch.load(tmp_path / "a.pt", weights_only=False)
    checkpoint_b = torch.load(tmp_path / "b.pt", weights_only=False)
    for key in checkpoint_a["model_state_dict"]:
        assert torch.equal(checkpoint_a["model_state_dict"][key], checkpoint_b["model_state_dict"][key])
    assert checkpoint_a["final_loss"] == checkpoint_b["final_loss"]


def test_train_reshuffle_does_not_mutate_caller_supplied_record_order(tmp_path):
    # train() must reshuffle only its own local copy of records, never the caller's
    # list -- a caller reusing the same list across multiple train() calls (as the
    # Phase 1 grid does) must see the same original order every time.
    caller_records = _records()
    original_order = list(caller_records)
    config = TrainingConfig(**{**vars(TINY_CONFIG), "steps": 20})

    train(config, caller_records, tmp_path / "checkpoint.pt")

    assert caller_records == original_order


def test_learning_rate_at_step_constant_schedule_is_flat():
    config = TrainingConfig(**{**vars(TINY_CONFIG), "steps": 100, "lr_schedule": "constant"})
    for step in (0, 1, 50, 99):
        assert _learning_rate_at_step(config, step) == TINY_CONFIG.learning_rate


def test_learning_rate_at_step_cosine_warms_up_then_decays_to_zero():
    config = TrainingConfig(
        **{**vars(TINY_CONFIG), "steps": 100, "lr_schedule": "cosine", "warmup_steps": 10, "learning_rate": 0.01}
    )
    # Warmup: monotonically increasing, reaching the full rate at the last warmup step.
    warmup_lrs = [_learning_rate_at_step(config, s) for s in range(10)]
    assert warmup_lrs == sorted(warmup_lrs)
    assert warmup_lrs[0] > 0
    assert warmup_lrs[-1] == pytest.approx(config.learning_rate)

    # Peak at the end of warmup, then cosine decay down to ~0 at the final step.
    assert _learning_rate_at_step(config, 10) == pytest.approx(config.learning_rate, abs=1e-9)
    assert _learning_rate_at_step(config, 99) == pytest.approx(0.0, abs=1e-3)

    # Monotonically non-increasing through the decay phase.
    decay_lrs = [_learning_rate_at_step(config, s) for s in range(10, 100)]
    assert decay_lrs == sorted(decay_lrs, reverse=True)


def test_learning_rate_at_step_rejects_unknown_schedule():
    config = TrainingConfig(**{**vars(TINY_CONFIG), "lr_schedule": "step-decay"})
    with pytest.raises(ValueError, match="unknown lr_schedule"):
        _learning_rate_at_step(config, 0)


def test_train_diagnostic_learning_rate_reflects_schedule(tmp_path):
    config = TrainingConfig(
        **{**vars(TINY_CONFIG), "steps": 10, "lr_schedule": "cosine", "warmup_steps": 2, "learning_rate": 0.01}
    )
    result = train(config, _records(), tmp_path / "checkpoint.pt", log_interval=1)

    logged_lrs = [d.learning_rate for d in result["diagnostics"]]
    expected_lrs = [_learning_rate_at_step(config, step) for step in range(config.steps)]
    assert logged_lrs == expected_lrs
    # Not flat -- the schedule is actually taking effect, not silently ignored.
    assert len(set(logged_lrs)) > 1


def test_train_populates_train_diagnostics_only_when_sample_given(tmp_path):
    with_sample = train(
        TINY_CONFIG, _records(), tmp_path / "a.pt", log_interval=1, train_diagnostic_sample=_records()
    )
    without_sample = train(TINY_CONFIG, _records(), tmp_path / "b.pt", log_interval=1)

    assert all(d.train_correlation is not None for d in with_sample["diagnostics"])
    assert all(d.train_rmse is not None for d in with_sample["diagnostics"])
    assert all(d.train_bias is not None for d in with_sample["diagnostics"])
    assert all(d.train_correlation is None for d in without_sample["diagnostics"])


def test_train_populates_held_out_calibration_diagnostics(tmp_path):
    result = train(TINY_CONFIG, _records(), tmp_path / "checkpoint.pt", held_out_records=_records(), log_interval=1)

    assert all(d.held_out_correlation is not None for d in result["diagnostics"])
    assert all(d.held_out_rmse is not None for d in result["diagnostics"])
    assert all(d.held_out_bias is not None for d in result["diagnostics"])


def test_train_writes_a_checkpoint_per_diagnostic_point_when_checkpoint_dir_given(tmp_path):
    checkpoint_dir = tmp_path / "checkpoints"
    config = TrainingConfig(**{**vars(TINY_CONFIG), "steps": 5})
    result = train(config, _records(), tmp_path / "final.pt", log_interval=2, checkpoint_dir=checkpoint_dir)

    expected_steps = [d.step for d in result["diagnostics"]]
    written = sorted(checkpoint_dir.glob("step-*.pt"))
    assert len(written) == len(expected_steps)
    assert written == [checkpoint_dir / f"step-{step:06d}.pt" for step in expected_steps]
    # Final logged checkpoint's weights match the run's own final checkpoint_path save.
    final_step_checkpoint = torch.load(written[-1], weights_only=False)
    final_path_checkpoint = torch.load(tmp_path / "final.pt", weights_only=False)
    for key in final_step_checkpoint["model_state_dict"]:
        assert torch.equal(final_step_checkpoint["model_state_dict"][key], final_path_checkpoint["model_state_dict"][key])


def test_train_without_checkpoint_dir_writes_no_intermediate_checkpoints(tmp_path):
    train(TINY_CONFIG, _records(), tmp_path / "final.pt", log_interval=1)
    # No stray directories/files beyond the one explicit checkpoint_path.
    assert list(tmp_path.iterdir()) == [tmp_path / "final.pt"]


def test_weighted_mean_formula_reduces_to_plain_mean_at_uniform_weight():
    # Phase 4B (RQ-2/`P4II`): the exact identity train()'s loss line relies on --
    # weights.sum()-normalized weighted mean, with every weight == 1.0, must equal
    # torch.mean() bit-for-bit. This is the algebraic claim the default mate_weight=1.0
    # rests on; checked directly rather than only via an end-to-end training run.
    squared_error = torch.tensor([0.04, 0.01, 0.09, 0.16, 0.25])
    weights = torch.ones_like(squared_error)
    weighted_mean = (weights * squared_error).sum() / weights.sum()
    assert torch.equal(weighted_mean, torch.mean(squared_error))


def test_train_default_mate_weight_matches_explicit_mate_weight_one(tmp_path):
    # `_records()`'s fixture (stage1_sample.csv) has one mate-labeled record among
    # five -- exercises the is_mate mask on a non-trivial mix. Confirms the implicit
    # default and an explicit mate_weight=1.0 are the same code path, not just the
    # same declared default.
    explicit_config = TrainingConfig(**{**vars(TINY_CONFIG), "mate_weight": 1.0})
    train(TINY_CONFIG, _records(), tmp_path / "default.pt")
    train(explicit_config, _records(), tmp_path / "explicit.pt")

    default_checkpoint = torch.load(tmp_path / "default.pt", weights_only=False)
    explicit_checkpoint = torch.load(tmp_path / "explicit.pt", weights_only=False)
    for key in default_checkpoint["model_state_dict"]:
        assert torch.equal(
            default_checkpoint["model_state_dict"][key], explicit_checkpoint["model_state_dict"][key]
        )
    assert default_checkpoint["final_loss"] == explicit_checkpoint["final_loss"]


def test_train_mate_weight_above_one_changes_the_trained_model(tmp_path):
    # Proves config.mate_weight actually reaches the loss (not dead code): a non-unit
    # weight on the fixture's one mate-labeled record must change the gradient signal
    # and therefore the trained weights, versus the mate_weight=1.0 baseline above.
    weighted_config = TrainingConfig(**{**vars(TINY_CONFIG), "mate_weight": 5.0})
    train(TINY_CONFIG, _records(), tmp_path / "baseline.pt")
    train(weighted_config, _records(), tmp_path / "weighted.pt")

    baseline_checkpoint = torch.load(tmp_path / "baseline.pt", weights_only=False)
    weighted_checkpoint = torch.load(tmp_path / "weighted.pt", weights_only=False)
    differing = any(
        not torch.equal(baseline_checkpoint["model_state_dict"][key], weighted_checkpoint["model_state_dict"][key])
        for key in baseline_checkpoint["model_state_dict"]
    )
    assert differing
    assert baseline_checkpoint["final_loss"] != weighted_checkpoint["final_loss"]
