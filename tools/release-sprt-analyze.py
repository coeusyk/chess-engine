"""Post-match statistics for the v0.5.7 vs v0.6.0 release SPRT. Reads archived files only.

    python tools/release-sprt-analyze.py <run-dir> [--elo0 0 --elo1 20 --alpha 0.05 --beta 0.05]

NEW is the candidate (v0.6.0), OLD is the baseline (v0.5.7). Elo is logistic. The LLR is the
normal-approximation GSPRT, which reproduces cutechess-cli's reported LLR for the archived Phase 21
result (4080-3829-5209 at [0,10] gives 2.974 here against 2.97 reported). The pair-level numbers treat the
two color-reversed games on one opening as one observation.
"""
import argparse
import json
import math
import re
import sys
from collections import defaultdict
from pathlib import Path


def elo_of(score):
    """Logistic Elo of a score. None when the score is 0 or 1 or outside (0, 1), where Elo is unbounded or undefined."""
    if not 0.0 < score < 1.0:
        return None
    return -400.0 * math.log10(1.0 / score - 1.0)


def score_of(elo):
    return 1.0 / (1.0 + 10.0 ** (-elo / 400.0))


def moments(counts_by_value):
    """counts_by_value: {value in [0,1]: count}. Returns n, mean, per-observation variance."""
    n = sum(counts_by_value.values())
    mean = sum(v * c for v, c in counts_by_value.items()) / n
    var = sum(c * (v - mean) ** 2 for v, c in counts_by_value.items()) / n
    return n, mean, var


def summarize(counts_by_value, elo0, elo1, alpha, beta):
    n, mean, var = moments(counts_by_value)
    se = math.sqrt(var / n)
    s0, s1 = score_of(elo0), score_of(elo1)
    llr = (s1 - s0) * (2 * mean - s0 - s1) * n / (2 * var) if var > 0 else float("nan")
    return {
        "observations": n, "score": mean, "elo": elo_of(mean),
        "elo_95_low": elo_of(mean - 1.96 * se), "elo_95_high": elo_of(mean + 1.96 * se),
        "llr": llr, "lower_bound": math.log(beta / (1 - alpha)), "upper_bound": math.log((1 - beta) / alpha),
    }


def read_pgn(text):
    games = []
    for block in re.split(r"(?m)^(?=\[Event\s)", text):
        tags = dict(re.findall(r'(?m)^\[(\w+) "((?:\\.|[^"\\])*)"\]\s*$', block))
        if {"White", "Black", "Result", "FEN"} <= tags.keys():
            games.append(tags)
    return games


def analyze(log_text, pgn_text, elo0, elo1, alpha, beta):
    points = {"1-0": (1.0, 0.0), "0-1": (0.0, 1.0), "1/2-1/2": (0.5, 0.5)}
    game_counts = defaultdict(int)
    pairs = defaultdict(dict)
    cancelled = 0
    for g in read_pgn(pgn_text):
        if g["Result"] not in points:
            cancelled += 1
            continue
        new_white = g["White"] == "NEW"
        got = points[g["Result"]][0 if new_white else 1]
        game_counts[got] += 1
        pairs[g["FEN"]]["W" if new_white else "B"] = got
    complete = {0.0: 0, 0.25: 0, 0.5: 0, 0.75: 0, 1.0: 0}
    unpaired = 0
    for sides in pairs.values():
        if len(sides) == 2:
            complete[(sides["W"] + sides["B"]) / 2] += 1
        else:
            unpaired += 1
    out = {"scored_games": sum(game_counts.values()), "cancelled_games": cancelled,
           "wins": game_counts[1.0], "draws": game_counts[0.5], "losses": game_counts[0.0],
           "completed_pairs": sum(complete.values()), "unpaired_games": unpaired,
           "pair_points_histogram_0_to_2": {str(k * 2): v for k, v in complete.items()}}
    out["game_level"] = summarize(game_counts, elo0, elo1, alpha, beta)
    if out["completed_pairs"]:
        out["pair_level"] = summarize(complete, elo0, elo1, alpha, beta)
    llr = re.findall(r"(?m)^SPRT:\s+llr\s+(-?[\d.]+)", log_text)
    elo = re.findall(r"(?m)^Elo difference:\s*(-?[\d.]+)\s*\+/-\s*([\d.]+)", log_text)
    if llr:
        out["cutechess_llr"] = float(llr[-1])
        out["llr_difference_vs_cutechess"] = out["game_level"]["llr"] - float(llr[-1])
    if elo:
        out["cutechess_elo"], out["cutechess_elo_error"] = float(elo[-1][0]), float(elo[-1][1])
    return out


def main():
    p = argparse.ArgumentParser()
    p.add_argument("run_dir")
    p.add_argument("--elo0", type=float, default=0)
    p.add_argument("--elo1", type=float, default=20)
    p.add_argument("--alpha", type=float, default=0.05)
    p.add_argument("--beta", type=float, default=0.05)
    a = p.parse_args()
    d = Path(a.run_dir)
    result = analyze((d / "match.log").read_text(encoding="utf-8", errors="replace"),
                     (d / "match.pgn").read_text(encoding="utf-8", errors="replace"),
                     a.elo0, a.elo1, a.alpha, a.beta)
    json.dump(result, sys.stdout, indent=2)
    print()


if __name__ == "__main__":
    main()
