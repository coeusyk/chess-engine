package coeusyk.game.chess.utils;

import coeusyk.game.chess.models.Move;


public class SearchResult {
    public Move bestMove;
    public int nodes;
    public int qNodes;

    public SearchResult(Move bestMove, int nodes, int qNodes) {
        this.bestMove = bestMove;
        this.nodes = nodes;
        this.qNodes = qNodes;
    }
}
