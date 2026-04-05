package coeusyk.game.chess.tuner;

import coeusyk.game.chess.core.models.Board;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CoordinateDescentTest {

    // Startpos evaluates to 0; drawn outcomes = 0.5 exactly match sigmoid(0, k) = 0.5,
    // so MSE = 0 at the very start. Any move from this state would only increase MSE.
    private static List<LabelledPosition> perfectlyDrawnPositions() {
        Board startpos = new Board();
        return List.of(
                new LabelledPosition(TunerPosition.from(startpos), 0.5),
                new LabelledPosition(TunerPosition.from(startpos), 0.5),
                new LabelledPosition(TunerPosition.from(startpos), 0.5)
        );
    }

    // Mixed dataset with some prediction error so the optimiser has something to improve.
    private static List<LabelledPosition> mixedOutcomePositions() {
        Board startpos = new Board();
        Board whiteUp = new Board("rnb1kbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
        Board blackUp = new Board("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNB1KBNR b KQkq - 0 1");
        return List.of(
                new LabelledPosition(TunerPosition.from(whiteUp),  1.0),   // White up a queen → White wins
                new LabelledPosition(TunerPosition.from(blackUp),  0.0),   // Black up a queen → Black wins
                new LabelledPosition(TunerPosition.from(startpos), 0.5),   // Equal → draw
                new LabelledPosition(TunerPosition.from(startpos), 0.5)
        );
    }

    @Test
    void inputArrayIsNotModified() {
        double[] params = EvalParams.extractFromCurrentEval();
        double[] snapshot = params.clone();

        CoordinateDescent.tune(perfectlyDrawnPositions(), params, 1.0, 1);

        assertArrayEquals(snapshot, params,
                "tune() must not modify the supplied params array");
    }

    @Test
    void returnedArrayHasSameLengthAsInput() {
        double[] params = EvalParams.extractFromCurrentEval();

        double[] result = CoordinateDescent.tune(perfectlyDrawnPositions(), params, 1.0, 1);

        assertEquals(params.length, result.length);
    }

    @Test
    void returnedArrayIsDifferentObjectFromInput() {
        double[] params = EvalParams.extractFromCurrentEval();

        double[] result = CoordinateDescent.tune(perfectlyDrawnPositions(), params, 1.0, 1);

        assertNotSame(params, result,
                "tune() must return a new array, not a reference to the input");
    }

    @Test
    void mseNonIncreasingAfterTuning() {
        double[] params = EvalParams.extractFromCurrentEval();
        double k = KFinder.findK(mixedOutcomePositions(), params);

        double mseBefore = TunerEvaluator.computeMse(mixedOutcomePositions(), params, k);
        double[] tuned   = CoordinateDescent.tune(mixedOutcomePositions(), params, k, 3);
        double mseAfter  = TunerEvaluator.computeMse(mixedOutcomePositions(), tuned, k);

        assertTrue(mseAfter <= mseBefore + 1e-9,
                "MSE after coordinate descent must be ≤ MSE before (" +
                        mseBefore + " → " + mseAfter + ")");
    }

    @Test
    void noRegressionOnDrawnPositions() {
        // With tempo, startpos eval is no longer zero, so MSE is not zero.
        // But the optimizer must not increase MSE from the starting point.
        double[] params = EvalParams.extractFromCurrentEval();
        double k = 1.0;

        double mseBefore = TunerEvaluator.computeMse(perfectlyDrawnPositions(), params, k);
        double[] tuned = CoordinateDescent.tune(perfectlyDrawnPositions(), params, k, 5);
        double mseFinal = TunerEvaluator.computeMse(perfectlyDrawnPositions(), tuned, k);

        assertTrue(mseFinal <= mseBefore + 1e-9,
                "MSE must not increase after tuning drawn positions (" +
                        mseBefore + " → " + mseFinal + ")");
    }
}
