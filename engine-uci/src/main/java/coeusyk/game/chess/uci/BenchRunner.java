package coeusyk.game.chess.uci;

import coeusyk.game.chess.core.eval.EvaluatorStrategy;
import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.search.SearchResult;
import coeusyk.game.chess.core.search.Searcher;

import java.util.Locale;
import java.util.function.Supplier;

/**
 * Standard fixed-depth benchmark for Vex.
 *
 * <p>Runs the engine at a fixed depth over a canonical set of 31 positions
 * (Stockfish bench suite, public domain) and reports total nodes searched,
 * elapsed wall time, and NPS.
 *
 * <p>Usage:
 * <pre>
 *   java -jar engine-uci-shaded.jar --bench [depth]
 *   java -jar engine-uci-shaded.jar --bench [depth] --eval-type NNUE --eval-file &lt;path&gt;
 *   # or, inside a UCI session:
 *   bench [depth]
 * </pre>
 *
 * <p><b>NPS is hardware-dependent</b> and varies with CPU speed, JIT warmup,
 * and OS scheduling. It must NOT be used as a pass/fail gate. Use node counts
 * at a given depth to verify search correctness across builds. Comparing
 * Classical-mode vs. NNUE-mode NPS on the *same* run (issue #206's performance
 * gate) is the one case this tool's own aggregate NPS number is meant for.
 *
 * <p>State isolation: a fresh {@link Searcher} (and therefore fresh history /
 * killer tables) is created for every position, and the transposition table is
 * allocated anew each time. This is equivalent to sending {@code ucinewgame}
 * between positions and guarantees that no positional state bleeds between runs.
 */
public class BenchRunner {

    /** Default search depth — matches the industry-standard bench depth. */
    public static final int DEFAULT_DEPTH = 13;

    private static final int BENCH_HASH_MB = 16;

