package coeusyk.game.chess.core.movegen;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class CastlingRestrictionsTest {

    @Test
    void kingMoveClearsBothCastlingRightsForThatSide() {
        Board board = new Board("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1");

        board.makeMove(new Move(60, 61));

        boolean[] rights = board.getCastlingAvailability();
        assertFalse(rights[0]);
        assertFalse(rights[1]);
        assertTrue(rights[2]);
        assertTrue(rights[3]);
    }

    @Test
    void rookMoveClearsOnlyMatchingSideCastlingRight() {
        Board board = new Board("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1");

        board.makeMove(new Move(63, 62));

        boolean[] rights = board.getCastlingAvailability();
        assertFalse(rights[0]);
        assertTrue(rights[1]);
        assertTrue(rights[2]);
        assertTrue(rights[3]);
    }

    @Test
    void rookCaptureOnCornerClearsOpponentCastlingRight() {
        Board board = new Board("4k2r/8/8/8/8/8/8/4K2R b K - 0 1");

        board.makeMove(new Move(7, 63));

        boolean[] rights = board.getCastlingAvailability();
        assertFalse(rights[0]);
    }

    @Test
    void castlingNotGeneratedWhenKingIsInCheck() {
        Board board = new Board("4r1k1/8/8/8/8/8/8/4K2R w K - 0 1");

        MovesGenerator movesGenerator = new MovesGenerator(board);
        ArrayList<Move> kingMoves = movesGenerator.getPieceMoves(60);

        assertFalse(containsMove(kingMoves, 60, 62, "castle-k"));
    }

    @Test
    void castlingNotGeneratedWhenPassingThroughAttackedSquare() {
        Board board = new Board("5rk1/8/8/8/8/8/8/4K2R w K - 0 1");

        MovesGenerator movesGenerator = new MovesGenerator(board);
        ArrayList<Move> kingMoves = movesGenerator.getPieceMoves(60);

        assertFalse(containsMove(kingMoves, 60, 62, "castle-k"));
    }

    @Test
    void legalKingsideCastleGeneratedWhenAllConstraintsHold() {
        Board board = new Board("4k2r/8/8/8/8/8/8/4K2R w K - 0 1");

        MovesGenerator movesGenerator = new MovesGenerator(board);
        ArrayList<Move> kingMoves = movesGenerator.getPieceMoves(60);

        assertTrue(containsMove(kingMoves, 60, 62, "castle-k"));
    }

    @Test
    void queensideCastleTargetsCorrectKingDestinationSquare() {
        Board board = new Board("4k3/8/8/8/8/8/8/R3K3 w Q - 0 1");

        MovesGenerator movesGenerator = new MovesGenerator(board);
        ArrayList<Move> kingMoves = movesGenerator.getPieceMoves(60);

        assertTrue(containsMove(kingMoves, 60, 58, "castle-q"));
    }

    private boolean containsMove(ArrayList<Move> moves, int startSquare, int targetSquare, String reaction) {
        return moves.stream().anyMatch(move ->
                move.startSquare == startSquare
                        && move.targetSquare == targetSquare
                        && reaction.equals(move.reaction));
    }
}
