package coeusyk.game.chess.search;

import coeusyk.game.chess.models.Board;
import coeusyk.game.chess.models.Piece;


/**
 * Static position evaluator. Returns a score in centipawns from the perspective
 * of the side to move (positive = good for the side to move).
 */
public class Evaluator {

    private static final int PAWN_VALUE   = 100;
    private static final int KNIGHT_VALUE = 300;
    private static final int BISHOP_VALUE = 310;
    private static final int ROOK_VALUE   = 500;
    private static final int QUEEN_VALUE  = 900;
    private static final int KING_VALUE   = 10000;

    /**
     * Evaluates the board position and returns a score from the current
     * player's perspective (positive = better for the side to move).
     */
    public static int evaluate(Board board) {
        int score = 0;
        int[] grid = board.getGrid();

        for (int sq = 0; sq < 64; sq++) {
            int piece = grid[sq];
            if (piece == Piece.None) continue;

            int value = pieceValue(Piece.type(piece));
            if (Piece.isWhite(piece)) {
                score += value;
            } else {
                score -= value;
            }
        }

        // Negate when it is black's turn so the score is always from the side-to-move perspective:
        return (board.getActiveColor() == Piece.White) ? score : -score;
    }

    private static int pieceValue(int pieceType) {
        return switch (pieceType) {
            case Piece.Pawn   -> PAWN_VALUE;
            case Piece.Knight -> KNIGHT_VALUE;
            case Piece.Bishop -> BISHOP_VALUE;
            case Piece.Rook   -> ROOK_VALUE;
            case Piece.Queen  -> QUEEN_VALUE;
            case Piece.King   -> KING_VALUE;
            default           -> 0;
        };
    }
}
