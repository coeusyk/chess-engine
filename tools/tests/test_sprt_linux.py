"""Contract tests for tools/sprt_linux.py against the existing SPRT execution contract in tools/sprt.ps1.

Nothing here launches an engine match. The only subprocess that reaches cutechess is a fake shell
script that prints a few lines and exits, used to check log tee-ing and exit-code handling.

Three layers check that the Linux launcher builds what sprt.ps1 builds:

1. a golden argument list written by hand from reading sprt.ps1;
2. a static parse of sprt.ps1's own argument-building statements, compared token for token with
   the Python builder, so an edit to either side that is not mirrored fails here without pwsh;
3. a differential run of the real `sprt.ps1 -PrepareOnly`, which needs pwsh. It is skipped when
   pwsh is absent, except when REQUIRE_PWSH=1 (set in CI), where a missing pwsh is a failure
   instead of a silent skip.
"""
import hashlib
import importlib.util
import json
import os
import re
import shutil
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
PS1 = ROOT / "tools/sprt.ps1"
SPEC = importlib.util.spec_from_file_location("sprt_linux", ROOT / "tools/sprt_linux.py")
assert SPEC is not None and SPEC.loader is not None
sprt_linux = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(sprt_linux)

PWSH = shutil.which("pwsh")


def fake_executable(path, body="exit 0"):
    path.write_text("#!/bin/sh\n" + body + "\n")
    path.chmod(path.stat().st_mode | stat.S_IEXEC)
    return path


class Workspace:
    """A temp directory with dummy jars, a book and a stub cutechess, all absolute paths."""

    def __init__(self, test):
        self.dir = Path(tempfile.mkdtemp(prefix="sprt-linux-test-")).resolve()
        test.addCleanup(shutil.rmtree, self.dir, True)
        self.new = self.dir / "cand.jar"
        self.old = self.dir / "base.jar"
        self.new.write_bytes(b"candidate-jar")
        self.old.write_bytes(b"baseline-jar")
        self.book = self.dir / "book.epd"
        self.book.write_text("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq -\n")
        self.cutechess = fake_executable(self.dir / "cutechess-cli")
        # a directory with spaces, so every case also covers whitespace in the output paths
        self.log = self.dir / "out dir" / "match.log"
        self.pgn = self.dir / "out dir" / "match.pgn"
        self.env = {"CUTECHESS": str(self.cutechess)}

    def argv(self, *extra, book=True, paths=True):
        argv = ["--new", str(self.new), "--old", str(self.old)]
        if book:
            argv += ["--openings-file", str(self.book)]
        if paths:
            argv += ["--log-path", str(self.log), "--pgn-path", str(self.pgn)]
        return argv + list(extra)

    def prepare(self, *extra, **kw):
        manifest = self.dir / "prepared.json"
        code = sprt_linux.run(self.argv("--prepare-only", "--argument-manifest-path", str(manifest), *extra, **kw),
                              env=self.env)
        assert code == 0
        return json.loads(manifest.read_text())


class NumberFormatting(unittest.TestCase):
    def test_numbers_print_like_powershell_7(self):
        for value, text in ((0, "0"), (10.0, "10"), (0.05, "0.05"), (-2.5, "-2.5"), (7.5, "7.5"),
                            (0.05 / 3, "0.016666666666666666"), (1e-05, "1E-05"), (1.5e-07, "1.5E-07"),
                            (0.0001, "0.0001"), (50, "50"), (999999999999999.0, "999999999999999")):
            self.assertEqual(sprt_linux.ps_number(value), text, value)

    def test_numbers_outside_the_supported_range_are_refused_not_formatted_differently(self):
        # .NET prints 1e15 as 1E+15 but Python repr prints 1000000000000000.0, so the launcher refuses.
        for value in (1e15, 1e16, -1e15):
            with self.assertRaisesRegex(sprt_linux.LauncherError, "supported range"):
                sprt_linux.ps_number(value)

    def test_non_finite_numbers_are_refused(self):
        for value in (float("nan"), float("inf")):
            with self.assertRaises(sprt_linux.LauncherError):
                sprt_linux.ps_number(value)

    def test_max_games_follows_the_ps1_rule(self):
        for max_games, min_games, expected in ((0, 0, 20000), (500, 0, 500), (0, 500, 20000),
                                               (0, 30000, 30000), (100, 30000, 100)):
            self.assertEqual(sprt_linux.max_games_for(max_games, min_games), expected)


