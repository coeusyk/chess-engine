"""The `Trainer` stage (NNUE_TRAINER_ARCHITECTURE.md Section 6/7, PRD "Trainer
Requirements"): optimization loop, KFinder-calibrated sigmoid loss, training-time
overflow-safety weight clipping. Consumes an `Iterable[PositionRecord]` (e.g. from
`trainer.dataset.read_shard`); produces a checkpoint.

Scope boundary (deliberate, not a gap): this loop materializes its input records into
memory and cycles through them in plain Python -- sufficient for the "tiny training
run, few steps" acceptance criterion this PR targets (issue #195). The PRD's own
"Python-side loading is the known bottleneck at the 50-100M position scale" is a real,
separate concern for whichever future PR actually trains at that scale; a `torch`
`DataLoader` wired to `trainer.reproducibility`'s `worker_init_fn`/
`dataloader_generator` is the natural next step then, not built preemptively here.

Second scope boundary (also deliberate): the PRD's "Trainer Requirements" describes
the target as a "blend of sigmoid-scaled eval and WDL outcome." Only the eval_cp/
eval_mate half of that blend is implemented here -- every `DatasetProvider` reachable
from this PR's dependency chain (D-1/D-2/D-3, Stage 1 text data) has no WDL field at
all (`trainer/trainer/contracts/dataset.py`'s `PositionLabel` allows it, but
`TextDatasetProvider` never populates it). `target_cp` raises loudly on a WDL-only
label rather than silently treating the blend as satisfied. The actual lambda-blend
becomes real work once a WDL-bearing source exists (Stage 3 self-play, Phase E, or a
future `Labeler` stage per the PRD's own component table) -- not built preemptively
against data that doesn't exist yet.
"""

from __future__ import annotations

import json
import math
import random
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional

import torch

from trainer.contracts import PositionLabel, PositionRecord
from trainer.model.batching import encode_batch
from trainer.model.network import NnueNet
from trainer.reproducibility import capture, seed_everything

# A large-but-finite centipawn stand-in for a forced mate, so mate-only labels share
# the same sigmoid-target code path as eval_cp labels rather than a special-cased
# branch in the loss itself.
MATE_EQUIVALENT_CP = 3000.0


@dataclass(frozen=True)
class TrainingDiagnostic:
    """Issue #215: one logged checkpoint in the training-loss/held-out-loss series.
    `held_out_loss` is `None` when `train()` isn't given held-out records to
    evaluate against (this field is optional at the call site, not always populated).

    Issue #219/Phase 1 (research doc §26.2's mandatory-measurements trajectory):
    `train_correlation`/`train_rmse`/`train_bias` are `None` unless `train()` is given
    `train_diagnostic_sample`; `held_out_correlation`/`held_out_rmse`/`held_out_bias`
    are `None` under the same condition as `held_out_loss` above. `learning_rate` is
    the *effective* rate at this step (post-schedule, §26.1's Phase 1 grid), not
    necessarily `TrainingConfig.learning_rate` verbatim.
    """

    step: int
    train_loss: float
    learning_rate: float
    gradient_norm: float
    held_out_loss: Optional[float] = None
    train_correlation: Optional[float] = None
    held_out_correlation: Optional[float] = None
    train_rmse: Optional[float] = None
    held_out_rmse: Optional[float] = None
    train_bias: Optional[float] = None
    held_out_bias: Optional[float] = None


@dataclass(frozen=True)
class TrainingConfig:
    hidden_width: int
    qa: float
    qb: float
    output_scale: float
    k: float
    learning_rate: float
    seed: int
    steps: int
    batch_size: int
    # Phase 1 (research doc §24.4/§26.1): optimization-schedule knobs only -- default
    # values reproduce every pre-existing config/checkpoint's behavior exactly (a flat
    # `learning_rate` for the whole run), so no prior caller or test needs updating.
    lr_schedule: str = "constant"
    warmup_steps: int = 0


def load_config(path: Path) -> TrainingConfig:
    with open(path, "r", encoding="utf-8") as f:
        raw = json.load(f)
    return TrainingConfig(**raw)


