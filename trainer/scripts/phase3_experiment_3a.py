"""Experiment 3A (research doc §33.1/§35): does removing Stage 1's confirmed sentinel-value
label outliers (research doc §32.4, corrected count) improve the *learned model*, not merely
the *reported metric*? §32.4 already showed post-hoc exclusion of these records from evaluation
raises measured held-out correlation by +0.06-0.064 -- that only proves the correlation metric
is outlier-sensitive. This experiment retrains on data with the same records removed and
measures whether the retrained model itself generalizes any differently.

**Filtering criterion (exact, no additional rules per this task's constraint)**: exclude Stage 1
records whose `eval_cp` is exactly one of the three confirmed sentinel values identified in
§32.4's corrected count -- `{-9605, 9605, 20000}` (no `-20000` occurrences exist in the source
data). Nothing else is filtered: no broader magnitude bound, no Stage 2 change, no mate-label
change.

**Leakage-safe split methodology (the one real methodological subtlety here, same category of
issue as Experiment 2A's held-out-set preservation, research doc §28)**: filtering Stage 1
*before* calling `combine_and_split` would re-shuffle a differently-sized pool and produce a
*different* held-out set membership than P1-G04's -- which would make "Evaluation Set 1:
Original held-out validation set" ambiguous (is it P1-G04's original 4,000, or Model B's own
newly-shuffled 4,000?) and would risk leakage: some of Model B's training records could then
coincide with P1-G04's original held-out set, invalidating a same-benchmark comparison. Instead:
`combine_and_split(seed=42)` is called ONCE, exactly reproducing P1-G04's original 36,000/4,000
split (byte-identical to every prior experiment in this roadmap). The sentinel-value filter is
then applied to the *already-split* training list only. This guarantees:
  - Benchmark v1 (the held-out 4,000) is byte-identical to P1-G04's original held-out set, valid
    for evaluating both models with zero risk of new leakage (Model B's training set is a strict
    subset of P1-G04's training set, so nothing new could have leaked into it).
  - The only difference between P1-G04's and Model B's training data is the ~67 removed
    sentinel-value records -- a strict, single-variable filter, not a re-derived corpus.

A standalone filtered Stage 1 CSV artifact is also written (`outputs/datasets/
stage1-lichess-filtered/`) as the literal "generate a filtered Stage 1 dataset" deliverable this
task's spec names -- for documentation and reuse, not used directly for the leakage-safe split
above (which filters the already-split training list instead, per the reasoning above).
"""

from __future__ import annotations

import csv
import dataclasses
import json
import random
import time
from collections import Counter
from pathlib import Path
from typing import List

import torch

from trainer.contracts import PositionLabel, PositionMetadata, PositionRecord
from trainer.model.network import NnueNet
from trainer.model.train import TrainingConfig, train
from trainer.validation.validator import calibration_report, evaluate_held_out
from scripts.train_candidate_net import combine_and_split

HIDDEN_WIDTH = 256
QA = 127
QB = 64
OUTPUT_SCALE = 400
K = 2.773456  # unchanged -- loss function out of scope

STAGE1_DIR = Path("outputs/datasets/stage1-lichess")
STAGE1_FILTERED_DIR = Path("outputs/datasets/stage1-lichess-filtered")
STAGE2_DIR = Path("outputs/datasets/stage2-quiet-sf")
SPLIT_SEED = 42

# Confirmed sentinel-value set (§32.4, corrected count) -- the ONLY filtering criterion.
SENTINEL_CP_VALUES = {-9605, 9605, 20000}

P1_G04_CHECKPOINT = Path("outputs/phase1/P1-G04/checkpoints/step-015999.pt")

EXPERIMENT_ID = "P3A-001"
OUTPUT_ROOT = Path("outputs/phase3") / EXPERIMENT_ID

# P1-G04's frozen schedule (research doc §27.11) -- unchanged, per this task's instruction.
STEPS = 20000
LEARNING_RATE = 0.01
LR_SCHEDULE = "cosine"
WARMUP_STEPS = 200
TRAINING_SEED = 42
BATCH_SIZE = 256

TRAIN_SAMPLE_SELECTION_SEED = 999
TRAIN_SAMPLE_SIZE = 4000


def _is_sentinel(record: PositionRecord) -> bool:
    return record.label.eval_cp in SENTINEL_CP_VALUES