class ArgumentArray(unittest.TestCase):
    def test_golden_argument_list_for_the_frozen_266_shape(self):
        # Written by hand from sprt.ps1 lines 198-222 for the #266 run: elo0 0, elo1 10, alpha =
        # beta = 0.05, TC 5+0.05, concurrency 6, one thread, 20000 games, random order.
        opts = ["Hash=16", "EvalType=Classical"]
        actual = sprt_linux.build_arguments(
            "java", "/w/cand.jar", "/w/base.jar", "5+0.05", 20000, 0.0, 10.0, 0.05, 0.05, 6, 1,
            opts, opts, "/w/match.pgn", "/w/book.epd", "random")
        engine = lambda name, jar: ["-engine", f"name={name}", "cmd=java", "arg=--add-modules",
                                    "arg=jdk.incubator.vector", "arg=-jar", f"arg={jar}", "proto=uci",
                                    "option.Threads=1", "option.Hash=16", "option.EvalType=Classical"]
        self.assertEqual(actual, engine("NEW", "/w/cand.jar") + engine("OLD", "/w/base.jar") + [
            "-each", "tc=5+0.05", "-games", "20000", "-repeat", "-recover",
            "-resign", "movecount=5", "score=400", "-draw", "movenumber=40", "movecount=8", "score=10",
            "-sprt", "elo0=0", "elo1=10", "alpha=0.05", "beta=0.05",
            "-concurrency", "6", "-ratinginterval", "10", "-pgnout", "/w/match.pgn",
            "-openings", "file=/w/book.epd", "format=epd", "order=random", "plies=4"])

    def test_no_openings_group_without_a_book(self):
        args = sprt_linux.build_arguments("java", "a", "b", "1+0", 10, 0, 10, 0.05, 0.05, 1, 1, [], [], "p", None, "random")
        self.assertNotIn("-openings", args)
        self.assertEqual(args[-2:], ["-pgnout", "p"])

    def test_builder_equals_the_argument_statements_in_sprt_ps1(self):
        """Parses the `$ccArgs` statements out of sprt.ps1 and compares them token for token."""
        text = PS1.read_text(encoding="utf-8")
        values = {"$Java": "JAVA", "$EngineThreads": "THREADS", "$($NewResolved.Path)": "NEWJAR",
                  "$($OldResolved.Path)": "OLDJAR", "$TC": "TCX", "$maxGames": "777", "$Elo0": "1.5",
                  "$Elo1": "2.5", "$alpha": "0.025", "$beta": "0.0125", "$Concurrency": "9",
                  "$PgnOut": "PGN", "$OpeningsFile": "BOOK", "$OpeningOrder": "ORDER"}

        def tokens(block):
            found = []
            for match in re.finditer(r'"((?:[^"`]|`.)*)"|(\$newOptionArgs|\$oldOptionArgs)|(\$PgnOut)', block):
                if match.group(2):
                    found.append("<" + match.group(2)[1:] + ">")
                    continue
                token = match.group(1) if match.group(1) is not None else match.group(3)
                for name in sorted(values, key=len, reverse=True):
                    token = token.replace(name, values[name])
                found.append(token)
            return found

        start = text.index("$ccArgs = @(")
        end = text.index("if ($openingsArgs.Count -gt 0)")
        body = tokens(text[start:end])
        openings_match = re.search(r'\$openingsArgs = @\((\s*"-openings".*?)\)', text, re.S)
        self.assertIsNotNone(openings_match, "sprt.ps1 no longer builds an -openings group the way this test parses it")
        openings = openings_match.group(1)
        expected = body + tokens(openings)

        new_opts, old_opts = ["NA=1"], ["OB=2"]
        built = sprt_linux.build_arguments("JAVA", "NEWJAR", "OLDJAR", "TCX", 777, 1.5, 2.5, 0.025, 0.0125, 9, "THREADS",
                                           new_opts, old_opts, "PGN", "BOOK", "ORDER")
        # sprt.ps1 splices the per-engine option arrays in as variables; mirror that in `built`
        built = [t for t in built]
        spliced = []
        for token in expected:
            if token == "<newOptionArgs>":
                spliced += ["option.NA=1"]
            elif token == "<oldOptionArgs>":
                spliced += ["option.OB=2"]
            else:
                spliced.append(token)
        self.assertEqual(built, spliced)


