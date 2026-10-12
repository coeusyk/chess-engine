package coeusyk.game.chess.core.search;

import coeusyk.game.chess.core.models.Move;

import java.util.List;

public record SearchResult(
        Move bestMove,
        int scoreCp,
        int depthReached,
        List<Move> principalVariation,
        long nodesVisited,
        long leafNodes,
        long quiescenceNodes,
        double ttHitRate,
        long betaCutoffs,
        long firstMoveCutoffs,
        long ttHits,
        double ebf,
        boolean aborted,
        long nullMoveCutoffs,
        long lmrApplications,
        long futilitySkips,
        long deltaPruningSkips,
        // Issue #216: search-time instrumentation, needed to distinguish
        // whether NNUE changes search behavior (not just evaluator accuracy).
        // evalNanos/accumulatorNanos are 0 unless Searcher.setInstrumentationEnabled(true)
        // was called; accumulatorNanos is additionally always 0 for a non-NNUE evaluator.
        long evalNanos,
        long accumulatorNanos,
        long pvNodeEvals,
        long cutNodeEvals
) {
    /** Returns the ponder move (PV[1]), or {@code null} if the PV has fewer than 2 moves. */
    public Move ponderMove() {
        List<Move> pv = principalVariation();
        return pv != null && pv.size() > 1 ? pv.get(1) : null;
    }
}