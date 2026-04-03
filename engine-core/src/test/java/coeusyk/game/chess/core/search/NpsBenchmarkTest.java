package coeusyk.game.chess.core.search;

import coeusyk.game.chess.core.models.Board;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fixed-depth NPS benchmark.
 *
 * <p>Excluded from the standard test suite — skips unless {@code -Dbenchmark.enabled=true}.
 * Run with:
 * <pre>
 *   .\mvnw.cmd test -pl engine-core -Dgroups=benchmark -Dbenchmark.enabled=true
 * </pre>
 *
 * <p>Methodology: 5 warmup rounds (shared Searcher) per position to prime JIT and TT,
 * then 10 measurement rounds (fresh Searcher each) to exclude TT/killer carry-over.
 * Matches the BenchMain protocol in engine-uci so numbers are directly comparable.
 *
 * <p>Baseline (Phase 8, post-Fix #5, depth 10):
 * <ul>
 *   <li>startpos:  402,750 ± 19,976 NPS</li>
 *   <li>kiwipete:  246,066 ± 13,767 NPS</li>
 *   <li>cpw-pos3:  601,293 ± 40,037 NPS</li>
 *   <li>cpw-pos4:  279,393 ± 16,894 NPS</li>
 *   <li>Aggregate: 381,194 NPS</li>
 * </ul>
 */
@Tag("benchmark")
class NpsBenchmarkTest {

    private static final int    BENCH_DEPTH    = 10;
    private static final int    WARMUP_ROUNDS  = 5;
    private static final int    MEASURE_ROUNDS = 10;
    private static final int    BENCH_HASH_MB  = 16;

    private static final String[] POSITION_NAMES = {
        "startpos",
        "kiwipete",
        "cpw-pos3",
        "cpw-pos4"
    };

    private static final String[] POSITION_FENS = {
        "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
        "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
        "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
        "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1"
    };

    @Test
    void aggregateNps() {
        Assumptions.assumeTrue(
            Boolean.getBoolean("benchmark.enabled"),
            "Set -Dbenchmark.enabled=true to run NPS benchmark"
        );

        System.out.printf(Locale.US,
            "[NpsBenchmark] depth=%d  warmup=%d  rounds=%d%n",
            BENCH_DEPTH, WARMUP_ROUNDS, MEASURE_ROUNDS);
        System.out.println();
        System.out.printf(Locale.US, "%-12s | %5s | %12s | %9s | %15s%n",
            "Position", "Round", "Nodes", "Time(ms)", "NPS");
        System.out.println("-------------|-------|--------------|-----------|----------------");

        long[] positionMeanNps = new long[POSITION_FENS.length];

        for (int p = 0; p < POSITION_FENS.length; p++) {
            String posName = POSITION_NAMES[p];
            String fen     = POSITION_FENS[p];

            // Warmup: shared Searcher primes JIT and TT — discarded
            Searcher warmupSearcher = new Searcher();
            warmupSearcher.setTranspositionTableSizeMb(BENCH_HASH_MB);
            for (int w = 0; w < WARMUP_ROUNDS; w++) {
                Board b = new Board(fen);
                b.setSearchMode(true);
                warmupSearcher.searchDepth(b, BENCH_DEPTH);
            }

            // Measurement: fresh Searcher each round (zero TT/killers/history carry-over)
            long[] roundNps = new long[MEASURE_ROUNDS];
            for (int r = 0; r < MEASURE_ROUNDS; r++) {
                Searcher searcher = new Searcher();
                searcher.setTranspositionTableSizeMb(BENCH_HASH_MB);
                Board board = new Board(fen);
                board.setSearchMode(true);

                long t0     = System.nanoTime();
                SearchResult result = searcher.searchDepth(board, BENCH_DEPTH);
                long ms     = Math.max(1L, (System.nanoTime() - t0) / 1_000_000L);
                long nodes  = result.nodesVisited();
                long nps    = nodes * 1_000L / ms;

                roundNps[r] = nps;
                System.out.printf(Locale.US, "%-12s | %5d | %12d | %9d | %,15d%n",
                    posName, r + 1, nodes, ms, nps);
            }

            // Per-position mean and stddev
            long sum = 0;
            for (long v : roundNps) sum += v;
            long mean = sum / MEASURE_ROUNDS;
            long sumSq = 0;
            for (long v : roundNps) { long d = v - mean; sumSq += d * d; }
            long stddev = (long) Math.sqrt((double) sumSq / MEASURE_ROUNDS);

            positionMeanNps[p] = mean;
            System.out.printf(Locale.US, "%-12s | %-5s |              |           | %,15d +/- %,d%n",
                posName, "MEAN", mean, stddev);
            System.out.println();
        }

        // Aggregate across all positions
        long aggSum = 0;
        for (long m : positionMeanNps) aggSum += m;
        long aggMean = aggSum / POSITION_FENS.length;
        long aggSumSq = 0;
        for (long m : positionMeanNps) { long d = m - aggMean; aggSumSq += d * d; }
        long aggStddev = (long) Math.sqrt((double) aggSumSq / POSITION_FENS.length);

        System.out.printf(Locale.US,
            "[NpsBenchmark] AGGREGATE MEAN NPS: %,d  +/- %,d%n", aggMean, aggStddev);

        // TT statistics: run all 4 positions once more through a dedicated Searcher
        // so that TT stats reflect a realistic cross-position end-of-benchmark state.
        // Pawn-hash stats are collected in the same pass.
        System.out.println();
        System.out.println("[NpsBenchmark] TT + Pawn-hash Statistics (single pass over all positions at depth " + BENCH_DEPTH + ")");
        Searcher statsSearcher = new Searcher();
        statsSearcher.setTranspositionTableSizeMb(BENCH_HASH_MB);
        statsSearcher.enablePawnHashStats();  // must be called before any search
        for (String fen : POSITION_FENS) {
            Board b = new Board(fen);
            b.setSearchMode(true);
            statsSearcher.searchDepth(b, BENCH_DEPTH);
        }
        TranspositionTable.TTStats ttStats = statsSearcher.getTranspositionTableStats();
        System.out.printf(Locale.US,
            "[NpsBenchmark] TT hashfull:       %d/1000%n", ttStats.hashfull());
        System.out.printf(Locale.US,
            "[NpsBenchmark] TT hit rate:       %.1f%%  (%d / %d probes)%n",
            ttStats.hitRate() * 100.0, ttStats.hits(), ttStats.probes());
        double pawnHitRate = statsSearcher.getPawnHashHitRate();
        System.out.printf(Locale.US,
            "[NpsBenchmark] Pawn hash hit rate: %.1f%%%n", pawnHitRate * 100.0);

        // Pawn-hash gate: ≥85% hit rate is expected on standard positions at depth 10.
        assertTrue(pawnHitRate >= 0.85, String.format(Locale.US,
            "[NpsBenchmark] REGRESSION: pawn hash hit rate %.1f%% < 85%%. " +
            "Pawn table may be too small or hash collisions excessive.",
            pawnHitRate * 100.0));

        // Regression gate: if nps.baseline is set, require aggMean >= baseline * (1 - nps.threshold).
        long npsBaseline = Long.getLong("nps.baseline", 0L);
        if (npsBaseline > 0) {
            double threshold = Double.parseDouble(System.getProperty("nps.threshold", "0.05"));
            long minRequired = (long) (npsBaseline * (1.0 - threshold));
            assertTrue(aggMean >= minRequired, String.format(Locale.US,
                "[NpsBenchmark] REGRESSION: aggregate NPS %,d < %,d (%.0f%% of baseline %,d)",
                aggMean, minRequired, (1.0 - threshold) * 100, npsBaseline));
        }
    }
}