def _write_filtered_stage1_csv() -> dict:
    """Standalone filtered Stage 1 artifact (documentation deliverable) -- not used for the
    leakage-safe training split below, see module docstring."""
    kept, removed = [], []
    with open(STAGE1_DIR / "stage1.csv", newline="", encoding="ascii") as f:
        for row in csv.reader(f):
            if not row:
                continue
            eval_cp = int(row[1]) if row[1].strip() else None
            if eval_cp in SENTINEL_CP_VALUES:
                removed.append(row)
            else:
                kept.append(row)

    STAGE1_FILTERED_DIR.mkdir(parents=True, exist_ok=True)
    with open(STAGE1_FILTERED_DIR / "stage1.csv", "w", newline="", encoding="ascii") as f:
        csv.writer(f).writerows(kept)

    manifest = {
        "dataset_identifier": "stage1-lichess-evals-2026-07-15-filtered-3a",
        "source": "outputs/datasets/stage1-lichess (2026-07-15 pull)",
        "filtering_criterion": "eval_cp in {-9605, 9605, 20000} (research doc SS32.4, corrected)",
        "original_count": len(kept) + len(removed),
        "removed_count": len(removed),
        "removed_pct": 100.0 * len(removed) / (len(kept) + len(removed)),
        "kept_count": len(kept),
        "removed_value_breakdown": dict(Counter(int(r[1]) for r in removed)),
    }
    (STAGE1_FILTERED_DIR / "manifest.json").write_text(json.dumps(manifest, indent=2))
    print(f"filtered Stage 1 artifact written: {STAGE1_FILTERED_DIR} "
          f"(removed {manifest['removed_count']}/{manifest['original_count']} = "
          f"{manifest['removed_pct']:.3f}%)")
    print(f"  removed value breakdown: {manifest['removed_value_breakdown']}")
    return manifest


def _select_best_checkpoint(diagnostics: list) -> tuple[int, Path]:
    best_step, best_corr = None, float("-inf")
    for d in diagnostics:
        if d["held_out_correlation"] is not None and d["held_out_correlation"] >= best_corr:
            best_corr = d["held_out_correlation"]
            best_step = d["step"]
    return best_step, OUTPUT_ROOT / "checkpoints" / f"step-{best_step:06d}.pt"


def _load_model(checkpoint_path: Path) -> NnueNet:
    checkpoint = torch.load(checkpoint_path, weights_only=False)
    model = NnueNet(HIDDEN_WIDTH, QA, QB, OUTPUT_SCALE)
    model.load_state_dict(checkpoint["model_state_dict"])
    return model


def _eval_cell(model: NnueNet, records: List[PositionRecord]) -> dict:
    held_out_eval = evaluate_held_out(model, records, K)
    cal = calibration_report(model, records)
    return {
        "n": len(records),
        "correlation": held_out_eval.label_correlation,
        "held_out_loss": held_out_eval.held_out_loss,
        "rmse": cal.overall.rmse,
        "bias": cal.overall.signed_mean_error,
        "compression_ratio": cal.overall.compression_ratio,
        "mae": cal.overall.mae,
    }


