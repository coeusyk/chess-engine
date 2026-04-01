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

    // Tempo bonus for side to move (centipawns, applied after phase interpolation)
    static int TEMPO = 15;

    // Bishop pair bonus
    static int BISHOP_PAIR_MG = 30;
    static int BISHOP_PAIR_EG = 50;

    // Rook on 7th rank bonus
    static int ROOK_7TH_MG = 20;
    static int ROOK_7TH_EG = 30;

    // Rank 7 bitboards (a8=0 convention): rank 7 = bits 8-15, rank 2 = bits 48-55
    private static final long WHITE_RANK_7 = 0x000000000000FF00L;
    private static final long BLACK_RANK_7 = 0x00FF000000000000L;

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

        MG_MOBILITY[Piece.Knight] = 5;
        MG_MOBILITY[Piece.Bishop] = 4;
        MG_MOBILITY[Piece.Rook]   = 5;
        MG_MOBILITY[Piece.Queen]  = 0;

        EG_MOBILITY[Piece.Knight] = 0;
        EG_MOBILITY[Piece.Bishop] = 2;
        EG_MOBILITY[Piece.Rook]   = 4;
        EG_MOBILITY[Piece.Queen]  = 8;

        MOBILITY_BASELINE[Piece.Knight] = 4;
        MOBILITY_BASELINE[Piece.Bishop] = 7;
        MOBILITY_BASELINE[Piece.Rook]   = 7;
        MOBILITY_BASELINE[Piece.Queen]  = 14;
    }

    public int evaluate(Board board) {
        // Material + PST scores are maintained incrementally in Board; read the cached values.
        int mgScore = board.getIncMgScore();
        int egScore = board.getIncEgScore();

        long allOccupancy = board.getWhiteOccupancy() | board.getBlackOccupancy();
        long whitePawnAtk = Attacks.whitePawnAttacks(board.getWhitePawns());
        long blackPawnAtk = Attacks.blackPawnAttacks(board.getBlackPawns());

        long whiteMobility = computeMobilityPacked(board, true, allOccupancy, blackPawnAtk);
        long blackMobility = computeMobilityPacked(board, false, allOccupancy, whitePawnAtk);

        mgScore += unpackMg(whiteMobility) - unpackMg(blackMobility);
        egScore += unpackEg(whiteMobility) - unpackEg(blackMobility);

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

        // --- Bishop pair bonus ---
        if (Long.bitCount(board.getWhiteBishops()) >= 2) {
            mgScore += BISHOP_PAIR_MG;
            egScore += BISHOP_PAIR_EG;
        }
        if (Long.bitCount(board.getBlackBishops()) >= 2) {
            mgScore -= BISHOP_PAIR_MG;
            egScore -= BISHOP_PAIR_EG;
        }

        // --- Rook on 7th rank bonus ---
        int wRook7 = Long.bitCount(board.getWhiteRooks() & WHITE_RANK_7);
        int bRook7 = Long.bitCount(board.getBlackRooks() & BLACK_RANK_7);
        mgScore += (wRook7 - bRook7) * ROOK_7TH_MG;
        egScore += (wRook7 - bRook7) * ROOK_7TH_EG;

        int phase = computePhase(board);
        egScore += MopUp.evaluate(board, phase);
        int score = (mgScore * phase + egScore * (TOTAL_PHASE - phase)) / TOTAL_PHASE;

        // --- Tempo bonus (applied after phase interpolation) ---
        score += Piece.isWhite(board.getActiveColor()) ? TEMPO : -TEMPO;

        return Piece.isWhite(board.getActiveColor()) ? score : -score;
    }

        private long computeMobilityPacked(Board board, boolean white, long allOccupancy, long enemyPawnAttacks) {
        long friendly = white ? board.getWhiteOccupancy() : board.getBlackOccupancy();
        long safeMask = ~friendly & ~enemyPawnAttacks;

        int mgMob = 0;
        int egMob = 0;

        long kn = pieceMobilityPacked(white ? board.getWhiteKnights() : board.getBlackKnights(),
            Piece.Knight, allOccupancy, safeMask);
        mgMob += unpackMg(kn);
        egMob += unpackEg(kn);

        long bi = pieceMobilityPacked(white ? board.getWhiteBishops() : board.getBlackBishops(),
            Piece.Bishop, allOccupancy, safeMask);
        mgMob += unpackMg(bi);
        egMob += unpackEg(bi);

        long ro = pieceMobilityPacked(white ? board.getWhiteRooks() : board.getBlackRooks(),
            Piece.Rook, allOccupancy, safeMask);
        mgMob += unpackMg(ro);
        egMob += unpackEg(ro);

        long qu = pieceMobilityPacked(white ? board.getWhiteQueens() : board.getBlackQueens(),
            Piece.Queen, allOccupancy, safeMask);
        mgMob += unpackMg(qu);
        egMob += unpackEg(qu);

        return packMobility(mgMob, egMob);
    }

        private long pieceMobilityPacked(long pieces, int pieceType, long allOccupancy, long safeMask) {
        int mgBonus = MG_MOBILITY[pieceType];
        int egBonus = EG_MOBILITY[pieceType];
        int baseline = MOBILITY_BASELINE[pieceType];
        int mgTotal = 0;
        int egTotal = 0;
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
            int delta = safeSquares - baseline;
            mgTotal += delta * mgBonus;
            egTotal += delta * egBonus;
            pieces &= pieces - 1;
        }
        return packMobility(mgTotal, egTotal);
    }

    private static long packMobility(int mg, int eg) {
        return (((long) mg) << 32) | (eg & 0xffffffffL);
    }

    private static int unpackMg(long packed) {
        return (int) (packed >> 32);
    }

    private static int unpackEg(long packed) {
        return (int) packed;
    }

    int computePhase(Board board) {
        int phase = 0;
        phase += Long.bitCount(board.getWhiteKnights() | board.getBlackKnights()) * PHASE_WEIGHTS[Piece.Knight];
        phase += Long.bitCount(board.getWhiteBishops() | board.getBlackBishops()) * PHASE_WEIGHTS[Piece.Bishop];
        phase += Long.bitCount(board.getWhiteRooks()   | board.getBlackRooks())   * PHASE_WEIGHTS[Piece.Rook];
        phase += Long.bitCount(board.getWhiteQueens()   | board.getBlackQueens())  * PHASE_WEIGHTS[Piece.Queen];
        return Math.min(phase, TOTAL_PHASE);
    }

    public static int mgMaterialValue(int pieceType) {
        return MG_MATERIAL[pieceType];
    }

    public static int egMaterialValue(int pieceType) {
        return EG_MATERIAL[pieceType];
    }
}
