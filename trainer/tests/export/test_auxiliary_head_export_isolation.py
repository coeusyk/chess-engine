"""Regression guard for the Phase 5 architecture-scoping finding
(`docs/architecture/research/nnue/phase5-arch-scoping-auxiliary-head.md`).

That scoping investigation's central, cost-estimate-changing claim is that adding an
auxiliary output head to `NnueNet` has **zero** export/quantization/inference blast
radius: `checkpoint_to_canonical()` reads the training checkpoint's `state_dict` by
explicit key name (`ft.weight`, `ft_bias`, `output_layer.weight`, `output_layer.bias`),
so parameters belonging to any additional head are simply never read, and the exported
`.nnue` body is bit-for-bit what a primary-head-only model would have produced.

These tests exist so that claim stays *verified* rather than merely asserted. If a future
change makes the export path iterate the `state_dict`, assert a key count, or otherwise
become sensitive to extra parameters, these fail loudly and the scoping document's
conclusion (and its "Low" cost estimate for the export axis) is invalidated at the point
the assumption breaks -- not silently, months later, mid-experiment.

Nothing here changes production behavior: the probe subclass is defined in this test
module only. No auxiliary head exists in `network.py`, and this scoping pass deliberately
did not add one (design investigation, not implementation).
"""

import dataclasses

import numpy as np
import pytest
import torch
from torch import nn

from trainer.export.canonical import checkpoint_to_canonical
from trainer.model.network import NnueNet
from trainer.model.train import TrainingConfig
from trainer.quantization.quantizer import quantize

HIDDEN_WIDTH = 8
QA = 127
QB = 64
OUTPUT_SCALE = 400


class _NnueNetWithAuxHead(NnueNet):
    """Test-only probe: the primary path is inherited untouched; one extra head shares
    the feature transformer, exactly the shape the scoping document's Option A describes
    (a second `Linear(2 * hidden_width, 1)` alongside `output_layer`)."""

    def __init__(self, hidden_width: int, qa: float, qb: float, output_scale: float):
        super().__init__(hidden_width, qa, qb, output_scale)
        self.wdl_head = nn.Linear(2 * hidden_width, 1)


def _config_dict() -> dict:
    return dataclasses.asdict(
        TrainingConfig(
            hidden_width=HIDDEN_WIDTH, qa=QA, qb=QB, output_scale=OUTPUT_SCALE,
            k=2.773456, learning_rate=0.01, seed=42, steps=1, batch_size=2,
        )
    )


def _checkpoint(model: NnueNet) -> dict:
    return {"model_state_dict": model.state_dict(), "config": _config_dict()}


def _paired_models() -> "tuple[NnueNet, _NnueNetWithAuxHead]":
    """A primary-only model and an aux-head model whose *primary* parameters are
    identical -- the only fair basis for claiming the export is unaffected."""
    base = NnueNet(HIDDEN_WIDTH, QA, QB, OUTPUT_SCALE)
    aux = _NnueNetWithAuxHead(HIDDEN_WIDTH, QA, QB, OUTPUT_SCALE)
    aux.load_state_dict(base.state_dict(), strict=False)
    return base, aux


def test_aux_head_adds_only_its_own_state_dict_keys():
    base, aux = _paired_models()
    extra = set(aux.state_dict()) - set(base.state_dict())
    assert extra == {"wdl_head.weight", "wdl_head.bias"}
    # The primary keys the export path names explicitly must all still be present.
    for key in ("ft.weight", "ft_bias", "output_layer.weight", "output_layer.bias"):
        assert key in aux.state_dict()


def test_checkpoint_to_canonical_ignores_auxiliary_head_parameters():
    """The load-bearing claim: extra parameters do not break, or alter, canonicalization."""
    base, aux = _paired_models()
    canonical_base = checkpoint_to_canonical(_checkpoint(base))
    canonical_aux = checkpoint_to_canonical(_checkpoint(aux))

    assert np.array_equal(canonical_base.ft_weights, canonical_aux.ft_weights)
    assert np.array_equal(canonical_base.ft_biases, canonical_aux.ft_biases)
    assert np.array_equal(canonical_base.output_weights, canonical_aux.output_weights)
    assert canonical_base.output_bias == canonical_aux.output_bias
    # Header identity fields must not drift -- a changed architecture_id would make the
    # exported file rejected by NnueNetwork.java's SUPPORTED_ARCHITECTURE_ID check.
    assert canonical_aux.architecture_id == canonical_base.architecture_id
    assert canonical_aux.feature_set_id == canonical_base.feature_set_id


def test_quantized_export_arrays_are_bitwise_identical_with_aux_head():
    """Extends the claim through quantization -- the last stage before `.nnue` bytes."""
    base, aux = _paired_models()
    quantized_base = quantize(checkpoint_to_canonical(_checkpoint(base)))
    quantized_aux = quantize(checkpoint_to_canonical(_checkpoint(aux)))
    if isinstance(quantized_base, tuple):  # (network, report) shape
        quantized_base, quantized_aux = quantized_base[0], quantized_aux[0]

    assert np.array_equal(quantized_base.ft_weights, quantized_aux.ft_weights)
    assert np.array_equal(quantized_base.ft_biases, quantized_aux.ft_biases)
    assert np.array_equal(quantized_base.output_weights, quantized_aux.output_weights)
    assert quantized_base.output_bias == quantized_aux.output_bias


def test_aux_head_does_not_change_primary_forward_output():
    """An auxiliary head must not perturb the primary eval the engine actually consumes."""
    base, aux = _paired_models()
    us_indices, us_offsets = torch.tensor([1, 5, 9]), torch.tensor([0])
    them_indices, them_offsets = torch.tensor([2, 6, 10]), torch.tensor([0])
    with torch.no_grad():
        assert torch.allclose(
            base(us_indices, us_offsets, them_indices, them_offsets),
            aux(us_indices, us_offsets, them_indices, them_offsets),
        )


def test_aux_head_checkpoint_needs_non_strict_load_into_plain_nnuenet():
    """The one real friction point the scoping document identifies: every existing
    `_load_model()` helper loads with PyTorch's default `strict=True`, which rejects an
    aux-head checkpoint. `strict=False` accepts it and yields a usable primary-only
    model -- so the evaluation path needs a one-argument change, not a redesign."""
    _, aux = _paired_models()
    with pytest.raises(RuntimeError):
        NnueNet(HIDDEN_WIDTH, QA, QB, OUTPUT_SCALE).load_state_dict(aux.state_dict())

    primary_only = NnueNet(HIDDEN_WIDTH, QA, QB, OUTPUT_SCALE)
    missing, unexpected = primary_only.load_state_dict(aux.state_dict(), strict=False)
    assert missing == []
    assert set(unexpected) == {"wdl_head.weight", "wdl_head.bias"}
