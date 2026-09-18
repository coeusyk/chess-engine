package coeusyk.game.chess.core.search;

import coeusyk.game.chess.core.models.Move;

import java.util.List;

/**
 * {@code completed} is true only when this pvIndex's root iteration finished without
 * {@code shouldStopHard} firing mid-search (i.e. {@code !RootResult.aborted}). {@link IterationListener#onIteration}
 * fires before {@code Searcher}'s own abort check, so a listener must read this field rather
 * than assume every callback represents a finished iteration.
 */
public record IterationInfo(
        int depth,
        int seldepth,
        int scoreCp,
        long nodes,
        long timeMs,
        int hashfull,
        List<Move> pv,
        int multipv,
        boolean completed
) {
}
