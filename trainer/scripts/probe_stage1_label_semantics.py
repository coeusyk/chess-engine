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

  white-perspective   (a) is at least `MIN_CORRELATION` for both sides, (b) is not, and no contiguous tenth of
                      either side's rows (file order) has a correlation with (a) below zero
  mover-relative      (b) is at least `MIN_CORRELATION` for both sides, (a) is not, and no contiguous tenth
                      has a correlation with (b) below zero
  inconclusive        anything else, including fewer than `--min-rows` rows with a cp label on either side

The exit status is 0 only for a definite verdict, so the result can gate a repair. The report carries the
SHA-256 of the manifest it was measured on and the directory's base name only, never a local path. Material
is a crude proxy for the score, which is why the threshold is a sign test, not a fit.

What this establishes, and what it cannot: the MAJORITY convention of the file. The real files measure about
+0.20 (Black to move) against a threshold of 0.10, a margin of about two, and with labels this noisy a minority
of rows that were already corrected, interleaved at random, is not visible: a quarter of the Black-to-move rows
flipped back still gives a verdict of white-perspective, and about a third pushes the correlation under the
threshold. The contiguous-tenth check catches a block of mostly flipped rows, such as a re-acquired segment or a
shard that was repaired separately, and nothing finer. That the whole file was written by one adapter version is
therefore a claim for the acquisition-code evidence, not for this probe, and the repair is only as justified
as both together.

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


CHUNKS = 10


def _chunk_correlations(xs: List[float], ys: List[float], chunks: int = CHUNKS) -> List[Optional[float]]:
    n = len(xs)
    return [_pearson(xs[i * n // chunks:(i + 1) * n // chunks], ys[i * n // chunks:(i + 1) * n // chunks]) for i in range(chunks)]


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
        chunk_white = _chunk_correlations(white_balance[side], labels[side]) if len(labels[side]) >= CHUNKS * 2 else []
        sides[name] = {
            "rows_with_cp": len(labels[side]),
            "correlation_with_white_minus_black_material": None if r_white is None else round(r_white, 4),
            "correlation_with_mover_material_balance": None if r_mover is None else round(r_mover, 4),
            "chunk_correlations_with_white_minus_black_material": [None if c is None else round(c, 3) for c in chunk_white],
        }

    def chunks_agree(perspective: str) -> bool:
        """No contiguous tenth with a correlation of the wrong sign (an undefined one counts as disagreeing).
        Against White-minus-Black material the expected sign is positive for both sides under White-perspective
        labels, and positive for White to move but negative for Black to move under mover-relative labels."""
        for side, name in (("w", "white_to_move"), ("b", "black_to_move")):
            chunk = sides[name]["chunk_correlations_with_white_minus_black_material"]
            expected = 1 if (perspective == "white" or side == "w") else -1
            if not chunk or any(c is None or expected * c < 0 for c in chunk):
                return False
        return True

    def follows(key: str) -> bool:
        return all(sides[s][key] is not None and sides[s][key] >= MIN_CORRELATION
                   for s in ("white_to_move", "black_to_move"))

    enough = all(sides[s]["rows_with_cp"] >= min_rows for s in sides)
    white, mover = follows("correlation_with_white_minus_black_material"), follows("correlation_with_mover_material_balance")
    if enough and white and not mover and chunks_agree("white"):
        verdict = "white-perspective"
    elif enough and mover and not white and chunks_agree("mover"):
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
