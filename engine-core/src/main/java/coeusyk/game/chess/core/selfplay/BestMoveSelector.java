package coeusyk.game.chess.core.selfplay;

import coeusyk.game.chess.core.search.IterationInfo;

import java.util.List;

/**
 * The deterministic first vertical slice (#221 section 4 / DR-E9 section 7): always chooses
 * rank-0 (the best candidate). Ignores {@code seed} entirely -- deterministic regardless of its
 * value, since there is no randomness to seed here. A future diversity mode is a separate,
 * explicitly-configured {@link MoveSelector} implementation, not a hidden branch in this one.
 */
public final class BestMoveSelector implements MoveSelector {

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
