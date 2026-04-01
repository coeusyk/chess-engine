package coeusyk.game.chess.core.eval;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Piece;

public final class KingSafety {

    private KingSafety() {}

    private static final int SHIELD_RANK_2_BONUS = 12;
    private static final int SHIELD_RANK_3_BONUS = 8;

    private static final int OPEN_FILE_PENALTY = 45;
    private static final int HALF_OPEN_FILE_PENALTY = 15;

    private static final int[] ATTACKER_WEIGHT = new int[7];

    private static final long[] WHITE_KING_ZONE = new long[64];
    private static final long[] BLACK_KING_ZONE = new long[64];

    private static final int WHITE_G1 = 62, WHITE_H1 = 63, WHITE_C1 = 58, WHITE_B1 = 57;
    private static final int BLACK_G8 = 6,  BLACK_H8 = 7,  BLACK_C8 = 2,  BLACK_B8 = 1;

    static {
        ATTACKER_WEIGHT[Piece.Knight] = 6;
        ATTACKER_WEIGHT[Piece.Bishop] = 4;
        ATTACKER_WEIGHT[Piece.Rook]   = 5;
        ATTACKER_WEIGHT[Piece.Queen]  = 7;

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
                bonus += SHIELD_RANK_2_BONUS;
            if (r2 >= 0 && r2 < 8 && (friendlyPawns & (1L << (r2 * 8 + f))) != 0)
                bonus += SHIELD_RANK_3_BONUS;
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
                penalty -= (enemy & fileMask) == 0 ? OPEN_FILE_PENALTY : HALF_OPEN_FILE_PENALTY;
            }
        }
        return penalty;
    }

    private static int attackerPenalty(Board board, boolean white, int kingSq) {
        long zone = white ? WHITE_KING_ZONE[kingSq] : BLACK_KING_ZONE[kingSq];
        long allOcc = board.getWhiteOccupancy() | board.getBlackOccupancy();

        long eKnights = white ? board.getBlackKnights() : board.getWhiteKnights();
        long eBishops = white ? board.getBlackBishops() : board.getWhiteBishops();
        long eRooks   = white ? board.getBlackRooks()   : board.getWhiteRooks();
        long eQueens  = white ? board.getBlackQueens()   : board.getWhiteQueens();

        int w = 0;
        w += countAttackers(eKnights, Piece.Knight, zone, allOcc) * ATTACKER_WEIGHT[Piece.Knight];
        w += countAttackers(eBishops, Piece.Bishop, zone, allOcc) * ATTACKER_WEIGHT[Piece.Bishop];
        w += countAttackers(eRooks,   Piece.Rook,   zone, allOcc) * ATTACKER_WEIGHT[Piece.Rook];
        w += countAttackers(eQueens,  Piece.Queen,  zone, allOcc) * ATTACKER_WEIGHT[Piece.Queen];

        return -(w * w / 4);
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
