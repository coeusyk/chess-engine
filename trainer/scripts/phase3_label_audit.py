"""Phase 3 (research doc §30-31): label-quality audit over the exact corpus P1-G04
was trained on (Stage 1 20k Lichess + Stage 2 20k Stockfish-labeled, 90/10 split,
seed=42 -- the same `combine_and_split` call every promoted/rejected checkpoint in
this roadmap has used). Analytical only: no training, no code under `trainer/` is
modified by this script or as a result of running it.

Reuses existing infrastructure rather than re-deriving it:
- `deduplicate`/`phase_of` (transform.py, #193) for duplicate rate and phase mix --
  already run combined by `report_dataset_quality` as a side effect of every
  `combine_and_split` call; this script re-derives the *per-source* split of those
  same numbers, which the existing report only prints combined.
- `combine_and_split` itself, called with the same (stage1_dir, stage2_dir, seed) as
  every prior experiment, to get the *exact* P1-G04 36k/4k split for the leakage
  check and the bucketed error analysis (loaded from the promoted P1-G04 checkpoint,
  no retraining).
- `encode_batch`/`target_cp`/`texel_sigmoid` (the same primitives
  `evaluate_held_out`/`calibration_report` use) for the bucketed error analysis,
  extended with cp-magnitude and search-depth buckets the existing mate/cp/phase
  split doesn't cover.

No new third-party dependency: histograms are computed with numpy (already a hard
dependency) and rendered as fixed-width ASCII bars in the printed report, not images.
"""

from __future__ import annotations

import json
import math
from collections import Counter
from pathlib import Path
from typing import List, Optional

import numpy as np
import torch

from trainer.contracts import PositionRecord, ShardRef
from trainer.dataset import deduplicate, phase_of
from trainer.dataset.mmap_shard import read_shard
from trainer.dataset.stockfish_provider import StockfishLabeledProvider
from trainer.dataset.text_provider import TextDatasetProvider
from trainer.model.batching import encode_batch
from trainer.model.network import NnueNet
from trainer.model.train import MATE_EQUIVALENT_CP, target_cp, texel_sigmoid
from scripts.train_candidate_net import combine_and_split, _load_all

HIDDEN_WIDTH = 256
QA = 127
QB = 64
OUTPUT_SCALE = 400
K = 2.773456

STAGE1_DIR = Path("outputs/datasets/stage1-lichess")
STAGE2_DIR = Path("outputs/datasets/stage2-quiet-sf")
SPLIT_SEED = 42

P1_G04_CHECKPOINT = Path("outputs/phase1/P1-G04/checkpoints/step-015999.pt")

OUTPUT_PATH = Path("outputs/phase3/label-audit.json")


def _load_stage1() -> List[PositionRecord]:
    provider = TextDatasetProvider(STAGE1_DIR, identifier="stage1-lichess-evals-2026-07-15", source_ref=str(STAGE1_DIR))
    return _load_all(provider)


def _load_stage2() -> List[PositionRecord]:
    provider = StockfishLabeledProvider(STAGE2_DIR, identifier="stage2-sf-labeled-quiet-2026-07-15", source_ref=str(STAGE2_DIR))
    return _load_all(provider)


def _cp_values(records: List[PositionRecord]) -> np.ndarray:
    return np.array([r.label.eval_cp for r in records if r.label.eval_cp is not None], dtype=np.float64)


def _skew_kurtosis(x: np.ndarray) -> tuple[float, float]:
    if len(x) < 2:
        return float("nan"), float("nan")
    mean, std = x.mean(), x.std(ddof=0)
    if std == 0:
        return float("nan"), float("nan")
    z = (x - mean) / std
    skew = float(np.mean(z**3))
    kurtosis = float(np.mean(z**4) - 3.0)  # excess kurtosis (normal = 0)
    return skew, kurtosis


