"""Grouped-by-game train/held-out splitting (#210 section 11). The existing
`combine_and_split()` (`scripts/train_candidate_net.py`) shuffles individual
`PositionRecord`s with no notion of which game produced them -- safe for
Stage 1/2 data (rows are independent), unsafe for Stage 3 self-play data
(adjacent samples from one game are correlated; splitting a game's rows across
both train and held-out leaks information between the two sets). This module
adds an explicit, separate grouping-aware path rather than silently changing
`combine_and_split()`'s row-wise behavior, which every existing Stage 1/2
training run still depends on unchanged.

Only `PositionRecord.metadata.game_id` is the grouping key. A record with no
`game_id` cannot be grouped by game at all (there is no group to put it in),
so `split_by_game()` fails loudly rather than inventing a synthetic
one-record-per-group fallback -- silently doing that would defeat the entire
point of grouping (every ungrouped row would trivially "not leak" by having a
group of size one, hiding the real problem: the caller handed this function
data it was never designed for).
"""

from __future__ import annotations

import random
from typing import Iterable, List, Tuple

from trainer.contracts import PositionRecord

DEFAULT_HELD_OUT_FRACTION = 0.10


def split_by_game(
    records: Iterable[PositionRecord], seed: int, held_out_fraction: float = DEFAULT_HELD_OUT_FRACTION
) -> Tuple[List[PositionRecord], List[PositionRecord]]:
    """Splits `records` into (training, held_out) such that every record
    sharing one `game_id` lands entirely on one side -- never split across
    both. Deterministic for a fixed `seed` and a fixed input order.

    The held-out target is an approximate *row* count, not an exact one:
    since games have different sample counts, whole games are selected (via a
    seeded shuffle of the distinct game IDs, in their first-appearance order)
    until their combined row count reaches `held_out_fraction * len(records)`,
    then that partial selection stops -- the held-out set's actual size can
    land above or below the exact target, by up to one game's worth of rows.
    This mirrors `combine_and_split()`'s own current target (~10% of rows) as
    closely as grouping allows, rather than switching to a percentage-of-games
    target that would drift further from the existing row-count convention
    whenever game sizes are uneven.

    Raises `ValueError` if any record has `metadata.game_id is None` -- a
    self-play grouped split requires every record to already carry a group.
    """
    groups: dict = {}
    order: List[int] = []
    total = 0
    for record in records:
        gid = record.metadata.game_id
        if gid is None:
            raise ValueError(
                "split_by_game() requires metadata.game_id on every record; found a record "
                "with game_id=None -- self-play data must be ingested with game IDs assigned "
                "(see selfplay_ingest.py) before it can be grouped-split"
            )
        if gid not in groups:
            groups[gid] = []
            order.append(gid)
        groups[gid].append(record)
        total += 1

    if total == 0:
        return [], []

    target_held_out_rows = round(total * held_out_fraction)

    shuffled_groups = list(order)
    random.Random(seed).shuffle(shuffled_groups)

    held_out_ids: set = set()
    held_out_rows = 0
    for gid in shuffled_groups:
        if held_out_rows >= target_held_out_rows:
            break
        held_out_ids.add(gid)
        held_out_rows += len(groups[gid])

    training_records: List[PositionRecord] = []
    held_out_records: List[PositionRecord] = []
    # Materialize in original group-appearance order (not shuffled order) --
    # the shuffle exists only to choose which groups go where, not to reorder
    # output rows.
    for gid in order:
        bucket = held_out_records if gid in held_out_ids else training_records
        bucket.extend(groups[gid])

    return training_records, held_out_records


def select_held_out_game_ids(game_ids: Iterable[int], seed: int, held_out_count: int) -> frozenset:
    """Deterministically selects exactly `held_out_count` distinct game IDs from `game_ids` via
    one seeded shuffle -- computed **once**, independent of any arm's own row counts.

    E-16 (`DR-E16-shared-opening-prefix-preregistration.md`): `split_by_game()` above chooses
    held-out games by accumulating *rows* until a target row count is reached, which is exactly
    right for Stage 1/2-style unpaired data but wrong for E-16's paired-by-opening-index corpora --
    control game `i` and treatment game `i` share the same opening, but can (and do) have very
    different lengths, so the same `seed` fed independently into `split_by_game()` per arm can
    accumulate a *different* held-out game-ID set per arm even though the shuffle itself is
    identical. This function separates "which game IDs are held out" (a pure function of the
    shared game-ID universe and the seed, computed once) from "how many rows does that produce"
    (an arm-specific, unconstrained consequence, per `split_by_fixed_game_ids()` below) --
    the two arms then apply the identical resulting set via `split_by_fixed_game_ids()`, so their
    train/held-out *opening* membership is guaranteed identical regardless of row-count drift.

    Raises `ValueError` if `held_out_count` exceeds the number of distinct game IDs given.
    """
    unique_ids = sorted(set(game_ids))
    if held_out_count > len(unique_ids):
        raise ValueError(
            f"cannot hold out {held_out_count} game IDs from only {len(unique_ids)} distinct IDs"
        )
    shuffled = list(unique_ids)
    random.Random(seed).shuffle(shuffled)
    return frozenset(shuffled[:held_out_count])


def split_by_fixed_game_ids(
    records: Iterable[PositionRecord], held_out_game_ids: Iterable[int]
) -> Tuple[List[PositionRecord], List[PositionRecord]]:
    """Partitions `records` into (training, held_out) using an **explicitly supplied** held-out
    game-ID set, instead of `split_by_game()`'s own per-call, row-count-driven selection. Every
    record sharing one `game_id` still lands entirely on one side -- never split across both --
    and output order is preserved (first-appearance order within each bucket, same convention as
    `split_by_game()`).

    This is the counterpart `select_held_out_game_ids()`'s docstring describes: call
    `select_held_out_game_ids()` once against the shared game-ID universe to get one held-out set,
    then call this function once per arm with that identical set -- both arms' train/held-out
    *opening* membership is then guaranteed identical by construction, independent of how many
    rows each arm's own games happen to contain. Row counts are **not** forced equal here (E-16's
    own row-budget equalization, a separate, later, training-side-only step, handles that).

    Raises `ValueError` if any record has `metadata.game_id is None` (same requirement
    `split_by_game()` enforces), or if `held_out_game_ids` contains an ID that does not appear in
    `records` at all -- a missing/mistyped ID fails loudly here rather than silently holding out
    nothing for it.
    """
    groups: dict = {}
    order: List[int] = []
    for record in records:
        gid = record.metadata.game_id
        if gid is None:
            raise ValueError(
                "split_by_fixed_game_ids() requires metadata.game_id on every record; found a "
                "record with game_id=None -- self-play data must be ingested with game IDs "
                "assigned (see selfplay_ingest.py) before it can be grouped-split"
            )
        if gid not in groups:
            groups[gid] = []
            order.append(gid)
        groups[gid].append(record)

    held_out_set = set(held_out_game_ids)
    unmatched = held_out_set - set(order)
    if unmatched:
        raise ValueError(
            f"held_out_game_ids contains ID(s) not present in records: {sorted(unmatched)} -- "
            "every held-out ID must correspond to a real game in this corpus"
        )

    training_records: List[PositionRecord] = []
    held_out_records: List[PositionRecord] = []
    # Materialize in original group-appearance order, same convention as split_by_game().
    for gid in order:
        bucket = held_out_records if gid in held_out_set else training_records
        bucket.extend(groups[gid])

    return training_records, held_out_records
