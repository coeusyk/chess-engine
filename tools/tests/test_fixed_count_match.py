"""Tests for tools/fixed_count_match.py (#319, for #260). Nothing here launches an engine match: the only process that
reaches Cute Chess is a stub shell script that prints a recorded log and copies a recorded PGN.
"""
import contextlib
import importlib.util
import io
import json
import os
import unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
TESTS = Path(__file__).resolve().parent


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


fixed = load("fixed_count_match_under_test", ROOT / "tools/fixed_count_match.py")
sprt_linux = fixed.sprt_linux
base_tests = load("test_sprt_linux_for_reuse", TESTS / "test_sprt_linux.py")
mv_tests = load("test_match_validation_for_reuse", TESTS / "test_match_validation.py")
Workspace, fake_executable = base_tests.Workspace, base_tests.fake_executable


class FixedWorkspace(Workspace):
    def fargv(self, *extra, games="4", seed="260258"):
        return ["--new", str(self.new), "--old", str(self.old), "--openings-file", str(self.book),
                "--log-path", str(self.log), "--pgn-path", str(self.pgn), "--games", games, "--seed", seed,
                "--provenance-path", str(self.dir / "provenance.json"), *extra]

    def stub_files(self, log_text, pgn_text, exit_code=0):
        log_file, pgn_file = self.dir / "recorded.log", self.dir / "recorded.pgn"
        log_file.write_text(log_text, encoding="utf-8")
        pgn_file.write_text(pgn_text, encoding="utf-8")
        fake_executable(self.cutechess, f'cat "{log_file}"\ncp "{pgn_file}" "{self.pgn}"\nexit {exit_code}')


def good_run(pairs=2):
    return mv_tests.build(mv_tests.cap_run(pairs))


class Arguments(unittest.TestCase):
    OPTS = ["Hash=16", "EvalType=Classical"]

    def built(self, **override):
        kw = dict(java="java", new_jar="/w/cand.jar", old_jar="/w/base.jar", tc="5+0.05", games=4000, concurrency=6,
                  engine_threads=1, new_options=self.OPTS, old_options=self.OPTS, pgn_out="/w/match.pgn",
                  openings_file="/w/book.epd", opening_order="random", seed=260258)
        kw.update(override)
        return fixed.build_arguments(**kw)

    def test_golden_argument_list_for_the_260_shape(self):
        engine = lambda name, jar: ["-engine", f"name={name}", "cmd=java", "arg=--add-modules",
                                    "arg=jdk.incubator.vector", "arg=-jar", f"arg={jar}", "proto=uci",
                                    "option.Threads=1", "option.Hash=16", "option.EvalType=Classical"]
        self.assertEqual(self.built(), engine("NEW", "/w/cand.jar") + engine("OLD", "/w/base.jar") + [
            "-each", "tc=5+0.05", "-games", "4000", "-repeat", "-recover",
            "-resign", "movecount=5", "score=400", "-draw", "movenumber=40", "movecount=8", "score=10",
            "-concurrency", "6", "-ratinginterval", "10", "-pgnout", "/w/match.pgn",
            "-openings", "file=/w/book.epd", "format=epd", "order=random", "plies=4", "-srand", "260258"])

    def test_it_is_the_sprt_argument_array_minus_the_sprt_group_and_nothing_else(self):
        sprt = sprt_linux.build_arguments("java", "/w/cand.jar", "/w/base.jar", "5+0.05", 4000, 0.0, 10.0, 0.05, 0.05, 6, 1,
                                          self.OPTS, self.OPTS, "/w/match.pgn", "/w/book.epd", "random", 260258)
        start = sprt.index("-sprt")
        self.assertEqual(sprt[:start] + sprt[start + 5:], self.built())
        self.assertNotIn("-sprt", self.built())
        self.assertFalse(any(token.startswith(("elo0=", "elo1=", "alpha=", "beta=")) for token in self.built()))

    def test_the_game_count_and_the_seed_are_taken_from_the_inputs(self):
        args = self.built(games=100, seed=7)
        self.assertEqual("100", args[args.index("-games") + 1])
        self.assertEqual(["-srand", "7"], args[-2:])


