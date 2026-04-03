package coeusyk.game.chess.core.bitboard;

import coeusyk.game.chess.core.models.Piece;
import java.util.Random;

/**
 * Zobrist hashing for chess positions.
 * 
 * Generates and manages random 64-bit keys for:
 * - Each piece type on each square (12 types × 64 squares)
 * - Side-to-move flag (white/black)
 * - Castling rights (4 bits: KQkq)
 * - En passant files (8 files, -1 for none)
 *
 * Hash is computed incrementally via XOR operations during make/unmake.
 * This ensures O(1) hash updates instead of O(n) recomputation.
 */
public class ZobristHash {
    // Zobrist keys indexed as [pieceType * 2 + colorOffset][square]
    // where colorOffset = 0 for White, 1 for Black
    private static final long[][] PIECE_SQUARE_KEYS = new long[12][64];
    
    // Zobrist key for black to move (white is implicit by not including this)
    private static final long BLACK_TO_MOVE_KEY;
    
    // Zobrist keys for castling rights (4 bits: WK, WQ, BK, BQ)
    private static final long[] CASTLING_KEYS = new long[16];  // 2^4 combinations
    
    // Zobrist keys for en passant file (0-7 for files a-h, 8 for no EP)
    private static final long[] EN_PASSANT_KEYS = new long[9];
    
    static {
        // Initialize random number generator with fixed seed for reproducibility
        Random random = new Random(42L);
        
        // Initialize piece-square keys
        for (int piece = 0; piece < 12; piece++) {
            for (int square = 0; square < 64; square++) {
                PIECE_SQUARE_KEYS[piece][square] = random.nextLong();
            }
        }
        
        // Initialize side-to-move key
        BLACK_TO_MOVE_KEY = random.nextLong();
        
        // Initialize castling keys for all 16 combinations
        for (int i = 0; i < 16; i++) {
            CASTLING_KEYS[i] = random.nextLong();
        }
        
        // Initialize en passant keys
        for (int i = 0; i < 9; i++) {
            EN_PASSANT_KEYS[i] = random.nextLong();
        }
    }
    
    /**
     * Get the Zobrist key for a piece on a square.
     * @param piece Piece ID (combination of type and color)
     * @param square Square (0-63)
     * @return The Zobrist key for this piece-square combination
     */
    public static long getKeyForPieceSquare(int piece, int square) {
        if (piece == Piece.None) {
            return 0L;
        }
        
        int pieceType = Piece.type(piece);
        int colorOffset = Piece.isWhite(piece) ? 0 : 1;
        int keyIndex = (pieceType - 1) * 2 + colorOffset;
        
        return PIECE_SQUARE_KEYS[keyIndex][square];
    }
    
    /**
     * Get the Zobrist key for black to move.
     * @return The key (XOR with hash if it's black's turn)
     */
    public static long getKeyForBlackToMove() {
        return BLACK_TO_MOVE_KEY;
    }
    
    /**
     * Get the Zobrist key for castling rights.
     * @param castlingRights 4-bit integer where bit i = castling right available
     *                       Bit 0: White kingside
     *                       Bit 1: White queenside
     *                       Bit 2: Black kingside
     *                       Bit 3: Black queenside
     * @return The combined Zobrist key for these castling rights
     */
    public static long getKeyForCastlingRights(int castlingRights) {
        return CASTLING_KEYS[castlingRights & 0xF];
    }
    
    /**
     * Get the Zobrist key for en passant file.
     * @param epFile En passant file (0-7 for files a-h), 8 for no en passant
     * @return The Zobrist key for this en passant file
     */
    public static long getKeyForEnPassantFile(int epFile) {
        if (epFile < 0 || epFile > 8) {
            return 0L;
        }
        return EN_PASSANT_KEYS[epFile];
    }
    
    /**
     * Convert en passant target square to file index.
     * @param epTargetSquare En passant target square (0-63) or -1 for none
     * @return File index (0-7) or 8 for no en passant
     */
    public static int getEPFileFromTargetSquare(int epTargetSquare) {
        if (epTargetSquare < 0) {
            return 8;
        }
        return epTargetSquare % 8;
    }
}
