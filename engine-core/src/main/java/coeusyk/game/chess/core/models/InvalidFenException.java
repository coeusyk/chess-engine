package coeusyk.game.chess.core.models;

/** Indicates that a Board could not parse the supplied FEN fields. */
public final class InvalidFenException extends IllegalArgumentException {
    public InvalidFenException(String message) {
        super(message);
    }

    InvalidFenException(String message, Throwable cause) {
        super(message, cause);
    }
}