def main() -> int:
    print(f"=== Experiment 3A ({EXPERIMENT_ID}): Stage 1 sentinel-value filtering ===")

    filter_manifest = _write_filtered_stage1_csv()

    # --- Reproduce P1-G04's exact split (Benchmark v1). ---
    training_records, held_out_records, _ = combine_and_split(STAGE1_DIR, STAGE2_DIR, SPLIT_SEED)
    print(f"P1-G04 original split: training={len(training_records)} held_out={len(held_out_records)}")

    # --- Model B's training set: filter sentinel records out of the already-split list. ---
    filtered_training_records = [r for r in training_records if not _is_sentinel(r)]
    removed_from_training = len(training_records) - len(filtered_training_records)
    print(f"filtered training set: {len(filtered_training_records)} "
          f"(removed {removed_from_training} sentinel records from the training split)")

    # --- Benchmark v1-clean: the SAME held-out set, sentinel records removed. ---
    held_out_clean = [r for r in held_out_records if not _is_sentinel(r)]
    removed_from_held_out = len(held_out_records) - len(held_out_clean)
    print(f"Benchmark v1-clean: {len(held_out_clean)} "
          f"(removed {removed_from_held_out} sentinel records from the held-out set)")

    fixed_sample_indices = random.Random(TRAIN_SAMPLE_SELECTION_SEED).sample(
        range(len(filtered_training_records)), min(TRAIN_SAMPLE_SIZE, len(filtered_training_records))
    )
    train_diagnostic_sample = [filtered_training_records[i] for i in fixed_sample_indices]

    OUTPUT_ROOT.mkdir(parents=True, exist_ok=True)
    config = TrainingConfig(
        hidden_width=HIDDEN_WIDTH, qa=QA, qb=QB, output_scale=OUTPUT_SCALE, k=K,
        learning_rate=LEARNING_RATE, seed=TRAINING_SEED, steps=STEPS, batch_size=BATCH_SIZE,
        lr_schedule=LR_SCHEDULE, warmup_steps=WARMUP_STEPS,
    )
    print(f"=== training Model B: steps={STEPS} lr={LEARNING_RATE} schedule={LR_SCHEDULE} "
          f"warmup={WARMUP_STEPS} seed={TRAINING_SEED} (P1-G04's frozen schedule, unchanged) ===")

    t0 = time.time()
    # Model B is evaluated against BOTH benchmarks post-hoc (below), so held_out_records here
    # is the original (uncleaned) set purely for the training-time diagnostic trajectory --
    # matching every prior experiment's convention, not a data leak (held_out_records was never
    # part of filtered_training_records).
    result = train(
        config, filtered_training_records, checkpoint_path=OUTPUT_ROOT / "final.pt",
        held_out_records=held_out_records, log_interval=max(1, STEPS // 20),
        train_diagnostic_sample=train_diagnostic_sample, checkpoint_dir=OUTPUT_ROOT / "checkpoints",
    )
    wall_clock_seconds = time.time() - t0

    diagnostics = [dataclasses.asdict(d) for d in result["diagnostics"]]
    (OUTPUT_ROOT / "training_diagnostics.json").write_text(json.dumps(diagnostics, indent=2))

    best_step, best_checkpoint_path = _select_best_checkpoint(diagnostics)
    print(f"Model B selected_step={best_step}/{STEPS - 1}  wall_clock={wall_clock_seconds:.1f}s")

    model_b = _load_model(best_checkpoint_path)
    model_a = _load_model(P1_G04_CHECKPOINT)

    matrix = {
        "model_a_on_v1": _eval_cell(model_a, held_out_records),
        "model_a_on_v1_clean": _eval_cell(model_a, held_out_clean),
        "model_b_on_v1": _eval_cell(model_b, held_out_records),
        "model_b_on_v1_clean": _eval_cell(model_b, held_out_clean),
    }

    print("\n=== 2x2 evaluation matrix ===")
    print(f"{'Model':<20}{'Benchmark':<16}{'n':>6}{'Correlation':>14}{'RMSE':>10}{'Bias':>10}{'Compression':>13}")
    for label, key in [("P1-G04 (Model A)", "model_a"), ("Filtered Retrain (Model B)", "model_b")]:
        for bench_label, bench_key in [("v1 (original)", "v1"), ("v1-clean", "v1_clean")]:
            c = matrix[f"{key}_on_{bench_key}"]
            print(f"{label:<20}{bench_label:<16}{c['n']:>6}{c['correlation']:>14.4f}"
                  f"{c['rmse']:>10.1f}{c['bias']:>10.1f}{c['compression_ratio']:>13.4f}")

    summary = {
        "experiment_id": EXPERIMENT_ID,
        "filtering_criterion": "eval_cp in {-9605, 9605, 20000}",
        "filter_manifest": filter_manifest,
        "removed_from_training_split": removed_from_training,
        "removed_from_held_out_split": removed_from_held_out,
        "training_records": len(filtered_training_records),
        "steps": STEPS, "learning_rate": LEARNING_RATE, "lr_schedule": LR_SCHEDULE,
        "warmup_steps": WARMUP_STEPS, "seed": TRAINING_SEED,
        "wall_clock_seconds": wall_clock_seconds,
        "selected_step": best_step, "final_step": STEPS - 1,
        "matrix": matrix,
    }
    (OUTPUT_ROOT / "summary.json").write_text(json.dumps(summary, indent=2))
    print(f"\nExperiment 3A complete. Summary written to {OUTPUT_ROOT / 'summary.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
