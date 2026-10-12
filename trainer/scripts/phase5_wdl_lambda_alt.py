"""Experiment P5-WDLALT (Phase 5 roadmap candidate #2, `docs/architecture/research/nnue/
phase5-roadmap.md` Deliverable 5 item 2): WDL blend at an alternate operating point --
a single disclosed test point, extending P4IV (`phase4-p4iv-wdl-blend.md`) to a different
`wdl_lambda` rather than repeating its λ=0.5 midpoint.

**λ value, and why -- disclosed resolution of a self-contradiction in the roadmap
document.** `phase5-roadmap.md`'s own text for this candidate says "a much smaller value
such as 0.1-0.2" while simultaneously describing the test as "moderate rather than
extreme ... analogous to how P4II tested a moderate rather than an extreme
`mate_weight`." Under `TrainingConfig.wdl_lambda`'s actual convention (DR-E1: 1.0 = pure
eval, 0.0 = pure outcome, `train.py` line ~284), 0.1-0.2 is the *extreme* outcome-weighted
end, not a moderate one -- the two halves of that sentence contradict each other. The most
likely reading is that the roadmap's author was picturing λ informally as "outcome
weight," where a *small* value means a *light* outcome touch -- i.e., the intended
operating point was high-λ (mostly eval, small outcome nudge), the inverse of the code's
own convention. Per this project's "disclose, don't resolve unilaterally" norm, this
ambiguity was surfaced to the user before training (not resolved silently) -- confirmed:
test the light-touch end, **λ=0.8** (80% eval-derived target, 20% game-outcome), not the
literal "0.1-0.2" text. Also confirmed as the more informative choice independent of the
disclosure: P4IV's own two undistinguished candidate causes (§10.2 -- (a) outcome signal
carries no ranking information regardless of weight, (b) λ=0.5 diluted a more precise eval
signal) both predict an *outcome-heavy* λ (0.1-0.2) simply regresses further -- a
near-certain, low-information result. λ=0.8's result is genuinely uncertain and directly
answers the roadmap's real question for this candidate: does *any* operating point in this
family avoid P4IV's failure, or does the whole family fail regardless of dose.

**Scope, identical to P4IV's own explicit constraint list**: only `TrainingConfig.
wdl_lambda` is varied (0.5 -> 0.8). `K` (2.773456), architecture, feature extraction,
optimizer, checkpointing, export, and quantization are untouched. Dataset: the same
FEN-join-backfilled `stage2-quiet-sf-wdl` corpus P4IV built and used, no new data
acquisition or re-labeling.

**Baseline is reused, not retrained -- same-rubric property inherited from P4IV**: like
P4IV, this experiment's `wdl_lambda` blend lives entirely inside `train()`'s loss line and
never touches `target_cp()`, `texel_sigmoid()`, or any `validator.py` evaluation path. The
baseline P3A-001 checkpoint is evaluated under the exact same rubric the candidate is --
no rubric-contamination risk (`measurement-model.md` §5), inherited as a design property
from the shared mechanism, not re-derived here.
"""

from __future__ import annotations

import dataclasses
import json
import random
import time
from pathlib import Path
from typing import List

from trainer.contracts import PositionRecord
from trainer.model.train import TrainingConfig, train
from trainer.validation.validator import evaluate_held_out
from scripts.train_candidate_net import combine_and_split
from scripts.phase4_p4i_k_sweep import (
    HIDDEN_WIDTH,
    QA,
    QB,
    OUTPUT_SCALE,
    K_BASE,
    SPLIT_SEED,
    STEPS,
    LEARNING_RATE,
    LR_SCHEDULE,
    WARMUP_STEPS,
    TRAINING_SEED,
    BATCH_SIZE,
    TRAIN_SAMPLE_SELECTION_SEED,
    TRAIN_SAMPLE_SIZE,
    P3A_001_BASELINE_CHECKPOINT,
    _is_sentinel,
    _load_model,
    _select_best_checkpoint,
    _eval_cell,
)

