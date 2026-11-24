"""Scale-utilization diagnostics for a float32 network (issue #265).

`compute_quantization_parity()` says how far the quantized network is from the float32 one. This
module says why it might be: how much of the int16 and QA range the network actually uses. It is
the measurable form of the concern in #265, that FT weights of order 1 are rounded with a step of
1.0 and then multiplied by large dense output weights, so rounding noise is amplified.

Everything here is a measurement of one network on one FEN set. Nothing is a threshold; whether a
value is acceptable is a separate decision that needs real checkpoints (none is in this repo).

Definitions (accumulators are pre-clamp, in QA-native units, both perspectives pooled):

- `frac_accumulator_below_zero`, `frac_accumulator_in_range`, `frac_accumulator_at_or_above_qa`:
  how the clamp `[0, qa]` treats the accumulators. A network whose activations never reach `qa`
  (`frac_accumulator_at_or_above_qa == 0`) is using a small part of the activation range, the
  regime the committed P5-AUXHEAD diagnostics describe.
- `activation_mean`, `activation_rms`: of the clamped activations, in the same units (the figures
  quoted in #265 are `mean` 1.47 to 2.35 and RMS 4.61 for P5-AUXHEAD-001).
- `dead_neuron_fraction`: neurons whose accumulator is at or below zero for every sampled
  position and perspective, so the clamp zeroes them everywhere.
- `ft_weight_*`, `output_weight_*`: spread of the weights that will be rounded to int16.
  `ft_weight_fraction_rounding_to_zero` counts FT weights with `|w| < 0.5`, which become exactly 0.
- `ft_rounding_error_to_weight_rms_ratio`: RMS of the actual rounding error over RMS of the FT
  weights. Close to 0 means rounding is small against the weights; near or above 0.29 / rms of
  1.0 means rounding noise is comparable to the signal.
- `cp_per_accumulator_unit_rms` and `_max`: centipawns that move when one accumulator unit moves
  on an unclamped neuron, `|w_out| * output_scale / (qa * qb)`, over the output weights. This is
  the amplification factor: multiplied by the rounding error per accumulator it bounds the score
  noise that rounding can add.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, asdict
from typing import Any, Dict, Sequence

import numpy as np

from trainer.export.canonical import CanonicalNetwork
from trainer.quantization.inference import _feature_lists

INT16_MAX = 32767


@dataclass(frozen=True)
class ScaleUtilizationReport:
    position_count: int
    accumulator_count: int
    qa: int
    frac_accumulator_below_zero: float
    frac_accumulator_in_range: float
    frac_accumulator_at_or_above_qa: float
    accumulator_max: float
    activation_mean: float
    activation_rms: float
    dead_neuron_fraction: float
    ft_weight_rms: float
    ft_weight_max_abs: float
    ft_weight_int16_headroom_ratio: float
    ft_weight_fraction_rounding_to_zero: float
    ft_rounding_error_to_weight_rms_ratio: float
    output_weight_rms: float
    output_weight_l2_norm: float
    output_weight_max_abs: float
    output_weight_int16_headroom_ratio: float
    cp_per_accumulator_unit_rms: float
    cp_per_accumulator_unit_max: float

    def to_manifest_dict(self) -> Dict[str, Any]:
        """Plain JSON-safe dict, written under `scale_utilization` in the provenance manifest."""
        return asdict(self)


def _rms(values: np.ndarray) -> float:
    return float(math.sqrt(float(np.mean(np.square(values, dtype=np.float64)))))


def compute_scale_utilization(canonical: CanonicalNetwork, fens: Sequence[str]) -> ScaleUtilizationReport:
    """Measures how `canonical` uses the QA activation range on `fens`, and how large its weights
    are relative to the int16 rounding step. Deterministic and torch-free."""
    if not len(fens):
        raise ValueError("compute_scale_utilization requires at least one FEN")

    qa = float(canonical.qa)
    ft_weights = canonical.ft_weights.astype(np.float64)
    rows = []
    for fen in fens:
        for features in _feature_lists(fen):
            accumulator = canonical.ft_biases.astype(np.float64).copy()
            if len(features):
                accumulator = accumulator + ft_weights[list(features)].sum(axis=0)
            rows.append(accumulator)
    accumulators = np.stack(rows)  # [positions * 2, hidden_width]
    activations = np.clip(accumulators, 0.0, qa)

    ft_rounding_error = np.round(ft_weights) - ft_weights
    ft_rms = _rms(ft_weights)
    output_weights = canonical.output_weights.astype(np.float64)
    output_abs = np.abs(output_weights)
    cp_per_unit = output_abs * canonical.output_scale / (canonical.qa * canonical.qb)

    return ScaleUtilizationReport(
        position_count=len(fens),
        accumulator_count=int(accumulators.size),
        qa=canonical.qa,
        frac_accumulator_below_zero=float(np.mean(accumulators < 0.0)),
        frac_accumulator_in_range=float(np.mean((accumulators >= 0.0) & (accumulators < qa))),
        frac_accumulator_at_or_above_qa=float(np.mean(accumulators >= qa)),
        accumulator_max=float(accumulators.max()),
        activation_mean=float(activations.mean()),
        activation_rms=_rms(activations),
        dead_neuron_fraction=float(np.mean(np.all(accumulators <= 0.0, axis=0))),
        ft_weight_rms=ft_rms,
        ft_weight_max_abs=float(np.abs(ft_weights).max()),
        ft_weight_int16_headroom_ratio=float(np.abs(ft_weights).max() / INT16_MAX),
        ft_weight_fraction_rounding_to_zero=float(np.mean(np.abs(ft_weights) < 0.5)),
        ft_rounding_error_to_weight_rms_ratio=0.0 if ft_rms == 0 else _rms(ft_rounding_error) / ft_rms,
        output_weight_rms=_rms(output_weights),
        output_weight_l2_norm=float(np.linalg.norm(output_weights)),
        output_weight_max_abs=float(output_abs.max()),
        output_weight_int16_headroom_ratio=float(output_abs.max() / INT16_MAX),
        cp_per_accumulator_unit_rms=_rms(cp_per_unit),
        cp_per_accumulator_unit_max=float(cp_per_unit.max()),
    )
