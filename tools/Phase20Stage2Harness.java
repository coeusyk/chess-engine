import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.search.SearchResult;
import coeusyk.game.chess.core.search.Searcher;
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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Native-Windows Phase 20 Stage 2 independent-searcher control. */
public final class Phase20Stage2Harness {
    private static final int DEPTH = 13;
    private static final int HASH_MB = 16;
    private static final int PAWN_HASH_MB = 1;
    private static final int[] WORKER_COUNTS = {1, 2, 4};
    private static final int MEASURED_PASSES = 7;
    private static final long EXPECTED_NODES = 24_780_049L;
    private static final String[] FENS = loadFens();

    private Phase20Stage2Harness() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--validate-only")) {
            for (int i = 0; i < FENS.length; i++) {
                if (!Board.isLegalFen(FENS[i])) throw new IllegalStateException("Illegal BenchRunner FEN at index " + i);
            }
            System.out.printf(Locale.ROOT, "corpus=%d depth=%d expected_nodes=%d hash_mb=%d pawn_hash_mb=%d%n",
                    FENS.length, DEPTH, EXPECTED_NODES, HASH_MB, PAWN_HASH_MB);
            return;
        }
        if (args.length > 0 && args[0].equals("--worker")) {
            processWorker(Integer.parseInt(args[1]));
            return;
        }
        if (args.length != 5 || !args[0].equals("--run")) {
            throw new IllegalArgumentException("usage: --run output-dir jar classes-dir java-exe | --worker id");
        }
        run(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), Path.of(args[4]));
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

    private static void run(Path output, Path jar, Path classes, Path javaExe) throws Exception {
        Files.createDirectories(output);
        List<Sample> samples = new ArrayList<>();
        List<Aggregate> aggregates = new ArrayList<>();
        List<ProcessWorker> processWorkers = new ArrayList<>();
        ExecutorService sameJvm = Executors.newFixedThreadPool(4);
        String classpath = jar.toAbsolutePath() + System.getProperty("path.separator") + classes.toAbsolutePath();
        try {
            for (int n : WORKER_COUNTS) {
                runConfig("process", n, 0, true, output, javaExe, classpath, processWorkers, sameJvm, samples, aggregates);
                runConfig("same-jvm", n, 0, true, output, javaExe, classpath, processWorkers, sameJvm, samples, aggregates);
            }

            for (int pass = 1; pass <= MEASURED_PASSES; pass++) {
                int rotation = (pass - 1) % WORKER_COUNTS.length;
                for (int i = 0; i < WORKER_COUNTS.length; i++) {
                    int n = WORKER_COUNTS[(rotation + i) % WORKER_COUNTS.length];
                    runConfig("process", n, pass, false, output, javaExe, classpath, processWorkers, sameJvm, samples, aggregates);
                    runConfig("same-jvm", n, pass, false, output, javaExe, classpath, processWorkers, sameJvm, samples, aggregates);
                }
            }

            writeCsv(output.resolve("workers.csv"), samples);
            writeCsv(output.resolve("aggregates.csv"), aggregates);
            String summary = makeSummary(samples, aggregates);
            Files.writeString(output.resolve("summary.md"), summary, StandardCharsets.UTF_8);
            if (hasClearJvmGap(samples)) {
                runBoundedJfr(output, javaExe, sameJvm);
                summary += "\nA clear same-JVM gap triggered one non-timing, depth-13 N=4 JFR diagnostic; see `same-jvm-gap.jfr`.\n";
            } else {
                summary += "\nNo bounded JFR/GC diagnostic was run because no same-JVM gap separated beyond observed ranges.\n";
            }
            Files.writeString(output.resolve("summary.md"), summary, StandardCharsets.UTF_8);
            System.out.print(summary);
        } finally {
            sameJvm.shutdownNow();
            sameJvm.awaitTermination(30, TimeUnit.SECONDS);
            for (ProcessWorker worker : processWorkers) worker.close();
        }
    }

    private static void runBoundedJfr(Path output, Path javaExe, ExecutorService sameJvm) throws Exception {
        String executable = javaExe.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".exe") ? "jcmd.exe" : "jcmd";
        Path jcmd = javaExe.getParent().resolve(executable);
        if (!Files.isRegularFile(jcmd)) throw new IOException("Same-JVM gap found, but jcmd is unavailable at " + jcmd);
        Path recording = output.resolve("same-jvm-gap.jfr").toAbsolutePath();
        Path log = output.resolve("jfr-diagnostic.log");
        runJcmd(jcmd, log, Long.toString(ProcessHandle.current().pid()), "JFR.start",
                "name=Phase20Stage2GapDiagnostic", "settings=profile", "filename=\"" + recording + "\"");
        try {
            runSameJvm(4, 0, sameJvm);
        } finally {
            runJcmd(jcmd, log, Long.toString(ProcessHandle.current().pid()), "JFR.stop",
                    "name=Phase20Stage2GapDiagnostic");
        }
    }

    private static void runJcmd(Path jcmd, Path log, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(jcmd.toString());
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Timed out running jcmd " + String.join(" ", args));
        }
        if (process.exitValue() != 0) throw new IOException("jcmd failed; see " + log);
    }

    private static void runConfig(String arm, int n, int pass, boolean warmup, Path output,
                                  Path javaExe, String classpath, List<ProcessWorker> processes,
                                  ExecutorService sameJvm, List<Sample> samples,
                                  List<Aggregate> aggregates) throws Exception {
        List<Sample> group = arm.equals("process")
                ? runProcesses(n, pass, javaExe, classpath, output, processes)
                : runSameJvm(n, pass, sameJvm);
        long skew = group.stream().mapToLong(Sample::startDelayNs).max().orElse(0)
                - group.stream().mapToLong(Sample::startDelayNs).min().orElse(0);
        long end = group.stream().mapToLong(s -> s.startDelayNs() + s.elapsedNs()).max().orElse(0);
        long begin = group.stream().mapToLong(Sample::startDelayNs).min().orElse(0);
        long nodes = group.stream().mapToLong(Sample::nodes).sum();
        long elapsed = end - begin;
        if (elapsed <= 0) throw new IllegalStateException("Non-positive aggregate elapsed time");
        Aggregate aggregate = new Aggregate(arm, n, pass, group.size(), elapsed, nodes, skew);
        if (warmup) {
            System.out.printf(Locale.ROOT, "Discarded warm-up: %s N=%d, nodes=%d%n", arm, n, nodes);
            return;
        }
        if (group.size() != n) throw new IllegalStateException("Expected " + n + " workers, got " + group.size());
        samples.addAll(group);
        aggregates.add(aggregate);
        System.out.printf(Locale.ROOT, "Measured %s N=%d pass=%d aggregate=%.0f NPS skew=%.3f ms%n",
                arm, n, pass, aggregate.nps(), skew / 1_000_000.0);
    }

    private static List<Sample> runProcesses(int n, int pass, Path javaExe, String classpath,
                                             Path output, List<ProcessWorker> workers) throws Exception {
        while (workers.size() < n) {
            int id = workers.size() + 1;
            List<String> command = new ArrayList<>(List.of(
                    javaExe.toString(), "-Xms512m", "-Xmx512m", "-XX:+UseG1GC",
                    "--add-modules", "jdk.incubator.vector", "-cp", classpath,
                    "Phase20Stage2Harness", "--worker", Integer.toString(id)));
            Process process = new ProcessBuilder(command)
                    .redirectError(output.resolve("process-worker-" + id + ".stderr.log").toFile())
                    .start();
            ProcessWorker worker = new ProcessWorker(id, process);
            String ready = worker.stdout().readLine();
            if (!"READY".equals(ready)) throw new IOException("Process worker " + id + " failed to start: " + ready);
            workers.add(worker);
        }

        long targetMillis = System.currentTimeMillis() + 500;
        for (int i = 0; i < n; i++) workers.get(i).send("RUN\t" + pass + "\t" + targetMillis);
        List<Sample> results = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String line = workers.get(i).stdout().readLine();
            if (line == null || line.startsWith("ERROR")) throw new IOException("Process worker failed: " + line);
            String[] fields = line.split("\\t");
            if (fields.length != 8 || !fields[0].equals("RESULT")) throw new IOException("Bad worker response: " + line);
            int reportedPass = Integer.parseInt(fields[1]);
            int id = Integer.parseInt(fields[2]);
            long elapsed = Long.parseLong(fields[3]);
            long delay = Long.parseLong(fields[4]);
            long nodes = Long.parseLong(fields[5]);
            long depthReached = Long.parseLong(fields[6]);
            boolean aborted = Boolean.parseBoolean(fields[7]);
            validate(nodes, depthReached, aborted, "process worker " + id);
            if (reportedPass != pass) throw new IOException("Worker pass mismatch: " + line);
            results.add(new Sample("process", n, pass, id, elapsed, delay, nodes));
        }
        return results;
    }

    private static List<Sample> runSameJvm(int n, int pass, ExecutorService pool) throws Exception {
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Sample>> futures = new ArrayList<>(n);
        for (int id = 1; id <= n; id++) {
            int worker = id;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                long started = System.nanoTime();
                long delay = started - releaseNanos;
                long nodes = searchCorpus();
                long elapsed = System.nanoTime() - started;
                validate(nodes, DEPTH, false, "same-JVM worker " + worker);
                return new Sample("same-jvm", n, pass, worker, elapsed, delay, nodes);
            }));
        }
        ready.await();
        releaseNanos = System.nanoTime();
        start.countDown();
        List<Sample> results = new ArrayList<>(n);
        for (Future<Sample> future : futures) results.add(future.get());
        return results;
    }

    private static volatile long releaseNanos;

    private static void processWorker(int id) throws Exception {
        try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
             BufferedWriter output = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8))) {
            output.write("READY\n");
            output.flush();
            String command;
            while ((command = input.readLine()) != null) {
                if (command.equals("EXIT")) return;
                String[] fields = command.split("\\t");
                if (fields.length != 3 || !fields[0].equals("RUN")) throw new IOException("Bad controller command: " + command);
                int pass = Integer.parseInt(fields[1]);
                long targetMillis = Long.parseLong(fields[2]);
                while (System.currentTimeMillis() < targetMillis) {
                    long remaining = targetMillis - System.currentTimeMillis();
                    if (remaining > 1) Thread.sleep(remaining - 1);
                    else Thread.onSpinWait();
                }
                long delayNs = Math.max(0L, System.currentTimeMillis() - targetMillis) * 1_000_000L;
                long started = System.nanoTime();
                long nodes = searchCorpus();
                long elapsed = System.nanoTime() - started;
                boolean aborted = false;
                long depthReached = DEPTH;
                validate(nodes, depthReached, aborted, "process worker " + id);
                output.write(String.format(Locale.ROOT, "RESULT\t%d\t%d\t%d\t%d\t%d\t%d\t%s%n",
                        pass, id, elapsed, delayNs, nodes, depthReached, aborted));
                output.flush();
            }
        } catch (Exception e) {
            System.err.println("Phase 20 Stage 2 process worker " + id + " failed");
            e.printStackTrace(System.err);
            System.out.println("ERROR\t" + e.getClass().getName() + "\t" + String.valueOf(e.getMessage()).replace('\t', ' '));
            throw e;
        }
    }

    private static long searchCorpus() {
        long nodes = 0;
        for (String fen : FENS) {
            Searcher searcher = new Searcher();
            searcher.setTranspositionTableSizeMb(HASH_MB);
            // Searcher defaults are Classical, PawnHash=1 MB, no Syzygy, and private state.
            searcher.setContempt(0);
            searcher.setMultiPV(1);
            searcher.setInstrumentationEnabled(false);
            Board board = new Board(fen);
            board.setSearchMode(true);
            SearchResult result = searcher.searchDepth(board, DEPTH);
            if (result.depthReached() != DEPTH || result.aborted() || result.bestMove() == null) {
                throw new IllegalStateException("Incomplete depth-" + DEPTH + " search: " + result);
            }
            nodes += result.nodesVisited();
        }
        return nodes;
    }

    private static void validate(long nodes, long depthReached, boolean aborted, String worker) {
        if (nodes != EXPECTED_NODES || depthReached != DEPTH || aborted) {
            throw new IllegalStateException(worker + " workload mismatch: nodes=" + nodes
                    + " depth=" + depthReached + " aborted=" + aborted
                    + " expectedNodes=" + EXPECTED_NODES);
        }
    }

    private static void writeCsv(Path path, List<?> rows) throws IOException {
        List<String> lines = new ArrayList<>();
        if (!rows.isEmpty() && rows.get(0) instanceof Sample) {
            lines.add("arm,n,pass,worker,elapsed_ns,start_delay_ns,nodes,nps");
            for (Sample s : (List<Sample>) rows) lines.add(String.format(Locale.ROOT, "%s,%d,%d,%d,%d,%d,%d,%.3f",
                    s.arm(), s.n(), s.pass(), s.worker(), s.elapsedNs(), s.startDelayNs(), s.nodes(), s.nps()));
        } else {
            lines.add("arm,n,pass,workers,aggregate_elapsed_ns,start_skew_ns,nodes,nps");
            for (Aggregate a : (List<Aggregate>) rows) lines.add(String.format(Locale.ROOT, "%s,%d,%d,%d,%d,%d,%d,%.3f",
                    a.arm(), a.n(), a.pass(), a.workers(), a.elapsedNs(), a.startSkewNs(), a.nodes(), a.nps()));
        }
        Files.write(path, lines, StandardCharsets.UTF_8);
    }

    private static String makeSummary(List<Sample> samples, List<Aggregate> aggregates) {
        StringBuilder out = new StringBuilder("# Phase 20 Stage 2 results\n\n")
                .append("31 canonical BenchRunner FENs, depth 13, fresh Searcher per FEN, private 16 MB TT, ")
                .append("PawnHashSize=1, Classical, instrumentation off. Each configuration had one discarded warm-up and seven measured passes.\n\n")
                .append("## Per-worker results\n\n")
                .append("| Arm | N | Median NPS/worker | Worker sample range | Pass-median range | r(N) |\n")
                .append("|---|---:|---:|---:|---:|---:|\n");
        for (String arm : List.of("process", "same-jvm")) {
            double base = workerNps(samples, arm, 1).stream().mapToDouble(Double::doubleValue).toArray().length == 0
                    ? 0 : median(workerNps(samples, arm, 1));
            for (int n : WORKER_COUNTS) {
                List<Double> values = workerNps(samples, arm, n);
                if (values.isEmpty()) continue;
                List<Double> passMedians = passMedians(samples, arm, n);
                out.append(String.format(Locale.ROOT, "| %s | %d | %.0f | %.0f–%.0f | %.0f–%.0f | %.4f |%n",
                        arm, n, median(values), min(values), max(values), min(passMedians), max(passMedians),
                        base == 0 ? 0 : median(values) / base));
            }
        }
        out.append("\n`r(N)` is median per-worker NPS divided by the same arm's 1T median per-worker NPS. Ranges expose observed run-to-run spread; no fixed scaling cutoff is applied.\n\n")
                .append("## Aggregate results\n\n| Arm | N | Median aggregate NPS | Pass range | Start-skew range (ms) |\n|---|---:|---:|---:|---:|\n");
        for (String arm : List.of("process", "same-jvm")) for (int n : WORKER_COUNTS) {
            List<Aggregate> rows = aggregates.stream().filter(a -> a.arm().equals(arm) && a.n() == n).toList();
            if (rows.isEmpty()) continue;
            List<Double> nps = rows.stream().map(Aggregate::nps).toList();
            List<Double> skew = rows.stream().map(a -> a.startSkewNs() / 1_000_000.0).toList();
            out.append(String.format(Locale.ROOT, "| %s | %d | %.0f | %.0f–%.0f | %.3f–%.3f |%n",
                    arm, n, median(nps), min(nps), max(nps), min(skew), max(skew)));
        }
        out.append("\n## Comparison against observed spread\n\n");
        for (int n : new int[]{2, 4}) {
            Range process = retentionRange(samples, "process", n);
            Range sameJvm = retentionRange(samples, "same-jvm", n);
            double processR = median(workerNps(samples, "process", n)) / median(workerNps(samples, "process", 1));
            double sameR = median(workerNps(samples, "same-jvm", n)) / median(workerNps(samples, "same-jvm", 1));
            out.append(String.format(Locale.ROOT,
                    "- N=%d: separate-process r=%.4f (observed range %.4f–%.4f); same-JVM r=%.4f (observed range %.4f–%.4f); ",
                    n, processR, process.low(), process.high(), sameR, sameJvm.low(), sameJvm.high()));
            out.append(sameJvm.high() < process.low()
                    ? "same-JVM range is wholly below the separate-process range; collect bounded JFR/GC evidence.\n"
                    : "observed ranges overlap; no additional JVM-level gap is separated by this run spread.\n");
            double processBaseMin = min(workerNps(samples, "process", 1));
            out.append(String.format(Locale.ROOT,
                    "  Separate-process median per-worker NPS is %s the process 1T observed range.\n",
                    median(workerNps(samples, "process", n)) < processBaseMin ? "below" : "within or above"));
        }
        return out.toString();
    }

    private static List<Double> workerNps(List<Sample> rows, String arm, int n) {
        return rows.stream().filter(s -> s.arm().equals(arm) && s.n() == n).map(Sample::nps).toList();
    }

    private static List<Double> passMedians(List<Sample> rows, String arm, int n) {
        List<Double> values = new ArrayList<>();
        for (int pass = 1; pass <= MEASURED_PASSES; pass++) {
            int p = pass;
            List<Double> group = rows.stream().filter(s -> s.arm().equals(arm) && s.n() == n && s.pass() == p)
                    .map(Sample::nps).toList();
            if (!group.isEmpty()) values.add(median(group));
        }
        return values;
    }

    private static Range retentionRange(List<Sample> rows, String arm, int n) {
        double nMin = min(workerNps(rows, arm, n));
        double nMax = max(workerNps(rows, arm, n));
        double baseMin = min(workerNps(rows, arm, 1));
        double baseMax = max(workerNps(rows, arm, 1));
        return new Range(nMin / baseMax, nMax / baseMin);
    }

    private static boolean hasClearJvmGap(List<Sample> rows) {
        for (int n : new int[]{2, 4}) {
            if (retentionRange(rows, "same-jvm", n).high() < retentionRange(rows, "process", n).low()) return true;
        }
        return false;
    }

    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        int mid = sorted.size() / 2;
        return sorted.size() % 2 == 0 ? (sorted.get(mid - 1) + sorted.get(mid)) / 2.0 : sorted.get(mid);
    }

    private static double min(List<Double> values) { return values.stream().mapToDouble(Double::doubleValue).min().orElseThrow(); }
    private static double max(List<Double> values) { return values.stream().mapToDouble(Double::doubleValue).max().orElseThrow(); }

    private record Sample(String arm, int n, int pass, int worker, long elapsedNs,
                          long startDelayNs, long nodes) {
        double nps() { return nodes * 1_000_000_000.0 / elapsedNs; }
    }

    private record Aggregate(String arm, int n, int pass, int workers, long elapsedNs,
                             long nodes, long startSkewNs) {
        double nps() { return nodes * 1_000_000_000.0 / elapsedNs; }
    }

    private record Range(double low, double high) {}

    private static final class ProcessWorker {
        private final Process process;
        private final BufferedReader stdout;
        private final BufferedWriter stdin;

        ProcessWorker(int id, Process process) {
            this.process = process;
            this.stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        }
        void send(String line) throws IOException { stdin.write(line); stdin.newLine(); stdin.flush(); }
        BufferedReader stdout() { return stdout; }
        void close() throws Exception {
            try { send("EXIT"); } catch (IOException ignored) {}
            stdin.close();
            if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly();
            stdout.close();
        }
    }
}
