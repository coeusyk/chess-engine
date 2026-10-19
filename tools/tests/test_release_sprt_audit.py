"""Checks for tools/release-sprt-audit.py, the independent audit that production requires of the smoke attempt.

Nothing here starts an engine, Cute Chess or a game. The fixtures are synthetic smoke attempts written to a temporary
directory: inert JAR files, the committed smoke opening list, and generated logs and PGN. Each negative test breaks one
thing, rewrites the manifest so only the audit's own criterion can object, and expects exactly that criterion to fail.
"""
import hashlib
import importlib.util
import json
import os
import re
import shutil
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RESEARCH = ROOT / "docs/architecture/research/release-strength-sprt-v0.5.7-v0.6.0"
COMMON = (ROOT / "tools/release-strength-sprt-common.ps1").read_text(encoding="utf-8")


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / "tools" / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


audit = load("release_sprt_audit", "release-sprt-audit.py")
analyze = load("release_sprt_analyze_for_audit", "release-sprt-analyze.py")


def common_value(key):
    return re.search(key + r"\s*=\s*'([^']+)'", COMMON).group(1)


def powershell_list(function):
    body = re.search(r"function " + function + r" \{\s*return @\((.*?)\)\s*\}", COMMON, re.S).group(1)
    return re.findall(r"'([^']+)'", body)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


# The preregistered smoke command line, copied from the arguments.json of the native smoke attempt (62 arguments). It is a literal on purpose:
# the audit's expected_arguments() and the fixture both build this list, so only an independent copy can notice if that function drifts.
GOLDEN_ARGUMENTS = (
    "-engine name=NEW cmd={JAVA} arg=--add-modules arg=jdk.incubator.vector arg=-jar arg={NEW} proto=uci option.Threads=1 option.Hash=16 "
    "option.PawnHashSize=1 option.EvalType=Classical option.MultiPV=1 option.Contempt=0 option.OwnBook=false option.SyzygyOnline=false option.SyzygyPath= "
    "-engine name=OLD cmd={JAVA} arg=--add-modules arg=jdk.incubator.vector arg=-jar arg={OLD} proto=uci option.Threads=1 option.Hash=16 "
    "option.PawnHashSize=1 option.MultiPV=1 option.Contempt=0 option.OwnBook=false option.SyzygyOnline=false option.SyzygyPath= "
    "-each tc=5+0.05 -games 20 -repeat -recover -resign movecount=5 score=400 -draw movenumber=40 movecount=8 score=10 "
    "-sprt elo0=0 elo1=20 alpha=0.05 beta=0.05 -concurrency 6 -ratinginterval 10 -pgnout {PGN} -openings file={OPEN} format=epd order=sequential plies=4"
)


class PinnedToThePowerShellRunner(unittest.TestCase):
    def test_expected_arguments_equal_the_golden_smoke_command_line(self):
        fill = {"JAVA": "J", "NEW": "N", "OLD": "O", "PGN": "P", "OPEN": "E"}
        golden = [re.sub(r"\{(\w+)\}", lambda m: fill[m.group(1)], part) for part in GOLDEN_ARGUMENTS.split(" ")]
        built = audit.expected_arguments("J", ("N", "O"), (audit.FROZEN["new_options"], audit.FROZEN["old_options"]), 20, "P", "E")
        self.assertEqual(len(golden), 62)
        self.assertEqual(built, golden)

    """The audit keeps its own copy of the frozen identities. These tests stop the two copies drifting apart."""

    def test_frozen_hashes_equal_the_runner_pins(self):
        f = audit.FROZEN
        self.assertEqual(f["candidate_jar"][1], common_value("CandidateSha256"))
        self.assertEqual(f["baseline_jar"][1], common_value("BaselineSha256"))
        self.assertEqual(f["smoke_openings"][1], common_value("SmokeOpeningSha256"))
        self.assertEqual(f["production_openings_sha256"], common_value("OpeningSha256"))
        self.assertEqual(f["preregistration_sha256"], common_value("PreregistrationSha256"))
        self.assertEqual(f["smoke_games"], int(re.search(r"SmokeGames = (\d+)", COMMON).group(1)))

    def test_options_equal_the_runner_options(self):
        shared = re.search(r"\$shared = @\((.*?)\)", COMMON).group(1)
        shared = re.findall(r"'([^']*)'", shared)
        self.assertEqual(audit.FROZEN["old_options"], shared)
        self.assertEqual(audit.FROZEN["new_options"], shared[:2] + ["EvalType=Classical"] + shared[2:])

    def test_script_and_criteria_lists_equal_the_runner_lists(self):
        self.assertEqual(audit.SCRIPT_NAMES, powershell_list("Get-ReleaseSprtScriptNames"))
        self.assertEqual(audit.CRITERIA, powershell_list("Get-ReleaseSprtAuditCriteria"))

    def test_the_audit_program_is_one_of_the_hashed_scripts(self):
        self.assertIn(audit.TOOL_NAME, audit.SCRIPT_NAMES)
        self.assertTrue((ROOT / "tools" / audit.TOOL_NAME).is_file())
        self.assertIn(audit.ANALYZER_NAME, audit.SCRIPT_NAMES)


