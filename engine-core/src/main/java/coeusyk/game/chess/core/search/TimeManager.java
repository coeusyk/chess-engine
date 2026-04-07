package coeusyk.game.chess.core.search;

import coeusyk.game.chess.core.models.Piece;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TimeManager {
    private static final Logger LOG = LoggerFactory.getLogger(TimeManager.class);
    private long moveOverheadMs = 30;
    private volatile long softLimitMs = 1;
    private volatile long hardLimitMs = 1;
    private volatile long startNanos;

    /**
     * Multiplier applied to softLimitMs inside shouldStopSoft().
     * Values below 1.0 allow early termination on stable positions;
     * values above 1.0 extend time when the best move changes.
     * Written and read only by the main search thread — no volatile needed.
     */
    private double stabilityScale = 1.0;

    public void setMoveOverheadMs(long moveOverheadMs) {
        if (moveOverheadMs < 0) {
            throw new IllegalArgumentException("move overhead must be >= 0");
        }
        this.moveOverheadMs = moveOverheadMs;
    }

    public long getMoveOverheadMs() {
        return moveOverheadMs;
    }

    /**
     * Sets the stability multiplier applied to the soft limit.
     * Must be called from the main search thread only.
     *
     * @param scale multiplier in [0.4, 2.0]; clamped silently outside that range
     */
    public void setStabilityScale(double scale) {
        this.stabilityScale = Math.max(0.4, Math.min(2.0, scale));
    }

    public double getStabilityScale() {
        return stabilityScale;
    }

    public void configureMovetime(long movetimeMs) {
        if (movetimeMs <= 0) {
            throw new IllegalArgumentException("movetime must be > 0");
        }

        long usable = Math.max(1, movetimeMs - moveOverheadMs);
        softLimitMs = usable;
        hardLimitMs = usable;
        stabilityScale = 1.0;
    }

    /**
     * Configures the time manager for ponder mode: both limits are set to
     * effectively infinite so the search runs until {@code stop} or
     * {@code ponderhit} arrives.
     */
    public void configurePonder() {
        softLimitMs = Long.MAX_VALUE / 2;
        hardLimitMs = Long.MAX_VALUE / 2;
        stabilityScale = 1.0;
    }

    /**
     * Reconfigures the time manager when a {@code ponderhit} command is received.
     * Resets the clock origin to "now" and calculates normal clock-based limits.
     */
    public void configurePonderHit(int activeColor, long wtimeMs, long btimeMs, long wincMs, long bincMs) {
        configurePonderHit(activeColor, wtimeMs, btimeMs, wincMs, bincMs, 0);
    }

    public void configurePonderHit(int activeColor, long wtimeMs, long btimeMs, long wincMs, long bincMs, int moveNumber) {
        startNanos = System.nanoTime();
        configureClock(activeColor, wtimeMs, btimeMs, wincMs, bincMs, moveNumber);
    }

    /**
     * Configures time limits for a normal clock-based search.
     * Uses move-number-aware divisor: deeper into the game the divisor falls
     * toward 15, reflecting fewer remaining moves, so more time is spent per move
     * in the endgame and less in the opening (where positions are often stable).
     *
     * @param moveNumber full moves already played (0 = starting position); used to
     *                   estimate how many moves remain and size the soft limit
     *                   accordingly.
     */
    public void configureClock(int activeColor, long wtimeMs, long btimeMs, long wincMs, long bincMs) {
        configureClock(activeColor, wtimeMs, btimeMs, wincMs, bincMs, 0);
    }

    public void configureClock(int activeColor, long wtimeMs, long btimeMs, long wincMs, long bincMs, int moveNumber) {
        long remaining = Piece.isWhite(activeColor) ? wtimeMs : btimeMs;
        long increment = Piece.isWhite(activeColor) ? wincMs : bincMs;
        long overhead = moveOverheadMs;

        // Dynamic divisor: estimated moves left, clamped to [15, 40].
        // At move 1 the engine expects ~49 moves ahead → divisor=40 (cap).
        // At move 35+ the engine expects ~15 moves ahead → divisor=15.
        // This prevents the flat remaining/20 from over-allocating in the opening
        // and under-allocating in the endgame.
        int divisor = Math.max(15, Math.min(40, 50 - Math.max(0, moveNumber)));

        long soft = (remaining / divisor) + (increment * 3 / 4) - overhead;
        // Hard limit is capped at 2× soft to prevent a single deep iteration
        // from consuming an unreasonable fraction of the remaining clock time.
        long hard = Math.min(remaining / 3, soft * 2) - overhead;

        softLimitMs = Math.max(soft, 50);
        hardLimitMs = Math.max(hard, softLimitMs + 50);

        // Safety cap: never spend more than half of remaining clock on a single move.
        long safetyMax = Math.max(1L, (remaining - overhead) / 2);
        softLimitMs = Math.min(softLimitMs, safetyMax);
        hardLimitMs = Math.min(hardLimitMs, safetyMax);

        // Reset stability scale for the new position.
        stabilityScale = 1.0;

        LOG.debug(String.format("[TIME] move=%d divisor=%d allocated soft=%dms hard=%dms",
                moveNumber, divisor, softLimitMs, hardLimitMs));
    }

    public void startNow() {
        startNanos = System.nanoTime();
    }

    public boolean shouldStopSoft() {
        return elapsedMs() >= (long) (softLimitMs * stabilityScale);
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