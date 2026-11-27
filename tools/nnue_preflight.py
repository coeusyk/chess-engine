"""Verify the effective configuration of an NNUE engine before a screening run starts (#330).

`StrictNnue` makes the engine end instead of playing Classical when it is told to use a network it cannot
use, but the engine can only act on what it was told. An option sent under a misspelled name is ignored
under the UCI rules and leaves Classical selected, a duplicated option has one value silently win, and a
JAR that predates the option makes Cute Chess print a warning and carry on. This tool checks those from
the outside, by launching exactly what the match will launch.

    python tools/nnue_preflight.py --jar JAR --eval-file NET --expected-uuid UUID --expected-nnue-sha256 SHA256
        [--java CMD] [--jvm-arg ARG ...] [--option NAME=VALUE ...] [--output OUT.json]

It launches the same Java executable, JVM arguments, JAR and options in the same order, so a configuration
that differs from the match is not what gets verified. It then checks:

* every option name is one the engine advertises (case-sensitive: an option that differs only in case is
  reported), none is given twice, `EvalType` is exactly `Classical` or `NNUE`, an NNUE engine has an
  absolute `EvalFile` and `StrictNnue=true`, and `EvalFile` is not set for a Classical engine;
* the network file's SHA-256 equals the pinned hash and the uuid the engine reports equals the pinned uuid.
  A pin is mandatory. A sibling `<uuid>.json` manifest, when present, must agree with the file. When none
  exists the record says `unverified-manifest-absent`: the bytes still matched the pin, but no provenance is
  claimed;
* the engine answers `readyok`, with the network loaded, within a bounded wait.

`recheck(record)` recomputes the file hash and must be called immediately before the match is launched, so a
file replaced after the handshake is caught.

Exit status: 0 pass, 3 configuration rejected, 4 the engine failed before `readyok`, 5 timeout, 6 pin or hash
mismatch. Records hold file names only, never a local directory layout.
"""
# No `from __future__ import annotations` here: fixed_count_match.py loads this file by path without registering it in
# sys.modules, and on Python 3.12 a dataclass with string annotations then fails (found by the native Windows run).
import argparse
import hashlib
import json
import os
import queue
import re
import subprocess
import sys
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Mapping, Optional, Sequence, Tuple

SCHEMA = "nnue-preflight/1"
EXIT_REJECTED, EXIT_ENGINE_FAILED, EXIT_TIMEOUT, EXIT_PIN = 3, 4, 5, 6
_LOADED = re.compile(r"^info string NNUE network loaded: (\S+)$")
_OPTION_LINE = re.compile(r"^option name (.+?) type ")
_SCORE_LINE = re.compile(r"^\s*int16 score\s+([+-]\d+) cp\s*$", re.MULTILINE)


class PreflightError(Exception):
    def __init__(self, code: int, reasons: Sequence[str]):
        super().__init__("; ".join(reasons))
        self.code = code
        self.reasons = list(reasons)


@dataclass(frozen=True)
class EngineConfig:
    """One `-engine` block as Cute Chess receives it."""
    name: str
    cmd: str
    args: Tuple[str, ...]
    options: Tuple[Tuple[str, str], ...]
    proto: str = "uci"

    @property
    def jar(self) -> Optional[str]:
        for i, arg in enumerate(self.args[:-1]):
            if arg == "-jar":
                return self.args[i + 1]
        return None

    @property
    def command(self) -> List[str]:
        return [self.cmd, *self.args]

    def option(self, name: str) -> Optional[str]:
        for key, value in self.options:
            if key == name:
                return value
        return None


@dataclass(frozen=True)
class Pins:
    uuid: str
    nnue_sha256: str


def effective_engine_config(cc_args: Sequence[str], engine_name: str) -> EngineConfig:
    """Parses the `-engine name=<engine_name> ...` block of a Cute Chess argument array."""
    _refuse_each_engine_options(cc_args)
    for i, token in enumerate(cc_args):
        if token != "-engine" or i + 1 >= len(cc_args) or cc_args[i + 1] != f"name={engine_name}":
            continue
        cmd, args, options, proto = "", [], [], "uci"
        for item in cc_args[i + 1:]:
            if item.startswith("-"):
                break
            if item.startswith("cmd="):
                cmd = item[4:]
            elif item.startswith("arg="):
                args.append(item[4:])
            elif item.startswith("proto="):
                proto = item[6:]
            elif item.startswith("option."):
                key, _, value = item[len("option."):].partition("=")
                options.append((key, value))
        if not cmd:
            raise PreflightError(EXIT_REJECTED, [f"engine {engine_name} has no cmd="])
        return EngineConfig(engine_name, cmd, tuple(args), tuple(options), proto)
    raise PreflightError(EXIT_REJECTED, [f"no -engine block named {engine_name}"])


