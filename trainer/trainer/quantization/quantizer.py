"""Quantizer (NNUE_TRAINER_ARCHITECTURE.md Section 7): the pure transformation
`CanonicalNetwork` (float32) -> `QuantizedCanonicalNetwork` (int16 tensors + int32
`output_bias`). No additional `qa`/`qb` scale multiply -- FT and output layer weights
already train in qa-/qb-native units, verified directly against `NnueOracle.java`'s
float64 reference oracle: `outWeights`/`ftWeights` are used unscaled, and
`outputScale/(qa*qb)` is applied exactly once at the very end of the formula. `quantize()`
is therefore round-to-nearest plus an int16 range clip for `ft_weights`/`ft_biases`/
`output_weights`; `output_bias` is a separate, wider int32 field (Section 8: `i32
outputBias`), round-to-nearest only, no clip (int32's range is not a realistic overflow
target for a centipawn-scale bias).
"""

from __future__ import annotations

import math
from dataclasses import dataclass

import numpy as np

from trainer.export.canonical import CanonicalNetwork, QuantizedCanonicalNetwork
from trainer.model.network import MAX_ACTIVE_FEATURES, validate_qa, validate_qb

INT16_MIN = -32768
INT16_MAX = 32767
INT32_MIN = -(1 << 31)
INT32_MAX = (1 << 31) - 1


@dataclass(frozen=True)
class ClippingBoundaryReport:
    """How many `ft_weights`/`ft_biases`/`output_weights` values in a `CanonicalNetwork`
    fall outside int16 range before rounding -- PRD's "Weight histogram + clipping
    report" analysis tool (Section 7). Zero across the board is the expected case; a
    nonzero count means `quantize()` silently protected an otherwise-invalid export,
    and training-time clipping (`train.py`'s `clip_ft_weights_`) or the config's
    `qa`/`qb` should be revisited.
    """

    ft_weights_clipped: int
    ft_biases_clipped: int
    output_weights_clipped: int


def _validate_finite(network: CanonicalNetwork) -> None:
    for name, array in (
        ("ft_weights", network.ft_weights),
        ("ft_biases", network.ft_biases),
        ("output_weights", network.output_weights),
    ):
        if not np.isfinite(array).all():
            raise ValueError(f"{name} contains non-finite (NaN or Inf) values")
    if not math.isfinite(network.output_bias):
        raise ValueError(f"output_bias must be finite, got {network.output_bias!r}")


def _validate_ft_accumulator_bounds(ft_weights: np.ndarray, ft_biases: np.ndarray) -> None:
    """Verify worst-case 32-active-feature accumulator sum fits in signed int16 per neuron."""
    biases_i64 = ft_biases.astype(np.int64)
    max_abs_weights_i64 = np.max(np.abs(ft_weights.astype(np.int64)), axis=0)
    worst_pos = biases_i64 + MAX_ACTIVE_FEATURES * max_abs_weights_i64
    worst_neg = biases_i64 - MAX_ACTIVE_FEATURES * max_abs_weights_i64
    if np.any(worst_pos > INT16_MAX) or np.any(worst_neg < INT16_MIN):
        raise ValueError(
            f"quantized FT accumulator worst-case range [{int(worst_neg.min())}, {int(worst_pos.max())}] "
            f"exceeds signed int16 [{INT16_MIN}, {INT16_MAX}]"
        )


def quantize(network: CanonicalNetwork) -> QuantizedCanonicalNetwork:
    """Pure function: never mutates `network` or any array it references."""
    validate_qa(network.qa)
    validate_qb(network.qb)
    _validate_finite(network)

    ft_weights = _round_and_clip(network.ft_weights)
    ft_biases = _round_and_clip(network.ft_biases)
    output_weights = _round_and_clip(network.output_weights)

    _validate_ft_accumulator_bounds(ft_weights, ft_biases)

    output_bias = int(np.round(network.output_bias))
    if output_bias < INT32_MIN or output_bias > INT32_MAX:
        raise ValueError(
            f"quantized output_bias {output_bias} out of signed int32 range [{INT32_MIN}, {INT32_MAX}]"
        )

    return QuantizedCanonicalNetwork(
        hidden_width=network.hidden_width,
        ft_weights=ft_weights,
        ft_biases=ft_biases,
        output_weights=output_weights,
        output_bias=output_bias,
        qa=network.qa,
        qb=network.qb,
        output_scale=network.output_scale,
        architecture_id=network.architecture_id,
        feature_set_id=network.feature_set_id,
    )


def clipping_report(network: CanonicalNetwork) -> ClippingBoundaryReport:
    """Independent of `quantize()`'s output -- inspects the pre-rounding float values
    directly so a caller doesn't need to reverse-engineer clipping from an
    already-clipped `QuantizedCanonicalNetwork`."""
    return ClippingBoundaryReport(
        ft_weights_clipped=_count_out_of_range(network.ft_weights),
        ft_biases_clipped=_count_out_of_range(network.ft_biases),
        output_weights_clipped=_count_out_of_range(network.output_weights),
    )


def _round_and_clip(array: np.ndarray) -> np.ndarray:
    rounded = np.round(array)
    clipped = np.clip(rounded, INT16_MIN, INT16_MAX)
    return clipped.astype(np.int16)


def _count_out_of_range(array: np.ndarray) -> int:
    rounded = np.round(array)
    return int(np.count_nonzero((rounded < INT16_MIN) | (rounded > INT16_MAX)))
