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
