"""Tests for tools/match_validation.py (#319).

Two kinds of input. The real-artifact fixtures under tools/tests/fixtures/match_validation/ are the full
2026-04-21 `phase14-a4-aspiration-25cp-s2` SPRT run that ended at an H1 boundary with one game still in flight, and the
last 30 games of the interrupted #296 run (movetext removed from both, and the excerpt's Score line recomputed
because an excerpt cannot carry the full run's totals). The focused regressions build small runs in memory so
each rule can be broken on its own.

The `fixed-260-*` fixtures are cut from the real #260 fixed-count run (Cute Chess 1.5.1, no `-sprt`, interrupted by a
power outage after 1,217 of 4,000 games): real `Finished game` lines, the real inactive `SPRT:` status line, the real
`Started game` lines of the abandoned in-flight games, movetext removed, and the excerpt's Score line recomputed. The
Elo line is the run's own and is not parsed. `fixed-260-head-complete` is games 1 to 30 of that run with a closing
`Finished match` added by hand, because the real run never reached one: it stands in for a normally completed
fixed-count match and its only claim is the real log format.
"""

import os
import re
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))

import match_validation as mv  # noqa: E402

FIXTURES = ROOT / "tools/tests/fixtures/match_validation"


def read(name):
    return (FIXTURES / name).read_text(encoding="utf-8")


def pgn_game(white, black, result, fen, termination=None, extra=""):
    tags = ['[Event "?"]', '[Site "?"]', '[Round "1"]', f'[White "{white}"]', f'[Black "{black}"]',
            f'[Result "{result}"]', f'[FEN "{fen}"]', '[SetUp "1"]']
    if extra:
        tags.append(extra)
    if termination:
        tags.append(f'[Termination "{termination}"]')
    return "\n".join(tags) + "\n\n1. e4 e5 " + result + "\n\n"


def build(games, expected_score=None, finished_match=True, sprt_line=None):
    """games: list of (number, white, black, result, log_text, fen, pgn_termination). Returns (log, pgn)."""
    log = ["Indexing opening suite..."]
    pgn = []
    w = l = d = 0
    for number, white, black, result, text, fen, term in games:
        log.append(f"Finished game {number} ({white} vs {black}): {result} {{{text}}}")
        pgn.append(pgn_game(white, black, result, fen, term, extra=f'[GameStartTime "t{number}"]'))
        if result == "*":
            continue
        if result == "1/2-1/2":
            d += 1
        elif (result == "1-0") == (white == "NEW"):
            w += 1
        else:
            l += 1
    score = expected_score or (w, l, d, w + l + d)
    log.append(f"Score of NEW vs OLD: {score[0]} - {score[1]} - {score[2]}  [0.500] {score[3]}")
    log.append("Elo difference: 0.0 +/- 0.0, LOS: 50.0 %, DrawRatio: 0.0 %")
    if sprt_line:
        log.append(sprt_line)
    log.append("")
    if finished_match:
        log.append("Finished match")
    return "\n".join(log) + "\n", "".join(pgn)


OPEN_A = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
OPEN_B = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1"
DRAW = "Draw by 3-fold repetition"


def cap_run(n_pairs=2):
    games = []
    number = 1
    for i in range(n_pairs):
        fen = OPEN_A if i % 2 == 0 else OPEN_B
        games.append((number, "NEW", "OLD", "1-0", "White mates", fen, None))
        games.append((number + 1, "OLD", "NEW", "1/2-1/2", DRAW, fen, None))
        number += 2
    return games


