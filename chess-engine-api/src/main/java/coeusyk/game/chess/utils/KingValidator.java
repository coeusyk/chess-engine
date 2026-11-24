package coeusyk.game.chess.utils;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.InvalidFenException;

/**
 * Search and move generation assume one king per side, so a position without one must be refused
 * at the API boundary as a bad request. The parser itself stays permissive because engine-core
 * tests and tools build partial positions on purpose.
 */
public final class KingValidator {
    private KingValidator() {}

    public static Board requireKings(Board board) {
        if (Long.bitCount(board.getWhiteKing()) != 1 || Long.bitCount(board.getBlackKing()) != 1) {
            throw new InvalidFenException("expected exactly one king per side");
        }
        return board;
    }
}
