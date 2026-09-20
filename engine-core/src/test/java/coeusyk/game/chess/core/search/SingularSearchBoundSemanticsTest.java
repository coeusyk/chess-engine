package coeusyk.game.chess.core.search;

import coeusyk.game.chess.core.eval.EvaluatorStrategy;
import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.movegen.MovesGenerator;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SingularSearchBoundSemanticsTest {

    private static final String POSITION = "4k3/8/8/8/8/8/4P3/4K3 w - - 0 1";
    private static final int CALLER_ALPHA = 0;
    private static final int CALLER_BETA = 100;
    private static final int DEPTH = 8;
    private static final int TT_SCORE = 50; // margin(8)=54 => singularBeta=-3
    private static final int TT_MOVE = Move.of(12, 28, 0); // e2-e4

    @Test
    void disprovingSingularityDoesNotProveCallerBeta() throws Exception {
        Board board = new Board(POSITION);
        board.setSearchMode(true);

        Searcher searcher = new Searcher(false, false, false, false, false, false);
        searcher.setEvaluatorStrategy(new ConstantEvaluator(-2));
        TranspositionTable table = new TranspositionTable(1);
        table.store(board.getZobristHash(), TT_MOVE, DEPTH, TT_SCORE, TTBound.LOWER_BOUND);
        searcher.setSharedTranspositionTable(table);

        int result = invokeAlphaBeta(searcher, board);

        assertNotEquals(CALLER_BETA, result,
                "an alternative at singularBeta must not manufacture the caller beta cutoff");
        assertTrue(result < CALLER_BETA,
                "the enclosing caller must continue its own search window");
    }

    @Test
    void verifiedSingularityStillProducesTheExtensionDecision() throws Exception {
        Board board = new Board(POSITION);
        board.setSearchMode(true);
        int[] moves = new int[256];
        int moveCount = MovesGenerator.generate(board, moves);

        Searcher searcher = new Searcher(false, false, false, false, false, false);
        searcher.setEvaluatorStrategy(new ConstantEvaluator(-4));
        Object result = invokeSingularitySearch(searcher, board, moves, moveCount, () -> false);

        assertEquals("SINGULAR", result.toString(),
                "an alternative below singularBeta must preserve the existing extension path");
    }

    @Test
    void abortedVerificationIsNotClassifiedAsSingular() throws Exception {
        Board board = new Board(POSITION);
        board.setSearchMode(true);
        int[] moves = new int[256];
        int moveCount = MovesGenerator.generate(board, moves);

        Searcher searcher = new Searcher(false, false, false, false, false, false);
        searcher.setEvaluatorStrategy(new ConstantEvaluator(-4));
        Object result = invokeSingularitySearch(searcher, board, moves, moveCount, () -> true);

        assertEquals("ABORTED", result.toString(),
                "an interrupted verification must not be treated as a proven singularity");
    }

    private static int invokeAlphaBeta(Searcher searcher, Board board) throws Exception {
        Method alphaBeta = Searcher.class.getDeclaredMethod(
                "alphaBeta",
                Board.class,
                int.class,
                int.class,
                int.class,
                int.class,
                BooleanSupplier.class,
                boolean.class,
                boolean.class,
                int.class,
                int.class,
                boolean.class
        );
        alphaBeta.setAccessible(true);
        return (int) alphaBeta.invoke(
                searcher,
                board,
                DEPTH,
                0,
                CALLER_ALPHA,
                CALLER_BETA,
                (BooleanSupplier) () -> false,
                false,
                false,
                0,
                0,
                false
        );
    }

    private static Object invokeSingularitySearch(
            Searcher searcher,
            Board board,
            int[] moves,
            int moveCount,
            BooleanSupplier shouldStopHard
    )
            throws Exception {
        Method singularity = Searcher.class.getDeclaredMethod(
                "runSingularitySearch",
                Board.class,
                int[].class,
                int.class,
                int.class,
                int.class,
                int.class,
                int.class,
                BooleanSupplier.class,
                int.class,
                int.class
        );
        singularity.setAccessible(true);
        return singularity.invoke(
                searcher,
                board,
                moves,
                moveCount,
                TT_MOVE,
                TT_SCORE,
                DEPTH,
                0,
                shouldStopHard,
                0,
                0
        );
    }

    private record ConstantEvaluator(int score) implements EvaluatorStrategy {
        @Override
        public int evaluate(Board board) {
            return score;
        }
    }
}
