"""Validator (NNUE_TRAINER_ARCHITECTURE.md Section 11 "Training level", PRD
"Evaluation Strategy" item 3, issue #197): held-out loss, label correlation, and
an eval-scale check against classical evaluation for a trained checkpoint.

Scope boundary (deliberate, not a gap): `eval_scale_check()` is the *consuming*
half of a comparison against classical evaluation -- it needs a
classical-eval-labeled position corpus as input, which does not yet exist
anywhere in this repository (`bench/nnue-corpus/golden-evals.csv` is pinned to
the synthetic CI test net via `TestNetworks.synthetic(8)`, not classical
evaluation -- confirmed by reading it directly, not assumed). Generating a real
classical-eval corpus needs a small Java test-scope tool analogous to
`NnueCorpusGenerator` (`engine-core/src/test/java/.../eval/nnue/`); building
that is a separate, scoped addition, not a side effect of this Python-focused
PR. `eval_scale_check()` itself is fully implemented and tested against a
hand-built fixture now, so its own logic is verified independently of that
corpus's existence -- wiring it to a real corpus is later work.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable, List

import torch

from trainer.contracts import PositionRecord
from trainer.model.batching import encode_batch, encode_fens
from trainer.model.network import NnueNet
from trainer.model.train import target_cp, texel_sigmoid


@dataclass(frozen=True)
class ValidationReport:
    held_out_loss: float
    label_correlation: float
    position_count: int


@dataclass(frozen=True)
class ClassicalEvalRecord:
    """One position with its classical-engine evaluation, in centipawns,
    side-to-move-relative (matching `PositionLabel.eval_cp`'s own convention)."""

    fen: str
    classical_eval_cp: int


@dataclass(frozen=True)
class EvalScaleCheck:
    mean_absolute_difference_cp: float
    position_count: int


def evaluate_held_out(model: NnueNet, records: Iterable[PositionRecord], k: float) -> ValidationReport:
    """Held-out loss (the same texel-sigmoid MSE `train.py`'s training loop
    minimizes) and label correlation, over records the model was not trained on.
    """
    records = list(records)
    if not records:
        raise ValueError("evaluate_held_out requires at least one record")

    batch = encode_batch(records)
    target_cps = torch.tensor([target_cp(r.label) for r in records], dtype=torch.float32)
    targets = texel_sigmoid(target_cps, k)

    with torch.no_grad():
        predicted_cp = model(batch.us_indices, batch.us_offsets, batch.them_indices, batch.them_offsets)
        predicted_prob = texel_sigmoid(predicted_cp, k)
        loss = torch.mean((predicted_prob - targets) ** 2).item()
        correlation = _pearson_correlation(predicted_cp, target_cps)

    return ValidationReport(held_out_loss=loss, label_correlation=correlation, position_count=len(records))


def eval_scale_check(model: NnueNet, records: Iterable[ClassicalEvalRecord]) -> EvalScaleCheck:
    """Compares the trained net's raw centipawn output against a pre-computed
    classical-eval-labeled corpus (PRD "Trainer Requirements": the net's output
    must land on classical's calibrated centipawn scale, since search margins
    are tuned to that scale). See module docstring for the real corpus's status.
    """
    records = list(records)
    if not records:
        raise ValueError("eval_scale_check requires at least one record")

    batch = encode_fens(r.fen for r in records)
    with torch.no_grad():
        predicted_cp = model(batch.us_indices, batch.us_offsets, batch.them_indices, batch.them_offsets)

    classical_cps = torch.tensor([r.classical_eval_cp for r in records], dtype=torch.float32)
    mean_absolute_difference_cp = torch.mean(torch.abs(predicted_cp - classical_cps)).item()

    return EvalScaleCheck(
        mean_absolute_difference_cp=mean_absolute_difference_cp,
        position_count=len(records),
    )


def _pearson_correlation(a: torch.Tensor, b: torch.Tensor) -> float:
    a = a.detach()
    b = b.detach()
    a_centered = a - a.mean()
    b_centered = b - b.mean()
    denominator = torch.sqrt((a_centered**2).sum() * (b_centered**2).sum())
    if denominator == 0:
        return 0.0
    return ((a_centered * b_centered).sum() / denominator).item()