class Fixture:
    """A synthetic smoke attempt that satisfies every criterion. Pairs are (result when NEW is White, result when OLD is White)."""
    PAIRS = [("1-0", "0-1"), ("1-0", "1/2-1/2"), ("0-1", "1-0"), ("1-0", "1-0"), ("1/2-1/2", "0-1"),
             ("1-0", "0-1"), ("0-1", "0-1"), ("1-0", "0-1"), ("1-0", "1/2-1/2"), ("1-0", "0-1")]
    RUN_ID = "smoke-20261019T065359346Z-7b3787d4"
    JAVA = "C:\\Tools\\Java\\zulu-21\\bin\\java.exe"
    CUTE = "C:\\Tools\\cutechess-1.5.1-win64\\cutechess-cli.exe"
    HEAD = "c" * 40
    ATTEMPT_DIR = f"C:\\runner\\tools\\results\\release-strength-sprt\\{RUN_ID}"

    def __init__(self, base):
        self.dir = Path(base) / self.RUN_ID
        self.dir.mkdir()
        self.epd = (RESEARCH / "openings-smoke-v1.epd").read_text(encoding="ascii").split("\n")
        self.epd = [l for l in self.epd if l]
        self.frozen = dict(audit.FROZEN)
        self.files = {}
        self.build()

    def put(self, name, text):
        data = text.encode("utf-8") if isinstance(text, str) else text
        (self.dir / name).write_bytes(data)

    def dump(self, name, obj):
        self.put(name, json.dumps(obj, indent=2))

    def reseal(self):
        lines = [f"{sha256(p.read_bytes())}  {p.name}" for p in sorted(self.dir.iterdir()) if p.is_file() and p.name != "SHA256SUMS"]
        self.put("SHA256SUMS", "\n".join(lines) + "\n")

    def read_json(self, name):
        return json.loads((self.dir / name).read_text(encoding="utf-8"))

    def windows(self, name):
        return f"{self.ATTEMPT_DIR}\\{name}"

    def build(self):
        d = self.dir
        self.put("candidate-v0.6.0.jar", "inert candidate")
        self.put("baseline-v0.5.7.jar", "inert baseline")
        self.put("preregistration.md", "inert preregistration")
        shutil.copy(RESEARCH / "openings-smoke-v1.epd", d / "openings-smoke-v1.epd")
        self.frozen["candidate_jar"] = ("candidate-v0.6.0.jar", sha256(b"inert candidate"))
        self.frozen["baseline_jar"] = ("baseline-v0.5.7.jar", sha256(b"inert baseline"))
        self.frozen["preregistration_sha256"] = sha256(b"inert preregistration")
        f = self.frozen
        cfg = {"CandidateSha256": f["candidate_jar"][1], "BaselineSha256": f["baseline_jar"][1], "OpeningSha256": f["production_openings_sha256"],
               "SmokeOpeningSha256": f["smoke_openings"][1], "PreregistrationSha256": f["preregistration_sha256"], "SmokeGames": 20,
               "Elo0": 0, "Elo1": 20, "Alpha": 0.05, "Beta": 0.05, "BonferroniM": 1, "MinGames": 0, "MaxGames": 20000, "TC": "5+0.05",
               "Concurrency": 6, "EngineThreads": 1, "OpeningOrder": "sequential", "NewOptions": f["new_options"], "OldOptions": f["old_options"]}
        scripts = {name: sha256(name.encode()) for name in audit.SCRIPT_NAMES}
        for real in (audit.TOOL_NAME, audit.ANALYZER_NAME):  # a real run records the real hashes of the audit program and the analyzer it imports
            scripts[real] = sha256((ROOT / "tools" / real).read_bytes())
        self.dump("identity.json", {"Kind": "smoke", "QualifyingPreflight": "preflight-x", "PriorAttempts": [], "InvokingHead": self.HEAD, "Config": cfg,
                                    "Assets": [{"Arm": "candidate", "Sha256": f["candidate_jar"][1]}, {"Arm": "baseline", "Sha256": f["baseline_jar"][1]}],
                                    "ScriptSha256": scripts})
        self.dump("attempt-start.json", {"RunId": self.RUN_ID, "Kind": "smoke", "StartedUtc": "2026-10-19T06:53:59.3470726Z", "Status": "STARTED", "InvokingHead": self.HEAD, "Branch": ""})
        self.dump("attempt-end.json", {"RunId": self.RUN_ID, "Kind": "smoke", "EndedUtc": "2026-10-19T06:55:12.4835009Z", "MatchStarted": True, "MatchCompleted": True, "ExitCode": 0, "Failure": ""})
        self.dump("environment.json", {"Platform": "Win32NT", "ComputerName": "RENEGADE", "CpuName": "AMD Ryzen 7 7700X 8-Core Processor             ",
                                       "PowerPlanRaw": "Power Scheme GUID: 381b4222-f694-41f0-9685-ff5bb260df2e  (Balanced)", "AffinityMask": 65535,
                                       "FullAffinityMask": 65535, "HardwareLogicalProcessors": 16, "JavaExecutable": self.JAVA, "JavaVersion": "21.0.10",
                                       "JavaRuntime": "21.0.10+7-LTS", "JavaVendor": "Azul Systems, Inc.", "CutechessExecutable": self.CUTE,
                                       "CutechessVersion": "1.5.1", "IdleCpuMean": 2.84, "IdleCpuMax": 5.15})
        args = audit.expected_arguments(self.JAVA, (self.windows("candidate-v0.6.0.jar"), self.windows("baseline-v0.5.7.jar")),
                                        (f["new_options"], f["old_options"]), 20, self.windows("match.pgn"), self.windows("openings-smoke-v1.epd"))
        self.dump("runner-parameters.json", {"New": self.windows("candidate-v0.6.0.jar"), "Old": self.windows("baseline-v0.5.7.jar"),
                                             "PgnPath": self.windows("match.pgn"), "OpeningsFile": self.windows("openings-smoke-v1.epd")})
        self.dump("arguments.json", {"Executable": self.CUTE, "Arguments": args})
        self.dump("prepared-arguments.json", {"Executable": self.CUTE, "Arguments": args})
        self.dump("assets.json", [{"Arm": "candidate"}, {"Arm": "baseline"}])
        self.dump("launch-confirmation.json", {"IdleHostConfirmed": True})
        self.dump("owned-runner.json", {"ProcessId": 1})
        self.dump("summary.json", {"Verdict": "SMOKE_PASSED", "EligibleForPromotion": False, "Kind": "smoke", "Cancelled": 0, "PartialPgnRecords": 0, "FaultLines": [], "AuditIssues": []})
        self.put("console.stdout.log", "Finished match\n")
        self.put("runner.log", f"Transcript\nEvidence and frozen inputs: {self.ATTEMPT_DIR}\n")
        self.put("console.stderr.log", "#< CLIXML\n<Objs><Obj S=\"information\"/></Objs>\n")
        self.write_match(self.PAIRS)
        self.reseal()

    def write_match(self, pairs, termination="adjudication", log_extra=""):
        games, blocks, started, finished = [], [], [], []
        for k, (first, second) in enumerate(pairs, start=1):
            fen = self.epd[k - 1]
            games += [(2 * k - 1, "NEW", "OLD", first, fen), (2 * k, "OLD", "NEW", second, fen)]
        wins = losses = draws = 0
        for n, white, black, result, fen in games:
            new_points = {"1-0": 1.0, "0-1": 0.0, "1/2-1/2": 0.5}[result] if white == "NEW" else {"1-0": 0.0, "0-1": 1.0, "1/2-1/2": 0.5}[result]
            wins, losses, draws = wins + (new_points == 1.0), losses + (new_points == 0.0), draws + (new_points == 0.5)
            is_draw = result == "1/2-1/2"
            text = "Draw by 3-fold repetition" if is_draw else ("White" if result == "1-0" else "Black") + " wins by adjudication"
            blocks.append(f'[Event "?"]\n[Site "?"]\n[Round "1"]\n[White "{white}"]\n[Black "{black}"]\n[Result "{result}"]\n[FEN "{fen}"]\n'
                          f'[SetUp "1"]\n[Termination "{"" if is_draw else termination}"]\n[TimeControl "5+0.05"]\n\n1. e4 e5 {result}\n')
            started.append(f"Started game {n} of 20 ({white} vs {black})")
            finished.append((n, f"Finished game {n} ({white} vs {black}): {result} {{{text}}}", wins, losses, draws))
        log = list(started)
        for n, line, w, l, d in finished:
            log += [line, f"Score of NEW vs OLD: {w} - {l} - {d}  [0.5] {w + l + d}"]
        llr = round(analyze.cutechess_llr(wins, losses, draws, 0.0, 20.0), 3)
        log += ["Elo difference: 0.0 +/- 100.0, LOS: 50.0 %, DrawRatio: 15.0 %", f"SPRT: llr {llr} (20.5%), lbound -2.94, ubound 2.94", "Finished match", log_extra]
        self.put("match.pgn", "\n".join(blocks))
        self.put("match.log", "\n".join(log) + "\n")

    def drop_game(self, index):
        games = [g for g in re.split(r"(?m)^(?=\[Event )", (self.dir / "match.pgn").read_text(encoding="utf-8")) if g.strip()]
        del games[index]
        (self.dir / "match.pgn").write_text("".join(games), encoding="utf-8")

    def audit(self, **kwargs):
        self.reseal()
        return audit.build_record(self.dir, frozen=self.frozen, repo_root=ROOT, **kwargs)