class RealArtifacts(unittest.TestCase):
    def test_the_real_completed_boundary_run_is_complete(self):
        v = mv.classify_run(read("boundary-real.log.txt"), read("boundary-real.pgn"), expected_games=20000, sprt=True)
        self.assertEqual(mv.COMPLETE_BOUNDARY, v.state, v.describe())
        # 185 finished games is odd: the in-flight game's twin is the orphan, and it is explained.
        self.assertEqual((185, 1, 1, 0, 92), (v.games, v.unterminated, v.orphan_openings, v.unexplained_orphans, v.complete_pairs))

    def test_the_real_296_tail_is_interrupted_not_complete(self):
        v = mv.classify_run(read("interrupted-296-tail.log.txt"), read("interrupted-296-tail.pgn"), expected_games=30000, sprt=True)
        self.assertEqual(mv.INTERRUPTED, v.state, v.describe())
        self.assertEqual((6, 4), (v.abandoned, v.unterminated))
        self.assertTrue(any("abandoned" in r for r in v.reasons))

    def test_the_boundary_run_is_not_a_complete_run_when_no_boundary_text_is_present(self):
        log = read("boundary-real.log.txt").replace(" - H1 was accepted", "")
        v = mv.classify_run(log, read("boundary-real.pgn"), expected_games=20000, sprt=True)
        self.assertEqual(mv.INTERRUPTED, v.state, v.describe())

    @unittest.skipUnless(os.environ.get("MATCH_VALIDATION_296_DIR"), "set MATCH_VALIDATION_296_DIR to the singular-296 run folder")
    def test_the_full_296_run_when_available(self):
        folder = Path(os.environ["MATCH_VALIDATION_296_DIR"])
        v = mv.classify_files(str(folder / "match.log"), str(folder / "match.pgn"), 30000, True)
        self.assertEqual(mv.INTERRUPTED, v.state, v.describe())
        # six abandoned games, and five `No result` games in the log of which only four have a PGN record
        self.assertEqual((6, 5, 10758), (v.abandoned, v.unterminated, v.games - 6))
        # 5,382 pairs counts the three pairs completed by the abandoned games; the 5,379 clean pairs of the
        # #296 note are those without them.
        self.assertEqual(5382, v.complete_pairs)


