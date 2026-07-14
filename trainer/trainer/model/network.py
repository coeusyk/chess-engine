"""The NNUE `nn.Module` (NNUE_TRAINER_ARCHITECTURE.md Section 6): `(768 -> hiddenWidth)
x 2 -> 1`, clipped ReLU, feature-transformer weights shared between perspectives. Only
`hidden_width` is configurable -- general topology config was rejected as
untested-surface overengineering (PRD Appendix A item 3).

The forward pass deliberately mirrors NnueEvaluator.java's/NnueOracle.java's own
formula exactly (`sum = Sigma clamp(acc, qa) * outWeight + outputBias; eval = sum *
outputScale / (qa * qb)`, read directly from
engine-core/.../NnueEvaluator.java:193-199) so a trained model's float-space output is
already on the same centipawn scale inference will eventually produce -- not a
coincidence, the two must agree for the K-calibrated loss (train.py) to be meaningful.
"""

from __future__ import annotations

from typing import Tuple

import torch
from torch import nn

from trainer.encoding.feature_spec import load_feature_spec

FEATURES_PER_PERSPECTIVE = load_feature_spec().features_per_perspective

# Matches FeatureExtractor.activeFeatureIndices()'s own buffer size
# (engine-core/.../FeatureExtractor.java: `int[] buffer = new int[32];`) -- the
# absolute maximum pieces a legal chess position can have, not a tunable hyperparameter.
MAX_ACTIVE_FEATURES = 32

INT16_MAX = 32767


def derive_weight_clip_bounds(qa: float) -> Tuple[float, float]:
    """Overflow-safety clipping bounds for the FT layer (architecture doc Section 7:
    "(bias + Sigma weights of the worst-case ~32 active features) cannot exceed int16
    range after quantization... derived from QA, not hardcoded").

    FT weights/bias are trained directly in QA-scaled (int16-native) float units --
    confirmed by NnueOracle.java's float64 reference oracle clamping to the *same* qa
    ceiling as the int16 path (`clamp(us[i], qa)` for both), not a normalized [0, 1]
    range multiplied by qa only at export. Quantization is therefore round-to-nearest
    for the FT layer, not an additional x qa scale -- so INT16_MAX is the real overflow
    budget these float weights/bias must respect.

    Reserves exactly `qa` worth of that budget for the bias (the bias operates on the
    same natural scale as the qa-bounded activation itself) and splits the remainder
    evenly across up to `MAX_ACTIVE_FEATURES` weight contributions, so the worst case
    (bias_clip + MAX_ACTIVE_FEATURES * weight_clip) exactly saturates INT16_MAX without
    exceeding it.

    Returns (bias_clip, weight_clip).
    """
    bias_clip = qa
    weight_clip = (INT16_MAX - qa) / MAX_ACTIVE_FEATURES
    return bias_clip, weight_clip


class NnueNet(nn.Module):
    """`(768 -> hidden_width) x 2 -> 1`. `qa`/`qb`/`output_scale` are the same
    quantization-scale fields `NnueNetwork.java` stores in the `.nnue` header
    (architecture doc Section 8) -- carried here only to keep the float forward pass
    numerically aligned with eventual int16 inference, never quantized or exported by
    this module (Section 6: "the model has no knowledge of `.nnue`'s byte layout").
    """

    def __init__(self, hidden_width: int, qa: float, qb: float, output_scale: float):
        super().__init__()
        self.hidden_width = hidden_width
        self.qa = qa
        self.qb = qb
        self.output_scale = output_scale

        self.ft = nn.EmbeddingBag(FEATURES_PER_PERSPECTIVE, hidden_width, mode="sum")
        self.ft_bias = nn.Parameter(torch.zeros(hidden_width))
        self.output_layer = nn.Linear(2 * hidden_width, 1)

    def _accumulate(self, indices: torch.Tensor, offsets: torch.Tensor) -> torch.Tensor:
        """Shared FT weights applied to one perspective's active-feature indices --
        `nn.EmbeddingBag(mode="sum")` computes exactly the accumulator sum
        `FeatureExtractor`/`NnueEvaluator` maintain incrementally, from a flat
        (indices, offsets) batch encoding rather than per-position padding.
        """
        return self.ft(indices, offsets) + self.ft_bias

    def forward(
        self,
        us_indices: torch.Tensor,
        us_offsets: torch.Tensor,
        them_indices: torch.Tensor,
        them_offsets: torch.Tensor,
    ) -> torch.Tensor:
        acc_us = self._accumulate(us_indices, us_offsets)
        acc_them = self._accumulate(them_indices, them_offsets)

        activation_us = torch.clamp(acc_us, 0, self.qa)
        activation_them = torch.clamp(acc_them, 0, self.qa)

        combined = torch.cat([activation_us, activation_them], dim=1)
        raw_sum = self.output_layer(combined).squeeze(-1)
        return raw_sum * self.output_scale / (self.qa * self.qb)

    def clip_ft_weights_(self) -> None:
        """Applies the overflow-safety clip (`derive_weight_clip_bounds`) to the FT
        layer's weights and bias in place -- called after every optimizer step
        (architecture doc Section 6: a `Trainer`-owned continuous constraint, not a
        one-shot post-hoc fix).
        """
        bias_clip, weight_clip = derive_weight_clip_bounds(self.qa)
        with torch.no_grad():
            self.ft.weight.clamp_(-weight_clip, weight_clip)
            self.ft_bias.clamp_(-bias_clip, bias_clip)
