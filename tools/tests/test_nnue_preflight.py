"""Tests for tools/nnue_preflight.py (#330). A small Python script stands in for the engine, so no JVM is needed:
it advertises a configurable option list, records its own argv, and answers (or fails to answer) the handshake
the way the real engine does in strict mode.
"""
import hashlib
import importlib.util
import json
import os
import re
import sys
import tempfile
import textwrap
import time
import unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


pre = load("nnue_preflight_under_test", ROOT / "tools/nnue_preflight.py")

UUID = "aaaaaaaa-1111-2222-3333-444444444444"
FULL_OPTIONS = ["Hash", "Threads", "EvalType", "EvalFile", "NnueDebug", "StrictNnue"]

FAKE_ENGINE = textwrap.dedent('''
    import json, os, sys
    mode = os.environ.get("FAKE_MODE", "ok")
    advertised = os.environ.get("FAKE_OPTIONS", "").split(",")
    if os.environ.get("FAKE_ARGV_OUT"):
        open(os.environ["FAKE_ARGV_OUT"], "w").write(json.dumps(sys.argv[1:]))
    if os.environ.get("FAKE_PID_OUT"):
        open(os.environ["FAKE_PID_OUT"], "w").write(str(os.getpid()))
    options = {}
    for line in sys.stdin:
        line = line.strip()
        if line == "uci":
            for name in advertised:
                print("option name " + name + " type check default false", flush=True)
            print("uciok", flush=True)
        elif line.startswith("setoption name "):
            name, _, value = line[len("setoption name "):].partition(" value ")
            options[name] = value
        elif line == "isready":
            if mode == "hang":
                import time
                time.sleep(60)
            if mode == "strict-exit":
                print("info string NNUE strict mode: cannot load EvalFile 'x': bad magic", flush=True)
                sys.exit(2)
            if options.get("EvalType") == "NNUE" and mode != "no-loaded-line":
                print("info string NNUE network loaded: " + os.environ.get("FAKE_UUID", ""), flush=True)
            print("readyok", flush=True)
        elif line == "quit":
            break
''')


DEFAULT = object()


