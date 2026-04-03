package coeusyk.game.chess.core.eval;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Piece;

public class Evaluator {

    private static final int TOTAL_PHASE = 24;

    // Pawn hash table: caches PawnStructure.evaluate() results keyed by pawn Zobrist hash.
    // 16K entries at 2 ints each = ~128KB. Pawn structure rarely changes between sibling nodes.
    private static final int PAWN_TABLE_SIZE = 1 << 14; // 16384 entries
    private static final int PAWN_TABLE_MASK = PAWN_TABLE_SIZE - 1;
    private final long[] pawnTableKeys   = new long[PAWN_TABLE_SIZE];
    private final int[]  pawnTableMg     = new int[PAWN_TABLE_SIZE];
    private final int[]  pawnTableEg     = new int[PAWN_TABLE_SIZE];

    private static final int[] PHASE_WEIGHTS = new int[7];
    private static final int[] MG_MATERIAL = new int[7];
    private static final int[] EG_MATERIAL = new int[7];

    // Mobility bonus per safe square (centipawns)
    private static final int[] MG_MOBILITY = new int[7];
    private static final int[] EG_MOBILITY = new int[7];
    // Baseline: subtract this many safe squares before applying bonus
    private static final int[] MOBILITY_BASELINE = new int[7];

    static {
        PHASE_WEIGHTS[Piece.Knight] = 1;
        PHASE_WEIGHTS[Piece.Bishop] = 1;
        PHASE_WEIGHTS[Piece.Rook]   = 2;
        PHASE_WEIGHTS[Piece.Queen]  = 4;

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

        MG_MOBILITY[Piece.Knight] = 4;
        MG_MOBILITY[Piece.Bishop] = 3;
        MG_MOBILITY[Piece.Rook]   = 2;
        MG_MOBILITY[Piece.Queen]  = 1;

        EG_MOBILITY[Piece.Knight] = 4;
        EG_MOBILITY[Piece.Bishop] = 3;
        EG_MOBILITY[Piece.Rook]   = 1;
        EG_MOBILITY[Piece.Queen]  = 2;

        MOBILITY_BASELINE[Piece.Knight] = 4;
        MOBILITY_BASELINE[Piece.Bishop] = 7;
        MOBILITY_BASELINE[Piece.Rook]   = 7;
        MOBILITY_BASELINE[Piece.Queen]  = 14;
    }

    public int evaluate(Board board) {
        int mgScore = 0;
        int egScore = 0;

        mgScore += computeMaterialAndPst(board, true, true) - computeMaterialAndPst(board, false, true);
        egScore += computeMaterialAndPst(board, true, false) - computeMaterialAndPst(board, false, false);

        long allOccupancy = board.getWhiteOccupancy() | board.getBlackOccupancy();
        long whitePawnAtk = Attacks.whitePawnAttacks(board.getWhitePawns());
        long blackPawnAtk = Attacks.blackPawnAttacks(board.getBlackPawns());

        int[] whiteMobility = computeMobility(board, true, allOccupancy, blackPawnAtk);
        int[] blackMobility = computeMobility(board, false, allOccupancy, whitePawnAtk);

        mgScore += whiteMobility[0] - blackMobility[0];
        egScore += whiteMobility[1] - blackMobility[1];

        int pawnMg, pawnEg;
        long pawnKey = board.getPawnZobristHash();
        int pawnIdx = (int) (pawnKey & PAWN_TABLE_MASK);
        if (pawnTableKeys[pawnIdx] == pawnKey) {
            pawnMg = pawnTableMg[pawnIdx];
            pawnEg = pawnTableEg[pawnIdx];
        } else {
            int[] pawnStructure = PawnStructure.evaluate(board.getWhitePawns(), board.getBlackPawns());
            pawnMg = pawnStructure[0];
            pawnEg = pawnStructure[1];
            pawnTableKeys[pawnIdx] = pawnKey;
            pawnTableMg[pawnIdx]   = pawnMg;
            pawnTableEg[pawnIdx]   = pawnEg;
        }
        mgScore += pawnMg;
        egScore += pawnEg;

        mgScore += KingSafety.evaluate(board);

        int phase = computePhase(board);
        egScore += MopUp.evaluate(board, phase);
        int score = (mgScore * phase + egScore * (TOTAL_PHASE - phase)) / TOTAL_PHASE;

        return Piece.isWhite(board.getActiveColor()) ? score : -score;
    }

