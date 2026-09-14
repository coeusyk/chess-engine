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

    // #222 regression: a position with fewer legal moves than the requested multiPV width
    // completes cleanly (no abort) but never reaches expectedCandidateCount -- discovered when
    // E-14 first requested multiPV > 1 (#221's multiPV=1 usage could never hit this, since every
    // non-terminal position has at least one legal move).

    @Test
    void finishFlushesAFinalDepthThatNeverReachedExpectedWidth() {
        CompletedRootCandidateAdapter adapter = new CompletedRootCandidateAdapter(3);
        adapter.onIteration(info(6, 1, true));
        adapter.onIteration(info(6, 2, true));
        // pv3 never arrives -- Searcher's own multiPV loop stopped because only 2 legal moves
        // exist, not because of an abort. No further onIteration call happens at all (this was
        // the final depth) -- only finish() can flush it.
        assertTrue(adapter.lastCompletedSet().isEmpty(), "must not publish before finish()");

        adapter.finish();

        List<IterationInfo> set = adapter.lastCompletedSet().orElseThrow();
        assertEquals(2, set.size());
        assertTrue(set.stream().allMatch(c -> c.depth() == 6));
    }

    @Test
    void depthTransitionFlushesAnUnderWidthNonFinalDepthAutomatically() {
        CompletedRootCandidateAdapter adapter = new CompletedRootCandidateAdapter(3);
        adapter.onIteration(info(5, 1, true));
        adapter.onIteration(info(5, 2, true));
        // depth 5 never reaches width 3 (only 2 legal moves), but depth 6 starting is itself
        // proof depth 5's loop finished cleanly -- no finish() call needed for this case.
        adapter.onIteration(info(6, 1, true));

        List<IterationInfo> set = adapter.lastCompletedSet().orElseThrow();
        // depth 6 is still mid-flight (only pv1 arrived) -- the *previous* completed depth (5,
        // width 2) is what's published until depth 6 itself completes or is flushed.
        assertEquals(2, set.size());
        assertTrue(set.stream().allMatch(c -> c.depth() == 5));
    }

    @Test
    void finishAfterAnAbortedFinalDepthDoesNotResurrectItOrOverwritePrevious() {
        CompletedRootCandidateAdapter adapter = new CompletedRootCandidateAdapter(3);
        adapter.onIteration(info(5, 1, true));
        adapter.onIteration(info(5, 2, true));
        adapter.onIteration(info(5, 3, true)); // depth 5 completes at full width, auto-published
        adapter.onIteration(info(6, 1, false)); // depth 6 aborts immediately

        adapter.finish();

        List<IterationInfo> set = adapter.lastCompletedSet().orElseThrow();
        assertEquals(3, set.size());
        assertTrue(set.stream().allMatch(c -> c.depth() == 5), "aborted depth 6 must not surface, even via finish()");
    }

    @Test
    void finishOnAnEmptyBufferIsANoOp() {
        CompletedRootCandidateAdapter adapter = new CompletedRootCandidateAdapter(3);
        adapter.finish();
        assertTrue(adapter.lastCompletedSet().isEmpty());
    }

    @Test
    void finishIsIdempotentAfterAnExactWidthPublish() {
        CompletedRootCandidateAdapter adapter = new CompletedRootCandidateAdapter(1);
        adapter.onIteration(info(4, 1, true));
        adapter.finish();
        adapter.finish();

        List<IterationInfo> set = adapter.lastCompletedSet().orElseThrow();
        assertEquals(1, set.size());
        assertEquals(4, set.get(0).depth());
    }
}