class LauncherBehaviour(unittest.TestCase):
    def test_manifest_has_the_ps1_shape(self):
        ws = Workspace(self)
        manifest = ws.prepare()
        self.assertEqual(set(manifest), {"Executable", "Arguments"})
        self.assertEqual(manifest["Executable"], str(ws.cutechess))
        self.assertIn("-sprt", manifest["Arguments"])
        self.assertFalse(ws.log.exists(), "prepare-only must not start a run")

    def test_prepare_only_requires_a_manifest_path(self):
        ws = Workspace(self)
        with self.assertRaisesRegex(sprt_linux.LauncherError, "requires"):
            sprt_linux.run(ws.argv("--prepare-only"), env=ws.env)

    def test_expected_manifest_is_accepted_when_equal_and_refused_when_not(self):
        ws = Workspace(self)
        ws.prepare()
        prepared = ws.dir / "prepared.json"
        base = ws.argv("--prepare-only", "--argument-manifest-path", str(ws.dir / "again.json"))
        self.assertEqual(sprt_linux.run(base + ["--expected-argument-manifest-path", str(prepared)], env=ws.env), 0)
        changed = ws.argv("--tc", "9+0.09", "--prepare-only", "--argument-manifest-path", str(ws.dir / "again.json"))
        with self.assertRaisesRegex(sprt_linux.LauncherError, "differ from the prepared manifest"):
            sprt_linux.run(changed + ["--expected-argument-manifest-path", str(prepared)], env=ws.env)

    def test_missing_explicit_book_is_refused(self):
        ws = Workspace(self)
        ws.book.unlink()
        with self.assertRaisesRegex(sprt_linux.LauncherError, "Opening book not found"):
            ws.prepare()

    def test_no_book_at_all_fails_closed_unless_startpos_is_allowed(self):
        ws = Workspace(self)
        with mock.patch.object(sprt_linux, "DEFAULT_BOOK", ws.dir / "absent.epd"):
            with self.assertRaisesRegex(sprt_linux.LauncherError, "No opening book"):
                ws.prepare(book=False)
            manifest = ws.prepare("--allow-startpos", book=False)
        self.assertNotIn("-openings", manifest["Arguments"])

    def test_an_empty_book_value_is_replaced_by_the_default_book_like_sprt_ps1(self):
        ws = Workspace(self)
        with mock.patch.object(sprt_linux, "DEFAULT_BOOK", ws.book):
            manifest = ws.prepare("--openings-file", "", book=False)
        self.assertIn(f"file={ws.book}", manifest["Arguments"])
        with mock.patch.object(sprt_linux, "DEFAULT_BOOK", ws.dir / "absent.epd"):
            with self.assertRaisesRegex(sprt_linux.LauncherError, "Opening book not found"):
                ws.prepare("--openings-file", "", book=False)

    def test_wildcard_jar_paths_are_not_expanded(self):
        ws = Workspace(self)
        with self.assertRaisesRegex(sprt_linux.LauncherError, "New engine JAR not found"):
            sprt_linux.run(["--new", str(ws.dir / "*.jar"), "--old", str(ws.old), "--openings-file", str(ws.book),
                            "--log-path", str(ws.log), "--pgn-path", str(ws.pgn)], env=ws.env)

    def test_default_book_is_picked_up_when_present(self):
        ws = Workspace(self)
        with mock.patch.object(sprt_linux, "DEFAULT_BOOK", ws.book):
            manifest = ws.prepare(book=False)
        self.assertIn(f"file={ws.book}", manifest["Arguments"])

    def test_log_and_pgn_paths_must_come_together_and_differ(self):
        ws = Workspace(self)
        with self.assertRaisesRegex(sprt_linux.LauncherError, "together"):
            sprt_linux.run(ws.argv("--log-path", str(ws.log), paths=False), env=ws.env)
        with self.assertRaisesRegex(sprt_linux.LauncherError, "different files"):
            sprt_linux.run(ws.argv("--log-path", str(ws.log), "--pgn-path", str(ws.log), paths=False), env=ws.env)

    def test_missing_cutechess_and_missing_jars_are_refused(self):
        ws = Workspace(self)
        with self.assertRaisesRegex(sprt_linux.LauncherError, "cutechess-cli not found"):
            sprt_linux.run(ws.argv(), env={"CUTECHESS": str(ws.dir / "nope")})
        ws.old.unlink()
        with self.assertRaisesRegex(sprt_linux.LauncherError, "Old engine JAR not found"):
            sprt_linux.run(ws.argv(), env=ws.env)

    def test_java_location_follows_java_then_java_home(self):
        self.assertEqual(sprt_linux.java_command({}), "java")
        self.assertEqual(sprt_linux.java_command({"JAVA_HOME": "/jdk"}), "/jdk/bin/java")
        self.assertEqual(sprt_linux.java_command({"JAVA": "/a", "JAVA_HOME": "/b"}), "/a/bin/java")

    def test_run_tees_output_to_the_log_and_propagates_the_exit_code(self):
        ws = Workspace(self)
        fake_executable(ws.cutechess, 'echo "Started game 1"; echo "Finished game 1 (NEW vs OLD): 1-0"; exit 0')
        with mock.patch.dict(os.environ, ws.env):
            self.assertEqual(sprt_linux.main(ws.argv()), 0)
        self.assertEqual(ws.log.read_text().splitlines(), ["Started game 1", "Finished game 1 (NEW vs OLD): 1-0"])

        fake_executable(ws.cutechess, 'echo "engine stalled"; exit 3')
        with mock.patch.dict(os.environ, ws.env):
            self.assertEqual(sprt_linux.main(ws.argv()), 1)
        self.assertEqual(ws.log.read_text().splitlines(), ["engine stalled"], "evidence is retained on failure")

    def test_importing_the_module_does_not_run_anything(self):
        self.assertTrue(callable(sprt_linux.main))


