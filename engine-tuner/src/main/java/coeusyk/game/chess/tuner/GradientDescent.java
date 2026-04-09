package coeusyk.game.chess.tuner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

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
     * @param groupMask    optional mask; if non-null only {@code true} indices are updated
     *                     (null = update all params, fully backward compatible)
     * @return tuned parameter array
     */
    public static double[] tuneWithFeatures(List<PositionFeatures> features,
                                            double[] initialParams,
                                            double k,
                                            int maxIters,
                                            boolean recalibrateK,
                                            boolean[] groupMask) {
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
                if (groupMask != null && !groupMask[i]) continue;  // skip params outside the active group

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
     * Convenience overload for feature-based tuning with {@link #DEFAULT_MAX_ITERATIONS}
     * and no group mask (all params updated).
     */
    public static double[] tuneWithFeatures(List<PositionFeatures> features,
                                            double[] initialParams,
                                            double k) {
        return tuneWithFeatures(features, initialParams, k, DEFAULT_MAX_ITERATIONS, true, null);
    }

    /**
     * Convenience overload without a group mask (all params updated).
     */
    public static double[] tuneWithFeatures(List<PositionFeatures> features,
                                            double[] initialParams,
                                            double k,
                                            int maxIters,
                                            boolean recalibrateK) {
        return tuneWithFeatures(features, initialParams, k, maxIters, recalibrateK, null);
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

    // =========================================================================
    // L-BFGS optimizer (Issue #137)
    // =========================================================================

    /** L-BFGS history length (number of (s, y) curvature pairs to retain). */
    private static final int LBFGS_M = 10;

    /** Gradient norm convergence threshold for L-BFGS: stop when ||∇L||₂ < this. */
    private static final double LBFGS_GRAD_NORM_THRESHOLD = 1e-5;

    /**
     * Runs L-BFGS gradient descent using precomputed {@link PositionFeatures}.
     *
     * <p>Uses the standard two-loop recursion to compute the search direction
     * {@code p = H^{-1} · ∇L}. Stores the last {@value #LBFGS_M} parameter-gradient
     * difference pairs {@code (s_k, y_k)} in a circular buffer.
     * The logarithmic barrier gradient (same as the Adam path in Issue #134) is
     * included in all gradient computations so that curvature pairs reflect the
     * full augmented objective.
     *
     * <p>Primary convergence criterion: {@code ||∇L||₂ < 1e-5}. The {@code maxIters}
     * parameter acts as an emergency cap.
     *
     * @param features     precomputed position features
     * @param initialParams starting point (length {@link EvalParams#TOTAL_PARAMS})
     * @param k            sigmoid scaling constant (from {@link KFinder})
     * @param maxIters     emergency iteration cap
     * @param recalibrateK if {@code true}, re-runs {@link KFinder} after each iteration
     * @return tuned parameter array
     */
    public static double[] tuneWithFeaturesLBFGS(List<PositionFeatures> features,
                                                 double[] initialParams,
                                                 double k,
                                                 int maxIters,
                                                 boolean recalibrateK,
                                                 boolean[] groupMask) {
        final int n = initialParams.length;
        final int m = LBFGS_M;

        double[] params = initialParams.clone();
        for (int i = 0; i < n; i++) {
            params[i] = EvalParams.clampOne(i, params[i]);
        }
        // Float accumulator — same purpose as in the Adam path: tracks sub-integer moves
        double[] accum = params.clone();

        // Circular buffer for L-BFGS curvature pairs
        double[][] sList  = new double[m][];  // s_k = accum_k − accum_{k-1}
        double[][] yList  = new double[m][];  // y_k = ∇L_k − ∇L_{k-1} (with barrier)
        double[]   rhoArr = new double[m];    // ρ_k = 1 / (y_k · s_k)
        int histLen  = 0;  // number of valid pairs stored
        int histHead = 0;  // next write position in the circular buffer

        double currentMse = TunerEvaluator.computeMseFromFeatures(features, params, k);
        LOG.info(String.format("[L-BFGS] start  MSE=%.8f  n=%d  positions=%d  m=%d",
                currentMse, n, features.size(), m));

        // Compute initial gradient (barrier included) — reused in first iteration
        double[] prevGrad = computeBarrierGradient(features, params, k, 1);

        for (int iter = 1; iter <= maxIters; iter++) {
            Instant iterStart = Instant.now();

            double[] grad = (iter == 1) ? prevGrad
                    : computeBarrierGradient(features, params, k, iter);

            // --- Gradient norm convergence check (Issue #137) ---
            double gradNormSq = 0.0;
            for (double g : grad) gradNormSq += g * g;
            double gradNorm = Math.sqrt(gradNormSq);
            if (gradNorm < LBFGS_GRAD_NORM_THRESHOLD) {
                System.out.printf("[L-BFGS] Converged at iteration %d — gradient norm: %.2e%n",
                        iter, gradNorm);
                LOG.info(String.format("[L-BFGS] converged at iteration %d — gradient norm: %.2e",
                        iter, gradNorm));
                break;
            }

            // --- Two-loop recursion: compute search direction q = H^{-1} · grad ---
            double[] q = grad.clone();
            int k2 = Math.min(histLen, m);  // number of valid history entries
            double[] alpha = new double[m];

            // First loop (backward through history)
            for (int i = k2 - 1; i >= 0; i--) {
                int idx = ((histHead - 1 - i) % m + m) % m;
                alpha[i] = rhoArr[idx] * dot(sList[idx], q);
                axpy(-alpha[i], yList[idx], q);
            }

            // Scale by H_0 = (y^T s / y^T y) · I (Oren-Luenberger initial scaling)
            if (k2 > 0) {
                int last = ((histHead - 1) % m + m) % m;
                double ys  = 1.0 / rhoArr[last];         // y·s = 1/ρ
                double yy  = dot(yList[last], yList[last]);
                if (yy > 1e-20) {
                    double h0 = ys / yy;
                    for (int j = 0; j < n; j++) q[j] *= h0;
                }
            }

            // Second loop (forward through history)
            for (int i = 0; i < k2; i++) {
                int idx = ((histHead - k2 + i) % m + m) % m;
                double beta = rhoArr[idx] * dot(yList[idx], q);
                axpy(alpha[i] - beta, sList[idx], q);
            }

            // Verify descent direction: q · grad must be positive. If not, reset history
            // and fall back to steepest descent for this step.
            if (dot(q, grad) <= 0.0) {
                LOG.warn(String.format("[L-BFGS] iter %d: non-descent direction detected, resetting history", iter));
                histLen  = 0;
                histHead = 0;
                q = grad.clone();
            }

            // --- Update float accumulator ---
            double[] newAccum = new double[n];
            for (int i = 0; i < n; i++) {
                if (EvalParams.PARAM_MIN[i] == EvalParams.PARAM_MAX[i]
                        || (groupMask != null && !groupMask[i])) {
                    newAccum[i] = accum[i];  // frozen or outside active group
                } else {
                    newAccum[i] = accum[i] - q[i];  // step size α = 1.0 (standard L-BFGS trial step)
                }
            }

            // --- Discretize and clamp (same logic as Adam path) ---
            double[] newParams = new double[n];
            for (int i = 0; i < n; i++) {
                if (EvalParams.PARAM_MIN[i] == EvalParams.PARAM_MAX[i]) {
                    newParams[i] = params[i];
                    continue;
                }
                if (EvalParams.isScalarParam(i)) {
                    newParams[i] = Math.min(EvalParams.PARAM_MAX[i], Math.round(newAccum[i]));
                } else {
                    newParams[i] = EvalParams.clampOne(i, Math.round(newAccum[i]));
                }
            }
            EvalParams.enforceMaterialOrdering(newParams);

            // --- Build curvature pair (s, y) in float-accumulator space ---
            double[] newGrad = computeBarrierGradient(features, newParams, k, iter);
            double[] sNew = new double[n];
            double[] yNew = new double[n];
            for (int i = 0; i < n; i++) {
                sNew[i] = newAccum[i] - accum[i];
                yNew[i] = newGrad[i]  - grad[i];
            }
            double ys = dot(yNew, sNew);
            if (ys > 1e-20) {  // only store positive-definite pairs
                sList[histHead]  = sNew;
                yList[histHead]  = yNew;
                rhoArr[histHead] = 1.0 / ys;
                histHead = (histHead + 1) % m;
                if (histLen < m) histLen++;
            }

            // Apply updates
            params = newParams;
            accum  = newAccum;

            // K recalibration
            if (recalibrateK) {
                double newK   = KFinder.findKFromFeatures(features, params);
                double kDrift = Math.abs(newK - k);
                if (kDrift >= 0.001) {
                    LOG.info(String.format("[L-BFGS] K recalibrated: %.6f \u2192 %.6f (drift=%.6f)", k, newK, kDrift));
                    k = newK;
                }
            }

            double newMse = TunerEvaluator.computeMseFromFeatures(features, params, k);
            long ms = Duration.between(iterStart, Instant.now()).toMillis();
            LOG.info(String.format("[L-BFGS] iter %3d  K=%.6f  MSE=%.8f  ||\u2207L||=%.2e  time=%dms",
                    iter, k, newMse, gradNorm, ms));

            // MSE flat-line secondary convergence check
            if (currentMse > 0 && Math.abs(currentMse - newMse) / currentMse < CONVERGENCE_THRESHOLD) {
                LOG.info(String.format("[L-BFGS] converged after %d iterations (MSE delta < %.1e)",
                        iter, CONVERGENCE_THRESHOLD));
                break;
            }
            currentMse = newMse;
        }

        return params;
    }

    /**
     * Convenience overload for L-BFGS with {@link #DEFAULT_MAX_ITERATIONS}
     * and no group mask (all params updated).
     */
    public static double[] tuneWithFeaturesLBFGS(List<PositionFeatures> features,
                                                 double[] initialParams,
                                                 double k) {
        return tuneWithFeaturesLBFGS(features, initialParams, k, DEFAULT_MAX_ITERATIONS, true, null);
    }

    /**
     * Convenience overload without a group mask (all params updated).
     */
    public static double[] tuneWithFeaturesLBFGS(List<PositionFeatures> features,
                                                 double[] initialParams,
                                                 double k,
                                                 int maxIters,
                                                 boolean recalibrateK) {
        return tuneWithFeaturesLBFGS(features, initialParams, k, maxIters, recalibrateK, null);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Computes the analytical gradient from features AND adds the logarithmic
     * barrier contribution for scalar parameters.  Used by both Adam and L-BFGS
     * so that curvature pairs (s, y) are consistent with the full augmented
     * objective.
     *
     * @param features precomputed features
     * @param params   current parameter array
     * @param k        sigmoid scaling constant
     * @param iter     current iteration number (controls barrier gamma annealing)
     */
    private static double[] computeBarrierGradient(List<PositionFeatures> features,
                                                   double[] params,
                                                   double k,
                                                   int iter) {
        double[] grad = computeGradientFromFeatures(features, params, k);
        double gamma = BARRIER_GAMMA_INIT * Math.pow(BARRIER_ANNEAL_RATE, iter - 1);
        for (int i = 0; i < grad.length; i++) {
            if (!EvalParams.isScalarParam(i)) continue;
            if (EvalParams.PARAM_MIN[i] == EvalParams.PARAM_MAX[i]) continue;
            double dist = params[i] - EvalParams.PARAM_MIN[i];
            if (dist < 1e-4) dist = 1e-4;
            grad[i] -= gamma / dist;
        }
        return grad;
    }

    /** Returns the dot product of two equal-length vectors. */
    private static double dot(double[] a, double[] b) {
        double s = 0.0;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return s;
    }

    /** In-place AXPY: {@code a += alpha * x}. */
    private static void axpy(double alpha, double[] x, double[] a) {
        for (int i = 0; i < a.length; i++) a[i] += alpha * x[i];
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

    // =========================================================================
    // Eval-mode Adam (Issue #141)
    // =========================================================================

    /**
     * Runs Adam in eval mode using direct centipawn MSE loss (no sigmoid, no K).
     *
     * <p>Loss = mean((vex_eval(p) − SF_eval_cp(p))²).
     * Gradient = (2/N) × Σ (vex_eval(p) − SF_eval_cp(p)) × ∂eval/∂params[i].
     *
     * <p>K calibration is not performed. All other Adam hyperparameters, logarithmic
     * barrier, material ordering, and group masking behave identically to WDL mode.
     *
     * @param features    precomputed feature vectors
     * @param sfEvalCps   Stockfish eval labels in centipawns, parallel to {@code features}
     * @param initialParams starting point (length {@link EvalParams#TOTAL_PARAMS})
     * @param maxIters    iteration cap
     * @param groupMask   optional; if non-null only {@code true} indices are updated
     * @return tuned parameter array
     */
    public static double[] tuneWithFeaturesEvalMode(List<PositionFeatures> features,
                                                    double[] sfEvalCps,
                                                    double[] initialParams,
                                                    int maxIters,
                                                    boolean[] groupMask) {
        int n = initialParams.length;
        double[] params = initialParams.clone();
        for (int i = 0; i < n; i++) params[i] = EvalParams.clampOne(i, params[i]);

        double[] accum = params.clone();
        double[] m = new double[n];
        double[] v = new double[n];

        double currentMse = TunerEvaluator.computeMseEvalMode(features, sfEvalCps, params);
        LOG.info(String.format("[Adam/eval] start  MSE_cp2=%.4f  params=%d  positions=%d  threads=%d",
                currentMse, n, features.size(), Runtime.getRuntime().availableProcessors()));

        for (int iter = 1; iter <= maxIters; iter++) {
            Instant iterStart = Instant.now();

            double[] grad = computeGradientEvalMode(features, sfEvalCps, params);

            // Logarithmic barrier — same as WDL mode (push scalar params away from PARAM_MIN)
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
                if (groupMask != null && !groupMask[i]) continue;

                m[i] = BETA1 * m[i] + (1 - BETA1) * grad[i];
                v[i] = BETA2 * v[i] + (1 - BETA2) * grad[i] * grad[i];

                double mHat = m[i] / (1 - Math.pow(BETA1, iter));
                double vHat = v[i] / (1 - Math.pow(BETA2, iter));

                accum[i] -= LR * mHat / (Math.sqrt(vHat) + EPSILON);
                if (EvalParams.isScalarParam(i)) {
                    params[i] = Math.min(EvalParams.PARAM_MAX[i], Math.round(accum[i]));
                } else {
                    params[i] = EvalParams.clampOne(i, Math.round(accum[i]));
                }
            }

            EvalParams.enforceMaterialOrdering(params);

            for (int i = 0; i < n; i++) {
                if (EvalParams.PARAM_MIN[i] == EvalParams.PARAM_MAX[i]) accum[i] = params[i];
            }

            double newMse = TunerEvaluator.computeMseEvalMode(features, sfEvalCps, params);
            long ms = Duration.between(iterStart, Instant.now()).toMillis();
            LOG.info(String.format("[Adam/eval] iter %3d  MSE_cp2=%.4f  time=%dms", iter, newMse, ms));

            if (currentMse > 0 && Math.abs(currentMse - newMse) / currentMse < CONVERGENCE_THRESHOLD) {
                LOG.info(String.format("[Adam/eval] converged after %d iterations (MSE delta < %.1e)",
                        iter, CONVERGENCE_THRESHOLD));
                currentMse = newMse;
                break;
            }
            currentMse = newMse;
        }

        return params;
    }

    /**
     * Computes the analytical gradient of the eval-mode MSE objective.
     *
     * <p>For each position p:
     * <pre>
     *   dMSE/dParams[i] = (2/N) × Σ (vex_eval(p) − SF_eval_cp(p)) × dEval/dParams[i]
     * </pre>
     * Uses the same sparse feature-weight representation as the WDL gradient path.
     * No sigmoid and no K factor — the loss is in raw centipawn space.
     */
    static double[] computeGradientEvalMode(List<PositionFeatures> features,
                                            double[] sfEvalCps,
                                            double[] params) {
        int n = params.length;
        int N = features.size();

        double[] grad = IntStream.range(0, N).parallel()
                .mapToObj(i -> {
                    double[] localGrad = new double[n];
                    PositionFeatures pf = features.get(i);
                    double factor = pf.eval(params) - sfEvalCps[i];
                    pf.accumulateGradient(localGrad, params, factor);
                    return localGrad;
                })
                .collect(
                    () -> new double[n],
                    (acc, lg) -> { for (int j = 0; j < n; j++) acc[j] += lg[j]; },
                    (a,   b)  -> { for (int j = 0; j < n; j++) a[j]  +=  b[j]; }
                );

        double scale = 2.0 / N;
        for (int i = 0; i < n; i++) grad[i] *= scale;
        return grad;
    }
}
