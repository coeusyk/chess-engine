#!/usr/bin/env python3
"""Linux/WSL launcher for the same cutechess-cli SPRT that tools/sprt.ps1 runs.

Why it exists: sprt.ps1 builds `bin\\java.exe`, documents `cutechess-cli.exe` and carries Windows
PowerShell 5.1 workarounds, so it cannot run under WSL as it stands. The 2026-11-23 addendum to
docs/engineering/investigations/2026-07-17-sprt-throughput-investigation.md proposed a separate
small launcher that emits the same cutechess argument manifest instead of editing the Windows one.
This is that launcher. sprt.ps1 and every preregistration that references it are unchanged.

What is the same as sprt.ps1 (and tested against it, see tools/tests/test_sprt_linux.py): the
cutechess argument array token for token (engine groups, `-each tc`, `-games`, `-repeat`,
`-recover`, resign/draw adjudication, the `-sprt` group, concurrency, `-ratinginterval`, `-pgnout`,
the `-openings` group), the `{Executable, Arguments}` argument manifest, `--prepare-only`,
`--expected-argument-manifest-path` (refuse to launch if the arguments differ), the log/PGN path
rules, and the exit-code handling. Numbers are formatted the way PowerShell 7 prints them.

Scope of the equivalence: literal paths (sprt.ps1's `Resolve-Path` also expands wildcards in `-New`
and `-Old`; this launcher does not, and fails with "JAR not found"), and numbers with |x| < 1e15.

What differs, on purpose:
- It fails closed on a missing opening book even when no book was named: no `tools/noob_3moves.epd`
  and no `--openings-file` is an error unless `--allow-startpos` is given. sprt.ps1 silently
  plays from the initial position in that case (tracked as a deferred item on #273).
- `--min-games` is accepted and only changes the default game cap, exactly like sprt.ps1, where it
  is not enforced as a minimum either.
- The informational White/Black colour-balance printout is not reproduced. It never affects
  stopping.
- It can write a provenance record (`--provenance-path`), see `build_provenance`. The record says
  `authoritative: false`. WSL has not been qualified for authoritative SPRTs; results from this
  launcher are exploratory until the staged qualification in the addendum has been done and the
  policy (CLAUDE.md section 6) has been changed by its owner. Nothing here changes that policy.

It launches no games unless it is run without `--prepare-only`. Importing it has no effect;
`main()` runs only when this file is executed.
"""

from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import math
import os
import platform
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any, Dict, List, Mapping, Optional, Sequence

SCRIPT_DIR = Path(__file__).resolve().parent
DEFAULT_BOOK = SCRIPT_DIR / "noob_3moves.epd"
PROVENANCE_SCHEMA = "sprt-linux-provenance/1"
AUTHORITATIVE_REASON = (
    "The Linux/WSL launcher is not qualified for authoritative SPRTs. Qualification is the staged "
    "protocol in the 2026-11-23 addendum to docs/engineering/investigations/"
    "2026-07-17-sprt-throughput-investigation.md. Use native Windows for any deciding result."
)


class LauncherError(Exception):
    """A refusal that should end the run with a message and a non-zero exit status."""


def ps_number(value: float) -> str:
    """A double as PowerShell 7 prints it in a string: integral values without a decimal point,
    otherwise the shortest round-trip form with an upper-case exponent. Supported range |x| < 1e15
    (see below). Windows PowerShell 5.1 prints 15 significant digits instead; the two agree for the
    frozen alpha = beta = 0.05."""
    value = float(value)
    if not math.isfinite(value):
        raise LauncherError(f"not a finite number: {value}")
    if abs(value) >= 1e15:
        # .NET switches to exponent form at 1e15, Python's repr at 1e16, so values in between would
        # print differently. No SPRT parameter is anywhere near this large; refuse instead of diverging.
        raise LauncherError(f"number outside the supported range (|x| < 1e15): {value}")
    if value == int(value):
        return str(int(value))
    return repr(value).replace("e", "E")


def java_command(env: Mapping[str, str]) -> str:
    """Same precedence as sprt.ps1 (JAVA, then JAVA_HOME, then `java` on PATH) with the Linux layout."""
    if env.get("JAVA"):
        return os.path.join(env["JAVA"], "bin", "java")
    if env.get("JAVA_HOME"):
        return os.path.join(env["JAVA_HOME"], "bin", "java")
    return "java"


def max_games_for(max_games: int, min_games: int) -> int:
    """sprt.ps1: MaxGames if given, else max(MinGames, 20000) if MinGames is given, else 20000."""
    if max_games > 0:
        return max_games
    if min_games > 0:
        return max(min_games, 20000)
    return 20000