def _ascii_histogram(x: np.ndarray, bins: int, label_fmt: str = "{:.0f}", width: int = 40) -> str:
    if len(x) == 0:
        return "  (no data)"
    counts, edges = np.histogram(x, bins=bins)
    max_count = counts.max() if counts.max() > 0 else 1
    lines = []
    for i, count in enumerate(counts):
        bar = "#" * int(width * count / max_count)
        lo, hi = edges[i], edges[i + 1]
        lines.append(f"  [{label_fmt.format(lo):>8}, {label_fmt.format(hi):>8}) {count:>6} {bar}")
    return "\n".join(lines)


def cp_distribution_report(name: str, records: List[PositionRecord]) -> dict:
    cp = _cp_values(records)
    skew, kurtosis = _skew_kurtosis(cp)
    report = {
        "name": name,
        "count": int(len(cp)),
        "mean": float(cp.mean()) if len(cp) else float("nan"),
        "median": float(np.median(cp)) if len(cp) else float("nan"),
        "variance": float(cp.var(ddof=0)) if len(cp) else float("nan"),
        "std": float(cp.std(ddof=0)) if len(cp) else float("nan"),
        "skew": skew,
        "kurtosis": kurtosis,
        "min": float(cp.min()) if len(cp) else float("nan"),
        "max": float(cp.max()) if len(cp) else float("nan"),
    }
    print(f"\n--- cp distribution: {name} (n={report['count']}) ---")
    print(f"mean={report['mean']:.1f}  median={report['median']:.1f}  std={report['std']:.1f}  "
          f"skew={report['skew']:.3f}  kurtosis={report['kurtosis']:.3f}  "
          f"min={report['min']:.0f}  max={report['max']:.0f}")
    print(_ascii_histogram(np.clip(cp, -1000, 1000), bins=20))
    return report


def mate_distribution_report(name: str, records: List[PositionRecord]) -> dict:
    mate_records = [r for r in records if r.label.eval_mate is not None]
    total = len(records)
    mate_count = len(mate_records)
    mate_plies = [r.label.eval_mate for r in mate_records]
    positive = sum(1 for m in mate_plies if m > 0)
    negative = sum(1 for m in mate_plies if m < 0)
    depth_counts = Counter(abs(m) for m in mate_plies)
    report = {
        "name": name,
        "total": total,
        "mate_count": mate_count,
        "mate_pct": 100.0 * mate_count / total if total else 0.0,
        "positive_mate_count": positive,
        "negative_mate_count": negative,
        "mate_depth_distribution": dict(sorted(depth_counts.items())),
    }
    print(f"\n--- mate distribution: {name} ---")
    print(f"mate labels: {mate_count}/{total} ({report['mate_pct']:.2f}%)  "
          f"positive(mover-mates)={positive}  negative(mover-mated)={negative}")
    if depth_counts:
        print("mate-depth (plies) histogram:", dict(sorted(depth_counts.items())))
    return report


def near_equality_report(name: str, records: List[PositionRecord]) -> dict:
    cp = _cp_values(records)
    total_cp_labeled = len(cp)
    thresholds = [10, 25, 50]
    result = {"name": name, "total_cp_labeled": total_cp_labeled}
    for t in thresholds:
        count = int(np.sum(np.abs(cp) <= t))
        pct = 100.0 * count / total_cp_labeled if total_cp_labeled else 0.0
        result[f"within_{t}cp_count"] = count
        result[f"within_{t}cp_pct"] = pct
    print(f"\n--- near-equality: {name} ---")
    for t in thresholds:
        print(f"  within +/-{t}cp: {result[f'within_{t}cp_count']} ({result[f'within_{t}cp_pct']:.2f}%)")
    return result


def extremes_report(name: str, records: List[PositionRecord]) -> dict:
    cp = _cp_values(records)
    if len(cp) == 0:
        return {"name": name, "count": 0}
    abs_cp = np.abs(cp)
    p99 = float(np.percentile(abs_cp, 99))
    max_abs = float(abs_cp.max())
    at_max = int(np.sum(abs_cp == max_abs))
    # outliers via a simple IQR rule (no scipy dependency)
    q1, q3 = np.percentile(cp, 25), np.percentile(cp, 75)
    iqr = q3 - q1
    lo, hi = q1 - 3 * iqr, q3 + 3 * iqr
    outlier_count = int(np.sum((cp < lo) | (cp > hi)))
    result = {
        "name": name,
        "count": len(cp),
        "p99_abs_cp": p99,
        "max_abs_cp": max_abs,
        "count_at_max_abs_cp": at_max,
        "iqr_outlier_count": outlier_count,
        "iqr_outlier_pct": 100.0 * outlier_count / len(cp),
    }
    print(f"\n--- extremes: {name} ---")
    print(f"p99(|cp|)={p99:.0f}  max(|cp|)={max_abs:.0f} (n at max={at_max})  "
          f"IQR-outliers(k=3): {outlier_count} ({result['iqr_outlier_pct']:.2f}%)")
    return result


