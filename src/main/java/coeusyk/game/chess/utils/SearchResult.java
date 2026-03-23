package coeusyk.game.chess.utils;

import coeusyk.game.chess.models.Move;

import java.util.List;


public class SearchResult {
    public final Move bestMove;
    public final int score;
    public final int depth;
    public final List<Move> pv;
    public final String uciInfo;

    public SearchResult(Move bestMove, int score, int depth, List<Move> pv, String uciInfo) {
        this.bestMove = bestMove;
        this.score = score;
        this.depth = depth;
        this.pv = pv;
        this.uciInfo = uciInfo;
    }
}
