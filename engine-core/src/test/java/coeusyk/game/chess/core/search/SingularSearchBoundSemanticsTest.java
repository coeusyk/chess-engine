package coeusyk.game.chess.core.search;

import coeusyk.game.chess.core.eval.EvaluatorStrategy;
import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.movegen.MovesGenerator;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SingularSearchBoundSemanticsTest {

    private static final String POSITION = "4k3/8/8/8/8/8/4P3/4K3 w - - 0 1";
    private static final int CALLER_ALPHA = 0;
    private static final int CALLER_BETA = 100;
    private static final int DEPTH = 8;
    private static final int TT_SCORE = 50; // margin(8)=54 => singularBeta=-3
    // e2-e4 as the generator packs it (square 52 = e2, 36 = e4; 12 would be e7). The old
    // Move.of(12, 28, 0) was not a legal move here, so the "TT move" was never in the move list.
    private static final int TT_MOVE = Move.of(52, 36, Move.FLAG_EP_TARGET);
    private static final int NO_EXTENSIONS = 0;
    private static final int ONE_EXTENSION = 1;

    @Test
    void disprovingSingularityDoesNotProveCallerBeta() throws Exception {
        Board board = new Board(POSITION);
        board.setSearchMode(true);

        Searcher searcher = new Searcher(false, false, false, false, false, false);
        searcher.setEvaluatorStrategy(new ConstantEvaluator(-2));
        TranspositionTable table = new TranspositionTable(1);
        table.store(board.getZobristHash(), TT_MOVE, DEPTH, TT_SCORE, TTBound.LOWER_BOUND);
        searcher.setSharedTranspositionTable(table);

        assertTtMoveIsLegal(board);
        int result = invokeAlphaBeta(searcher, board, ONE_EXTENSION);

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
    void verifiedSingularMoveStillGetsTheExistingExtension() throws Exception {
        Board board = new Board(POSITION);
        board.setSearchMode(true);
        assertTtMoveIsLegal(board);

        // Identical search, identical singularity probe; only the extension budget differs.
        Searcher budgeted = configuredSearcher(board, TT_MOVE);
        invokeAlphaBeta(budgeted, board, ONE_EXTENSION);

        Board noBudgetBoard = new Board(POSITION);
        noBudgetBoard.setSearchMode(true);
        Searcher noBudget = configuredSearcher(noBudgetBoard, TT_MOVE);
        invokeAlphaBeta(noBudget, noBudgetBoard, NO_EXTENSIONS);

        Board noTtBoard = new Board(POSITION);
        noTtBoard.setSearchMode(true);
        Searcher noTtMove = configuredSearcher(noTtBoard, Move.NONE);
        invokeAlphaBeta(noTtMove, noTtBoard, ONE_EXTENSION);

        assertEquals(1, budgeted.singularExtensionsAppliedForTesting(),
                "a proven singular TT move must retain its extra search ply");
        assertEquals(0, noBudget.singularExtensionsAppliedForTesting(),
                "an exhausted extension budget must suppress the singular extension");
        assertEquals(0, noTtMove.singularExtensionsAppliedForTesting(),
                "without a TT move there is nothing to extend");
    }

    // #267 item 3. The singular test compares a child's score against `ttScore - margin`. A mate TT score
    // is node-relative (scoreToTT adds the ply) while the child's score is root-relative, and the window
    // lands in the mate range where margins in centipawns mean nothing. Singular verification on a mate
    // score is also unnecessary: the TT move already proves a forced mate.
    private static final int MATE_SCORE = 100_000; // Searcher.MATE_SCORE is private

    private static TranspositionTable.Entry entry(int score, TTBound bound) {
        return new TranspositionTable.Entry(1L, TT_MOVE, DEPTH, score, bound, (byte) 0);
    }

    @Test
    void singularVerificationIsNotAttemptedOnAMateScore() {
        Searcher searcher = new Searcher();
        assertTrue(searcher.canAttemptSingularityForTesting(DEPTH, entry(TT_SCORE, TTBound.LOWER_BOUND), false, false),
                "control: an ordinary score still qualifies");
        assertTrue(searcher.canAttemptSingularityForTesting(DEPTH, entry(MATE_SCORE - 1000, TTBound.LOWER_BOUND), false, false),
                "control: a large but non-mate score still qualifies");
        for (int mateScore : new int[] {MATE_SCORE - 6, MATE_SCORE - 127, -(MATE_SCORE - 6), -(MATE_SCORE - 127)}) {
            assertFalse(searcher.canAttemptSingularityForTesting(DEPTH, entry(mateScore, TTBound.EXACT), false, false),
                    "mate TT score " + mateScore);
            assertFalse(searcher.canAttemptSingularityForTesting(DEPTH, entry(mateScore, TTBound.LOWER_BOUND), false, false),
                    "mate TT score " + mateScore);
        }
    }

    @Test
    void aMateTtMoveDoesNotGetASingularExtension() throws Exception {
        Board board = new Board(POSITION);
        board.setSearchMode(true);
        assertTtMoveIsLegal(board);
        Searcher searcher = new Searcher(false, false, false, false, false, false);
        searcher.setEvaluatorStrategy(new ConstantEvaluator(-4));
        TranspositionTable table = new TranspositionTable(1);
        // depth 6 < 8, so the entry is too shallow to cut off but deep enough to attempt singularity
        table.store(board.getZobristHash(), TT_MOVE, DEPTH - 2, MATE_SCORE - 6, TTBound.LOWER_BOUND);
        searcher.setSharedTranspositionTable(table);

        invokeAlphaBeta(searcher, board, ONE_EXTENSION);

        assertEquals(0, searcher.singularExtensionsAppliedForTesting(),
                "a forced mate needs no singular extension, and its mate-range window must not be searched");
    }

    private static void assertTtMoveIsLegal(Board board) {
        int[] moves = new int[256];
        int count = MovesGenerator.generate(board, moves);
        boolean found = false;
        for (int i = 0; i < count; i++) {
            found |= moves[i] == TT_MOVE;
        }
        assertTrue(found, "TT_MOVE must be a legal move of POSITION as the generator packs it");
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

    private static int invokeAlphaBeta(Searcher searcher, Board board, int maxExtensions) throws Exception {
        Method alphaBeta = Searcher.class.getDeclaredMethod(
                "alphaBeta",
                Board.class,
                int.class,
                int.class,
                int.class,
                int.class,
                BooleanSupplier.class,
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
                0,
                maxExtensions,
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

    private static Searcher configuredSearcher(Board board, int bestMove) {
        Searcher searcher = new Searcher(false, false, false, false, false, false);
        searcher.setEvaluatorStrategy(new ConstantEvaluator(-4));
        TranspositionTable table = new TranspositionTable(1);
        table.store(board.getZobristHash(), bestMove, DEPTH, TT_SCORE, TTBound.LOWER_BOUND);
        searcher.setSharedTranspositionTable(table);
        return searcher;
    }

    private record ConstantEvaluator(int score) implements EvaluatorStrategy {
        @Override
        public int evaluate(Board board) {
            return score;
        }
    }
}
