package coeusyk.game.chess.controllers;

import coeusyk.game.chess.core.models.InvalidFenException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.io.IOException;
import java.util.Map;

/**
 * Maps invalid FEN input to HTTP 400. Also handles IOExceptions that reach the
 * async error dispatch pipeline when an SSE client
 * disconnects mid-stream.  Spring dispatches these through Tomcat's async error path and
 * would otherwise log them at ERROR level via the DispatcherServlet.  Resolving them here
 * causes Spring to treat them as handled (204) and log at DEBUG instead.
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InvalidFenException.class)
    public ResponseEntity<Map<String, String>> handleInvalidFen(InvalidFenException ex) {
        return ResponseEntity.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IOException.class)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void handleClientDisconnect(IOException ignored) {
        // Client disconnected during SSE streaming — not an application error.
    }
}
