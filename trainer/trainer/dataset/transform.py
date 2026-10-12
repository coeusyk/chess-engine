"""Composable Transform stage (docs/architecture/NNUE_TRAINER_ARCHITECTURE.md
Section 2 / docs/NNUE_PRD.md Section 3: "Filtering/deduplication/phase balancing,
composable in a declared order... records in -> records out"). Operates on the
DatasetProvider contract's own types only -- no format-specific knowledge, same as
the contract itself.
"""

from __future__ import annotations

from typing import Callable, Iterable, Iterator

from trainer.contracts import PositionRecord

Transform = Callable[[Iterable[PositionRecord]], Iterator[PositionRecord]]


def compose(*transforms: Transform) -> Transform:
    """Chain transforms in the given, declared order -- the order argument order is
    the order applied, left to right.
    """

    def chained(records: Iterable[PositionRecord]) -> Iterator[PositionRecord]:
        stream: Iterable[PositionRecord] = records
        for transform in transforms:
            stream = transform(stream)
        return iter(stream)

    return chained


def deduplicate(records: Iterable[PositionRecord]) -> Iterator[PositionRecord]:
    """Drop positions whose FEN has already been seen. Streaming, but the set of seen
    FENs is held in memory for the duration of iteration -- an accepted cost for
    deduplication, which is inherently a whole-stream concern.
    """
    seen: set[str] = set()
    for record in records:
        if record.fen in seen:
            continue
        seen.add(record.fen)
        yield record


def filter_by_ply_range(min_ply: int, max_ply: int) -> Transform:
    """A Transform factory: drop positions whose `metadata.ply` falls outside
    `[min_ply, max_ply]`. Positions with no recorded ply pass through unfiltered --
    absence of ply information is not evidence the position is out of range.
    """

    def transform(records: Iterable[PositionRecord]) -> Iterator[PositionRecord]:
        for record in records:
            ply = record.metadata.ply
            if ply is not None and not (min_ply <= ply <= max_ply):
                continue
            yield record

    return transform


# Total non-pawn, non-king pieces on the board (Q/R/B/N, either color) as a phase
# proxy -- cheap (a single pass over the FEN's board field, no board parsing), and
# consistent with how many engines classify phase for tapered eval. Starting position
# has 14 (1Q+2R+2B+2N per side); thresholds below are chosen so a fresh game reads as
# "opening" and a bare-king-and-pawns endgame reads as "endgame."
_PHASE_PIECE_CHARS = frozenset("QqRrBbNn")
_OPENING_MIN_PIECES = 10
_MIDDLEGAME_MIN_PIECES = 4


def phase_of(fen: str) -> str:
    """Classify a FEN's game phase as "opening" / "middlegame" / "endgame" from
    non-pawn, non-king piece count. A heuristic proxy, not a precise phase
    detector -- sufficient for balancing a dataset's phase mix, not for anything
    eval-affecting (that's the engine's own tapered eval, untouched by this module).
    """
    board_field = fen.split(" ", 1)[0]
    piece_count = sum(1 for ch in board_field if ch in _PHASE_PIECE_CHARS)
    if piece_count >= _OPENING_MIN_PIECES:
        return "opening"
    if piece_count >= _MIDDLEGAME_MIN_PIECES:
        return "middlegame"
    return "endgame"


def balance_phases(max_per_phase: int) -> Transform:
    """A Transform factory: cap each phase bucket (`phase_of`) at `max_per_phase`
    positions, dropping the rest -- a position-count phase balancer, not a
    resampler (it never duplicates positions to fill an under-represented phase,
    only thins over-represented ones). Streaming: holds only three integer counters
    in memory, not the positions themselves.
    """

    def transform(records: Iterable[PositionRecord]) -> Iterator[PositionRecord]:
        counts = {"opening": 0, "middlegame": 0, "endgame": 0}
        for record in records:
            phase = phase_of(record.fen)
            if counts[phase] >= max_per_phase:
                continue
            counts[phase] += 1
            yield record

    return transform
