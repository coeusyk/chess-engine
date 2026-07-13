package coeusyk.game.chess.core.eval.nnue;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Piece;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * PR C-1: {@link NnueEvaluator#verifyAgainstRebuild(Board)} must report NONE when the live
 * accumulator matches a from-scratch rebuild, and must report the exact diverging index when
 * it doesn't — the mechanism PR C-3's automatic desync assertion will later build on.
 */
class NnueEvaluatorRebuildComparatorTest {

    @Test
    void reportsNoneWhenAccumulatorMatchesRebuild() {
        NnueNetwork network = TestNetworks.synthetic(8);
        NnueEvaluator evaluator = new NnueEvaluator(network);
        evaluator.reset(new Board());

        assertEquals(NnueEvaluator.RebuildDiff.NONE, evaluator.verifyAgainstRebuild(new Board()));
    }

    @Test
    void reportsExactDivergingIndexAndDeltaOnWhitePerspectiveCorruption() {
        NnueNetwork network = TestNetworks.synthetic(8);
        NnueEvaluator evaluator = new NnueEvaluator(network);
        Board board = new Board();
        evaluator.reset(board);

        evaluator.corruptForTest(Piece.White, 3, (short) 7);
        NnueEvaluator.RebuildDiff diff = evaluator.verifyAgainstRebuild(board);

        assertFalse(diff.matches(), "corrupted accumulator must not match rebuild");
        assertEquals(Piece.White, diff.perspectiveColor());
        assertEquals(3, diff.firstDivergingIndex());
        assertEquals(7, diff.delta());
    }

    @Test
    void reportsExactDivergingIndexAndDeltaOnBlackPerspectiveCorruption() {
        NnueNetwork network = TestNetworks.synthetic(8);
        NnueEvaluator evaluator = new NnueEvaluator(network);
        Board board = new Board();
        evaluator.reset(board);

        evaluator.corruptForTest(Piece.Black, 5, (short) -4);
        NnueEvaluator.RebuildDiff diff = evaluator.verifyAgainstRebuild(board);

        assertFalse(diff.matches(), "corrupted accumulator must not match rebuild");
        assertEquals(Piece.Black, diff.perspectiveColor());
        assertEquals(5, diff.firstDivergingIndex());
        assertEquals(-4, diff.delta());
    }

    @Test
    void verifyAgainstRebuildNeverMutatesLiveAccumulator() {
        NnueNetwork network = TestNetworks.synthetic(8);
        NnueEvaluator evaluator = new NnueEvaluator(network);
        Board board = new Board();
        evaluator.reset(board);

        short[] whiteBefore = evaluator.currentAccumulator(Piece.White).clone();
        short[] blackBefore = evaluator.currentAccumulator(Piece.Black).clone();

        evaluator.verifyAgainstRebuild(board);

        assertArrayEquals(whiteBefore, evaluator.currentAccumulator(Piece.White),
                "verifyAgainstRebuild must never mutate the live accumulator");
        assertArrayEquals(blackBefore, evaluator.currentAccumulator(Piece.Black),
                "verifyAgainstRebuild must never mutate the live accumulator");
    }
}
