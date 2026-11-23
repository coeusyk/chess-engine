package coeusyk.game.chess.core.search;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.movegen.MovesGenerator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #259: the transposition table is keyed by the Zobrist hash, which leaves out the halfmove
 * clock, so a score searched at one clock can be reused at another where the fifty-move rule
 * changes the result. Scores must not be shared across clocks that make the rule reachable
 * inside the searched subtree, in either order, while the best-move hint stays usable.
 *
 * <p>The position is white Ka1 and Ra2 against a bare black king: Ra3 is winning (about +6 pawns)
 * with a fresh clock, but at clock 98 every black reply reaches clock 100 and the game is drawn.
 */
class TranspositionTableScoreValidityTest {

    private static final String ROOK_ENDING_CLOCK_0 = "7k/8/8/8/8/8/R7/K7 w - - 0 80";
    private static final String ROOK_ENDING_CLOCK_98 = "7k/8/8/8/8/8/R7/K7 w - - 98 80";
    private static final String ROOK_ENDING_CLOCK_20 = "7k/8/8/8/8/8/R7/K7 w - - 20 80";
    private static final int DEPTH = 2;

    private static Move move(Board board, String uci) {
        ArrayList<Move> moves = new ArrayList<>();
        new MovesGenerator(board, moves);
        for (Move m : moves) {
            String s = "" + (char) ('a' + m.startSquare % 8) + (8 - m.startSquare / 8)
                    + (char) ('a' + m.targetSquare % 8) + (8 - m.targetSquare / 8);
            if (s.equals(uci)) {
                return m;
            }
        }
        throw new AssertionError("no legal move " + uci);
    }

    /** One fixed-depth Ra3-only search of {@code fen} on {@code tt}, single threaded and deterministic. */
    private static SearchResult ra3(String fen, TranspositionTable tt) {
        Board board = new Board(fen);
        Searcher searcher = new Searcher();
        searcher.setSharedTranspositionTable(tt);
        searcher.setSearchMoves(List.of(move(board, "a2a3")));
        return searcher.searchDepth(board, DEPTH);
    }

    private static long keyAfterRa3(String fen) {
        Board board = new Board(fen);
        board.makeMove(move(board, "a2a3"));
        return board.getZobristHash();
    }

    @Test
    void coldSearchesGiveTheTwoDifferentAnswers() {
        // The reference values every warm-table case below is compared against.
        assertTrue(ra3(ROOK_ENDING_CLOCK_0, new TranspositionTable(1)).scoreCp() > 300,
                "Ra3 wins with a fresh clock");
        assertEquals(0, ra3(ROOK_ENDING_CLOCK_98, new TranspositionTable(1)).scoreCp(),
                "every reply to Ra3 reaches clock 100, so the result is a draw");
    }

    @Test
    void scoreSearchedAtClockZeroIsNotReusedAtClock98() {
        TranspositionTable shared = new TranspositionTable(1);
        int fresh = ra3(ROOK_ENDING_CLOCK_0, shared).scoreCp();
        assertTrue(fresh > 300);

        SearchResult warm = ra3(ROOK_ENDING_CLOCK_98, shared);

        assertEquals(0, warm.scoreCp(), "a winning score cached at clock 0 leaked into a clock-98 search");
    }

    @Test
    void scoreSearchedAtClock98DoesNotPoisonALaterClockZeroSearch() {
        TranspositionTable shared = new TranspositionTable(1);
        assertEquals(0, ra3(ROOK_ENDING_CLOCK_98, shared).scoreCp());

        SearchResult warm = ra3(ROOK_ENDING_CLOCK_0, shared);

        assertTrue(warm.scoreCp() > 300, "a drawn clock-98 score cached in the table leaked into a clock-0 search: "
                + warm.scoreCp());
    }

    @Test
    void theBestMoveHintSurvivesAndTheCachedScoreIsNotOverwrittenByAClock98Search() {
        TranspositionTable shared = new TranspositionTable(1);
        ra3(ROOK_ENDING_CLOCK_0, shared);
        long key = keyAfterRa3(ROOK_ENDING_CLOCK_0);
        TranspositionTable.Entry before = shared.probe(key);
        assertNotNull(before, "the clock-0 search must have stored the position after Ra3");

        ra3(ROOK_ENDING_CLOCK_98, shared);

        TranspositionTable.Entry after = shared.probe(key);
        assertNotNull(after);
        assertEquals(before.bestMove(), after.bestMove(), "the move hint must still be there");
        assertEquals(before.score(), after.score(), "a clock-98 search must not rewrite the clock-0 score");
    }

    @Test
    void ordinaryClocksStillShareTheTable() {
        // Guard against over-correcting: clocks that cannot reach 100 inside the horizon keep reusing entries.
        TranspositionTable shared = new TranspositionTable(1);
        SearchResult cold = ra3(ROOK_ENDING_CLOCK_20, new TranspositionTable(1));
        ra3(ROOK_ENDING_CLOCK_0, shared);
        SearchResult warm = ra3(ROOK_ENDING_CLOCK_20, shared);

        assertEquals(cold.scoreCp(), warm.scoreCp());
        assertTrue(warm.ttHits() > 0, "a clock-20 search should still hit entries stored at clock 0");
        assertTrue(warm.nodesVisited() < cold.nodesVisited(),
                "reuse should save nodes: warm " + warm.nodesVisited() + " vs cold " + cold.nodesVisited());
    }

    @Test
    void theFiftyMoveGuardAppliesOnlyWhenTheRuleIsReachableInsideTheSearchedSubtree() {
        // clock + remaining depth + extension budget must reach 100 before the rule can matter.
        assertFalse(Searcher.fiftyMoveSensitive(0, 20, 16));
        assertFalse(Searcher.fiftyMoveSensitive(63, 20, 16));
        assertTrue(Searcher.fiftyMoveSensitive(64, 20, 16));
        assertTrue(Searcher.fiftyMoveSensitive(98, 2, 0));
        assertFalse(Searcher.fiftyMoveSensitive(97, 2, 0));
        assertTrue(Searcher.fiftyMoveSensitive(98, 1, 1));
    }
}
