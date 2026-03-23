package coeusyk.game.chess.core.search;

import coeusyk.game.chess.core.models.Move;

public record SearchResult(
        Move bestMove,
        int scoreCp,
        int depthReached,
        long nodesVisited,
        long leafNodes,
        boolean aborted
) {
}