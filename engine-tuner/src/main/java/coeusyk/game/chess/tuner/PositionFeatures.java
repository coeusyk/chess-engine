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
 * term evaluates attacker pressure via a piecewise-linear safety table:
 * <pre>
 *   nonLinear = (-safetyEval(wW) + safetyEval(wBk)) × (phase/24)
 *   wW  = wN×ATK_N + wB×ATK_B + wR×ATK_R + wQ×ATK_Q  (attackers on white king zone)
 *   wBk = bN×ATK_N + bB×ATK_B + bR×ATK_R + bQ×ATK_Q  (attackers on black king zone)
 *   safetyEval(w) — piecewise-linear interpolation of SAFETY_TABLE, mirrors KingSafety
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
    // Safety table helpers (piecewise-linear, mirrors KingSafety.SAFETY_TABLE)
    // =========================================================================

    // Must match TunerEvaluator.SAFETY_TABLE and KingSafety.SAFETY_TABLE.
    private static final int[] SAFETY_TABLE = {
        0, 0, 1, 2, 3, 5, 7, 9, 12, 15, 18, 22, 26, 30, 35, 40, 45, 50
    };

    /**
     * Piecewise-linear interpolation of the safety table at a floating-point weight {@code w}.
     * Allows the Adam optimizer to treat ATK_WEIGHT params as continuous variables
     * while matching the integer lookup the engine performs at game time.
     */
    private static double safetyEval(double w) {
        if (w <= 0.0) return 0.0;
        int lo = (int) w;
        if (lo >= SAFETY_TABLE.length - 1) return SAFETY_TABLE[SAFETY_TABLE.length - 1];
        return SAFETY_TABLE[lo] * (1.0 - (w - lo)) + SAFETY_TABLE[lo + 1] * (w - lo);
    }

    /**
     * Slope of the piecewise-linear safety table at {@code w} (used for gradient computation).
     * Returns 0 at the plateau (w >= table max index).
     */
    private static double safetyGradient(double w) {
        if (w <= 0.0) return 0.0;
        int lo = (int) w;
        if (lo >= SAFETY_TABLE.length - 1) return 0.0;
        return SAFETY_TABLE[lo + 1] - SAFETY_TABLE[lo];
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
        // Non-linear king safety attacker penalty using piecewise-linear safety table
        double wW  = wN * params[EvalParams.IDX_ATK_KNIGHT]
                   + wB * params[EvalParams.IDX_ATK_BISHOP]
                   + wR * params[EvalParams.IDX_ATK_ROOK]
                   + wQ * params[EvalParams.IDX_ATK_QUEEN];
        double wBk = bN * params[EvalParams.IDX_ATK_KNIGHT]
                   + bB * params[EvalParams.IDX_ATK_BISHOP]
                   + bR * params[EvalParams.IDX_ATK_ROOK]
                   + bQ * params[EvalParams.IDX_ATK_QUEEN];
        e += (-safetyEval(wW) + safetyEval(wBk)) * phase / 24.0
             * params[EvalParams.IDX_KING_SAFETY_SCALE] / 100.0;
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
        // Non-linear king safety ATK gradient using piecewise-linear safety table.
        // ∂(-safetyEval(wW) + safetyEval(wBk)) / ∂ATK_X
        //   = (-safetyGradient(wW) * xW_count + safetyGradient(wBk) * xBk_count)
        // phase/24 factor because attacker penalty goes into mgScore which is tapered
        double wW  = wN * params[EvalParams.IDX_ATK_KNIGHT]
                   + wB * params[EvalParams.IDX_ATK_BISHOP]
                   + wR * params[EvalParams.IDX_ATK_ROOK]
                   + wQ * params[EvalParams.IDX_ATK_QUEEN];
        double wBk = bN * params[EvalParams.IDX_ATK_KNIGHT]
                   + bB * params[EvalParams.IDX_ATK_BISHOP]
                   + bR * params[EvalParams.IDX_ATK_ROOK]
                   + bQ * params[EvalParams.IDX_ATK_QUEEN];
        double pf     = factor * phase / 24.0;
        double sGradW  = safetyGradient(wW);
        double sGradBk = safetyGradient(wBk);
        double kss = params[EvalParams.IDX_KING_SAFETY_SCALE] / 100.0;
        grad[EvalParams.IDX_ATK_KNIGHT] += pf * kss * (-sGradW * wN + sGradBk * bN);
        grad[EvalParams.IDX_ATK_BISHOP] += pf * kss * (-sGradW * wB + sGradBk * bB);
        grad[EvalParams.IDX_ATK_ROOK]   += pf * kss * (-sGradW * wR + sGradBk * bR);
        grad[EvalParams.IDX_ATK_QUEEN]  += pf * kss * (-sGradW * wQ + sGradBk * bQ);
        // Gradient for KING_SAFETY_SCALE: d(eval)/d(scale) = baseKingSafetyEval / 100
        double baseKsSafety = (-safetyEval(wW) + safetyEval(wBk)) * phase / 24.0;
        grad[EvalParams.IDX_KING_SAFETY_SCALE] += factor * baseKsSafety / 100.0;
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
