import numpy as np
import pytest

from trainer.export.canonical import ARCHITECTURE_ID, FEATURE_SET_ID, CanonicalNetwork, FEATURES_PER_PERSPECTIVE
from trainer.quantization.quantizer import INT16_MAX, INT16_MIN, clipping_report, quantize


def _network(ft_weights=None, ft_biases=None, output_weights=None, output_bias=1.0, hidden_width=2) -> CanonicalNetwork:
    return CanonicalNetwork(
        hidden_width=hidden_width,
        ft_weights=ft_weights if ft_weights is not None else np.zeros((FEATURES_PER_PERSPECTIVE, hidden_width), dtype=np.float32),
        ft_biases=ft_biases if ft_biases is not None else np.zeros(hidden_width, dtype=np.float32),
        output_weights=output_weights if output_weights is not None else np.zeros((2, hidden_width), dtype=np.float32),
        output_bias=output_bias,
        qa=127,
        qb=64,
        output_scale=400,
        architecture_id=ARCHITECTURE_ID,
        feature_set_id=FEATURE_SET_ID,
    )


def test_quantize_rounds_to_nearest():
    ft_biases = np.array([1.4, 1.5, -1.4, -1.5], dtype=np.float32)
    network = _network(ft_biases=ft_biases, hidden_width=4,
                        ft_weights=np.zeros((FEATURES_PER_PERSPECTIVE, 4), dtype=np.float32),
                        output_weights=np.zeros((2, 4), dtype=np.float32))
    quantized = quantize(network)
    np.testing.assert_array_equal(quantized.ft_biases, np.round(ft_biases).astype(np.int16))


def test_quantize_clips_ft_and_output_tensors_to_int16_range():
    ft_weights = np.full((FEATURES_PER_PERSPECTIVE, 2), 40000.0, dtype=np.float32)
    network = _network(ft_weights=ft_weights)
    quantized = quantize(network)
    assert np.all(quantized.ft_weights == INT16_MAX)


def test_quantize_does_not_clip_output_bias_to_int16_range():
    huge_bias = float(INT16_MAX) * 10
    network = _network(output_bias=huge_bias)
    quantized = quantize(network)
    assert quantized.output_bias == round(huge_bias)


def test_quantize_applies_no_additional_scale_multiply():
    # A value already within int16 range must round-trip near-verbatim -- proving
    # quantize() does not also multiply by qa/qb (architecture doc Section 7).
    ft_weights = np.full((FEATURES_PER_PERSPECTIVE, 2), 100.0, dtype=np.float32)
    network = _network(ft_weights=ft_weights)
    quantized = quantize(network)
    assert np.all(quantized.ft_weights == 100)


def test_quantize_carries_scalar_fields_through_unchanged():
    network = _network()
    quantized = quantize(network)
    assert quantized.hidden_width == network.hidden_width
    assert quantized.qa == network.qa
    assert quantized.qb == network.qb
    assert quantized.output_scale == network.output_scale
    assert quantized.architecture_id == network.architecture_id
    assert quantized.feature_set_id == network.feature_set_id


def test_quantize_is_deterministic():
    network = _network(
        ft_weights=np.linspace(-100, 100, FEATURES_PER_PERSPECTIVE * 2, dtype=np.float32).reshape(FEATURES_PER_PERSPECTIVE, 2),
        output_weights=np.array([[1.1, -2.2], [3.3, -4.4]], dtype=np.float32),
    )
    first = quantize(network)
    second = quantize(network)
    np.testing.assert_array_equal(first.ft_weights, second.ft_weights)
    np.testing.assert_array_equal(first.ft_biases, second.ft_biases)
    np.testing.assert_array_equal(first.output_weights, second.output_weights)
    assert first.output_bias == second.output_bias


def test_quantize_does_not_mutate_its_input():
    ft_weights = np.array([[1.0, 2.0]] * FEATURES_PER_PERSPECTIVE, dtype=np.float32)
    network = _network(ft_weights=ft_weights)
    original = np.array(network.ft_weights, copy=True)

    quantize(network)

    np.testing.assert_array_equal(network.ft_weights, original)


def test_quantize_output_is_genuinely_immutable():
    quantized = quantize(_network())
    with pytest.raises(ValueError, match="read-only"):
        quantized.ft_weights[0, 0] = 5


def test_clipping_report_is_zero_for_in_range_weights():
    report = clipping_report(_network())
    assert report.ft_weights_clipped == 0
    assert report.ft_biases_clipped == 0
    assert report.output_weights_clipped == 0


def test_clipping_report_flags_out_of_range_output_weights():
    output_weights = np.array([[40000.0, 0.0], [0.0, -40000.0]], dtype=np.float32)
    network = _network(output_weights=output_weights)
    report = clipping_report(network)
    assert report.output_weights_clipped == 2


def test_int16_bounds_match_engine_side_int16_range():
    assert INT16_MIN == -32768
    assert INT16_MAX == 32767
