#!/usr/bin/env python3
"""Read-only analysis of the s313 NNUE raw rounds. Reads results.jsonl and pressure.csv, writes nothing.

A unit is flagged if any WSL CPU sample inside its [start, end] window exceeds 12 percent (INCIDENT-1 rule).
A round is clean only if none of its units is flagged. Prints per-round paired differences and a clean-only summary.
"""
import datetime
import json
import statistics
from pathlib import Path

HERE = Path(__file__).resolve().parent
LIMIT = 12.0


def t(s):
    return datetime.datetime.fromisoformat(s)


samples = []
for line in (HERE / "pressure.csv").read_text().splitlines():
    ts, cpu, _mem = line.split(",")
    samples.append((t(ts), float(cpu)))

units = {}
for line in (HERE / "results.jsonl").read_text().splitlines():
    r = json.loads(line)
    if r.get("key", "").startswith("raw:s313:nnue:") and not r.get("warmup"):
        units.setdefault(r["round"], {})[r["variant"]] = r


def peak(u):
    a, b = t(u["start_utc"]), t(u["end_utc"])
    w = [c for ts, c in samples if a <= ts <= b]
    return max(w) if w else None


rows = []
for rnd in sorted(units):
    v = units[rnd]
    if set(v) != {"base", "base_repeat", "candidate"}:
        print(f"round {rnd}: incomplete {sorted(v)}")
        continue
    pk = {k: peak(u) for k, u in v.items()}
    clean = all(p is not None and p <= LIMIT for p in pk.values())
    b = v["base"]["nps"]
    d_c = 100 * (v["candidate"]["nps"] / b - 1)
    d_r = 100 * (v["base_repeat"]["nps"] / b - 1)
    rows.append((rnd, clean, d_c, d_r))
    print(f"round {rnd} {'CLEAN' if clean else 'excluded'}: base {b} repeat {v['base_repeat']['nps']} cand {v['candidate']['nps']}"
          f" | cand vs base {d_c:+.2f}% repeat vs base {d_r:+.2f}% | peak cpu {pk}")


def summ(label, sel):
    if not sel:
        print(f"{label}: none")
        return
    c, r = [x[2] for x in sel], [x[3] for x in sel]
    print(f"{label}: n={len(sel)} rounds {[x[0] for x in sel]} candidate median {statistics.median(c):+.2f}% range {min(c):+.2f}..{max(c):+.2f}"
          f" | repeat median {statistics.median(r):+.2f}% range {min(r):+.2f}..{max(r):+.2f}")


summ("clean", [x for x in rows if x[1]])
summ("all (supplementary)", rows)
