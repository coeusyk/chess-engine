"""Experiment P4IV (research doc RQ-4, Lever C): WDL-blended training target, single
disclosed test point -- the highest-ranked remaining Phase 4 candidate per
`docs/architecture/research/nnue/phase4c-reranking-wdl-audit.md`.

**Scope, per this task's explicit constraint list**: only `TrainingConfig.wdl_lambda`
is varied. `K` (2.773456, unchanged), architecture, feature extraction, optimizer,
checkpointing, export, and quantization are untouched -- `network.py`, `batching.py`,
`quantizer.py`, `canonical.py`, `exporter.py` are not imported for modification, only
(transitively) for evaluation. Dataset generation logic changed only to the extent
explicitly required for WDL support: `mmap_shard.py`'s `SHARD_DTYPE` gained a `wdl`
field, and `backfill_stage2_wdl.py` backfilled the existing Stage 2 corpus via a pure
FEN join (no Stockfish re-execution, no new data acquisition) -- see that script and
`mmap_shard.py`'s own docstring for the full migration.

**Baseline is reused, not retrained**: unlike P4III (which redefined `target_cp()`'s
mate branch, consumed by both training *and* evaluation), this experiment's
`wdl_lambda` blend lives entirely inside `train()`'s loss line and never touches
`target_cp()`, `texel_sigmoid()`, or any `validator.py` evaluation path. The baseline
P3A-001 checkpoint is therefore evaluated under the *exact same rubric* the candidate
is -- no rubric-contamination risk of the kind `measurement-model.md` §5 documents,
disclosed here as a design property, not merely asserted.

**`wdl_lambda=0.5`, disclosed derivation, not tuned post-hoc**: DR-E1's own λ
convention (1.0 = pure eval, 0.0 = pure outcome) leaves λ unconstrained for this
backfilled-corpus use case (its own λ discussion is scoped to Stage 3 self-play
generation, not this retrofit). 0.5 -- an equal-weight blend of search-eval and
game-outcome signal -- is the single, pre-declared test point: the natural midpoint,
not the most aggressive (0.0, discarding all search-derived signal for ~56% of the
training corpus) or most conservative (near 1.0, indistinguishable from baseline)
endpoint, mirroring P4II's own precedent of testing a moderate rather than maximal
value on a first, one-shot test.

**Scoping note, methodologically distinct from P4II/P4III**: `wdl` is populated by
FEN membership in the backfilled Stage 2 corpus (~55.7% of the sentinel-filtered
training set), which cuts across *both* the cp-labeled and mate-labeled populations --
unlike P4II/P4III's loss/target changes, which were scoped to the mate-labeled branch
only. cp-only correlation remains the decisive majority-population metric (per
`measurement-model.md` §7 condition 4, cp-labeled records are still ~88.5% of the
corpus), but it is not immune to this intervention the way it was to P4II/P4III's
mate-scoped changes -- most cp-labeled Stage 2 records are themselves part of the
wdl-blended population. This is disclosed explicitly so the promotion-rule reading
below isn't misread as "automatically isolated."
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

TAG = "P4IV-001-wdl-blend"
STAGE1_DIR = Path("outputs/datasets/stage1-lichess")
STAGE2_DIR = Path("outputs/datasets/stage2-quiet-sf-wdl")  # backfilled, not the original
OUTPUT_ROOT = Path("outputs/phase4") / "P4IV"

WDL_LAMBDA = 0.5


def _train_p4iv_arm(
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
    print("=== Experiment P4IV: WDL-blended training target (Lever C, RQ-4), single test point ===")
    print(f"wdl_lambda={WDL_LAMBDA} (0.5 = equal blend of search-eval and outcome, disclosed midpoint)")

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

    # --- Baseline: reused from P3A-001. Directly comparable -- evaluation is untouched
    # by wdl_lambda (see module docstring). ---
    baseline_model = _load_model(P3A_001_BASELINE_CHECKPOINT)
    arm_meta = _train_p4iv_arm(filtered_training_records, held_out_records, train_diagnostic_sample)
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
        "experiment_id": "P4IV", "k_base": K_BASE, "wdl_lambda": WDL_LAMBDA,
        "wdl_fraction_of_training_corpus": n_wdl / len(filtered_training_records),
        "mate_fraction_of_training_corpus": n_mate / len(filtered_training_records),
        "training_records": len(filtered_training_records),
        "steps": STEPS, "learning_rate": LEARNING_RATE, "lr_schedule": LR_SCHEDULE,
        "warmup_steps": WARMUP_STEPS, "seed": TRAINING_SEED,
        "arm": arm_meta, "matrix": matrix,
    }
    (OUTPUT_ROOT / "summary.json").write_text(json.dumps(summary, indent=2))
    print(f"\nExperiment P4IV training+evaluation complete. Summary: {OUTPUT_ROOT / 'summary.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