    /**
     * The 31-position Stockfish bench suite (public domain).
     * Positions cover a wide range of middle-game structures, endgames, and
     * tactical motifs to exercise all major search and evaluation branches.
     *
     * <p>All FENs in this list must be legal chess positions. {@link #run(int)}
     * validates every FEN at startup and throws {@link IllegalStateException}
     * if any is illegal, preventing silent skips that distort the NPS baseline.
     *
     * <p>Beyond legality, a bench position must be:
     * <ul>
     *   <li><b>Representative</b> — a realistic middle-game, endgame, or
     *       tactical structure, not a constructed edge case.</li>
     *   <li><b>Deterministic</b> — same depth, same position, same result
     *       across runs (the fresh-Searcher/fresh-TT isolation above already
     *       guarantees this within a single run).</li>
     *   <li><b>Stable under reasonable transposition-table configurations</b> —
     *       node count and search cost at a fixed depth must not vary by
     *       orders of magnitude, and must not diverge non-monotonically, as
     *       TT size changes across the range a real match/tuning run might
     *       use. A position that is otherwise legal and representative but
     *       fails this criterion contaminates cross-evaluator NPS comparisons,
     *       since different evaluators reach different nodes via different
     *       move ordering and can therefore land on wildly different points
     *       of an unstable position's search-cost curve.</li>
     * </ul>
     * This last criterion was added after issue #217 found that the prior
     * position 15 was TT-history-dependent (stable at 1 MB and 64 MB, but
     * failing to complete depth 13 within 15 minutes at several sizes in
     * between) — a real, reproducible search-instability characteristic of
     * that position, not a benchmark bug, but one that violates the "roughly
     * comparable search trees across evaluators" assumption an NPS benchmark
     * depends on. It was replaced following the same precedent set by commit
     * {@code 44aea1a}, which replaced an earlier pathological position
     * (BENCH_FENS[8] at the time, &gt;150M nodes at depth 13) for the same
     * reason: a single position dominating suite runtime and distorting the
     * NPS baseline. Issue #217 remains open as an independent search-behavior
     * investigation; the benchmark accommodation here does not depend on that
     * investigation's outcome.
     */
    private static final String[] BENCH_FENS = {
        "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
        "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
        "4k3/8/8/8/8/8/4P3/4K3 w - - 5 39",
        "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1",
        "rnbqkbnr/p1pppppp/8/1p6/2PP4/8/PP2PPPP/RNBQKBNR b KQkq c3 0 2",
        "r1bq1r1k/1pp1n1pp/1p1p4/4p2b/2B1P3/P1N1NP2/1PP3PP/R1BQ1RK1 w - - 2 14",
        "r3r1k1/2p2ppp/p1p1bn2/8/1q2P3/2NPQN2/PPP3PP/R4RK1 b - - 2 24",
        "r1bb4/3n1k2/p3p3/1p1pPp2/1Pp5/2P2N1B/P4PPP/4RK2 w - - 2 21",
        "r1bq1rk1/ppp1nppp/4n3/3p3Q/3P4/1BP1B3/PP1N2PP/R4RK1 w - - 1 16",
        "6k1/3r4/2R5/P5pp/3b4/6P1/5PK1/8 w - - 1 45",
        "r1bq1rk1/pp2ppbp/2np1np1/8/3NP3/2N1BP2/PPPQ2PP/R4RK1 b - - 0 10",
        "8/p7/1p2k1p1/2p5/2P1b3/1P3P2/P2K4/8 b - - 0 38",
        "8/8/6k1/8/5P2/4K1P1/8/8 b - - 0 65",
        "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
        "r1bq1r1k/b1p1npp1/p2p3p/1p6/3PP3/1B2NN2/PP3PPP/R2Q1RK1 w - - 1 16",
        "r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10",
        "r3k2r/pb3p2/5npp/n2p4/1p1PPB2/6P1/P2N1PBP/R3K2R b KQkq - 0 13",
        "rr6/2pq2pk/p2p1pnp/8/2QBPP2/1P6/P5PP/4RRK1 b - - 2 25",
        "r1bqkb1r/ppp2ppp/4pn2/3p4/2PP4/8/PP2PPPP/RNBQKBNR w KQkq d6 0 5",
        "2r3k1/4qpp1/3p3p/B1pP4/P1P1p1b1/1P4Pp/3Q1P1P/R3R1K1 w - - 0 28",
        "r4rk1/3qppbp/6p1/2p1n3/2B1P3/2P5/P2Q1PPP/R4RK1 b - - 0 20",
        "1rr3k1/4ppb1/2q1bnpp/3p4/2pP4/2P1BNNP/PP3PPK/2QRR3 b - - 6 26",
        "r3r1k1/6pp/bq2p3/p1pn4/Pn1N4/1B3P2/1PP2QBP/3RR1K1 w - - 0 24",
        "r2r4/8/k2N4/6B1/p7/P5P1/4K2P/1R6 w - - 4 46",
        "8/3b2kp/4p1p1/1p1n4/1P6/3NB3/6PP/6K1 b - - 0 34",
        "r3k2r/4npp1/1pq1p2p/p6b/PpP1PP1P/7N/2P1QP2/R1B2RK1 b kq - 0 18",
        "8/8/k7/1p6/2p5/2P1K3/8/8 b - - 0 67",
        "8/6b1/p1p4p/2P5/P1B2P1k/8/2K3P1/8 b - - 0 58",
        "6k1/4pp1p/3p2p1/P1pPb3/R7/1r2P1PP/3B1P2/6K1 w - - 0 40",
        "r4k2/pb2bp1r/2n1p2p/1pq3p1/3B1N2/2NQP1PP/PPP2PK1/R4R2 b - - 1 25",
        "3r2k1/1p3ppp/2pq4/p1n5/P6P/1P6/1PB2QP1/1K2R3 w - - 0 28",
    };

    /**
     * Run the benchmark at the given depth using the default (Classical) evaluator.
     *
     * @param depth search depth; must be in [1, 127]
     */
    public void run(int depth) {
        run(depth, null, "Classical");
    }

