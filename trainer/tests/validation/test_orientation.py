"""Orientation validation (#265, plan #329): a hard gate on controlled positions with sign-inversion negative
controls, and corpus diagnostics that are measurements and never a pass or fail."""

import dataclasses

import numpy as np
import pytest

from trainer.export.canonical import QuantizedCanonicalNetwork
from trainer.quantization.inference import eval_quantized_fens
from trainer.validation.orientation import (
    POSITIONS_FILE,
    _mover_balance,
    controlled_forms,
    controlled_orientation,
    corpus_diagnostics,
    load_controlled_positions,
    mirror_fen,
)

POSITIONS = load_controlled_positions()
CORPUS = [fen for fen, _ in POSITIONS] + [
    "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq -", "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq -",
    "4k3/8/8/8/8/8/4Q3/4K3 b - -", "4k3/8/8/8/8/8/4q3/4K3 w - -"]


def material_oracle(fens, scale=50):
    """A synthetic mover-relative scorer: the correct orientation by construction."""
    return [scale * _mover_balance(f) for f in fens]


def test_the_committed_positions_are_the_four_forms_of_each_base_and_the_expected_signs_follow_the_mover():
    assert len(POSITIONS) == 24 and len(set(f for f, _ in POSITIONS)) == 24
    for fen, expected in POSITIONS:
        assert expected == (1 if _mover_balance(fen) > 0 else -1), fen
    bases = [l[len("# base: "):] for l in POSITIONS_FILE.read_text().splitlines() if l.startswith("# base: ")]
    regenerated = [pair for base in bases for pair in controlled_forms(base)]
    assert regenerated == POSITIONS, "the file must be the output of controlled_forms, not hand edited"


def test_mirror_fen_is_an_involution_and_flips_colour_side_and_castling():
    fen = "r3k2r/8/8/8/8/8/PP6/R3K2R w KQkq -"
    assert mirror_fen(mirror_fen(fen)) == fen
    assert mirror_fen("4k3/8/8/8/8/8/PPP5/3QK3 w - -") == "3qk3/ppp5/8/8/8/8/8/4K3 b - -"
    assert mirror_fen("r3k2r/8/8/8/8/8/8/R3K2R w KQ e3").split()[2:] == ["kq", "e6"]


def test_a_correctly_oriented_scorer_passes_the_gate_for_all_four_forms():
    result = controlled_orientation(material_oracle([f for f, _ in POSITIONS]), POSITIONS)
    assert result.passed and result.checked == 24 and result.failures == []


def test_negative_control_a_fully_inverted_scorer_fails_every_position():
    scores = [-s for s in material_oracle([f for f, _ in POSITIONS])]
    result = controlled_orientation(scores, POSITIONS)
    assert not result.passed and len(result.failures) == 24


def test_negative_control_inversion_for_black_to_move_only_fails_exactly_those_positions_while_the_rest_stay_perfect():
    fens = [f for f, _ in POSITIONS]
    scores = [(-s if f.split()[1] == "b" else s) for f, s in zip(fens, material_oracle(fens))]

    result = controlled_orientation(scores, POSITIONS)

    failed = {x["fen"] for x in result.failures}
    assert failed == {f for f in fens if f.split()[1] == "b"} and len(failed) == 12
    # parity-style agreement with the unflipped half is perfect, and still the gate fails
    white_to_move = [(s, e) for s, (f, e) in zip(scores, POSITIONS) if f.split()[1] == "w"]
    assert all((s > 0) == (e > 0) for s, e in white_to_move)


def test_a_zero_score_is_not_the_correct_sign():
    result = controlled_orientation([0] * 24, POSITIONS)
    assert not result.passed and len(result.failures) == 24


def test_negating_the_output_layer_of_a_quantized_network_negates_every_score_exactly():
    """The weight-level negative control: truncation toward zero is symmetric, so output weights and bias negated
    give exactly the negated scores. This is the sign-inversion a mislabelled training set produces in the limit."""
    rng = np.random.default_rng(5)
    hidden = 8
    network = QuantizedCanonicalNetwork(
        hidden_width=hidden, ft_weights=rng.integers(-20, 20, size=(768, hidden)).astype(np.int16),
        ft_biases=rng.integers(0, 40, size=hidden).astype(np.int16),
        output_weights=rng.integers(-60, 60, size=(2, hidden)).astype(np.int16), output_bias=7,
        qa=127, qb=64, output_scale=400, architecture_id=1, feature_set_id=1)
    negated = dataclasses.replace(network, output_weights=-network.output_weights, output_bias=-network.output_bias)
    fens = [f for f, _ in POSITIONS]

    assert (eval_quantized_fens(negated, fens) == -eval_quantized_fens(network, fens)).all()


