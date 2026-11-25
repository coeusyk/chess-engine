package coeusyk.game.chess.core.eval;

import coeusyk.game.chess.core.models.Board;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * With kings on opposite flanks the shelter of the side under heavier attack is discounted. The discount is
 * meant for the pawn shield bonus. It must not soften the open-file penalty, which is already a penalty (#278).
 */
class OppositeFlankShelterTest {
    private static int whiteMinusBlack(String fen, int whiteAttackWeight, int blackAttackWeight, int scale) {
        int saved = EvalParams.OPPOSITE_FLANK_SHIELD_SCALE;
        try {
            EvalParams.OPPOSITE_FLANK_SHIELD_SCALE = scale;
            return KingSafety.evaluatePawnShieldAndFiles(new Board(fen), whiteAttackWeight, blackAttackWeight);
        } finally {
            EvalParams.OPPOSITE_FLANK_SHIELD_SCALE = saved;
        }
    }

    @Test
    void theDiscountDoesNotSoftenAnOpenFilePenalty() {
        // White king on c1 with no pawns anywhere: three open files, no shield. Black king is on the other flank
        // and its pieces attack harder, so white is the discounted side. The penalty must stay the same.
        String fen = "4k3/8/8/8/8/8/8/2K5 w - - 0 1";
        assertEquals(whiteMinusBlack(fen, 0, 5, 100), whiteMinusBlack(fen, 0, 5, 75));
    }

    @Test
    void theDiscountStillReducesAPawnShieldBonus() {
        // King b1 with pawns a2 b2 c2: shield only, 3 * SHIELD_RANK2. Discounted to 75 percent.
        String fen = "4k3/8/8/8/8/8/PPP5/1K6 w - - 0 1";
        int full = 3 * EvalParams.SHIELD_RANK2;
        assertEquals(full - full * 75 / 100, whiteMinusBlack(fen, 0, 5, 100) - whiteMinusBlack(fen, 0, 5, 75));
    }

    @Test
    void withBothShieldAndOpenFilesOnlyTheShieldIsDiscounted() {
        // King b1, pawn a2 only: one shield pawn, and open files on b and c.
        String fen = "4k3/8/8/8/8/8/P7/1K6 w - - 0 1";
        int shield = EvalParams.SHIELD_RANK2;
        assertEquals(shield - shield * 75 / 100, whiteMinusBlack(fen, 0, 5, 100) - whiteMinusBlack(fen, 0, 5, 75));
    }
}