def texel_sigmoid(x: torch.Tensor, k: float) -> torch.Tensor:
    """The same sigmoid `KFinder.java` uses
    (`engine-tuner/.../TunerEvaluator.java`: `sigma(s) = 1 / (1 + 10^(-K*s/400))`),
    applied identically here so the NNUE loss lands on classical's calibrated
    centipawn scale (PRD "Trainer Requirements": "a hard requirement -- search
    margins... are tuned to that scale"). `k` is taken from an existing `KFinder` run
    (config-supplied), never recomputed in Python.
    """
    return 1.0 / (1.0 + torch.pow(torch.tensor(10.0), -k * x / 400.0))


def target_cp(label: PositionLabel) -> float:
    if label.eval_cp is not None:
        return float(label.eval_cp)
    if label.eval_mate is not None:
        return MATE_EQUIVALENT_CP if label.eval_mate > 0 else -MATE_EQUIVALENT_CP
    raise ValueError(
        "PositionLabel has neither eval_cp nor eval_mate -- WDL-only labels have no "
        "target-cp equivalent yet (no WDL-bearing DatasetProvider exists before Phase E)"
    )


def _learning_rate_at_step(config: TrainingConfig, step: int) -> float:
    """Phase 1's optimization-schedule knob (research doc §24.4/§26.1) -- optimizer
    *type* is untouched (still plain Adam, per the phase's own scope); only the rate
    Adam is given at each step varies. `"constant"` (the default) returns
    `config.learning_rate` unconditionally, exactly reproducing every pre-Phase-1
    config's behavior. `"cosine"` linearly warms up over `warmup_steps` (if any), then
    cosine-decays from `learning_rate` to 0 over the remaining steps -- chosen over a
    step/exponential decay because it needs no extra knob beyond `warmup_steps`
    (already required for warmup) and is the standard choice this project's own
    reasoning (§24.4 Phase 1: LR=0.01 held flat for 20,000 steps is exactly the
    condition that tends to oscillate near a minimum) argues for testing.
    """
    if config.lr_schedule == "constant":
        return config.learning_rate
    if config.lr_schedule == "cosine":
        if step < config.warmup_steps:
            return config.learning_rate * (step + 1) / max(1, config.warmup_steps)
        decay_steps = max(1, config.steps - config.warmup_steps)
        progress = min(1.0, (step - config.warmup_steps) / decay_steps)
        return config.learning_rate * 0.5 * (1.0 + math.cos(math.pi * progress))
    raise ValueError(f"unknown lr_schedule {config.lr_schedule!r} (expected 'constant' or 'cosine')")


def _gradient_norm(model: NnueNet) -> float:
    """Read-only L2 norm of the gradients `loss.backward()` just populated -- reads
    `.grad` without modifying it, so this has zero effect on the optimizer step that
    follows. Issue #215: a standard optimizer-pathology signal (blowup/collapse),
    more direct than loss-curve shape alone.
    """
    total = 0.0
    for p in model.parameters():
        if p.grad is not None:
            total += p.grad.detach().norm(2).item() ** 2
    return total**0.5


