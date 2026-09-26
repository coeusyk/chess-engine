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
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Stage 6 fixed-time UCI driver. All timed work uses the production UCI path. */
public final class Phase20Stage6Harness {
    private static final int[] THREADS = {1, 2, 4};
    private static final String MOVETIME = "movetime";
    private static final String CLOCK = "clock";
    private static final int MOVETIME_MS = 1950;
    private static final int MOVE_OVERHEAD_MS = 30;
    private static final int CLOCK_MS = 60_000;
    private static final int INCREMENT_MS = 600;
    private static final int MOVETIME_SOFT_HARD_MS = 1920;
    private static final int CLOCK_SOFT_MS = 1920;
    private static final int CLOCK_HARD_MS = 3810;
    private static final int MEASURED_PASSES = 7;
    private static final int MEASURED_COUNT = 1302;
    private static final int WARMUP_COUNT = 186;
    private static final int MAX_DEPTH = 64;
    private static final long SEARCH_TIMEOUT_NANOS = TimeUnit.MINUTES.toNanos(10);
    private static final long DRAIN_AFTER_STOP_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final long TOTAL_BUDGET_NANOS = TimeUnit.HOURS.toNanos(6);
    private static final String[] FENS = loadFens();

    private Phase20Stage6Harness() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--validate-only")) {
            validateOnly();
            return;
        }
        if (args.length != 5 || !args[0].equals("--run")) {
            throw new IllegalArgumentException("usage: --run output-dir jar java-exe identity.json | --validate-only");
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

    private static void validateOnly() throws Exception {
        require(FENS.length == 31, "Expected canonical 31-position corpus");
        for (int i = 0; i < FENS.length; i++) require(Board.isLegalFen(FENS[i]), "Illegal FEN at " + i);
        validateSchedule();
        validateCommandsAndOptions();
        validateLifecycleAccounting();
        validateTargetsAndReferences();
        validateAgreementAndDecisionRules();
        validateArtifactGateAndFailureCapture();
        System.out.println("Stage 6 self-checks passed: corpus=31 warmup=186 measured=1302 arms=1,2,4 modes=movetime,clock");
    }

    private static void validateSchedule() {
        List<Step> warmup = schedule(0, true);
        require(warmup.size() == WARMUP_COUNT, "Warm-up schedule cardinality changed");
        require(warmup.stream().filter(s -> s.warmup).count() == WARMUP_COUNT, "Warm-up marker count changed");
        require(schedule(1, false).size() == MEASURED_COUNT / MEASURED_PASSES, "One measured pass cardinality changed");
        int measured = 0;
        for (int pass = 1; pass <= MEASURED_PASSES; pass++) measured += schedule(pass, false).size();
        require(measured == MEASURED_COUNT, "Measured schedule cardinality changed");
        List<Step> p1 = schedule(1, false).stream().filter(s -> s.position == 0).toList();
        require(p1.size() == 6, "Position did not receive six mode/arm observations");
        for (int i = 0; i < 3; i++) {
            require(p1.get(i).mode.equals(MOVETIME) && p1.get(i).threads == THREADS[i], "Pass 1 movetime arm order changed");
            require(p1.get(i + 3).mode.equals(CLOCK) && p1.get(i + 3).threads == THREADS[i], "Pass 1 clock arm order changed");
        }
        List<Step> p2 = schedule(2, false).stream().filter(s -> s.position == 0).toList();
        for (int i = 0; i < 3; i++) {
            require(p2.get(i).mode.equals(CLOCK) && p2.get(i).threads == THREADS[(i + 1) % 3], "Pass 2 clock arm order changed");
            require(p2.get(i + 3).mode.equals(MOVETIME) && p2.get(i + 3).threads == THREADS[(i + 1) % 3], "Pass 2 movetime arm order changed");
        }
        List<Step> warmupPosition = warmup.stream().filter(s -> s.position == 0).toList();
        for (int i = 0; i < 3; i++) {
            require(warmupPosition.get(i).mode.equals(CLOCK) && warmupPosition.get(i).threads == THREADS[i],
                    "Warm-up first-mode/thread order changed");
            require(warmupPosition.get(i + 3).mode.equals(MOVETIME) && warmupPosition.get(i + 3).threads == THREADS[i],
                    "Warm-up second-mode/thread order changed");
        }
        System.out.println("schedule_cardinality_and_order=pass");
    }

    private static List<Step> schedule(int pass, boolean warmup) {
        List<Step> result = new ArrayList<>(FENS.length * THREADS.length * 2);
        for (int position = 0; position < FENS.length; position++) {
            boolean moveFirst = ((pass + position + 1) & 1) == 0;
            String firstMode = moveFirst ? MOVETIME : CLOCK;
            String secondMode = moveFirst ? CLOCK : MOVETIME;
            int rotation = warmup ? 0 : (pass - 1) % THREADS.length;
            for (String mode : List.of(firstMode, secondMode)) {
                for (int offset = 0; offset < THREADS.length; offset++) {
                    result.add(new Step(pass, position, THREADS[(rotation + offset) % THREADS.length], mode, warmup));
                }
            }
        }
        return result;
    }

    private static void validateCommandsAndOptions() {
        require(goCommand(MOVETIME).equals("go movetime 1950"), "Movetime command changed");
        require(goCommand(CLOCK).equals("go wtime 60000 btime 60000 winc 600 binc 600"), "Clock command changed");
        List<String> options = options(4);
        require(options.equals(List.of("setoption name EvalType value Classical", "setoption name OwnBook value false",
                "setoption name SyzygyOnline value false", "setoption name MultiPV value 1",
                "setoption name Contempt value 0", "setoption name Hash value 16",
                "setoption name PawnHashSize value 1", "setoption name MoveOverhead value 30",
                "setoption name Threads value 4")), "Frozen options changed: " + options);
        for (int threads : THREADS) {
            require(options(threads).get(options(threads).size() - 1)
                    .equals("setoption name Threads value " + threads), "Threads option differs by arm");
        }
        require(MOVETIME_SOFT_HARD_MS == 1920 && CLOCK_SOFT_MS == 1920 && CLOCK_HARD_MS == 3810,
                "Frozen allocator arithmetic changed");
        System.out.println("commands_and_options=pass");
    }

    private static String goCommand(String mode) {
        return switch (mode) {
            case MOVETIME -> "go movetime " + MOVETIME_MS;
            case CLOCK -> "go wtime " + CLOCK_MS + " btime " + CLOCK_MS + " winc " + INCREMENT_MS + " binc " + INCREMENT_MS;
            default -> throw new IllegalArgumentException("Unknown mode " + mode);
        };
    }

    private static List<String> options(int threads) {
        return List.of("setoption name EvalType value Classical", "setoption name OwnBook value false",
                "setoption name SyzygyOnline value false", "setoption name MultiPV value 1",
                "setoption name Contempt value 0", "setoption name Hash value 16",
                "setoption name PawnHashSize value 1", "setoption name MoveOverhead value " + MOVE_OVERHEAD_MS,
                "setoption name Threads value " + threads);
    }

    private static void validateLifecycleAccounting() throws Exception {
        List<String> pre = new ArrayList<>(List.of("SMPDIAG search=17 event=exit helper=0"));
        List<Map<String, String>> exits = Phase20UciEvents.collectHelperExits(pre, 17, 2,
                new QueueReader(List.of("SMPDIAG search=17 event=exit helper=1")));
        require(exits.size() == 2 && Phase20UciEvents.countEvents(pre, 17, "exit") == 2,
                "Exits before and after bestmove were not both collected");
        boolean duplicateRejected = false;
        try {
            Phase20UciEvents.collectHelperExits(new ArrayList<>(List.of(
                    "SMPDIAG search=17 event=exit helper=0", "SMPDIAG search=17 event=exit helper=0")),
                    17, 1, new QueueReader(List.of()));
        } catch (IllegalStateException expected) { duplicateRejected = true; }
        require(duplicateRejected, "Duplicate helper exit was accepted");
        System.out.println("shared_lifecycle_accounting=pass");
    }

    private static void validateTargetsAndReferences() {
        List<DepthObservation> synthetic = List.of(new DepthObservation(0, 12), new DepthObservation(0, 14),
                new DepthObservation(1, 16), new DepthObservation(1, 11));
        List<Target> targets = deriveTargets(2, synthetic);
        require(targets.get(0).dmax == 14 && targets.get(0).dref == 16, "Dmax/Dref derivation failed");
        require(targets.get(1).dmax == 16 && targets.get(1).dref == 18, "Dmax/Dref derivation failed");
        Reference resolved = referenceResult(14, 16, Map.of(15, "e2e4", 16, "e2e4"), 16, false);
        require(resolved.resolved && resolved.move.equals("e2e4"), "Adjacent-depth agreement failed");
        Reference disagree = referenceResult(14, 16, Map.of(15, "e2e4", 16, "d2d4"), 16, false);
        require(!disagree.resolved && disagree.reason.equals("adjacent_moves_disagree"), "Unstable reference not unresolved");
        Reference early = referenceResult(14, 16, Map.of(15, "e2e4"), 15, false);
        require(!early.resolved && early.reason.equals("reference_stopped_before_target"), "Early reference stop not unresolved");
        Reference overMax = unresolved(14, 66, "target_exceeds_max_depth");
        require(!overMax.resolved && overMax.depth == 0, "Over-maximum target not unresolved");
        Reference watchdog = referenceResult(14, 16, Map.of(), 0, true);
        require(!watchdog.resolved && watchdog.reason.equals("watchdog_timeout"), "Watchdog result not unresolved");
        System.out.println("target_manifest_and_reference_rules=pass");
    }

    private static List<Target> deriveTargets(int positions, List<DepthObservation> observations) {
        int[] max = new int[positions];
        for (DepthObservation item : observations) {
            if (item.position < 0 || item.position >= positions) throw new IllegalArgumentException("Bad position");
            max[item.position] = Math.max(max[item.position], item.depth);
        }
        List<Target> targets = new ArrayList<>();
        for (int position = 0; position < positions; position++) targets.add(new Target(position, max[position], max[position] + 2));
        return targets;
    }

    private static Reference referenceResult(int dmax, int dref, Map<Integer, String> moves,
                                             int completedDepth, boolean timedOut) {
        if (dref > MAX_DEPTH) return unresolved(dmax, dref, "target_exceeds_max_depth");
        if (timedOut) return unresolved(dmax, dref, "watchdog_timeout");
        if (completedDepth < dref) return unresolved(dmax, dref, "reference_stopped_before_target");
        String before = moves.get(dmax + 1);
        String target = moves.get(dmax + 2);
        if (before == null || target == null) return unresolved(dmax, dref, "confirmation_depth_missing");
        if (!before.equals(target)) return unresolved(dmax, dref, "adjacent_moves_disagree");
        return new Reference(dmax, dref, completedDepth, target, true, "resolved");
    }

    private static Reference unresolved(int dmax, int dref, String reason) {
        return new Reference(dmax, dref, 0, "", false, reason);
    }

    private static void validateAgreementAndDecisionRules() {
        Bounds wins = pairedAgreement("e2e4", "d2d4", "e2e4", true);
        require(wins.low == 1 && wins.high == 1, "Resolved paired agreement interval failed");
        Bounds unknown = pairedAgreement("e2e4", "d2d4", "", false);
        require(unknown.low == -1 && unknown.high == 1, "Unresolved paired agreement interval failed");
        Bounds sameUnknown = pairedAgreement("e2e4", "e2e4", "", false);
        require(sameUnknown.low == 0 && sameUnknown.high == 0, "Identical moves should have known zero contrast");
        require(noiseRange(List.of(2.0, 1.0, 3.0)) == 2.0, "Pass-summary range failed");
        require(median(List.of(3.0, 1.0, 2.0)) == 2.0, "Pass median failed");
        List<Double> quantiles = List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0);
        require(percentile(quantiles, 0.10) == 1.0 && percentile(quantiles, 0.90) == 9.0,
                "Nearest-rank distribution summaries changed");
        require(maxAgreementRange(List.of(new PassSummary(0, 0, 0.0, 0.2, 0, 0),
                        new PassSummary(0, 0, 0.1, 0.3, 0, 0))) == 0.3,
                "Unresolved-reference agreement noise range failed");
        Map<Integer, Reference> unresolved = new HashMap<>();
        for (int position = 0; position < FENS.length; position++) {
            unresolved.put(position, new Reference(0, 2, 0, "", false, "watchdog_timeout"));
        }
        List<TimedObservation> sameMoves = new ArrayList<>();
        for (int pass = 1; pass <= MEASURED_PASSES; pass++) for (int position = 0; position < FENS.length; position++) {
            sameMoves.add(syntheticTimed(pass, position, 1, "e2e4"));
            sameMoves.add(syntheticTimed(pass, position, 2, "e2e4"));
        }
        PassSummary sameUnknownPass = passSummaries(sameMoves, unresolved, 2, MOVETIME, 1).get(0);
        require(sameUnknownPass.agreementContrastLow == 0 && sameUnknownPass.agreementContrastHigh == 0,
                "Paired same-move unresolved references should have zero contrast");
        List<TimedObservation> differentMoves = sameMoves.stream().map(s -> s.threads == 2
                ? syntheticTimed(s.pass, s.position, 2, "d2d4") : s).toList();
        PassSummary differentUnknownPass = passSummaries(differentMoves, unresolved, 2, MOVETIME, 1).get(0);
        require(differentUnknownPass.agreementContrastLow == -1.0
                        && differentUnknownPass.agreementContrastHigh == 1.0,
                "Paired different-move unresolved agreement interval failed: "
                        + differentUnknownPass.agreementContrastLow + "," + differentUnknownPass.agreementContrastHigh);
        ModeResult pass = decide(new Metric(1.0, 0.5), new Bounds(0.1, 0.2), 0.4,
                new Metric(0.0, 0.1), 0.3);
        require(pass.qualifies, "Positive depth benefit with non-regressing agreement and time should pass");
        ModeResult edge = decide(new Metric(0.5, 0.5), new Bounds(0.4, 0.4), 0.4,
                new Metric(0.3, 0.3), 0.3);
        require(!edge.qualifies, "Benefit equal to the spread should not pass");
        ModeResult fail = decide(new Metric(0.0, 0.5), new Bounds(-0.2, 0.2), 0.4,
                new Metric(0.6, 0.1), 0.3);
        require(!fail.qualifies && fail.classification.contains("time_inflation"), "Time inflation rule failed");
        System.out.println("agreement_intervals_noise_and_decisions=pass");
    }

    private static TimedObservation syntheticTimed(int pass, int position, int threads, String move) {
        return new TimedObservation(pass, position, threads, MOVETIME, false, FENS[position],
                (long) pass * 1000 + position * 10 + threads, goCommand(MOVETIME), 10, 100, 100,
                move, move, "main-result", "true", "0", move, 1000, 1000, 1000,
                List.of(), List.of(), Map.of(), Map.of(), List.of());
    }

    private static Bounds pairedAgreement(String main, String singleThread, String reference, boolean resolved) {
        if (resolved) return new Bounds((main.equals(reference) ? 1 : 0) - (singleThread.equals(reference) ? 1 : 0),
                (main.equals(reference) ? 1 : 0) - (singleThread.equals(reference) ? 1 : 0));
        if (main.equals(singleThread)) return new Bounds(0, 0);
        return new Bounds(-1, 1);
    }

    private static double noiseRange(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).max().orElseThrow()
                - values.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
    }

    private static double median(List<Double> values) {
        double[] sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        int mid = sorted.length / 2;
        return (sorted.length & 1) == 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2.0;
    }

    private static ModeResult decide(Metric depth, Bounds agreement, double agreementRange,
                                     Metric time, double timeRange) {
        boolean noRegression = depth.contrast >= 0 && agreement.low >= 0;
        boolean benefit = depth.contrast > depth.range || agreement.low > agreementRange;
        boolean noInflation = time.contrast <= timeRange;
        List<String> labels = new ArrayList<>();
        if (depth.contrast < -depth.range) labels.add("depth_regression");
        if (agreement.high < -agreementRange) labels.add("agreement_regression");
        if (time.contrast > timeRange) labels.add("time_inflation");
        if (time.contrast > timeRange && depth.contrast <= depth.range) labels.add("time_management_limited");
        if (!noRegression) labels.add("non_regression_gate_failed");
        if (!benefit) labels.add("no_benefit_outside_noise");
        if (!noInflation) labels.add("inflated_time");
        return new ModeResult(noRegression && benefit && noInflation,
                labels.isEmpty() ? "practically_useful" : String.join(";", labels));
    }

    private static void validateArtifactGateAndFailureCapture() throws Exception {
        Path dir = Files.createTempDirectory("phase20-stage6-selfcheck-");
        Path timed = dir.resolve("timed.csv");
        Path helpers = dir.resolve("helpers.csv");
        Path manifest = dir.resolve("target-manifest.json");
        try {
            Files.writeString(timed, "complete timed data\n");
            Files.writeString(helpers, "complete helper data\n");
            boolean blocked = false;
            try { verifyFrozenArtifacts(dir, timed, helpers); }
            catch (IllegalStateException expected) { blocked = true; }
            require(blocked, "References were allowed before timed completion and frozen manifest");
            freezeSynthetic(dir, timed, helpers);
            verifyFrozenArtifacts(dir, timed, helpers);
            require(Files.exists(manifest), "Frozen target manifest missing");
            Files.writeString(timed, "tampered timed data\n");
            boolean mutationBlocked = false;
            try { verifyFrozenArtifacts(dir, timed, helpers); }
            catch (IllegalStateException expected) { mutationBlocked = true; }
            require(mutationBlocked, "References were allowed after timed artifacts changed");
            Path identity = dir.resolve("identity.json");
            Path transcript = dir.resolve("transcript.log");
            Path failure = dir.resolve("failure.txt");
            Files.writeString(identity, "{\"jar_sha256\":\"test-hash\",\"commit\":\"test-commit\"}\n");
            Files.writeString(transcript, "IN go movetime 1950\nOUT bestmove e2e4\n");
            preserveFailure(identity, transcript, failure, new IOException("synthetic failure"));
            String saved = Files.readString(failure);
            require(saved.contains("test-hash") && saved.contains("synthetic failure")
                            && saved.contains("go movetime 1950") && saved.contains("bestmove e2e4"),
                    "Failure artifact did not preserve identity and complete transcript");
        } finally {
            try (var paths = Files.walk(dir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) { } });
            }
        }
        System.out.println("reference_gate_and_failure_artifact=pass");
    }

    private static void freezeSynthetic(Path dir, Path timed, Path helpers) throws Exception {
        String completion = "timed_sha256=" + sha256(timed) + "\nhelpers_sha256=" + sha256(helpers) + "\nmeasured=1302\nwarmup=186\n";
        Files.writeString(dir.resolve("timed-complete.txt"), completion);
        Path manifest = dir.resolve("target-manifest.json");
        Path temp = dir.resolve("target-manifest.json.tmp");
        Files.writeString(temp, "{\"frozen\":true,\"timed_sha256\":\"" + sha256(timed) + "\"}\n");
        Files.move(temp, manifest, StandardCopyOption.ATOMIC_MOVE);
        Files.writeString(dir.resolve("target-manifest.sha256"), sha256(manifest) + "\n");
    }

    private static void verifyFrozenArtifacts(Path dir, Path timed, Path helpers) throws Exception {
        Path complete = dir.resolve("timed-complete.txt");
        Path manifest = dir.resolve("target-manifest.json");
        Path manifestHash = dir.resolve("target-manifest.sha256");
        require(Files.exists(complete) && Files.exists(manifest) && Files.exists(manifestHash),
                "References require completed timed artifacts and a frozen manifest");
        String completion = Files.readString(complete);
        require(completion.contains("timed_sha256=" + sha256(timed) + "\n")
                        && completion.contains("helpers_sha256=" + sha256(helpers) + "\n")
                        && completion.contains("measured=" + MEASURED_COUNT + "\n")
                        && completion.contains("warmup=" + WARMUP_COUNT + "\n"),
                "Timed artifacts changed after completion");
        String expectedManifestHash = Files.readString(manifestHash).trim();
        require(sha256(manifest).equals(expectedManifestHash), "Target manifest is not frozen or was modified");
        require(Files.readString(manifest).contains("\"frozen\":true"), "Target manifest lacks frozen marker");
    }

    private static void preserveFailure(Path identity, Path transcript, Path failure, Exception cause) throws IOException {
        String id = Files.exists(identity) ? Files.readString(identity) : "identity unavailable\n";
        String raw = Files.exists(transcript) ? Files.readString(transcript) : "transcript unavailable\n";
        Files.writeString(failure, "status=failed\nerror=" + cause + "\nidentity:\n" + id + "transcript:\n" + raw,
                StandardCharsets.UTF_8);
    }

    private static void run(Path output, Path jar, Path javaExe, Path identityFile) throws Exception {
        for (String artifact : List.of("transcript.log", "timed-searches.csv", "timed-complete.txt",
                "target-manifest.json", "reference-started.json")) {
            require(!Files.exists(output.resolve(artifact)), "Refusing to overwrite an existing Stage 6 artifact: " + artifact);
        }
        Files.createDirectories(output);
        Path transcript = output.resolve("transcript.log");
        Path failure = output.resolve("failure.txt");
        Path identity = output.resolve("identity.json");
        if (!identityFile.toAbsolutePath().normalize().equals(identity.toAbsolutePath().normalize())) {
            Files.copy(identityFile, identity, StandardCopyOption.REPLACE_EXISTING);
        }
        Recorder recorder = new Recorder(transcript);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (!recorder.finished) {
                try { preserveFailure(identity, transcript, failure, new IOException("process interrupted")); }
                catch (IOException ignored) { }
            }
        }, "phase20-stage6-failure-capture"));
        long runStart = System.nanoTime();
        List<TimedObservation> observations = new ArrayList<>(MEASURED_COUNT);
        List<TimedObservation> warmups = new ArrayList<>(WARMUP_COUNT);
        List<UciSession> sessions = new ArrayList<>();
        Exception pendingFailure = null;
        try {
            Map<Integer, UciSession> byThreads = new LinkedHashMap<>();
            for (int threads : THREADS) {
                checkBudget(runStart);
                UciSession session = UciSession.start(javaExe, jar, recorder);
                sessions.add(session);
                byThreads.put(threads, session);
                session.send("uci");
                session.await("uciok", 30);
            }
            try (Csv searches = new Csv(output.resolve("timed-searches.csv"), SEARCH_HEADER);
                 Csv helpers = new Csv(output.resolve("helpers.csv"), HELPER_HEADER);
                 Csv iterations = new Csv(output.resolve("iterations.csv"), ITERATION_HEADER);
                 Csv events = new Csv(output.resolve("events.csv"), EVENT_HEADER)) {
                for (Step step : schedule(0, true)) {
                    checkBudget(runStart);
                    TimedObservation sample = timed(byThreads.get(step.threads), step, runStart);
                    writeObservation(sample, searches, helpers, iterations, events);
                    warmups.add(sample);
                }
                for (int pass = 1; pass <= MEASURED_PASSES; pass++) {
                    for (Step step : schedule(pass, false)) {
                        checkBudget(runStart);
                        TimedObservation sample = timed(byThreads.get(step.threads), step, runStart);
                        writeObservation(sample, searches, helpers, iterations, events);
                        observations.add(sample);
                    }
                }
            }
            require(warmups.size() == WARMUP_COUNT && observations.size() == MEASURED_COUNT,
                    "Timed corpus count mismatch; warmup=" + warmups.size() + " measured=" + observations.size());
            Path timedFile = output.resolve("timed-searches.csv");
            Path helperFile = output.resolve("helpers.csv");
            Path iterationFile = output.resolve("iterations.csv");
            Path eventFile = output.resolve("events.csv");
            writeTimedCompletion(output, timedFile, helperFile, iterationFile, eventFile, recorder.path);
            List<DepthObservation> depths = new ArrayList<>();
            for (TimedObservation sample : observations) {
                depths.add(new DepthObservation(sample.position, sample.depth));
                for (Map<String, String> helper : sample.exits) {
                    depths.add(new DepthObservation(sample.position, integer(helper, "completed_depth")));
                }
            }
            List<Target> targets = deriveTargets(FENS.length, depths);
            Path manifest = freezeManifest(output, targets, timedFile, helperFile, iterationFile, eventFile, recorder.path);
            verifyFrozenArtifacts(output, timedFile, helperFile, iterationFile, eventFile, recorder.path);
            Files.writeString(output.resolve("reference-started.json"), "{\"started_utc\":\"" + Instant.now() + "\",\"manifest_sha256\":\"" + sha256(manifest) + "\"}\n");

            List<Reference> references = new ArrayList<>(FENS.length);
            Csv refs = new Csv(output.resolve("references.csv"), REFERENCE_HEADER);
            try {
                UciSession oneThread = byThreads.get(1);
                for (Target target : targets) {
                    checkBudget(runStart);
                    Reference reference;
                    if (target.dref > MAX_DEPTH) {
                        reference = unresolved(target.dmax, target.dref, "target_exceeds_max_depth");
                    } else {
                        try {
                            SearchResult result = search(oneThread, target.position, FENS[target.position], 1,
                                    "reference", target.dref, SEARCH_TIMEOUT_NANOS, runStart);
                            Map<Integer, String> moves = new HashMap<>();
                            result.iterations.forEach(it -> moves.put(it.depth, it.move));
                            reference = referenceResult(target.dmax, target.dref, moves, result.depth, false);
                            reference = new Reference(reference.dmax, reference.dref, result.depth,
                                    reference.move, reference.resolved, reference.reason, result.mainNodes,
                                    result.engineNs, result.iterations, result.bestmoveDiagnostic);
                        } catch (SearchTimeout timeout) {
                            drainStoppedSearch(oneThread, timeout.lines, runStart);
                            if (timeout.totalBudgetExpired) {
                                throw new IOException("Six-hour total Stage 6 execution guard reached", timeout);
                            }
                            reference = unresolved(target.dmax, target.dref, "watchdog_timeout");
                        }
                    }
                    references.add(reference);
                    refs.row(target.position, FENS[target.position], target.dmax, target.dref,
                            reference.depth, reference.resolved, reference.move, reference.reason,
                            reference.nodes, reference.engineNs, jsonIterations(reference.iterations), jsonMap(reference.diagnostic));
                }
            } finally { refs.close(); }
            require(references.size() == FENS.length, "Reference record count mismatch");
            writeAgreementsAndHelperAgreements(output, observations, references);
            analyze(output, observations, references);
            System.out.println("Stage 6 artifacts: " + output.toAbsolutePath());
        } catch (Exception failureCause) {
            recorder.record("HARNESS", failureCause.toString());
            pendingFailure = failureCause;
        } finally {
            for (UciSession session : sessions) {
                try { session.close(); } catch (Exception closeFailure) { recorder.record("CLOSE_ERROR", closeFailure.toString()); }
            }
        }
        if (pendingFailure != null) {
            recorder.close();
            preserveFailure(identity, transcript, failure, pendingFailure);
            recorder.finished = true;
            throw pendingFailure;
        }
        recorder.finished = true;
        recorder.close();
    }

    private static void checkBudget(long runStart) {
        require(System.nanoTime() - runStart < TOTAL_BUDGET_NANOS, "Six-hour total Stage 6 execution guard reached");
    }

    private static long budgetedTimeout(long requestedNanos, long runStart) {
        long remaining = TOTAL_BUDGET_NANOS - (System.nanoTime() - runStart);
        if (remaining <= DRAIN_AFTER_STOP_NANOS) {
            throw new IllegalStateException("Six-hour total Stage 6 execution guard reached before next search");
        }
        return Math.min(requestedNanos, remaining - DRAIN_AFTER_STOP_NANOS);
    }

    private static TimedObservation timed(UciSession session, Step step, long runStart) throws Exception {
        SearchResult result;
        try {
            result = search(session, step.position, FENS[step.position], step.threads,
                    step.mode, 0, SEARCH_TIMEOUT_NANOS, runStart);
        } catch (SearchTimeout timeout) {
            drainStoppedSearch(session, timeout.lines, runStart);
            throw new IOException(timeout.totalBudgetExpired
                    ? "Six-hour total Stage 6 execution guard reached during timed observations"
                    : "Timed search watchdog expired", timeout);
        }
        return new TimedObservation(step.pass, step.position, step.threads, step.mode, step.warmup,
                FENS[step.position], result.searchId, goCommand(step.mode), result.depth,
                result.mainNodes, result.totalNodes, result.mainMove, result.emittedMove,
                result.bestmoveDiagnostic.get("source"), result.bestmoveDiagnostic.get("legal"),
                result.bestmoveDiagnostic.getOrDefault("main_score_cp", "0"), result.pv,
                result.engineNs, result.externalNs, result.drainNs, result.iterations,
                result.exits, result.bestmoveDiagnostic, result.drainedDiagnostic, result.lines);
    }

    private static SearchResult search(UciSession session, int position, String fen, int threads,
                                       String kind, int depth, long timeoutNanos, long runStart) throws Exception {
        List<String> lines = new ArrayList<>();
        for (String command : prepareCommands(threads, fen)) {
            session.send(command);
            if (command.equals("isready")) session.await("readyok", 30, lines);
        }
        long started = System.nanoTime();
        long actualTimeoutNanos = budgetedTimeout(timeoutNanos, runStart);
        session.send(kind.equals("reference") ? "go depth " + depth : goCommand(kind));
        long deadline = started + actualTimeoutNanos;
        String bestmoveLine;
        long bestmoveAt;
        try {
            while (true) {
                String line = session.nextLine(deadline, lines);
                Phase20UciEvents.failOnMainException(line);
                if (line.startsWith("bestmove ")) {
                    bestmoveLine = line;
                    bestmoveAt = System.nanoTime();
                    break;
                }
            }
        } catch (UciTimeout timeout) {
            throw new SearchTimeout(lines, timeout, actualTimeoutNanos < timeoutNanos);
        }
        Map<String, String> begin = Phase20UciEvents.awaitEvent(lines, -1, "begin",
                () -> session.nextLine(deadline, lines));
        long searchId = Phase20UciEvents.number(begin, "search");
        Map<String, String> best = Phase20UciEvents.awaitEvent(lines, searchId, "bestmove",
                () -> session.nextLine(deadline, lines));
        int expectedHelpers = threads - 1;
        require(Phase20UciEvents.number(begin, "helpers") == expectedHelpers, "Wrong helper count in begin event");
        List<Map<String, String>> exits = Phase20UciEvents.collectHelperExits(lines, searchId, expectedHelpers,
                () -> session.nextLine(deadline, lines));
        Map<String, String> drained = Phase20UciEvents.awaitEvent(lines, searchId, "drained",
                () -> session.nextLine(deadline, lines));
        Phase20UciEvents.validateObservedHelperExits(lines, searchId, expectedHelpers);
        validateLifecycle(lines, threads, searchId, best, drained, exits);
        String emitted = bestmoveLine.split("\\s+", 3)[1];
        require(emitted.equals(best.get("move")) && "main-result".equals(best.get("source")),
                "Emitted bestmove is not the main result");
        require("true".equals(best.get("legal")), "Engine reports illegal bestmove");
        int mainDepth = integer(best, "main_depth");
        long mainNodes = number(best, "main_nodes");
        String mainMove = best.get("main_move");
        String pv = best.getOrDefault("main_pv", "");
        List<Iteration> iterations = parseIterations(lines, mainDepth);
        require(!iterations.isEmpty() && iterations.get(iterations.size() - 1).depth == mainDepth,
                "Completed iteration stream does not end at main completed depth");
        require(iterations.size() == mainDepth, "Completed iteration stream has a missing depth");
        for (int i = 0; i < iterations.size(); i++) {
            require(iterations.get(i).depth == i + 1, "Completed iteration depths are not contiguous from depth 1");
        }
        require(iterations.get(iterations.size() - 1).nodes == mainNodes,
                "Final iteration nodes differ from main diagnostic nodes");
        require(iterations.get(iterations.size() - 1).move.equals(mainMove),
                "Final completed iteration move differs from main result");
        if (kind.equals("reference")) {
            require(mainDepth <= depth, "Reference exceeded the frozen Dref");
        }
        long totalNodes = mainNodes;
        for (Map<String, String> exit : exits) {
            require("-".equals(exit.get("exception")), "Helper exception: " + exit);
            require(number(exit, "helper_nodes") >= 0 && number(exit, "helper_elapsed_ns") > 0,
                    "Invalid helper node/time values");
            for (String key : List.of("after_generation", "after_clear", "after_resize",
                    "reads_after_generation", "writes_after_generation", "other_after_generation",
                    "reads_after_clear", "writes_after_clear", "other_after_clear",
                    "reads_after_resize", "writes_after_resize", "other_after_resize")) {
                require(number(exit, key) == 0, "TT access across lifecycle boundary " + key + ": " + exit);
            }
            totalNodes += number(exit, "helper_nodes");
        }
        long engineNs = number(best, "search_elapsed_ns");
        long drainNs = number(drained, "drain_elapsed_ns");
        require(engineNs > 0 && drainNs >= engineNs, "Invalid main or drain elapsed time");
        return new SearchResult(searchId, mainDepth, mainNodes, totalNodes, mainMove, emitted, pv,
                engineNs, bestmoveAt - started, drainNs, iterations, exits, best, drained, lines);
    }

    private static List<String> prepareCommands(int threads, String fen) {
        List<String> commands = new ArrayList<>();
        commands.add("ucinewgame");
        commands.addAll(options(threads));
        commands.add("isready");
        commands.add("position fen " + fen);
        return commands;
    }

    private static void validateLifecycle(List<String> lines, int threads, long searchId,
                                          Map<String, String> best, Map<String, String> drained,
                                          List<Map<String, String>> exits) {
        int expected = threads - 1;
        require(Phase20UciEvents.countEvents(lines, searchId, "submit") == expected
                        && Phase20UciEvents.countEvents(lines, searchId, "start") == expected
                        && exits.size() == expected,
                "Helper lifecycle submit/start/exit count mismatch");
        require(integer(best, "helper_submitted") == expected && integer(best, "helper_exceptions") == 0,
                "Bestmove helper accounting failed");
        require(integer(drained, "helper_submitted") == expected && integer(drained, "helper_started") == expected
                        && integer(drained, "helper_exited") == expected && integer(drained, "helper_pending") == 0
                        && integer(drained, "helper_exceptions") == 0,
                "Drain accounting failed");
    }

    private static List<Iteration> parseIterations(List<String> lines, int mainDepth) {
        List<Iteration> iterations = new ArrayList<>();
        Map<Integer, Iteration> byDepth = new LinkedHashMap<>();
        for (String line : lines) {
            if (!line.startsWith("info depth ")) continue;
            Map<String, String> info = Phase20UciEvents.fields(line);
            int depth = integer(info, "depth");
            if (depth > mainDepth || integerOr(info, "multipv", 1) != 1) continue;
            String pv = info.getOrDefault("pv", "");
            String move = pv.isEmpty() ? "" : pv.split("\\s+")[0];
            if (move.isEmpty()) continue;
            Iteration item = new Iteration(depth, number(info, "nodes"), number(info, "time"),
                    info.getOrDefault("score", ""), pv, move, line, 0, 1.0);
            require(byDepth.putIfAbsent(depth, item) == null, "Duplicate completed info depth " + depth);
        }
        String previousMove = "";
        int stableDepthCount = 0;
        for (Iteration raw : byDepth.values()) {
            double scale = 1.0;
            if (raw.depth >= 5 && !previousMove.isEmpty()) {
                boolean same = previousMove.equals(raw.move);
                if (same) stableDepthCount++; else stableDepthCount = 0;
                if (!same) scale = 1.20;
                else if (stableDepthCount >= 3) scale = 0.75;
                else if (stableDepthCount == 2) scale = 0.85;
            }
            Iteration derived = new Iteration(raw.depth, raw.nodes, raw.timeMs, raw.score, raw.pv,
                    raw.move, raw.raw, stableDepthCount, scale);
            iterations.add(derived);
            previousMove = raw.move;
        }
        return iterations;
    }

    private static void drainStoppedSearch(UciSession session, List<String> lines, long runStart) throws Exception {
        session.send("stop");
        long deadline = System.nanoTime() + DRAIN_AFTER_STOP_NANOS;
        boolean hasBestmoveLine = lines.stream().anyMatch(line -> line.startsWith("bestmove "));
        while (!hasBestmoveLine) {
            String line = session.nextLine(deadline, lines);
            Phase20UciEvents.failOnMainException(line);
            hasBestmoveLine = line.startsWith("bestmove ");
        }
        Map<String, String> begin = Phase20UciEvents.event(lines, -1, "begin");
        require(begin != null, "Timed out reference lacks begin lifecycle event");
        long searchId = number(begin, "search");
        Phase20UciEvents.awaitEvent(lines, searchId, "bestmove", () -> session.nextLine(deadline, lines));
        int expected = integer(begin, "helpers");
        Phase20UciEvents.collectHelperExits(lines, searchId, expected, () -> session.nextLine(deadline, lines));
        Map<String, String> drained = Phase20UciEvents.awaitEvent(lines, searchId, "drained",
                () -> session.nextLine(deadline, lines));
        require(integer(drained, "helper_pending") == 0, "Timed-out reference did not drain all helpers");
        checkBudget(runStart);
    }

    private static void writeObservation(TimedObservation s, Csv searches, Csv helpers,
                                         Csv iterations, Csv events) throws IOException {
        searches.row(s.warmup ? "warmup" : "measured", s.pass, s.position, s.threads, s.mode, s.searchId,
                s.fen, s.command, s.mode.equals(MOVETIME) ? MOVETIME_SOFT_HARD_MS : CLOCK_SOFT_MS,
                s.mode.equals(MOVETIME) ? MOVETIME_SOFT_HARD_MS : CLOCK_HARD_MS,
                s.depth, s.mainNodes, s.totalNodes, s.mainMove, s.emittedMove, s.source, s.legal,
                s.score, s.pv, s.engineNs, s.externalNs, s.drainNs, jsonIterations(s.iterations),
                jsonMap(s.bestmoveDiagnostic), jsonMap(s.drainedDiagnostic));
        for (Iteration item : s.iterations) {
            iterations.row(s.warmup ? "warmup" : "measured", s.pass, s.position, s.threads, s.mode,
                    s.searchId, item.depth, item.move, item.nodes, item.timeMs, item.score, item.pv,
                    item.stableDepthCount, item.stabilityScale, item.raw);
        }
        for (String line : s.lines) {
            if (line.startsWith("SMPDIAG ")) {
                Map<String, String> event = Phase20UciEvents.fields(line);
                events.row(s.warmup ? "warmup" : "measured", s.pass, s.position, s.threads, s.mode,
                        event.get("search"), event.get("event"), event.getOrDefault("helper", "-"), line);
            }
        }
        for (Map<String, String> helper : s.exits) {
            helpers.row(s.warmup ? "warmup" : "measured", s.pass, s.position, s.threads, s.mode,
                    s.searchId, helper.get("helper"), helper.get("exit_cause"), helper.get("completed_depth"),
                    helper.get("bestmove"), helper.get("helper_nodes"), helper.get("helper_elapsed_ns"),
                    helper.get("submit_ms"), helper.get("start_ms"), helper.get("exit_ms"), helper.get("abort_ms"),
                    helper.get("bestmove_ms"), helper.get("exception"), helper.get("tt_reads"),
                    helper.get("tt_writes"), helper.get("tt_other"), helper.get("after_abort"),
                    helper.get("after_generation"), helper.get("after_clear"), helper.get("after_resize"),
                    helper.get("reads_after_abort"), helper.get("writes_after_abort"), helper.get("other_after_abort"),
                    helper.get("reads_after_generation"), helper.get("writes_after_generation"),
                    helper.get("other_after_generation"), helper.get("reads_after_clear"),
                    helper.get("writes_after_clear"), helper.get("other_after_clear"),
                    helper.get("reads_after_resize"), helper.get("writes_after_resize"),
                    helper.get("other_after_resize"), jsonMap(helper));
        }
    }

    private static void writeTimedCompletion(Path output, Path... timedFiles) throws Exception {
        require(lineCount(timedFiles[0]) == MEASURED_COUNT + WARMUP_COUNT + 1,
                "Timed search artifact row count mismatch");
        StringBuilder marker = new StringBuilder("measured=1302\nwarmup=186\n");
        for (Path file : timedFiles) marker.append(file.getFileName()).append('=').append(sha256(file)).append('\n');
        Files.writeString(output.resolve("timed-complete.txt"), marker, StandardCharsets.UTF_8);
    }

    private static long lineCount(Path file) throws IOException {
        try (var lines = Files.lines(file, StandardCharsets.UTF_8)) { return lines.count(); }
    }

    private static Path freezeManifest(Path output, List<Target> targets, Path... timedFiles) throws Exception {
        StringBuilder json = new StringBuilder("{\"frozen\":true,\"created_utc\":\"")
                .append(Instant.now()).append("\",\"timed_files\":{");
        for (int i = 0; i < timedFiles.length; i++) {
            if (i > 0) json.append(',');
            json.append(jsonQuote(timedFiles[i].getFileName().toString())).append(':')
                    .append(jsonQuote(sha256(timedFiles[i])));
        }
        json.append("},\"targets\":[");
        for (int i = 0; i < targets.size(); i++) {
            if (i > 0) json.append(',');
            Target target = targets.get(i);
            json.append("{\"position\":").append(target.position).append(",\"fen\":")
                    .append(jsonQuote(FENS[target.position])).append(",\"dmax\":").append(target.dmax)
                    .append(",\"dref\":").append(target.dref).append('}');
        }
        json.append("]}\n");
        Path manifest = output.resolve("target-manifest.json");
        Path temporary = output.resolve("target-manifest.json.tmp");
        Files.writeString(temporary, json, StandardCharsets.UTF_8);
        Files.move(temporary, manifest, StandardCopyOption.ATOMIC_MOVE);
        Files.writeString(output.resolve("target-manifest.sha256"), sha256(manifest) + "\n");
        return manifest;
    }

    private static void verifyFrozenArtifacts(Path output, Path... timedFiles) throws Exception {
        Path complete = output.resolve("timed-complete.txt");
        Path manifest = output.resolve("target-manifest.json");
        Path frozenHash = output.resolve("target-manifest.sha256");
        require(Files.exists(complete) && Files.exists(manifest) && Files.exists(frozenHash),
                "Reference searches blocked until timed artifacts and target manifest are complete/frozen");
        String marker = Files.readString(complete);
        require(marker.contains("measured=" + MEASURED_COUNT + "\n") && marker.contains("warmup=" + WARMUP_COUNT + "\n"),
                "Timed corpus count marker invalid");
        for (Path file : timedFiles) require(marker.contains(file.getFileName() + "=" + sha256(file) + "\n"),
                "Timed artifact changed before references: " + file.getFileName());
        require(sha256(manifest).equals(Files.readString(frozenHash).trim()), "Target manifest hash mismatch");
        require(Files.readString(manifest).contains("\"frozen\":true"), "Target manifest is not marked frozen");
    }

    private static void writeAgreementsAndHelperAgreements(Path output, List<TimedObservation> observations,
                                                           List<Reference> references) throws IOException {
        Map<Integer, Reference> byPosition = new HashMap<>();
        for (int i = 0; i < references.size(); i++) byPosition.put(i, references.get(i));
        try (Csv agreements = new Csv(output.resolve("agreement.csv"), "sample_type", "pass", "position", "threads",
                "mode", "search_id", "reference_status", "reference_move", "main_move", "main_agreement",
                "agreement_low", "agreement_high");
             Csv helperAgreements = new Csv(output.resolve("helper-agreement.csv"), "sample_type", "pass", "position",
                     "threads", "mode", "search_id", "helper", "helper_depth", "helper_move", "reference_status",
                     "reference_move", "is_deepest_helper", "helper_agreement", "main_agreement")) {
            for (TimedObservation s : observations) {
                Reference ref = byPosition.get(s.position);
                String mainAgreement = ref.resolved ? Boolean.toString(s.mainMove.equals(ref.move)) : "unresolved";
                String agreementLow = ref.resolved ? (s.mainMove.equals(ref.move) ? "1" : "0") : "0";
                String agreementHigh = ref.resolved ? agreementLow : "1";
                agreements.row("measured", s.pass, s.position, s.threads, s.mode, s.searchId,
                        ref.reason, ref.move, s.mainMove, mainAgreement,
                        agreementLow, agreementHigh);
                for (Map<String, String> helper : s.exits) {
                    String move = helper.get("bestmove");
                    String agree = ref.resolved ? Boolean.toString(move.equals(ref.move)) : "unresolved";
                    int deepest = s.exits.stream().mapToInt(exit -> integer(exit, "completed_depth")).max().orElse(0);
                    helperAgreements.row("measured", s.pass, s.position, s.threads, s.mode, s.searchId,
                            helper.get("helper"), helper.get("completed_depth"), move, ref.reason, ref.move,
                            integer(helper, "completed_depth") == deepest, agree, mainAgreement);
                }
            }
        }
    }

    private static void analyze(Path output, List<TimedObservation> observations,
                                List<Reference> references) throws IOException {
        Map<Integer, Reference> refByPosition = new HashMap<>();
        for (int i = 0; i < references.size(); i++) refByPosition.put(i, references.get(i));
        Map<Integer, List<Boolean>> modeQualification = new HashMap<>();
        Map<Integer, List<Boolean>> strengthEvidence = new HashMap<>();
        try (Csv summaries = new Csv(output.resolve("decision-summary.csv"), "threads", "mode", "depth_contrast",
                "depth_noise_range", "agreement_contrast_low", "agreement_contrast_high", "agreement_noise_range",
                "time_contrast_ns", "time_noise_range_ns", "qualifies", "classification")) {
            for (int threads : new int[]{2, 4}) {
                for (String mode : List.of(MOVETIME, CLOCK)) {
                    List<PassSummary> one = passSummaries(observations, refByPosition, 1, mode, 1);
                    List<PassSummary> nt = passSummaries(observations, refByPosition, threads, mode, 1);
                    List<Double> depthBase = one.stream().map(PassSummary::depth).toList();
                    List<Double> timeBase = one.stream().map(PassSummary::timeNs).toList();
                    double depthRange = noiseRange(depthBase);
                    double timeRange = noiseRange(timeBase);
                    double agreementRange = maxAgreementRange(one);
                    List<Double> depthDifferences = new ArrayList<>();
                    List<Double> timeDifferences = new ArrayList<>();
                    List<Double> agreementLow = new ArrayList<>();
                    List<Double> agreementHigh = new ArrayList<>();
                    for (int pass = 0; pass < MEASURED_PASSES; pass++) {
                        depthDifferences.add(nt.get(pass).depth - one.get(pass).depth);
                        timeDifferences.add(nt.get(pass).timeNs - one.get(pass).timeNs);
                        agreementLow.add(nt.get(pass).agreementContrastLow);
                        agreementHigh.add(nt.get(pass).agreementContrastHigh);
                    }
                    Metric depth = new Metric(median(depthDifferences), depthRange);
                    Bounds agr = new Bounds(median(agreementLow), median(agreementHigh));
                    Metric time = new Metric(median(timeDifferences), timeRange);
                    ModeResult result = decide(depth, agr, agreementRange, time, timeRange);
                    modeQualification.computeIfAbsent(threads, ignored -> new ArrayList<>()).add(result.qualifies);
                    strengthEvidence.computeIfAbsent(threads, ignored -> new ArrayList<>()).add(
                            result.qualifies && depth.contrast > depthRange && agr.low >= 0
                                    && time.contrast <= timeRange);
                    summaries.row(threads, mode, depth.contrast, depth.range, agr.low, agr.high,
                            agreementRange, time.contrast, time.range, result.qualifies, result.classification);
                }
            }
        }
        try (Csv arms = new Csv(output.resolve("arm-decision.csv"), "threads", "qualifies_both_modes",
                "later_2t_strength_test_evidence")) {
            for (int threads : new int[]{2, 4}) {
                boolean qualifies = modeQualification.getOrDefault(threads, List.of()).size() == 2
                        && modeQualification.get(threads).stream().allMatch(Boolean::booleanValue);
                boolean strength = strengthEvidence.getOrDefault(threads, List.of()).size() == 2
                        && strengthEvidence.get(threads).stream().allMatch(Boolean::booleanValue);
                arms.row(threads, qualifies, threads == 2 && strength);
            }
        }
        writePassSummary(output, observations, refByPosition);
        writePositionSummary(output, observations, refByPosition);
        writeDistributionSummary(output, observations);
        writeHelperCaseSummary(output, observations, refByPosition);
        Files.writeString(output.resolve("analysis.json"), "{\"mode_results\":\"decision-summary.csv\",\"arm_results\":\"arm-decision.csv\",\"pass_summaries\":\"pass-summary.csv\",\"position_pairs\":\"paired-position-summary.csv\",\"distributions\":\"distribution-summary.csv\",\"helper_cases\":\"helper-case-summary.csv\"}\n");
    }

    private static void writePassSummary(Path output, List<TimedObservation> observations,
                                        Map<Integer, Reference> references) throws IOException {
        try (Csv csv = new Csv(output.resolve("pass-summary.csv"), "threads", "mode", "pass", "mean_depth",
                "agreement_low", "agreement_high", "agreement_contrast_low_vs_1t",
                "agreement_contrast_high_vs_1t", "mean_bestmove_ns")) {
            for (int threads : THREADS) {
                for (String mode : List.of(MOVETIME, CLOCK)) {
                    List<PassSummary> passes = passSummaries(observations, references, threads, mode, 1);
                    for (int i = 0; i < passes.size(); i++) {
                        PassSummary p = passes.get(i);
                        csv.row(threads, mode, i + 1, p.depth, p.agreementLow, p.agreementHigh,
                                p.agreementContrastLow, p.agreementContrastHigh, p.timeNs);
                    }
                }
            }
        }
    }

    private static void writePositionSummary(Path output, List<TimedObservation> observations,
                                             Map<Integer, Reference> references) throws IOException {
        try (Csv csv = new Csv(output.resolve("paired-position-summary.csv"), "threads", "mode", "position",
                "mean_depth_difference", "median_depth_difference", "depth_wins", "depth_ties", "depth_losses",
                "mean_time_difference_ns", "move_changes", "nt_only_reference_agreement", "1t_only_reference_agreement",
                "unresolved_reference_pairs")) {
            for (int threads : new int[]{2, 4}) {
                for (String mode : List.of(MOVETIME, CLOCK)) {
                    for (int position = 0; position < FENS.length; position++) {
                        List<Double> depthDiff = new ArrayList<>(), timeDiff = new ArrayList<>();
                        int wins = 0, ties = 0, losses = 0, changed = 0, ntOnly = 0, oneOnly = 0, unresolved = 0;
                        for (int pass = 1; pass <= MEASURED_PASSES; pass++) {
                            TimedObservation nt = findObservation(observations, pass, position, threads, mode);
                            TimedObservation one = findObservation(observations, pass, position, 1, mode);
                            double delta = nt.depth - one.depth;
                            depthDiff.add(delta);
                            timeDiff.add((double) (nt.engineNs - one.engineNs));
                            if (delta > 0) wins++; else if (delta < 0) losses++; else ties++;
                            if (!nt.emittedMove.equals(one.emittedMove)) changed++;
                            Reference ref = references.get(position);
                            if (!ref.resolved) unresolved++;
                            else {
                                boolean ntMatch = nt.emittedMove.equals(ref.move);
                                boolean oneMatch = one.emittedMove.equals(ref.move);
                                if (ntMatch && !oneMatch) ntOnly++;
                                if (!ntMatch && oneMatch) oneOnly++;
                            }
                        }
                        csv.row(threads, mode, position, average(depthDiff), median(depthDiff), wins, ties, losses,
                                average(timeDiff), changed, ntOnly, oneOnly, unresolved);
                    }
                }
            }
        }
    }

    private static TimedObservation findObservation(List<TimedObservation> observations, int pass, int position,
                                                   int threads, String mode) {
        return observations.stream().filter(s -> !s.warmup && s.pass == pass && s.position == position
                && s.threads == threads && s.mode.equals(mode)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing paired observation"));
    }

    private static void writeDistributionSummary(Path output, List<TimedObservation> observations) throws IOException {
        try (Csv csv = new Csv(output.resolve("distribution-summary.csv"), "threads", "mode", "metric", "n",
                "minimum", "p10", "median", "p90", "maximum", "range")) {
            for (int threads : THREADS) {
                for (String mode : List.of(MOVETIME, CLOCK)) {
                    List<TimedObservation> rows = observations.stream().filter(s -> s.threads == threads
                            && s.mode.equals(mode)).toList();
                    distribution(csv, threads, mode, "main_depth", rows.stream().map(s -> (double) s.depth).toList());
                    distribution(csv, threads, mode, "main_nodes", rows.stream().map(s -> (double) s.mainNodes).toList());
                    distribution(csv, threads, mode, "total_nodes", rows.stream().map(s -> (double) s.totalNodes).toList());
                    distribution(csv, threads, mode, "bestmove_ns", rows.stream().map(s -> (double) s.engineNs).toList());
                    if (threads > 1) {
                        List<Double> helperDepths = rows.stream().flatMap(s -> s.exits.stream())
                                .map(h -> (double) integer(h, "completed_depth")).toList();
                        List<Double> helperNodes = rows.stream().flatMap(s -> s.exits.stream())
                                .map(h -> (double) number(h, "helper_nodes")).toList();
                        List<Double> maxPerSearch = rows.stream().map(s -> s.exits.stream()
                                .mapToInt(h -> integer(h, "completed_depth")).max().orElse(0) * 1.0).toList();
                        distribution(csv, threads, mode, "helper_depth", helperDepths);
                        distribution(csv, threads, mode, "helper_nodes", helperNodes);
                        distribution(csv, threads, mode, "max_helper_depth_per_search", maxPerSearch);
                    }
                }
            }
        }
    }

    private static void distribution(Csv csv, int threads, String mode, String metric,
                                     List<Double> values) throws IOException {
        if (values.isEmpty()) return;
        List<Double> sorted = values.stream().sorted().toList();
        double min = sorted.get(0), max = sorted.get(sorted.size() - 1);
        csv.row(threads, mode, metric, sorted.size(), min, percentile(sorted, 0.10), median(sorted),
                percentile(sorted, 0.90), max, max - min);
    }

    /** Descriptive nearest-rank percentile; no percentile is an acceptance threshold. */
    private static double percentile(List<Double> sorted, double p) {
        int index = Math.max(0, (int) Math.ceil(p * sorted.size()) - 1);
        return sorted.get(index);
    }

    private static double average(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
    }

    private static List<PassSummary> passSummaries(List<TimedObservation> observations,
                                                   Map<Integer, Reference> references,
                                                   int threads, String mode, int baselineThreads) {
        List<PassSummary> result = new ArrayList<>();
        for (int pass = 1; pass <= MEASURED_PASSES; pass++) {
            int passNumber = pass;
            List<TimedObservation> rows = observations.stream().filter(s -> !s.warmup && s.pass == passNumber
                    && s.threads == threads && s.mode.equals(mode)).toList();
            Map<Integer, TimedObservation> baseline = new HashMap<>();
            observations.stream().filter(s -> !s.warmup && s.pass == passNumber
                    && s.threads == baselineThreads && s.mode.equals(mode)).forEach(s -> baseline.put(s.position, s));
            require(rows.size() == FENS.length, "Missing observations for pass summary");
            require(baseline.size() == FENS.length, "Missing baseline observations for pass summary");
            double depth = rows.stream().mapToInt(s -> s.depth).average().orElseThrow();
            double time = rows.stream().mapToLong(s -> s.engineNs).average().orElseThrow();
            double agreeLow = 0, agreeHigh = 0, contrastLow = 0, contrastHigh = 0;
            for (TimedObservation row : rows) {
                Reference ref = references.get(row.position);
                TimedObservation baselineRow = baseline.get(row.position);
                Bounds paired = pairedAgreement(row.mainMove, baselineRow.mainMove, ref.move, ref.resolved);
                contrastLow += paired.low;
                contrastHigh += paired.high;
                if (ref.resolved) {
                    double match = row.mainMove.equals(ref.move) ? 1 : 0;
                    agreeLow += match;
                    agreeHigh += match;
                } else {
                    agreeHigh += 1;
                }
            }
            result.add(new PassSummary(depth, time, agreeLow / FENS.length, agreeHigh / FENS.length,
                    contrastLow / FENS.length, contrastHigh / FENS.length));
        }
        return result;
    }

    private static double maxAgreementRange(List<PassSummary> summaries) {
        return summaries.stream().mapToDouble(PassSummary::agreementHigh).max().orElseThrow()
                - summaries.stream().mapToDouble(PassSummary::agreementLow).min().orElseThrow();
    }

    private static void writeHelperCaseSummary(Path output, List<TimedObservation> observations,
                                               Map<Integer, Reference> references) throws IOException {
        try (Csv cases = new Csv(output.resolve("helper-case-summary.csv"), "threads", "mode", "searches",
                "searches_with_deeper_different_helper", "helper_candidates", "helper_only_agreement",
                "main_only_agreement", "both_agree", "neither_agrees", "reference_unresolved")) {
            for (int threads : new int[]{2, 4}) {
                for (String mode : List.of(MOVETIME, CLOCK)) {
                    List<TimedObservation> rows = observations.stream().filter(s -> s.threads == threads
                            && s.mode.equals(mode)).toList();
                    int candidates = 0, deeperDifferent = 0, helperOnly = 0, mainOnly = 0;
                    int both = 0, neither = 0, unresolved = 0;
                    for (TimedObservation row : rows) {
                        List<Map<String, String>> eligible = row.exits.stream().filter(h ->
                                integer(h, "completed_depth") > row.depth
                                        && !row.emittedMove.equals(h.get("bestmove"))).toList();
                        if (eligible.isEmpty()) continue;
                        deeperDifferent++;
                        candidates += eligible.size();
                        Reference ref = references.get(row.position);
                        if (!ref.resolved) { unresolved++; continue; }
                        boolean mainMatch = row.emittedMove.equals(ref.move);
                        boolean helperMatch = eligible.stream().anyMatch(h -> ref.move.equals(h.get("bestmove")));
                        if (helperMatch && !mainMatch) helperOnly++;
                        else if (!helperMatch && mainMatch) mainOnly++;
                        else if (helperMatch) both++;
                        else neither++;
                    }
                    cases.row(threads, mode, rows.size(), deeperDifferent, candidates, helperOnly,
                            mainOnly, both, neither, unresolved);
                }
            }
        }
    }

    private static String jsonIterations(List<Iteration> iterations) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < iterations.size(); i++) {
            if (i > 0) json.append(',');
            Iteration item = iterations.get(i);
            json.append("{\"depth\":").append(item.depth).append(",\"move\":").append(jsonQuote(item.move))
                    .append(",\"nodes\":").append(item.nodes).append(",\"time_ms\":").append(item.timeMs)
                    .append(",\"score\":").append(jsonQuote(item.score)).append(",\"pv\":").append(jsonQuote(item.pv))
                    .append(",\"stable_depth_count\":").append(item.stableDepthCount)
                    .append(",\"stability_scale\":").append(item.stabilityScale).append('}');
        }
        return json.append(']').toString();
    }

    private static String jsonMap(Map<String, String> values) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!first) json.append(',');
            first = false;
            json.append(jsonQuote(entry.getKey())).append(':').append(jsonQuote(entry.getValue()));
        }
        return json.append('}').toString();
    }

    private static String jsonQuote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> { if (c < 0x20) result.append(String.format(Locale.ROOT, "\\u%04x", (int) c)); else result.append(c); }
            }
        }
        return result.append('"').toString();
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = new byte[64 * 1024];
            int read;
            while ((read = input.read(bytes)) >= 0) digest.update(bytes, 0, read);
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private static long number(Map<String, String> values, String key) { return Phase20UciEvents.number(values, key); }
    private static int integer(Map<String, String> values, String key) { return Math.toIntExact(number(values, key)); }
    private static int integerOr(Map<String, String> values, String key, int fallback) {
        return values.containsKey(key) ? integer(values, key) : fallback;
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static final String[] SEARCH_HEADER = {"sample_type", "pass", "position", "threads", "mode", "search_id", "fen",
            "command", "soft_limit_ms", "hard_limit_ms", "main_depth", "main_nodes", "total_nodes", "main_move",
            "emitted_move", "bestmove_source", "legal", "main_score_cp", "main_pv", "engine_bestmove_ns",
            "harness_bestmove_ns", "drain_elapsed_ns", "iterations_json", "bestmove_diagnostic", "drained_diagnostic"};
    private static final String[] HELPER_HEADER = {"sample_type", "pass", "position", "threads", "mode", "search_id", "helper",
            "exit_cause", "completed_depth", "bestmove", "nodes", "elapsed_ns", "submit_ms", "start_ms", "exit_ms",
            "abort_ms", "bestmove_ms", "exception", "tt_reads", "tt_writes", "tt_other", "after_abort", "after_generation",
            "after_clear", "after_resize", "reads_after_abort", "writes_after_abort", "other_after_abort",
            "reads_after_generation", "writes_after_generation", "other_after_generation", "reads_after_clear",
            "writes_after_clear", "other_after_clear", "reads_after_resize", "writes_after_resize", "other_after_resize", "diagnostic_json"};
    private static final String[] ITERATION_HEADER = {"sample_type", "pass", "position", "threads", "mode", "search_id", "depth",
            "move", "nodes", "time_ms", "score", "pv", "stable_depth_count", "stability_scale", "raw_info"};
    private static final String[] EVENT_HEADER = {"sample_type", "pass", "position", "threads", "mode", "search_id", "event", "helper", "raw"};
    private static final String[] REFERENCE_HEADER = {"position", "fen", "dmax", "dref", "completed_depth", "resolved", "move",
            "reason", "nodes", "engine_ns", "iterations_json", "bestmove_diagnostic"};

    private record Step(int pass, int position, int threads, String mode, boolean warmup) {}
    private record DepthObservation(int position, int depth) {}
    private record Target(int position, int dmax, int dref) {}
    private record Bounds(double low, double high) {}
    private record Metric(double contrast, double range) {}
    private record ModeResult(boolean qualifies, String classification) {}
    private record PassSummary(double depth, double timeNs, double agreementLow, double agreementHigh,
                               double agreementContrastLow, double agreementContrastHigh) {}
    private record Iteration(int depth, long nodes, long timeMs, String score, String pv,
                             String move, String raw, int stableDepthCount, double stabilityScale) {}
    private record Reference(int dmax, int dref, int depth, String move, boolean resolved, String reason,
                             long nodes, long engineNs, List<Iteration> iterations, Map<String, String> diagnostic) {
        Reference(int dmax, int dref, int depth, String move, boolean resolved, String reason) {
            this(dmax, dref, depth, move, resolved, reason, 0, 0, List.of(), Map.of());
        }
    }
    private record TimedObservation(int pass, int position, int threads, String mode, boolean warmup, String fen,
                                    long searchId, String command, int depth, long mainNodes, long totalNodes,
                                    String mainMove, String emittedMove, String source, String legal, String score,
                                    String pv, long engineNs, long externalNs, long drainNs,
                                    List<Iteration> iterations, List<Map<String, String>> exits,
                                    Map<String, String> bestmoveDiagnostic, Map<String, String> drainedDiagnostic,
                                    List<String> lines) {}
    private record SearchResult(long searchId, int depth, long mainNodes, long totalNodes, String mainMove,
                                String emittedMove, String pv, long engineNs, long externalNs, long drainNs,
                                List<Iteration> iterations, List<Map<String, String>> exits,
                                Map<String, String> bestmoveDiagnostic, Map<String, String> drainedDiagnostic,
                                List<String> lines) {}

    private static final class SearchTimeout extends IOException {
        final List<String> lines;
        final boolean totalBudgetExpired;
        SearchTimeout(List<String> lines, Exception cause, boolean totalBudgetExpired) {
            super("Reference search watchdog expired", cause);
            this.lines = lines;
            this.totalBudgetExpired = totalBudgetExpired;
        }
    }

    private static final class QueueReader implements Phase20UciEvents.LineReader {
        private final List<String> lines;
        private int index;
        QueueReader(List<String> lines) { this.lines = lines; }
        @Override public String nextLine() { return lines.get(index++); }
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
            writer.flush();
        }
        @Override public void close() throws IOException { writer.close(); }
        private static String csv(String value) { return "\"" + value.replace("\"", "\"\"") + "\""; }
    }

    private static final class Recorder implements AutoCloseable {
        final Path path;
        final BufferedWriter writer;
        volatile boolean finished;
        Recorder(Path path) throws IOException {
            this.path = path;
            writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
        }
        synchronized void record(String direction, String line) {
            try { writer.write(Instant.now() + "\t" + direction + "\t" + line + "\n"); writer.flush(); }
            catch (IOException e) { throw new IllegalStateException("Cannot preserve raw transcript", e); }
        }
        @Override public synchronized void close() throws IOException { writer.close(); }
    }

    private static final class UciTimeout extends IOException {
        UciTimeout(String message) { super(message); }
    }

    private static final class UciSession implements AutoCloseable {
        final Process process;
        final BufferedWriter input;
        final BlockingQueue<String> output = new LinkedBlockingQueue<>();
        final Recorder recorder;
        final Thread readerThread;
        volatile IOException readerFailure;
        private UciSession(Process process, Recorder recorder) throws IOException {
            this.process = process;
            this.recorder = recorder;
            this.input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            readerThread = new Thread(() -> {
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String trimmed = line.trim();
                        recorder.record("OUT", trimmed);
                        output.put(trimmed);
                    }
                } catch (IOException e) { readerFailure = e; }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }, "phase20-stage6-uci-reader");
            readerThread.setDaemon(true);
            readerThread.start();
        }
        static UciSession start(Path javaExe, Path jar, Recorder recorder) throws IOException {
            List<String> command = new ArrayList<>();
            command.add(javaExe.toString());
            command.addAll(List.of("-Xms512m", "-Xmx512m", "-XX:+UseG1GC", "--add-modules", "jdk.incubator.vector",
                    "-Dvex.smp.diagnostics=true", "-jar", jar.toString()));
            recorder.record("PROCESS", String.join(" ", command));
            return new UciSession(new ProcessBuilder(command).redirectErrorStream(true).start(), recorder);
        }
        void send(String command) throws IOException {
            recorder.record("IN", command);
            input.write(command); input.newLine(); input.flush();
        }
        String nextLine(long deadline, List<String> capture) throws Exception {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new UciTimeout("UCI deadline expired");
            String line = output.poll(remaining, TimeUnit.NANOSECONDS);
            if (line != null) { capture.add(line); return line; }
            if (readerFailure != null) throw readerFailure;
            if (!process.isAlive()) throw new IOException("UCI process exited " + process.exitValue());
            throw new UciTimeout("Timed out waiting for UCI output");
        }
        void await(String expected, long seconds) throws Exception { await(expected, seconds, new ArrayList<>()); }
        void await(String expected, long seconds, List<String> capture) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            while (true) {
                String line = nextLine(deadline, capture);
                Phase20UciEvents.failOnMainException(line);
                if (line.equals(expected)) return;
            }
        }
        @Override public void close() throws Exception {
            if (process.isAlive()) {
                try { send("quit"); } catch (IOException ignored) { }
                if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly();
            }
            readerThread.join(5000);
            input.close();
        }
    }
}