def _refuse_each_engine_options(cc_args: Sequence[str]) -> None:
    """`-each option.X=Y` and `-each initstr=...` apply to every engine and are not part of any `-engine` block,
    so they would reach the engines unverified. The launchers emit only `-each tc=...`; anything else is refused."""
    for i, token in enumerate(cc_args):
        if token != "-each":
            continue
        for item in cc_args[i + 1:]:
            if item.startswith("-"):
                break
            if item.startswith(("option.", "initstr=")):
                raise PreflightError(EXIT_REJECTED, [f"-each {item.split('=')[0]} reaches the engines unverified; give engine options per -engine"])


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with Path(path).open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def parse_eval_score(output: str) -> int:
    """The integer score from the UCI `eval` breakdown of an NNUE engine (`  int16 score   +37 cp`)."""
    match = _SCORE_LINE.search(output)
    if match is None:
        raise ValueError("no 'int16 score' line in the eval output")
    return int(match.group(1))


def _looks_absolute(value: str) -> bool:
    """Absolute in either path style. Used to redact local paths from records, where both styles must go."""
    return value.startswith(("/", "\\\\")) or (len(value) > 2 and value[1] == ":" and value[2] in "\\/")


def _absolute_on_this_host(value: str) -> bool:
    """Absolute for the engine's host: `C:\\x` and `\\\\server\\x` on Windows, a leading `/` elsewhere. A leading
    `/` is drive-relative on Windows and `C:\\x` is relative on POSIX, so neither style is accepted on the other."""
    if os.name == "nt":
        return len(value) > 2 and value[1] == ":" and value[2] in "\\/" or value.startswith("\\\\")
    return value.startswith("/")


def validate_options(config: EngineConfig, advertised: Sequence[str]) -> List[str]:
    """Every reason the effective option list is not a trustworthy NNUE (or Classical) configuration."""
    reasons: List[str] = []
    seen: Dict[str, str] = {}
    for name, value in config.options:
        folded = name.casefold()
        if folded in seen:
            same = "the same value" if seen[folded] == value else "a different value"
            reasons.append(f"option {name} is given more than once (second time with {same})")
        seen[folded] = value
    advertised_exact = set(advertised)
    advertised_folded = {a.casefold(): a for a in advertised}
    for name, _ in config.options:
        if name in advertised_exact:
            continue
        if name.casefold() in advertised_folded:
            reasons.append(f"option {name} differs only in case from the advertised {advertised_folded[name.casefold()]}")
        else:
            reasons.append(f"option {name} is not advertised by the engine (misspelled, or a JAR that predates it)")
    eval_type, eval_file, strict = config.option("EvalType"), config.option("EvalFile"), config.option("StrictNnue")
    if eval_type == "NNUE" and "StrictNnue" not in advertised_exact:
        reasons.append("the engine does not advertise StrictNnue: a JAR that predates strict mode would fall back silently")
    if eval_type is not None and eval_type not in ("Classical", "NNUE"):
        reasons.append(f"EvalType value {eval_type!r} must be exactly Classical or NNUE")
    if eval_type == "NNUE":
        if not eval_file:
            reasons.append("EvalType=NNUE without an EvalFile")
        elif not _absolute_on_this_host(eval_file):
            reasons.append("EvalFile must be an absolute path so the match engine reads the file that was verified")
        if strict != "true":
            reasons.append("EvalType=NNUE without StrictNnue=true")
    elif eval_file:
        reasons.append("EvalFile is set but EvalType is not NNUE: the network would never be used")
    return reasons


def _redact(value: str) -> str:
    """An absolute path, or `name=<absolute path>`, reduced to its file name, so a record never carries a
    local directory layout."""
    key, separator, rest = value.partition("=")
    if separator and _looks_absolute(rest):
        return f"{key}={rest.replace(chr(92), '/').rsplit('/', 1)[-1]}"
    if _looks_absolute(value):
        return value.replace(chr(92), "/").rsplit("/", 1)[-1]
    return value


