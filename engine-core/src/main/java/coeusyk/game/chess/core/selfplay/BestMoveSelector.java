package coeusyk.game.chess.core.selfplay;

import coeusyk.game.chess.core.search.IterationInfo;

import java.util.List;

/**
 * The deterministic first vertical slice (#221 section 4 / DR-E9 section 7): always chooses
 * rank-0 (the best candidate). Ignores {@code seed} entirely -- deterministic regardless of its
 * value, since there is no randomness to seed here. A future diversity mode is a separate,
 * explicitly-configured {@link MoveSelector} implementation, not a hidden branch in this one.
 *
 * <p>{@link #requiredCandidateCount()} defaults to 1 (the no-arg constructor, #221's original
 * behavior, byte-for-byte unchanged). E-15 (issue #223) needs a <em>matched</em> control arm that
 * runs {@code Searcher} at the same multiPV width as {@link SeededDiversitySelector} -- otherwise
 * comparing "old multiPV=1 corpus" against "new multiPV=3 diverse corpus" would confound MultiPV-
 * induced search/TT effects (DR-E14 section 2.1) with the selector policy itself
 * (DR-E15 section 2). The {@link #BestMoveSelector(int)} constructor exists only to request that
 * wider candidate set from {@code GameLoop}/{@code Searcher} -- move selection is unaffected:
 * this class always picks rank-0 regardless of how many candidates it was given.
 */
public final class BestMoveSelector implements MoveSelector {

    private final int requiredCandidateCount;

    public BestMoveSelector() {
        this(1);
    }

    /** @param requiredCandidateCount how many ranked root candidates {@code GameLoop} should
     * request from {@code Searcher} (its multiPV setting) -- move selection still always picks
     * rank-0; this only controls candidate-set width. Must be &gt;= 1. Bounded consistently with
     * {@link SeededDiversitySelector}'s own {@code maxRank} validation (that class also requires
     * &gt;= 1, there via &gt;= 2 since a stochastic selector needs at least one alternative to
     * rank-1) -- no separate {@code Searcher}-side multiPV ceiling exists to validate against. */
    public BestMoveSelector(int requiredCandidateCount) {
        if (requiredCandidateCount < 1) {
            throw new IllegalArgumentException("requiredCandidateCount must be >= 1");
        }
        this.requiredCandidateCount = requiredCandidateCount;
    }

    @Override
    public int requiredCandidateCount() {
        return requiredCandidateCount;
    }

    @Override
    public int select(List<IterationInfo> candidates, long seed) {
        if (candidates == null || candidates.isEmpty()) {
            throw new SelfPlayGenerationException(
                    SelfPlayGenerationException.Reason.NO_COMPLETED_CANDIDATE,
                    "BestMoveSelector received an empty candidate list");
        }
        IterationInfo best = candidates.get(0); // rank 0, ascending order per CompletedRootCandidateAdapter
        if (best.pv() == null || best.pv().isEmpty()) {
            throw new SelfPlayGenerationException(
                    SelfPlayGenerationException.Reason.NO_COMPLETED_CANDIDATE,
                    "rank-0 candidate at depth " + best.depth() + " has an empty principal variation");
        }
        return best.pv().get(0).pack();
    }
}
