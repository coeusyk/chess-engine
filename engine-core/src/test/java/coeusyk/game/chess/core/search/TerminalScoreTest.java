package coeusyk.game.chess.core.search;

import coeusyk.game.chess.core.models.Board;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** #270: a node with no legal move scores as mate when the side to move is in check and as a draw otherwise. */
class TerminalScoreTest {
    private static final int MATE_SCORE = 100_000;

    @Test
    void aMatedRootScoresAsMateAndAStalemateRootScoresAsADraw() {
        SearchResult mated = new Searcher().iterativeDeepening(new Board("7k/6Q1/6K1/8/8/8/8/8 b - - 0 1"), 3);
        assertNull(mated.bestMove());
        assertEquals(-MATE_SCORE, mated.scoreCp());

        SearchResult stalemate = new Searcher().iterativeDeepening(new Board("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1"), 3);
        assertNull(stalemate.bestMove());
        assertEquals(0, stalemate.scoreCp());
    }

    @Test
    void mateInOneAndTheStalemateTrapStillScoreFromInteriorNodes() {
        // Qg7 mates: the node after it has no legal move and the king is in check.
        SearchResult mateInOne = new Searcher().iterativeDeepening(new Board("7k/8/5K2/8/8/8/6Q1/8 w - - 0 1"), 3);
        assertEquals(MATE_SCORE - 1, mateInOne.scoreCp());

        // Qc7 stalemates the king on a8 and Qc8 mates it. If a node with no legal move scored as mate whatever
        // the check state, the stalemating move would look as good as the mate, so the search has to pick the
        // mating move and score it as a mate.
        SearchResult trap = new Searcher().iterativeDeepening(new Board("k7/8/1K6/8/8/8/8/2Q5 w - - 0 1"), 3);
        assertEquals(MATE_SCORE - 1, trap.scoreCp());
        assertEquals(58, trap.bestMove().startSquare); // c1
        assertEquals(2, trap.bestMove().targetSquare); // c8
    }
}
