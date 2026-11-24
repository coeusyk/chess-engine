package coeusyk.game.chess.services;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.notation.SanConverter;
import coeusyk.game.chess.core.search.IterationInfo;
import coeusyk.game.chess.core.search.SearchResult;
import coeusyk.game.chess.core.search.Searcher;
import coeusyk.game.chess.core.search.TimeManager;
import coeusyk.game.chess.utils.KingValidator;
import coeusyk.game.chess.utils.UciConverter;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Service
public class AnalysisService {

    // Scores at or above this threshold (in absolute value) are mate scores.
    private static final int MATE_SCORE = 100_000;
    private static final int MAX_PLY = 128;
    private static final int MATE_THRESHOLD = MATE_SCORE - MAX_PLY;

    // Daemon so an in-flight search can never keep the JVM alive; shutdown() below also cancels it.
    private final ExecutorService searchExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "analysis-search");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicReference<AtomicBoolean> activeCancellationFlag = new AtomicReference<>();

    @PreDestroy
    void shutdown() {
        AtomicBoolean running = activeCancellationFlag.getAndSet(null);
        if (running != null) {
            running.set(true);
        }
        searchExecutor.shutdownNow();
    }

    /**
     * Validates the FEN synchronously (throws {@code IllegalArgumentException} on invalid input),
     * then opens an SSE stream and runs the search on a background thread.
     *
     * <p>If a search is already in progress, it is cancelled before the new one starts.
     *
     * @return an {@code SseEmitter} that will produce {@code info} and {@code bestmove} events.
     */
    @SuppressWarnings("null")
    public SseEmitter startAnalysis(String fen, int depth, Integer movetime, int multiPv) {
        // Validate FEN synchronously — throws IllegalArgumentException on bad input.
        Board board = KingValidator.requireKings(new Board(fen));

        // Take ownership of the active slot and cancel the previous search in one atomic step.
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicBoolean previousFlag = activeCancellationFlag.getAndSet(cancelled);
        if (previousFlag != null) {
            previousFlag.set(true);
        }

        SseEmitter emitter = new SseEmitter(300_000L);

        Runnable cleanup = () -> {
            cancelled.set(true);
            activeCancellationFlag.compareAndSet(cancelled, null);
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        CompletableFuture.runAsync(() -> {
            try {
                Searcher searcher = new Searcher();
                searcher.setMultiPV(Math.max(1, multiPv));

                Searcher.IterationListener listener = info -> sendInfoEvent(emitter, info, cancelled, fen);

                SearchResult result;
                if (movetime != null && movetime > 0) {
                    TimeManager tm = new TimeManager();
                    tm.configureMovetime(movetime);
                    result = searcher.searchWithTimeManager(board, depth, tm, cancelled::get, listener);
                } else {
                    result = searcher.iterativeDeepening(board, depth, cancelled::get, cancelled::get, listener);
                }

                var best = result.bestMove();
                String bestUci = best != null ? UciConverter.toUci(best) : "0000";
                String bestSan = best != null ? SanConverter.toSan(best, new Board(fen)) : null;
                // Same monitor as sendInfoEvent. The Tomcat timeout thread runs cleanup() without it, so
                // re-check the flag inside the lock and never send into an emitter that already completed.
                synchronized (emitter) {
                    if (!cancelled.get()) {
                        emitter.send(SseEmitter.event()
                                .name("bestmove")
                                .data(Map.of("type", "bestmove", "move", bestUci, "san", bestSan != null ? bestSan : bestUci)));
                        emitter.complete();
                    } else {
                        completeQuietly(emitter);
                    }
                }
            } catch (IOException ignored) {
                // Client disconnected mid-stream; search already cancelled by onError/onCompletion.
            } catch (Exception e) {
                synchronized (emitter) {
                    if (!cancelled.get()) {
                        try {
                            emitter.send(SseEmitter.event()
                                    .name("error")
                                    .data(Map.of("type", "error", "message", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())));
                        } catch (IOException ignored) {
                        }
                        emitter.completeWithError(e);
                    } else {
                        completeQuietly(emitter);
                    }
                }
            }
        }, searchExecutor);

        return emitter;
    }

    // A superseded stream would otherwise stay open until the 300 s emitter timeout with no terminal
    // event. For a stream the client or the container already closed this is a no-op.
    private static void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (IllegalStateException ignored) {
        }
    }

    @SuppressWarnings("null")
    private void sendInfoEvent(SseEmitter emitter, IterationInfo info, AtomicBoolean cancelled, String fen) {
        if (cancelled.get()) return;
        synchronized (emitter) {
            if (cancelled.get()) return;
            try {
                int scoreCp = info.scoreCp();
                boolean isMate = Math.abs(scoreCp) >= MATE_THRESHOLD;

                Map<String, Object> scoreMap;
                if (isMate) {
                    int mateInMoves = scoreCp > 0
                            ? (MATE_SCORE - scoreCp + 1) / 2
                            : -((MATE_SCORE + scoreCp + 1) / 2);
                    scoreMap = Map.of("type", "mate", "value", mateInMoves);
                } else {
                    scoreMap = Map.of("type", "cp", "value", scoreCp);
                }

                long timeMs = info.timeMs();
                long nps = timeMs > 0 ? (info.nodes() * 1000L) / timeMs : 0;

                List<MoveDto> pvDtos = pvToMoveDtos(info.pv(), fen);

                Map<String, Object> infoEvent = Map.of(
                        "type", "info",
                        "depth", info.depth(),
                        "seldepth", info.seldepth(),
                        "multiPv", info.multipv(),
                        "score", scoreMap,
                        "nodes", info.nodes(),
                        "nps", nps,
                        "time", timeMs,
                        "hashfull", info.hashfull(),
                        "pv", pvDtos
                );

                emitter.send(SseEmitter.event().name("info").data(infoEvent));
            } catch (IOException e) {
                cancelled.set(true);
            }
        }
    }

    private static List<MoveDto> pvToMoveDtos(List<Move> pv, String fen) {
        Board pvBoard = new Board(fen);
        List<MoveDto> result = new ArrayList<>();
        for (Move move : pv) {
            String uci = UciConverter.toUci(move);
            String san = SanConverter.toSan(move, pvBoard);
            result.add(new MoveDto(uci, san != null ? san : uci));
            pvBoard.makeMove(move);
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Synchronous evaluate endpoint (POST /api/analysis/evaluate)
    // -------------------------------------------------------------------------

    private static final int EVALUATE_DEPTH_CAP = 15;
    private static final long EVALUATE_TIMEOUT_SECONDS = 60;

    /**
     * Runs a fixed-depth search synchronously and returns a complete evaluation result.
     *
     * <p>Depth is silently clamped to {@value #EVALUATE_DEPTH_CAP}. If the search
     * exceeds {@value #EVALUATE_TIMEOUT_SECONDS} seconds, {@link SearchTimeoutException} is thrown.
     *
     * <p>Any in-progress SSE analysis is cancelled before this search starts.
     *
     * @throws IllegalArgumentException on invalid FEN
     * @throws SearchTimeoutException   when the 60-second server-side limit is exceeded
     */
    public EvaluateResponse evaluate(String fen, int rawDepth, int rawMultiPv) {
        // Validate FEN synchronously — throws IllegalArgumentException on bad input.
        Board board = KingValidator.requireKings(new Board(fen));

        int depth = Math.min(rawDepth < 1 ? EVALUATE_DEPTH_CAP : rawDepth, EVALUATE_DEPTH_CAP);
        int multiPv = Math.max(1, rawMultiPv);

        // Register this evaluate search so a subsequent request can cancel it, and cancel any running
        // analysis to free the executor thread immediately, in one atomic step.
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicBoolean previousFlag = activeCancellationFlag.getAndSet(cancelled);
        if (previousFlag != null) {
            previousFlag.set(true);
        }

        // Collect iteration info events to populate MultiPV lines in the response.
        List<IterationInfo> currentDepthBuffer = new ArrayList<>();
        AtomicReference<List<IterationInfo>> lastCompleteInfos = new AtomicReference<>(List.of());
        AtomicReference<IterationInfo> lastRank1Info = new AtomicReference<>();

        Searcher searcher = new Searcher();
        searcher.setMultiPV(multiPv);

        Searcher.IterationListener listener = info -> {
            if (info.multipv() == 1 && !currentDepthBuffer.isEmpty()) {
                // A new depth iteration is starting — commit the previous one as complete.
                lastCompleteInfos.set(new ArrayList<>(currentDepthBuffer));
                currentDepthBuffer.clear();
            }
            currentDepthBuffer.add(info);
            if (info.multipv() == 1) {
                lastRank1Info.set(info);
            }
        };

        CompletableFuture<SearchResult> future = CompletableFuture.supplyAsync(
                () -> searcher.iterativeDeepening(board, depth, cancelled::get, cancelled::get, listener),
                searchExecutor);

        SearchResult result;
        try {
            result = future.get(EVALUATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            cancelled.set(true);
            throw new SearchTimeoutException("Analysis exceeded the " + EVALUATE_TIMEOUT_SECONDS + "-second time limit");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelled.set(true);
            throw new RuntimeException("Analysis interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IllegalArgumentException iae) throw iae;
            throw new RuntimeException("Analysis failed: " + cause.getMessage(), cause);
        } finally {
            // Release the slot so a finished search is not left registered as the active one.
            activeCancellationFlag.compareAndSet(cancelled, null);
        }

        // A cancelled search returns a truncated result. Report it as superseded instead of HTTP 200.
        if (cancelled.get()) {
            throw new SearchAbortedException("Analysis was superseded by a newer request");
        }

        // Commit any events still in the current-depth buffer as the final iteration.
        if (!currentDepthBuffer.isEmpty()) {
            lastCompleteInfos.set(new ArrayList<>(currentDepthBuffer));
        }

        // Build lines from the last completed iteration.
        List<LineInfo> lines = lastCompleteInfos.get().stream()
                .map(info -> new LineInfo(info.multipv(), buildScoreInfo(info.scoreCp()),
                        pvToMoveDtos(info.pv(), fen)))
                .collect(Collectors.toList());

        IterationInfo r1 = lastRank1Info.get();
        ScoreInfo rootScore = buildScoreInfo(r1 != null ? r1.scoreCp() : result.scoreCp());

        long nodes = result.nodesVisited();
        long timeMs = r1 != null ? r1.timeMs() : 1L;
        long nps = timeMs > 0 ? (nodes * 1000L) / timeMs : 0;

        List<MoveDto> pvDtos = pvToMoveDtos(result.principalVariation(), fen);

        var best = result.bestMove();
        String bestUci = best != null ? UciConverter.toUci(best) : "0000";
        String bestSan = best != null ? SanConverter.toSan(best, new Board(fen)) : null;
        MoveDto bestMoveDto = new MoveDto(bestUci, bestSan != null ? bestSan : bestUci);

        return new EvaluateResponse(bestMoveDto, rootScore, result.depthReached(), nodes, nps, pvDtos, lines);
    }

    private ScoreInfo buildScoreInfo(int scoreCp) {
        boolean isMate = Math.abs(scoreCp) >= MATE_THRESHOLD;
        if (isMate) {
            int mateInMoves = scoreCp > 0
                    ? (MATE_SCORE - scoreCp + 1) / 2
                    : -((MATE_SCORE + scoreCp + 1) / 2);
            return new ScoreInfo("mate", mateInMoves);
        }
        return new ScoreInfo("cp", scoreCp);
    }
}
