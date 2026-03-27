package coeusyk.game.chess.core.eval;

import coeusyk.game.chess.core.bitboard.MagicBitboards;

/**
 * Static attack bitboard computations for evaluation.
 * Board uses a8=0 convention: index 0 = a8, index 63 = h1.
 */
public final class Attacks {

    private Attacks() {}

    private static final long NOT_A_FILE  = ~0x0101010101010101L;
    private static final long NOT_H_FILE  = ~0x8080808080808080L;
    private static final long NOT_AB_FILE = ~0x0303030303030303L;
    private static final long NOT_GH_FILE = ~0xC0C0C0C0C0C0C0C0L;

    public static long whitePawnAttacks(long pawns) {
        return ((pawns >>> 9) & NOT_H_FILE) | ((pawns >>> 7) & NOT_A_FILE);
    }

    public static long blackPawnAttacks(long pawns) {
        return ((pawns << 7) & NOT_H_FILE) | ((pawns << 9) & NOT_A_FILE);
    }

    public static long knightAttacks(int sq) {
        long bb = 1L << sq;
        long attacks = 0L;
        attacks |= (bb >>> 17) & NOT_H_FILE;
        attacks |= (bb >>> 15) & NOT_A_FILE;
        attacks |= (bb >>> 10) & NOT_GH_FILE;
        attacks |= (bb >>> 6)  & NOT_AB_FILE;
        attacks |= (bb << 6)   & NOT_GH_FILE;
        attacks |= (bb << 10)  & NOT_AB_FILE;
        attacks |= (bb << 15)  & NOT_H_FILE;
        attacks |= (bb << 17)  & NOT_A_FILE;
        return attacks;
    }

    public static long bishopAttacks(int sq, long occupancy) {
        return MagicBitboards.getBishopAttacks(sq, occupancy);
    }

    public static long rookAttacks(int sq, long occupancy) {
        return MagicBitboards.getRookAttacks(sq, occupancy);
    }

    public static long queenAttacks(int sq, long occupancy) {
        return MagicBitboards.getQueenAttacks(sq, occupancy);
    }
}
