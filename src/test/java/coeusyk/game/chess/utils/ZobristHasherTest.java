package coeusyk.game.chess.utils;

import coeusyk.game.chess.models.Board;
import coeusyk.game.chess.models.Piece;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;


class ZobristHasherTest {

    @Test
    void samePositionProducesSameHash() {
        Board a = new Board();
        Board b = new Board();
        assertEquals(ZobristHasher.computeHash(a), ZobristHasher.computeHash(b));
    }

    @Test
    void differentPositionsProduceDifferentHashes() {
        Board starting = new Board();
        // Position after 1.e4
        Board afterE4 = new Board("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1");
        assertNotEquals(ZobristHasher.computeHash(starting), ZobristHasher.computeHash(afterE4));
    }

    @Test
    void hashIsNonZeroForStartingPosition() {
        Board board = new Board();
        assertNotEquals(0L, ZobristHasher.computeHash(board));
    }

    @Test
    void colorToMoveAffectsHash() {
        // Two positions that differ only in side-to-move
        Board white = new Board("8/8/8/8/8/8/8/4K2k w - - 0 1");
        Board black = new Board("8/8/8/8/8/8/8/4K2k b - - 0 1");
        assertNotEquals(ZobristHasher.computeHash(white), ZobristHasher.computeHash(black));
    }

    @Test
    void customFenHashIsReproducible() {
        String fen = "r1bqkb1r/pppp1ppp/2n2n2/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 4 4";
        Board a = new Board(fen);
        Board b = new Board(fen);
        assertEquals(ZobristHasher.computeHash(a), ZobristHasher.computeHash(b));
    }
}
