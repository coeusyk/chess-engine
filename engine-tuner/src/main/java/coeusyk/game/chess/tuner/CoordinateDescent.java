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
        return tune(positions, initialParams, k, maxIters, recalibrateK, null);
    }

    /**
     * Same as above and, when {@code metrics} is not null, records the pass count, the MSE trail, whether the
     * run stopped on the pass cap, and the final and minimum MSE for the post-run convergence gate (#274).
     */
    public static double[] tune(List<LabelledPosition> positions,
                                double[] initialParams,
                                double k,
                                int maxIters,
                                boolean recalibrateK,
                                TunerRunMetrics metrics) {
        return tune(positions, positions, initialParams, k, maxIters, recalibrateK, null, metrics);
    }

    /**
     * Full form. {@code positions} is the training partition that the MSE is minimised on, and
     * {@code kPositions} is the held-out partition K is recalibrated on, so the two never mix. A parameter
     * is active only when it is not frozen and {@code groupMask} (may be null) selects it; inactive
     * parameters keep exactly their input value, including through initial clamping and material ordering.
     */
    public static double[] tune(List<LabelledPosition> positions,
                                List<LabelledPosition> kPositions,
                                double[] initialParams,
                                double k,
                                int maxIters,
                                boolean recalibrateK,
                                boolean[] groupMask,
                                TunerRunMetrics metrics) {
        if (maxIters < 1) {
            throw new IllegalArgumentException("maxIters must be at least 1, got " + maxIters);
        }
        double[] params = initialParams.clone();
        // Clamp active params to legal bounds before the first MSE computation; inactive ones are left as given
        for (int i = 0; i < params.length; i++) {
            if (EvalParams.isActive(i, groupMask)) params[i] = EvalParams.clampOne(i, params[i]);
        }
        final double[] owned = params.clone();
        double currentMse = TunerEvaluator.computeMse(positions, params, k);
        if (metrics != null) metrics.minMse = Math.min(metrics.minMse, currentMse);
        boolean converged = false;

        LOG.info(String.format("[Tuner] start  MSE=%.8f  params=%d  positions=%d",
                currentMse, params.length, positions.size()));

        for (int iter = 1; iter <= maxIters; iter++) {
            Instant iterStart = Instant.now();
            double mseAtPassStart = currentMse;
            boolean improved = false;

            for (int i = 0; i < params.length; i++) {
                if (!EvalParams.isActive(i, groupMask)) continue;
                double lo = EvalParams.PARAM_MIN[i];
                double hi = EvalParams.PARAM_MAX[i];
                // enforceMaterialOrdering can move other material values during a trial, so a rejected
                // trial restores the whole array and not just parameter i (#274).
                double[] saved = params.clone();

                // Try +1 (only if within upper bound)
                if (params[i] < hi) {
                    params[i] += 1.0;
                    EvalParams.enforceMaterialOrdering(params);
                    EvalParams.restoreInactive(params, owned, groupMask);
                    double msePlus = TunerEvaluator.computeMse(positions, params, k);
                    if (msePlus < currentMse) {
                        currentMse = msePlus;
                        improved = true;
                        continue;
                    }
                    System.arraycopy(saved, 0, params, 0, params.length);
                }

                // Try -1 (only if within lower bound)
                if (params[i] > lo) {
                    params[i] -= 1.0;
                    EvalParams.enforceMaterialOrdering(params);
                    EvalParams.restoreInactive(params, owned, groupMask);
                    double mseMinus = TunerEvaluator.computeMse(positions, params, k);
                    if (mseMinus < currentMse) {
                        currentMse = mseMinus;
                        improved = true;
                        continue;
                    }
                    System.arraycopy(saved, 0, params, 0, params.length);
                }
            }

            // K recalibration after each pass
            if (recalibrateK) {
                double newK = KFinder.findK(kPositions, params);
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

            if (metrics != null) metrics.recordIteration(iter, mseAtPassStart, currentMse);

            if (!improved) {
                LOG.info(String.format("[Tuner] converged after %d iterations", iter));
                converged = true;
                break;
            }
        }

        if (metrics != null) metrics.recordTermination(converged);
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
