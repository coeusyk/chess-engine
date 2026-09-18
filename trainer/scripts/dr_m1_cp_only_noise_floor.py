"""M-1: empirical seed-to-seed noise floor for cp-only correlation (measurement-characterization
follow-up to E-15, issue #223's Phase E). `measurement-model.md` section 1 documents a same-K
pooled v1-clean noise floor (~0.0004, `phase4-p4i-replication.md`) but explicitly flags cp-only
correlation as "not yet independently seed-measured" -- this script measures it directly, for the
first time, under the exact configuration E-15's arms trained under (P3A-001's frozen schedule,
`stage2-quiet-sf-v1` base dataset), so E-15's own +0.0058/+0.0079 cp-only deltas can be judged
against a configuration-matched noise floor rather than an untested assumption.

**Scope, per this task's explicit constraints**: base training data only (the same 36,000-record
`combine_and_split(stage1_dir, stage2-quiet-sf-v1, seed=42)` set E-15 used, unfiltered, matching
`DR-E15-phase-d-training-report.md` section 1 -- no sentinel filter applied to training records,
same as E-15's own arms), no Stage-3 data at all, architecture/schedule/K unchanged from
P3A-001's frozen values. **Only `TrainingConfig.seed` varies** (42, 43, 44) -- this project's
established repeated-seed convention (`P1-G00`/`-S43`/`-S44`, `phase4_p4i_replication.py`'s
seed 43 replicate). `seed_everything(config.seed)` governs both model init and the
epoch-reshuffle data order (unchanged trainer semantics, `DR-E15` section 14) -- no
`initial_state_dict` pinning is used here, since the noise floor this script characterizes is
exactly the combined init+data-order variance every prior same-K seed-replicate measurement in
this roadmap (P1-G00/-S43/-S44, P4I replication) has also measured, and reusing P3A-001's own
checkpoint as a fourth seed would introduce a dataset-version confound (P3A-001 trained on
`stage2-quiet-sf`, not the `-v1` variant E-15 and this script both use) -- three fresh, matched
runs are used instead.

n=3 (not a larger sweep): matches this project's own smallest-defensible-n precedent
(`phase1_optimization_sweep.py`'s P1-G00/-S43/-S44 trio) rather than the n=2 pairwise estimate
`phase4_p4i_replication.py` used when only one replicate was affordable -- three points give one
pairwise-spread estimate *per pair* (three pairs) instead of one, a modest but free improvement
at ~150s/run (`DR-E15-phase-d-training-report.md` section 4's measured wall-clock), still far
below "a large arbitrary sweep."
"""

from __future__ import annotations

import dataclasses
import json
import time
from pathlib import Path
from typing import List

import torch

from trainer.contracts import PositionRecord
from trainer.model.batching import encode_batch
from trainer.model.network import NnueNet
from trainer.model.train import TrainingConfig, target_cp, train
from trainer.validation.validator import calibration_report, evaluate_held_out, _pearson_correlation
from scripts.train_candidate_net import combine_and_split

HIDDEN_WIDTH, QA, QB, OUTPUT_SCALE, K = 256, 127, 64, 400, 2.773456
STEPS, LEARNING_RATE, LR_SCHEDULE, WARMUP_STEPS, BATCH_SIZE = 20000, 0.01, "cosine", 200, 256

STAGE1_DIR = Path("outputs/datasets/stage1-lichess")
STAGE2_V1_DIR = Path("outputs/datasets/stage2-quiet-sf-v1")
SPLIT_SEED = 42
SENTINEL_CP_VALUES = {-9605, 9605, 20000}

SEEDS = [42, 43, 44]
OUTPUT_ROOT = Path("outputs/phase-m1") / "cp-only-noise-floor"


def _is_sentinel(record: PositionRecord) -> bool:
    return record.label.eval_cp in SENTINEL_CP_VALUES


def _load_model(checkpoint_path: Path) -> NnueNet:
    checkpoint = torch.load(checkpoint_path, weights_only=False)
    model = NnueNet(HIDDEN_WIDTH, QA, QB, OUTPUT_SCALE)
    model.load_state_dict(checkpoint["model_state_dict"])
    model.eval()
    return model


def _select_best_checkpoint(diagnostics: list, output_root: Path) -> tuple[int, Path]:
    best_step, best_corr = None, float("-inf")
    for d in diagnostics:
        if d["held_out_correlation"] is not None and d["held_out_correlation"] >= best_corr:
            best_corr = d["held_out_correlation"]
            best_step = d["step"]
    return best_step, output_root / "checkpoints" / f"step-{best_step:06d}.pt"


