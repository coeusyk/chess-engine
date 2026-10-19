"""Independent audit of an archived release-SPRT smoke attempt. Starts no engine, no Cute Chess and no game.

    python tools/release-sprt-audit.py <smoke-dir> [--out-root <dir>]

The runner's own SMOKE_PASSED label is produced by the tooling that ran the match, so production must not trust it alone.
This program re-derives the verdict from the archived raw files against explicit acceptance criteria, with its own copy of
the frozen identities (tools/tests/test_release_sprt_audit.py pins that copy to release-strength-sprt-common.ps1). The
runner's summary.json is read only as a corroboration: a runner that disagrees with the audit blocks approval, but a runner
that agrees never grants it.

It never writes into the smoke directory. It writes a new directory <out-root>/audit-<UTC>-<random>/ holding
smoke-audit.json and SHA256SUMS. The record names the exact evidence manifest (SHA-256 of the smoke SHA256SUMS) and the
runner commit and script hashes it approved. The production gate in release-strength-sprt-common.ps1
(Find-ReleaseSprtApprovedSmoke) re-checks all of that against the live files, so an unaudited or merely SMOKE_PASSED
attempt cannot authorize production. Exit code 0 means AUDIT_APPROVED, 1 means AUDIT_REJECTED (the record is still written).
"""
import argparse
import hashlib
import importlib.util
import json
import re
import secrets
import sys
from datetime import datetime, timezone
from pathlib import Path, PureWindowsPath

HERE = Path(__file__).resolve().parent
SCHEMA = 1
TOOL_NAME = "release-sprt-audit.py"
ANALYZER_NAME = "release-sprt-analyze.py"

# Order is the order of the record. release-strength-sprt-common.ps1 (Get-ReleaseSprtAuditCriteria) must list the same ids.
CRITERIA = ["evidence-manifest", "attempt-completed", "frozen-identities", "script-identity", "cutechess-arguments",
            "host-and-tools", "games-complete", "opening-pairs", "results-reconcile", "no-faults", "smoke-isolation",
            "runner-corroboration"]

# Must equal the names hashed into identity.json by release-strength-sprt.ps1 (Get-ReleaseSprtScriptNames).
SCRIPT_NAMES = ["release-strength-sprt.ps1", "release-strength-sprt-common.ps1", "release-strength-sprt-validation.ps1",
                "phase21-stage6-common.ps1", "phase21-stage6.ps1", "sprt.ps1", "release-sprt-analyze.py",
                "release-sprt-openings.py", "release-sprt-audit.py"]

SHARED_OPTIONS = ["Hash=16", "PawnHashSize=1", "MultiPV=1", "Contempt=0", "OwnBook=false", "SyzygyOnline=false", "SyzygyPath="]
FROZEN = {
    "candidate_jar": ("candidate-v0.6.0.jar", "e16163b8fe6f8278161db0d9220883c5d49a4814df0d81f7a6cad901c07cf4e7"),
    "baseline_jar": ("baseline-v0.5.7.jar", "bc41c91b0d3b4c20f6a334ff3f0cc2cf5c04e6820b817b1cfd46e62a640663d3"),
    "smoke_openings": ("openings-smoke-v1.epd", "51ed4dc523be6e29c28c6693ad6022478b142465b699df3397c53537cd6ca195"),
    "production_openings_sha256": "32a2dc9b6ee1fee14a44ebb915441a2401e54276f75424804fe70f7f48354e1d",
    "preregistration_sha256": "190326d037a7588258798d16c60d473c0c3c72e41a94aaa157a7c1b88072555c",
    "smoke_games": 20,
    "new_options": SHARED_OPTIONS[:2] + ["EvalType=Classical"] + SHARED_OPTIONS[2:],
    "old_options": list(SHARED_OPTIONS),
    "host": {"ComputerName": "RENEGADE", "CpuPrefix": "AMD Ryzen 7 7700X", "LogicalProcessors": 16, "JavaVersion": "21.0.10",
             "JavaRuntime": "21.0.10+7-LTS", "CutechessVersion": "1.5.1", "IdleCpuMeanLimit": 5.0,
             "PowerPlanGuid": "381b4222-f694-41f0-9685-ff5bb260df2e"},
}

