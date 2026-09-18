"""Experiment P5-AUXHEAD-RMS-001: parameter-free auxiliary-input RMS normalization.

Behavioral contract, written test-first against the design record
`docs/architecture/research/nnue/phase5-p5-auxhead-rms-design.md`.

The treatment normalizes only the auxiliary head's input, per sample, by its own RMS. The
load-bearing properties this suite protects: the primary forward path is byte-for-byte
unaffected, the default (aux_rms_norm=False) path is exactly the pre-existing unnormalized
auxiliary head, the normalization introduces no parameters (so the state_dict and export
contract are unchanged), and the treatment does not break gradient isolation or the export
path. The normalization must also be finite for an all-zero activation because of the eps
floor.
"""

import dataclasses

import numpy as np
import torch

from trainer.export.canonical import checkpoint_to_canonical
from trainer.model.network import NnueNet
from trainer.model.train import TrainingConfig
from trainer.quantization.quantizer import quantize

HIDDEN_WIDTH = 8
QA = 127
QB = 64
OUTPUT_SCALE = 400


def _single_position():
    us_i, us_o = torch.tensor([1, 5, 9]), torch.tensor([0])
    them_i, them_o = torch.tensor([2, 6, 10]), torch.tensor([0])
    return us_i, us_o, them_i, them_o


def _multi_position():
    """Three positions with different active-feature counts, so their shared activations
    have genuinely different magnitudes -- the case per-sample RMS normalization must handle."""
    us_i = torch.tensor([1, 5, 9, 2, 7, 3, 11, 13])
    us_o = torch.tensor([0, 3, 5])
    them_i = torch.tensor([4, 8, 6, 10, 12, 0])
    them_o = torch.tensor([0, 2, 5])
    return us_i, us_o, them_i, them_o


def _config_dict(**overrides) -> dict:
    base = dict(
        hidden_width=HIDDEN_WIDTH, qa=QA, qb=QB, output_scale=OUTPUT_SCALE,
        k=2.773456, learning_rate=0.01, seed=42, steps=1, batch_size=2,
    )
    base.update(overrides)
    return dataclasses.asdict(TrainingConfig(**base))


# --- flag defaults and state-dict contract -----------------------------------------

def test_default_flag_is_false():
    assert NnueNet(HIDDEN_WIDTH, QA, QB, OUTPUT_SCALE).aux_rms_norm is False
    assert NnueNet(HIDDEN_WIDTH, QA, QB, OUTPUT_SCALE, with_aux_wdl_head=True).aux_rms_norm is False


def test_rms_norm_adds_no_state_dict_keys():
    plain_aux = NnueNet(HIDDEN_WIDTH, QA, QB, OUTPUT_SCALE, with_aux_wdl_head=True, aux_rms_norm=False)
    rms_aux = NnueNet(HIDDEN_WIDTH, QA, QB, OUTPUT_SCALE, with_aux_wdl_head=True, aux_rms_norm=True)
    assert set(plain_aux.state_dict()) == set(rms_aux.state_dict())
    assert set(rms_aux.state_dict()) == {
        "ft.weight", "ft_bias", "output_layer.weight", "output_layer.bias",
        "wdl_head.weight", "wdl_head.bias",
    }


def test_training_config_defaults_aux_rms_norm_false_for_old_configs():
    """A pre-existing config dict has no aux_rms_norm key; it must still construct, defaulting
    to False -- so every historical checkpoint's config round-trips unchanged."""
    old = dict(hidden_width=8, qa=127, qb=64, output_scale=400,
               k=2.773456, learning_rate=0.01, seed=42, steps=1, batch_size=2)
    cfg = TrainingConfig(**old)
    assert cfg.aux_rms_norm is False


# --- the default path is the unchanged unnormalized head ---------------------------

def test_unnormalized_default_aux_path_is_plain_wdl_head():
    model = NnueNet(HIDDEN_WIDTH, QA, QB, OUTPUT_SCALE, with_aux_wdl_head=True)
    assert model.aux_rms_norm is False
    us_i, us_o, them_i, them_o = _single_position()
    with torch.no_grad():
        combined = model.shared_activation(us_i, us_o, them_i, them_o)
        expected = model.wdl_head(combined).squeeze(-1)
        actual = model.auxiliary_wdl_logit(us_i, us_o, them_i, them_o)
    assert torch.equal(actual, expected)


def test_rms_flag_does_not_change_primary_forward():
    plain = NnueNet(HIDDEN_WIDTH, QA, QB, OUTPUT_SCALE)
    rms_off = NnueNet(HIDDEN_WIDTH, QA, QB, OUTPUT_SCALE, with_aux_wdl_head=True, aux_rms_norm=False)
    rms_on = NnueNet(HIDDEN_WIDTH, QA, QB, OUTPUT_SCALE, with_aux_wdl_head=True, aux_rms_norm=True)
    rms_off.load_state_dict(plain.state_dict(), strict=False)
    rms_on.load_state_dict(plain.state_dict(), strict=False)
    us_i, us_o, them_i, them_o = _single_position()
    with torch.no_grad():
        a = plain(us_i, us_o, them_i, them_o)
        b = rms_off(us_i, us_o, them_i, them_o)
        c = rms_on(us_i, us_o, them_i, them_o)
    assert torch.equal(a, b)
    assert torch.equal(a, c)


# --- the treatment normalization itself --------------------------------------------

