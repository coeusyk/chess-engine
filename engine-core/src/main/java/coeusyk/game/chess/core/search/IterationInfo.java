package coeusyk.game.chess.core.search;

import coeusyk.game.chess.core.models.Move;

import java.util.List;

public record IterationInfo(
        int depth,
        int seldepth,
        int scoreCp,
        long nodes,
        long timeMs,
        int hashfull,
        List<Move> pv
) {
}
