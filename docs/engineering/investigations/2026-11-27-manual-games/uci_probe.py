"""A bounded direct UCI client for the investigation scripts."""
import queue
import re
import subprocess
import threading
import time


class Engine:
    def __init__(self, command, options):
        self.command = command
        self.options = options

    def __enter__(self):
        self.process = subprocess.Popen(self.command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        text=True, bufsize=1)
        self.lines = queue.Queue()

        def read():
            for line in self.process.stdout:
                self.lines.put(line.rstrip())
            self.lines.put(None)

        threading.Thread(target=read, daemon=True).start()
        try:
            self.send("uci")
            self.expect("uciok")
            for name, value in self.options.items():
                self.send(f"setoption name {name} value {value}")
            self.new_game()
        except BaseException:
            self.__exit__()
            raise
        return self

    def __exit__(self, *_):
        try:
            if self.process.poll() is None:
                self.send("quit")
                self.process.wait(timeout=3)
        except (BrokenPipeError, subprocess.TimeoutExpired):
            self.process.kill()
            self.process.wait()
        finally:
            self.process.stdin.close()
            self.process.stdout.close()

    def send(self, line):
        self.process.stdin.write(line + "\n")
        self.process.stdin.flush()

    def expect(self, prefix):
        output = []
        deadline = time.monotonic() + 20
        while (remaining := deadline - time.monotonic()) > 0:
            line = self.lines.get(timeout=remaining)
            if line is None:
                raise RuntimeError(f"engine exited before {prefix}: {output}")
            output.append(line)
            if line.startswith(prefix):
                return output
        raise TimeoutError(f"engine did not send {prefix}")

    def new_game(self):
        self.send("ucinewgame")
        self.send("isready")
        self.expect("readyok")

    def search(self, board, limit):
        position = "position fen " + board.root().fen()
        if board.move_stack:
            position += " moves " + " ".join(move.uci() for move in board.move_stack)
        self.send(position)
        self.send("go " + limit)
        output = self.expect("bestmove ")
        move = board.parse_uci(output[-1].split()[1])
        info = next((line for line in reversed(output) if line.startswith("info depth ") and " score " in line), "")
        score = re.search(r" score (cp|mate) (-?\d+)", info)
        return move, {
            "depth": int(info.split()[2]) if info else None,
            "score": [score[1], int(score[2])] if score else None,
            "pv": info.partition(" pv ")[2].split(),
        }
