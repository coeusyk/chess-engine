package coeusyk.game.chess.uci;

import coeusyk.game.chess.core.eval.nnue.NnueEvaluator;
import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.models.Piece;
import coeusyk.game.chess.core.movegen.MovesGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UciApplicationIntegrationTest {
    private UciHarness harness;

    @AfterEach
    void cleanup() throws IOException {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void handshakeCommandsRespondCorrectly() throws Exception {
        harness = UciHarness.start();

        harness.send("uci");
        assertNotNull(harness.awaitLine("id name Vex", Duration.ofSeconds(2)));
        assertNotNull(harness.awaitLine(line -> line.startsWith("id author "), Duration.ofSeconds(2)));
        assertNotNull(harness.awaitLine("uciok", Duration.ofSeconds(2)));

        harness.send("isready");
        assertNotNull(harness.awaitLine("readyok", Duration.ofSeconds(2)));
    }

    @Test
    void uciListsEvalTypeAndEvalFileOptions() throws Exception {
        harness = UciHarness.start();

        harness.send("uci");
        assertNotNull(harness.awaitLine(
                "option name EvalType type combo default Classical var Classical var NNUE",
                Duration.ofSeconds(2)),
                "EvalType option not advertised");
        assertNotNull(harness.awaitLine(
                "option name EvalFile type string default <empty>",
                Duration.ofSeconds(2)),
                "EvalFile option not advertised");
        assertNotNull(harness.awaitLine("uciok", Duration.ofSeconds(2)));
    }

    @Test
    void evalTypeNnueWithoutEvalFileFallsBackAtGoTime() throws Exception {
        harness = UciHarness.start();

        // setoption alone no longer decides the fallback — EvalFile might arrive
        // later — so no info string is expected here yet.
        harness.send("setoption name EvalType value NNUE");

        harness.send("position startpos moves e2e4 e7e5");
        harness.send("go depth 2");
        assertNotNull(harness.awaitLine(
                "info string NNUE evaluator requested but EvalFile is not set; falling back to Classical.",
                Duration.ofSeconds(5)),
                "Expected NNUE fallback info string at go-time");
        String bestMoveLine = harness.awaitLine(line -> line.startsWith("bestmove "), Duration.ofSeconds(10));
        assertNotNull(bestMoveLine, "Engine did not emit bestmove after EvalType NNUE fallback");
    }

    @Test
    void evalTypeNnueWithInvalidEvalFileFallsBackAtGoTime() throws Exception {
        harness = UciHarness.start();

        harness.send("setoption name EvalType value NNUE");
        harness.send("setoption name EvalFile value /nonexistent/path/does-not-exist.nnue");
        harness.send("position startpos");
        harness.send("go depth 2");

        assertNotNull(harness.awaitLine(
                line -> line.startsWith("info string Failed to load NNUE network"),
                Duration.ofSeconds(5)),
                "Expected NNUE load-failure fallback info string");
        assertNotNull(harness.awaitLine(line -> line.startsWith("bestmove "), Duration.ofSeconds(10)),
                "Engine did not emit bestmove after NNUE load failure");
    }

    @Test
    void evalTypeNnueWithValidEvalFileActuallySearchesWithNnue(@TempDir Path tempDir) throws Exception {
        Path networkFile = tempDir.resolve("tiny.nnue");
        writeTinyNnueFile(networkFile, "integration-test-uuid");

        harness = UciHarness.start();
        harness.send("setoption name EvalType value NNUE");
        harness.send("setoption name EvalFile value " + networkFile);
        harness.send("position startpos");
        harness.send("go depth 2");

        assertNotNull(harness.awaitLine(
                "info string NNUE network loaded: integration-test-uuid",
                Duration.ofSeconds(5)),
                "Expected NNUE network-loaded info string, not a fallback");
        assertNotNull(harness.awaitLine(line -> line.startsWith("bestmove "), Duration.ofSeconds(10)),
                "Engine did not emit bestmove with a loaded NNUE network");
    }

    @Test
    void evalTypeNnueWithValidEvalFileAndLazySmpGivesEachHelperItsOwnEvaluator(@TempDir Path tempDir) throws Exception {
        Path networkFile = tempDir.resolve("tiny.nnue");
        writeTinyNnueFile(networkFile, "smp-test-uuid");

        harness = UciHarness.start();
        harness.send("setoption name Threads value 2");
        harness.send("setoption name EvalType value NNUE");
        harness.send("setoption name EvalFile value " + networkFile);
        harness.send("position startpos");
        harness.send("go depth 4");

        // Not a direct instance-identity assertion (that's a code-review/construction
        // guarantee, not something observable over the UCI wire) — this exercises the
        // actual helper-spawn path with a live NnueEvaluator per thread and asserts it
        // doesn't crash or hang, which a shared/corrupted accumulator would eventually do.
        assertNotNull(harness.awaitLine(line -> line.startsWith("bestmove "), Duration.ofSeconds(15)),
                "Engine did not emit bestmove with NNUE + Threads=2");
    }

    @Test
    void evalTypeInvalidValueRejectedWithoutCrash() throws Exception {
        harness = UciHarness.start();

        harness.send("setoption name EvalType value Bogus");
        assertNotNull(harness.awaitLine(
                line -> line.startsWith("info string Unknown EvalType value"),
                Duration.ofSeconds(2)),
                "Expected rejection info string for invalid EvalType value");

        harness.send("isready");
        assertNotNull(harness.awaitLine("readyok", Duration.ofSeconds(2)),
                "Engine did not remain responsive after invalid EvalType value");
    }

    @Test
    void evalCommandInClassicalModeReturnsBreakdown() throws Exception {
        harness = UciHarness.start();

        harness.send("position startpos");
        harness.send("eval");

        assertNotNull(harness.awaitLine(
                line -> line.startsWith("info string --- eval breakdown"),
                Duration.ofSeconds(2)),
                "Expected classical eval breakdown header");
        assertNotNull(harness.awaitLine(
                line -> line.startsWith("info string") && line.contains("final"),
                Duration.ofSeconds(2)),
                "Expected classical eval breakdown final-score line");

        harness.send("isready");
        assertNotNull(harness.awaitLine("readyok", Duration.ofSeconds(2)),
                "Engine did not remain responsive after eval in Classical mode");
    }

    @Test
    void evalCommandInNnueModeReturnsNnueBreakdown(@TempDir Path tempDir) throws Exception {
        Path networkFile = tempDir.resolve("eval-cmd.nnue");
        writeTinyNnueFile(networkFile, "eval-cmd-uuid");

        harness = UciHarness.start();
        harness.send("setoption name EvalType value NNUE");
        harness.send("setoption name EvalFile value " + networkFile);
        harness.send("position startpos");
        harness.send("eval");

        assertNotNull(harness.awaitLine(
                "info string NNUE network loaded: eval-cmd-uuid",
                Duration.ofSeconds(5)),
                "Expected NNUE network-loaded info string before the breakdown");
        assertNotNull(harness.awaitLine(
                line -> line.startsWith("info string --- nnue eval breakdown"),
                Duration.ofSeconds(2)),
                "Expected NNUE eval breakdown header, not the classical one");
        assertNotNull(harness.awaitLine(
                line -> line.startsWith("info string") && line.contains("float32 oracle score"),
                Duration.ofSeconds(2)),
                "Expected the float32 oracle score line in the NNUE breakdown");
    }

    @Test
    void evalCommandInNnueModeWithoutEvalFileFallsBackToClassicalBreakdown() throws Exception {
        harness = UciHarness.start();
        harness.send("setoption name EvalType value NNUE");
        harness.send("position startpos");
        harness.send("eval");

        assertNotNull(harness.awaitLine(
                "info string NNUE evaluator requested but EvalFile is not set; falling back to Classical.",
                Duration.ofSeconds(5)),
                "Expected the same go-time NNUE fallback info string, reused for eval");
        assertNotNull(harness.awaitLine(
                line -> line.startsWith("info string --- eval breakdown"),
                Duration.ofSeconds(2)),
                "Expected the classical breakdown after falling back, not a crash or silence");
    }

    @Test
    void evalFileAloneIsInertWhileEvalTypeStaysClassical() throws Exception {
        harness = UciHarness.start();

        harness.send("setoption name EvalFile value some-network.nnue");
        harness.send("isready");
        assertNotNull(harness.awaitLine("readyok", Duration.ofSeconds(2)),
                "Engine did not remain responsive after setting EvalFile");

        // EvalType defaults to Classical, so EvalFile (even pointing at a file that
        // doesn't exist) is never consulted — no load attempt, no fallback string.
        harness.send("position startpos");
        harness.send("go depth 2");
        assertNotNull(harness.awaitLine(line -> line.startsWith("bestmove "), Duration.ofSeconds(10)),
                "Engine did not emit bestmove after setting EvalFile");
    }

    @Test
    void uciListsNnueDebugOption() throws Exception {
        harness = UciHarness.start();

        harness.send("uci");
        assertNotNull(harness.awaitLine(
                "option name NnueDebug type check default false",
                Duration.ofSeconds(2)),
                "NnueDebug option not advertised");
        assertNotNull(harness.awaitLine("uciok", Duration.ofSeconds(2)));
    }

    @Test
    void nnueDebugCommandsRejectedWhenNnueDebugOff(@TempDir Path tempDir) throws Exception {
        Path networkFile = tempDir.resolve("debug-off.nnue");
        writeTinyNnueFile(networkFile, "debug-off-uuid");

        harness = UciHarness.start();
        harness.send("setoption name EvalType value NNUE");
        harness.send("setoption name EvalFile value " + networkFile);
        harness.send("position startpos");
        harness.send("nnue features");

        assertNotNull(harness.awaitLine(
                "info string nnue debug commands require NnueDebug=true",
                Duration.ofSeconds(2)),
                "Expected rejection info string when NnueDebug is off");
    }

    @Test
    void nnueDebugCommandsRejectedWhenEvalTypeIsClassical() throws Exception {
        harness = UciHarness.start();
        harness.send("setoption name NnueDebug value true");
        harness.send("position startpos");
        harness.send("nnue acc");

        assertNotNull(harness.awaitLine(
                "info string nnue debug commands require EvalType=NNUE",
                Duration.ofSeconds(2)),
                "Expected rejection info string when active eval type is Classical");
    }

    @Test
    void nnueFeaturesCommandListsActiveFeaturesWhenEnabled(@TempDir Path tempDir) throws Exception {
        Path networkFile = tempDir.resolve("features.nnue");
        writeTinyNnueFile(networkFile, "features-uuid");

        harness = UciHarness.start();
        harness.send("setoption name NnueDebug value true");
        harness.send("setoption name EvalType value NNUE");
        harness.send("setoption name EvalFile value " + networkFile);
        harness.send("position startpos");
        harness.send("nnue features");

        assertNotNull(harness.awaitLine(
                "info string NNUE network loaded: features-uuid",
                Duration.ofSeconds(5)),
                "Expected NNUE network-loaded info string before the feature list");
        assertNotNull(harness.awaitLine(
                line -> line.startsWith("info string active features:"),
                Duration.ofSeconds(2)),
                "Expected the active-features header");
        assertNotNull(harness.awaitLine(
                line -> line.startsWith("info string white=["),
                Duration.ofSeconds(2)),
                "Expected the white-perspective active feature index list");
        assertNotNull(harness.awaitLine(
                line -> line.startsWith("info string black=["),
                Duration.ofSeconds(2)),
                "Expected the black-perspective active feature index list");
    }

    @Test
    void nnueAccCommandDumpsRootAccumulatorWhenEnabled(@TempDir Path tempDir) throws Exception {
        Path networkFile = tempDir.resolve("acc.nnue");
        writeTinyNnueFile(networkFile, "acc-uuid");

        harness = UciHarness.start();
        harness.send("setoption name NnueDebug value true");
        harness.send("setoption name EvalType value NNUE");
        harness.send("setoption name EvalFile value " + networkFile);
        harness.send("position startpos");
        harness.send("nnue acc");

        assertNotNull(harness.awaitLine(
                "info string NNUE network loaded: acc-uuid",
                Duration.ofSeconds(5)),
                "Expected NNUE network-loaded info string before the accumulator dump");
        assertNotNull(harness.awaitLine(
                line -> line.startsWith("info string sp=0"),
                Duration.ofSeconds(2)),
                "Expected the root-position accumulator dump (sp=0)");
    }

    @Test
    void nnueVerifyCommandReportsNoDivergenceAtRootWhenEnabled(@TempDir Path tempDir) throws Exception {
        Path networkFile = tempDir.resolve("verify.nnue");
        writeTinyNnueFile(networkFile, "verify-uuid");

        harness = UciHarness.start();
        harness.send("setoption name NnueDebug value true");
        harness.send("setoption name EvalType value NNUE");
        harness.send("setoption name EvalFile value " + networkFile);
        harness.send("position startpos");
        harness.send("nnue verify");

        assertNotNull(harness.awaitLine(
                "info string NNUE network loaded: verify-uuid",
                Duration.ofSeconds(5)),
                "Expected NNUE network-loaded info string before the verify result");
        assertNotNull(harness.awaitLine(
                "info string verify: OK (incremental matches from-scratch rebuild)",
                Duration.ofSeconds(2)),
                "Expected an OK verify result — a freshly reset root evaluator must match its own rebuild");
    }

    /**
     * {@link UciApplication#formatVerifyResult} formats {@link
     * NnueEvaluator.RebuildDiff} for a human reading the UCI console — direct unit
     * tests (not through the subprocess harness) since the divergent path can't be
     * reached over the wire: {@code corruptForTest} is package-private to {@code
     * eval.nnue} and there's no UCI command to trigger it (nor should there be —
     * that's a test-only hook, not debug-tool scope).
     */
    @Test
    void formatVerifyResultReportsOkWhenDiffMatches() {
        assertEquals("verify: OK (incremental matches from-scratch rebuild)",
                UciApplication.formatVerifyResult(NnueEvaluator.RebuildDiff.NONE));
    }

    @Test
    void formatVerifyResultReportsSymbolicPerspectiveOnMismatch() {
        NnueEvaluator.RebuildDiff whiteDiff = new NnueEvaluator.RebuildDiff(Piece.White, 3, 7);
        assertEquals("verify: MISMATCH perspective=white index=3 delta=7",
                UciApplication.formatVerifyResult(whiteDiff),
                "raw perspectiveColor int (8) must be translated to the symbolic label");

        NnueEvaluator.RebuildDiff blackDiff = new NnueEvaluator.RebuildDiff(Piece.Black, 5, -4);
        assertEquals("verify: MISMATCH perspective=black index=5 delta=-4",
                UciApplication.formatVerifyResult(blackDiff),
                "raw perspectiveColor int (16) must be translated to the symbolic label");
    }

    /** Minimal valid file in NnueNetwork's documented binary format — see NnueNetwork's Javadoc. */
    private static void writeTinyNnueFile(Path path, String uuid) throws IOException {
        int width = 4;
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(path))) {
            out.writeBytes("VNUE");
            out.writeInt(1); // formatVersion
            out.writeInt(1); // architectureId
            out.writeInt(1); // featureSetId
            out.writeInt(width);
            out.writeInt(1); // quantVersion
            out.writeInt(127); // qa
            out.writeInt(64);  // qb
            out.writeInt(400); // outputScale
            out.writeUTF(uuid);
            out.writeUTF("test-commit");
            out.writeLong(0L);
            for (int i = 0; i < 768 * width; i++) {
                out.writeShort(0);
            }
            for (int i = 0; i < width; i++) {
                out.writeShort(0);
            }
            for (int i = 0; i < 2 * width; i++) {
                out.writeShort(0);
            }
            out.writeInt(0); // outputBias
        }
    }

    @Test
    void goDepthReturnsLegalMoveForPosition() throws Exception {
        harness = UciHarness.start();

        harness.send("position startpos moves e2e4 e7e5");
        harness.send("go depth 2");

        String bestMoveLine = harness.awaitLine(line -> line.startsWith("bestmove "), Duration.ofSeconds(10));
        assertNotNull(bestMoveLine);
        String bestMoveFull = bestMoveLine.substring("bestmove ".length()).trim().toLowerCase(Locale.ROOT);
        // The engine may append " ponder <move>" — extract just the first token.
        String bestMove = bestMoveFull.contains(" ") ? bestMoveFull.substring(0, bestMoveFull.indexOf(' ')) : bestMoveFull;

        Board board = new Board();
        applyUciMove(board, "e2e4");
        applyUciMove(board, "e7e5");

        List<Move> legalMoves = new MovesGenerator(board).getActiveMoves(board.getActiveColor());
        assertTrue(
                legalMoves.stream().map(this::toUci).anyMatch(bestMove::equals),
                "Engine emitted illegal move for current position: " + bestMove
        );
    }

    @Test
    void helperExitDiagnosticsExposeWorkAndElapsedTime() throws Exception {
        harness = UciHarness.startWithDiagnostics();
        harness.send("setoption name Threads value 2");
        harness.send("setoption name OwnBook value false");
        harness.send("ucinewgame");
        harness.send("position startpos");
        harness.send("go depth 6");

        String begin = harness.awaitDiagnostic(line -> line.contains("event=begin"), Duration.ofSeconds(5));
        assertNotNull(begin, "No search begin diagnostic");
        long searchId = diagnosticLong(begin, "search");
        assertNotNull(harness.awaitLine(line -> line.startsWith("bestmove "), Duration.ofSeconds(10)));
        String bestmove = harness.awaitDiagnostic(line -> isDiagnostic(line, searchId, "bestmove"),
                Duration.ofSeconds(5));
        String exit = harness.awaitDiagnostic(line -> isDiagnostic(line, searchId, "exit"), Duration.ofSeconds(10));
        String drained = harness.awaitDiagnostic(line -> isDiagnostic(line, searchId, "drained"), Duration.ofSeconds(10));

        assertNotNull(bestmove, "Bestmove diagnostic missing");
        assertNotNull(exit, "Helper did not exit");
        assertNotNull(drained, "Search drain diagnostic missing");
        assertTrue(exit.contains("helper_nodes="), "Helper node count missing: " + exit);
        assertTrue(exit.contains("helper_elapsed_ns="), "Helper elapsed time missing: " + exit);
        assertTrue(exit.contains("search_elapsed_ns="), "Search elapsed time missing at helper exit: " + exit);
        assertTrue(bestmove.contains("search_elapsed_ns="), "Search elapsed time missing at bestmove: " + bestmove);
        assertTrue(diagnosticLong(exit, "helper_nodes") >= 0);
        assertTrue(diagnosticLong(exit, "helper_elapsed_ns") > 0);
        assertTrue(diagnosticLong(exit, "search_elapsed_ns") > 0);
        assertTrue(diagnosticLong(bestmove, "search_elapsed_ns") > 0);
        assertEquals(0, diagnosticInt(drained, "helper_pending"));
        assertTrue(drained.contains("hashfull="), "Post-drain TT fullness missing: " + drained);
    }

    static Stream<Arguments> activeHashResizeArms() {
        return Stream.of(
                Arguments.of(2, 16, 32),
                Arguments.of(2, 32, 16),
                Arguments.of(4, 16, 32),
                Arguments.of(4, 32, 16));
    }

    @ParameterizedTest(name = "active Hash resize Threads={0}, {1}MB to {2}MB")
    @MethodSource("activeHashResizeArms")
    void activeHashResizeWaitsForSearchQuiescence(int threads, int oldHashMb, int newHashMb) throws Exception {
        Assumptions.assumeTrue(Runtime.getRuntime().availableProcessors() >= threads,
                "The SMP arm requires at least " + threads + " available processors");
        harness = UciHarness.startWithDiagnostics();
        harness.send("uci");
        assertNotNull(harness.awaitLine("uciok", Duration.ofSeconds(5)));
        harness.send("setoption name Threads value " + threads);
        harness.send("setoption name Hash value " + oldHashMb);
        harness.send("setoption name EvalType value Classical");
        harness.send("setoption name OwnBook value false");
        harness.send("setoption name SyzygyOnline value false");
        harness.send("setoption name MultiPV value 1");
        harness.send("setoption name Contempt value 0");
        harness.send("setoption name PawnHashSize value 1");
        harness.send("isready");
        assertNotNull(harness.awaitLine("readyok", Duration.ofSeconds(5)));
        harness.send("ucinewgame");
        harness.send("isready");
        assertNotNull(harness.awaitLine("readyok", Duration.ofSeconds(5)));
        harness.send("position startpos");
        harness.send("go depth 127");

        String begin = harness.awaitDiagnostic(line -> line.contains("event=begin"), Duration.ofSeconds(5));
        assertNotNull(begin, "No diagnostic begin event");
        long searchId = diagnosticLong(begin, "search");
        assertEquals(threads - 1, diagnosticInt(begin, "helpers"));
        for (int helper = 1; helper < threads; helper++) {
            int helperId = helper;
            assertNotNull(harness.awaitDiagnostic(line -> isDiagnostic(line, searchId, "start")
                            && diagnosticInt(line, "helper") == helperId,
                    Duration.ofSeconds(5)), "Helper did not start: " + helper);
        }
        assertNotNull(harness.awaitLine(line -> line.startsWith("info depth "), Duration.ofSeconds(5)));

        int beforeResize = harness.outputHistory.size();
        harness.send("setoption name Hash value " + newHashMb);
        harness.send("isready");
        assertNotNull(harness.awaitLine("readyok", Duration.ofSeconds(5)), "Missing readyok after active resize");
        List<String> beforeNextGo = new java.util.ArrayList<>(harness.outputHistory.subList(
                beforeResize, harness.outputHistory.size()));
        int readyBeforeGo = beforeNextGo.indexOf("readyok");
        String bestmoveBeforeReady = null;
        for (int i = 0; i < readyBeforeGo; i++) {
            if (beforeNextGo.get(i).startsWith("bestmove ")) {
                bestmoveBeforeReady = beforeNextGo.get(i);
                break;
            }
        }
        harness.send("go depth 6");
        String firstBestmove = bestmoveBeforeReady != null ? bestmoveBeforeReady
                : harness.awaitLine(line -> line.startsWith("bestmove "), Duration.ofSeconds(5));
        String secondBestmove = harness.awaitLine(line -> line.startsWith("bestmove "), Duration.ofSeconds(15));

        assertNotNull(firstBestmove, "Missing bestmove for interrupted search");
        assertNotNull(secondBestmove, "Missing bestmove for post-resize search");
        String bestmoveEvent = harness.awaitDiagnostic(line -> isDiagnostic(line, searchId, "bestmove"),
                Duration.ofSeconds(5));
        assertNotNull(bestmoveEvent, "Missing interrupted-search bestmove diagnostics");
        for (int helper = 1; helper < threads; helper++) {
            int helperId = helper;
            assertNotNull(harness.awaitDiagnostic(line -> isDiagnostic(line, searchId, "exit")
                            && diagnosticInt(line, "helper") == helperId,
                    Duration.ofSeconds(5)), "Helper did not exit: " + helper);
        }

        List<String> outputSinceResize = harness.outputHistory.subList(beforeResize, harness.outputHistory.size());
        int readyIndex = outputSinceResize.indexOf("readyok");
        int bestmoveIndex = -1;
        for (int i = 0; i < outputSinceResize.size(); i++) {
            if (outputSinceResize.get(i).startsWith("bestmove ")) {
                bestmoveIndex = i;
                break;
            }
        }
        List<String> exits = harness.diagnosticHistory.stream()
                .filter(line -> isDiagnostic(line, searchId, "exit"))
                .toList();
        int afterResize = exits.stream().mapToInt(line -> diagnosticInt(line, "after_resize")).sum();
        long helperExceptions = exits.stream().filter(line -> !"-".equals(diagnosticField(line, "exception"))).count();
        long submitCount = harness.diagnosticHistory.stream()
                .filter(line -> isDiagnostic(line, searchId, "submit")).count();
        boolean uncaughtMainException = harness.diagnosticHistory.stream()
                .anyMatch(line -> line.contains("Exception in thread \"uci-search-thread\""));

        List<String> failures = new java.util.ArrayList<>();
        if (!(bestmoveIndex >= 0 && readyIndex >= 0 && bestmoveIndex < readyIndex)) {
            failures.add("A: bestmove was not before readyok");
        }
        if (!isLegalStartBestmove(firstBestmove) || !isLegalStartBestmove(secondBestmove)) {
            failures.add("B: bestmove illegal");
        }
        if (afterResize != 0) failures.add("C: after_resize=" + afterResize);
        if (helperExceptions != 0 || diagnosticInt(bestmoveEvent, "helper_exceptions") != 0) {
            failures.add("D: helper exception observed");
        }
        if (exits.size() != submitCount) failures.add("E: submits=" + submitCount + " exits=" + exits.size());
        if (uncaughtMainException) failures.add("F: uncaught main-search exception");

        assertTrue(failures.isEmpty(), "Resize arm Threads=" + threads + " " + oldHashMb + "->" + newHashMb
                + "MB failed " + failures + "; diagnostics=" + exits + "; ready_index=" + readyIndex
                + "; bestmove_index=" + bestmoveIndex);
    }

    private static boolean isDiagnostic(String line, long searchId, String event) {
        return line.startsWith("SMPDIAG search=" + searchId + " event=" + event + " ");
    }

    private static String diagnosticField(String line, String key) {
        if (line == null) return "";
        for (String part : line.split("\\s+")) {
            if (part.startsWith(key + "=")) return part.substring(key.length() + 1);
        }
        return "";
    }

    private static int diagnosticInt(String line, String key) {
        return Integer.parseInt(diagnosticField(line, key));
    }

    private static long diagnosticLong(String line, String key) {
        return Long.parseLong(diagnosticField(line, key));
    }

    private boolean isLegalStartBestmove(String line) {
        if (line == null || !line.startsWith("bestmove ")) return false;
        String move = line.substring("bestmove ".length()).split("\\s+")[0];
        Board board = new Board();
        return new MovesGenerator(board).getActiveMoves(board.getActiveColor()).stream()
                .anyMatch(legal -> toUci(legal).equals(move));
    }

    @Test
    void stopReturnsBestMovePromptly() throws Exception {
        harness = UciHarness.start();

        harness.send("position startpos");
        harness.send("go movetime 5000");
        Thread.sleep(150);

        Instant start = Instant.now();
        harness.send("stop");
        String bestMoveLine = harness.awaitLine(line -> line.startsWith("bestmove "), Duration.ofSeconds(2));
        long elapsedMs = Duration.between(start, Instant.now()).toMillis();

        assertNotNull(bestMoveLine);
        assertTrue(elapsedMs < 1500, "Expected stop response under 1500ms but got " + elapsedMs + "ms");
    }

    @Test
    void unknownInputDoesNotCrashLoop() throws Exception {
        harness = UciHarness.start();

        harness.send("nonsense command");
        harness.send("isready");
        assertNotNull(harness.awaitLine("readyok", Duration.ofSeconds(2)));
    }

    @Test
    void lazySmpThreads2ReturnsLegalMove() throws Exception {
        harness = UciHarness.start();

        harness.send("setoption name Threads value 2");
        harness.send("position startpos");
        harness.send("go depth 4");

        String bestMoveLine = harness.awaitLine(line -> line.startsWith("bestmove "), Duration.ofSeconds(15));
        assertNotNull(bestMoveLine, "Engine did not emit bestmove with Threads=2");

        String bestMove = bestMoveLine.substring("bestmove ".length()).trim().toLowerCase(Locale.ROOT);
        Board board = new Board();
        List<Move> legalMoves = new MovesGenerator(board).getActiveMoves(board.getActiveColor());
        assertTrue(
                legalMoves.stream().map(this::toUci).anyMatch(bestMove::startsWith),
                "Engine emitted illegal move with Threads=2: " + bestMove
        );
    }

    /**
     * AC8 for #73: no deadlocks or race conditions under extended search play.
     * Runs 1000 successive movetime searches with Threads=2 to detect any hang or
     * race condition in the Lazy SMP implementation. The loop reuses one engine
     * process (shared TT, Threads=2) and verifies every search responds within 2s.
     */
    @Test
    void lazySmpNoDeadlockOver1000Searches() throws Exception {
        harness = UciHarness.start();
        harness.send("setoption name Threads value 2");

        // Use a short movetime so 1000 iterations run quickly (~10s total).
        final int SEARCHES = 1000;
        final Duration PER_SEARCH_TIMEOUT = Duration.ofSeconds(2);
        AtomicInteger failures = new AtomicInteger(0);

        // Alternate between startpos and a middlegame position to vary TT interaction.
        String[] positions = new String[]{
                "position startpos",
                "position fen r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3"
        };

        for (int i = 0; i < SEARCHES; i++) {
            harness.send(positions[i % positions.length]);
            harness.send("go movetime 5");

            String bestMoveLine = harness.awaitLine(
                    line -> line.startsWith("bestmove "), PER_SEARCH_TIMEOUT);
            if (bestMoveLine == null) {
                // Timeout: send "stop" and drain any pending bestmove before
                // the next iteration. This prevents stale bestmoves from a
                // late-completing search from being consumed by a future iteration,
                // which would cause a cascade of further failures.
                failures.incrementAndGet();
                harness.send("stop");
                harness.awaitLine(line -> line.startsWith("bestmove "), Duration.ofSeconds(3));
            }
        }

        assertTrue(failures.get() == 0,
                "Deadlock or non-response detected in " + failures.get()
                        + "/" + SEARCHES + " searches with Threads=2");
    }

    private void applyUciMove(Board board, String uci) {
        Move move = findLegalMoveByUci(board, uci);
        Objects.requireNonNull(move, "Expected legal move for test setup: " + uci);
        board.makeMove(move);
    }

    private Move findLegalMoveByUci(Board board, String uciMove) {
        List<Move> legalMoves = new MovesGenerator(board).getActiveMoves(board.getActiveColor());
        for (Move move : legalMoves) {
            if (toUci(move).equals(uciMove)) {
                return move;
            }
        }
        return null;
    }

    private String toUci(Move move) {
        StringBuilder builder = new StringBuilder();
        builder.append(squareToUci(move.startSquare));
        builder.append(squareToUci(move.targetSquare));

        if ("promote-q".equals(move.reaction)) builder.append('q');
        if ("promote-r".equals(move.reaction)) builder.append('r');
        if ("promote-b".equals(move.reaction)) builder.append('b');
        if ("promote-n".equals(move.reaction)) builder.append('n');

        return builder.toString();
    }

    private String squareToUci(int square) {
        int file = square % 8;
        int rank = 8 - (square / 8);
        char fileChar = (char) ('a' + file);
        return "" + fileChar + rank;
    }

    private static final class UciHarness implements AutoCloseable {
        private final Process process;
        private final BufferedWriter in;
        private final BlockingQueue<String> lines;
        private final BlockingQueue<String> diagnostics;
        private final List<String> outputHistory;
        private final List<String> diagnosticHistory;

        private UciHarness(Process process, BufferedWriter in, BlockingQueue<String> lines,
                           BlockingQueue<String> diagnostics, List<String> outputHistory,
                           List<String> diagnosticHistory) {
            this.process = process;
            this.in = in;
            this.lines = lines;
            this.diagnostics = diagnostics;
            this.outputHistory = outputHistory;
            this.diagnosticHistory = diagnosticHistory;
        }

        static UciHarness start() throws IOException {
            return start(false);
        }

        static UciHarness startWithDiagnostics() throws IOException {
            return start(true);
        }

        private static UciHarness start(boolean withDiagnostics) throws IOException {
            String javaBinary = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                    ? "java.exe"
                    : "java";
            String javaExec = Path.of(System.getProperty("java.home"), "bin", javaBinary).toString();
            String classpath = System.getProperty("java.class.path");

            List<String> command = new java.util.ArrayList<>();
            command.add(javaExec);
            if (withDiagnostics) command.add("-Dvex.smp.diagnostics=true");
            command.add("-cp");
            command.add(classpath);
            command.add("coeusyk.game.chess.uci.UciApplication");
            ProcessBuilder builder = new ProcessBuilder(command);
            // Redirect stderr to INHERIT (Maven console) so that [BENCH] depth-stats
            // written to System.err by iterativeDeepening do NOT pollute the stdout
            // pipe that this harness reads bestmove lines from.  Merging the streams
            // via redirectErrorStream(true) could fill the 64 KB OS pipe buffer when
            // many depths are searched in rapid succession, blocking the engine's
            // emitBestMove write and causing the awaitLine timeout to fire.
            if (!withDiagnostics) builder.redirectError(ProcessBuilder.Redirect.INHERIT);
            Process process = builder.start();

            BufferedWriter in = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            BlockingQueue<String> lines = new LinkedBlockingQueue<>();
            BlockingQueue<String> diagnostics = new LinkedBlockingQueue<>();
            List<String> outputHistory = new CopyOnWriteArrayList<>();
            List<String> diagnosticHistory = new CopyOnWriteArrayList<>();

            Thread reader = new Thread(() -> {
                try {
                    String line;
                    while ((line = out.readLine()) != null) {
                        outputHistory.add(line.trim());
                        lines.offer(line.trim());
                    }
                } catch (IOException ignored) {
                    // Process exit closes stream and ends this thread naturally.
                }
            }, "uci-harness-reader");
            reader.setDaemon(true);
            reader.start();

            if (withDiagnostics) {
                BufferedReader err = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8));
                Thread errorReader = new Thread(() -> {
                    try {
                        String line;
                        while ((line = err.readLine()) != null) {
                            diagnosticHistory.add(line.trim());
                            diagnostics.offer(line.trim());
                        }
                    } catch (IOException ignored) {
                        // Process exit closes stream and ends this reader naturally.
                    }
                }, "uci-harness-diagnostic-reader");
                errorReader.setDaemon(true);
                errorReader.start();
            }

            return new UciHarness(process, in, lines, diagnostics, outputHistory, diagnosticHistory);
        }

        void send(String command) throws IOException {
            in.write(command);
            in.newLine();
            in.flush();
        }

        String awaitLine(String expected, Duration timeout) throws InterruptedException {
            return awaitLine(expected::equals, timeout);
        }

        String awaitLine(java.util.function.Predicate<String> predicate, Duration timeout) throws InterruptedException {
            long deadlineNanos = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadlineNanos) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    break;
                }

                String line = lines.poll(Math.max(1, remainingNanos / 1_000_000L), TimeUnit.MILLISECONDS);
                if (line == null) {
                    continue;
                }
                if (predicate.test(line)) {
                    return line;
                }
            }
            return null;
        }

        String awaitDiagnostic(java.util.function.Predicate<String> predicate, Duration timeout) throws InterruptedException {
            long deadlineNanos = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadlineNanos) {
                for (String line : diagnosticHistory) {
                    if (predicate.test(line)) return line;
                }
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) break;
                diagnostics.poll(Math.max(1, remainingNanos / 1_000_000L), TimeUnit.MILLISECONDS);
            }
            for (String line : diagnosticHistory) {
                if (predicate.test(line)) return line;
            }
            return null;
        }

        @Override
        public void close() throws IOException {
            try {
                send("quit");
            } catch (IOException ignored) {
                // Process may already be down.
            }

            try {
                process.waitFor(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            if (process.isAlive()) {
                process.destroyForcibly();
            }

            in.close();
        }
    }
}
