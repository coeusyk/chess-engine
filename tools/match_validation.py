#!/usr/bin/env python3
"""Decides from a finished cutechess-cli 1.5.1 log and PGN whether a two-engine match really completed (#319).

Why: with `-recover`, Cute Chess finishes a match after its engines are killed and exits 0, so neither the exit
status nor the line `SPRT complete` says the run finished. The #296 native SPRT was cut short by a host power off
and every success signal still said it was done. This module reads the two files the launchers already write
and puts the run in one explicit state.

Scope: the output of cutechess-cli 1.5.1 for one match between two engines with `-repeat`. It is not a general
tournament parser. Anything outside the vocabulary listed below ends in INVALID_EVIDENCE, never in a pass.

States (see docs/superpowers/plans/2026-11-25-319-sprt-completion-integrity.md):
  COMPLETE_CAP       exactly the expected number of finished games, all normal, complete opening pairs
  COMPLETE_BOUNDARY  SPRT only: accepted boundary consistent with its llr, normal end, pairs explained
  INTERRUPTED        abandoned or disconnected games, unterminated games without a boundary, an unexplained
                     orphan opening, no `Finished match`, or too few games
  OVERRUN            more finished games than expected
  INVALID_EVIDENCE   missing, empty or unparseable files, duplicates, unknown termination, a contradiction
                     between log and PGN or inside the log, final statistics that do not match the PGN

Usage: python match_validation.py --log LOG --pgn PGN [--expected-games N] [--sprt] [--max-unterminated N]
Exit status 0 only for COMPLETE_CAP and COMPLETE_BOUNDARY.
"""

from __future__ import annotations

import argparse
import hashlib
import re
import sys
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Tuple

COMPLETE_CAP = "COMPLETE_CAP"
COMPLETE_BOUNDARY = "COMPLETE_BOUNDARY"
INTERRUPTED = "INTERRUPTED"
OVERRUN = "OVERRUN"
INVALID_EVIDENCE = "INVALID_EVIDENCE"
SUCCESS_STATES = (COMPLETE_CAP, COMPLETE_BOUNDARY)

# Termination classes shared by the log text and the PGN tag, so the two can be compared.
NORMAL, ADJUDICATION, TIME_FORFEIT, RULES_INFRACTION, ABANDONED, UNTERMINATED = (
    "normal", "adjudication", "time forfeit", "rules infraction", "abandoned", "unterminated")
ABNORMAL_CLASSES = (ABANDONED, UNTERMINATED)

# Log result texts: (regex, termination class, the only Result token the text can come with).
_LOG_TEXTS: Sequence[Tuple[str, str, str]] = (
    (r"White mates", NORMAL, "1-0"),
    (r"Black mates", NORMAL, "0-1"),
    (r"White wins by adjudication", ADJUDICATION, "1-0"),
    (r"Black wins by adjudication", ADJUDICATION, "0-1"),
    (r"White loses on time", TIME_FORFEIT, "0-1"),
    (r"Black loses on time", TIME_FORFEIT, "1-0"),
    (r"White makes an illegal move.*", RULES_INFRACTION, "0-1"),
    (r"Black makes an illegal move.*", RULES_INFRACTION, "1-0"),
    (r"Draw by 3-fold repetition", NORMAL, "1/2-1/2"),
    (r"Draw by fifty moves rule", NORMAL, "1/2-1/2"),
    (r"Draw by insufficient mating material", NORMAL, "1/2-1/2"),
    (r"Draw by stalemate", NORMAL, "1/2-1/2"),
    (r"Draw by adjudication", ADJUDICATION, "1/2-1/2"),
    (r"White disconnects", ABANDONED, "0-1"),
    (r"Black disconnects", ABANDONED, "1-0"),
    (r"White's connection stalls", ABANDONED, "0-1"),
    (r"Black's connection stalls", ABANDONED, "1-0"),
    (r"No result", UNTERMINATED, "*"),
)
_LOG_TEXT_RES = [(re.compile(f"^{pattern}$"), cls, result) for pattern, cls, result in _LOG_TEXTS]

# PGN Termination tag values. An absent tag is how Cute Chess writes a game that ended by mate or a draw rule.
_PGN_CLASSES = {"": NORMAL, "normal": NORMAL, "adjudication": ADJUDICATION, "time forfeit": TIME_FORFEIT,
                "rules infraction": RULES_INFRACTION, "abandoned": ABANDONED, "unterminated": UNTERMINATED}

