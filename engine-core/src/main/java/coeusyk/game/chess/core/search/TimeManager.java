package coeusyk.game.chess.core.search;

import coeusyk.game.chess.core.models.Piece;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TimeManager {
    private static final Logger LOG = LoggerFactory.getLogger(TimeManager.class);
    private long moveOverheadMs = 30;
    private long softLimitMs = 1;
    private long hardLimitMs = 1;
    private long startNanos;

    public void setMoveOverheadMs(long moveOverheadMs) {
        if (moveOverheadMs < 0) {
            throw new IllegalArgumentException("move overhead must be >= 0");
        }
        this.moveOverheadMs = moveOverheadMs;
    }

    public long getMoveOverheadMs() {
        return moveOverheadMs;
    }

    public void configureMovetime(long movetimeMs) {
        if (movetimeMs <= 0) {
            throw new IllegalArgumentException("movetime must be > 0");
        }

        long usable = Math.max(1, movetimeMs - moveOverheadMs);
        softLimitMs = usable;
        hardLimitMs = usable;
    }

    public void configureClock(int activeColor, long wtimeMs, long btimeMs, long wincMs, long bincMs) {
        long remaining = Piece.isWhite(activeColor) ? wtimeMs : btimeMs;
        long increment = Piece.isWhite(activeColor) ? wincMs : bincMs;
        long overhead = moveOverheadMs;

        long soft = (remaining / 20) + (increment * 3 / 4) - overhead;
        // Hard limit is capped at 2.5× soft to prevent a single deep iteration
        // from consuming an unreasonable fraction of the remaining clock time.
        // Previously 4×, which allowed single depths to run 75–116 s on
        // long time controls.
        long hard = Math.min(remaining / 3, soft * 5 / 2) - overhead;

        softLimitMs = Math.max(soft, 50);
        hardLimitMs = Math.max(hard, softLimitMs + 50);

        // Safety cap: never spend more than half of remaining clock on a single move.
        // When remaining < ~250 ms the floor above can push hardLimitMs above the
        // available budget, causing certain time-loss in Lazy-SMP mode where helpers
        // seed the TT and push the main search to deeper depths than at 1T.
        long safetyMax = Math.max(1L, (remaining - overhead) / 2);
        softLimitMs = Math.min(softLimitMs, safetyMax);
        hardLimitMs = Math.min(hardLimitMs, safetyMax);

        LOG.debug(String.format("[TIME] allocated soft=%dms hard=%dms", softLimitMs, hardLimitMs));
    }

    public void startNow() {
        startNanos = System.nanoTime();
    }

    public boolean shouldStopSoft() {
        return elapsedMs() >= softLimitMs;
    }

    public boolean shouldStopHard() {
        return elapsedMs() >= hardLimitMs;
    }

    public long getSoftLimitMs() {
        return softLimitMs;
    }

    public long getHardLimitMs() {
        return hardLimitMs;
    }

    public long elapsedMs() {
        if (startNanos == 0) {
            return 0;
        }
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

}