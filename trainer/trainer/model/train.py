"""The `Trainer` stage (NNUE_TRAINER_ARCHITECTURE.md Section 6/7, PRD "Trainer
Requirements"): optimization loop, KFinder-calibrated sigmoid loss, training-time
overflow-safety weight clipping. Consumes an `Iterable[PositionRecord]` (e.g. from
`trainer.dataset.read_shard`); produces a checkpoint.

Scope boundary (deliberate, not a gap): this loop materializes its input records into
memory and cycles through them in plain Python -- sufficient for the "tiny training
run, few steps" acceptance criterion this PR targets (issue #195). The PRD's own
"Python-side loading is the known bottleneck at the 50-100M position scale" is a real,
separate concern for whichever future PR actually trains at that scale; a `torch`
`DataLoader` wired to `trainer.reproducibility`'s `worker_init_fn`/
`dataloader_generator` is the natural next step then, not built preemptively here.

Second scope boundary (also deliberate): the PRD's "Trainer Requirements" describes
the target as a "blend of sigmoid-scaled eval and WDL outcome." Only the eval_cp/
eval_mate half of that blend is implemented here -- every `DatasetProvider` reachable
from this PR's dependency chain (D-1/D-2/D-3, Stage 1 text data) has no WDL field at
all (`trainer/trainer/contracts/dataset.py`'s `PositionLabel` allows it, but
`TextDatasetProvider` never populates it). `_target_cp` raises loudly on a WDL-only
label rather than silently treating the blend as satisfied. The actual lambda-blend
becomes real work once a WDL-bearing source exists (Stage 3 self-play, Phase E, or a
future `Labeler` stage per the PRD's own component table) -- not built preemptively
against data that doesn't exist yet.
"""

from __future__ import annotations

import json
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Dict, Iterable, List

import torch

from trainer.contracts import PositionLabel, PositionRecord
from trainer.model.batching import encode_batch
from trainer.model.network import NnueNet
from trainer.reproducibility import capture, seed_everything

# A large-but-finite centipawn stand-in for a forced mate, so mate-only labels share
# the same sigmoid-target code path as eval_cp labels rather than a special-cased
# branch in the loss itself.
MATE_EQUIVALENT_CP = 3000.0


@dataclass(frozen=True)
class TrainingConfig:
    hidden_width: int
    qa: float
    qb: float
    output_scale: float
    k: float
    learning_rate: float
    seed: int
    steps: int
    batch_size: int


def load_config(path: Path) -> TrainingConfig:
    with open(path, "r", encoding="utf-8") as f:
        raw = json.load(f)
    return TrainingConfig(**raw)


def texel_sigmoid(x: torch.Tensor, k: float) -> torch.Tensor:
    """The same sigmoid `KFinder.java` uses
    (`engine-tuner/.../TunerEvaluator.java`: `sigma(s) = 1 / (1 + 10^(-K*s/400))`),
    applied identically here so the NNUE loss lands on classical's calibrated
    centipawn scale (PRD "Trainer Requirements": "a hard requirement -- search
    margins... are tuned to that scale"). `k` is taken from an existing `KFinder` run
    (config-supplied), never recomputed in Python.
    """
    return 1.0 / (1.0 + torch.pow(torch.tensor(10.0), -k * x / 400.0))


def _target_cp(label: PositionLabel) -> float:
    if label.eval_cp is not None:
        return float(label.eval_cp)
    if label.eval_mate is not None:
        return MATE_EQUIVALENT_CP if label.eval_mate > 0 else -MATE_EQUIVALENT_CP
    raise ValueError(
        "PositionLabel has neither eval_cp nor eval_mate -- WDL-only labels have no "
        "target-cp equivalent yet (no WDL-bearing DatasetProvider exists before Phase E)"
    )


def train(config: TrainingConfig, records: Iterable[PositionRecord], checkpoint_path: Path) -> Dict[str, Any]:
    """Runs `config.steps` optimization steps over `records` (cycled deterministically,
    see module docstring) and writes a checkpoint to `checkpoint_path`. Returns the
    per-step loss history.
    """
    seed_everything(config.seed)

    model = NnueNet(config.hidden_width, config.qa, config.qb, config.output_scale)
    optimizer = torch.optim.Adam(model.parameters(), lr=config.learning_rate)

    records = list(records)
    if not records:
        raise ValueError("train() requires at least one record")

    losses: List[float] = []
    cursor = 0
    for _ in range(config.steps):
        batch_records = []
        for _ in range(config.batch_size):
            batch_records.append(records[cursor % len(records)])
            cursor += 1

        batch = encode_batch(batch_records)
        target_cps = torch.tensor([_target_cp(r.label) for r in batch_records], dtype=torch.float32)
        targets = texel_sigmoid(target_cps, config.k)

        optimizer.zero_grad()
        predicted_cp = model(batch.us_indices, batch.us_offsets, batch.them_indices, batch.them_offsets)
        predicted_prob = texel_sigmoid(predicted_cp, config.k)
        loss = torch.mean((predicted_prob - targets) ** 2)
        loss.backward()
        optimizer.step()
        model.clip_ft_weights_()

        losses.append(loss.item())

    metadata = capture(seed=config.seed, config=asdict(config))
    torch.save(
        {
            "model_state_dict": model.state_dict(),
            "optimizer_state_dict": optimizer.state_dict(),
            "config": asdict(config),
            "experiment_metadata": asdict(metadata),
            "final_loss": losses[-1],
        },
        checkpoint_path,
    )

    return {"losses": losses}
