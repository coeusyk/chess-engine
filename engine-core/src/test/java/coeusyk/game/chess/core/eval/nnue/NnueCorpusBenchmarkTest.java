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

    // Issue #218 SS16.1: the production net's hidden width (docs/NNUE_PRD.md SS3,
    // nets/*.json "hidden_width": 256) -- a Stage 1 SIMD speedup measured at width 8 (half a
    // single AVX2 short-lane) would understate or hide any real vectorization benefit.
    private static final int PRODUCTION_WIDTH = 256;

    @Test
    void perCategoryThroughputAndNps() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean("benchmark.enabled"),
                "Set -Dbenchmark.enabled=true to run the NNUE corpus benchmark");

        NnueNetwork network = TestNetworks.synthetic(PRODUCTION_WIDTH);

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

    // Issue #218 Stage 1 (Task 1 of the implementation plan): scalar-vs-vector
    // addFeature/subtractFeature comparison at PRODUCTION_WIDTH, using identical inputs for
    // both paths -- doesn't change perCategoryThroughputAndNps's own logic, purely additive.
    private static final int ACCUM_WARMUP_ROUNDS = 5;
    private static final int ACCUM_MEASURE_ROUNDS = 20;
    private static final int ACCUM_OPS_PER_ROUND = 200_000;

    /**
     * <b>Read this before trusting the printed speedup number.</b> C2's SuperWord/SLP
     * auto-vectorizer targets exactly this loop shape (a counted {@code short[]} elementwise
     * add/subtract) — confirmed directly: a standalone run of {@code addFeatureScalar}'s logic
     * measured 22.4 ns/op with SuperWord enabled (default) vs. 76.7 ns/op with {@code
     * -XX:-UseSuperWord}, on the same JVM. This benchmark's own "scalar" arm has been observed
     * at 12 ns/op, 91 ns/op, and other values across different harness shapes in this same
     * codebase's development history — all real measurements of the same source code, differing
     * only in whether C2 chose to auto-vectorize the scalar loop in that particular calling
     * context. <b>The printed speedup is therefore a range depending on JIT behavior this test
     * does not control (roughly 1.3x when the scalar arm auto-vectorizes, up to ~12x when it
     * does not) — not a single production estimate.</b> Only a full pre-PR-vs-post-PR
     * native-Windows {@code --bench} comparison (issue #218 PR 3) captures what C2 actually does
     * to the scalar path inside the real {@code Searcher}/{@code NnueEvaluator} call pattern,
     * which differs from this isolated tight-loop microbenchmark in inlining budget, call-site
     * polymorphism, and surrounding code shape. Treat this test as "explicit Vector API is not
     * slower and is often meaningfully faster than scalar," not as a specific multiplier.
     */
    @Test
    void accumulatorUpdateScalarVsVectorMicrobenchmark() {
        Assumptions.assumeTrue(Boolean.getBoolean("benchmark.enabled"),
                "Set -Dbenchmark.enabled=true to run the NNUE corpus benchmark");
        Assumptions.assumeTrue(VectorCapabilities.AVAILABLE,
                "jdk.incubator.vector not available in this environment");

        short[] weights = TestNetworks.synthetic(PRODUCTION_WIDTH).ftWeights();

        // Direct calls, no functional-interface indirection -- a lambda-per-op layer would add
        // the same fixed dispatch cost to both arms and could dilute the *relative* speedup at
        // this operation's scale (a handful of ns/op), so each arm is its own hand-written loop.
        long[] scalarNanosPerOp = timeScalarRounds(weights);
        long[] vectorNanosPerOp = timeVectorRounds(weights);

        Stats scalarStats = Stats.of(scalarNanosPerOp);
        Stats vectorStats = Stats.of(vectorNanosPerOp);
        double speedup = scalarStats.meanNanos / vectorStats.meanNanos;

        System.out.printf(Locale.US,
                "[VectorBench] width=%d ops/round=%d rounds=%d%n",
                PRODUCTION_WIDTH, ACCUM_OPS_PER_ROUND, ACCUM_MEASURE_ROUNDS);
        System.out.printf(Locale.US,
                "  scalar : %,10.1f ns/op  (+/- %,8.1f)  %,12.0f ops/sec%n",
                scalarStats.meanNanos, scalarStats.stddevNanos, 1_000_000_000.0 / scalarStats.meanNanos);
        System.out.printf(Locale.US,
                "  vector : %,10.1f ns/op  (+/- %,8.1f)  %,12.0f ops/sec%n",
                vectorStats.meanNanos, vectorStats.stddevNanos, 1_000_000_000.0 / vectorStats.meanNanos);
        System.out.printf(Locale.US, "  speedup: %.2fx  (preferred short lanes=%d)%n",
                speedup, VectorCapabilities.PREFERRED_SHORT_LANES);
    }

    private static long[] timeScalarRounds(short[] weights) {
        short[] scratch = new short[PRODUCTION_WIDTH];
        for (int w = 0; w < ACCUM_WARMUP_ROUNDS; w++) {
            for (int i = 0; i < ACCUM_OPS_PER_ROUND; i++) {
                NnueEvaluator.addFeatureScalar(scratch, 0, weights);
            }
        }
        long[] nanosPerOp = new long[ACCUM_MEASURE_ROUNDS];
        for (int r = 0; r < ACCUM_MEASURE_ROUNDS; r++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < ACCUM_OPS_PER_ROUND; i++) {
                NnueEvaluator.addFeatureScalar(scratch, 0, weights);
            }
            nanosPerOp[r] = (System.nanoTime() - t0) / ACCUM_OPS_PER_ROUND;
        }
        return nanosPerOp;
    }

    private static long[] timeVectorRounds(short[] weights) {
        short[] scratch = new short[PRODUCTION_WIDTH];
        for (int w = 0; w < ACCUM_WARMUP_ROUNDS; w++) {
            for (int i = 0; i < ACCUM_OPS_PER_ROUND; i++) {
                NnueAccumulatorVectorOps.addFeature(scratch, 0, weights);
            }
        }
        long[] nanosPerOp = new long[ACCUM_MEASURE_ROUNDS];
        for (int r = 0; r < ACCUM_MEASURE_ROUNDS; r++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < ACCUM_OPS_PER_ROUND; i++) {
                NnueAccumulatorVectorOps.addFeature(scratch, 0, weights);
            }
            nanosPerOp[r] = (System.nanoTime() - t0) / ACCUM_OPS_PER_ROUND;
        }
        return nanosPerOp;
    }

    private record Stats(double meanNanos, double stddevNanos) {
        static Stats of(long[] samples) {
            double mean = 0;
            for (long s : samples) mean += s;
            mean /= samples.length;
            double variance = 0;
            for (long s : samples) variance += (s - mean) * (s - mean);
            variance /= samples.length;
            return new Stats(mean, Math.sqrt(variance));
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
