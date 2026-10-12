"""Phase 1 (research doc §24.4/§26): optimization-only grid over the existing
20,000+20,000-position dataset, holding architecture/features/labels/loss/export/
quantization fixed (§26.1's controlled-variable table). Determines whether `dfffd3da`'s
0.504 held-out correlation is optimization-limited (steps/LR/schedule) before any data
volume or label-quality change is considered (§24's roadmap ordering, unchanged).

Grid (§26.1: steps x LR is one declared 2-D grid, not accidental; the seed-replication
trio is §26.3's declared exception -- vary training seed only, to measure natural
run-to-run correlation variance before treating any grid delta as a real effect):

  P1-G00          seed=42 steps=2000  lr=0.01   constant  (baseline hyperparameters,
                                                            + this phase's reshuffle)
  P1-G00-S43      seed=43 steps=2000  lr=0.01   constant  (seed replicate)
  P1-G00-S44      seed=44 steps=2000  lr=0.01   constant  (seed replicate)
  P1-G01          seed=42 steps=20000 lr=0.01   constant  (steps only)
  P1-G02          seed=42 steps=2000  lr=0.001  constant  (LR only)
  P1-G03          seed=42 steps=20000 lr=0.001  constant  (steps + LR)
  P1-G04          seed=42 steps=20000 lr=0.01   cosine    (schedule; LR=0.01 flat for
                                                            20,000 steps is exactly the
                                                            condition likely to oscillate
                                                            near a minimum -- promoted to
                                                            a core cell, not conditional)

Every artifact (checkpoint, diagnostics, calibration report) is written under
`outputs/phase1/<experiment_id>/`, never overwritten across runs (§26.5).
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
from trainer.validation.validator import calibration_report, evaluate_held_out
from scripts.train_candidate_net import combine_and_split

HIDDEN_WIDTH = 256
QA = 127
QB = 64
OUTPUT_SCALE = 400
K = 2.773456  # unchanged from dfffd3da -- loss function is out of Phase 1's scope

# Fixed, same across every grid cell (§26.1): dataset/split seed.
STAGE1_DIR = Path("outputs/datasets/stage1-lichess")
STAGE2_DIR = Path("outputs/datasets/stage2-quiet-sf")
SPLIT_SEED = 42

# Selection seed for the fixed train-diagnostic subsample -- deliberately distinct from
# any training seed, so varying the training seed (the seed-replication cells) never
# changes which positions the "train correlation" trajectory is measured against.
TRAIN_SAMPLE_SELECTION_SEED = 999
TRAIN_SAMPLE_SIZE = 4000  # matches held-out set size, for a comparable-cost measurement

OUTPUT_ROOT = Path("outputs/phase1")


@dataclasses.dataclass(frozen=True)
class GridCell:
    experiment_id: str
    seed: int
    steps: int
    learning_rate: float
    lr_schedule: str
    warmup_steps: int = 0


GRID: List[GridCell] = [
    GridCell("P1-G00", seed=42, steps=2000, learning_rate=0.01, lr_schedule="constant"),
    GridCell("P1-G00-S43", seed=43, steps=2000, learning_rate=0.01, lr_schedule="constant"),
    GridCell("P1-G00-S44", seed=44, steps=2000, learning_rate=0.01, lr_schedule="constant"),
    GridCell("P1-G01", seed=42, steps=20000, learning_rate=0.01, lr_schedule="constant"),
    GridCell("P1-G02", seed=42, steps=2000, learning_rate=0.001, lr_schedule="constant"),
    GridCell("P1-G03", seed=42, steps=20000, learning_rate=0.001, lr_schedule="constant"),
    GridCell("P1-G04", seed=42, steps=20000, learning_rate=0.01, lr_schedule="cosine", warmup_steps=200),
]


def _log_interval_for(steps: int) -> int:
    # ~20 diagnostic/checkpoint points per run regardless of length (§26.2's trajectory
    # requirement), never coarser than every step for very short runs.
    return max(1, steps // 20)


def _select_best_checkpoint(experiment_dir: Path, diagnostics: list) -> tuple[int, Path]:
    """Peak held-out correlation before decline (research doc §24.4's curve rule) --
    the last diagnostic point whose held_out_correlation is the maximum seen so far,
    not necessarily the final step.
    """
    best_step = None
    best_corr = float("-inf")
    for d in diagnostics:
        if d["held_out_correlation"] is not None and d["held_out_correlation"] >= best_corr:
            best_corr = d["held_out_correlation"]
            best_step = d["step"]
    checkpoint_path = experiment_dir / "checkpoints" / f"step-{best_step:06d}.pt"
    return best_step, checkpoint_path


def run_cell(cell: GridCell, training_records, held_out_records, train_diagnostic_sample) -> dict:
    experiment_dir = OUTPUT_ROOT / cell.experiment_id
    experiment_dir.mkdir(parents=True, exist_ok=True)

    config = TrainingConfig(
        hidden_width=HIDDEN_WIDTH,
        qa=QA,
        qb=QB,
        output_scale=OUTPUT_SCALE,
        k=K,
        learning_rate=cell.learning_rate,
        seed=cell.seed,
        steps=cell.steps,
        batch_size=256,
        lr_schedule=cell.lr_schedule,
        warmup_steps=cell.warmup_steps,
    )

    print(f"=== {cell.experiment_id}: steps={cell.steps} lr={cell.learning_rate} "
          f"schedule={cell.lr_schedule} seed={cell.seed} ===")
    t0 = time.time()
    result = train(
        config,
        training_records,
        checkpoint_path=experiment_dir / "final.pt",
        held_out_records=held_out_records,
        log_interval=_log_interval_for(cell.steps),
        train_diagnostic_sample=train_diagnostic_sample,
        checkpoint_dir=experiment_dir / "checkpoints",
    )
    wall_clock_seconds = time.time() - t0

    diagnostics = [dataclasses.asdict(d) for d in result["diagnostics"]]
    (experiment_dir / "training_diagnostics.json").write_text(json.dumps(diagnostics, indent=2))

    best_step, best_checkpoint_path = _select_best_checkpoint(experiment_dir, diagnostics)

    checkpoint = torch.load(best_checkpoint_path, weights_only=False)
    model = NnueNet(HIDDEN_WIDTH, QA, QB, OUTPUT_SCALE)
    model.load_state_dict(checkpoint["model_state_dict"])

    final_eval = evaluate_held_out(model, held_out_records, k=K)
    final_cal = calibration_report(model, held_out_records)

    summary = {
        "experiment_id": cell.experiment_id,
        "seed": cell.seed,
        "steps": cell.steps,
        "learning_rate": cell.learning_rate,
        "lr_schedule": cell.lr_schedule,
        "warmup_steps": cell.warmup_steps,
        "wall_clock_seconds": wall_clock_seconds,
        "selected_step": best_step,
        "final_step": cell.steps - 1,
        "held_out_loss": final_eval.held_out_loss,
        "held_out_correlation": final_eval.label_correlation,
        "overall": dataclasses.asdict(final_cal.overall),
        "mate_labeled": dataclasses.asdict(final_cal.mate_labeled),
        "cp_labeled": dataclasses.asdict(final_cal.cp_labeled),
    }
    (experiment_dir / "summary.json").write_text(json.dumps(summary, indent=2))
    print(f"    selected_step={best_step}/{cell.steps - 1}  held_out_correlation={final_eval.label_correlation:.4f}  "
          f"held_out_loss={final_eval.held_out_loss:.4f}  wall_clock={wall_clock_seconds:.1f}s")
    return summary


def main() -> int:
    print("Loading dataset (Stage 1 + Stage 2, seed=%d split, unchanged from dfffd3da) ..." % SPLIT_SEED)
    training_records, held_out_records, _ = combine_and_split(STAGE1_DIR, STAGE2_DIR, SPLIT_SEED)
    print(f"training={len(training_records)} held_out={len(held_out_records)}")

    fixed_sample_indices = random.Random(TRAIN_SAMPLE_SELECTION_SEED).sample(
        range(len(training_records)), TRAIN_SAMPLE_SIZE
    )
    train_diagnostic_sample: List[PositionRecord] = [training_records[i] for i in fixed_sample_indices]
    print(f"fixed train-diagnostic subsample: n={len(train_diagnostic_sample)} "
          f"(selection seed={TRAIN_SAMPLE_SELECTION_SEED}, fixed across every grid cell)")

    OUTPUT_ROOT.mkdir(parents=True, exist_ok=True)
    summaries = []
    for cell in GRID:
        summaries.append(run_cell(cell, training_records, held_out_records, train_diagnostic_sample))

    (OUTPUT_ROOT / "grid_summary.json").write_text(json.dumps(summaries, indent=2))
    print("\nAll grid cells complete. Summary written to", OUTPUT_ROOT / "grid_summary.json")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
