"""Compare Vex's Classical terms and Stockfish search on the White endgame."""
import argparse
import json
import subprocess

import chess
from uci_probe import Engine


CRITICAL = "8/2b5/N1Pk1p2/7p/p2rp2P/P4pP1/5P2/2R1K3 w - - 2 48"
MOVE_52 = "8/2b5/N1Pk4/5p1p/p3p2P/P2r1pP1/5P2/2R3K1 w - - 2 52"


def relocated(board, start, end):
    probe = board.copy(stack=False)
    source, target = chess.parse_square(start), chess.parse_square(end)
    assert not probe.piece_at(target)
    probe.set_piece_at(target, probe.remove_piece_at(source))
    assert probe.is_valid(), probe.fen()
    return probe


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar")
    parser.add_argument("stockfish")
    args = parser.parse_args()
    critical, later = chess.Board(CRITICAL), chess.Board(MOVE_52)
    kf1, nc7 = critical.copy(), critical.copy()
    kf1.push_san("Kf1")
    nc7.push_san("Nxc7")
    black_to_move = later.copy()
    black_to_move.turn = chess.BLACK
    assert black_to_move.is_valid()
    samples = {
        "move48": critical,
        "48.Kf1": kf1,
        "48.Nxc7": nc7,
        "move52": later,
        "52:f2-g2 (unblock f-pawn)": relocated(later, "f2", "g2"),
        "52:f3-f4 (recede f-pawn)": relocated(later, "f3", "f4"),
        "52:Rd3-b3 (change rook access)": relocated(later, "d3", "b3"),
        "52:Kg1-h2 (relocate king)": relocated(later, "g1", "h2"),
        "52:Black to move": black_to_move,
        "52:Black to move, f2-g2": relocated(black_to_move, "f2", "g2"),
    }
    command = ["java", "--add-modules", "jdk.incubator.vector", "-jar", args.jar]
    with Engine([args.stockfish], {"Threads": 1, "Hash": 64}) as sf:
        for label, board in samples.items():
            result = subprocess.run(command, input=f"position fen {board.fen()}\neval\nquit\n",
                                    capture_output=True, text=True, check=True, timeout=10)
            if "eval breakdown" not in result.stdout:
                raise RuntimeError(result.stdout)
            sf.new_game()
            best, info = sf.search(board, "depth 18")
            kind, score = info["score"]
            white_score = score if board.turn == chess.WHITE else -score
            king_moves = [board.san(move) for move in board.legal_moves
                          if move.from_square == board.king(board.turn)]
            f_pawn_moves = [board.san(move) for move in board.legal_moves if move.from_square == chess.F3
                            and board.piece_at(chess.F3) == chess.Piece(chess.PAWN, board.turn)]
            print(json.dumps({"sample": label, "fen": board.fen(), "depth": info["depth"],
                              "stockfish_white": [kind, white_score],
                              "stockfish_first": board.san(best),
                              "king_moves_for_side_to_move": king_moves,
                              "f3_pawn_moves": f_pawn_moves,
                              "vex_breakdown": result.stdout.splitlines()}), flush=True)


if __name__ == "__main__":
    main()
