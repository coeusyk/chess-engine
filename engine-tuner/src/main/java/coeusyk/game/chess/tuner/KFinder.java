package coeusyk.game.chess.tuner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Finds the optimal sigmoid scaling constant K for the Texel tuning objective.
 *
 * <p>K controls how steeply the win-probability sigmoid rises per centipawn.
 * A K that is too low treats every position as a draw; too high makes the
 * sigmoid behave like a step function. Ternary search over K ∈ [kMin, kMax]
 * converges in O(log₃((kMax − kMin) / tolerance)) iterations (~35–40 steps for
 * the defaults below).
 *
 * <p>Search is performed with the supplied params array held fixed (typically
 * the initial defaults). Re-running KFinder after partial tuning is unnecessary:
 * K is only sensitive to the overall eval scale, which does not change
 * dramatically during coordinate descent on a well-balanced eval.
 */
public final class KFinder {

    private static final Logger LOG = LoggerFactory.getLogger(KFinder.class);

    /** Lower bound of the K search range. */
    public static final double K_MIN = 0.5;

    /** Upper bound of the K search range. */
    public static final double K_MAX = 3.0;

    /** Stop when the interval width is smaller than this. */
    public static final double TOLERANCE = 1e-4;

    private KFinder() {}

    /**
     * Finds the K that minimises MSE, using ternary search.
     *
     * @param positions pre-loaded training set
     * @param params    evaluation parameter array (held fixed during K search)
     * @return optimal K ∈ [K_MIN, K_MAX]
     */
    public static double findK(List<LabelledPosition> positions, double[] params) {
        double lo = K_MIN;
        double hi = K_MAX;

        while (hi - lo > TOLERANCE) {
            double m1 = lo + (hi - lo) / 3.0;
            double m2 = hi - (hi - lo) / 3.0;

            double mse1 = TunerEvaluator.computeMse(positions, params, m1);
            double mse2 = TunerEvaluator.computeMse(positions, params, m2);

            if (mse1 < mse2) {
                hi = m2;
            } else {
                lo = m1;
            }
        }

        double best = (lo + hi) / 2.0;
        LOG.info(String.format("[KFinder] K = %.6f  (MSE = %.8f)",
                best, TunerEvaluator.computeMse(positions, params, best)));
        return best;
    }
}
