"""Experiment RQ-5 / Experiment 5 (research doc §39 RQ-5, §6 Exp. 5; Phase 5 roadmap
candidate #1, `docs/architecture/research/nnue/phase5-roadmap.md`): label budget-sensitivity
measurement, not a trained-model intervention.

**What this measures, precisely.** Single-threaded, fixed-node Stockfish search is
deterministic (same FEN, same node budget, same persistent-engine hash state -> same eval),
so re-running one budget twice would reproduce the same numbers. What this script measures
is **budget-sensitivity**: how much a position's eval moves between a 25,000-node and a
50,000-node search -- a convergence/bias proxy for how far from "settled" the production
Stage 2 label (25,000 nodes) is, not run-to-run statistical noise in the usual sense.

**Reconciling with the research doc's own §32.5 duplicate-FEN finding** (two occurrences of
the same FEN in the existing corpus carrying different labels): that is a *different*
mechanism from budget-sensitivity. `stockfish_label.py`'s `UciEngine` never sends
`ucinewgame` between positions (matching production Stage 2 labeling exactly -- left
unchanged here per this task's "diagnostic, keep production behavior unchanged" rule), so
the persistent engine's transposition-table/hash state carries over from whatever position
preceded it. Two identical FENs at different points in an input stream can therefore get
different evals purely from carryover context, independent of node budget. This script does
not attempt to isolate that channel (a same-budget replication arm would be a second
independent variable, breaking the one-variable-at-a-time discipline,
`measurement-model.md` Section 9) -- it is noted as a limitation, not measured here. Both
budget arms run over the *identical* FEN sequence in the *identical* order, so both are
subject to the same carryover context, keeping the comparison apples-to-apples on the one
variable that *is* varied (node budget).

**Position sample.** Reconstructs the same 20,000-FEN Stage 2 source population via the
documented every-36th-line convention (`trainer/configs/stockfish-label-e2-real.md`) applied
to `data/quiet-labeled.epd`, then takes an evenly-spaced 1,000-position sub-sample
(every 20th of the 20,000) -- deterministic and representative of the Stage 2 population,
not claimed to be byte-identical to the specific 20,000-record shard (not present in this
checkout; data artifacts are gitignored, `trainer/configs/stockfish-label-e2-real.md`).

**Independent variable**: Stockfish node budget only (25,000 vs 50,000), fixed position
sample, fixed engine, fixed thread count (1, driver-hardcoded) -- no trainer/model code is
touched, no labels are changed, no dataset or checkpoint is modified (research doc Section 39
RQ-5's own explicit scope).

**Pre-registered interpretation thresholds** (declared before running, per
`measurement-model.md` Section 9's "declare, then report" discipline): primary metric is the
median `|eval_25k - eval_50k|` in cp over cp-labeled (mode-matched) pairs.
- **>= 25cp: material.** At or above the `near_zero` magnitude-bucket boundary
  (`phase4_p4i_k_sweep.py`'s `MAGNITUDE_BUCKETS`, reused here) already used throughout this
  roadmap as the finest distinction band the model is asked to resolve -- reprioritize toward
  label stability/higher node budget over further loss/target work.
- **< 10cp: negligible.** Strengthens the case that Phase 4-style objective levers, not
  label re-collection, remain the right next investment.
- **10-25cp: threshold-contingent, disclosed as such** -- not resolved by picking whichever
  bar is convenient (P4I's own precedent for an ambiguous result).
"""

from __future__ import annotations

import json
import re
import statistics
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional

from scripts.stockfish_label import StockfishLabelConfig, label_positions
from trainer.dataset.mmap_shard import read_shard

SOURCE_EPD = Path("../data/quiet-labeled.epd")  # repo-root data/, run from trainer/ (backfill_stage2_wdl.py's own convention)
STAGE2_SAMPLE_STRIDE = 36  # matches the documented Stage 2 input convention
SUB_SAMPLE_SIZE = 1000
SUB_SAMPLE_STRIDE = 20  # 20,000 / 1,000 -- evenly spaced across the Stage 2 population

ENGINE_PATH = Path("/home/coeusyk/.local/bin/stockfish")
NODES_LOW = 25000
NODES_HIGH = 50000

