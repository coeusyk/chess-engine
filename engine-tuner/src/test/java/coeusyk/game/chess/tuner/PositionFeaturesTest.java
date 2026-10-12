package coeusyk.game.chess.tuner;

import coeusyk.game.chess.core.models.Board;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PositionFeatures} — forward eval and gradient wiring.
 *
 * <p>FEN used across tests: "4k3/8/8/8/8/5n2/8/4K3 w - - 0 1"
 * (Black knight on f3, White king on e1). In the a8=0 convention used by
 * this engine, f3 = square 45 and e1 = square 60.
 *
 * <p>The knight on f3 attacks d2 (sq 51) and e1 (sq 60), both of which lie
 * in {@code WHITE_KING_ZONE[60]}. With ATK_KNIGHT_DEFAULT=53 the safety
 * table is saturated (safetyEval(53) = 50), producing a large negative
 * contribution to the White-perspective eval, scaled by
 * {@code KING_SAFETY_SCALE / 100.0}.
 */
class PositionFeaturesTest {

    // FEN with one black attacker (knight) in white king zone. Phase = 1.
    private static final String KNIGHT_ATTACKS_KING_FEN =
            "4k3/8/8/8/8/5n2/8/4K3 w - - 0 1";

    private static double[] defaultParams;
    private static PositionFeatures pf;

    @BeforeAll
    static void setup() {
        defaultParams = EvalParams.extractFromCurrentEval();
        Board board   = new Board(KNIGHT_ATTACKS_KING_FEN);
        pf = TunerEvaluator.buildFeatures(TunerPosition.from(board), 0.5f);
    }

    // -----------------------------------------------------------------------
    // Gradient wiring
    // -----------------------------------------------------------------------

    /**
     * Core acceptance-criteria test for Issue #180.
     * KING_SAFETY_SCALE [830] must receive a non-zero gradient whenever there
     * is king-zone attacker pressure, so the Adam optimizer can move it.
     */
    @Test
    void kingSafetyScaleGradientIsNonZero() {
        double[] grad = new double[EvalParams.TOTAL_PARAMS];
        pf.accumulateGradient(grad, defaultParams, 1.0);

        assertNotEquals(0.0, grad[EvalParams.IDX_KING_SAFETY_SCALE],
                "KING_SAFETY_SCALE [830] gradient must be non-zero when there is "
                        + "king-zone attacker pressure (wN > 0)");
    }

    // -----------------------------------------------------------------------
    // Eval linearity in KING_SAFETY_SCALE
    // -----------------------------------------------------------------------

    /**
     * The safety contribution is linear in KING_SAFETY_SCALE:
     *   eval(scale + delta) − eval(scale) == constant for any scale.
     *
     * Verified by checking equal-step increments: [0→100] == [100→200].
     * This confirms the scale factor is a simple multiplicative scalar and is
     * correctly wired into {@link PositionFeatures#eval(double[])}.
     */
    @Test
    void kingSafetyScaleEval_isLinearInScale() {
        double[] paramsS0   = defaultParams.clone();
        double[] paramsS100 = defaultParams.clone();
        double[] paramsS200 = defaultParams.clone();
        paramsS0[EvalParams.IDX_KING_SAFETY_SCALE]   = 0.0;
        paramsS100[EvalParams.IDX_KING_SAFETY_SCALE] = 100.0;  // already the default
        paramsS200[EvalParams.IDX_KING_SAFETY_SCALE] = 200.0;

        double diff1 = pf.eval(paramsS100) - pf.eval(paramsS0);
        double diff2 = pf.eval(paramsS200) - pf.eval(paramsS100);

        assertEquals(diff1, diff2, 1e-9,
                "Safety contribution must be linear in KING_SAFETY_SCALE: "
                        + "[0→100] step must equal [100→200] step");
    }

    // -----------------------------------------------------------------------
    // Directional correctness
    // -----------------------------------------------------------------------

    /**
     * With a Black attacker on the White king zone (and no White attacker on
     * Black king zone), halving KING_SAFETY_SCALE must raise the White-perspective
     * eval (i.e., the penalty on White is smaller at scale=50 than at scale=100).
     *
     * <p>This confirms that the scale factor reduces attacker-pressure magnitude
     * in the correct direction.
     */
    @Test
    void kingSafetyScaleBelow100_reducesAttackerPenaltyForWhite() {
        double[] params100 = defaultParams.clone();
        double[] params50  = defaultParams.clone();
        params100[EvalParams.IDX_KING_SAFETY_SCALE] = 100.0;
        params50[EvalParams.IDX_KING_SAFETY_SCALE]  = 50.0;

        double eval100 = pf.eval(params100);
        double eval50  = pf.eval(params50);

        // Black attacker → safety penalty on White → negative contribution to White eval.
        // At scale=50 the magnitude is halved → White eval is HIGHER (less penalised).
        assertTrue(eval50 > eval100,
                "eval at KING_SAFETY_SCALE=50 must be greater than at KING_SAFETY_SCALE=100 "
                        + "when Black has attacker pressure on the White king zone");
    }
}
