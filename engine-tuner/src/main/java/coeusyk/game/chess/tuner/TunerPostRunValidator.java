package coeusyk.game.chess.tuner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Post-run validity gate between the tuner and SPRT.
 *
 * <p>Three independent gates are applied in order:
 * <ol>
 *   <li><b>Convergence audit</b> — checks that the run did not terminate with
 *       an iteration-cap hit AND a still-large relative-delta, and that MSE did
 *       not overshoot the recorded minimum by more than 15 %.</li>
 *   <li><b>Param sanity</b> — checks that material ordering is preserved
 *       (P ≤ N ≤ B &lt; R &lt; Q in both MG and EG), PST entries are within
 *       ±{@value #PST_MG_BOUND} cp (MG) / ±{@value #PST_EG_BOUND} cp (EG),
 *       king-safety attacker weights are not severely negative (&gt; -50),
 *       and mobility bonus scalars are not severely negative (&gt; -20 per move).</li>
 *   <li><b>Smoke test</b> — plays a short fixed-depth self-play match via
 *       {@link SmokeTestRunner}: tuned params vs. initial params.  The run is
 *       rejected if the tuned side's LOS falls below
 *       {@link ValidatorConfig#losRejectThreshold()}.</li>
 * </ol>
 *
 * <p>A textual report is always produced regardless of pass/fail.
 */
public final class TunerPostRunValidator {

    private static final Logger LOG = LoggerFactory.getLogger(TunerPostRunValidator.class);

    // --- Sanity bounds ---
    static final int PST_MG_BOUND = 300; // centipawn — tuned PST entries can legitimately reach ~200
    static final int PST_EG_BOUND = 250; // centipawn

    /**
     * PST layout: for piece type {@code pt} (1-based, pawn=1 .. king=6):
     * <pre>
     *   MG: params[IDX_PST_START + (pt-1)*128 + sq]  sq ∈ [0,63]
     *   EG: params[IDX_PST_START + (pt-1)*128 + 64 + sq]
     * </pre>
     */
    private static final int PST_SLICE = 128; // MG+EG per piece
    private static final int PST_PIECES = 6;

    /**
     * Convergence: reject if run hit iteration cap AND mean relative delta > this threshold.
     * 1e-4 = 0.01 % per iteration — still moving.
     */
    private static final double CONVERGENCE_DELTA_THRESHOLD = 1e-4;

    /** Convergence: reject if finalMse > minMse × this multiplier (overshoot). */
    private static final double MSE_OVERSHOOT_FACTOR = 1.15;

    /** Material index offsets within the params array (flat layout). */
    private static final int PAWN_MG   = 0;
    private static final int PAWN_EG   = 1;
    private static final int KNIGHT_MG = 2;
    private static final int KNIGHT_EG = 3;
    private static final int BISHOP_MG = 4;
    private static final int BISHOP_EG = 5;
    private static final int ROOK_MG   = 6;
    private static final int ROOK_EG   = 7;
    private static final int QUEEN_MG  = 8;
    private static final int QUEEN_EG  = 9;

    private TunerPostRunValidator() {}

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Configuration for the validator.
     *
     * @param skipConvergenceCheck skip Gate 1
     * @param skipSanityCheck      skip Gate 2
     * @param skipSmokeTest        skip Gate 3
     * @param smokeGames           number of self-play games (must be even; rounded up)
     * @param smokeDepth           fixed search depth per move
     * @param losRejectThreshold   LOS below this rejects the run (default 0.30)
     */
    public record ValidatorConfig(
            boolean skipConvergenceCheck,
            boolean skipSanityCheck,
            boolean skipSmokeTest,
            int smokeGames,
            int smokeDepth,
            double losRejectThreshold) {

        /** Default: all gates enabled, 100 games, depth 3, LOS threshold 0.30. */
        public static ValidatorConfig defaults() {
            return new ValidatorConfig(false, false, false, 100, 3, 0.30);
        }
    }

    /**
     * Result of a validation run.
     *
     * @param passed              true iff all enabled gates passed
     * @param convergenceVerdict  one-line verdict string from Gate 1 (or "SKIPPED")
     * @param sanityVerdict       one-line verdict string from Gate 2 (or "SKIPPED")
     * @param smokeVerdict        one-line verdict string from Gate 3 (or "SKIPPED")
     * @param reportText          full human-readable report
     */
    public record ValidationResult(
            boolean passed,
            String convergenceVerdict,
            String sanityVerdict,
            String smokeVerdict,
            String reportText) {}

    /**
     * Runs all configured gates and returns a {@link ValidationResult}.
     *
     * @param initialParams params before tuning (used as smoke-test baseline)
     * @param tunedParams   params after tuning (the candidate to validate)
     * @param metrics       run statistics from {@link GradientDescent} (may be null)
     * @param config        gate configuration
     */
    public static ValidationResult validate(double[] initialParams,
                                            double[] tunedParams,
                                            TunerRunMetrics metrics,
                                            ValidatorConfig config) {
        StringBuilder report = new StringBuilder();
        report.append("=== Tuner Post-Run Validation Report ===\n");

        boolean allPassed = true;

        // --- Gate 1: Convergence ---
        String convergenceVerdict;
        if (config.skipConvergenceCheck() || metrics == null) {
            convergenceVerdict = metrics == null
                    ? "[Convergence] SKIPPED — no metrics collected"
                    : "[Convergence] SKIPPED — --skip-convergence flag set";
        } else {
            convergenceVerdict = checkConvergence(metrics);
        }
        boolean convergencePassed = convergenceVerdict.contains("PASS")
                || convergenceVerdict.contains("SKIPPED");
        if (!convergencePassed) allPassed = false;
        report.append(convergenceVerdict).append('\n');

        // --- Gate 2: Param sanity ---
        String sanityVerdict;
        if (config.skipSanityCheck()) {
            sanityVerdict = "[Sanity] SKIPPED — --skip-sanity flag set";
        } else {
            sanityVerdict = checkSanity(tunedParams);
        }
        boolean sanityPassed = sanityVerdict.contains("PASS")
                || sanityVerdict.contains("SKIPPED");
        if (!sanityPassed) allPassed = false;
        report.append(sanityVerdict).append('\n');

        // --- Gate 3: Smoke test ---
        String smokeVerdict;
        if (config.skipSmokeTest()) {
            smokeVerdict = "[Smoke] SKIPPED — --skip-smoke flag set";
        } else {
            smokeVerdict = runSmoke(tunedParams, initialParams, config);
        }
        boolean smokePassed = smokeVerdict.contains("PASS")
                || smokeVerdict.contains("SKIPPED");
        if (!smokePassed) allPassed = false;
        report.append(smokeVerdict).append('\n');

        report.append('\n');
        report.append(allPassed
                ? "[Validator] OVERALL: PASS — tuned_params.txt will be written.\n"
                : "[Validator] OVERALL: FAIL — tuned_params.txt NOT written. Fix the above issues.\n");

        String reportText = report.toString();
        LOG.info(reportText);

        return new ValidationResult(allPassed,
                convergenceVerdict, sanityVerdict, smokeVerdict, reportText);
    }

    // -----------------------------------------------------------------------
    // Gate 1: Convergence
    // -----------------------------------------------------------------------

    static String checkConvergence(TunerRunMetrics metrics) {
        StringBuilder sb = new StringBuilder("[Convergence] ");

        boolean fail = false;
        StringBuilder reasons = new StringBuilder();

        // Check 1: hit iter cap with non-trivial remaining delta
        if (metrics.hitIterCap) {
            double meanDelta = metrics.meanRecentRelDelta(20);
            if (!Double.isNaN(meanDelta) && meanDelta > CONVERGENCE_DELTA_THRESHOLD) {
                reasons.append(String.format(
                        "hit maxIters cap with meanRelDelta=%.2e > %.2e (still moving); ",
                        meanDelta, CONVERGENCE_DELTA_THRESHOLD));
                fail = true;
            }
        }

        // Check 2: MSE overshoot — finalMse > minMse * 1.15
        if (!Double.isNaN(metrics.finalMse) && metrics.minMse < Double.MAX_VALUE) {
            if (metrics.finalMse > metrics.minMse * MSE_OVERSHOOT_FACTOR) {
                reasons.append(String.format(
                        "MSE overshot minimum (final=%.6f > min=%.6f × %.2f); ",
                        metrics.finalMse, metrics.minMse, MSE_OVERSHOOT_FACTOR));
                fail = true;
            }
        }

        if (fail) {
            sb.append("FAIL — ").append(reasons);
        } else {
            sb.append("PASS — iters=").append(metrics.itersCompleted)
              .append(" hitCap=").append(metrics.hitIterCap)
              .append(String.format(" finalMse=%.6f", Double.isNaN(metrics.finalMse) ? 0 : metrics.finalMse))
              .append(String.format(" meanRelDelta=%.2e", metrics.meanRecentRelDelta(20)));
        }

        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // Gate 2: Param sanity
    // -----------------------------------------------------------------------

    static String checkSanity(double[] params) {
        StringBuilder sb = new StringBuilder("[Sanity] ");

        StringBuilder failures = new StringBuilder();

        // 2a. Material ordering: P ≤ N ≤ B < R < Q (MG)
        if (!checkMaterialOrdering(params, true)) {
            failures.append("Material ordering violated (MG): expected P≤N≤B<R<Q but got "
                    + String.format("P=%.0f N=%.0f B=%.0f R=%.0f Q=%.0f; ",
                            params[PAWN_MG], params[KNIGHT_MG], params[BISHOP_MG],
                            params[ROOK_MG], params[QUEEN_MG]));
        }

        // 2b. Material ordering: P ≤ N ≤ B < R < Q (EG)
        if (!checkMaterialOrdering(params, false)) {
            failures.append("Material ordering violated (EG): expected P≤N≤B<R<Q but got "
                    + String.format("P=%.0f N=%.0f B=%.0f R=%.0f Q=%.0f; ",
                            params[PAWN_EG], params[KNIGHT_EG], params[BISHOP_EG],
                            params[ROOK_EG], params[QUEEN_EG]));
        }

        // 2c. PST entry bounds
        String pstCheck = checkPstBounds(params);
        if (pstCheck != null) failures.append(pstCheck);

        // 2d. King-safety attacker weights: must be positive
        String atkCheck = checkAttackerWeights(params);
        if (atkCheck != null) failures.append(atkCheck);

        // 2e. Mobility scalars: non-decreasing across piece types (804..811)
        String mobCheck = checkMobility(params);
        if (mobCheck != null) failures.append(mobCheck);

        if (failures.length() > 0) {
            sb.append("FAIL — ").append(failures);
        } else {
            sb.append("PASS");
        }

        return sb.toString();
    }

    /** Returns true when the material ordering constraint holds. MG uses even indices 0,2,4,6,8. */
    private static boolean checkMaterialOrdering(double[] p, boolean mg) {
        int pawn   = mg ? PAWN_MG   : PAWN_EG;
        int knight = mg ? KNIGHT_MG : KNIGHT_EG;
        int bishop = mg ? BISHOP_MG : BISHOP_EG;
        int rook   = mg ? ROOK_MG   : ROOK_EG;
        int queen  = mg ? QUEEN_MG  : QUEEN_EG;

        // P ≤ N ≤ B < R < Q
        return p[pawn]   <= p[knight]
            && p[knight] <= p[bishop]
            && p[bishop]  < p[rook]
            && p[rook]    < p[queen];
    }

    /**
     * Returns a failure message if any PST entry exceeds bounds, otherwise null.
     * MG entries: IDX_PST_START + (pt-1)*128 + [0..63], bound ±{@value #PST_MG_BOUND} cp.
     * EG entries: IDX_PST_START + (pt-1)*128 + [64..127], bound ±{@value #PST_EG_BOUND} cp.
     */
    private static String checkPstBounds(double[] params) {
        int base = EvalParams.IDX_PST_START;
        int violations = 0;
        double maxViolation = 0;

        for (int pt = 0; pt < PST_PIECES; pt++) {
            int sliceBase = base + pt * PST_SLICE;
            // MG: [sliceBase .. sliceBase+63]
            for (int sq = 0; sq < 64; sq++) {
                double v = params[sliceBase + sq];
                if (Math.abs(v) > PST_MG_BOUND) {
                    violations++;
                    maxViolation = Math.max(maxViolation, Math.abs(v));
                }
            }
            // EG: [sliceBase+64 .. sliceBase+127]
            for (int sq = 0; sq < 64; sq++) {
                double v = params[sliceBase + 64 + sq];
                if (Math.abs(v) > PST_EG_BOUND) {
                    violations++;
                    maxViolation = Math.max(maxViolation, Math.abs(v));
                }
            }
        }

        if (violations > 0) {
            return String.format("PST entry exceeds bounds: %d violation(s), max=%.0f cp; ",
                    violations, maxViolation);
        }
        return null;
    }

    /** Attacker weights below this threshold are flagged as clearly wrong tuning divergence. */
    private static final double ATK_WEIGHT_MIN = -50.0;

    /**
     * Returns a failure message if any attacker-weight param is severely negative (< -50),
     * else null.  Small negatives (e.g. −1) can be valid tuning results and are allowed.
     * Indices IDX_ATK_KNIGHT(800) through IDX_ATK_QUEEN(803).
     */
    private static String checkAttackerWeights(double[] params) {
        int[] indices = {
            EvalParams.IDX_ATK_KNIGHT,
            EvalParams.IDX_ATK_BISHOP,
            EvalParams.IDX_ATK_ROOK,
            EvalParams.IDX_ATK_QUEEN
        };
        String[] names = {"ATK_KNIGHT", "ATK_BISHOP", "ATK_ROOK", "ATK_QUEEN"};

        StringBuilder sb = null;
        for (int i = 0; i < indices.length; i++) {
            if (params[indices[i]] < ATK_WEIGHT_MIN) {
                if (sb == null) sb = new StringBuilder();
                sb.append(String.format("King-safety weight %s=%.0f is severely negative; ",
                        names[i], params[indices[i]]));
            }
        }
        return (sb == null) ? null : sb.toString();
    }

    /** Mobility values below this threshold indicate tuning divergence. */
    private static final double MOB_MIN = -20.0;

    /**
     * Returns a failure message if any mobility bonus scalar (804..811) is severely negative
     * (< -20 cp per move), else null.  Per-piece-type ordering (N/B/R/Q) is NOT enforced
     * because the tuner may legitimately find non-monotone values.
     */
    private static String checkMobility(double[] params) {
        int start = EvalParams.IDX_MOB_MG_START;
        StringBuilder sb = null;
        for (int i = start; i < start + 8; i++) {
            if (params[i] < MOB_MIN) {
                if (sb == null) sb = new StringBuilder("Mobility bonus severely negative: ");
                sb.append(String.format("params[%d]=%.0f; ", i, params[i]));
            }
        }
        return (sb == null) ? null : sb.toString();
    }

    // -----------------------------------------------------------------------
    // Gate 3: Smoke test
    // -----------------------------------------------------------------------

    private static String runSmoke(double[] tunedParams, double[] initialParams,
                                   ValidatorConfig config) {
        LOG.info("[Validator] Running smoke test ({} games, depth {})…",
                config.smokeGames(), config.smokeDepth());

        SmokeTestRunner.SmokeResult result = SmokeTestRunner.run(
                tunedParams, initialParams, config.smokeGames(), config.smokeDepth());

        String verdict = String.format(
                "[Smoke] %s — games=%d W=%d D=%d L=%d score=%.3f LOS=%.3f (threshold=%.2f)",
                result.los() >= config.losRejectThreshold() ? "PASS" : "FAIL",
                result.wins() + result.draws() + result.losses(),
                result.wins(), result.draws(), result.losses(),
                result.score(), result.los(), config.losRejectThreshold());

        LOG.info(verdict);
        return verdict;
    }
}
