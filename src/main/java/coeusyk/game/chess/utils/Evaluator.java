package coeusyk.game.chess.utils;

import coeusyk.game.chess.models.Board;
import coeusyk.game.chess.models.Piece;


public class Evaluator {

    // Standard centipawn material values indexed by piece type:
    private static final int[] PIECE_VALUES = { 0, 100, 320, 330, 500, 900, 20000 };

    /**
     * Returns the static material evaluation of the position from the perspective
     * of the side to move (positive = good for the side to move).
     */
    public static int evaluate(Board board) {
        int[] grid = board.getGrid();
        int score = 0;

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

        // Return relative to the side to move (negamax convention):
        return (board.getActiveColor() == Piece.White) ? score : -score;
    }
}
