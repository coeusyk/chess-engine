"""Contract checks for the v0.5.7 vs v0.6.0 release SPRT tooling. Nothing here starts an engine or a game."""
import ast
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
    def lines(self, name):
        data = (RESEARCH / name).read_bytes()
        rows = data.decode("ascii").split("\n")
        self.assertEqual(rows.pop(), "")
        return data, rows

    def test_committed_lists_reproduce_and_match_the_runner_pins(self):
        done = subprocess.run([sys.executable, "-I", str(ROOT / "tools/release-sprt-openings.py"), "--check"],
                              cwd=ROOT, capture_output=True, text=True)
        self.assertEqual(done.returncode, 0, done.stderr)
        for name, pin, count in (("openings-v1.epd", "OpeningSha256", 10000), ("openings-smoke-v1.epd", "SmokeOpeningSha256", 10)):
            data, rows = self.lines(name)
            self.assertEqual(hashlib.sha256(data).hexdigest(), common_value(pin), name)
            self.assertEqual(len(rows), count)
            self.assertEqual(len({position_key(r) for r in rows}), count, "distinct positions: Cute Chess rewrites the move counters")
            self.assertTrue(all(len(r.split(" ")) == 6 and r.split(" ")[1] == "w" for r in rows))

    def test_smoke_and_production_share_no_position(self):
        production = {position_key(r) for r in self.lines("openings-v1.epd")[1]}
        smoke = {position_key(r) for r in self.lines("openings-smoke-v1.epd")[1]}
        self.assertFalse(production & smoke)


def position_key(fen):
    return analyze.position_key(fen)


def game(white, result, fen):
    black = "OLD" if white == "NEW" else "NEW"
    return f'[Event "x"]\n[White "{white}"]\n[Black "{black}"]\n[Result "{result}"]\n[FEN "{fen}"]\n\n1. e4 {result}\n\n'


class Analyzer(unittest.TestCase):
    def test_reproduces_the_archived_phase21_result(self):
        r = analyze.analyze((PHASE21 / "match.log").read_text(encoding="utf-8", errors="replace"),
                            (PHASE21 / "match.pgn").read_text(encoding="utf-8", errors="replace"), 0, 10, 0.05, 0.05)
        self.assertEqual((r["wins"], r["losses"], r["draws"]), (4080, 3829, 5209))
        self.assertEqual((r["scored_games"], r["cancelled_games"], r["games_started"]), (13118, 5, 13123))
        self.assertEqual(r["completed_pairs"] * 2 + r["unpaired_games"], r["scored_games"])
        self.assertTrue(r["games_reconcile_with_log_score"])
        rec = r["llr_reconciliation"]
        self.assertTrue(rec["agrees_with_cutechess_log"])
        self.assertAlmostEqual(rec["model_llr"], 2.9709, places=3)
        self.assertAlmostEqual(r["game_level"]["elo"], r["cutechess_elo"], delta=0.06)
        half = (r["game_level"]["elo_95_high"] - r["game_level"]["elo_95_low"]) / 2
        self.assertAlmostEqual(half, r["cutechess_elo_error"], delta=0.1)

    def test_cutechess_model_matches_an_independent_worked_example(self):
        self.assertAlmostEqual(analyze.cutechess_llr(6303, 5757, 7940, 0, 20), -2.819, places=3)

    def test_pairs_are_scored_by_opening_and_cancelled_games_are_excluded(self):
        pgn = (game("NEW", "1-0", "A w - - 0 1") + game("OLD", "1-0", "A w - - 0 1")
               + game("NEW", "1-0", "B w - - 0 1") + game("OLD", "0-1", "B w - - 0 1")
               + game("NEW", "*", "C w - - 0 1"))
        r = analyze.analyze("", pgn, 0, 20, 0.05, 0.05)
        self.assertEqual((r["wins"], r["losses"], r["draws"], r["cancelled_games"], r["games_started"]), (3, 1, 0, 1, 5))
        self.assertEqual(r["completed_pairs"], 2)
        self.assertEqual(r["pair_points_histogram_0_to_2"]["1.0"], 1)
        self.assertEqual(r["pair_points_histogram_0_to_2"]["2.0"], 1)

    def test_a_one_sided_sample_does_not_crash(self):
        r = analyze.analyze("", game("NEW", "1-0", "A w - - 0 1") + game("OLD", "0-1", "A w - - 0 1"), 0, 20, 0.05, 0.05)
        self.assertIsNone(r["game_level"]["elo_95_high"])

    def test_opening_reconciliation_accepts_a_prefix_and_flags_reuse_gaps_and_strangers(self):
        book = [f"p{i}/8/8/8/8/8/8/K6k w - - 0 4" for i in range(5)]
        fen = lambda i: book[i].rsplit(" ", 2)[0] + " 0 1"            # Cute Chess rewrites the counters
        ok = [{"FEN": fen(i)} for i in (0, 0, 1, 1, 2)]
        self.assertFalse(analyze.reconcile_openings(ok, book)["wrapped_or_repeated"])
        self.assertTrue(analyze.reconcile_openings(ok + [{"FEN": fen(0)}], book)["wrapped_or_repeated"])     # a third use: wraparound
        self.assertTrue(analyze.reconcile_openings([{"FEN": fen(i)} for i in (0, 0, 2, 2)], book)["wrapped_or_repeated"])  # gap
        self.assertTrue(analyze.reconcile_openings([{"FEN": "k7/8/8/8/8/8/8/K7 w - - 0 1"}], book)["wrapped_or_repeated"])  # unknown

    def test_the_analyzer_is_read_only_and_cannot_reach_the_runner(self):
        source = (ROOT / "tools/release-sprt-analyze.py").read_text(encoding="utf-8")
        imported = {a.name.split(".")[0] for n in ast.walk(ast.parse(source)) if isinstance(n, ast.Import) for a in n.names}
        imported |= {n.module.split(".")[0] for n in ast.walk(ast.parse(source)) if isinstance(n, ast.ImportFrom)}
        self.assertFalse(imported & {"subprocess", "os", "shutil", "socket", "signal", "ctypes"}, imported)
        self.assertNotRegex(source, r"open\([^)]*[\"']\s*[wax]\+?b?[\"']")
        self.assertNotRegex(source, r"write_text|write_bytes|\.unlink|\.mkdir")
        wrapper = (ROOT / "tools/release-strength-sprt.ps1").read_text(encoding="utf-8") + COMMON
        self.assertIsNone(re.search(r"python|py\.exe", wrapper, re.I), "the runner never invokes the analyzer or any interpreter")


