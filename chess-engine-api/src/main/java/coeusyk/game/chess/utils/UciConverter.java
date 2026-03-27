package coeusyk.game.chess.utils;

import coeusyk.game.chess.core.models.Move;

/**
 * Shared UCI notation helpers used by both the game and analysis services.
 */
public final class UciConverter {

    private UciConverter() {}

    public static String toUci(Move move) {
        StringBuilder sb = new StringBuilder();
        sb.append(squareToUci(move.startSquare));
        sb.append(squareToUci(move.targetSquare));
        if ("promote-q".equals(move.reaction)) sb.append('q');
        else if ("promote-r".equals(move.reaction)) sb.append('r');
        else if ("promote-b".equals(move.reaction)) sb.append('b');
        else if ("promote-n".equals(move.reaction)) sb.append('n');
        return sb.toString();
    }

    public static String squareToUci(int square) {
        int file = square % 8;
        int rank = 8 - (square / 8);
        return "" + (char) ('a' + file) + rank;
    }
}
