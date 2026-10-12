"""Experiment P5-AUXHEAD-001: auxiliary WDL head (multi-task) — behavioral contract.

Written test-first, against the design record
`docs/architecture/research/nnue/phase5-p5-auxhead-design.md`. Each test corresponds to a
numbered guarantee in that document, so a failure here names which design promise broke.

The load-bearing property this suite protects is **gradient isolation**: the auxiliary
objective may reshape the shared feature transformer, and may never touch the primary head's
own parameters. That is the entire structural difference between this experiment and the
already-closed WDL-blend family (P4IV/P5-WDLALT), which contaminated the primary *target*.
If that isolation silently broke, this experiment would quietly become another member of
that family and its result would mean something different than what the report claims.
"""

import dataclasses

import torch

from trainer.contracts import PositionLabel, PositionMetadata, PositionRecord
from trainer.model.network import NnueNet
from trainer.model.train import TrainingConfig, train

HIDDEN_WIDTH = 8
QA = 127
QB = 64
OUTPUT_SCALE = 400
AUX_WEIGHT = 0.04


def _config(**overrides) -> TrainingConfig:
    base = dict(
        hidden_width=HIDDEN_WIDTH, qa=QA, qb=QB, output_scale=OUTPUT_SCALE,
        k=2.773456, learning_rate=0.01, seed=42, steps=3, batch_size=4,
    )
    base.update(overrides)
    return TrainingConfig(**base)


def _records() -> "list[PositionRecord]":
    """A mixed corpus: cp-labeled and mate-labeled, some with wdl and some without --
    the same heterogeneity the real Stage 1 + Stage 2 blend has."""
    fens = [
        "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
        "8/8/1p1k4/1P6/8/3p3P/1r4P1/5K2 w - - 0 1",
        "r1q1kb1r/6pp/b1p1pn2/2P1Np2/QP6/4P3/P2N2PP/R1BR2K1 b kq - 0 1",
        "8/8/4k3/8/8/4K3/8/8 w - - 0 1",
    ]
    labels = [
        PositionLabel(eval_cp=35, wdl=1.0),
        PositionLabel(eval_cp=-120, wdl=0.0),
        PositionLabel(eval_cp=10, wdl=None),       # no wdl -- must be excluded from aux loss
        PositionLabel(eval_cp=None, eval_mate=3, wdl=0.5),
    ]
    return [PositionRecord(fen=f, label=l, metadata=PositionMetadata())
            for f, l in zip(fens, labels)]


# --- Guarantee 3: checkpoint layout ------------------------------------------------

def test_default_weight_constructs_no_auxiliary_head():
    """At the default the head must not exist -- not merely be unused. This is what makes
    a default-config checkpoint's key set bit-identical to every pre-existing one."""
    model = NnueNet(HIDDEN_WIDTH, QA, QB, OUTPUT_SCALE)
    assert not hasattr(model, "wdl_head") or model.wdl_head is None
    assert set(model.state_dict()) == {
        "ft.weight", "ft_bias", "output_layer.weight", "output_layer.bias"
    }


def test_enabled_head_adds_only_its_own_keys():
    model = NnueNet(HIDDEN_WIDTH, QA, QB, OUTPUT_SCALE, with_aux_wdl_head=True)
    assert set(model.state_dict()) == {
        "ft.weight", "ft_bias", "output_layer.weight", "output_layer.bias",
        "wdl_head.weight", "wdl_head.bias",
    }


# --- Guarantee 1: gradient flow ----------------------------------------------------

def test_auxiliary_gradient_reaches_shared_trunk_but_not_primary_head():
    """THE load-bearing test. An auxiliary-only backward pass must leave the primary head's
    parameters gradient-free while producing real gradient on the shared transformer."""
    model = NnueNet(HIDDEN_WIDTH, QA, QB, OUTPUT_SCALE, with_aux_wdl_head=True)
    us_i, us_o = torch.tensor([1, 5, 9]), torch.tensor([0])
    them_i, them_o = torch.tensor([2, 6, 10]), torch.tensor([0])

    logit = model.auxiliary_wdl_logit(us_i, us_o, them_i, them_o)
    torch.nn.functional.binary_cross_entropy_with_logits(
        logit, torch.tensor([1.0])
    ).backward()

    assert model.ft.weight.grad is not None and model.ft.weight.grad.abs().sum() > 0, \
        "auxiliary loss must shape the shared feature transformer"
    assert model.wdl_head.weight.grad is not None and model.wdl_head.weight.grad.abs().sum() > 0
    assert model.output_layer.weight.grad is None or model.output_layer.weight.grad.abs().sum() == 0, \
        "auxiliary loss must NOT reach the primary head's own parameters"
    assert model.output_layer.bias.grad is None or model.output_layer.bias.grad.abs().sum() == 0


def test_auxiliary_head_does_not_change_primary_forward_output():
    plain = NnueNet(HIDDEN_WIDTH, QA, QB, OUTPUT_SCALE)
    withaux = NnueNet(HIDDEN_WIDTH, QA, QB, OUTPUT_SCALE, with_aux_wdl_head=True)
    withaux.load_state_dict(plain.state_dict(), strict=False)
    us_i, us_o = torch.tensor([1, 5, 9]), torch.tensor([0])
    them_i, them_o = torch.tensor([2, 6, 10]), torch.tensor([0])
    with torch.no_grad():
        assert torch.allclose(plain(us_i, us_o, them_i, them_o),
                              withaux(us_i, us_o, them_i, them_o))


