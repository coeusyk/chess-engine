package coeusyk.game.chess.core.bitboard;

/**
 * Precomputed attack tables for knights and kings.
 * Each entry is a bitboard of squares attacked by the piece on that square.
 * Used for O(1) non-sliding piece attack detection in isSquareAttackedBy().
 */
public final class AttackTables {

    public static final long[] KNIGHT_ATTACKS = new long[64];
    public static final long[] KING_ATTACKS   = new long[64];

    static {
        for (int sq = 0; sq < 64; sq++) {
            int rank = sq / 8;
            int file = sq % 8;
            long attacks = 0L;

            // Knight offsets: (±2,±1) and (±1,±2) in (rank,file) deltas.
            // Positive rank = downward on the board (toward larger sq index).
            int[][] knightDeltas = {
                {-2, -1}, {-2, +1}, {-1, -2}, {-1, +2},
                {+1, -2}, {+1, +2}, {+2, -1}, {+2, +1}
            };
            for (int[] d : knightDeltas) {
                int tr = rank + d[0];
                int tf = file + d[1];
                if (tr >= 0 && tr < 8 && tf >= 0 && tf < 8) {
                    attacks |= 1L << (tr * 8 + tf);
                }
            }
            KNIGHT_ATTACKS[sq] = attacks;

            attacks = 0L;
            // King offsets: all 8 adjacent squares.
            for (int dr = -1; dr <= 1; dr++) {
                for (int df = -1; df <= 1; df++) {
                    if (dr == 0 && df == 0) continue;
                    int tr = rank + dr;
                    int tf = file + df;
                    if (tr >= 0 && tr < 8 && tf >= 0 && tf < 8) {
                        attacks |= 1L << (tr * 8 + tf);
                    }
                }
            }
            KING_ATTACKS[sq] = attacks;
        }
    }

    private AttackTables() {}
}
