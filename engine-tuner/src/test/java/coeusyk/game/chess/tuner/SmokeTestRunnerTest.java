package coeusyk.game.chess.tuner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The smoke test decides which parameter set plays each move and who won a mate from the side to move.
 * Piece.White is 8, not 0, so reading the colour as {@code == 0} made every side look like Black (#274).
 */
class SmokeTestRunnerTest {
    private static final String WHITE_MATES_IN_ONE = "7k/5Q2/6K1/8/8/8/8/8 w - - 0 1";
    private static final String BLACK_MATES_IN_ONE = "8/8/8/8/8/6k1/5q2/7K b - - 0 1";

    private static SmokeTestRunner.GameOutcome play(String fen, boolean candidateIsWhite) {
        double[] params = EvalParams.extractFromCurrentEval();
        return SmokeTestRunner.playGame(fen, params, params, candidateIsWhite, 2);
    }

    @Test
    void whiteMatingInOneIsAWinForWhite() {
        assertEquals(SmokeTestRunner.GameOutcome.CANDIDATE_WIN, play(WHITE_MATES_IN_ONE, true));
        assertEquals(SmokeTestRunner.GameOutcome.BASELINE_WIN, play(WHITE_MATES_IN_ONE, false));
    }

    @Test
    void blackMatingInOneIsAWinForBlack() {
        assertEquals(SmokeTestRunner.GameOutcome.CANDIDATE_WIN, play(BLACK_MATES_IN_ONE, false));
        assertEquals(SmokeTestRunner.GameOutcome.BASELINE_WIN, play(BLACK_MATES_IN_ONE, true));
    }
}
