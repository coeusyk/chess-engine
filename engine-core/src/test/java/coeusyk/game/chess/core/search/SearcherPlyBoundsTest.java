package coeusyk.game.chess.core.search;

import coeusyk.game.chess.core.eval.EvaluatorStrategy;
import coeusyk.game.chess.core.models.Board;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #267 item 1: {@code alphaBeta} had no ply bound. Iterative deepening reaches depth 127 and check
 * extensions can add up to 16 plies, so a node can sit at ply 143, while {@code staticEvalStack}
 * holds {@code MAX_PLY + 2 = 130} entries. These tests call {@code alphaBeta} at plies past that
 * array directly (the same reflection route {@link SingularSearchBoundSemanticsTest} uses), because
 * no real search reaches them in test time.
 */
class SearcherPlyBoundsTest {

    private static final String START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    private record ConstantEvaluator(int score) implements EvaluatorStrategy {
        @Override
        public int evaluate(Board board) {
            return score;
        }
    }

    static int alphaBeta(Searcher searcher, Board board, int depth, int ply) throws Exception {
        Method alphaBeta = Searcher.class.getDeclaredMethod("alphaBeta", Board.class, int.class, int.class,
                int.class, int.class, BooleanSupplier.class, boolean.class, int.class, int.class, boolean.class);
        alphaBeta.setAccessible(true);
        try {
            return (int) alphaBeta.invoke(searcher, board, depth, ply, -1000, 1000,
                    (BooleanSupplier) () -> false, false, 0, 16, false);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    @Test
    void aNodePastTheStaticEvalStackDoesNotThrow() {
        for (int ply : new int[] {128, 129, 130, 131, 136, 143}) {
            Board board = new Board(START);
            board.setSearchMode(true);
            Searcher searcher = new Searcher();
            searcher.setEvaluatorStrategy(new ConstantEvaluator(25));
            assertDoesNotThrow(() -> alphaBeta(searcher, board, 3, ply), "alphaBeta at ply " + ply);
        }
    }

    // Searcher.MATE_SCORE and MAX_PLY are private; these mirror them.
    private static final int MATE_SCORE = 100_000;
    private static final int MAX_PLY = 128;
    private static final int QUIESCENCE_REACH = 9; // MAX_Q_DEPTH + MAX_Q_CHECK_EXTENSION
    private static final String CHECKMATE = "7k/6Q1/6K1/8/8/8/8/8 b - - 0 1";
    private static final String STALEMATE = "7k/5Q2/6K1/8/8/8/8/8 b - - 0 1";
    private static final String INSUFFICIENT = "4k3/8/8/8/8/8/8/4KB2 w - - 0 1";

    private static Searcher searcherWithEval(int eval) {
        Searcher searcher = new Searcher();
        searcher.setEvaluatorStrategy(new ConstantEvaluator(eval));
        return searcher;
    }

    private static Board board(String fen) {
        Board board = new Board(fen);
        board.setSearchMode(true);
        return board;
    }

    private static long nodesVisited(Searcher searcher) throws Exception {
        var field = Searcher.class.getDeclaredField("nodesVisited");
        field.setAccessible(true);
        return field.getLong(searcher);
    }

    @Test
    void theBoundaryLeavesRoomForTheDeepestQuiescenceBelowMaxPly() {
        // Every ply a quiescence call can reach from the last searching ply must stay within MAX_PLY, the
        // largest distance scoreToTT and isMateWindow still treat as mate.
        assertTrue(Searcher.MAX_SEARCH_PLY + QUIESCENCE_REACH - 1 < MAX_PLY, "MAX_SEARCH_PLY=" + Searcher.MAX_SEARCH_PLY);
        assertTrue(Searcher.MAX_SEARCH_PLY < 127, "the guard must sit below the old effectiveMaxDepth");
    }

    @Test
    void mateScoresAreRecognisedUpToTheDeepestReachablePly() {
        for (int ply = 1; ply <= Searcher.MAX_SEARCH_PLY + QUIESCENCE_REACH; ply++) { // ply 0 adds nothing to adjust
            int mated = -MATE_SCORE + ply;
            assertNotEquals(mated, Searcher.scoreToTT(mated, ply), "mate at ply " + ply + " must be recognised");
            assertEquals(mated, Searcher.scoreFromTT(Searcher.scoreToTT(mated, ply), ply), "round trip at ply " + ply);
        }
        // Negative control: past MAX_PLY a mate score is no longer recognised, which is why the margin exists.
        int tooDeep = -MATE_SCORE + MAX_PLY + 1;
        assertEquals(tooDeep, Searcher.scoreToTT(tooDeep, MAX_PLY + 1));
    }

    @Test
    void aNodeAtTheBoundaryIsAdjudicatedExactlyAndDoesNotSearchDeeper() throws Exception {
        int ply = Searcher.MAX_SEARCH_PLY;

        Searcher mated = searcherWithEval(25);
        assertEquals(-MATE_SCORE + ply, alphaBeta(mated, board(CHECKMATE), 5, ply), "checkmate keeps its mate score");

        Searcher stalemate = searcherWithEval(25);
        assertEquals(0, alphaBeta(stalemate, board(STALEMATE), 5, ply), "stalemate is a draw, not the static eval");

        Searcher ordinary = searcherWithEval(25);
        assertEquals(25, alphaBeta(ordinary, board(START), 5, ply), "an ordinary position gets its static eval");
        assertEquals(0, nodesVisited(ordinary), "no child is searched at the boundary");

        Searcher drawn = searcherWithEval(25);
        assertEquals(0, alphaBeta(drawn, board(INSUFFICIENT), 5, ply), "draw detection still precedes the guard");
    }

    @Test
    void theNodeJustAboveTheBoundaryStillSearches() throws Exception {
        Searcher searcher = searcherWithEval(25);
        alphaBeta(searcher, board(START), 1, Searcher.MAX_SEARCH_PLY - 1);
        assertTrue(nodesVisited(searcher) >= 1, "the guard must not cut off search below MAX_SEARCH_PLY");
    }

    @Test
    void aQuiescenceMateAtTheDeepestReachablePlyIsStillAMateScore() throws Exception {
        Method quiescence = Searcher.class.getDeclaredMethod("quiescence", Board.class, int.class, int.class,
                int.class, int.class, BooleanSupplier.class);
        quiescence.setAccessible(true);
        int ply = Searcher.MAX_SEARCH_PLY - 1 + QUIESCENCE_REACH - 1; // last searching ply plus its deepest qPly
        int score = (int) quiescence.invoke(searcherWithEval(25), board(CHECKMATE), -MATE_SCORE, MATE_SCORE, ply, 0,
                (BooleanSupplier) () -> false);
        assertEquals(-MATE_SCORE + ply, score);
        assertNotEquals(score, Searcher.scoreToTT(score, ply), "recognised as a mate score");
    }
}
