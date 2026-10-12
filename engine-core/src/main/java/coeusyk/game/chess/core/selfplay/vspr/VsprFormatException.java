package coeusyk.game.chess.core.selfplay.vspr;

import java.io.IOException;

/**
 * Thrown when a VSPR stream is malformed, truncated, or violates a DR-220 bound or validity
 * rule -- distinct from a plain {@link java.io.IOException} propagating from the underlying
 * stream (a real I/O failure), so a caller (e.g. a future #210 ingester) can tell "this data is
 * bad" from "the read itself failed" without inspecting message text.
 */
public final class VsprFormatException extends IOException {

    /** Which class of DR-220 violation this is, for a caller that wants to branch on it. */
    public enum Reason {
        BAD_MAGIC,
        UNSUPPORTED_VERSION,
        TRUNCATED,
        CRC_MISMATCH,
        BOUND_VIOLATION,
        INVALID_ENUM,
        INVALID_PAIRING,
        STRUCTURAL
    }

    private final Reason reason;

    public VsprFormatException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
