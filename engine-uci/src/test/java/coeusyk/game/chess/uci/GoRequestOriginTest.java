package coeusyk.game.chess.uci;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.search.TimeManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** What each kind of go does with the request-receipt origin. Origins are placed in the past, so nothing here sleeps. */
class GoRequestOriginTest {
    private static final long SECOND_NANOS = 1_000_000_000L;

    private final UciApplication app = new UciApplication();
    private final Board board = new Board();

    private TimeManager build(String go, boolean ponder, long origin) {
        return app.buildTimeManager(go.split("\\s+"), board, ponder, origin);
    }

    @Test
    void movetimeKeepsTheRequestOrigin() {
        TimeManager manager = build("go movetime 1000", false, System.nanoTime() - 700_000_000L);

        manager.startNow();

        assertTrue(manager.elapsedMs() >= 700);
        assertEquals(970, manager.getHardLimitMs(), "allocation is unchanged by the origin");
    }

    @Test
    void clockSearchKeepsTheRequestOrigin() {
        TimeManager manager = build("go wtime 60000 btime 60000 winc 1000 binc 1000", false,
                System.nanoTime() - 700_000_000L);

        manager.startNow();

        assertTrue(manager.elapsedMs() >= 700);
    }

    @Test
    void depthNodeAndInfiniteSearchesAreNotTimed() {
        long origin = System.nanoTime() - 5 * SECOND_NANOS;
        assertNull(build("go depth 6", false, origin));
        assertNull(build("go nodes 100000", false, origin));
        assertNull(build("go infinite", false, origin));
    }

    @Test
    void ponderSearchRunsUntilPonderHitAndThenStartsAFreshClock() {
        TimeManager manager = build("go ponder wtime 60000 btime 60000", true, System.nanoTime() - 5 * SECOND_NANOS);

        manager.startNow();
        assertTrue(manager.getHardLimitMs() > 1_000_000_000L, "ponder is unbounded until ponderhit");
        assertFalse(manager.shouldStopHard());

        manager.configurePonderHit(System.nanoTime());
        assertEquals(2_910, manager.getHardLimitMs());
        assertTrue(manager.elapsedMs() < 100, "the ponderhit clock must not include the ponder time");
        assertFalse(manager.shouldStopHard());
    }

    @Test
    void fixedMovetimeKeepsItsBudgetWhenPonderHitPrecedesWorkerStart() {
        long go = System.nanoTime() - 5 * SECOND_NANOS;
        TimeManager manager = build("go ponder movetime 1000 wtime 60000 btime 60000", true, go);
        assertFalse(manager.shouldStopHard());

        manager.configurePonderHit(System.nanoTime() - 2 * SECOND_NANOS);
        manager.startNow();

        assertEquals(970, manager.getSoftLimitMs());
        assertEquals(970, manager.getHardLimitMs());
        assertTrue(manager.shouldStopHard(), "worker startup must charge time since the hit");
    }

    @Test
    void eachGoGetsItsOwnOrigin() {
        long first = System.nanoTime() - 3 * SECOND_NANOS;
        long second = System.nanoTime() - 400_000_000L;

        TimeManager a = build("go movetime 5000", false, first);
        TimeManager b = build("go movetime 5000", false, second);
        a.startNow();
        b.startNow();

        assertTrue(a.elapsedMs() >= 3_000);
        assertTrue(b.elapsedMs() >= 400 && b.elapsedMs() < 1_000, "was " + b.elapsedMs());
    }
}
