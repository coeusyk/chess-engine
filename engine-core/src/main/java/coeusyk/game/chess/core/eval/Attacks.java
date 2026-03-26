package coeusyk.game.chess.core.eval;

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

    // Direction offsets matching MovesGenerator convention (a8=0):
    // 0=North(-8), 1=East(+1), 2=South(+8), 3=West(-1),
    // 4=NW(-9), 5=NE(-7), 6=SE(+9), 7=SW(+7)
    private static final int[] DIRECTION_OFFSETS = { -8, 1, 8, -1, -9, -7, 9, 7 };
    private static final int[][] SQUARES_TO_EDGE = new int[64][8];

    static {
        for (int rank = 0; rank < 8; rank++) {
            for (int file = 0; file < 8; file++) {
                int sq = rank * 8 + file;
                int top = rank;
                int bottom = 7 - rank;
                int left = file;
                int right = 7 - file;
                SQUARES_TO_EDGE[sq] = new int[]{
                        top, right, bottom, left,
                        Math.min(top, left), Math.min(top, right),
                        Math.min(bottom, right), Math.min(bottom, left)
                };
            }
        }
    }

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
        return slidingAttacks(sq, occupancy, 4, 7);
    }

    public static long rookAttacks(int sq, long occupancy) {
        return slidingAttacks(sq, occupancy, 0, 3);
    }

    public static long queenAttacks(int sq, long occupancy) {
        return slidingAttacks(sq, occupancy, 0, 7);
    }

    private static long slidingAttacks(int sq, long occupancy, int startDir, int endDir) {
        long attacks = 0L;
        for (int dir = startDir; dir <= endDir; dir++) {
            int offset = DIRECTION_OFFSETS[dir];
            int maxDist = SQUARES_TO_EDGE[sq][dir];
            for (int dist = 1; dist <= maxDist; dist++) {
                int target = sq + offset * dist;
                attacks |= 1L << target;
                if ((occupancy & (1L << target)) != 0) break; // blocked
            }
        }
        return attacks;
    }
}
