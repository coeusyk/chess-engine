package coeusyk.game.chess.engine;

import coeusyk.game.chess.models.Piece;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TimeManagerTest {

    // -------------------------------------------------------------------------
    // Constructor / configuration
    // -------------------------------------------------------------------------

    @Test
    void defaultOverheadIs30ms() {
        TimeManager tm = new TimeManager();
        assertEquals(30, tm.getOverheadMs());
    }

    @Test
    void customOverheadIsStored() {
        TimeManager tm = new TimeManager(50);
        assertEquals(50, tm.getOverheadMs());
    }

    @Test
    void negativeOverheadThrows() {
        assertThrows(IllegalArgumentException.class, () -> new TimeManager(-1));
    }

    // -------------------------------------------------------------------------
    // movetime handling
    // -------------------------------------------------------------------------

    @Test
    void movetimeSetsEqualSoftAndHardLimits() {
        TimeManager tm = new TimeManager();
        GoParams p = new GoParams();
        p.movetime = 500;

        tm.start(p, Piece.White);

        assertEquals(500, tm.getSoftLimitMs());
        assertEquals(500, tm.getHardLimitMs());
    }

    @Test
    void movetimeOverridesClockParams() {
        TimeManager tm = new TimeManager();
        GoParams p = new GoParams();
        p.wtime = 60_000;
        p.btime = 60_000;
        p.movetime = 1_000;

        tm.start(p, Piece.White);

        assertEquals(1_000, tm.getSoftLimitMs());
        assertEquals(1_000, tm.getHardLimitMs());
    }

    // -------------------------------------------------------------------------
    // Infinite search
    // -------------------------------------------------------------------------

    @Test
    void infiniteSearchHasMaxLimits() {
        TimeManager tm = new TimeManager();
        GoParams p = new GoParams();
        p.infinite = true;

        tm.start(p, Piece.White);

        assertEquals(Long.MAX_VALUE, tm.getSoftLimitMs());
        assertEquals(Long.MAX_VALUE, tm.getHardLimitMs());
        assertFalse(tm.isSoftLimitReached());
        assertFalse(tm.isHardLimitReached());
    }

    // -------------------------------------------------------------------------
    // Clock-based time management
    // -------------------------------------------------------------------------

    @Test
    void overheadIsSubtractedFromAvailableTime() {
        // With 30 ms overhead and 60 s on the clock (no increment, 30-move estimate):
        // available = 60_000 - 30 = 59_970
        // soft = 59_970 / 30 = 1_999
        TimeManager tm = new TimeManager(30);
        GoParams p = new GoParams();
        p.wtime = 60_000;

        tm.start(p, Piece.White);

        // Soft limit should be less than 60_000/30 = 2_000 (overhead subtracted).
        assertTrue(tm.getSoftLimitMs() < 2_000,
            "Soft limit should reflect overhead deduction");
        assertTrue(tm.getSoftLimitMs() > 0,
            "Soft limit should be positive");
    }

    @Test
    void hardLimitIsGreaterThanOrEqualToSoftLimit() {
        TimeManager tm = new TimeManager();
        GoParams p = new GoParams();
        p.wtime = 120_000;
        p.winc = 500;

        tm.start(p, Piece.White);

        assertTrue(tm.getHardLimitMs() >= tm.getSoftLimitMs(),
            "Hard limit must be >= soft limit");
    }

    @Test
    void hardLimitDoesNotExceedAvailableTime() {
        TimeManager tm = new TimeManager(30);
        GoParams p = new GoParams();
        p.wtime = 5_000;

        tm.start(p, Piece.White);

        long available = 5_000 - 30;
        assertTrue(tm.getHardLimitMs() <= available,
            "Hard limit must not exceed available time");
    }

    @Test
    void blackUsesBlackClockAndIncrement() {
        TimeManager tmW = new TimeManager(0);
        GoParams pW = new GoParams();
        pW.wtime = 60_000;
        pW.btime = 10_000;
        pW.winc = 0;
        pW.binc = 0;
        tmW.start(pW, Piece.White);

        TimeManager tmB = new TimeManager(0);
        GoParams pB = new GoParams();
        pB.wtime = 60_000;
        pB.btime = 10_000;
        pB.winc = 0;
        pB.binc = 0;
        tmB.start(pB, Piece.Black);

        // White has more time, so should get a larger soft limit.
        assertTrue(tmW.getSoftLimitMs() > tmB.getSoftLimitMs(),
            "White (60 s) should have a larger soft limit than black (10 s)");
    }

    @Test
    void movesToGoIsUsedWhenProvided() {
        // movesToGo=5 with 5_000 ms → target ≈ 5_000/5 = 1_000 ms (ignoring overhead).
        TimeManager tmKnown = new TimeManager(0);
        GoParams pKnown = new GoParams();
        pKnown.wtime = 5_000;
        pKnown.movesToGo = 5;
        tmKnown.start(pKnown, Piece.White);

        // Default estimate uses 30 moves → target ≈ 5_000/30 ≈ 167 ms.
        TimeManager tmUnknown = new TimeManager(0);
        GoParams pUnknown = new GoParams();
        pUnknown.wtime = 5_000;
        tmUnknown.start(pUnknown, Piece.White);

        assertTrue(tmKnown.getSoftLimitMs() > tmUnknown.getSoftLimitMs(),
            "movesToGo=5 should allocate more time per move than the 30-move estimate");
    }

    @Test
    void incrementIsIncludedInSoftLimit() {
        TimeManager tmNoInc = new TimeManager(0);
        GoParams pNoInc = new GoParams();
        pNoInc.wtime = 60_000;
        tmNoInc.start(pNoInc, Piece.White);

        TimeManager tmInc = new TimeManager(0);
        GoParams pInc = new GoParams();
        pInc.wtime = 60_000;
        pInc.winc = 500;
        tmInc.start(pInc, Piece.White);

        assertTrue(tmInc.getSoftLimitMs() > tmNoInc.getSoftLimitMs(),
            "Increment should increase the soft time limit");
    }

    // -------------------------------------------------------------------------
    // Limit detection
    // -------------------------------------------------------------------------

    @Test
    void softLimitNotReachedImmediatelyAfterStart() {
        TimeManager tm = new TimeManager();
        GoParams p = new GoParams();
        p.movetime = 500;

        tm.start(p, Piece.White);

        assertFalse(tm.isSoftLimitReached(), "Soft limit should not be reached immediately");
    }

    @Test
    void hardLimitNotReachedImmediatelyAfterStart() {
        TimeManager tm = new TimeManager();
        GoParams p = new GoParams();
        p.movetime = 500;

        tm.start(p, Piece.White);

        assertFalse(tm.isHardLimitReached(), "Hard limit should not be reached immediately");
    }

    @Test
    void limitsReachedAfterElapsedTimeExceedsMovetime() throws InterruptedException {
        TimeManager tm = new TimeManager();
        GoParams p = new GoParams();
        p.movetime = 50;

        tm.start(p, Piece.White);

        Thread.sleep(80);

        assertTrue(tm.isSoftLimitReached(), "Soft limit should be reached after movetime elapses");
        assertTrue(tm.isHardLimitReached(), "Hard limit should be reached after movetime elapses");
    }

    @Test
    void elapsedMsIncreasesOverTime() throws InterruptedException {
        TimeManager tm = new TimeManager();
        GoParams p = new GoParams();
        p.movetime = 1_000;

        tm.start(p, Piece.White);
        long first = tm.elapsedMs();

        Thread.sleep(30);
        long second = tm.elapsedMs();

        assertTrue(second > first, "Elapsed time should increase between calls");
    }

    // -------------------------------------------------------------------------
    // Edge cases
    // -------------------------------------------------------------------------

    @Test
    void zeroClockTimeResultsInZeroLimits() {
        TimeManager tm = new TimeManager();
        GoParams p = new GoParams();
        p.wtime = 0;

        tm.start(p, Piece.White);

        assertEquals(0, tm.getSoftLimitMs(), "Zero clock time should yield a zero soft limit");
        assertEquals(0, tm.getHardLimitMs(), "Zero clock time should yield a zero hard limit");
    }

    @Test
    void overheadLargerThanClockTimeClampedToZero() {
        // overhead=100, wtime=50 → available = max(0, 50-100) = 0
        TimeManager tm = new TimeManager(100);
        GoParams p = new GoParams();
        p.wtime = 50;

        tm.start(p, Piece.White);

        assertEquals(0, tm.getSoftLimitMs());
        assertEquals(0, tm.getHardLimitMs());
    }
}