class Inputs(unittest.TestCase):
    def run_main(self, ws, argv):
        err = io.StringIO()
        with mock.patch.dict(os.environ, ws.env), contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(err):
            code = fixed.main(argv)
        return code, err.getvalue()

    def test_games_and_seed_are_required(self):
        ws = FixedWorkspace(self)
        base = ["--new", str(ws.new), "--old", str(ws.old), "--log-path", str(ws.log), "--pgn-path", str(ws.pgn),
                "--provenance-path", str(ws.dir / "p.json")]
        for missing in (["--seed", "1"], ["--games", "4"]):
            with self.subTest(given=missing), contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit):
                    fixed.parse_args(base + missing)

    def test_an_odd_or_non_positive_game_count_and_a_negative_seed_are_refused(self):
        ws = FixedWorkspace(self)
        for games, seed in (("5", "1"), ("0", "1"), ("-2", "1"), ("4", "-1")):
            with self.subTest(games=games, seed=seed):
                code, err = self.run_main(ws, ws.fargv("--prepare-only", "--argument-manifest-path", str(ws.dir / "m.json"),
                                                       games=games, seed=seed))
                self.assertEqual(1, code)
                self.assertRegex(err, r"--games|--seed")

    def test_prepare_only_needs_a_manifest_path(self):
        ws = FixedWorkspace(self)
        code, err = self.run_main(ws, ws.fargv("--prepare-only"))
        self.assertEqual(1, code)
        self.assertIn("--argument-manifest-path", err)


