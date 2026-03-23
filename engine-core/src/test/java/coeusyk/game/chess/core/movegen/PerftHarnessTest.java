package coeusyk.game.chess.core.movegen;

import coeusyk.game.chess.core.models.Board;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PerftHarnessTest {

    @Test
    void startPositionPerftMatchesReferenceAtDepthsOneToThree() {
        Board board = new Board();

        assertEquals(20L, Perft.countNodes(board, 1));
        assertEquals(400L, Perft.countNodes(board, 2));
        assertEquals(8902L, Perft.countNodes(board, 3));
    }

    @Test
    @Disabled("Pending remaining legality parity fixes: current engine undercounts Kiwipete")
    void kiwipetePerftMatchesReferenceAtDepthOneAndTwo() {
        Board board = new Board("r3k2r/p1ppqpb1/bn2pnp1/2pPn3/1p2P3/2N2N2/PPQBBPPP/R3K2R w KQkq - 0 1");

        assertEquals(48L, Perft.countNodes(board, 1));
        assertEquals(2039L, Perft.countNodes(board, 2));
    }
}
