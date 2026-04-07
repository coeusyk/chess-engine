package coeusyk.game.chess.core.eval;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Piece;

public final class MopUp {

    private MopUp() {}

    static final int[] CENTER_MANHATTAN_DISTANCE = {
        6, 5, 4, 3, 3, 4, 5, 6,
        5, 4, 3, 2, 2, 3, 4, 5,
        4, 3, 2, 1, 1, 2, 3, 4,
        3, 2, 1, 0, 0, 1, 2, 3,
        3, 2, 1, 0, 0, 1, 2, 3,
        4, 3, 2, 1, 1, 2, 3, 4,
        5, 4, 3, 2, 2, 3, 4, 5,
        6, 5, 4, 3, 3, 4, 5, 6
    };

    private static final int PHASE_THRESHOLD = 8;
    private static final int MATERIAL_THRESHOLD = 400;

    public static int evaluate(Board board, int phase) {
        if (phase > PHASE_THRESHOLD) return 0;
        if (board.getWhiteKing() == 0 || board.getBlackKing() == 0) return 0;

        int whiteMat = countMaterial(board, true);
        int blackMat = countMaterial(board, false);
        int diff = whiteMat - blackMat;
        if (Math.abs(diff) < MATERIAL_THRESHOLD) return 0;

        boolean whiteWinning = diff > 0;
        if (!hasNonPawnPiece(board, whiteWinning)) return 0;

        int winKingSq = Long.numberOfTrailingZeros(
                whiteWinning ? board.getWhiteKing() : board.getBlackKing());
        int loseKingSq = Long.numberOfTrailingZeros(
                whiteWinning ? board.getBlackKing() : board.getWhiteKing());

        int edgeBonus = CENTER_MANHATTAN_DISTANCE[loseKingSq] * 10;
        int proximityBonus = (14 - manhattanDistance(winKingSq, loseKingSq)) * 4;

        int mopUp = edgeBonus + proximityBonus;
        return whiteWinning ? mopUp : -mopUp;
    }

    private static int countMaterial(Board board, boolean white) {
        int mat = 0;
        mat += Long.bitCount(white ? board.getWhitePawns()   : board.getBlackPawns())   * Evaluator.egMaterialValue(Piece.Pawn);
        mat += Long.bitCount(white ? board.getWhiteKnights() : board.getBlackKnights()) * Evaluator.egMaterialValue(Piece.Knight);
        mat += Long.bitCount(white ? board.getWhiteBishops() : board.getBlackBishops()) * Evaluator.egMaterialValue(Piece.Bishop);
        mat += Long.bitCount(white ? board.getWhiteRooks()   : board.getBlackRooks())   * Evaluator.egMaterialValue(Piece.Rook);
        mat += Long.bitCount(white ? board.getWhiteQueens()   : board.getBlackQueens()) * Evaluator.egMaterialValue(Piece.Queen);
        return mat;
    }

    private static boolean hasNonPawnPiece(Board board, boolean white) {
        if (white) {
            return (board.getWhiteKnights() | board.getWhiteBishops()
                  | board.getWhiteRooks()   | board.getWhiteQueens()) != 0;
        }
        return (board.getBlackKnights() | board.getBlackBishops()
              | board.getBlackRooks()   | board.getBlackQueens()) != 0;
    }

    private static int manhattanDistance(int sq1, int sq2) {
        return Math.abs(sq1 % 8 - sq2 % 8) + Math.abs(sq1 / 8 - sq2 / 8);
    }
}
