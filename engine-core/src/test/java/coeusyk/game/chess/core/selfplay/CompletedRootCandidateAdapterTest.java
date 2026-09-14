package coeusyk.game.chess.core.selfplay;

import coeusyk.game.chess.core.search.IterationInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CompletedRootCandidateAdapterTest {

    private static IterationInfo info(int depth, int multipv, boolean completed) {
        return new IterationInfo(depth, 0, 10 * depth, 0, 0, 0, List.of(), multipv, completed);
    }

    @Test
    void completedSingleWidthDepthIsPublished() {
        CompletedRootCandidateAdapter adapter = new CompletedRootCandidateAdapter(1);
        adapter.onIteration(info(4, 1, true));

        assertTrue(adapter.lastCompletedSet().isPresent());
        assertEquals(1, adapter.lastCompletedSet().get().size());
        assertEquals(4, adapter.lastCompletedSet().get().get(0).depth());
    }

    @Test
    void abortedDepthIsDiscarded() {
        CompletedRootCandidateAdapter adapter = new CompletedRootCandidateAdapter(1);
        adapter.onIteration(info(4, 1, false));

        assertTrue(adapter.lastCompletedSet().isEmpty());
    }

    @Test
    void abortedDepthDoesNotOverwritePreviousCompletedDepth() {
        CompletedRootCandidateAdapter adapter = new CompletedRootCandidateAdapter(1);
        adapter.onIteration(info(4, 1, true));
        adapter.onIteration(info(5, 1, false)); // depth 5 aborts

        assertTrue(adapter.lastCompletedSet().isPresent());
        assertEquals(4, adapter.lastCompletedSet().get().get(0).depth());
    }

    @Test
    void allCandidatesInPublishedSetShareTheSameDepth() {
        CompletedRootCandidateAdapter adapter = new CompletedRootCandidateAdapter(3);
        adapter.onIteration(info(6, 1, true));
        adapter.onIteration(info(6, 2, true));
        adapter.onIteration(info(6, 3, true));

        List<IterationInfo> set = adapter.lastCompletedSet().orElseThrow();
        assertEquals(3, set.size());
        assertTrue(set.stream().allMatch(c -> c.depth() == 6));
    }

    @Test
    void rankOrderIsPreserved() {
        CompletedRootCandidateAdapter adapter = new CompletedRootCandidateAdapter(3);
        adapter.onIteration(info(6, 1, true));
        adapter.onIteration(info(6, 2, true));
        adapter.onIteration(info(6, 3, true));

        List<IterationInfo> set = adapter.lastCompletedSet().orElseThrow();
        assertEquals(List.of(1, 2, 3), set.stream().map(IterationInfo::multipv).toList());
    }

    @Test
    void partialWidthNeverPublishes() {
        CompletedRootCandidateAdapter adapter = new CompletedRootCandidateAdapter(3);
        adapter.onIteration(info(6, 1, true));
        adapter.onIteration(info(6, 2, true));
        // pvIndex 2 (multipv=3) never arrives -- this depth's set must never publish.

        assertTrue(adapter.lastCompletedSet().isEmpty());
    }

    @Test
    void resetClearsLastCompletedSet() {
        CompletedRootCandidateAdapter adapter = new CompletedRootCandidateAdapter(1);
        adapter.onIteration(info(4, 1, true));
        assertTrue(adapter.lastCompletedSet().isPresent());

        adapter.reset();
        assertTrue(adapter.lastCompletedSet().isEmpty());
    }

    @Test
    void laterCompletedDepthReplacesEarlierOne() {
        CompletedRootCandidateAdapter adapter = new CompletedRootCandidateAdapter(1);
        adapter.onIteration(info(4, 1, true));
        adapter.onIteration(info(5, 1, true));

        assertEquals(5, adapter.lastCompletedSet().orElseThrow().get(0).depth());
    }
}
