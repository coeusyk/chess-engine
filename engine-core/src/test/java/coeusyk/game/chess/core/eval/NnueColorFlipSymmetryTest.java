package coeusyk.game.chess.core.eval;

import coeusyk.game.chess.core.eval.nnue.NnueEvaluator;
import coeusyk.game.chess.core.eval.nnue.NnueNetwork;
import coeusyk.game.chess.core.eval.nnue.TestNetworks;
import coeusyk.game.chess.core.models.Board;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #269: the classical evaluator has a colour-flip property test (issue #213); the NNUE path had
 * none. The NNUE score is side-to-move relative and its features are perspective-relative, so
 * {@code eval(P) == eval(colorFlip(P))} must hold for any network. A swapped perspective, a
 * mis-flipped square or a wrong accumulator order in the output layer breaks it for a random
 * network just as it would for a trained one.
 *
 * <p>Width 256 runs whole SIMD iterations (see {@code NnueIncrementalVsRebuildFuzzTest}); width 8
 * is the historical test width. Lives in the {@code eval} package to reuse
 * {@link EvaluatorTest#colorFlipFen}.
 */
class NnueColorFlipSymmetryTest {

    private static final Path CORPUS = Path.of("..", "bench", "nnue-corpus", "golden-evals.csv");

    @ParameterizedTest(name = "width {0}")
    @ValueSource(ints = {8, 256})
    void scoreIsInvariantUnderColourFlipAcrossTheGoldenCorpus(int width) throws IOException {
        NnueEvaluator evaluator = new NnueEvaluator(TestNetworks.synthetic(width));
        List<String> lines = Files.readAllLines(CORPUS);
        int checked = 0;
        for (int i = 1; i < lines.size(); i++) { // skip "fen,eval" header
            String fen = lines.get(i).substring(0, lines.get(i).lastIndexOf(',')) + " 0 1";
            String flipped = EvaluatorTest.colorFlipFen(fen);

            Board original = new Board(fen);
            evaluator.reset(original);
            int evalOriginal = evaluator.evaluate(original);

            Board mirror = new Board(flipped);
            evaluator.reset(mirror);
            int evalFlipped = evaluator.evaluate(mirror);

            assertEquals(evalOriginal, evalFlipped,
                    "NNUE eval not colour-flip symmetric (width " + width + ")\n  " + fen + "\n  " + flipped);
            checked++;
        }
        assertTrue(checked > 100, "corpus must supply a meaningful sample, got " + checked);
    }

    /** The invariance must not be satisfiable by an evaluator that ignores the position. */
    @Test
    void symmetryCheckIsNotVacuous() {
        NnueNetwork network = TestNetworks.synthetic(256);
        NnueEvaluator evaluator = new NnueEvaluator(network);
        Board start = new Board("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
        Board other = new Board("4k3/8/8/3q4/8/8/4P3/4K3 w - - 0 1");
        evaluator.reset(start);
        int a = evaluator.evaluate(start);
        evaluator.reset(other);
        int b = evaluator.evaluate(other);
        assertTrue(a != b, "distinct positions should not all collapse to one score (" + a + ")");
    }
}
