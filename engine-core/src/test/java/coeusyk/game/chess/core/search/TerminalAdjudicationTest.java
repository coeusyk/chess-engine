package coeusyk.game.chess.core.search;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.movegen.MovesGenerator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #261: checkmate takes precedence over the fifty-move draw, and a terminal root
 * (already checkmated or stalemated) is a completed result, not an aborted search.
 */
class TerminalAdjudicationTest {

    private static final int MATE_SCORE = 100_000;
    // White Qg6-g7# is the only mating move; White Kf7 protects g7.
    private static final String MATE_IN_ONE_CLOCK_98 = "7k/5K2/6Q1/8/8/8/8/8 w - - 98 80";
    private static final String MATE_IN_ONE_CLOCK_99 = "7k/5K2/6Q1/8/8/8/8/8 w - - 99 80";
    // Black to move, checkmated (fool's-mate style: Qxf7#).
    private static final String CHECKMATED_ROOT = "r1bqkb1r/pppp1Qpp/2n2n2/4p3/2B1P3/8/PPPP1PPP/RNB1K1NR b KQkq - 0 4";
    // Black to move, stalemated.
    private static final String STALEMATED_ROOT = "k7/2Q5/1K6/8/8/8/8/8 b - - 0 1";

    private static Move moveByUci(Board board, String uci) {
        ArrayList<Move> moves = new ArrayList<>();
        new MovesGenerator(board, moves);
        for (Move m : moves) {
            if (toUci(m).equals(uci)) {
                return m;
            }
        }
        throw new AssertionError("no legal move " + uci);
    }

    private static String toUci(Move move) {
        return "" + (char) ('a' + move.startSquare % 8) + (8 - move.startSquare / 8)
                + (char) ('a' + move.targetSquare % 8) + (8 - move.targetSquare / 8);
    }

    private static SearchResult searchOnly(String fen, String uci, int depth) {
        Board board = new Board(fen);
        Searcher searcher = new Searcher();
        searcher.setSearchMoves(List.of(moveByUci(board, uci)));
        return searcher.searchDepth(board, depth);
    }

    @Test
    void mateDeliveredAsClockGoesFrom98To99IsMateInOne() {
        SearchResult r = searchOnly(MATE_IN_ONE_CLOCK_98, "g6g7", 1);
        assertEquals(MATE_SCORE - 1, r.scoreCp());
    }

    @Test
    void mateDeliveredAsClockReaches100IsMateInOneNotADraw() {
        // Regression: the child node has halfmoveClock == 100 and used to return the draw score.
        SearchResult r = searchOnly(MATE_IN_ONE_CLOCK_99, "g6g7", 1);
        assertEquals(MATE_SCORE - 1, r.scoreCp(), "checkmate must beat the fifty-move draw");
        assertFalse(r.aborted());
    }

    @Test
    void checkAtClock100WithALegalEvasionIsStillADraw() {
        // Qg8+ here is not mate: the white king is far away, so ...Kxg8 is a legal evasion.
        // The move takes the clock from 99 to 100 with Black in check but able to move, so the
        // fifty-move draw stands and the score must stay a draw score, never a mate score.
        SearchResult r = searchOnly("7k/8/6Q1/8/8/8/8/K7 w - - 99 80", "g6g8", 1);
        assertTrue(Math.abs(r.scoreCp()) < 1_000, "a genuine fifty-move draw must not become a mate score, got " + r.scoreCp());
    }

    @Test
    void protectedQueenCheckAtClock100IsMateButUnprotectedIsNot() {
        // Same Qg6-g8+ idea, differing only in whether the white king guards g8.
        int guarded = searchOnly("7k/5K2/6Q1/8/8/8/8/8 w - - 99 80", "g6g8", 1).scoreCp();
        int unguarded = searchOnly("7k/8/6Q1/8/8/8/8/K7 w - - 99 80", "g6g8", 1).scoreCp();
        assertEquals(MATE_SCORE - 1, guarded);
        assertTrue(Math.abs(unguarded) < 1_000);
    }

    @Test
    void sameMateWithClockBelowTheThresholdIsUnchanged() {
        SearchResult r = searchOnly("7k/5K2/6Q1/8/8/8/8/8 w - - 0 80", "g6g7", 1);
        assertEquals(MATE_SCORE - 1, r.scoreCp());
    }

    @Test
    void checkmatedRootIsACompletedTerminalResult() {
        Searcher searcher = new Searcher();
        SearchResult r = searcher.searchDepth(new Board(CHECKMATED_ROOT), 6);
        assertFalse(r.aborted(), "a checkmated root is not a cancelled search");
        assertNull(r.bestMove());
        assertEquals(-MATE_SCORE, r.scoreCp());
    }

    @Test
    void stalematedRootIsACompletedDrawNotAnAbort() {
        Searcher searcher = new Searcher();
        SearchResult r = searcher.searchDepth(new Board(STALEMATED_ROOT), 6);
        assertFalse(r.aborted(), "a stalemated root is not a cancelled search");
        assertNull(r.bestMove());
        assertEquals(0, r.scoreCp());
    }

    @Test
    void terminalRootStopsAfterTheFirstIteration() {
        AtomicInteger iterations = new AtomicInteger();
        Searcher searcher = new Searcher();
        SearchResult r = searcher.iterativeDeepening(new Board(STALEMATED_ROOT), 20, () -> false, () -> false,
                info -> iterations.incrementAndGet());
        assertFalse(r.aborted());
        assertTrue(iterations.get() <= 1, "no pointless deepening on a terminal root, saw " + iterations.get());
    }

    @Test
    void cancelledSearchOfANormalPositionIsStillAborted() {
        Searcher searcher = new Searcher();
        SearchResult r = searcher.iterativeDeepening(new Board("4k3/8/8/8/8/8/4P3/4K3 w - - 0 1"), 8, () -> true);
        assertTrue(r.aborted(), "a stop request before depth 1 completes must still report aborted");
    }

    @Test
    void cancelledSearchOfATerminalRootBeforeItStartsIsStillAborted() {
        Searcher searcher = new Searcher();
        SearchResult r = searcher.iterativeDeepening(new Board(CHECKMATED_ROOT), 8, () -> true);
        assertTrue(r.aborted(), "cancellation is reported as such even if the root happens to be terminal");
    }

    @Test
    void searchmovesThatMatchNothingIsNotMistakenForATerminalRoot() {
        // Legal moves exist, the searchmoves filter just removes all of them: not mate, not stalemate.
        Board board = new Board("4k3/8/8/8/8/8/4P3/4K3 w - - 0 1");
        Searcher searcher = new Searcher();
        Board other = new Board("4k3/8/8/8/8/8/8/R3K3 w - - 0 1");
        searcher.setSearchMoves(List.of(moveByUci(other, "a1a8")));
        SearchResult r = searcher.searchDepth(board, 2);
        assertTrue(r.aborted(), "an unmatchable searchmoves filter keeps its previous (aborted) behavior");
        assertNotNull(r);
    }
}
