package coeusyk.game.chess.core.eval;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Piece;

public final class KingSafety {

    private KingSafety() {}

    // Shield/open-file/attacker constants are now read from EvalParams (overrideable at startup).

    static final long[] WHITE_KING_ZONE = new long[64];
    static final long[] BLACK_KING_ZONE = new long[64];

    private static final int WHITE_G1 = 62, WHITE_H1 = 63, WHITE_C1 = 58, WHITE_B1 = 57;
    private static final int BLACK_G8 = 6,  BLACK_H8 = 7,  BLACK_C8 = 2,  BLACK_B8 = 1;

    static {
        for (int sq = 0; sq < 64; sq++) {
            int row = sq / 8;
            int file = sq % 8;

            long zone = 0L;
            for (int dr = -1; dr <= 1; dr++) {
                for (int df = -1; df <= 1; df++) {
                    int r = row + dr, f = file + df;
                    if (r >= 0 && r < 8 && f >= 0 && f < 8)
                        zone |= 1L << (r * 8 + f);
                }
            }

            long wZone = zone;
            int wForward = row - 2;
            if (wForward >= 0) {
                for (int df = -1; df <= 1; df++) {
                    int f = file + df;
                    if (f >= 0 && f < 8) wZone |= 1L << (wForward * 8 + f);
                }
            }
            WHITE_KING_ZONE[sq] = wZone;

            long bZone = zone;
            int bForward = row + 2;
            if (bForward < 8) {
                for (int df = -1; df <= 1; df++) {
                    int f = file + df;
                    if (f >= 0 && f < 8) bZone |= 1L << (bForward * 8 + f);
                }
            }
            BLACK_KING_ZONE[sq] = bZone;
        }
    }

    public static int evaluate(Board board) {
        return evaluateSide(board, true) - evaluateSide(board, false);
    }

    /**
     * Returns only the pawn shield + open-file components of king safety (white minus black),
     * mg-only. Attacker penalties are computed in Evaluator's merged mobility pass.
     *
     * <p>When kings are on opposite flanks (one on files a-d, the other on e-h), defender-side
     * shield structure is discounted to avoid over-valuing static shelter in dynamic attack races.
     * Only the side currently under higher king-zone pressure is scaled.
     *
     * @param whiteAttackWeight attacker weight on Black king (from White pieces)
     * @param blackAttackWeight attacker weight on White king (from Black pieces)
     */
    public static int evaluatePawnShieldAndFiles(Board board, int whiteAttackWeight, int blackAttackWeight) {
        long wKing = board.getWhiteKing();
        long bKing = board.getBlackKing();
        int wScore = wKing != 0L ? evaluateCheapSide(board, true)  : 0;
        int bScore = bKing != 0L ? evaluateCheapSide(board, false) : 0;
        // Opposite flanks: one king on queenside (files 0-3), the other on kingside (files 4-7).
        if (wKing != 0L && bKing != 0L) {
            int wFile = Long.numberOfTrailingZeros(wKing) % 8;
            int bFile = Long.numberOfTrailingZeros(bKing) % 8;
            if ((wFile < 4) != (bFile < 4)) {
                int scale = EvalParams.OPPOSITE_FLANK_SHIELD_SCALE;
                if (blackAttackWeight > whiteAttackWeight) {
                    wScore = (wScore * scale) / 100;
                } else if (whiteAttackWeight > blackAttackWeight) {
                    bScore = (bScore * scale) / 100;
                }
            }
        }
        return wScore - bScore;
    }

    private static int evaluateCheapSide(Board board, boolean white) {
        long kingBb = white ? board.getWhiteKing() : board.getBlackKing();
        if (kingBb == 0) return 0;
        int kingSq = Long.numberOfTrailingZeros(kingBb);
        return pawnShield(board, white, kingSq) + openFiles(board, white, kingSq);
    }

    private static int evaluateSide(Board board, boolean white) {
        long kingBb = white ? board.getWhiteKing() : board.getBlackKing();
        if (kingBb == 0) return 0;
        int kingSq = Long.numberOfTrailingZeros(kingBb);
        return pawnShield(board, white, kingSq)
             + openFiles(board, white, kingSq)
             + attackerPenalty(board, white, kingSq);
    }

    private static boolean isCastled(boolean white, int kingSq) {
        if (white) {
            return kingSq == WHITE_G1 || kingSq == WHITE_H1
                || kingSq == WHITE_C1 || kingSq == WHITE_B1;
        }
        return kingSq == BLACK_G8 || kingSq == BLACK_H8
            || kingSq == BLACK_C8 || kingSq == BLACK_B8;
    }

