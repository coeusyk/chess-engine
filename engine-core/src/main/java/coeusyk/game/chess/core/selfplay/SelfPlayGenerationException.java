package coeusyk.game.chess.core.selfplay;

/**
 * A hard, fail-closed failure during Stage-3 generation (DR-E12-stage3-generator-selection.md
 * section 11's HARD CORRECTNESS FAILURE category) -- illegal returned move, non-finite/corrupted
 * search score, a board left mutated after search, history-capacity danger, or a VSPR encode
 * failure. Always aborts the whole pilot; never caught to skip a bad game, record a draw, or
 * continue best-effort (#221 section 12).
 */
public final class SelfPlayGenerationException extends RuntimeException {

    public enum Reason {
        NETWORK_IDENTITY_MISMATCH,
        ENGINE_LOAD_FAILURE,
        ILLEGAL_MOVE,
        NON_FINITE_SCORE,
        BOARD_INVARIANT_VIOLATION,
        HISTORY_CAPACITY_DANGER,
        NO_COMPLETED_CANDIDATE,
        VSPR_ENCODE_FAILURE,
        SEARCH_INVARIANT_FAILURE
    }

    private final Reason reason;

    public SelfPlayGenerationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public SelfPlayGenerationException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
