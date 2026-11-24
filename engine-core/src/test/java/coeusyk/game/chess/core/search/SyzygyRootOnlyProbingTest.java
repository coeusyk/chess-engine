package coeusyk.game.chess.core.search;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.syzygy.DTZResult;
import coeusyk.game.chess.core.syzygy.SyzygyProber;
import coeusyk.game.chess.core.syzygy.WDLResult;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A blocking prober is consulted at the root only; the root answer still decides the move. */
class SyzygyRootOnlyProbingTest {
    private static final String KQK = "8/8/4k3/8/8/8/4K3/Q7 w - - 0 1";

    private static final class CountingProber implements SyzygyProber {
        final AtomicInteger wdlCalls = new AtomicInteger();
        final AtomicInteger dtzCalls = new AtomicInteger();
        final boolean interior;
        final DTZResult rootAnswer;

        CountingProber(boolean interior, DTZResult rootAnswer) {
            this.interior = interior;
            this.rootAnswer = rootAnswer;
        }

        @Override public WDLResult probeWDL(Board board) { wdlCalls.incrementAndGet(); return WDLResult.INVALID; }
        @Override public DTZResult probeDTZ(Board board) { dtzCalls.incrementAndGet(); return rootAnswer; }
        @Override public boolean isAvailable() { return true; }
        @Override public boolean allowsInteriorProbing() { return interior; }
    }

    private static SearchResult search(CountingProber prober) {
        Searcher searcher = new Searcher();
        searcher.setSyzygyProber(prober);
        return searcher.iterativeDeepening(new Board(KQK), 4);
    }

    @Test
    void aBlockingProberIsNeverCalledBelowTheRoot() {
        CountingProber prober = new CountingProber(false, DTZResult.INVALID);
        assertNotNull(search(prober).bestMove());
        assertEquals(0, prober.wdlCalls.get(), "no interior probe may reach a blocking prober");
        assertTrue(prober.dtzCalls.get() >= 1, "the root is still probed");
    }

    @Test
    void anInteriorSafeProberStillGetsInteriorProbes() {
        CountingProber prober = new CountingProber(true, DTZResult.INVALID);
        search(prober);
        assertTrue(prober.wdlCalls.get() > 0, "default behaviour for local tablebases is unchanged");
    }

    @Test
    void theRootProbeStillChoosesTheMoveAndScoresIt() {
        // a1a5: queen from square 56 to square 24.
        CountingProber prober = new CountingProber(false, new DTZResult("a1a5", 3, WDLResult.WDL.WIN, true));
        SearchResult result = search(prober);
        assertEquals(56, result.bestMove().startSquare);
        assertEquals(24, result.bestMove().targetSquare);
        assertTrue(result.scoreCp() > 0, "a tablebase win is reported as a winning score");
        assertEquals(0, prober.wdlCalls.get());
    }
}
