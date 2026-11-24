package coeusyk.game.chess.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import coeusyk.game.chess.controllers.AnalysisController;
import coeusyk.game.chess.core.models.InvalidFenException;
import coeusyk.game.chess.utils.FENStringHandler;
import coeusyk.game.chess.utils.MoveHandler;
import coeusyk.game.chess.utils.ResponseContainer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Plain unit tests for the #276 fixes: no Spring context, so they stay cheap. */
class ApiCorrectnessTest {
    private static final String START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
    private static final String PROMOTION_FEN = "8/P6k/8/8/8/8/8/K7 w - - 0 1";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static MoveHandler move(int start, int target, String promotion) throws Exception {
        return JSON.readValue("{\"startSquare\":" + start + ",\"targetSquare\":" + target
                + (promotion == null ? "" : ",\"promotion\":\"" + promotion + "\"") + "}", MoveHandler.class);
    }

    private static FENStringHandler fenBody(String fen) throws Exception {
        return JSON.readValue("{\"fenString\":\"" + fen + "\"}", FENStringHandler.class);
    }

    // ---- bounded session store ----------------------------------------------------------------

    @Test
    void storeEvictsLeastRecentlyUsedAndKeepsTouchedAndDefaultSessions() {
        GameSessionStore store = new GameSessionStore(3);
        store.getOrCreate(null); // the legacy default session
        String a = store.create();
        String b = store.create();
        store.get(a); // a is now more recent than b
        String c = store.create(); // 4 sessions, cap 3: the oldest non-default entry (b) goes

        assertEquals(3, store.size());
        assertNotNull(store.get(a), "a recently used session must survive");
        assertNotNull(store.get(c));
        assertNotNull(store.getOrCreate(GameSessionStore.DEFAULT_GAME_ID));
        GameNotFoundException gone = assertThrows(GameNotFoundException.class, () -> store.get(b));
        assertEquals("Game not found: " + b, gone.getMessage());
    }

    @Test
    void storeNeverEvictsTheDefaultSessionEvenWhenItIsTheOldest() {
        GameSessionStore store = new GameSessionStore(2);
        GameSession original = store.getOrCreate(null);
        for (int i = 0; i < 5; i++) {
            store.create();
        }
        assertEquals(2, store.size());
        assertSame(original, store.getOrCreate(null));
    }

    // ---- redo stack, underpromotion, snapshot, kings ------------------------------------------

    @Test
    void legacyLoadFenAndNewGameDropRedoHistoryFromTheOldPosition() throws Exception {
        ChessGameService service = new ChessGameService(new GameSessionStore());
        String id = service.createGame();
        assertTrue(service.makeMove(move(52, 36, null), id).success); // e2e4
        assertTrue(service.undoMove(id));
        assertTrue(service.getState(id).canRedo());

        service.loadFen(fenBody(PROMOTION_FEN), id);
        assertFalse(service.getState(id).canRedo());
        assertFalse(service.redoMove(id));

        assertTrue(service.makeMove(move(8, 0, null), id).success);
        assertTrue(service.undoMove(id));
        assertTrue(service.getState(id).canRedo());
        service.newGame(id);
        assertFalse(service.getState(id).canRedo());
    }

    @Test
    void makeMovePromotesToTheRequestedPieceAndDefaultsToQueen() throws Exception {
        ChessGameService service = new ChessGameService(new GameSessionStore());
        String id = service.createGame();

        for (String piece : new String[]{"n", "b", "r", "q", "Q"}) {
            service.loadFenForGame(id, PROMOTION_FEN);
            ResponseContainer r = service.makeMove(move(8, 0, piece), id);
            assertTrue(r.success, piece);
            assertTrue(r.fen.startsWith(piece.toUpperCase() + "7/7k/8/8/8/8/8/K7 b"), r.fen);
        }

        service.loadFenForGame(id, PROMOTION_FEN);
        ResponseContainer defaulted = service.makeMove(move(8, 0, null), id);
        assertTrue(defaulted.fen.startsWith("Q7/"), defaulted.fen);

        service.loadFenForGame(id, PROMOTION_FEN);
        assertFalse(service.makeMove(move(8, 0, "k"), id).success, "an unknown promotion piece must not promote");
        assertFalse(service.getState(id).canUndo(), "a refused move leaves the position untouched");
    }

