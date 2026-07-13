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
