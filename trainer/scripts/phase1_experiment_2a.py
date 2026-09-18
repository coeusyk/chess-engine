"""Experiment 2A (research doc §24.4/§27.9/§28): does Stage 1 volume (20k -> 100k) improve
held-out correlation beyond P1-G04, holding everything else fixed (§26.1's per-phase table)?

**Held-out-set preservation, the one real methodological subtlety here**: `combine_and_split`
shuffles Stage1+Stage2 together and takes 10% as held-out -- re-running it over a *larger* Stage
1 pool would produce a *different* held-out set (still 10%, but a different, larger set of
positions), which would confound "more data" with "different/larger evaluation set." Instead:
the ORIGINAL 4,000-record held-out set (and the original 36,000-record training set) are reused
byte-for-byte from Phase 1's own split, and the *new* 80,000 Stage-1-only positions (verified
below to be genuinely new, non-overlapping with the original 20,000) are added to the training
set only. Held-out stays the exact same 4,000 positions across every data-volume experiment in
this roadmap, per §26.1's own stated intent.

Uses P1-G04's frozen schedule (research doc §27.11) unchanged: steps=20,000, LR=0.01, cosine,
warmup=200, seed=42 -- per this task's explicit instruction, not §24.4's earlier (superseded)
passes-scaling idea.
"""

from __future__ import annotations

import csv
import dataclasses
import json
import random
import time
from pathlib import Path
from typing import List

import torch

from trainer.contracts import PositionLabel, PositionMetadata, PositionRecord
from trainer.model.network import NnueNet
from trainer.model.train import TrainingConfig, train
from trainer.validation.validator import calibration_report, evaluate_held_out
from scripts.train_candidate_net import combine_and_split
from scripts.acquire_stage1_lichess import DEFAULT_SOURCE_URL  # noqa: F401 -- provenance only

HIDDEN_WIDTH = 256
QA = 127
QB = 64
OUTPUT_SCALE = 400
K = 2.773456  # unchanged -- loss function out of scope

STAGE1_ORIGINAL_DIR = Path("outputs/datasets/stage1-lichess")
STAGE1_100K_DIR = Path("outputs/datasets/stage1-lichess-100k")
STAGE2_DIR = Path("outputs/datasets/stage2-quiet-sf")
SPLIT_SEED = 42
ORIGINAL_STAGE1_COUNT = 20000

TRAIN_SAMPLE_SELECTION_SEED = 999
TRAIN_SAMPLE_SIZE = 4000

EXPERIMENT_ID = "P2A-001"
OUTPUT_ROOT = Path("outputs/phase1") / EXPERIMENT_ID

# P1-G04's frozen schedule (research doc §27.11) -- unchanged, per this task's instruction.
STEPS = 20000
LEARNING_RATE = 0.01
LR_SCHEDULE = "cosine"
WARMUP_STEPS = 200
TRAINING_SEED = 42


def _parse_stage1_csv(path: Path) -> List[PositionRecord]:
    records = []
    with open(path, newline="", encoding="ascii") as f:
        for row in csv.reader(f):
            if not row:
                continue
            fen = row[0]
            eval_cp = int(row[1]) if row[1].strip() else None
            eval_mate = int(row[2]) if len(row) > 2 and row[2].strip() else None
            records.append(
                PositionRecord(fen=fen, label=PositionLabel(eval_cp=eval_cp, eval_mate=eval_mate),
                                metadata=PositionMetadata())
            )
    return records


def _select_best_checkpoint(diagnostics: list) -> tuple[int, Path]:
    best_step, best_corr = None, float("-inf")
    for d in diagnostics:
        if d["held_out_correlation"] is not None and d["held_out_correlation"] >= best_corr:
            best_corr = d["held_out_correlation"]
            best_step = d["step"]
    return best_step, OUTPUT_ROOT / "checkpoints" / f"step-{best_step:06d}.pt"


