"""Experiment P4I (research doc §46, `docs/architecture/research/nnue/`): coarse-to-fine
retrain ablation of the texel-sigmoid loss's `K` parameter -- Stage 1 (exactly three points:
baseline, low endpoint, high endpoint; NOT a dense sweep, per this task's explicit instruction).

**Scope, per this task's explicit constraint list**: only `K` (`TrainingConfig.k`, consumed
exclusively by `texel_sigmoid()`, `trainer/model/train.py:99`) is varied. Architecture
(`hidden_width`/`qa`/`qb`/`output_scale`), features (plain-768, unchanged), dataset (Stage 1/
Stage 2 identity and split), optimizer (Adam, untouched), checkpointing (`train()`'s own
mechanism, unmodified), export, and quantization are all held fixed and are not touched by this
script -- `network.py`, `batching.py`, `quantizer.py`, `canonical.py`, `exporter.py`,
`contracts/dataset.py` are not imported for modification, only (transitively) for evaluation.

**Baseline is reused, not retrained**: `trainer/outputs/phase3/P3A-001/checkpoints/
step-016999.pt` (Experiment 3A, research doc §35) is *exactly* the "baseline (current K)" arm
this design calls for -- P1-G04's frozen schedule, K=2.773456 (unchanged), trained on the
sentinel-filtered Stage 1 training set that research doc §35.9 adopted as permanent ingestion
hygiene. Retraining an identical configuration would be a duplicate, wasted run; the existing
checkpoint (and its already-computed matrix in `P3A-001/summary.json`) is reused directly.

**K grid (research doc §46.3's saturation-formula-derived approach, narrowed to this task's
tighter 20-25% band)**: `K_low`/`K_high` are the baseline scaled by -22.5%/+22.5% -- the midpoint
of the requested 20-25% band, chosen once and declared here rather than tuned after seeing
results, per this project's "declared, not tuned post-hoc" convention (research doc §33.1).

**Leakage-safe split methodology (identical to Experiment 3A, research doc §35.3, reused
verbatim)**: `combine_and_split(seed=42)` reproduces P1-G04's exact 36,000/4,000 split; the
sentinel filter is applied to the *already-split* training list only, so Benchmark v1 (the
held-out 4,000) stays byte-identical across every model in this experiment and every prior one.
"""

from __future__ import annotations

import dataclasses
import json
import math
import random
import time
from pathlib import Path
from typing import Dict, List

import torch

from trainer.contracts import PositionRecord
from trainer.model.network import NnueNet
from trainer.model.train import TrainingConfig, train
from trainer.validation.validator import calibration_report, evaluate_held_out
from scripts.train_candidate_net import combine_and_split

HIDDEN_WIDTH = 256
QA = 127
QB = 64
OUTPUT_SCALE = 400

K_BASE = 2.773456
K_PCT = 0.225  # midpoint of this task's requested "approximately 20-25%" band
K_LOW = round(K_BASE * (1 - K_PCT), 6)
K_HIGH = round(K_BASE * (1 + K_PCT), 6)

STAGE1_DIR = Path("outputs/datasets/stage1-lichess")
STAGE2_DIR = Path("outputs/datasets/stage2-quiet-sf")
SPLIT_SEED = 42

# Same confirmed sentinel-value set as Experiment 3A (research doc §32.4, corrected count) --
# the training corpus for every P4I arm is the sentinel-filtered set §35.9 adopted as hygiene.
SENTINEL_CP_VALUES = {-9605, 9605, 20000}

P3A_001_BASELINE_CHECKPOINT = Path("outputs/phase3/P3A-001/checkpoints/step-016999.pt")

# P1-G04's frozen schedule (research doc §27.11), unchanged -- the only independent variable in
# this experiment is K.
STEPS = 20000
LEARNING_RATE = 0.01
LR_SCHEDULE = "cosine"
WARMUP_STEPS = 200
TRAINING_SEED = 42
BATCH_SIZE = 256

TRAIN_SAMPLE_SELECTION_SEED = 999
TRAIN_SAMPLE_SIZE = 4000

# Measured seed-to-seed noise floor (research doc §27.2, n=3, one configuration -- 2,000 steps,
# constant LR -- not yet re-measured at this 20,000-step cosine schedule; used here as the best
# available estimate per that section's own "something concrete is better than an undefined
# 'looks better'" reasoning, with this generalization caveat carried forward explicitly).
NOISE_FLOOR_STD = 0.0019
MEANINGFUL_SIGMA = 3.0
CORRELATION_THRESHOLD = MEANINGFUL_SIGMA * NOISE_FLOOR_STD

OUTPUT_ROOT = Path("outputs/phase4") / "P4I"

# research doc §32.6's magnitude-bucket boundaries, reused verbatim for the extreme-evaluation
# check the promotion criteria require.
MAGNITUDE_BUCKETS = [
    ("near_zero", 0, 25),
    ("moderate", 25, 200),
    ("large", 200, 800),
    ("extreme", 800, float("inf")),
]

HISTOGRAM_EDGES = list(range(-1000, 1100, 100))  # clipped to [-1000, 1000], 100cp buckets


