"""Shared test-data builders for trainer/tests/export/ -- not a `test_*.py` module, so
pytest never collects it directly; imported by test_exporter.py and test_manifest_schema.py
so both stop maintaining their own copies of the same throwaway network/metadata shape.
"""

from __future__ import annotations

import numpy as np

from trainer.export.canonical import ARCHITECTURE_ID, FEATURE_SET_ID, FEATURES_PER_PERSPECTIVE, QuantizedCanonicalNetwork
from trainer.reproducibility.experiment_metadata import ExperimentMetadata


def quantized_network(hidden_width: int = 2) -> QuantizedCanonicalNetwork:
    return QuantizedCanonicalNetwork(
        hidden_width=hidden_width,
        ft_weights=np.arange(FEATURES_PER_PERSPECTIVE * hidden_width, dtype=np.int16).reshape(
            FEATURES_PER_PERSPECTIVE, hidden_width
        ),
        ft_biases=np.array([1, -2], dtype=np.int16)[:hidden_width],
        output_weights=np.array([[3, -4], [5, -6]], dtype=np.int16)[:, :hidden_width],
        output_bias=7,
        qa=127,
        qb=64,
        output_scale=400,
        architecture_id=ARCHITECTURE_ID,
        feature_set_id=FEATURE_SET_ID,
    )


def metadata() -> ExperimentMetadata:
    return ExperimentMetadata(
        seed=42,
        trainer_commit="a" * 40,
        started_at_epoch_seconds=1000,
        config={"hidden_width": 2, "qa": 127, "qb": 64, "output_scale": 400, "k": 1.0},
    )
