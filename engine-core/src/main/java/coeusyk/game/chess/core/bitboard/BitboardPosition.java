package coeusyk.game.chess.core.bitboard;

/**
 * Phase 0 placeholder for the bitboard-based position model.
 *
 * This class establishes the direction of travel for engine-core. The current
 * move generator still uses the legacy square-array board while Phase 1+ work
 * migrates to this representation.
 */
public class BitboardPosition {
    // White piece bitboards
    public long whitePawns;
    public long whiteKnights;
    public long whiteBishops;
    public long whiteRooks;
    public long whiteQueens;
    public long whiteKing;

    // Black piece bitboards
    public long blackPawns;
    public long blackKnights;
    public long blackBishops;
    public long blackRooks;
    public long blackQueens;
    public long blackKing;

    // Occupancy masks
    public long whiteOccupancy;
    public long blackOccupancy;
    public long allOccupancy;

    // Side to move and rule state
    public boolean whiteToMove;
    public int castlingRights;
    public int enPassantSquare;
    public int halfmoveClock;
    public int fullmoveNumber;
}