def _is_sentinel(record: PositionRecord) -> bool:
    return record.label.eval_cp in SENTINEL_CP_VALUES


def _load_model(checkpoint_path: Path) -> NnueNet:
    checkpoint = torch.load(checkpoint_path, weights_only=False)
    model = NnueNet(HIDDEN_WIDTH, QA, QB, OUTPUT_SCALE)
    model.load_state_dict(checkpoint["model_state_dict"])
    return model


def _select_best_checkpoint(diagnostics: list, output_root: Path) -> tuple[int, Path]:
    best_step, best_corr = None, float("-inf")
    for d in diagnostics:
        if d["held_out_correlation"] is not None and d["held_out_correlation"] >= best_corr:
            best_corr = d["held_out_correlation"]
            best_step = d["step"]
    return best_step, output_root / "checkpoints" / f"step-{best_step:06d}.pt"


def _predictions(model: NnueNet, records: List[PositionRecord]) -> tuple[torch.Tensor, torch.Tensor]:
    from trainer.model.batching import encode_batch
    from trainer.model.train import target_cp

    batch = encode_batch(records)
    target_cps = torch.tensor([target_cp(r.label) for r in records], dtype=torch.float32)
    with torch.no_grad():
        predicted_cp = model(batch.us_indices, batch.us_offsets, batch.them_indices, batch.them_offsets)
    return predicted_cp, target_cps


def _magnitude_buckets(model: NnueNet, records: List[PositionRecord]) -> Dict[str, dict]:
    """Non-mate records only, bucketed by |target_cp| per research doc §32.6's boundaries --
    the "extreme-evaluation behavior" the promotion criteria require a check against."""
    predicted_cp, target_cps = _predictions(model, records)
    is_mate = torch.tensor([r.label.eval_mate is not None for r in records], dtype=torch.bool)
    abs_target = target_cps.abs()

    out: Dict[str, dict] = {}
    for name, lo, hi in MAGNITUDE_BUCKETS:
        mask = (~is_mate) & (abs_target >= lo) & (abs_target < hi)
        count = int(mask.sum().item())
        if count == 0:
            out[name] = {"n": 0, "mae": None, "bias": None}
            continue
        residual = predicted_cp[mask] - target_cps[mask]
        out[name] = {
            "n": count,
            "mae": residual.abs().mean().item(),
            "bias": residual.mean().item(),
        }
    return out


def _histogram(values: torch.Tensor) -> Dict[str, int]:
    clipped = values.clamp(HISTOGRAM_EDGES[0], HISTOGRAM_EDGES[-1] - 1)
    counts: Dict[str, int] = {}
    for lo, hi in zip(HISTOGRAM_EDGES[:-1], HISTOGRAM_EDGES[1:]):
        mask = (clipped >= lo) & (clipped < hi)
        counts[f"[{lo},{hi})"] = int(mask.sum().item())
    return counts


def _eval_cell(model: NnueNet, records: List[PositionRecord], k: float) -> dict:
    held_out_eval = evaluate_held_out(model, records, k)
    cal = calibration_report(model, records)
    predicted_cp, _ = _predictions(model, records)
    return {
        "n": len(records),
        "correlation": held_out_eval.label_correlation,
        "held_out_loss": held_out_eval.held_out_loss,
        "overall": dataclasses.asdict(cal.overall),
        "mate_labeled": dataclasses.asdict(cal.mate_labeled),
        "cp_labeled": dataclasses.asdict(cal.cp_labeled),
        "magnitude_buckets": _magnitude_buckets(model, records),
        "prediction_histogram": _histogram(predicted_cp),
    }


def _train_arm(tag: str, k: float, training_records: List[PositionRecord],
                held_out_records: List[PositionRecord],
                train_diagnostic_sample: List[PositionRecord]) -> dict:
    output_root = OUTPUT_ROOT / tag
    output_root.mkdir(parents=True, exist_ok=True)

    config = TrainingConfig(
        hidden_width=HIDDEN_WIDTH, qa=QA, qb=QB, output_scale=OUTPUT_SCALE, k=k,
        learning_rate=LEARNING_RATE, seed=TRAINING_SEED, steps=STEPS, batch_size=BATCH_SIZE,
        lr_schedule=LR_SCHEDULE, warmup_steps=WARMUP_STEPS,
    )
    print(f"=== training {tag}: K={k} steps={STEPS} lr={LEARNING_RATE} schedule={LR_SCHEDULE} "
          f"warmup={WARMUP_STEPS} seed={TRAINING_SEED} (P1-G04's frozen schedule, K only varies) ===")

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
    print(f"{tag}: selected_step={best_step}/{STEPS - 1} wall_clock={wall_clock_seconds:.1f}s")

    return {
        "tag": tag, "k": k, "wall_clock_seconds": wall_clock_seconds,
        "selected_step": best_step, "final_step": STEPS - 1,
        "checkpoint_path": str(best_checkpoint_path),
    }


