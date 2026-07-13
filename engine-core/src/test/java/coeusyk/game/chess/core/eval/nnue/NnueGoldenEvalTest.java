package coeusyk.game.chess.core.eval.nnue;

import coeusyk.game.chess.core.models.Board;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * PR C-4 (issue #188): {@code bench/nnue-corpus/golden-evals.csv} pins the CI test
 * net's exact int16 eval for every corpus FEN. Any divergence (weight-format change,
 * accumulator bug, quantization drift) must fail the build with the offending FEN
 * printed — a hard failure, not a threshold (plan Task 3, "Regression thresholds").
 *
 * <p>PR C-5 (issue #189): tagged {@code nnue-golden} so CI can select it as its own
 * distinctly-reported job — additive only, this still runs untagged in the default
 * {@code mvn test} reactor build exactly as it did in C-4.
 */
@Tag("nnue-golden")
class NnueGoldenEvalTest {

    private static final Path GOLDEN_EVALS_CSV = Path.of("..", "bench", "nnue-corpus", "golden-evals.csv");

    @Test
    void everyGoldenPositionMatchesCiTestNetExactly() throws IOException {
        NnueNetwork network = TestNetworks.synthetic(8);
        NnueEvaluator evaluator = new NnueEvaluator(network);

        List<String> lines = Files.readAllLines(GOLDEN_EVALS_CSV);
        for (int i = 1; i < lines.size(); i++) { // skip "fen,eval" header
            int lastComma = lines.get(i).lastIndexOf(',');
            String fen = lines.get(i).substring(0, lastComma);
            short expected = Short.parseShort(lines.get(i).substring(lastComma + 1));

            Board board = NnueCorpusCategories.toBoard(fen);
            evaluator.reset(board);
            short actual = (short) evaluator.evaluate(board);

            assertEquals(expected, actual, "golden-eval mismatch for FEN: " + fen);
        }
    }
}
