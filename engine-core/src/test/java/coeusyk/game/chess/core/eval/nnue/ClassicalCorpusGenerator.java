package coeusyk.game.chess.core.eval.nnue;

import coeusyk.game.chess.core.eval.ClassicalEvaluator;
import coeusyk.game.chess.core.models.Board;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Issue #201 (Phase E, E-1): generator for a classical-eval-labeled corpus, consumed by
 * the trainer's {@code eval_scale_check} validator. Reads the already-committed
 * {@code bench/nnue-corpus/*.epd} category files — written once by
 * {@link NnueCorpusGenerator} — and never regenerates them; the two generators are
 * independent passes over the same static fixtures.
 *
 * <p>Excluded from the standard test suite and from CI, matching
 * {@link NnueCorpusGenerator}'s convention. Run manually with:
 * <pre>
 *   mvn -pl engine-core test -Dgroups=corpus-generation -Dcorpus.generate=true
 * </pre>
 */
@Tag("corpus-generation")
class ClassicalCorpusGenerator {

    private static final Path CORPUS_DIR = Path.of("..", "bench", "nnue-corpus");

    @Test
    void generateClassicalGoldenEvals() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean("corpus.generate"),
                "corpus generation only runs when explicitly requested via -Dcorpus.generate=true");

        ClassicalEvaluator evaluator = new ClassicalEvaluator();

        List<String> lines = new ArrayList<>();
        lines.add("# Regenerate: mvn -pl engine-core test -Dgroups=corpus-generation -Dcorpus.generate=true");
        lines.add("fen,eval");
        for (String category : NnueCorpusCategories.FILE_NAMES) {
            for (String fen : NnueCorpusCategories.readFens(CORPUS_DIR.resolve(category))) {
                Board board = NnueCorpusCategories.toBoard(fen);
                short eval = (short) evaluator.evaluate(board);
                lines.add(fen + "," + eval);
            }
        }
        Files.write(CORPUS_DIR.resolve("classical-golden-evals.csv"), lines);
    }
}
