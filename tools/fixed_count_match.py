#!/usr/bin/env python3
"""A fixed-count cutechess-cli match between two engine JARs, with no SPRT stopping rule (#319, for #260).

Why it exists: sprt.ps1 and sprt_linux.py always pass `-sprt`, and `-sprt` with any alpha and beta still stops a match
when a bound is crossed, so the game count would depend on the results. The #260 characterization is a fixed
count of 4,000 games (2,000 opening pairs) analysed offline as a pooled pair estimate, with a recorded opening seed.

It is Python so it runs unchanged on native Windows (where deciding runs belong, CLAUDE.md section 6) and on Linux. It
reuses sprt_linux.py for the argument construction, the provenance record, the hashing and the process streaming, so
the arguments are exactly what sprt.ps1 builds minus the `-sprt` group, plus `-srand`. The historical sprt.ps1, its
hash and every frozen run folder are not touched.

Differences from the SPRT launchers, on purpose:
- `--games` and `--seed` are required. The seed is part of the argument array and of the provenance.
- `--prepare-only` writes the argument manifest and a provenance record (file names and SHA-256 hashes of the two
  engine JARs, the opening book, this launcher and the Cute Chess executable, plus the argument array hash, the game
  count and the seed) and launches nothing.
- `--expected-provenance-path` refuses to launch unless the arguments AND those hashes equal the prepared record,
  so a swapped JAR, book, launcher or Cute Chess build is caught, not only a changed argument.
- After the run, a non-zero Cute Chess exit fails on its own. Otherwise completion is decided from the log and PGN by
  tools/match_validation.py, and only COMPLETE_CAP counts as success (`Fixed-count run complete`).

It launches no games unless it is run without `--prepare-only`.
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import os
import platform
import sys
from pathlib import Path
from typing import Any, Dict, List, Mapping, Optional, Sequence

SCRIPT_DIR = Path(__file__).resolve().parent
SCHEMA = "fixed-count-provenance/1"


def _load(name: str):
    spec = importlib.util.spec_from_file_location(name, SCRIPT_DIR / f"{name}.py")
    if spec is None or spec.loader is None:
        raise SystemExit(f"tools/{name}.py is missing")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


sprt_linux = _load("sprt_linux")
LauncherError = sprt_linux.LauncherError


def build_arguments(java: str, new_jar: str, old_jar: str, tc: str, games: int, concurrency: int, engine_threads: int,
                    new_options: Sequence[str], old_options: Sequence[str], pgn_out: str, openings_file: Optional[str],
                    opening_order: str, seed: int) -> List[str]:
    """The sprt.ps1 argument array with the `-sprt` group removed, `-games` set to the fixed count and `-srand` last."""
    args = sprt_linux.build_arguments(java, new_jar, old_jar, tc, games, 0.0, 0.0, 0.05, 0.05, concurrency, engine_threads,
                                      new_options, old_options, pgn_out, openings_file, opening_order, seed)
    start = args.index("-sprt")
    del args[start:start + 5]  # "-sprt" and its elo0, elo1, alpha, beta
    return args


def build_record(*, args: argparse.Namespace, cutechess: str, java: str, new_jar: str, old_jar: str, book: Optional[str],
                 cc_args: Sequence[str], wsl: bool) -> Dict[str, Any]:
    parameters = {"tc": args.tc, "games": args.games, "seed": args.seed, "concurrency": args.concurrency,
                  "engine_threads": args.engine_threads, "opening_order": args.opening_order,
                  "new_options": [sprt_linux.redact_option(o) for o in args.new_option],
                  "old_options": [sprt_linux.redact_option(o) for o in args.old_option]}
    record = sprt_linux.build_provenance(
        launcher_path=Path(__file__).resolve(), new_jar=Path(new_jar), old_jar=Path(old_jar),
        opening_book=None if book is None else Path(book), parameters=parameters, arguments=cc_args,
        java_line=sprt_linux.first_line_of([java, "-version"]),
        cutechess_line=sprt_linux.first_line_of([cutechess, "--version"]), host=sprt_linux.collect_host(), wsl=wsl,
        launcher_git_commit=sprt_linux.first_line_of(["git", "-C", str(SCRIPT_DIR), "rev-parse", "HEAD"]),
        started_utc=sprt_linux.utc_now())
    native_windows = platform.system() == "Windows" and not wsl
    record["schema"] = SCHEMA
    record["authoritative"] = native_windows
    record["authoritative_reason"] = ("Native Windows, the platform CLAUDE.md section 6 requires for deciding runs." if native_windows
                                      else sprt_linux.AUTHORITATIVE_REASON)
    record["cutechess"] = {"file": Path(cutechess).name, "sha256": sprt_linux.file_sha256(Path(cutechess))}
    return record


def check_expected(expected: Mapping[str, Any], actual: Mapping[str, Any]) -> None:
    """Refuse to launch unless every relevant hash and the argument array equal the prepared record."""
    def get(record, *path):
        for key in path:
            record = record.get(key) if isinstance(record, Mapping) else None
        return record

    checks = [("argument array", ("argument_array_sha256",)), ("candidate JAR", ("engines", "new", "sha256")),
              ("baseline JAR", ("engines", "old", "sha256")), ("opening book", ("opening_book", "sha256")),
              ("launcher", ("launcher", "sha256")), ("Cute Chess executable", ("cutechess", "sha256")),
              ("game count", ("parameters", "games")), ("seed", ("parameters", "seed")),
              ("time control", ("parameters", "tc")), ("concurrency", ("parameters", "concurrency"))]
    differences = [label for label, path in checks if get(expected, *path) != get(actual, *path)]
    if get(expected, "schema") != SCHEMA:
        differences.insert(0, "record schema")
    if differences:
        raise LauncherError("The run differs from the prepared record: " + ", ".join(differences) + ".")


def parse_args(argv: Optional[Sequence[str]]) -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Run a fixed-count cutechess-cli match (no SPRT) between two engine JARs.")
    p.add_argument("--new", required=True, help="Candidate engine JAR.")
    p.add_argument("--old", required=True, help="Baseline engine JAR.")
    p.add_argument("--games", type=int, required=True, help="Total games, an even number (pairs of openings).")
    p.add_argument("--seed", type=int, required=True, help="Opening seed for -srand, recorded in the provenance.")
    p.add_argument("--tc", default="5+0.05")
    p.add_argument("--concurrency", type=int, default=6)
    p.add_argument("--engine-threads", type=int, default=1)
    p.add_argument("--openings-file", default=None)
    p.add_argument("--allow-startpos", action="store_true")
    p.add_argument("--opening-order", choices=("random", "sequential"), default="random")
    p.add_argument("--new-option", action="append", default=[], metavar="NAME=VALUE")
    p.add_argument("--old-option", action="append", default=[], metavar="NAME=VALUE")
    p.add_argument("--log-path", required=True)
    p.add_argument("--pgn-path", required=True)
    p.add_argument("--prepare-only", action="store_true")
    p.add_argument("--argument-manifest-path", default="")
    p.add_argument("--provenance-path", required=True)
    p.add_argument("--expected-argument-manifest-path", default="")
    p.add_argument("--expected-provenance-path", default="")
    return p.parse_args(argv)


def run(argv: Optional[Sequence[str]] = None, env: Optional[Mapping[str, str]] = None) -> int:
    args = parse_args(argv)
    env = dict(os.environ if env is None else env)
    if args.games <= 0 or args.games % 2:
        raise LauncherError("--games must be a positive even number: each opening is played twice (-repeat).")
    if args.seed < 0:
        raise LauncherError("--seed must be a non-negative integer.")
    if args.prepare_only and not args.argument_manifest_path:
        raise LauncherError("--prepare-only requires --argument-manifest-path.")

    cutechess = sprt_linux.locate_cutechess(env)
    java = sprt_linux.java_command(env)
    for label, jar in (("New", args.new), ("Old", args.old)):
        if not Path(jar).is_file():
            raise LauncherError(f"{label} engine JAR not found: {jar}")
    new_jar, old_jar = os.path.abspath(args.new), os.path.abspath(args.old)
    book = sprt_linux.resolve_book(args.openings_file, args.allow_startpos)
    pgn_out, log_out = os.path.abspath(args.pgn_path), os.path.abspath(args.log_path)
    if pgn_out == log_out:
        raise LauncherError("--log-path and --pgn-path must be different files.")
    for output in (pgn_out, log_out):
        Path(output).parent.mkdir(parents=True, exist_ok=True)

    cc_args = build_arguments(java, new_jar, old_jar, args.tc, args.games, args.concurrency, args.engine_threads,
                              args.new_option, args.old_option, pgn_out, book, args.opening_order, args.seed)
    manifest = {"Executable": os.path.abspath(cutechess), "Arguments": cc_args}
    if args.expected_argument_manifest_path:
        expected = json.loads(Path(args.expected_argument_manifest_path).read_text(encoding="utf-8-sig"))
        if expected.get("Executable") != manifest["Executable"] or list(expected.get("Arguments", [])) != cc_args:
            raise LauncherError("Actual Cute Chess arguments differ from the prepared manifest.")
    record = build_record(args=args, cutechess=cutechess, java=java, new_jar=new_jar, old_jar=old_jar, book=book,
                          cc_args=cc_args, wsl=sprt_linux.is_wsl(env=env))
    if args.expected_provenance_path:
        check_expected(json.loads(Path(args.expected_provenance_path).read_text(encoding="utf-8-sig")), record)
    if args.argument_manifest_path:
        sprt_linux.write_json(Path(args.argument_manifest_path), manifest)
    sprt_linux.write_json(Path(args.provenance_path), record)

    print(f"Fixed count: new vs old  games={args.games}  seed={args.seed}  TC={args.tc}  concurrency={args.concurrency}"
          f"  threads/engine={args.engine_threads}")
    print(f"NEW : {new_jar}\nOLD : {old_jar}\nPGN : {pgn_out}\nLOG : {log_out}")
    print(f"Opening book: {book if book else '(none)'}\n")
    if args.prepare_only:
        return 0

    exit_code = sprt_linux.stream_cutechess(cutechess, cc_args, log_out)
    record["finished_utc"] = sprt_linux.utc_now()
    record["exit_code"] = exit_code
    sprt_linux.write_json(Path(args.provenance_path), record)
    # A non-zero exit is a failure on its own, whatever the artifacts say.
    if exit_code != 0:
        raise LauncherError(f"Cute Chess exited with code {exit_code}. Evidence retained at {log_out}")
    verdict = sprt_linux.load_match_validation().classify_files(log_out, pgn_out, expected_games=args.games, sprt=False)
    record["completion"] = {"state": verdict.state, "games": verdict.games, "abandoned": verdict.abandoned,
                            "unterminated": verdict.unterminated, "complete_pairs": verdict.complete_pairs,
                            "reasons": verdict.reasons, "notes": verdict.notes}
    sprt_linux.write_json(Path(args.provenance_path), record)
    print(f"\nCompletion: {verdict.describe()}")
    if verdict.state != "COMPLETE_CAP":
        raise LauncherError(f"The run did not complete ({verdict.state}). Evidence retained at {log_out}")
    print(f"\nFixed-count run complete. Log: {log_out}")
    return 0


def main(argv: Optional[Sequence[str]] = None) -> int:
    try:
        return run(argv)
    except LauncherError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
