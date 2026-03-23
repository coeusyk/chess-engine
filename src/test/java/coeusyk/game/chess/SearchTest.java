package coeusyk.game.chess;

import coeusyk.game.chess.models.Board;
import coeusyk.game.chess.models.Move;
import coeusyk.game.chess.utils.Search;
import coeusyk.game.chess.utils.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;


class SearchTest {

    // -------------------------------------------------------------------------
    // PV correctness
    // -------------------------------------------------------------------------

    @Test
    void pvIsNotEmptyAfterSearch() {
        Board board = new Board();
        Search search = new Search();

        SearchResult result = search.search(board, 2);

        assertNotNull(result.bestMove, "bestMove must not be null");
        assertFalse(result.pv.isEmpty(), "PV must not be empty");
    }

    @Test
    void pvFirstMoveMatchesBestMove() {
        Board board = new Board();
        Search search = new Search();

        SearchResult result = search.search(board, 2);

        assertNotNull(result.bestMove);
        assertFalse(result.pv.isEmpty());
        Move pvFirst = result.pv.get(0);
        assertEquals(result.bestMove.startSquare, pvFirst.startSquare,
                "First move in PV must equal bestMove startSquare");
        assertEquals(result.bestMove.targetSquare, pvFirst.targetSquare,
                "First move in PV must equal bestMove targetSquare");
    }

    // -------------------------------------------------------------------------
    // PV length bounded by search depth
    // -------------------------------------------------------------------------

    @Test
    void pvLengthBoundedBySearchDepth() {
        Board board = new Board();
        Search search = new Search();

        int maxDepth = 3;
        SearchResult result = search.search(board, maxDepth);

        assertTrue(result.pv.size() <= maxDepth,
                "PV length must not exceed search depth");
    }

    @Test
    void pvLengthEqualsSearchDepthFromStartingPosition() {
        Board board = new Board();
        Search search = new Search();

        int maxDepth = 2;
        SearchResult result = search.search(board, maxDepth);

        assertEquals(maxDepth, result.pv.size(),
                "PV should have exactly maxDepth moves from the starting position");
    }

    @Test
    void pvLengthIsOneForDepthOne() {
        Board board = new Board();
        Search search = new Search();

        SearchResult result = search.search(board, 1);

        assertEquals(1, result.pv.size(), "At depth 1 the PV should contain exactly one move");
    }

    // -------------------------------------------------------------------------
    // Reported depth
    // -------------------------------------------------------------------------

    @Test
    void reportedDepthMatchesMaxDepth() {
        Board board = new Board();
        Search search = new Search();

        int maxDepth = 2;
        SearchResult result = search.search(board, maxDepth);

        assertEquals(maxDepth, result.depth, "Reported depth must equal maxDepth");
    }

    // -------------------------------------------------------------------------
    // PV updated on every alpha improvement (indirect: deeper search improves PV)
    // -------------------------------------------------------------------------

    @Test
    void deeperSearchProducesPvWithMoreMoves() {
        Board board = new Board();

        SearchResult shallow = new Search().search(board, 1);
        SearchResult deep    = new Search().search(board, 2);

        assertTrue(deep.pv.size() > shallow.pv.size(),
                "A deeper search should produce a longer PV");
    }

    // -------------------------------------------------------------------------
    // UCI info line format
    // -------------------------------------------------------------------------

    @Test
    void uciInfoLineHasCorrectFormat() {
        Board board = new Board();
        Search search = new Search();

        SearchResult result = search.search(board, 1);

        assertNotNull(result.uciInfo, "uciInfo must not be null");
        assertTrue(result.uciInfo.startsWith("info depth "), "UCI info must start with 'info depth '");
        assertTrue(result.uciInfo.contains("score cp "), "UCI info must contain 'score cp '");
        assertTrue(result.uciInfo.contains("nodes "), "UCI info must contain 'nodes '");
        assertTrue(result.uciInfo.contains(" pv "), "UCI info must contain ' pv '");
    }

    @Test
    void uciInfoPvMovesAreInLongAlgebraicNotation() {
        Board board = new Board();
        Search search = new Search();

        SearchResult result = search.search(board, 1);

        String[] parts = result.uciInfo.split(" pv ");
        assertEquals(2, parts.length, "UCI info line must have a pv section");
        String[] pvMoves = parts[1].trim().split(" ");
        for (String mv : pvMoves) {
            assertTrue(mv.matches("[a-h][1-8][a-h][1-8]"),
                    "Each PV move must be in long-algebraic format (e.g. e2e4), got: " + mv);
        }
    }

    // -------------------------------------------------------------------------
    // moveToUci helper
    // -------------------------------------------------------------------------

    @Test
    void moveToUciConvertsSquaresCorrectly() {
        // e2 = square 52, e4 = square 36
        Move e2e4 = new Move(52, 36);
        assertEquals("e2e4", Search.moveToUci(e2e4));

        // a8 = square 0, a1 = square 56
        Move a8a1 = new Move(0, 56);
        assertEquals("a8a1", Search.moveToUci(a8a1));

        // h1 = square 63, h8 = square 7
        Move h1h8 = new Move(63, 7);
        assertEquals("h1h8", Search.moveToUci(h1h8));
    }
}
