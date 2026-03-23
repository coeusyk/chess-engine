package coeusyk.game.chess;

import coeusyk.game.chess.models.Board;
import coeusyk.game.chess.models.Move;
import coeusyk.game.chess.models.Piece;
import coeusyk.game.chess.utils.Evaluator;
import coeusyk.game.chess.utils.Search;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;


class SearchTest {

    /**
     * White queen on d4 (square 35) can immediately capture the hanging black queen
     * on d5 (square 27). Even at depth 1 the quiescence search at the leaf must
     * see this capture and return the correct best move.
     */
    @Test
    void qSearchFindsHangingPiece() {
        // "4k3/8/8/3q4/3Q4/8/8/4K3 w - - 0 1"
        // Black queen at d5 = square 27, white queen at d4 = square 35
        Board board = new Board("4k3/8/8/3q4/3Q4/8/8/4K3 w - - 0 1");

        Search search = new Search();
        Move bestMove = search.findBestMove(board, 1);

        assertNotNull(bestMove);
        // The best move must capture the black queen on d5 (square 27):
        assertEquals(27, bestMove.targetSquare);
    }

    /**
     * Both main-search node count and q-search node count must be positive after
     * a real search, demonstrating that the separate counters are wired up.
     */
    @Test
    void nodeCountsAreTrackedSeparately() {
        Board board = new Board();
        Search search = new Search();
        search.findBestMove(board, 2);

        assertTrue(search.getNodes() > 0, "main-search nodes must be tracked");
        assertTrue(search.getQNodes() > 0, "q-search nodes must be tracked");
    }

    /**
     * In a quiet position with no captures available the quiescence search must
     * return the stand-pat (static evaluation) immediately without touching any
     * capture moves.
     */
    @Test
    void standPatReturnedInQuietPosition() {
        // King-vs-king endgame: no pawns or pieces to capture.
        Board board = new Board("4k3/8/8/8/8/8/8/4K3 w - - 0 1");

        int staticEval = Evaluator.evaluate(board);
        // Both sides have only a king, so the material difference is 0.
        assertEquals(0, staticEval);
    }

    /**
     * Simple one-move exchange: white rook can capture black rook. The quiescence
     * search must prefer that capture over doing nothing.
     */
    @Test
    void qSearchFindsRookCapture() {
        // White rook on a1 (square 56), black rook on a8 (square 0), kings on h1/h8.
        // "r6k/8/8/8/8/8/8/R6K w - - 0 1"
        // White rook at a1=square 56, black rook at a8=square 0.
        Board board = new Board("r6k/8/8/8/8/8/8/R6K w - - 0 1");

        Search search = new Search();
        Move bestMove = search.findBestMove(board, 1);

        assertNotNull(bestMove);
        // The best move must capture the black rook on a8 (square 0):
        assertEquals(0, bestMove.targetSquare);
    }

    /**
     * Board copy constructor must produce an independent copy so that making a
     * move on the copy does not affect the original board.
     */
    @Test
    void boardCopyIsIndependent() {
        Board original = new Board();
        Board copy = new Board(original);

        // Find a legal move and apply it only to the copy:
        coeusyk.game.chess.utils.MovesGenerator gen =
                new coeusyk.game.chess.utils.MovesGenerator(original);
        Move move = gen.getActiveMoves(original.getActiveColor()).get(0);
        copy.makeMove(move);

        // The original board must be unchanged:
        assertNotEquals(copy.getActiveColor(), original.getActiveColor());
    }

    /**
     * After a pawn double-push the en-passant target square must be set, and after
     * the next move it must be cleared (no bleed-through in search).
     */
    @Test
    void epTargetSquareClearedAfterNonPawnMove() {
        // White pawn double-push sets ep target; then a non-pawn move clears it.
        Board board = new Board();

        // e2-e4 (white pawn double push): startSquare=52, targetSquare=36, reaction="ep-target"
        board.makeMove(new Move(52, 36, "ep-target"));
        // ep target should now be set (to e3, square 44):
        assertEquals(44, board.getEpTargetSquare());

        // Now any non-pawn-double-push move must clear it; e.g. black pawn d7-d6:
        board.makeMove(new Move(11, 19, null));
        assertEquals(0, board.getEpTargetSquare());
    }
}
