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

        mgScore += computeMaterialAndPst(board, true, true) - computeMaterialAndPst(board, false, true);
        egScore += computeMaterialAndPst(board, true, false) - computeMaterialAndPst(board, false, false);

        // Until tapered eval is wired in, return mgScore directly
        int score = mgScore;

        return Piece.isWhite(board.getActiveColor()) ? score : -score;
    }

    private int computeMaterialAndPst(Board board, boolean white, boolean mg) {
        int score = 0;
        int[] material = mg ? MG_MATERIAL : EG_MATERIAL;

        score += sumPiecePst(white ? board.getWhitePawns() : board.getBlackPawns(),
                Piece.Pawn, white, mg, material);
        score += sumPiecePst(white ? board.getWhiteKnights() : board.getBlackKnights(),
                Piece.Knight, white, mg, material);
        score += sumPiecePst(white ? board.getWhiteBishops() : board.getBlackBishops(),
                Piece.Bishop, white, mg, material);
        score += sumPiecePst(white ? board.getWhiteRooks() : board.getBlackRooks(),
                Piece.Rook, white, mg, material);
        score += sumPiecePst(white ? board.getWhiteQueens() : board.getBlackQueens(),
                Piece.Queen, white, mg, material);
        score += sumPiecePst(white ? board.getWhiteKing() : board.getBlackKing(),
                Piece.King, white, mg, material);

        return score;
    }

    private int sumPiecePst(long bitboard, int pieceType, boolean white, boolean mg, int[] material) {
        int score = 0;
        while (bitboard != 0) {
            int square = Long.numberOfTrailingZeros(bitboard);
            score += material[pieceType];
            int pstSquare = white ? (square ^ 56) : square;
            score += mg ? PieceSquareTables.mg(pieceType, pstSquare)
                        : PieceSquareTables.eg(pieceType, pstSquare);
            bitboard &= bitboard - 1; // clear LSB
        }
        return score;
    }

    public static int mgMaterialValue(int pieceType) {
        return MG_MATERIAL[pieceType];
    }

    public static int egMaterialValue(int pieceType) {
        return EG_MATERIAL[pieceType];
    }
}
