"""Experiment P5-AUXHEAD-001: auxiliary WDL head (multi-task learning), Phase 5.

Implements the design scoped in `docs/architecture/research/nnue/
phase5-arch-scoping-auxiliary-head.md` (Option A) under the pre-implementation contract in
`phase5-p5-auxhead-design.md`. Read that design record for gradient flow, checkpoint layout,
export/inference behavior, and the rollback plan -- not restated here.

**Scope**: only `TrainingConfig.aux_wdl_weight` is varied (0.0 -> 0.04). `K`, architecture,
optimizer, P1-G04's frozen schedule, datasets, split/training/shuffle seeds, benchmark
versions, checkpoint-selection protocol, evaluation protocol, and promotion protocol are all
unchanged. Nothing in `trainer/export/`, `trainer/quantization/`, or `engine-core/` is
touched: the auxiliary head is training-only and structurally invisible to the export path.

**Why this is not another member of the closed WDL-blend family**: P4IV (lambda=0.5) and
P5-WDLALT (lambda=0.8) blended outcome *into the primary target*, so the primary head was
fitted to contaminated values. Here the primary target stays pure `eval_cp` and outcome
signal reaches only the *shared* feature transformer, through a separate head whose gradient
provably never touches `output_layer` (asserted in
`tests/model/test_auxiliary_wdl_head.py::test_auxiliary_gradient_reaches_shared_trunk_but_not_primary_head`).

**Baseline reuse and the same-rubric property**: the P3A-001 checkpoint (P1-G04, the current
production reference) is reused unmodified. The auxiliary head never touches `target_cp()`,
`texel_sigmoid()`, or any `validator.py` path, so baseline and candidate are graded under an
identical rubric -- a direct comparison with none of `measurement-model.md` §5's
rubric-contamination risk, the same clean property P4IV had.
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
from trainer.model.network import NnueNet
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

TAG = "P5-AUXHEAD-001"
STAGE1_DIR = Path("outputs/datasets/stage1-lichess")
STAGE2_DIR = Path("outputs/datasets/stage2-quiet-sf-wdl")  # same corpus P4IV/P5-WDLALT used
OUTPUT_ROOT = Path("outputs/phase5") / "P5-AUXHEAD"

# Declared before running, derived from measured loss magnitudes at initialization
# (primary MSE 0.144562, auxiliary masked-BCE 0.873508, ratio 6.04x): 0.04 puts the
# auxiliary term at ~25% of the primary term's magnitude -- meaningful but unambiguously
# subordinate, keeping the primary evaluation loss the primary objective. Full derivation
# and rationale in phase5-p5-auxhead-design.md §2. Not tuned post-hoc.
AUX_WDL_WEIGHT = 0.04


def _load_model_allowing_aux_head(checkpoint_path: Path) -> NnueNet:
    """Local, deliberately narrow variant of `phase4_p4i_k_sweep._load_model`.

    This experiment's checkpoints carry `wdl_head.*` parameters that a plain `NnueNet` does
    not declare, so a strict load rejects them. Loading non-strictly into a *primary-only*
    model is exactly right for evaluation: the auxiliary head is training-only and must not
    participate in any metric, so discarding it here is the intended behavior, not a
    workaround.

    `phase4_p4i_k_sweep._load_model`'s own `strict=True` is deliberately NOT relaxed --
    six unrelated scripts import it, and weakening a shared correctness guard for one
    experiment's convenience is the near-miss P4III caught (an early draft flipping a shared
    default for every caller). The compatibility is introduced here only, where it is needed.
    """
    checkpoint = torch.load(checkpoint_path, weights_only=False)
    model = NnueNet(HIDDEN_WIDTH, QA, QB, OUTPUT_SCALE)  # primary-only, by design
    missing, unexpected = model.load_state_dict(checkpoint["model_state_dict"], strict=False)
    if missing:
        raise ValueError(f"checkpoint is missing primary parameters: {missing}")
    if any(not key.startswith("wdl_head") for key in unexpected):
        raise ValueError(f"unexpected non-auxiliary parameters in checkpoint: {unexpected}")
    return model


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
        lr_schedule=LR_SCHEDULE, warmup_steps=WARMUP_STEPS, aux_wdl_weight=AUX_WDL_WEIGHT,
    )
    print(f"=== training {TAG}: aux_wdl_weight={AUX_WDL_WEIGHT} K={K_BASE} steps={STEPS} "
          f"lr={LEARNING_RATE} schedule={LR_SCHEDULE} warmup={WARMUP_STEPS} "
          f"seed={TRAINING_SEED} (P1-G04's frozen schedule, aux_wdl_weight only varies) ===")

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
        "tag": TAG, "aux_wdl_weight": AUX_WDL_WEIGHT, "wall_clock_seconds": wall_clock_seconds,
        "selected_step": selected_step, "final_step": final_step,
        "selected_checkpoint_path": str(selected_checkpoint_path),
        "final_checkpoint_path": str(final_checkpoint_path),
    }


def _subset_correlation(model, records: List[PositionRecord], predicate) -> "float | None":
    subset = [r for r in records if predicate(r)]
    return evaluate_held_out(model, subset, K_BASE).label_correlation if subset else None


def main() -> int:
    print("=== Experiment P5-AUXHEAD-001: auxiliary WDL head (multi-task), Phase 5 ===")
    print(f"aux_wdl_weight={AUX_WDL_WEIGHT} (training-only head sharing the feature transformer;"
          f" primary target left pure, unlike P4IV/P5-WDLALT)")

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

    # Baseline: the current production reference (P1-G04 via P3A-001), reused unmodified and
    # graded under an identical rubric -- the auxiliary head touches no evaluation path.
    baseline_model = _load_model(P3A_001_BASELINE_CHECKPOINT)
    arm_meta = _train_arm(filtered_training_records, held_out_records, train_diagnostic_sample)
    selected_model = _load_model_allowing_aux_head(Path(arm_meta["selected_checkpoint_path"]))
    final_model = _load_model_allowing_aux_head(Path(arm_meta["final_checkpoint_path"]))

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

    print("\n=== evaluation matrix (majority-population rule order: cp-subset FIRST, then mate, then pooled) ===")
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

    baseline_cp = matrix["baseline_on_v1_clean"]["cp_subset_correlation"]
    for arm in ("candidate_selected", "candidate_final"):
        delta = matrix[f"{arm}_on_v1_clean"]["cp_subset_correlation"] - baseline_cp
        print(f"PRIMARY DECISION METRIC  {arm:<20} cp-only v1-clean delta vs baseline: {delta:+.4f}")

    summary = {
        "experiment_id": "P5-AUXHEAD-001", "k_base": K_BASE, "aux_wdl_weight": AUX_WDL_WEIGHT,
        "wdl_fraction_of_training_corpus": n_wdl / len(filtered_training_records),
        "mate_fraction_of_training_corpus": n_mate / len(filtered_training_records),
        "training_records": len(filtered_training_records),
        "steps": STEPS, "learning_rate": LEARNING_RATE, "lr_schedule": LR_SCHEDULE,
        "warmup_steps": WARMUP_STEPS, "seed": TRAINING_SEED,
        "arm": arm_meta, "matrix": matrix,
    }
    (OUTPUT_ROOT / "summary.json").write_text(json.dumps(summary, indent=2))
    print(f"\nExperiment P5-AUXHEAD-001 complete. Summary: {OUTPUT_ROOT / 'summary.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
