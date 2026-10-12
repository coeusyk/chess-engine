"""Post-match reconciliation and reporting for the v0.5.7 vs v0.6.0 release SPRT. Read-only.

    python tools/release-sprt-analyze.py <run-dir> [--elo0 0 --elo1 20 --alpha 0.05 --beta 0.05] [--openings FILE]

The opening list is chosen from the Kind recorded in the run's identity.json (production list for a run, smoke list for a
smoke attempt). A smoke attempt is labelled as not a result.

Cute Chess decides when the SPRT stops. Nothing here feeds back into that decision: this script reads archived
files, prints JSON to stdout and writes nothing. It reconciles the archive against the Cute Chess log and reports
statistics; a disagreement is reported, it does not overrule Cute Chess.

NEW is the candidate (v0.6.0), OLD is the baseline (v0.5.7). Elo is logistic.

llr_reconciliation uses a port of the SPRT model in cutechess-cli 1.5.1 (projects/lib/src/sprt.cpp: Jeffreys +0.5
per class, BayesElo with an estimated draw Elo). It reproduces the archived Phase 21 value (2.9709 against 2.97
printed). The printed value has two decimals, so the tolerance is 0.01. The Elo intervals are nominal fixed-sample
intervals: the match stops on an LLR boundary, so their coverage after that stop is not guaranteed to be 95%.
The pair-level numbers treat the two color-reversed games on one opening as one observation and use a normal
approximation; Cute Chess has no pair model, so they are descriptive only.
"""
import argparse
import json
import math
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RESEARCH = ROOT / "docs/architecture/research/release-strength-sprt-v0.5.7-v0.6.0"
OPENINGS_BY_KIND = {"run": RESEARCH / "openings-v1.epd", "smoke": RESEARCH / "openings-smoke-v1.epd"}
LLR_TOLERANCE = 0.01


def elo_of(score):
    """Logistic Elo of a score. None when the score is 0 or 1 or outside (0, 1), where Elo is unbounded or undefined."""
    if not 0.0 < score < 1.0:
        return None
    return -400.0 * math.log10(1.0 / score - 1.0)


def score_of(elo):
    return 1.0 / (1.0 + 10.0 ** (-elo / 400.0))


def cutechess_llr(wins, losses, draws, elo0, elo1):
    """The LLR cutechess-cli 1.5.1 computes for a W/L/D count."""
    w, l, d = wins + 0.5, losses + 0.5, draws + 0.5
    n = w + l + d
    p_win, p_loss = w / n, l / n
    draw_elo = 200.0 * math.log10((1.0 - p_loss) / p_loss * (1.0 - p_win) / p_win)
    x = 10.0 ** (-draw_elo / 400.0)
    scale = 4.0 * x / ((1.0 + x) ** 2)

    def probs(elo):
        bayes = elo / scale
        pw = 1.0 / (1.0 + 10.0 ** ((draw_elo - bayes) / 400.0))
        pl = 1.0 / (1.0 + 10.0 ** ((draw_elo + bayes) / 400.0))
        return pw, pl, 1.0 - pw - pl
    p0, p1 = probs(elo0), probs(elo1)
    return w * math.log(p1[0] / p0[0]) + l * math.log(p1[1] / p0[1]) + d * math.log(p1[2] / p0[2])


def moments(counts_by_value):
    n = sum(counts_by_value.values())
    mean = sum(v * c for v, c in counts_by_value.items()) / n
    var = sum(c * (v - mean) ** 2 for v, c in counts_by_value.items()) / n
    return n, mean, var


def summarize(counts_by_value, elo0, elo1):
    n, mean, var = moments(counts_by_value)
    se = math.sqrt(var / n)
    s0, s1 = score_of(elo0), score_of(elo1)
    return {
        "observations": n, "score": mean, "elo": elo_of(mean),
        "elo_95_low": elo_of(mean - 1.96 * se), "elo_95_high": elo_of(mean + 1.96 * se),
        "interval_basis": "nominal fixed-sample normal approximation; not corrected for sequential stopping",
        "llr_normal_approximation": (s1 - s0) * (2 * mean - s0 - s1) * n / (2 * var) if var > 0 else None,
    }


def read_pgn(text):
    games = []
    for block in re.split(r"(?m)^(?=\[Event\s)", text):
        tags = dict(re.findall(r'(?m)^\[(\w+) "((?:\\.|[^"\\])*)"\]\s*$', block))
        if {"White", "Black", "Result", "FEN"} <= tags.keys():
            games.append(tags)
    return games


def position_key(fen):
    """Cute Chess rewrites the move counters in the PGN FEN tag, so compare the first four fields only."""
    return " ".join(fen.split()[:4])


