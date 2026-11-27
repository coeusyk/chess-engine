package coeusyk.game.chess.uci;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine half of fail-closed NNUE experiments: with {@code StrictNnue=true} an engine told
 * {@code EvalType=NNUE} that cannot use the requested network exits with status 2 instead of playing
 * Classical. Every test runs a fresh engine process, because the behavior under test is the process ending.
 * The engine cannot see an option that arrives under a misspelled name; {@link
 * #misspelledOptionNameIsOutOfScopeForTheEngine()} pins that limit.
 */
class StrictNnueIntegrationTest {
    private Engine engine;

    @AfterEach
    void cleanup() {
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    void uciListsStrictNnueOptionDefaultFalse() throws Exception {
        engine = Engine.start();
        engine.send("uci");
        assertNotNull(engine.await(l -> l.equals("option name StrictNnue type check default false"), 5));
    }

    @Test
    void strictWithEmptyEvalFileExitsBeforeReadyok() throws Exception {
        engine = Engine.start();
        engine.send("setoption name EvalType value NNUE");
        engine.send("setoption name StrictNnue value true");
        engine.send("isready");

        assertStrictExit("EvalFile is not set");
    }

    @Test
    void strictWithMissingFileExitsBeforeReadyok(@TempDir Path dir) throws Exception {
        engine = Engine.start();
        engine.send("setoption name EvalType value NNUE");
        engine.send("setoption name EvalFile value " + dir.resolve("does-not-exist.nnue"));
        engine.send("setoption name StrictNnue value true");
        engine.send("isready");

        assertStrictExit("cannot load EvalFile");
    }

    @Test
    void strictWithCorruptFileExitsBeforeReadyok(@TempDir Path dir) throws Exception {
        Path corrupt = dir.resolve("corrupt.nnue");
        Files.write(corrupt, new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        engine = Engine.start();
        engine.send("setoption name EvalType value NNUE");
        engine.send("setoption name EvalFile value " + corrupt);
        engine.send("setoption name StrictNnue value true");
        engine.send("isready");

        assertStrictExit("cannot load EvalFile");
    }

    @Test
    void strictWithUnknownEvalTypeValueExitsBeforeReadyok() throws Exception {
        engine = Engine.start();
        engine.send("setoption name EvalType value NNUE2");
        engine.send("setoption name StrictNnue value true");
        engine.send("isready");

        assertStrictExit("unknown EvalType value 'NNUE2'");
    }

    @Test
    void strictWithValidNetworkAnswersReadyokAndSearchesWithNnue(@TempDir Path dir) throws Exception {
        Path net = dir.resolve("tiny.nnue");
        writeTinyNnueFile(net, "11111111-1111-1111-1111-111111111111");
        engine = Engine.start();
        engine.send("setoption name EvalType value NNUE");
        engine.send("setoption name EvalFile value " + net);
        engine.send("setoption name StrictNnue value true");
        engine.send("isready");

        assertNotNull(engine.await(l -> l.equals("info string NNUE network loaded: 11111111-1111-1111-1111-111111111111"), 5));
        assertNotNull(engine.await(l -> l.equals("readyok"), 5));
        engine.send("position startpos");
        engine.send("go depth 2");
        assertNotNull(engine.await(l -> l.startsWith("bestmove "), 10));
        assertFalse(engine.history().stream().anyMatch(l -> l.contains("falling back to Classical")));
    }

    @Test
    void strictBackstopAtGoWhenOptionsChangeAfterIsready(@TempDir Path dir) throws Exception {
        Path net = dir.resolve("tiny.nnue");
        writeTinyNnueFile(net, "22222222-2222-2222-2222-222222222222");
        engine = Engine.start();
        engine.send("setoption name EvalType value NNUE");
        engine.send("setoption name EvalFile value " + net);
        engine.send("setoption name StrictNnue value true");
        engine.send("isready");
        assertNotNull(engine.await(l -> l.equals("readyok"), 5));

        engine.send("setoption name EvalFile value " + dir.resolve("gone.nnue"));
        engine.send("position startpos");
        engine.send("go depth 1");

        assertTrue(engine.awaitExit(5), "engine must end, not search");
        assertEquals(2, engine.exitValue());
        assertTrue(engine.history().stream().anyMatch(l -> l.startsWith("info string NNUE strict mode:")));
        assertTrue(engine.history().stream().noneMatch(l -> l.startsWith("bestmove ")), "no bestmove may be emitted");
    }

    @Test
    void strictWithClassicalIsUnaffected() throws Exception {
        engine = Engine.start();
        engine.send("setoption name StrictNnue value true");
        engine.send("setoption name EvalType value Classical");
        engine.send("setoption name EvalFile value /nonexistent/never-used.nnue");
        engine.send("isready");
        assertNotNull(engine.await(l -> l.equals("readyok"), 5));
        engine.send("position startpos");
        engine.send("go depth 2");

        assertNotNull(engine.await(l -> l.startsWith("bestmove "), 10));
    }

    @Test
    void nonStrictFallbackBehaviorIsUnchanged() throws Exception {
        for (String strictSetting : new String[]{null, "false"}) {
            engine = Engine.start();
            if (strictSetting != null) {
                engine.send("setoption name StrictNnue value " + strictSetting);
            }
            engine.send("setoption name EvalType value NNUE");
            engine.send("isready");
            assertNotNull(engine.await(l -> l.equals("readyok"), 5));
            engine.send("position startpos");
            engine.send("go depth 2");
            assertNotNull(engine.await(l -> l.equals(
                    "info string NNUE evaluator requested but EvalFile is not set; falling back to Classical."), 10));
            assertNotNull(engine.await(l -> l.startsWith("bestmove "), 10));
            engine.close();

            engine = Engine.start();
            engine.send("setoption name EvalType value NNUE");
            engine.send("setoption name EvalFile value /nonexistent/path/does-not-exist.nnue");
            engine.send("position startpos");
            engine.send("go depth 2");
            assertNotNull(engine.await(l -> l.startsWith("info string Failed to load NNUE network"), 10));
            assertNotNull(engine.await(l -> l.startsWith("bestmove "), 10));
            engine.close();
        }
    }

    @Test
    void strictIsOrderIndependent(@TempDir Path dir) throws Exception {
        engine = Engine.start();
        engine.send("setoption name StrictNnue value true");
        engine.send("setoption name EvalFile value " + dir.resolve("none.nnue"));
        engine.send("setoption name EvalType value NNUE");
        engine.send("isready");
        assertStrictExit("cannot load EvalFile");
    }

    @Test
    void strictAlsoGuardsEvalNnueAndBenchWithoutAnIsready(@TempDir Path dir) throws Exception {
        for (String command : new String[]{"eval", "nnue features", "bench 1"}) {
            engine = Engine.start();
            engine.send("setoption name EvalType value NNUE");
            engine.send("setoption name EvalFile value " + dir.resolve("none.nnue"));
            engine.send("setoption name StrictNnue value true");
            engine.send(command);

            assertTrue(engine.awaitExit(10), command + ": engine must exit");
            assertEquals(2, engine.exitValue(), command);
            assertTrue(engine.history().stream().anyMatch(l -> l.startsWith("info string NNUE strict mode:")), command);
            engine.close();
        }
    }

    @Test
    void strictTreatsAnyCaseOfNnueAsARequestForNnue(@TempDir Path dir) throws Exception {
        engine = Engine.start();
        engine.send("setoption name EvalType value nnue");
        engine.send("setoption name EvalFile value " + dir.resolve("none.nnue"));
        engine.send("setoption name StrictNnue value true");
        engine.send("isready");

        assertStrictExit("cannot load EvalFile");
    }

    @Test
    void strictLoadedLineIsPrintedAtIsreadyNotAtFirstGo(@TempDir Path dir) throws Exception {
        Path net = dir.resolve("tiny.nnue");
        writeTinyNnueFile(net, "33333333-3333-3333-3333-333333333333");
        engine = Engine.start();
        engine.send("setoption name EvalType value NNUE");
        engine.send("setoption name EvalFile value " + net);
        engine.send("setoption name StrictNnue value true");
        engine.send("isready");

        assertNotNull(engine.await(l -> l.equals("info string NNUE network loaded: 33333333-3333-3333-3333-333333333333"), 5));
        assertNotNull(engine.await(l -> l.equals("readyok"), 5));
    }

    /** The engine only knows what it is told: a misspelled option name is ignored under the UCI rules. */
    @Test
    void misspelledOptionNameIsOutOfScopeForTheEngine() throws Exception {
        engine = Engine.start();
        engine.send("setoption name StrictNnue value true");
        engine.send("setoption name Evaltype2 value NNUE");
        engine.send("isready");
        assertNotNull(engine.await(l -> l.equals("readyok"), 5));
        engine.send("position startpos");
        engine.send("go depth 2");

        assertNotNull(engine.await(l -> l.startsWith("bestmove "), 10), "Classical plays on, which is why launchers must verify options");
    }

    private void assertStrictExit(String reasonFragment) throws Exception {
        assertTrue(engine.awaitExit(5), "engine must exit within 5 seconds");
        assertEquals(2, engine.exitValue());
        List<String> strictLines = engine.history().stream().filter(l -> l.startsWith("info string NNUE strict mode:")).toList();
        assertEquals(1, strictLines.size(), engine.history().toString());
        assertTrue(strictLines.get(0).contains(reasonFragment), strictLines.get(0));
        assertFalse(engine.history().contains("readyok"), "readyok must not be sent");
        assertNull(engine.history().stream().filter(l -> l.startsWith("bestmove ")).findFirst().orElse(null));
    }

    /** Minimal valid file in NnueNetwork's documented binary format. */
    private static void writeTinyNnueFile(Path path, String uuid) throws IOException {
        int width = 4;
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(path))) {
            out.writeBytes("VNUE");
            out.writeInt(1);
            out.writeInt(1);
            out.writeInt(1);
            out.writeInt(width);
            out.writeInt(1);
            out.writeInt(127);
            out.writeInt(64);
            out.writeInt(400);
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
            out.writeInt(0);
        }
    }

    private static final class Engine {
        private final Process process;
        private final BufferedWriter in;
        private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        private final List<String> history = new CopyOnWriteArrayList<>();

        private Engine(Process process) {
            this.process = process;
            this.in = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            Thread reader = new Thread(() -> {
                try (BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = out.readLine()) != null) {
                        history.add(line.trim());
                        lines.offer(line.trim());
                    }
                } catch (IOException ignored) {
                    // the process ending closes the stream
                }
            }, "strict-nnue-test-reader");
            reader.setDaemon(true);
            reader.start();
        }

        static Engine start() throws IOException {
            String javaBinary = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
            List<String> command = List.of(
                    Path.of(System.getProperty("java.home"), "bin", javaBinary).toString(),
                    "--add-modules", "jdk.incubator.vector",
                    "-cp", System.getProperty("java.class.path"),
                    "coeusyk.game.chess.uci.UciApplication");
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            return new Engine(builder.start());
        }

        void send(String command) throws IOException {
            in.write(command);
            in.newLine();
            in.flush();
        }

        String await(Predicate<String> predicate, int seconds) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            while (System.nanoTime() < deadline) {
                String line = lines.poll(100, TimeUnit.MILLISECONDS);
                if (line != null && predicate.test(line)) {
                    return line;
                }
            }
            return null;
        }

        boolean awaitExit(int seconds) throws InterruptedException {
            boolean exited = process.waitFor(seconds, TimeUnit.SECONDS);
            // Let the reader drain what the process wrote before it ended.
            Thread.sleep(100);
            return exited;
        }

        int exitValue() {
            return process.exitValue();
        }

        List<String> history() {
            return history;
        }

        void close() {
            process.destroyForcibly();
        }
    }
}
