package coeusyk.game.chess.core.movegen;

import coeusyk.game.chess.core.models.Board;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PerftHarnessTest {

    @Test
    void startPositionPerftMatchesReferenceAtDepthsOneToFive() {
        Board board = new Board();

        assertEquals(20L, Perft.countNodes(board, 1));
        assertEquals(400L, Perft.countNodes(board, 2));
        assertEquals(8902L, Perft.countNodes(board, 3));
        assertEquals(197281L, Perft.countNodes(board, 4));
        assertEquals(4865609L, Perft.countNodes(board, 5));
    }

    @Test
    void kiwipetePerftMatchesReferenceAtDepthOneToThree() {
        Board board = new Board("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1");

        assertEquals(48L, Perft.countNodes(board, 1));
        assertEquals(2039L, Perft.countNodes(board, 2));
        assertEquals(97862L, Perft.countNodes(board, 3));
    }

    @Test
    void cpwPosition3PerftMatchesReferenceAtDepthsOneToFive() {
        Board board = new Board("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1");

        assertEquals(14L, Perft.countNodes(board, 1));
        assertEquals(191L, Perft.countNodes(board, 2));
        assertEquals(2812L, Perft.countNodes(board, 3));
        assertEquals(43238L, Perft.countNodes(board, 4));
        assertEquals(674624L, Perft.countNodes(board, 5));
    }

    @Test
    void cpwPosition4PerftMatchesReferenceAtDepthsOneToFour() {
        Board board = new Board("r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1");

        assertEquals(6L, Perft.countNodes(board, 1));
        assertEquals(264L, Perft.countNodes(board, 2));
        assertEquals(9467L, Perft.countNodes(board, 3));
        assertEquals(422333L, Perft.countNodes(board, 4));
    }

    @Test
    void cpwPosition5PerftMatchesReferenceAtDepthsOneToFour() {
        Board board = new Board("rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8");

        assertEquals(44L, Perft.countNodes(board, 1));
        assertEquals(1486L, Perft.countNodes(board, 2));
        assertEquals(62379L, Perft.countNodes(board, 3));
        assertEquals(2103487L, Perft.countNodes(board, 4));
    }
}
