"""Contract checks for the v0.5.7 vs v0.6.0 release SPRT tooling. Nothing here starts an engine or a game."""
import hashlib
import importlib.util
import re
import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RESEARCH = ROOT / "docs/architecture/research/release-strength-sprt-v0.5.7-v0.6.0"
COMMON = (ROOT / "tools/release-strength-sprt-common.ps1").read_text(encoding="utf-8")
PREREG = (RESEARCH / "preregistration.md").read_text(encoding="utf-8")
PHASE21 = ROOT / "tools/results/phase21-stage6/20261011T061318503Z-b5a507c6"


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / "tools" / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def common_value(key):
    return re.search(key + r"\s*=\s*'([^']+)'", COMMON).group(1)


analyze = load("release_sprt_analyze", "release-sprt-analyze.py")


class OpeningList(unittest.TestCase):
    def test_committed_list_reproduces_and_matches_the_runner_pin(self):
        done = subprocess.run([sys.executable, "-I", str(ROOT / "tools/release-sprt-openings.py"), "--check"],
                              cwd=ROOT, capture_output=True, text=True)
        self.assertEqual(done.returncode, 0, done.stderr)
        data = (RESEARCH / "openings-v1.epd").read_bytes()
        self.assertEqual(hashlib.sha256(data).hexdigest(), common_value("OpeningSha256"))
        lines = data.decode("ascii").split("\n")
        self.assertEqual(lines.pop(), "")
        self.assertEqual(len(lines), 10000)
        self.assertEqual(len(set(lines)), 10000)
        self.assertTrue(all(len(l.split(" ")) == 6 and l.split(" ")[1] == "w" for l in lines))


class Analyzer(unittest.TestCase):
    def test_reproduces_the_archived_phase21_result(self):
        r = analyze.analyze((PHASE21 / "match.log").read_text(encoding="utf-8", errors="replace"),
                            (PHASE21 / "match.pgn").read_text(encoding="utf-8", errors="replace"), 0, 10, 0.05, 0.05)
        self.assertEqual((r["wins"], r["losses"], r["draws"]), (4080, 3829, 5209))
        self.assertEqual((r["scored_games"], r["cancelled_games"]), (13118, 5))
        self.assertEqual(r["completed_pairs"] * 2 + r["unpaired_games"], r["scored_games"])
        self.assertAlmostEqual(r["game_level"]["llr"], r["cutechess_llr"], delta=0.02)
        self.assertAlmostEqual(r["game_level"]["elo"], r["cutechess_elo"], delta=0.06)
        half = (r["game_level"]["elo_95_high"] - r["game_level"]["elo_95_low"]) / 2
        self.assertAlmostEqual(half, r["cutechess_elo_error"], delta=0.1)

    def test_pairs_are_scored_by_opening_and_cancelled_games_are_excluded(self):
        def game(white, result, fen):
            black = "OLD" if white == "NEW" else "NEW"
            return f'[Event "x"]\n[White "{white}"]\n[Black "{black}"]\n[Result "{result}"]\n[FEN "{fen}"]\n\n1. e4 {result}\n\n'
        pgn = (game("NEW", "1-0", "A") + game("OLD", "1-0", "A")      # NEW wins as White, loses as Black: pair = 1 point
               + game("NEW", "1-0", "B") + game("OLD", "0-1", "B")    # NEW wins both: pair = 2 points
               + game("NEW", "*", "C"))                                 # cancelled
        r = analyze.analyze("", pgn, 0, 20, 0.05, 0.05)
        self.assertEqual((r["wins"], r["losses"], r["draws"], r["cancelled_games"]), (3, 1, 0, 1))
        self.assertEqual(r["completed_pairs"], 2)
        self.assertEqual(r["pair_points_histogram_0_to_2"]["1.0"], 1)
        self.assertEqual(r["pair_points_histogram_0_to_2"]["2.0"], 1)


class PreregistrationMatchesRunner(unittest.TestCase):
    def test_pins_and_identities_agree(self):
        pin = common_value("PreregistrationSha256")
        self.assertNotEqual(pin, "PENDING")
        self.assertEqual(hashlib.sha256((RESEARCH / "preregistration.md").read_bytes()).hexdigest(), pin)
        for key in ("CandidateSha256", "BaselineSha256", "OpeningSha256"):
            self.assertIn(common_value(key), PREREG, key)

    def test_proposed_parameters_appear_in_both(self):
        for text in ("Elo0 = 0; Elo1 = 20; Alpha = 0.05; Beta = 0.05", "TC = '5+0.05'; Concurrency = 6; EngineThreads = 1", "MaxGames = 20000"):
            self.assertIn(text, COMMON)
        for text in ("Elo0 = 0, Elo1 = 20", "tc=5+0.05", "20,000 games", "alpha = beta = 0.05"):
            self.assertIn(text, PREREG)

    def test_candidate_gets_evaltype_and_baseline_does_not(self):
        self.assertIn("'EvalType=Classical'", COMMON)
        self.assertEqual(COMMON.count("EvalType=Classical"), 1)
        self.assertIn("OldOptions = @($shared)", COMMON)

    def test_shared_runner_default_is_unchanged(self):
        sprt = (ROOT / "tools/sprt.ps1").read_text(encoding="utf-8")
        self.assertIn("[ValidateSet('random','sequential')][string]$OpeningOrder = 'random'", sprt)

    def test_no_absolute_local_paths_or_typographic_dashes(self):
        for path in [RESEARCH / "preregistration.md", ROOT / "tools/release-strength-sprt.ps1", ROOT / "tools/release-strength-sprt-common.ps1",
                     ROOT / "tools/release-strength-sprt-validation.ps1", ROOT / "tools/release-sprt-analyze.py", ROOT / "tools/release-sprt-openings.py"]:
            text = path.read_text(encoding="utf-8")
            self.assertIsNone(re.search(r"/home/|/tmp/|[A-Za-z]:\\Users", text), path.name)
            self.assertIsNone(re.search("[–—‘’“”]", text), path.name)


if __name__ == "__main__":
    unittest.main()