class Case(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.addCleanup(self.tmp.cleanup)
        self.engine = self.dir / "fake_engine.py"
        self.engine.write_text(FAKE_ENGINE, encoding="utf-8")
        self.jar = self.dir / "vex-test.jar"
        self.jar.write_bytes(b"jar")
        self.net = self.dir / f"{UUID}.nnue"
        self.net.write_bytes(b"network bytes")
        self.sha = hashlib.sha256(b"network bytes").hexdigest()
        self.argv_out = self.dir / "argv.json"
        env = {"FAKE_OPTIONS": ",".join(FULL_OPTIONS), "FAKE_UUID": UUID, "FAKE_ARGV_OUT": str(self.argv_out)}
        patcher = mock.patch.dict(os.environ, env)
        patcher.start()
        self.addCleanup(patcher.stop)

    def config(self, options=None, jvm_args=("-Xmx64m",)):
        options = options if options is not None else [
            ("Threads", "1"), ("EvalType", "NNUE"), ("EvalFile", str(self.net)), ("StrictNnue", "true")]
        return pre.EngineConfig("NEW", sys.executable, (str(self.engine), *jvm_args, "-jar", str(self.jar)), tuple(options))

    def pins(self, uuid=UUID, sha=None):
        return pre.Pins(uuid, sha or self.sha)

    def run_pre(self, config=None, pins=DEFAULT, timeout=10.0):
        return pre.preflight(config or self.config(), self.pins() if pins is DEFAULT else pins, timeout=timeout)

    def fails(self, code, config=None, pins=DEFAULT, fragment=None, timeout=10.0):
        with self.assertRaises(pre.PreflightError) as caught:
            self.run_pre(config, pins, timeout)
        self.assertEqual(caught.exception.code, code, str(caught.exception))
        if fragment:
            self.assertIn(fragment, str(caught.exception))
        return caught.exception


class ConfigRejections(Case):
    def test_duplicate_option_is_rejected_even_with_the_same_value(self):
        self.fails(pre.EXIT_REJECTED, self.config([("Threads", "1"), ("Threads", "1"), ("EvalType", "Classical")]),
                   fragment="given more than once")

    def test_conflicting_duplicate_option_is_rejected(self):
        error = self.fails(pre.EXIT_REJECTED, self.config([("Threads", "1"), ("Threads", "8"), ("EvalType", "Classical")]))
        self.assertIn("different value", str(error))

    def test_misspelled_option_name_is_rejected(self):
        self.fails(pre.EXIT_REJECTED, self.config([("EvalTyp", "NNUE"), ("EvalFile", str(self.net)), ("StrictNnue", "true")]),
                   fragment="EvalTyp is not advertised")

    def test_option_differing_only_in_case_is_rejected_with_the_advertised_name(self):
        error = self.fails(pre.EXIT_REJECTED, self.config([("evaltype", "Classical")]))
        self.assertIn("differs only in case from the advertised EvalType", str(error))

    def test_unadvertised_option_is_rejected(self):
        self.fails(pre.EXIT_REJECTED, self.config([("Ponderr", "true"), ("EvalType", "Classical")]), fragment="Ponderr")

    def test_invalid_eval_type_value_is_rejected(self):
        self.fails(pre.EXIT_REJECTED, self.config([("EvalType", "NNUE2"), ("StrictNnue", "true")]), fragment="exactly Classical or NNUE")
        self.fails(pre.EXIT_REJECTED, self.config([("EvalType", "nnue"), ("EvalFile", str(self.net)), ("StrictNnue", "true")]),
                   fragment="exactly Classical or NNUE")

    def test_nnue_without_eval_file_is_rejected(self):
        self.fails(pre.EXIT_REJECTED, self.config([("EvalType", "NNUE"), ("StrictNnue", "true")]), fragment="without an EvalFile")

    def test_relative_eval_file_is_rejected(self):
        self.fails(pre.EXIT_REJECTED, self.config([("EvalType", "NNUE"), ("EvalFile", "net.nnue"), ("StrictNnue", "true")]),
                   fragment="absolute path")

    def test_eval_file_with_a_classical_engine_is_rejected(self):
        self.fails(pre.EXIT_REJECTED, self.config([("EvalType", "Classical"), ("EvalFile", str(self.net))]),
                   fragment="would never be used")

    def test_nnue_without_strict_is_rejected(self):
        self.fails(pre.EXIT_REJECTED, self.config([("EvalType", "NNUE"), ("EvalFile", str(self.net))]),
                   fragment="without StrictNnue=true")

    def test_jar_that_predates_strict_mode_is_rejected(self):
        with mock.patch.dict(os.environ, {"FAKE_OPTIONS": "Hash,Threads,EvalType,EvalFile,NnueDebug"}):
            self.fails(pre.EXIT_REJECTED, fragment="does not advertise StrictNnue")

    def test_missing_jar_is_rejected_before_anything_is_launched(self):
        config = pre.EngineConfig("NEW", sys.executable, (str(self.engine), "-jar", str(self.dir / "absent.jar")),
                                  (("EvalType", "Classical"),))
        self.fails(pre.EXIT_REJECTED, config)
        self.assertFalse(self.argv_out.exists())


class EachOptions(unittest.TestCase):
    def test_each_engine_options_are_refused_because_they_reach_the_engines_unverified(self):
        for each in ("option.EvalType=NNUE", "initstr=setoption name EvalType value NNUE"):
            cc = ["-engine", "name=NEW", "cmd=java", "arg=-jar", "arg=/w/c.jar", "proto=uci", "-each", "tc=5+0.05", each]
            with self.assertRaises(pre.PreflightError) as caught:
                pre.effective_engine_config(cc, "NEW")
            self.assertEqual(caught.exception.code, pre.EXIT_REJECTED)

    def test_each_tc_alone_is_fine(self):
        cc = ["-engine", "name=NEW", "cmd=java", "arg=-jar", "arg=/w/c.jar", "proto=uci", "-each", "tc=5+0.05", "proto=uci"]
        self.assertEqual(pre.effective_engine_config(cc, "NEW").jar, "/w/c.jar")


class AbsolutePaths(unittest.TestCase):
    def test_absolute_is_judged_for_the_engine_host(self):
        with mock.patch.object(pre.os, "name", "posix"):
            self.assertTrue(pre._absolute_on_this_host("/net/x.nnue"))
            for relative in ("net.nnue", "C:\\net.nnue", "C:/net.nnue", "\\\\srv\\share\\x"):
                self.assertFalse(pre._absolute_on_this_host(relative), relative)
        with mock.patch.object(pre.os, "name", "nt"):
            for absolute in ("C:\\net.nnue", "C:/net.nnue", "\\\\srv\\share\\x.nnue"):
                self.assertTrue(pre._absolute_on_this_host(absolute), absolute)
            for relative in ("/net.nnue", "net.nnue", "\\net.nnue", "C:net.nnue"):
                self.assertFalse(pre._absolute_on_this_host(relative), relative)

    def test_redaction_removes_either_style_of_absolute_path(self):
        self.assertEqual(pre._redact("EvalFile=C:\\Tools\\net.nnue"), "EvalFile=net.nnue")
        self.assertEqual(pre._redact("/home/someone/net.nnue"), "net.nnue")
        self.assertEqual(pre._redact("Threads=8"), "Threads=8")


class LaunchedExactly(Case):
    def test_the_launched_command_line_is_exactly_the_configured_one(self):
        config = self.config(jvm_args=("-Xmx64m", "-Dsome.flag=1"))

        self.run_pre(config)

        self.assertEqual(json.loads(self.argv_out.read_text()),
                         ["-Xmx64m", "-Dsome.flag=1", "-jar", str(self.jar)])

    def test_the_options_are_sent_in_the_configured_order(self):
        record = self.run_pre()
        self.assertEqual(record["options"], ["Threads=1", "EvalType=NNUE", f"EvalFile={self.net.name}", "StrictNnue=true"])

    def test_parses_a_cutechess_engine_block(self):
        cc = ["-engine", "name=NEW", "cmd=java", "arg=--add-modules", "arg=jdk.incubator.vector", "arg=-jar", "arg=/w/c.jar",
              "proto=uci", "option.Threads=1", "option.EvalType=NNUE", "-engine", "name=OLD", "cmd=java", "arg=-jar",
              "arg=/w/o.jar", "proto=uci", "option.Threads=1", "-each", "tc=5+0.05"]

        new = pre.effective_engine_config(cc, "NEW")
        old = pre.effective_engine_config(cc, "OLD")

        self.assertEqual((new.cmd, new.args, new.jar), ("java", ("--add-modules", "jdk.incubator.vector", "-jar", "/w/c.jar"), "/w/c.jar"))
        self.assertEqual(new.options, (("Threads", "1"), ("EvalType", "NNUE")))
        self.assertEqual((old.jar, old.options), ("/w/o.jar", (("Threads", "1"),)))
        with self.assertRaises(pre.PreflightError):
            pre.effective_engine_config(cc, "MISSING")


class PinnedIdentity(Case):
    def test_pass_records_basenames_only_and_both_hashes(self):
        record = self.run_pre()

        self.assertEqual(record["result"], "pass")
        self.assertEqual(record["uuid_reported"], UUID)
        self.assertEqual(record["nnue_sha256"], self.sha)
        self.assertEqual(record["jar"], "vex-test.jar")
        self.assertNotIn(str(self.dir), json.dumps(record))

    def test_a_pin_is_mandatory_for_nnue(self):
        self.fails(pre.EXIT_PIN, pins=None, fragment="pinned uuid and SHA-256 are required")

    def test_wrong_hash_pin_is_refused_before_any_engine_starts(self):
        self.fails(pre.EXIT_PIN, pins=self.pins(sha="0" * 64), fragment="differs from the pin")
        self.assertFalse(self.argv_out.exists())

    def test_wrong_uuid_pin_is_refused(self):
        self.fails(pre.EXIT_PIN, pins=self.pins(uuid="bbbbbbbb-1111-2222-3333-444444444444"), fragment="the pin is")

    def test_no_manifest_is_accepted_only_as_unverified_provenance(self):
        self.assertEqual(self.run_pre()["provenance"], "unverified-manifest-absent")

    def test_agreeing_manifest_is_reported_as_verified(self):
        (self.dir / f"{UUID}.json").write_text(json.dumps({"nnue_sha256": self.sha, "network_uuid": UUID}))
        self.assertEqual(self.run_pre()["provenance"], "manifest-verified")

    def test_disagreeing_manifest_is_refused(self):
        (self.dir / f"{UUID}.json").write_text(json.dumps({"nnue_sha256": "f" * 64, "network_uuid": UUID}))
        self.fails(pre.EXIT_PIN, fragment="manifest disagrees")

    def test_recheck_catches_a_file_replaced_after_verification(self):
        record = self.run_pre()
        pre.recheck(record, str(self.net))
        self.net.write_bytes(b"swapped network")
        with self.assertRaises(pre.PreflightError) as caught:
            pre.recheck(record, str(self.net))
        self.assertEqual(caught.exception.code, pre.EXIT_PIN)

    def test_recheck_catches_a_deleted_file(self):
        record = self.run_pre()
        self.net.unlink()
        with self.assertRaises(pre.PreflightError):
            pre.recheck(record, str(self.net))

    def test_pins_are_compared_case_insensitively(self):
        record = self.run_pre(pins=pre.Pins(UUID.upper(), (" " + self.sha.upper() + "\n")))
        self.assertEqual(record["uuid_reported"], UUID)
        self.assertEqual(record["pinned_uuid"], UUID)

    def test_a_classical_engine_needs_no_pin(self):
        record = self.run_pre(self.config([("Threads", "1"), ("EvalType", "Classical")]), pins=None)
        self.assertEqual((record["nnue_sha256"], record["provenance"]), (None, "not-applicable"))


class HandshakeFailures(Case):
    def test_engine_exit_before_readyok_reports_its_strict_line(self):
        with mock.patch.dict(os.environ, {"FAKE_MODE": "strict-exit"}):
            error = self.fails(pre.EXIT_ENGINE_FAILED)
        self.assertIn("NNUE strict mode", str(error))

    def test_readyok_without_a_loaded_line_is_refused(self):
        with mock.patch.dict(os.environ, {"FAKE_MODE": "no-loaded-line"}):
            self.fails(pre.EXIT_ENGINE_FAILED, fragment="without an 'NNUE network loaded' line")

    def test_a_hung_engine_times_out_and_is_killed(self):
        pid_file = self.dir / "pid.txt"
        with mock.patch.dict(os.environ, {"FAKE_MODE": "hang", "FAKE_PID_OUT": str(pid_file)}):
            started = time.monotonic()
            self.fails(pre.EXIT_TIMEOUT, timeout=1.0)
        self.assertLess(time.monotonic() - started, 10)
        with self.assertRaises(ProcessLookupError):
            os.kill(int(pid_file.read_text()), 0)

    def test_a_corrupt_or_non_object_manifest_is_a_pin_failure_not_a_traceback(self):
        manifest = self.dir / f"{UUID}.json"
        for text in ("{not json", "[1, 2]", ""):
            manifest.write_text(text)
            error = self.fails(pre.EXIT_PIN)
            self.assertIn("manifest", str(error))

    def test_a_missing_network_file_is_rejected_before_any_engine_starts(self):
        self.net.unlink()
        self.fails(pre.EXIT_REJECTED, fragment="does not exist")
        self.assertFalse(self.argv_out.exists())


class LoadedByPath(unittest.TestCase):
    def test_module_has_no_deferred_annotations(self):
        """fixed_count_match.py loads this file by path without registering it in sys.modules. On Python 3.12 a
        dataclass defined under `from __future__ import annotations` then raises AttributeError at import (found
        by the native Windows run, not reproducible on 3.15), so the import must stay out of this module."""
        source = (ROOT / "tools/nnue_preflight.py").read_text(encoding="utf-8")
        self.assertIsNone(re.search(r"^from __future__ import annotations", source, re.MULTILINE))

    def test_loads_without_being_registered_in_sys_modules(self):
        spec = importlib.util.spec_from_file_location("nnue_preflight_unregistered", ROOT / "tools/nnue_preflight.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        self.assertEqual(module.Pins("u", "h").uuid, "u")


class EvalScore(unittest.TestCase):
    def test_parses_positive_and_negative_scores(self):
        self.assertEqual(pre.parse_eval_score("x\n  int16 score           +37 cp\n  float32 oracle score  +37.0000 cp"), 37)
        self.assertEqual(pre.parse_eval_score("  int16 score           -5 cp"), -5)

    def test_raises_without_the_line(self):
        with self.assertRaises(ValueError):
            pre.parse_eval_score("classical breakdown")


if __name__ == "__main__":
    unittest.main()
