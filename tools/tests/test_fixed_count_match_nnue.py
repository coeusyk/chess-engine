"""Tests for the NNUE guard in tools/fixed_count_match.py (#330). No match is ever launched: the stub Cute Chess
executable writes a marker file, and every refusal asserts the marker was never written. The engine is a Python
fake behind a fake `java` script, so the real pre-flight handshake runs on the exact arguments the launcher built.
"""
import contextlib
import hashlib
import importlib.util
import io
import json
import os
import stat
import sys
import unittest
from pathlib import Path
from unittest import mock

TESTS = Path(__file__).resolve().parent
ROOT = TESTS.parents[1]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


fixed = load("fixed_count_match_nnue_under_test", ROOT / "tools/fixed_count_match.py")
fixed_tests = load("test_fixed_count_match_for_nnue_reuse", TESTS / "test_fixed_count_match.py")
preflight_tests = load("test_nnue_preflight_for_reuse", TESTS / "test_nnue_preflight.py")
nnue_preflight = fixed.nnue_preflight
LauncherError = fixed.LauncherError
UUID = preflight_tests.UUID


class NnueWorkspace(fixed_tests.FixedWorkspace):
    def __init__(self, test):
        super().__init__(test)
        self.net = self.dir / f"{UUID}.nnue"
        self.net.write_bytes(b"network bytes")
        self.sha = hashlib.sha256(b"network bytes").hexdigest()
        engine = self.dir / "fake_engine.py"
        engine.write_text(preflight_tests.FAKE_ENGINE, encoding="utf-8")
        java_home = self.dir / "jh"
        (java_home / "bin").mkdir(parents=True)
        java = java_home / "bin" / "java"
        java.write_text(f'#!/bin/sh\nexec "{sys.executable}" "{engine}" "$@"\n')
        java.chmod(java.stat().st_mode | stat.S_IEXEC)
        self.marker = self.dir / "cutechess-was-launched"
        # `--version` is queried for the provenance record; only a real match invocation (it has -engine) counts.
        self.cutechess.write_text(f'#!/bin/sh\ncase "$*" in *-engine*) touch "{self.marker}";; esac\nexit 1\n')
        self.cutechess.chmod(self.cutechess.stat().st_mode | stat.S_IEXEC)
        self.env = {"CUTECHESS": str(self.cutechess), "JAVA": str(java_home)}

    def nnue_argv(self, *extra, pins=True, strict=True, prepare=True):
        options = ["--new-option", "EvalType=NNUE", "--new-option", f"EvalFile={self.net}"]
        if strict:
            options += ["--new-option", "StrictNnue=true"]
        pin_args = ["--new-nnue-uuid", UUID, "--new-nnue-sha256", self.sha] if pins else []
        manifest = ["--prepare-only", "--argument-manifest-path", str(self.dir / "m.json")] if prepare else []
        return self.fargv(*options, *pin_args, *manifest, *extra)


class Case(unittest.TestCase):
    def setUp(self):
        self.ws = NnueWorkspace(self)
        patcher = mock.patch.dict(os.environ, {"FAKE_OPTIONS": ",".join(preflight_tests.FULL_OPTIONS), "FAKE_UUID": UUID})
        patcher.start()
        self.addCleanup(patcher.stop)

    def run_fixed(self, argv):
        with contextlib.redirect_stdout(io.StringIO()):
            return fixed.run(argv, env=self.ws.env)

    def refused(self, argv, fragment):
        with self.assertRaises(LauncherError) as caught:
            self.run_fixed(argv)
        self.assertIn(fragment, str(caught.exception))
        self.assertFalse(self.ws.marker.exists(), "Cute Chess must not have been started")
        return str(caught.exception)