_FINISHED = re.compile(r"^Finished game (\d+) \((\S+) vs (\S+)\): (1-0|0-1|1/2-1/2|\*) \{(.*)\}$")
_SCORE = re.compile(r"^Score of (\S+) vs (\S+): (\d+) - (\d+) - (\d+)\s+\[[0-9.]+\]\s+(\d+)$")
_SPRT = re.compile(r"^SPRT: llr (-?[0-9.]+) \([^)]*\), lbound (-?[0-9.]+), ubound (-?[0-9.]+)(?: - (H[01]) was accepted)?$")
_TAG = re.compile(r'^\[(\w+) "(.*)"\]$')
_RESULT_TOKENS = ("1-0", "0-1", "1/2-1/2", "*")


@dataclass
class RunVerdict:
    state: str
    games: int = 0            # finished games that carry a result (1-0, 0-1, 1/2-1/2), as Cute Chess scores them
    unterminated: int = 0
    abandoned: int = 0
    orphan_openings: int = 0  # excess finished games per opening, summed, before in-flight explanations
    unexplained_orphans: int = 0
    complete_pairs: int = 0
    reasons: List[str] = field(default_factory=list)
    notes: List[str] = field(default_factory=list)

    @property
    def success(self) -> bool:
        return self.state in SUCCESS_STATES

    def describe(self) -> str:
        lines = [f"{self.state}: games={self.games} complete_pairs={self.complete_pairs} abandoned={self.abandoned} "
                 f"unterminated={self.unterminated} orphan_openings={self.orphan_openings} "
                 f"unexplained_orphans={self.unexplained_orphans}"]
        lines += [f"  reason: {r}" for r in self.reasons] + [f"  note: {n}" for n in self.notes]
        return "\n".join(lines)


class _Invalid(Exception):
    pass


@dataclass
class _Game:
    white: str
    black: str
    result: str
    cls: str
    opening: str
    record_hash: str


def _classify_log_text(text: str, result: str) -> str:
    for regex, cls, expected_result in _LOG_TEXT_RES:
        if regex.match(text):
            if result != expected_result:
                raise _Invalid(f"log text '{text}' does not agree with its result {result}")
            return cls
    raise _Invalid(f"unknown termination text in the log: '{text}'")


def parse_log(log_text: str):
    """Finished games, the last Score line, the last SPRT line and whether the log ends with `Finished match`."""
    games: List[Tuple[int, str, str, str, str]] = []
    ids = set()
    score = None
    sprt = None
    last_line = ""
    for raw in log_text.splitlines():
        line = raw.rstrip("\r").strip()
        if not line:
            continue
        last_line = line
        m = _FINISHED.match(line)
        if m:
            number = int(m.group(1))
            if number in ids:
                raise _Invalid(f"duplicate game number {number} in the log")
            ids.add(number)
            cls = _classify_log_text(m.group(5), m.group(4))
            games.append((number, m.group(2), m.group(3), m.group(4), cls))
            continue
        if line.startswith("Finished game"):
            raise _Invalid(f"unparseable 'Finished game' line: {line[:80]}")
        m = _SCORE.match(line)
        if m:
            score = (m.group(1), m.group(2), int(m.group(3)), int(m.group(4)), int(m.group(5)), int(m.group(6)))
            continue
        m = _SPRT.match(line)
        if m:
            sprt = (float(m.group(1)), float(m.group(2)), float(m.group(3)), m.group(4))
    return games, score, sprt, last_line == "Finished match"


