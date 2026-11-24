#!/usr/bin/env python3
"""A minimal UCI-speaking stub, test-scope only -- lets UciEngine's plumbing (start
handshake, evaluate request/response, timeout, close) be unit-tested in CI without a
real Stockfish binary. Not a chess engine: the "score" it returns is a deterministic
function of the FEN string's length, not a real evaluation.

Special triggers:
- If the position FEN contains the literal substring "HANG", this stub never
  responds to the following `go` command -- used to test UciEngine's timeout
  handling deterministically, without relying on a real search actually being slow.
- If the position FEN contains the literal substring "NOSCORE", this stub replies
  with `bestmove` immediately, with no preceding `info ... score ...` line -- used
  to test UciEngine's EngineProtocolError path (a well-formed but score-less
  response) deterministically, distinct from a timeout.
- If the position FEN contains "BOUNDLAST", this stub sends an exact `score cp 100`, then a later
  `score cp 900 lowerbound` line, then bestmove: the exact score is the real result.
- If the position FEN contains "BOUNDONLY", this stub sends only `score cp 900 upperbound`
  before bestmove: there is no exact score at all.
- If the environment variable `STUB_LOG` names a file, every command line received is appended
  to it, so a test can check what the driver sent and in which order.
- If the environment variable `STUB_HANG_ON_UCI=1` is set, this stub never responds
  to the initial `uci` handshake command at all -- used to test UciEngine.start()'s
  failure-cleanup path (a startup handshake timeout) deterministically.
"""

import os
import sys


def main() -> None:
    if os.environ.get("STUB_HANG_ON_UCI") == "1":
        # Deliberately never respond to anything -- exercises UciEngine.start()'s
        # timeout-during-handshake path.
        for _ in sys.stdin:
            pass
        return

    position_fen = None
    log_path = os.environ.get("STUB_LOG")
    for line in sys.stdin:
        line = line.strip()
        if log_path:
            with open(log_path, "a", encoding="utf-8") as log:
                log.write(line + "\n")
        if line == "uci":
            print("id name StubEngine")
            print("id author test")
            print("uciok")
            sys.stdout.flush()
        elif line == "isready":
            print("readyok")
            sys.stdout.flush()
        elif line.startswith("setoption"):
            pass  # silently accept, matching a real engine's tolerant behavior
        elif line.startswith("position fen"):
            position_fen = line[len("position fen "):]
        elif line.startswith("go"):
            if position_fen is not None and "HANG" in position_fen:
                # Deliberately never respond -- exercises UciEngine's timeout path.
                continue
            if position_fen is not None and "NOSCORE" in position_fen:
                # bestmove with no preceding score line -- exercises
                # EngineProtocolError, distinct from a timeout.
                print("bestmove e2e4")
                sys.stdout.flush()
                continue
            if position_fen is not None and "BOUNDLAST" in position_fen:
                print("info depth 5 score cp 100 nodes 10")
                print("info depth 6 score cp 900 lowerbound nodes 20")
                print("bestmove e2e4")
                sys.stdout.flush()
                continue
            if position_fen is not None and "BOUNDONLY" in position_fen:
                print("info depth 6 score cp 900 upperbound nodes 20")
                print("bestmove e2e4")
                sys.stdout.flush()
                continue
            score = (len(position_fen) * 7) % 2000 - 1000 if position_fen else 0
            depth = int(line.split()[-1]) if line.split()[-1].isdigit() else 1
            print(f"info depth {depth} score cp {score} nodes 123")
            print("bestmove e2e4")
            sys.stdout.flush()
        elif line == "quit":
            return


if __name__ == "__main__":
    main()