class Refusals(Case):
    def test_nnue_side_without_pins_is_refused(self):
        self.refused(self.ws.nnue_argv(pins=False), "needs --new-nnue-uuid")

    def test_wrong_hash_pin_is_refused(self):
        argv = self.ws.nnue_argv()
        argv[argv.index("--new-nnue-sha256") + 1] = "0" * 64
        self.refused(argv, "differs from the pin")

    def test_wrong_uuid_pin_is_refused(self):
        argv = self.ws.nnue_argv()
        argv[argv.index("--new-nnue-uuid") + 1] = "bbbbbbbb-1111-2222-3333-444444444444"
        self.refused(argv, "the pin is")

    def test_nnue_without_strict_is_refused(self):
        self.refused(self.ws.nnue_argv(strict=False), "without StrictNnue=true")

    def test_pins_without_an_nnue_side_are_refused(self):
        self.refused(self.ws.fargv("--new-nnue-uuid", UUID, "--new-nnue-sha256", self.ws.sha), "no side has EvalType=NNUE")

    def test_pins_for_a_classical_side_are_refused(self):
        argv = self.ws.nnue_argv() + ["--old-nnue-uuid", UUID, "--old-nnue-sha256", self.ws.sha]
        self.refused(argv, "not EvalType=NNUE")

    def test_duplicate_threads_from_the_launcher_default_and_a_user_option_is_refused(self):
        self.refused(self.ws.nnue_argv("--new-option", "Threads=8"), "given more than once")

    def test_misspelled_option_is_refused(self):
        argv = self.ws.nnue_argv("--new-option", "Evaltyp=NNUE")
        self.refused(argv, "Evaltyp is not advertised")

    def test_a_jar_without_the_strict_option_is_refused(self):
        with mock.patch.dict(os.environ, {"FAKE_OPTIONS": "Hash,Threads,EvalType,EvalFile,NnueDebug"}):
            self.refused(self.ws.nnue_argv(), "does not advertise StrictNnue")

    def test_an_engine_that_fails_the_handshake_is_refused(self):
        with mock.patch.dict(os.environ, {"FAKE_MODE": "strict-exit"}):
            self.refused(self.ws.nnue_argv(), "NNUE strict mode")

    def test_a_network_replaced_after_the_preflight_is_refused_before_launch(self):
        real = nnue_preflight.preflight

        def swap_after_verifying(config, pins, **kw):
            record = real(config, pins, **kw)
            if pins is not None:
                self.ws.net.write_bytes(b"swapped network")
            return record

        with mock.patch.object(nnue_preflight, "preflight", swap_after_verifying), \
                mock.patch.object(fixed.sprt_linux, "stream_cutechess") as stream:
            self.refused(self.ws.nnue_argv(prepare=False), "failed the re-check before launch")
        stream.assert_not_called()


class Accepted(Case):
    def test_a_verified_nnue_run_records_the_preflight_in_the_provenance(self):
        code = self.run_fixed(self.ws.nnue_argv())

        self.assertEqual(code, 0)
        record = json.loads((self.ws.dir / "provenance.json").read_text())
        self.assertEqual(record["nnue_preflight"]["new"]["result"], "pass")
        self.assertEqual(record["nnue_preflight"]["new"]["uuid_reported"], UUID)
        self.assertEqual(record["nnue_preflight"]["new"]["provenance"], "unverified-manifest-absent")
        self.assertEqual(record["nnue_preflight"]["old"]["nnue_sha256"], None)
        self.assertFalse(self.ws.marker.exists(), "--prepare-only must not start Cute Chess")
        self.assertNotIn(str(self.ws.dir), json.dumps(record["nnue_preflight"]))

    def test_classical_only_run_is_untouched_and_runs_no_preflight(self):
        with mock.patch.object(nnue_preflight, "preflight") as preflight:
            code = self.run_fixed(self.ws.fargv("--new-option", "Hash=16", "--prepare-only",
                                                "--argument-manifest-path", str(self.ws.dir / "m.json")))

        self.assertEqual(code, 0)
        preflight.assert_not_called()
        self.assertNotIn("nnue_preflight", json.loads((self.ws.dir / "provenance.json").read_text()))

    def test_the_preflight_sees_the_exact_launcher_arguments(self):
        seen = {}
        real = nnue_preflight.preflight

        def spy(config, pins, **kw):
            seen[config.name] = config
            return real(config, pins, **kw)

        with mock.patch.object(nnue_preflight, "preflight", spy):
            self.run_fixed(self.ws.nnue_argv())

        new = seen["NEW"]
        self.assertEqual(new.cmd, os.path.join(self.ws.env["JAVA"], "bin", "java"))
        self.assertEqual(new.args, ("--add-modules", "jdk.incubator.vector", "-jar", str(self.ws.new)))
        self.assertEqual([n for n, _ in new.options], ["Threads", "EvalType", "EvalFile", "StrictNnue"])
        self.assertEqual(seen["OLD"].jar, str(self.ws.old))


if __name__ == "__main__":
    unittest.main()
