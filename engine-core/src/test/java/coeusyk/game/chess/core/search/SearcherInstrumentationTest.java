package coeusyk.game.chess.core.search;

import coeusyk.game.chess.core.eval.ClassicalEvaluator;
import coeusyk.game.chess.core.eval.nnue.NnueEvaluator;
import coeusyk.game.chess.core.eval.nnue.TestNetworks;
import coeusyk.game.chess.core.models.Board;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Issue #216: search-time instrumentation (eval latency, NNUE accumulator-update
 * cost, PV/cut-node eval frequency). Verifies the acceptance criteria directly --
 * classical-mode accumulator time reads ~0, instrumentation is zero unless
 * explicitly enabled, and the metrics are internally consistent -- rather than via
 * {@code --bench}, since {@code BenchRunner}/{@code BenchMain} don't support
 * selecting {@code EvalType} at all (that wiring is issue #206's scope, not #216's).
 */
class SearcherInstrumentationTest {

    private static final String MIDDLEGAME_FEN =
            "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/2N5/PPPP1PPP/R1BQK1NR b KQkq - 2 3";

    @Test
    void instrumentationDisabledByDefaultReportsZeroTiming() {
        Searcher searcher = new Searcher();
        SearchResult result = searcher.searchDepth(new Board(MIDDLEGAME_FEN), 4);

        assertEquals(0L, result.evalNanos());
        assertEquals(0L, result.accumulatorNanos());
    }

    @Test
    void pvAndCutNodeEvalCountsAreTrackedRegardlessOfInstrumentationFlag() {
        // Cheap counters (per the research doc's own reasoning) -- always on, like
        // the existing nodesVisited/ttHits/etc. counters, not gated behind the flag
        // that guards the nanoTime()-based metrics.
        Searcher searcher = new Searcher();
        SearchResult result = searcher.searchDepth(new Board(MIDDLEGAME_FEN), 4);

        assertTrue(result.pvNodeEvals() + result.cutNodeEvals() > 0);
    }

    @Test
    void classicalEvaluatorAccumulatorTimeStaysZeroWhenInstrumentationEnabled() {
        Searcher searcher = new Searcher();
        searcher.setEvaluatorStrategy(new ClassicalEvaluator());
        searcher.setInstrumentationEnabled(true);

        SearchResult result = searcher.searchDepth(new Board(MIDDLEGAME_FEN), 4);

        assertTrue(result.evalNanos() > 0, "eval-time should be measured once instrumentation is enabled");
        assertEquals(0L, result.accumulatorNanos(), "classical evaluator has no accumulator to time");
    }

    @Test
    void nnueEvaluatorAccumulatorTimeIsPositiveWhenInstrumentationEnabled() {
        Searcher searcher = new Searcher();
        searcher.setEvaluatorStrategy(new NnueEvaluator(TestNetworks.synthetic(8)));
        searcher.setInstrumentationEnabled(true);

        SearchResult result = searcher.searchDepth(new Board(MIDDLEGAME_FEN), 4);

        assertTrue(result.evalNanos() > 0);
        assertTrue(result.accumulatorNanos() > 0, "NNUE accumulator maintenance should register non-zero time");
    }

    @Test
    void enablingInstrumentationAfterSwappingEvaluatorStillEnablesAccumulatorTiming() {
        // setInstrumentationEnabled propagates to whichever evaluator is active at
        // call time; setEvaluatorStrategy also propagates if instrumentation was
        // already enabled first. This exercises the first ordering.
        Searcher searcher = new Searcher();
        searcher.setInstrumentationEnabled(true);
        searcher.setEvaluatorStrategy(new NnueEvaluator(TestNetworks.synthetic(8)));

        SearchResult result = searcher.searchDepth(new Board(MIDDLEGAME_FEN), 4);

        assertTrue(result.accumulatorNanos() > 0);
    }
}
