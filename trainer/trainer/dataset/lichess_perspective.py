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

import json
from pathlib import Path
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


class UnsafeStage1PerspectiveError(ValueError):
    """A Stage 1 dataset directory cannot be shown to hold mover-relative labels."""


def require_mover_relative_stage1(directory: Path) -> None:
    """Fail-closed gate for the training entry (`scripts/train_candidate_net.py::
    combine_and_split`), called on the Stage 1 directory only -- never from a reader, so
    Stage 2/3 sources and `TextDatasetProvider` are unaffected.

    Training proceeds only when `<directory>/manifest.json` exists and declares
    `label_perspective == "mover-relative"`. Everything else is refused with a message
    that names the remedy:

    * no readable manifest: provenance cannot be established;
    * marker absent (every pre-#263 manifest): the labels are historical
      White-perspective, so training on them would invert half the data. Run
      `scripts/repair_stage1_perspective.py` to produce a new, marked directory;
    * any other marker value (including an explicit `white-relative`): refused, since
      only `mover-relative` is known to match the trainer's label contract.
    """
    manifest_path = Path(directory) / "manifest.json"
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise UnsafeStage1PerspectiveError(
            f"{directory}: cannot read manifest.json ({exc}); refusing to train on a Stage 1 dataset "
            f"whose label perspective is unknown"
        ) from exc
    if not isinstance(manifest, dict):
        raise UnsafeStage1PerspectiveError(f"{directory}: manifest.json is not a JSON object")

    marker = manifest.get(LABEL_PERSPECTIVE_FIELD)
    if marker == MOVER_RELATIVE:
        return
    if marker is None:
        raise UnsafeStage1PerspectiveError(
            f"{directory}: manifest has no {LABEL_PERSPECTIVE_FIELD!r} marker, so this is a legacy "
            "White-perspective Stage 1 dataset (issue #263). Run scripts/repair_stage1_perspective.py "
            "to write a corrected copy and train on that."
        )
    raise UnsafeStage1PerspectiveError(
        f"{directory}: {LABEL_PERSPECTIVE_FIELD}={marker!r}, expected {MOVER_RELATIVE!r}"
    )
