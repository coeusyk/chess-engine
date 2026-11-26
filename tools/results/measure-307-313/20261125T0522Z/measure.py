#!/usr/bin/env python3
"""Controlled measurements for #307 and #313, following docs/engineering/investigations/2026-11-24-307-search-overhead-measurement-protocol.md.

One process, one measurement at a time, resumable: every finished unit is appended to results.jsonl and skipped on a
restart. Nothing here picks a winner. It records Gate 1 (identical node counts), raw speed in a rotating schedule,
timed play, allocation and the host pressure, and writes summary.json and summary.md.

Usage (from the archive directory this file sits in):
    python3 measure.py --repo <repo checkout with bench/nnue-corpus and tools/measure-307> --phases gate,raw,timed,alloc,summary [--rounds 10]
Phases: build313, gate, raw, timed, alloc, summary. The #307 jars are built beforehand by tools/measure-307/build-variants.sh
into jars307/, the net used for NNUE mode is net.nnue next to this file.
"""
import argparse
import csv
import datetime
import hashlib
import json
import os
import re
import statistics
import subprocess
import sys
import threading
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
RESULTS = HERE / "results.jsonl"
JAVA_FLAGS = ["-Xms256m", "-Xmx512m", "-XX:-UsePerfData", "--add-modules", "jdk.incubator.vector"]
ALLOC_FLAGS = ["-Xms512m", "-Xmx512m", "-XX:+UseSerialGC", "--add-modules", "jdk.incubator.vector"]
DEPTH = "13"
NNUE_ARGS = ["--eval-type", "NNUE", "--eval-file", "net.nnue"]

SUITES = {
    "s307": {
        "jars": {"base": "jars307/base.jar", "base_repeat": "jars307/base.jar", "terminal": "jars307/terminal.jar",
                 "onmake": "jars307/onmake.jar", "poll": "jars307/poll.jar", "all": "jars307/all.jar",
                 "all_no_poll": "jars307/all-no-poll.jar", "searchmode": "jars307/searchmode.jar"},
        "schedule": ["base", "base_repeat", "terminal", "onmake", "poll", "all", "all_no_poll"],
        "gate_only": ["searchmode"],
        "raw_modes": ["classical", "nnue"],
        "timed": True,
        "alloc": ["base", "terminal", "all"],
    },
    "s313": {
        "jars": {"base": "jars313/base.jar", "base_repeat": "jars313/base.jar", "candidate": "jars313/candidate.jar"},
        "schedule": ["base", "base_repeat", "candidate"],
        "gate_only": [],
        "raw_modes": ["nnue"],
        "timed": False,
        "alloc": ["base", "candidate"],
    },
}


def now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def load_done():
    done = {}
    if RESULTS.exists():
        for line in RESULTS.read_text(encoding="utf-8").splitlines():
            if line.strip():
                rec = json.loads(line)
                done[rec["key"]] = rec
    return done


DONE = load_done()


def record(key, **fields):
    rec = {"key": key, "utc": now(), **fields}
    with open(RESULTS, "a", encoding="utf-8") as fh:
        fh.write(json.dumps(rec) + "\n")
    DONE[key] = rec
    return rec


# ---------------------------------------------------------------- host

def powershell(script):
    out = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script],
                         capture_output=True, text=True, timeout=300)
    return out.stdout.strip()


def windows_counters(samples=15, interval=2):
    script = ("$s=Get-Counter '\\Processor(_Total)\\% Processor Time','\\Memory\\Available MBytes' "
              f"-SampleInterval {interval} -MaxSamples {samples}; "
              "$cpu=@($s|ForEach-Object{$_.CounterSamples}|Where-Object{$_.Path -like '*% processor time'}|ForEach-Object{$_.CookedValue}); "
              "$mem=@($s|ForEach-Object{$_.CounterSamples}|Where-Object{$_.Path -like '*available mbytes'}|ForEach-Object{$_.CookedValue}); "
              "@{cpu=$cpu;mem=$mem}|ConvertTo-Json -Compress")
    return json.loads(powershell(script))


