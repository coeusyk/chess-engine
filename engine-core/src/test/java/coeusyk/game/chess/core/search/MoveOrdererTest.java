package coeusyk.game.chess.core.search;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.movegen.MovesGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

class MoveOrdererTest {

    @Test
    void capturesAreSortedByMvvLva() {
        Board board = new Board("4k3/8/8/3q1r2/4P3/8/8/4K3 w - - 0 1");
        List<Move> legalMoves = new MovesGenerator(board).getActiveMoves(board.getActiveColor());

        MoveOrderer orderer = new MoveOrderer();
        List<Move> ordered = orderer.orderMoves(board, legalMoves, 0, null, new Move[128][2], new int[7][64]);

        // e4xd5 (captures queen) should outrank e4xf5 (captures rook).
        assertEquals(36, ordered.get(0).startSquare);
        assertEquals(27, ordered.get(0).targetSquare);
    }

    @Test
    void ttMoveIsOrderedFirst() {
        Board board = new Board();
        List<Move> legalMoves = new MovesGenerator(board).getActiveMoves(board.getActiveColor());

        MoveOrderer orderer = new MoveOrderer();
        Move ttMove = findMove(legalMoves, 52, 36); // e2e4 with exact reaction
        List<Move> ordered = orderer.orderMoves(board, legalMoves, 0, ttMove, new Move[128][2], new int[7][64]);

        assertEquals(52, ordered.get(0).startSquare);
        assertEquals(36, ordered.get(0).targetSquare);
    }

    private Move findMove(List<Move> legalMoves, int startSquare, int targetSquare) {
        for (Move move : legalMoves) {
            if (move.startSquare == startSquare && move.targetSquare == targetSquare) {
                return move;
            }
        }
        fail("Expected legal move not found");
        return null;
    }
}