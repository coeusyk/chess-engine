package coeusyk.game.chess.controllers;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.io.IOException;

/**
 * Handles IOExceptions that reach the async error dispatch pipeline when an SSE client
 * disconnects mid-stream.  Spring dispatches these through Tomcat's async error path and
 * would otherwise log them at ERROR level via the DispatcherServlet.  Resolving them here
 * causes Spring to treat them as handled (204) and log at DEBUG instead.
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IOException.class)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void handleClientDisconnect(IOException ignored) {
        // Client disconnected during SSE streaming — not an application error.
    }
}
