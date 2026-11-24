package coeusyk.game.chess.core.search;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.movegen.MovesGenerator;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hard stop is polled once per STOP_POLL_INTERVAL nodes and not at every node. These tests pin what that must
 * not change: a stop that arrives mid-search still ends the search promptly with a legal move, a timed search stays
 * near its deadline, a search that is never stopped is unaffected by the supplier, and a stop is also seen inside
 * a quiescence-heavy position.
 */
class StopPollingSemanticsTest {
    private static final String KIWIPETE = "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1";
    private static final String START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    private static boolean isLegal(Board board, Move move) {
        return new MovesGenerator(board).getActiveMoves(board.getActiveColor()).stream()
                .anyMatch(m -> m.startSquare == move.startSquare && m.targetSquare == move.targetSquare);
    }

    @Test
    void aStopThatArrivesMidSearchEndsItPromptlyWithALegalMove() {
        Board board = new Board(KIWIPETE);
        long deadline = System.nanoTime() + 150_000_000L;

        long start = System.nanoTime();
        SearchResult result = new Searcher().iterativeDeepening(board, 64, () -> false,
                () -> System.nanoTime() >= deadline, info -> { });
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertTrue(result.aborted(), "a search cut off at depth 64 must report that it was aborted");
        assertNotNull(result.bestMove(), "an aborted search must still return a move");
        assertTrue(isLegal(new Board(KIWIPETE), result.bestMove()), "the move must be legal");
        assertTrue(elapsedMs < 1500, "the stop was seen after " + elapsedMs + " ms, expected well under 1500");
    }

    @Test
    void aTimedSearchStaysNearItsDeadline() {
        TimeManager time = new TimeManager();
        time.configureMovetime(200);

        long start = System.nanoTime();
        SearchResult result = new Searcher().searchWithTimeManager(new Board(KIWIPETE), 64, time);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertNotNull(result.bestMove());
        assertTrue(elapsedMs < 1200, "movetime 200 took " + elapsedMs + " ms");
    }

    @Test
    void aSearchThatIsNeverStoppedDoesNotDependOnTheStopSupplier() {
        AtomicInteger calls = new AtomicInteger();
        SearchResult plain = new Searcher().iterativeDeepening(new Board(KIWIPETE), 5);
        SearchResult polled = new Searcher().iterativeDeepening(new Board(KIWIPETE), 5, () -> false,
                () -> { calls.incrementAndGet(); return false; }, info -> { });

        assertTrue(calls.get() > 0, "the supplier must have been consulted");
        assertEquals(plain.nodesVisited(), polled.nodesVisited(), "polling must not change the nodes searched");
        assertEquals(plain.scoreCp(), polled.scoreCp());
        assertEquals(plain.bestMove().startSquare, polled.bestMove().startSquare);
        assertEquals(plain.bestMove().targetSquare, polled.bestMove().targetSquare);
        assertTrue(!polled.aborted());
    }

    @Test
    void aPendingStopIsSeenInACaptureHeavyPositionBeforeAnyNodesAreSearched() {
        // Kiwipete is rich in captures, so the quiescence search is reached early. A stop that is already
        // pending must be honoured on the first poll. Searching nothing and returning no move is the behaviour
        // before the throttle too (measured on develop: aborted, 0 nodes, no move), and the UCI layer supplies
        // the fallback move, so that is what is pinned here and not a move.
        SearchResult result = new Searcher().iterativeDeepening(new Board(KIWIPETE), 30, () -> true, () -> true, info -> { });

        assertTrue(result.aborted());
        assertTrue(result.nodesVisited() < 5_000, "a pending stop searched " + result.nodesVisited() + " nodes");
    }

    @Test
    void theCounterRestartsForEverySearchOnTheSameSearcher() {
        // A second search on a reused Searcher must also see a stop at its first poll, not after however many
        // nodes were left of the previous counter window.
        Searcher searcher = new Searcher();
        searcher.iterativeDeepening(new Board(START), 4);

        SearchResult stopped = searcher.iterativeDeepening(new Board(START), 30, () -> true, () -> true, info -> { });
        assertTrue(stopped.aborted());
        assertTrue(stopped.nodesVisited() < 5_000, "the second search ran " + stopped.nodesVisited() + " nodes");
    }
}
