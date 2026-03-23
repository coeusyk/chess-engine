package coeusyk.game.chess.utils;

import coeusyk.game.chess.models.Board;
import coeusyk.game.chess.models.Piece;

import java.util.Random;


/**
 * Generates Zobrist hash keys for board positions.
 * <p>
 * A fixed random table is seeded deterministically so hashes are reproducible
 * across JVM runs.  Each (piece, color, square) triple maps to a unique 64-bit
 * random value; hashing a position is a simple XOR of all applicable values.
 */
public final class ZobristHasher {

    // Random tables – indexed by piece type (0-6) then square (0-63)
    private static final long[][] WHITE_PIECE_TABLE = new long[7][64];
    private static final long[][] BLACK_PIECE_TABLE = new long[7][64];
    // One value per castling right (KQ for white, kq for black)
    private static final long[] CASTLING_TABLE = new long[4];
    // One value per en-passant file (0-7)
    private static final long[] EP_FILE_TABLE = new long[8];
    // XOR'd when it is black's turn to move
    private static final long SIDE_TO_MOVE;

    static {
        Random rng = new Random(0x123456789ABCDEF0L);
        for (int type = 0; type < 7; type++) {
            for (int sq = 0; sq < 64; sq++) {
                WHITE_PIECE_TABLE[type][sq] = rng.nextLong();
                BLACK_PIECE_TABLE[type][sq] = rng.nextLong();
            }
        }
        for (int i = 0; i < 4; i++) {
            CASTLING_TABLE[i] = rng.nextLong();
        }
        for (int i = 0; i < 8; i++) {
            EP_FILE_TABLE[i] = rng.nextLong();
        }
        SIDE_TO_MOVE = rng.nextLong();
    }

    private ZobristHasher() {}

    /**
     * Computes the Zobrist hash for the given board state.
     *
     * @param board the board to hash
     * @return a 64-bit Zobrist key
     */
    public static long computeHash(Board board) {
        long hash = 0L;
        int[] grid = board.getGrid();

        for (int sq = 0; sq < 64; sq++) {
            int piece = grid[sq];
            if (piece != Piece.None) {
                int type = Piece.type(piece);
                if (type > 0 && type <= 6) {
                    if (Piece.isWhite(piece)) {
                        hash ^= WHITE_PIECE_TABLE[type][sq];
                    } else {
                        hash ^= BLACK_PIECE_TABLE[type][sq];
                    }
                }
            }
        }

        boolean[] castling = board.getCastlingAvailability();
        for (int i = 0; i < 4; i++) {
            if (castling[i]) {
                hash ^= CASTLING_TABLE[i];
            }
        }

        int epSq = board.getEpTargetSquare();
        if (epSq > 0 && epSq < 64) {
            hash ^= EP_FILE_TABLE[epSq % 8];
        }

        if (board.getActiveColor() == Piece.Black) {
            hash ^= SIDE_TO_MOVE;
        }

        return hash;
    }
}
