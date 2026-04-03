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
    void startposEvaluatesToZero() {
        // Starting position is perfectly symmetric; White-perspective score must be 0.
        Board startpos = new Board();
        assertEquals(0, TunerEvaluator.evaluate(startpos, defaultParams));
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
    void evalIndependentOfSideToMove() {
        // The evaluator is a static score — flipping STM on the same material balance
        // must produce an identical numeric result.
        Board whiteToMove = new Board(
                "rnb1kbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
        Board blackToMove = new Board(
                "rnb1kbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR b KQkq - 0 1");
        assertEquals(TunerEvaluator.evaluate(whiteToMove, defaultParams),
                     TunerEvaluator.evaluate(blackToMove, defaultParams),
                "Eval must not depend on the side to move");
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
    void computeMseZeroForSymmetricDrawnPositions() {
        // Drawn positions with eval=0 → sigmoid(0, k) = 0.5 == outcome → MSE = 0
        Board startpos = new Board();
        LabelledPosition drawnPos = new LabelledPosition(startpos, 0.5);
        List<LabelledPosition> positions = List.of(drawnPos, drawnPos, drawnPos);

        double mse = TunerEvaluator.computeMse(positions, defaultParams, 1.0);

        assertEquals(0.0, mse, 1e-12,
                "MSE must be 0 when startpos (eval=0) is labelled as a draw (outcome=0.5)");
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
}
