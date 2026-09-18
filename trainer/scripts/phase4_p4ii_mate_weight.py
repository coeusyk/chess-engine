"""Experiment P4II (research doc `docs/architecture/research/nnue/phase4-p4ii-mate-weight.md`,
RQ-2/Lever B, §39): mate-aware loss weighting -- does upweighting mate-labeled records' loss
contribution reduce mate-labeled bias without regressing cp-labeled metrics or the primary
promotion metric (v1-clean correlation)?

**Scope, per this task's explicit constraint list**: only `TrainingConfig.mate_weight`
(`trainer/model/train.py`, Phase 4B addition) is varied. `K` is held at the production value
(2.773456, unchanged) -- per RQ-2's own pre-declared fallback ("Best K from 4(i), or the original
K if 4(i) shows no improvement") and Phase 4A's closure (P4I's low-K gain was uncorroborated, not
promoted; K exploration exhausted under the current objective). Architecture, features, dataset,
optimizer, checkpointing, export, and quantization are all held fixed -- `network.py`,
`batching.py`, `quantizer.py`, `canonical.py`, `exporter.py`, `contracts/dataset.py` are not
imported for modification, only (transitively, via reused helpers) for evaluation.

**Baseline is reused, not retrained**: `mate_weight=1.0` (the default) makes `train.py`'s weighted
loss algebraically identical to a plain unweighted mean (proven directly,
`tests/model/test_train.py::test_weighted_mean_formula_reduces_to_plain_mean_at_uniform_weight`),
and the implicit default trains an identical checkpoint to an explicit `mate_weight=1.0`
(`test_train_default_mate_weight_matches_explicit_mate_weight_one`) -- both checks against the
current code, not against the pre-Phase-4B code path, which no longer exists to compare against.
On that basis, `P3A-001`'s existing checkpoint (`outputs/phase3/P3A-001/checkpoints/
step-016999.pt`) is treated as the `mate_weight=1.0` arm this design calls for, reused directly
rather than retrained (same pattern as `phase4_p4i_k_sweep.py`).

**mate_weight value (disclosed derivation, not a round-number guess)**: full class-frequency
equalization -- giving the mate-labeled subset (11.51% of the sentinel-filtered training corpus,
measured directly: 4,137/35,933) equal aggregate loss weight to the cp-labeled majority -- would
require `w = (1-p)/p = 7.6858`. This experiment does NOT use that value: full equalization is the
single point most likely to trip the cp-labeled-majority regression guard (research doc §23.5's
failure mode) by construction, and since this is a one-shot (not swept) experiment, the most
aggressive point risks being the least informative one -- it would tell us "this much weight
breaks cp metrics" without telling us whether the mechanism has any viable operating point. This
experiment uses **mate_weight=3.0** (roughly 40% of full equalization) instead: a substantial,
disclosed, pre-declared single test point, not tuned after seeing results.

**A known limitation, stated per this project's disclosure discipline (not discovered after the
fact)**: research doc §23.7's mate-bias mechanism is saturation-induced *gradient* vanishing at
mate-labeled targets (sigma'(3000cp, K=2.773456) is computationally indistinguishable from zero --
see the research doc's Measurement Model appendix). A finite loss weight rescales whatever
gradient signal remains at that saturation point; it cannot restore gradient magnitude the target
representation itself has collapsed to near-zero. This is precisely why RQ-3 (mate-target
representation, a distinct future candidate) exists as this roadmap's other Lever-B variant --
this experiment tests the loss-*weighting* mechanism specifically, not a claim that weighting can
fully substitute for changing the target representation.

**Leakage-safe split methodology (identical to Experiment 3A/P4I, research doc §35.3, reused
verbatim)**: `combine_and_split(seed=42)` reproduces P1-G04's exact 36,000/4,000 split; the
sentinel filter is applied to the *already-split* training list only, so Benchmark v1 (the
held-out 4,000) stays byte-identical across every model in this roadmap.
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
    HIDDEN_WIDTH, QA, QB, OUTPUT_SCALE, K_BASE,
    STAGE1_DIR, STAGE2_DIR, SPLIT_SEED, STEPS, LEARNING_RATE, LR_SCHEDULE, WARMUP_STEPS,
    TRAINING_SEED, BATCH_SIZE, TRAIN_SAMPLE_SELECTION_SEED, TRAIN_SAMPLE_SIZE,
    P3A_001_BASELINE_CHECKPOINT,
    _is_sentinel, _load_model, _select_best_checkpoint, _eval_cell,
)

TAG = "P4II-001-mateweight"
OUTPUT_ROOT = Path("outputs/phase4") / "P4II"

MATE_WEIGHT = 3.0
MATE_WEIGHT_FULL_EQUALIZATION_REFERENCE = 7.6858  # disclosed reference point, NOT used (see module docstring)


def _train_p4ii_arm(training_records: List[PositionRecord], held_out_records: List[PositionRecord],
                     train_diagnostic_sample: List[PositionRecord]) -> dict:
    output_root = OUTPUT_ROOT / TAG
    output_root.mkdir(parents=True, exist_ok=True)

    config = TrainingConfig(
        hidden_width=HIDDEN_WIDTH, qa=QA, qb=QB, output_scale=OUTPUT_SCALE, k=K_BASE,
        learning_rate=LEARNING_RATE, seed=TRAINING_SEED, steps=STEPS, batch_size=BATCH_SIZE,
        lr_schedule=LR_SCHEDULE, warmup_steps=WARMUP_STEPS, mate_weight=MATE_WEIGHT,
    )
    print(f"=== training {TAG}: K={K_BASE} (unchanged) mate_weight={MATE_WEIGHT} steps={STEPS} "
          f"lr={LEARNING_RATE} schedule={LR_SCHEDULE} warmup={WARMUP_STEPS} seed={TRAINING_SEED} "
          f"(P1-G04's frozen schedule; only mate_weight varies vs. P3A-001 baseline) ===")

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
    print(f"{TAG}: selected_step={best_step}/{STEPS - 1} wall_clock={wall_clock_seconds:.1f}s")

    return {
        "tag": TAG, "k": K_BASE, "mate_weight": MATE_WEIGHT, "wall_clock_seconds": wall_clock_seconds,
        "selected_step": best_step, "final_step": STEPS - 1,
        "checkpoint_path": str(best_checkpoint_path),
    }


def main() -> int:
    print("=== Experiment P4II: mate-aware loss weighting (Lever B, RQ-2), single test point ===")
    print(f"mate_weight={MATE_WEIGHT}  "
          f"(full class-frequency-equalization reference={MATE_WEIGHT_FULL_EQUALIZATION_REFERENCE}, not used)")

    training_records, held_out_records, _ = combine_and_split(STAGE1_DIR, STAGE2_DIR, SPLIT_SEED)
    filtered_training_records = [r for r in training_records if not _is_sentinel(r)]
    held_out_clean = [r for r in held_out_records if not _is_sentinel(r)]
    n_mate = sum(1 for r in filtered_training_records if r.label.eval_mate is not None)
    print(f"training set: {len(filtered_training_records)} (mate-labeled: {n_mate}, "
          f"{n_mate / len(filtered_training_records):.4f})")
    print(f"v1: {len(held_out_records)}  v1-clean: {len(held_out_clean)}")

    fixed_sample_indices = random.Random(TRAIN_SAMPLE_SELECTION_SEED).sample(
        range(len(filtered_training_records)), min(TRAIN_SAMPLE_SIZE, len(filtered_training_records))
    )
    train_diagnostic_sample = [filtered_training_records[i] for i in fixed_sample_indices]

    OUTPUT_ROOT.mkdir(parents=True, exist_ok=True)

    # --- Baseline: reused from P3A-001 (mate_weight=1.0 identity, see module docstring). ---
    baseline_model = _load_model(P3A_001_BASELINE_CHECKPOINT)
    arm_meta = _train_p4ii_arm(filtered_training_records, held_out_records, train_diagnostic_sample)
    candidate_model = _load_model(Path(arm_meta["checkpoint_path"]))

    # --- Evaluation: baseline vs. candidate on v1/v1-clean, overall + mate-subset-specific. ---
    matrix = {}
    for arm, model in [("baseline", baseline_model), ("candidate", candidate_model)]:
        matrix[f"{arm}_on_v1"] = _eval_cell(model, held_out_records, K_BASE)
        matrix[f"{arm}_on_v1_clean"] = _eval_cell(model, held_out_clean, K_BASE)

        # Mate-subset-specific correlation (research doc §38.4's flagged unknown -- newly
        # tracked here, no prior experiment reported mate-subset correlation alone).
        mate_only_v1 = [r for r in held_out_records if r.label.eval_mate is not None]
        mate_only_clean = [r for r in held_out_clean if r.label.eval_mate is not None]
        matrix[f"{arm}_on_v1"]["mate_subset_correlation"] = (
            evaluate_held_out(model, mate_only_v1, K_BASE).label_correlation if mate_only_v1 else None
        )
        matrix[f"{arm}_on_v1_clean"]["mate_subset_correlation"] = (
            evaluate_held_out(model, mate_only_clean, K_BASE).label_correlation if mate_only_clean else None
        )

    print("\n=== evaluation matrix (correlation / RMSE / bias / mate-subset correlation) ===")
    print(f"{'Arm':<12}{'Benchmark':<12}{'n':>6}{'Correlation':>14}{'MateSubsetCorr':>16}{'RMSE':>10}{'Bias':>10}")
    for arm in ("baseline", "candidate"):
        for bench_label, bench_key in [("v1", "v1"), ("v1-clean", "v1_clean")]:
            c = matrix[f"{arm}_on_{bench_key}"]
            mate_corr = c["mate_subset_correlation"]
            mate_corr_str = f"{mate_corr:.4f}" if mate_corr is not None else "n/a"
            print(f"{arm:<12}{bench_label:<12}{c['n']:>6}{c['correlation']:>14.4f}{mate_corr_str:>16}"
                  f"{c['overall']['rmse']:>10.1f}{c['overall']['signed_mean_error']:>10.1f}")

    summary = {
        "experiment_id": "P4II", "k_base": K_BASE, "mate_weight": MATE_WEIGHT,
        "mate_weight_full_equalization_reference": MATE_WEIGHT_FULL_EQUALIZATION_REFERENCE,
        "mate_fraction_of_training_corpus": n_mate / len(filtered_training_records),
        "training_records": len(filtered_training_records),
        "steps": STEPS, "learning_rate": LEARNING_RATE, "lr_schedule": LR_SCHEDULE,
        "warmup_steps": WARMUP_STEPS, "seed": TRAINING_SEED,
        "arm": arm_meta, "matrix": matrix,
    }
    (OUTPUT_ROOT / "summary.json").write_text(json.dumps(summary, indent=2))
    print(f"\nExperiment P4II training+evaluation complete. Summary: {OUTPUT_ROOT / 'summary.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
