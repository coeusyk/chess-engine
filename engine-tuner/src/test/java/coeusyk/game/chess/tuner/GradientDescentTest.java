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

        assertTrue(mseAfter <= mseBefore + 1e-9,
                "MSE after Adam must be ≤ MSE before (" + mseBefore + " → " + mseAfter + ")");
    }

    @Test
    void noRegressionOnDrawnPositions() {
        double[] params = EvalParams.extractFromCurrentEval();
        double k = 1.0;

        double mseBefore = TunerEvaluator.computeMse(perfectlyDrawnPositions(), params, k);
        double[] tuned = GradientDescent.tune(perfectlyDrawnPositions(), params, k, 5);
        double mseFinal = TunerEvaluator.computeMse(perfectlyDrawnPositions(), tuned, k);

        assertTrue(mseFinal <= mseBefore + 1e-9,
                "MSE must not increase after tuning drawn positions (" +
                        mseBefore + " → " + mseFinal + ")");
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
}