def foreign_load():
    """Names of processes that must not be running: cutechess or SPRT launchers on Windows or WSL."""
    found = []
    tl = subprocess.run(["tasklist.exe"], capture_output=True, text=True).stdout.lower()
    for name in ("cutechess", "fastchess"):
        if name in tl:
            found.append("windows:" + name)
    # Operational fix, not a protocol change: v1 and v2 matched these names anywhere in a command line, so a shell
    # command that merely mentioned `sprt_linux` stopped the run (it did, at 2026-11-25 07:16 UTC). Now a match
    # needs a process whose executable is a match engine, or an interpreter whose script is one of the launchers.
    ps = subprocess.run(["ps", "-eo", "comm=,args="], capture_output=True, text=True).stdout.lower().splitlines()
    launchers = {"sprt_linux.py", "sprt.ps1", "fixed_count_match.py"}
    for line in ps:
        parts = line.split()
        if not parts:
            continue
        comm, tokens = parts[0], parts[1:]
        if comm in ("cutechess-cli", "cutechess", "fastchess"):
            found.append("wsl:" + comm)
        elif comm.startswith(("python", "pwsh")):
            script = next((t for t in tokens[1:] if not t.startswith("-")), "")
            if script.replace("\\", "/").rsplit("/", 1)[-1] in launchers:
                found.append("wsl:" + script.rsplit("/", 1)[-1])
    return sorted(set(found))


def headroom_gate(label):
    """CPU average at most 15 percent and at least 6144 MiB free, as used for the SPRTs. Four tries of 30 seconds."""
    for trial in range(1, 5):
        c = windows_counters()
        cpu_avg = statistics.fmean(c["cpu"])
        mem_min = min(c["mem"])
        rec = {"trial": trial, "cpu_avg_pct": cpu_avg, "mem_min_mib": mem_min, "foreign": foreign_load()}
        if cpu_avg <= 15 and mem_min >= 6144 and not rec["foreign"]:
            record(f"headroom:{label}", ok=True, **rec)
            return
        record(f"headroom:{label}:trial{trial}", ok=False, **rec)
    raise SystemExit("host headroom gate failed")


