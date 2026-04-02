package coeusyk.game.chess.uci;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.search.SearchResult;
import coeusyk.game.chess.core.search.Searcher;
import java.util.Locale;

/**
 * Fixed-depth NPS benchmark harness.
 *
 * <p>Usage: java -cp engine-uci-shaded.jar coeusyk.game.chess.uci.BenchMain [--depth N]
 *
 * <p>Methodology:
 * <ul>
 *   <li>5 warmup rounds (shared Searcher) per position to warm the JIT and TT.
 *   <li>10 measurement rounds (fresh Searcher each) to exclude TT/killer carry-over.
 *   <li>Prints per-round NPS, per-position MEAN ± stddev, and aggregate mean NPS.
 * </ul>
 */
public class BenchMain {

    private static final int    DEFAULT_DEPTH  = 10;
    private static final int    WARMUP_ROUNDS  = 5;
    private static final int    MEASURE_ROUNDS = 10;
    private static final int    BENCH_HASH_MB  = 16;
    private static final String VERSION        = "0.4.10-SNAPSHOT";

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

    public static void main(String[] args) {
        int depth = DEFAULT_DEPTH;
        for (int i = 0; i < args.length - 1; i++) {
            if ("--depth".equals(args[i])) {
                try {
                    depth = Math.max(1, Math.min(64, Integer.parseInt(args[i + 1])));
                } catch (NumberFormatException ignored) {
                    // fall through — keep default
                }
            }
        }

        System.out.printf(Locale.US,
            "[Bench] Vex %s  depth=%d  warmup=%d  rounds=%d%n",
            VERSION, depth, WARMUP_ROUNDS, MEASURE_ROUNDS);
        System.out.println();
        System.out.printf(Locale.US, "%-12s | %5s | %12s | %9s | %15s%n",
            "Position", "Round", "Nodes", "Time(ms)", "NPS");
        System.out.println("-------------|-------|--------------|-----------|----------------");

        long[] positionMeanNps = new long[POSITION_FENS.length];

        for (int p = 0; p < POSITION_FENS.length; p++) {
            String posName = POSITION_NAMES[p];
            String fen     = POSITION_FENS[p];

            // --- warmup: shared Searcher, primes JIT and TT ---
            Searcher warmupSearcher = new Searcher();
            warmupSearcher.setTranspositionTableSizeMb(BENCH_HASH_MB);
            for (int w = 0; w < WARMUP_ROUNDS; w++) {
                Board wBoard = new Board(fen);
                wBoard.setSearchMode(true);
                warmupSearcher.searchDepth(wBoard, depth);
            }

            // --- measurement: fresh Searcher each round (zero TT/killers/history) ---
            long[] roundNps = new long[MEASURE_ROUNDS];
            for (int r = 0; r < MEASURE_ROUNDS; r++) {
                Searcher searcher = new Searcher();
                searcher.setTranspositionTableSizeMb(BENCH_HASH_MB);
                Board board = new Board(fen);
                board.setSearchMode(true);

                long t0      = System.nanoTime();
                SearchResult result = searcher.searchDepth(board, depth);
                long ms      = Math.max(1L, (System.nanoTime() - t0) / 1_000_000L);
                long nodes   = result.nodesVisited();
                long nps     = nodes * 1_000L / ms;

                roundNps[r] = nps;
                System.out.printf(Locale.US, "%-12s | %5d | %12d | %9d | %,15d%n",
                    posName, r + 1, nodes, ms, nps);
            }

            // mean and stddev for this position
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

        // aggregate across all positions
        long aggSum = 0;
        for (long m : positionMeanNps) aggSum += m;
        long aggMean = aggSum / POSITION_FENS.length;

        long aggSumSq = 0;
        for (long m : positionMeanNps) { long d = m - aggMean; aggSumSq += d * d; }
        long aggStddev = (long) Math.sqrt((double) aggSumSq / POSITION_FENS.length);

        System.out.printf(Locale.US, "[Bench] AGGREGATE MEAN NPS: %,d  +/- %,d%n", aggMean, aggStddev);
    }
}
