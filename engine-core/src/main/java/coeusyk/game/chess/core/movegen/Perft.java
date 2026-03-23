package coeusyk.game.chess.core.movegen;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;

import java.util.ArrayList;

public final class Perft {

    private Perft() {
    }

    public static long countNodes(Board board, int depth) {
        if (depth < 0) {
            throw new IllegalArgumentException("depth must be >= 0");
        }

        if (depth == 0) {
            return 1L;
        }

        MovesGenerator movesGenerator = new MovesGenerator(board);
        ArrayList<Move> legalMoves = movesGenerator.getActiveMoves(board.getActiveColor());

        if (depth == 1) {
            return legalMoves.size();
        }

        long nodes = 0L;
        for (Move move : legalMoves) {
            board.makeMove(move);
            nodes += countNodes(board, depth - 1);
            board.unmakeMove();
        }

        return nodes;
    }
}
