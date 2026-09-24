import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.uci.BenchRunner;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Native-Windows Stage 3 driver for the production UCI Lazy SMP path. */
public final class Phase20Stage3Harness {
    private static final int DEPTH = 13;
    private static final int HASH_MB = 16;
    private static final int PAWN_HASH_MB = 1;
    private static final int[] THREADS = {1, 2, 4};
    private static final int MEASURED_PASSES = 7;
    private static final long EXPECTED_1T_NODES = 24_780_049L;
    private static final long SEARCH_TIMEOUT_MINUTES = 10;
    private static final String[] FENS = loadFens();
    private static final String[] JVM_FLAGS = {
            "-Xms512m", "-Xmx512m", "-XX:+UseG1GC", "--add-modules", "jdk.incubator.vector"
    };

    private Phase20Stage3Harness() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--validate-only")) {
            validateOnly();
            return;
        }
        if (args.length != 4 || !args[0].equals("--run")) {
            throw new IllegalArgumentException("usage: --run output-dir jar java-exe | --validate-only");
        }
        run(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]));
    }

    private static String[] loadFens() {
        try {
            Field field = BenchRunner.class.getDeclaredField("BENCH_FENS");
            field.setAccessible(true);
            String[] fens = ((String[]) field.get(null)).clone();
            if (fens.length != 31) throw new IllegalStateException("Expected 31 BenchRunner FENs, found " + fens.length);
            return fens;
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static void validateOnly() {
        for (int i = 0; i < FENS.length; i++) {
            if (!Board.isLegalFen(FENS[i])) throw new IllegalStateException("Illegal BenchRunner FEN at index " + i);
        }
        require(THREADS.length == 3 && THREADS[0] == 1 && THREADS[1] == 2 && THREADS[2] == 4,
                "Unexpected thread arms");
        Map<String, String> info = fields("info depth 13 seldepth 22 multipv 1 score cp 41 nodes 1234 nps 50000 time 25 hashfull 17 pv e2e4");
        Map<String, String> helper = fields("SMPDIAG search=9 event=exit helper=1 helper_nodes=5 helper_elapsed_ns=1000 search_elapsed_ns=2000");
        Map<String, String> drained = fields("SMPDIAG search=9 event=drained helper_pending=0 drain_elapsed_ns=3000 hashfull=17");
        require("13".equals(info.get("depth")) && "1234".equals(info.get("nodes"))
                && "25".equals(info.get("time")) && "17".equals(info.get("hashfull")), "UCI info parser self-check failed");
        require("9".equals(helper.get("search")) && "5".equals(helper.get("helper_nodes"))
                && "1000".equals(helper.get("helper_elapsed_ns")), "SMP diagnostic parser self-check failed");
        require("0".equals(drained.get("helper_pending")) && "3000".equals(drained.get("drain_elapsed_ns"))
                && "17".equals(drained.get("hashfull")), "Drain diagnostic parser self-check failed");
        System.out.printf(Locale.ROOT, "corpus=%d depth=%d expected_1t_nodes=%d hash_mb=%d pawn_hash_mb=%d threads=1,2,4%n",
                FENS.length, DEPTH, EXPECTED_1T_NODES, HASH_MB, PAWN_HASH_MB);
    }

    private static void run(Path output, Path jar, Path javaExe) throws Exception {
        Files.createDirectories(output);
        Path workersFile = output.resolve("workers.csv");
        Path helpersFile = output.resolve("helpers.csv");
        Path eventsFile = output.resolve("events.csv");
        int completedMeasured = 0;
        long warmupOneThreadNodes = 0;
        try (Csv workers = new Csv(workersFile,
                     "run_type", "pass", "position", "fen", "threads", "search", "main_depth", "main_nodes",
                     "main_qnodes", "main_tt_hits", "main_ttd_ms", "main_nps_uci", "hashfull_depth13",
                     "final_hashfull", "main_move",
                     "bestmove", "bestmove_source", "legal", "total_nodes", "total_nps", "drain_elapsed_ns",
                     "helpers_submitted", "helpers_started", "helpers_exited", "helper_exceptions", "info_line",
                     "bestmove_diagnostic");
             Csv helpers = new Csv(helpersFile,
                     "run_type", "pass", "position", "threads", "search", "helper", "exit_cause",
                     "completed_depth", "bestmove", "helper_nodes", "helper_elapsed_ns", "helper_nps",
                     "tt_reads", "tt_writes", "tt_other", "after_abort", "after_generation", "after_clear",
                     "after_resize", "reads_after_abort", "writes_after_abort", "other_after_abort",
                     "reads_after_generation", "writes_after_generation", "other_after_generation",
                     "reads_after_clear", "writes_after_clear", "other_after_clear", "reads_after_resize",
                     "writes_after_resize", "other_after_resize", "diagnostic");
             Csv events = new Csv(eventsFile,
                     "run_type", "pass", "position", "threads", "search", "event", "helper", "diagnostic")) {
            try (UciEngine engine = UciEngine.start(javaExe, jar)) {
                engine.send("uci");
                engine.await("uciok", new ArrayList<>(), 30);
                configure(engine);

                for (int position = 0; position < FENS.length; position++) {
                    for (int offset = 0; offset < THREADS.length; offset++) {
                        int n = THREADS[(position + offset) % THREADS.length];
                        prepare(engine, n);
                        SearchSample sample = search(engine, position, FENS[position], n);
                        validateSample(sample, n, position);
                        if (n == 1) warmupOneThreadNodes += sample.mainNodes;
                    }
                }
                require(warmupOneThreadNodes == EXPECTED_1T_NODES,
                        "Discarded 1T warm-up did not reproduce the frozen depth-13 corpus node total: "
                                + warmupOneThreadNodes);

                for (int pass = 1; pass <= MEASURED_PASSES; pass++) {
                    long oneThreadNodes = 0;
                    for (int position = 0; position < FENS.length; position++) {
                        for (int offset = 0; offset < THREADS.length; offset++) {
                            int order = (position + pass - 1 + offset) % THREADS.length;
                            int n = THREADS[order];
                            prepare(engine, n);
                            SearchSample sample = search(engine, position, FENS[position], n);
                            validateSample(sample, n, position);
                            sample.write(workers, helpers, events, "measured", pass);
                            if (n == 1) oneThreadNodes += sample.mainNodes;
                            completedMeasured++;
                        }
                    }
                    require(oneThreadNodes == EXPECTED_1T_NODES,
                            "Measured 1T pass " + pass + " did not reproduce the frozen depth-13 corpus total: "
                                    + oneThreadNodes);
                    System.out.printf(Locale.ROOT, "pass=%d complete; measured samples=%d%n", pass, completedMeasured);
                }

                prepare(engine, 1); // final ucinewgame/isready barrier joins the last search before shutdown
            }
        }
        if (completedMeasured != THREADS.length * MEASURED_PASSES * FENS.length) {
            throw new IllegalStateException("Incomplete measured schedule: " + completedMeasured);
        }
    }

    private static void configure(UciEngine engine) throws Exception {
        engine.send("setoption name EvalType value Classical");
        engine.send("setoption name OwnBook value false");
        engine.send("setoption name SyzygyOnline value false");
        engine.send("setoption name MultiPV value 1");
        engine.send("setoption name Contempt value 0");
        engine.send("setoption name Hash value " + HASH_MB);
        engine.send("setoption name PawnHashSize value " + PAWN_HASH_MB);
        engine.send("isready");
        engine.await("readyok", new ArrayList<>(), 30);
    }

    private static void prepare(UciEngine engine, int threads) throws Exception {
        engine.send("ucinewgame");
        engine.send("setoption name Threads value " + threads);
        List<String> lines = new ArrayList<>();
        engine.send("isready");
        engine.await("readyok", lines, 30);
        require(lines.stream().noneMatch(line -> line.startsWith("bestmove ")),
                "Unexpected extra bestmove while quiescing before Threads=" + threads);
    }

    private static SearchSample search(UciEngine engine, int position, String fen, int threads) throws Exception {
        engine.send("position fen " + fen);
        engine.send("go depth " + DEPTH);
        List<String> lines = new ArrayList<>();
        String bestmoveLine = null;
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(SEARCH_TIMEOUT_MINUTES);
        while (bestmoveLine == null) {
            String line = engine.nextLine(deadline);
            lines.add(line);
            failOnMainException(line);
            if (line.startsWith("bestmove ")) bestmoveLine = line;
        }

        Map<String, String> begin = event(lines, -1, "begin");
        require(begin != null, "Missing SMP begin diagnostic at position " + position);
        long searchId = number(begin, "search");
        int submittedHelpers = integer(begin, "helpers");
        Map<String, String> bestmove = awaitEvent(engine, lines, searchId, "bestmove", deadline);
        int expectedHelpers = threads - 1;
        require(submittedHelpers == expectedHelpers,
                "Threads=" + threads + " expected " + expectedHelpers + " helpers, got " + submittedHelpers);

        List<Map<String, String>> exits = new ArrayList<>();
        while (exits.size() < expectedHelpers) {
            String line = engine.nextLine(deadline);
            lines.add(line);
            failOnMainException(line);
            if (line.startsWith("bestmove ")) throw new IllegalStateException("Duplicate bestmove for search " + searchId);
            Map<String, String> diag = parseEvent(line, searchId, "exit");
            if (diag != null) exits.add(diag);
        }
        require(exits.stream().map(exit -> exit.get("helper")).distinct().count() == expectedHelpers,
                "Helper exit IDs were not unique for search " + searchId);
        Map<String, String> drained = awaitEvent(engine, lines, searchId, "drained", deadline);

        int submits = countEvents(lines, searchId, "submit");
        int starts = countEvents(lines, searchId, "start");
        require(submits == expectedHelpers && starts == expectedHelpers && exits.size() == expectedHelpers,
                "Helper lifecycle count mismatch search=" + searchId + " submit/start/exit="
                        + submits + "/" + starts + "/" + exits.size());
        require(integer(bestmove, "helper_submitted") == expectedHelpers
                        && integer(bestmove, "helper_exceptions") == 0
                        && integer(bestmove, "helper_pending") >= 0,
                "Bestmove helper accounting failed search=" + searchId);
        require(integer(drained, "helper_submitted") == expectedHelpers
                        && integer(drained, "helper_started") == expectedHelpers
                        && integer(drained, "helper_exited") == expectedHelpers
                        && integer(drained, "helper_pending") == 0
                        && integer(drained, "helper_exceptions") == 0,
                "Post-drain helper accounting failed search=" + searchId);
        require("true".equals(bestmove.get("legal")), "Illegal bestmove according to root legal move list: " + bestmove);
        String emittedMove = bestmoveLine.split("\\s+", 3)[1];
        require(emittedMove.equals(bestmove.get("move")), "UCI bestmove differs from diagnostic move");
        require("main-result".equals(bestmove.get("source")), "Bestmove did not come from the main-thread result");
        require(integer(bestmove, "main_depth") == DEPTH, "Main search failed to reach depth " + DEPTH);

        List<String> depthLines = lines.stream()
                .filter(line -> line.startsWith("info depth " + DEPTH + " "))
                .toList();
        require(depthLines.size() == 1, "Expected one depth-" + DEPTH + " info line; got " + depthLines.size());
        Map<String, String> info = fields(depthLines.get(0));
        long mainNodes = number(info, "nodes");
        require(mainNodes == number(bestmove, "main_nodes"), "UCI and diagnostic main node counts differ");
        require(number(info, "time") > 0 && number(info, "nps") > 0 && number(info, "hashfull") >= 0,
                "Missing main TTD/NPS/final hashfull fields");

        long totalNodes = mainNodes;
        long drainElapsedNs = number(drained, "drain_elapsed_ns");
        for (Map<String, String> exit : exits) {
            int helper = integer(exit, "helper");
            require("-".equals(exit.get("exception")), "Helper exception search=" + searchId + " helper=" + helper);
            require(number(exit, "helper_nodes") >= 0 && number(exit, "helper_elapsed_ns") > 0,
                    "Invalid helper node/time count search=" + searchId + " helper=" + helper);
            require(number(exit, "after_generation") == 0 && number(exit, "after_clear") == 0
                            && number(exit, "after_resize") == 0,
                    "Old helper activity crossed a TT lifecycle boundary: " + exit);
            for (String key : List.of("reads_after_generation", "writes_after_generation", "other_after_generation",
                    "reads_after_clear", "writes_after_clear", "other_after_clear",
                    "reads_after_resize", "writes_after_resize", "other_after_resize")) {
                require(number(exit, key) == 0, "TT activity after lifecycle boundary " + key + ": " + exit);
            }
            totalNodes += number(exit, "helper_nodes");
        }
        require(drainElapsedNs > 0, "Non-positive search/drain elapsed time");
        return new SearchSample(position, fen, threads, searchId, integer(info, "depth"), mainNodes,
                number(bestmove, "main_qnodes"), number(bestmove, "main_tt_hits"), number(info, "time"),
                number(info, "nps"), integer(info, "hashfull"), integer(drained, "hashfull"),
                bestmove.get("main_move"), emittedMove,
                bestmove.get("source"), Boolean.parseBoolean(bestmove.get("legal")), totalNodes,
                totalNodes * 1_000_000_000.0 / drainElapsedNs, drainElapsedNs, submittedHelpers, starts,
                exits.size(), integer(bestmove, "helper_exceptions"), depthLines.get(0), bestmove, exits, lines);
    }

    private static void validateSample(SearchSample sample, int threads, int position) {
        if (threads == 1) {
            require(sample.submittedHelpers == 0 && sample.startedHelpers == 0 && sample.exitedHelpers == 0,
                    "Threads=1 created helper work at position " + position);
        }
    }

    private static void failOnMainException(String line) {
        require(!line.contains("Exception in thread \"uci-search-thread\""), "Uncaught main-search exception: " + line);
        require(!line.contains("SMP helper exception"), "Logged SMP helper exception: " + line);
        require(!line.contains("SMP helper execution failure"), "Uncaught helper failure: " + line);
    }

    private static Map<String, String> awaitEvent(UciEngine engine, List<String> lines, long searchId,
                                                   String name, long deadline) throws Exception {
        Map<String, String> found = event(lines, searchId, name);
        while (found == null) {
            String line = engine.nextLine(deadline);
            lines.add(line);
            failOnMainException(line);
            if (line.startsWith("bestmove ")) throw new IllegalStateException("Duplicate bestmove for search " + searchId);
            found = parseEvent(line, searchId, name);
        }
        return found;
    }

    private static Map<String, String> event(List<String> lines, long searchId, String name) {
        for (String line : lines) {
            Map<String, String> event = parseEvent(line, searchId, name);
            if (event != null) return event;
        }
        return null;
    }

    private static Map<String, String> parseEvent(String line, long searchId, String name) {
        if (!line.startsWith("SMPDIAG ")) return null;
        Map<String, String> fields = fields(line);
        if (!name.equals(fields.get("event"))) return null;
        if (searchId >= 0 && number(fields, "search") != searchId) return null;
        return fields;
    }

    private static int countEvents(List<String> lines, long searchId, String name) {
        return (int) lines.stream().filter(line -> parseEvent(line, searchId, name) != null).count();
    }

    private static Map<String, String> fields(String line) {
        Map<String, String> fields = new HashMap<>();
        for (String part : line.split("\\s+")) {
            int equals = part.indexOf('=');
            if (equals > 0 && equals < part.length() - 1) fields.put(part.substring(0, equals), part.substring(equals + 1));
        }
        String[] parts = line.split("\\s+");
        for (int i = 0; i + 1 < parts.length; i++) {
            if (List.of("depth", "time", "nodes", "nps", "hashfull").contains(parts[i])) {
                fields.put(parts[i], parts[i + 1]);
            }
        }
        return fields;
    }

    private static long number(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value == null) throw new IllegalStateException("Missing " + key + " in " + fields);
        return Long.parseLong(value);
    }

    private static int integer(Map<String, String> fields, String key) {
        return Math.toIntExact(number(fields, key));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static String csv(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private record SearchSample(int position, String fen, int threads, long searchId, int depth,
                                long mainNodes, long mainQnodes, long mainTtHits, long mainTtdMs,
                                long mainNpsUci, int hashfullDepth13, int finalHashfull, String mainMove, String bestmove,
                                String bestmoveSource, boolean legal, long totalNodes, double totalNps,
                                long drainElapsedNs, int submittedHelpers, int startedHelpers,
                                int exitedHelpers, int helperExceptions, String infoLine,
                                Map<String, String> bestmoveDiagnostic, List<Map<String, String>> exits,
                                List<String> lines) {
        void write(Csv workers, Csv helpers, Csv events, String runType, int pass) throws IOException {
            workers.row(runType, pass, position, fen, threads, searchId, depth, mainNodes, mainQnodes,
                    mainTtHits, mainTtdMs, mainNpsUci, hashfullDepth13, finalHashfull,
                    mainMove, bestmove, bestmoveSource, legal,
                    totalNodes, String.format(Locale.ROOT, "%.3f", totalNps), drainElapsedNs,
                    submittedHelpers, startedHelpers, exitedHelpers, helperExceptions, infoLine,
                    bestmoveDiagnostic);
            for (String line : lines) {
                if (!line.startsWith("SMPDIAG ")) continue;
                Map<String, String> event = fields(line);
                events.row(runType, pass, position, threads, event.get("search"), event.get("event"),
                        event.getOrDefault("helper", "-"), line);
            }
            for (Map<String, String> exit : exits) {
                long nodes = number(exit, "helper_nodes");
                long elapsed = number(exit, "helper_elapsed_ns");
                double nps = nodes * 1_000_000_000.0 / elapsed;
                helpers.row(runType, pass, position, threads, searchId, exit.get("helper"), exit.get("exit_cause"),
                        exit.get("completed_depth"), exit.get("bestmove"), nodes, elapsed,
                        String.format(Locale.ROOT, "%.3f", nps), exit.get("tt_reads"), exit.get("tt_writes"),
                        exit.get("tt_other"), exit.get("after_abort"), exit.get("after_generation"),
                        exit.get("after_clear"), exit.get("after_resize"), exit.get("reads_after_abort"),
                        exit.get("writes_after_abort"), exit.get("other_after_abort"),
                        exit.get("reads_after_generation"), exit.get("writes_after_generation"),
                        exit.get("other_after_generation"), exit.get("reads_after_clear"),
                        exit.get("writes_after_clear"), exit.get("other_after_clear"),
                        exit.get("reads_after_resize"), exit.get("writes_after_resize"),
                        exit.get("other_after_resize"), exit);
            }
        }
    }

    private static final class Csv implements AutoCloseable {
        private final BufferedWriter writer;

        Csv(Path path, String... header) throws IOException {
            writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
            row((Object[]) header);
        }

        void row(Object... cells) throws IOException {
            for (int i = 0; i < cells.length; i++) {
                if (i > 0) writer.write(',');
                writer.write(csv(String.valueOf(cells[i])));
            }
            writer.newLine();
        }

        @Override public void close() throws IOException { writer.close(); }
    }

    private static final class UciEngine implements AutoCloseable {
        private final Process process;
        private final BufferedWriter input;
        private final BlockingQueue<String> output = new LinkedBlockingQueue<>();
        private volatile IOException readerFailure;

        private UciEngine(Process process) throws IOException {
            this.process = process;
            input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            Thread thread = new Thread(() -> {
                try {
                    String line;
                    while ((line = reader.readLine()) != null) output.put(line.trim());
                } catch (IOException e) {
                    readerFailure = e;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "phase20-stage3-uci-reader");
            thread.setDaemon(true);
            thread.start();
        }

        static UciEngine start(Path javaExe, Path jar) throws IOException {
            List<String> command = new ArrayList<>();
            command.add(javaExe.toString());
            command.addAll(List.of(JVM_FLAGS));
            command.add("-Dvex.smp.diagnostics=true");
            command.add("-jar");
            command.add(jar.toString());
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            return new UciEngine(process);
        }

        void send(String command) throws IOException {
            input.write(command);
            input.newLine();
            input.flush();
        }

        String nextLine(long deadlineNanos) throws Exception {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) throw new IOException("Timed out waiting for UCI output");
            String line = output.poll(remaining, TimeUnit.NANOSECONDS);
            if (line != null) return line;
            if (readerFailure != null) throw readerFailure;
            if (!process.isAlive()) throw new IOException("UCI engine exited with code " + process.exitValue());
            throw new IOException("Timed out waiting for UCI output");
        }

        void await(String expected, List<String> capture, long timeoutSeconds) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            while (true) {
                String line = nextLine(deadline);
                capture.add(line);
                failOnMainException(line);
                if (line.equals(expected)) return;
            }
        }

        @Override public void close() throws Exception {
            if (process.isAlive()) {
                try { send("quit"); } catch (IOException ignored) { }
                if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly();
            }
            input.close();
        }
    }

}
