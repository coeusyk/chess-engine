"""Establish which perspective a Stage 1 CSV directory's labels are in (issue #263).

`repair_stage1_perspective.py` negates the labels of every Black-to-move row, which is only right when the
shard really holds White-perspective labels. A commit date or a missing manifest marker does not show that.
This probe measures it from the data itself, read-only:

    python -m scripts.probe_stage1_label_semantics <shard_dir> [--min-rows N]

For each side to move it computes the Pearson correlation of `eval_cp` with
  (a) White's material minus Black's (a fixed, colour-based quantity), and
  (b) the mover's material balance (side to move minus the other side).
White-perspective labels follow (a) for both sides to move. Mover-relative labels follow (b) for both, so for
Black to move their correlation with (a) is negative. Verdict:

  white-perspective   (a) is at least `MIN_CORRELATION` for both sides and (b) is not
  mover-relative      (b) is at least `MIN_CORRELATION` for both sides and (a) is not
  inconclusive        anything else, including fewer than `--min-rows` rows with a cp label on either side

The exit status is 0 only for a definite verdict, so the result can gate a repair. The report carries the
SHA-256 of the manifest it was measured on and the directory's base name only, never a local path. Material
is a crude proxy for the score, which is why the threshold is a sign test far below the measured values
(about 0.2 to 0.26 on the real files), not a fit.

Nothing is written to the directory. Rows without an `eval_cp` (mate-only rows) are skipped.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional

from trainer.dataset.lichess_perspective import side_to_move

#: Smallest correlation that counts as "follows this quantity". Real White-perspective data measured
#: +0.20 to +0.26 per side, so this is a sign test with room, not a calibrated fit.
MIN_CORRELATION = 0.10
DEFAULT_MIN_ROWS = 500
_VALUE = {"p": 1, "n": 3, "b": 3, "r": 5, "q": 9}


def _white_minus_black(fen: str) -> int:
    balance = 0
    for char in fen.split()[0]:
        value = _VALUE.get(char.lower())
        if value is not None:
            balance += value if char.isupper() else -value
    return balance


def _pearson(xs: List[float], ys: List[float]) -> Optional[float]:
    n = len(xs)
    if n < 2:
        return None
    mean_x, mean_y = sum(xs) / n, sum(ys) / n
    sxx = sum((x - mean_x) ** 2 for x in xs)
    syy = sum((y - mean_y) ** 2 for y in ys)
    if sxx == 0 or syy == 0:
        return None
    return sum((x - mean_x) * (y - mean_y) for x, y in zip(xs, ys)) / math.sqrt(sxx * syy)


def probe(directory: Path, min_rows: int = DEFAULT_MIN_ROWS) -> Dict[str, Any]:
    directory = Path(directory)
    shards = sorted(directory.glob("*.csv"))
    if not shards:
        raise ValueError(f"{directory.name}: no *.csv shards found")
    white_balance: Dict[str, List[float]] = {"w": [], "b": []}
    labels: Dict[str, List[float]] = {"w": [], "b": []}
    for shard in shards:
        with open(shard, newline="", encoding="ascii") as handle:
            for row in csv.reader(handle):
                if len(row) < 2 or not row[1].strip():
                    continue
                side = side_to_move(row[0])
                white_balance[side].append(_white_minus_black(row[0]))
                labels[side].append(int(row[1]))

    sides: Dict[str, Any] = {}
    for side, name in (("w", "white_to_move"), ("b", "black_to_move")):
        sign = 1 if side == "w" else -1
        r_white = _pearson(white_balance[side], labels[side])
        r_mover = None if r_white is None else sign * r_white  # mover balance = sign * (white - black)
        sides[name] = {
            "rows_with_cp": len(labels[side]),
            "correlation_with_white_minus_black_material": None if r_white is None else round(r_white, 4),
            "correlation_with_mover_material_balance": None if r_mover is None else round(r_mover, 4),
        }

    def follows(key: str) -> bool:
        return all(sides[s][key] is not None and sides[s][key] >= MIN_CORRELATION
                   for s in ("white_to_move", "black_to_move"))

    enough = all(sides[s]["rows_with_cp"] >= min_rows for s in sides)
    white, mover = follows("correlation_with_white_minus_black_material"), follows("correlation_with_mover_material_balance")
    if enough and white and not mover:
        verdict = "white-perspective"
    elif enough and mover and not white:
        verdict = "mover-relative"
    else:
        verdict = "inconclusive"
    manifest = directory / "manifest.json"
    return {
        "directory": directory.name,
        "manifest_sha256": hashlib.sha256(manifest.read_bytes()).hexdigest() if manifest.is_file() else None,
        "min_correlation": MIN_CORRELATION,
        "min_rows_per_side": min_rows,
        **sides,
        "verdict": verdict,
    }


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("shard_dir", type=Path)
    parser.add_argument("--min-rows", type=int, default=DEFAULT_MIN_ROWS)
    args = parser.parse_args(argv)
    report = probe(args.shard_dir, args.min_rows)
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0 if report["verdict"] != "inconclusive" else 1


if __name__ == "__main__":
    sys.exit(main())