def _hash(path: Path) -> str:
    try:
        return file_sha256(path)
    except OSError as error:
        raise PreflightError(EXIT_PIN, [f"cannot read the network file: {error.__class__.__name__}"]) from error


def _read_manifest(path: Path) -> Optional[dict]:
    """The sibling manifest, None when absent. One that exists but cannot be read or is not a JSON object is a
    pin failure, never an unhandled exception and never silently treated as absent."""
    if not path.is_file():
        return None
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise PreflightError(EXIT_PIN, [f"the sibling manifest {path.name} cannot be read: {error.__class__.__name__}"]) from error
    if not isinstance(manifest, dict):
        raise PreflightError(EXIT_PIN, [f"the sibling manifest {path.name} is not a JSON object"])
    return manifest


class _Engine:
    def __init__(self, command: Sequence[str]):
        self.process = subprocess.Popen(list(command), stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=subprocess.DEVNULL, text=True, bufsize=1)
        self.lines: "queue.Queue[Optional[str]]" = queue.Queue()
        self.seen: List[str] = []
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self) -> None:
        assert self.process.stdout is not None
        for line in self.process.stdout:
            self.lines.put(line.rstrip("\r\n"))
        self.lines.put(None)

    def send(self, text: str) -> None:
        try:
            assert self.process.stdin is not None
            self.process.stdin.write(text + "\n")
            self.process.stdin.flush()
        except (BrokenPipeError, OSError):
            pass

    def wait_for(self, expected: str, timeout: float) -> Optional[str]:
        """The first line equal to `expected`, or None on engine exit (EOF) or timeout (raises TimeoutError)."""
        deadline = time.monotonic() + timeout
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError(expected)
            try:
                line = self.lines.get(timeout=remaining)
            except queue.Empty:
                raise TimeoutError(expected)
            if line is None:
                return None
            self.seen.append(line)
            if line == expected:
                return line

    def close(self) -> None:
        try:
            self.send("quit")
            self.process.wait(timeout=2)
        except Exception:
            pass
        if self.process.poll() is None:
            self.process.kill()
            self.process.wait()
        for stream in (self.process.stdin, self.process.stdout):
            try:
                if stream is not None:
                    stream.close()
            except OSError:
                pass


