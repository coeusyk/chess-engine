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

Second scope boundary (updated, Phase 4-WDL): the PRD's "Trainer Requirements"
describes the target as a "blend of sigmoid-scaled eval and WDL outcome." The
lambda-blend half is now implemented (`TrainingConfig.wdl_lambda`, see below) --
`target_cp` itself is unchanged and still raises loudly on a WDL-only label (no
current `DatasetProvider` produces one; `TextDatasetProvider`/Stage 1 has no WDL
field, per `docs/architecture/research/nnue/phase4c-reranking-wdl-audit.md` §4).
The blend operates entirely in `train()`'s loss line, directly on `PositionLabel.wdl`
where present, not through `target_cp()` -- see `train()`'s own docstring. Only
Stage 2 (via `trainer/scripts/backfill_stage2_wdl.py`'s FEN-join backfill) currently
populates `wdl`; this is a Stage-2-only intervention for now, not a claim that Stage 1
or a future Stage 3 self-play source is wired in.
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
# branch in the loss itself. Retained only for `target_cp(..., distance_aware=False)`
# (research doc RQ-3/`P4III`: reproduces the pre-Phase-4C flat-target behavior exactly,
# for direct comparison against historical experiment numbers) -- the production
# default (`distance_aware=True`) no longer uses this constant; see MATE_BASE_CP below.
MATE_EQUIVALENT_CP = 3000.0

# Phase 4C (research doc RQ-3/`P4III`, §39): mate-distance-aware target, replacing the
# flat MATE_EQUIVALENT_CP for mate-labeled records. Derived from `texel_sigmoid`'s own
# saturation formula (K=2.773456's calibrated value), not chosen by eye -- see the
# research doc's RQ-3 report for the full derivation and the disclosed confound it
# accepts (this necessarily also de-scales mate-target magnitude, see below).
#
# `MATE_BASE_CP` (mate-in-0/1, the shortest/most-certain distance): anchored near the
# 99th percentile of |eval_cp| in the sentinel-filtered training corpus (measured
# directly: 976cp, rounded to 1000) -- keeps short/common mate distances more extreme
# than ~99% of ordinary evaluations, preserving the "categorically decisive" semantic
# the flat design intended, at the cost of near-zero residual gradient at this end
# (`texel_sigmoid`'s own gradient-ratio formula: sigma'(1000)/sigma'(0) approx 4.7e-7)
# -- an accepted trade-off since the shortest, most-certain mates are also the ones a
# reasonably-trained net should already predict confidently.
#
# `MATE_FLOOR_CP` (the longest mate distance observed in this training corpus, 64
# moves): the centipawn value where residual gradient has decayed to exactly 10% of
# its zero-crossing peak (`sigma'(x)/sigma'(0) = 4*sigma(x)*(1-sigma(x))`, solved
# numerically: 227.8cp) -- genuinely differentiable, restoring real training signal for
# long, less-certain mates, at the cost of a mate target *smaller* than roughly 29% of
# ordinary cp-labeled evaluations in this corpus (measured: 9,221/31,796 records exceed
# 375cp). This confound -- distance-awareness necessarily entailing some magnitude
# de-scaling under this K-calibrated sigmoid -- is disclosed here explicitly, not
# discovered after the fact: any target kept above roughly 1,000-1,200cp collapses to
# an indistinguishable sigma=1.0 in float32 regardless of its exact value (verified
# directly this session), so "stay extreme AND stay differentiable" is not jointly
# achievable for the long tail under the current loss -- only the short/common end of
# the distribution (median mate distance in this corpus: 5 moves) keeps the "more
# extreme than nearly all ordinary evaluations" property intact.
MATE_BASE_CP = 1000.0
MATE_FLOOR_CP = 227.8
MATE_MAX_OBSERVED_N = 64  # longest |eval_mate| in the sentinel-filtered training corpus, declared in advance


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
    # Phase 4B (research doc RQ-2/`P4II`, §39): per-record loss weight applied to
    # mate-labeled records only (cp-labeled records always weight 1.0). Default 1.0
    # makes mate-labeled and cp-labeled records equal-weighted, reproducing every
    # pre-existing config/checkpoint's behavior exactly (`train()`'s loss line reduces
    # to a plain mean, see `train()`'s docstring for the identity this preserves).
    mate_weight: float = 1.0
    # Phase 4C (research doc RQ-3/`P4III`, §39): whether train()'s loss line grades
    # mate-labeled records against the distance-aware target (see target_cp()'s own
    # docstring) or the pre-Phase-4C flat MATE_EQUIVALENT_CP. Default False reproduces
    # every pre-existing config/checkpoint's behavior exactly -- an unpromoted P4III
    # candidate must not silently become the new default for unrelated future training
    # runs, the same discipline mate_weight's own default=1.0 follows.
    mate_target_distance_aware: bool = False
    # Phase 4-WDL (research doc RQ-4, phase4c-reranking-wdl-audit.md): lambda-blend
    # weight between the sigmoid-scaled eval target and PositionLabel.wdl, for records
    # that carry a wdl value (DR-E1's own convention: 1.0 = pure eval, 0.0 = pure
    # outcome). Default 1.0 reproduces every pre-existing config/checkpoint's behavior
    # exactly, the same safe-default discipline mate_weight/mate_target_distance_aware
    # already follow -- see train()'s own docstring for the blend formula and how
    # records without a wdl value are left unaffected regardless of this value.
    wdl_lambda: float = 1.0
    # Phase 5 (`P5-AUXHEAD`, docs/architecture/research/nnue/phase5-p5-auxhead-design.md):
    # weight of a training-only auxiliary game-outcome (wdl) objective, computed from a
    # second head that shares the feature transformer with the primary evaluation head.
    # Structurally different from wdl_lambda above: wdl_lambda blends outcome INTO the
    # primary target (so the primary head is fitted to contaminated values -- the design
    # P4IV/P5-WDLALT tested and closed); this instead leaves the primary target pure and
    # lets outcome signal reach only the SHARED representation via a separate gradient
    # path. Default 0.0 constructs no auxiliary head at all, reproducing every pre-existing
    # config/checkpoint's behavior exactly -- including its state_dict key set -- the same
    # safe-default discipline mate_weight/mate_target_distance_aware/wdl_lambda follow.
    aux_wdl_weight: float = 0.0


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


def target_cp(label: PositionLabel, distance_aware: bool = False) -> float:
    """`distance_aware=False` (the default): reproduces the pre-Phase-4C flat
    `MATE_EQUIVALENT_CP` behavior exactly, matching every prior config/checkpoint in
    this roadmap -- `train()`'s own loss line only opts into the alternative below via
    `TrainingConfig.mate_target_distance_aware` (default `False`, same reasoning), never
    by relying on this function's own default changing global behavior.

    `distance_aware=True` (research doc RQ-3/`P4III`, unpromoted as of that experiment,
    §39): mate-labeled records get a magnitude that linearly decays from `MATE_BASE_CP`
    (mate-in-0/1) to `MATE_FLOOR_CP` (at `MATE_MAX_OBSERVED_N` moves-to-mate or beyond,
    clamped) -- see the constants' own comments for the saturation-formula derivation.
    `eval_mate` is in *moves* to mate (UCI/Lichess-API convention, confirmed against
    both label sources this session), not plies. Evaluation call sites (`validator.py`
    and everything built on it) always call this with the default -- they have no
    `TrainingConfig` to consult, so any future promoted use of `distance_aware=True`
    would need its own, deliberate evaluation-side plumbing, not assumed for free.
    """
    if label.eval_cp is not None:
        return float(label.eval_cp)
    if label.eval_mate is not None:
        sign = 1.0 if label.eval_mate > 0 else -1.0
        if not distance_aware:
            return sign * MATE_EQUIVALENT_CP
        distance = min(abs(label.eval_mate), MATE_MAX_OBSERVED_N)
        magnitude = MATE_BASE_CP - (MATE_BASE_CP - MATE_FLOOR_CP) * distance / MATE_MAX_OBSERVED_N
        return sign * magnitude
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
    checkpoint_dir: Optional[Path] = None,
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

    `config.mate_weight` (Phase 4B, research doc RQ-2/`P4II`) scales the loss
    contribution of mate-labeled records only; cp-labeled records always weight 1.0.
    The default `mate_weight=1.0` makes every record equal-weighted, so the weighted-mean
    loss formula is algebraically identical to a plain unweighted mean at that default
    (proven directly, `tests/model/test_train.py::
    test_weighted_mean_formula_reduces_to_plain_mean_at_uniform_weight`) -- not verified
    bit-for-bit against this module's pre-Phase-4B literal code path (which no longer
    exists to compare against), only that the implicit default and an explicit
    `mate_weight=1.0` train identical checkpoints under the current code
    (`test_train_default_mate_weight_matches_explicit_mate_weight_one`).

    `config.mate_target_distance_aware` (Phase 4C, research doc RQ-3/`P4III`, unpromoted)
    selects which `target_cp()` mate-branch formula this loss line grades against. The
    default `False` reproduces the pre-Phase-4C flat `MATE_EQUIVALENT_CP` target exactly
    -- an unpromoted P4III candidate does not silently change what any other caller of
    `train()` trains toward.

    `config.wdl_lambda` (Phase 4-WDL, research doc RQ-4): for records whose label
    carries a `wdl` value, the sigmoid-space target is blended
    `wdl_lambda * sigmoid_target + (1 - wdl_lambda) * label.wdl` (DR-E1's own
    lambda-blend formula, `docs/architecture/research/DR-E1-self-play-data-generation.md`
    §1 point 3). Records with no `wdl` value are unaffected regardless of `wdl_lambda`
    -- a per-record `has_wdl` mask selects the blended target only where `wdl` is
    actually present, falling back to the unblended sigmoid target everywhere else, the
    same mask-based isolation `mate_weight`'s `is_mate` mask already uses. The default
    `wdl_lambda=1.0` makes the blend formula algebraically identical to the unblended
    target (`1.0*sigmoid_target + 0.0*label.wdl == sigmoid_target`), reproducing every
    pre-existing config/checkpoint's behavior exactly regardless of whether any record
    in the corpus carries a `wdl` value at all.

    If `checkpoint_dir` is given, a checkpoint is additionally written at every logged
    diagnostic point (not just the final step) to `checkpoint_dir/step-{step:06d}.pt`,
    named by step so ordering is visible from the filename alone -- research doc
    §26.5's checkpoint-preservation policy ("every checkpoint... never overwritten");
    the always-written `checkpoint_path` final-step checkpoint is unaffected either way.
    """
    seed_everything(config.seed)

    model = NnueNet(config.hidden_width, config.qa, config.qb, config.output_scale,
                     with_aux_wdl_head=config.aux_wdl_weight > 0.0)
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
        target_cps = torch.tensor(
            [target_cp(r.label, distance_aware=config.mate_target_distance_aware) for r in batch_records],
            dtype=torch.float32,
        )
        targets = texel_sigmoid(target_cps, config.k)
        # Phase 4-WDL (RQ-4): blend in PositionLabel.wdl where present. has_wdl gates
        # the blend to only records that actually carry a wdl value -- records without
        # one keep their unblended sigmoid target regardless of wdl_lambda, the same
        # isolation discipline mate_weight's is_mate mask below already establishes.
        # At the default wdl_lambda=1.0 this is an exact no-op (see train()'s docstring).
        has_wdl = torch.tensor(
            [r.label.wdl is not None for r in batch_records], dtype=torch.float32
        )
        wdl_values = torch.tensor(
            [r.label.wdl if r.label.wdl is not None else 0.0 for r in batch_records],
            dtype=torch.float32,
        )
        blended_targets = config.wdl_lambda * targets + (1.0 - config.wdl_lambda) * wdl_values
        targets = has_wdl * blended_targets + (1.0 - has_wdl) * targets
        # Phase 4B (RQ-2/`P4II`): mate-labeled records get `config.mate_weight`, everything
        # else weight 1.0 -- the same is_mate boolean-mask pattern `validator.py:205`'s
        # calibration split already uses. At the default mate_weight=1.0 this is a uniform
        # weight of 1.0 everywhere, so `weighted_squared_error.sum() / weights.sum()` is
        # algebraically identical to `torch.mean((predicted_prob - targets) ** 2)`
        # (see `tests/model/test_train.py`'s identity check -- proven against this
        # formula directly, not against the prior code path, which no longer exists).
        is_mate = torch.tensor(
            [r.label.eval_mate is not None for r in batch_records], dtype=torch.float32
        )
        weights = 1.0 + is_mate * (config.mate_weight - 1.0)

        current_lr = _learning_rate_at_step(config, step)
        for param_group in optimizer.param_groups:
            param_group["lr"] = current_lr

        optimizer.zero_grad()
        predicted_cp = model(batch.us_indices, batch.us_offsets, batch.them_indices, batch.them_offsets)
        predicted_prob = texel_sigmoid(predicted_cp, config.k)
        weighted_squared_error = weights * (predicted_prob - targets) ** 2
        loss = weighted_squared_error.sum() / weights.sum()
        # Phase 5 (`P5-AUXHEAD`): add the auxiliary game-outcome objective, if enabled. The
        # auxiliary term is a mean over *wdl-bearing records only* -- not over the whole
        # batch -- so its magnitude does not drift with a given batch's mask density; the
        # denominator is floored at 1.0 so an all-missing batch contributes exactly zero
        # rather than NaN (roughly half the corpus carries no wdl, so this is a real case,
        # not a defensive flourish).
        #
        # `loss` (the primary objective) is what gets appended to `losses`, while
        # `total_loss` is what gets optimized -- deliberate, so an auxiliary run's loss
        # curve stays on the same scale as every prior experiment's rather than silently
        # including a second, differently-scaled term.
        #
        # Scope of that guarantee, stated precisely: it covers `losses` (and the
        # `training_loss` diagnostic derived from it). It does NOT cover `gradient_norm`
        # below, which is computed over every parameter after `total_loss.backward()` and
        # therefore includes both the auxiliary head's gradients and the auxiliary
        # contribution to the shared FT gradients. On an auxiliary run that diagnostic is
        # consequently not comparable to a primary-only run's -- disclosed here and in the
        # experiment report rather than left as an unstated trap for the next reader.
        total_loss = loss
        if config.aux_wdl_weight > 0.0:
            aux_logits = model.auxiliary_wdl_logit(
                batch.us_indices, batch.us_offsets, batch.them_indices, batch.them_offsets
            )
            per_record_bce = torch.nn.functional.binary_cross_entropy_with_logits(
                aux_logits, wdl_values, reduction="none"
            )
            aux_loss = (has_wdl * per_record_bce).sum() / has_wdl.sum().clamp(min=1.0)
            total_loss = loss + config.aux_wdl_weight * aux_loss
        total_loss.backward()
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

            if checkpoint_dir is not None:
                checkpoint_dir.mkdir(parents=True, exist_ok=True)
                torch.save(
                    {"model_state_dict": model.state_dict(), "config": asdict(config), "step": step},
                    checkpoint_dir / f"step-{step:06d}.pt",
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
