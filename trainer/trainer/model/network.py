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

    def __init__(self, hidden_width: int, qa: float, qb: float, output_scale: float,
                  with_aux_wdl_head: bool = False):
        super().__init__()
        self.hidden_width = hidden_width
        self.qa = qa
        self.qb = qb
        self.output_scale = output_scale

        self.ft = nn.EmbeddingBag(FEATURES_PER_PERSPECTIVE, hidden_width, mode="sum")
        self.ft_bias = nn.Parameter(torch.zeros(hidden_width))
        self.output_layer = nn.Linear(2 * hidden_width, 1)
        # Experiment P5-AUXHEAD (Phase 5 candidate #3's scoped design, see
        # docs/architecture/research/nnue/phase5-p5-auxhead-design.md): an optional
        # training-only auxiliary head predicting game outcome (wdl) from the *same*
        # shared activation the primary head consumes. Deliberately NOT constructed at the
        # default -- a plain NnueNet's state_dict must keep exactly its historical key set,
        # so every pre-existing config, checkpoint, and test is unaffected bit-for-bit
        # (the same safe-default discipline TrainingConfig's mate_weight/wdl_lambda follow).
        #
        # This head is never exported and never reaches inference: checkpoint_to_canonical()
        # reads weights by explicit key name, so `wdl_head.*` is structurally invisible to
        # the export path (verified in tests/export/test_auxiliary_head_export_isolation.py).
        self.wdl_head = nn.Linear(2 * hidden_width, 1) if with_aux_wdl_head else None

    def _accumulate(self, indices: torch.Tensor, offsets: torch.Tensor) -> torch.Tensor:
        """Shared FT weights applied to one perspective's active-feature indices --
        `nn.EmbeddingBag(mode="sum")` computes exactly the accumulator sum
        `FeatureExtractor`/`NnueEvaluator` maintain incrementally, from a flat
        (indices, offsets) batch encoding rather than per-position padding.
        """
        return self.ft(indices, offsets) + self.ft_bias

    def shared_activation(
        self,
        us_indices: torch.Tensor,
        us_offsets: torch.Tensor,
        them_indices: torch.Tensor,
        them_offsets: torch.Tensor,
    ) -> torch.Tensor:
        """The clipped-ReLU, both-perspectives activation vector -- the shared
        representation every head consumes. Extracted from `forward()` (which now calls it)
        so that every head is defined against one definition of the shared representation
        rather than each re-deriving its own, subtly divergent, version. `forward()`'s
        numerical behavior is unchanged by the extraction.

        Note what this does and does not guarantee: callers that invoke this *and*
        `forward()` in the same step recompute the activation rather than reusing one
        tensor. That costs an extra feature-transformer forward pass, and is deliberate for
        clarity over micro-optimization -- it is not a correctness difference, since
        `EmbeddingBag` is deterministic (the two results are bitwise equal) and autograd
        sums the gradients of both paths into the same `ft` parameters exactly as a single
        shared tensor would.
        """
        acc_us = self._accumulate(us_indices, us_offsets)
        acc_them = self._accumulate(them_indices, them_offsets)

        activation_us = torch.clamp(acc_us, 0, self.qa)
        activation_them = torch.clamp(acc_them, 0, self.qa)

        return torch.cat([activation_us, activation_them], dim=1)

    def forward(
        self,
        us_indices: torch.Tensor,
        us_offsets: torch.Tensor,
        them_indices: torch.Tensor,
        them_offsets: torch.Tensor,
    ) -> torch.Tensor:
        combined = self.shared_activation(us_indices, us_offsets, them_indices, them_offsets)
        raw_sum = self.output_layer(combined).squeeze(-1)
        return raw_sum * self.output_scale / (self.qa * self.qb)

    def auxiliary_wdl_logit(
        self,
        us_indices: torch.Tensor,
        us_offsets: torch.Tensor,
        them_indices: torch.Tensor,
        them_offsets: torch.Tensor,
    ) -> torch.Tensor:
        """Raw logit of the training-only auxiliary WDL head (P5-AUXHEAD).

        Returns a *logit*, not a probability -- the caller pairs this with
        `binary_cross_entropy_with_logits`, which is numerically stabler than an explicit
        sigmoid followed by BCE. Gradient from this path reaches the shared feature
        transformer and this head only; it never touches `output_layer`, which is what
        keeps the primary evaluation objective uncontaminated (design record §1).
        """
        if self.wdl_head is None:
            raise ValueError(
                "auxiliary_wdl_logit() requires a model built with with_aux_wdl_head=True"
            )
        combined = self.shared_activation(us_indices, us_offsets, them_indices, them_offsets)
        return self.wdl_head(combined).squeeze(-1)

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
