package coeusyk.game.chess.tuner;

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
        double[] params = initialParams.clone();
        // Clamp initial params to legal bounds before the first MSE computation
        for (int i = 0; i < params.length; i++) {
            params[i] = EvalParams.clampOne(i, params[i]);
        }
        double currentMse = TunerEvaluator.computeMse(positions, params, k);

        System.out.printf("[Tuner] start  MSE=%.8f  params=%d  positions=%d%n",
                currentMse, params.length, positions.size());

        for (int iter = 1; iter <= maxIters; iter++) {
            Instant iterStart = Instant.now();
            boolean improved = false;

            for (int i = 0; i < params.length; i++) {
                double lo = EvalParams.PARAM_MIN[i];
                double hi = EvalParams.PARAM_MAX[i];

                // Try +1 (only if within upper bound)
                if (params[i] < hi) {
                    params[i] += 1.0;
                    double msePlus = TunerEvaluator.computeMse(positions, params, k);
                    if (msePlus < currentMse) {
                        currentMse = msePlus;
                        improved = true;
                        continue;
                    }
                    params[i] -= 1.0;
                }

                // Try -1 (only if within lower bound)
                if (params[i] > lo) {
                    params[i] -= 1.0;
                    double mseMinus = TunerEvaluator.computeMse(positions, params, k);
                    if (mseMinus < currentMse) {
                        currentMse = mseMinus;
                        improved = true;
                        continue;
                    }
                    params[i] += 1.0;
                }
            }

            long ms = Duration.between(iterStart, Instant.now()).toMillis();
            System.out.printf("[Tuner] iter %3d  MSE=%.8f  improved=%b  time=%dms%n",
                    iter, currentMse, improved, ms);

            if (!improved) {
                System.out.printf("[Tuner] converged after %d iterations%n", iter);
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