OUTPUT_ROOT = Path("outputs/phase5") / "RQ5-001"

MAGNITUDE_BUCKETS = [
    ("near_zero", 0, 25),
    ("moderate", 25, 200),
    ("large", 200, 800),
    ("extreme", 800, float("inf")),
]

MATERIAL_THRESHOLD_CP = 25.0
NEGLIGIBLE_THRESHOLD_CP = 10.0

_C9_SUFFIX = re.compile(r"\s+c9\s+\"[^\"]*\";?\s*$")


def _extract_fen(epd_line: str) -> Optional[str]:
    """Strips a trailing `c9 "..."` opcode from one `data/quiet-labeled.epd` line,
    returning the plain 4-field FEN (piece placement/side/castling/en-passant) --
    Stockfish accepts this directly via `position fen`, defaulting the omitted
    halfmove/fullmove counters."""
    line = epd_line.strip()
    if not line:
        return None
    fen = _C9_SUFFIX.sub("", line).strip()
    return fen or None


def stage2_population(epd_path: Path, stride: int = STAGE2_SAMPLE_STRIDE) -> List[str]:
    fens: List[str] = []
    with open(epd_path, "r", encoding="ascii") as f:
        for i, line in enumerate(f):
            if i % stride != 0:
                continue
            fen = _extract_fen(line)
            if fen is not None:
                fens.append(fen)
    return fens


def fixed_subsample(population: List[str], size: int = SUB_SAMPLE_SIZE,
                     stride: int = SUB_SAMPLE_STRIDE) -> List[str]:
    sample = population[::stride][:size]
    if len(sample) != size:
        raise ValueError(f"expected {size} positions, got {len(sample)} (population size {len(population)})")
    return sample


def magnitude_bucket(abs_cp: float) -> str:
    for name, lo, hi in MAGNITUDE_BUCKETS:
        if lo <= abs_cp < hi:
            return name
    return MAGNITUDE_BUCKETS[-1][0]


@dataclass(frozen=True)
class SpreadResult:
    matched_cp_pairs: int
    mode_mismatch_count: int
    both_mate_count: int
    cp_spread: List[float]
    mate_ply_spread: List[int]


def compute_spread(low_records, high_records) -> SpreadResult:
    """Matches records by position (both lists are the same fixed-order FEN sequence,
    same length) and classifies each pair by label mode before diffing -- a cp eval and
    a mate eval are not on a comparable scale, so a mode mismatch is counted and
    reported separately (research doc Section 39 RQ-5's own "secondary: whether spread
    differs by ... bucket" -- mode mismatch is exactly this roadmap's phase-dependent
    breakdown, not folded into the primary cp spread)."""
    if len(low_records) != len(high_records):
        raise ValueError(f"record count mismatch: {len(low_records)} vs {len(high_records)}")

    cp_spread: List[float] = []
    mate_ply_spread: List[int] = []
    mode_mismatch = 0
    both_mate = 0

    for low, high in zip(low_records, high_records):
        low_is_mate = low.label.eval_mate is not None
        high_is_mate = high.label.eval_mate is not None
        if low_is_mate != high_is_mate:
            mode_mismatch += 1
            continue
        if low_is_mate and high_is_mate:
            both_mate += 1
            mate_ply_spread.append(abs(low.label.eval_mate - high.label.eval_mate))
            continue
        if low.label.eval_cp is None or high.label.eval_cp is None:
            mode_mismatch += 1
            continue
        cp_spread.append(abs(low.label.eval_cp - high.label.eval_cp))

    return SpreadResult(
        matched_cp_pairs=len(cp_spread),
        mode_mismatch_count=mode_mismatch,
        both_mate_count=both_mate,
        cp_spread=cp_spread,
        mate_ply_spread=mate_ply_spread,
    )


def _percentile(values: List[float], pct: float) -> Optional[float]:
    if not values:
        return None
    ordered = sorted(values)
    idx = min(len(ordered) - 1, int(round(pct / 100.0 * (len(ordered) - 1))))
    return ordered[idx]


