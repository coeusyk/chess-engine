package coeusyk.game.chess.core.search;

import coeusyk.game.chess.core.models.Board;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** #270 and #271: the overhead removals must not change what the search reports. */
class SearchOverheadTest {
    private static final int MATE_SCORE = 100_000;
    private static final String START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    @Test
    void aMatedRootScoresAsMateAndAStalemateRootScoresAsADraw() {
        SearchResult mated = new Searcher().iterativeDeepening(new Board("7k/6Q1/6K1/8/8/8/8/8 b - - 0 1"), 3);
        assertNull(mated.bestMove());
        assertEquals(-MATE_SCORE, mated.scoreCp());

        SearchResult stalemate = new Searcher().iterativeDeepening(new Board("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1"), 3);
        assertNull(stalemate.bestMove());
        assertEquals(0, stalemate.scoreCp());
    }

    @Test
    void mateInOneAndTheStalemateTrapStillScoreFromInteriorNodes() {
        // Qg7 mates: the node after it has no legal move and the king is in check.
        SearchResult mateInOne = new Searcher().iterativeDeepening(new Board("7k/8/5K2/8/8/8/6Q1/8 w - - 0 1"), 3);
        assertEquals(MATE_SCORE - 1, mateInOne.scoreCp());

        // Every white move but Qg6 or Qf7 keeps the game going. A search that treated stalemate as
        // checkmate would prefer these stalemating moves, so the winning side must not choose them.
        SearchResult trap = new Searcher().iterativeDeepening(new Board("7k/8/5K2/8/8/8/6Q1/8 w - - 0 1"), 4);
        assertTrue(trap.scoreCp() > 1000);
    }

    @Test
    void theHardStopSupplierIsPolledOncePerManyNodes() {
        AtomicInteger polls = new AtomicInteger();
        Searcher searcher = new Searcher();
        Board board = new Board(START);
        board.setSearchMode(true);
        SearchResult result = searcher.iterativeDeepening(board, 6, () -> false, () -> {
            polls.incrementAndGet();
            return false;
        }, info -> { });
        long nodes = result.nodesVisited() + result.quiescenceNodes();
        assertTrue(nodes > 5_000, "the search must be big enough to show the interval, nodes=" + nodes);
        // Before the change every node polled, so polls was about equal to nodes. The root still polls at
        // each root move and each iteration, which is why the bound is a tenth and not a thousandth.
        assertTrue(polls.get() * 10L < nodes, "polls=" + polls.get() + " nodes=" + nodes);
    }

    @Test
    void aPendingStopIsStillSeenAtOnceAndATimedSearchStopsPromptly() {
        long started = System.nanoTime();
        TimeManager tm = new TimeManager();
        tm.configureMovetime(50);
        Searcher searcher = new Searcher();
        SearchResult result = searcher.searchWithTimeManager(new Board(START), 40, tm);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        assertNotNull(result.bestMove());
        assertTrue(elapsedMs < 1500, "a 50 ms movetime search took " + elapsedMs + " ms");

        Searcher stopped = new Searcher();
        SearchResult immediate = stopped.iterativeDeepening(new Board(START), 40, () -> true, () -> true, info -> { });
        assertTrue(immediate.nodesVisited() < 5_000, "a stop already pending must end the search quickly");
    }
}
