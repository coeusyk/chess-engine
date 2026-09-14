package coeusyk.game.chess.core.selfplay.vspr;

/** Wire values are this enum's declaration order (DR-220 section 6) -- never reorder. */
public enum TerminationReason {
    CHECKMATE,
    STALEMATE,
    THREEFOLD_REPETITION,
    FIFTY_MOVE_RULE,
    INSUFFICIENT_MATERIAL,
    ADJUDICATED_SCORE,
    MOVE_CAP,
    SEARCH_ABORT_OR_FAILURE
}
