package coeusyk.game.chess.core.movegen;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.models.Piece;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class EnPassantLegalityTest {

    @Test
    void doublePawnPushSetsEnPassantTargetSquare() {
        Board board = new Board("4k3/8/8/8/8/8/4P3/4K3 w - - 0 1");

        board.makeMove(new Move(52, 36, "ep-target"));

        assertEquals(44, board.getEpTargetSquare());
    }

    @Test
    void enPassantMoveGeneratedWhenTargetAndEnemyPawnAreValid() {
        Board board = new Board("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1");

        MovesGenerator movesGenerator = new MovesGenerator(board);
        ArrayList<Move> pawnMoves = movesGenerator.getPieceMoves(28);

        assertTrue(containsMove(pawnMoves, 28, 19, "en-passant"));
    }

    @Test
    void enPassantCaptureRemovesCorrectPawn() {
        Board board = new Board("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1");

        board.makeMove(new Move(28, 19, "en-passant"));

        assertEquals(Piece.None, board.getPiece(27));
        assertEquals(Piece.White | Piece.Pawn, board.getPiece(19));
    }

    @Test
    void enPassantNotGeneratedWhenItExposesOwnKingToCheck() {
        Board board = new Board("k7/8/8/r4pPK/8/8/8/8 w - f6 0 1");

        MovesGenerator movesGenerator = new MovesGenerator(board);
        ArrayList<Move> pawnMoves = movesGenerator.getPieceMoves(30);

        assertFalse(containsMove(pawnMoves, 30, 21, "en-passant"));
    }

    private boolean containsMove(ArrayList<Move> moves, int startSquare, int targetSquare, String reaction) {
        return moves.stream().anyMatch(move ->
                move.startSquare == startSquare
                        && move.targetSquare == targetSquare
                        && reaction.equals(move.reaction));
    }
}