def test_rms_normalized_input_has_unit_per_sample_rms():
    model = NnueNet(HIDDEN_WIDTH, QA, QB, OUTPUT_SCALE, with_aux_wdl_head=True, aux_rms_norm=True)
    with torch.no_grad():
        model.ft.weight.fill_(0.5)  # positive weights -> nonzero clipped-ReLU activation per row
    us_i, us_o, them_i, them_o = _multi_position()
    combined = model.shared_activation(us_i, us_o, them_i, them_o)
    assert (combined.abs().sum(dim=1) > 0).all()  # precondition: no all-zero rows here
    rms = torch.sqrt(torch.mean(combined ** 2, dim=1, keepdim=True) + NnueNet.AUX_RMS_EPS)
    normalized = combined / rms
    per_row_rms = torch.sqrt(torch.mean(normalized ** 2, dim=1))
    assert torch.allclose(per_row_rms, torch.ones_like(per_row_rms), atol=1e-3)


def test_rms_treatment_matches_manual_normalization():
    model = NnueNet(HIDDEN_WIDTH, QA, QB, OUTPUT_SCALE, with_aux_wdl_head=True, aux_rms_norm=True)
    with torch.no_grad():
        model.ft.weight.uniform_(-0.3, 0.7)
        us_i, us_o, them_i, them_o = _multi_position()
        combined = model.shared_activation(us_i, us_o, them_i, them_o)
        rms = torch.sqrt(torch.mean(combined ** 2, dim=1, keepdim=True) + NnueNet.AUX_RMS_EPS)
        expected = model.wdl_head(combined / rms).squeeze(-1)
        actual = model.auxiliary_wdl_logit(us_i, us_o, them_i, them_o)
    assert torch.allclose(actual, expected, atol=1e-6)


def test_rms_finite_for_all_zero_activation():
    model = NnueNet(HIDDEN_WIDTH, QA, QB, OUTPUT_SCALE, with_aux_wdl_head=True, aux_rms_norm=True)
    with torch.no_grad():
        model.ft.weight.zero_()
        model.ft_bias.zero_()  # already zero at init, made explicit
    us_i, us_o, them_i, them_o = _single_position()
    combined = model.shared_activation(us_i, us_o, them_i, them_o)
    assert torch.all(combined == 0)  # precondition: activation is genuinely all-zero
    logit = model.auxiliary_wdl_logit(us_i, us_o, them_i, them_o)
    assert torch.isfinite(logit).all()
    # aux_input = 0 / sqrt(0 + eps) = 0, so the logit must be exactly the head bias
    assert torch.allclose(logit, model.wdl_head.bias.detach())


# --- gradient isolation still holds under RMS --------------------------------------

def test_rms_aux_gradient_reaches_trunk_and_head_not_primary():
    model = NnueNet(HIDDEN_WIDTH, QA, QB, OUTPUT_SCALE, with_aux_wdl_head=True, aux_rms_norm=True)
    with torch.no_grad():
        model.ft.weight.fill_(0.5)  # ensure a nonzero, differentiable activation
    us_i, us_o, them_i, them_o = _single_position()
    logit = model.auxiliary_wdl_logit(us_i, us_o, them_i, them_o)
    torch.nn.functional.binary_cross_entropy_with_logits(logit, torch.tensor([1.0])).backward()
    assert model.ft.weight.grad is not None and model.ft.weight.grad.abs().sum() > 0, \
        "auxiliary loss must still shape the shared feature transformer through the RMS path"
    assert model.wdl_head.weight.grad is not None and model.wdl_head.weight.grad.abs().sum() > 0
    assert model.output_layer.weight.grad is None or model.output_layer.weight.grad.abs().sum() == 0, \
        "auxiliary loss must NOT reach the primary head, even through the RMS path"
    assert model.output_layer.bias.grad is None or model.output_layer.bias.grad.abs().sum() == 0


# --- export/quantization isolation is unchanged ------------------------------------

def _paired_plain_and_treatment():
    plain = NnueNet(HIDDEN_WIDTH, QA, QB, OUTPUT_SCALE)
    treat = NnueNet(HIDDEN_WIDTH, QA, QB, OUTPUT_SCALE, with_aux_wdl_head=True, aux_rms_norm=True)
    treat.load_state_dict(plain.state_dict(), strict=False)  # identical primary + ft params
    return plain, treat


def test_rms_treatment_canonical_primary_arrays_unchanged():
    plain, treat = _paired_plain_and_treatment()
    c_plain = checkpoint_to_canonical(
        {"model_state_dict": plain.state_dict(), "config": _config_dict()})
    c_treat = checkpoint_to_canonical(
        {"model_state_dict": treat.state_dict(),
         "config": _config_dict(aux_wdl_weight=0.04, aux_rms_norm=True)})
    assert np.array_equal(c_plain.ft_weights, c_treat.ft_weights)
    assert np.array_equal(c_plain.ft_biases, c_treat.ft_biases)
    assert np.array_equal(c_plain.output_weights, c_treat.output_weights)
    assert c_plain.output_bias == c_treat.output_bias
    assert c_treat.architecture_id == c_plain.architecture_id
    assert c_treat.feature_set_id == c_plain.feature_set_id


def test_rms_treatment_quantized_arrays_bitwise_identical():
    plain, treat = _paired_plain_and_treatment()
    q_plain = quantize(checkpoint_to_canonical({"model_state_dict": plain.state_dict(), "config": _config_dict()}))
    q_treat = quantize(checkpoint_to_canonical(
        {"model_state_dict": treat.state_dict(),
         "config": _config_dict(aux_wdl_weight=0.04, aux_rms_norm=True)}))
    net_plain = q_plain[0] if isinstance(q_plain, tuple) else q_plain
    net_treat = q_treat[0] if isinstance(q_treat, tuple) else q_treat
    assert np.array_equal(net_plain.ft_weights, net_treat.ft_weights)
    assert np.array_equal(net_plain.output_weights, net_treat.output_weights)
