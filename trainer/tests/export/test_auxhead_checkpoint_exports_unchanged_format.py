"""End-to-end guard: a checkpoint produced by a real auxiliary-head training run must
export to a `.nnue` that is structurally indistinguishable from a primary-only one.

`test_auxiliary_head_export_isolation.py` (Phase 5 candidate #3's scoping pass) proved this
at the canonical/quantized-array level using a hand-built probe subclass. This module closes
the remaining gap in *provenance of the input*: it runs the actual production trainer
(`train()` with `aux_wdl_weight > 0`) and feeds the resulting real checkpoint through
`checkpoint_to_canonical()` -> `quantize()`, so the claim covers a checkpoint the experiment
genuinely produces rather than a stand-in for one.

Scope, stated precisely: this stops at the quantized arrays and the header fields. It does
**not** call `export()` and does not assert over serialized bytes -- byte-level round-tripping
against the engine is already covered on the Java side by `NnueExportFixtureRoundTripTest`,
and duplicating it here would test the exporter rather than this experiment's effect on it.

The header assertions matter for a specific reason: `NnueNetwork.java` rejects any file whose
`architectureId`/`featureSetId` differ from its `SUPPORTED_*` constants, so a silent drift in
either would make an exported net unloadable by the engine at runtime rather than failing
here.
"""

import torch

from trainer.contracts import PositionLabel, PositionMetadata, PositionRecord
from trainer.export.canonical import checkpoint_to_canonical
from trainer.export.exporter import MAGIC, FORMAT_VERSION
from trainer.model.train import TrainingConfig, train
from trainer.quantization.quantizer import quantize

HIDDEN_WIDTH = 8
QA = 127
QB = 64
OUTPUT_SCALE = 400


def _config(aux_wdl_weight: float) -> TrainingConfig:
    return TrainingConfig(
        hidden_width=HIDDEN_WIDTH, qa=QA, qb=QB, output_scale=OUTPUT_SCALE,
        k=2.773456, learning_rate=0.01, seed=42, steps=3, batch_size=4,
        aux_wdl_weight=aux_wdl_weight,
    )


def _records() -> "list[PositionRecord]":
    fens = [
        "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
        "8/8/1p1k4/1P6/8/3p3P/1r4P1/5K2 w - - 0 1",
        "r1q1kb1r/6pp/b1p1pn2/2P1Np2/QP6/4P3/P2N2PP/R1BR2K1 b kq - 0 1",
        "8/8/4k3/8/8/4K3/8/8 w - - 0 1",
    ]
    labels = [
        PositionLabel(eval_cp=35, wdl=1.0),
        PositionLabel(eval_cp=-120, wdl=0.0),
        PositionLabel(eval_cp=10, wdl=None),
        PositionLabel(eval_cp=None, eval_mate=3, wdl=0.5),
    ]
    return [PositionRecord(fen=f, label=l, metadata=PositionMetadata())
            for f, l in zip(fens, labels)]


def _quantized_from(checkpoint_path):
    checkpoint = torch.load(checkpoint_path, weights_only=False)
    quantized = quantize(checkpoint_to_canonical(checkpoint))
    return quantized[0] if isinstance(quantized, tuple) else quantized


def test_real_auxhead_training_run_exports_expected_tensor_shapes(tmp_path):
    """A genuine aux-head checkpoint must canonicalize/quantize without special handling,
    producing exactly the shapes the frozen .nnue layout expects."""
    train(_config(0.04), _records(), checkpoint_path=tmp_path / "aux.pt")
    state = torch.load(tmp_path / "aux.pt", weights_only=False)["model_state_dict"]
    assert {"wdl_head.weight", "wdl_head.bias"} <= set(state), "probe precondition: head present"

    quantized = _quantized_from(tmp_path / "aux.pt")
    assert quantized.ft_weights.shape == (768, HIDDEN_WIDTH)
    assert quantized.ft_biases.shape == (HIDDEN_WIDTH,)
    assert quantized.output_weights.shape == (2, HIDDEN_WIDTH)
    assert quantized.ft_weights.dtype == "int16" or quantized.ft_weights.dtype.name == "int16"


def test_auxhead_export_header_fields_are_unchanged(tmp_path):
    """architecture_id / feature_set_id must not drift -- NnueNetwork.java rejects mismatches."""
    train(_config(0.04), _records(), checkpoint_path=tmp_path / "aux.pt")
    train(_config(0.0), _records(), checkpoint_path=tmp_path / "plain.pt")

    aux = _quantized_from(tmp_path / "aux.pt")
    plain = _quantized_from(tmp_path / "plain.pt")
    assert aux.architecture_id == plain.architecture_id
    assert aux.feature_set_id == plain.feature_set_id
    assert aux.hidden_width == plain.hidden_width
    assert (aux.qa, aux.qb, aux.output_scale) == (plain.qa, plain.qb, plain.output_scale)


def test_export_format_constants_are_untouched_by_this_experiment():
    """This experiment must not alter the frozen wire format. These two constants are what
    `NnueNetwork.java` matches against on load, so a change to either silently breaks every
    previously-exported net -- cheap to pin, expensive to discover later."""
    assert MAGIC == b"VNUE"
    assert FORMAT_VERSION == 1
