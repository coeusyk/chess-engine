package coeusyk.game.chess.core.movegen;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.models.Piece;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PromotionHandlingTest {

    @Test
    void whitePromotionGeneratesAllFourPromotionChoices() {
        Board board = new Board("4k3/P7/8/8/8/8/8/4K3 w - - 0 1");

        MovesGenerator movesGenerator = new MovesGenerator(board);
        ArrayList<Move> pawnMoves = movesGenerator.getPieceMoves(8);

        assertPromotionSetPresent(pawnMoves, 8, 0);
    }

    @Test
    void blackPromotionGeneratesAllFourPromotionChoices() {
        Board board = new Board("4k3/8/8/8/8/8/7p/4K3 b - - 0 1");

        MovesGenerator movesGenerator = new MovesGenerator(board);
        ArrayList<Move> pawnMoves = movesGenerator.getPieceMoves(55);

        assertPromotionSetPresent(pawnMoves, 55, 63);
    }

    @Test
    void promotionMoveUpdatesBoardToPromotedPiece() {
        Board board = new Board("4k3/P7/8/8/8/8/8/4K3 w - - 0 1");

        board.makeMove(new Move(8, 0, "promote-n"));

        assertEquals(Piece.White | Piece.Knight, board.getPiece(0));
        assertEquals(Piece.None, board.getPiece(8));
    }

    @Test
    void unmakePromotionRestoresPawnOnOriginalSquare() {
        Board board = new Board("4k3/P7/8/8/8/8/8/4K3 w - - 0 1");

        board.makeMove(new Move(8, 0, "promote-q"));
        board.unmakeMove();

        assertEquals(Piece.White | Piece.Pawn, board.getPiece(8));
        assertEquals(Piece.None, board.getPiece(0));
    }

    private void assertPromotionSetPresent(ArrayList<Move> moves, int startSquare, int targetSquare) {
        List<String> expectedReactions = List.of("promote-q", "promote-r", "promote-b", "promote-n");

        for (String reaction : expectedReactions) {
            boolean found = moves.stream().anyMatch(move ->
                    move.startSquare == startSquare
                            && move.targetSquare == targetSquare
                            && reaction.equals(move.reaction));
            assertTrue(found, "missing promotion reaction: " + reaction);
        }
    }
}
