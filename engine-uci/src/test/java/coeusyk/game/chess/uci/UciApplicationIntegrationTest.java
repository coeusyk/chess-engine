package coeusyk.game.chess.uci;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.movegen.MovesGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

        private UciHarness(Process process, BufferedWriter in, BlockingQueue<String> lines) {
            this.process = process;
            this.in = in;
            this.lines = lines;
        }

        static UciHarness start() throws IOException {
            String javaBinary = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                    ? "java.exe"
                    : "java";
            String javaExec = Path.of(System.getProperty("java.home"), "bin", javaBinary).toString();
            String classpath = System.getProperty("java.class.path");

            ProcessBuilder builder = new ProcessBuilder(
                    javaExec,
                    "-cp",
                    classpath,
                    "coeusyk.game.chess.uci.UciApplication"
            );
            // Redirect stderr to INHERIT (Maven console) so that [BENCH] depth-stats
            // written to System.err by iterativeDeepening do NOT pollute the stdout
            // pipe that this harness reads bestmove lines from.  Merging the streams
            // via redirectErrorStream(true) could fill the 64 KB OS pipe buffer when
            // many depths are searched in rapid succession, blocking the engine's
            // emitBestMove write and causing the awaitLine timeout to fire.
            builder.redirectError(ProcessBuilder.Redirect.INHERIT);
            Process process = builder.start();

            BufferedWriter in = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            BlockingQueue<String> lines = new LinkedBlockingQueue<>();

            Thread reader = new Thread(() -> {
                try {
                    String line;
                    while ((line = out.readLine()) != null) {
                        lines.offer(line.trim());
                    }
                } catch (IOException ignored) {
                    // Process exit closes stream and ends this thread naturally.
                }
            }, "uci-harness-reader");
            reader.setDaemon(true);
            reader.start();

            return new UciHarness(process, in, lines);
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
