import pytest

from trainer.dataset.lichess_perspective import side_to_move, to_mover_relative

W = "4k3/8/8/8/8/8/3Q4/4K3 w - -"
B = "4k3/3q4/8/8/8/8/8/4K3 b - - 12 40"


def test_side_to_move_accepts_four_and_six_field_fens():
    assert side_to_move(W) == "w"
    assert side_to_move(B) == "b"


@pytest.mark.parametrize("fen", ["", "8/8/8/8/8/8/8/8", "8/8/8/8/8/8/8/8 x - -", "8/8/8/8/8/8/8/8 W - -"])
def test_side_to_move_rejects_undeterminable_perspective(fen):
    with pytest.raises(ValueError, match="side to move"):
        side_to_move(fen)


def test_white_to_move_is_unchanged():
    assert to_mover_relative(W, 120, None) == (120, None)
    assert to_mover_relative(W, None, -3) == (None, -3)


def test_black_to_move_negates_present_values_only():
    assert to_mover_relative(B, 120, None) == (-120, None)
    assert to_mover_relative(B, -45, None) == (45, None)
    assert to_mover_relative(B, None, 2) == (None, -2)
    assert to_mover_relative(B, None, -1) == (None, 1)
    assert to_mover_relative(B, 0, None) == (0, None)


def test_conversion_is_an_involution_which_is_why_double_application_is_guarded():
    once = to_mover_relative(B, 77, -4)
    assert to_mover_relative(B, *once) == (77, -4)
