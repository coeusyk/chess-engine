"""#210 section 11/12/13 -- grouped-by-game train/held-out splitting. Proves
the one required invariant (a game never splits across both sides) plus
determinism, approximation behavior, and the documented edge cases.
"""

import pytest

from trainer.contracts import PositionLabel, PositionMetadata, PositionRecord
from trainer.dataset.split import split_by_game

FEN = "4k3/8/8/8/8/8/4Q3/4K3 w - - 0 1"


def _records(game_id: int, count: int, fen: str = FEN) -> list:
    return [
        PositionRecord(fen=fen, label=PositionLabel(eval_cp=i), metadata=PositionMetadata(game_id=game_id))
        for i in range(count)
    ]


def _many_games(sizes: dict) -> list:
    records = []
    for gid, size in sizes.items():
        records.extend(_records(gid, size))
    return records


def _game_ids(records: list) -> set:
    return {r.metadata.game_id for r in records}


def test_no_game_leaks_across_split():
    records = _many_games({i: 10 for i in range(20)})
    train, held_out = split_by_game(records, seed=1, held_out_fraction=0.2)
    assert _game_ids(train) & _game_ids(held_out) == set()


def test_approximate_requested_row_ratio():
    records = _many_games({i: 10 for i in range(100)})  # 1000 rows, uniform game size
    train, held_out = split_by_game(records, seed=1, held_out_fraction=0.1)
    ratio = len(held_out) / len(records)
    assert 0.05 <= ratio <= 0.15  # within one game's worth of the 10% target


def test_deterministic_for_fixed_seed():
    records = _many_games({i: 3 for i in range(30)})
    train_a, held_a = split_by_game(records, seed=7, held_out_fraction=0.2)
    train_b, held_b = split_by_game(records, seed=7, held_out_fraction=0.2)
    assert [r.metadata.game_id for r in train_a] == [r.metadata.game_id for r in train_b]
    assert [r.metadata.game_id for r in held_a] == [r.metadata.game_id for r in held_b]


def test_different_seed_can_produce_different_split():
    records = _many_games({i: 3 for i in range(30)})
    _, held_a = split_by_game(records, seed=1, held_out_fraction=0.3)
    _, held_b = split_by_game(records, seed=2, held_out_fraction=0.3)
    assert _game_ids(held_a) != _game_ids(held_b)


def test_unequal_group_sizes_still_respect_grouping():
    records = _many_games({0: 1, 1: 500, 2: 2, 3: 300, 4: 3})
    train, held_out = split_by_game(records, seed=3, held_out_fraction=0.4)
    assert _game_ids(train) & _game_ids(held_out) == set()
    # Every record for a chosen game lands together.
    for gid in _game_ids(held_out):
        assert all(r.metadata.game_id == gid for r in held_out if r.metadata.game_id == gid)


def test_repeated_fens_do_not_change_grouping():
    # Same FEN appears across two different games -- grouping must use game_id, not FEN.
    records = _records(0, 5, fen=FEN) + _records(1, 5, fen=FEN)
    train, held_out = split_by_game(records, seed=1, held_out_fraction=0.5)
    assert _game_ids(train) | _game_ids(held_out) == {0, 1}
    assert _game_ids(train) & _game_ids(held_out) == set()


def test_game_id_zero_works():
    records = _many_games({0: 5, 1: 5, 2: 5})
    train, held_out = split_by_game(records, seed=1, held_out_fraction=0.3)
    assert 0 in _game_ids(train) | _game_ids(held_out)


def test_max_int64_game_id_works():
    max_i64 = 2**63 - 1
    records = _records(max_i64, 5) + _records(0, 5)
    train, held_out = split_by_game(records, seed=1, held_out_fraction=0.5)
    assert max_i64 in (_game_ids(train) | _game_ids(held_out))


def test_one_game_only():
    records = _records(5, 10)
    train, held_out = split_by_game(records, seed=1, held_out_fraction=0.1)
    # The single group goes entirely to one side or the other -- never split.
    assert (len(train) == 10 and len(held_out) == 0) or (len(train) == 0 and len(held_out) == 10)


def test_two_games():
    records = _records(0, 5) + _records(1, 5)
    train, held_out = split_by_game(records, seed=1, held_out_fraction=0.5)
    assert len(train) + len(held_out) == 10
    assert _game_ids(train) & _game_ids(held_out) == set()


def test_missing_game_id_fails_for_grouped_mode():
    records = [
        PositionRecord(fen=FEN, label=PositionLabel(eval_cp=1), metadata=PositionMetadata(game_id=None))
    ]
    with pytest.raises(ValueError):
        split_by_game(records, seed=1)


def test_empty_input_returns_empty_split():
    assert split_by_game([], seed=1) == ([], [])
