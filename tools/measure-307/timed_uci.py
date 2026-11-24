#!/usr/bin/env python3
"""Timed UCI driver for the #307 measurements.

Starts one engine process, plays `go movetime M` on a fixed list of positions taken from the committed bench
corpus, and records for each search how long the engine really took to answer and how many nodes it reported.
The overshoot (answer time minus movetime) is what the throttled stop poll can change, and nodes per second at a
fixed time is what the other optimisations can change. Nothing here picks a winner. It writes a CSV and prints
a summary, and the protocol document says how the numbers are compared.

Usage: python timed_uci.py --jar engine-uci.jar --out run.csv [--movetime-ms 100] [--per-file 12] [--threads 1]
"""
import argparse
import csv
import statistics
import subprocess
import sys
import time
from pathlib import Path

CORPUS_FILES = ["opening.epd", "middlegame.epd", "tactical.epd", "endgame.epd", "quiet.epd"]


def epd_to_fen(line):
    """First four EPD fields plus move counters, or None for blank and comment lines."""
    line = line.strip()
    if not line or line.startswith("#"):
        return None
    parts = line.split()
    if len(parts) < 4:
        return None
    return " ".join(parts[:4]) + " 0 1"


def load_positions(corpus_dir, per_file):
    fens = []
    for name in CORPUS_FILES:
        taken = 0
        for line in (Path(corpus_dir) / name).read_text(encoding="utf-8").splitlines():
            fen = epd_to_fen(line)
            if fen is None:
                continue
            fens.append(fen)
            taken += 1
            if taken >= per_file:
                break
    return fens


def summarize(rows, movetime_ms):
    overshoot = sorted(r["overshoot_ms"] for r in rows)
    nps = [r["nodes"] / (r["wall_ms"] / 1000.0) for r in rows if r["wall_ms"] > 0 and r["nodes"] > 0]
    p95 = overshoot[min(len(overshoot) - 1, int(round(0.95 * (len(overshoot) - 1))))]
    return {
        "searches": len(rows),
        "movetime_ms": movetime_ms,
        "overshoot_ms_mean": statistics.fmean(overshoot),
        "overshoot_ms_median": statistics.median(overshoot),
        "overshoot_ms_p95": p95,
        "overshoot_ms_max": overshoot[-1],
        "nodes_per_second_mean": statistics.fmean(nps) if nps else 0.0,
    }


class Engine:
    def __init__(self, java, jar, threads, hash_mb):
        self.proc = subprocess.Popen([java, "-jar", jar], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=subprocess.DEVNULL, text=True, bufsize=1)
        self.send("uci")
        self.wait_for("uciok")
        self.send(f"setoption name Threads value {threads}")
        self.send(f"setoption name Hash value {hash_mb}")
        self.ready()

    def send(self, line):
        self.proc.stdin.write(line + "\n")
        self.proc.stdin.flush()

    def wait_for(self, prefix):
        for line in self.proc.stdout:
            if line.startswith(prefix):
                return line
        raise RuntimeError(f"engine closed before '{prefix}'")

    def ready(self):
        self.send("isready")
        self.wait_for("readyok")

    def search(self, fen, movetime_ms):
        self.send("ucinewgame")
        self.ready()
        self.send(f"position fen {fen}")
        start = time.perf_counter()
        self.send(f"go movetime {movetime_ms}")
        nodes = 0
        for line in self.proc.stdout:
            if line.startswith("info") and " nodes " in line:
                parts = line.split()
                nodes = int(parts[parts.index("nodes") + 1])
            if line.startswith("bestmove"):
                return (time.perf_counter() - start) * 1000.0, nodes
        raise RuntimeError("engine closed before bestmove")

    def close(self):
        try:
            self.send("quit")
            self.proc.wait(timeout=10)
        except Exception:
            self.proc.kill()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jar", required=True)
    ap.add_argument("--java", default="java")
    ap.add_argument("--out", required=True)
    ap.add_argument("--corpus-dir", default="bench/nnue-corpus")
    ap.add_argument("--movetime-ms", type=int, default=100)
    ap.add_argument("--per-file", type=int, default=12)
    ap.add_argument("--threads", type=int, default=1)
    ap.add_argument("--hash-mb", type=int, default=16)
    ap.add_argument("--warmup", type=int, default=5, help="searches run first and left out of the summary")
    args = ap.parse_args()

    fens = load_positions(args.corpus_dir, args.per_file)
    engine = Engine(args.java, args.jar, args.threads, args.hash_mb)
    rows = []
    try:
        for i, fen in enumerate(fens):
            wall_ms, nodes = engine.search(fen, args.movetime_ms)
            if i < args.warmup:
                continue
            rows.append({"fen": fen, "wall_ms": wall_ms, "overshoot_ms": wall_ms - args.movetime_ms, "nodes": nodes})
    finally:
        engine.close()

    with open(args.out, "w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=["fen", "wall_ms", "overshoot_ms", "nodes"])
        writer.writeheader()
        writer.writerows(rows)
    for key, value in summarize(rows, args.movetime_ms).items():
        print(f"{key}={value:.2f}" if isinstance(value, float) else f"{key}={value}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
