"""#210 section 11/12/13 -- grouped-by-game train/held-out splitting. Proves
the one required invariant (a game never splits across both sides) plus
determinism, approximation behavior, and the documented edge cases.
"""

import pytest

from trainer.contracts import PositionLabel, PositionMetadata, PositionRecord
from trainer.dataset.split import select_held_out_game_ids, split_by_fixed_game_ids, split_by_game

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


# ---- E-16 (#224): select_held_out_game_ids() / split_by_fixed_game_ids() ----
# DR-E16-shared-opening-prefix-preregistration.md's hardened Phase C design -- both arms must
# hold out the identical opening/game-ID set regardless of each arm's own row counts, which
# split_by_game()'s per-call, row-count-driven selection cannot guarantee for paired-by-opening
# corpora of very different game lengths.


def test_select_held_out_game_ids_is_deterministic_for_a_fixed_seed():
    a = select_held_out_game_ids(range(58), seed=20261602, held_out_count=6)
    b = select_held_out_game_ids(range(58), seed=20261602, held_out_count=6)
    assert a == b
    assert len(a) == 6


def test_select_held_out_game_ids_rejects_count_larger_than_universe():
    with pytest.raises(ValueError):
        select_held_out_game_ids(range(5), seed=1, held_out_count=6)


def test_same_held_out_ids_applied_to_two_corpora_with_very_different_row_counts_per_game():
    # Same 58 game IDs in both "arms," but wildly different per-game row counts -- exactly the
    # control (long, uniform games) vs. treatment (short, varied games) asymmetry E-16 hit in
    # practice (DR-E16-phase-b-generation-report.md section 6: control length min/median/max
    # 43/128/483, treatment 61/112/383).
    control_sizes = {gid: 200 for gid in range(58)}  # uniform, long games
    treatment_sizes = {gid: (gid % 7) + 1 for gid in range(58)}  # short, wildly varied
    control_records = _many_games(control_sizes)
    treatment_records = _many_games(treatment_sizes)

    held_out_ids = select_held_out_game_ids(range(58), seed=20261602, held_out_count=6)
    assert held_out_ids == frozenset({17, 20, 27, 31, 34, 51})

    control_train, control_held_out = split_by_fixed_game_ids(control_records, held_out_ids)
    treatment_train, treatment_held_out = split_by_fixed_game_ids(treatment_records, held_out_ids)

    # Exact same game-ID partition across both arms -- not merely the same *count*.
    assert _game_ids(control_held_out) == held_out_ids
    assert _game_ids(treatment_held_out) == held_out_ids
    assert _game_ids(control_train) == set(range(58)) - held_out_ids
    assert _game_ids(treatment_train) == set(range(58)) - held_out_ids

    # Row counts are NOT forced equal -- that is a separate, later, training-side-only step.
    assert len(control_held_out) != len(treatment_held_out)

    # No leakage, either arm.
    assert _game_ids(control_train) & _game_ids(control_held_out) == set()
    assert _game_ids(treatment_train) & _game_ids(treatment_held_out) == set()


def test_split_by_fixed_game_ids_preserves_original_row_order():
    records = _records(0, 3) + _records(1, 3) + _records(2, 3)
    train, held_out = split_by_fixed_game_ids(records, held_out_game_ids={1})
    # Game 0 then game 2, in original first-appearance order -- game 1 removed, not reordered.
    assert [r.metadata.game_id for r in train] == [0, 0, 0, 2, 2, 2]
    assert [r.metadata.game_id for r in held_out] == [1, 1, 1]


def test_split_by_fixed_game_ids_rejects_held_out_id_not_present_in_records():
    records = _many_games({0: 5, 1: 5, 2: 5})
    with pytest.raises(ValueError):
        split_by_fixed_game_ids(records, held_out_game_ids={0, 99})


def test_split_by_fixed_game_ids_requires_game_id_on_every_record():
    records = [
        PositionRecord(fen=FEN, label=PositionLabel(eval_cp=1), metadata=PositionMetadata(game_id=None))
    ]
    with pytest.raises(ValueError):
        split_by_fixed_game_ids(records, held_out_game_ids=set())


def test_split_by_fixed_game_ids_empty_held_out_set_holds_out_nothing():
    records = _many_games({0: 5, 1: 5})
    train, held_out = split_by_fixed_game_ids(records, held_out_game_ids=set())
    assert held_out == []
    assert len(train) == 10