class Preparation(unittest.TestCase):
    def prepare(self, ws, *extra, **kw):
        manifest = ws.dir / "prepared.json"
        with mock.patch.object(sprt_linux, "first_line_of", return_value="x"), contextlib.redirect_stdout(io.StringIO()):
            code = fixed.run(ws.fargv("--prepare-only", "--argument-manifest-path", str(manifest), *extra, **kw), env=ws.env)
        self.assertEqual(0, code)
        return json.loads(manifest.read_text()), json.loads((ws.dir / "provenance.json").read_text())

    def save_expected(self, ws):
        saved = ws.dir / "expected.json"
        saved.write_text((ws.dir / "provenance.json").read_text())
        return saved

    def check(self, ws, saved, *extra, **kw):
        with mock.patch.object(sprt_linux, "first_line_of", return_value="x"), contextlib.redirect_stdout(io.StringIO()):
            return fixed.run(ws.fargv("--prepare-only", "--argument-manifest-path", str(ws.dir / "again.json"),
                                      "--expected-provenance-path", str(saved), *extra, **kw), env=ws.env)

    def test_the_manifest_and_provenance_record_the_seed_count_and_hashes(self):
        ws = FixedWorkspace(self)
        manifest, record = self.prepare(ws)
        self.assertEqual(["-srand", "260258"], manifest["Arguments"][-2:])
        self.assertNotIn("-sprt", manifest["Arguments"])
        self.assertEqual((4, 260258), (record["parameters"]["games"], record["parameters"]["seed"]))
        self.assertEqual("fixed-count-provenance/1", record["schema"])
        for digest in (record["engines"]["new"]["sha256"], record["engines"]["old"]["sha256"],
                       record["opening_book"]["sha256"], record["cutechess"]["sha256"], record["launcher"]["sha256"],
                       record["argument_array_sha256"]):
            self.assertRegex(digest, r"^[0-9a-f]{64}$")
        self.assertNotIn(str(ws.dir), json.dumps(record), "no absolute path in the provenance record")

    def test_the_expected_record_is_accepted_when_nothing_changed(self):
        ws = FixedWorkspace(self)
        self.prepare(ws)
        self.assertEqual(0, self.check(ws, self.save_expected(ws)))

    def refused(self, ws, what, mutate):
        self.prepare(ws)
        saved = self.save_expected(ws)
        mutate()
        with self.assertRaisesRegex(fixed.LauncherError, "differs from the prepared record.*" + what):
            self.check(ws, saved)

    def test_a_changed_candidate_jar_is_refused(self):
        ws = FixedWorkspace(self)
        self.refused(ws, "candidate JAR", lambda: ws.new.write_bytes(b"another candidate"))

    def test_a_changed_baseline_jar_is_refused(self):
        ws = FixedWorkspace(self)
        self.refused(ws, "baseline JAR", lambda: ws.old.write_bytes(b"another baseline"))

    def test_a_changed_opening_book_is_refused(self):
        ws = FixedWorkspace(self)
        self.refused(ws, "opening book", lambda: ws.book.write_text("different book\n"))

    def test_a_changed_cutechess_executable_is_refused(self):
        ws = FixedWorkspace(self)
        self.refused(ws, "Cute Chess executable", lambda: fake_executable(ws.cutechess, "echo another build"))

    def test_a_changed_launcher_hash_is_refused(self):
        ws = FixedWorkspace(self)
        self.prepare(ws)
        record = json.loads((ws.dir / "provenance.json").read_text())
        record["launcher"]["sha256"] = "0" * 64
        saved = ws.dir / "expected.json"
        saved.write_text(json.dumps(record))
        with self.assertRaisesRegex(fixed.LauncherError, "launcher"):
            self.check(ws, saved)

    def test_a_changed_seed_or_game_count_is_refused(self):
        ws = FixedWorkspace(self)
        self.prepare(ws)
        saved = self.save_expected(ws)
        for kw, what in (({"seed": "1"}, "seed"), ({"games": "6"}, "game count")):
            with self.subTest(change=what), self.assertRaisesRegex(fixed.LauncherError, what):
                self.check(ws, saved, **kw)

    def test_changed_arguments_are_refused_even_when_every_hash_matches(self):
        ws = FixedWorkspace(self)
        self.prepare(ws)
        saved = self.save_expected(ws)
        with self.assertRaisesRegex(fixed.LauncherError, "argument array"):
            self.check(ws, saved, "--new-option", "Hash=64")

    def test_a_record_of_another_schema_is_refused(self):
        ws = FixedWorkspace(self)
        self.prepare(ws)
        record = json.loads((ws.dir / "provenance.json").read_text())
        record["schema"] = "sprt-linux-provenance/1"
        saved = ws.dir / "expected.json"
        saved.write_text(json.dumps(record))
        with self.assertRaisesRegex(fixed.LauncherError, "record schema"):
            self.check(ws, saved)


