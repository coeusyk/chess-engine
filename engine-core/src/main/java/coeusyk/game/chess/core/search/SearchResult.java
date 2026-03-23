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
        boolean aborted
) {
}