class InactiveSprtStatus(unittest.TestCase):
    """Cute Chess 1.5.1 prints `SPRT: llr 0 (0.0%), lbound -inf, ubound inf` in a match run without `-sprt` (#260)."""

    INACTIVE = "SPRT: llr 0 (0.0%), lbound -inf, ubound inf"

    def test_the_real_260_head_is_a_complete_fixed_count_run(self):
        log = read("fixed-260-head-complete.log.txt")
        self.assertIn(self.INACTIVE, log)
        v = mv.classify_run(log, read("fixed-260-head-complete.pgn"), expected_games=30)
        self.assertEqual(mv.COMPLETE_CAP, v.state, v.describe())
        self.assertEqual((30, 15, 0), (v.games, v.complete_pairs, v.orphan_openings))

    def test_the_real_260_tail_is_interrupted_not_invalid(self):
        log = read("fixed-260-interrupted-tail.log.txt")
        self.assertIn(self.INACTIVE, log)
        v = mv.classify_run(log, read("fixed-260-interrupted-tail.pgn"), expected_games=4000)
        self.assertEqual(mv.INTERRUPTED, v.state, v.describe())
        self.assertEqual((29, 14, 1, 1), (v.games, v.complete_pairs, v.orphan_openings, v.unexplained_orphans))
        self.assertTrue(any("Finished match" in r for r in v.reasons))

    @unittest.skipUnless(os.environ.get("MATCH_VALIDATION_260_DIR"), "set MATCH_VALIDATION_260_DIR to the fixed-260 run folder")
    def test_the_full_260_run_when_available(self):
        folder = Path(os.environ["MATCH_VALIDATION_260_DIR"])
        v = mv.classify_files(str(folder / "match.log"), str(folder / "match.pgn"), 4000)
        self.assertEqual(mv.INTERRUPTED, v.state, v.describe())
        self.assertEqual((1217, 608, 1, 1, 0, 0), (v.games, v.complete_pairs, v.orphan_openings, v.unexplained_orphans,
                                                   v.abandoned, v.unterminated))

    def test_the_inactive_line_is_not_accepted_for_an_sprt_run(self):
        log, pgn = build(cap_run(2), sprt_line=self.INACTIVE)
        v = mv.classify_run(log, pgn, sprt=True)
        self.assertEqual(mv.INVALID_EVIDENCE, v.state, v.describe())
        self.assertTrue(any("no 'SPRT:' statistics line" in r for r in v.reasons))

    def test_a_finite_sprt_line_in_a_fixed_count_run_is_still_invalid(self):
        log, pgn = build(cap_run(2), sprt_line="SPRT: llr 0.10 (3.4%), lbound -2.94, ubound 2.94")
        self.assertEqual(mv.INVALID_EVIDENCE, mv.classify_run(log, pgn, expected_games=4).state)

    def test_an_acceptance_message_on_the_infinite_bounds_is_invalid(self):
        for line in (self.INACTIVE + " - H1 was accepted", self.INACTIVE + " - H0 was accepted"):
            log, pgn = build(cap_run(2), sprt_line=line)
            self.assertEqual(mv.INVALID_EVIDENCE, mv.classify_run(log, pgn, expected_games=4).state, line)

    def test_other_infinite_or_nonzero_variants_are_malformed(self):
        for line in ("SPRT: llr 0 (0.0%), lbound -inf, ubound 2.94", "SPRT: llr 0 (0.0%), lbound -2.94, ubound inf",
                     "SPRT: llr 1.20 (40.0%), lbound -inf, ubound inf", "SPRT: llr 0 (0.0%), lbound inf, ubound -inf",
                     "SPRT: llr 0 (0.0%), lbound -inf, ubound inf extra", "SPRT: llr 0 (0.0%), lbound -inf, ubound inf "
                     "- H1 was accepted"):
            log, pgn = build(cap_run(2), sprt_line=line)
            self.assertEqual(mv.INVALID_EVIDENCE, mv.classify_run(log, pgn, expected_games=4).state, line)

    def test_an_inactive_line_mixed_with_a_finite_statistic_is_contradictory(self):
        log, pgn = build(cap_run(2), sprt_line="SPRT: llr 0.10 (3.4%), lbound -2.94, ubound 2.94")
        log = log.replace("Finished match", self.INACTIVE + "\nFinished match")
        for sprt in (False, True):
            v = mv.classify_run(log, pgn, expected_games=4, sprt=sprt)
            self.assertEqual(mv.INVALID_EVIDENCE, v.state, v.describe())

    def test_a_complete_fixed_count_run_with_the_inactive_line_is_complete(self):
        log, pgn = build(cap_run(2), sprt_line=self.INACTIVE)
        self.assertEqual(mv.COMPLETE_CAP, mv.classify_run(log, pgn, expected_games=4).state)


class BoundaryRules(unittest.TestCase):
    """Amendment 1: a boundary needs a consistent llr, a normal end, consistent statistics and explained pairs."""

    def test_acceptance_text_with_a_llr_that_is_not_beyond_the_bound_is_invalid(self):
        log = read("boundary-real.log.txt").replace("llr 4.12 (101.1%)", "llr 1.00 (24.5%)")
        v = mv.classify_run(log, read("boundary-real.pgn"), sprt=True)
        self.assertEqual(mv.INVALID_EVIDENCE, v.state, v.describe())

    def test_acceptance_without_the_final_match_line_is_interrupted(self):
        log = read("boundary-real.log.txt").rstrip().removesuffix("Finished match")
        v = mv.classify_run(log, read("boundary-real.pgn"), sprt=True)
        self.assertEqual(mv.INTERRUPTED, v.state, v.describe())
        self.assertTrue(any("Finished match" in r for r in v.reasons))

    def test_final_statistics_that_do_not_match_the_pgn_are_invalid(self):
        log = read("boundary-real.log.txt")
        log = log[:log.rindex("Score of NEW vs OLD: 97 - 19 - 69")] + log[log.rindex("Score of NEW vs OLD: 97 - 19 - 69"):].replace(
            "97 - 19 - 69  [0.711] 185", "98 - 19 - 69  [0.711] 186", 1)
        v = mv.classify_run(log, read("boundary-real.pgn"), sprt=True)
        self.assertEqual(mv.INVALID_EVIDENCE, v.state, v.describe())

    def test_an_orphan_opening_with_no_in_flight_twin_is_not_a_complete_boundary(self):
        log, pgn = read("boundary-real.log.txt"), read("boundary-real.pgn")
        # remove the in-flight game from both files: its twin is now an unexplained orphan
        log = re.sub(r"^Finished game 186 .*\n", "", log, flags=re.M)
        games = re.split(r"(?=^\[Event )", pgn, flags=re.M)
        games = [g for g in games if 'Termination "unterminated"' not in g]
        v = mv.classify_run(log, "".join(games), sprt=True)
        self.assertEqual(mv.INTERRUPTED, v.state, v.describe())
        self.assertEqual(1, v.unexplained_orphans)

    def test_a_boundary_with_a_disconnected_game_is_interrupted(self):
        games = cap_run(2)
        games[0] = (1, "NEW", "OLD", "0-1", "White disconnects", OPEN_A, "abandoned")
        games[1] = (2, "OLD", "NEW", "1/2-1/2", DRAW, OPEN_A, None)
        log, pgn = build(games, sprt_line="SPRT: llr 3.00 (100.0%), lbound -2.94, ubound 2.94 - H1 was accepted")
        v = mv.classify_run(log, pgn, sprt=True)
        self.assertEqual(mv.INTERRUPTED, v.state, v.describe())


