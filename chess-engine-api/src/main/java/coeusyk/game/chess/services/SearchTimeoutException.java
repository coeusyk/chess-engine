package coeusyk.game.chess.services;

public class SearchTimeoutException extends RuntimeException {
    public SearchTimeoutException(String message) {
        super(message);
    }
}