def preflight(config: EngineConfig, pins: Optional[Pins], *, timeout: float = 20.0) -> Dict[str, object]:
    """Verifies one engine configuration end to end. Returns the record on success, raises PreflightError."""
    reasons: List[str] = []
    jar = config.jar
    if jar is None or not Path(jar).is_file():
        raise PreflightError(EXIT_REJECTED, ["the JAR named by -jar does not exist"])
    is_nnue = config.option("EvalType") == "NNUE"
    eval_file = config.option("EvalFile") if is_nnue else None
    if is_nnue and pins is None:
        raise PreflightError(EXIT_PIN, ["a pinned uuid and SHA-256 are required for an NNUE screening run"])
    if is_nnue and eval_file and not _absolute_on_this_host(eval_file):
        raise PreflightError(EXIT_REJECTED, ["EvalFile must be an absolute path so the match engine reads the file that was verified"])
    pin_sha = pins.nnue_sha256.strip().lower() if pins else None
    pin_uuid = pins.uuid.strip().lower() if pins else None
    nnue_sha = None
    if is_nnue and eval_file:
        if not Path(eval_file).is_file():
            raise PreflightError(EXIT_REJECTED, ["the network file named by EvalFile does not exist"])
        nnue_sha = _hash(Path(eval_file))
        if nnue_sha != pin_sha:
            raise PreflightError(EXIT_PIN, [f"network file SHA-256 {nnue_sha} differs from the pin {pin_sha}"])

    engine = _Engine(config.command)
    try:
        engine.send("uci")
        try:
            if engine.wait_for("uciok", timeout) is None:
                raise PreflightError(EXIT_ENGINE_FAILED, ["the engine exited before uciok"])
        except TimeoutError:
            raise PreflightError(EXIT_TIMEOUT, ["no uciok within the time limit"])
        advertised = [m.group(1) for m in (_OPTION_LINE.match(l) for l in engine.seen) if m]
        reasons = validate_options(config, advertised)
        if reasons:
            raise PreflightError(EXIT_REJECTED, reasons)
        for name, value in config.options:
            engine.send(f"setoption name {name} value {value}")
        engine.send("isready")
        try:
            ready = engine.wait_for("readyok", timeout)
        except TimeoutError:
            raise PreflightError(EXIT_TIMEOUT, ["no readyok within the time limit"])
        if ready is None:
            strict = [l for l in engine.seen if l.startswith("info string NNUE strict mode:")]
            raise PreflightError(EXIT_ENGINE_FAILED, strict or ["the engine exited before readyok"])
        reported = next((m.group(1) for m in (_LOADED.match(l) for l in engine.seen) if m), None)
        manifest_state = "not-applicable"
        if is_nnue:
            if reported is None:
                raise PreflightError(EXIT_ENGINE_FAILED, ["readyok arrived without an 'NNUE network loaded' line"])
            if reported.lower() != pin_uuid:
                raise PreflightError(EXIT_PIN, [f"the engine loaded uuid {reported}, the pin is {pin_uuid}"])
            assert eval_file is not None  # validate_options refused NNUE without an EvalFile
            if _hash(Path(eval_file)) != nnue_sha:
                raise PreflightError(EXIT_PIN, ["the network file changed during the handshake"])
            manifest = _read_manifest(Path(eval_file).with_suffix(".json"))
            if manifest is None:
                manifest_state = "unverified-manifest-absent"
            else:
                if str(manifest.get("nnue_sha256", "")).lower() != nnue_sha or str(manifest.get("network_uuid", "")).lower() != reported.lower():
                    raise PreflightError(EXIT_PIN, ["the sibling manifest disagrees with the network file or the loaded uuid"])
                manifest_state = "manifest-verified"
    finally:
        engine.close()

    return {
        "schema": SCHEMA,
        "result": "pass",
        "java": Path(config.cmd).name,
        "jvm_args": [_redact(a) for a in (config.args[: config.args.index("-jar")] if "-jar" in config.args else config.args)],
        "jar": Path(jar).name,
        "jar_sha256": file_sha256(Path(jar)),
        "options": [f"{n}={_redact(v)}" for n, v in config.options],
        "advertised_strict_nnue": True,
        "readyok": True,
        "nnue_file": _redact(eval_file) if eval_file else None,
        "nnue_sha256": nnue_sha,
        "pinned_uuid": pin_uuid,
        "uuid_reported": reported,
        "provenance": manifest_state,
    }


def recheck(record: Mapping[str, object], eval_file: Optional[str]) -> None:
    """Recomputes the network file hash and refuses when it no longer equals the verified one. Called by the
    launcher immediately before the match starts."""
    if record.get("nnue_sha256") is None:
        return
    if not eval_file or not Path(eval_file).is_file() or _hash(Path(eval_file)) != record["nnue_sha256"]:
        raise PreflightError(EXIT_PIN, ["the network file changed since it was verified"])


def parse_args(argv: Optional[Sequence[str]]) -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--jar", required=True)
    p.add_argument("--eval-file", required=True)
    p.add_argument("--expected-uuid", required=True)
    p.add_argument("--expected-nnue-sha256", required=True)
    p.add_argument("--java", default="java")
    p.add_argument("--jvm-arg", action="append", default=[])
    p.add_argument("--option", action="append", default=[], metavar="NAME=VALUE")
    p.add_argument("--timeout", type=float, default=20.0)
    p.add_argument("--output", default="")
    return p.parse_args(argv)


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = parse_args(argv)
    options = [("EvalType", "NNUE"), ("EvalFile", os.path.abspath(args.eval_file)), ("StrictNnue", "true")]
    options += [tuple(o.partition("=")[::2]) for o in args.option]
    config = EngineConfig("NNUE", args.java, (*args.jvm_arg, "-jar", os.path.abspath(args.jar)), tuple(options))
    try:
        record = preflight(config, Pins(args.expected_uuid, args.expected_nnue_sha256), timeout=args.timeout)
    except PreflightError as error:
        print(f"preflight failed ({error.code}): {error}", file=sys.stderr)
        return error.code
    text = json.dumps(record, indent=2, sort_keys=True) + "\n"
    if args.output:
        Path(args.output).write_text(text, encoding="utf-8")
    print(text, end="")
    return 0


if __name__ == "__main__":
    sys.exit(main())
