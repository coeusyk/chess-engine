"""Train Vex's first real candidate net (issue #203, E-3): combine E-2's real Stage
1 + Stage 2 datasets, train, quantize, and export -- the first real execution of the
D-4/D-5/D-6 pipeline (`train.py`/`quantizer.py`/`exporter.py`) against non-tiny,
non-synthetic data. Held-out-loss/eval-scale validation is a separate step (this
script's caller, not this module's own job -- `Validator` already exists and is not
duplicated here).

**K provenance**: taken from a fresh `engine-tuner --freeze-params` run against the
*currently-live* `EvalParams` (existing "K calibration isolation" mode, commit
`47ff6cb`) -- never reused from a stale historical tuning log, of which several exist
from different eras/corpora with no single one authoritative for today's EvalParams.
Passed in via `--k`, not hardcoded, so this script never silently goes stale relative
to a future re-tune. See `trainer/configs/train-e3-real.md` for the exact command and
recorded output.

**Network shape**: `hidden_width=256` per `docs/NNUE_PRD.md` Section 4 Network
Specification ("(768 -> 256) x2 -> 1"); `qa=127`/`qb=64`/`output_scale=400` matching
the existing `TestNetworks.java`/`train-tiny.json` convention, not invented values.

**Held-out split**: combined records are shuffled (fixed seed) then split
train/held-out here -- `train()` itself has no shuffling of its own (Section 6's own
scope boundary: "cycles through them... in plain Python"), so pre-shuffling is this
script's one piece of real logic; everything else is composition of existing stages.
"""

from __future__ import annotations

import argparse
import random
from collections import Counter
from pathlib import Path
from typing import List, Tuple

import torch

from trainer.contracts import DatasetProvider, PositionRecord
from trainer.dataset.stockfish_provider import StockfishLabeledProvider
from trainer.dataset.text_provider import TextDatasetProvider
from trainer.export.canonical import checkpoint_to_canonical
from trainer.export.exporter import DatasetComposition, ExportResult, export
from trainer.model.train import TrainingConfig, train
from trainer.quantization.quantizer import ClippingBoundaryReport, clipping_report, quantize
from trainer.reproducibility.experiment_metadata import ExperimentMetadata

HIDDEN_WIDTH = 256  # PRD Section 4 Network Specification: (768 -> 256) x2 -> 1
QA = 127
QB = 64
OUTPUT_SCALE = 400
HELD_OUT_FRACTION = 0.10


def _load_all(provider: DatasetProvider) -> List[PositionRecord]:
    records: List[PositionRecord] = []
    for shard in provider.shards():
        records.extend(provider.positions(shard))
    return records


def combine_and_split(
    stage1_dir: Path, stage2_dir: Path, seed: int
) -> Tuple[List[PositionRecord], List[PositionRecord], List[DatasetComposition]]:
    """Loads both real E-2 datasets, shuffles their union (tagged by origin) with
    `seed`, and splits off `HELD_OUT_FRACTION` for validation. Returns
    (training_records, held_out_records, dataset_composition) -- the last computed
    from `training_records`' *actual* post-split origins (not the pre-split union),
    so the manifest's recorded proportions always match what was really trained on,
    even if the two sources are unequal-sized or the held-out fraction changes.
    """
    stage1 = TextDatasetProvider(
        directory=stage1_dir, identifier="stage1-lichess-evals-2026-07-15", source_ref=str(stage1_dir)
    )
    stage2 = StockfishLabeledProvider(
        directory=stage2_dir, identifier="stage2-sf-labeled-quiet-2026-07-15", source_ref=str(stage2_dir)
    )

    tagged = [(r, stage1.metadata().identifier, "public") for r in _load_all(stage1)]
    tagged += [(r, stage2.metadata().identifier, "sf-labeled") for r in _load_all(stage2)]
    if not tagged:
        raise ValueError("combine_and_split found no records in either stage1_dir or stage2_dir")

    random.Random(seed).shuffle(tagged)

    held_out_count = int(len(tagged) * HELD_OUT_FRACTION)
    held_out_tagged, training_tagged = tagged[:held_out_count], tagged[held_out_count:]

    identifiers_and_stages = [(identifier, stage) for _, identifier, stage in training_tagged]
    dataset_composition = [
        DatasetComposition(identifier=identifier, stage=stage, proportion=count / len(training_tagged))
        for (identifier, stage), count in Counter(identifiers_and_stages).items()
    ]

    training_records = [r for r, _, _ in training_tagged]
    held_out_records = [r for r, _, _ in held_out_tagged]
    return training_records, held_out_records, dataset_composition


def train_quantize_export(
    training_records: List[PositionRecord],
    dataset_composition: List[DatasetComposition],
    k: float,
    seed: int,
    steps: int,
    batch_size: int,
    learning_rate: float,
    output_dir: Path,
) -> Tuple[ExportResult, ClippingBoundaryReport]:
    # No seed_everything() call here -- train() already owns seeding internally
    # (single-owner RNG seeding, per reproducibility/seeding.py's own docstring).
    config = TrainingConfig(
        hidden_width=HIDDEN_WIDTH,
        qa=QA,
        qb=QB,
        output_scale=OUTPUT_SCALE,
        k=k,
        learning_rate=learning_rate,
        seed=seed,
        steps=steps,
        batch_size=batch_size,
    )

    output_dir.mkdir(parents=True, exist_ok=True)
    checkpoint_path = output_dir / "checkpoint.pt"
    train_result = train(config, training_records, checkpoint_path)
    print(f"final training loss: {train_result['losses'][-1]:.6f}")

    checkpoint = torch.load(checkpoint_path, weights_only=False)
    canonical = checkpoint_to_canonical(checkpoint)
    report = clipping_report(canonical)
    quantized = quantize(canonical)

    experiment_metadata = ExperimentMetadata(**checkpoint["experiment_metadata"])
    # label_engine_version is left None: this corpus mixes public (no label engine)
    # and sf-labeled (Stockfish 18) sources -- a single flat value here would misstate
    # a 50/50 mix as 100% Stockfish-labeled. dataset_composition (above) already
    # carries the accurate per-source stage breakdown; exporter.py's own docstring
    # confirms None is a valid, complete value "where applicable", not a missing one.
    result = export(
        quantized,
        experiment_metadata,
        dataset_composition=dataset_composition,
        output_dir=output_dir,
    )
    return result, report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stage1_dir", type=Path)
    parser.add_argument("stage2_dir", type=Path)
    parser.add_argument("output_dir", type=Path)
    parser.add_argument(
        "--k", type=float, required=True,
        help="Calibrated K from engine-tuner --freeze-params (see trainer/configs/train-e3-real.md)",
    )
    parser.add_argument("--steps", type=int, default=2000)
    parser.add_argument("--batch-size", type=int, default=256)
    parser.add_argument("--learning-rate", type=float, default=0.01)
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    training_records, held_out_records, dataset_composition = combine_and_split(
        args.stage1_dir, args.stage2_dir, args.seed
    )
    print(f"training records: {len(training_records)}, held-out records: {len(held_out_records)}")

    result, report = train_quantize_export(
        training_records,
        dataset_composition,
        k=args.k,
        seed=args.seed,
        steps=args.steps,
        batch_size=args.batch_size,
        learning_rate=args.learning_rate,
        output_dir=args.output_dir,
    )
    print(f"clipping report: {report}")
    print(f"exported {result.nnue_path} + {result.manifest_path} (uuid {result.network_uuid})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
