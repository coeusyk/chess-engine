package coeusyk.game.chess.core.selfplay;

import coeusyk.game.chess.core.search.IterationInfo;
import coeusyk.game.chess.core.selfplay.vspr.SelectionMechanismKind;

import java.util.List;

/**
 * Chooses one move from a completed root candidate set (DR-E9 section 7: randomized
 * move-selection semantics are deferred configuration, not decided by this initial vertical
 * slice). {@code candidates} is always non-empty, same-depth, and in ascending rank order
 * (guaranteed by {@link CompletedRootCandidateAdapter}).
 */
public interface MoveSelector {
    /** Returns the selected candidate's packed move (see {@code Move.of}/{@code Move.pack}). */
    int select(List<IterationInfo> candidates, long seed);

    /** How many ranked root candidates {@link GameLoop} must request from {@code Searcher}
     * (its multiPV setting) for this selector to have what it needs. Deterministic rank-0-only
     * selectors need nothing more than the best move. */
    default int requiredCandidateCount() {
        return 1;
    }

    /** Recorded on every {@code PlayedMoveDecision} this selector produces (DR-220 §5). */
    default SelectionMechanismKind mechanismKind() {
        return SelectionMechanismKind.BEST_MOVE;
    }

    /** Required when {@link #mechanismKind()} is {@code NAMED}; {@code null} otherwise. */
    default String mechanismName() {
        return null;
    }

    /** The weight assigned to the most recently selected candidate, or {@code NaN} if this
     * selector is not weighted (#222 section 8's quality-cost diagnostics). */
    default double lastSelectionWeight() {
        return Double.NaN;
    }

    /** The normalized probability of the most recently selected candidate, or {@code NaN} if
     * this selector is not weighted. */
    default double lastSelectionProbability() {
        return Double.NaN;
    }
}
