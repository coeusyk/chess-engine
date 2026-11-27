"""Measure a packaged engine's fixed and clock-based UCI searches."""
import argparse
import json
import queue
import subprocess
import threading
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar")
    parser.add_argument("--verify", action="store_true", help="require fixed ponderhit to spend its budget")
    args = parser.parse_args()
    command = ["java", "--add-modules", "jdk.incubator.vector", "-jar", args.jar]
    with subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                          text=True, bufsize=1) as engine:
        lines = queue.Queue()

        def read():
            for line in engine.stdout:
                lines.put((time.monotonic(), line.rstrip()))
            lines.put((time.monotonic(), None))

        threading.Thread(target=read, daemon=True).start()

        def send(*commands):
            engine.stdin.write("\n".join(commands) + "\n")
            engine.stdin.flush()

        def expect(prefix):
            deadline = time.monotonic() + 10
            while (remaining := deadline - time.monotonic()) > 0:
                stamp, line = lines.get(timeout=remaining)
                if line is None:
                    raise RuntimeError("engine exited")
                if line.startswith(prefix):
                    return stamp, line
            raise TimeoutError(prefix)

        try:
            send("uci")
            expect("uciok")
            for label, go, hit in (
                ("plain-fixed", "go movetime 700", False),
                ("ponder-fixed", "go ponder movetime 700", True),
                ("ponder-clock", "go ponder wtime 12000 btime 12000", True),
            ):
                send("ucinewgame", "position startpos", "isready")
                expect("readyok")
                start = time.monotonic()
                send(go)
                if hit:
                    expect("info depth 2 ")
                    start = time.monotonic()
                    send("ponderhit")
                stamp, move = expect("bestmove ")
                elapsed_ms = round((stamp - start) * 1000)
                print(json.dumps({"case": label, "elapsed_ms": elapsed_ms, "reply": move}), flush=True)
                if move.split()[1] == "0000":
                    raise ValueError("engine did not return a legal move")
                if args.verify and label == "ponder-fixed" and not 350 <= elapsed_ms <= 1500:
                    raise ValueError(f"fixed ponderhit is outside its expected window ({elapsed_ms} ms)")
        finally:
            send("quit")
            try:
                engine.wait(timeout=3)
            except subprocess.TimeoutExpired:
                engine.kill()


if __name__ == "__main__":
    main()
