package coeusyk.game.chess.core.eval.nnue;

import coeusyk.game.chess.core.eval.ClassicalEvaluator;
import coeusyk.game.chess.core.eval.EvaluatorStrategy;
import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.search.SearchResult;
import coeusyk.game.chess.core.search.Searcher;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * PR C-4 (issue #188): per-category eval throughput and fixed-depth NPS across the
 * {@code bench/nnue-corpus} categories, for both evaluators. Tracked, not gated — the
 * §1 aggregate NPS gate (see {@code NpsBenchmarkTest}) is the only number CI enforces;
 * this harness's per-category figures are for regression tracking (plan Task 3).
 *
 * <p>Excluded from the standard test suite <em>and</em> from {@code NpsBenchmarkTest}'s
 * existing CI step — deliberately a distinct tag from {@code @Tag("benchmark")}, so this
 * doesn't silently ride along on {@code ci.yml}'s {@code -Dgroups=benchmark} step (which
 * has no {@code -Dtest=} scoping). Not wired into CI at all yet — that's PR C-5. Run with:
 * <pre>
 *   mvn -pl engine-core test -Dgroups=nnue-benchmark -Dbenchmark.enabled=true -Dtest=NnueCorpusBenchmarkTest
 * </pre>
 */
@Tag("nnue-benchmark")
class NnueCorpusBenchmarkTest {

    private static final Path CORPUS_DIR = Path.of("..", "bench", "nnue-corpus");
    private static final int EVAL_REPS = 5;
    private static final int NPS_SAMPLE_SIZE = 5;
    private static final int NPS_DEPTH = Integer.getInteger("benchmark.depth", 6);
    private static final int BENCH_HASH_MB = 16;

    @Test
    void perCategoryThroughputAndNps() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean("benchmark.enabled"),
                "Set -Dbenchmark.enabled=true to run the NNUE corpus benchmark");

        NnueNetwork network = TestNetworks.synthetic(8);

        System.out.printf(Locale.US, "%-14s | %11s | %10s | %11s | %10s%n",
                "Category", "NNUE ev/s", "NNUE NPS", "Cls ev/s", "Cls NPS");
        System.out.println("---------------|-------------|------------|-------------|------------");

        for (String category : NnueCorpusCategories.FILE_NAMES) {
            List<String> fens = NnueCorpusCategories.readFens(CORPUS_DIR.resolve(category));
            List<String> npsSample = fens.subList(0, Math.min(NPS_SAMPLE_SIZE, fens.size()));

            long nnueEvalRate = evalThroughput(fens, () -> new NnueEvaluator(network));
            long classicalEvalRate = evalThroughput(fens, ClassicalEvaluator::new);
            long nnueNps = fixedDepthNps(npsSample, () -> new NnueEvaluator(network));
            long classicalNps = fixedDepthNps(npsSample, null);

            System.out.printf(Locale.US, "%-14s | %,11d | %,10d | %,11d | %,10d%n",
                    category, nnueEvalRate, nnueNps, classicalEvalRate, classicalNps);
        }
    }

    /** evals/sec over {@link #EVAL_REPS} passes across every position in {@code fens}. */
    private static long evalThroughput(List<String> fens, Supplier<EvaluatorStrategy> evaluatorFactory) {
        EvaluatorStrategy evaluator = evaluatorFactory.get();
        long t0 = System.nanoTime();
        long evals = 0;
        for (int rep = 0; rep < EVAL_REPS; rep++) {
            for (String fen : fens) {
                Board board = NnueCorpusCategories.toBoard(fen);
                evaluator.reset(board);
                evaluator.evaluate(board);
                evals++;
            }
        }
        long elapsedMs = Math.max(1L, (System.nanoTime() - t0) / 1_000_000L);
        return evals * 1_000L / elapsedMs;
    }

    /** Aggregate NPS over {@code sample} at {@link #NPS_DEPTH}. {@code evaluatorFactory} null == classical default. */
    private static long fixedDepthNps(List<String> sample, Supplier<EvaluatorStrategy> evaluatorFactory) {
        long totalNodes = 0;
        long totalMs = 0;
        for (String fen : sample) {
            Searcher searcher = new Searcher();
            searcher.setTranspositionTableSizeMb(BENCH_HASH_MB);
            if (evaluatorFactory != null) {
                searcher.setEvaluatorStrategy(evaluatorFactory.get());
            }
            Board board = NnueCorpusCategories.toBoard(fen);
            board.setSearchMode(true);

            long t0 = System.nanoTime();
            SearchResult result = searcher.searchDepth(board, NPS_DEPTH);
            long ms = Math.max(1L, (System.nanoTime() - t0) / 1_000_000L);

            totalNodes += result.nodesVisited();
            totalMs += ms;
        }
        return totalNodes * 1_000L / Math.max(1L, totalMs);
    }
}