class ArchivedCompatibility(unittest.TestCase):
    def transcript(self, name):
        return (RESEARCH / "compatibility" / name).read_text(encoding="utf-8")

    def options(self, text):
        return set(re.findall(r"(?m)^option name (\S+) type", text))

    def sent(self, array_name):
        shared = re.search(r"\$shared = @\((.*?)\)", COMMON).group(1)
        names = {o.split("=")[0].strip("' ") for o in shared.split(",")}
        return names | ({"EvalType"} if array_name == "new" else set()) | {"Threads"}

    def test_each_engine_advertises_every_option_it_is_sent(self):
        cand = self.transcript("uci-handshake-candidate-v0.6.0.txt")
        base = self.transcript("uci-handshake-baseline-v0.5.7.txt")
        self.assertLessEqual(self.sent("new"), self.options(cand))
        self.assertLessEqual(self.sent("old"), self.options(base))
        self.assertNotIn("EvalType", self.options(base))
        for text in (cand, base):
            self.assertRegex(text, r"(?m)^uciok$")
            self.assertRegex(text, r"(?m)^readyok$")
            self.assertIn("[exit code 0]", text)
            self.assertIn("option name Contempt type spin default 0 min 0 max 200", text)

    def test_the_contempt_correction_keeps_its_provenance(self):
        report = (ROOT / "docs/architecture/research/release-comparison-v0.5.7-v0.6.0/README.md").read_text(encoding="utf-8")
        self.assertIn("## Corrections", report)
        self.assertIn("Originally stated", report)
        self.assertIn("29aafac", report)
        self.assertNotIn("is 50 in v0.5.7 and 0 in v0.6.0. The in-process", report.split("## Corrections")[0])


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
        for text in ("Elo0 = 0, Elo1 = 20", "tc=5+0.05", "20,000 game starts", "alpha = beta = 0.05"):
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
                     ROOT / "tools/release-strength-sprt-validation.ps1", ROOT / "tools/release-sprt-analyze.py", ROOT / "tools/release-sprt-openings.py",
                     ROOT / "docs/architecture/research/release-comparison-v0.5.7-v0.6.0/README.md"]:
            text = path.read_text(encoding="utf-8")
            self.assertIsNone(re.search(r"/home/|/tmp/|[A-Za-z]:\\Users", text), path.name)
            self.assertIsNone(re.search("[–—‘’“”]", text), path.name)


if __name__ == "__main__":
    unittest.main()
