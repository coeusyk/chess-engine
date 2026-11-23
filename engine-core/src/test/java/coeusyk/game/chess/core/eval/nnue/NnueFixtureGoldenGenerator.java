package coeusyk.game.chess.core.eval.nnue;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Issue #265: writes {@code bench/nnue-corpus/fixture-*-golden-evals.csv}. Excluded from the standard
 * suite and from CI, like {@link ClassicalCorpusGenerator}. The fixtures are frozen, so this is meant
 * to run when a new fixture is added, never to "refresh" an existing golden file.
 * <pre>
 *   mvn -pl engine-core test -Dgroups=corpus-generation -Dcorpus.generate=true -Dtest=NnueFixtureGoldenGenerator
 * </pre>
 */
@Tag("corpus-generation")
class NnueFixtureGoldenGenerator {

    @Test
    void generateFixtureGoldenEvals() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean("corpus.generate"),
                "corpus generation only runs when explicitly requested via -Dcorpus.generate=true");

        List<String> fens = NnueFixtureGoldenSupport.corpusFens();
        for (NnueFixtureGoldenSupport.Fixture fixture : NnueFixtureGoldenSupport.FIXTURES) {
            NnueEvaluator evaluator = new NnueEvaluator(NnueNetwork.load(NnueFixtureGoldenSupport.fixturePath(fixture)));
            List<String> lines = new ArrayList<>();
            lines.add("# Regenerate: mvn -pl engine-core test -Dgroups=corpus-generation -Dcorpus.generate=true -Dtest=NnueFixtureGoldenGenerator");
            lines.add("fen,eval");
            for (String fen : fens) {
                lines.add(fen + "," + NnueFixtureGoldenSupport.evaluate(evaluator, fen));
            }
            Files.write(NnueFixtureGoldenSupport.goldenPath(fixture), lines);
        }
    }
}