class LogOnlyUnterminated(unittest.TestCase):
    """The real #296 run has a `No result` game in its log that Cute Chess never wrote to the PGN."""

    def test_a_log_only_no_result_game_is_counted_and_still_interrupts_a_capped_run(self):
        log, pgn = build(cap_run(2))
        log = log.replace("Score of", "Finished game 5 (NEW vs OLD): * {No result}\nScore of", 1)
        v = mv.classify_run(log, pgn, expected_games=4)
        self.assertEqual(mv.INTERRUPTED, v.state, v.describe())
        self.assertEqual(1, v.unterminated)

    def test_a_log_only_game_with_a_result_is_still_contradictory_evidence(self):
        log, pgn = build(cap_run(2))
        log = log.replace("Score of", "Finished game 5 (NEW vs OLD): 1-0 {White mates}\nScore of", 1)
        self.assertEqual(mv.INVALID_EVIDENCE, mv.classify_run(log, pgn, expected_games=4).state)

    def test_a_pgn_only_game_is_contradictory_evidence(self):
        log, pgn = build(cap_run(2))
        pgn += pgn_game("NEW", "OLD", "1-0", OPEN_A, extra='[GameStartTime "t99"]')
        self.assertEqual(mv.INVALID_EVIDENCE, mv.classify_run(log, pgn, expected_games=4).state)

    def test_a_log_only_no_result_game_can_stand_for_the_twin_at_a_boundary(self):
        games = cap_run(2)[:3]  # three finished games: one orphan opening
        log, pgn = build(games, sprt_line="SPRT: llr 3.00 (100.0%), lbound -2.94, ubound 2.94 - H1 was accepted")
        log = log.replace("Score of", "Finished game 4 (OLD vs NEW): * {No result}\nScore of", 1)
        v = mv.classify_run(log, pgn, sprt=True)
        self.assertEqual(mv.COMPLETE_BOUNDARY, v.state, v.describe())


