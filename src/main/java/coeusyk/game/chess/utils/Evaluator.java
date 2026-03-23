package coeusyk.game.chess.utils;

import coeusyk.game.chess.models.Board;
import coeusyk.game.chess.models.Piece;


public class Evaluator {

    // Centipawn values for each piece type, indexed by Piece constant:
    // index 0=None, 1=Pawn, 2=Knight, 3=Bishop, 4=Rook, 5=Queen, 6=King
    private static final int[] PIECE_VALUES = { 0, 100, 320, 330, 500, 900, 20000 };

    /**
     * Returns a static material evaluation of the board from the
     * perspective of the side to move (positive = good for the side to move).
     */
    public static int evaluate(Board board) {
        int score = 0;
        int[] grid = board.getGrid();

        for (int sq = 0; sq < 64; sq++) {
            int piece = grid[sq];
            if (piece == Piece.None) continue;

            int value = PIECE_VALUES[Piece.type(piece)];
            if (Piece.isWhite(piece)) {
                score += value;
            } else {
                score -= value;
            }
        }

        // Return score from the perspective of the side to move
        return (board.getActiveColor() == Piece.White) ? score : -score;
    }
}
