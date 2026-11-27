import argparse
import json

import chess.pgn
from uci_probe import Engine

parser = argparse.ArgumentParser()
parser.add_argument("jar")
parser.add_argument("pgn")
parser.add_argument("--from-ply", type=int, default=93)
parser.add_argument("--to-ply", type=int, default=123)
parser.add_argument("--depth", type=int, default=13)
parser.add_argument("--target", type=int, default=-1)
parser.add_argument("--key", default="")
parser.add_argument("--ignore-stop", action="store_true")
parser.add_argument("--cold", action="store_true")
parser.add_argument("--fen-only", action="store_true")
parser.add_argument("--force", default="")
args = parser.parse_args()
with open(args.pgn) as stream:
    game = chess.pgn.read_game(stream)
board = game.board()
cmd = ["java", "--add-modules", "jdk.incubator.vector", "-Dreplay.trace=true",
       f"-Dreplay.target={args.target}", f"-Dreplay.key={args.key}",
       f"-Dreplay.ignoreStop={str(args.ignore_stop).lower()}", "-jar", args.jar]
with Engine(cmd, {"Threads": 1, "Hash": 64, "Contempt": 50}) as engine:
    for played in game.mainline_moves():
        ply = board.ply()
        if ply > args.to_ply:
            break
        if ply >= args.from_ply and board.turn == chess.BLACK:
            if args.cold:
                engine.new_game()
            search_board = chess.Board(board.fen()) if args.fen_only and ply == args.target else board
            limit = f"depth {args.depth}"
            if args.force and ply == args.target:
                limit += f" searchmoves {args.force}"
            best, info = engine.search(search_board, limit)
            print(json.dumps({"ply": ply, "best": best.uci(), "san": board.san(best),
                              "played": played.uci(), **info}), flush=True)
        board.push(played)