def reconcile_openings(games, opening_lines):
    """Check that the games used a gap-free prefix of the opening list, each opening at most twice."""
    index = {position_key(line): i for i, line in enumerate(opening_lines)}
    uses = Counter(position_key(g["FEN"]) for g in games)
    unknown = sorted(k for k in uses if k not in index)
    used = sorted(index[k] for k in uses if k in index)
    prefix_complete = used == list(range(len(used)))
    return {
        "openings_in_list": len(opening_lines), "distinct_openings_used": len(uses),
        "unknown_openings": len(unknown), "highest_index_used": used[-1] if used else None,
        "used_is_gap_free_prefix": prefix_complete,
        "games_per_opening": {str(k): v for k, v in sorted(Counter(uses.values()).items())},
        "any_opening_used_more_than_twice": any(c > 2 for c in uses.values()),
        "wrapped_or_repeated": (not prefix_complete) or any(c > 2 for c in uses.values()) or bool(unknown),
    }


def analyze(log_text, pgn_text, elo0, elo1, alpha, beta, opening_lines=None):
    points = {"1-0": (1.0, 0.0), "0-1": (0.0, 1.0), "1/2-1/2": (0.5, 0.5)}
    game_counts = defaultdict(int)
    pairs = defaultdict(dict)
    cancelled = 0
    games = read_pgn(pgn_text)
    for g in games:
        if g["Result"] not in points:
            cancelled += 1
            continue
        new_white = g["White"] == "NEW"
        got = points[g["Result"]][0 if new_white else 1]
        game_counts[got] += 1
        pairs[position_key(g["FEN"])].setdefault("W" if new_white else "B", []).append(got)
    complete = {0.0: 0, 0.25: 0, 0.5: 0, 0.75: 0, 1.0: 0}
    unpaired = 0
    for sides in pairs.values():
        # Exactly one game per side is a pair. A third game on one opening is reuse: it is counted unpaired and
        # opening_reconciliation flags it, but it never overwrites a scored game.
        if len(sides.get("W", [])) == 1 and len(sides.get("B", [])) == 1:
            complete[(sides["W"][0] + sides["B"][0]) / 2] += 1
        else:
            unpaired += len(sides.get("W", [])) + len(sides.get("B", []))
    wins, draws, losses = game_counts[1.0], game_counts[0.5], game_counts[0.0]
    out = {"scored_games": wins + draws + losses, "cancelled_games": cancelled, "games_started": len(games),
           "wins": wins, "draws": draws, "losses": losses,
           "completed_pairs": sum(complete.values()), "unpaired_games": unpaired,
           "pair_points_histogram_0_to_2": {str(k * 2): v for k, v in complete.items()}}
    out["game_level"] = summarize(game_counts, elo0, elo1)
    if out["completed_pairs"]:
        out["pair_level"] = summarize(complete, elo0, elo1)
    rec = {"model_llr": cutechess_llr(wins, losses, draws, elo0, elo1),
           "lower_bound": math.log(beta / (1 - alpha)), "upper_bound": math.log((1 - beta) / alpha), "tolerance": LLR_TOLERANCE}
    llr = re.findall(r"(?m)^SPRT:\s+llr\s+(-?[\d.]+)", log_text)
    if llr:
        rec["log_llr"] = float(llr[-1])
        rec["difference"] = rec["model_llr"] - rec["log_llr"]
        rec["agrees_with_cutechess_log"] = abs(rec["difference"]) <= LLR_TOLERANCE
    out["llr_reconciliation"] = rec
    elo = re.findall(r"(?m)^Elo difference:\s*(-?[\d.]+)\s*\+/-\s*([\d.]+)", log_text)
    if elo:
        out["cutechess_elo"], out["cutechess_elo_error"] = float(elo[-1][0]), float(elo[-1][1])
    out["games_reconcile_with_log_score"] = None
    score = re.findall(r"(?m)^Score of NEW vs OLD:\s*(\d+)\s*-\s*(\d+)\s*-\s*(\d+)", log_text)
    if score:
        out["games_reconcile_with_log_score"] = (wins, losses, draws) == tuple(int(x) for x in score[-1])
    if opening_lines is not None:
        out["opening_reconciliation"] = reconcile_openings(games, opening_lines)
    return out


def main():
    p = argparse.ArgumentParser()
    p.add_argument("run_dir")
    p.add_argument("--elo0", type=float, default=0)
    p.add_argument("--elo1", type=float, default=20)
    p.add_argument("--alpha", type=float, default=0.05)
    p.add_argument("--beta", type=float, default=0.05)
    p.add_argument("--openings", default=None)
    a = p.parse_args()
    d = Path(a.run_dir)
    kind = None
    if (d / "identity.json").exists():
        kind = json.loads((d / "identity.json").read_text(encoding="utf-8-sig")).get("Kind")
    openings = Path(a.openings) if a.openings else OPENINGS_BY_KIND.get(kind, OPENINGS_BY_KIND["run"])
    lines = openings.read_text(encoding="ascii").split("\n")
    lines = [l for l in lines if l]
    result = analyze((d / "match.log").read_text(encoding="utf-8", errors="replace"),
                     (d / "match.pgn").read_text(encoding="utf-8", errors="replace"),
                     a.elo0, a.elo1, a.alpha, a.beta, lines)
    result["attempt_kind"] = kind
    if kind == "smoke":
        result["note"] = "SMOKE ATTEMPT: not a result. Statistics below describe a handful of games and are not an Elo estimate."
    json.dump(result, sys.stdout, indent=2)
    print()


if __name__ == "__main__":
    main()
