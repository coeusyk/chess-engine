package coeusyk.game.chess.uci;

import coeusyk.game.chess.core.eval.EvalParams;
import coeusyk.game.chess.core.eval.nnue.NnueEvaluator;
import coeusyk.game.chess.core.eval.nnue.NnueNetwork;
import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.models.Piece;
import coeusyk.game.chess.core.movegen.MovesGenerator;
import coeusyk.game.chess.core.search.IterationInfo;
import coeusyk.game.chess.core.search.SearchResult;
import coeusyk.game.chess.core.search.Searcher;
import coeusyk.game.chess.core.search.TimeManager;
import coeusyk.game.chess.core.book.OpeningBook;
import coeusyk.game.chess.core.search.TranspositionTable;
import coeusyk.game.chess.uci.syzygy.OnlineSyzygyProber;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UciApplication {
    private static final Logger LOG = LoggerFactory.getLogger(UciApplication.class);
    private static final boolean SMP_DIAGNOSTICS = Boolean.getBoolean("vex.smp.diagnostics");
    private static final String ENGINE_NAME = "Vex";
    private static final String ENGINE_AUTHOR = "coeusyk";
    private static final int MATE_SCORE = 100_000;
    private static final int MAX_PLY = 128;
    private static final int MAX_SEARCH_DEPTH = 127;

    private Board board = new Board();
    private int multiPV = 1;
    private int hashSizeMb = 64;
    private int pawnHashSizeMb = 1;
    // "NNUE" is accepted at setoption time; whether a search actually uses NNUE is
    // resolved lazily at go-time (resolveNnueNetworkForSearch), since EvalFile may
    // not be set yet, or may point to a file that fails to load — either falls back
    // to Classical with an info string rather than crashing (PRD US-2).
    // Volatile because the search worker resolves the network after the command thread has applied later options.
    private volatile String evalType = "Classical";
    private volatile String evalFile = "";
    // PR C-3 (issue #187): gates the sampled rebuild assertion inside NnueEvaluator's
    // onMake (zero overhead when false) and the nnue features/acc/verify UCI commands.
    private boolean nnueDebug = false;
    // Opt-in (default false): when true, an engine told EvalType=NNUE that cannot use the requested
    // network ends the process (status 2) instead of falling back to Classical, so an NNUE experiment
    // can never silently play Classical. It only covers what the engine is told: an option that arrives
    // under a misspelled name is ignored under the UCI rules and leaves Classical selected.
    private volatile boolean strictNnue = false;
    // The raw value of the last EvalType setoption that was neither Classical nor NNUE (null once a
    // valid value has been set). Only strict mode acts on it.
    private volatile String rejectedEvalType;
    // Cache of the last successfully loaded network, keyed by evalFile's value at
    // load time, so a network isn't re-read from disk on every go.
    private NnueNetwork nnueNetwork;
    private String loadedNnueFilePath;
    private int threads = 1;
    private long moveOverheadMs = 30;
    @SuppressWarnings("unused") // UCI setoption stub — wired up when local Syzygy probing is added
    private String syzygyPath = "";
    private boolean syzygyOnline = false;
    private OnlineSyzygyProber onlineSyzygyProber;
    private int syzygyProbeDepth = 1;
    private boolean syzygy50MoveRule = true;

    // Opening book
    private OpeningBook openingBook = new OpeningBook(50);
    private boolean ownBook = false;
    private String bookFile = "Performance.bin";
    private int bookDepth = 20;
    private int bookVariance = 50;
    private int contempt = 0;

    // Pondering. The ponder clock parameters are read and written on the command thread only.
    private int ponderActiveColor;
    private long ponderWtime, ponderBtime, ponderWinc, ponderBinc;
    private int ponderMoveNumber;

    // Shared transposition table — a single instance sized by Hash setoption,
    // cleared on ucinewgame, and injected into every Searcher (main + helpers).
    private final TranspositionTable sharedTT = new TranspositionTable(64, SMP_DIAGNOSTICS);
    private final AtomicLong searchSequence = new AtomicLong();

    // Number of physical (logical) processors available to this JVM.
    // Helpers are only spawned when there is at least one spare core beyond what
    // the main search thread needs (i.e. availableCores > 1).  On single-core
    // machines or heavily loaded environments this avoids halving the main
    // search's NPS due to CPU time-sharing.
    private static final int AVAILABLE_CORES = Runtime.getRuntime().availableProcessors();

    // Cached thread pool for Lazy SMP helper threads.
    // Helpers run one step below normal priority (NORM_PRIORITY - 1 = 4) so that
    // when they compete with the main search thread on the same core the main thread
    // wins, while still getting near-full throughput on idle/spare cores.
    // MIN_PRIORITY (1) mapped to THREAD_PRIORITY_IDLE on Windows and effectively
    // starved helpers of CPU time, negating the SMP benefit.
    private final ExecutorService smpExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "smp-helper");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private volatile Move latestIterativeBestMove;
    private volatile Thread searchThread;

    /**
     * Everything the command thread and the search worker share for one {@code go}. It is built on the command
     * thread and published before the worker starts, so a {@code stop} or {@code ponderhit} that follows the
     * {@code go} immediately always finds it. A search worker is the only thing that prints its own
     * {@code bestmove}, and {@link #bestmoveEmitted} keeps that to one line. Forced-move and terminal answers have no
     * worker: {@code handleGo} prints them synchronously, once.
     */
    private static final class GoSession {
        /** {@code go infinite} and {@code go ponder}: no bestmove until {@code stop} or {@code ponderhit}. */
        final boolean hold;
        final boolean ponder;
        /** Null for depth and infinite searches. For ponder it starts in ponder mode. */
        final TimeManager timeManager;
        final AtomicBoolean ponderHit = new AtomicBoolean(false);
        final AtomicBoolean bestmoveEmitted = new AtomicBoolean(false);

        GoSession(boolean hold, boolean ponder, TimeManager timeManager) {
            this.hold = hold;
            this.ponder = ponder;
            this.timeManager = timeManager;
        }
    }

    private volatile GoSession session;
    /**
     * Identity of the evaluator the shared transposition table currently holds scores for: "Classical" or
     * "NNUE:" plus the file path and the network's UUID. Written only by a search worker, before it starts any helper and
     * after the previous worker has been joined, so no extra synchronization is needed (issue #259).
     */
    private String tableEvaluatorIdentity = "Classical";
    /** Wakes a worker that is holding its bestmove back. Always set the flag first, then notify under this lock. */
    private final Object holdMonitor = new Object();

    private static final int DEFAULT_BENCH_DEPTH = BenchRunner.DEFAULT_DEPTH;

    /**
     * Test seam (issue #268): keeps a finished search worker alive this long after it has printed its bestmove and
     * drained its helpers, to simulate a worker that is slow to stop. Zero, and never set, in normal use.
     */
    private static final long TEST_DRAIN_DELAY_MS = Long.getLong("vex.test.drainDelayMs", 0L);

    public static void main(String[] args) throws IOException {
        // Heap cap sanity check — must run before any large allocation.
        // Written to stderr with "info string" prefix so GUI logs see it without
        // breaking the UCI protocol (UCI parsers ignore unrecognised info tokens
        // and never read stderr).
        long maxHeapBytes = Runtime.getRuntime().maxMemory();
        final long WARN_THRESHOLD = 256L * 1024L * 1024L; // 256 MB
        if (maxHeapBytes < WARN_THRESHOLD) {
            long maxHeapMb = maxHeapBytes / (1024L * 1024L);
            System.err.println("info string WARNING: JVM heap cap is only " + maxHeapMb
                    + "mb. Recommend -Xmx512m or higher."
                    + " GC pauses may degrade search under multi-threading.");
        }

        // Apply eval-parameter overrides before any Evaluator is constructed so that
        // Evaluator.DEFAULT_CONFIG picks up the new TEMPO value at class-init time.
        for (int i = 0; i < args.length - 1; i++) {
            if ("--param-overrides".equals(args[i])) {
                java.nio.file.Path p = java.nio.file.Paths.get(args[i + 1]);
                if (java.nio.file.Files.exists(p)) {
                    EvalParams.loadOverrides(p);
                }
                break;
            }
        }
        UciApplication app = new UciApplication();
        // Issue #206: evaluator selection in the benchmark path. Only meaningful
        // combined with --bench; setting these on a normal UCI-session launch is
        // harmless (the same fields setoption already assigns during a session).
        for (int i = 0; i < args.length - 1; i++) {
            if ("--eval-type".equals(args[i])) {
                app.evalType = args[i + 1];
            }
            if ("--eval-file".equals(args[i])) {
                app.evalFile = args[i + 1];
            }
        }
        boolean reportSearchTiming = java.util.Arrays.asList(args).contains("--bench-timing");
        for (int i = 0; i < args.length; i++) {
            // --bench-raw: same 31-position suite/protocol as --bench, with the
            // per-node eval-timing instrumentation disabled (Phase 16 P16-2: a
            // production-like NPS number, distinct from --bench's always-on
            // attribution numbers). --bench itself is unchanged for compatibility
            // with existing scripts/docs that already report its instrumented number.
            if ("--bench-raw".equals(args[i])) {
                int depth = DEFAULT_BENCH_DEPTH;
                if (i + 1 < args.length) {
                    try {
                        depth = Integer.parseInt(args[i + 1]);
                        depth = Math.max(1, Math.min(MAX_SEARCH_DEPTH, depth));
                    } catch (NumberFormatException ignored) {
                    }
                }
                app.runBench(depth, false, reportSearchTiming);
                return;
            }
            if ("--bench".equals(args[i])) {
                int depth = DEFAULT_BENCH_DEPTH;
                if (i + 1 < args.length) {
                    try {
                        depth = Integer.parseInt(args[i + 1]);
                        depth = Math.max(1, Math.min(MAX_SEARCH_DEPTH, depth));
                    } catch (NumberFormatException ignored) {
                    }
                }
                app.runBench(depth, true, reportSearchTiming);
                return;
            }
        }
        try {
            app.run();
        } finally {
            app.openingBook.close();
        }
    }

    private void run() throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));

        String line;
        while ((line = in.readLine()) != null) {
            // The request clock starts here, before any parsing or search setup, so a go is charged for all of it.
            long receivedNanos = System.nanoTime();
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }

            if ("uci".equals(line)) {
                System.out.println("id name " + ENGINE_NAME);
                System.out.println("id author " + ENGINE_AUTHOR);
                System.out.println("option name Hash type spin default 64 min 1 max 65536");
                System.out.println("option name PawnHashSize type spin default 1 min 1 max 256");
                System.out.println("option name MultiPV type spin default 1 min 1 max 500");
                System.out.println("option name MoveOverhead type spin default 30 min 0 max 5000");
                System.out.println("option name Threads type spin default 1 min 1 max 512");
                System.out.println("option name SyzygyPath type string default <empty>");
                System.out.println("option name SyzygyOnline type check default false");
                System.out.println("option name SyzygyProbeDepth type spin default 1 min 0 max 100");
                System.out.println("option name Syzygy50MoveRule type check default true");
                System.out.println("option name Ponder type check default false");
                System.out.println("option name OwnBook type check default false");
                System.out.println("option name BookFile type string default Performance.bin");
                System.out.println("option name BookDepth type spin default 20 min 0 max 50");
                System.out.println("option name BookVariance type spin default 50 min 0 max 100");
                System.out.println("option name Contempt type spin default 0 min 0 max 200");
                System.out.println("option name EvalType type combo default Classical var Classical var NNUE");
                System.out.println("option name EvalFile type string default <empty>");
                System.out.println("option name NnueDebug type check default false");
                System.out.println("option name StrictNnue type check default false");
                System.out.println("uciok");
            } else if ("isready".equals(line)) {
                enforceStrictNnue();
                System.out.println("readyok");
            } else if ("ucinewgame".equals(line)) {
                drainSearch();
                board = new Board();
                // Full TT clear (not just age-bump) ensures no stale entries from
                // the previous game are visible to the next game's search.
                sharedTT.clear();
                // Searcher history is reset by constructing a new Searcher per go.
                openingBook.close();
                session = null;
            } else if (line.startsWith("position")) {
                handlePosition(line);
            } else if (line.startsWith("setoption")) {
                handleSetOption(line);
            } else if (line.startsWith("go")) {
                enforceStrictNnue();
                handleGo(line, receivedNanos);
            } else if (line.startsWith("bench")) {
                enforceStrictNnue();
                String[] benchParts = line.split("\\s+");
                int benchDepth = DEFAULT_BENCH_DEPTH;
                if (benchParts.length > 1) {
                    try {
                        benchDepth = Integer.parseInt(benchParts[1]);
                        benchDepth = Math.max(1, Math.min(MAX_SEARCH_DEPTH, benchDepth));
                    } catch (NumberFormatException ignored) {
                    }
                }
                runBench(benchDepth, true);
            } else if ("stop".equals(line)) {
                // The running search prints its own bestmove. A stop with nothing running prints nothing.
                requestStop();
            } else if ("ponderhit".equals(line)) {
                handlePonderHit();
            } else if ("quit".equals(line)) {
                break;
            } else if ("eval".equals(line)) {
                enforceStrictNnue();
                handleEval();
            } else if (line.startsWith("nnue ")) {
                enforceStrictNnue();
                handleNnueDebug(line);
            }

            System.out.flush();
        }
        // quit or end of input: ask the worker to stop and give it up to 2 s to print its bestmove and drain its
        // helpers. Then the process exits regardless: the worker and the helpers are daemon threads, so none of
        // them can outlive it, and a worker that is still busy is simply abandoned with the JVM.
        requestStop();
        stopAndJoinSearch();
        closeOnlineSyzygyProber();
        System.out.flush();
    }

    // One prober per session keeps its position cache and HTTP client across `go` commands. The
    // 50-move flag is fixed at construction, so a change of that option builds a fresh one.
    private OnlineSyzygyProber onlineSyzygyProber() {
        if (onlineSyzygyProber != null && onlineSyzygyProber.respects50MoveRule() != syzygy50MoveRule) {
            closeOnlineSyzygyProber();
        }
        if (onlineSyzygyProber == null) {
            onlineSyzygyProber = new OnlineSyzygyProber(syzygy50MoveRule);
        }
        return onlineSyzygyProber;
    }

    private void closeOnlineSyzygyProber() {
        if (onlineSyzygyProber != null) {
            onlineSyzygyProber.close();
            onlineSyzygyProber = null;
        }
    }

    /**
     * PR C-3 (issue #187): {@code nnue features}/{@code nnue acc}/{@code nnue
     * verify} — gated behind {@code NnueDebug}, root-position-only (no persistent
     * live-search reference exists once {@code go} returns; see class-level note on
     * {@link #resolveNnueNetworkForSearch}). Builds a throwaway {@link NnueEvaluator}
     * over the current {@link #board}, matching {@link #handleEval}'s pattern —
     * never touches the live search's own evaluator instance.
     */
    private void handleNnueDebug(String line) {
        if (!nnueDebug) {
            System.out.println("info string nnue debug commands require NnueDebug=true");
            return;
        }
        if (!"NNUE".equals(evalType)) {
            System.out.println("info string nnue debug commands require EvalType=NNUE");
            return;
        }
        NnueEvaluator nnueEvaluator = buildRootNnueEvaluator();
        if (nnueEvaluator == null) {
            // resolveNnueNetworkForSearch() already printed its own fallback info string.
            return;
        }

        String subcommand = line.substring("nnue ".length()).trim();
        if ("features".equals(subcommand)) {
            printBreakdown(nnueEvaluator.dumpActiveFeatures(board));
        } else if ("acc".equals(subcommand)) {
            printBreakdown(nnueEvaluator.dumpAccumulators());
        } else if ("verify".equals(subcommand)) {
            printBreakdown(formatVerifyResult(nnueEvaluator.verifyAgainstRebuild(board)));
        } else {
            System.out.println("info string Unknown nnue subcommand '" + subcommand
                    + "' — expected features, acc, or verify.");
        }
    }

    /**
     * {@link NnueEvaluator.RebuildDiff}'s default record {@code toString()} prints
     * raw {@code perspectiveColor} ints ({@link Piece#White}=8, {@link
     * Piece#Black}=16) — opaque over a UCI console. Formats it the way {@link
     * NnueEvaluator#dumpAccumulators} already labels perspectives (white=/black=).
     */
    static String formatVerifyResult(NnueEvaluator.RebuildDiff diff) {
        if (diff.matches()) {
            return "verify: OK (incremental matches from-scratch rebuild)";
        }
        String perspective = diff.perspectiveColor() == Piece.White ? "white" : "black";
        return "verify: MISMATCH perspective=" + perspective
                + " index=" + diff.firstDivergingIndex()
                + " delta=" + diff.delta();
    }

    private void handleEval() {
        if ("NNUE".equals(evalType)) {
            NnueEvaluator nnueEvaluator = buildRootNnueEvaluator();
            if (nnueEvaluator != null) {
                printBreakdown(nnueEvaluator.explainEval(board));
                return;
            }
            // resolveNnueNetworkForSearch() already printed its own fallback info
            // string (missing/invalid EvalFile) — fall through to Classical below.
        }
        coeusyk.game.chess.core.eval.Evaluator ev = new coeusyk.game.chess.core.eval.Evaluator();
        printBreakdown(ev.explainEval(board));
    }

    /**
     * Shared by both search-thread construction sites in {@code handleGo}: builds
     * the live search's own {@link NnueEvaluator}, configured with the current
     * {@link #nnueDebug} flag, so every future construction site stays in sync
     * with that flag by construction rather than by convention.
     */
    private NnueEvaluator newSearchNnueEvaluator(NnueNetwork network) {
        return new NnueEvaluator(network, nnueDebug);
    }

    /**
     * Shared by {@link #handleEval} and {@link #handleNnueDebug}: a throwaway,
     * non-debug-mode {@link NnueEvaluator} reset over the current root {@link
     * #board} — never the live search's own evaluator instance. Returns {@code
     * null} if {@code EvalType} isn't NNUE or the network fails to resolve (the
     * caller's own fallback/rejection message applies in that case).
     */
    private NnueEvaluator buildRootNnueEvaluator() {
        NnueNetwork network = resolveNnueNetworkForSearch();
        if (network == null) {
            return null;
        }
        NnueEvaluator nnueEvaluator = new NnueEvaluator(network);
        nnueEvaluator.reset(board);
        return nnueEvaluator;
    }

    private static void printBreakdown(String breakdown) {
        for (String line : breakdown.split("\n")) {
            System.out.println("info string " + line);
        }
    }

    private void handlePosition(String command) {
        String[] parts = command.split("\\s+");
        if (parts.length < 2) {
            return;
        }
        // The worker probes the opening book, which the reopen guard below can touch.
        drainSearch();

        int index = 1;
        if ("startpos".equals(parts[index])) {
            board = new Board();
            index++;
        } else if ("fen".equals(parts[index])) {
            if (parts.length < index + 7) {
                return;
            }
            String fen = String.join(" ",
                    parts[index + 1],
                    parts[index + 2],
                    parts[index + 3],
                    parts[index + 4],
                    parts[index + 5],
                    parts[index + 6]);
            board = new Board(fen);
            index += 7;
        } else {
            return;
        }

        if (index < parts.length && "moves".equals(parts[index])) {
            index++;
            for (; index < parts.length; index++) {
                Move move = findLegalMoveByUci(parts[index]);
                if (move == null) {
                    return;
                }
                board.makeMove(move);
            }
        }

        // Reopen book on the UCI main thread so the book is guaranteed open
        // before the next "go" arrives. This fixes the data race where ucinewgame
        // closed the book and the lazy reopen in runSearch() ran on the search thread
        // concurrently with setoption writes from the UCI thread.
        if (ownBook && !openingBook.isOpen()) {
            openNewBook();
        }
    }

    private void handleSetOption(String command) {
        String[] tokens = command.split("\\s+");
        if (tokens.length == 0) {
            return;
        }

        int nameIndex = -1;
        int valueIndex = -1;
        for (int i = 0; i < tokens.length; i++) {
            String t = tokens[i];
            if (t.equalsIgnoreCase("name") && nameIndex < 0) {
                nameIndex = i;
            } else if (t.equalsIgnoreCase("value") && valueIndex < 0) {
                valueIndex = i;
            }
        }

        if (nameIndex < 0 || nameIndex + 1 >= tokens.length) {
            return;
        }

        int nameEnd = (valueIndex > nameIndex) ? valueIndex : tokens.length;
        StringBuilder nameBuilder = new StringBuilder();
        for (int i = nameIndex + 1; i < nameEnd; i++) {
            if (nameBuilder.length() > 0) {
                nameBuilder.append(' ');
            }
            nameBuilder.append(tokens[i]);
        }
        String optionName = nameBuilder.toString().trim();
        if (optionName.isEmpty()) {
            return;
        }
        String optionNameLower = optionName.toLowerCase();

        String valuePart = null;
        if (valueIndex >= 0 && valueIndex + 1 < tokens.length) {
            StringBuilder valueBuilder = new StringBuilder();
            for (int i = valueIndex + 1; i < tokens.length; i++) {
                if (valueBuilder.length() > 0) {
                    valueBuilder.append(' ');
                }
                valueBuilder.append(tokens[i]);
            }
            valuePart = valueBuilder.toString();
        }
        if (valuePart == null) {
            return;
        }

        if ("hash".equals(optionNameLower)) {
            try {
                int value = Integer.parseInt(valuePart);
                int newHashSizeMb = Math.max(1, Math.min(65536, value));
                drainSearch();
                sharedTT.resize(newHashSizeMb);
                hashSizeMb = newHashSizeMb;
            } catch (NumberFormatException ignored) {
            }
        } else if ("multipv".equals(optionNameLower)) {
            try {
                int value = Integer.parseInt(valuePart);
                multiPV = Math.max(1, Math.min(500, value));
            } catch (NumberFormatException ignored) {
            }
        } else if ("moveoverhead".equals(optionNameLower)) {
            try {
                long value = Long.parseLong(valuePart);
                moveOverheadMs = Math.max(0, Math.min(5000, value));
            } catch (NumberFormatException ignored) {
            }
        } else if ("syzygypath".equals(optionNameLower)) {
            syzygyPath = valuePart.trim();
        } else if ("syzygyonline".equals(optionNameLower)) {
            syzygyOnline = "true".equalsIgnoreCase(valuePart);
            if (!syzygyOnline) {
                // The worker creates and uses the prober, so wait for it before closing, as Hash does.
                drainSearch();
                closeOnlineSyzygyProber();
            }
        } else if ("syzygyprobedepth".equals(optionNameLower)) {
            try {
                int value = Integer.parseInt(valuePart);
                syzygyProbeDepth = Math.max(0, Math.min(100, value));
            } catch (NumberFormatException ignored) {
            }
        } else if ("syzygy50moverule".equals(optionNameLower)) {
            syzygy50MoveRule = "true".equalsIgnoreCase(valuePart);
        } else if ("threads".equals(optionNameLower)) {
            try {
                int value = Integer.parseInt(valuePart);
                threads = Math.max(1, Math.min(512, value));
            } catch (NumberFormatException ignored) {
            }
        } else if ("pawnhashsize".equals(optionNameLower)) {
            try {
                int value = Integer.parseInt(valuePart);
                pawnHashSizeMb = Math.max(1, Math.min(256, value));
            } catch (NumberFormatException ignored) {
            }
        } else if ("ponder".equals(optionNameLower)) {
            // acknowledged; ponder state is managed per-go via "go ponder"
        } else if ("ownbook".equals(optionNameLower)) {
            ownBook = "true".equalsIgnoreCase(valuePart);
        } else if ("bookfile".equals(optionNameLower)) {
            String trimmed = valuePart.trim();
            if (trimmed.contains("..") || trimmed.contains("\0") || trimmed.length() > 512) {
                LOG.warn("setoption BookFile rejected: invalid path '{}'",
                        trimmed.replaceAll("[\r\n\t]", "_"));
                return;
            }
            drainSearch();
            bookFile = trimmed;
            openNewBook();
        } else if ("bookdepth".equals(optionNameLower)) {
            try {
                bookDepth = Math.max(0, Math.min(50, Integer.parseInt(valuePart)));
            } catch (NumberFormatException ignored) {
            }
        } else if ("bookvariance".equals(optionNameLower)) {
            try {
                int newVariance = Math.max(0, Math.min(100, Integer.parseInt(valuePart)));
                drainSearch();
                bookVariance = newVariance;
                OpeningBook previous = openingBook;
                openingBook = new OpeningBook(bookVariance);
                previous.close();
                openNewBook();
            } catch (NumberFormatException ignored) {
            }
        } else if ("contempt".equals(optionNameLower)) {
            try {
                contempt = Math.max(0, Math.min(200, Integer.parseInt(valuePart)));
            } catch (NumberFormatException ignored) {
            }
        } else if ("evaltype".equals(optionNameLower)) {
            String trimmed = valuePart.trim();
            if ("classical".equalsIgnoreCase(trimmed)) {
                evalType = "Classical";
                rejectedEvalType = null;
            } else if ("nnue".equalsIgnoreCase(trimmed)) {
                // Whether this actually resolves to NNUE (valid EvalFile, loads OK) is
                // decided lazily at go-time — EvalFile may not be set yet.
                evalType = "NNUE";
                rejectedEvalType = null;
            } else {
                rejectedEvalType = trimmed;
                System.out.println("info string Unknown EvalType value '" + trimmed
                        + "' — expected Classical or NNUE. Keeping " + evalType + ".");
            }
        } else if ("evalfile".equals(optionNameLower)) {
            evalFile = valuePart.trim();
        } else if ("nnuedebug".equals(optionNameLower)) {
            nnueDebug = "true".equalsIgnoreCase(valuePart);
        } else if ("strictnnue".equals(optionNameLower)) {
            strictNnue = "true".equalsIgnoreCase(valuePart.trim());
        }
        // Unknown options are silently ignored per UCI spec.
    }

    /**
     * Resolves which {@link NnueNetwork} (if any) this search should use, called once
     * per {@code go} before the main searcher and any Lazy SMP helpers are
     * constructed — not once per thread, so the fallback info string (if any) is
     * printed exactly once, not once per helper. Returns {@code null} whenever the
     * search should use Classical: {@code EvalType} isn't {@code NNUE}, no
     * {@code EvalFile} is set, or the file failed to load (PRD US-2: never crash,
     * always fall back with an info string).
     */
    private NnueNetwork resolveNnueNetworkForSearch() {
        if (!"NNUE".equals(evalType)) {
            return null;
        }
        if (evalFile.isEmpty()) {
            System.out.println("info string NNUE evaluator requested but EvalFile is not set; "
                    + "falling back to Classical.");
            return null;
        }
        if (nnueNetwork != null && evalFile.equals(loadedNnueFilePath)) {
            return nnueNetwork;
        }
        try {
            NnueNetwork loaded = NnueNetwork.load(Path.of(evalFile));
            nnueNetwork = loaded;
            loadedNnueFilePath = evalFile;
            System.out.println("info string NNUE network loaded: " + loaded.networkUuid());
            return loaded;
        } catch (Exception e) {
            System.out.println("info string Failed to load NNUE network from '" + evalFile
                    + "' (" + e.getMessage() + "); falling back to Classical.");
            nnueNetwork = null;
            loadedNnueFilePath = null;
            return null;
        }
    }

    private void openNewBook() {
        openingBook.close();
        String safeLog = bookFile.replaceAll("[\r\n\t]", "_");  // log injection guard

        java.nio.file.Path p = java.nio.file.Path.of(bookFile);

        // Step 1: absolute path — use directly.
        if (p.isAbsolute()) {
            try {
                openingBook.open(p);
            } catch (java.io.IOException e) {
                LOG.warn("Could not open book file '{}': {}", safeLog, e.getMessage());
            }
            if (!openingBook.isOpen()) {
                LOG.warn("Book file not found at absolute path '{}' — book disabled", safeLog);
            }
            return;
        }

        // Step 2: relative to the JAR directory (covers GUI launchers with arbitrary CWD).
        try {
            java.nio.file.Path jarLoc = java.nio.file.Path.of(
                UciApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI()
            );
            java.nio.file.Path jarDir = jarLoc.getParent();
            if (jarDir != null) {
                try {
                    openingBook.open(jarDir.resolve(bookFile));
                } catch (java.io.IOException ignored) { /* fall through */ }
                if (openingBook.isOpen()) return;
            }
        } catch (Exception ignored) {
            // getCodeSource() can return null, or URI may be malformed — fall through.
        }

        // Step 3: relative to the working directory.
        try {
            openingBook.open(p);
        } catch (java.io.IOException ignored) { /* fall through */ }
        if (openingBook.isOpen()) return;

        // Step 4: classpath resource (only allow plain filenames, no directory traversal).
        if (!bookFile.contains("/") && !bookFile.contains("\\")) {
            openingBook.openFromClasspath("books/" + bookFile);
        }

        if (!openingBook.isOpen()) {
            LOG.warn("Book file '{}' not found at any location — book disabled", safeLog);
        }
    }

    /**
     * Why strict mode cannot use the evaluator the options ask for, or null when strict mode is off or the
     * request can be honoured. A network that loads is cached exactly as the normal resolver does, so the
     * search that follows uses it without reading the file again. Never reports the "falling back" lines
     * of the non-strict path: strict mode has no fallback.
     */
    String strictNnueFailure() {
        if (!strictNnue) {
            return null;
        }
        if (rejectedEvalType != null) {
            return "unknown EvalType value '" + rejectedEvalType + "', expected Classical or NNUE";
        }
        if (!"NNUE".equals(evalType)) {
            return null;
        }
        if (evalFile.isEmpty()) {
            return "EvalType=NNUE was requested but EvalFile is not set";
        }
        if (nnueNetwork != null && evalFile.equals(loadedNnueFilePath)) {
            return null;
        }
        try {
            NnueNetwork loaded = NnueNetwork.load(Path.of(evalFile));
            nnueNetwork = loaded;
            loadedNnueFilePath = evalFile;
            System.out.println("info string NNUE network loaded: " + loaded.networkUuid());
            return null;
        } catch (Exception e) {
            nnueNetwork = null;
            loadedNnueFilePath = null;
            return "cannot load EvalFile '" + evalFile + "': " + e.getMessage();
        }
    }

    /** Ends the process (status 2) when strict mode cannot honour the requested evaluator. */
    private void enforceStrictNnue() {
        String failure = strictNnueFailure();
        if (failure != null) {
            System.out.println("info string NNUE strict mode: " + failure);
            System.out.flush();
            System.exit(2);
        }
    }

    private void handleGo(String command, long receivedNanos) {
        drainSearch();

        long searchId = SMP_DIAGNOSTICS ? searchSequence.incrementAndGet() : 0L;
        stopRequested.set(false);
        latestIterativeBestMove = null;

        // Detect ponder flag and save time params for ponderhit reconfiguration.
        String[] goParts = command.split("\\s+");
        boolean isGoPonder = contains(goParts, "ponder");
        // go infinite and go ponder answer only after stop (or ponderhit), however early the search finishes.
        boolean hold = isGoPonder || contains(goParts, "infinite");
        if (isGoPonder) {
            ponderActiveColor = board.getActiveColor();
            ponderWtime = parseLongArg(goParts, "wtime", 0L);
            ponderBtime = parseLongArg(goParts, "btime", 0L);
            ponderWinc  = parseLongArg(goParts, "winc",  0L);
            ponderBinc  = parseLongArg(goParts, "binc",  0L);
            ponderMoveNumber = Math.max(1, board.getFullMoves()) - 1;
        }

        Board.SearchSnapshot snapshot = board.snapshotForSearch();
        Board searchBoard = snapshot.newBoard();

        // Forced move detection: if exactly one legal move exists, play it
        // immediately without entering the search. Saves clock time in positions
        // where there is no choice (e.g. single legal reply to check).
        // Skip in ponder and infinite mode: they are held until stop or ponderhit.
        List<Move> legalMoves = new MovesGenerator(searchBoard).getActiveMoves(searchBoard.getActiveColor());
        if (!hold && legalMoves.size() == 1) {
            Move forcedMove = legalMoves.get(0);
            emitBestMove(forcedMove);
            System.out.flush();
            if (SMP_DIAGNOSTICS) {
                smpDiagnostic("search=" + searchId + " event=bestmove source=forced move="
                        + moveToUci(forcedMove) + " legal=true helpers_submitted=0");
            }
            return;
        }
        if (legalMoves.isEmpty()) {
            // Checkmate or stalemate — no move to play.
            LOG.warn("warn: handleGo called with 0 legal moves");
            emitBestMove(null);
            System.out.flush();
            if (SMP_DIAGNOSTICS) {
                smpDiagnostic("search=" + searchId + " event=bestmove source=terminal move=0000 legal=true helpers_submitted=0");
            }
            return;
        }

        // Built and published here, before the worker exists, so ponderhit and stop can never beat it.
        GoSession goSession = new GoSession(hold, isGoPonder,
                hold && !isGoPonder ? null : buildTimeManager(goParts, searchBoard, isGoPonder, receivedNanos));
        session = goSession;
        Thread worker = new Thread(() -> runSearch(command, snapshot, searchBoard, searchId, legalMoves, goSession),
                "uci-search-thread");
        worker.setDaemon(true);
        searchThread = worker;
        worker.start();
    }

    /**
     * Time control for a go command, or null when it is a depth or infinite search. The manager keeps
     * {@code receivedNanos} as its clock origin, so everything between the request and the search is charged.
     */
    TimeManager buildTimeManager(String[] parts, Board searchBoard, boolean ponder, long receivedNanos) {
        TimeManager manager;
        if (contains(parts, "movetime")) {
            manager = new TimeManager();
            manager.setMoveOverheadMs(moveOverheadMs);
            manager.configureMovetime(parseLongArg(parts, "movetime", 1000L));
        } else if (contains(parts, "wtime") || contains(parts, "btime")) {
            manager = new TimeManager();
            manager.setMoveOverheadMs(moveOverheadMs);
            int moveNumber = Math.max(1, searchBoard.getFullMoves()) - 1;
            manager.configureClock(searchBoard.getActiveColor(),
                    parseLongArg(parts, "wtime", 0L), parseLongArg(parts, "btime", 0L),
                    parseLongArg(parts, "winc", 0L), parseLongArg(parts, "binc", 0L), moveNumber);
        } else {
            return null;
        }
        manager.markRequestReceived(receivedNanos);
        if (ponder) {
            manager.configurePonder();
        }
        return manager;
    }

    private void handlePonderHit() {
        GoSession current = session;
        if (current == null || !current.ponder || !current.ponderHit.compareAndSet(false, true)) {
            return;
        }
        if (current.timeManager != null) {
            current.timeManager.configurePonderHit(
                    ponderActiveColor, ponderWtime, ponderBtime, ponderWinc, ponderBinc, ponderMoveNumber);
        }
        synchronized (holdMonitor) {
            holdMonitor.notifyAll();
        }
    }

    /** Blocks a held search's bestmove until stop, ponderhit (ponder only) or a state change asks it to end. */
    private void awaitRelease(GoSession current) {
        synchronized (holdMonitor) {
            try {
                while (!stopRequested.get() && !(current.ponder && current.ponderHit.get())) {
                    holdMonitor.wait();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void runSearch(String command, Board.SearchSnapshot snapshot, Board searchBoard, long searchId,
                           List<Move> rootLegalMoves, GoSession goSession) {
        // Per-search abort flag for Lazy SMP helper threads.
        // Separate from the global stopRequested so setting it to true at the end
        // of this search does not bleed into the next search cycle.
        AtomicBoolean helperAbort = new AtomicBoolean(false);
        long searchStartedNanos = SMP_DIAGNOSTICS ? System.nanoTime() : 0L;
        AtomicLong helperAbortNanos = SMP_DIAGNOSTICS ? new AtomicLong() : null;
        AtomicLong bestmoveNanos = SMP_DIAGNOSTICS ? new AtomicLong() : null;
        AtomicInteger helperSubmissions = SMP_DIAGNOSTICS ? new AtomicInteger() : null;
        AtomicInteger helperStarts = SMP_DIAGNOSTICS ? new AtomicInteger() : null;
        AtomicInteger helperExits = SMP_DIAGNOSTICS ? new AtomicInteger() : null;
        AtomicInteger helperExceptions = SMP_DIAGNOSTICS ? new AtomicInteger() : null;
        List<Future<?>> helperFutures = new ArrayList<>();
        Move bestMoveToEmit = null;
        SearchResult result = null;
        try {
            // Resolved once per go (not once per thread) so any fallback info string
            // prints exactly once; each thread below still gets its own NnueEvaluator
            // instance — never the same instance shared across threads.
            NnueNetwork nnueNetworkForSearch = resolveNnueNetworkForSearch();
            // The check at go ran on the command thread. An EvalFile or EvalType option that arrived after it
            // (UCI forbids setoption during a search, but nothing here enforces that) must not turn a strict
            // NNUE request into a Classical search, so strict mode re-checks where the network is resolved.
            if (nnueNetworkForSearch == null) {
                enforceStrictNnue();
            }
            // Issue #259: the shared table's entries are scores from one particular evaluator, and its
            // key does not say which. When the effective evaluator changes (Classical or NNUE, or a
            // different network, after any fallback has been applied) the entries are meaningless to
            // the next search, so clear them. This runs before any helper of this search is submitted
            // and after handleGo has joined the previous worker and its helpers, so nothing is
            // reading or writing the table while it is cleared.
            // The file path is part of the identity because the UUID inside a file is only unique if every
            // tool that writes networks makes it so: a copied or hand-edited header would otherwise let two
            // different networks share one.
            String evaluatorIdentity = nnueNetworkForSearch == null
                    ? "Classical" : "NNUE:" + evalFile + "#" + nnueNetworkForSearch.networkUuid();
            if (!evaluatorIdentity.equals(tableEvaluatorIdentity)) {
                sharedTT.clear();
                tableEvaluatorIdentity = evaluatorIdentity;
                System.out.println("info string transposition table cleared: evaluator changed");
            }
            // --- Lazy SMP: launch N-1 helper threads before the main search ---
            // Each helper runs an independent iterative-deepening loop on its own
            // Board copy, all sharing the same TranspositionTable.  Helpers stop
            // when helperAbort or stopRequested becomes true.
            // Only spawn helpers when there are spare cores (AVAILABLE_CORES > 1)
            // to prevent helpers from stealing CPU from the main search thread
            // on single-core or heavily-loaded machines.
            int effectiveHelpers = Math.min(threads - 1, AVAILABLE_CORES - 1);
            // Bump the generation counter before any thread (main or helper) starts
            // writing to the shared TT for this position.  If this were called inside
            // searchWithTimeManager() instead, helpers submitted just before would
            // already be running at generation N while the main thread bumps to N+1,
            // immediately evicting any shallow entries deposited by the helpers.
            sharedTT.incrementGeneration();
            TranspositionTable.DiagnosticSnapshot helperSearchStart = SMP_DIAGNOSTICS
                    ? sharedTT.diagnosticSnapshot() : null;
            if (SMP_DIAGNOSTICS) {
                smpDiagnostic("search=" + searchId + " event=begin threads=" + threads
                        + " helpers=" + effectiveHelpers + " available_cores=" + AVAILABLE_CORES);
            }
            if (effectiveHelpers > 0) {
                for (int i = 1; i <= effectiveHelpers; i++) {
                    // Stagger start depths per spec:
                    //   odd-indexed helpers (1, 3, 5,...) start at depth (i/2)+2
                    //   even-indexed helpers (2, 4, 6,...) start at depth 1
                    // This reduces redundant shallow-depth work and helps threads
                    // diverge earlier, improving TT utilisation.
                    final int startDepth = (i % 2 == 1) ? (i / 2) + 2 : 1;
                    final int helperId = i;
                    long submittedNanos = SMP_DIAGNOSTICS ? System.nanoTime() : 0L;
                    if (SMP_DIAGNOSTICS) {
                        helperSubmissions.incrementAndGet();
                        smpDiagnostic("search=" + searchId + " event=submit helper=" + helperId
                                + " ms=" + elapsedMillis(searchStartedNanos, submittedNanos)
                                + " start_depth=" + startDepth);
                    }
                    Future<?> helperFuture = smpExecutor.submit(() -> {
                        long helperStartedNanos = SMP_DIAGNOSTICS ? System.nanoTime() : 0L;
                        SearchResult helperResult = null;
                        Exception helperFailure = null;
                        if (SMP_DIAGNOSTICS) {
                            helperStarts.incrementAndGet();
                            sharedTT.beginHelperDiagnostics(searchId, helperId, helperSearchStart,
                                    () -> helperAbort.get() || stopRequested.get());
                            smpDiagnostic("search=" + searchId + " event=start helper=" + helperId
                                    + " ms=" + elapsedMillis(searchStartedNanos, helperStartedNanos));
                        }
                        try {
                            Searcher helper = new Searcher();
                            helper.setSharedTranspositionTable(sharedTT);
                            helper.setPawnHashSizeMb(pawnHashSizeMb);
                            helper.setContempt(contempt);
                            if (nnueNetworkForSearch != null) {
                                helper.setEvaluatorStrategy(newSearchNnueEvaluator(nnueNetworkForSearch));
                            }
                            helperResult = helper.iterativeDeepening(
                                    snapshot.newBoard(),
                                    MAX_SEARCH_DEPTH,
                                    startDepth,
                                    () -> helperAbort.get() || stopRequested.get(),
                                    () -> helperAbort.get() || stopRequested.get(),
                                    null
                            );
                        } catch (Exception e) {
                            // Helper failures are swallowed; only the main thread result matters.
                            helperFailure = e;
                            if (SMP_DIAGNOSTICS) {
                                helperExceptions.incrementAndGet();
                                LOG.error("SMP helper exception search={} helper={}", searchId, helperId, e);
                            }
                        } finally {
                            if (SMP_DIAGNOSTICS) {
                                long exitedNanos = System.nanoTime();
                                TranspositionTable.HelperActivity activity = sharedTT.endHelperDiagnostics();
                                helperExits.incrementAndGet();
                                long abortNanos = helperAbortNanos.get();
                                long emittedNanos = bestmoveNanos.get();
                                String exitCause = helperFailure != null ? "exception"
                                        : helperResult == null ? "no-result"
                                        : !helperResult.aborted() ? "completed"
                                        : stopRequested.get() && !helperAbort.get() ? "stop-requested"
                                        : "helper-abort";
                                smpDiagnostic("search=" + searchId + " event=exit helper=" + helperId
                                        + " submit_ms=" + elapsedMillis(searchStartedNanos, submittedNanos)
                                        + " start_ms=" + elapsedMillis(searchStartedNanos, helperStartedNanos)
                                        + " exit_ms=" + elapsedMillis(searchStartedNanos, exitedNanos)
                                        + " abort_ms=" + elapsedMillisOrPending(searchStartedNanos, abortNanos)
                                        + " bestmove_ms=" + elapsedMillisOrPending(searchStartedNanos, emittedNanos)
                                        + " helper_elapsed_ns=" + (exitedNanos - helperStartedNanos)
                                        + " helper_nodes=" + (helperResult == null ? 0 : helperResult.nodesVisited())
                                        + " search_elapsed_ns=" + (exitedNanos - searchStartedNanos)
                                        + " exit_cause=" + exitCause
                                        + " completed_depth=" + (helperResult == null ? 0 : helperResult.depthReached())
                                        + " bestmove=" + (helperResult == null || helperResult.bestMove() == null
                                                ? "-" : moveToUci(helperResult.bestMove()))
                                        + " exception=" + (helperFailure == null ? "-" : helperFailure.getClass().getName())
                                        + " tt_reads=" + activity.reads() + " tt_writes=" + activity.writes()
                                        + " tt_other=" + activity.other()
                                        + " after_abort=" + activity.afterAbort()
                                        + " after_generation=" + activity.afterGeneration()
                                        + " after_clear=" + activity.afterClear()
                                        + " after_resize=" + activity.afterResize()
                                        + " reads_after_abort=" + activity.readsAfterAbort()
                                        + " writes_after_abort=" + activity.writesAfterAbort()
                                        + " other_after_abort=" + activity.otherAfterAbort()
                                        + " reads_after_generation=" + activity.readsAfterGeneration()
                                        + " writes_after_generation=" + activity.writesAfterGeneration()
                                        + " other_after_generation=" + activity.otherAfterGeneration()
                                        + " reads_after_clear=" + activity.readsAfterClear()
                                        + " writes_after_clear=" + activity.writesAfterClear()
                                        + " other_after_clear=" + activity.otherAfterClear()
                                        + " reads_after_resize=" + activity.readsAfterResize()
                                        + " writes_after_resize=" + activity.writesAfterResize()
                                        + " other_after_resize=" + activity.otherAfterResize());
                            }
                        }
                    });
                    helperFutures.add(helperFuture);
                }
            }

            // --- Main search ---
            String[] parts = command.split("\\s+");
            boolean isPonder = contains(parts, "ponder");

            // Opening book probe — skip when pondering (we're searching the opponent's position).
            // Book is guaranteed open if handlePosition() ran before this go (see reopen guard there).
            if (ownBook && !isPonder) {
                long halfMoves = 2L * (Math.max(1, searchBoard.getFullMoves()) - 1)
                        + (Piece.isWhite(searchBoard.getActiveColor()) ? 0 : 1);
                if (halfMoves < bookDepth * 2L) {
                    Move bookMove = openingBook.probe(searchBoard);
                    if (bookMove != null) {
                        bestMoveToEmit = bookMove;
                        return;
                    }
                }
            }

            Searcher searcher = new Searcher();
            searcher.setSharedTranspositionTable(sharedTT);
            searcher.setPawnHashSizeMb(pawnHashSizeMb);
            searcher.setContempt(contempt);
            if (nnueNetworkForSearch != null) {
                searcher.setEvaluatorStrategy(newSearchNnueEvaluator(nnueNetworkForSearch));
            }
            if (multiPV > 1) {
                searcher.setMultiPV(multiPV);
            }

            // Configure Syzygy probing
            if (syzygyOnline) {
                searcher.setSyzygyProber(onlineSyzygyProber());
                searcher.setSyzygyProbeDepth(syzygyProbeDepth);
                searcher.setSyzygy50MoveRule(syzygy50MoveRule);
            }

            List<Move> searchMovesList = parseSearchMoves(parts, searchBoard);
            if (!searchMovesList.isEmpty()) {
                searcher.setSearchMoves(searchMovesList);
            }

            if (goSession.timeManager != null) {
                result = searcher.searchWithTimeManager(
                        searchBoard,
                        64,
                        goSession.timeManager,
                        stopRequested::get,
                        this::printInfoLine
                );
            } else {
                // Depth searches default to 4. Held searches (infinite, bare ponder) run to the depth cap.
                int depth = parseDepthArg(parts, "depth", goSession.hold ? MAX_SEARCH_DEPTH : 4);
                result = searcher.iterativeDeepening(
                        searchBoard,
                        depth,
                        stopRequested::get,
                        stopRequested::get,
                        this::printInfoLine
                );
            }

            bestMoveToEmit = result.bestMove() != null ? result.bestMove() : latestIterativeBestMove;
        } finally {
            // Emit the main-thread result promptly, then drain owned helpers before
            // marking this search idle. UCI mutations join this thread as the
            // quiescence boundary for all of its workers.
            if (SMP_DIAGNOSTICS) {
                helperAbortNanos.compareAndSet(0L, System.nanoTime());
            }
            helperAbort.set(true);
            Move ponderMove = result != null ? result.ponderMove() : null;
            Move found = bestMoveToEmit != null ? bestMoveToEmit : latestIterativeBestMove;
            // Aborted before depth 1 finished: a null move is only right for a terminal position.
            Move toEmit = found == null && !rootLegalMoves.isEmpty() ? rootLegalMoves.get(0) : found;
            if (goSession.hold) {
                if (SMP_DIAGNOSTICS) {
                    smpDiagnostic("search=" + searchId + " event=hold");
                }
                awaitRelease(goSession);
            }
            if (SMP_DIAGNOSTICS) {
                bestmoveNanos.set(System.nanoTime());
            }
            if (goSession.bestmoveEmitted.compareAndSet(false, true)) {
                emitBestMove(toEmit, ponderMove);
            }
            System.out.flush();
            if (SMP_DIAGNOSTICS) {
                String source = result != null && result.bestMove() != null ? "main-result"
                        : result == null && bestMoveToEmit != null ? "book"
                        : toEmit != null ? "latest-iterative" : "none";
                boolean legal = toEmit == null
                        ? rootLegalMoves.isEmpty()
                        : rootLegalMoves.stream().anyMatch(move -> moveToUci(move).equals(moveToUci(toEmit)));
                String mainPv = result == null || result.principalVariation().isEmpty() ? "-"
                        : result.principalVariation().stream().map(this::moveToUci)
                                .collect(java.util.stream.Collectors.joining(","));
                smpDiagnostic("search=" + searchId + " event=bestmove source=" + source
                        + " move=" + (toEmit == null ? "0000" : moveToUci(toEmit))
                        + " legal=" + legal
                        + " main_depth=" + (result == null ? 0 : result.depthReached())
                        + " main_move=" + (result == null || result.bestMove() == null
                                ? "-" : moveToUci(result.bestMove()))
                        + " latest_move=" + (latestIterativeBestMove == null
                                ? "-" : moveToUci(latestIterativeBestMove))
                        + " main_score_cp=" + (result == null ? 0 : result.scoreCp())
                        + " main_nodes=" + (result == null ? 0 : result.nodesVisited())
                        + " main_qnodes=" + (result == null ? 0 : result.quiescenceNodes())
                        + " main_tt_hits=" + (result == null ? 0 : result.ttHits())
                        + " main_pv=" + mainPv
                        + " aborted=" + (result != null && result.aborted())
                        + " helper_submitted=" + helperSubmissions.get()
                        + " helper_started=" + helperStarts.get()
                        + " helper_exited=" + helperExits.get()
                        + " helper_pending=" + (helperSubmissions.get() - helperExits.get())
                        + " helper_exceptions=" + helperExceptions.get()
                        + " abort_ms=" + elapsedMillis(searchStartedNanos, helperAbortNanos.get())
                        + " bestmove_ms=" + elapsedMillis(searchStartedNanos, bestmoveNanos.get())
                        + " search_elapsed_ns=" + (bestmoveNanos.get() - searchStartedNanos));
            }
            for (Future<?> helperFuture : helperFutures) {
                try {
                    helperFuture.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (ExecutionException e) {
                    LOG.error("SMP helper execution failure search={}", searchId, e.getCause());
                }
            }
            if (SMP_DIAGNOSTICS) {
                long drainedNanos = System.nanoTime();
                smpDiagnostic("search=" + searchId + " event=drained"
                        + " helper_submitted=" + helperSubmissions.get()
                        + " helper_started=" + helperStarts.get()
                        + " helper_exited=" + helperExits.get()
                        + " helper_pending=" + (helperSubmissions.get() - helperExits.get())
                        + " helper_exceptions=" + helperExceptions.get()
                        + " drain_elapsed_ns=" + (drainedNanos - searchStartedNanos)
                        + " hashfull=" + sharedTT.hashfull());
            }
            if (TEST_DRAIN_DELAY_MS > 0) {
                try {
                    Thread.sleep(TEST_DRAIN_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void requestStop() {
        stopRequested.set(true);
        synchronized (holdMonitor) {
            holdMonitor.notifyAll();
        }
    }

    /**
     * Stops the running search and waits for its worker, and through it the helpers, to finish. It waits as long as
     * that takes: a command that changes engine state, or starts the next search, must never overlap the old worker,
     * and skipping or dropping it would leave the next command running against stale state. Only quit and end of
     * input use the bounded {@link #stopAndJoinSearch()} on its own.
     */
    private void drainSearch() {
        if (stopAndJoinSearch()) {
            return;
        }
        System.out.println("info string search is slow to stop, waiting for it before continuing");
        System.out.flush();
        Thread current = searchThread;
        boolean interrupted = false;
        while (current.isAlive()) {
            try {
                current.join();
            } catch (InterruptedException e) {
                interrupted = true; // keep waiting: giving up here would let the next command overlap the worker
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Bounded: asks the worker to stop and waits up to 2 s. True if it has finished. */
    private boolean stopAndJoinSearch() {
        Thread current = searchThread;
        if (current == null) return true;
        requestStop();
        try {
            current.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return !current.isAlive();
    }

    private static void smpDiagnostic(String event) {
        System.err.println("SMPDIAG " + event);
    }

    private static long elapsedMillis(long startNanos, long eventNanos) {
        return (eventNanos - startNanos) / 1_000_000L;
    }

    private static String elapsedMillisOrPending(long startNanos, long eventNanos) {
        return eventNanos == 0L ? "pending" : Long.toString(elapsedMillis(startNanos, eventNanos));
    }

    private void runBench(int depth, boolean instrumentationEnabled) {
        runBench(depth, instrumentationEnabled, false);
    }

    private void runBench(int depth, boolean instrumentationEnabled, boolean reportSearchTiming) {
        if (!"NNUE".equals(evalType)) {
            new BenchRunner().run(depth, null, "Classical", instrumentationEnabled, reportSearchTiming);
            return;
        }
        NnueNetwork network = resolveNnueNetworkForSearch();
        if (network == null) {
            // resolveNnueNetworkForSearch() already printed its own fallback info string.
            new BenchRunner().run(depth, null, "Classical", instrumentationEnabled, reportSearchTiming);
            return;
        }
        new BenchRunner().run(depth, () -> new NnueEvaluator(network),
                "NNUE (" + network.networkUuid() + ")", instrumentationEnabled, reportSearchTiming);
    }

    private void printInfoLine(IterationInfo info) {
        if (info.multipv() == 1 && !info.pv().isEmpty()) {
            latestIterativeBestMove = info.pv().get(0);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("info depth ").append(info.depth());
        sb.append(" seldepth ").append(info.seldepth());

        if (multiPV > 1) {
            sb.append(" multipv ").append(info.multipv());
        }

        int score = info.scoreCp();
        if (Math.abs(score) >= MATE_SCORE - MAX_PLY) {
            int mateInPly = MATE_SCORE - Math.abs(score);
            int mateInMoves = (mateInPly + 1) / 2;
            if (score < 0) {
                mateInMoves = -mateInMoves;
            }
            sb.append(" score mate ").append(mateInMoves);
        } else {
            sb.append(" score cp ").append(score);
        }

        sb.append(" nodes ").append(info.nodes());

        long timeMs = info.timeMs();
        long nps = timeMs > 0 ? info.nodes() * 1000L / timeMs : 0;
        sb.append(" nps ").append(nps);
        sb.append(" time ").append(timeMs);
        sb.append(" hashfull ").append(info.hashfull());

        if (!info.pv().isEmpty()) {
            sb.append(" pv");
            for (Move move : info.pv()) {
                sb.append(' ').append(moveToUci(move));
            }
        }

        System.out.println(sb.toString());
    }

    private void emitBestMove(Move move) {
        if (move == null) {
            System.out.println("bestmove 0000");
        } else {
            System.out.println("bestmove " + moveToUci(move));
        }
    }

    private void emitBestMove(Move move, Move ponderMove) {
        if (move == null) {
            System.out.println("bestmove 0000");
            return;
        }
        if (ponderMove != null) {
            System.out.println("bestmove " + moveToUci(move) + " ponder " + moveToUci(ponderMove));
        } else {
            System.out.println("bestmove " + moveToUci(move));
        }
    }

    private Move findLegalMoveByUci(String uciMove) {
        List<Move> legalMoves = new MovesGenerator(board).getActiveMoves(board.getActiveColor());
        for (Move move : legalMoves) {
            if (moveToUci(move).equals(uciMove)) {
                return move;
            }
        }
        return null;
    }

    private String moveToUci(Move move) {
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

    private boolean contains(String[] parts, String token) {
        for (String part : parts) {
            if (token.equals(part)) {
                return true;
            }
        }
        return false;
    }

    private long parseLongArg(String[] parts, String key, long fallback) {
        for (int i = 0; i < parts.length - 1; i++) {
            if (key.equals(parts[i])) {
                try {
                    return Long.parseLong(parts[i + 1]);
                } catch (NumberFormatException ignored) {
                    return fallback;
                }
            }
        }
        return fallback;
    }

    private int parseDepthArg(String[] parts, String key, int fallback) {
        long rawDepth = parseLongArg(parts, key, fallback);
        long clampedDepth = Math.max(1L, Math.min((long) MAX_SEARCH_DEPTH, rawDepth));
        return (int) clampedDepth;
    }

    private List<Move> parseSearchMoves(String[] parts, Board searchBoard) {
        List<Move> result = new ArrayList<>();
        boolean inSearchMoves = false;
        List<Move> legalMoves = new MovesGenerator(searchBoard).getActiveMoves(searchBoard.getActiveColor());

        for (String part : parts) {
            if ("searchmoves".equals(part)) {
                inSearchMoves = true;
                continue;
            }
            if (!inSearchMoves) {
                continue;
            }
            // Stop collecting if we hit another known go sub-command
            if ("wtime".equals(part) || "btime".equals(part) || "winc".equals(part)
                    || "binc".equals(part) || "movestogo".equals(part) || "depth".equals(part)
                    || "nodes".equals(part) || "mate".equals(part) || "movetime".equals(part)
                    || "infinite".equals(part) || "ponder".equals(part)) {
                break;
            }
            for (Move legal : legalMoves) {
                if (moveToUci(legal).equals(part)) {
                    result.add(legal);
                    break;
                }
            }
        }
        return result;
    }
}