def summarize(result: SpreadResult, low_records) -> Dict:
    spread = result.cp_spread
    bucket_counts: Dict[str, int] = {name: 0 for name, _, _ in MAGNITUDE_BUCKETS}
    bucket_medians: Dict[str, List[float]] = {name: [] for name, _, _ in MAGNITUDE_BUCKETS}
    for low, diff in zip((r for r in low_records if r.label.eval_mate is None), spread):
        bucket = magnitude_bucket(abs(low.label.eval_cp))
        bucket_counts[bucket] += 1
        bucket_medians[bucket].append(diff)

    median = statistics.median(spread) if spread else None
    verdict = "insufficient_data"
    if median is not None:
        if median >= MATERIAL_THRESHOLD_CP:
            verdict = "material"
        elif median < NEGLIGIBLE_THRESHOLD_CP:
            verdict = "negligible"
        else:
            verdict = "threshold_contingent"

    return {
        "matched_cp_pairs": result.matched_cp_pairs,
        "mode_mismatch_count": result.mode_mismatch_count,
        "both_mate_count": result.both_mate_count,
        "cp_spread": {
            "mean": statistics.mean(spread) if spread else None,
            "median": median,
            "stdev": statistics.pstdev(spread) if len(spread) > 1 else None,
            "p90": _percentile(spread, 90),
            "p99": _percentile(spread, 99),
            "max": max(spread) if spread else None,
        },
        "mate_ply_spread": {
            "n": len(result.mate_ply_spread),
            "mean": statistics.mean(result.mate_ply_spread) if result.mate_ply_spread else None,
        },
        "magnitude_buckets": {
            name: {
                "n": bucket_counts[name],
                "median_spread_cp": statistics.median(vals) if vals else None,
            }
            for name, vals in bucket_medians.items()
        },
        "pre_registered_thresholds": {
            "material_cp": MATERIAL_THRESHOLD_CP,
            "negligible_cp": NEGLIGIBLE_THRESHOLD_CP,
        },
        "verdict": verdict,
    }


def main() -> int:
    OUTPUT_ROOT.mkdir(parents=True, exist_ok=True)

    population = stage2_population(SOURCE_EPD)
    print(f"reconstructed Stage 2 population: {len(population)} FENs (stride {STAGE2_SAMPLE_STRIDE})")
    sample = fixed_subsample(population)
    print(f"fixed sub-sample: {len(sample)} FENs (stride {SUB_SAMPLE_STRIDE})")

    fen_path = OUTPUT_ROOT / "rq5-input.fen"
    fen_path.write_text("\n".join(sample) + "\n", encoding="ascii")

    low_dir = OUTPUT_ROOT / "nodes-25000"
    high_dir = OUTPUT_ROOT / "nodes-50000"

    t0 = time.monotonic()
    low_config = StockfishLabelConfig(engine_path=ENGINE_PATH, nodes=NODES_LOW)
    low_result = label_positions(low_config, fen_path, low_dir, "rq5-001-nodes25000")
    print(f"nodes=25000: labeled {low_result.labeled_count}, skipped {low_result.skipped_count} "
          f"({time.monotonic() - t0:.1f}s)")

    t1 = time.monotonic()
    high_config = StockfishLabelConfig(engine_path=ENGINE_PATH, nodes=NODES_HIGH)
    high_result = label_positions(high_config, fen_path, high_dir, "rq5-001-nodes50000")
    print(f"nodes=50000: labeled {high_result.labeled_count}, skipped {high_result.skipped_count} "
          f"({time.monotonic() - t1:.1f}s)")

    low_records = list(read_shard(low_result.shard))
    high_records = list(read_shard(high_result.shard))

    spread = compute_spread(low_records, high_records)
    summary = summarize(spread, low_records)
    summary["sample_size"] = len(sample)
    summary["stage2_population_size"] = len(population)
    summary["nodes_low"] = NODES_LOW
    summary["nodes_high"] = NODES_HIGH

    summary_path = OUTPUT_ROOT / "summary.json"
    summary_path.write_text(json.dumps(summary, indent=2))
    print(json.dumps(summary, indent=2))
    print(f"\nExperiment RQ-5 complete. Summary: {summary_path}")
    return 0


if __name__ == "__main__":
    import sys
    sys.exit(main())
