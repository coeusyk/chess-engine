"""Replay a full game prefix, then have Vex convert against Stockfish."""
import argparse
import json
import time
from contextlib import ExitStack
from pathlib import Path

import chess.pgn
from uci_probe import Engine


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path)
    parser.add_argument("stockfish")
    parser.add_argument("mode", choices=["persistent", "cleared", "fresh"])
    parser.add_argument("--pgn", type=Path, default=Path(__file__).parent / "games/2500_black.pgn")
    parser.add_argument("--start-ply", type=int, default=93)
    parser.add_argument("--threads", type=int, default=8)
    parser.add_argument("--hash", type=int, default=256)
    parser.add_argument("--contempt", type=int, default=0)
    parser.add_argument("--vex-ms", type=int, default=3000)
    parser.add_argument("--defender-ms", type=int, default=150)
    parser.add_argument("--skip-mate-tt", action="store_true", help="requires the diagnostic JAR")
    args = parser.parse_args()

    with args.pgn.open() as source:
        game = chess.pgn.read_game(source)
    if game is None or game.errors:
        parser.error("expected a valid game")
    moves = list(game.mainline_moves())
    if not 0 <= args.start_ply <= len(moves):
        parser.error("start ply must fall within the game")
    board = game.board()
    for move in moves[:args.start_ply]:
        board.push(move)
    vex_color = board.turn

    command = ["java"]
    if args.skip_mate_tt:
        command.append("-Ddiag.skipMateTt=true")
    command += ["--add-modules", "jdk.incubator.vector", "-jar", str(args.jar)]

    def open_vex(stack):
        return stack.enter_context(Engine(command, {"Threads": args.threads, "Hash": args.hash,
                                                    "Contempt": args.contempt}))

    trace = []
    with ExitStack() as processes:
        defender = processes.enter_context(Engine([args.stockfish], {"Threads": 4, "Hash": 256}))
        vex = open_vex(processes) if args.mode != "fresh" else None
        for _ in range(120):
            outcome = board.outcome(claim_draw=True)
            if outcome:
                result = outcome.termination.name
                winner = None if outcome.winner is None else ("vex" if outcome.winner == vex_color else "defender")
                break

            started = time.monotonic()
            if board.turn == vex_color:
                with ExitStack() as per_move:
                    if args.mode == "fresh":
                        vex = open_vex(per_move)
                    if args.mode == "cleared":
                        vex.new_game()
                    move, info = vex.search(board, f"movetime {args.vex_ms}")
            else:
                move, info = defender.search(board, f"movetime {args.defender_ms}")

            if move not in board.legal_moves:
                raise ValueError(f"illegal bestmove {move} at {board.fen()}")
            trace.append({
                "ply": board.ply() + 1,
                "player": "vex" if board.turn == vex_color else "defender",
                "move": move.uci(),
                **info,
                "wall_ms": round((time.monotonic() - started) * 1000),
            })
            board.push(move)
        else:
            result, winner = "MOVE_LIMIT", None

    print(json.dumps({"start_ply": args.start_ply, "mode": args.mode, "outcome": result,
                      "winner": winner, "final_fen": board.fen(), "trace": trace}))


if __name__ == "__main__":
    main()
