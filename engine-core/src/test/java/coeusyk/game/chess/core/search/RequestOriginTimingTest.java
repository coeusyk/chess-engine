package coeusyk.game.chess.core.search;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.models.Piece;
import coeusyk.game.chess.core.movegen.MovesGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The timing contract for the clock origin: a search is charged from the moment its request arrived, not from the
 * moment the search code started. Every test uses an origin placed in the past, so none depends on real wall time
 * except the single end-to-end budget check, whose bound is the 100 ms the origin leaves.
 */
class RequestOriginTimingTest {
    private static final long SECOND_NANOS = 1_000_000_000L;

    private static boolean sameMove(Move a, Move b) {
        return a.startSquare == b.startSquare && a.targetSquare == b.targetSquare;
    }

    @Test
    void startNowKeepsTheMarkedRequestOrigin() {
        TimeManager manager = new TimeManager();
        manager.markRequestReceived(System.nanoTime() - 500_000_000L);

        manager.startNow();

        assertTrue(manager.elapsedMs() >= 500, "pre-search time must be charged, was " + manager.elapsedMs());
    }

    @Test
    void startNowWithoutAMarkedOriginStartsTheClockNow() {
        TimeManager manager = new TimeManager();

        manager.startNow();

        assertTrue(manager.elapsedMs() < 100, "no origin was marked, was " + manager.elapsedMs());
    }

    @Test
    void markedOriginIsConsumedSoAReusedManagerStartsALaterSearchFresh() {
        TimeManager manager = new TimeManager();
        manager.markRequestReceived(System.nanoTime() - 5 * SECOND_NANOS);
        manager.startNow();
        assertTrue(manager.elapsedMs() >= 5_000);

        manager.startNow();

        assertTrue(manager.elapsedMs() < 100, "second search must not inherit the first origin, was " + manager.elapsedMs());
    }

    @Test
    void ponderHitDiscardsAPendingRequestOrigin() {
        TimeManager manager = new TimeManager();
        manager.configurePonder();
        manager.markRequestReceived(System.nanoTime() - 5 * SECOND_NANOS);

        // ponderhit lands before the worker reaches startNow(): the ponder time was never this move's budget.
        manager.configurePonderHit(Piece.White, 60_000, 60_000, 0, 0, 0);
        manager.startNow();

        assertTrue(manager.elapsedMs() < 100, "ponderhit restarts the clock, was " + manager.elapsedMs());
    }

    @Test
    void ponderHitAfterStartNowRestartsTheClock() {
        TimeManager manager = new TimeManager();
        manager.configurePonder();
        manager.markRequestReceived(System.nanoTime() - 5 * SECOND_NANOS);
        manager.startNow();
        assertTrue(manager.elapsedMs() >= 5_000);

        manager.configurePonderHit(Piece.White, 60_000, 60_000, 0, 0, 0);

        assertTrue(manager.elapsedMs() < 100, "ponderhit restarts the clock, was " + manager.elapsedMs());
    }

    @Test
    void budgetConsumedBeforeTheSearchStartsIsAlreadyExhausted() {
        TimeManager manager = new TimeManager();
        manager.setMoveOverheadMs(30);
        manager.configureMovetime(100);
        // 70 ms usable. 80 ms went on class loading and thread start before the search began.
        manager.markRequestReceived(System.nanoTime() - 80_000_000L);

        manager.startNow();

        assertTrue(manager.shouldStopSoft());
        assertTrue(manager.shouldStopHard());
    }

    @Test
    void allocationDoesNotDependOnTheOrigin() {
        TimeManager plain = new TimeManager();
        TimeManager marked = new TimeManager();
        marked.markRequestReceived(System.nanoTime() - SECOND_NANOS);
        plain.setMoveOverheadMs(30);
        marked.setMoveOverheadMs(30);

        plain.configureClock(Piece.White, 60_000, 60_000, 1_000, 1_000, 10);
        marked.configureClock(Piece.White, 60_000, 60_000, 1_000, 1_000, 10);
        assertEquals(plain.getSoftLimitMs(), marked.getSoftLimitMs());
        assertEquals(plain.getHardLimitMs(), marked.getHardLimitMs());

        marked.configureMovetime(500);
        assertEquals(470, marked.getSoftLimitMs());
        assertEquals(470, marked.getHardLimitMs());
    }

    @Test
    void searchWithAnExhaustedBudgetStillReturnsALegalMove() {
        Board board = new Board();
        Searcher searcher = new Searcher();
        TimeManager manager = new TimeManager();
        manager.setMoveOverheadMs(30);
        manager.configureMovetime(100);
        manager.markRequestReceived(System.nanoTime() - 10 * SECOND_NANOS);

        SearchResult result = searcher.searchWithTimeManager(board, 30, manager);

        assertTrue(result.aborted());
        assertEquals(0, result.depthReached(), "no iteration can complete on a spent budget");
        assertNotNull(result.bestMove(), "a nonterminal root must still yield a move");
        List<Move> legal = new MovesGenerator(board).getActiveMoves(board.getActiveColor());
        assertTrue(legal.stream().anyMatch(m -> sameMove(m, result.bestMove())), "fallback must be a legal root move");
    }

    @Test
    void exhaustedBudgetFallbackRespectsSearchMoves() {
        Board board = new Board();
        List<Move> legal = new MovesGenerator(board).getActiveMoves(board.getActiveColor());
        Move restricted = legal.get(legal.size() - 1);
        Searcher searcher = new Searcher();
        searcher.setSearchMoves(List.of(restricted));
        TimeManager manager = new TimeManager();
        manager.configureMovetime(100);
        manager.markRequestReceived(System.nanoTime() - 10 * SECOND_NANOS);

        SearchResult result = searcher.searchWithTimeManager(board, 30, manager);

        assertNotNull(result.bestMove());
        assertTrue(sameMove(restricted, result.bestMove()));
    }

    @Test
    void exhaustedBudgetOnATerminalRootReturnsNoMove() {
        // Fool's mate: White is checkmated, so there is no legal move to fall back to.
        Board board = new Board("rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3");
        Searcher searcher = new Searcher();
        TimeManager manager = new TimeManager();
        manager.configureMovetime(100);
        manager.markRequestReceived(System.nanoTime() - 10 * SECOND_NANOS);

        SearchResult result = searcher.searchWithTimeManager(board, 30, manager);

        assertNull(result.bestMove());
    }

    @Test
    void searchSpendsItsBudgetFromTheRequestOrigin() {
        Board board = new Board();
        Searcher searcher = new Searcher();
        TimeManager manager = new TimeManager();
        manager.setMoveOverheadMs(0);
        manager.configureMovetime(300);
        // 200 ms were already spent before the search began, so only about 100 ms remain.
        manager.markRequestReceived(System.nanoTime() - 200_000_000L);

        searcher.searchWithTimeManager(board, 40, manager);

        long total = manager.elapsedMs();
        assertTrue(total >= 300, "the budget runs from the request origin, was " + total);
        assertTrue(total < 400, "search ran more than 100 ms past its request-relative budget, was " + total);
    }
}
