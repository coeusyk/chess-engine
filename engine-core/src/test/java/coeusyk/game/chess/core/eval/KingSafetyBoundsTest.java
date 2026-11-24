package coeusyk.game.chess.core.eval;

import coeusyk.game.chess.core.models.Board;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** A negative ATK_WEIGHT_* override (--param-overrides) must not index the safety table below zero (#278). */
class KingSafetyBoundsTest {
    // White knight on g5 attacks the black king zone (f7 and h7).
    private static final String KNIGHT_ON_THE_KING = "6k1/5ppp/8/6N1/8/8/8/6K1 w - - 0 1";

    @Test
    void aNegativeAttackerWeightDoesNotThrowInTheSafetyTable() {
        assertEquals(0, KingSafety.safetyTablePenalty(-3));
    }

    @Test
    void aNegativeAttackWeightOverrideDoesNotBreakEvaluation() {
        int saved = EvalParams.ATK_WEIGHT_KNIGHT;
        try {
            EvalParams.ATK_WEIGHT_KNIGHT = -5;
            assertDoesNotThrow(() -> KingSafety.evaluate(new Board(KNIGHT_ON_THE_KING)));
            assertDoesNotThrow(() -> new Evaluator().evaluate(new Board(KNIGHT_ON_THE_KING)));
        } finally {
            EvalParams.ATK_WEIGHT_KNIGHT = saved;
        }
    }
}