    /**
     * Run the benchmark at the given depth with a specific evaluator (issue #206:
     * evaluator selection in the benchmark path, so NNUE-mode NPS can be measured
     * against the same 31-position suite/depth as the classical baseline).
     *
     * <p>A fresh {@link Searcher} is created for each position so that killer
     * moves, history heuristic, and correction-history tables are all zeroed —
     * exactly the state produced by {@code ucinewgame} in the UCI loop.
     * The transposition table is also freshly allocated per position so
     * results are deterministic regardless of prior search history.
     *
     * <p>Search-time instrumentation (issue #216: eval latency, NNUE accumulator-
     * update cost, PV/cut-node eval frequency) is always enabled here and reported
     * in the summary — negligible cost relative to the rest of a fixed-depth bench
     * run, and this is exactly the tool #216's instrumentation exists to feed.
     *
     * @param depth             search depth; must be in [1, 127]
     * @param evaluatorFactory  supplies a fresh {@link EvaluatorStrategy} per
     *                          position (a new instance each time, since NNUE's
     *                          own evaluator is documented one-per-Searcher, never
     *                          shared); {@code null} keeps Searcher's own default
     *                          (Classical).
     * @param evaluatorLabel    printed in the summary header (e.g. "Classical" or
     *                          "NNUE (&lt;network-uuid&gt;)").
     */
    public void run(int depth, Supplier<EvaluatorStrategy> evaluatorFactory, String evaluatorLabel) {
        long totalNodes = 0L;
        long totalEvalNanos = 0L;
        long totalAccumulatorNanos = 0L;
        long totalPvNodeEvals = 0L;
        long totalCutNodeEvals = 0L;
        long startMs = System.currentTimeMillis();

        // Validate the entire suite before searching. An illegal FEN must be
        // removed from the source — silent runtime skips distort the NPS baseline.
        for (int i = 0; i < BENCH_FENS.length; i++) {
            if (!Board.isLegalFen(BENCH_FENS[i])) {
                throw new IllegalStateException(
                    "BENCH_FENS[" + i + "] is not a legal chess position: " + BENCH_FENS[i]);
            }
        }

        System.out.printf(Locale.US,
            "Bench   : depth %d | hash %d MB | %d positions | evaluator %s%n",
            depth, BENCH_HASH_MB, BENCH_FENS.length, evaluatorLabel);

        for (int i = 0; i < BENCH_FENS.length; i++) {
            // Fresh Searcher → killers, history, correction-history all zeroed.
            // setTranspositionTableSizeMb resizes the private 1 MB placeholder TT
            // that the default constructor creates, so there is no TT carry-over.
            Searcher searcher = new Searcher();
            searcher.setTranspositionTableSizeMb(BENCH_HASH_MB);
            if (evaluatorFactory != null) {
                searcher.setEvaluatorStrategy(evaluatorFactory.get());
            }
            searcher.setInstrumentationEnabled(true);

            Board board = new Board(BENCH_FENS[i]);
            board.setSearchMode(true);

            SearchResult result = searcher.searchDepth(board, depth);
            totalNodes += result.nodesVisited();
            totalEvalNanos += result.evalNanos();
            totalAccumulatorNanos += result.accumulatorNanos();
            totalPvNodeEvals += result.pvNodeEvals();
            totalCutNodeEvals += result.cutNodeEvals();

            System.out.printf(Locale.US,
                "  %2d/%d  nodes=%-12d  depth=%d%n",
                i + 1, BENCH_FENS.length, result.nodesVisited(), depth);
        }

        long elapsedMs = Math.max(1L, System.currentTimeMillis() - startMs);
        long nps       = totalNodes * 1_000L / elapsedMs;
        double elapsedNanos = elapsedMs * 1_000_000.0;
        double evalPct = elapsedNanos > 0 ? 100.0 * totalEvalNanos / elapsedNanos : 0.0;
        double accPct  = elapsedNanos > 0 ? 100.0 * totalAccumulatorNanos / elapsedNanos : 0.0;

        System.out.println();
        System.out.printf(Locale.US, "Nodes searched: %d%n", totalNodes);
        System.out.printf(Locale.US, "Time  : %d ms%n",      elapsedMs);
        System.out.printf(Locale.US, "NPS   : %d%n",         nps);
        System.out.printf(Locale.US, "Eval time: %.1f%% | Accumulator-update time: %.1f%% | PV evals: %d | Cut evals: %d%n",
            evalPct, accPct, totalPvNodeEvals, totalCutNodeEvals);
        System.out.flush();
    }
}
