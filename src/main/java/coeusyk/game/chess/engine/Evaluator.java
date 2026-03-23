package coeusyk.game.chess.engine;

import coeusyk.game.chess.models.Board;
import coeusyk.game.chess.models.Piece;

/**
 * Simple material-count evaluator.
 *
 * <p>Returns a score in centipawns from the perspective of the side to move:
 * positive means the side to move is ahead, negative means it is behind.
 */
public class Evaluator {

    private static final int[] PIECE_VALUES = {
        0,    // None
        100,  // Pawn
        320,  // Knight
        330,  // Bishop
        500,  // Rook
        900,  // Queen
        0     // King (not counted in material)
    };

    /**
     * Evaluates the board position.
     *
     * @param board the position to evaluate
     * @return score in centipawns from the perspective of the side to move
     */
    public int evaluate(Board board) {
        int whiteScore = 0;
        int blackScore = 0;

        for (int sq = 0; sq < 64; sq++) {
            int piece = board.getPiece(sq);
            if (piece == Piece.None) continue;

            int pieceType = Piece.type(piece);
            int value = (pieceType >= 0 && pieceType < PIECE_VALUES.length) ? PIECE_VALUES[pieceType] : 0;

            if (Piece.isWhite(piece)) {
                whiteScore += value;
            } else if (Piece.isBlack(piece)) {
                blackScore += value;
            }
        }

        int score = whiteScore - blackScore;
        return (board.getActiveColor() == Piece.White) ? score : -score;
    }
}