def main() -> int:
    print("=== Experiment P4I: coarse-to-fine K exploration (Stage 1, three points) ===")
    print(f"K_base={K_BASE}  K_low={K_LOW} ({(1 - K_LOW / K_BASE) * 100:.2f}% below)  "
          f"K_high={K_HIGH} ({(K_HIGH / K_BASE - 1) * 100:.2f}% above)")

    # --- Reproduce P1-G04's exact split (Benchmark v1), identical to every prior experiment. ---
    training_records, held_out_records, _ = combine_and_split(STAGE1_DIR, STAGE2_DIR, SPLIT_SEED)
    print(f"original split: training={len(training_records)} held_out={len(held_out_records)}")

    # --- Sentinel-filtered training corpus (research doc §35.9's adopted hygiene, reused). ---
    filtered_training_records = [r for r in training_records if not _is_sentinel(r)]
    removed_from_training = len(training_records) - len(filtered_training_records)
    print(f"filtered training set: {len(filtered_training_records)} "
          f"(removed {removed_from_training} sentinel records, matching P3A-001's methodology)")

    # --- Benchmark v1-clean: same held-out set, sentinel records removed. ---
    held_out_clean = [r for r in held_out_records if not _is_sentinel(r)]
    removed_from_held_out = len(held_out_records) - len(held_out_clean)
    print(f"Benchmark v1-clean: {len(held_out_clean)} (removed {removed_from_held_out})")

    fixed_sample_indices = random.Random(TRAIN_SAMPLE_SELECTION_SEED).sample(
        range(len(filtered_training_records)), min(TRAIN_SAMPLE_SIZE, len(filtered_training_records))
    )
    train_diagnostic_sample = [filtered_training_records[i] for i in fixed_sample_indices]

    OUTPUT_ROOT.mkdir(parents=True, exist_ok=True)

    # --- Baseline: reused from P3A-001, not retrained (see module docstring). ---
    baseline_model = _load_model(P3A_001_BASELINE_CHECKPOINT)
    arms_meta = {
        "baseline": {
            "tag": "baseline", "k": K_BASE, "reused_from": "P3A-001",
            "checkpoint_path": str(P3A_001_BASELINE_CHECKPOINT),
        }
    }
    models = {"baseline": baseline_model}

    # --- Low and high endpoints: trained here. ---
    low_meta = _train_arm("P4I-001-low", K_LOW, filtered_training_records, held_out_records,
                           train_diagnostic_sample)
    arms_meta["low"] = low_meta
    models["low"] = _load_model(Path(low_meta["checkpoint_path"]))

    high_meta = _train_arm("P4I-002-high", K_HIGH, filtered_training_records, held_out_records,
                            train_diagnostic_sample)
    arms_meta["high"] = high_meta
    models["high"] = _load_model(Path(high_meta["checkpoint_path"]))

    # --- Evaluation matrix: 3 models x 2 benchmarks. Each model evaluated with its OWN K
    # (texel-sigmoid loss/held-out-loss is K-dependent; correlation/RMSE/calibration/histograms
    # are computed on raw predicted_cp/target_cp and are not, per validator.py's own design). ---
    k_by_arm = {"baseline": K_BASE, "low": K_LOW, "high": K_HIGH}
    matrix = {}
    for arm, model in models.items():
        matrix[f"{arm}_on_v1"] = _eval_cell(model, held_out_records, k_by_arm[arm])
        matrix[f"{arm}_on_v1_clean"] = _eval_cell(model, held_out_clean, k_by_arm[arm])

    print("\n=== evaluation matrix (correlation / RMSE / bias / compression) ===")
    print(f"{'Arm':<10}{'K':>10}{'Benchmark':<14}{'n':>6}{'Correlation':>14}{'RMSE':>10}"
          f"{'Bias':>10}{'Compression':>13}")
    for arm in ("baseline", "low", "high"):
        for bench_label, bench_key in [("v1", "v1"), ("v1-clean", "v1_clean")]:
            c = matrix[f"{arm}_on_{bench_key}"]
            print(f"{arm:<10}{k_by_arm[arm]:>10.4f}{bench_label:<14}{c['n']:>6}"
                  f"{c['correlation']:>14.4f}{c['overall']['rmse']:>10.1f}"
                  f"{c['overall']['signed_mean_error']:>10.1f}{c['overall']['compression_ratio']:>13.4f}")

    summary = {
        "experiment_id": "P4I", "k_base": K_BASE, "k_low": K_LOW, "k_high": K_HIGH,
        "k_pct_band": K_PCT, "removed_from_training_split": removed_from_training,
        "removed_from_held_out_split": removed_from_held_out,
        "training_records": len(filtered_training_records),
        "steps": STEPS, "learning_rate": LEARNING_RATE, "lr_schedule": LR_SCHEDULE,
        "warmup_steps": WARMUP_STEPS, "seed": TRAINING_SEED,
        "noise_floor_std": NOISE_FLOOR_STD, "meaningful_sigma": MEANINGFUL_SIGMA,
        "correlation_threshold": CORRELATION_THRESHOLD,
        "arms": arms_meta, "matrix": matrix,
    }
    (OUTPUT_ROOT / "summary.json").write_text(json.dumps(summary, indent=2))
    print(f"\nExperiment P4I training+evaluation complete. Summary: {OUTPUT_ROOT / 'summary.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
