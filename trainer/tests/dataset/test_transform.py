from trainer.contracts import PositionLabel, PositionMetadata, PositionRecord
from trainer.dataset import balance_phases, compose, deduplicate, filter_by_ply_range, phase_of


def _record(fen: str, ply: int = None) -> PositionRecord:
    return PositionRecord(
        fen=fen,
        label=PositionLabel(eval_cp=0),
        metadata=PositionMetadata(ply=ply),
    )


def test_deduplicate_drops_repeated_fens():
    records = [_record("a"), _record("b"), _record("a"), _record("c")]
    result = list(deduplicate(records))
    assert [r.fen for r in result] == ["a", "b", "c"]


def test_filter_by_ply_range_keeps_only_in_range():
    records = [_record("a", ply=5), _record("b", ply=50), _record("c", ply=100)]
    transform = filter_by_ply_range(10, 60)
    result = list(transform(records))
    assert [r.fen for r in result] == ["b"]


def test_filter_by_ply_range_passes_through_missing_ply():
    records = [_record("a", ply=None)]
    transform = filter_by_ply_range(10, 60)
    result = list(transform(records))
    assert [r.fen for r in result] == ["a"]


def test_compose_chains_in_declared_order():
    records = [_record("a", ply=5), _record("a", ply=5), _record("b", ply=200)]
    pipeline = compose(deduplicate, filter_by_ply_range(0, 100))
    result = list(pipeline(records))
    assert [r.fen for r in result] == ["a"]


def test_phase_of_classifies_starting_position_as_opening():
    startpos = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    assert phase_of(startpos) == "opening"


def test_phase_of_classifies_lone_queen_endgame():
    fen = "4k3/8/8/8/8/8/4Q3/4K3 w - - 0 1"
    assert phase_of(fen) == "endgame"


def test_phase_of_classifies_middlegame_by_piece_count():
    # 8 non-pawn/king pieces total (r,q,b,r + R,Q,B,R) -- below the opening
    # threshold (10), at or above the middlegame threshold (4).
    fen = "r2qkb1r/8/8/8/8/8/8/R2QKB1R w - - 0 1"
    assert phase_of(fen) == "middlegame"


def test_balance_phases_caps_each_bucket_independently():
    opening = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    endgame = "4k3/8/8/8/8/8/4Q3/4K3 w - - 0 1"
    records = [_record(opening)] * 5 + [_record(endgame)] * 2
    transform = balance_phases(max_per_phase=3)
    result = list(transform(records))
    assert sum(1 for r in result if r.fen == opening) == 3
    assert sum(1 for r in result if r.fen == endgame) == 2


def test_position_key_is_placement_and_side_to_move_only():
    from trainer.dataset.transform import position_key

    base = "4k3/8/8/8/8/8/4Q3/4K3 w - - 0 1"
    assert position_key(base) == "4k3/8/8/8/8/8/4Q3/4K3 w"
    assert position_key(base.replace("0 1", "37 60")) == position_key(base)  # clocks differ
    assert position_key("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1") == position_key(
        "r3k2r/8/8/8/8/8/8/R3K2R w - - 0 1"
    )  # castling rights differ
    assert position_key(base.replace(" w ", " b ")) != position_key(base)  # side to move matters
    assert position_key("4k3/8/8/8/8/8/3Q4/4K3 w - - 0 1") != position_key(base)  # placement matters


def test_deduplicate_treats_the_same_position_with_different_clocks_as_a_duplicate():
    # #275: keying on the full FEN let a position re-appear with a different halfmove/fullmove counter.
    first = "4k3/8/8/8/8/8/4Q3/4K3 w - - 0 1"
    records = [
        _record(first),
        _record("4k3/8/8/8/8/8/4Q3/4K3 w - - 12 40"),
        _record("4k3/8/8/8/8/8/4Q3/4K3 b - - 0 1"),
    ]
    result = list(deduplicate(records))
    assert [r.fen for r in result] == [first, "4k3/8/8/8/8/8/4Q3/4K3 b - - 0 1"], "the first record wins"
