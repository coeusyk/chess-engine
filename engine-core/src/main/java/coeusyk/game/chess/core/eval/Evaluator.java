package coeusyk.game.chess.core.eval;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Piece;

public class Evaluator {

    private static final int[] MG_MATERIAL = new int[7];
    private static final int[] EG_MATERIAL = new int[7];

    static {
        MG_MATERIAL[Piece.Pawn]   = 82;
        MG_MATERIAL[Piece.Knight] = 337;
        MG_MATERIAL[Piece.Bishop] = 365;
        MG_MATERIAL[Piece.Rook]   = 477;
        MG_MATERIAL[Piece.Queen]  = 1025;
        MG_MATERIAL[Piece.King]   = 0;

        EG_MATERIAL[Piece.Pawn]   = 94;
        EG_MATERIAL[Piece.Knight] = 281;
        EG_MATERIAL[Piece.Bishop] = 297;
        EG_MATERIAL[Piece.Rook]   = 512;
        EG_MATERIAL[Piece.Queen]  = 936;
        EG_MATERIAL[Piece.King]   = 0;
    }

    public int evaluate(Board board) {
        int mgScore = 0;
        int egScore = 0;

        mgScore += computeMaterial(board, true) - computeMaterial(board, false);
        egScore += computeMaterialEg(board, true) - computeMaterialEg(board, false);

        // Until tapered eval is wired in, return mgScore directly
        int score = mgScore;

        return Piece.isWhite(board.getActiveColor()) ? score : -score;
    }

    private int computeMaterial(Board board, boolean white) {
        int material = 0;
        material += Long.bitCount(white ? board.getWhitePawns() : board.getBlackPawns()) * MG_MATERIAL[Piece.Pawn];
        material += Long.bitCount(white ? board.getWhiteKnights() : board.getBlackKnights()) * MG_MATERIAL[Piece.Knight];
        material += Long.bitCount(white ? board.getWhiteBishops() : board.getBlackBishops()) * MG_MATERIAL[Piece.Bishop];
        material += Long.bitCount(white ? board.getWhiteRooks() : board.getBlackRooks()) * MG_MATERIAL[Piece.Rook];
        material += Long.bitCount(white ? board.getWhiteQueens() : board.getBlackQueens()) * MG_MATERIAL[Piece.Queen];
        return material;
    }

    private int computeMaterialEg(Board board, boolean white) {
        int material = 0;
        material += Long.bitCount(white ? board.getWhitePawns() : board.getBlackPawns()) * EG_MATERIAL[Piece.Pawn];
        material += Long.bitCount(white ? board.getWhiteKnights() : board.getBlackKnights()) * EG_MATERIAL[Piece.Knight];
        material += Long.bitCount(white ? board.getWhiteBishops() : board.getBlackBishops()) * EG_MATERIAL[Piece.Bishop];
        material += Long.bitCount(white ? board.getWhiteRooks() : board.getBlackRooks()) * EG_MATERIAL[Piece.Rook];
        material += Long.bitCount(white ? board.getWhiteQueens() : board.getBlackQueens()) * EG_MATERIAL[Piece.Queen];
        return material;
    }

    public static int mgMaterialValue(int pieceType) {
        return MG_MATERIAL[pieceType];
    }

    public static int egMaterialValue(int pieceType) {
        return EG_MATERIAL[pieceType];
    }
}
