package coeusyk.game.chess.core.movegen;

import coeusyk.game.chess.core.models.Board;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FenLegalityValidationTest {

    @Test
    void acceptsLegalStartPositionFen() {
        assertTrue(Board.isLegalFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"));
    }

    @Test
    void rejectsFenWhereSideNotToMoveIsInCheck() {
        assertFalse(Board.isLegalFen("1Q6/5pk1/2p3p1/1p2N2p/1b5P/1bn5/2r3P1/2K5 b - - 0 44"));
    }

    @Test
    void rejectsFenWithAdjacentKings() {
        assertFalse(Board.isLegalFen("8/8/8/8/8/8/4k3/4K3 w - - 0 1"));
    }

    @Test
    void rejectsFenWithPawnOnBackRank() {
        assertFalse(Board.isLegalFen("4k3/8/8/8/8/8/8/P3K3 w - - 0 1"));
    }
}
