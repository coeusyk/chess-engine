package coeusyk.game.chess.core.movegen;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ThreefoldRepetitionTest {

    @Test
    void threefoldRepetitionDetectedAfterThirdOccurrenceOfSamePosition() {
        Board board = new Board("1n2k3/8/8/8/8/8/8/1N2K3 w - - 0 1");

        playReversibleCycle(board);
        assertFalse(board.isThreefoldRepetition());

        playReversibleCycle(board);
        assertTrue(board.isThreefoldRepetition());
    }

    private void playReversibleCycle(Board board) {
        board.makeMove(new Move(57, 40));
        board.makeMove(new Move(1, 16));
        board.makeMove(new Move(40, 57));
        board.makeMove(new Move(16, 1));
    }
}
