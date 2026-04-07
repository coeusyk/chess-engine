package coeusyk.game.chess.tuner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Adam gradient descent optimiser for Texel tuning.
 *
 * <p>Uses analytical gradient of the MSE objective with finite-difference
 * approximation for dEval/dParam[i]. The Adam update rule handles sparse
 * gradients well (important for PST entries where each parameter is only
 * active for specific piece/square combinations).
 *
 * <p>After each update step, float accumulators are discretized via
 * {@link Math#round(double)} — PST values remain integers throughout.
 * Parameters are clamped to {@link EvalParams#PARAM_MIN}/{@link EvalParams#PARAM_MAX}
 * bounds and material ordering is enforced via {@link EvalParams#enforceMaterialOrdering}.
 *
 * <p>Gradient computation uses thread-local params clones and {@code evaluateStatic}
 * (not qsearch) for finite difference. Per-position contributions are reduced
 * via a thread-safe collector to avoid mutable-identity bugs in {@code reduce()}.
 */
public final class GradientDescent {

    private static final Logger LOG = LoggerFactory.getLogger(GradientDescent.class);

    /** Default maximum number of iterations (passes over all parameters). */
    public static final int DEFAULT_MAX_ITERATIONS = 500;

    // Adam hyperparameters
    private static final double LR      = 1.0;
    private static final double BETA1   = 0.9;
    private static final double BETA2   = 0.999;
    private static final double EPSILON = 1e-8;

    /** Early-stop if relative MSE improvement falls below this threshold. */
    private static final double CONVERGENCE_THRESHOLD = 1e-9;

    // Logarithmic barrier hyperparameters (Issue #134)
    // Barrier pushes scalar params away from PARAM_MIN, replacing the hard lower-bound clamp.
    // gamma is annealed per-iteration: gamma_t = BARRIER_GAMMA_INIT * BARRIER_ANNEAL_RATE^(t-1)
    private static final double BARRIER_GAMMA_INIT  = 0.001;
    private static final double BARRIER_ANNEAL_RATE = 0.99;

    private GradientDescent() {}

    /**
     * Runs Adam gradient descent and returns the optimised parameter array.
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
     * Runs Adam gradient descent and returns the optimised parameter array.
     *
     * <p>The input {@code params} array is not modified; a copy is returned.
     *
     * @param positions     pre-loaded training set
     * @param initialParams starting point (length {@link EvalParams#TOTAL_PARAMS})
     * @param k             sigmoid scaling constant (from {@link KFinder})
     * @param maxIters      iteration cap
     * @param recalibrateK  if {@code true}, re-runs {@link KFinder} after each iteration
     *                      and updates K; skipped when drift &lt; 0.001
     * @return tuned parameter array (same length as {@code initialParams})
     */
    public static double[] tune(List<LabelledPosition> positions,
                                double[] initialParams,
                                double k,
                                int maxIters,
                                boolean recalibrateK) {
        int n = initialParams.length;
        double[] params = initialParams.clone();

        // Clamp initial params to legal bounds
        for (int i = 0; i < n; i++) {
            params[i] = EvalParams.clampOne(i, params[i]);
        }

        // Float accumulators for sub-integer gradient accumulation
        double[] accum = params.clone();

        // Adam moment estimates
        double[] m = new double[n]; // first moment (mean of gradients)
        double[] v = new double[n]; // second moment (mean of squared gradients)

        double currentMse = TunerEvaluator.computeMse(positions, params, k);

        LOG.info(String.format("[Adam] start  MSE=%.8f  params=%d  positions=%d  threads=%d",
                currentMse, n, positions.size(), Runtime.getRuntime().availableProcessors()));

        for (int iter = 1; iter <= maxIters; iter++) {
            Instant iterStart = Instant.now();

            // Compute gradient via finite difference
            double[] grad = computeGradient(positions, params, k);

            // Apply logarithmic barrier gradient to scalar params to push away from PARAM_MIN.
            // Replaces the hard lower-bound clamp for non-PST parameters.
            double gamma = BARRIER_GAMMA_INIT * Math.pow(BARRIER_ANNEAL_RATE, iter - 1);
            for (int i = 0; i < n; i++) {
                if (!EvalParams.isScalarParam(i)) continue;
                if (EvalParams.PARAM_MIN[i] == EvalParams.PARAM_MAX[i]) continue;
                double distance = params[i] - EvalParams.PARAM_MIN[i];
                if (distance < 1e-4) distance = 1e-4;
                grad[i] -= gamma / distance;
            }

            // Adam update
            for (int i = 0; i < n; i++) {
                // Skip frozen parameters (min == max)
                if (EvalParams.PARAM_MIN[i] == EvalParams.PARAM_MAX[i]) continue;

                m[i] = BETA1 * m[i] + (1 - BETA1) * grad[i];
                v[i] = BETA2 * v[i] + (1 - BETA2) * grad[i] * grad[i];

                // Bias-corrected estimates
                double mHat = m[i] / (1 - Math.pow(BETA1, iter));
                double vHat = v[i] / (1 - Math.pow(BETA2, iter));

                // Update float accumulator
                accum[i] -= LR * mHat / (Math.sqrt(vHat) + EPSILON);

                // Discretize and clamp. For scalar params the barrier enforces the lower bound,
                // so only the upper bound is hard-clamped; PSTs use the full two-sided clamp.
                if (EvalParams.isScalarParam(i)) {
                    params[i] = Math.min(EvalParams.PARAM_MAX[i], Math.round(accum[i]));
                } else {
                    params[i] = EvalParams.clampOne(i, Math.round(accum[i]));
                }
            }

            // Enforce material ordering after full update
            EvalParams.enforceMaterialOrdering(params);

            // Sync accumulators with discretized params (avoid drift)
            for (int i = 0; i < n; i++) {
                if (EvalParams.PARAM_MIN[i] == EvalParams.PARAM_MAX[i]) {
                    accum[i] = params[i];
                }
            }

            // K recalibration after each pass
            if (recalibrateK) {
                double newK = KFinder.findK(positions, params);
                double kDrift = Math.abs(newK - k);
                if (kDrift < 0.001) {
                    LOG.info(String.format("[Adam] K stable (drift=%.6f < 0.001), skipping recalibration", kDrift));
                } else {
                    LOG.info(String.format("[Adam] K recalibrated: %.6f \u2192 %.6f (drift=%.6f)", k, newK, kDrift));
                    k = newK;
                }
            }

            double newMse = TunerEvaluator.computeMse(positions, params, k);
            long ms = Duration.between(iterStart, Instant.now()).toMillis();

            LOG.info(String.format("[Adam] iter %3d  K=%.6f  MSE=%.8f  time=%dms",
                    iter, k, newMse, ms));

            // Convergence check
            if (currentMse > 0 && Math.abs(currentMse - newMse) / currentMse < CONVERGENCE_THRESHOLD) {
                LOG.info(String.format("[Adam] converged after %d iterations (MSE delta < %.1e)",
                        iter, CONVERGENCE_THRESHOLD));
                currentMse = newMse;
                break;
            }
            currentMse = newMse;
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

    // =========================================================================
    // Fast path: analytical gradient using precomputed feature vectors
    // =========================================================================

    /**
     * Runs Adam gradient descent using precomputed {@link PositionFeatures}.
     *
     * <p>Each gradient computation is O(N × avgActiveFeatures) instead of
     * O(N × P × 2 × evalCost) — typically 100–1000× faster than the finite-
     * difference path.
     *
     * @param features     precomputed list (build via {@link PositionFeatures#buildList})
     * @param initialParams starting point (length {@link EvalParams#TOTAL_PARAMS})
     * @param k            sigmoid scaling constant (from {@link KFinder})
     * @param maxIters     iteration cap
     * @param recalibrateK if {@code true}, re-runs {@link KFinder} after each iteration
     * @return tuned parameter array
     */
    public static double[] tuneWithFeatures(List<PositionFeatures> features,
                                            double[] initialParams,
                                            double k,
                                            int maxIters,
                                            boolean recalibrateK) {
        int n = initialParams.length;
        double[] params = initialParams.clone();

        for (int i = 0; i < n; i++) {
            params[i] = EvalParams.clampOne(i, params[i]);
        }

        double[] accum = params.clone();
        double[] m = new double[n];
        double[] v = new double[n];

        double currentMse = TunerEvaluator.computeMseFromFeatures(features, params, k);

        LOG.info(String.format("[Adam/fast] start  MSE=%.8f  params=%d  positions=%d  threads=%d",
                currentMse, n, features.size(), Runtime.getRuntime().availableProcessors()));

        for (int iter = 1; iter <= maxIters; iter++) {
            Instant iterStart = Instant.now();

            double[] grad = computeGradientFromFeatures(features, params, k);

            // Apply logarithmic barrier gradient to scalar params to push away from PARAM_MIN.
            // Replaces the hard lower-bound clamp for non-PST parameters.
            double gamma = BARRIER_GAMMA_INIT * Math.pow(BARRIER_ANNEAL_RATE, iter - 1);
            for (int i = 0; i < n; i++) {
                if (!EvalParams.isScalarParam(i)) continue;
                if (EvalParams.PARAM_MIN[i] == EvalParams.PARAM_MAX[i]) continue;
                double distance = params[i] - EvalParams.PARAM_MIN[i];
                if (distance < 1e-4) distance = 1e-4;
                grad[i] -= gamma / distance;
            }

            for (int i = 0; i < n; i++) {
                if (EvalParams.PARAM_MIN[i] == EvalParams.PARAM_MAX[i]) continue;

                m[i] = BETA1 * m[i] + (1 - BETA1) * grad[i];
                v[i] = BETA2 * v[i] + (1 - BETA2) * grad[i] * grad[i];

                double mHat = m[i] / (1 - Math.pow(BETA1, iter));
                double vHat = v[i] / (1 - Math.pow(BETA2, iter));

                accum[i] -= LR * mHat / (Math.sqrt(vHat) + EPSILON);
                // Barrier enforces lower bound for scalar params; only hard-clamp upper bound.
                if (EvalParams.isScalarParam(i)) {
                    params[i] = Math.min(EvalParams.PARAM_MAX[i], Math.round(accum[i]));
                } else {
                    params[i] = EvalParams.clampOne(i, Math.round(accum[i]));
                }
            }

            EvalParams.enforceMaterialOrdering(params);

            for (int i = 0; i < n; i++) {
                if (EvalParams.PARAM_MIN[i] == EvalParams.PARAM_MAX[i]) {
                    accum[i] = params[i];
                }
            }

            if (recalibrateK) {
                double newK    = KFinder.findKFromFeatures(features, params);
                double kDrift  = Math.abs(newK - k);
                if (kDrift < 0.001) {
                    LOG.info(String.format("[Adam/fast] K stable (drift=%.6f < 0.001), skipping recalibration", kDrift));
                } else {
                    LOG.info(String.format("[Adam/fast] K recalibrated: %.6f \u2192 %.6f (drift=%.6f)", k, newK, kDrift));
                    k = newK;
                }
            }

            double newMse = TunerEvaluator.computeMseFromFeatures(features, params, k);
            long ms = Duration.between(iterStart, Instant.now()).toMillis();

            LOG.info(String.format("[Adam/fast] iter %3d  K=%.6f  MSE=%.8f  time=%dms",
                    iter, k, newMse, ms));

            if (currentMse > 0 && Math.abs(currentMse - newMse) / currentMse < CONVERGENCE_THRESHOLD) {
                LOG.info(String.format("[Adam/fast] converged after %d iterations (MSE delta < %.1e)",
                        iter, CONVERGENCE_THRESHOLD));
                currentMse = newMse;
                break;
            }
            currentMse = newMse;
        }

        return params;
    }

    /**
     * Convenience overload for feature-based tuning with
     * {@link #DEFAULT_MAX_ITERATIONS}.
     */
    public static double[] tuneWithFeatures(List<PositionFeatures> features,
                                            double[] initialParams,
                                            double k) {
        return tuneWithFeatures(features, initialParams, k, DEFAULT_MAX_ITERATIONS, true);
    }

    /**
     * Computes the analytical gradient of the MSE objective using precomputed
     * feature vectors.
     *
     * <p>For each position p with sigmoid prediction s and outcome y:
     * <pre>
     *   dMSE/dParams[i] = (2/N) × Σ (s−y) × s×(1−s) × K×ln10/400 × dEval/dParams[i]
     * </pre>
     * For linear params, {@code dEval/dParams[i] = features.weights[i]}.
     * For the non-linear ATK params, the derivative involves the current-
     * params-dependent king-zone attacker weights and is computed per-position
     * via {@link PositionFeatures#accumulateGradient}.
     *
     * <p>No bitboard operations are performed here — all positional structure is
     * encoded in the precomputed feature vectors.
     */
    static double[] computeGradientFromFeatures(List<PositionFeatures> features,
                                    double[] params,
                                    double k) {
        int n = params.length;
        double kFactor = k * Math.log(10.0) / 400.0;

        double[] grad = features.parallelStream()
                .map(pf -> {
                    double[] localGrad = new double[n];
                    double eval   = pf.eval(params);
                    double sig    = TunerEvaluator.sigmoid(eval, k);
                    double factor = (sig - pf.outcome) * sig * (1.0 - sig) * kFactor;
                    pf.accumulateGradient(localGrad, params, factor);
                    return localGrad;
                })
                .collect(
                    () -> new double[n],
                    (acc, lg) -> { for (int i = 0; i < n; i++) acc[i] += lg[i]; },
                    (a,   b)  -> { for (int i = 0; i < n; i++) a[i]  +=  b[i]; }
                );

        double scale = 2.0 / features.size();
        for (int i = 0; i < n; i++) grad[i] *= scale;
        return grad;
    }

    /**
     * Computes the diagonal of the empirical Fisher information matrix.
     *
     * <p>For each parameter i: {@code fisherDiag[i] = mean_p( (∂L/∂p_i)^2 )}.
     * A low value indicates that the parameter has little gradient signal across the
     * corpus — i.e. the corpus is "starved" of positions that exercise feature i.
     *
     * @param features precomputed position features
     * @param params   current parameter array
     * @param k        sigmoid scaling constant
     * @return array of length {@code params.length}: diagonal Fisher estimates
     */
    public static double[] computeFisherDiagonal(List<PositionFeatures> features,
                                                 double[] params,
                                                 double k) {
        int n = params.length;
        double kFactor = k * Math.log(10.0) / 400.0;

        double[] fisherDiag = features.parallelStream()
                .map(pf -> {
                    double[] localFisher = new double[n];
                    double eval   = pf.eval(params);
                    double sig    = TunerEvaluator.sigmoid(eval, k);
                    double factor = (sig - pf.outcome) * sig * (1.0 - sig) * kFactor;
                    double[] localGrad = new double[n];
                    pf.accumulateGradient(localGrad, params, factor);
                    for (int i = 0; i < n; i++) {
                        localFisher[i] = localGrad[i] * localGrad[i];
                    }
                    return localFisher;
                })
                .collect(
                    () -> new double[n],
                    (acc, lf) -> { for (int i = 0; i < n; i++) acc[i] += lf[i]; },
                    (a,   b)  -> { for (int i = 0; i < n; i++) a[i]  +=  b[i]; }
                );

        double scale = 1.0 / features.size();
        for (int i = 0; i < n; i++) fisherDiag[i] *= scale;
        return fisherDiag;
    }

    /**
     * Computes the analytical gradient of the MSE objective using finite difference
     * for dEval/dParam[i].
     *
     * <p>Finite difference is computed via {@code evaluateStatic} (not qsearch),
     * which is standard Texel tuning practice — qsearch only used for MSE with
     * final params to handle noisy positions.
     *
     * <p>For each position p with outcome y, predicted sigmoid s:
     * <pre>
     *   dMSE/d(params[i]) = (2/N) * Σ (s - y) * s * (1 - s) * (K * ln(10) / 400) * dEval/d(params[i])
     * </pre>
     *
     * <p>Thread-local params clone: to avoid 815× allocations per parameter per position,
     * each thread uses a single clone modified in-place: save → increment → eval → restore.
     * Thread-safe because gradient computation is read-only on params and the clone
     * is thread-local.
     *
     * <p>Per-position gradients are accumulated via a thread-safe collector instead of
     * a mutable identity in {@code reduce()}, avoiding data races.
     */
    static double[] computeGradient(List<LabelledPosition> positions,
                                    double[] params,
                                    double k) {
        int n = params.length;
        double kFactor = k * Math.log(10.0) / 400.0;

        // ThreadLocal for per-thread params clone (saves ~815 allocations per iteration)
        ThreadLocal<double[]> paramClone = ThreadLocal.withInitial(() -> params.clone());

        // Parallel stream: compute per-position gradient contributions
        double[] grad = positions.parallelStream()
                .map(lp -> {
                    double[] localGrad = new double[n];
                    PositionData pos = lp.pos();

                    // Static eval (no qsearch) for gradient finite difference
                    int eval = TunerEvaluator.evaluateStatic(pos, params);
                    double sig = TunerEvaluator.sigmoid(eval, k);
                    double errTerm = (sig - lp.outcome()) * sig * (1.0 - sig) * kFactor;

                    // Compute dEval/dParam[i] by finite difference
                    double[] pClone = paramClone.get();
                    for (int i = 0; i < n; i++) {
                        // Skip frozen parameters
                        if (EvalParams.PARAM_MIN[i] == EvalParams.PARAM_MAX[i]) continue;

                        // Save, modify +1, eval, restore to avoid 2 allocations
                        double saved = pClone[i];
                        pClone[i] = saved + 1.0;
                        int evalPlus = TunerEvaluator.evaluateStatic(pos, pClone);
                        pClone[i] = saved - 1.0;
                        int evalMinus = TunerEvaluator.evaluateStatic(pos, pClone);
                        pClone[i] = saved;  // restore to original

                        double dEval = (evalPlus - evalMinus) / 2.0;
                        localGrad[i] = errTerm * dEval;
                    }
                    return localGrad;
                })
                .collect(
                    () -> new double[n],
                    (gradAcc, localGrad) -> {
                        for (int i = 0; i < n; i++) gradAcc[i] += localGrad[i];
                    },
                    (grad1, grad2) -> {
                        for (int i = 0; i < n; i++) grad1[i] += grad2[i];
                    }
                );

        // Average over positions
        double scale = 2.0 / positions.size();
        for (int i = 0; i < n; i++) {
            grad[i] *= scale;
        }

        return grad;
    }
}
