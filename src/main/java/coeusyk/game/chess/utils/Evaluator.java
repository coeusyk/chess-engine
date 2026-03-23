package coeusyk.game.chess.utils;

import coeusyk.game.chess.models.Board;
import coeusyk.game.chess.models.Piece;


/**
 * Simple material-balance evaluator.
 * <p>
 * Scores are returned from the perspective of the side to move: a positive
 * value means the side to move has the advantage.
 */
public class Evaluator {

    // Centipawn values for each piece type
    private static final int[] PIECE_VALUES = {
            0,    // None
            100,  // Pawn
            320,  // Knight
            330,  // Bishop
            500,  // Rook
            900,  // Queen
            0     // King (handled separately)
    };

    /**
     * Returns a centipawn score from the perspective of the side to move.
     *
     * @param board the position to evaluate
     * @return positive if the side to move is ahead, negative if behind
     */
    public int evaluate(Board board) {
        int whiteScore = 0;
        int blackScore = 0;

        int[] grid = board.getGrid();
        for (int sq = 0; sq < 64; sq++) {
            int piece = grid[sq];
            if (piece == Piece.None) continue;

            int type = Piece.type(piece);
            if (type < 1 || type > 6) continue;

            int value = PIECE_VALUES[type];
            if (Piece.isWhite(piece)) {
                whiteScore += value;
            } else {
                blackScore += value;
            }
        }

        int score = whiteScore - blackScore;
        return (board.getActiveColor() == Piece.White) ? score : -score;
    }
}
