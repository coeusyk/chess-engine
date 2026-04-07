package coeusyk.game.chess.core.search;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Piece;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TimeManagerTest {

    @Test
    void movetimeModeUsesSoftAndHardFromMovetimeMinusOverhead() {
        TimeManager manager = new TimeManager();
        manager.setMoveOverheadMs(30);

        manager.configureMovetime(200);

        assertEquals(170, manager.getSoftLimitMs());
        assertEquals(170, manager.getHardLimitMs());
    }

    @Test
    void clockModeComputesSoftAndHardLimitsWithOverhead() {
        TimeManager manager = new TimeManager();
        manager.setMoveOverheadMs(30);

        manager.configureClock(Piece.White, 10_000, 8_000, 500, 200);

        assertTrue(manager.getSoftLimitMs() >= 1);
        assertTrue(manager.getHardLimitMs() >= manager.getSoftLimitMs());
        assertTrue(manager.getHardLimitMs() <= 9_970);
    }

    @Test
    void safetyCapPreventsHardLimitExceedingHalfOfRemainingTime() {
        // When remaining < ~250 ms the floor (max(hard, soft+50)) can push hardLimitMs
        // above the available budget, causing time loss in Lazy-SMP mode.
        // The safety cap must clamp hardLimitMs to at most (remaining - overhead) / 2.
        TimeManager manager = new TimeManager();
        manager.setMoveOverheadMs(30);

        // 100 ms remaining, 50 ms increment → safetyMax = (100-30)/2 = 35 ms
        manager.configureClock(Piece.White, 100, 100, 50, 50);
        assertTrue(manager.getHardLimitMs() <= 35,
                "hardLimitMs should be ≤ 35 ms but was " + manager.getHardLimitMs());
    }

    @Test
    void searchAbortsUnderHardLimitAndReturnsPromptly() {
        Board board = new Board();
        Searcher searcher = new Searcher();
        TimeManager manager = new TimeManager();
        manager.setMoveOverheadMs(0);
        manager.configureMovetime(10);

        long started = System.nanoTime();
        SearchResult result = searcher.searchWithTimeManager(board, 30, manager);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;

        assertTrue(result.aborted());
        assertTrue(elapsedMs < 500);
    }

    @Test
    void moveNumberAwareDivisorAllocatesLessTimeInOpening() {
        // Move 1 (divisor=40) should produce a smaller soft limit than move 35 (divisor=15)
        // even though both have the same remaining time, because the opening is expected
        // to have more moves ahead.
        TimeManager early = new TimeManager();
        early.setMoveOverheadMs(0);
        early.configureClock(Piece.White, 300_000, 300_000, 0, 0, 1);

        TimeManager late = new TimeManager();
        late.setMoveOverheadMs(0);
        late.configureClock(Piece.White, 300_000, 300_000, 0, 0, 35);

        assertTrue(early.getSoftLimitMs() < late.getSoftLimitMs(),
                "Opening soft limit (" + early.getSoftLimitMs() + " ms) should be less than " +
                "endgame soft limit (" + late.getSoftLimitMs() + " ms) at same remaining clock");
    }

    @Test
    void stabilityScaleRestoresAfterConfigureClock() {
        TimeManager manager = new TimeManager();
        manager.setMoveOverheadMs(0);
        manager.configureClock(Piece.White, 300_000, 300_000, 0, 0, 10);
        manager.setStabilityScale(0.75);
        assertEquals(0.75, manager.getStabilityScale(), 1e-9);

        // Re-configuring the clock should reset the scale to 1.0.
        manager.configureClock(Piece.White, 290_000, 290_000, 0, 0, 11);
        assertEquals(1.0, manager.getStabilityScale(), 1e-9);
    }

    @Test
    void stabilityScaleClampedToLegalRange() {
        TimeManager manager = new TimeManager();
        manager.setStabilityScale(0.1);
        assertEquals(0.4, manager.getStabilityScale(), 1e-9, "below minimum should be clamped to 0.4");

        manager.setStabilityScale(5.0);
        assertEquals(2.0, manager.getStabilityScale(), 1e-9, "above maximum should be clamped to 2.0");
    }

    @Test
    void shouldStopSoftRespectsStabilityScale() {
        TimeManager manager = new TimeManager();
        manager.setMoveOverheadMs(0);
        manager.configureMovetime(1000);
        manager.startNow();

        // With scale=1.0 and elapsed≈0, soft should not fire.
        assertFalse(manager.shouldStopSoft());

        // With scale=0.0001 (floored to 0.4), soft still should NOT fire immediately
        // since elapsed≈0 and softLimit=1000*0.4=400ms.
        manager.setStabilityScale(0.0001);
        assertFalse(manager.shouldStopSoft());
    }
}