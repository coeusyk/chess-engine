"""Float32-checkpoint versus quantized-network parity (issue #265).

`validator.py` evaluates the float32 `NnueNet`. Nothing before this module ran the *exported*
integer network, so quantization loss was invisible. `compute_quantization_parity()` scores a
position set with both the float32 canonical network and its `quantize()` result (evaluated by
`eval_quantized_canonical`, the bit-exact mirror of the engine's `NnueEvaluator`) and reports how
far apart they are, in centipawns.

What a report means, and what it does not:

- `mae_cp`, `rmse_cp`, `max_abs_cp`, `mean_signed_cp` are quantized minus float32, on the corpus
  you pass. They describe that corpus only; there is no claim about positions outside it.
- `weight_rounding_mae_cp` (float32 versus the float64 evaluation of the *rounded* weights) and
  `integer_arithmetic_max_abs_cp` (that float64 evaluation versus the integer score) split the
  error into its two sources. The second is integer truncation toward zero and is always under
  1 cp for a well-formed network; anything larger would mean Python and Java arithmetic disagree.
- Correlations: `pearson_float_quantized` is between the two score vectors. When labels are
  given, `label_correlation_float`, `label_correlation_quantized` and their absolute difference
  show whether quantization changed how well the net tracks the labels.
- Release thresholds are NOT defined here. `ParityThresholds` fields all default to `None`
  (report only). The values suggested in issue #265 (MAE 5 cp, max 25 cp on non-extreme
  positions, label correlation delta 0.005) are provisional and have never been calibrated
  against a real checkpoint, because no real checkpoint is available in this repository.
"""

from __future__ import annotations

import hashlib
import math
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence, Tuple

import numpy as np

from trainer.export.canonical import CanonicalNetwork, QuantizedCanonicalNetwork
from trainer.export.exporter import DatasetComposition, ExportResult, export
from trainer.quantization.inference import (
    eval_dequantized_float,
    eval_float_canonical,
    eval_quantized_fens,
)
from trainer.quantization.quantizer import quantize
from trainer.validation.scale_utilization import compute_scale_utilization
from trainer.reproducibility.experiment_metadata import ExperimentMetadata


@dataclass(frozen=True)
class QuantizationParityReport:
    position_count: int
    mae_cp: float
    rmse_cp: float
    max_abs_cp: float
    mean_signed_cp: float
    pearson_float_quantized: float
    weight_rounding_mae_cp: float
    integer_arithmetic_max_abs_cp: float
    label_correlation_float: Optional[float]
    label_correlation_quantized: Optional[float]
    label_correlation_delta: Optional[float]
    fen_corpus_sha256: str
    labels_sha256: Optional[str]
    qa: int
    qb: int
    output_scale: int

    def to_manifest_dict(self) -> Dict[str, Any]:
        """Plain JSON-safe dict, written under `quantization_parity` in the provenance manifest."""
        return asdict(self)


@dataclass(frozen=True)
class ParityThresholds:
    """Upper bounds, each optional. All `None` means "report only"; there is deliberately no
    default numeric gate (see the module docstring)."""

    max_mae_cp: Optional[float] = None
    max_rmse_cp: Optional[float] = None
    max_abs_cp: Optional[float] = None
    max_label_correlation_delta: Optional[float] = None
    min_pearson_float_quantized: Optional[float] = None


class QuantizationParityError(ValueError):
    def __init__(self, violations: List[str], report: QuantizationParityReport):
        super().__init__("quantization parity gate failed: " + "; ".join(violations))
        self.violations = violations
        self.report = report


def _pearson(a: np.ndarray, b: np.ndarray) -> float:
    a_centered, b_centered = a - a.mean(), b - b.mean()
    denominator = math.sqrt(float((a_centered ** 2).sum()) * float((b_centered ** 2).sum()))
    # Same convention as validator._pearson_correlation: a constant vector has no correlation.
    return 0.0 if denominator == 0 else float((a_centered * b_centered).sum()) / denominator


def fen_corpus_sha256(fens: Sequence[str]) -> str:
    """Identity of the validation input, in order: SHA-256 of the FENs joined by newlines."""
    return hashlib.sha256("\n".join(fens).encode("utf-8")).hexdigest()


def labels_sha256(labels: Sequence[float]) -> str:
    """Identity of the label vector: SHA-256 of its float64 little-endian bytes, in order."""
    return hashlib.sha256(np.asarray(labels, dtype="<f8").tobytes()).hexdigest()