    private static int pawnShield(Board board, boolean white, int kingSq) {
        if (!isCastled(white, kingSq)) return 0;

        long friendlyPawns = white ? board.getWhitePawns() : board.getBlackPawns();
        int file = kingSq % 8;
        int row = kingSq / 8;
        int r1 = white ? row - 1 : row + 1;
        int r2 = white ? row - 2 : row + 2;

        int bonus = 0;
        for (int df = -1; df <= 1; df++) {
            int f = file + df;
            if (f < 0 || f > 7) continue;
            if (r1 >= 0 && r1 < 8 && (friendlyPawns & (1L << (r1 * 8 + f))) != 0)
                bonus += EvalParams.SHIELD_RANK2;
            if (r2 >= 0 && r2 < 8 && (friendlyPawns & (1L << (r2 * 8 + f))) != 0)
                bonus += EvalParams.SHIELD_RANK3;
        }
        return bonus;
    }

    private static int openFiles(Board board, boolean white, int kingSq) {
        int file = kingSq % 8;
        long friendly = white ? board.getWhitePawns() : board.getBlackPawns();
        long enemy = white ? board.getBlackPawns() : board.getWhitePawns();

        int penalty = 0;
        for (int df = -1; df <= 1; df++) {
            int f = file + df;
            if (f < 0 || f > 7) continue;
            long fileMask = 0x0101010101010101L << f;
            if ((friendly & fileMask) == 0) {
                penalty -= (enemy & fileMask) == 0 ? EvalParams.OPEN_FILE_PENALTY : EvalParams.HALF_OPEN_FILE_PENALTY;
            }
        }
        return penalty;
    }

    // Non-linear mapping from attacker-weight sum to centipawn penalty.
    // Replaces the old -(w*w/4) integer formula which truncated to 0 for w<2
    // and grew quadratically without bound for large multi-attacker positions.
    // Table is capped at 50 cp. Mirrors PositionFeatures.SAFETY_TABLE in engine-tuner.
    static final int[] SAFETY_TABLE = {
        0, 0, 1, 2, 3, 5, 7, 9, 12, 15, 18, 22, 26, 30, 35, 40, 45, 50
    };

    /**
     * Converts a pre-computed attacker-weight sum into a centipawn penalty
     * using the non-linear SAFETY_TABLE and {@link EvalParams#KING_SAFETY_SCALE}.
     *
     * <p>Called from {@link Evaluator#evaluate(Board)} after the merged
     * mobility+attack pass has accumulated the weight in
     * {@code tempWhiteAttackWeight} / {@code tempBlackAttackWeight}.
     *
     * @param atkWeight accumulated attacker weight (sum of per-piece ATK_WEIGHT_* values)
     * @return positive centipawn penalty (caller applies sign convention)
     */
    public static int safetyTablePenalty(int atkWeight) {
        int base = atkWeight < SAFETY_TABLE.length
                 ? SAFETY_TABLE[atkWeight]
                 : SAFETY_TABLE[SAFETY_TABLE.length - 1];
        return base * EvalParams.KING_SAFETY_SCALE / 100;
    }

    private static int attackerPenalty(Board board, boolean white, int kingSq) {
        long zone = white ? WHITE_KING_ZONE[kingSq] : BLACK_KING_ZONE[kingSq];
        long allOcc = board.getWhiteOccupancy() | board.getBlackOccupancy();

        long eKnights = white ? board.getBlackKnights() : board.getWhiteKnights();
        long eBishops = white ? board.getBlackBishops() : board.getWhiteBishops();
        long eRooks   = white ? board.getBlackRooks()   : board.getWhiteRooks();
        long eQueens  = white ? board.getBlackQueens()   : board.getWhiteQueens();

        int w = 0;
        w += countAttackers(eKnights, Piece.Knight, zone, allOcc) * EvalParams.ATK_WEIGHT_KNIGHT;
        w += countAttackers(eBishops, Piece.Bishop, zone, allOcc) * EvalParams.ATK_WEIGHT_BISHOP;
        w += countAttackers(eRooks,   Piece.Rook,   zone, allOcc) * EvalParams.ATK_WEIGHT_ROOK;
        w += countAttackers(eQueens,  Piece.Queen,  zone, allOcc) * EvalParams.ATK_WEIGHT_QUEEN;

        int base = w < SAFETY_TABLE.length ? SAFETY_TABLE[w] : SAFETY_TABLE[SAFETY_TABLE.length - 1];
        return -(base * EvalParams.KING_SAFETY_SCALE / 100);
    }

    private static int countAttackers(long pieces, int pieceType, long zone, long allOcc) {
        int count = 0;
        while (pieces != 0) {
            int sq = Long.numberOfTrailingZeros(pieces);
            long attacks;
            switch (pieceType) {
                case Piece.Knight: attacks = Attacks.knightAttacks(sq); break;
                case Piece.Bishop: attacks = Attacks.bishopAttacks(sq, allOcc); break;
                case Piece.Rook:   attacks = Attacks.rookAttacks(sq, allOcc); break;
                case Piece.Queen:  attacks = Attacks.queenAttacks(sq, allOcc); break;
                default: attacks = 0L;
            }
            if ((attacks & zone) != 0) count++;
            pieces &= pieces - 1;
        }
        return count;
    }
}