def failed(record):
    return sorted(c["Id"] for c in record["Criteria"] if not c["Passed"])


class AuditCriteria(unittest.TestCase):
    def setUp(self):
        self.base = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.base, True)
        self.fx = Fixture(self.base)

    def assertOnlyFails(self, criterion, record=None):
        record = record or self.fx.audit()
        self.assertEqual(failed(record), [criterion], [c["Detail"] for c in record["Criteria"] if not c["Passed"]])
        self.assertEqual(record["Verdict"], "AUDIT_REJECTED")

    def test_a_clean_smoke_attempt_is_approved_and_the_record_binds_the_evidence(self):
        record = self.fx.audit()
        self.assertEqual(failed(record), [])
        self.assertEqual(record["Verdict"], "AUDIT_APPROVED")
        self.assertEqual([c["Id"] for c in record["Criteria"]], audit.CRITERIA)
        self.assertEqual(record["Smoke"]["RunId"], Fixture.RUN_ID)
        self.assertEqual(record["Smoke"]["ManifestSha256"], sha256((self.fx.dir / "SHA256SUMS").read_bytes()))
        self.assertEqual(record["Smoke"]["InvokingHead"], Fixture.HEAD)
        self.assertEqual(record["Smoke"]["ScriptSha256"], self.fx.read_json("identity.json")["ScriptSha256"])
        self.assertEqual(record["AuditTool"]["Sha256"], sha256((ROOT / "tools" / audit.TOOL_NAME).read_bytes()))
        self.assertEqual(record["Counts"]["games"], 20)

    def test_players_in_the_pgn_must_match_the_log(self):
        pgn = (self.fx.dir / "match.pgn").read_text(encoding="utf-8")
        (self.fx.dir / "match.pgn").write_text(pgn.replace('[Black "OLD"]', '[Black "OTHER"]').replace('[Black "NEW"]', '[Black "OTHER"]'), encoding="utf-8")
        record = self.fx.audit()
        self.assertIn("results-reconcile", failed(record))
        self.assertIn("PGN players or result differ from the log", " ".join(c["Detail"] for c in record["Criteria"]))

    def test_the_fixture_arguments_equal_the_golden_command_line(self):
        arguments = self.fx.read_json("arguments.json")["Arguments"]
        fill = {"JAVA": Fixture.JAVA, "NEW": self.fx.windows("candidate-v0.6.0.jar"), "OLD": self.fx.windows("baseline-v0.5.7.jar"),
                "PGN": self.fx.windows("match.pgn"), "OPEN": self.fx.windows("openings-smoke-v1.epd")}
        self.assertEqual(arguments, [re.sub(r"\{(\w+)\}", lambda m: fill[m.group(1)], part) for part in GOLDEN_ARGUMENTS.split(" ")])

    def test_the_record_has_the_fields_the_powershell_gate_reads(self):
        record = self.fx.audit()
        for key in ("Schema", "Verdict", "AuditedUtc", "AuditTool", "Smoke", "Criteria"):
            self.assertIn(key, record)
        for key in ("Name", "Sha256"):
            self.assertIn(key, record["AuditTool"])
        for key in ("RunId", "ManifestSha256", "InvokingHead", "ScriptSha256", "StartedUtc", "EndedUtc"):
            self.assertIn(key, record["Smoke"])
        self.assertTrue(all(isinstance(c["Passed"], bool) for c in record["Criteria"]))
        self.assertRegex(record["AuditedUtc"], r"^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{6}Z$")

    def test_a_runner_that_says_passed_cannot_rescue_bad_evidence(self):
        # Dropping a game while summary.json still says SMOKE_PASSED: the runner's label is corroboration, never authority.
        self.fx.drop_game(-1)
        record = self.fx.audit()
        self.assertEqual(self.fx.read_json("summary.json")["Verdict"], "SMOKE_PASSED")
        self.assertIn("games-complete", failed(record))
        self.assertEqual(record["Verdict"], "AUDIT_REJECTED")

    def test_a_runner_that_disagrees_blocks_approval(self):
        summary = self.fx.read_json("summary.json")
        summary["Verdict"] = "SMOKE_FAILED"
        self.fx.dump("summary.json", summary)
        self.assertOnlyFails("runner-corroboration")

    def test_a_missing_game_is_rejected(self):
        self.fx.drop_game(0)
        self.assertIn("games-complete", failed(self.fx.audit()))

    def test_a_cancelled_game_is_rejected(self):
        pgn = (self.fx.dir / "match.pgn").read_text(encoding="utf-8")
        (self.fx.dir / "match.pgn").write_text(pgn.replace('[Result "1-0"]', '[Result "*"]', 1), encoding="utf-8")
        self.assertIn("games-complete", failed(self.fx.audit()))

    def test_a_duplicate_start_is_rejected(self):
        log = (self.fx.dir / "match.log").read_text(encoding="utf-8")
        (self.fx.dir / "match.log").write_text(log.replace("Started game 2 of 20 (OLD vs NEW)", "Started game 1 of 20 (NEW vs OLD)"), encoding="utf-8")
        self.assertIn("games-complete", failed(self.fx.audit()))

    def test_an_opening_played_twice_with_the_same_colour_is_rejected(self):
        pgn = (self.fx.dir / "match.pgn").read_text(encoding="utf-8")
        pgn = pgn.replace('[White "OLD"]\n[Black "NEW"]', '[White "NEW"]\n[Black "OLD"]', 1)
        (self.fx.dir / "match.pgn").write_text(pgn, encoding="utf-8")
        self.assertIn("opening-pairs", failed(self.fx.audit()))

    def test_a_game_from_the_wrong_opening_is_rejected(self):
        pgn = (self.fx.dir / "match.pgn").read_text(encoding="utf-8")
        (self.fx.dir / "match.pgn").write_text(pgn.replace(self.fx.epd[0], self.fx.epd[1], 2), encoding="utf-8")
        self.assertIn("opening-pairs", failed(self.fx.audit()))

    def test_a_pgn_result_that_disagrees_with_the_log_is_rejected(self):
        pgn = (self.fx.dir / "match.pgn").read_text(encoding="utf-8")
        (self.fx.dir / "match.pgn").write_text(pgn.replace('[Result "1-0"]', '[Result "0-1"]', 1), encoding="utf-8")
        self.assertIn("results-reconcile", failed(self.fx.audit()))

    def test_results_swapped_between_two_games_are_rejected_although_the_totals_still_agree(self):
        # Games 1 and 5 have opposite outcomes for NEW. Swapping their PGN results keeps W/L/D, the score line and the LLR intact,
        # so only the per-game comparison of log and PGN can notice.
        games = [g for g in re.split(r"(?m)^(?=\[Event )", (self.fx.dir / "match.pgn").read_text(encoding="utf-8")) if g.strip()]
        first, fifth = re.search(r'\[Result "([^"]+)"\]', games[0]).group(1), re.search(r'\[Result "([^"]+)"\]', games[4]).group(1)
        self.assertNotEqual(first, fifth)
        games[0] = games[0].replace(f'[Result "{first}"]', f'[Result "{fifth}"]')
        games[4] = games[4].replace(f'[Result "{fifth}"]', f'[Result "{first}"]')
        (self.fx.dir / "match.pgn").write_text("".join(games), encoding="utf-8")
        record = self.fx.audit()
        self.assertEqual(failed(record), ["results-reconcile"])
        self.assertIn("PGN players or result differ from the log", " ".join(c["Detail"] for c in record["Criteria"]))

    def test_a_logged_score_that_disagrees_with_the_games_is_rejected(self):
        log = (self.fx.dir / "match.log").read_text(encoding="utf-8")
        last = re.findall(r"Score of NEW vs OLD: \d+ - \d+ - \d+", log)[-1]
        (self.fx.dir / "match.log").write_text(log.replace(last, "Score of NEW vs OLD: 1 - 1 - 1"), encoding="utf-8")
        self.assertIn("results-reconcile", failed(self.fx.audit()))

    def test_a_logged_llr_that_disagrees_with_the_games_is_rejected(self):
        log = (self.fx.dir / "match.log").read_text(encoding="utf-8")
        (self.fx.dir / "match.log").write_text(re.sub(r"SPRT: llr -?[\d.]+", "SPRT: llr 9.999", log), encoding="utf-8")
        self.assertIn("results-reconcile", failed(self.fx.audit()))

    def test_infrastructure_faults_are_rejected(self):
        for line in ("NEW loses on time", "Warning: OLD disconnected", "engine crashed", "illegal move", "Unknown option Hash"):
            with self.subTest(line=line):
                fx = Fixture(tempfile.mkdtemp())
                self.addCleanup(shutil.rmtree, fx.dir.parent, True)
                fx.write_match(Fixture.PAIRS, log_extra=line)
                self.assertIn("no-faults", failed(fx.audit()))

    def test_a_forfeit_termination_is_rejected(self):
        self.fx.write_match(Fixture.PAIRS, termination="time forfeit")
        self.assertIn("no-faults", failed(self.fx.audit()))

    def test_a_console_error_record_is_rejected(self):
        self.fx.put("console.stderr.log", '#< CLIXML\n<Objs><S S="Error">boom</S></Objs>\n')
        self.assertIn("no-faults", failed(self.fx.audit()))

    def test_a_changed_cutechess_argument_is_rejected(self):
        manifest = self.fx.read_json("arguments.json")
        manifest["Arguments"][manifest["Arguments"].index("-concurrency") + 1] = "8"
        self.fx.dump("arguments.json", manifest)
        self.fx.dump("prepared-arguments.json", manifest)
        self.assertOnlyFails("cutechess-arguments")

    def test_arguments_that_differ_from_the_prepared_manifest_are_rejected(self):
        manifest = self.fx.read_json("prepared-arguments.json")
        manifest["Arguments"].append("-extra")
        self.fx.dump("prepared-arguments.json", manifest)
        self.assertOnlyFails("cutechess-arguments")

    def test_arguments_that_point_outside_this_attempt_are_rejected(self):
        manifest = self.fx.read_json("arguments.json")
        manifest["Arguments"] = [a.replace(Fixture.RUN_ID, "smoke-20200101T000000000Z-deadbeef") for a in manifest["Arguments"]]
        self.fx.dump("arguments.json", manifest)
        self.fx.dump("prepared-arguments.json", manifest)
        self.assertOnlyFails("cutechess-arguments")

    def test_arguments_that_point_at_another_directory_with_the_same_name_are_rejected(self):
        manifest = self.fx.read_json("arguments.json")
        elsewhere = "C:\\other\\" + Fixture.RUN_ID
        manifest["Arguments"] = [a.replace(Fixture.ATTEMPT_DIR, elsewhere) for a in manifest["Arguments"]]
        self.fx.dump("arguments.json", manifest)
        self.fx.dump("prepared-arguments.json", manifest)
        record = self.fx.audit()
        self.assertIn("cutechess-arguments", failed(record))
        self.assertIn("not under tools", " ".join(c["Detail"] for c in record["Criteria"]))

    def test_one_argument_path_in_a_different_directory_is_rejected(self):
        manifest = self.fx.read_json("arguments.json")
        manifest["Arguments"] = [a.replace(Fixture.ATTEMPT_DIR + "\\candidate-v0.6.0.jar", "C:\\elsewhere\\" + Fixture.RUN_ID + "\\candidate-v0.6.0.jar") for a in manifest["Arguments"]]
        self.fx.dump("arguments.json", manifest)
        self.fx.dump("prepared-arguments.json", manifest)
        self.assertIn("cutechess-arguments", failed(self.fx.audit()))

    def test_arguments_the_runner_transcript_and_parameters_do_not_agree_with_are_rejected(self):
        self.fx.put("runner.log", "Transcript with no evidence line\n")
        self.assertIn("cutechess-arguments", failed(self.fx.audit()))
        self.fx.put("runner.log", f"Evidence and frozen inputs: {Fixture.ATTEMPT_DIR}\n")
        params = self.fx.read_json("runner-parameters.json")
        params["PgnPath"] = "C:\\other\\match.pgn"
        self.fx.dump("runner-parameters.json", params)
        self.assertIn("cutechess-arguments", failed(self.fx.audit()))

    def test_a_different_jar_is_rejected(self):
        self.fx.put("candidate-v0.6.0.jar", "some other build")
        self.assertIn("frozen-identities", failed(self.fx.audit()))

    def test_a_changed_frozen_configuration_is_rejected(self):
        identity = self.fx.read_json("identity.json")
        identity["Config"]["Elo1"] = 25
        self.fx.dump("identity.json", identity)
        self.assertOnlyFails("frozen-identities")

    def test_a_missing_script_hash_is_rejected(self):
        identity = self.fx.read_json("identity.json")
        del identity["ScriptSha256"][audit.TOOL_NAME]
        self.fx.dump("identity.json", identity)
        self.assertOnlyFails("script-identity")

    def test_an_audit_program_or_analyzer_other_than_the_recorded_one_is_rejected(self):
        for name in (audit.TOOL_NAME, audit.ANALYZER_NAME):
            with self.subTest(name=name):
                fx = Fixture(tempfile.mkdtemp())
                self.addCleanup(shutil.rmtree, fx.dir.parent, True)
                identity = fx.read_json("identity.json")
                identity["ScriptSha256"][name] = "0" * 64
                fx.dump("identity.json", identity)
                record = fx.audit()
                self.assertIn("script-identity", failed(record))
                self.assertIn("is not the one the smoke attempt recorded", " ".join(c["Detail"] for c in record["Criteria"]))

    def test_booleans_do_not_satisfy_numeric_frozen_values(self):
        self.assertFalse(audit.same(True, 1))
        self.assertFalse(audit.same(0, False))
        self.assertTrue(audit.same(0, 0.0))
        self.assertFalse(audit.same([1, True], [1, 1]))
        for key, value in (("BonferroniM", True), ("Elo0", False), ("MinGames", False)):
            with self.subTest(key=key):
                fx = Fixture(tempfile.mkdtemp())
                self.addCleanup(shutil.rmtree, fx.dir.parent, True)
                identity = fx.read_json("identity.json")
                identity["Config"][key] = value
                fx.dump("identity.json", identity)
                self.assertIn("frozen-identities", failed(fx.audit()))

    def test_an_exit_code_of_false_is_not_a_clean_exit(self):
        end = self.fx.read_json("attempt-end.json")
        end["ExitCode"] = False
        self.fx.dump("attempt-end.json", end)
        self.assertIn("attempt-completed", failed(self.fx.audit()))

    def test_an_end_before_the_start_within_one_microsecond_is_rejected(self):
        self.assertGreater(audit.parse_utc_ns("2026-10-19T06:53:59.0000002Z"), audit.parse_utc_ns("2026-10-19T06:53:59.0000001Z"))
        self.assertEqual(audit.parse_utc_ns("2026-10-19T06:53:59Z") % 10**9, 0)
        start = self.fx.read_json("attempt-start.json"); end = self.fx.read_json("attempt-end.json")
        start["StartedUtc"] = "2026-10-19T06:55:12.4835002Z"; end["EndedUtc"] = "2026-10-19T06:55:12.4835001Z"
        self.fx.dump("attempt-start.json", start); self.fx.dump("attempt-end.json", end)
        record = self.fx.audit()
        self.assertIn("attempt-completed", failed(record))
        self.assertIn("ends before it starts", " ".join(c["Detail"] for c in record["Criteria"]))
        for text in ("2026-10-19T06:55:12", "2026-10-19 06:55:12Z", "2026-10-19T06:55:12+00:00", ""):
            with self.assertRaises(ValueError):
                audit.parse_utc_ns(text)

    def test_the_recorded_attempt_directory_must_be_the_audited_directory_on_windows(self):
        self.assertIsNone(audit.location_matches(self.fx.dir, Fixture.ATTEMPT_DIR, native_windows=False))
        here = os.path.normcase(os.path.realpath(self.fx.dir))
        self.assertTrue(audit.location_matches(self.fx.dir, here, native_windows=True))
        self.assertFalse(audit.location_matches(self.fx.dir, here + "-other", native_windows=True))
        off_windows = self.fx.audit()
        self.assertFalse(off_windows["Smoke"]["LocationVerified"], "an audit that could not check the location says so")
        record = audit.build_record(self.fx.dir, frozen=self.fx.frozen, repo_root=ROOT, native_windows=True)
        self.assertIn("cutechess-arguments", failed(record))
        self.assertIn("not the directory being audited", " ".join(c["Detail"] for c in record["Criteria"]))
        self.assertFalse(record["Smoke"]["LocationVerified"])

    def test_a_short_commit_id_is_rejected(self):
        identity = self.fx.read_json("identity.json")
        identity["InvokingHead"] = "c9816ae"
        self.fx.dump("identity.json", identity)
        self.assertIn("script-identity", failed(self.fx.audit()))

    def test_the_wrong_host_or_a_busy_host_is_rejected(self):
        for key, value in (("ComputerName", "OTHER"), ("IdleCpuMean", 7.5), ("IdleCpuMean", "2.1"), ("IdleCpuMean", -1), ("CutechessVersion", "1.4.0"), ("HardwareLogicalProcessors", 8)):
            with self.subTest(key=key):
                fx = Fixture(tempfile.mkdtemp())
                self.addCleanup(shutil.rmtree, fx.dir.parent, True)
                env = fx.read_json("environment.json")
                env[key] = value
                fx.dump("environment.json", env)
                self.assertIn("host-and-tools", failed(fx.audit()))

    def test_nan_in_the_evidence_is_rejected_not_treated_as_idle(self):
        text = (self.fx.dir / "environment.json").read_text(encoding="utf-8").replace('"IdleCpuMean": 2.84', '"IdleCpuMean": NaN')
        self.assertIn("NaN", text)
        (self.fx.dir / "environment.json").write_text(text, encoding="utf-8")
        record = self.fx.audit()
        self.assertIn("host-and-tools", failed(record))
        self.assertIn("NaN", " ".join(c["Detail"] for c in record["Criteria"]))

    def test_a_log_that_declares_another_game_total_is_rejected(self):
        log = (self.fx.dir / "match.log").read_text(encoding="utf-8")
        (self.fx.dir / "match.log").write_text(log.replace("of 20 (", "of 20000 ("), encoding="utf-8")
        record = self.fx.audit()
        self.assertIn("games-complete", failed(record))
        self.assertIn("game total", " ".join(c["Detail"] for c in record["Criteria"]))

    def test_an_attempt_that_did_not_complete_cleanly_is_rejected(self):
        end = self.fx.read_json("attempt-end.json")
        end["ExitCode"] = 1
        self.fx.dump("attempt-end.json", end)
        self.assertOnlyFails("attempt-completed")

    def test_a_production_opening_in_a_smoke_game_is_rejected(self):
        production = (RESEARCH / "openings-v1.epd").read_text(encoding="ascii").split("\n")[0]
        pgn = (self.fx.dir / "match.pgn").read_text(encoding="utf-8")
        (self.fx.dir / "match.pgn").write_text(pgn.replace(self.fx.epd[0], production), encoding="utf-8")
        self.assertIn("smoke-isolation", failed(self.fx.audit()))

    def test_an_archived_production_opening_list_is_rejected(self):
        self.fx.put("openings-v1.epd", "x")
        self.assertIn("smoke-isolation", failed(self.fx.audit()))

    def test_evidence_changed_after_sealing_is_rejected(self):
        self.fx.reseal()
        (self.fx.dir / "summary.json").write_text("{}", encoding="utf-8")
        record = audit.build_record(self.fx.dir, frozen=self.fx.frozen, repo_root=ROOT)
        self.assertIn("evidence-manifest", failed(record))

    def test_a_symbolic_link_is_not_a_plain_file(self):
        self.fx.reseal()
        outside = Path(self.base) / "outside.txt"
        outside.write_text("elsewhere", encoding="utf-8")
        link = self.fx.dir / "console.stdout.log"
        link.unlink()
        try:
            link.symlink_to(outside)
        except (OSError, NotImplementedError):
            self.skipTest("symbolic links are not available here")
        self.assertIn("symbolic link", " ".join(audit.manifest_problems(self.fx.dir)))
        extra = self.fx.dir / "extra-link.txt"
        extra.symlink_to(outside)
        self.assertIn("symbolic link in the evidence directory: extra-link.txt", audit.manifest_problems(self.fx.dir))

    def test_an_unlisted_file_or_a_subdirectory_is_rejected(self):
        self.fx.reseal()
        (self.fx.dir / "extra.txt").write_text("late", encoding="utf-8")
        (self.fx.dir / "sub").mkdir()
        details = " ".join(audit.manifest_problems(self.fx.dir))
        self.assertIn("file not in manifest: extra.txt", details)
        self.assertIn("unverifiable subdirectory: sub", details)

    def test_manifest_edge_cases(self):
        self.fx.reseal()
        sums = self.fx.dir / "SHA256SUMS"
        good = sums.read_text(encoding="ascii")
        for label, text, needle in (("empty", "", "empty"), ("traversal", good + f"{'a' * 64}  ..\\x\n", "malformed"),
                                    ("short digest", good + "abc  match.log\n", "malformed"), ("duplicate", good + good.split("\n")[0] + "\n", "duplicate")):
            with self.subTest(label=label):
                sums.write_text(text, encoding="ascii")
                self.assertIn(needle, " ".join(audit.manifest_problems(self.fx.dir)))
        sums.write_text(good.replace("\n", "\r\n"), encoding="ascii")
        self.assertEqual(audit.manifest_problems(self.fx.dir), [], "a manifest written with Windows line endings verifies")
        sums.unlink()
        self.assertEqual(audit.manifest_problems(self.fx.dir), ["SHA256SUMS is missing"])

    def test_a_missing_evidence_file_fails_a_criterion_and_still_yields_a_record(self):
        (self.fx.dir / "identity.json").unlink()
        record = self.fx.audit()
        self.assertEqual(record["Verdict"], "AUDIT_REJECTED")
        self.assertIn("script-identity", failed(record))
        self.assertIn("could not evaluate", " ".join(c["Detail"] for c in record["Criteria"]))


