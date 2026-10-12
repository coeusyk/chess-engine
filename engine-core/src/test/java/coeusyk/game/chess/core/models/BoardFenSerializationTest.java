package coeusyk.game.chess.core.models;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression coverage for issue #190: {@link Board#getCurrentFEN()} (via the public
 * {@link Board#toFen()}) must flush a trailing run of empty squares on the final rank
 * (chess rank 1, the last iteration of the square-index loop) exactly as it already
 * does for every other rank.
 */
class BoardFenSerializationTest {

    @Test
    void trailingEmptySquaresAfterAPieceOnTheLastRankAreFlushed() {
        // Rank 1: a1,b1 empty; c1=B; d1 empty; e1=K; f1,g1,h1 empty -> "2B1K3"
        Board board = new Board("4k3/8/8/8/8/8/8/2B1K3 w - - 0 1");

        String placement = board.toFen().split(" ")[0];
        String[] ranks = placement.split("/");

        assertEquals(8, ranks.length, "placement field must have 8 rank segments");
        assertEquals("2B1K3", ranks[7], "last rank must flush its trailing empty-square count");
    }

    @Test
    void entirelyEmptyLastRankStillEmitsAFullEightSegment() {
        Board board = new Board("4k3/8/8/4K3/8/8/8/8 w - - 0 1");

        String placement = board.toFen().split(" ")[0];
        String[] ranks = placement.split("/");

        assertEquals(8, ranks.length, "placement field must have 8 rank segments even when the last rank is empty");
        assertEquals("8", ranks[7], "a fully empty last rank must still emit its digit");
    }

    @Test
    void roundTripsCorrectlyThroughTheBoardConstructor() {
        Board board = new Board("4k3/8/8/8/8/8/8/2B1K3 w - - 0 1");
        Board roundTripped = new Board(board.toFen());

        assertEquals(board.toFen(), roundTripped.toFen());
    }
}
