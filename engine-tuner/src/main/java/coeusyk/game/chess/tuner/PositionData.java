package coeusyk.game.chess.tuner;

/**
 * Read-only view of a chess position's bitboard data.
 *
 * <p>Both {@link TunerPosition} (compact, heap-friendly) and the engine's
 * {@code Board} can supply these getters. {@link TunerEvaluator}'s static-eval
 * helpers are written against this interface so that they work with either
 * source without duplication.
 */
interface PositionData {

    long getWhitePawns();
    long getBlackPawns();
    long getWhiteKnights();
    long getBlackKnights();
    long getWhiteBishops();
    long getBlackBishops();
    long getWhiteRooks();
    long getBlackRooks();
    long getWhiteQueens();
    long getBlackQueens();
    long getWhiteKing();
    long getBlackKing();
    long getWhiteOccupancy();
    long getBlackOccupancy();
    int  getActiveColor();
}
