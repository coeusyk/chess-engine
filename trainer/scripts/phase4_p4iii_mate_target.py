"""Experiment P4III (research doc `docs/architecture/research/nnue/phase4-p4iii-mate-target.md`,
RQ-3, Lever B): mate-target *representation* -- replacing the flat `MATE_EQUIVALENT_CP` with a
mate-distance-aware target for mate-labeled records only, as a distinct intervention from P4II's
loss-*weighting* change. Runs after RQ-2 per the roadmap's own pre-declared sequencing (research
doc §38.5, RQ-3's own definition: "run as its own single-variable experiment... compared against
RQ-2's result").

**Scope, per this task's explicit constraint list**: only `trainer/model/train.py::target_cp()`
(new mate-distance-aware formula, opt-in via `distance_aware=True`) and
`TrainingConfig.mate_target_distance_aware` (new field, default `False`, matching `mate_weight`'s
own opt-in discipline -- an unpromoted candidate must not silently become the new default for
unrelated future training) are changed. K is held at the production value (2.773456, unchanged).
Architecture, features, dataset, optimizer, checkpointing, export, quantization are all held fixed
and not imported for modification -- only (transitively, via reused helpers) for evaluation.

**A critical evaluation-protocol issue, identified before running (not after)**: `target_cp()` is
consumed by BOTH training AND evaluation (`validator.py`, every downstream reporting helper) --
confirmed via this experiment's own Graphify discovery. `target_cp()`'s own default
(`distance_aware=False`) reproduces the pre-Phase-4C flat target everywhere by default, so ordinary
evaluation helpers (`_eval_cell`, `evaluate_held_out`, `calibration_report`) automatically stay on
the historical rubric unless a caller explicitly asks for the new one. **This script therefore
builds small local helpers that evaluate under an *explicit*, disclosed target definition** (never
relying on which default happens to be live) **and evaluates the baseline under BOTH definitions**
-- the old rubric (historical continuity) and the new one (the only valid basis for comparison
against the candidate, which was trained toward it) -- **treating cp-subset correlation --
target-invariant, since cp-labeled records never touch the mate branch -- as the decisive,
uncontaminated comparison**, per this task's own majority-population rule.

**A second, disclosed confound, inherent to this experiment's own design (not a code defect)**:
making mate targets distance-aware, under `K=2.773456`'s sigmoid, necessarily also de-scales
mate-target magnitude for medium/long mate distances -- any target held above roughly 1,000-
1,200cp is float32-indistinguishable from full saturation (`sigma=1.0` exactly), so "stay as
extreme as the old flat 3000cp constant AND be differentiable" is not jointly achievable. This
experiment tests distance-awareness and this de-scaling together, by mechanistic necessity, not
convenience -- see `trainer/model/train.py`'s `MATE_BASE_CP`/`MATE_FLOOR_CP` comments for the full
derivation and the research doc report's own disclosure.

**Baseline is reused, not retrained**: `P3A-001`'s existing checkpoint is the "before" model for
both target-definition readings above -- no separate baseline retrain is needed since the baseline
model's *weights* are unaffected by which `target_cp()` is used to *grade* it (only training
changes weights; evaluation is read-only).
"""

from __future__ import annotations

import dataclasses
import json
import random
import time
from pathlib import Path
from typing import List

import torch

from trainer.contracts import PositionRecord
from trainer.model.batching import encode_batch
from trainer.model.train import TrainingConfig, target_cp, train
from trainer.validation.validator import _pearson_correlation
from scripts.train_candidate_net import combine_and_split
from scripts.phase4_p4i_k_sweep import (
    HIDDEN_WIDTH, QA, QB, OUTPUT_SCALE, K_BASE,
    STAGE1_DIR, STAGE2_DIR, SPLIT_SEED, STEPS, LEARNING_RATE, LR_SCHEDULE, WARMUP_STEPS,
    TRAINING_SEED, BATCH_SIZE, TRAIN_SAMPLE_SELECTION_SEED, TRAIN_SAMPLE_SIZE,
    P3A_001_BASELINE_CHECKPOINT,
    _is_sentinel, _load_model, _select_best_checkpoint,
)

TAG = "P4III-001-mate-target"
OUTPUT_ROOT = Path("outputs/phase4") / "P4III"