def search_info_report(name: str, records: List[PositionRecord]) -> dict:
    depths = [r.metadata.search_depth for r in records if r.metadata.search_depth is not None]
    nodes = [r.metadata.search_nodes for r in records if r.metadata.search_nodes is not None]
    result = {
        "name": name,
        "total": len(records),
        "has_search_depth_count": len(depths),
        "has_search_nodes_count": len(nodes),
        "multipv_field_exists_in_schema": False,
        "termination_reason_field_exists_in_schema": False,
    }
    if depths:
        result["search_depth_min"] = int(min(depths))
        result["search_depth_max"] = int(max(depths))
        result["search_depth_mean"] = float(sum(depths) / len(depths))
        result["search_depth_distribution"] = dict(sorted(Counter(depths).items()))
    if nodes:
        result["search_nodes_distinct_values"] = sorted(set(nodes))
    print(f"\n--- search info: {name} ---")
    print(f"has_search_depth: {len(depths)}/{len(records)}  has_search_nodes: {len(nodes)}/{len(records)}")
    if depths:
        print(f"search_depth: min={result['search_depth_min']} max={result['search_depth_max']} "
              f"mean={result['search_depth_mean']:.2f}")
    if nodes:
        print(f"search_nodes distinct values: {result['search_nodes_distinct_values']}")
    print("multipv field: absent from PositionMetadata schema (structurally unavailable, not merely unpopulated)")
    print("termination_reason field: absent from PositionMetadata schema (structurally unavailable)")
    return result


def quantization_report(name: str, records: List[PositionRecord]) -> dict:
    cp = _cp_values(records).astype(np.int64)
    if len(cp) == 0:
        return {"name": name}
    divisors_checked = [2, 5, 10, 25, 50]
    divisibility = {d: float(100.0 * np.mean(cp % d == 0)) for d in divisors_checked}
    result = {"name": name, "count": len(cp), "divisibility_pct": divisibility}
    print(f"\n--- quantization check: {name} ---")
    for d, pct in divisibility.items():
        print(f"  divisible by {d}: {pct:.1f}%  (uniform-random baseline: {100.0/d:.1f}%)")
    return result


def mate_discontinuity_report(records: List[PositionRecord]) -> dict:
    cp = _cp_values(records)
    if len(cp) == 0:
        return {}
    max_abs_cp = float(np.abs(cp).max())
    gap = MATE_EQUIVALENT_CP - max_abs_cp
    result = {
        "mate_equivalent_cp": MATE_EQUIVALENT_CP,
        "max_abs_cp_label": max_abs_cp,
        "gap_to_mate_equivalent": gap,
    }
    print(f"\n--- mate-score discontinuity ---")
    print(f"MATE_EQUIVALENT_CP={MATE_EQUIVALENT_CP:.0f}  max |eval_cp| label seen={max_abs_cp:.0f}  "
          f"gap={gap:.0f}cp (every mate label jumps straight to +/-{MATE_EQUIVALENT_CP:.0f} "
          f"regardless of mate distance)")
    return result