def test_auxiliary_logit_shares_the_same_activation_as_the_primary_head():
    """Option A requires both heads to consume the identical clipped-ReLU vector; if the
    auxiliary head saw a different representation, 'shared representation' would be a
    misnomer and the experiment would not test what the report claims."""
    model = NnueNet(HIDDEN_WIDTH, QA, QB, OUTPUT_SCALE, with_aux_wdl_head=True)
    us_i, us_o = torch.tensor([1, 5, 9]), torch.tensor([0])
    them_i, them_o = torch.tensor([2, 6, 10]), torch.tensor([0])
    with torch.no_grad():
        combined = model.shared_activation(us_i, us_o, them_i, them_o)
        expected = model.wdl_head(combined).squeeze(-1)
        assert torch.allclose(model.auxiliary_wdl_logit(us_i, us_o, them_i, them_o), expected)
        # ...and the primary head consumes that same vector.
        primary_from_shared = (model.output_layer(combined).squeeze(-1)
                               * model.output_scale / (model.qa * model.qb))
        assert torch.allclose(model(us_i, us_o, them_i, them_o), primary_from_shared)


# --- Guarantee 7: rollback / no-op at default --------------------------------------

def test_default_config_training_is_bit_identical_to_explicit_zero_weight(tmp_path):
    """Rollback layer 1: the new field's default must reproduce prior behavior exactly."""
    records = _records()
    implicit = train(_config(), records, checkpoint_path=tmp_path / "implicit.pt")
    explicit = train(_config(aux_wdl_weight=0.0), records, checkpoint_path=tmp_path / "explicit.pt")
    assert implicit["losses"] == explicit["losses"]

    a = torch.load(tmp_path / "implicit.pt", weights_only=False)["model_state_dict"]
    b = torch.load(tmp_path / "explicit.pt", weights_only=False)["model_state_dict"]
    assert set(a) == set(b)
    for key in a:
        assert torch.equal(a[key], b[key]), f"{key} diverged at default weight"


def test_default_training_checkpoint_has_no_auxiliary_parameters(tmp_path):
    """Rollback layer 2: a default run leaves no residue in saved artifacts."""
    train(_config(), _records(), checkpoint_path=tmp_path / "net.pt")
    state = torch.load(tmp_path / "net.pt", weights_only=False)["model_state_dict"]
    assert not any(k.startswith("wdl_head") for k in state)


# --- Training integration ----------------------------------------------------------

def test_non_zero_weight_actually_changes_the_trained_model(tmp_path):
    """Guards against the auxiliary objective being silently inert -- the failure mode that
    would make a null result meaningless."""
    records = _records()
    train(_config(), records, checkpoint_path=tmp_path / "off.pt")
    train(_config(aux_wdl_weight=AUX_WEIGHT), records, checkpoint_path=tmp_path / "on.pt")
    off = torch.load(tmp_path / "off.pt", weights_only=False)["model_state_dict"]
    on = torch.load(tmp_path / "on.pt", weights_only=False)["model_state_dict"]
    assert not torch.equal(off["ft.weight"], on["ft.weight"]), \
        "auxiliary objective must actually reshape the shared trunk"


def test_enabled_run_writes_auxiliary_parameters_into_the_checkpoint(tmp_path):
    train(_config(aux_wdl_weight=AUX_WEIGHT), _records(), checkpoint_path=tmp_path / "net.pt")
    state = torch.load(tmp_path / "net.pt", weights_only=False)["model_state_dict"]
    assert {"wdl_head.weight", "wdl_head.bias"} <= set(state)
    assert torch.load(tmp_path / "net.pt", weights_only=False)["config"]["aux_wdl_weight"] == AUX_WEIGHT


def test_records_without_wdl_are_excluded_from_the_auxiliary_loss(tmp_path):
    """The has_wdl mask must genuinely gate the auxiliary term: a corpus where NO record
    carries wdl must train identically whether the auxiliary objective is on or off."""
    no_wdl = [
        PositionRecord(fen=r.fen,
                       label=PositionLabel(eval_cp=r.label.eval_cp, eval_mate=r.label.eval_mate),
                       metadata=PositionMetadata())
        for r in _records()
    ]
    off = train(_config(), no_wdl, checkpoint_path=tmp_path / "off.pt")
    on = train(_config(aux_wdl_weight=AUX_WEIGHT), no_wdl, checkpoint_path=tmp_path / "on.pt")
    assert off["losses"] == on["losses"], \
        "with no wdl-bearing records the auxiliary term must contribute exactly zero"
    a = torch.load(tmp_path / "off.pt", weights_only=False)["model_state_dict"]
    b = torch.load(tmp_path / "on.pt", weights_only=False)["model_state_dict"]
    assert torch.equal(a["ft.weight"], b["ft.weight"])


def test_reported_loss_excludes_the_auxiliary_term(tmp_path):
    """`losses` must report the PRIMARY objective, not the optimized total, so an auxiliary
    run's loss curve stays on the same scale as every prior experiment's.

    The weight here is deliberately large (10.0, far above the experiment's 0.04) to make
    the test *discriminating*: at 0.04 an un-separated total would be
    `0.144 + 0.04 * 0.87 ~= 0.18`, still inside any plausible primary-loss range, so the
    assertion would pass whether or not the separation existed. At 10.0 an un-separated
    total is `~0.14 + 10 * 0.87 ~= 8.8`, an order of magnitude outside it -- so this now
    fails if `total_loss` is ever appended to `losses` by mistake.
    """
    result = train(_config(aux_wdl_weight=10.0), _records(),
                   checkpoint_path=tmp_path / "net.pt")
    assert result["losses"], "precondition: training produced a loss series"
    # A texel-sigmoid MSE is bounded by 1.0 by construction (both terms lie in [0, 1]).
    assert all(0.0 <= loss <= 1.0 for loss in result["losses"]), (
        f"reported loss escaped the primary objective's range: max={max(result['losses'])}"
    )
