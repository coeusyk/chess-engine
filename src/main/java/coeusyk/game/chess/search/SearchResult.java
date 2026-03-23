package coeusyk.game.chess.search;

import coeusyk.game.chess.models.Move;


/** Carries the result of a completed or aborted iterative-deepening search. */
public class SearchResult {

    /** The best move found. May be {@code null} if the search was aborted before the first iteration completed. */
    public final Move bestMove;

    /** Score in centipawns from the side-to-move's perspective at the depth where {@code bestMove} was found. */
    public final int score;

    /** The deepest fully-completed iteration depth. */
    public final int completedDepth;

    /** Total number of nodes visited during the search. */
    public final int nodesSearched;

    public SearchResult(Move bestMove, int score, int completedDepth, int nodesSearched) {
        this.bestMove = bestMove;
        this.score = score;
        this.completedDepth = completedDepth;
        this.nodesSearched = nodesSearched;
    }
}
