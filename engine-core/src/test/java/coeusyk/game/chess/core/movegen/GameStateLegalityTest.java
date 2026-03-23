package coeusyk.game.chess.core.movegen;

import coeusyk.game.chess.core.models.Board;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GameStateLegalityTest {

    @Test
    void detectsCheckWithoutCheckmate() {
        Board board = new Board("7k/6Q1/8/8/8/8/8/7K b - - 0 1");

        assertTrue(board.isActiveColorInCheck());
        assertFalse(board.isCheckmate());
        assertFalse(board.isStalemate());
    }

    @Test
    void detectsCheckmateWhenInCheckAndNoLegalMoves() {
        Board board = new Board("7k/6Q1/5K2/8/8/8/8/8 b - - 0 1");

        assertTrue(board.isActiveColorInCheck());
        assertTrue(board.isCheckmate());
        assertFalse(board.isStalemate());
    }

    @Test
    void detectsStalemateWhenNotInCheckAndNoLegalMoves() {
        Board board = new Board("7k/5K2/6Q1/8/8/8/8/8 b - - 0 1");

        assertFalse(board.isActiveColorInCheck());
        assertFalse(board.isCheckmate());
        assertTrue(board.isStalemate());
    }
}
