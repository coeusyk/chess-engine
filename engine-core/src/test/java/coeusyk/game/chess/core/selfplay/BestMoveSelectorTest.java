package coeusyk.game.chess.core.selfplay;

import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.search.IterationInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** E-15 (#223): the matched-control-arm multiPV-width seam. Move selection itself is unchanged
 * behavior, already covered by {@link GameLoopTest}/{@link GameLoopDiversityTest} -- this class
 * covers only the new constructor/{@code requiredCandidateCount()} seam. */
class BestMoveSelectorTest {

    private static IterationInfo candidate(int rank, int scoreCp) {
        Move move = new Move(rank, rank + 8);
        return new IterationInfo(4, 4, scoreCp, 0, 0, 0, List.of(move), rank, true);
    }

    @Test
    void noArgConstructorStillRequestsOneCandidate() {
        assertEquals(1, new BestMoveSelector().requiredCandidateCount());
    }

    @Test
    void explicitConstructorRequestsTheGivenWidth() {
        assertEquals(3, new BestMoveSelector(3).requiredCandidateCount());
        assertEquals(1, new BestMoveSelector(1).requiredCandidateCount());
        assertEquals(8, new BestMoveSelector(8).requiredCandidateCount());
    }

    @Test
    void invalidWidthsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new BestMoveSelector(0));
        assertThrows(IllegalArgumentException.class, () -> new BestMoveSelector(-1));
    }

    @Test
    void bothConstructorsAlwaysChooseRankZeroRegardlessOfCandidateSetWidth() {
        List<IterationInfo> three = List.of(candidate(0, 40), candidate(1, 30), candidate(2, 20));
        int rank0Packed = three.get(0).pv().get(0).pack();

        assertEquals(rank0Packed, new BestMoveSelector().select(List.of(three.get(0)), 1L));
        assertEquals(rank0Packed, new BestMoveSelector(3).select(three, 1L));
        assertEquals(rank0Packed, new BestMoveSelector(3).select(three, 999L)); // seed still ignored
    }

    @Test
    void mechanismIdentityUnaffectedByWidth() {
        BestMoveSelector wide = new BestMoveSelector(3);
        assertEquals(coeusyk.game.chess.core.selfplay.vspr.SelectionMechanismKind.BEST_MOVE, wide.mechanismKind());
        assertNull(wide.mechanismName());
        assertTrue(Double.isNaN(wide.lastSelectionWeight()));
    }
}
