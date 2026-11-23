"""Reference inference for a quantized Vex network, in Python (issue #265).

`eval_quantized_canonical()` is a bit-for-bit mirror of `NnueEvaluator.evaluate()` in the
engine, so the trainer can measure what the *exported* `.nnue` will actually score, not what
the float32 checkpoint scored. It reuses the existing numerical contract unchanged: weights
are used exactly as stored (no extra `qa`/`qb` scaling here or in `quantize()`), and
`output_scale / (qa * qb)` is applied once, at the very end, as in `NnueNet.forward()`.

The arithmetic, step by step, and where it lives in Java:

1. Each perspective's accumulator is `ft_biases + sum(ft_weights[feature])` in **int16 with
   wraparound** (`NnueEvaluator.addFeature`: `(short) (acc + w)`). Wraparound is modular, so
   the order and the incremental add/remove sequence in the engine do not matter: the result
   equals a from-scratch sum reduced modulo 2^16 into the signed range. A network whose
   accumulator can wrap is rejected by `NnueNetwork.load()` (issue #264), so this module
   refuses it too (`assert_java_loadable`): a parity claim against a net the engine will not
   load would be meaningless.
2. Activation: `clamp(acc, 0, qa)` per hidden unit, both perspectives (`NnueEvaluator.clamp`).
3. `sum = dot(us_activation, output_weights[0]) + dot(them_activation, output_weights[1])
   + output_bias` in 64-bit integers.
4. `score = (int) (sum * output_scale / (qa * qb))`: `long` multiplication, `long` division
   (**truncation toward zero**, not floor and not round-to-nearest), then a narrowing cast
   to `int`. Both the 64-bit wrap and the 32-bit cast are reproduced so the claim is exact
   even for absurd inputs.

`eval_float_canonical()` is the float32 reference: the same network before quantization,
evaluated the way `NnueNet.forward()` does (float32 accumulate, `clamp(0, qa)`, no integer
truncation, no rounding of weights). `eval_dequantized_float()` is the float64 evaluation of
the *quantized* weights, the Python counterpart of `NnueOracle.float32Evaluate`; it separates
weight-rounding error from integer-arithmetic error in a parity report.

None of this imports torch. Feature extraction reuses `trainer.encoding.feature_encoder`, the
same index function the training batches use.
"""

from __future__ import annotations

from typing import Iterable, List, Sequence

import numpy as np

from trainer.encoding.feature_encoder import BLACK, WHITE, active_feature_indices
from trainer.export.canonical import CanonicalNetwork, QuantizedCanonicalNetwork
from trainer.model.network import MAX_ACTIVE_FEATURES

_INT16_MIN = -32768
_INT64_MIN = -(1 << 63)


def _wrap_int16(value: np.ndarray) -> np.ndarray:
    """Reduces int64 values into the signed 16-bit range, as Java's `(short)` cast does."""
    return ((value + 32768) & 0xFFFF) - 32768


def _wrap_int64(value: int) -> int:
    return ((value - _INT64_MIN) % (1 << 64)) + _INT64_MIN


def _wrap_int32(value: int) -> int:
    return ((value + (1 << 31)) % (1 << 32)) - (1 << 31)


def _truncating_division(numerator: int, denominator: int) -> int:
    """Java `long` division: the quotient is rounded toward zero. Python's `//` floors."""
    quotient = abs(numerator) // denominator
    return -quotient if numerator < 0 else quotient


def accumulator_bound_violations(network: QuantizedCanonicalNetwork) -> List[str]:
    """Mirrors `NnueNetwork.validateAccumulatorBounds` (issue #264): for each hidden neuron,
    bias + (sum of the `MAX_ACTIVE_FEATURES` largest positive weights) must not exceed int16
    max and bias + (sum of the `MAX_ACTIVE_FEATURES` most negative weights) must not fall
    below int16 min. Returns one message per violating neuron, empty if the net would load.
    """
    weights = np.sort(network.ft_weights.astype(np.int64), axis=0)
    biases = network.ft_biases.astype(np.int64)
    positive = np.maximum(weights[-MAX_ACTIVE_FEATURES:, :], 0).sum(axis=0)
    negative = np.minimum(weights[:MAX_ACTIVE_FEATURES, :], 0).sum(axis=0)
    problems: List[str] = []
    for neuron in np.nonzero(biases + positive > 32767)[0]:
        problems.append(f"neuron {int(neuron)}: worst-case accumulator {int(biases[neuron] + positive[neuron])} > 32767")
    for neuron in np.nonzero(biases + negative < _INT16_MIN)[0]:
        problems.append(f"neuron {int(neuron)}: worst-case accumulator {int(biases[neuron] + negative[neuron])} < -32768")
    return problems