class LogOnlyTwinColours(unittest.TestCase):
    """A log-only game has no opening to check, but its colours must be those a twin would have."""

    BOUNDARY = "SPRT: llr 3.00 (100.0%), lbound -2.94, ubound 2.94 - H1 was accepted"

    def three_games(self):
        return build(cap_run(2)[:3], sprt_line=self.BOUNDARY)  # the orphan is game 3, NEW as White

    def test_a_log_only_game_with_the_same_colours_as_the_orphan_is_not_its_twin(self):
        log, pgn = self.three_games()
        log = log.replace("Score of", "Finished game 4 (NEW vs OLD): * {No result}\nScore of", 1)
        v = mv.classify_run(log, pgn, sprt=True)
        self.assertEqual(mv.INTERRUPTED, v.state, v.describe())
        self.assertEqual(1, v.unexplained_orphans)

    def test_a_log_only_game_with_the_opposite_colours_can_be_its_twin(self):
        log, pgn = self.three_games()
        log = log.replace("Score of", "Finished game 4 (OLD vs NEW): * {No result}\nScore of", 1)
        self.assertEqual(mv.COMPLETE_BOUNDARY, mv.classify_run(log, pgn, sprt=True).state)

    def test_one_log_only_game_explains_one_orphan_only(self):
        games = [(1, "NEW", "OLD", "1-0", "White mates", OPEN_A, None),
                 (2, "NEW", "OLD", "1-0", "White mates", OPEN_B, None)]  # two orphans, both need OLD as White
        log, pgn = build(games, sprt_line=self.BOUNDARY)
        log = log.replace("Score of", "Finished game 3 (OLD vs NEW): * {No result}\nScore of", 1)
        v = mv.classify_run(log, pgn, sprt=True)
        self.assertEqual(mv.INTERRUPTED, v.state, v.describe())
        self.assertEqual(1, v.unexplained_orphans)


class MalformedStatistics(unittest.TestCase):
    """A later unparseable statistics line must not be hidden by an earlier good one."""

    def corrupt(self, extra):
        log = read("boundary-real.log.txt").replace("Finished match", extra + "\nFinished match")
        return mv.classify_run(log, read("boundary-real.pgn"), sprt=True)

    def test_a_corrupted_sprt_line_is_invalid(self):
        v = self.corrupt("SPRT: corrupted")
        self.assertEqual(mv.INVALID_EVIDENCE, v.state, v.describe())
        self.assertTrue(any("SPRT:" in r for r in v.reasons))

    def test_a_corrupted_score_line_is_invalid(self):
        v = self.corrupt("Score of NEW vs OLD: corrupted")
        self.assertEqual(mv.INVALID_EVIDENCE, v.state, v.describe())
        self.assertTrue(any("Score of" in r for r in v.reasons))

    def test_both_corrupted_together_are_invalid(self):
        self.assertEqual(mv.INVALID_EVIDENCE, self.corrupt("SPRT: corrupted\nScore of NEW vs OLD: corrupted").state)

    def test_the_unmodified_real_run_still_passes(self):
        self.assertEqual(mv.COMPLETE_BOUNDARY, self.corrupt("").state)


class OpeningPairs(unittest.TestCase):
    """Amendment 3: pair completeness comes from the openings, not from the game count."""

    def test_a_complete_capped_run_passes(self):
        log, pgn = build(cap_run(2))
        v = mv.classify_run(log, pgn, expected_games=4)
        self.assertEqual(mv.COMPLETE_CAP, v.state, v.describe())
        self.assertEqual(2, v.complete_pairs)

    def test_an_even_game_count_with_incomplete_pairs_does_not_pass(self):
        games = [(1, "NEW", "OLD", "1-0", "White mates", OPEN_A, None),
                 (2, "NEW", "OLD", "1-0", "White mates", OPEN_A, None),  # same colour twice, no twin
                 (3, "OLD", "NEW", "1/2-1/2", DRAW, OPEN_B, None),
                 (4, "OLD", "NEW", "1/2-1/2", DRAW, OPEN_B, None)]
        log, pgn = build(games)
        v = mv.classify_run(log, pgn, expected_games=4)
        self.assertEqual(4, v.games)
        self.assertEqual(mv.INTERRUPTED, v.state, v.describe())
        self.assertEqual(4, v.orphan_openings)

    def test_an_odd_capped_run_does_not_pass(self):
        log, pgn = build(cap_run(2)[:3])
        v = mv.classify_run(log, pgn, expected_games=3)
        self.assertEqual(mv.INTERRUPTED, v.state, v.describe())
        self.assertEqual(1, v.orphan_openings)

    def test_the_same_opening_recurring_in_two_pairs_is_not_a_duplicate(self):
        games = cap_run(2)
        games = [(n, w, b, r, t, OPEN_A, term) for n, w, b, r, t, _, term in games]
        log, pgn = build(games)
        v = mv.classify_run(log, pgn, expected_games=4)
        self.assertEqual(mv.COMPLETE_CAP, v.state, v.describe())


