package coeusyk.game.chess.tuner;

import coeusyk.game.chess.core.models.Board;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TunerEvaluatorTest {

    // Default params extracted from the live evaluator constants
    private static double[] defaultParams;

    @BeforeAll
    static void loadParams() {
        defaultParams = EvalParams.extractFromCurrentEval();
    }

    // -----------------------------------------------------------------------
    // sigmoid
    // -----------------------------------------------------------------------

    @Test
    void sigmoidAtZeroIsHalf() {
        // σ(0, k) = 1 / (1 + 10^0) = 0.5 for any k
        assertEquals(0.5, TunerEvaluator.sigmoid(0, 1.0),   1e-12);
        assertEquals(0.5, TunerEvaluator.sigmoid(0, 0.5),   1e-12);
        assertEquals(0.5, TunerEvaluator.sigmoid(0, 3.0),   1e-12);
    }

    @Test
    void sigmoidAt400k1ApproachesNinetyPercent() {
        // σ(400, 1.0) = 1 / (1 + 10^-1) = 1/1.1 ≈ 0.9090909…
        assertEquals(10.0 / 11.0, TunerEvaluator.sigmoid(400, 1.0), 1e-9);
    }

    @Test
    void sigmoidIsMonotonicallyIncreasing() {
        double k = 1.0;
        double prev = TunerEvaluator.sigmoid(-500, k);
        for (int eval = -400; eval <= 500; eval += 100) {
            double cur = TunerEvaluator.sigmoid(eval, k);
            assertTrue(cur > prev, "sigmoid should be strictly increasing");
            prev = cur;
        }
    }

    @Test
    void sigmoidSymmetryAroundZero() {
        // σ(s) + σ(-s) == 1 for all s
        double k = 1.5;
        for (int eval : new int[]{100, 200, 300, 400}) {
            assertEquals(1.0,
                    TunerEvaluator.sigmoid(eval, k) + TunerEvaluator.sigmoid(-eval, k),
                    1e-12);
        }
    }

    // -----------------------------------------------------------------------
    // evaluate — basic properties
    // -----------------------------------------------------------------------

    @Test
    void startposEvaluatesToTempo() {
        // Starting position is symmetric except for tempo: White to move gets +tempo.
        Board startpos = new Board();
        int tempo = (int) defaultParams[EvalParams.IDX_TEMPO];
        assertEquals(tempo, TunerEvaluator.evaluate(startpos, defaultParams),
                "Startpos eval should equal the tempo bonus (White to move)");
    }

    @Test
    void evalIsAlwaysWhitePerspective_whiteSideToMove() {
        // Black is missing her queen; White to move — White should have a positive score.
        Board blackMissingQueenWhiteToMove = new Board(
                "rnb1kbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
        assertTrue(TunerEvaluator.evaluate(blackMissingQueenWhiteToMove, defaultParams) > 0,
                "White-perspective score should be positive when Black is missing her queen");
    }

    @Test
    void evalIsAlwaysWhitePerspective_blackSideToMove() {
        // Same material imbalance; Black to move this time — score must still be positive.
        Board blackMissingQueenBlackToMove = new Board(
                "rnb1kbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR b KQkq - 0 1");
        assertTrue(TunerEvaluator.evaluate(blackMissingQueenBlackToMove, defaultParams) > 0,
                "White-perspective score must stay positive regardless of side to move");
    }

    @Test
    void evalDiffersBySideToMoveByTwiceTempo() {
        // With tempo bonus, flipping STM shifts the eval by 2×tempo:
        // White to move: +tempo, Black to move: −tempo → difference = 2×tempo.
        Board whiteToMove = new Board(
                "rnb1kbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
        Board blackToMove = new Board(
                "rnb1kbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR b KQkq - 0 1");
        int tempo = (int) defaultParams[EvalParams.IDX_TEMPO];
        int diff = TunerEvaluator.evaluate(whiteToMove, defaultParams)
                 - TunerEvaluator.evaluate(blackToMove, defaultParams);
        assertEquals(2 * tempo, diff,
                "Flipping STM should shift eval by 2×tempo");
    }

    @Test
    void evalSymmetry_mirroredPositions() {
        // For mirror positions (colors swapped, ranks flipped):
        // evaluate(pos) == -evaluate(mirror(pos))
        //
        // pos  : Black missing queen, White to move → positive score
        // mirror: White missing queen, Black to move → negative score (same absolute value)
        Board blackMissingQueen = new Board(
                "rnb1kbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
        Board whiteMissingQueen = new Board(
                "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNB1KBNR b KQkq - 0 1");

        int scoreA = TunerEvaluator.evaluate(blackMissingQueen, defaultParams);
        int scoreB = TunerEvaluator.evaluate(whiteMissingQueen, defaultParams);

        assertEquals(scoreA, -scoreB,
                "evaluate(pos) must equal -evaluate(mirror(pos)) for mirrored positions");
    }

    @Test
    void evalPositiveWhenWhiteHasExtraRook() {
        // Simpler material imbalance — White has an extra rook.
        Board whiteExtraRook = new Board(
                "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBR1 w KQkq - 0 1");
        // Note: second rook replaced with empty — actually let's use a position where
        // White clearly has more material on an otherwise empty board.
        Board whiteRookUp = new Board(
                "4k3/8/8/8/8/8/8/4K2R w K - 0 1");
        assertTrue(TunerEvaluator.evaluate(whiteRookUp, defaultParams) > 0);
    }

    // -----------------------------------------------------------------------
    // computeMse
    // -----------------------------------------------------------------------

    @Test
    void computeMseSmallForSymmetricDrawnPositions() {
        // With tempo bonus, startpos eval is ~15 rather than 0, so MSE for draws
        // is no longer exactly zero. But it should be very small.
        Board startpos = new Board();
        LabelledPosition drawnPos = new LabelledPosition(startpos, 0.5);
        List<LabelledPosition> positions = List.of(drawnPos, drawnPos, drawnPos);

        double mse = TunerEvaluator.computeMse(positions, defaultParams, 1.0);

        assertTrue(mse < 0.01,
                "MSE for drawn startpos positions should be very small, got " + mse);
    }

    @Test
    void computeMseInRange() {
        // MSE is always in [0, 1] by construction (error is at most 1.0, squared ≤ 1.0).
        Board startpos = new Board();
        List<LabelledPosition> positions = List.of(
                new LabelledPosition(startpos, 1.0),   // wrong prediction → non-zero error
                new LabelledPosition(startpos, 0.0),   // wrong prediction → non-zero error
                new LabelledPosition(startpos, 0.5)    // correct prediction → zero error
        );

        double mse = TunerEvaluator.computeMse(positions, defaultParams, 1.0);

        assertTrue(mse >= 0.0, "MSE must be non-negative");
        assertTrue(mse <= 1.0, "MSE must be at most 1.0");
    }

    @Test
    void computeMseEmptyPositionsReturnsZero() {
        double mse = TunerEvaluator.computeMse(List.of(), defaultParams, 1.0);
        assertEquals(0.0, mse, 1e-12);
    }

    // -----------------------------------------------------------------------
    // bishop pair bonus
    // -----------------------------------------------------------------------

    @Test
    void bishopPairBonusAppliesToSideWithTwoBishops() {
        // White has both bishops, Black has only one → White gets bishop pair bonus.
        Board whitePair = new Board("rnb1k2r/pppppppp/8/8/8/8/PPPPPPPP/RNBQKB1R w KQkq - 0 1");
        // Baseline: same position but White also has only one bishop.
        Board noPair = new Board("rn2k2r/pppppppp/8/8/8/8/PPPPPPPP/RN1QKB1R w Kkq - 0 1");

        int evalPair   = TunerEvaluator.evaluate(whitePair, defaultParams);
        int evalNoPair = TunerEvaluator.evaluate(noPair, defaultParams);

        // White bishop pair bonus should make this score higher.
        // Both positions have White to move, so tempo is the same.
        assertTrue(evalPair > evalNoPair,
                "Position with two White bishops should score higher than one bishop");
    }

    // -----------------------------------------------------------------------
    // rook on 7th rank bonus
    // -----------------------------------------------------------------------

    @Test
    void rookOnSeventhRankGivesBonus() {
        // White rook on e7 (7th rank for White) vs White rook on e4.
        Board rookOn7th = new Board("4k3/4R3/8/8/8/8/8/4K3 w - - 0 1");
        Board rookOn4th = new Board("4k3/8/8/8/4R3/8/8/4K3 w - - 0 1");

        int evalOn7th = TunerEvaluator.evaluate(rookOn7th, defaultParams);
        int evalOn4th = TunerEvaluator.evaluate(rookOn4th, defaultParams);

        assertTrue(evalOn7th > evalOn4th,
                "Rook on 7th rank should score higher than rook on 4th rank");
    }

    // -----------------------------------------------------------------------
    // tempo bonus
    // -----------------------------------------------------------------------

    @Test
    void tempoIsPositiveForWhiteToMove() {
        Board startpos = new Board();
        int eval = TunerEvaluator.evaluate(startpos, defaultParams);
        assertTrue(eval > 0,
                "Startpos (White to move) should have positive eval due to tempo");
    }

    @Test
    void tempoIsNegativeForBlackToMove() {
        Board startBlack = new Board(
                "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR b KQkq - 0 1");
        int eval = TunerEvaluator.evaluate(startBlack, defaultParams);
        assertTrue(eval < 0,
                "Startpos (Black to move) should have negative eval due to tempo");
    }
}
