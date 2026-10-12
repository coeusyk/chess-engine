package coeusyk.game.chess.tuner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TunerPostRunValidator} convergence and sanity gates.
 *
 * <p>Smoke-test gate is not tested here (requires running full self-play games, which is too slow
 * for a unit test). The smoke gate is covered by the acceptance-criteria validation in
 * the integration build.
 */
class TunerValidatorTest {

    private static final TunerPostRunValidator.ValidatorConfig SKIP_SMOKE =
            new TunerPostRunValidator.ValidatorConfig(true, 100, 3, 0.30);

    /** A parameter array that satisfies all default sanity constraints. */
    private double[] validParams;

    @BeforeEach
    void setUp() {
        validParams = EvalParams.extractFromCurrentEval();
    }

    // =========================================================================
    // Gate 1: Convergence
    // =========================================================================

    @Test
    void convergence_pass_with_low_delta_and_no_cap() {
        TunerRunMetrics m = new TunerRunMetrics();
        m.itersCompleted = 200;
        m.hitIterCap = false;
        m.finalMse = 0.02;
        m.minMse = 0.019;
        for (int i = 0; i < 20; i++) m.recordRelDelta(5e-6); // tiny deltas

        String verdict = TunerPostRunValidator.checkConvergence(m);

        assertTrue(verdict.contains("PASS"), "Expected PASS but got: " + verdict);
    }

    @Test
    void convergence_pass_when_cap_hit_but_delta_tiny() {
        TunerRunMetrics m = new TunerRunMetrics();
        m.itersCompleted = 1500;
        m.hitIterCap = true;
        m.finalMse = 0.0250;
        m.minMse   = 0.0245;
        // mean delta well below threshold
        for (int i = 0; i < 20; i++) m.recordRelDelta(1e-6);

        String verdict = TunerPostRunValidator.checkConvergence(m);

        assertTrue(verdict.contains("PASS"), "Expected PASS but got: " + verdict);
    }

    @Test
    void convergence_fail_on_iter_cap_with_high_delta() {
        TunerRunMetrics m = new TunerRunMetrics();
        m.itersCompleted = 1500;
        m.hitIterCap = true;
        m.finalMse = 0.05;
        m.minMse   = 0.048;
        // mean delta > 1e-4 threshold
        for (int i = 0; i < 20; i++) m.recordRelDelta(3e-3);

        String verdict = TunerPostRunValidator.checkConvergence(m);

        assertTrue(verdict.contains("FAIL"), "Expected FAIL but got: " + verdict);
        assertTrue(verdict.contains("hit maxIters cap"), "Expected cap message in: " + verdict);
    }

    @Test
    void convergence_fail_on_mse_overshoot() {
        TunerRunMetrics m = new TunerRunMetrics();
        m.itersCompleted = 300;
        m.hitIterCap = false;
        m.minMse   = 100.0;
        m.finalMse = 120.0; // 20 % above minimum — exceeds 15 % threshold
        for (int i = 0; i < 20; i++) m.recordRelDelta(1e-7);

        String verdict = TunerPostRunValidator.checkConvergence(m);

        assertTrue(verdict.contains("FAIL"), "Expected FAIL but got: " + verdict);
        assertTrue(verdict.contains("overshot"), "Expected overshoot message in: " + verdict);
    }

    @Test
    void convergence_pass_when_mse_just_below_overshoot_threshold() {
        TunerRunMetrics m = new TunerRunMetrics();
        m.itersCompleted = 500;
        m.hitIterCap = false;
        m.minMse   = 100.0;
        m.finalMse = 114.9; // 14.9 % above min — just inside 15 % threshold
        for (int i = 0; i < 20; i++) m.recordRelDelta(1e-7);

        String verdict = TunerPostRunValidator.checkConvergence(m);

        assertTrue(verdict.contains("PASS"), "Expected PASS but got: " + verdict);
    }

    // =========================================================================
    // Gate 2: Sanity — material ordering
    // =========================================================================

    @Test
    void sanity_pass_with_extracted_engine_params() {
        String verdict = TunerPostRunValidator.checkSanity(validParams);
        assertTrue(verdict.contains("PASS"), "Engine defaults should pass sanity: " + verdict);
    }

    @Test
    void sanity_fail_when_queen_less_than_rook_mg() {
        double[] p = validParams.clone();
        // Force Q_MG < R_MG (indices 8 and 6)
        p[8] = p[6] - 10; // queen less than rook

        String verdict = TunerPostRunValidator.checkSanity(p);

        assertTrue(verdict.contains("FAIL"), "Expected FAIL but got: " + verdict);
        assertTrue(verdict.contains("Material ordering"), "Expected ordering message in: " + verdict);
    }

