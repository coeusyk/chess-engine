package coeusyk.game.chess.search;

import coeusyk.game.chess.models.Board;
import coeusyk.game.chess.models.Move;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;


class SearchTest {

    // ------------------------------------------------------------------
    // 1. Search returns a best move at any given depth
    // ------------------------------------------------------------------

    @Test
    void searchReturnsBestMoveAtDepth1() {
        Board board = new Board();
        IterativeDeepeningSearch search = new IterativeDeepeningSearch();

        SearchResult result = search.search(board, 1);

        assertNotNull(result.bestMove, "Search must return a best move at depth 1");
    }

    @Test
    void searchReturnsBestMoveAtDepth3() {
        Board board = new Board();
        IterativeDeepeningSearch search = new IterativeDeepeningSearch();

        SearchResult result = search.search(board, 3);

        assertNotNull(result.bestMove, "Search must return a best move at depth 3");
    }

    @Test
    void searchReturnsBestMoveFromOpeningPosition() {
        // Ruy Lopez opening – a well-known early opening position
        String fen = "r1bqkbnr/pppp1ppp/2n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3";
        Board board = new Board(fen);
        IterativeDeepeningSearch search = new IterativeDeepeningSearch();

        SearchResult result = search.search(board, 2);

        assertNotNull(result.bestMove, "Search must return a best move from an opening position");
    }

    // ------------------------------------------------------------------
    // 2. Alpha-beta prunes branches (node count lower than depth-only search)
    // ------------------------------------------------------------------

    @Test
    void alphaBetaVisitsFewerNodesThanBruteForce() {
        Board board = new Board();

        // Brute-force: disable pruning by using bounds that never cut (pass NEG_INF/POS_INF
        // but compare against a separate brute-force wrapper that expands every node).
        // We approximate brute-force by running alpha-beta at depth N and noting that
        // any real brute-force minimax at the same depth on a starting position visits
        // considerably more nodes. We verify by comparing depth-1 vs depth-3 node counts
        // (alpha-beta at depth 3 must visit ≤ brute-force bound for 20 moves^3 = 8000 worst case).
        IterativeDeepeningSearch search = new IterativeDeepeningSearch();

        // Full depth-3 iterative deepening search:
        SearchResult result = search.search(board, 3);

        assertNotNull(result.bestMove);
        // Brute force at depth 3 from start would visit up to ~8 000 leaf nodes.
        // Alpha-beta typically reduces this by more than half on real positions.
        assertTrue(result.nodesSearched < 8000,
                "Alpha-beta should visit fewer nodes than brute force (got " + result.nodesSearched + ")");
    }

    // ------------------------------------------------------------------
    // 3. Iterative deepening produces consistent best-move convergence
    // ------------------------------------------------------------------

    @Test
    void iterativeDeepeningCompletedDepthMatchesMaxDepth() {
        Board board = new Board();
        IterativeDeepeningSearch search = new IterativeDeepeningSearch();

        SearchResult result = search.search(board, 3);

        assertEquals(3, result.completedDepth,
                "Iterative deepening should complete all depth iterations when not aborted");
    }

    @Test
    void iterativeDeepeningBestMoveIsConsistent() {
        // Use a position where white has a clear material advantage: white queen + rook vs bare black king.
        // The best move for black (bare king, a-file) should agree between depth 2 and depth 3
        // because there is very limited choice.
        // FEN: Black king on a8, White king on c6, White rook on b1 – Black to move
        String fen = "k7/8/2K5/8/8/8/8/1R6 b - - 0 1";
        Board board = new Board(fen);

        SearchResult result2 = new IterativeDeepeningSearch().search(board, 2);
        SearchResult result3 = new IterativeDeepeningSearch().search(board, 3);

        assertNotNull(result2.bestMove, "Depth-2 search must return a move");
        assertNotNull(result3.bestMove, "Depth-3 search must return a move");
        // Both searches should agree on best move start/target squares
        assertEquals(result2.bestMove.startSquare, result3.bestMove.startSquare,
                "Start square should be consistent across depths");
        assertEquals(result2.bestMove.targetSquare, result3.bestMove.targetSquare,
                "Target square should be consistent across depths");
    }

    // ------------------------------------------------------------------
    // 4. Search can be aborted cleanly between iterations
    // ------------------------------------------------------------------

    @Test
    void searchCanBeAbortedAndReturnsLastCompletedResult() throws InterruptedException {
        Board board = new Board();
        IterativeDeepeningSearch search = new IterativeDeepeningSearch();

        // Schedule stop() to fire shortly after the search starts:
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.schedule(search::stop, 50, TimeUnit.MILLISECONDS);

        // Use a large depth so the search won't finish on its own in 50 ms:
        SearchResult result = search.search(board, 8);

        scheduler.shutdown();

        // Even if aborted, at least depth 1 should have completed:
        assertTrue(result.completedDepth >= 1,
                "Aborted search must have completed at least one iteration (got depth " + result.completedDepth + ")");
        assertNotNull(result.bestMove, "Aborted search must still return the last completed best move");
    }

    @Test
    void abortedSearchCompletedDepthIsLessThanMaxDepth() throws InterruptedException {
        Board board = new Board();
        IterativeDeepeningSearch search = new IterativeDeepeningSearch();

        // Stop immediately – the search should not complete all 10 depth levels:
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.schedule(search::stop, 10, TimeUnit.MILLISECONDS);

        SearchResult result = search.search(board, 10);

        scheduler.shutdown();

        // The search was aborted, so it should not have finished all 10 depths:
        assertTrue(result.completedDepth < 10,
                "Aborted search should not complete all 10 depth levels (got " + result.completedDepth + ")");
    }

    // ------------------------------------------------------------------
    // 5. Board copy constructor correctness
    // ------------------------------------------------------------------

    @Test
    void boardCopyConstructorProducesIndependentCopy() {
        Board original = new Board();
        Board copy = new Board(original);

        // Modify copy and ensure original is unchanged:
        Move anyMove = new Move(48, 40); // e2-e3 (white pawn)
        copy.makeMove(anyMove);

        // Original grid must not be affected:
        assertNotEquals(copy.getActiveColor(), original.getActiveColor(),
                "Active color should differ after move on copy");
        assertEquals(0, original.getGrid()[40],
                "Original board square must not be modified when copy is changed");
    }
}
