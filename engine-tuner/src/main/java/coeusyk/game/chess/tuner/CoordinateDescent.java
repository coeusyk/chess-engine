package coeusyk.game.chess.tuner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Local coordinate descent optimiser for Texel tuning.
 *
 * <p>For each parameter in turn, the algorithm tries adding +1 and −1
 * (integer steps). If either reduces the MSE it is accepted and the loop
 * restarts from parameter 0; otherwise the parameter is left unchanged.
 * The outer loop iterates until a full pass over all parameters produces
 * no improvement, or the iteration cap is reached.
 *
 * <p>Integer steps are intentional: all eval constants are centipawn integers
 * and the live evaluator uses {@code (int)} casts. Non-integer steps produce
 * values that cannot be copied back to source without rounding anyway.
 *
 * <p>Parallelism: MSE computation delegates to
 * {@link TunerEvaluator#computeMse}, which uses {@code parallelStream}
 * internally. The descent loop itself is single-threaded to avoid parameter
 * update races.
 */
public final class CoordinateDescent {

    private static final Logger LOG = LoggerFactory.getLogger(CoordinateDescent.class);

    /** Default maximum number of full-pass iterations. */
    public static final int DEFAULT_MAX_ITERATIONS = 500;

    private CoordinateDescent() {}

    /**
     * Runs coordinate descent and returns the optimised parameter array.
     *
     * <p>The input {@code params} array is not modified; a copy is returned.
     *
     * @param positions    pre-loaded training set
     * @param initialParams starting point (length {@link EvalParams#TOTAL_PARAMS})
     * @param k            sigmoid scaling constant (from {@link KFinder})
     * @param maxIters     iteration cap
     * @return tuned parameter array (same length as {@code initialParams})
     */
    public static double[] tune(List<LabelledPosition> positions,
                                double[] initialParams,
                                double k,
                                int maxIters) {
        return tune(positions, initialParams, k, maxIters, true);
    }

    /**
     * Runs coordinate descent and returns the optimised parameter array.
     *
     * <p>The input {@code params} array is not modified; a copy is returned.
     *
     * @param positions     pre-loaded training set
     * @param initialParams starting point (length {@link EvalParams#TOTAL_PARAMS})
     * @param k             sigmoid scaling constant (from {@link KFinder})
     * @param maxIters      iteration cap
     * @param recalibrateK  if {@code true}, re-runs {@link KFinder} after each pass
     *                      and logs K drift; skip if drift &lt; 0.001
     * @return tuned parameter array (same length as {@code initialParams})
     */
    public static double[] tune(List<LabelledPosition> positions,
                                double[] initialParams,
                                double k,
                                int maxIters,
                                boolean recalibrateK) {
        double[] params = initialParams.clone();
        // Clamp initial params to legal bounds before the first MSE computation
        for (int i = 0; i < params.length; i++) {
            params[i] = EvalParams.clampOne(i, params[i]);
        }
        double currentMse = TunerEvaluator.computeMse(positions, params, k);

        LOG.info(String.format("[Tuner] start  MSE=%.8f  params=%d  positions=%d",
                currentMse, params.length, positions.size()));

        for (int iter = 1; iter <= maxIters; iter++) {
            Instant iterStart = Instant.now();
            boolean improved = false;

            for (int i = 0; i < params.length; i++) {
                double lo = EvalParams.PARAM_MIN[i];
                double hi = EvalParams.PARAM_MAX[i];

                // Try +1 (only if within upper bound)
                if (params[i] < hi) {
                    params[i] += 1.0;
                    EvalParams.enforceMaterialOrdering(params);
                    double msePlus = TunerEvaluator.computeMse(positions, params, k);
                    if (msePlus < currentMse) {
                        currentMse = msePlus;
                        improved = true;
                        continue;
                    }
                    params[i] -= 1.0;
                    EvalParams.enforceMaterialOrdering(params);
                }

                // Try -1 (only if within lower bound)
                if (params[i] > lo) {
                    params[i] -= 1.0;
                    EvalParams.enforceMaterialOrdering(params);
                    double mseMinus = TunerEvaluator.computeMse(positions, params, k);
                    if (mseMinus < currentMse) {
                        currentMse = mseMinus;
                        improved = true;
                        continue;
                    }
                    params[i] += 1.0;
                    EvalParams.enforceMaterialOrdering(params);
                }
            }

            // K recalibration after each pass
            if (recalibrateK) {
                double newK = KFinder.findK(positions, params);
                double kDrift = Math.abs(newK - k);
                if (kDrift < 0.001) {
                    LOG.info(String.format("[Tuner] K stable (drift=%.6f < 0.001), skipping recalibration", kDrift));
                } else {
                    LOG.info(String.format("[Tuner] K recalibrated: %.6f \u2192 %.6f (drift=%.6f)", k, newK, kDrift));
                    k = newK;
                    currentMse = TunerEvaluator.computeMse(positions, params, k);
                }
            }

            long ms = Duration.between(iterStart, Instant.now()).toMillis();
            LOG.info(String.format("[Tuner] iter %3d  K=%.6f  MSE=%.8f  improved=%b  time=%dms",
                    iter, k, currentMse, improved, ms));

            if (!improved) {
                LOG.info(String.format("[Tuner] converged after %d iterations", iter));
                break;
            }
        }

        return params;
    }

    /**
     * Convenience overload that uses {@link #DEFAULT_MAX_ITERATIONS}.
     */
    public static double[] tune(List<LabelledPosition> positions,
                                double[] initialParams,
                                double k) {
        return tune(positions, initialParams, k, DEFAULT_MAX_ITERATIONS);
    }
}
