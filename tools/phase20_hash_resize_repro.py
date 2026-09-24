#!/usr/bin/env python3
"""Qualify an active production-UCI Hash resize (Phase 20 L2)."""

import argparse
import json
import queue
import subprocess
import threading
import time
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / "engine-uci/target/engine-uci-0.6.0-SNAPSHOT.jar"


def pump(stream, lines, history):
    for line in stream:
        line = line.rstrip("\r\n")
        history.append(line)
        lines.put(line)
    lines.put(None)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--threads", type=int, choices=(2, 4), default=2)
    parser.add_argument("--direction", choices=("grow", "shrink"), default="grow")
    args = parser.parse_args()
    old_hash, new_hash = (16, 32) if args.direction == "grow" else (32, 16)

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
    stdout_history, stderr_history = [], []
    threading.Thread(target=pump, args=(process.stdout, stdout, stdout_history), daemon=True).start()
    threading.Thread(target=pump, args=(process.stderr, stderr, stderr_history), daemon=True).start()
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

    def search_event(search_id, event_name, helper=None):
        return lambda event: event.get("search") == str(search_id) and event.get("event") == event_name and (
            helper is None or event.get("helper") == str(helper))

    try:
        send("uci")
        next_line(stdout, lambda line: line == "uciok")
        for name, value in [
            ("Threads", str(args.threads)), ("Hash", str(old_hash)), ("EvalType", "Classical"),
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
        begin = diagnostic(search_event(1, "begin"))
        if int(begin.get("helpers", "-1")) != args.threads - 1:
            raise SystemExit(f"wrong helper count: {begin}")
        for helper in range(1, args.threads):
            diagnostic(search_event(1, "start", helper))
        next_line(stdout, lambda line: line.startswith("info depth "))

        before_resize = len(stdout_history)
        send(f"setoption name Hash value {new_hash}")
        send("isready")
        first_bestmove = next_line(stdout, lambda line: line.startswith("bestmove "))
        next_line(stdout, lambda line: line == "readyok")
        resize_output = stdout_history[before_resize:]
        bm_index = next(i for i, line in enumerate(resize_output) if line.startswith("bestmove "))
        ready_index = resize_output.index("readyok")

        main_result = diagnostic(search_event(1, "bestmove"))
        helper_exits = [diagnostic(search_event(1, "exit", helper)) for helper in range(1, args.threads)]
        send("go depth 6")
        second_bestmove = next_line(stdout, lambda line: line.startswith("bestmove "))
        second_result = diagnostic(search_event(2, "bestmove"))
        second_exits = [diagnostic(search_event(2, "exit", helper)) for helper in range(1, args.threads)]

        def exit_summary(event):
            bestmove_ms = event.get("bestmove_ms", "pending")
            return {
                "helper": event.get("helper"),
                "exit_cause": event.get("exit_cause"),
                "completed_depth": event.get("completed_depth"),
                "bestmove": event.get("bestmove"),
                "exception": event.get("exception"),
                "after_resize": int(event.get("after_resize", "0")),
                "drain_ms": 0 if bestmove_ms == "pending" else max(
                    0, int(event.get("exit_ms", "0")) - int(bestmove_ms)),
            }

        exits = [exit_summary(event) for event in helper_exits]
        summary = {
            "threads": args.threads,
            "direction": args.direction,
            "resize": f"{old_hash}->{new_hash}MB",
            "bestmoves": [first_bestmove, second_bestmove],
            "bestmove_before_readyok": bm_index < ready_index,
            "legal": [main_result.get("legal"), second_result.get("legal")],
            "helper_exceptions": [main_result.get("helper_exceptions"), second_result.get("helper_exceptions")],
            "search1_helpers": exits,
            "search2_after_resize": [int(event.get("after_resize", "0")) for event in second_exits],
            "helper_execution_errors": [line for line in stderr_history if "SMP helper execution failure" in line],
            "uncaught_main_errors": [line for line in stderr_history if 'Exception in thread "uci-search-thread"' in line],
            "bestmove_count": sum(line.startswith("bestmove ") for line in stdout_history),
        }
        print(json.dumps(summary, separators=(",", ":")))
        if not summary["bestmove_before_readyok"]:
            raise SystemExit("missing bestmove before post-resize readyok")
        if summary["bestmove_count"] != 2 or any(value != "true" for value in summary["legal"]):
            raise SystemExit("expected exactly one legal bestmove for each go")
        if any(int(value or "0") != 0 for value in summary["helper_exceptions"]):
            raise SystemExit("helper exception invariant failed")
        if any(event["after_resize"] != 0 for event in exits + [exit_summary(event) for event in second_exits]):
            raise SystemExit("helper TT activity after resize")
        if any(event["exception"] != "-" for event in exits + [exit_summary(event) for event in second_exits]):
            raise SystemExit("helper exit exception observed")
        if summary["helper_execution_errors"] or summary["uncaught_main_errors"]:
            raise SystemExit("uncaught helper or main-search error observed")
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
