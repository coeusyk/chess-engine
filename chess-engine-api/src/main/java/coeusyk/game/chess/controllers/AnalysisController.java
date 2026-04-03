package coeusyk.game.chess.controllers;

import coeusyk.game.chess.services.AnalysisService;
import coeusyk.game.chess.services.EvaluateRequest;
import coeusyk.game.chess.services.EvaluateResponse;
import coeusyk.game.chess.services.SearchTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@RestController
@RequestMapping("/api/analysis")
public class AnalysisController {

    private final AnalysisService analysisService;

    public AnalysisController(AnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    /**
     * Opens an SSE stream that emits {@code info} events as the engine searches,
     * and a single {@code bestmove} event when the search finishes.
     *
     * <p>FEN is validated synchronously before the stream is opened; an invalid FEN
     * returns HTTP 400 immediately without opening any SSE connection.
     *
     * @param fen      FEN string of the position to analyze (required)
     * @param depth    maximum search depth (default 20)
     * @param movetime optional time limit in milliseconds; overrides depth if present
     * @param multiPv  number of PV lines per iteration (default 1)
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestParam(name = "fen") String fen,
            @RequestParam(name = "depth", defaultValue = "20") int depth,
            @RequestParam(name = "movetime", required = false) Integer movetime,
            @RequestParam(name = "multiPv", defaultValue = "1") int multiPv) {
        return analysisService.startAnalysis(fen, depth, movetime, multiPv);
    }

    /**
     * Runs a synchronous fixed-depth search and returns the complete evaluation result.
     *
     * <p>Depth is silently clamped to 15. Server-side timeout is 60 seconds (HTTP 504).
     *
     * @param request body containing {@code fen}, optional {@code depth} (default 15),
     *                and optional {@code multiPv} (default 1)
     */
    @PostMapping("/evaluate")
    public EvaluateResponse evaluate(@RequestBody EvaluateRequest request) {
        if (request.fen() == null || request.fen().isBlank()) {
            throw new IllegalArgumentException("fen is required");
        }
        int depth = request.depth() != null ? request.depth() : 15;
        int multiPv = request.multiPv() != null ? request.multiPv() : 1;
        return analysisService.evaluate(request.fen(), depth, multiPv);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(SearchTimeoutException.class)
    public ResponseEntity<Map<String, String>> handleTimeout(SearchTimeoutException ex) {
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(Map.of("error", ex.getMessage()));
    }
}