class CountsAndIdentity(unittest.TestCase):
    """Amendments 2 and the fixed-count rules."""

    def test_fewer_games_than_expected_is_interrupted(self):
        log, pgn = build(cap_run(2))
        self.assertEqual(mv.INTERRUPTED, mv.classify_run(log, pgn, expected_games=6).state)

    def test_more_games_than_expected_is_an_overrun(self):
        log, pgn = build(cap_run(2))
        self.assertEqual(mv.OVERRUN, mv.classify_run(log, pgn, expected_games=2).state)

    def test_a_fixed_count_run_without_an_expected_count_does_not_pass(self):
        log, pgn = build(cap_run(2))
        self.assertEqual(mv.INTERRUPTED, mv.classify_run(log, pgn, expected_games=None).state)

    def test_duplicate_game_numbers_in_the_log_are_invalid(self):
        games = cap_run(2)
        games[3] = (3,) + games[3][1:]
        log, pgn = build(games)
        v = mv.classify_run(log, pgn, expected_games=4)
        self.assertEqual(mv.INVALID_EVIDENCE, v.state, v.describe())
        self.assertTrue(any("duplicate game number" in r for r in v.reasons))

    def test_a_duplicated_pgn_record_is_invalid(self):
        # The log has five distinct game numbers, so the log and the PGN agree on the multiset of games and
        # only the exact repetition of a whole PGN record (same tags, same start time) can reveal the problem.
        games = cap_run(2) + [(5, "NEW", "OLD", "1-0", "White mates", OPEN_A, None)]
        log, pgn = build(games)
        v = mv.classify_run(log, pgn.replace('GameStartTime "t5"', 'GameStartTime "t1"'), expected_games=5)
        self.assertEqual(mv.INVALID_EVIDENCE, v.state, v.describe())
        self.assertTrue(any("appears twice" in r for r in v.reasons))

    def test_a_log_and_pgn_that_disagree_about_an_outcome_are_invalid(self):
        games = cap_run(2)
        log, _ = build(games)
        games[0] = (1, "NEW", "OLD", "0-1", "White mates", OPEN_A, None)  # the PGN will say 0-1
        _, pgn = build(games)
        v = mv.classify_run(log, pgn, expected_games=4)
        self.assertEqual(mv.INVALID_EVIDENCE, v.state, v.describe())

    def test_a_log_text_that_contradicts_its_result_token_is_invalid(self):
        games = cap_run(2)
        games[0] = (1, "NEW", "OLD", "0-1", "White mates", OPEN_A, None)
        log, pgn = build(games)
        self.assertEqual(mv.INVALID_EVIDENCE, mv.classify_run(log, pgn, expected_games=4).state)

    def test_a_score_line_that_does_not_match_the_pgn_is_invalid(self):
        log, pgn = build(cap_run(2), expected_score=(3, 0, 1, 4))
        self.assertEqual(mv.INVALID_EVIDENCE, mv.classify_run(log, pgn, expected_games=4).state)

    def test_an_sprt_line_in_a_fixed_count_run_is_invalid(self):
        log, pgn = build(cap_run(2), sprt_line="SPRT: llr 0.10 (3.4%), lbound -2.94, ubound 2.94")
        self.assertEqual(mv.INVALID_EVIDENCE, mv.classify_run(log, pgn, expected_games=4, sprt=False).state)

    def test_an_sprt_run_without_an_sprt_line_is_invalid(self):
        log, pgn = build(cap_run(2))
        self.assertEqual(mv.INVALID_EVIDENCE, mv.classify_run(log, pgn, expected_games=4, sprt=True).state)


