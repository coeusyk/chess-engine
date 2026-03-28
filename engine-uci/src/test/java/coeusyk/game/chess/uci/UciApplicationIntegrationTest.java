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
        String bestMove = bestMoveLine.substring("bestmove ".length()).trim().toLowerCase(Locale.ROOT);

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
            builder.redirectErrorStream(true);
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
