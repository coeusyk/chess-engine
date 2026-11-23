package coeusyk.game.chess.uci;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.movegen.MovesGenerator;
import coeusyk.game.chess.uci.UciApplicationIntegrationTest.UciHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #268: the UCI search lifecycle. Every wait here is on an engine output line or on the isready/readyok
 * barrier, never on a fixed sleep. Commands that must reach the engine back to back are written with one flush.
 */
class UciSearchLifecycleTest {
    private static final Duration LONG = Duration.ofSeconds(20);
    private static final String START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
    private static final String ONE_LEGAL_MOVE = "7k/8/8/8/8/8/r7/K1r5 w - - 0 1";
    private static final String MATE_IN_ONE = "6k1/5ppp/8/8/8/8/8/R5K1 w - - 0 1";
    private static final String STALEMATE = "7k/5Q2/6K1/8/8/8/8/8 b - - 0 1";

    private UciHarness harness;

    @AfterEach
    void cleanup() throws Exception {
        if (harness != null) harness.close();
    }

    private static boolean isLegalFrom(String fen, String bestmoveLine) {
        if (bestmoveLine == null || !bestmoveLine.startsWith("bestmove ")) return false;
        String move = bestmoveLine.substring("bestmove ".length()).split("\\s+")[0];
        Board board = new Board(fen);
        List<Move> legal = new MovesGenerator(board).getActiveMoves(board.getActiveColor());
        return legal.stream().anyMatch(m -> uci(m).equals(move));
    }

    private static String uci(Move m) {
        String s = sq(m.startSquare) + sq(m.targetSquare);
        if (m.reaction != null && m.reaction.startsWith("promote-")) s += m.reaction.charAt(8);
        return s;
    }

    private static String sq(int square) {
        return "" + (char) ('a' + square % 8) + (char) ('8' - square / 8);
    }

    @Test
    void goInfiniteSearchesPastDepthFourAndWaitsForStop() throws Exception {
        harness = UciHarness.start();
        harness.send("position startpos");
        harness.send("go infinite");

        assertNotNull(harness.awaitLine(l -> l.startsWith("info depth 6 "), LONG),
                "go infinite never got past depth 4");
        assertEquals(0, harness.bestmoveCount(), "bestmove emitted before stop");

        harness.send("stop");
        assertNotNull(harness.awaitLine(l -> l.startsWith("bestmove "), LONG));
        harness.barrier();
        assertEquals(1, harness.bestmoveCount());
    }

    @Test
    void stopAfterCompletionOrWhenIdleEmitsNothing() throws Exception {
        harness = UciHarness.start();
        harness.send("stop");
        harness.send("stop");
        harness.barrier();
        assertEquals(0, harness.bestmoveCount(), "idle stop must not emit bestmove");

        harness.send("position startpos");
        for (int i = 1; i <= 25; i++) {
            harness.send("go depth 1");
            assertNotNull(harness.awaitLine(l -> l.startsWith("bestmove "), LONG));
            harness.sendBatch("stop", "stop");
        }
        harness.barrier();
        assertEquals(25, harness.bestmoveCount(), "stop after a finished search produced extra bestmove lines");
    }

