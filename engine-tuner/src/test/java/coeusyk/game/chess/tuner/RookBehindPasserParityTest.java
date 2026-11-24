package coeusyk.game.chess.tuner;

import coeusyk.game.chess.core.models.Board;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The tuner must read "passed" the same way as the core evaluator for the rook-behind-passer term (#278). */
class RookBehindPasserParityTest {
    private static int bonus(String fen) {
        double[] with = EvalParams.extractFromCurrentEval();
        double[] without = with.clone();
        without[EvalParams.IDX_ROOK_BEHIND_PASSER_MG] = 0;
        without[EvalParams.IDX_ROOK_BEHIND_PASSER_EG] = 0;
        return TunerEvaluator.evaluateStatic(new Board(fen), with) - TunerEvaluator.evaluateStatic(new Board(fen), without);
    }

    @Test
    void anAdjacentPawnThatCanCaptureMeansNoBonusForEitherColour() {
        assertEquals(0, bonus("4k3/8/2p5/3P4/8/8/8/3RK3 w - - 0 1"));
        assertEquals(0, bonus("3rk3/8/8/8/3p4/2P5/8/4K3 b - - 0 1"));
    }

    @Test
    void aRealPassedPawnStillGetsTheBonus() {
        assertTrue(bonus("4k3/8/1p6/3P4/8/8/8/3RK3 w - - 0 1") > 0);
        assertTrue(bonus("3rk3/8/8/8/3p4/1P6/8/4K3 b - - 0 1") < 0);
    }

    private static float featureCount(String fen) {
        float[] tmp = new float[EvalParams.TOTAL_PARAMS];
        TunerEvaluator.addRookBehindPasserFeatures(TunerPosition.from(new Board(fen)), tmp, 1f, 1f);
        return tmp[EvalParams.IDX_ROOK_BEHIND_PASSER_MG];
    }

    @Test
    void theFeatureExtractorUsesTheSamePredicate() {
        assertEquals(0f, featureCount("4k3/8/2p5/3P4/8/8/8/3RK3 w - - 0 1"));
        assertEquals(1f, featureCount("4k3/8/1p6/3P4/8/8/8/3RK3 w - - 0 1"));
        assertEquals(0f, featureCount("3rk3/8/8/8/3p4/2P5/8/4K3 b - - 0 1"));
        assertEquals(-1f, featureCount("3rk3/8/8/8/3p4/1P6/8/4K3 b - - 0 1"));
    }
}