    @Test
    void responseBoardIsADetachedCopyThatMatchesTheLiveBoardAtTheTimeOfTheMove() throws Exception {
        GameSessionStore store = new GameSessionStore();
        ChessGameService service = new ChessGameService(store);
        String id = service.createGame();

        ResponseContainer first = service.makeMove(move(52, 36, null), id); // e2e4
        String firstJson = JSON.writeValueAsString(first.board);
        assertNotSame(store.get(id).getBoard(), first.board);
        assertEquals(JSON.writeValueAsString(store.get(id).getBoard()), firstJson,
                "the snapshot must serialize exactly like the live board did");

        assertTrue(service.makeMove(move(12, 28, null), id).success); // e7e5 mutates the live board
        assertEquals(firstJson, JSON.writeValueAsString(first.board), "later moves must not reach an earlier response");
        assertNotSame(store.get(id).getBoard(), service.setup(id).board);
    }

    @Test
    void positionsWithoutExactlyOneKingPerSideAreRejectedAndLeaveTheSessionAlone() throws Exception {
        ChessGameService service = new ChessGameService(new GameSessionStore());
        String id = service.createGame();
        service.loadFenForGame(id, PROMOTION_FEN);
        String before = service.getState(id).fen();

        assertThrows(InvalidFenException.class, () -> service.loadFenForGame(id, "8/8/8/8/8/8/8/4K3 w - - 0 1"));
        assertThrows(InvalidFenException.class, () -> service.loadFenForGame(id, "k7/k7/8/8/8/8/8/4K3 w - - 0 1"));
        assertThrows(InvalidFenException.class, () -> service.loadFen(fenBody("8/8/8/8/8/8/8/4k3 w - - 0 1"), id));
        assertEquals(before, service.getState(id).fen());

        AnalysisService analysis = new AnalysisService();
        try {
            assertThrows(InvalidFenException.class, () -> analysis.evaluate("8/8/8/8/8/8/8/4K3 w - - 0 1", 1, 1));
            assertThrows(InvalidFenException.class, () -> analysis.startAnalysis("8/8/8/8/8/8/8/4K3 w - - 0 1", 1, null, 1));
        } finally {
            analysis.shutdown();
        }
    }

    // ---- AnalysisService lifecycle ------------------------------------------------------------

    @Test
    void shutdownStopsTheSearchExecutor() {
        AnalysisService analysis = new AnalysisService();
        analysis.shutdown();
        assertThrows(RejectedExecutionException.class, () -> analysis.startAnalysis(START, 1, null, 1));
    }

    @Test
    void aSupersededEvaluationIsAnAbortNotATruncatedSuccess() throws Exception {
        AnalysisService analysis = new AnalysisService();
        try {
            AtomicReference<Throwable> firstOutcome = new AtomicReference<>();
            Thread first = new Thread(() -> {
                try {
                    analysis.evaluate(START, 15, 1);
                    firstOutcome.set(new AssertionError("the superseded search returned a result"));
                } catch (Throwable t) {
                    firstOutcome.set(t);
                }
            });
            first.start();
            // evaluate() registers its cancellation flag, submits the search, then blocks in a timed
            // wait. Observing that wait means the second call is guaranteed to supersede it.
            long deadline = System.nanoTime() + 20_000_000_000L;
            while (first.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertEquals(Thread.State.TIMED_WAITING, first.getState());

            EvaluateResponse second = analysis.evaluate(START, 1, 1);
            first.join(20_000);
            assertFalse(first.isAlive(), "the cancelled search must stop promptly");

            assertInstanceOf(SearchAbortedException.class, firstOutcome.get());
            assertNotNull(second.bestMove());
            assertNotEquals("0000", second.bestMove().uci());
        } finally {
            analysis.shutdown();
        }
    }

    @Test
    void abortedSearchesMapToConflict() {
        var response = new AnalysisController(null).handleAborted(new SearchAbortedException("superseded"));
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
    }
}