    private int[] computeMobility(Board board, boolean white, long allOccupancy, long enemyPawnAttacks) {
        long friendly = white ? board.getWhiteOccupancy() : board.getBlackOccupancy();
        long safeMask = ~friendly & ~enemyPawnAttacks;

        int mgMob = 0;
        int egMob = 0;

        mgMob += pieceMobility(white ? board.getWhiteKnights() : board.getBlackKnights(),
                Piece.Knight, allOccupancy, safeMask, true);
        egMob += pieceMobility(white ? board.getWhiteKnights() : board.getBlackKnights(),
                Piece.Knight, allOccupancy, safeMask, false);

        mgMob += pieceMobility(white ? board.getWhiteBishops() : board.getBlackBishops(),
                Piece.Bishop, allOccupancy, safeMask, true);
        egMob += pieceMobility(white ? board.getWhiteBishops() : board.getBlackBishops(),
                Piece.Bishop, allOccupancy, safeMask, false);

        mgMob += pieceMobility(white ? board.getWhiteRooks() : board.getBlackRooks(),
                Piece.Rook, allOccupancy, safeMask, true);
        egMob += pieceMobility(white ? board.getWhiteRooks() : board.getBlackRooks(),
                Piece.Rook, allOccupancy, safeMask, false);

        mgMob += pieceMobility(white ? board.getWhiteQueens() : board.getBlackQueens(),
                Piece.Queen, allOccupancy, safeMask, true);
        egMob += pieceMobility(white ? board.getWhiteQueens() : board.getBlackQueens(),
                Piece.Queen, allOccupancy, safeMask, false);

        return new int[]{ mgMob, egMob };
    }

    private int pieceMobility(long pieces, int pieceType, long allOccupancy, long safeMask, boolean mg) {
        int bonus = mg ? MG_MOBILITY[pieceType] : EG_MOBILITY[pieceType];
        int baseline = MOBILITY_BASELINE[pieceType];
        int total = 0;
        while (pieces != 0) {
            int sq = Long.numberOfTrailingZeros(pieces);
            long attacks;
            switch (pieceType) {
                case Piece.Knight: attacks = Attacks.knightAttacks(sq); break;
                case Piece.Bishop: attacks = Attacks.bishopAttacks(sq, allOccupancy); break;
                case Piece.Rook:   attacks = Attacks.rookAttacks(sq, allOccupancy); break;
                case Piece.Queen:  attacks = Attacks.queenAttacks(sq, allOccupancy); break;
                default: attacks = 0L;
            }
            int safeSquares = Long.bitCount(attacks & safeMask);
            total += (safeSquares - baseline) * bonus;
            pieces &= pieces - 1;
        }
        return total;
    }

    int computePhase(Board board) {
        int phase = 0;
        phase += Long.bitCount(board.getWhiteKnights() | board.getBlackKnights()) * PHASE_WEIGHTS[Piece.Knight];
        phase += Long.bitCount(board.getWhiteBishops() | board.getBlackBishops()) * PHASE_WEIGHTS[Piece.Bishop];
        phase += Long.bitCount(board.getWhiteRooks()   | board.getBlackRooks())   * PHASE_WEIGHTS[Piece.Rook];
        phase += Long.bitCount(board.getWhiteQueens()   | board.getBlackQueens())  * PHASE_WEIGHTS[Piece.Queen];
        return Math.min(phase, TOTAL_PHASE);
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
            int pstSquare = white ? square : (square ^ 56);
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