def build_arguments(java: str, new_jar: str, old_jar: str, tc: str, max_games: int, elo0: float,
                    elo1: float, alpha: float, beta: float, concurrency: int, engine_threads: int,
                    new_options: Sequence[str], old_options: Sequence[str], pgn_out: str,
                    openings_file: Optional[str], opening_order: str) -> List[str]:
    """The cutechess argument array, in the order sprt.ps1 builds it."""
    def engine(name: str, jar: str, options: Sequence[str]) -> List[str]:
        return (["-engine", f"name={name}", f"cmd={java}", "arg=--add-modules",
                 "arg=jdk.incubator.vector", "arg=-jar", f"arg={jar}", "proto=uci",
                 f"option.Threads={engine_threads}"] + [f"option.{o}" for o in options])

    args = engine("NEW", new_jar, new_options) + engine("OLD", old_jar, old_options)
    args += [
        "-each", f"tc={tc}",
        "-games", str(max_games),
        "-repeat",
        "-recover",
        "-resign", "movecount=5", "score=400",
        "-draw", "movenumber=40", "movecount=8", "score=10",
        "-sprt", f"elo0={ps_number(elo0)}", f"elo1={ps_number(elo1)}",
        f"alpha={ps_number(alpha)}", f"beta={ps_number(beta)}",
        "-concurrency", str(concurrency),
        "-ratinginterval", "10",
        "-pgnout", pgn_out,
    ]
    if openings_file:
        args += ["-openings", f"file={openings_file}", "format=epd", f"order={opening_order}", "plies=4"]
    return args


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with Path(path).open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def arguments_sha256(arguments: Sequence[str]) -> str:
    """Identity of the exact argument array: SHA-256 of its compact JSON."""
    return hashlib.sha256(json.dumps(list(arguments), separators=(",", ":")).encode("utf-8")).hexdigest()


def first_line_of(command: Sequence[str]) -> Optional[str]:
    """First line a version command prints (java writes to stderr), or None if it cannot run."""
    try:
        done = subprocess.run(list(command), capture_output=True, text=True, timeout=30)
    except (OSError, subprocess.SubprocessError):
        return None
    lines = (done.stdout + done.stderr).strip().splitlines()
    return lines[0].strip() if lines else None


def read_text(path: str) -> Optional[str]:
    try:
        return Path(path).read_text()
    except OSError:
        return None


def is_wsl(read=read_text, env: Optional[Mapping[str, str]] = None) -> bool:
    env = os.environ if env is None else env
    version = read("/proc/version") or ""
    return "microsoft" in version.lower() or bool(env.get("WSL_DISTRO_NAME"))


def collect_host(read=read_text) -> Dict[str, Any]:
    """Read-only host and VM facts the 2026-11-23 Stage 0 record asks for. Raw text where a parse
    would hide something (pressure files), None where a file does not exist."""
    meminfo = read("/proc/meminfo") or ""

    def meminfo_kb(key: str) -> Optional[int]:
        for line in meminfo.splitlines():
            if line.startswith(key + ":"):
                return int(line.split()[1])
        return None

    return {
        "cpu_count": os.cpu_count(),
        "mem_total_kb": meminfo_kb("MemTotal"),
        "swap_total_kb": meminfo_kb("SwapTotal"),
        "loadavg": (read("/proc/loadavg") or "").strip() or None,
        "pressure_cpu": (read("/proc/pressure/cpu") or "").strip() or None,
        "pressure_memory": (read("/proc/pressure/memory") or "").strip() or None,
    }


def build_provenance(*, launcher_path: Path, new_jar: Path, old_jar: Path, opening_book: Optional[Path],
                     parameters: Dict[str, Any], arguments: Sequence[str], java_line: Optional[str],
                     cutechess_line: Optional[str], host: Dict[str, Any], wsl: bool,
                     launcher_git_commit: Optional[str], started_utc: str) -> Dict[str, Any]:
    """The provenance record. File names and hashes only: no absolute path is recorded (the
    argument manifest, which sprt.ps1 also writes, necessarily has them)."""
    return {
        "schema": PROVENANCE_SCHEMA,
        "authoritative": False,
        "authoritative_reason": AUTHORITATIVE_REASON,
        "launcher": {"file": launcher_path.name, "sha256": file_sha256(launcher_path),
                     "git_commit": launcher_git_commit},
        "platform": {"system": platform.system(), "release": platform.release(),
                     "machine": platform.machine(), "python": platform.python_version(), "wsl": wsl,
                     "wsl_distro": os.environ.get("WSL_DISTRO_NAME")},
        "java_version_line": java_line,
        "cutechess_version_line": cutechess_line,
        "engines": {"new": {"file": new_jar.name, "sha256": file_sha256(new_jar)},
                    "old": {"file": old_jar.name, "sha256": file_sha256(old_jar)}},
        "opening_book": None if opening_book is None else
        {"file": opening_book.name, "sha256": file_sha256(opening_book)},
        "parameters": parameters,
        "argument_array_sha256": arguments_sha256(arguments),
        "host": host,
        "started_utc": started_utc,
        "finished_utc": None,
        "exit_code": None,
    }


