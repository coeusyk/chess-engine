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
}