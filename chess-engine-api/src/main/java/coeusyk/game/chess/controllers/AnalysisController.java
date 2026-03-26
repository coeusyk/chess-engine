package coeusyk.game.chess.controllers;

import coeusyk.game.chess.services.AnalysisService;
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

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadFen(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }
}