def compute_quantization_parity(
    canonical: CanonicalNetwork,
    fens: Sequence[str],
    labels_cp: Optional[Sequence[float]] = None,
) -> Tuple[QuantizationParityReport, QuantizedCanonicalNetwork]:
    """Quantizes `canonical` with the production `quantize()` and compares the two networks on
    `fens`. Returns the report and the quantized network that was measured, so the caller exports
    exactly what was validated. Deterministic: same inputs, same report, no randomness.
    """
    if not len(fens):
        raise ValueError("compute_quantization_parity requires at least one FEN")
    if labels_cp is not None and len(labels_cp) != len(fens):
        raise ValueError(f"labels_cp has {len(labels_cp)} entries for {len(fens)} FENs")

    quantized = quantize(canonical)
    float_scores = eval_float_canonical(canonical, fens)
    int_scores = eval_quantized_fens(quantized, fens).astype(np.float64)
    dequantized_scores = eval_dequantized_float(quantized, fens)

    delta = int_scores - float_scores
    label_float = label_quantized = label_delta = None
    if labels_cp is not None:
        labels = np.asarray(labels_cp, dtype=np.float64)
        label_float = _pearson(float_scores, labels)
        label_quantized = _pearson(int_scores, labels)
        label_delta = abs(label_float - label_quantized)

    report = QuantizationParityReport(
        position_count=len(fens),
        mae_cp=float(np.abs(delta).mean()),
        rmse_cp=float(math.sqrt(float((delta ** 2).mean()))),
        max_abs_cp=float(np.abs(delta).max()),
        mean_signed_cp=float(delta.mean()),
        pearson_float_quantized=_pearson(float_scores, int_scores),
        weight_rounding_mae_cp=float(np.abs(dequantized_scores - float_scores).mean()),
        integer_arithmetic_max_abs_cp=float(np.abs(int_scores - dequantized_scores).max()),
        label_correlation_float=label_float,
        label_correlation_quantized=label_quantized,
        label_correlation_delta=label_delta,
        fen_corpus_sha256=fen_corpus_sha256(fens),
        labels_sha256=None if labels_cp is None else labels_sha256(labels_cp),
        qa=canonical.qa,
        qb=canonical.qb,
        output_scale=canonical.output_scale,
    )
    return report, quantized


def threshold_violations(report: QuantizationParityReport, thresholds: ParityThresholds) -> List[str]:
    """One message per exceeded bound. Empty means the report passes (or no bound was set)."""
    found: List[str] = []

    def over(name: str, value: Optional[float], limit: Optional[float]) -> None:
        if limit is not None and value is not None and value > limit:
            found.append(f"{name} {value:.4f} > {limit}")

    over("mae_cp", report.mae_cp, thresholds.max_mae_cp)
    over("rmse_cp", report.rmse_cp, thresholds.max_rmse_cp)
    over("max_abs_cp", report.max_abs_cp, thresholds.max_abs_cp)
    if thresholds.max_label_correlation_delta is not None and report.label_correlation_delta is None:
        # A bound that cannot be evaluated must not pass silently.
        found.append("max_label_correlation_delta was set but no labels were given")
    over("label_correlation_delta", report.label_correlation_delta, thresholds.max_label_correlation_delta)
    if (thresholds.min_pearson_float_quantized is not None
            and report.pearson_float_quantized < thresholds.min_pearson_float_quantized):
        found.append(f"pearson_float_quantized {report.pearson_float_quantized:.6f} < "
                     f"{thresholds.min_pearson_float_quantized}")
    return found


def quantize_validate_export(
    canonical: CanonicalNetwork,
    experiment_metadata: ExperimentMetadata,
    dataset_composition: List[DatasetComposition],
    output_dir: Path,
    fens: Sequence[str],
    labels_cp: Optional[Sequence[float]] = None,
    thresholds: Optional[ParityThresholds] = None,
    label_engine_version: Optional[str] = None,
    utilization_max_positions: int = 5000,
) -> Tuple[ExportResult, QuantizationParityReport]:
    """Quantize, measure float32-versus-quantized parity on `fens`, gate it, then export with
    the report recorded in the provenance manifest. When `thresholds` is exceeded this raises
    `QuantizationParityError` before anything is written, so a rejected network never reaches
    `output_dir`. With no thresholds the report is still computed and recorded. The scale
    utilization of the float32 network (`scale_utilization.py`) is measured on the first
    `utilization_max_positions` FENs and recorded next to it.
    """
    report, quantized = compute_quantization_parity(canonical, fens, labels_cp)
    if thresholds is not None:
        violations = threshold_violations(report, thresholds)
        if violations:
            raise QuantizationParityError(violations, report)
    utilization = compute_scale_utilization(canonical, list(fens)[:utilization_max_positions])
    result = export(quantized, experiment_metadata, dataset_composition, output_dir,
                    label_engine_version=label_engine_version,
                    quantization_parity=report.to_manifest_dict(),
                    scale_utilization=utilization.to_manifest_dict())
    return result, report