def _predictions_with_target(model, records: List[PositionRecord], distance_aware: bool):
    """Mirrors `phase4_p4i_k_sweep.py::_predictions()`, but with an explicit, disclosed
    `distance_aware` choice rather than relying on `target_cp()`'s own default (whatever that
    happens to be) -- every evaluation call in this script states its rubric explicitly."""
    batch = encode_batch(records)
    target_cps = torch.tensor(
        [target_cp(r.label, distance_aware=distance_aware) for r in records], dtype=torch.float32
    )
    with torch.no_grad():
        predicted_cp = model(batch.us_indices, batch.us_offsets, batch.them_indices, batch.them_offsets)
    return predicted_cp, target_cps


def _correlation_with_target(model, records: List[PositionRecord], distance_aware: bool) -> float:
    predicted_cp, target_cps = _predictions_with_target(model, records, distance_aware)
    return _pearson_correlation(predicted_cp, target_cps)


def _bucket(predicted_cp: torch.Tensor, target_cps: torch.Tensor, mask: torch.Tensor) -> dict:
    count = int(mask.sum().item())
    if count == 0:
        return {"signed_mean_error": float("nan"), "compression_ratio": float("nan"),
                "mae": float("nan"), "rmse": float("nan"), "position_count": 0}
    pred, targ = predicted_cp[mask], target_cps[mask]
    residual = pred - targ
    target_std = targ.std(unbiased=False).item()
    compression_ratio = pred.std(unbiased=False).item() / target_std if target_std != 0 else float("nan")
    return {
        "signed_mean_error": residual.mean().item(), "compression_ratio": compression_ratio,
        "mae": residual.abs().mean().item(), "rmse": torch.sqrt((residual**2).mean()).item(),
        "position_count": count,
    }


def _eval_cell_with_target(model, records: List[PositionRecord], distance_aware: bool) -> dict:
    """Full correlation + calibration battery under one *explicit* target definition -- both
    the "old rubric" (distance_aware=False) and "new rubric" (distance_aware=True) readings this
    experiment needs are built from this one function, never from two near-duplicate ones, and
    never by relying on `_eval_cell()`'s implicit use of `target_cp()`'s bare default (which
    would silently tie this script's correctness to a default value declared elsewhere)."""
    predicted_cp, target_cps = _predictions_with_target(model, records, distance_aware)
    is_mate = torch.tensor([r.label.eval_mate is not None for r in records], dtype=torch.bool)
    cp_only = [r for r in records if r.label.eval_mate is None]
    mate_only = [r for r in records if r.label.eval_mate is not None]
    return {
        "correlation": _pearson_correlation(predicted_cp, target_cps),
        "cp_subset_correlation": _correlation_with_target(model, cp_only, distance_aware),
        "mate_subset_correlation": (
            _correlation_with_target(model, mate_only, distance_aware) if mate_only else None
        ),
        "overall": _bucket(predicted_cp, target_cps, torch.ones_like(is_mate)),
        "mate_labeled": _bucket(predicted_cp, target_cps, is_mate),
        "cp_labeled": _bucket(predicted_cp, target_cps, ~is_mate),
    }