class Pressure:
    """Samples WSL cpu and memory every 10 s into pressure.csv, and the Windows counters through typeperf."""

    def __init__(self):
        self.stop = threading.Event()
        self.thread = threading.Thread(target=self.run, daemon=True)
        self.typeperf = None

    def start(self):
        out_win = subprocess.run(["wslpath", "-w", str(HERE / "pressure-windows.csv")], capture_output=True, text=True).stdout.strip()
        self.typeperf = subprocess.Popen(["typeperf.exe", r"\Processor(_Total)\% Processor Time", r"\Memory\Available MBytes",
                                          "-si", "10", "-sc", "100000", "-o", out_win, "-y"],
                                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.thread.start()

    def run(self):
        def cpu():
            with open("/proc/stat") as fh:
                f = list(map(int, fh.readline().split()[1:]))
            return sum(f), f[3] + f[4]
        total0, idle0 = cpu()
        with open(HERE / "pressure.csv", "a", encoding="utf-8") as out:
            while not self.stop.wait(10):
                total1, idle1 = cpu()
                busy = 100.0 * (1 - (idle1 - idle0) / max(1, total1 - total0))
                total0, idle0 = total1, idle1
                avail = int(next(l for l in open("/proc/meminfo") if l.startswith("MemAvailable")).split()[1]) // 1024
                out.write(f"{now()},{busy:.1f},{avail}\n")
                out.flush()

    def close(self):
        self.stop.set()
        if self.typeperf and self.typeperf.poll() is None:
            self.typeperf.terminate()


# ---------------------------------------------------------------- bench

def parse_bench(text):
    nodes_by_position = [int(m.group(1)) for m in re.finditer(r"^\s*\d+/\d+\s+nodes=(\d+)", text, re.M)]
    nodes = int(re.search(r"Nodes searched:\s*(\d+)", text).group(1))
    time_ms = int(re.search(r"^Time\s*:\s*(\d+) ms", text, re.M).group(1))
    nps = int(re.search(r"^NPS\s*:\s*(\d+)", text, re.M).group(1))
    return {"nodes_by_position": nodes_by_position, "nodes": nodes, "time_ms": time_ms, "nps": nps}


def run_bench(suite, variant, mode, extra=None, flags=JAVA_FLAGS, log=None):
    jar = HERE / SUITES[suite]["jars"][variant]
    cmd = ["java", *flags, "-jar", str(jar), "--bench-raw", DEPTH, *(extra or []), *(NNUE_ARGS if mode == "nnue" else [])]
    start = now()
    proc = subprocess.run(cmd, capture_output=True, text=True, cwd=HERE)
    if proc.returncode != 0:
        raise SystemExit(f"bench failed: {suite} {variant} {mode}: {proc.stderr[-300:]}")
    if log:
        (HERE / "raw" / log).write_text(proc.stdout, encoding="utf-8")
    return {"start_utc": start, "end_utc": now(), **parse_bench(proc.stdout)}


def phase_gate(suite):
    s = SUITES[suite]
    (HERE / "raw").mkdir(exist_ok=True)
    headroom_gate(f"{suite}:gate")
    for mode in ("classical", "nnue"):
        reference = None
        for variant in ["base"] + [v for v in s["schedule"] if v not in ("base", "base_repeat")] + s["gate_only"]:
            key = f"gate:{suite}:{mode}:{variant}"
            if key not in DONE:
                r = run_bench(suite, variant, mode, log=f"gate-{suite}-{mode}-{variant}.txt")
                record(key, suite=suite, mode=mode, variant=variant, jar_sha256=sha256(HERE / s["jars"][variant]), **r)
            rec = DONE[key]
            if reference is None:
                reference = rec
            same = rec["nodes_by_position"] == reference["nodes_by_position"]
            record(f"gatecheck:{suite}:{mode}:{variant}", suite=suite, mode=mode, variant=variant, identical=same,
                   nodes=rec["nodes"], base_nodes=reference["nodes"])
            if not same:
                diffs = [i + 1 for i, (a, b) in enumerate(zip(rec["nodes_by_position"], reference["nodes_by_position"])) if a != b]
                raise SystemExit(f"GATE 1 FAILED: {suite} {mode} {variant} differs from base at positions {diffs[:10]}")


def rotated(schedule, r):
    n = len(schedule)
    return [schedule[(r + i) % n] for i in range(n)]


def phase_raw(suite, rounds, rounds_nnue=None):
    # Amendment 1: NNUE rounds are counted separately from the Classical ones.
    s = SUITES[suite]
    (HERE / "raw").mkdir(exist_ok=True)
    pressure = Pressure()
    pressure.start()
    try:
        for mode in s["raw_modes"]:
            headroom_gate(f"{suite}:{mode}:raw")
            for r in range(rounds_nnue if (mode == "nnue" and rounds_nnue is not None) else rounds):
                warm_key = f"raw:{suite}:{mode}:r{r}:warmup"
                if warm_key not in DONE:
                    rec = run_bench(suite, "base", mode, log=f"warmup-{suite}-{mode}-r{r}.txt")
                    record(warm_key, suite=suite, mode=mode, round=r, variant="base", warmup=True, nps=rec["nps"])
                for position, variant in enumerate(rotated(s["schedule"], r)):
                    key = f"raw:{suite}:{mode}:r{r}:{variant}"
                    if key in DONE:
                        continue
                    if foreign_load():
                        raise SystemExit("foreign load appeared: " + ",".join(foreign_load()))
                    rec = run_bench(suite, variant, mode, log=f"raw-{suite}-{mode}-r{r}-{variant}.txt")
                    record(key, suite=suite, mode=mode, round=r, position=position, variant=variant, warmup=False,
                           nodes=rec["nodes"], time_ms=rec["time_ms"], nps=rec["nps"],
                           start_utc=rec["start_utc"], end_utc=rec["end_utc"])
    finally:
        pressure.close()


def phase_timed(suite, rounds, repo):
    s = SUITES[suite]
    if not s["timed"]:
        return
    (HERE / "timed").mkdir(exist_ok=True)
    pressure = Pressure()
    pressure.start()
    settings = [(100, 1), (20, 1), (100, 2)]
    try:
        headroom_gate(f"{suite}:timed")
        for r in range(rounds):
            for movetime, threads in settings:
                for variant in rotated(s["schedule"], r):
                    key = f"timed:{suite}:m{movetime}t{threads}:r{r}:{variant}"
                    if key in DONE:
                        continue
                    out = HERE / "timed" / f"{suite}-m{movetime}-t{threads}-r{r}-{variant}.csv"
                    proc = subprocess.run(["python3", str(Path(repo) / "tools/measure-307/timed_uci.py"),
                                           "--jar", str(HERE / s["jars"][variant]), "--out", str(out),
                                           "--movetime-ms", str(movetime), "--threads", str(threads)],
                                          capture_output=True, text=True, cwd=repo)
                    if proc.returncode != 0:
                        raise SystemExit("timed run failed: " + proc.stderr[-300:])
                    rows = list(csv.DictReader(open(out, encoding="utf-8")))
                    over = sorted(float(x["overshoot_ms"]) for x in rows)
                    nps = [int(x["nodes"]) / (float(x["wall_ms"]) / 1000) for x in rows if float(x["wall_ms"]) > 0 and int(x["nodes"]) > 0]
                    record(key, suite=suite, movetime=movetime, threads=threads, round=r, variant=variant, searches=len(rows),
                           overshoot_mean=statistics.fmean(over), overshoot_median=statistics.median(over),
                           overshoot_p95=over[min(len(over) - 1, int(round(0.95 * (len(over) - 1))))], overshoot_max=over[-1],
                           nps_mean=statistics.fmean(nps))
    finally:
        pressure.close()


UNITS = {"bytes": 1, "kB": 1e3, "MB": 1e6, "GB": 1e9}


def parse_jfr(path):
    text = subprocess.run(["jfr", "print", "--events", "jdk.ObjectAllocationSample", str(path)],
                          capture_output=True, text=True).stdout
    total = 0.0
    by_class = {}
    by_site = {}
    for block in text.split("jdk.ObjectAllocationSample {")[1:]:
        w = re.search(r"weight\s*=\s*([\d.,]+)\s*(bytes|kB|MB|GB)", block)
        c = re.search(r"objectClass\s*=\s*(\S+)", block)
        f = re.search(r"stackTrace\s*=\s*\[\s*\n\s*([^\n]+)", block)
        if not w:
            continue
        weight = float(w.group(1).replace(",", "")) * UNITS[w.group(2)]
        total += weight
        if c:
            by_class[c.group(1)] = by_class.get(c.group(1), 0) + weight
        if f:
            site = f.group(1).strip().split(" line:")[0]
            by_site[site] = by_site.get(site, 0) + weight
    top = lambda d: sorted(d.items(), key=lambda kv: -kv[1])[:6]
    return total, top(by_class), top(by_site)


def phase_alloc(suite):
    s = SUITES[suite]
    (HERE / "alloc").mkdir(exist_ok=True)
    headroom_gate(f"{suite}:alloc")
    for mode in s["raw_modes"]:
        for variant in s["alloc"]:
            key = f"alloc:{suite}:{mode}:{variant}"
            if key in DONE:
                continue
            base = HERE / "alloc" / f"{suite}-{mode}-{variant}"
            flags = [*ALLOC_FLAGS, f"-XX:StartFlightRecording=filename={base}.jfr,settings=profile", f"-Xlog:gc:file={base}.gc.log"]
            (HERE / "raw").mkdir(exist_ok=True)
            rec = run_bench(suite, variant, mode, flags=flags, log=f"alloc-{suite}-{mode}-{variant}.txt")
            gc = (Path(str(base) + ".gc.log")).read_text(errors="replace")
            young = len(re.findall(r"Pause Young", gc))
            total, top_class, top_site = parse_jfr(Path(str(base) + ".jfr"))
            record(key, suite=suite, mode=mode, variant=variant, nodes=rec["nodes"], nps_with_jfr=rec["nps"],
                   sampled_allocation_bytes=total, young_collections=young, top_classes=top_class, top_sites=top_site,
                   bytes_per_node=total / rec["nodes"])


def phase_build313(repo):
    out = HERE / "jars313"
    out.mkdir(exist_ok=True)
    commits = {"base": "3c4e8ba", "candidate": "1de80b2"}
    sums = []
    for name, commit in commits.items():
        jar = out / f"{name}.jar"
        if jar.exists():
            sums.append(f"{sha256(jar)}  {name}.jar")
            continue
        tree = out / f"src-{name}"
        subprocess.run(["git", "-C", repo, "worktree", "add", "-q", "--detach", str(tree), commit], check=True)
        subprocess.run(["mvn", "-B", "-q", "-pl", "engine-core,engine-uci", "-am", "package", "-DskipTests"], cwd=tree, check=True)
        built = sorted(p for p in (tree / "engine-uci/target").glob("engine-uci-*-SNAPSHOT.jar") if "original" not in p.name)[0]
        jar.write_bytes(built.read_bytes())
        subprocess.run(["git", "-C", repo, "worktree", "remove", "--force", str(tree)], check=True)
        sums.append(f"{sha256(jar)}  {name}.jar")
        print("built", name, commit)
    (out / "SHA256SUMS").write_text("\n".join(sums) + "\n")


def median_range(values):
    return {"median": statistics.median(values), "min": min(values), "max": max(values), "n": len(values)}


def phase_summary():
    out = {}
    for suite, s in SUITES.items():
        suite_out = {"gate1": {}, "raw": {}, "timed": {}, "alloc": {}}
        for key, rec in DONE.items():
            if key.startswith(f"gatecheck:{suite}:"):
                suite_out["gate1"][f"{rec['mode']}:{rec['variant']}"] = rec["identical"]
        for mode in s["raw_modes"]:
            per = {}
            for key, rec in DONE.items():
                if key.startswith(f"raw:{suite}:{mode}:") and not rec.get("warmup"):
                    per.setdefault(rec["variant"], {})[rec["round"]] = rec["nps"]
            for variant, by_round in per.items():
                entry = median_range(list(by_round.values()))
                diffs = [100.0 * (by_round[r] - per["base"][r]) / per["base"][r] for r in by_round if r in per.get("base", {})]
                entry["paired_diff_to_base_pct"] = median_range(diffs) if diffs and variant != "base" else None
                suite_out["raw"].setdefault(mode, {})[variant] = entry
        for setting in ("m100t1", "m20t1", "m100t2"):
            per = {}
            for key, rec in DONE.items():
                if key.startswith(f"timed:{suite}:{setting}:"):
                    per.setdefault(rec["variant"], []).append(rec)
            for variant, rows in per.items():
                suite_out["timed"].setdefault(setting, {})[variant] = {
                    "rounds": len(rows),
                    "overshoot_mean": median_range([x["overshoot_mean"] for x in rows]),
                    "overshoot_median": median_range([x["overshoot_median"] for x in rows]),
                    "overshoot_p95": median_range([x["overshoot_p95"] for x in rows]),
                    "overshoot_max": median_range([x["overshoot_max"] for x in rows]),
                    "nps_mean": median_range([x["nps_mean"] for x in rows])}
        for key, rec in DONE.items():
            if key.startswith(f"alloc:{suite}:"):
                suite_out["alloc"][f"{rec['mode']}:{rec['variant']}"] = {
                    k: rec[k] for k in ("sampled_allocation_bytes", "bytes_per_node", "young_collections", "top_classes", "top_sites")}
        out[suite] = suite_out
    (HERE / "summary.json").write_text(json.dumps(out, indent=2), encoding="utf-8")
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True)
    ap.add_argument("--phases", default="gate,raw,timed,alloc,summary")
    ap.add_argument("--suites", default="s307,s313")
    ap.add_argument("--rounds", type=int, default=10)
    ap.add_argument("--rounds-nnue", type=int, default=None, help="rounds for NNUE mode raw speed (Amendment 1)")
    args = ap.parse_args()
    phases = args.phases.split(",")
    if "build313" in phases:
        phase_build313(args.repo)
    for suite in args.suites.split(","):
        if "gate" in phases:
            phase_gate(suite)
        if "raw" in phases:
            phase_raw(suite, args.rounds, args.rounds_nnue)
        if "timed" in phases:
            phase_timed(suite, args.rounds, args.repo)
        if "alloc" in phases:
            phase_alloc(suite)
    if "summary" in phases:
        phase_summary()
    print("done", now())


if __name__ == "__main__":
    main()