    @Test
    void immediateStopNeverEmitsNullBestmoveInLivePosition() throws Exception {
        harness = UciHarness.start();
        harness.send("position startpos");
        for (int i = 1; i <= 50; i++) {
            harness.sendBatch("go infinite", "stop");
            String line = harness.awaitLine(l -> l.startsWith("bestmove "), LONG);
            assertNotNull(line, "iteration " + i);
            assertTrue(isLegalFrom(START, line), "iteration " + i + ": " + line);
            harness.barrier();
        }
        assertEquals(50, harness.bestmoveCount());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void ponderhitRightAfterGoPonderIsNeverDropped(int threads) throws Exception {
        if (threads == 2) Assumptions.assumeTrue(Runtime.getRuntime().availableProcessors() >= 2);
        harness = UciHarness.start();
        harness.send("setoption name Threads value " + threads);
        harness.send("position startpos");
        for (int i = 1; i <= 30; i++) {
            harness.sendBatch("go ponder wtime 2000 btime 2000 winc 0 binc 0", "ponderhit");
            String line = harness.awaitLine(l -> l.startsWith("bestmove "), Duration.ofSeconds(8));
            if (line == null) {
                harness.send("stop");
                throw new AssertionError("iteration " + i + ": ponderhit was dropped, the search stayed in ponder mode");
            }
        }
        harness.barrier();
        assertEquals(30, harness.bestmoveCount());
    }

    @Test
    void forcedMoveUnderInfiniteWaitsForStop() throws Exception {
        harness = UciHarness.start();
        harness.send("position fen " + ONE_LEGAL_MOVE);
        harness.send("go infinite");
        harness.barrier();
        assertEquals(0, harness.bestmoveCount(), "forced move was published before stop");
        harness.send("stop");
        String line = harness.awaitLine(l -> l.startsWith("bestmove "), LONG);
        assertEquals("bestmove a1a2", line);
        harness.barrier();
        assertEquals(1, harness.bestmoveCount());
    }

    @Test
    void forcedMoveWithoutInfiniteStillAnswersImmediately() throws Exception {
        harness = UciHarness.start();
        harness.send("position fen " + ONE_LEGAL_MOVE);
        harness.send("go wtime 1000 btime 1000");
        assertEquals("bestmove a1a2", harness.awaitLine(l -> l.startsWith("bestmove "), LONG));
    }

    @Test
    void mateFoundUnderInfiniteStillWaitsForStop() throws Exception {
        harness = UciHarness.start();
        harness.send("position fen " + MATE_IN_ONE);
        harness.send("go infinite");
        assertNotNull(harness.awaitLine(l -> l.contains(" score mate 1"), LONG));
        harness.barrier();
        assertEquals(0, harness.bestmoveCount(), "bestmove published before stop after a mate was found");
        harness.send("stop");
        String line = harness.awaitLine(l -> l.startsWith("bestmove "), LONG);
        assertTrue(line.startsWith("bestmove a1a8"), line);
        harness.barrier();
        assertEquals(1, harness.bestmoveCount());
    }

    @Test
    void terminalPositionKeepsItsNullBestmoveExactlyOnce() throws Exception {
        harness = UciHarness.start();
        harness.send("position fen " + STALEMATE);
        harness.send("go depth 3");
        assertEquals("bestmove 0000", harness.awaitLine(l -> l.startsWith("bestmove "), LONG));
        harness.send("go infinite");
        assertEquals("bestmove 0000", harness.awaitLine(l -> l.startsWith("bestmove "), LONG));
        harness.sendBatch("stop", "stop");
        harness.barrier();
        assertEquals(2, harness.bestmoveCount(), "one bestmove per go, none for stop");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "setoption name BookVariance value 10",
            "setoption name BookFile value Performance.bin",
            "position startpos moves e2e4"})
    void stateChangingCommandsDrainTheRunningSearchFirst(String command) throws Exception {
        harness = UciHarness.start();
        harness.send("position startpos");
        harness.send("go infinite");
        assertNotNull(harness.awaitLine(l -> l.startsWith("info depth 3 "), LONG));
        harness.send(command);
        harness.barrier();
        List<String> history = harness.history();
        int bestmove = -1, ready = -1;
        for (int i = 0; i < history.size(); i++) {
            if (history.get(i).startsWith("bestmove ") && bestmove < 0) bestmove = i;
            if (history.get(i).equals("readyok")) ready = i;
        }
        assertTrue(bestmove >= 0 && bestmove < ready,
                "the interrupted search had not finished when readyok was sent: " + history);
        assertEquals(1, harness.bestmoveCount());
    }

    @Test
    void quitDuringSearchExitsPromptlyWithAtMostOneBestmove() throws Exception {
        harness = UciHarness.start();
        harness.send("position startpos");
        harness.send("go infinite");
        assertNotNull(harness.awaitLine(l -> l.startsWith("info depth 3 "), LONG));
        harness.send("quit");
        assertTrue(harness.awaitExit(Duration.ofSeconds(10)), "engine did not exit after quit");
        assertTrue(harness.bestmoveCount() <= 1);
    }

    @Test
    void rapidGoStopCyclesEmitOncePerGoAndDrainEveryHelper() throws Exception {
        Assumptions.assumeTrue(Runtime.getRuntime().availableProcessors() >= 2);
        harness = UciHarness.startWithDiagnostics();
        harness.send("setoption name Threads value 2");
        for (int i = 1; i <= 20; i++) {
            harness.sendBatch("position startpos", "go infinite", "stop");
        }
        harness.sendBatch("go infinite", "go infinite", "stop");
        harness.send("setoption name Hash value 16"); // joins the last search
        harness.barrier();
        assertEquals(22, harness.bestmoveCount(), "one bestmove per go (20 cycles, then a replaced search and its replacement)");
        long drained = harness.diagnosticLines().stream().filter(l -> l.contains("event=drained")).count();
        long clean = harness.diagnosticLines().stream()
                .filter(l -> l.contains("event=drained") && l.contains("helper_pending=0")).count();
        assertEquals(22, drained);
        assertEquals(22, clean, "a search finished with helpers still pending");
        assertFalse(harness.history().isEmpty());
        assertNull(harness.awaitLine("bestmove 0000", Duration.ofMillis(1)));
    }
}