TAG = "P5-WDLALT-001"
STAGE1_DIR = Path("outputs/datasets/stage1-lichess")
STAGE2_DIR = Path("outputs/datasets/stage2-quiet-sf-wdl")  # same backfilled corpus P4IV used
OUTPUT_ROOT = Path("outputs/phase5") / "P5-WDLALT"

WDL_LAMBDA = 0.8  # light outcome touch -- see module docstring for the λ=0.15-vs-0.8 disclosure


def _train_arm(
    training_records: List[PositionRecord],
    held_out_records: List[PositionRecord],
    train_diagnostic_sample: List[PositionRecord],
) -> dict:
    output_root = OUTPUT_ROOT / TAG
    output_root.mkdir(parents=True, exist_ok=True)

    config = TrainingConfig(
        hidden_width=HIDDEN_WIDTH, qa=QA, qb=QB, output_scale=OUTPUT_SCALE, k=K_BASE,
        learning_rate=LEARNING_RATE, seed=TRAINING_SEED, steps=STEPS, batch_size=BATCH_SIZE,
        lr_schedule=LR_SCHEDULE, warmup_steps=WARMUP_STEPS, wdl_lambda=WDL_LAMBDA,
    )
    print(f"=== training {TAG}: wdl_lambda={WDL_LAMBDA} K={K_BASE} steps={STEPS} "
          f"lr={LEARNING_RATE} schedule={LR_SCHEDULE} warmup={WARMUP_STEPS} "
          f"seed={TRAINING_SEED} (P1-G04's frozen schedule, wdl_lambda only varies) ===")

    t0 = time.time()
    result = train(
        config, training_records, checkpoint_path=output_root / "final.pt",
        held_out_records=held_out_records, log_interval=max(1, STEPS // 20),
        train_diagnostic_sample=train_diagnostic_sample, checkpoint_dir=output_root / "checkpoints",
    )
    wall_clock_seconds = time.time() - t0

    diagnostics = [dataclasses.asdict(d) for d in result["diagnostics"]]
    (output_root / "training_diagnostics.json").write_text(json.dumps(diagnostics, indent=2))

    selected_step, selected_checkpoint_path = _select_best_checkpoint(diagnostics, output_root)
    final_step = STEPS - 1
    final_checkpoint_path = output_root / "checkpoints" / f"step-{final_step:06d}.pt"
    print(f"{TAG}: selected_step={selected_step}/{STEPS - 1} final_step={final_step} "
          f"wall_clock={wall_clock_seconds:.1f}s "
          f"(measurement-model.md §8: both checkpoints reported)")

    return {
        "tag": TAG, "wdl_lambda": WDL_LAMBDA, "wall_clock_seconds": wall_clock_seconds,
        "selected_step": selected_step, "final_step": final_step,
        "selected_checkpoint_path": str(selected_checkpoint_path),
        "final_checkpoint_path": str(final_checkpoint_path),
    }


def _subset_correlation(model, records: List[PositionRecord], predicate) -> "float | None":
    subset = [r for r in records if predicate(r)]
    return evaluate_held_out(model, subset, K_BASE).label_correlation if subset else None


def main() -> int:
    print("=== Experiment P5-WDLALT: WDL blend, alternate operating point (Phase 5 candidate #2) ===")
    print(f"wdl_lambda={WDL_LAMBDA} (light outcome touch, vs P4IV's λ=0.5 midpoint)")

    training_records, held_out_records, _ = combine_and_split(STAGE1_DIR, STAGE2_DIR, SPLIT_SEED)
    filtered_training_records = [r for r in training_records if not _is_sentinel(r)]
    held_out_clean = [r for r in held_out_records if not _is_sentinel(r)]

    n_wdl = sum(1 for r in filtered_training_records if r.label.wdl is not None)
    n_mate = sum(1 for r in filtered_training_records if r.label.eval_mate is not None)
    print(f"training set: {len(filtered_training_records)} "
          f"(wdl-labeled: {n_wdl}, {n_wdl / len(filtered_training_records):.4f}; "
          f"mate-labeled: {n_mate}, {n_mate / len(filtered_training_records):.4f})")
    print(f"v1: {len(held_out_records)}  v1-clean: {len(held_out_clean)}")

    fixed_sample_indices = random.Random(TRAIN_SAMPLE_SELECTION_SEED).sample(
        range(len(filtered_training_records)), min(TRAIN_SAMPLE_SIZE, len(filtered_training_records))
    )
    train_diagnostic_sample = [filtered_training_records[i] for i in fixed_sample_indices]

    OUTPUT_ROOT.mkdir(parents=True, exist_ok=True)

    baseline_model = _load_model(P3A_001_BASELINE_CHECKPOINT)
    arm_meta = _train_arm(filtered_training_records, held_out_records, train_diagnostic_sample)
    selected_model = _load_model(Path(arm_meta["selected_checkpoint_path"]))
    final_model = _load_model(Path(arm_meta["final_checkpoint_path"]))

    is_mate = lambda r: r.label.eval_mate is not None  # noqa: E731
    is_cp = lambda r: r.label.eval_cp is not None  # noqa: E731

    matrix = {}
    for arm, model in [("baseline", baseline_model), ("candidate_selected", selected_model),
                        ("candidate_final", final_model)]:
        matrix[f"{arm}_on_v1"] = _eval_cell(model, held_out_records, K_BASE)
        matrix[f"{arm}_on_v1_clean"] = _eval_cell(model, held_out_clean, K_BASE)
        for bench_key, bench_records in [("v1", held_out_records), ("v1_clean", held_out_clean)]:
            matrix[f"{arm}_on_{bench_key}"]["cp_subset_correlation"] = _subset_correlation(
                model, bench_records, is_cp
            )
            matrix[f"{arm}_on_{bench_key}"]["mate_subset_correlation"] = _subset_correlation(
                model, bench_records, is_mate
            )

    print("\n=== evaluation matrix (majority-population rule order: cp-subset, mate-subset, pooled) ===")
    print(f"{'Arm':<20}{'Benchmark':<11}{'n':>6}{'CpSubsetCorr':>14}{'MateSubsetCorr':>16}"
          f"{'PooledCorr':>12}{'RMSE':>10}{'Bias':>10}")
    for arm in ("baseline", "candidate_selected", "candidate_final"):
        for bench_label, bench_key in [("v1", "v1"), ("v1-clean", "v1_clean")]:
            c = matrix[f"{arm}_on_{bench_key}"]
            cp_str = f"{c['cp_subset_correlation']:.4f}" if c["cp_subset_correlation"] is not None else "n/a"
            mate_str = f"{c['mate_subset_correlation']:.4f}" if c["mate_subset_correlation"] is not None else "n/a"
            print(f"{arm:<20}{bench_label:<11}{c['n']:>6}{cp_str:>14}{mate_str:>16}"
                  f"{c['correlation']:>12.4f}{c['overall']['rmse']:>10.1f}"
                  f"{c['overall']['signed_mean_error']:>10.1f}")

    summary = {
        "experiment_id": "P5-WDLALT", "k_base": K_BASE, "wdl_lambda": WDL_LAMBDA,
        "wdl_fraction_of_training_corpus": n_wdl / len(filtered_training_records),
        "mate_fraction_of_training_corpus": n_mate / len(filtered_training_records),
        "training_records": len(filtered_training_records),
        "steps": STEPS, "learning_rate": LEARNING_RATE, "lr_schedule": LR_SCHEDULE,
        "warmup_steps": WARMUP_STEPS, "seed": TRAINING_SEED,
        "arm": arm_meta, "matrix": matrix,
    }
    (OUTPUT_ROOT / "summary.json").write_text(json.dumps(summary, indent=2))
    print(f"\nExperiment P5-WDLALT training+evaluation complete. Summary: {OUTPUT_ROOT / 'summary.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