def test_diagnostics_report_per_side_sign_agreement_correlation_and_gap_with_no_verdict():
    report = corpus_diagnostics(material_oracle(CORPUS), CORPUS, material_oracle([mirror_fen(f) for f in CORPUS]))

    assert report["white_to_move"]["sign_agreement"] == 1.0 and report["black_to_move"]["sign_agreement"] == 1.0
    assert report["side_gap"] == 0.0
    assert report["white_to_move"]["pearson_with_material"] == 1.0
    assert report["mirror_difference_cp"] == {"mean": 0.0, "max": 0}
    assert not any(k in report for k in ("passed", "verdict", "failed"))


def test_diagnostics_do_not_merge_correlation_and_sign_agreement():
    scores = [(1 if _mover_balance(f) > 0 else -1) * (1 + (i % 7)) for i, f in enumerate(CORPUS)]  # right signs, weak linearity
    report = corpus_diagnostics(scores, CORPUS)

    assert report["white_to_move"]["sign_agreement"] == 1.0
    assert report["white_to_move"]["pearson_with_material"] < 0.99


def test_diagnostics_show_the_side_gap_of_a_one_sided_inversion_without_gating_on_it():
    scores = [(-s if f.split()[1] == "b" else s) for f, s in zip(CORPUS, material_oracle(CORPUS))]
    report = corpus_diagnostics(scores, CORPUS)

    assert report["side_gap"] == 1.0
    assert "passed" not in report


def test_mismatched_lengths_are_an_error():
    with pytest.raises(ValueError):
        controlled_orientation([1], POSITIONS)
    with pytest.raises(ValueError):
        corpus_diagnostics([1, 2], CORPUS)


def _material_network(hidden=2, qa=255, qb=64, output_scale=1600):
    """A small quantized network that really knows material: unit 0 sums the mover's piece values, unit 1 the
    opponent's, and the output is their difference taken from the mover's accumulator. Feature layout
    (checked against `active_feature_indices`): block 0 = the perspective's own pieces, block 1 = the opponent's,
    index = block * 384 + piece_type * 64 + square, piece_type order p, n, b, r, q, k."""
    ft = np.zeros((768, hidden), dtype=np.int16)
    for block in (0, 1):
        for piece_type, value in enumerate((1, 3, 3, 5, 9)):
            ft[block * 384 + piece_type * 64: block * 384 + piece_type * 64 + 64, block] = 5 * value
    out = np.zeros((2, hidden), dtype=np.int16)
    out[0, 0], out[0, 1] = 64, -64
    return QuantizedCanonicalNetwork(hidden_width=hidden, ft_weights=ft, ft_biases=np.zeros(hidden, dtype=np.int16),
                                     output_weights=out, output_bias=0, qa=qa, qb=qb, output_scale=output_scale,
                                     architecture_id=1, feature_set_id=1)


def test_a_network_that_knows_material_passes_the_gate_through_the_real_evaluator():
    """Ties the gate's expected signs to the Python reference's own side-to-move convention: if the reference
    returned White-relative scores, this would fail for the Black-to-move forms."""
    scores = eval_quantized_fens(_material_network(), [f for f, _ in POSITIONS])
    assert controlled_orientation([int(s) for s in scores], POSITIONS).passed


def test_the_same_network_with_its_output_layer_negated_fails_all_24_through_the_real_evaluator():
    net = _material_network()
    negated = dataclasses.replace(net, output_weights=-net.output_weights)
    fens = [f for f, _ in POSITIONS]

    good, inverted = eval_quantized_fens(net, fens), eval_quantized_fens(negated, fens)

    assert (np.abs(good) == np.abs(inverted)).all(), "identical in magnitude, so a magnitude comparison cannot tell them apart"
    result = controlled_orientation([int(s) for s in inverted], POSITIONS)
    assert not result.passed and len(result.failures) == 24