class Completion(unittest.TestCase):
    def run_main(self, ws, *extra, **kw):
        out, err = io.StringIO(), io.StringIO()
        with mock.patch.object(sprt_linux, "first_line_of", return_value="x"), mock.patch.dict(os.environ, ws.env), \
                contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            os.environ.pop("JAVA", None)
            os.environ.pop("JAVA_HOME", None)
            code = fixed.main(ws.fargv(*extra, **kw))
        return code, out.getvalue(), err.getvalue()

    def record(self, ws):
        return json.loads((ws.dir / "provenance.json").read_text())

    def test_a_complete_fixed_count_run_succeeds_and_is_recorded(self):
        ws = FixedWorkspace(self)
        ws.stub_files(*good_run())
        code, out, _ = self.run_main(ws)
        self.assertEqual(0, code)
        self.assertIn("Fixed-count run complete", out)
        self.assertEqual("COMPLETE_CAP", self.record(ws)["completion"]["state"])
        self.assertEqual(0, self.record(ws)["exit_code"])

    def test_an_interrupted_run_that_exits_zero_does_not_succeed(self):
        ws = FixedWorkspace(self)
        ws.stub_files(*mv_tests.build(mv_tests.cap_run(2), finished_match=False))
        code, out, _ = self.run_main(ws)
        self.assertEqual(1, code)
        self.assertNotIn("Fixed-count run complete", out)
        self.assertIn("INTERRUPTED", out)
        self.assertEqual("INTERRUPTED", self.record(ws)["completion"]["state"])

    def test_fewer_games_than_requested_does_not_succeed(self):
        ws = FixedWorkspace(self)
        ws.stub_files(*good_run())
        code, out, _ = self.run_main(ws, games="6")
        self.assertEqual(1, code)
        self.assertNotIn("Fixed-count run complete", out)

    def test_more_games_than_requested_is_an_overrun(self):
        ws = FixedWorkspace(self)
        ws.stub_files(*good_run(3))
        code, out, _ = self.run_main(ws, games="4")
        self.assertEqual(1, code)
        self.assertIn("OVERRUN", out)

    def test_a_fixed_count_log_with_an_sprt_line_is_invalid(self):
        ws = FixedWorkspace(self)
        ws.stub_files(*mv_tests.build(mv_tests.cap_run(2), sprt_line="SPRT: llr 0.10 (3.4%), lbound -2.94, ubound 2.94"))
        code, out, _ = self.run_main(ws)
        self.assertEqual(1, code)
        self.assertIn("INVALID_EVIDENCE", out)

    def test_a_non_zero_exit_fails_even_when_the_artifacts_look_complete(self):
        ws = FixedWorkspace(self)
        ws.stub_files(*good_run(), exit_code=3)
        code, out, err = self.run_main(ws)
        self.assertEqual(1, code)
        self.assertNotIn("Fixed-count run complete", out)
        self.assertIn("exited with code 3", err)
        self.assertEqual(3, self.record(ws)["exit_code"])

    def test_a_non_zero_exit_fails_even_if_the_validator_would_pass_it(self):
        ws = FixedWorkspace(self)
        ws.stub_files(*good_run(), exit_code=2)
        always_ok = mock.Mock()
        always_ok.classify_files.return_value = mock.Mock(state="COMPLETE_CAP", describe=lambda: "ok")
        with mock.patch.object(sprt_linux, "load_match_validation", return_value=always_ok):
            code, out, _ = self.run_main(ws)
        self.assertEqual(1, code)
        self.assertNotIn("Fixed-count run complete", out)
        always_ok.classify_files.assert_not_called()

    def test_a_run_prepared_for_other_inputs_is_not_launched(self):
        ws = FixedWorkspace(self)
        ws.stub_files(*good_run())
        with mock.patch.object(sprt_linux, "first_line_of", return_value="x"), contextlib.redirect_stdout(io.StringIO()):
            fixed.run(ws.fargv("--prepare-only", "--argument-manifest-path", str(ws.dir / "m.json")), env=ws.env)
        saved = ws.dir / "expected.json"
        saved.write_text((ws.dir / "provenance.json").read_text())
        ws.new.write_bytes(b"swapped after preparation")
        code, out, err = self.run_main(ws, "--expected-provenance-path", str(saved))
        self.assertEqual(1, code)
        self.assertIn("candidate JAR", err)
        self.assertNotIn("Completion:", out, "nothing was launched")


