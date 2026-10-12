package coeusyk.game.chess.core.selfplay;

import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.search.IterationInfo;
import coeusyk.game.chess.core.selfplay.vspr.SelectionMechanismKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SeededDiversitySelectorTest {

    private static final int MATE_SCORE = 100_000;

    private static IterationInfo candidate(int rank, int scoreCp) {
        Move move = new Move(rank, rank + 8); // distinct legal-looking squares per rank, packed differs
        return new IterationInfo(4, 4, scoreCp, 0, 0, 0, List.of(move), rank, true);
    }

    @Test
    void constructionRejectsInvalidParameters() {
        assertThrows(IllegalArgumentException.class, () -> new SeededDiversitySelector(1, 30, 14.0));
        assertThrows(IllegalArgumentException.class, () -> new SeededDiversitySelector(3, -1, 14.0));
        assertThrows(IllegalArgumentException.class, () -> new SeededDiversitySelector(3, 30, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new SeededDiversitySelector(3, 30, -1.0));
    }

    @Test
    void singleCandidateIsAlwaysDeterministic() {
        SeededDiversitySelector selector = new SeededDiversitySelector(3, 30, 14.0);
        List<IterationInfo> candidates = List.of(candidate(0, 50));

        int packed = selector.select(candidates, 12345L);

        assertEquals(candidates.get(0).pv().get(0).pack(), packed);
        assertEquals(0, selector.lastChosenRank());
    }

    @Test
    void winningMateRank1IsNeverAbandonedForDiversity() {
        SeededDiversitySelector selector = new SeededDiversitySelector(3, 500, 14.0);
        List<IterationInfo> candidates = List.of(
                candidate(0, MATE_SCORE - 3), // mate in 3, winning
                candidate(1, 200),
                candidate(2, 150));

        for (long seed = 0; seed < 50; seed++) {
            int packed = selector.select(candidates, seed);
            assertEquals(candidates.get(0).pv().get(0).pack(), packed);
            assertEquals(0, selector.lastChosenRank());
        }
    }

    @Test
    void losingMateRank1IsAlsoDeterministic() {
        SeededDiversitySelector selector = new SeededDiversitySelector(3, 500, 14.0);
        List<IterationInfo> candidates = List.of(
                candidate(0, -(MATE_SCORE - 4)),
                candidate(1, -300),
                candidate(2, -350));

        int packed = selector.select(candidates, 99L);
        assertEquals(candidates.get(0).pv().get(0).pack(), packed);
    }

    @Test
    void candidateOutsideEligibilityBoundIsNeverSelected() {
        // rank-2 is 200cp worse than rank-1, well outside a 30cp bound -- across many seeds it
        // must never be chosen.
        SeededDiversitySelector selector = new SeededDiversitySelector(3, 30, 14.0);
        List<IterationInfo> candidates = List.of(
                candidate(0, 100),
                candidate(1, -100));
        int excludedPacked = candidates.get(1).pv().get(0).pack();

        for (long seed = 0; seed < 500; seed++) {
            int packed = selector.select(candidates, seed);
            assertNotEquals(excludedPacked, packed);
        }
    }

    @Test
    void mixedScoreKindCandidateIsExcluded() {
        // rank-1 is CP; rank-2 is a mate score -- must never be selected (no CP-equivalent for
        // mate distance is defined).
        SeededDiversitySelector selector = new SeededDiversitySelector(3, 100_000, 14.0);
        List<IterationInfo> candidates = List.of(
                candidate(0, 50),
                candidate(1, MATE_SCORE - 2));
        int mateCandidatePacked = candidates.get(1).pv().get(0).pack();

        for (long seed = 0; seed < 200; seed++) {
            int packed = selector.select(candidates, seed);
            assertNotEquals(mateCandidatePacked, packed);
        }
    }

    @Test
    void sameSeedAndInputAlwaysProducesTheSameMove() {
        SeededDiversitySelector selector = new SeededDiversitySelector(3, 100, 14.0);
        List<IterationInfo> candidates = List.of(
                candidate(0, 40),
                candidate(1, 30),
                candidate(2, 20));

        int first = selector.select(candidates, 7777L);
        int second = new SeededDiversitySelector(3, 100, 14.0).select(candidates, 7777L);
        assertEquals(first, second);
    }

    @Test
    void differentSeedCanAlterSelectionOnAConstructedEligibleSet() {
        SeededDiversitySelector selector = new SeededDiversitySelector(3, 100, 14.0);
        List<IterationInfo> candidates = List.of(
                candidate(0, 40),
                candidate(1, 30),
                candidate(2, 20));

        // Raw seeds are run through SeedDerivation.derive first, matching how GameLoop actually
        // calls this selector -- java.util.Random(seed) correlates nearby/sequential raw seeds
        // on their first draw (see SeedDerivationTest), so testing with 0..199 directly would
        // not exercise realistic seed variety.
        java.util.Set<Integer> seenPacked = new java.util.HashSet<>();
        for (long i = 0; i < 200; i++) {
            long derivedSeed = SeedDerivation.derive(i, 0);
            seenPacked.add(selector.select(candidates, derivedSeed));
        }
        assertTrue(seenPacked.size() > 1, "expected at least two distinct moves across 200 seeds");
    }

    @Test
    void rawSequentialSeedsAreStillDecorrelatedInternally() {
        // Defense in depth: even if a caller passes raw sequential seeds directly (not run
        // through SeedDerivation.derive first), the selector's own internal remix must still
        // decorrelate java.util.Random's first-draw weakness.
        SeededDiversitySelector selector = new SeededDiversitySelector(3, 100, 14.0);
        List<IterationInfo> candidates = List.of(
                candidate(0, 40),
                candidate(1, 30),
                candidate(2, 20));

        java.util.Set<Integer> seenPacked = new java.util.HashSet<>();
        for (long seed = 0; seed < 200; seed++) {
            seenPacked.add(selector.select(candidates, seed));
        }
        assertTrue(seenPacked.size() > 1, "expected at least two distinct moves across 200 raw sequential seeds");
    }

    @Test
    void mechanismIdentityIsNamedNotBestMove() {
        SeededDiversitySelector selector = new SeededDiversitySelector(3, 30, 14.0);
        assertEquals(SelectionMechanismKind.NAMED, selector.mechanismKind());
        assertEquals("seeded-diversity-v1", selector.mechanismName());
        assertEquals(3, selector.requiredCandidateCount());
    }

    @Test
    void bestMoveSelectorIsUnaffectedByNewDefaults() {
        BestMoveSelector best = new BestMoveSelector();
        assertEquals(1, best.requiredCandidateCount());
        assertEquals(SelectionMechanismKind.BEST_MOVE, best.mechanismKind());
        assertNull(best.mechanismName());
        assertTrue(Double.isNaN(best.lastSelectionWeight()));
    }
}
