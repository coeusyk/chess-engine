"""Scratch: build the QA 1016 / QB 128 fixture network, check the selection criteria, export it.

Usage (from trainer/): python gen_fixture.py OUT_DIR [--export]
"""
import sys
from pathlib import Path

import numpy as np

from scripts.audit_checkpoint_quantization import default_corpus
from trainer.export.canonical import FEATURES_PER_PERSPECTIVE, QuantizedCanonicalNetwork
from trainer.export.exporter import export
from trainer.quantization.inference import _feature_lists, _wrap_int16, accumulator_bound_violations, eval_quantized_fens
from trainer.reproducibility.experiment_metadata import ExperimentMetadata

out_dir = Path(sys.argv[1])
do_export = "--export" in sys.argv
fens, _ = default_corpus()

rng = np.random.default_rng(1016)
width = 64
network = QuantizedCanonicalNetwork(
    hidden_width=width,
    ft_weights=np.round(rng.normal(0, 128, (FEATURES_PER_PERSPECTIVE, width))).astype(np.int16),
    ft_biases=np.round(rng.normal(240, 200, width)).astype(np.int16),
    output_weights=np.round(rng.normal(0, 80, (2, width))).astype(np.int16),
    output_bias=-123,
    qa=1016, qb=128, output_scale=400,
    architecture_id=1, feature_set_id=1,
)
print("loader bound violations:", len(accumulator_bound_violations(network)))

scores = eval_quantized_fens(network, fens)
above = below = total = 0
for fen in fens:
    for features in _feature_lists(fen):
        acc = network.ft_biases.astype(np.int64) + network.ft_weights[list(features)].astype(np.int64).sum(axis=0)
        acc = _wrap_int16(acc)
        above += int((acc > network.qa).sum())
        below += int((acc < 0).sum())
        total += acc.size
print(f"scores min {scores.min()} max {scores.max()} negative {int((scores < 0).sum())} of {len(scores)}")
print(f"accumulator values above qa {above} ({above / total:.3%}), below 0 {below} ({below / total:.3%}), total {total}")

if do_export:
    metadata = ExperimentMetadata(seed=1016, trainer_commit="e" * 40, started_at_epoch_seconds=1700000003, config={})
    result = export(network, metadata, [], out_dir)
    print("exported", result.nnue_path.name, "uuid", result.network_uuid)
