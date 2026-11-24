package coeusyk.game.chess.core.search;

import coeusyk.game.chess.core.models.Board;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Search mode (#307) makes Board.makeMove and unmakeMove skip the per-move FEN history, which API analysis and the
 * self-play speculative search no longer need. Both only toggle the flag and then run the same Searcher, so the
 * workload is the same fixed-depth search with the flag on and off. The UCI jar cannot show it.
 *
 * <p>The first test always runs: the flag must not change the search at all. The second is the measurement
 * harness, off by default, run with {@code -Dworkload.run=true} (optional {@code -Dworkload.depth},
 * {@code -Dworkload.rounds}). It alternates the two modes within each round, in a rotating order, and prints the
 * per-round times and the ratio. It asserts nothing about speed and must only be run on an idle host.
 */
class SearchModeWorkloadTest {
    static final String[] FENS = {
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
            "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
            "r4rk1/pp3ppp/2n1b3/8/8/2N1B3/PP3PPP/R4RK1 w - - 0 1",
            "r1bqkb1r/pppp1ppp/2n2n2/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 4 4",
            "6k1/5p2/6p1/8/7P/6P1/5P2/6K1 w - - 0 1",
    };

    static SearchResult run(String fen, int depth, boolean searchMode) {
        Board board = new Board(fen);
        board.setSearchMode(searchMode);
        return new Searcher().iterativeDeepening(board, depth);
    }

    @Test
    void searchModeDoesNotChangeTheSearch() {
        for (String fen : FENS) {
            SearchResult normal = run(fen, 5, false);
            SearchResult fast = run(fen, 5, true);
            assertEquals(normal.nodesVisited(), fast.nodesVisited(), "nodes differ for " + fen);
            assertEquals(normal.scoreCp(), fast.scoreCp(), "score differs for " + fen);
            assertEquals(normal.bestMove().startSquare, fast.bestMove().startSquare, "move differs for " + fen);
            assertEquals(normal.bestMove().targetSquare, fast.bestMove().targetSquare, "move differs for " + fen);
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "workload.run", matches = "true")
    void searchModeWorkload() {
        int depth = Integer.getInteger("workload.depth", 7);
        int rounds = Integer.getInteger("workload.rounds", 5);
        run(FENS[0], 4, false); // warm-up of both paths
        run(FENS[0], 4, true);
        List<Double> ratios = new ArrayList<>();
        long nodesNormal = 0, nodesFast = 0;
        for (int round = 0; round < rounds; round++) {
            boolean fastFirst = round % 2 == 1;
            long normalNanos = 0, fastNanos = 0;
            nodesNormal = 0;
            nodesFast = 0;
            for (boolean fast : fastFirst ? new boolean[] {true, false} : new boolean[] {false, true}) {
                long start = System.nanoTime();
                long nodes = 0;
                for (String fen : FENS) {
                    nodes += run(fen, depth, fast).nodesVisited();
                }
                long elapsed = System.nanoTime() - start;
                if (fast) { fastNanos = elapsed; nodesFast = nodes; } else { normalNanos = elapsed; nodesNormal = nodes; }
            }
            double ratio = (double) normalNanos / fastNanos;
            ratios.add(ratio);
            System.out.printf("WORKLOAD round=%d depth=%d normal=%.0fms searchMode=%.0fms ratio=%.3f nodes=%d/%d%n",
                    round, depth, normalNanos / 1e6, fastNanos / 1e6, ratio, nodesNormal, nodesFast);
        }
        Collections.sort(ratios);
        System.out.printf("WORKLOAD SUMMARY depth=%d rounds=%d median ratio normal/searchMode=%.3f (nodes equal=%b)%n",
                depth, rounds, ratios.get(ratios.size() / 2), nodesNormal == nodesFast);
        assertEquals(nodesNormal, nodesFast, "the flag must not change the node count");
    }
}
