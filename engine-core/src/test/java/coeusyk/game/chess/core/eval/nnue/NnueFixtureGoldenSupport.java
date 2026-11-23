package coeusyk.game.chess.core.eval.nnue;

import coeusyk.game.chess.core.models.Board;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Issue #265: the committed {@code .nnue} fixtures whose canonical Java int16 outputs are pinned in
 * {@code bench/nnue-corpus/fixture-*-golden-evals.csv}, shared by the golden test and its generator.
 */
final class NnueFixtureGoldenSupport {

    record Fixture(String nnueFile, String goldenCsv) {
    }

    static final List<Fixture> FIXTURES = List.of(
            new Fixture("d6-export-fixture.nnue", "fixture-d6-golden-evals.csv"),
            new Fixture("parity-w32-fixture.nnue", "fixture-w32-golden-evals.csv"));

    private NnueFixtureGoldenSupport() {
    }

    static Path fixturePath(Fixture fixture) throws IOException {
        return RepoPaths.repoRoot().resolve("engine-core/src/test/resources/nnue").resolve(fixture.nnueFile());
    }

    static Path goldenPath(Fixture fixture) throws IOException {
        return RepoPaths.repoRoot().resolve("bench/nnue-corpus").resolve(fixture.goldenCsv());
    }

    /** Every corpus FEN, in the same category order the other corpus tools use (4-field FENs). */
    static List<String> corpusFens() throws IOException {
        List<String> fens = new ArrayList<>();
        for (String category : NnueCorpusCategories.FILE_NAMES) {
            fens.addAll(NnueCorpusCategories.readFens(RepoPaths.repoRoot().resolve("bench/nnue-corpus").resolve(category)));
        }
        return fens;
    }

    static int evaluate(NnueEvaluator evaluator, String fourFieldFen) {
        Board board = NnueCorpusCategories.toBoard(fourFieldFen);
        evaluator.reset(board);
        return evaluator.evaluate(board);
    }
}
