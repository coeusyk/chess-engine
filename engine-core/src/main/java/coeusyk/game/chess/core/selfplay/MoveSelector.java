package coeusyk.game.chess.core.selfplay;

import coeusyk.game.chess.core.search.IterationInfo;

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
}
