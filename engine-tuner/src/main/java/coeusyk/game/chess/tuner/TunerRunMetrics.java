package coeusyk.game.chess.tuner;

import java.util.Arrays;

/**
 * Mutable stats collector populated by an optimizer during a tuning run.
 * Passed into tuning methods and filled as the run proceeds so the post-run
 * validator can assess convergence quality.
 */
public final class TunerRunMetrics {

    /** True when the run terminated because the iteration cap was reached
     *  (as opposed to a convergence criterion or plateau stop). */
    public boolean hitIterCap = false;

    /** Number of optimizer iterations that actually ran. */
    public int itersCompleted = 0;

    /** Final MSE at the last completed iteration. */
    public double finalMse = Double.NaN;

    /** Minimum MSE observed at any point during the run. */
    public double minMse = Double.MAX_VALUE;

    /** Ring buffer of the most recent relative-delta values
     *  {@code |currentMse - newMse| / currentMse}.
     *  Contents are meaningful only after at least one iteration. */
    private final double[] relDeltaRing = new double[20];
    private int ringHead  = 0;
    private int ringFilled = 0; // number of values written (capped at 20)

    /** Record a new per-iteration relative delta. */
    public void recordRelDelta(double relDelta) {
        relDeltaRing[ringHead % 20] = relDelta;
        ringHead++;
        if (ringFilled < 20) ringFilled++;
    }

    /**
     * Returns the relative deltas recorded so far (copy of ring, up to 20 entries).
     * The array is in chronological order (oldest first) when the ring has been
     * filled less than 20 times; after 20 fills the order wraps.
     */
    public double[] getRecentRelDeltas() {
        return Arrays.copyOf(relDeltaRing, ringFilled);
    }

    /**
     * Returns the mean of the most-recent {@code n} relative deltas.
     * If fewer than {@code n} deltas have been recorded, uses all available values.
     * Returns {@link Double#NaN} if no deltas have been recorded.
     */
    public double meanRecentRelDelta(int n) {
        if (ringFilled == 0) return Double.NaN;
        int count = Math.min(n, ringFilled);
        double sum = 0.0;
        for (int i = 0; i < count; i++) {
            // Walk backwards from the most-recent entry
            int idx = ((ringHead - 1 - i) % 20 + 20) % 20;
            sum += relDeltaRing[idx];
        }
        return sum / count;
    }
}
