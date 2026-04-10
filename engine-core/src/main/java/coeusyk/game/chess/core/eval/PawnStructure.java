package coeusyk.game.chess.core.eval;

public final class PawnStructure {

    private PawnStructure() {}

    private static final int[] PASSED_MG = {0, 16, 1, 1, 1, 1, 2, 0};
    private static final int[] PASSED_EG = {0, 1, 9, 28, 48, 103, 96, 0};

    private static final int ISOLATED_MG = 11;
    private static final int ISOLATED_EG = 4;

    private static final int DOUBLED_MG = -2;
    private static final int DOUBLED_EG = 23;

    private static final long NOT_A_FILE = ~0x0101010101010101L;
    private static final long NOT_H_FILE = ~0x8080808080808080L;

    private static final long[] WHITE_PASSED_MASKS = new long[64];
    private static final long[] BLACK_PASSED_MASKS = new long[64];

    static {
        for (int sq = 0; sq < 64; sq++) {
            int file = sq % 8;
            int row = sq / 8;

            long wMask = 0L;
            for (int r = 0; r < row; r++) {
                for (int f = Math.max(0, file - 1); f <= Math.min(7, file + 1); f++) {
                    wMask |= 1L << (r * 8 + f);
                }
            }
            WHITE_PASSED_MASKS[sq] = wMask;

            long bMask = 0L;
            for (int r = row + 1; r < 8; r++) {
                for (int f = Math.max(0, file - 1); f <= Math.min(7, file + 1); f++) {
                    bMask |= 1L << (r * 8 + f);
                }
            }
            BLACK_PASSED_MASKS[sq] = bMask;
        }
    }

    public static int[] evaluate(long whitePawns, long blackPawns) {
        int mg = 0, eg = 0;

        int[] wp = passedPawnScores(whitePawns, blackPawns, true);
        int[] bp = passedPawnScores(blackPawns, whitePawns, false);
        mg += wp[0] - bp[0];
        eg += wp[1] - bp[1];

        int wi = isolatedCount(whitePawns);
        int bi = isolatedCount(blackPawns);
        mg -= (wi - bi) * ISOLATED_MG;
        eg -= (wi - bi) * ISOLATED_EG;

        int wd = doubledCount(whitePawns);
        int bd = doubledCount(blackPawns);
        mg -= (wd - bd) * DOUBLED_MG;
        eg -= (wd - bd) * DOUBLED_EG;

        return new int[]{mg, eg};
    }

    private static int[] passedPawnScores(long friendly, long enemy, boolean white) {
        int mg = 0, eg = 0;
        long[] masks = white ? WHITE_PASSED_MASKS : BLACK_PASSED_MASKS;
        long temp = friendly;
        while (temp != 0) {
            int sq = Long.numberOfTrailingZeros(temp);
            if ((enemy & masks[sq]) == 0) {
                int row = sq / 8;
                int idx = white ? (7 - row) : row;
                mg += PASSED_MG[idx];
                eg += PASSED_EG[idx];
            }
            temp &= temp - 1;
        }
        return new int[]{mg, eg};
    }

    private static int isolatedCount(long pawns) {
        long fill = pawns;
        fill |= fill << 8;
        fill |= fill << 16;
        fill |= fill << 32;
        fill |= fill >>> 8;
        fill |= fill >>> 16;
        fill |= fill >>> 32;
        long adjacent = ((fill & NOT_A_FILE) >>> 1) | ((fill & NOT_H_FILE) << 1);
        return Long.bitCount(pawns & ~adjacent);
    }

    private static int doubledCount(long pawns) {
        long fill = pawns;
        fill |= fill << 8;
        fill |= fill << 16;
        fill |= fill << 32;
        fill |= fill >>> 8;
        fill |= fill >>> 16;
        fill |= fill >>> 32;
        return Long.bitCount(pawns) - Long.bitCount(fill & 0xFFL);
    }
}