def _train_p4iii_arm(training_records: List[PositionRecord], held_out_records: List[PositionRecord],
                      train_diagnostic_sample: List[PositionRecord]) -> dict:
    output_root = OUTPUT_ROOT / TAG
    output_root.mkdir(parents=True, exist_ok=True)

    config = TrainingConfig(
        hidden_width=HIDDEN_WIDTH, qa=QA, qb=QB, output_scale=OUTPUT_SCALE, k=K_BASE,
        learning_rate=LEARNING_RATE, seed=TRAINING_SEED, steps=STEPS, batch_size=BATCH_SIZE,
        lr_schedule=LR_SCHEDULE, warmup_steps=WARMUP_STEPS, mate_target_distance_aware=True,
    )
    print(f"=== training {TAG}: K={K_BASE} (unchanged) mate_target_distance_aware=True "
          f"steps={STEPS} lr={LEARNING_RATE} schedule={LR_SCHEDULE} warmup={WARMUP_STEPS} "
          f"seed={TRAINING_SEED} (P1-G04's frozen schedule; only target_cp's mate branch varies) ===")

    t0 = time.time()
    result = train(
        config, training_records, checkpoint_path=output_root / "final.pt",
        held_out_records=held_out_records, log_interval=max(1, STEPS // 20),
        train_diagnostic_sample=train_diagnostic_sample, checkpoint_dir=output_root / "checkpoints",
    )
    wall_clock_seconds = time.time() - t0

    diagnostics = [dataclasses.asdict(d) for d in result["diagnostics"]]
    (output_root / "training_diagnostics.json").write_text(json.dumps(diagnostics, indent=2))

    best_step, best_checkpoint_path = _select_best_checkpoint(diagnostics, output_root)
    final_step = STEPS - 1
    final_checkpoint_path = output_root / "checkpoints" / f"step-{final_step:06d}.pt"
    print(f"{TAG}: selected_step={best_step}/{final_step} wall_clock={wall_clock_seconds:.1f}s")

    return {
        "tag": TAG, "k": K_BASE, "wall_clock_seconds": wall_clock_seconds,
        "selected_step": best_step, "final_step": final_step,
        "selected_checkpoint_path": str(best_checkpoint_path),
        "final_checkpoint_path": str(final_checkpoint_path),
    }


def main() -> int:
    print("=== Experiment P4III: mate-target representation (Lever B, RQ-3), single test point ===")

    training_records, held_out_records, _ = combine_and_split(STAGE1_DIR, STAGE2_DIR, SPLIT_SEED)
    filtered_training_records = [r for r in training_records if not _is_sentinel(r)]
    held_out_clean = [r for r in held_out_records if not _is_sentinel(r)]
    print(f"training set: {len(filtered_training_records)}")
    print(f"v1: {len(held_out_records)}  v1-clean: {len(held_out_clean)}")

    fixed_sample_indices = random.Random(TRAIN_SAMPLE_SELECTION_SEED).sample(
        range(len(filtered_training_records)), min(TRAIN_SAMPLE_SIZE, len(filtered_training_records))
    )
    train_diagnostic_sample = [filtered_training_records[i] for i in fixed_sample_indices]

    OUTPUT_ROOT.mkdir(parents=True, exist_ok=True)

    # --- Baseline: reused from P3A-001 (weights unaffected by which target_cp grades them). ---
    baseline_model = _load_model(P3A_001_BASELINE_CHECKPOINT)

    # --- Candidate: trained with mate_target_distance_aware=True (train()'s only change). ---
    arm_meta = _train_p4iii_arm(filtered_training_records, held_out_records, train_diagnostic_sample)
    selected_model = _load_model(Path(arm_meta["selected_checkpoint_path"]))
    final_model = _load_model(Path(arm_meta["final_checkpoint_path"]))

    # --- Evaluation, per this task's mandatory ordering: PRIMARY, SECONDARY, MAJORITY ANALYSIS,
    # TARGET SUBSET, EXPLORATORY. Baseline evaluated under BOTH target definitions (module
    # docstring); candidate (both checkpoints) only under the new definition it was trained
    # toward -- the only rubric under which comparing it to anything is valid. ---
    matrix = {}
    benches = [("v1", held_out_records), ("v1_clean", held_out_clean)]

    for bench_label, records in benches:
        matrix[f"baseline_old_target_on_{bench_label}"] = _eval_cell_with_target(
            baseline_model, records, distance_aware=False
        )
        matrix[f"baseline_new_target_on_{bench_label}"] = _eval_cell_with_target(
            baseline_model, records, distance_aware=True
        )
        matrix[f"candidate_selected_on_{bench_label}"] = _eval_cell_with_target(
            selected_model, records, distance_aware=True
        )
        matrix[f"candidate_final_on_{bench_label}"] = _eval_cell_with_target(
            final_model, records, distance_aware=True
        )

    print("\n=== majority-population rule: pooled / cp-subset / mate-subset correlation ===")
    print(f"{'Arm':<28}{'Benchmark':<10}{'Pooled':>10}{'CpSubset':>10}{'MateSubset':>12}")
    for arm in ("baseline_old_target", "baseline_new_target", "candidate_selected", "candidate_final"):
        for bench_label in ("v1", "v1_clean"):
            c = matrix[f"{arm}_on_{bench_label}"]
            mate_c = c["mate_subset_correlation"]
            mate_str = f"{mate_c:.4f}" if mate_c is not None else "n/a"
            print(f"{arm:<28}{bench_label:<10}{c['correlation']:>10.4f}"
                  f"{c['cp_subset_correlation']:>10.4f}{mate_str:>12}")

    summary = {
        "experiment_id": "P4III", "k_base": K_BASE,
        "training_records": len(filtered_training_records),
        "steps": STEPS, "learning_rate": LEARNING_RATE, "lr_schedule": LR_SCHEDULE,
        "warmup_steps": WARMUP_STEPS, "seed": TRAINING_SEED,
        "arm": arm_meta, "matrix": matrix,
    }
    (OUTPUT_ROOT / "summary.json").write_text(json.dumps(summary, indent=2))
    print(f"\nExperiment P4III training+evaluation complete. Summary: {OUTPUT_ROOT / 'summary.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
