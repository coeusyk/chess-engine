"""Issue #265: scale-utilization diagnostics.

Everything here uses synthetic networks. The hand-computed case pins the arithmetic exactly; the
contrast case only shows that the diagnostics separate the two scaling regimes the issue
describes. Neither says anything about a real checkpoint, none of which is in this repository.
"""

import json
import math

import numpy as np
import pytest

from tests.export._fixtures import metadata
from tests.quantization._parity_data import (
    corpus_fens, poorly_scaled_network, well_scaled_network,
)
from trainer.export.canonical import (
    ARCHITECTURE_ID, FEATURE_SET_ID, FEATURES_PER_PERSPECTIVE, CanonicalNetwork,
)
from trainer.export.exporter import DatasetComposition
from trainer.export.manifest_schema import validate_manifest
from trainer.validation.quantization_parity import quantize_validate_export
from trainer.validation.scale_utilization import compute_scale_utilization

START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
AFTER_E4 = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1"


def _bias_only_network(biases, output_weights) -> CanonicalNetwork:
    """FT weights all zero, so every accumulator equals its bias for every position."""
    width = len(biases)
    return CanonicalNetwork(
        hidden_width=width,
        ft_weights=np.zeros((FEATURES_PER_PERSPECTIVE, width), dtype=np.float32),
        ft_biases=np.array(biases, dtype=np.float32),
        output_weights=np.array(output_weights, dtype=np.float32),
        output_bias=0.0, qa=127, qb=64, output_scale=400,
        architecture_id=ARCHITECTURE_ID, feature_set_id=FEATURE_SET_ID,
    )


def test_hand_computed_utilization():
    # accumulators per neuron: -5 (below zero), 3 (in range), 200 and 127 (both at or above qa)
    network = _bias_only_network([-5, 3, 200, 127], [[1, 2, 3, 4], [0, 0, 0, 0]])
    report = compute_scale_utilization(network, [START, AFTER_E4])

    assert report.position_count == 2
    assert report.accumulator_count == 2 * 2 * 4  # positions x perspectives x neurons
    assert report.frac_accumulator_below_zero == 0.25
    assert report.frac_accumulator_in_range == 0.25
    assert report.frac_accumulator_at_or_above_qa == 0.5
    assert report.accumulator_max == 200.0
    assert report.activation_mean == pytest.approx((0 + 3 + 127 + 127) / 4)
    assert report.activation_rms == pytest.approx(math.sqrt((0 + 9 + 127 ** 2 + 127 ** 2) / 4))
    assert report.dead_neuron_fraction == 0.25  # only the -5 neuron is never above zero

    assert report.ft_weight_rms == 0.0 and report.ft_rounding_error_to_weight_rms_ratio == 0.0
    assert report.ft_weight_fraction_rounding_to_zero == 1.0
    assert report.output_weight_max_abs == 4.0
    assert report.output_weight_l2_norm == pytest.approx(math.sqrt(1 + 4 + 9 + 16))
    assert report.output_weight_rms == pytest.approx(math.sqrt(30 / 8))
    assert report.cp_per_accumulator_unit_max == pytest.approx(4 * 400 / (127 * 64))
    assert report.output_weight_int16_headroom_ratio == pytest.approx(4 / 32767)


def test_rejects_an_empty_corpus():
    with pytest.raises(ValueError):
        compute_scale_utilization(_bias_only_network([0], [[1], [1]]), [])


def test_diagnostics_separate_the_two_scaling_regimes():
    fens = corpus_fens()
    well = compute_scale_utilization(well_scaled_network(), fens)
    poor = compute_scale_utilization(poorly_scaled_network(), fens)

    # FT weights of order 1 are mostly within one rounding step of zero and rounding is a large
    # fraction of their size; FT weights spread over the int16 range are not.
    assert poor.ft_weight_rms < 2.0 < well.ft_weight_rms
    assert poor.ft_rounding_error_to_weight_rms_ratio > 10 * well.ft_rounding_error_to_weight_rms_ratio
    assert poor.ft_weight_fraction_rounding_to_zero > 0.3 > well.ft_weight_fraction_rounding_to_zero
    # and the output weights that multiply that rounding noise are much larger
    assert poor.cp_per_accumulator_unit_rms > 5 * well.cp_per_accumulator_unit_rms
    # the poorly scaled net also uses a smaller share of the activation range
    assert poor.accumulator_max < well.accumulator_max


def test_utilization_is_written_to_the_manifest_and_validates(tmp_path):
    fens = corpus_fens()[:40]
    result, _ = quantize_validate_export(
        well_scaled_network(), metadata(), [DatasetComposition("synthetic", "test", 1.0)],
        tmp_path, fens=fens)
    manifest = json.loads(result.manifest_path.read_text())

    recorded = manifest["scale_utilization"]
    expected = compute_scale_utilization(well_scaled_network(), fens).to_manifest_dict()
    assert recorded == expected
    validate_manifest(manifest)

    broken = dict(manifest, scale_utilization=dict(recorded, activation_rms="high"))
    with pytest.raises(ValueError, match="scale_utilization.'activation_rms'"):
        validate_manifest(broken)
    del broken["scale_utilization"]["activation_rms"]
    with pytest.raises(ValueError, match="scale_utilization.'activation_rms'"):
        validate_manifest(broken)
    validate_manifest({k: v for k, v in manifest.items() if k != "scale_utilization"})


def test_utilization_sample_is_capped(tmp_path):
    fens = corpus_fens()[:50]
    result, _ = quantize_validate_export(
        well_scaled_network(), metadata(), [DatasetComposition("synthetic", "test", 1.0)],
        tmp_path, fens=fens, utilization_max_positions=10)
    manifest = json.loads(result.manifest_path.read_text())
    assert manifest["scale_utilization"]["position_count"] == 10
    assert manifest["quantization_parity"]["position_count"] == 50
