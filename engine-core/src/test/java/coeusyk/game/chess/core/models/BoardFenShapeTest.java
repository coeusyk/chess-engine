package coeusyk.game.chess.core.models;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BoardFenShapeTest {
    private static final String START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    @Test
    void rejectsPlacementsThatAreNotEightRanksOfEightSquares() {
        String[] bad = {
                "8/8/8/8/8/8/8/8/8 w - - 0 1",              // 9 ranks
                "8/8/8/8/8/8/8 w - - 0 1",                  // 7 ranks
                "4k3/8/8/8/8/8/8/3K3 w - - 0 1",            // short rank
                "4k3/8/8/8/8/8/8/4K4 w - - 0 1",            // long rank, would shift past the board
                "rnbqkbnrp/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", // 9 pieces on a rank
                "9/8/8/8/8/8/8/4K3 w - - 0 1",              // digit above 8
                "0/8/8/8/8/8/8/4K3 w - - 0 1",              // digit zero
                "4k3//8/8/8/8/8/4K3 w - - 0 1",             // empty rank
                "4k3/8/8/8/8/8/8/4K3/ w - - 0 1",           // trailing separator
        };
        for (String fen : bad) {
            assertThrows(InvalidFenException.class, () -> new Board(fen), fen);
        }
    }

    @Test
    void rejectsEnPassantSquaresOffTheBoard() {
        for (String ep : new String[]{"a0", "a9", "i3", "z6", "e0"}) {
            String fen = "4k3/8/8/8/8/8/8/4K3 w - " + ep + " 0 1";
            assertThrows(InvalidFenException.class, () -> new Board(fen), ep);
        }
    }

    @Test
    void stillAcceptsOrdinaryFens() {
        assertEquals(START, new Board(START).toFen());
        new Board("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1");
        new Board("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1");
        new Board("4k3/8/8/8/8/8/8/4K3 w - - 0 1");
    }
}
