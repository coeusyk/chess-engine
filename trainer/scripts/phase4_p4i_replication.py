"""P4I replication (research doc `docs/architecture/research/nnue/phase4-p4i-replication.md`):
does the low-K arm's weak positive correlation gain reproduce under a second training seed, at
the actual Phase 4 configuration -- rather than relying on the noise floor measured at a
*different* configuration (Phase 1's §27.2, 2,000 steps/constant LR, K=2.773456/unfiltered)?

**Scope, per this task's explicit constraint list**: only the random seed changes. Everything
else -- schedule, dataset, preprocessing, checkpoint selection, K itself -- is identical to P4I's
low-K arm. This script does not modify K, does not evaluate additional K values, and does not
change the production K value. It reuses `phase4_p4i_k_sweep.py`'s own helpers (`_train_arm`,
`_eval_cell`, `_load_model`) rather than duplicating them, so the training/evaluation code path is
byte-identical to P4I's own -- the only difference is the config passed in.

**Seed choice**: 43 -- continuing this project's own established repeated-seed-variance
convention (research doc §27.2's P1-G00/-S43/-S44 used seeds 42/43/44 for exactly this purpose).
Not arbitrary: the same convention already in use, applied to a new configuration.
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
from scripts.train_candidate_net import combine_and_split
from scripts.phase4_p4i_k_sweep import (
    HIDDEN_WIDTH, QA, QB, OUTPUT_SCALE, K_LOW, K_BASE,
    STAGE1_DIR, STAGE2_DIR, SPLIT_SEED, STEPS, LEARNING_RATE, LR_SCHEDULE, WARMUP_STEPS,
    BATCH_SIZE, TRAIN_SAMPLE_SELECTION_SEED, TRAIN_SAMPLE_SIZE, NOISE_FLOOR_STD,
    _is_sentinel, _load_model, _select_best_checkpoint, _eval_cell,
)

REPLICATION_SEED = 43  # research doc §27.2's own repeated-seed convention (42/43/44), reused here
TAG = "P4I-003-low-repl-s43"
OUTPUT_ROOT = Path("outputs/phase4") / "P4I" / TAG

P3A_001_BASELINE_CHECKPOINT = Path("outputs/phase3/P3A-001/checkpoints/step-016999.pt")
P4I_LOW_CHECKPOINT = Path("outputs/phase4/P4I/P4I-001-low/checkpoints/step-010999.pt")


def main() -> int:
    print("=== P4I replication: low-K arm at a second training seed ===")
    print(f"K={K_LOW} (unchanged from P4I-001-low), seed={REPLICATION_SEED} "
          f"(P4I-001-low's own seed was 42)")

    # --- Identical dataset/split/preprocessing to P4I (and P3A-001 before it). ---
    training_records, held_out_records, _ = combine_and_split(STAGE1_DIR, STAGE2_DIR, SPLIT_SEED)
    filtered_training_records = [r for r in training_records if not _is_sentinel(r)]
    held_out_clean = [r for r in held_out_records if not _is_sentinel(r)]
    print(f"training set: {len(filtered_training_records)} (identical to P4I's, sentinel-filtered)")
    print(f"v1: {len(held_out_records)}  v1-clean: {len(held_out_clean)}")

    fixed_sample_indices = random.Random(TRAIN_SAMPLE_SELECTION_SEED).sample(
        range(len(filtered_training_records)), min(TRAIN_SAMPLE_SIZE, len(filtered_training_records))
    )
    train_diagnostic_sample = [filtered_training_records[i] for i in fixed_sample_indices]

    OUTPUT_ROOT.mkdir(parents=True, exist_ok=True)
    config = TrainingConfig(
        hidden_width=HIDDEN_WIDTH, qa=QA, qb=QB, output_scale=OUTPUT_SCALE, k=K_LOW,
        learning_rate=LEARNING_RATE, seed=REPLICATION_SEED, steps=STEPS, batch_size=BATCH_SIZE,
        lr_schedule=LR_SCHEDULE, warmup_steps=WARMUP_STEPS,
    )
    print(f"=== training {TAG}: K={K_LOW} steps={STEPS} lr={LEARNING_RATE} schedule={LR_SCHEDULE} "
          f"warmup={WARMUP_STEPS} seed={REPLICATION_SEED} (identical to P4I-001-low except seed) ===")

    t0 = time.time()
    result = train(
        config, filtered_training_records, checkpoint_path=OUTPUT_ROOT / "final.pt",
        held_out_records=held_out_records, log_interval=max(1, STEPS // 20),
        train_diagnostic_sample=train_diagnostic_sample, checkpoint_dir=OUTPUT_ROOT / "checkpoints",
    )
    wall_clock_seconds = time.time() - t0

    diagnostics = [dataclasses.asdict(d) for d in result["diagnostics"]]
    (OUTPUT_ROOT / "training_diagnostics.json").write_text(json.dumps(diagnostics, indent=2))

    best_step, best_checkpoint_path = _select_best_checkpoint(diagnostics, OUTPUT_ROOT)
    print(f"{TAG}: selected_step={best_step}/{STEPS - 1} wall_clock={wall_clock_seconds:.1f}s")

    # --- Evaluate replicate + reload the two existing checkpoints for comparison. ---
    replicate_model = _load_model(best_checkpoint_path)
    baseline_model = _load_model(P3A_001_BASELINE_CHECKPOINT)
    original_low_model = _load_model(P4I_LOW_CHECKPOINT)

    matrix = {}
    for arm, model, k in [("replicate", replicate_model, K_LOW),
                           ("baseline", baseline_model, K_BASE),
                           ("original_low", original_low_model, K_LOW)]:
        matrix[f"{arm}_on_v1"] = _eval_cell(model, held_out_records, k)
        matrix[f"{arm}_on_v1_clean"] = _eval_cell(model, held_out_clean, k)

    print("\n=== evaluation matrix (correlation / RMSE / bias / compression) ===")
    print(f"{'Arm':<14}{'K':>10}{'Seed':>6}{'Benchmark':<12}{'n':>6}{'Correlation':>14}{'RMSE':>10}"
          f"{'Bias':>10}{'Compression':>13}")
    seed_by_arm = {"replicate": REPLICATION_SEED, "baseline": 42, "original_low": 42}
    k_by_arm = {"replicate": K_LOW, "baseline": K_BASE, "original_low": K_LOW}
    for arm in ("baseline", "original_low", "replicate"):
        for bench_label, bench_key in [("v1", "v1"), ("v1-clean", "v1_clean")]:
            c = matrix[f"{arm}_on_{bench_key}"]
            print(f"{arm:<14}{k_by_arm[arm]:>10.4f}{seed_by_arm[arm]:>6}{bench_label:<12}{c['n']:>6}"
                  f"{c['correlation']:>14.4f}{c['overall']['rmse']:>10.1f}"
                  f"{c['overall']['signed_mean_error']:>10.1f}{c['overall']['compression_ratio']:>13.4f}")

    # --- Variance estimate at the ACTUAL Phase 4 configuration (K=K_LOW, steps=20000, cosine),
    # not §27.2's cross-configuration figure -- n=2 (seed 42, seed 43), one degree of freedom. ---
    seed_variance = {}
    for bench in ("v1", "v1_clean"):
        c42 = matrix[f"original_low_on_{bench}"]["correlation"]
        c43 = matrix[f"replicate_on_{bench}"]["correlation"]
        mean = (c42 + c43) / 2
        spread = abs(c42 - c43)
        # Two-point sample std (n=2, ddof=1): sqrt(sum((x-mean)^2)/(n-1)) = |x1-x2|/sqrt(2).
        sample_std_n2 = spread / (2 ** 0.5)
        seed_variance[bench] = {
            "seed_42_corr": c42, "seed_43_corr": c43, "mean": mean,
            "spread": spread, "sample_std_n2": sample_std_n2,
        }

    print("\n=== seed-variance estimate at K=K_LOW, actual Phase 4 configuration (n=2) ===")
    for bench, v in seed_variance.items():
        print(f"  {bench}: seed42={v['seed_42_corr']:.4f} seed43={v['seed_43_corr']:.4f} "
              f"spread={v['spread']:.4f} sample_std(n=2)={v['sample_std_n2']:.4f}")

    summary = {
        "experiment_id": TAG, "replication_seed": REPLICATION_SEED, "original_seed": 42,
        "k_low": K_LOW, "k_base": K_BASE,
        "training_records": len(filtered_training_records),
        "steps": STEPS, "learning_rate": LEARNING_RATE, "lr_schedule": LR_SCHEDULE,
        "warmup_steps": WARMUP_STEPS,
        "phase1_noise_floor_std": NOISE_FLOOR_STD,
        "selected_step": best_step, "final_step": STEPS - 1,
        "wall_clock_seconds": wall_clock_seconds,
        "checkpoint_path": str(best_checkpoint_path),
        "matrix": matrix, "seed_variance": seed_variance,
    }
    (OUTPUT_ROOT / "summary.json").write_text(json.dumps(summary, indent=2))
    print(f"\nReplication complete. Summary: {OUTPUT_ROOT / 'summary.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
