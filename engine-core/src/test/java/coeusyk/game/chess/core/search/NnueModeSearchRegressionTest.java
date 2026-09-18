package coeusyk.game.chess.core.search;

import coeusyk.game.chess.core.eval.nnue.NnueEvaluator;
import coeusyk.game.chess.core.eval.nnue.NnueNetwork;
import coeusyk.game.chess.core.eval.nnue.TestNetworks;
import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.movegen.MovesGenerator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PR C-5 (issue #189): re-runs {@link SearchRegressionTest}'s position set under a real
 * search tree (make/unmake at every node, quiescence, null-move, LMR — a materially
 * deeper stress test of the incremental accumulator than {@code
 * NnueIncrementalVsRebuildFuzzTest}'s linear self-play replay) with {@code
 * EvaluatorStrategy} swapped to {@link NnueEvaluator} over the CI test net ({@link
 * TestNetworks#synthetic}), never touching {@code SearchRegressionTest} itself.
 *
 * <p>Deliberately does <em>not</em> assert "same best move as classical" for most
 * positions: {@code TestNetworks.synthetic} is untrained, seeded-random weights with no
 * learned relationship to material or position — there is no reason its static eval
 * should agree with the handcrafted classical evaluator's move preferences, and gating
 * on that would be an unfair, flaky test of net quality rather than of search-under-NNUE
 * correctness. What IS asserted for every position: the search completes without
 * throwing and returns a legal move — regressions here mean something in the NNUE
 * accumulator/search-tree integration broke, not that the net "got worse". ({@code
 * nodesVisited() > 0} was considered and dropped — verified empirically that several of
 * these positions (T1, T2, T4, T5, T7) report zero visited nodes even under the
 * classical evaluator, i.e. it's a pre-existing root-level shortcut for trivially-decided
 * positions, not something NNUE-specific, so asserting on it would be testing the wrong
 * thing.) T1, T2, T4, T5 are the exception: each is a literal mate-in-1 (verified against
 * {@code SearchRegressionTest}'s own comments — T3 looked like a candidate but its
 * comment says the correct move only "sets up" mate next ply, so it's excluded here),
 * which is evaluator-independent (the decisive terminal score dominates any static eval
 * magnitude), so those four additionally assert the returned move actually delivers
 * checkmate — a real, non-flaky correctness gate specific to NNUE-mode search.
 */
@Tag("nnue-mode")
class NnueModeSearchRegressionTest {

    private static final int DEFAULT_DEPTH = 8;

    private static Searcher nnueSearcher() {
        Searcher searcher = new Searcher();
        NnueNetwork network = TestNetworks.synthetic(8);
        searcher.setEvaluatorStrategy(new NnueEvaluator(network));
        return searcher;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allRegressionPositions")
    void searchCompletesWithALegalMoveUnderNnue(String name, String fen) {
        Board board = new Board(fen);
        SearchResult result = nnueSearcher().searchDepth(board, DEFAULT_DEPTH);

        Move bestMove = result.bestMove();
        assertNotNull(bestMove, "NNUE-mode search returned no move for " + name + " | " + fen);
        assertTrue(isAmongLegalMoves(board, bestMove),
                "NNUE-mode search returned a move not present in the actual legal-move list for "
                        + name + " | " + fen);
    }

    /**
     * {@link Board#makeMove} only validates the {@code reaction} string, not that the
     * move is genuinely legal for the position — comparing against {@link
     * MovesGenerator}'s own legal-move list (via {@link Move#pack()}, since {@link Move}
     * has no {@code equals}) is the real check.
     */
    private static boolean isAmongLegalMoves(Board board, Move candidate) {
        int packedCandidate = candidate.pack();
        List<Move> legalMoves = new MovesGenerator(board).getActiveMoves(board.getActiveColor());
        for (Move legalMove : legalMoves) {
            if (legalMove.pack() == packedCandidate) {
                return true;
            }
        }
        return false;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("forcedMateInOnePositions")
    void forcedMateInOneIsStillFoundUnderNnue(String name, String fen) {
        Board board = new Board(fen);
        SearchResult result = nnueSearcher().searchDepth(board, DEFAULT_DEPTH);

        Move bestMove = result.bestMove();
        assertNotNull(bestMove, "NNUE-mode search returned no move for forced mate " + name);
        assertTrue(isAmongLegalMoves(board, bestMove),
                "NNUE-mode search returned a move not present in the actual legal-move list for forced mate "
                        + name + " | " + fen);

        board.makeMove(bestMove);
        assertTrue(board.isCheckmate(),
                "NNUE-mode search failed to deliver the forced mate-in-1 for " + name
                        + " | " + fen + " — this is evaluator-independent, so a miss here"
                        + " points at a search/accumulator integration bug, not net quality");
    }

    static Stream<Arguments> allRegressionPositions() {
        return Stream.of(
                Arguments.of("T1", SearchRegressionTest.T1_FEN),
                Arguments.of("T2", SearchRegressionTest.T2_FEN),
                Arguments.of("T3", SearchRegressionTest.T3_FEN),
                Arguments.of("T4", SearchRegressionTest.T4_FEN),
                Arguments.of("T5", SearchRegressionTest.T5_FEN),
                Arguments.of("T6", SearchRegressionTest.T6_FEN),
                Arguments.of("T7", SearchRegressionTest.T7_FEN),
                Arguments.of("T8", SearchRegressionTest.T8_FEN),
                Arguments.of("T9", SearchRegressionTest.T9_FEN),
                Arguments.of("T10", SearchRegressionTest.T10_FEN),
                Arguments.of("P1", SearchRegressionTest.P1_FEN),
                Arguments.of("P2", SearchRegressionTest.P2_FEN),
                Arguments.of("P3", SearchRegressionTest.P3_FEN),
                Arguments.of("P4", SearchRegressionTest.P4_FEN),
                Arguments.of("P5", SearchRegressionTest.P5_FEN),
                Arguments.of("P6", SearchRegressionTest.P6_FEN),
                Arguments.of("P7", SearchRegressionTest.P7_FEN),
                Arguments.of("P8", SearchRegressionTest.P8_FEN),
                Arguments.of("P9", SearchRegressionTest.P9_FEN),
                Arguments.of("P10", SearchRegressionTest.P10_FEN),
                Arguments.of("E1", SearchRegressionTest.E1_FEN),
                Arguments.of("E2", SearchRegressionTest.E2_FEN),
                Arguments.of("E3", SearchRegressionTest.E3_FEN),
                Arguments.of("E4", SearchRegressionTest.E4_FEN),
                Arguments.of("E5", SearchRegressionTest.E5_FEN),
                Arguments.of("E6", SearchRegressionTest.E6_FEN),
                Arguments.of("E7", SearchRegressionTest.E7_FEN),
                Arguments.of("E8", SearchRegressionTest.E8_FEN),
                Arguments.of("E9", SearchRegressionTest.E9_FEN),
                Arguments.of("E10", SearchRegressionTest.E10_FEN)
        );
    }

    static Stream<Arguments> forcedMateInOnePositions() {
        // T3 deliberately excluded — per SearchRegressionTest's own comment, its correct
        // move only "sets up" mate next ply, it isn't itself a mate-in-1.
        return Stream.of(
                Arguments.of("T1", SearchRegressionTest.T1_FEN),
                Arguments.of("T2", SearchRegressionTest.T2_FEN),
                Arguments.of("T4", SearchRegressionTest.T4_FEN),
                Arguments.of("T5", SearchRegressionTest.T5_FEN)
        );
    }
}