def assert_java_loadable(network: QuantizedCanonicalNetwork) -> None:
    problems = accumulator_bound_violations(network)
    if problems:
        raise ValueError(
            "NnueNetwork.load() would reject this network (accumulator overflow risk, issue #264), "
            "so Java parity is undefined: " + "; ".join(problems[:3]) + (" ..." if len(problems) > 3 else "")
        )


def eval_quantized_canonical(
    network: QuantizedCanonicalNetwork,
    active_us: Sequence[int],
    active_them: Sequence[int],
    *,
    enforce_loader_bounds: bool = True,
) -> int:
    """Centipawn score for one position, from the side to move's point of view, exactly as
    `NnueEvaluator.evaluate()` computes it. `active_us`/`active_them` are the active feature
    indices from the mover's and the opponent's perspective (`active_feature_indices`).
    """
    if enforce_loader_bounds:
        assert_java_loadable(network)
    qa = int(network.qa)
    activations = []
    for features in (active_us, active_them):
        accumulator = network.ft_biases.astype(np.int64) + (
            network.ft_weights[list(features)].astype(np.int64).sum(axis=0) if len(features) else 0
        )
        activations.append(np.clip(_wrap_int16(accumulator), 0, qa))
    total = int(activations[0] @ network.output_weights[0].astype(np.int64)) \
        + int(activations[1] @ network.output_weights[1].astype(np.int64)) \
        + int(network.output_bias)
    scaled = _wrap_int64(total * int(network.output_scale))
    return _wrap_int32(_truncating_division(scaled, _wrap_int64(qa * int(network.qb))))


def _feature_lists(fen: str) -> tuple[List[int], List[int]]:
    mover = WHITE if fen.split(" ")[1] == "w" else BLACK
    opponent = BLACK if mover == WHITE else WHITE
    return active_feature_indices(fen, mover), active_feature_indices(fen, opponent)


def eval_quantized_fens(network: QuantizedCanonicalNetwork, fens: Iterable[str]) -> np.ndarray:
    """`eval_quantized_canonical` over a FEN list, as an int64 array. FENs may be 4- or
    6-field, like the corpus files."""
    assert_java_loadable(network)
    return np.array(
        [eval_quantized_canonical(network, *_feature_lists(fen), enforce_loader_bounds=False) for fen in fens],
        dtype=np.int64,
    )


def eval_float_canonical(network: CanonicalNetwork, fens: Iterable[str]) -> np.ndarray:
    """Float32 reference: `NnueNet.forward()` without torch. Float32 accumulation and
    activation, the same `clamp(0, qa)`, and `raw * output_scale / (qa * qb)` with no
    integer truncation. Returned as float64 so metric arithmetic adds no further rounding.
    """
    qa = np.float32(network.qa)
    scores = []
    for fen in fens:
        us, them = _feature_lists(fen)
        activations = []
        for features in (us, them):
            accumulator = network.ft_biases.astype(np.float32).copy()
            if len(features):
                accumulator = accumulator + network.ft_weights[list(features)].astype(np.float32).sum(axis=0, dtype=np.float32)
            activations.append(np.clip(accumulator, np.float32(0), qa))
        raw = (activations[0] @ network.output_weights[0].astype(np.float32)
               + activations[1] @ network.output_weights[1].astype(np.float32)
               + np.float32(network.output_bias))
        scores.append(float(raw) * network.output_scale / (network.qa * network.qb))
    return np.array(scores, dtype=np.float64)


def eval_dequantized_float(network: QuantizedCanonicalNetwork, fens: Iterable[str]) -> np.ndarray:
    """Float64 evaluation of the *quantized* weights with the same clamp and final scale, no
    integer truncation: the Python counterpart of `NnueOracle.float32Evaluate`. Differs from
    `eval_quantized_canonical` only by integer truncation (under 1 cp), so a larger gap is a
    real arithmetic mismatch, not quantization loss.
    """
    qa = float(network.qa)
    scores = []
    for fen in fens:
        us, them = _feature_lists(fen)
        activations = []
        for features in (us, them):
            accumulator = network.ft_biases.astype(np.float64).copy()
            if len(features):
                accumulator = accumulator + network.ft_weights[list(features)].astype(np.float64).sum(axis=0)
            activations.append(np.clip(accumulator, 0.0, qa))
        raw = (activations[0] @ network.output_weights[0].astype(np.float64)
               + activations[1] @ network.output_weights[1].astype(np.float64)
               + float(network.output_bias))
        scores.append(raw * network.output_scale / (network.qa * network.qb))
    return np.array(scores, dtype=np.float64)