class Provenance(unittest.TestCase):
    def run_with_provenance(self, ws, extra=()):
        path = ws.dir / "provenance.json"
        with mock.patch.object(sprt_linux, "first_line_of", side_effect=lambda cmd: "line-of-" + Path(cmd[0]).name):
            sprt_linux.run(ws.argv("--prepare-only", "--argument-manifest-path", str(ws.dir / "m.json"),
                                   "--provenance-path", str(path), *extra), env=ws.env)
        return json.loads(path.read_text()), path

    def test_record_identifies_inputs_arguments_and_host(self):
        ws = Workspace(self)
        record, _ = self.run_with_provenance(ws, ["--new-option", "Hash=16"])

        self.assertEqual(record["schema"], "sprt-linux-provenance/1")
        self.assertEqual(record["engines"]["new"], {"file": "cand.jar", "sha256": hashlib.sha256(b"candidate-jar").hexdigest()})
        self.assertEqual(record["engines"]["old"]["sha256"], hashlib.sha256(b"baseline-jar").hexdigest())
        self.assertEqual(record["opening_book"]["sha256"], hashlib.sha256(ws.book.read_bytes()).hexdigest())
        manifest = json.loads((ws.dir / "m.json").read_text())
        self.assertEqual(record["argument_array_sha256"], sprt_linux.arguments_sha256(manifest["Arguments"]))
        self.assertEqual(record["parameters"]["new_options"], ["Hash=16"])
        self.assertEqual(record["java_version_line"], "line-of-java")
        self.assertEqual(record["cutechess_version_line"], "line-of-cutechess-cli")
        self.assertEqual(record["launcher"]["sha256"], hashlib.sha256((ROOT / "tools/sprt_linux.py").read_bytes()).hexdigest())
        for key in ("cpu_count", "mem_total_kb", "swap_total_kb", "loadavg", "pressure_cpu", "pressure_memory"):
            self.assertIn(key, record["host"])
        self.assertIsNone(record["finished_utc"])

    def test_path_valued_engine_options_are_redacted_in_the_record(self):
        ws = Workspace(self)
        record, path = self.run_with_provenance(ws, [
            "--new-option", "EvalFile=/home/someone/nets/net.nnue", "--new-option", "EvalType=NNUE",
            "--old-option", "EvalFile=C:\\Users\\someone\\net.nnue", "--old-option", "SyzygyPath="])
        self.assertEqual(record["parameters"]["new_options"],
                         ["EvalFile=<path omitted: net.nnue>", "EvalType=NNUE"])
        self.assertEqual(record["parameters"]["old_options"], ["EvalFile=<path omitted: net.nnue>", "SyzygyPath="])
        self.assertNotIn("someone", path.read_text())
        # the argument manifest, like sprt.ps1's, still carries the real values
        manifest = json.loads((ws.dir / "m.json").read_text())
        self.assertIn("option.EvalFile=/home/someone/nets/net.nnue", manifest["Arguments"])

    def test_record_is_never_authoritative_and_has_no_absolute_paths(self):
        ws = Workspace(self)
        record, path = self.run_with_provenance(ws)
        self.assertIs(record["authoritative"], False)
        self.assertIn("not qualified", record["authoritative_reason"])
        self.assertNotIn(str(ws.dir), path.read_text())
        self.assertNotRegex(path.read_text(), r'"/(home|mnt|tmp|Users)/')

    def test_wsl_detection_uses_the_kernel_string_or_the_distro_variable(self):
        self.assertTrue(sprt_linux.is_wsl(lambda _path: "Linux version 6.1 microsoft-standard-WSL2", {}))
        self.assertTrue(sprt_linux.is_wsl(lambda _path: "Linux version 6.1 generic", {"WSL_DISTRO_NAME": "Ubuntu"}))
        self.assertFalse(sprt_linux.is_wsl(lambda _path: "Linux version 6.1 generic", {}))

    def test_host_record_parses_meminfo_and_keeps_pressure_text_raw(self):
        files = {"/proc/meminfo": "MemTotal:       16000000 kB\nSwapTotal:      0 kB\n",
                 "/proc/loadavg": "0.10 0.20 0.30 1/200 999\n",
                 "/proc/pressure/cpu": "some avg10=1.00 avg60=0.50 avg300=0.10 total=123\n"}
        host = sprt_linux.collect_host(lambda p: files.get(p))
        self.assertEqual((host["mem_total_kb"], host["swap_total_kb"]), (16000000, 0))
        self.assertEqual(host["loadavg"], "0.10 0.20 0.30 1/200 999")
        self.assertTrue(host["pressure_cpu"].startswith("some avg10=1.00"))
        self.assertIsNone(host["pressure_memory"])

    def test_finished_fields_are_filled_after_a_run(self):
        ws = Workspace(self)
        fake_executable(ws.cutechess, "echo done; exit 0")
        path = ws.dir / "provenance.json"
        with mock.patch.object(sprt_linux, "first_line_of", return_value="x"):
            sprt_linux.run(ws.argv("--provenance-path", str(path)), env=ws.env)
        record = json.loads(path.read_text())
        self.assertEqual(record["exit_code"], 0)
        self.assertRegex(record["finished_utc"], r"^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ$")


