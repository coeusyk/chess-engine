package coeusyk.game.chess.engine;

import coeusyk.game.chess.models.Piece;

/**
 * Computes and tracks soft / hard time limits for a single search.
 *
 * <p>Soft limit — the search tries to finish the current iterative-deepening
 * iteration before stopping.
 * Hard limit — the search aborts immediately, even mid-iteration.
 *
 * <p>The move-overhead buffer is subtracted from the available clock time before
 * any limit is computed, to guard against network / GUI latency causing the
 * engine to flag.
 */
public class TimeManager {

    /** Default per-move overhead buffer in milliseconds. */
    public static final int DEFAULT_OVERHEAD_MS = 30;

    /**
     * When the number of remaining moves to the next time control is unknown,
     * assume this many moves are left in the game.
     */
    private static final int MOVES_TO_GO_ESTIMATE = 30;

    /**
     * Multiplier applied to the soft limit to derive the hard limit.
     * Capped at the total available time.
     */
    private static final double SOFT_TO_HARD_MULTIPLIER = 3.0;

    /**
     * Fraction of the per-move increment that is added to the soft limit.
     * Using 75 % (rather than 100 %) keeps a small reserve of the increment for
     * situations where the search overruns the soft limit slightly.
     */
    private static final double INCREMENT_USAGE_FACTOR = 0.75;

    private final int overheadMs;

    private long startTimeMs;
    private long softLimitMs;
    private long hardLimitMs;

    /** Creates a {@code TimeManager} with the default overhead buffer (30 ms). */
    public TimeManager() {
        this(DEFAULT_OVERHEAD_MS);
    }

    /**
     * Creates a {@code TimeManager} with a configurable overhead buffer.
     *
     * @param overheadMs milliseconds to subtract from available time before
     *                   computing limits; must be non-negative
     */
    public TimeManager(int overheadMs) {
        if (overheadMs < 0) {
            throw new IllegalArgumentException("overheadMs must be non-negative");
        }
        this.overheadMs = overheadMs;
    }

    /**
     * Records the search start time and computes soft / hard limits from the
     * supplied {@link GoParams}.
     *
     * @param params      UCI go-command parameters
     * @param activeColor the side to move ({@link Piece#White} or {@link Piece#Black})
     */
    public void start(GoParams params, int activeColor) {
        startTimeMs = System.currentTimeMillis();
        computeLimits(params, activeColor);
    }

    // -------------------------------------------------------------------------
    // Limit queries
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} once the elapsed time has reached the soft limit.
     * The caller (iterative-deepening loop) should finish the current iteration
     * and then stop.
     */
    public boolean isSoftLimitReached() {
        return elapsedMs() >= softLimitMs;
    }

    /**
     * Returns {@code true} once the elapsed time has reached the hard limit.
     * The caller (inner search loop) should abort immediately.
     */
    public boolean isHardLimitReached() {
        return elapsedMs() >= hardLimitMs;
    }

    /** Returns the number of milliseconds elapsed since {@link #start} was called. */
    public long elapsedMs() {
        return System.currentTimeMillis() - startTimeMs;
    }

    // -------------------------------------------------------------------------
    // Accessors (useful for logging / testing)
    // -------------------------------------------------------------------------

    /** Returns the configured overhead buffer in milliseconds. */
    public int getOverheadMs() {
        return overheadMs;
    }

    /** Returns the computed soft limit in milliseconds. */
    public long getSoftLimitMs() {
        return softLimitMs;
    }

    /** Returns the computed hard limit in milliseconds. */
    public long getHardLimitMs() {
        return hardLimitMs;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private void computeLimits(GoParams params, int activeColor) {
        // Infinite search — limits are effectively unbounded.
        if (params.infinite) {
            softLimitMs = Long.MAX_VALUE;
            hardLimitMs = Long.MAX_VALUE;
            return;
        }

        // Fixed movetime — both limits equal the requested duration.
        if (params.movetime > 0) {
            softLimitMs = params.movetime;
            hardLimitMs = params.movetime;
            return;
        }

        // Clock-based time management.
        long myTime = (activeColor == Piece.White) ? params.wtime : params.btime;
        long myInc  = (activeColor == Piece.White) ? params.winc  : params.binc;

        // Subtract overhead to avoid flagging.
        myTime = Math.max(0, myTime - overheadMs);

        long soft;
        if (params.movesToGo > 0) {
            // Exact number of moves until the next time control is known.
            soft = myTime / params.movesToGo + myInc;
        } else {
            // Estimate the number of remaining moves.
            soft = myTime / MOVES_TO_GO_ESTIMATE + (long) (myInc * INCREMENT_USAGE_FACTOR);
        }

        // Clamp soft limit so we never plan to use more time than we have.
        softLimitMs = Math.min(soft, myTime);

        // Hard limit is a multiple of the soft limit, again capped at total available time.
        hardLimitMs = Math.min((long) (softLimitMs * SOFT_TO_HARD_MULTIPLIER), myTime);
    }
}