def redact_option(option: str) -> str:
    """`Name=Value` with an absolute-path Value replaced by its file name, so the provenance record
    never carries a local directory layout (an `EvalFile` option naming a local network file, for example)."""
    name, separator, value = option.partition("=")
    looks_absolute = value.startswith(("/", "\\\\")) or (len(value) > 2 and value[1] == ":" and value[2] in "\\/")
    if separator and looks_absolute:
        return f"{name}=<path omitted: {value.replace(chr(92), '/').rsplit('/', 1)[-1]}>"
    return option


def write_json(path: Path, data: Any) -> None:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_name(path.name + ".tmp")
    temp.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    os.replace(temp, path)


def utc_now() -> str:
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def resolve_book(openings_file: Optional[str], allow_startpos: bool) -> Optional[str]:
    """Explicit book must exist. With none named, use tools/noob_3moves.epd if present. With
    neither, refuse unless `allow_startpos` (sprt.ps1 would silently play from the start position)."""
    # Same order as sprt.ps1: an empty value is replaced by the default book when that exists, and only
    # then is an explicitly named book required to exist.
    book = openings_file
    if book in (None, "") and DEFAULT_BOOK.is_file():
        book = str(DEFAULT_BOOK)
    if openings_file is not None:
        if not book or not Path(book).is_file():
            raise LauncherError(f"Opening book not found: {openings_file}")
        return book
    if book:
        return book
    if allow_startpos:
        return None
    raise LauncherError("No opening book: tools/noob_3moves.epd is absent and --openings-file was not "
                        "given. Pass --allow-startpos to play every game from the initial position.")


def parse_args(argv: Optional[Sequence[str]]) -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Run a cutechess-cli SPRT between two engine JARs on Linux/WSL.")
    p.add_argument("--new", required=True, help="Candidate engine JAR.")
    p.add_argument("--old", required=True, help="Baseline engine JAR.")
    p.add_argument("--tag", default="sprt")
    p.add_argument("--bonferroni-m", type=int, default=1)
    p.add_argument("--alpha", type=float, default=0.05)
    p.add_argument("--beta", type=float, default=0.05)
    p.add_argument("--elo0", type=float, default=0.0)
    p.add_argument("--elo1", type=float, default=10.0)
    p.add_argument("--tc", default="60+0.6")
    p.add_argument("--concurrency", type=int, default=2)
    p.add_argument("--engine-threads", type=int, default=1)
    p.add_argument("--min-games", type=int, default=0)
    p.add_argument("--max-games", type=int, default=0)
    p.add_argument("--openings-file", default=None)
    p.add_argument("--allow-startpos", action="store_true")
    p.add_argument("--opening-order", choices=("random", "sequential"), default="random")
    p.add_argument("--new-option", action="append", default=[], metavar="NAME=VALUE")
    p.add_argument("--old-option", action="append", default=[], metavar="NAME=VALUE")
    p.add_argument("--log-path", default="")
    p.add_argument("--pgn-path", default="")
    p.add_argument("--prepare-only", action="store_true")
    p.add_argument("--argument-manifest-path", default="")
    p.add_argument("--expected-argument-manifest-path", default="")
    p.add_argument("--provenance-path", default="")
    return p.parse_args(argv)


def locate_cutechess(env: Mapping[str, str]) -> str:
    candidate = env.get("CUTECHESS") or shutil.which("cutechess-cli")
    if not candidate or not os.path.exists(candidate):
        raise LauncherError("cutechess-cli not found. Set CUTECHESS or put cutechess-cli on PATH.")
    return candidate


