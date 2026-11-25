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

    @Test
    void rejectedMaterialTrialLeavesTheOrderingNeighbourUntouched() {
        // No knights or bishops on the board, so changing either value never changes the MSE and every
        // trial of them is rejected. With the knight one below the bishop, trying knight + 1 makes
        // enforceMaterialOrdering push the bishop up. Undoing only the knight used to leave the bishop moved.
        List<LabelledPosition> positions = List.of(
                new LabelledPosition(TunerPosition.from(new Board("4k3/pppp4/8/8/8/8/PPPP4/R3K2R w - - 0 1")), 1.0),
                new LabelledPosition(TunerPosition.from(new Board("r3k2r/pppp4/8/8/8/8/PPPP4/4K3 b - - 0 1")), 0.0));
        double[] params = EvalParams.extractFromCurrentEval();
        params[2] = 390;   // knight MG
        params[4] = 391;   // bishop MG, one above the knight
        double[] before = params.clone();

        double[] tuned = CoordinateDescent.tune(positions, params, 1.0, 1, false);

        assertEquals(before[2], tuned[2], "knight MG must not move, it cannot change the MSE");
        assertEquals(before[4], tuned[4], "bishop MG must not be left raised by a rejected knight trial");
    }

    @Test
    void convergedRunRecordsMetricsWithoutHittingTheCap() {
        TunerRunMetrics metrics = new TunerRunMetrics();
        double[] params = EvalParams.extractFromCurrentEval();
        // Fully tuned drawn positions: after enough passes no single step improves the MSE, so the run converges.
        double[] tuned = CoordinateDescent.tune(perfectlyDrawnPositions(), params, 1.0, 200, false, metrics);

        assertTrue(metrics.itersCompleted >= 1);
        assertFalse(Double.isNaN(metrics.finalMse));
        assertEquals(metrics.itersCompleted < 200, !metrics.hitIterCap);
        assertEquals(params.length, tuned.length);
    }
}