class TerminationVocabulary(unittest.TestCase):
    """Amendment 4: supported categories pass, unknown or abnormal ones never do."""

    def run_with(self, text, result, term=None):
        games = cap_run(2)
        games[0] = (1, "NEW", "OLD", result, text, OPEN_A, term)
        log, pgn = build(games)
        return mv.classify_run(log, pgn, expected_games=4)

    def test_every_supported_normal_text_passes(self):
        supported = [("White mates", "1-0", None), ("White wins by adjudication", "1-0", "adjudication"),
                     ("Black loses on time", "1-0", "time forfeit"), ("White makes an illegal move: e2e5", "0-1", "rules infraction"),
                     ("Draw by fifty moves rule", "1/2-1/2", None), ("Draw by insufficient mating material", "1/2-1/2", None),
                     ("Draw by stalemate", "1/2-1/2", None), ("Draw by adjudication", "1/2-1/2", "adjudication")]
        for text, result, term in supported:
            with self.subTest(text=text):
                self.assertEqual(mv.COMPLETE_CAP, self.run_with(text, result, term).state)

    def test_an_unknown_log_text_is_invalid(self):
        self.assertEqual(mv.INVALID_EVIDENCE, self.run_with("White is struck by lightning", "0-1").state)

    def test_an_unknown_pgn_termination_is_invalid(self):
        self.assertEqual(mv.INVALID_EVIDENCE, self.run_with("White mates", "1-0", "mystery").state)

    def test_a_log_and_pgn_that_classify_a_game_differently_are_invalid(self):
        self.assertEqual(mv.INVALID_EVIDENCE, self.run_with("White wins by adjudication", "1-0", None).state)

    def test_a_disconnect_is_interrupted_and_reported(self):
        v = self.run_with("Black disconnects", "1-0", "abandoned")
        self.assertEqual(mv.INTERRUPTED, v.state)
        self.assertEqual(1, v.abandoned)

    def test_a_stalled_connection_is_interrupted(self):
        self.assertEqual(mv.INTERRUPTED, self.run_with("White's connection stalls", "0-1", "abandoned").state)

    def test_a_no_result_game_in_a_capped_run_is_interrupted(self):
        self.assertEqual(mv.INTERRUPTED, self.run_with("No result", "*", "unterminated").state)


class EvidenceProblems(unittest.TestCase):
    def test_missing_or_empty_files_are_invalid(self):
        log, pgn = build(cap_run(2))
        for case in ((None, pgn), (log, None), ("", pgn), (log, "  \n")):
            with self.subTest(case=[bool(c) for c in case]):
                self.assertEqual(mv.INVALID_EVIDENCE, mv.classify_run(case[0], case[1], 4).state)

    def test_a_pgn_cut_in_the_middle_of_a_game_is_invalid(self):
        log, pgn = build(cap_run(2))
        v = mv.classify_run(log, pgn[: pgn.rindex("1. e4")] + "1. e4 e", expected_games=4)
        self.assertEqual(mv.INVALID_EVIDENCE, v.state, v.describe())

    def test_a_log_without_a_final_match_line_is_interrupted(self):
        log, pgn = build(cap_run(2), finished_match=False)
        self.assertEqual(mv.INTERRUPTED, mv.classify_run(log, pgn, expected_games=4).state)

    def test_nonexistent_paths_are_invalid(self):
        v = mv.classify_files("no-such.log", "no-such.pgn", 4)
        self.assertEqual(mv.INVALID_EVIDENCE, v.state)

    def test_the_command_line_exit_status_follows_success(self):
        log, pgn = build(cap_run(2))
        with tempfile.TemporaryDirectory() as folder:
            lp, pp = Path(folder) / "m.log", Path(folder) / "m.pgn"
            lp.write_text(log, encoding="utf-8")
            pp.write_text(pgn, encoding="utf-8")
            self.assertEqual(0, mv.main(["--log", str(lp), "--pgn", str(pp), "--expected-games", "4"]))
            self.assertEqual(1, mv.main(["--log", str(lp), "--pgn", str(pp), "--expected-games", "6"]))


if __name__ == "__main__":
    unittest.main()
