package coeusyk.game.chess.core.selfplay.vspr;

import java.util.List;

/** DR-220 section 5: one entry in a persisted {@link CandidateSet}. */
public record SearchCandidate(
        int move,
        int rank,
        ScoreKind scoreKind,
        int score,
        List<Integer> pv,
        boolean complete) {
}