def train(
    config: TrainingConfig,
    records: Iterable[PositionRecord],
    checkpoint_path: Path,
    held_out_records: Optional[Iterable[PositionRecord]] = None,
    log_interval: int = 100,
    train_diagnostic_sample: Optional[List[PositionRecord]] = None,
) -> Dict[str, Any]:
    """Runs `config.steps` optimization steps over `records` and writes a checkpoint to
    `checkpoint_path`. Returns the per-step loss history, plus (issue #215) a
    `diagnostics` series logged every `log_interval` steps (and always at the final
    step): train loss, effective learning rate, gradient norm, and -- only if
    `held_out_records` is given -- held-out loss/correlation/RMSE/bias; likewise
    train-set correlation/RMSE/bias are populated only if `train_diagnostic_sample` is
    given (research doc §26.2's mandatory Phase 1 trajectory).

    Positions are cycled once per pass in a fixed order, **reshuffled at the start of
    every subsequent pass** (Phase 1, research doc §24.4/§26.1) -- driven by the same
    `seed_everything(config.seed)`-seeded `random` module already used for model
    initialization, not a separate dedicated shuffle seed (§26.1's seed inventory
    records this choice). A batch may span an epoch boundary (part before the
    reshuffle, part after) when `batch_size` doesn't evenly divide `len(records)` --
    ordinary, expected behavior for shuffled sampling, not a bug.

    Passing `held_out_records=None` and `train_diagnostic_sample=None` (both default)
    reproduces this function's pre-Phase-1 behavior for `held_out_loss`/other
    diagnostics; the reshuffling above is unconditional (applies regardless of these
    two parameters) since it is a Phase-1-wide pipeline change, not a per-call option
    (research doc §26.1: bundled into every Phase 1 grid cell, not swept).
    """
    seed_everything(config.seed)

    model = NnueNet(config.hidden_width, config.qa, config.qb, config.output_scale)
    optimizer = torch.optim.Adam(model.parameters(), lr=config.learning_rate)

    records = list(records)
    if not records:
        raise ValueError("train() requires at least one record")
    held_out_records = list(held_out_records) if held_out_records is not None else None

    losses: List[float] = []
    diagnostics: List[TrainingDiagnostic] = []
    epoch_order = list(range(len(records)))
    random.shuffle(epoch_order)
    position_in_epoch = 0
    for step in range(config.steps):
        batch_records = []
        for _ in range(config.batch_size):
            if position_in_epoch >= len(epoch_order):
                random.shuffle(epoch_order)
                position_in_epoch = 0
            batch_records.append(records[epoch_order[position_in_epoch]])
            position_in_epoch += 1

        batch = encode_batch(batch_records)
        target_cps = torch.tensor([target_cp(r.label) for r in batch_records], dtype=torch.float32)
        targets = texel_sigmoid(target_cps, config.k)

        current_lr = _learning_rate_at_step(config, step)
        for param_group in optimizer.param_groups:
            param_group["lr"] = current_lr

        optimizer.zero_grad()
        predicted_cp = model(batch.us_indices, batch.us_offsets, batch.them_indices, batch.them_offsets)
        predicted_prob = texel_sigmoid(predicted_cp, config.k)
        loss = torch.mean((predicted_prob - targets) ** 2)
        loss.backward()
        gradient_norm = _gradient_norm(model)
        optimizer.step()
        model.clip_ft_weights_()

        losses.append(loss.item())

        if (step + 1) % log_interval == 0 or step == config.steps - 1:
            # Deferred import: validator.py imports target_cp/texel_sigmoid from this
            # module, so a module-level import here would be circular.
            from trainer.validation.validator import calibration_report, evaluate_held_out

            held_out_loss = held_out_correlation = held_out_rmse = held_out_bias = None
            if held_out_records is not None:
                held_out_eval = evaluate_held_out(model, held_out_records, config.k)
                held_out_loss = held_out_eval.held_out_loss
                held_out_correlation = held_out_eval.label_correlation
                held_out_cal = calibration_report(model, held_out_records).overall
                held_out_rmse = held_out_cal.rmse
                held_out_bias = held_out_cal.signed_mean_error

            train_correlation = train_rmse = train_bias = None
            if train_diagnostic_sample is not None:
                train_eval = evaluate_held_out(model, train_diagnostic_sample, config.k)
                train_correlation = train_eval.label_correlation
                train_cal = calibration_report(model, train_diagnostic_sample).overall
                train_rmse = train_cal.rmse
                train_bias = train_cal.signed_mean_error

            diagnostics.append(
                TrainingDiagnostic(
                    step=step,
                    train_loss=losses[-1],
                    learning_rate=current_lr,
                    gradient_norm=gradient_norm,
                    held_out_loss=held_out_loss,
                    train_correlation=train_correlation,
                    held_out_correlation=held_out_correlation,
                    train_rmse=train_rmse,
                    held_out_rmse=held_out_rmse,
                    train_bias=train_bias,
                    held_out_bias=held_out_bias,
                )
            )

    metadata = capture(seed=config.seed, config=asdict(config))
    torch.save(
        {
            "model_state_dict": model.state_dict(),
            "optimizer_state_dict": optimizer.state_dict(),
            "config": asdict(config),
            "experiment_metadata": asdict(metadata),
            "final_loss": losses[-1],
        },
        checkpoint_path,
    )

    return {"losses": losses, "diagnostics": diagnostics}
