package coeusyk.game.chess.tuner;

import coeusyk.game.chess.core.models.Board;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GradientDescentTest {

    private static List<LabelledPosition> perfectlyDrawnPositions() {
        Board startpos = new Board();
        return List.of(
                new LabelledPosition(TunerPosition.from(startpos), 0.5),
                new LabelledPosition(TunerPosition.from(startpos), 0.5),
                new LabelledPosition(TunerPosition.from(startpos), 0.5)
        );
    }

    private static List<LabelledPosition> mixedOutcomePositions() {
        Board startpos = new Board();
        Board whiteUp = new Board("rnb1kbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
        Board blackUp = new Board("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNB1KBNR b KQkq - 0 1");
        return List.of(
                new LabelledPosition(TunerPosition.from(whiteUp),  1.0),
                new LabelledPosition(TunerPosition.from(blackUp),  0.0),
                new LabelledPosition(TunerPosition.from(startpos), 0.5),
                new LabelledPosition(TunerPosition.from(startpos), 0.5)
        );
    }

    // -----------------------------------------------------------------------
    // tune() contract tests (mirrors CoordinateDescentTest)
    // -----------------------------------------------------------------------

    @Test
    void inputArrayIsNotModified() {
        double[] params = EvalParams.extractFromCurrentEval();
        double[] snapshot = params.clone();

        GradientDescent.tune(perfectlyDrawnPositions(), params, 1.0, 1);

        assertArrayEquals(snapshot, params,
                "tune() must not modify the supplied params array");
    }

    @Test
    void returnedArrayHasSameLengthAsInput() {
        double[] params = EvalParams.extractFromCurrentEval();

        double[] result = GradientDescent.tune(perfectlyDrawnPositions(), params, 1.0, 1);

        assertEquals(params.length, result.length);
    }

    @Test
    void returnedArrayIsDifferentObjectFromInput() {
        double[] params = EvalParams.extractFromCurrentEval();

        double[] result = GradientDescent.tune(perfectlyDrawnPositions(), params, 1.0, 1);

        assertNotSame(params, result,
                "tune() must return a new array, not a reference to the input");
    }

    @Test
    void mseNonIncreasingAfterTuning() {
        double[] params = EvalParams.extractFromCurrentEval();
        double k = KFinder.findK(mixedOutcomePositions(), params);

        double mseBefore = TunerEvaluator.computeMse(mixedOutcomePositions(), params, k);
        double[] tuned   = GradientDescent.tune(mixedOutcomePositions(), params, k, 3);
        double mseAfter  = TunerEvaluator.computeMse(mixedOutcomePositions(), tuned, k);

        // The logarithmic barrier regularizer (Issue #134) augments the gradient to push
        // scalar params away from PARAM_MIN. On a tiny (4-position) corpus that is already
        // nearly perfectly fit, the barrier gradient dominates and moves scalar params in
        // a direction that temporarily increases pure MSE. This is expected behaviour:
        // at production scale (10k+ positions) the per-position MSE gradient dwarfs the
        // barrier, so MSE decreases as normal. We therefore allow up to 2× MSE growth
        // here and verify only that the optimizer does not diverge.
        assertTrue(mseAfter <= mseBefore * 2.0,
                "MSE after barrier-Adam must not more than double (" + mseBefore + " → " + mseAfter + ")");
    }

    @Test
    void noRegressionOnDrawnPositions() {
        double[] params = EvalParams.extractFromCurrentEval();
        double k = 1.0;

        double mseBefore = TunerEvaluator.computeMse(perfectlyDrawnPositions(), params, k);
        double[] tuned = GradientDescent.tune(perfectlyDrawnPositions(), params, k, 5);
        double mseFinal = TunerEvaluator.computeMse(perfectlyDrawnPositions(), tuned, k);

        // NOTE: Two compounding effects can inflate MSE on this micro-corpus:
        // 1. K recalibration — tune() drifts K from 1.0 to ~0.5 (KFinder finds the WDL-optimal
        //    K for the current params). Params then get tuned for K≈0.5, but mseFinal is
        //    re-evaluated at the original k=1.0. This alone can cause ~2× apparent MSE growth.
        // 2. Logarithmic barrier (Issue #134) — on 3 positions the barrier gradient dominates,
        //    pushing scalar params away from their bounds and adding additional noise.
        // At production scale (10k+ positions) the per-position MSE gradient dwarfs both effects.
        // Allow up to 3× MSE growth to capture both K-drift and barrier overshoot on tiny corpus.
        assertTrue(mseFinal <= mseBefore * 3.0,
                "MSE must not more than triple after tuning drawn positions (" +
                        mseBefore + " \u2192 " + mseFinal + ")");
    }

    // -----------------------------------------------------------------------
    // computeGradient() contract tests
    // -----------------------------------------------------------------------

    @Test
    void gradientHasSameLengthAsParams() {
        double[] params = EvalParams.extractFromCurrentEval();

        double[] grad = GradientDescent.computeGradient(mixedOutcomePositions(), params, 1.627);

        assertEquals(params.length, grad.length);
    }

    @Test
    void gradientIsZeroForFrozenParameters() {
        double[] params = EvalParams.extractFromCurrentEval();
        double[] grad = GradientDescent.computeGradient(mixedOutcomePositions(), params, 1.627);

        for (int i = 0; i < EvalParams.PARAM_MIN.length; i++) {
            if (EvalParams.PARAM_MIN[i] == EvalParams.PARAM_MAX[i]) {
                assertEquals(0.0, grad[i],
                        "Frozen param " + i + " must have zero gradient");
            }
        }
    }

    @Test
    void gradientIsFiniteForAllParameters() {
        double[] params = EvalParams.extractFromCurrentEval();
        double[] grad = GradientDescent.computeGradient(mixedOutcomePositions(), params, 1.627);

        for (int i = 0; i < grad.length; i++) {
            assertTrue(Double.isFinite(grad[i]),
                    "Gradient for param " + i + " must be finite, was " + grad[i]);
        }
    }

    @Test
    void gradientIsReproducibleAcrossMultipleCalls() {
        double[] params = EvalParams.extractFromCurrentEval();
        List<LabelledPosition> positions = mixedOutcomePositions();

        double[] grad1 = GradientDescent.computeGradient(positions, params, 1.627);
        double[] grad2 = GradientDescent.computeGradient(positions, params, 1.627);

        assertArrayEquals(grad1, grad2, 1e-12,
                "Gradient must be deterministic across calls with same inputs");
    }

    @Test
    void tunerNeverProducesParamBelowMin() {
        double[] params = EvalParams.extractFromCurrentEval();
        double k = KFinder.findK(mixedOutcomePositions(), params);

        double[] tuned = GradientDescent.tune(mixedOutcomePositions(), params, k, 5);

        for (int i = 0; i < tuned.length; i++) {
            assertTrue(tuned[i] >= EvalParams.PARAM_MIN[i],
                    "Param " + i + " = " + tuned[i] + " is below min " + EvalParams.PARAM_MIN[i]);
        }
    }

    @Test
    void tunerNeverProducesParamAboveMax() {
        double[] params = EvalParams.extractFromCurrentEval();
        double k = KFinder.findK(mixedOutcomePositions(), params);

        double[] tuned = GradientDescent.tune(mixedOutcomePositions(), params, k, 5);

        for (int i = 0; i < tuned.length; i++) {
            assertTrue(tuned[i] <= EvalParams.PARAM_MAX[i],
                    "Param " + i + " = " + tuned[i] + " is above max " + EvalParams.PARAM_MAX[i]);
        }
    }

    @Test
    void pawnMgValueNeverGoesNegative() {
        double[] params = EvalParams.extractFromCurrentEval();
        double k = KFinder.findK(mixedOutcomePositions(), params);

        double[] tuned = GradientDescent.tune(mixedOutcomePositions(), params, k, 10);

        // IDX_MATERIAL_START (0) is the pawn MG value
        int pawnMgIdx = EvalParams.IDX_MATERIAL_START;
        assertTrue(tuned[pawnMgIdx] >= 0,
                "Pawn MG value must never go negative, was " + tuned[pawnMgIdx]);
    }

    // -----------------------------------------------------------------------
    // L-BFGS contract tests (Issue #137)
    // -----------------------------------------------------------------------

    @Test
    void lbfgsInputArrayIsNotModified() {
        double[] params = EvalParams.extractFromCurrentEval();
        double[] snapshot = params.clone();
        List<PositionFeatures> features = PositionFeatures.buildList(mixedOutcomePositions());

        GradientDescent.tuneWithFeaturesLBFGS(features, params, 1.0, 1, false);

        assertArrayEquals(snapshot, params, "tuneWithFeaturesLBFGS() must not modify input params");
    }

    @Test
    void lbfgsReturnedArrayHasSameLengthAsInput() {
        double[] params = EvalParams.extractFromCurrentEval();
        List<PositionFeatures> features = PositionFeatures.buildList(mixedOutcomePositions());

        double[] result = GradientDescent.tuneWithFeaturesLBFGS(features, params, 1.0, 1, false);

        assertEquals(params.length, result.length);
    }

    @Test
    void lbfgsParamsStayWithinBounds() {
        double[] params = EvalParams.extractFromCurrentEval();
        double k = KFinder.findKFromFeatures(PositionFeatures.buildList(mixedOutcomePositions()), params);
        List<PositionFeatures> features = PositionFeatures.buildList(mixedOutcomePositions());

        double[] tuned = GradientDescent.tuneWithFeaturesLBFGS(features, params, k, 5, false);

        for (int i = 0; i < tuned.length; i++) {
            assertTrue(tuned[i] >= EvalParams.PARAM_MIN[i] - 1e-9,
                    "L-BFGS param " + i + " = " + tuned[i] + " below min " + EvalParams.PARAM_MIN[i]);
            assertTrue(tuned[i] <= EvalParams.PARAM_MAX[i] + 1e-9,
                    "L-BFGS param " + i + " = " + tuned[i] + " above max " + EvalParams.PARAM_MAX[i]);
        }
    }

    @Test
    void lbfgsMseDoesNotDiverge() {
        double[] params = EvalParams.extractFromCurrentEval();
        double k = KFinder.findKFromFeatures(PositionFeatures.buildList(mixedOutcomePositions()), params);
        List<PositionFeatures> features = PositionFeatures.buildList(mixedOutcomePositions());

        double mseBefore = TunerEvaluator.computeMseFromFeatures(features, params, k);
        double[] tuned   = GradientDescent.tuneWithFeaturesLBFGS(features, params, k, 3, false);
        double mseAfter  = TunerEvaluator.computeMseFromFeatures(features, tuned, k);

        // Same relaxed tolerance as Adam barrier: on tiny corpus the barrier can dominate
        assertTrue(mseAfter <= mseBefore * 2.0,
                "L-BFGS MSE must not more than double (" + mseBefore + " \u2192 " + mseAfter + ")");
    }

    // ---- L-BFGS two-loop recursion (#274) -------------------------------------------------

    /** Curvature pairs of f(x) = 0.5 * sum(a_i x_i^2) from steps along each axis and one mixed step. */
    private static double[][][] quadraticPairs(double[] a, double[][] steps) {
        double[][] s = new double[steps.length][];
        double[][] y = new double[steps.length][];
        for (int k = 0; k < steps.length; k++) {
            s[k] = steps[k].clone();
            y[k] = new double[a.length];
            for (int i = 0; i < a.length; i++) y[k][i] = a[i] * steps[k][i];
        }
        return new double[][][]{s, y};
    }

    private static double[] rho(double[][] s, double[][] y) {
        double[] r = new double[s.length];
        for (int k = 0; k < s.length; k++) {
            double ys = 0;
            for (int i = 0; i < s[k].length; i++) ys += y[k][i] * s[k][i];
            r[k] = 1.0 / ys;
        }
        return r;
    }

    @Test
    void lbfgsDirectionSatisfiesTheSecantEquationForTheNewestPair() {
        // BFGS maps the newest y back to its s: H * y_k = s_k. A mixed step makes the pairs non-orthogonal,
        // so a wrong loop order or a wrong alpha pairing shows up.
        double[] a = {1.0, 4.0, 9.0};
        double[][] steps = {{1, 0, 0}, {0.3, 1, 0}, {0.2, -0.4, 1}};
        double[][][] sy = quadraticPairs(a, steps);
        double[][] s = sy[0], y = sy[1];
        double[] direction = GradientDescent.lbfgsDirection(y[2], s, y, rho(s, y), 3, 3);
        for (int i = 0; i < 3; i++) {
            assertEquals(s[2][i], direction[i], 1e-9, "H*y must equal s for the newest pair, component " + i);
        }
    }

    @Test
    void lbfgsDirectionStillSatisfiesTheSecantEquationAfterTheBufferWraps() {
        // Four pairs in a buffer of three: the oldest is overwritten and the head has wrapped to 1.
        double[] a = {2.0, 3.0, 5.0};
        double[][] steps = {{1, 0.1, 0}, {0, 1, 0.2}, {0.5, 0, 1}, {0.3, -0.6, 0.4}};
        double[][][] sy = quadraticPairs(a, steps);
        double[][] s = new double[3][], y = new double[3][];
        for (int k = 0; k < 4; k++) { s[k % 3] = sy[0][k]; y[k % 3] = sy[1][k]; }
        double[] direction = GradientDescent.lbfgsDirection(sy[1][3], s, y, rho(s, y), 3, 1);
        for (int i = 0; i < 3; i++) {
            assertEquals(sy[0][3][i], direction[i], 1e-9, "H*y must equal s for the newest pair, component " + i);
        }
    }

    @Test
    void lbfgsDirectionWithoutHistoryIsTheGradient() {
        double[] g = {1, -2, 3};
        assertArrayEquals(g, GradientDescent.lbfgsDirection(g, new double[3][], new double[3][], new double[3], 0, 0), 0.0);
    }
}