def ps_quote(value):
    return "'" + str(value).replace("'", "''") + "'"


@unittest.skipUnless(PWSH or os.environ.get("REQUIRE_PWSH") == "1", "pwsh not installed")
class DifferentialAgainstSprtPs1(unittest.TestCase):
    """Runs the real sprt.ps1 -PrepareOnly and the Linux launcher on the same inputs and compares the
    argument manifests. Needs pwsh 7; in CI REQUIRE_PWSH=1 makes its absence a failure."""

    CASES = {
        "defaults": ([], ["--tc", "60+0.6"]),
        "frozen-266": (["-Elo0", "0", "-Elo1", "10", "-Alpha", "0.05", "-Beta", "0.05", "-BonferroniM", "1",
                        "-MinGames", "0", "-MaxGames", "20000", "-TC", "5+0.05", "-Concurrency", "6",
                        "-EngineThreads", "1", "-OpeningOrder", "random"],
                       ["--elo0", "0", "--elo1", "10", "--alpha", "0.05", "--beta", "0.05", "--bonferroni-m", "1",
                        "--min-games", "0", "--max-games", "20000", "--tc", "5+0.05", "--concurrency", "6",
                        "--engine-threads", "1", "--opening-order", "random"]),
        "bonferroni-and-options": (
            ["-BonferroniM", "3", "-Elo0", "-2.5", "-Elo1", "7.5", "-EngineThreads", "2", "-OpeningOrder", "sequential",
             "-NewOptions", "'EvalType=NNUE','EvalFile=net.nnue'", "-OldOptions", "'EvalType=Classical'"],
            ["--bonferroni-m", "3", "--elo0", "-2.5", "--elo1", "7.5", "--engine-threads", "2",
             "--opening-order", "sequential", "--new-option", "EvalType=NNUE", "--new-option", "EvalFile=net.nnue",
             "--old-option", "EvalType=Classical"]),
        "small-and-large-doubles": (["-Alpha", "0.00001", "-Elo0", "-0.0001", "-Elo1", "999999999999999"],
                                    ["--alpha", "0.00001", "--elo0", "-0.0001", "--elo1", "999999999999999"]),
        "min-games-raises-the-cap": (["-MinGames", "30000"], ["--min-games", "30000"]),
        "min-games-below-default": (["-MinGames", "500"], ["--min-games", "500"]),
    }

    def setUp(self):
        self.assertIsNotNone(PWSH, "REQUIRE_PWSH=1 but pwsh is not installed")
        self.ws = Workspace(self)

    def ps_manifest(self, ps_extra, manifest_name="ps.json", expected=None):
        ws = self.ws
        manifest = ws.dir / manifest_name
        parts = ["&", ps_quote(PS1), "-New", ps_quote(ws.new), "-Old", ps_quote(ws.old), "-Tag", "x",
                 "-OpeningsFile", ps_quote(ws.book), "-LogPath", ps_quote(ws.log), "-PgnPath", ps_quote(ws.pgn),
                 "-PrepareOnly", "-ArgumentManifestPath", ps_quote(manifest)] + ps_extra
        if expected:
            parts += ["-ExpectedArgumentManifestPath", ps_quote(expected)]
        env = dict(os.environ, CUTECHESS=str(ws.cutechess))
        env.pop("JAVA", None)
        env.pop("JAVA_HOME", None)
        done = subprocess.run([str(PWSH), "-NoProfile", "-Command", " ".join(parts)], cwd=ROOT, env=env,
                              capture_output=True, text=True)
        return done, manifest

    def linux_manifest(self, extra, name="linux.json"):
        manifest = self.ws.dir / name
        argv = self.ws.argv("--prepare-only", "--argument-manifest-path", str(manifest), *extra)
        with mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop("JAVA", None)
            os.environ.pop("JAVA_HOME", None)
            sprt_linux.run(argv, env={"CUTECHESS": str(self.ws.cutechess)})
        return manifest

    @staticmethod
    def load(path):
        return json.loads(Path(path).read_text(encoding="utf-8-sig"))

    def test_argument_manifests_are_identical(self):
        for name, (ps_extra, linux_extra) in self.CASES.items():
            with self.subTest(case=name):
                done, ps_path = self.ps_manifest(ps_extra, f"ps-{name}.json")
                self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
                linux_path = self.linux_manifest(linux_extra, f"linux-{name}.json")
                ps, linux = self.load(ps_path), self.load(linux_path)
                self.assertEqual(ps["Executable"], linux["Executable"])
                self.assertEqual(ps["Arguments"], linux["Arguments"])

    def test_each_launcher_accepts_the_others_manifest_and_refuses_a_changed_one(self):
        done, ps_path = self.ps_manifest([], "ps.json")
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        linux_path = self.linux_manifest([], "linux.json")

        # Linux launcher accepts the manifest sprt.ps1 wrote
        extra = ["--expected-argument-manifest-path", str(ps_path)]
        self.linux_manifest(extra, "linux-checked.json")
        # sprt.ps1 accepts the manifest the Linux launcher wrote
        done, _ = self.ps_manifest([], "ps-checked.json", expected=str(linux_path))
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)

        # and each refuses when the arguments differ
        with self.assertRaises(sprt_linux.LauncherError):
            self.linux_manifest(["--tc", "1+0.01", "--expected-argument-manifest-path", str(ps_path)], "x.json")
        done, _ = self.ps_manifest(["-TC", "'1+0.01'"], "ps-bad.json", expected=str(linux_path))
        self.assertNotEqual(done.returncode, 0)
        self.assertIn("differ from the prepared manifest", done.stdout + done.stderr)


class FrozenFilesAreUntouched(unittest.TestCase):
    def test_the_linux_launcher_adds_no_dependency_on_the_windows_one(self):
        source = (ROOT / "tools/sprt_linux.py").read_text(encoding="utf-8")
        self.assertNotIn("subprocess.run([\"pwsh\"", source)
        self.assertNotRegex(source, r"[A-Za-z]:\\\\(Users|Tools|Windows)|/home/|/mnt/|/Users/")


if __name__ == "__main__":
    unittest.main()
