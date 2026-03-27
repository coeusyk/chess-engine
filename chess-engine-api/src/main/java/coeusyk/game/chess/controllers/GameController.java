package coeusyk.game.chess.controllers;

import coeusyk.game.chess.services.ChessGameService;
import coeusyk.game.chess.services.GameNotFoundException;
import coeusyk.game.chess.services.GameStateResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/game")
public class GameController {

    private final ChessGameService gameService;

    public GameController(ChessGameService gameService) {
        this.gameService = gameService;
    }

    @PostMapping("/create")
    public ResponseEntity<Map<String, String>> createGame() {
        String gameId = gameService.createGame();
        return ResponseEntity.ok(Map.of("gameId", gameId));
    }

    @PostMapping("/{gameId}/reset")
    public ResponseEntity<Void> resetGame(@PathVariable(name = "gameId") String gameId) {
        gameService.resetGame(gameId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{gameId}/load")
    public ResponseEntity<Void> loadFen(
            @PathVariable(name = "gameId") String gameId,
            @RequestBody Map<String, String> body) {
        String fen = body.get("fen");
        if (fen == null || fen.isBlank()) {
            throw new IllegalArgumentException("fen is required");
        }
        gameService.loadFenForGame(gameId, fen);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{gameId}/undo")
    public ResponseEntity<Map<String, Boolean>> undoMove(@PathVariable(name = "gameId") String gameId) {
        boolean moved = gameService.undoMove(gameId);
        return ResponseEntity.ok(Map.of("moved", moved));
    }

    @PostMapping("/{gameId}/redo")
    public ResponseEntity<Map<String, Boolean>> redoMove(@PathVariable(name = "gameId") String gameId) {
        boolean moved = gameService.redoMove(gameId);
        return ResponseEntity.ok(Map.of("moved", moved));
    }

    @GetMapping("/{gameId}/state")
    public ResponseEntity<GameStateResponse> getState(@PathVariable(name = "gameId") String gameId) {
        return ResponseEntity.ok(gameService.getState(gameId));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadFen(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(GameNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(GameNotFoundException ex) {
        return ResponseEntity.status(404).body(Map.of("error", ex.getMessage()));
    }
}
