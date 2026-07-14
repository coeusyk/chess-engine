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

from dataclasses import dataclass

import numpy as np

from trainer.export.canonical import CanonicalNetwork, QuantizedCanonicalNetwork

INT16_MIN = -32768
INT16_MAX = 32767


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


def quantize(network: CanonicalNetwork) -> QuantizedCanonicalNetwork:
    """Pure function: never mutates `network` or any array it references."""
    return QuantizedCanonicalNetwork(
        hidden_width=network.hidden_width,
        ft_weights=_round_and_clip(network.ft_weights),
        ft_biases=_round_and_clip(network.ft_biases),
        output_weights=_round_and_clip(network.output_weights),
        output_bias=int(np.round(network.output_bias)),
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
