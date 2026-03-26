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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
}
