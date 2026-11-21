"""Perspective normalization for Lichess evaluation labels (issue #263).

Lichess's published evaluation dump (`lichess_db_eval.jsonl.zst`) stores `cp` and
`mate` from **White's perspective** regardless of the side to move (see
https://github.com/lichess-org/lila/blob/master/ui/lib/src/ceval/protocol.ts, where
engine scores for Black-to-move positions are negated into White's perspective).
Everything downstream in this trainer -- `PositionLabel`
(`trainer/contracts/dataset.py`), `trainer/model/batching.py` and the Java
`NnueEvaluator` -- is **mover-relative** (positive = good for the side to move).

This module is the single, dependency-free home for that conversion so the
acquisition adapter (`scripts/acquire_stage1_lichess.py`) and the historical-shard
repair tool (`scripts/repair_stage1_perspective.py`) cannot diverge. It is
deliberately **not** applied by `TextDatasetProvider` or any other reader: Stage 2
(Stockfish) and Stage 3 (self-play) labels are already mover-relative, so a global
reader-side flip would corrupt them.
"""

from __future__ import annotations

from typing import Optional, Tuple

#: Manifest field/value written by the corrected acquisition adapter and by the repair
#: tool. Its absence on a Stage 1 manifest means "unversioned, historical,
#: White-perspective" (every shard produced before issue #263 was fixed).
LABEL_PERSPECTIVE_FIELD = "label_perspective"
MOVER_RELATIVE = "mover-relative"


def side_to_move(fen: str) -> str:
    """`"w"` or `"b"` from the FEN's second field. Accepts FENs with any number of
    trailing fields (the Lichess dump omits the move clocks). Raises `ValueError` on
    anything else -- a label is never guessed for a position whose perspective cannot
    be determined.
    """
    parts = fen.split()
    if len(parts) < 2 or parts[1] not in ("w", "b"):
        raise ValueError(f"cannot determine side to move from FEN {fen!r}")
    return parts[1]


def to_mover_relative(
    fen: str, eval_cp: Optional[int], eval_mate: Optional[int]
) -> Tuple[Optional[int], Optional[int]]:
    """Converts White-perspective `(eval_cp, eval_mate)` to mover-relative: unchanged
    when White is to move, both present values negated when Black is to move. `None`
    (absent) stays `None`. The operation is an involution, which the repair tool's
    `already normalized` guard exists to protect against applying twice.
    """
    if side_to_move(fen) == "w":
        return eval_cp, eval_mate
    return (
        None if eval_cp is None else -eval_cp,
        None if eval_mate is None else -eval_mate,
    )