def run(argv: Optional[Sequence[str]] = None, env: Optional[Mapping[str, str]] = None) -> int:
    args = parse_args(argv)
    env = dict(os.environ if env is None else env)

    cutechess = locate_cutechess(env)
    java = java_command(env)
    for label, jar in (("New", args.new), ("Old", args.old)):
        if not Path(jar).is_file():
            raise LauncherError(f"{label} engine JAR not found: {jar}")
    new_jar, old_jar = os.path.abspath(args.new), os.path.abspath(args.old)

    alpha, beta = args.alpha / args.bonferroni_m, args.beta / args.bonferroni_m
    book = resolve_book(args.openings_file, args.allow_startpos)

    if bool(args.log_path) != bool(args.pgn_path):
        raise LauncherError("--log-path and --pgn-path must be supplied together.")
    if args.log_path:
        pgn_out, log_out = os.path.abspath(args.pgn_path), os.path.abspath(args.log_path)
        if pgn_out == log_out:
            raise LauncherError("--log-path and --pgn-path must be different files.")
        for output in (pgn_out, log_out):
            Path(output).parent.mkdir(parents=True, exist_ok=True)
    else:
        results = SCRIPT_DIR / "results"
        results.mkdir(exist_ok=True)
        stamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
        pgn_out, log_out = str(results / f"sprt_{args.tag}_{stamp}.pgn"), str(results / f"sprt_{args.tag}_{stamp}.log")

    max_games = max_games_for(args.max_games, args.min_games)
    cc_args = build_arguments(java, new_jar, old_jar, args.tc, max_games, args.elo0, args.elo1, alpha, beta,
                              args.concurrency, args.engine_threads, args.new_option, args.old_option,
                              pgn_out, book, args.opening_order)

    if args.prepare_only and not args.argument_manifest_path:
        raise LauncherError("--prepare-only requires --argument-manifest-path.")
    manifest = {"Executable": os.path.abspath(cutechess), "Arguments": cc_args}
    if args.expected_argument_manifest_path:
        expected = json.loads(Path(args.expected_argument_manifest_path).read_text(encoding="utf-8-sig"))
        if expected.get("Executable") != manifest["Executable"] or list(expected.get("Arguments", [])) != cc_args:
            raise LauncherError("Actual Cute Chess arguments differ from the prepared manifest.")
    if args.argument_manifest_path:
        write_json(Path(args.argument_manifest_path), manifest)

    wsl = is_wsl(env=env)
    print(f"SPRT: new vs old  ELO0={ps_number(args.elo0)} ELO1={ps_number(args.elo1)} alpha={ps_number(alpha)} "
          f"beta={ps_number(beta)}  TC={args.tc}  concurrency={args.concurrency}  threads/engine={args.engine_threads}"
          f"  maxGames={max_games}")
    print(f"NEW : {new_jar}\nOLD : {old_jar}\nPGN : {pgn_out}\nLOG : {log_out}")
    print(f"Opening book: {book if book else '(none)'}")
    if wsl:
        print("WSL detected: this launcher is not qualified for authoritative SPRTs; treat the result as exploratory.")
    print()

    provenance = None
    if args.provenance_path:
        parameters = {"tc": args.tc, "concurrency": args.concurrency, "engine_threads": args.engine_threads,
                      "elo0": args.elo0, "elo1": args.elo1, "alpha": alpha, "beta": beta, "max_games": max_games,
                      "opening_order": args.opening_order,
                      "new_options": [redact_option(o) for o in args.new_option],
                      "old_options": [redact_option(o) for o in args.old_option]}
        provenance = build_provenance(
            launcher_path=Path(__file__).resolve(), new_jar=Path(new_jar), old_jar=Path(old_jar),
            opening_book=None if book is None else Path(book), parameters=parameters, arguments=cc_args,
            java_line=first_line_of([java, "-version"]), cutechess_line=first_line_of([cutechess, "--version"]),
            host=collect_host(), wsl=wsl,
            launcher_git_commit=first_line_of(["git", "-C", str(SCRIPT_DIR), "rev-parse", "HEAD"]),
            started_utc=utc_now())
        write_json(Path(args.provenance_path), provenance)

    if args.prepare_only:
        return 0

    exit_code = stream_cutechess(cutechess, cc_args, log_out)
    if provenance is not None:
        provenance["finished_utc"] = utc_now()
        provenance["exit_code"] = exit_code
        write_json(Path(args.provenance_path), provenance)
    if exit_code != 0:
        raise LauncherError(f"Cute Chess exited with code {exit_code}. Evidence retained at {log_out}")
    print(f"\nSPRT complete. Log: {log_out}")
    return 0


def stream_cutechess(cutechess: str, cc_args: Sequence[str], log_out: str) -> int:
    """Runs cutechess-cli, echoing every line to the console and the log, flushed as it arrives."""
    with open(log_out, "w", encoding="utf-8", buffering=1) as log:
        process = subprocess.Popen([cutechess, *cc_args], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   text=True, bufsize=1)
        assert process.stdout is not None
        for line in process.stdout:
            line = line.rstrip("\n")
            print(line, flush=True)
            log.write(line + "\n")
            log.flush()
        return process.wait()


def main(argv: Optional[Sequence[str]] = None) -> int:
    try:
        return run(argv)
    except LauncherError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
