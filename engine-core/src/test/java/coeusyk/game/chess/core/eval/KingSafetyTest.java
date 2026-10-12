package coeusyk.game.chess.core.eval;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for the {@link KingSafety#SAFETY_TABLE} extension (Phase 15
 * prerequisite, phase-14.md "King Safety Retune Postmortem"). The original 18-entry
 * table saturated at attacker weight &gt;= 17, giving the Texel tuner a zero gradient
 * for any ATK weight combination that pushed the attacker-weight sum past that point.
 * The table was extended to 32 entries so weights in the 18-31 range remain on the
 * non-flat part of the curve.
 */
class KingSafetyTest {

    @Test
    void safetyTablePenaltyIsNonSaturatingAtFormerPlateauWeights() {
        int p17 = KingSafety.safetyTablePenalty(17);
        int p20 = KingSafety.safetyTablePenalty(20);
        int p25 = KingSafety.safetyTablePenalty(25);
        int p30 = KingSafety.safetyTablePenalty(30);
        int p31 = KingSafety.safetyTablePenalty(31);

        assertTrue(p20 > p17,
                "penalty(20) must exceed penalty(17) — w=17 was the old table's saturation point");
        assertTrue(p25 > p20,
                "penalty(25) must exceed penalty(20) — w=20..31 must not be flat");
        assertTrue(p30 > p25,
                "penalty(30) must exceed penalty(25) — w=20..31 must not be flat");
        assertTrue(p31 > p30,
                "penalty(31) must exceed penalty(30) — the new table's last non-plateau step");
    }

    @Test
    void safetyTablePenaltyStillPlateausBeyondTableLength() {
        int atLastIndex = KingSafety.safetyTablePenalty(31);
        int wayBeyond = KingSafety.safetyTablePenalty(1000);

        assertTrue(wayBeyond == atLastIndex,
                "weights beyond the table length must clamp to the final entry, not throw or grow unbounded");
    }
}
