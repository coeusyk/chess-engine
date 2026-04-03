package coeusyk.game.chess.tuner;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Per-position feature vector for analytical (precomputed) gradient computation.
 *
 * <p>At load time, each {@link LabelledPosition} is converted into a compact
 * representation that stores all positional features needed to compute both
 * the static evaluation and its gradient w.r.t. the 817 tuning parameters —
 * without any bitboard operation at iteration time.
 *
 * <h3>Evaluation decomposition</h3>
 * <p>The evaluation is decomposed as:
 * <pre>
 *   eval(pos, params) = dot(w, params) + nonLinearKingSafety(params)
 * </pre>
 * where {@code w[i]} is the precomputed coefficient for param {@code i} (with
 * the tapered-eval phase weight already applied), and the non-linear king safety
 * term handles the quadratic attacker-penalty expression:
 * <pre>
 *   nonLinear = (-wW² + wB²) / 4 × (phase/24)
 *   wW = wN×ATK_N + wB_f×ATK_B + wR_f×ATK_R + wQ_f×ATK_Q  (attackers on white king zone)
 *   wB = bN×ATK_N + bB_f×ATK_B + bR_f×ATK_R + bQ_f×ATK_Q  (attackers on black king zone)
 * </pre>
 *
 * <h3>Memory</h3>
 * <p>Features are stored sparsely (only non-zero param indices + their weights).
 * At ~120 active features per position, 725k positions occupy roughly 600 MB.
 * Run the tuner with {@code -Xmx2g} or larger.
 *
 * <h3>Thread safety</h3>
 * <p>All fields are final after construction; instances are immutable.
 */
public final class PositionFeatures {

    /** Game result from White's perspective (1.0 = White win, 0.5 = draw, 0.0 = Black win). */
    public final float outcome;

    /**
     * Game phase (0–24). 24 = full middlegame, 0 = pure endgame.
     * Stored because the non-linear king-safety gradient needs it.
     */
    final int phase;

    /**
     * Non-zero param indices (ATK_KNIGHT..ATK_QUEEN excluded; handled separately).
     * All values fit in a short since TOTAL_PARAMS = 817 ≤ Short.MAX_VALUE.
     */
    final short[] indices;

    /**
     * Corresponding precomputed weights for each entry in {@link #indices}.
     * Each weight equals: (net positional count) × (phase taper for that param type).
     * Eval contribution: sum_k(indices[k] → params[indices[k]] * weights[k]).
     */
    final float[] weights;

    // ---- King zone attacker counts (for non-linear king safety penalty) ----
    // wX = number of Black X pieces attacking the White king zone
    final short wN, wB, wR, wQ;
    // bX = number of White X pieces attacking the Black king zone
    final short bN, bB, bR, bQ;

    PositionFeatures(float outcome, int phase,
                     short[] indices, float[] weights,
                     short wN, short wB, short wR, short wQ,
                     short bN, short bB, short bR, short bQ) {
        this.outcome = outcome;
        this.phase   = phase;
        this.indices = indices;
        this.weights = weights;
        this.wN = wN; this.wB = wB; this.wR = wR; this.wQ = wQ;
        this.bN = bN; this.bB = bB; this.bR = bR; this.bQ = bQ;
    }

    // =========================================================================
    // Fast eval using precomputed features (no bitboard operations)
    // =========================================================================

    /**
     * Evaluates this position using the precomputed feature vector.
     * Equivalent to {@link TunerEvaluator#evaluateStatic(PositionData, double[])}
     * but costs only a dot product + 8 multiplications per call.
     */
    public double eval(double[] params) {
        double e = 0.0;
        for (int k = 0; k < indices.length; k++) {
            e += weights[k] * params[indices[k]];
        }
        // Non-linear king safety attacker penalty
        double wW  = wN * params[EvalParams.IDX_ATK_KNIGHT]
                   + wB * params[EvalParams.IDX_ATK_BISHOP]
                   + wR * params[EvalParams.IDX_ATK_ROOK]
                   + wQ * params[EvalParams.IDX_ATK_QUEEN];
        double wBk = bN * params[EvalParams.IDX_ATK_KNIGHT]
                   + bB * params[EvalParams.IDX_ATK_BISHOP]
                   + bR * params[EvalParams.IDX_ATK_ROOK]
                   + bQ * params[EvalParams.IDX_ATK_QUEEN];
        e += (-wW * wW + wBk * wBk) / 4.0 * phase / 24.0;
        return e;
    }

    // =========================================================================
    // Analytical gradient accumulation
    // =========================================================================

    /**
     * Accumulates this position's gradient contribution into {@code grad[]}.
     *
     * <p>{@code factor} is the per-position multiplier already computed by the caller:
     * <pre>
     *   factor = (sigmoid(eval, K) − outcome) × sigmoid × (1 − sigmoid) × K×ln10/400
     * </pre>
     * The average-over-positions scale factor (2/N) is applied by the caller after all
     * positions have been processed.
     *
     * @param grad   gradient accumulator array (length {@link EvalParams#TOTAL_PARAMS})
     * @param params current parameter array (needed for non-linear ATK gradient)
     * @param factor per-position error × sigmoid-derivative × K-factor
     */
    void accumulateGradient(double[] grad, double[] params, double factor) {
        // Linear terms: grad[i] += factor * weight_i
        for (int k = 0; k < indices.length; k++) {
            grad[indices[k]] += factor * weights[k];
        }
        // Non-linear king safety ATK gradient
        // d(-wW²+wBk²)/4 / d(ATK_X) = (-wW × wX_count + wBk × bX_count) / 2
        // phase/24 factor because attacker penalty goes into mgScore which is tapered
        double wW  = wN * params[EvalParams.IDX_ATK_KNIGHT]
                   + wB * params[EvalParams.IDX_ATK_BISHOP]
                   + wR * params[EvalParams.IDX_ATK_ROOK]
                   + wQ * params[EvalParams.IDX_ATK_QUEEN];
        double wBk = bN * params[EvalParams.IDX_ATK_KNIGHT]
                   + bB * params[EvalParams.IDX_ATK_BISHOP]
                   + bR * params[EvalParams.IDX_ATK_ROOK]
                   + bQ * params[EvalParams.IDX_ATK_QUEEN];
        double pf  = factor * phase / 24.0;
        grad[EvalParams.IDX_ATK_KNIGHT] += pf * (-wW * wN + wBk * bN) / 2.0;
        grad[EvalParams.IDX_ATK_BISHOP] += pf * (-wW * wB + wBk * bB) / 2.0;
        grad[EvalParams.IDX_ATK_ROOK]   += pf * (-wW * wR + wBk * bR) / 2.0;
        grad[EvalParams.IDX_ATK_QUEEN]  += pf * (-wW * wQ + wBk * bQ) / 2.0;
    }

    // =========================================================================
    // Factory
    // =========================================================================

    /**
     * Builds a list of feature vectors from all labelled positions in parallel.
     * Each bitboard operation runs exactly once per position; no bitboard ops
     * are performed during subsequent gradient iterations.
     *
     * @param positions pre-loaded training set
     * @return list of feature vectors, same order as input
     */
    public static List<PositionFeatures> buildList(List<LabelledPosition> positions) {
        return positions.parallelStream()
                .map(lp -> TunerEvaluator.buildFeatures(lp.pos(), (float) lp.outcome()))
                .collect(Collectors.toList());
    }
}
