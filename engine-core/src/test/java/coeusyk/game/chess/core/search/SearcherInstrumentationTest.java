package coeusyk.game.chess.core.search;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import coeusyk.game.chess.core.eval.ClassicalEvaluator;
import coeusyk.game.chess.core.eval.nnue.NnueEvaluator;
import coeusyk.game.chess.core.eval.nnue.TestNetworks;
import coeusyk.game.chess.core.movegen.MovesGenerator;
import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

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
    private static final String[] P18_REFERENCE_FENS = {
        "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
        "4k3/8/8/8/8/8/4P3/4K3 w - - 5 39",
        "r3r1k1/2p2ppp/p1p1bn2/8/1q2P3/2NPQN2/PPP3PP/R4RK1 b - - 2 24",
        "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
        "r4rk1/3qppbp/6p1/2p1n3/2B1P3/2P5/P2Q1PPP/R4RK1 b - - 0 20"
    };

    @Test
    void p18ReferencePrincipalVariationsAreLegal() {
        for (int i = 0; i < P18_REFERENCE_FENS.length; i++) {
            Board board = new Board(P18_REFERENCE_FENS[i]);
            board.setSearchMode(true);
            Searcher searcher = new Searcher();
            searcher.setTranspositionTableSizeMb(16);
            SearchResult result = searcher.searchDepth(board, 8);
            assertPrincipalVariationIsLegal(board, result.principalVariation(), "P18-5 position " + i);
        }
    }

    private static void assertPrincipalVariationIsLegal(Board board, java.util.List<Move> pv, String name) {
        for (Move move : pv) {
            boolean legal = new MovesGenerator(board).getAllMoves().stream()
                    .anyMatch(candidate -> candidate.pack() == move.pack());
            assertTrue(legal, "Illegal PV move for " + name + ": " + move.pack());
            board.makeMove(move);
        }
    }

    @Test
    void phase21ReportsAreOptInCumulativeAndPreserveSearch() {
        Logger logger = (Logger) LoggerFactory.getLogger(Searcher.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            SearchResult control = new Searcher().searchDepth(new Board(MIDDLEGAME_FEN), 6);
            assertTrue(appender.list.stream().noneMatch(event ->
                    event.getFormattedMessage().startsWith("[BENCH] phase21 ")));
            appender.list.clear();

            Searcher instrumented = new Searcher();
            instrumented.setInstrumentationEnabled(true);
            SearchResult candidate = instrumented.searchDepth(new Board(MIDDLEGAME_FEN), 6);
            assertEquals(control.bestMove().pack(), candidate.bestMove().pack());
            assertEquals(control.scoreCp(), candidate.scoreCp());
            assertEquals(control.nodesVisited(), candidate.nodesVisited());
            assertEquals(control.quiescenceNodes(), candidate.quiescenceNodes());
            assertEquals(control.ttHits(), candidate.ttHits());
            assertEquals(control.principalVariation().stream().map(Move::pack).toList(),
                    candidate.principalVariation().stream().map(Move::pack).toList());

            var reports = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.startsWith("[BENCH] phase21 "))
                    .map(SearcherInstrumentationTest::phase21Counters).toList();
            assertEquals(6, reports.size());
            Map<String, Long> previous = Map.of();
            for (int i = 0; i < reports.size(); i++) {
                Map<String, Long> report = reports.get(i);
                assertEquals(28, report.size());
                assertEquals(i + 1L, report.get("depth"));
                assertTrue(report.containsKey("pvs_zero_window_probes"));
                for (var counter : report.entrySet()) {
                    assertTrue(counter.getValue() >= previous.getOrDefault(counter.getKey(), 0L),
                            counter.getKey() + " must be cumulative");
                }
                assertEquals(report.get("ab_calls").longValue(), report.get("pv_wide")
                        + report.get("pv_null") + report.get("nonpv_wide") + report.get("nonpv_null"));
                assertEquals(0L, report.get("pv_null"));
                assertEquals(0L, report.get("nonpv_wide"));
                assertEquals(report.get("lmr_fail_highs"), report.get("lmr_researches"));
                assertTrue(report.get("lmr_fail_highs") <= report.get("lmr_probes"));
                assertTrue(report.get("pvs_zero_window_probes") > 0);
                assertTrue(report.get("full_depth_zero_window_probes") >= report.get("lmr_researches"));
                assertEquals(report.get("full_window_researches"), report.get("full_window_researches_pv_owned"));
                assertTrue(report.get("full_window_researches") <= report.get("pv_wide_zero_window_probes")
                        + report.get("root_wide_zero_window_probes"));
                previous = report;
            }
            Map<String, Long> last = reports.getLast();
            assertTrue(last.get("ab_calls") > candidate.nodesVisited(), "entry population includes horizon/early returns");
            assertTrue(last.get("lmr_fail_highs") > 0, "exercise the existing verification gate");
            assertEquals(candidate.lmrApplications(), last.get("lmr_probes"));
            assertTrue(last.get("pv_wide_full_window_research_le_alpha")
                    + last.get("pv_wide_full_window_research_inside")
                    + last.get("pv_wide_full_window_research_ge_beta") > 0);
            assertTrue(last.get("root_wide_full_window_research_le_alpha")
                    + last.get("root_wide_full_window_research_inside")
                    + last.get("root_wide_full_window_research_ge_beta") > 0);
            assertTrue(last.get("pv_wide_zero_window_probes") > 0);
            assertTrue(last.get("root_wide_zero_window_probes") > 0);
            assertTrue(last.get("root_full_window_researches") <= last.get("root_wide_zero_window_probes"));

            appender.list.clear();
            instrumented.searchDepth(new Board(MIDDLEGAME_FEN), 1);
            var resetReports = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.startsWith("[BENCH] phase21 "))
                    .map(SearcherInstrumentationTest::phase21Counters).toList();
            assertEquals(1, resetReports.size());
            Map<String, Long> reset = resetReports.getFirst();
            assertTrue(reset.get("ab_calls") > 0);
            assertEquals(reset.get("ab_calls").longValue(), reset.get("pv_wide") + reset.get("pv_null")
                    + reset.get("nonpv_wide") + reset.get("nonpv_null"));
            assertEquals(0L, resetReports.getFirst().get("lmr_probes"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(previousLevel);
        }
    }

    private static Map<String, Long> phase21Counters(String message) {
        Map<String, Long> counters = new HashMap<>();
        for (String token : message.split(" ")) {
            int separator = token.indexOf('=');
            if (separator > 0) {
                counters.put(token.substring(0, separator), Long.parseLong(token.substring(separator + 1)));
            }
        }
        return counters;
    }

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
