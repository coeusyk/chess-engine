"""Score orientation of a network (#265, plan #329): does a positive score mean good for the side to move?

An NNUE evaluator is mover-relative: positive is good for the side to move. A network trained on labels of the
wrong perspective (the #263 defect) can have perfect float-versus-quantized parity and still be wrong, so
parity cannot stand in for orientation. This module checks orientation two ways, kept apart on purpose.

**A hard gate on controlled positions.** `orientation_positions.epd` holds quiet positions with a decisive,
unambiguous material difference (a queen or two rooks against a bare king and a few pawns, a full army against
two rooks), each in four forms: the advantaged colour is White or Black, and it is to move or not. The expected
sign of the mover's score is known by construction: positive when the side to move is the advantaged colour,
negative otherwise. `controlled_orientation` requires the correct sign on every position, because a network that
gets a queen-up position wrong in sign for one colour is broken, not unlucky. A score of zero is not the correct
sign. The set is committed and was written before any network was scored, and the gate is not loosened to pass a
network: a real network that fails a position is reported as a finding about that network, with the position.

**Diagnostics, not gates.** `corpus_diagnostics` reports, on a corpus of positions, the sign agreement between
the score and the mover's material balance for each side to move (positions with an imbalance of at least 3
pawns, the count stated), the gap between the sides, the Pearson correlation (a different metric from sign
agreement and never merged with it), and the difference between a position's score and its colour-flipped
mirror's. Material is a poor proxy for the score in tactical positions, and a trained network is not exactly
mirror-symmetric, so no threshold turns these into a pass or fail. They are measurements to read next to the
gate.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence, Tuple

import numpy as np

POSITIONS_FILE = Path(__file__).with_name("orientation_positions.epd")
_VALUE = {"p": 1, "n": 3, "b": 3, "r": 5, "q": 9}
MIN_IMBALANCE_PAWNS = 3


def mirror_fen(fen: str) -> str:
    """The colour-flipped position: ranks reversed, piece colours swapped, side to move, castling rights and
    en passant square swapped accordingly. The mover-relative score of a symmetric evaluator is unchanged."""
    fields = fen.split()
    placement, side, castling = fields[0], fields[1], fields[2] if len(fields) > 2 else "-"
    ep = fields[3] if len(fields) > 3 else "-"
    flipped = "/".join(reversed(placement.split("/"))).swapcase()
    new_side = "b" if side == "w" else "w"
    new_castling = "-" if castling == "-" else "".join(sorted(castling.swapcase(), key=lambda c: "KQkq".index(c)))
    new_ep = "-" if ep == "-" else ep[0] + str(9 - int(ep[1]))
    return f"{flipped} {new_side} {new_castling} {new_ep}"


def controlled_forms(white_advantage_white_to_move: str) -> List[Tuple[str, int]]:
    """The four forms of one base position, as `(fen, expected sign of the mover's score)`."""
    base = white_advantage_white_to_move.split()
    white_adv_black_to_move = " ".join([base[0], "b", *base[2:]])
    black_adv_black_to_move = mirror_fen(white_advantage_white_to_move)
    mirrored = black_adv_black_to_move.split()
    black_adv_white_to_move = " ".join([mirrored[0], "w", *mirrored[2:]])
    return [(white_advantage_white_to_move, 1), (white_adv_black_to_move, -1),
            (black_adv_black_to_move, 1), (black_adv_white_to_move, -1)]


def load_controlled_positions(path: Path = POSITIONS_FILE) -> List[Tuple[str, int]]:
    positions: List[Tuple[str, int]] = []
    for line in Path(path).read_text(encoding="ascii").splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            fen, _, sign = line.partition(";")
            positions.append((fen.strip(), int(sign)))
    return positions


@dataclass(frozen=True)
class OrientationResult:
    passed: bool
    checked: int
    failures: List[Dict[str, Any]] = field(default_factory=list)


def controlled_orientation(scores: Sequence[int], positions: Sequence[Tuple[str, int]]) -> OrientationResult:
    """The hard gate: the correct sign on every controlled position (zero is not a sign)."""
    if len(scores) != len(positions):
        raise ValueError(f"{len(scores)} scores for {len(positions)} controlled positions")
    failures = [{"fen": fen, "expected_sign": expected, "score": int(score)}
                for score, (fen, expected) in zip(scores, positions)
                if int(score) == 0 or (int(score) > 0) != (expected > 0)]
    return OrientationResult(passed=not failures, checked=len(positions), failures=failures)


def _mover_balance(fen: str) -> int:
    balance = 0
    for char in fen.split()[0]:
        value = _VALUE.get(char.lower())
        if value is not None:
            balance += value if char.isupper() else -value
    return balance if fen.split()[1] == "w" else -balance


def _pearson(xs: Sequence[float], ys: Sequence[float]) -> Optional[float]:
    if len(xs) < 2:
        return None
    x, y = np.asarray(xs, dtype=np.float64), np.asarray(ys, dtype=np.float64)
    if x.std() == 0 or y.std() == 0:
        return None
    return float(np.corrcoef(x, y)[0, 1])


def corpus_diagnostics(scores: Sequence[int], fens: Sequence[str],
                       mirror_scores: Optional[Sequence[int]] = None) -> Dict[str, Any]:
    """Measurements only. No field here has a pass or fail threshold."""
    if len(scores) != len(fens):
        raise ValueError(f"{len(scores)} scores for {len(fens)} FENs")
    report: Dict[str, Any] = {"positions": len(fens), "min_imbalance_pawns": MIN_IMBALANCE_PAWNS}
    agreement: Dict[str, Optional[float]] = {}
    for side, name in (("w", "white_to_move"), ("b", "black_to_move")):
        indices = [i for i, f in enumerate(fens) if f.split()[1] == side]
        balances = [_mover_balance(fens[i]) for i in indices]
        side_scores = [int(scores[i]) for i in indices]
        decisive = [(s, b) for s, b in zip(side_scores, balances) if abs(b) >= MIN_IMBALANCE_PAWNS and s != 0]
        agree = sum(1 for s, b in decisive if (s > 0) == (b > 0))
        agreement[name] = round(agree / len(decisive), 4) if decisive else None
        correlation = _pearson(side_scores, balances)
        report[name] = {"positions": len(indices), "decisive_positions": len(decisive),
                        "sign_agreement": agreement[name],
                        "pearson_with_material": None if correlation is None else round(correlation, 4)}
    both = [agreement["white_to_move"], agreement["black_to_move"]]
    report["side_gap"] = None if None in both else round(abs(both[0] - both[1]), 4)
    if mirror_scores is not None:
        if len(mirror_scores) != len(scores):
            raise ValueError("mirror_scores must pair with scores")
        delta = np.abs(np.asarray(scores, dtype=np.int64) - np.asarray(mirror_scores, dtype=np.int64))
        report["mirror_difference_cp"] = {"mean": round(float(delta.mean()), 3), "max": int(delta.max())}
    return report