def parse_pgn(pgn_text: str) -> List[_Game]:
    games: List[_Game] = []
    seen = set()
    blocks: List[List[str]] = []
    current: Optional[List[str]] = None
    for raw in pgn_text.splitlines():
        line = raw.rstrip("\r")
        if line.startswith("[Event "):
            current = [line]
            blocks.append(current)
        elif current is not None:
            current.append(line)
        elif line.strip():
            raise _Invalid("PGN text before the first game header")
    for block in blocks:
        tags: Dict[str, str] = {}
        body: List[str] = []
        for line in block:
            m = _TAG.match(line)
            if m and not body:
                tags[m.group(1)] = m.group(2)
            elif line.strip():
                body.append(line.strip())
            elif tags and not body:
                continue
        for needed in ("White", "Black", "Result"):
            if needed not in tags:
                raise _Invalid(f"a PGN game has no {needed} tag")
        if tags["Result"] not in _RESULT_TOKENS:
            raise _Invalid(f"unknown PGN result {tags['Result']!r}")
        movetext = " ".join(body).split()
        if not movetext or movetext[-1] != tags["Result"]:
            raise _Invalid("a PGN game is truncated: its movetext does not end with its Result tag")
        termination = tags.get("Termination", "")
        if termination not in _PGN_CLASSES:
            raise _Invalid(f"unknown PGN Termination value {termination!r}")
        cls = _PGN_CLASSES[termination]
        if (cls == UNTERMINATED) != (tags["Result"] == "*"):
            raise _Invalid("a PGN game's Termination and Result disagree about whether it finished")
        record_hash = hashlib.sha256("\n".join(block).encode("utf-8")).hexdigest()
        if record_hash in seen:
            raise _Invalid("a PGN game record appears twice")
        seen.add(record_hash)
        games.append(_Game(tags["White"], tags["Black"], tags["Result"], cls, tags.get("FEN", "startpos"), record_hash))
    return games


def _opening_pairs(finished: List[_Game], unterminated: List[_Game], engines: Tuple[str, str]):
    """Complete pairs, total orphans, and the orphans no in-flight unterminated twin explains."""
    a, b = engines
    white_a: Counter = Counter()
    white_b: Counter = Counter()
    for g in finished:
        (white_a if g.white == a else white_b)[g.opening] += 1
    twins: Counter = Counter((g.opening, g.white) for g in unterminated)
    pairs = orphans = unexplained = 0
    for opening in set(white_a) | set(white_b):
        na, nb = white_a[opening], white_b[opening]
        pairs += min(na, nb)
        excess = abs(na - nb)
        orphans += excess
        need_white = b if na > nb else a  # the missing twin has the other engine as White
        take = min(excess, twins[(opening, need_white)])
        twins[(opening, need_white)] -= take
        unexplained += excess - take
    return pairs, orphans, unexplained


