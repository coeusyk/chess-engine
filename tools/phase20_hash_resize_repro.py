#!/usr/bin/env python3
"""Reproduce helper TT activity after a live UCI Hash resize (Phase 20 L2)."""

import json
import queue
import subprocess
import threading
import time
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / "engine-uci/target/engine-uci-0.6.0-SNAPSHOT.jar"


def pump(stream, lines):
    for line in stream:
        lines.put(line.rstrip("\n"))
    lines.put(None)


def main():
    process = subprocess.Popen(
        [
            "rtk", "proxy", "java", "-Xms512m", "-Xmx512m", "-XX:+UseG1GC",
            "--add-modules", "jdk.incubator.vector", "-Dvex.smp.diagnostics=true",
            "-jar", str(JAR),
        ],
        cwd=ROOT,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        bufsize=1,
    )
    stdout, stderr = queue.Queue(), queue.Queue()
    threading.Thread(target=pump, args=(process.stdout, stdout), daemon=True).start()
    threading.Thread(target=pump, args=(process.stderr, stderr), daemon=True).start()
    events = []

    def send(line):
        process.stdin.write(line + "\n")
        process.stdin.flush()

    def next_line(lines, predicate, timeout=20):
        deadline = time.monotonic() + timeout
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("timed out waiting for UCI response")
            line = lines.get(timeout=remaining)
            if line is None:
                raise RuntimeError("engine closed its output")
            if predicate(line):
                return line

    def diagnostic(predicate, timeout=20):
        for event in events:
            if predicate(event):
                return event
        deadline = time.monotonic() + timeout
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("timed out waiting for helper diagnostic")
            line = stderr.get(timeout=remaining)
            if line is None:
                raise RuntimeError("engine closed stderr")
            if not line.startswith("SMPDIAG "):
                continue
            event = dict(field.split("=", 1) for field in line.split()[1:] if "=" in field)
            events.append(event)
            if predicate(event):
                return event

    try:
        send("uci")
        next_line(stdout, lambda line: line == "uciok")
        for name, value in [
            ("Threads", "2"), ("Hash", "16"), ("EvalType", "Classical"),
            ("OwnBook", "false"), ("SyzygyOnline", "false"), ("MultiPV", "1"),
            ("Contempt", "0"), ("PawnHashSize", "1"),
        ]:
            send(f"setoption name {name} value {value}")
        send("isready")
        next_line(stdout, lambda line: line == "readyok")
        send("ucinewgame")
        send("isready")
        next_line(stdout, lambda line: line == "readyok")
        send("position startpos")
        send("go depth 127")
        diagnostic(lambda event: event.get("search") == "1" and event.get("event") == "start")
        next_line(stdout, lambda line: line.startswith("info depth "))
        send("setoption name Hash value 32")
        time.sleep(0.05)  # Give the active helper time to touch the resized table.
        send("stop")
        bestmove = next_line(stdout, lambda line: line.startswith("bestmove ")).split()[1]
        main_result = diagnostic(lambda event: event.get("search") == "1" and event.get("event") == "bestmove")
        helper_exit = diagnostic(lambda event: event.get("search") == "1" and event.get("event") == "exit")
        send("isready")
        next_line(stdout, lambda line: line == "readyok")
        result = {
            "helper": helper_exit.get("helper"),
            "exit_cause": helper_exit.get("exit_cause"),
            "bestmove": bestmove,
            "legal": main_result.get("legal"),
            "helper_exceptions": main_result.get("helper_exceptions"),
            "tt_activity_after_resize": helper_exit.get("after_resize"),
            "tt_reads_after_resize": helper_exit.get("reads_after_resize"),
            "tt_writes_after_resize": helper_exit.get("writes_after_resize"),
            "tt_other_after_resize": helper_exit.get("other_after_resize"),
            "events": events,
        }
        print(json.dumps(result, separators=(",", ":")))
        if int(result["tt_activity_after_resize"] or 0) == 0:
            raise SystemExit("resize activity was not reproduced")
        if result["legal"] != "true" or int(result["helper_exceptions"] or 0) != 0:
            raise SystemExit("bestmove or helper-exception invariant failed")
    finally:
        try:
            send("quit")
        except (BrokenPipeError, OSError):
            pass
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.terminate()


if __name__ == "__main__":
    main()
