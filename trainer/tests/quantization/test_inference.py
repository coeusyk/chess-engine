"""Python quantized inference versus the engine's integer arithmetic (issue #265), on cases small
enough to compute by hand, plus the float32 reference against the real `NnueNet`."""

import numpy as np
import pytest
import torch

from tests.quantization._parity_data import corpus_fens, synthetic_float_network
from trainer.export.canonical import ARCHITECTURE_ID, FEATURE_SET_ID, FEATURES_PER_PERSPECTIVE, QuantizedCanonicalNetwork
from trainer.model.batching import encode_fens
from trainer.model.network import NnueNet
from trainer.quantization.inference import (
    accumulator_bound_violations,
    eval_dequantized_float,
    eval_float_canonical,
    eval_quantized_canonical,
    eval_quantized_fens,
)
from trainer.quantization.quantizer import quantize

START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq -"


def _quantized(ft_weights, ft_biases, output_weights, output_bias, qa=127, qb=64, output_scale=400):
    hidden_width = len(ft_biases)
    full = np.zeros((FEATURES_PER_PERSPECTIVE, hidden_width), dtype=np.int16)
    for feature, row in ft_weights.items():
        full[feature] = row
    return QuantizedCanonicalNetwork(
        hidden_width=hidden_width, ft_weights=full, ft_biases=np.array(ft_biases, dtype=np.int16),
        output_weights=np.array(output_weights, dtype=np.int16), output_bias=output_bias,
        qa=qa, qb=qb, output_scale=output_scale,
        architecture_id=ARCHITECTURE_ID, feature_set_id=FEATURE_SET_ID,
    )


def test_hand_computed_score_with_clamp_at_both_ends():
    # width 2. us accumulator = bias + w[3] = [200, -5] -> clamp [127, 0]; them = bias = [10, 20].
    network = _quantized({3: [190, -25]}, [10, 20], [[2, 3], [5, 7]], output_bias=100)
    # sum = 127*2 + 0*3 + 10*5 + 20*7 + 100 = 254 + 50 + 140 + 100 = 544; 544 * 400 / 8128 = 26.77 -> 26
    assert eval_quantized_canonical(network, [3], []) == 26


def test_division_truncates_toward_zero_not_toward_negative_infinity():
    network = _quantized({}, [0, 0], [[0, 0], [0, 0]], output_bias=-1)
    # -1 * 400 / 8128 = -0.049 -> Java long division gives 0, Python floor division would give -1.
    assert eval_quantized_canonical(network, [], []) == 0
    network = _quantized({}, [0, 0], [[0, 0], [0, 0]], output_bias=-21)
    # -21 * 400 / 8128 = -1.0334 -> -1 (truncation); floor would give -2.
    assert eval_quantized_canonical(network, [], []) == -1


def test_accumulator_wraps_like_a_java_short_when_the_loader_bound_is_not_enforced():
    # 32767 + 1 wraps to -32768 in a Java `short`, so the activation clamps to 0, not to qa.
    network = _quantized({0: [1, 0]}, [32767, 0], [[1, 0], [0, 0]], output_bias=0)
    wrapped = eval_quantized_canonical(network, [0], [], enforce_loader_bounds=False)
    unwrapped_if_saturated = (127 * 1) * 400 // 8128
    assert wrapped == 0 and unwrapped_if_saturated == 6


def test_a_network_the_java_loader_would_reject_is_rejected_here_too():
    network = _quantized({f: [1100, 0] for f in range(40)}, [0, 0], [[1, 0], [0, 0]], output_bias=0)
    assert accumulator_bound_violations(network)
    with pytest.raises(ValueError, match="NnueNetwork.load\\(\\) would reject"):
        eval_quantized_canonical(network, [0], [])
    with pytest.raises(ValueError, match="NnueNetwork.load\\(\\) would reject"):
        eval_quantized_fens(network, [START])


def test_loader_bound_agrees_with_the_quantizer_it_is_never_stricter_than():
    # quantize() rejects bias + 32*max|w| > int16 max; the Java rule (sum of the 32 largest) is never
    # stricter, so any network the quantizer accepts is loadable here.
    for seed in range(3):
        network = quantize(synthetic_float_network(seed, ft_sigma=16, out_sigma=12))
        assert accumulator_bound_violations(network) == []


def test_dequantized_float_differs_from_the_integer_score_by_less_than_one_centipawn():
    fens = corpus_fens()
    for seed in range(3):
        network = quantize(synthetic_float_network(seed, ft_sigma=16, out_sigma=12))
        ints = eval_quantized_fens(network, fens)
        floats = eval_dequantized_float(network, fens)
        assert np.abs(ints - floats).max() < 1.0
        assert np.all(np.abs(ints) <= np.abs(floats))  # truncation toward zero never grows a magnitude


def test_float_reference_matches_the_real_pytorch_forward_pass():
    canonical = synthetic_float_network(seed=5, ft_sigma=8, out_sigma=10, hidden_width=16)
    model = NnueNet(hidden_width=16, qa=127, qb=64, output_scale=400)
    with torch.no_grad():
        model.ft.weight.copy_(torch.from_numpy(canonical.ft_weights.copy()))
        model.ft_bias.copy_(torch.from_numpy(canonical.ft_biases.copy()))
        model.output_layer.weight.copy_(torch.from_numpy(np.concatenate([canonical.output_weights[0], canonical.output_weights[1]])[None, :].copy()))
        model.output_layer.bias.copy_(torch.tensor([canonical.output_bias]))
    fens = corpus_fens()[:120]
    batch = encode_fens(fens)
    with torch.no_grad():
        torch_scores = model(batch.us_indices, batch.us_offsets, batch.them_indices, batch.them_offsets).numpy()
    np.testing.assert_allclose(eval_float_canonical(canonical, fens), torch_scores, atol=1e-2, rtol=0)


def test_fen_input_may_be_four_or_six_fields():
    network = quantize(synthetic_float_network(2, 16, 12))
    assert eval_quantized_fens(network, [START])[0] == eval_quantized_fens(network, [START + " 0 1"])[0]