class OutputsMayNotOverwriteInputs(unittest.TestCase):
    """The outputs are written after the hash check, so an output that aliased a verified input would destroy it
    before Cute Chess loads it."""

    def prepared(self, ws):
        with mock.patch.object(sprt_linux, "first_line_of", return_value="x"), contextlib.redirect_stdout(io.StringIO()):
            fixed.run(ws.fargv("--prepare-only", "--argument-manifest-path", str(ws.dir / "m.json")), env=ws.env)
        saved = ws.dir / "expected.json"
        saved.write_text((ws.dir / "provenance.json").read_text())
        return saved

    def launch(self, ws, saved, *override):
        out, err = io.StringIO(), io.StringIO()
        with mock.patch.object(sprt_linux, "first_line_of", return_value="x"), mock.patch.dict(os.environ, ws.env), \
                contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            # prepared() used an explicit environment, so the real JAVA and JAVA_HOME (set on CI runners) must not
            # change the java command here, or every hash and argument comparison would differ for that reason alone
            os.environ.pop("JAVA", None)
            os.environ.pop("JAVA_HOME", None)
            code = fixed.main(ws.fargv("--expected-provenance-path", str(saved), *override))
        return code, out.getvalue(), err.getvalue()

    def assert_refused(self, ws, saved, override, before, name):
        code, out, err = self.launch(ws, saved, *override)
        self.assertEqual(1, code)
        self.assertIn("refusing to overwrite an input", err)
        self.assertNotIn("Completion:", out, "nothing was launched")
        for path, content in before.items():
            self.assertEqual(content, path.read_bytes(), f"{path.name} must be untouched ({name})")

    def test_the_provenance_path_may_not_be_the_candidate_jar(self):
        ws = FixedWorkspace(self)
        ws.stub_files(*good_run())
        saved = self.prepared(ws)
        before = {ws.new: ws.new.read_bytes()}
        self.assert_refused(ws, saved, ["--provenance-path", str(ws.new)], before, "candidate JAR")

    def test_the_manifest_path_may_not_be_the_opening_book_or_the_baseline_jar(self):
        ws = FixedWorkspace(self)
        saved = self.prepared(ws)
        for target in (ws.book, ws.old):
            with self.subTest(target=target.name):
                before = {target: target.read_bytes()}
                self.assert_refused(ws, saved, ["--argument-manifest-path", str(target)], before, target.name)

    def test_a_symlink_to_an_input_is_the_same_file(self):
        ws = FixedWorkspace(self)
        saved = self.prepared(ws)
        link = ws.dir / "provenance-link.json"
        link.symlink_to(ws.new)
        before = {ws.new: ws.new.read_bytes()}
        self.assert_refused(ws, saved, ["--provenance-path", str(link)], before, "symlink to the candidate JAR")

    def test_the_provenance_path_may_not_be_the_expected_record_or_the_launcher(self):
        ws = FixedWorkspace(self)
        saved = self.prepared(ws)
        before = {saved: saved.read_bytes()}
        self.assert_refused(ws, saved, ["--provenance-path", str(saved)], before, "expected record")
        launcher = ROOT / "tools/fixed_count_match.py"
        code, _, err = self.launch(ws, saved, "--provenance-path", str(launcher))
        self.assertEqual(1, code)
        self.assertIn("refusing to overwrite an input", err)

    def test_the_outputs_may_not_be_the_same_file_as_each_other(self):
        ws = FixedWorkspace(self)
        saved = self.prepared(ws)
        code, _, err = self.launch(ws, saved, "--argument-manifest-path", str(ws.dir / "provenance.json"))
        self.assertEqual(1, code)
        self.assertIn("are the same file", err)

    def test_distinct_outputs_are_still_accepted(self):
        ws = FixedWorkspace(self)
        ws.stub_files(*good_run())
        saved = self.prepared(ws)
        code, out, _ = self.launch(ws, saved)
        self.assertEqual(0, code, out)


class Hygiene(unittest.TestCase):
    def test_no_absolute_paths_and_one_sprt_token_only_to_remove_it(self):
        source = (ROOT / "tools/fixed_count_match.py").read_text(encoding="utf-8")
        self.assertNotRegex(source, r"[A-Za-z]:\\\\(Users|Tools|Windows)|/home/|/mnt/|/Users/")
        self.assertEqual(1, source.count('index("-sprt")'), "the launcher only looks the SPRT group up in order to remove it")
        self.assertNotIn("+= [\"-sprt\"", source)


if __name__ == "__main__":
    unittest.main()
