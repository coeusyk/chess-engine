import torch

from trainer.model.batching import encode_batch
from trainer.model.network import INT16_MAX, MAX_ACTIVE_FEATURES, NnueNet, derive_weight_clip_bounds
from trainer.contracts import PositionLabel, PositionMetadata, PositionRecord

FEN_A = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
FEN_B = "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1"


def _record(fen: str) -> PositionRecord:
    return PositionRecord(fen=fen, label=PositionLabel(eval_cp=0), metadata=PositionMetadata())


def test_derive_weight_clip_bounds_saturates_int16_exactly():
    bias_clip, weight_clip = derive_weight_clip_bounds(qa=127)
    assert bias_clip == 127
    assert weight_clip == (INT16_MAX - 127) / MAX_ACTIVE_FEATURES
    assert bias_clip + MAX_ACTIVE_FEATURES * weight_clip == INT16_MAX


def test_derive_weight_clip_bounds_scales_with_qa():
    _, weight_clip_small_qa = derive_weight_clip_bounds(qa=64)
    _, weight_clip_large_qa = derive_weight_clip_bounds(qa=254)
    # A larger qa reserves more int16 budget for the bias, leaving less for weights.
    assert weight_clip_large_qa < weight_clip_small_qa


def test_forward_pass_produces_one_scalar_per_position():
    model = NnueNet(hidden_width=4, qa=127, qb=64, output_scale=400)
    batch = encode_batch([_record(FEN_A), _record(FEN_B)])
    output = model(batch.us_indices, batch.us_offsets, batch.them_indices, batch.them_offsets)
    assert output.shape == (2,)
    assert torch.isfinite(output).all()


def test_clip_ft_weights_enforces_the_derived_bounds():
    model = NnueNet(hidden_width=4, qa=127, qb=64, output_scale=400)
    with torch.no_grad():
        model.ft.weight.fill_(999999.0)
        model.ft_bias.fill_(999999.0)

    model.clip_ft_weights_()

    bias_clip, weight_clip = derive_weight_clip_bounds(127)
    assert torch.all(model.ft.weight <= weight_clip)
    assert torch.all(model.ft_bias <= bias_clip)
