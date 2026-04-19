package coeusyk.game.chess.uci;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.search.SearchResult;
import coeusyk.game.chess.core.search.Searcher;

import java.util.Locale;

/**
 * Standard fixed-depth benchmark for Vex.
 *
 * <p>Runs the engine at a fixed depth over a canonical set of 33 positions
 * (Stockfish bench suite, public domain) and reports total nodes searched,
 * elapsed wall time, and NPS.
 *
 * <p>Usage:
 * <pre>
 *   java -jar engine-uci-shaded.jar --bench [depth]
 *   # or, inside a UCI session:
 *   bench [depth]
 * </pre>
 *
 * <p><b>NPS is hardware-dependent</b> and varies with CPU speed, JIT warmup,
 * and OS scheduling. It must NOT be used as a pass/fail gate. Use node counts
 * at a given depth to verify search correctness across builds.
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
     * The 33-position Stockfish bench suite (public domain).
     * Positions cover a wide range of middle-game structures, endgames, and
     * tactical motifs to exercise all major search and evaluation branches.
     */
    private static final String[] BENCH_FENS = {
        "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
        "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
        "4k3/8/8/8/8/8/4P3/4K3 w - - 5 39",
        "8/8/8/8/8/5k2/5p2/5K2 w - - 0 67",
        "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1",
        "rnbqkbnr/p1pppppp/8/1p6/2PP4/8/PP2PPPP/RNBQKBNR b KQkq c3 0 2",
        "r1bq1r1k/1pp1n1pp/1p1p4/4p2b/2B1P3/P1N1NP2/1PP3PP/R1BQ1RK1 w - - 2 14",
        "r3r1k1/2p2ppp/p1p1bn2/8/1q2P3/2NPQN2/PPP3PP/R4RK1 b - - 2 24",
        "r1bb4/3n1k2/p3p3/1p1pPp2/1Pp5/2P2N1B/P4PPP/4RK2 w - - 2 21",
        "3r4/8/1q6/2p5/pp1P3k/P4P1p/1P2R1r1/7K w - - 0 45",
        "6k1/3r4/2R5/P5pp/3b4/6P1/5PK1/8 w - - 1 45",
        "r1bq1rk1/pp2ppbp/2np1np1/8/3NP3/2N1BP2/PPPQ2PP/R4RK1 b - - 0 10",
        "8/p7/1p2k1p1/2p5/2P1b3/1P3P2/P2K4/8 b - - 0 38",
        "8/8/6k1/8/5P2/4K1P1/8/8 b - - 0 65",
        "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
        "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NpPP/RNBQK2R w KQ - 1 8",
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
        "1Q6/5pk1/2p3p1/1p2N2p/1b5P/1bn5/2r3P1/2K5 b - - 0 44",
        "8/8/k7/1p6/2p5/2P1K3/8/8 b - - 0 67",
        "8/6b1/p1p4p/2P5/P1B2P1k/8/2K3P1/8 b - - 0 58",
        "6k1/4pp1p/3p2p1/P1pPb3/R7/1r2P1PP/3B1P2/6K1 w - - 0 40",
        "r4k2/pb2bp1r/2n1p2p/1pq3p1/3B1N2/2NQP1PP/PPP2PK1/R4R2 b - - 1 25",
        "3r2k1/1p3ppp/2pq4/p1n5/P6P/1P6/1PB2QP1/1K2R3 w - - 0 28",
    };

    /**
     * Run the benchmark at the given depth.
     *
     * <p>A fresh {@link Searcher} is created for each position so that killer
     * moves, history heuristic, and correction-history tables are all zeroed —
     * exactly the state produced by {@code ucinewgame} in the UCI loop.
     * The transposition table is also freshly allocated per position so
     * results are deterministic regardless of prior search history.
     *
     * @param depth search depth; must be in [1, 127]
     */
    public void run(int depth) {
        long totalNodes = 0L;
        long startMs    = System.currentTimeMillis();

        System.out.printf(Locale.US,
            "Bench   : depth %d | hash %d MB | %d positions%n",
            depth, BENCH_HASH_MB, BENCH_FENS.length);

        for (int i = 0; i < BENCH_FENS.length; i++) {
            // Fresh Searcher → killers, history, correction-history all zeroed.
            // setTranspositionTableSizeMb resizes the private 1 MB placeholder TT
            // that the default constructor creates, so there is no TT carry-over.
            Searcher searcher = new Searcher();
            searcher.setTranspositionTableSizeMb(BENCH_HASH_MB);

            Board board = new Board(BENCH_FENS[i]);
            board.setSearchMode(true);

            SearchResult result = searcher.searchDepth(board, depth);
            totalNodes += result.nodesVisited();

            System.out.printf(Locale.US,
                "  %2d/%d  nodes=%-12d  depth=%d%n",
                i + 1, BENCH_FENS.length, result.nodesVisited(), depth);
        }

        long elapsedMs = Math.max(1L, System.currentTimeMillis() - startMs);
        long nps       = totalNodes * 1_000L / elapsedMs;

        System.out.println();
        System.out.printf(Locale.US, "Nodes searched: %d%n", totalNodes);
        System.out.printf(Locale.US, "Time  : %d ms%n",      elapsedMs);
        System.out.printf(Locale.US, "NPS   : %d%n",         nps);
        System.out.flush();
    }
}