def _predictions(model: NnueNet, records: List[PositionRecord]):
    batch = encode_batch(records)
    target_cps = torch.tensor([target_cp(r.label) for r in records], dtype=torch.float32)
    with torch.no_grad():
        predicted_cp = model(batch.us_indices, batch.us_offsets, batch.them_indices, batch.them_offsets)
    return predicted_cp, target_cps


def _eval_cell(model: NnueNet, records: List[PositionRecord]) -> dict:
    cp_only = [r for r in records if r.label.eval_mate is None]
    mate_only = [r for r in records if r.label.eval_mate is not None]
    held_out_eval = evaluate_held_out(model, records, K)
    cal = calibration_report(model, records)
    return {
        "n": len(records),
        "pooled_correlation": held_out_eval.label_correlation,
        "held_out_loss": held_out_eval.held_out_loss,
        "cp_only_correlation": _pearson_correlation(*_predictions(model, cp_only)) if cp_only else None,
        "mate_only_correlation": _pearson_correlation(*_predictions(model, mate_only)) if mate_only else None,
        "overall": dataclasses.asdict(cal.overall),
        "cp_labeled": dataclasses.asdict(cal.cp_labeled),
        "mate_labeled": dataclasses.asdict(cal.mate_labeled),
    }


def _train_seed(seed: int, training_records: List[PositionRecord],
                 held_out_records: List[PositionRecord]) -> dict:
    tag = f"seed{seed}"
    output_root = OUTPUT_ROOT / tag
    output_root.mkdir(parents=True, exist_ok=True)

    config = TrainingConfig(
        hidden_width=HIDDEN_WIDTH, qa=QA, qb=QB, output_scale=OUTPUT_SCALE, k=K,
        learning_rate=LEARNING_RATE, seed=seed, steps=STEPS, batch_size=BATCH_SIZE,
        lr_schedule=LR_SCHEDULE, warmup_steps=WARMUP_STEPS,
    )
    print(f"=== training {tag}: seed={seed} K={K} steps={STEPS} lr={LEARNING_RATE} "
          f"schedule={LR_SCHEDULE} warmup={WARMUP_STEPS} (P3A-001's frozen schedule, base data "
          f"only, no Stage-3) ===")

    t0 = time.time()
    result = train(
        config, training_records, checkpoint_path=output_root / "final.pt",
        held_out_records=held_out_records, log_interval=max(1, STEPS // 20),
        checkpoint_dir=output_root / "checkpoints",
    )
    wall_clock_seconds = time.time() - t0

    diagnostics = [dataclasses.asdict(d) for d in result["diagnostics"]]
    (output_root / "training_diagnostics.json").write_text(json.dumps(diagnostics, indent=2))

    best_step, best_checkpoint_path = _select_best_checkpoint(diagnostics, output_root)
    final_step = STEPS - 1
    final_checkpoint_path = output_root / "checkpoints" / f"step-{final_step:06d}.pt"
    print(f"{tag}: selected_step={best_step}/{final_step} wall_clock={wall_clock_seconds:.1f}s")

    return {
        "seed": seed, "wall_clock_seconds": wall_clock_seconds,
        "selected_step": best_step, "final_step": final_step,
        "selected_checkpoint_path": str(best_checkpoint_path),
        "final_checkpoint_path": str(final_checkpoint_path),
    }


def main() -> int:
    print("=== DR-M1: cp-only correlation seed-to-seed noise floor (base data only, no Stage-3) ===")

    training_records, held_out_records, _ = combine_and_split(STAGE1_DIR, STAGE2_V1_DIR, SPLIT_SEED)
    held_out_clean = [r for r in held_out_records if not _is_sentinel(r)]
    print(f"training set: {len(training_records)}  v1: {len(held_out_records)}  "
          f"v1-clean: {len(held_out_clean)}")

    OUTPUT_ROOT.mkdir(parents=True, exist_ok=True)

    runs = {}
    matrix = {}
    for seed in SEEDS:
        arm_meta = _train_seed(seed, training_records, held_out_records)
        runs[seed] = arm_meta
        selected_model = _load_model(Path(arm_meta["selected_checkpoint_path"]))
        final_model = _load_model(Path(arm_meta["final_checkpoint_path"]))
        matrix[f"seed{seed}_selected_on_v1_clean"] = _eval_cell(selected_model, held_out_clean)
        matrix[f"seed{seed}_final_on_v1_clean"] = _eval_cell(final_model, held_out_clean)

    output = {"runs": runs, "matrix": matrix, "v1_clean_n": len(held_out_clean)}
    output_path = OUTPUT_ROOT / "noise_floor_results.json"
    output_path.write_text(json.dumps(output, indent=2))
    print(f"wrote {output_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
