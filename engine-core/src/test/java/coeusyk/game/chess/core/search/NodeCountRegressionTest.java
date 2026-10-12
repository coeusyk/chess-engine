package coeusyk.game.chess.core.search;

import coeusyk.game.chess.core.models.Board;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins exact depth-8 node counts on five positions from {@code BenchRunner}'s
 * suite for the Phase 21 PVS search shape.
 */
class NodeCountRegressionTest {

    private static final int DEPTH = 8;

    private static final String[] FENS = {
        "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
        "4k3/8/8/8/8/8/4P3/4K3 w - - 5 39",
        "r3r1k1/2p2ppp/p1p1bn2/8/1q2P3/2NPQN2/PPP3PP/R4RK1 b - - 2 24",
        "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
        "r4rk1/3qppbp/6p1/2p1n3/2B1P3/2P5/P2Q1PPP/R4RK1 b - - 0 20",
    };

    private static final long[] EXPECTED_NODES = {
        14776,
        1287,
        32267,
        6136,
        7829,
    };

    @Test
    void nodeCountsAreStableForPhase21Pvs() {
        for (int i = 0; i < FENS.length; i++) {
            Searcher searcher = new Searcher();
            searcher.setTranspositionTableSizeMb(16);

            Board board = new Board(FENS[i]);
            board.setSearchMode(true);

            SearchResult result = searcher.searchDepth(board, DEPTH);

            assertEquals(EXPECTED_NODES[i], result.nodesVisited(),
                "Node count drifted for FEN[" + i + "]=" + FENS[i]
                    + " — a search-shape change occurred. If intentional, "
                    + "re-capture golden values; otherwise this is a regression.");
        }
    }
}