    @Test
    void sanity_fail_when_rook_less_than_bishop_eg() {
        double[] p = validParams.clone();
        // Force R_EG < B_EG (indices 7 and 5)
        p[7] = p[5] - 5; // rook less than bishop EG

        String verdict = TunerPostRunValidator.checkSanity(p);

        assertTrue(verdict.contains("FAIL"), "Expected FAIL but got: " + verdict);
        assertTrue(verdict.contains("Material ordering"), "Expected ordering message in: " + verdict);
    }

    @Test
    void sanity_fail_when_pst_entry_exceeds_mg_bound() {
        double[] p = validParams.clone();
        // Set a pawn MG PST entry to 350 cp (> ±300 cp bound)
        p[EvalParams.IDX_PST_START] = 350.0;

        String verdict = TunerPostRunValidator.checkSanity(p);

        assertTrue(verdict.contains("FAIL"), "Expected FAIL but got: " + verdict);
        assertTrue(verdict.contains("PST entry exceeds bounds"), "Expected PST message in: " + verdict);
    }

    @Test
    void sanity_fail_when_pst_entry_exceeds_eg_bound() {
        double[] p = validParams.clone();
        // Set a pawn EG PST entry to -280 cp (> ±250 cp EG bound)
        p[EvalParams.IDX_PST_START + 64] = -280.0;

        String verdict = TunerPostRunValidator.checkSanity(p);

        assertTrue(verdict.contains("FAIL"), "Expected FAIL but got: " + verdict);
        assertTrue(verdict.contains("PST entry exceeds bounds"), "Expected PST message in: " + verdict);
    }

    @Test
    void sanity_pass_when_pst_entry_at_exact_bounds() {
        double[] p = validParams.clone();
        // Set entries to exact boundary values — should pass
        p[EvalParams.IDX_PST_START]      = 300.0;  // MG bound (allowed)
        p[EvalParams.IDX_PST_START + 64] = -250.0; // EG bound (allowed)

        String verdict = TunerPostRunValidator.checkSanity(p);

        // Only the material ordering check should run; PST bounds are exactly at limit
        // A FAIL here means something else went wrong
        assertFalse(verdict.contains("PST entry exceeds bounds"),
                "PST at exact limit should not trigger violation: " + verdict);
    }

    @Test
    void sanity_fail_when_attacker_weight_zero() {
        double[] p = validParams.clone();
        // Use a strongly negative value to trigger the severity threshold (< -50)
        p[EvalParams.IDX_ATK_KNIGHT] = -100;

        String verdict = TunerPostRunValidator.checkSanity(p);

        assertTrue(verdict.contains("FAIL"), "Expected FAIL but got: " + verdict);
        assertTrue(verdict.contains("King-safety weight"), "Expected ATK message in: " + verdict);
    }

    @Test
    void sanity_fail_when_mobility_not_monotone() {
        double[] p = validParams.clone();
        // Set a mobility bonus to a severely negative value, which indicates tuning divergence
        p[EvalParams.IDX_MOB_MG_START]     = 10;  // knight MG bonus (fine)
        p[EvalParams.IDX_MOB_MG_START + 1] = -50; // bishop MG — strongly negative (wrong)

        String verdict = TunerPostRunValidator.checkSanity(p);

        assertTrue(verdict.contains("FAIL"), "Expected FAIL but got: " + verdict);
        assertTrue(verdict.contains("Mobility"), "Expected mobility message in: " + verdict);
    }

    // =========================================================================
    // Gate integration: full validate() with smoke skipped
    // =========================================================================

    @Test
    void validate_passes_with_good_metrics_and_valid_params() {
        TunerRunMetrics m = buildGoodMetrics();

        TunerPostRunValidator.ValidationResult result =
                TunerPostRunValidator.validate(validParams, validParams, m, SKIP_SMOKE);

        assertTrue(result.passed(), "Full validation should PASS: " + result.reportText());
    }

    @Test
    void validate_fails_when_convergence_fails() {
        TunerRunMetrics m = buildGoodMetrics();
        m.hitIterCap = true;
        for (int i = 0; i < 20; i++) m.recordRelDelta(5e-3); // above threshold

        TunerPostRunValidator.ValidationResult result =
                TunerPostRunValidator.validate(validParams, validParams, m, SKIP_SMOKE);

        assertFalse(result.passed(), "Should FAIL on convergence: " + result.reportText());
    }

