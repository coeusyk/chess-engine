package coeusyk.game.chess.uci;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.movegen.MovesGenerator;
import coeusyk.game.chess.core.search.IterationInfo;
import coeusyk.game.chess.core.search.SearchResult;
import coeusyk.game.chess.core.search.Searcher;
import coeusyk.game.chess.core.search.TimeManager;
import coeusyk.game.chess.core.search.TranspositionTable;
import coeusyk.game.chess.uci.syzygy.OnlineSyzygyProber;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class UciApplication {
    private static final String ENGINE_NAME = "Vex";
    private static final String ENGINE_AUTHOR = "coeusyk";
    private static final int MATE_SCORE = 100_000;
    private static final int MAX_PLY = 128;
    private static final int MAX_SEARCH_DEPTH = 127;

    private Board board = new Board();
    private int multiPV = 1;
    private int hashSizeMb = 64;
    private int threads = 1;
    private long moveOverheadMs = 30;
    private String syzygyPath = "";
    private int syzygyProbeDepth = 1;
    private boolean syzygy50MoveRule = true;

    // Shared transposition table — a single instance sized by Hash setoption,
    // cleared on ucinewgame, and injected into every Searcher (main + helpers).
    private final TranspositionTable sharedTT = new TranspositionTable(64);

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
    private volatile boolean searchRunning = false;
    private volatile Move latestIterativeBestMove;
    @SuppressWarnings("unused") // assigned for future stop-command interrupt support
    private volatile Thread searchThread;

    private static final String[] BENCH_FENS = {
        "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
        "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
        "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
        "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1",
        "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8",
        "r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10"
    };
    private static final int DEFAULT_BENCH_DEPTH = 13;
    private static final int BENCH_HASH_MB = 16;

    public static void main(String[] args) throws IOException {
        UciApplication app = new UciApplication();
        for (int i = 0; i < args.length; i++) {
            if ("--bench".equals(args[i])) {
                int depth = DEFAULT_BENCH_DEPTH;
                if (i + 1 < args.length) {
                    try {
                        depth = Integer.parseInt(args[i + 1]);
                        depth = Math.max(1, Math.min(MAX_SEARCH_DEPTH, depth));
                    } catch (NumberFormatException ignored) {
                    }
                }
                app.runBench(depth);
                return;
            }
        }
        app.run();
    }

    private void run() throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));

        String line;
        while ((line = in.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }

            if ("uci".equals(line)) {
                System.out.println("id name " + ENGINE_NAME);
                System.out.println("id author " + ENGINE_AUTHOR);
                System.out.println("option name Hash type spin default 64 min 1 max 65536");
                System.out.println("option name MultiPV type spin default 1 min 1 max 500");
                System.out.println("option name MoveOverhead type spin default 30 min 0 max 5000");
                System.out.println("option name Threads type spin default 1 min 1 max 512");
                System.out.println("option name SyzygyPath type string default <empty>");
                System.out.println("option name SyzygyProbeDepth type spin default 1 min 0 max 100");
                System.out.println("option name Syzygy50MoveRule type check default true");
                System.out.println("uciok");
            } else if ("isready".equals(line)) {
                System.out.println("readyok");
            } else if ("ucinewgame".equals(line)) {
                stopRequested.set(true);
                board = new Board();
                sharedTT.clear();
            } else if (line.startsWith("position")) {
                stopRequested.set(true);
                handlePosition(line);
            } else if (line.startsWith("setoption")) {
                handleSetOption(line);
            } else if (line.startsWith("go")) {
                handleGo(line);
            } else if (line.startsWith("bench")) {
                String[] benchParts = line.split("\\s+");
                int benchDepth = DEFAULT_BENCH_DEPTH;
                if (benchParts.length > 1) {
                    try {
                        benchDepth = Integer.parseInt(benchParts[1]);
                        benchDepth = Math.max(1, Math.min(MAX_SEARCH_DEPTH, benchDepth));
                    } catch (NumberFormatException ignored) {
                    }
                }
                runBench(benchDepth);
            } else if ("stop".equals(line)) {
                stopRequested.set(true);
                if (!searchRunning) {
                    emitBestMove(latestIterativeBestMove);
                }
            } else if ("ponderhit".equals(line)) {
                // Ponder stub: treat as no-op (full pondering not implemented)
            } else if ("quit".equals(line)) {
                stopRequested.set(true);
                break;
            }

            System.out.flush();
        }
    }

    private void handlePosition(String command) {
        String[] parts = command.split("\\s+");
        if (parts.length < 2) {
            return;
        }

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
                hashSizeMb = Math.max(1, Math.min(65536, value));
                sharedTT.resize(hashSizeMb); // apply immediately to the shared TT
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
        }
        // Unknown options are silently ignored per UCI spec.
    }

    private void handleGo(String command) {
        // If a search is already running, signal it to stop and wait for the worker
        // thread to finish (up to 2 s). This prevents the silent-drop race where
        // the worker's finally block hasn't cleared searchRunning yet by the time
        // the next "go" arrives, which would cause no bestmove to ever be emitted.
        if (searchRunning) {
            stopRequested.set(true);
            Thread current = searchThread;
            if (current != null) {
                try {
                    current.join(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            // If the search is somehow still running after 2 s, bail out.
            if (searchRunning) {
                return;
            }
        }

        stopRequested.set(false);
        latestIterativeBestMove = null;

        String positionSnapshot = board.boardStates.get(board.boardStates.size() - 1);
        Board searchBoard = new Board(positionSnapshot);
        searchBoard.setSearchMode(true);

        // Forced move detection: if exactly one legal move exists, play it
        // immediately without entering the search. Saves clock time in positions
        // where there is no choice (e.g. single legal reply to check).
        List<Move> legalMoves = new MovesGenerator(searchBoard).getActiveMoves(searchBoard.getActiveColor());
        if (legalMoves.size() == 1) {
            emitBestMove(legalMoves.get(0));
            System.out.flush();
            return;
        }
        if (legalMoves.isEmpty()) {
            // Checkmate or stalemate — no move to play.
            System.err.println("warn: handleGo called with 0 legal moves");
            emitBestMove(null);
            System.out.flush();
            return;
        }

        searchRunning = true;
        Thread worker = new Thread(() -> runSearch(command, searchBoard), "uci-search-thread");
        worker.setDaemon(true);
        searchThread = worker;
        worker.start();
    }

    private void runSearch(String command, Board searchBoard) {
        // Per-search abort flag for Lazy SMP helper threads.
        // Separate from the global stopRequested so setting it to true at the end
        // of this search does not bleed into the next search cycle.
        AtomicBoolean helperAbort = new AtomicBoolean(false);
        // Declared outside try so the finally block can emit it after clearing
        // searchRunning. This is critical: emitting bestmove BEFORE setting
        // searchRunning=false causes a race where the GUI sends the next "go"
        // before handleGo sees searchRunning=false, silently dropping the command.
        Move bestMoveToEmit = null;
        try {
            // --- Lazy SMP: launch N-1 helper threads before the main search ---
            // Each helper runs an independent iterative-deepening loop on its own
            // Board copy, all sharing the same TranspositionTable.  Helpers stop
            // when helperAbort or stopRequested becomes true.
            // Only spawn helpers when there are spare cores (AVAILABLE_CORES > 1)
            // to prevent helpers from stealing CPU from the main search thread
            // on single-core or heavily-loaded machines.
            int effectiveHelpers = Math.min(threads - 1, AVAILABLE_CORES - 1);
            if (effectiveHelpers > 0) {
                // Snapshot the current position string from the board so each
                // helper can create an independent Board without sharing state.
                String positionFen = searchBoard.boardStates.get(searchBoard.boardStates.size() - 1);
                for (int i = 1; i <= effectiveHelpers; i++) {
                    // Stagger start depths per spec:
                    //   odd-indexed helpers (1, 3, 5,...) start at depth (i/2)+2
                    //   even-indexed helpers (2, 4, 6,...) start at depth 1
                    // This reduces redundant shallow-depth work and helps threads
                    // diverge earlier, improving TT utilisation.
                    final int startDepth = (i % 2 == 1) ? (i / 2) + 2 : 1;
                    smpExecutor.submit(() -> {
                        try {
                            Searcher helper = new Searcher();
                            helper.setSharedTranspositionTable(sharedTT);
                            Board helperBoard = new Board(positionFen);
                            helperBoard.setSearchMode(true);
                            helper.iterativeDeepening(
                                    helperBoard,
                                    MAX_SEARCH_DEPTH,
                                    startDepth,
                                    () -> helperAbort.get() || stopRequested.get(),
                                    () -> helperAbort.get() || stopRequested.get(),
                                    null
                            );
                        } catch (Exception ignored) {
                            // Helper failures are swallowed; only the main thread result matters.
                        }
                    });
                }
            }

            // --- Main search ---
            String[] parts = command.split("\\s+");
            Searcher searcher = new Searcher();
            searcher.setSharedTranspositionTable(sharedTT);
            if (multiPV > 1) {
                searcher.setMultiPV(multiPV);
            }

            // Configure Syzygy probing
            if (!syzygyPath.isEmpty() && !"<empty>".equals(syzygyPath)) {
                searcher.setSyzygyProber(new OnlineSyzygyProber(syzygy50MoveRule));
                searcher.setSyzygyProbeDepth(syzygyProbeDepth);
                searcher.setSyzygy50MoveRule(syzygy50MoveRule);
            }

            List<Move> searchMovesList = parseSearchMoves(parts, searchBoard);
            if (!searchMovesList.isEmpty()) {
                searcher.setSearchMoves(searchMovesList);
            }

            SearchResult result;

            if (contains(parts, "movetime")) {
                long movetime = parseLongArg(parts, "movetime", 1000L);
                TimeManager manager = new TimeManager();
                manager.setMoveOverheadMs(moveOverheadMs);
                manager.configureMovetime(movetime);
                result = searcher.searchWithTimeManager(
                        searchBoard,
                        64,
                        manager,
                        stopRequested::get,
                        this::printInfoLine
                );
            } else if (contains(parts, "wtime") || contains(parts, "btime")) {
                long wtime = parseLongArg(parts, "wtime", 0L);
                long btime = parseLongArg(parts, "btime", 0L);
                long winc = parseLongArg(parts, "winc", 0L);
                long binc = parseLongArg(parts, "binc", 0L);

                TimeManager manager = new TimeManager();
                manager.setMoveOverheadMs(moveOverheadMs);
                manager.configureClock(searchBoard.getActiveColor(), wtime, btime, winc, binc);
                result = searcher.searchWithTimeManager(
                        searchBoard,
                        64,
                        manager,
                        stopRequested::get,
                        this::printInfoLine
                );
            } else {
                int depth = parseDepthArg(parts, "depth", 4);
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
            // Signal helpers to stop BEFORE clearing searchRunning.
            helperAbort.set(true);
            // Clear searchRunning BEFORE emitting bestmove so handleGo never
            // sees a go command while searchRunning is still true due to the
            // output latency between emitBestMove and the flag flip.
            searchRunning = false;
            searchThread = null;
            // Emit bestmove only after the flag is cleared.
            Move toEmit = bestMoveToEmit != null ? bestMoveToEmit : latestIterativeBestMove;
            emitBestMove(toEmit);
            System.out.flush();
        }
    }

    private void runBench(int depth) {
        Searcher searcher = new Searcher();
        searcher.setTranspositionTableSizeMb(BENCH_HASH_MB);
        long totalNodes = 0;
        long totalQNodes = 0;
        long startMs = System.currentTimeMillis();

        System.out.printf("Bench depth %d | hash %dMB | %d positions%n", depth, BENCH_HASH_MB, BENCH_FENS.length);
        for (int i = 0; i < BENCH_FENS.length; i++) {
            Board benchBoard = new Board(BENCH_FENS[i]);
            benchBoard.setSearchMode(true);
            searcher.clearTranspositionTable();
            long posStart = System.currentTimeMillis();
            SearchResult result = searcher.iterativeDeepening(benchBoard, depth);
            long posMs = Math.max(1, System.currentTimeMillis() - posStart);
            long posNps = result.nodesVisited() * 1000L / posMs;
            double qRatio = result.nodesVisited() > 0
                    ? (double) result.quiescenceNodes() / result.nodesVisited() : 0.0;
            double fmcPct = result.betaCutoffs() > 0
                    ? 100.0 * result.firstMoveCutoffs() / result.betaCutoffs() : 0.0;
            System.out.printf("Position %d: nodes=%d qnodes=%d ms=%d nps=%d tt_hit=%.1f%% q_ratio=%.1fx cutoffs=%d fmc%%=%.1f tt_hits=%d ebf=%.2f%n",
                    i + 1, result.nodesVisited(), result.quiescenceNodes(), posMs, posNps,
                    result.ttHitRate() * 100.0, qRatio,
                    result.betaCutoffs(), fmcPct, result.ttHits(), result.ebf());
            totalNodes += result.nodesVisited();
            totalQNodes += result.quiescenceNodes();
        }

        long elapsedMs = Math.max(1, System.currentTimeMillis() - startMs);
        long nps = totalNodes * 1000L / elapsedMs;
        double totalQRatio = totalNodes > 0 ? (double) totalQNodes / totalNodes : 0.0;
        System.out.printf("Bench: %d nodes %dms %d nps | q_ratio=%.1fx%n",
                totalNodes, elapsedMs, nps, totalQRatio);
        System.out.flush();
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
