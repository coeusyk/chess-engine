package coeusyk.game.chess.core.eval.nnue;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.models.Piece;
import coeusyk.game.chess.core.movegen.MovesGenerator;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PR C-1: {@link NnueEvaluator#dumpAccumulators()} is a stable, diffable text dump of
 * both perspectives' current-ply accumulator state — read-only, no new arithmetic.
 * PR C-2: {@link NnueEvaluator#explainEval(Board)} reports pre-activation ranges,
 * clip counts, output-layer parameters, the int16 score, and the float32 oracle
 * comparison — read-only, no production-path arithmetic added.
 */
class NnueEvaluatorDebugTest {

    @Test
    void dumpMatchesRawAccumulatorValuesAtRootPosition() {
        NnueNetwork network = TestNetworks.synthetic(8);
        NnueEvaluator evaluator = new NnueEvaluator(network);
        Board board = new Board();
        evaluator.reset(board);

        String dump = evaluator.dumpAccumulators();

        assertTrue(dump.contains("sp=0"), "dump should report the current stack pointer");
        assertTrue(dump.contains("white=" + Arrays.toString(evaluator.currentAccumulator(Piece.White))),
                "dump should contain the exact white accumulator values");
        assertTrue(dump.contains("black=" + Arrays.toString(evaluator.currentAccumulator(Piece.Black))),
                "dump should contain the exact black accumulator values");
    }

    @Test
    void dumpFormatIsStableAcrossRepeatedCalls() {
        NnueNetwork network = TestNetworks.synthetic(8);
        NnueEvaluator evaluator = new NnueEvaluator(network);
        evaluator.reset(new Board());

        assertEquals(evaluator.dumpAccumulators(), evaluator.dumpAccumulators(),
                "dumping the same unchanged state twice must produce identical text");
    }

    @Test
    void dumpTracksStackPointerAfterOnMake() {
        NnueNetwork network = TestNetworks.synthetic(8);
        NnueEvaluator evaluator = new NnueEvaluator(network);
        Board board = new Board();
        evaluator.reset(board);

        Move move = new MovesGenerator(board).getActiveMoves(board.getActiveColor()).get(0);
        board.makeMove(move);
        evaluator.onMake(board, move.pack(), board.lastCapturedPiece());

        assertTrue(evaluator.dumpAccumulators().contains("sp=1"), "sp should advance to 1 after one onMake");
    }

    @Test
    void explainEvalContainsAllRequiredFields() {
        NnueNetwork network = TestNetworks.synthetic(8);
        NnueEvaluator evaluator = new NnueEvaluator(network);
        Board board = new Board();
        evaluator.reset(board);

        String breakdown = evaluator.explainEval(board);

        assertTrue(breakdown.contains("pre-activation us"), "missing pre-activation us range");
        assertTrue(breakdown.contains("pre-activation them"), "missing pre-activation them range");
        assertTrue(breakdown.contains("clipped="), "missing clip count");
        assertTrue(breakdown.contains("output contribution"), "missing output contribution");
        assertTrue(breakdown.contains("qa=" + 127), "output contribution line should report qa");
        assertTrue(breakdown.contains("int16 score"), "missing int16 score");
        assertTrue(breakdown.contains("float32 oracle score"), "missing float32 oracle score");
        assertTrue(breakdown.contains("delta="), "missing oracle delta");
    }

    @Test
    void explainEvalInt16ScoreMatchesEvaluate() {
        NnueNetwork network = TestNetworks.synthetic(8);
        NnueEvaluator evaluator = new NnueEvaluator(network);
        Board board = new Board();
        evaluator.reset(board);

        int directScore = evaluator.evaluate(board);
        String breakdown = evaluator.explainEval(board);

        assertTrue(breakdown.contains("int16 score           " + String.format("%+d", directScore)),
                "explainEval's int16 score must match evaluate()'s own return value");
    }
}
