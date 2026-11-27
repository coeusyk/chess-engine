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


def test_parity_cannot_stand_in_for_orientation():
    """Float-versus-quantized parity of an inverted network is as good as the original's. The gate sees the sign."""
    fens = [f for f, _ in POSITIONS]
    good = material_oracle(fens)
    inverted = [-s for s in good]
    assert np.abs(np.array(good) + np.array(inverted)).max() == 0  # the two are exact mirror images of each other
    assert controlled_orientation(good, POSITIONS).passed and not controlled_orientation(inverted, POSITIONS).passed


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
