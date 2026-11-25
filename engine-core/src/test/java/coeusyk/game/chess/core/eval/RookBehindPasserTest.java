package coeusyk.game.chess.core.eval;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Piece;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rook-behind-passer bonus is for a rook behind a passed pawn. A pawn that an enemy pawn on an adjacent file
 * can still capture is not passed, so it gets no bonus, whatever the file says (#278).
 */
class RookBehindPasserTest {
    private static final EvalConfig NO_BONUS = new EvalConfig(
            Evaluator.DEFAULT_CONFIG.bishopPairMg(), Evaluator.DEFAULT_CONFIG.bishopPairEg(),
            Evaluator.DEFAULT_CONFIG.rook7thMg(), Evaluator.DEFAULT_CONFIG.rook7thEg(),
            Evaluator.DEFAULT_CONFIG.rookOpenFileMg(), Evaluator.DEFAULT_CONFIG.rookOpenFileEg(),
            Evaluator.DEFAULT_CONFIG.rookSemiOpenFileMg(), Evaluator.DEFAULT_CONFIG.rookSemiOpenFileEg(),
            Evaluator.DEFAULT_CONFIG.knightOutpostMg(), Evaluator.DEFAULT_CONFIG.knightOutpostEg(),
            Evaluator.DEFAULT_CONFIG.connectedPawnMg(), Evaluator.DEFAULT_CONFIG.connectedPawnEg(),
            Evaluator.DEFAULT_CONFIG.backwardPawnMg(), Evaluator.DEFAULT_CONFIG.backwardPawnEg(),
            0, 0);

    private static int whitePov(Evaluator evaluator, String fen) {
        Board board = new Board(fen);
        int score = evaluator.evaluate(board);
        return Piece.isWhite(board.getActiveColor()) ? score : -score;
    }

    /** White-point-of-view contribution of the rook-behind-passer term alone. */
    private static int bonus(String fen) {
        return whitePov(new Evaluator(), fen) - whitePov(new Evaluator(NO_BONUS), fen);
    }

    @Test
    void whiteRookBehindAPawnThatAnAdjacentPawnCanCaptureGetsNoBonus() {
        assertEquals(0, bonus("4k3/8/2p5/3P4/8/8/8/3RK3 w - - 0 1"), "c6 can capture on d5, so d5 is not passed");
    }

    @Test
    void whiteRookBehindARealPassedPawnStillGetsTheBonus() {
        assertTrue(bonus("4k3/8/1p6/3P4/8/8/8/3RK3 w - - 0 1") > 0, "b6 cannot stop d5, so d5 is passed");
    }

    @Test
    void blackRookBehindAPawnThatAnAdjacentPawnCanCaptureGetsNoBonus() {
        assertEquals(0, bonus("3rk3/8/8/8/3p4/2P5/8/4K3 b - - 0 1"), "c3 can capture on d4, so d4 is not passed");
    }

    @Test
    void blackRookBehindARealPassedPawnStillGetsTheBonus() {
        assertTrue(bonus("3rk3/8/8/8/3p4/1P6/8/4K3 b - - 0 1") < 0, "b3 cannot stop d4, so d4 is passed");
    }
}