def classify_run(log_text: Optional[str], pgn_text: Optional[str], expected_games: Optional[int] = None,
                 sprt: bool = False, max_unterminated: Optional[int] = None) -> RunVerdict:
    if not log_text or not log_text.strip():
        return RunVerdict(INVALID_EVIDENCE, reasons=["the log is missing or empty"])
    if not pgn_text or not pgn_text.strip():
        return RunVerdict(INVALID_EVIDENCE, reasons=["the PGN is missing or empty"])
    try:
        log_games, score, sprt_line, normal_end = parse_log(log_text)
        pgn_games = parse_pgn(pgn_text)
        if not log_games:
            raise _Invalid("the log has no finished game")
        log_key = Counter((w, b, r, c) for _, w, b, r, c in log_games)
        pgn_key = Counter((g.white, g.black, g.result, g.cls) for g in pgn_games)
        log_only, pgn_only = log_key - pgn_key, pgn_key - log_key
        # Cute Chess does not write a PGN record for a game that never made a move (the real #296 run has five
        # `No result` games in its log and four `unterminated` records). That one asymmetry is tolerated and
        # counted. Any other difference between the two files is contradictory evidence.
        if pgn_only or any(key[3] != UNTERMINATED for key in log_only):
            raise _Invalid(f"the log and the PGN disagree about the games: {sum(log_only.values())} in the log only, "
                           f"{sum(pgn_only.values())} in the PGN only")
        log_only_unterminated = sum(log_only.values())
        names = sorted({g.white for g in pgn_games} | {g.black for g in pgn_games})
        if len(names) != 2:
            raise _Invalid(f"expected exactly two engines, found {names}")
        if score is None:
            raise _Invalid("the log has no final 'Score of' line")
        a, b = score[0], score[1]
        if sorted((a, b)) != names:
            raise _Invalid("the Score line names different engines than the games")
        wins = losses = draws = 0
        for g in pgn_games:
            if g.result == "*":
                continue
            if g.result == "1/2-1/2":
                draws += 1
            elif (g.result == "1-0") == (g.white == a):
                wins += 1
            else:
                losses += 1
        if (score[2], score[3], score[4], score[5]) != (wins, losses, draws, wins + losses + draws):
            raise _Invalid(f"the final Score line ({score[2]} - {score[3]} - {score[4]} over {score[5]}) does not match "
                           f"the PGN ({wins} - {losses} - {draws} over {wins + losses + draws})")
        if sprt and sprt_line is None:
            raise _Invalid("an SPRT run has no 'SPRT:' statistics line")
        if not sprt and sprt_line is not None:
            raise _Invalid("a fixed-count run has an unexpected 'SPRT:' statistics line")
        if sprt_line is not None and sprt_line[3] is not None:
            llr, lower, upper, accepted = sprt_line
            consistent = llr >= upper - 0.011 if accepted == "H1" else llr <= lower + 0.011
            if not consistent:
                raise _Invalid(f"the SPRT line says {accepted} was accepted but llr {llr} is not beyond its bound")
    except _Invalid as error:
        return RunVerdict(INVALID_EVIDENCE, reasons=[str(error)])

    finished = [g for g in pgn_games if g.cls != UNTERMINATED]
    unterminated = [g for g in pgn_games if g.cls == UNTERMINATED]
    abandoned = [g for g in pgn_games if g.cls == ABANDONED]
    pairs, orphans, unexplained = _opening_pairs(finished, unterminated, (a, b))
    # A log-only unterminated game has no FEN to match, so it can stand in for one otherwise unexplained orphan.
    unexplained = max(0, unexplained - log_only_unterminated)
    verdict = RunVerdict(INTERRUPTED, games=wins + losses + draws, unterminated=len(unterminated) + log_only_unterminated,
                         abandoned=len(abandoned), orphan_openings=orphans, unexplained_orphans=unexplained,
                         complete_pairs=pairs)
    boundary = sprt_line is not None and sprt_line[3] is not None
    if not normal_end:
        verdict.reasons.append("the log does not end with 'Finished match'")
    if abandoned:
        verdict.reasons.append(f"{len(abandoned)} game(s) ended abandoned or disconnected")
    if max_unterminated is not None and verdict.unterminated > max_unterminated:
        verdict.reasons.append(f"{verdict.unterminated} unterminated games, more than the {max_unterminated} that can be in flight")
    if boundary:
        if unexplained:
            verdict.reasons.append(f"{unexplained} orphan opening game(s) not explained by an in-flight unterminated twin")
        if verdict.unterminated:
            verdict.notes.append(f"{verdict.unterminated} game(s) were still in flight when the boundary was reached")
        if not verdict.reasons:
            verdict.state = COMPLETE_BOUNDARY
        return verdict
    if verdict.unterminated:
        verdict.reasons.append(f"{verdict.unterminated} game(s) unterminated and no SPRT boundary was reached")
    if orphans:
        verdict.reasons.append(f"{orphans} orphan opening game(s): the opening pairs are not complete")
    if expected_games is not None:
        if len(finished) > expected_games:
            verdict.state = OVERRUN
            verdict.reasons.append(f"{len(finished)} finished games, more than the expected {expected_games}")
            return verdict
        if len(finished) < expected_games:
            verdict.reasons.append(f"{len(finished)} finished games, fewer than the expected {expected_games}")
    elif not sprt:
        verdict.reasons.append("no expected game count was given for a fixed-count run")
    if not verdict.reasons:
        verdict.state = COMPLETE_CAP
    return verdict


def classify_files(log_path: str, pgn_path: str, expected_games: Optional[int] = None, sprt: bool = False,
                   max_unterminated: Optional[int] = None) -> RunVerdict:
    def read(path: str) -> Optional[str]:
        p = Path(path)
        return p.read_text(encoding="utf-8", errors="replace") if p.is_file() else None
    return classify_run(read(log_path), read(pgn_path), expected_games, sprt, max_unterminated)


def main(argv: Optional[Sequence[str]] = None) -> int:
    p = argparse.ArgumentParser(description="Classify a finished cutechess-cli match from its log and PGN.")
    p.add_argument("--log", required=True)
    p.add_argument("--pgn", required=True)
    p.add_argument("--expected-games", type=int, default=None)
    p.add_argument("--sprt", action="store_true")
    p.add_argument("--max-unterminated", type=int, default=None)
    args = p.parse_args(argv)
    verdict = classify_files(args.log, args.pgn, args.expected_games, args.sprt, args.max_unterminated)
    print(verdict.describe())
    return 0 if verdict.success else 1


if __name__ == "__main__":
    sys.exit(main())
