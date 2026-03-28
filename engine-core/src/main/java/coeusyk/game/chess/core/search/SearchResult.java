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
        long futilitySkips
) {
}