def duplicate_and_leakage_report(stage1: List[PositionRecord], stage2: List[PositionRecord]) -> dict:
    combined = stage1 + stage2
    unique_combined = list(deduplicate(combined))
    dup_combined = len(combined) - len(unique_combined)

    unique_stage1 = list(deduplicate(stage1))
    dup_stage1 = len(stage1) - len(unique_stage1)
    unique_stage2 = list(deduplicate(stage2))
    dup_stage2 = len(stage2) - len(unique_stage2)

    stage1_fens = {r.fen for r in stage1}
    stage2_fens = {r.fen for r in stage2}
    cross_stage_overlap = stage1_fens & stage2_fens

    # Exact-duplicate FENs (anywhere in the combined 40k) carrying different labels.
    by_fen: dict[str, list] = {}
    for r in combined:
        by_fen.setdefault(r.fen, []).append(r.label)
    inconsistent_fens = 0
    for fen, labels in by_fen.items():
        if len(labels) < 2:
            continue
        cps = {l.eval_cp for l in labels if l.eval_cp is not None}
        mates = {l.eval_mate for l in labels if l.eval_mate is not None}
        if len(cps) > 1 or len(mates) > 1 or (cps and mates):
            inconsistent_fens += 1

    training_records, held_out_records, _ = combine_and_split(STAGE1_DIR, STAGE2_DIR, SPLIT_SEED)
    train_fens = {r.fen for r in training_records}
    held_out_fens = {r.fen for r in held_out_records}
    leak = train_fens & held_out_fens

    result = {
        "combined_total": len(combined),
        "combined_duplicate_count": dup_combined,
        "combined_duplicate_pct": 100.0 * dup_combined / len(combined),
        "stage1_duplicate_count": dup_stage1,
        "stage1_duplicate_pct": 100.0 * dup_stage1 / len(stage1) if stage1 else 0.0,
        "stage2_duplicate_count": dup_stage2,
        "stage2_duplicate_pct": 100.0 * dup_stage2 / len(stage2) if stage2 else 0.0,
        "cross_stage_fen_overlap_count": len(cross_stage_overlap),
        "inconsistent_label_fen_count": inconsistent_fens,
        "train_count": len(training_records),
        "held_out_count": len(held_out_records),
        "train_held_out_fen_overlap_count": len(leak),
    }
    print("\n--- duplicate / leakage report ---")
    print(f"combined 40k: {dup_combined} duplicates ({result['combined_duplicate_pct']:.3f}%)")
    print(f"  stage1-only: {dup_stage1} ({result['stage1_duplicate_pct']:.3f}%)  "
          f"stage2-only: {dup_stage2} ({result['stage2_duplicate_pct']:.3f}%)")
    print(f"cross-stage FEN overlap (same FEN in both Stage1 and Stage2): {len(cross_stage_overlap)}")
    print(f"FENs with inconsistent labels across duplicate occurrences: {inconsistent_fens}")
    print(f"train/held-out FEN overlap (leakage): {len(leak)} "
          f"(train={len(training_records)}, held_out={len(held_out_records)})")
    return result


def bucketed_error_report(model: NnueNet, held_out_records: List[PositionRecord]) -> dict:
    batch = encode_batch(held_out_records)
    target_cps = torch.tensor([target_cp(r.label) for r in held_out_records], dtype=torch.float32)
    with torch.no_grad():
        predicted_cp = model(batch.us_indices, batch.us_offsets, batch.them_indices, batch.them_offsets)
    residual = predicted_cp - target_cps
    is_mate = torch.tensor([r.label.eval_mate is not None for r in held_out_records], dtype=torch.bool)

    def bucket_stats(mask: torch.Tensor) -> dict:
        count = int(mask.sum().item())
        if count == 0:
            return {"count": 0, "mae": float("nan"), "rmse": float("nan"), "bias": float("nan")}
        res = residual[mask]
        return {
            "count": count,
            "mae": float(res.abs().mean().item()),
            "rmse": float(torch.sqrt((res**2).mean()).item()),
            "bias": float(res.mean().item()),
        }

    abs_target = target_cps.abs()
    buckets = {
        "near_zero_leq25cp": bucket_stats((~is_mate) & (abs_target <= 25)),
        "moderate_25_200cp": bucket_stats((~is_mate) & (abs_target > 25) & (abs_target <= 200)),
        "large_200_800cp": bucket_stats((~is_mate) & (abs_target > 200) & (abs_target <= 800)),
        "extreme_gt800cp": bucket_stats((~is_mate) & (abs_target > 800)),
        "mate_all": bucket_stats(is_mate),
        "mate_favoring_mover": bucket_stats(is_mate & (target_cps > 0)),
        "mate_against_mover": bucket_stats(is_mate & (target_cps < 0)),
    }

    # Search-depth-reached bucket, as a tactical-complexity proxy (Stage 2 records
    # only -- Stage 1 has no search_depth metadata at all, per search_info_report).
    depths = [r.metadata.search_depth for r in held_out_records]
    has_depth = torch.tensor([d is not None for d in depths], dtype=torch.bool)
    if has_depth.any():
        depth_values = torch.tensor([d if d is not None else -1 for d in depths], dtype=torch.float32)
        median_depth = float(depth_values[has_depth].median().item())
        buckets["stage2_shallow_depth"] = bucket_stats(has_depth & (depth_values <= median_depth))
        buckets["stage2_deep_depth"] = bucket_stats(has_depth & (depth_values > median_depth))
        buckets["_stage2_median_depth_reached"] = median_depth

    print("\n--- bucketed error analysis (P1-G04 on the 4,000-record held-out set) ---")
    for name, stats in buckets.items():
        if name.startswith("_"):
            continue
        if stats.get("count", 0) == 0:
            print(f"  {name:>24}: n=0")
            continue
        print(f"  {name:>24}: n={stats['count']:>5}  MAE={stats['mae']:>8.1f}  "
              f"RMSE={stats['rmse']:>8.1f}  bias={stats['bias']:>8.1f}")
    return buckets


