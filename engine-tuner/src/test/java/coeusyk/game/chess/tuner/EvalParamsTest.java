package coeusyk.game.chess.tuner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EvalParamsTest {

    @Test
    void totalParamsIs831() {
        assertEquals(831, EvalParams.TOTAL_PARAMS);
    }

    @Test
    void extractedParamsHaveCorrectLength() {
        double[] params = EvalParams.extractFromCurrentEval();
        assertEquals(EvalParams.TOTAL_PARAMS, params.length);
    }

    @Test
    void pawnMgIsPinnedAt100() {
        assertEquals(100.0, EvalParams.PARAM_MIN[0], 1e-12);
        assertEquals(100.0, EvalParams.PARAM_MAX[0], 1e-12);
        double[] params = EvalParams.extractFromCurrentEval();
        assertEquals(100.0, params[0], 1e-12);
    }

    @Test
    void kingMaterialPinnedAtZero() {
        assertEquals(0.0, EvalParams.PARAM_MIN[10], 1e-12);
        assertEquals(0.0, EvalParams.PARAM_MAX[10], 1e-12);
        assertEquals(0.0, EvalParams.PARAM_MIN[11], 1e-12);
        assertEquals(0.0, EvalParams.PARAM_MAX[11], 1e-12);
    }

    @Test
    void materialValuesFloatFreelyExceptPawnMgAndKing() {
        // Indices 1 (P-EG), 2 (N-MG), 3 (N-EG), ..., 9 (Q-EG) should have min < max.
        for (int i = 1; i <= 9; i++) {
            assertTrue(EvalParams.PARAM_MIN[i] < EvalParams.PARAM_MAX[i],
                    "Material index " + i + " should have min < max (not frozen)");
        }
    }

    @Test
    void newTermIndicesAreCorrect() {
        assertEquals(812, EvalParams.IDX_TEMPO);
        assertEquals(813, EvalParams.IDX_BISHOP_PAIR_MG);
        assertEquals(814, EvalParams.IDX_BISHOP_PAIR_EG);
        assertEquals(815, EvalParams.IDX_ROOK_7TH_MG);
        assertEquals(816, EvalParams.IDX_ROOK_7TH_EG);
    }

    @Test
    void newTermInitialValuesArePositive() {
        double[] params = EvalParams.extractFromCurrentEval();
        assertTrue(params[EvalParams.IDX_TEMPO] > 0, "Tempo should be positive");
        assertTrue(params[EvalParams.IDX_BISHOP_PAIR_MG] > 0, "Bishop pair MG should be positive");
        assertTrue(params[EvalParams.IDX_BISHOP_PAIR_EG] > 0, "Bishop pair EG should be positive");
        assertTrue(params[EvalParams.IDX_ROOK_7TH_MG] >= 0, "Rook 7th MG should be non-negative");
        assertTrue(params[EvalParams.IDX_ROOK_7TH_EG] > 0, "Rook 7th EG should be positive");
    }

    @Test
    void newTermBoundsAreReasonable() {
        assertEquals(5.0,  EvalParams.PARAM_MIN[EvalParams.IDX_TEMPO], 1e-12);
        assertEquals(30.0, EvalParams.PARAM_MAX[EvalParams.IDX_TEMPO], 1e-12);
        assertEquals(15.0, EvalParams.PARAM_MIN[EvalParams.IDX_BISHOP_PAIR_MG], 1e-12);
        assertEquals(60.0, EvalParams.PARAM_MAX[EvalParams.IDX_BISHOP_PAIR_MG], 1e-12);
        assertEquals(15.0, EvalParams.PARAM_MIN[EvalParams.IDX_BISHOP_PAIR_EG], 1e-12);
        assertEquals(80.0, EvalParams.PARAM_MAX[EvalParams.IDX_BISHOP_PAIR_EG], 1e-12);
        assertEquals(0.0,  EvalParams.PARAM_MIN[EvalParams.IDX_ROOK_7TH_MG], 1e-12);
        assertEquals(50.0, EvalParams.PARAM_MAX[EvalParams.IDX_ROOK_7TH_MG], 1e-12);
        assertEquals(0.0,  EvalParams.PARAM_MIN[EvalParams.IDX_ROOK_7TH_EG], 1e-12);
        assertEquals(50.0, EvalParams.PARAM_MAX[EvalParams.IDX_ROOK_7TH_EG], 1e-12);
    }

    // -----------------------------------------------------------------------
    // enforceMaterialOrdering
    // -----------------------------------------------------------------------

    @Test
    void enforceMaterialOrdering_noChangeWhenAlreadyOrdered() {
        double[] params = EvalParams.extractFromCurrentEval();
        double[] before = params.clone();
        EvalParams.enforceMaterialOrdering(params);
        assertArrayEquals(before, params,
                "enforceMaterialOrdering should not change already-ordered params");
    }

    @Test
    void enforceMaterialOrdering_fixesViolationMg() {
        double[] params = EvalParams.extractFromCurrentEval();
        // Force knight MG (index 2) below pawn MG (index 0 = 100)
        params[2] = 50;
        EvalParams.enforceMaterialOrdering(params);
        assertTrue(params[2] > params[0],
                "Knight MG must be > Pawn MG after ordering enforcement");
        assertTrue(params[4] > params[2],
                "Bishop MG must be > Knight MG after ordering enforcement");
    }

    @Test
    void enforceMaterialOrdering_fixesViolationEg() {
        double[] params = EvalParams.extractFromCurrentEval();
        // Force knight EG (index 3) below pawn EG (index 1)
        params[3] = params[1] - 10;
        EvalParams.enforceMaterialOrdering(params);
        assertTrue(params[3] > params[1],
                "Knight EG must be > Pawn EG after ordering enforcement");
    }

    @Test
    void enforceMaterialOrdering_cascadesForward() {
        double[] params = EvalParams.extractFromCurrentEval();
        // Set all MG pieces to pawn value → cascading fix must restore ordering
        params[2] = 100;  // N-MG = P-MG
        params[4] = 100;  // B-MG = P-MG
        params[6] = 100;  // R-MG = P-MG
        params[8] = 100;  // Q-MG = P-MG
        EvalParams.enforceMaterialOrdering(params);
        assertTrue(params[0] < params[2], "P < N (MG)");
        assertTrue(params[2] < params[4], "N < B (MG)");
        assertTrue(params[4] < params[6], "B < R (MG)");
        assertTrue(params[6] < params[8], "R < Q (MG)");
    }

    @Test
    void clampOneRespectsMinMax() {
        assertEquals(EvalParams.PARAM_MIN[0], EvalParams.clampOne(0, -1000));
        assertEquals(EvalParams.PARAM_MAX[0], EvalParams.clampOne(0, 1000));
        // Tempo max is 30, so clamping 50 → 30
        assertEquals(30.0, EvalParams.clampOne(EvalParams.IDX_TEMPO, 50), 1e-12);
        // Value within range is unchanged
        assertEquals(15.0, EvalParams.clampOne(EvalParams.IDX_TEMPO, 15), 1e-12);
    }
}
