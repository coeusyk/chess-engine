package coeusyk.game.chess.core.search;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.movegen.MovesGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class StaticExchangeEvaluatorTest {

    @Test
    void seeEvaluatesEqualPawnExchangeNearZero() {
        Board board = new Board("4k3/8/4p3/3p4/4P3/8/8/4K3 w - - 0 1");
        Move move = findMove(board, 36, 27); // e4xd5

        int see = new StaticExchangeEvaluator().evaluate(board, move);

        assertEquals(0, see);
    }

    @Test
    void seeEvaluatesWinningCaptureAsPositive() {
        Board board = new Board("4k3/8/8/3n4/4P3/8/8/4K3 w - - 0 1");
        Move move = findMove(board, 36, 27); // e4xd5 (pawn takes knight)

        int see = new StaticExchangeEvaluator().evaluate(board, move);

        assertTrue(see > 0, "Expected winning capture SEE > 0");
    }

    @Test
    void seeEvaluatesLosingCaptureAsNegative() {
        Board board = new Board("4k3/5p2/4p3/8/5N2/8/8/4K3 w - - 0 1");
        Move move = findMove(board, 37, 20); // Nf4xe6 defended by f7-pawn

        int see = new StaticExchangeEvaluator().evaluate(board, move);

        assertTrue(see < 0, "Expected defended losing capture SEE < 0");
    }

    @Test
    void seeIncludesXrayRecaptures() {
        Board board = new Board("k2r4/8/3b4/8/8/6B1/8/7K w - - 0 1");
        Move move = findMove(board, 46, 19); // Bg3xd6, rook behind bishop can recapture

        int see = new StaticExchangeEvaluator().evaluate(board, move);

        assertTrue(see <= 0, "Expected x-ray recapture to neutralize or punish the capture");
    }

    private Move findMove(Board board, int startSquare, int targetSquare) {
        List<Move> moves = new MovesGenerator(board).getActiveMoves(board.getActiveColor());
        for (Move move : moves) {
            if (move.startSquare == startSquare && move.targetSquare == targetSquare) {
                return move;
            }
        }

        fail("Expected legal move not found");
        return null;
    }
}
