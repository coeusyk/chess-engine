package coeusyk.game.chess.services;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.search.IterationInfo;
import coeusyk.game.chess.core.search.SearchResult;
import coeusyk.game.chess.core.search.Searcher;
import coeusyk.game.chess.core.search.TimeManager;
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

    private final ExecutorService searchExecutor = Executors.newSingleThreadExecutor();
    private final AtomicReference<AtomicBoolean> activeCancellationFlag = new AtomicReference<>();

    /**
     * Validates the FEN synchronously (throws {@code IllegalArgumentException} on invalid input),
     * then opens an SSE stream and runs the search on a background thread.
     *
     * <p>If a search is already in progress, it is cancelled before the new one starts.
     *
     * @return an {@code SseEmitter} that will produce {@code info} and {@code bestmove} events.
     */
    public SseEmitter startAnalysis(String fen, int depth, Integer movetime, int multiPv) {
        // Validate FEN synchronously — throws IllegalArgumentException on bad input.
        Board board = new Board(fen);

        // Cancel the previous search if one is running.
        AtomicBoolean previousFlag = activeCancellationFlag.get();
        if (previousFlag != null) {
            previousFlag.set(true);
        }

        AtomicBoolean cancelled = new AtomicBoolean(false);
        activeCancellationFlag.set(cancelled);

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

                Searcher.IterationListener listener = info -> sendInfoEvent(emitter, info, cancelled);

                SearchResult result;
                if (movetime != null && movetime > 0) {
                    TimeManager tm = new TimeManager();
                    tm.configureMovetime(movetime);
                    result = searcher.searchWithTimeManager(board, depth, tm, cancelled::get, listener);
                } else {
                    result = searcher.iterativeDeepening(board, depth, cancelled::get, cancelled::get, listener);
                }

                if (!cancelled.get()) {
                    Move best = result.bestMove();
                    String bestmoveUci = best != null ? toUci(best) : "0000";
                    emitter.send(SseEmitter.event()
                            .name("bestmove")
                            .data(Map.of("type", "bestmove", "move", bestmoveUci)));
                    emitter.complete();
                }
            } catch (IOException ignored) {
                // Client disconnected mid-stream; search already cancelled by onError/onCompletion.
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data(Map.of("type", "error", "message", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())));
                } catch (IOException ignored) {
                }
                emitter.completeWithError(e);
            }
        }, searchExecutor);

        return emitter;
    }

    private void sendInfoEvent(SseEmitter emitter, IterationInfo info, AtomicBoolean cancelled) {
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

            List<String> pvUci = info.pv().stream()
                    .map(this::toUci)
                    .collect(Collectors.toList());

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
                    "pv", pvUci
            );

            emitter.send(SseEmitter.event().name("info").data(infoEvent));
        } catch (IOException e) {
            cancelled.set(true);
        }
    }

    private String toUci(Move move) {
        StringBuilder sb = new StringBuilder();
        sb.append(squareToUci(move.startSquare));
        sb.append(squareToUci(move.targetSquare));
        if ("promote-q".equals(move.reaction)) sb.append('q');
        else if ("promote-r".equals(move.reaction)) sb.append('r');
        else if ("promote-b".equals(move.reaction)) sb.append('b');
        else if ("promote-n".equals(move.reaction)) sb.append('n');
        return sb.toString();
    }

    private String squareToUci(int square) {
        int file = square % 8;
        int rank = 8 - (square / 8);
        return "" + (char) ('a' + file) + rank;
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
        Board board = new Board(fen);

        int depth = Math.min(rawDepth < 1 ? EVALUATE_DEPTH_CAP : rawDepth, EVALUATE_DEPTH_CAP);
        int multiPv = Math.max(1, rawMultiPv);

        // Cancel any running SSE analysis to free the executor thread immediately.
        AtomicBoolean previousFlag = activeCancellationFlag.get();
        if (previousFlag != null) {
            previousFlag.set(true);
        }

        AtomicBoolean cancelled = new AtomicBoolean(false);

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
        }

        // Commit any events still in the current-depth buffer as the final iteration.
        if (!currentDepthBuffer.isEmpty()) {
            lastCompleteInfos.set(new ArrayList<>(currentDepthBuffer));
        }

        // Build lines from the last completed iteration.
        List<LineInfo> lines = lastCompleteInfos.get().stream()
                .map(info -> new LineInfo(info.multipv(), buildScoreInfo(info.scoreCp()),
                        info.pv().stream().map(this::toUci).collect(Collectors.toList())))
                .collect(Collectors.toList());

        IterationInfo r1 = lastRank1Info.get();
        ScoreInfo rootScore = buildScoreInfo(r1 != null ? r1.scoreCp() : result.scoreCp());

        long nodes = result.nodesVisited();
        long timeMs = r1 != null ? r1.timeMs() : 1L;
        long nps = timeMs > 0 ? (nodes * 1000L) / timeMs : 0;

        List<String> pv = result.principalVariation().stream()
                .map(this::toUci)
                .collect(Collectors.toList());

        String bestMoveUci = result.bestMove() != null ? toUci(result.bestMove()) : "0000";

        return new EvaluateResponse(bestMoveUci, rootScore, result.depthReached(), nodes, nps, pv, lines);
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
