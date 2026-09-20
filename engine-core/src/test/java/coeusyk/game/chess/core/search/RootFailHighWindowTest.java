package coeusyk.game.chess.core.search;

import coeusyk.game.chess.core.eval.EvaluatorStrategy;
import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.movegen.MovesGenerator;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RootFailHighWindowTest {

    private static final String POSITION = "4k3/8/8/8/8/8/4P3/4K3 w - - 0 1";

    @Test
    void rootFailHighStopsBeforeTheSecondSibling() throws Exception {
        Board board = new Board(POSITION);
        board.setSearchMode(true);
        List<Move> rootMoves = legalMoves(board);
        assertTrue(rootMoves.size() >= 2);

        long firstChildHash = childHash(board, rootMoves.get(0));
        long secondChildHash = childHash(board, rootMoves.get(1));
        RecordingEvaluator evaluator = new RecordingEvaluator(firstChildHash, secondChildHash, 20);
        Searcher searcher = pureRootSearcher(evaluator);

        Object result = invokeSearchRoot(searcher, board, 0, 10, () -> false);

        assertEquals(20, bestScore(result), "the first child must produce the controlled fail-high");
        Move bestMove = bestMove(result);
        assertNotNull(bestMove);
        assertSameMove(bestMove, principalVariation(result).get(0));
        assertEquals(0, evaluator.secondChildEvaluations,
                "root must not invoke another sibling after alpha reaches beta");
    }

    @Test
    void failHighStillRetriesTheAspirationWindow() throws Exception {
        Board board = new Board(POSITION);
        board.setSearchMode(true);
        Move firstMove = legalMoves(board).get(0);
        RecordingEvaluator evaluator = new RecordingEvaluator(childHash(board, firstMove), 0L, 40);
        Searcher searcher = pureRootSearcher(evaluator);

        Object result = invokeSearchRootWithAspiration(searcher, board, 0, () -> false);

        assertEquals(40, bestScore(result));
        assertTrue(evaluator.firstChildEvaluations >= 2,
                "a fail-high root result must trigger the existing aspiration retry");
        assertFalse(aborted(result));
    }

    @Test
    void repeatedAspirationFailureStillFallsBackToFullWindow() throws Exception {
        Board board = new Board(POSITION);
        board.setSearchMode(true);
        Move firstMove = legalMoves(board).get(0);
        RecordingEvaluator evaluator = new RecordingEvaluator(childHash(board, firstMove), 0L, 80);
        Searcher searcher = pureRootSearcher(evaluator);

        Object result = invokeSearchRootWithAspiration(searcher, board, 0, () -> false);

        assertEquals(80, bestScore(result));
        assertTrue(evaluator.firstChildEvaluations >= 3,
                "two aspiration failures must preserve the full-window fallback");
        assertFalse(aborted(result));
    }

    @Test
    void abortDuringRetryPreservesTheLastCompletedIteration() {
        Board board = new Board(POSITION);
        board.setSearchMode(true);
        Searcher searcher = pureRootSearcher(new ConstantEvaluator(0));
        AtomicBoolean stop = new AtomicBoolean();

        SearchResult result = searcher.iterativeDeepening(
                board,
                2,
                () -> false,
                stop::get,
                info -> {
                    if (info.depth() == 1) {
                        stop.set(true);
                    }
                }
        );

        assertTrue(result.aborted());
        assertEquals(1, result.depthReached(),
                "an aborted retry must not replace the last completed iteration");
        assertNotNull(result.bestMove());
    }

    private static Searcher pureRootSearcher(EvaluatorStrategy evaluator) {
        Searcher searcher = new Searcher(false, true, false, false, false, false);
        searcher.setEvaluatorStrategy(evaluator);
        return searcher;
    }

    private static List<Move> legalMoves(Board board) {
        return new MovesGenerator(board).getActiveMoves(board.getActiveColor());
    }

    private static long childHash(Board board, Move move) {
        board.makeMove(move);
        long hash = board.getZobristHash();
        board.unmakeMove();
        return hash;
    }

    private static Object invokeSearchRoot(
            Searcher searcher,
            Board board,
            int alpha,
            int beta,
            BooleanSupplier stop
    ) throws Exception {
        Method method = Searcher.class.getDeclaredMethod(
                "searchRoot",
                Board.class,
                int.class,
                Move.class,
                BooleanSupplier.class,
                int.class,
                int.class,
                int.class,
                List.class
        );
        method.setAccessible(true);
        return method.invoke(searcher, board, 1, null, stop, alpha, beta, 0, List.of());
    }

    private static Object invokeSearchRootWithAspiration(
            Searcher searcher,
            Board board,
            int previousScore,
            BooleanSupplier stop
    ) throws Exception {
        Method method = Searcher.class.getDeclaredMethod(
                "searchRootWithAspiration",
                Board.class,
                int.class,
                Move.class,
                int.class,
                BooleanSupplier.class,
                int.class,
                List.class
        );
        method.setAccessible(true);
        return method.invoke(searcher, board, 1, null, previousScore, stop, 0, List.of());
    }

    private static int bestScore(Object result) throws Exception {
        return (int) result.getClass().getDeclaredMethod("bestScore").invoke(result);
    }

    private static Move bestMove(Object result) throws Exception {
        return (Move) result.getClass().getDeclaredMethod("bestMove").invoke(result);
    }

    private static boolean aborted(Object result) throws Exception {
        return (boolean) result.getClass().getDeclaredMethod("aborted").invoke(result);
    }

    @SuppressWarnings("unchecked")
    private static List<Move> principalVariation(Object result) throws Exception {
        return (List<Move>) result.getClass().getDeclaredMethod("principalVariation").invoke(result);
    }

    private static void assertSameMove(Move expected, Move actual) {
        assertEquals(expected.startSquare, actual.startSquare);
        assertEquals(expected.targetSquare, actual.targetSquare);
        assertEquals(expected.reaction, actual.reaction);
    }

    private static final class RecordingEvaluator implements EvaluatorStrategy {
        private final long firstChildHash;
        private final long secondChildHash;
        private final int firstScore;
        private int firstChildEvaluations;
        private int secondChildEvaluations;

        private RecordingEvaluator(long firstChildHash, long secondChildHash, int firstScore) {
            this.firstChildHash = firstChildHash;
            this.secondChildHash = secondChildHash;
            this.firstScore = firstScore;
        }

        @Override
        public int evaluate(Board board) {
            long hash = board.getZobristHash();
            if (hash == firstChildHash) {
                firstChildEvaluations++;
                return -firstScore;
            }
            if (hash == secondChildHash) {
                secondChildEvaluations++;
            }
            return 0;
        }
    }

    private record ConstantEvaluator(int score) implements EvaluatorStrategy {
        @Override
        public int evaluate(Board board) {
            return score;
        }
    }
}
