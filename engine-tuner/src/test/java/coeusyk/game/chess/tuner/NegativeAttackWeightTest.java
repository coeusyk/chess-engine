package coeusyk.game.chess.tuner;

import coeusyk.game.chess.core.models.Board;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/** The tuner's king safety must also survive a negative attacker weight, as the core evaluator does (#278). */
class NegativeAttackWeightTest {
    @Test
    void aNegativeAttackerWeightDoesNotThrowInTheTunerEvaluator() {
        double[] params = EvalParams.extractFromCurrentEval();
        params[EvalParams.IDX_ATK_KNIGHT] = -5;
        assertDoesNotThrow(() -> TunerEvaluator.evaluateStatic(new Board("6k1/5ppp/8/6N1/8/8/8/6K1 w - - 0 1"), params));
    }
}
