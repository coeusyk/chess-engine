"""Trainer reproducibility CI check (NNUE_TRAINER_ARCHITECTURE.md Section 12, PRD
"Continuous Validation (CI)" item 5, issue #198): runs the full DatasetProvider ->
Trainer -> CanonicalNetwork -> Quantizer -> Exporter pipeline end to end on a tiny
fixed-seed fixture, twice, and gives `tests/cli/test_reproducibility_check.py` what it
needs to assert reproducibility.

Scope of "byte-identical" (deliberate, not a narrowing of the issue -- see that test
file's own docstring): `Exporter.export()` assigns a *fresh* `network_uuid` and
`created_at_epoch_seconds` on every call, by design (Section 5's provenance-timing
rule, D-6). Two independent `export()` calls can therefore never produce byte-identical
`.nnue` files or manifests as a whole. What Section 10/Invariant 5 actually promise --
and what this module exists to let a test prove -- is that quantization is
byte-identical given the same checkpoint, and that the `.nnue` byte-writer itself is a
pure deterministic function of its inputs. `run_pipeline()` exposes the intermediate
`QuantizedCanonicalNetwork` alongside the `ExportResult` specifically so a caller can
check both without re-deriving them.
"""

from __future__ import annotations

import sys
from dataclasses import dataclass
from pathlib import Path

import torch

from trainer.dataset.text_provider import TextDatasetProvider
from trainer.encoding.feature_spec import repo_root
from trainer.export.canonical import QuantizedCanonicalNetwork, checkpoint_to_canonical
from trainer.export.exporter import DatasetComposition, ExportResult, export
from trainer.model.train import TrainingConfig, load_config, train
from trainer.quantization.quantizer import quantize
from trainer.reproducibility.experiment_metadata import ExperimentMetadata

# The same tiny, fixed-seed fixtures D-4 (issue #195) already established for
# exercising the training loop's plumbing -- reused here rather than duplicated
# (train-tiny.md documents their provenance and explicitly-uncalibrated values).
_FIXTURE_DATASET_DIR = Path(__file__).resolve().parent.parent.parent / "tests" / "fixtures"
_TINY_CONFIG_PATH = Path(__file__).resolve().parent.parent.parent / "configs" / "train-tiny.json"


@dataclass(frozen=True)
class PipelineResult:
    export_result: ExportResult
    quantized_network: QuantizedCanonicalNetwork


def run_pipeline(output_dir: Path, config_path: Path = _TINY_CONFIG_PATH,
                  dataset_dir: Path = _FIXTURE_DATASET_DIR) -> PipelineResult:
    """Runs one full pipeline pass: reads `dataset_dir`'s CSV shards, trains
    `config_path`'s tiny fixed-seed config, converts the checkpoint to a
    `CanonicalNetwork`, quantizes it, and exports a `.nnue` + manifest into
    `output_dir`. Deterministic given the same `config_path`/`dataset_dir` (same seed,
    same records, no RNG downstream of training) -- see module docstring for the one
    documented exception (export identity fields).
    """
    config = load_config(config_path)
    provider = TextDatasetProvider(directory=dataset_dir, identifier="ci-tiny-fixture", source_ref="stage1_sample.csv")
    records = [r for shard in provider.shards() for r in provider.positions(shard)]

    checkpoint_path = output_dir / "checkpoint.pt"
    train(config, records, checkpoint_path)
    checkpoint = torch.load(checkpoint_path, weights_only=False)

    canonical = checkpoint_to_canonical(checkpoint)
    quantized = quantize(canonical)

    metadata = ExperimentMetadata(**checkpoint["experiment_metadata"])
    dataset_composition = [DatasetComposition(
        identifier=provider.metadata().identifier, stage=provider.metadata().stage, proportion=1.0,
    )]
    export_result = export(quantized, metadata, dataset_composition, output_dir)

    return PipelineResult(export_result=export_result, quantized_network=quantized)


def main() -> int:
    """Manual smoke-test entry point (`python -m trainer.cli.reproducibility_check`) --
    runs the pipeline once into a scratch directory and prints the resulting artifact
    paths. The actual reproducibility assertions live in the pytest test, which is what
    trainer CI runs; this is for a human checking the pipeline still wires up by hand.
    """
    output_dir = repo_root(Path(__file__).resolve()) / "trainer" / "outputs" / "reproducibility-check-smoke"
    output_dir.mkdir(parents=True, exist_ok=True)
    result = run_pipeline(output_dir)
    print(f"nnue: {result.export_result.nnue_path}")
    print(f"manifest: {result.export_result.manifest_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
