package coeusyk.game.chess.core.movegen;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FiftyMoveRuleTest {

    @Test
    void fiftyMoveRuleDetectedAtHundredHalfmoves() {
        Board board = new Board("4k3/8/8/8/8/8/8/4K3 w - - 100 1");

        assertTrue(board.isFiftyMoveRuleDraw());
    }

    @Test
    void fiftyMoveRuleNotDetectedBelowThreshold() {
        Board board = new Board("4k3/8/8/8/8/8/8/4K3 w - - 99 1");

        assertFalse(board.isFiftyMoveRuleDraw());
    }

    @Test
    void pawnMoveResetsHalfmoveClockAndClearsFiftyMoveDraw() {
        Board board = new Board("4k3/8/8/8/8/8/4P3/4K3 w - - 99 1");

        board.makeMove(new Move(52, 44));

        assertEquals(0, board.getHalfmoveClock());
        assertFalse(board.isFiftyMoveRuleDraw());
    }
}
