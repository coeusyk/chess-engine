package coeusyk.game.chess.core.notation;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.movegen.MovesGenerator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

class SanConverterTest {
    private final SanConverter sanConverter = new SanConverter();

    @Test
    void toSanSupportsPawnAndPieceMoves() {
        Board board = new Board();

        Move pawnMove = findMove(board, 52, 36); // e2e4
        Move knightMove = findMove(board, 62, 45); // g1f3

        assertEquals("e4", sanConverter.toSan(pawnMove, board));
        assertEquals("Nf3", sanConverter.toSan(knightMove, board));
    }

    @Test
    void toSanSupportsCastlingPromotionAndCheckSuffix() {
        Board castleBoard = new Board("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1");
        Move kingSideCastle = findMove(castleBoard, 60, 62);
        assertEquals("O-O", sanConverter.toSan(kingSideCastle, castleBoard));

        Board promotionBoard = new Board("k7/4P3/8/8/8/8/8/4K3 w - - 0 1");
        Move promoteToQueen = findMove(promotionBoard, 12, 4); // e7e8=Q
        assertEquals("e8=Q+", sanConverter.toSan(promoteToQueen, promotionBoard));

        Board checkBoard = new Board("7k/8/8/8/8/8/4R3/4K3 w - - 0 1");
        Move checkingMove = findMove(checkBoard, 52, 4); // Re8+
        assertEquals("Re8+", sanConverter.toSan(checkingMove, checkBoard));
    }

    @Test
    void toSanAddsDisambiguationWhenRequired() {
        Board board = new Board("4k3/8/8/8/8/5N2/8/1N2K3 w - - 0 1");

        Move knightFromBFile = findMove(board, 57, 51); // b1d2
        String san = sanConverter.toSan(knightFromBFile, board);

        assertEquals("Nbd2", san);
    }

    @Test
    void fromSanReturnsMatchingLegalMove() {
        Board board = new Board();

        Move parsed = sanConverter.fromSan("Nf3", board);

        assertNotNull(parsed);
        assertEquals(62, parsed.startSquare);
        assertEquals(45, parsed.targetSquare);
    }

    @Test
    void fromSanParsesCapturesAndCastling() {
        Board captureBoard = new Board("4k3/8/8/3p4/4P3/8/8/4K3 w - - 0 1");
        Move capture = sanConverter.fromSan("exd5", captureBoard);
        assertNotNull(capture);
        assertEquals(36, capture.startSquare);
        assertEquals(27, capture.targetSquare);

        Board castleBoard = new Board("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1");
        Move castleMove = sanConverter.fromSan("O-O", castleBoard);
        assertNotNull(castleMove);
        assertEquals(60, castleMove.startSquare);
        assertEquals(62, castleMove.targetSquare);
    }

    private Move findMove(Board board, int startSquare, int targetSquare) {
        ArrayList<Move> moves = new MovesGenerator(board).getActiveMoves(board.getActiveColor());
        for (Move move : moves) {
            if (move.startSquare == startSquare && move.targetSquare == targetSquare) {
                return move;
            }
        }

        fail("Expected move not found: " + startSquare + "->" + targetSquare);
        return null;
    }
}