    @Test
    void validate_fails_when_sanity_fails() {
        TunerRunMetrics m = buildGoodMetrics();
        double[] badParams = validParams.clone();
        badParams[8] = badParams[6] - 10; // Q < R

        TunerPostRunValidator.ValidationResult result =
                TunerPostRunValidator.validate(validParams, badParams, m, SKIP_SMOKE);

        assertFalse(result.passed(), "Should FAIL on sanity: " + result.reportText());
    }

    @Test
    void validate_fails_when_convergence_and_sanity_both_bad() {
        // Convergence and sanity gates are now mandatory (no skip flags).
        // Bad iter-cap delta + ordering violation must cause overall FAIL.
        TunerRunMetrics m = buildGoodMetrics();
        m.hitIterCap = true;
        for (int i = 0; i < 20; i++) m.recordRelDelta(5e-3); // would fail convergence

        double[] badParams = validParams.clone();
        badParams[4] = badParams[2] - 10; // Bishop MG < Knight MG: violates ordering but stays in bounds

        TunerPostRunValidator.ValidationResult result =
                TunerPostRunValidator.validate(validParams, badParams, m, SKIP_SMOKE);

        assertFalse(result.passed(), "Should FAIL with mandatory convergence/sanity: " + result.reportText());
    }

    @Test
    void sanity_fail_when_rook_mg_collapsed_by_eval_mode() {
        // Rook MG = 362 replicates the eval-mode compression from issue #141 post-mortem.
        // Min bound is 430. Must fail via the mandatory material-bounds gate.
        double[] p = validParams.clone();
        p[6] = 362.0; // ROOK_MG index (params[6])

        TunerPostRunValidator.ValidatorConfig skipSmoke =
                new TunerPostRunValidator.ValidatorConfig(true, 100, 3, 0.30);

        TunerPostRunValidator.ValidationResult result =
                TunerPostRunValidator.validate(validParams, p, buildGoodMetrics(), skipSmoke);

        assertFalse(result.passed(),
                "Collapsed Rook MG=362 must fail material bounds: "
                + result.reportText());
        assertTrue(result.reportText().contains("Rook"),
                "Report must name the violating piece: " + result.reportText());
    }

    // =========================================================================
    // SmokeTestRunner LOS computation
    // =========================================================================

    @Test
    void los_is_0_5_when_wins_equal_losses() {
        double los = SmokeTestRunner.computeLos(50, 50);
        assertEquals(0.5, los, 1e-9);
    }

    @Test
    void los_above_0_5_when_wins_exceed_losses() {
        double los = SmokeTestRunner.computeLos(70, 30);
        assertTrue(los > 0.5, "LOS should be > 0.5 when wins > losses, got: " + los);
    }

    @Test
    void los_below_0_5_when_losses_exceed_wins() {
        double los = SmokeTestRunner.computeLos(20, 60);
        assertTrue(los < 0.5, "LOS should be < 0.5 when losses > wins, got: " + los);
    }

    @Test
    void los_approaches_1_on_overwhelming_win() {
        double los = SmokeTestRunner.computeLos(100, 0);
        assertTrue(los > 0.99, "LOS should approach 1.0 on 100-0, got: " + los);
    }

    @Test
    void los_returns_0_5_when_both_zero() {
        double los = SmokeTestRunner.computeLos(0, 0);
        assertEquals(0.5, los, 1e-9);
    }

    // =========================================================================
    // TunerRunMetrics ring buffer
    // =========================================================================

    @Test
    void metrics_mean_returns_nan_before_any_records() {
        TunerRunMetrics m = new TunerRunMetrics();
        assertTrue(Double.isNaN(m.meanRecentRelDelta(5)));
    }

    @Test
    void metrics_mean_uses_last_n_entries() {
        TunerRunMetrics m = new TunerRunMetrics();
        for (int i = 0; i < 25; i++) m.recordRelDelta(i * 0.001); // 0..0.024
        // After 25 records the ring holds the last 20 (indices 5..24 → values 0.005..0.024)
        double mean20 = m.meanRecentRelDelta(20);
        // Last 20 values: 0.005, 0.006 ... 0.024; mean = (0.005+0.024)/2 = 0.0145
        assertEquals(0.0145, mean20, 1e-9,
                "Expected mean of last 20 entries to be 0.0145, got: " + mean20);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private TunerRunMetrics buildGoodMetrics() {
        TunerRunMetrics m = new TunerRunMetrics();
        m.itersCompleted = 200;
        m.hitIterCap = false;
        m.minMse   = 0.020;
        m.finalMse = 0.021; // just 5 % above min — within 15 % threshold
        for (int i = 0; i < 20; i++) m.recordRelDelta(1e-6);
        return m;
    }
}