FAULT = re.compile(r"\b(crash(?:ed)?|disconnect(?:ed)?|forfeit|timeout|timed? out|illegal move|connection stalls?|stalled connection|"
                   r"wins on time|loses on time|lost on time|cannot start|could not start|failed to start|unknown option|"
                   r"unrecognized option|unsupported option|invalid option|does not support option|doesn't support option)\b|"
                   r"^\s*(Error:|Exception in thread)", re.IGNORECASE)
BAD_TERMINATION = re.compile(r"forfeit|abandon|disconnect|stall|illegal|unterminated|crash|time", re.IGNORECASE)
FINISHED = re.compile(r"Finished game (\d+) \((\w+) vs (\w+)\): (\S+) \{([^}]*)\}")
STARTED = re.compile(r"Started game (\d+) of (\d+) \((\w+) vs (\w+)\)")


def load_analyzer():
    spec = importlib.util.spec_from_file_location("release_sprt_analyze", HERE / ANALYZER_NAME)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def sha256_file(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def _reject_constant(name):
    raise ValueError(f"{name} is not a valid number in evidence")


def read_json(path):
    """Strict JSON: Python's parser accepts NaN and Infinity, which would slip past a 'greater than the limit' comparison."""
    return json.loads(Path(path).read_text(encoding="utf-8-sig"), parse_constant=_reject_constant)


def parse_utc(text):
    """ISO-8601 with up to 7 fractional digits (the runner writes 7) and a Z suffix."""
    m = re.fullmatch(r"(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.(\d+))?Z", text)
    if not m:
        raise ValueError(f"not a UTC timestamp: {text!r}")
    return datetime.fromisoformat(m.group(1) + "." + (m.group(2) or "0").ljust(6, "0")[:6]).replace(tzinfo=timezone.utc)


def utc_text(moment):
    return moment.strftime("%Y-%m-%dT%H:%M:%S.") + f"{moment.microsecond:06d}Z"


def manifest_problems(directory):
    """Reasons the directory's SHA256SUMS cannot vouch for its contents. Empty means complete and valid."""
    directory = Path(directory)
    sums = directory / "SHA256SUMS"
    if not sums.is_file():
        return ["SHA256SUMS is missing"]
    problems, listed = [], []
    lines = [l for l in sums.read_text(encoding="ascii", errors="replace").replace("\r", "").split("\n") if l]
    if not lines:
        problems.append("SHA256SUMS is empty")
    for line in lines:
        digest, sep, name = line.partition("  ")
        if not sep or not re.fullmatch(r"[0-9a-f]{64}", digest) or not name or name in (".", "..") or "/" in name or "\\" in name:
            problems.append(f"malformed manifest line: {line[:80]!r}")
            continue
        if name in listed:
            problems.append(f"duplicate manifest entry: {name}")
        listed.append(name)
        target = directory / name
        if not target.is_file():
            problems.append(f"listed file is missing: {name}")
        elif sha256_file(target) != digest:
            problems.append(f"hash mismatch: {name}")
    for entry in sorted(directory.iterdir()):
        if entry.name == "SHA256SUMS":
            continue
        if entry.is_dir():
            problems.append(f"unverifiable subdirectory: {entry.name}")
        elif entry.name not in listed:
            problems.append(f"file not in manifest: {entry.name}")
    return problems


def expected_arguments(java, jars, options, games, pgn, openings):
    """The preregistered smoke command line, rebuilt from the frozen values (not from the runner)."""
    args = []
    for name, jar, opts in (("NEW", jars[0], options[0]), ("OLD", jars[1], options[1])):
        args += ["-engine", f"name={name}", f"cmd={java}", "arg=--add-modules", "arg=jdk.incubator.vector", "arg=-jar",
                 f"arg={jar}", "proto=uci", "option.Threads=1"] + [f"option.{o}" for o in opts]
    args += ["-each", "tc=5+0.05", "-games", str(games), "-repeat", "-recover", "-resign", "movecount=5", "score=400",
             "-draw", "movenumber=40", "movecount=8", "score=10", "-sprt", "elo0=0", "elo1=20", "alpha=0.05", "beta=0.05",
             "-concurrency", "6", "-ratinginterval", "10", "-pgnout", pgn, "-openings", f"file={openings}", "format=epd",
             "order=sequential", "plies=4"]
    return args


class Audit:
    def __init__(self, smoke_dir, frozen, repo_root):
        self.dir = Path(smoke_dir)
        self.frozen = frozen
        self.repo_root = Path(repo_root)
        self.analyzer = load_analyzer()
        self.counts = {}
        self.cache = {}

    def json(self, name):
        if name not in self.cache:
            self.cache[name] = read_json(self.dir / name)
        return self.cache[name]

    def text(self, name):
        return (self.dir / name).read_text(encoding="utf-8", errors="replace")

    # Each check returns (passed, detail). An exception (missing file, bad JSON, missing key) is a failed criterion.
    def evidence_manifest(self):
        problems = manifest_problems(self.dir)
        return not problems, "; ".join(problems[:5]) or "SHA256SUMS lists every file and every hash verifies"

    def attempt_completed(self):
        start, end, ident = self.json("attempt-start.json"), self.json("attempt-end.json"), self.json("identity.json")
        run_id = self.dir.name
        problems = []
        if not run_id.startswith("smoke-"):
            problems.append("directory is not a smoke attempt")
        for label, doc in (("attempt-start", start), ("attempt-end", end)):
            if doc.get("RunId") != run_id or doc.get("Kind") != "smoke":
                problems.append(f"{label} RunId/Kind does not match the directory")
        if ident.get("Kind") != "smoke":
            problems.append("identity Kind is not smoke")
        if not (end.get("MatchStarted") is True and end.get("MatchCompleted") is True and end.get("ExitCode") == 0 and end.get("Failure") == ""):
            problems.append("attempt-end does not show a completed, clean match")
        if parse_utc(end["EndedUtc"]) < parse_utc(start["StartedUtc"]):
            problems.append("attempt ends before it starts")
        return not problems, "; ".join(problems) or "smoke attempt started, ran and ended cleanly with exit code 0"

    def frozen_identities(self):
        f, ident, problems = self.frozen, self.json("identity.json"), []
        cfg = ident["Config"]
        for key, (name, digest) in (("candidate", f["candidate_jar"]), ("baseline", f["baseline_jar"])):
            if sha256_file(self.dir / name) != digest:
                problems.append(f"archived {name} differs from the published identity")
        sums = {a["Arm"]: a for a in ident["Assets"]}
        if sums["candidate"]["Sha256"] != f["candidate_jar"][1] or sums["baseline"]["Sha256"] != f["baseline_jar"][1]:
            problems.append("identity.json lists different JAR hashes")
        if sha256_file(self.dir / f["smoke_openings"][0]) != f["smoke_openings"][1]:
            problems.append("archived smoke opening list differs from the frozen list")
        if sha256_file(self.dir / "preregistration.md") != f["preregistration_sha256"]:
            problems.append("archived preregistration differs from the frozen hash")
        checks = {"CandidateSha256": f["candidate_jar"][1], "BaselineSha256": f["baseline_jar"][1], "OpeningSha256": f["production_openings_sha256"],
                  "SmokeOpeningSha256": f["smoke_openings"][1], "PreregistrationSha256": f["preregistration_sha256"], "SmokeGames": f["smoke_games"],
                  "Elo0": 0, "Elo1": 20, "Alpha": 0.05, "Beta": 0.05, "BonferroniM": 1, "MinGames": 0, "MaxGames": 20000, "TC": "5+0.05",
                  "Concurrency": 6, "EngineThreads": 1, "OpeningOrder": "sequential", "NewOptions": f["new_options"], "OldOptions": f["old_options"]}
        for key, want in checks.items():
            if cfg.get(key) != want:
                problems.append(f"config {key} differs from the frozen value")
        return not problems, "; ".join(problems) or "JARs, opening lists, preregistration and SPRT configuration equal the frozen identities"

    def script_identity(self):
        ident, problems = self.json("identity.json"), []
        head, scripts = ident.get("InvokingHead", ""), ident.get("ScriptSha256", {})
        if not re.fullmatch(r"[0-9a-f]{40}", head):
            problems.append("InvokingHead is not a full commit id")
        if self.json("attempt-start.json").get("InvokingHead") != head:
            problems.append("attempt-start and identity disagree on the commit")
        for name in SCRIPT_NAMES:
            if not re.fullmatch(r"[0-9a-f]{64}", str(scripts.get(name, ""))):
                problems.append(f"no script hash recorded for {name}")
        # The audit's own code and the analyzer it imports must be the ones the smoke attempt recorded, or a modified helper could
        # produce passing reconciliation criteria that a later restore would hide.
        for name in (TOOL_NAME, ANALYZER_NAME):
            if scripts.get(name) != sha256_file(HERE / name):
                problems.append(f"{name} in this checkout is not the one the smoke attempt recorded")
        return not problems, "; ".join(problems) or f"commit {head[:12]} and {len(SCRIPT_NAMES)} script hashes recorded"

    def cutechess_arguments(self):
        f, env = self.frozen, self.json("environment.json")
        manifest, prepared = self.json("arguments.json"), self.json("prepared-arguments.json")
        problems = []
        if manifest != prepared:
            problems.append("arguments.json differs from prepared-arguments.json")
        args = manifest["Arguments"]
        # Paths are the runner's absolute Windows paths. They must be the frozen file names in one directory that is this attempt's
        # directory under tools\results\release-strength-sprt, which the runner's own transcript and parameters also name.
        paths = {"new": args[args.index("name=NEW") + 5].removeprefix("arg="), "old": args[args.index("name=OLD") + 5].removeprefix("arg="),
                 "pgn": args[args.index("-pgnout") + 1], "openings": next(a for a in args if a.startswith("file=")).removeprefix("file=")}
        for key, name in (("new", f["candidate_jar"][0]), ("old", f["baseline_jar"][0]), ("pgn", "match.pgn"), ("openings", f["smoke_openings"][0])):
            if PureWindowsPath(paths[key]).name != name:
                problems.append(f"{key} path is not {name}")
        parents = {PureWindowsPath(v).parent for v in paths.values()}
        attempt = sorted(parents, key=str)[0]
        if len(parents) != 1 or attempt.name != self.dir.name:
            problems.append("the recorded paths are not all in one directory named after this attempt")
        if tuple(part.lower() for part in attempt.parent.parts[-3:]) != ("tools", "results", "release-strength-sprt"):
            problems.append("the recorded attempt directory is not under tools\\results\\release-strength-sprt")
        if not re.search(r"(?m)^Evidence and frozen inputs: " + re.escape(str(attempt)) + r"\s*$", self.text("runner.log")):
            problems.append("the runner transcript does not name this directory as the attempt directory")
        runner = self.json("runner-parameters.json")
        for key, field in (("new", "New"), ("old", "Old"), ("pgn", "PgnPath"), ("openings", "OpeningsFile")):
            if runner.get(field) != paths[key]:
                problems.append(f"runner-parameters.json {field} differs from the Cute Chess argument")
        want = expected_arguments(env["JavaExecutable"], (paths["new"], paths["old"]), (f["new_options"], f["old_options"]), f["smoke_games"], paths["pgn"], paths["openings"])
        if manifest["Executable"] != env["CutechessExecutable"]:
            problems.append("Cute Chess executable differs from the one the host facts recorded")
        if args != want:
            diff = next((i for i, (a, b) in enumerate(zip(args, want)) if a != b), min(len(args), len(want)))
            problems.append(f"argument list differs from the frozen smoke command at index {diff}")
        return not problems, "; ".join(problems) or f"{len(args)} Cute Chess arguments equal the frozen smoke command line"

    def host_and_tools(self):
        h, env, problems = self.frozen["host"], self.json("environment.json"), []
        if env["ComputerName"].upper() != h["ComputerName"] or not env["CpuName"].strip().startswith(h["CpuPrefix"]) or env["Platform"] != "Win32NT":
            problems.append("not the qualified host")
        if env["HardwareLogicalProcessors"] != h["LogicalProcessors"] or env["AffinityMask"] != env["FullAffinityMask"]:
            problems.append("processor count or affinity is not the frozen one")
        if h["PowerPlanGuid"] not in env["PowerPlanRaw"]:
            problems.append("power plan is not Balanced")
        if (env["JavaVersion"], env["JavaRuntime"]) != (h["JavaVersion"], h["JavaRuntime"]) or "Azul" not in env["JavaVendor"]:
            problems.append("wrong JDK")
        if env["CutechessVersion"] != h["CutechessVersion"]:
            problems.append("wrong Cute Chess version")
        idle = env["IdleCpuMean"]
        if isinstance(idle, bool) or not isinstance(idle, (int, float)) or not 0 <= idle <= h["IdleCpuMeanLimit"]:
            problems.append(f"host was not idle (or the reading is not a number): mean CPU {idle!r}%")
        return not problems, "; ".join(problems) or f"{env['ComputerName']}, JDK {env['JavaRuntime']}, Cute Chess {env['CutechessVersion']}, idle CPU mean {env['IdleCpuMean']}%"

    def games(self):
        if "games" not in self.cache:
            log, pgn = self.text("match.log"), self.text("match.pgn")
            self.cache["games"] = (log, pgn, self.analyzer.read_pgn(pgn))
        return self.cache["games"]

    def games_complete(self):
        n, (log, pgn, games) = self.frozen["smoke_games"], self.games()
        started = STARTED.findall(log)
        finished = FINISHED.findall(log)
        problems = []
        if sorted(int(s[0]) for s in started) != list(range(1, n + 1)):
            problems.append(f"log does not show games 1..{n} started exactly once")
        if any(int(s[1]) != n for s in started):
            problems.append(f"log declares a game total other than {n}")
        if sorted(int(s[0]) for s in finished) != list(range(1, n + 1)):
            problems.append(f"log does not show games 1..{n} finished exactly once")
        if len(games) != n or pgn.count("[Event ") != n:
            problems.append(f"PGN holds {len(games)} complete game records, expected {n}")
        if any(g["Result"] not in ("1-0", "0-1", "1/2-1/2") for g in games):
            problems.append("PGN holds an unfinished or cancelled game")
        if "Finished match" not in log:
            problems.append("log has no 'Finished match'")
        self.counts["games"] = len(games)
        return not problems, "; ".join(problems) or f"{n} games started, finished and archived; none cancelled"

    def opening_pairs(self):
        f, (log, pgn, games) = self.frozen, self.games()
        opening_lines = [l for l in self.text(f["smoke_openings"][0]).split("\n") if l]
        key = self.analyzer.position_key
        problems = []
        by_opening = {}
        for g in games:
            by_opening.setdefault(key(g["FEN"]), {}).setdefault(g["White"], []).append(g["Result"])
        expected = [key(l) for l in opening_lines]
        if sorted(by_opening) != sorted(expected) or len(expected) != f["smoke_games"] // 2:
            problems.append("games did not use exactly the ten smoke openings")
        for fen, sides in by_opening.items():
            if sorted(sides) != ["NEW", "OLD"] or any(len(v) != 1 for v in sides.values()):
                problems.append("an opening was not played once with each colour")
                break
        rec = self.analyzer.reconcile_openings(games, opening_lines)
        if not rec["used_is_gap_free_prefix"] or rec["wrapped_or_repeated"] or rec["unknown_openings"]:
            problems.append("opening reconciliation reports reuse, a gap or an unknown position")
        self.counts["completed_pairs"] = len(by_opening) if not problems else 0
        return not problems, "; ".join(problems) or "10 distinct smoke openings, each played once as White and once as Black by NEW"

    def results_reconcile(self):
        f, (log, pgn, games) = self.frozen, self.games()
        problems = []
        started = {int(a): (b, c) for a, _, b, c in STARTED.findall(log)}
        finished = {int(a): (b, c, r) for a, b, c, r, _ in FINISHED.findall(log)}
        epd = [self.analyzer.position_key(l) for l in self.text(f["smoke_openings"][0]).split("\n") if l]
        pgn_games = {}  # (opening, White) -> (Black, Result), so both player tags are compared with the log
        for g in games:
            pgn_games[(self.analyzer.position_key(g["FEN"]), g["White"])] = (g["Black"], g["Result"])
        for k in range(1, len(epd) + 1):
            for game in (2 * k - 1, 2 * k):
                if game not in finished or game not in started or started[game] != finished[game][:2]:
                    problems.append(f"game {game}: start/finish lines disagree")
                    continue
                if pgn_games.get((epd[k - 1], finished[game][0])) != (finished[game][1], finished[game][2]):
                    problems.append(f"game {game}: PGN players or result differ from the log")
            if 2 * k - 1 in finished and 2 * k in finished and finished[2 * k - 1][0] == finished[2 * k][0]:
                problems.append(f"opening {k}: both games have the same White engine")
        result = self.analyzer.analyze(log, pgn, 0.0, 20.0, 0.05, 0.05, [l for l in self.text(f["smoke_openings"][0]).split("\n") if l])
        if result["games_reconcile_with_log_score"] is not True:
            problems.append("W/L/D from the PGN differ from the last Score line in the log")
        if not result["llr_reconciliation"].get("agrees_with_cutechess_log"):
            problems.append("LLR recomputed from W/L/D differs from the logged LLR")
        if result["scored_games"] != f["smoke_games"] or result["unpaired_games"] != 0 or result["completed_pairs"] != f["smoke_games"] // 2:
            problems.append("analyzer does not report 20 scored games in 10 completed pairs")
        self.counts.update({"wins": result["wins"], "losses": result["losses"], "draws": result["draws"], "log_llr": result["llr_reconciliation"].get("log_llr")})
        return not problems, "; ".join(problems[:5]) or f"{result['wins']}-{result['losses']}-{result['draws']} (W-L-D for NEW) reconciles between PGN and log, LLR {result['llr_reconciliation'].get('log_llr')}"

    def no_faults(self):
        games = self.games()[2]
        problems = []
        for name in ("match.log", "console.stdout.log"):
            hits = [l for l in self.text(name).splitlines() if FAULT.search(l)]
            if hits:
                problems.append(f"{name}: {hits[0][:80]}")
        if 'S="Error"' in self.text("console.stderr.log"):
            problems.append("console.stderr.log holds an error record")
        if any(BAD_TERMINATION.search(g.get("Termination", "")) for g in games):
            problems.append("a PGN termination reports a forfeit, stall, crash or time loss")
        return not problems, "; ".join(problems) or "no time forfeit, crash, disconnect, protocol or option fault in the log, console streams or PGN"

    def smoke_isolation(self):
        f, problems = self.frozen, []
        if not (self.dir / f["smoke_openings"][0]).is_file():
            problems.append("smoke opening list not archived")
        production = self.repo_root / "docs/architecture/research/release-strength-sprt-v0.5.7-v0.6.0/openings-v1.epd"
        if not production.is_file():
            problems.append("production opening list not found to compare against")
        elif sha256_file(production) != f["production_openings_sha256"]:
            problems.append("production opening list differs from its frozen hash")
        else:
            key = self.analyzer.position_key
            prod = {key(l) for l in production.read_text(encoding="ascii").split("\n") if l}
            used = {key(g["FEN"]) for g in self.games()[2]}
            if used & prod:
                problems.append("a smoke game started from a production opening")
        if (self.dir / "openings-v1.epd").exists():
            problems.append("the production opening list is archived in a smoke attempt")
        args = self.json("arguments.json")["Arguments"]
        if any(PureWindowsPath(a.removeprefix("file=")).name == "openings-v1.epd" for a in args):
            problems.append("a Cute Chess argument names the production opening list")
        return not problems, "; ".join(problems) or "smoke games used only the smoke list; none of its positions is in the production corpus"

    def runner_corroboration(self):
        summary, confirm = self.json("summary.json"), self.json("launch-confirmation.json")
        problems = []
        if summary.get("Verdict") != "SMOKE_PASSED" or summary.get("EligibleForPromotion") is not False or summary.get("Kind") != "smoke":
            problems.append("runner summary is not an ineligible SMOKE_PASSED smoke")
        if summary.get("FaultLines") or summary.get("AuditIssues") or summary.get("Cancelled") or summary.get("PartialPgnRecords"):
            problems.append("runner summary lists faults, audit issues, cancellations or partial records")
        if confirm.get("IdleHostConfirmed") is not True:
            problems.append("no idle-host launch confirmation")
        return not problems, "; ".join(problems) or "runner summary agrees (SMOKE_PASSED, ineligible for promotion) and the idle-host confirmation is recorded"

    def run(self):
        checks = {"evidence-manifest": self.evidence_manifest, "attempt-completed": self.attempt_completed,
                  "frozen-identities": self.frozen_identities, "script-identity": self.script_identity,
                  "cutechess-arguments": self.cutechess_arguments, "host-and-tools": self.host_and_tools,
                  "games-complete": self.games_complete, "opening-pairs": self.opening_pairs,
                  "results-reconcile": self.results_reconcile, "no-faults": self.no_faults,
                  "smoke-isolation": self.smoke_isolation, "runner-corroboration": self.runner_corroboration}
        assert list(checks) == CRITERIA
        results = []
        for criterion in CRITERIA:
            try:
                passed, detail = checks[criterion]()
            except Exception as error:  # a missing file or key is a failed criterion, never a crash that skips the record
                passed, detail = False, f"could not evaluate: {type(error).__name__}: {error}"
            results.append({"Id": criterion, "Passed": bool(passed), "Detail": detail})
        return results


def build_record(smoke_dir, frozen=FROZEN, repo_root=None, now=None):
    smoke_dir = Path(smoke_dir)
    repo_root = Path(repo_root) if repo_root else HERE.parent
    audit = Audit(smoke_dir, frozen, repo_root)
    criteria = audit.run()
    approved = all(c["Passed"] for c in criteria)
    smoke = {"RunId": smoke_dir.name}
    try:
        ident, start, end = read_json(smoke_dir / "identity.json"), read_json(smoke_dir / "attempt-start.json"), read_json(smoke_dir / "attempt-end.json")
        smoke.update({"ManifestSha256": sha256_file(smoke_dir / "SHA256SUMS"), "InvokingHead": ident["InvokingHead"],
                      "ScriptSha256": ident["ScriptSha256"], "StartedUtc": start["StartedUtc"], "EndedUtc": end["EndedUtc"]})
    except Exception:
        approved = False  # no manifest/commit/script identity to bind a record to
    return {"Schema": SCHEMA, "Verdict": "AUDIT_APPROVED" if approved else "AUDIT_REJECTED",
            "AuditedUtc": utc_text(now or datetime.now(timezone.utc)),
            "AuditTool": {"Name": TOOL_NAME, "Sha256": sha256_file(HERE / TOOL_NAME), "AnalyzerSha256": sha256_file(HERE / ANALYZER_NAME)},
            "Smoke": smoke, "Counts": audit.counts, "Criteria": criteria}


def write_record(record, out_root, now=None):
    moment = now or datetime.now(timezone.utc)
    out = Path(out_root) / f"audit-{moment.strftime('%Y%m%dT%H%M%S')}{moment.microsecond // 1000:03d}Z-{secrets.token_hex(4)}"
    out.mkdir(parents=True)  # never overwrites: an existing directory is an error
    (out / "smoke-audit.json").write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    (out / "SHA256SUMS").write_text(f"{sha256_file(out / 'smoke-audit.json')}  smoke-audit.json\n", encoding="ascii")
    return out


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("smoke_dir")
    p.add_argument("--out-root", default=None, help="where the audit-* directory is created (default: the smoke directory's parent)")
    a = p.parse_args(argv)
    smoke = Path(a.smoke_dir).resolve()
    out_root = Path(a.out_root).resolve() if a.out_root else smoke.parent
    if out_root == smoke or smoke in out_root.parents:
        sys.exit("Refusing to write inside the smoke attempt directory.")
    if not smoke.is_dir() or not smoke.name.startswith("smoke-"):
        sys.exit("Pass a smoke-* attempt directory.")
    record = build_record(smoke)
    out = write_record(record, out_root)
    for c in record["Criteria"]:
        print(f"{'PASS' if c['Passed'] else 'FAIL'}  {c['Id']}: {c['Detail']}")
    print(f"{record['Verdict']}  record: {out.name}/smoke-audit.json")
    return 0 if record["Verdict"] == "AUDIT_APPROVED" else 1


if __name__ == "__main__":
    sys.exit(main())
