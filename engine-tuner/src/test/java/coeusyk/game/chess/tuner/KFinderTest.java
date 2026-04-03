package coeusyk.game.chess.tuner;

import coeusyk.game.chess.core.models.Board;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KFinderTest {

    private static List<LabelledPosition> smallDataset() {
        Board startpos = new Board();
        // Mix of outcomes so K search has a meaningful signal to optimise
        return List.of(
                new LabelledPosition(startpos, 1.0),
                new LabelledPosition(startpos, 0.5),
                new LabelledPosition(startpos, 0.5),
                new LabelledPosition(startpos, 0.0)
        );
    }

    @Test
    void kIsInExpectedRange() {
        double[] params = EvalParams.extractFromCurrentEval();
        List<LabelledPosition> positions = smallDataset();

        double k = KFinder.findK(positions, params);

        assertTrue(k >= KFinder.K_MIN,
                () -> "K=" + k + " is below K_MIN=" + KFinder.K_MIN);
        assertTrue(k <= KFinder.K_MAX,
                () -> "K=" + k + " is above K_MAX=" + KFinder.K_MAX);
    }

    @Test
    void kIsDeterministic() {
        double[] params = EvalParams.extractFromCurrentEval();
        List<LabelledPosition> positions = smallDataset();

        double k1 = KFinder.findK(positions, params);
        double k2 = KFinder.findK(positions, params);

        assertEquals(k1, k2, 1e-12,
                "findK must be deterministic given the same inputs");
    }

    @Test
    void kMinimisesOrMatchesMseBrackets() {
        // The K returned must produce MSE ≤ MSE at boundary points,
        // confirming ternary search converged to a minimum in range.
        double[] params = EvalParams.extractFromCurrentEval();
        List<LabelledPosition> positions = smallDataset();

        double k = KFinder.findK(positions, params);
        double mseAtK    = TunerEvaluator.computeMse(positions, params, k);
        double mseAtMin  = TunerEvaluator.computeMse(positions, params, KFinder.K_MIN);
        double mseAtMax  = TunerEvaluator.computeMse(positions, params, KFinder.K_MAX);

        assertTrue(mseAtK <= mseAtMin + 1e-6,
                "MSE at optimal K must be ≤ MSE at K_MIN");
        assertTrue(mseAtK <= mseAtMax + 1e-6,
                "MSE at optimal K must be ≤ MSE at K_MAX");
    }
}