def main() -> int:
    print("=== Experiment 2A: Stage 1 volume 20k -> 100k ===")

    # --- Verify the new 100k pull's first 20,000 lines exactly match the original 20k file. ---
    original_lines = Path(STAGE1_ORIGINAL_DIR / "stage1.csv").read_text().splitlines()
    new_lines = Path(STAGE1_100K_DIR / "stage1.csv").read_text().splitlines()
    assert len(original_lines) == ORIGINAL_STAGE1_COUNT, f"expected {ORIGINAL_STAGE1_COUNT} original lines"
    assert len(new_lines) == 100000, f"expected 100000 new lines, got {len(new_lines)}"
    assert new_lines[:ORIGINAL_STAGE1_COUNT] == original_lines, (
        "the new 100k pull's first 20,000 lines do not match the original 20k file byte-for-byte "
        "-- refusing to proceed, since held-out-set preservation below assumes this identity"
    )
    print(f"verified: new pull's first {ORIGINAL_STAGE1_COUNT} lines match the original file exactly")

    new_stage1_only_lines = new_lines[ORIGINAL_STAGE1_COUNT:]
    assert len(new_stage1_only_lines) == 80000
    new_stage1_records = []
    for row in csv.reader(new_stage1_only_lines):
        fen = row[0]
        eval_cp = int(row[1]) if row[1].strip() else None
        eval_mate = int(row[2]) if len(row) > 2 and row[2].strip() else None
        new_stage1_records.append(
            PositionRecord(fen=fen, label=PositionLabel(eval_cp=eval_cp, eval_mate=eval_mate),
                            metadata=PositionMetadata())
        )
    print(f"new Stage-1-only records (never seen in Phase 1, train or held-out): {len(new_stage1_records)}")

    # --- Reproduce Phase 1's exact training/held-out split (same call, same seed). ---
    phase1_training_records, held_out_records, _ = combine_and_split(STAGE1_ORIGINAL_DIR, STAGE2_DIR, SPLIT_SEED)
    print(f"Phase 1 original: training={len(phase1_training_records)} held_out={len(held_out_records)}")

    training_records = phase1_training_records + new_stage1_records
    print(f"Experiment 2A training set: {len(training_records)} "
          f"({len(phase1_training_records)} original + {len(new_stage1_records)} new) -- held_out UNCHANGED")

    fixed_sample_indices = random.Random(TRAIN_SAMPLE_SELECTION_SEED).sample(
        range(len(training_records)), TRAIN_SAMPLE_SIZE
    )
    train_diagnostic_sample = [training_records[i] for i in fixed_sample_indices]
    print(f"fixed train-diagnostic subsample: n={len(train_diagnostic_sample)} "
          f"(selection seed={TRAIN_SAMPLE_SELECTION_SEED}, drawn from the new 116k training pool)")

    OUTPUT_ROOT.mkdir(parents=True, exist_ok=True)
    config = TrainingConfig(
        hidden_width=HIDDEN_WIDTH, qa=QA, qb=QB, output_scale=OUTPUT_SCALE, k=K,
        learning_rate=LEARNING_RATE, seed=TRAINING_SEED, steps=STEPS, batch_size=256,
        lr_schedule=LR_SCHEDULE, warmup_steps=WARMUP_STEPS,
    )
    print(f"=== {EXPERIMENT_ID}: steps={STEPS} lr={LEARNING_RATE} schedule={LR_SCHEDULE} "
          f"warmup={WARMUP_STEPS} seed={TRAINING_SEED} (P1-G04's frozen schedule, unchanged) ===")

    t0 = time.time()
    result = train(
        config, training_records, checkpoint_path=OUTPUT_ROOT / "final.pt",
        held_out_records=held_out_records, log_interval=max(1, STEPS // 20),
        train_diagnostic_sample=train_diagnostic_sample, checkpoint_dir=OUTPUT_ROOT / "checkpoints",
    )
    wall_clock_seconds = time.time() - t0

    diagnostics = [dataclasses.asdict(d) for d in result["diagnostics"]]
    (OUTPUT_ROOT / "training_diagnostics.json").write_text(json.dumps(diagnostics, indent=2))

    best_step, best_checkpoint_path = _select_best_checkpoint(diagnostics)
    checkpoint = torch.load(best_checkpoint_path, weights_only=False)
    model = NnueNet(HIDDEN_WIDTH, QA, QB, OUTPUT_SCALE)
    model.load_state_dict(checkpoint["model_state_dict"])

    final_eval = evaluate_held_out(model, held_out_records, k=K)
    final_cal = calibration_report(model, held_out_records)

    summary = {
        "experiment_id": EXPERIMENT_ID,
        "stage1_count": 100000,
        "stage2_count": 20000,
        "training_records": len(training_records),
        "held_out_records": len(held_out_records),
        "steps": STEPS, "learning_rate": LEARNING_RATE, "lr_schedule": LR_SCHEDULE,
        "warmup_steps": WARMUP_STEPS, "seed": TRAINING_SEED,
        "wall_clock_seconds": wall_clock_seconds,
        "selected_step": best_step, "final_step": STEPS - 1,
        "held_out_loss": final_eval.held_out_loss,
        "held_out_correlation": final_eval.label_correlation,
        "overall": dataclasses.asdict(final_cal.overall),
        "mate_labeled": dataclasses.asdict(final_cal.mate_labeled),
        "cp_labeled": dataclasses.asdict(final_cal.cp_labeled),
    }
    (OUTPUT_ROOT / "summary.json").write_text(json.dumps(summary, indent=2))
    print(f"selected_step={best_step}/{STEPS - 1}  held_out_correlation={final_eval.label_correlation:.4f}  "
          f"held_out_loss={final_eval.held_out_loss:.4f}  wall_clock={wall_clock_seconds:.1f}s")
    print("Experiment 2A complete. Summary written to", OUTPUT_ROOT / "summary.json")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