def main() -> int:
    print("=== Phase 3 label-quality audit (analytical only, no training) ===")
    stage1 = _load_stage1()
    stage2 = _load_stage2()
    print(f"loaded stage1={len(stage1)} stage2={len(stage2)}")

    report: dict = {"stage1_count": len(stage1), "stage2_count": len(stage2)}

    report["cp_distribution"] = {
        "stage1": cp_distribution_report("Stage 1 (Lichess)", stage1),
        "stage2": cp_distribution_report("Stage 2 (Stockfish)", stage2),
        "combined": cp_distribution_report("Combined", stage1 + stage2),
    }
    report["mate_distribution"] = {
        "stage1": mate_distribution_report("Stage 1 (Lichess)", stage1),
        "stage2": mate_distribution_report("Stage 2 (Stockfish)", stage2),
        "combined": mate_distribution_report("Combined", stage1 + stage2),
    }
    report["near_equality"] = {
        "stage1": near_equality_report("Stage 1 (Lichess)", stage1),
        "stage2": near_equality_report("Stage 2 (Stockfish)", stage2),
        "combined": near_equality_report("Combined", stage1 + stage2),
    }
    report["extremes"] = {
        "stage1": extremes_report("Stage 1 (Lichess)", stage1),
        "stage2": extremes_report("Stage 2 (Stockfish)", stage2),
        "combined": extremes_report("Combined", stage1 + stage2),
    }
    report["search_info"] = {
        "stage1": search_info_report("Stage 1 (Lichess)", stage1),
        "stage2": search_info_report("Stage 2 (Stockfish)", stage2),
    }
    report["quantization"] = {
        "stage1": quantization_report("Stage 1 (Lichess)", stage1),
        "stage2": quantization_report("Stage 2 (Stockfish)", stage2),
    }
    report["mate_discontinuity"] = mate_discontinuity_report(stage1 + stage2)
    report["duplicate_and_leakage"] = duplicate_and_leakage_report(stage1, stage2)

    print("\nloading P1-G04 checkpoint for bucketed error analysis (inference only, no training) ...")
    checkpoint = torch.load(P1_G04_CHECKPOINT, weights_only=False)
    model = NnueNet(HIDDEN_WIDTH, QA, QB, OUTPUT_SCALE)
    model.load_state_dict(checkpoint["model_state_dict"])
    _, held_out_records, _ = combine_and_split(STAGE1_DIR, STAGE2_DIR, SPLIT_SEED)
    report["bucketed_error"] = bucketed_error_report(model, held_out_records)

    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT_PATH.write_text(json.dumps(report, indent=2, default=str))
    print(f"\nFull report written to {OUTPUT_PATH}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
