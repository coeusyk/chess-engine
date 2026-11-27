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
    void fixedMovetimeStartsAtPonderHit() throws Exception {
        harness = UciHarness.start();
        harness.send("position startpos");
        harness.send("go ponder movetime 700");
        assertNotNull(harness.awaitLine(l -> l.startsWith("info depth 2 "), LONG));
        assertEquals(0, harness.bestmoveCount());

        long hit = System.nanoTime();
        harness.send("ponderhit");
        String bestmove = harness.awaitLine(l -> l.startsWith("bestmove "), LONG);
        assertTrue(isLegalFrom(START, bestmove));
        assertTrue(System.nanoTime() - hit >= Duration.ofMillis(350).toNanos(),
                "the fixed budget was replaced by the zero-clock allocation");
        harness.barrier();
        assertEquals(1, harness.bestmoveCount());
    }

    /**
     * Waits until the worker reports it has finished searching and is holding its bestmove. Only then is "no bestmove
     * yet" a statement about a worker that is blocked, and not about one that has not got there. The isready round
     * trip gives a worker that wrongly skipped the hold time to print.
     */
    private void awaitHoldWithNoBestmove() throws Exception {
        assertNotNull(harness.awaitDiagnostic(l -> l.contains("event=hold"), LONG), "worker never reached its hold");
        harness.barrier();
        assertEquals(0, harness.bestmoveCount(), "bestmove published while the search was meant to be held");
    }

    @Test
    void forcedMoveUnderInfiniteIsSearchedAndWaitsForStop() throws Exception {
        harness = UciHarness.startWithDiagnostics();
        harness.send("position fen " + ONE_LEGAL_MOVE);
        harness.send("go infinite depth 6");
        awaitHoldWithNoBestmove();
        harness.send("stop");
        assertTrue(harness.awaitLine(l -> l.startsWith("bestmove "), LONG).startsWith("bestmove a1a2"));
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
        harness = UciHarness.startWithDiagnostics();
        harness.send("position fen " + MATE_IN_ONE);
        harness.send("go infinite depth 4");
        awaitHoldWithNoBestmove();
        assertTrue(harness.history().stream().anyMatch(l -> l.contains(" score mate 1")), "the mate was not found");
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
        harness.send("setoption name Hash value 16"); // joins the last search, so its bestmove is already printed
        harness.barrier();
        assertEquals(22, harness.bestmoveCount(), "one bestmove per go (20 cycles, then a replaced search and its replacement)");
        // The drained diagnostics travel on stderr, which readyok does not order. Wait for them explicitly.
        assertNotNull(harness.awaitDiagnostic(l -> countDrained(harness, false) >= 22, LONG), "not every search drained");
        assertEquals(22, countDrained(harness, false));
        assertEquals(22, countDrained(harness, true), "a search finished with helpers still pending");
        assertNull(harness.awaitLine("bestmove 0000", Duration.ofMillis(1)));
    }

    private static long countDrained(UciHarness h, boolean requireNoPendingHelpers) {
        return h.diagnosticLines().stream()
                .filter(l -> l.contains("event=drained") && (!requireNoPendingHelpers || l.contains("helper_pending=0")))
                .count();
    }

    private static final String AFTER_E4 = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1";

    /**
     * A worker that is slow to stop (the test seam keeps it alive 2.5 s after it has finished, longer than the 2 s
     * bounded wait) must not make position or go run against the state it was told to replace.
     */
    @Test
    void slowToDrainWorkerNeverLeavesPositionOrGoOnStaleState() throws Exception {
        harness = UciHarness.startWithVmOptions(false, "-Dvex.test.drainDelayMs=2500");
        harness.send("position startpos");
        harness.send("go infinite");
        assertNotNull(harness.awaitLine(l -> l.startsWith("info depth 3 "), LONG));
        harness.sendBatch("position startpos moves e2e4", "go depth 1");

        assertNotNull(harness.awaitLine(l -> l.startsWith("bestmove "), LONG), "the first search never answered");
        String second = harness.awaitLine(l -> l.startsWith("bestmove "), LONG);
        assertNotNull(second, "go depth 1 was dropped while the old worker was draining");
        assertTrue(isLegalFrom(AFTER_E4, second),
                "go ran on the old position instead of the one sent after it: " + second);
        harness.barrier();
        assertEquals(2, harness.bestmoveCount());
    }

    /** quit must not wait for a worker that never finishes: every search thread is a daemon. */
    @Test
    void quitExitsEvenIfTheWorkerIsSlowToDrain() throws Exception {
        harness = UciHarness.startWithVmOptions(false, "-Dvex.test.drainDelayMs=30000");
        harness.send("position startpos");
        harness.send("go infinite");
        assertNotNull(harness.awaitLine(l -> l.startsWith("info depth 3 "), LONG));
        harness.send("quit");
        assertTrue(harness.awaitExit(Duration.ofSeconds(10)), "the engine waited for the slow worker instead of exiting");
        assertTrue(harness.bestmoveCount() <= 1);
    }

    /**
     * A forced-move or terminal answer does not replace the previous session. A later ponderhit or stop must still do
     * nothing to the search that was answered, and the next search must be unaffected.
     */
    @Test
    void ponderhitAndStopAfterAForcedMoveAnswerAreInert() throws Exception {
        harness = UciHarness.start();
        harness.send("position startpos");
        harness.send("go ponder wtime 5000 btime 5000");
        assertNotNull(harness.awaitLine(l -> l.startsWith("info depth 2 "), LONG));
        harness.send("stop");
        assertNotNull(harness.awaitLine(l -> l.startsWith("bestmove "), LONG));

        harness.send("position fen " + ONE_LEGAL_MOVE);
        harness.send("go wtime 1000 btime 1000"); // answered immediately, no worker, no new session
        assertEquals("bestmove a1a2", harness.awaitLine(l -> l.startsWith("bestmove "), LONG));
        harness.sendBatch("ponderhit", "stop", "ponderhit");
        harness.barrier();
        assertEquals(2, harness.bestmoveCount(), "ponderhit or stop after a forced-move answer printed something");

        harness.send("position startpos");
        harness.send("go depth 2");
        assertNotNull(harness.awaitLine(l -> l.startsWith("bestmove "), LONG), "the next search was affected");
        harness.barrier();
        assertEquals(3, harness.bestmoveCount());
    }
}
