package coeusyk.game.chess.core.selfplay.vspr;

import java.util.List;
import java.util.OptionalLong;

/**
 * DR-220 section 4/5: one {@code GameFrame} -- the full record of one self-play game.
 * {@code candidateSets} is empty (never null) when the owning file's
 * {@code header.candidatesPersisted()} is false; the codec never leaves it null.
 */
public record GameFrame(
        long gameId,
        OptionalLong gameSeed,
        GameOutcome gameOutcome,
        TerminationReason terminationReason,
        OutcomePerspective outcomePerspective,
        List<PlayedMoveDecision> playedMoves,
        List<TrainingSample> samples,
        List<CandidateSet> candidateSets) {
}
