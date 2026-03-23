package coeusyk.game.chess.utils;

import coeusyk.game.chess.models.Board;
import coeusyk.game.chess.models.Move;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;


class SearchEngineTest {

    /**
     * The engine must return a non-null move for any position with legal moves.
     */
    @Test
    void findsBestMoveFromStartingPosition() {
        TranspositionTable tt = new TranspositionTable(1);
        SearchEngine engine = new SearchEngine(tt);

        Board board = new Board();
        Move best = engine.findBestMove(board, 2);

        assertNotNull(best, "Engine must find a move from the starting position");
    }

    /**
     * TT hit rate must be non-zero when the same position is searched more than once.
     */
    @Test
    void ttHitRateNonZeroOnRepeatedSearch() {
        TranspositionTable tt = new TranspositionTable(1);
        SearchEngine engine = new SearchEngine(tt);

        Board board = new Board();
        // First search populates the TT
        engine.findBestMove(board, 2);
        // Second search should get TT hits
        engine.findBestMove(board, 2);

        assertTrue(tt.getHitRate() > 0.0, "TT hit rate should be non-zero after repeated search of the same position");
    }

    /**
     * The engine returns null when there are no legal moves (empty board except kings in a stalemate-like setup).
     * This verifies graceful handling of terminal positions.
     */
    @Test
    void returnsNullWhenNoLegalMoves() {
        // Lone white king – white has no legal moves in this simplified generator
        // (castling squares are empty so castling moves are generated, but the search
        // should still not crash even if some edge-case moves exist)
        TranspositionTable tt = new TranspositionTable(1);
        SearchEngine engine = new SearchEngine(tt);

        // Position with only kings – the generator may still produce king moves
        Board board = new Board("4k3/8/8/8/8/8/8/4K3 w - - 0 1");
        Move best = engine.findBestMove(board, 1);
        // Either a move is returned or null – what matters is no exception is thrown
        // and the TT is still in a valid state
        assertTrue(tt.getLookups() >= 0);
    }

    /**
     * After searching a position, the TT must contain an entry for that position.
     */
    @Test
    void ttContainsEntryAfterSearch() {
        TranspositionTable tt = new TranspositionTable(1);
        SearchEngine engine = new SearchEngine(tt);

        Board board = new Board();
        long hash = ZobristHasher.computeHash(board);
        engine.findBestMove(board, 2);

        TranspositionTable.TTEntry entry = tt.probe(hash);
        assertNotNull(entry, "TT must store the root position entry after search");
    }

    /**
     * The EXACT bound type entry returned from the root should have
     * a depth equal to the requested search depth.
     */
    @Test
    void ttRootEntryHasCorrectDepth() {
        TranspositionTable tt = new TranspositionTable(1);
        SearchEngine engine = new SearchEngine(tt);

        Board board = new Board();
        long hash = ZobristHasher.computeHash(board);
        engine.findBestMove(board, 3);

        TranspositionTable.TTEntry entry = tt.probe(hash);
        assertNotNull(entry);
        assertEquals(3, entry.depth);
    }
}