class RecordOutput(unittest.TestCase):
    def setUp(self):
        self.base = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.base, True)
        self.fx = Fixture(self.base)

    def test_the_record_is_written_beside_the_evidence_with_its_own_manifest(self):
        record = self.fx.audit()
        before = {p.name: sha256(p.read_bytes()) for p in self.fx.dir.iterdir()}
        out = audit.write_record(record, self.base)
        self.assertRegex(out.name, r"^audit-\d{8}T\d{9}Z-[0-9a-f]{8}$")
        self.assertEqual(sorted(p.name for p in out.iterdir()), ["SHA256SUMS", "smoke-audit.json"])
        self.assertEqual(audit.manifest_problems(out), [])
        self.assertEqual(json.loads((out / "smoke-audit.json").read_text(encoding="utf-8")), record)
        self.assertEqual({p.name: sha256(p.read_bytes()) for p in self.fx.dir.iterdir()}, before, "the smoke evidence is untouched")

    def test_two_records_never_share_a_directory(self):
        record = self.fx.audit()
        self.assertNotEqual(audit.write_record(record, self.base), audit.write_record(record, self.base))

    def test_main_refuses_to_write_inside_the_smoke_directory(self):
        with self.assertRaises(SystemExit) as stopped:
            audit.main([str(self.fx.dir), "--out-root", str(self.fx.dir)])
        self.assertIn("Refusing", str(stopped.exception))
        with self.assertRaises(SystemExit):
            audit.main([str(self.fx.dir), "--out-root", str(self.fx.dir / "nested")])

    def test_main_refuses_a_directory_that_is_not_a_smoke_attempt(self):
        with self.assertRaises(SystemExit):
            audit.main([self.base])

    def test_the_audit_does_not_use_the_runner_summary_as_its_verdict(self):
        source = (ROOT / "tools" / audit.TOOL_NAME).read_text(encoding="utf-8")
        self.assertEqual(len(re.findall(r'"SMOKE_PASSED"', source)), 1, "SMOKE_PASSED appears only in the corroboration check")


if __name__ == "__main__":
    unittest.main()
