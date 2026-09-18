package coeusyk.game.chess.core.selfplay.vspr;

import java.util.List;

/**
 * DR-220 section 5: the complete root candidate set persisted for one ply, present only when
 * {@code header.candidatesPersisted()} is true.
 */
public record CandidateSet(long ply, int depth, List<SearchCandidate> candidates) {
}
