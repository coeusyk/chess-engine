package coeusyk.game.chess.services;

/** The search was cancelled (superseded by a newer analysis request) before it finished. */
public class SearchAbortedException extends RuntimeException {
    public SearchAbortedException(String message) {
        super(message);
    }
}
