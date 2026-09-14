package coeusyk.game.chess.core.selfplay;

import coeusyk.game.chess.core.search.IterationInfo;
import coeusyk.game.chess.core.search.Searcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The smallest safe seam DR-E9 section 5 / #221 section 8 call for: {@link Searcher.IterationListener#onIteration}
 * fires before {@code Searcher}'s own per-iteration abort check, and (pre-#221)
 * {@code IterationInfo} carried no completed/aborted flag at all -- so a listener consuming the
 * callback directly could publish a candidate set for a depth that never actually finished. This
 * adapter buffers one depth's {@code IterationInfo} entries at a time and only ever publishes a
 * full-width (every {@code pvIndex} from 0 to {@code expectedCandidateCount - 1}), all-completed
 * set as {@link #lastCompletedSet()} -- an aborted or partial depth is discarded wholesale, and
 * the previously published set (from an earlier, fully-completed depth) is retained unless a
 * later depth actually completes in full. Never modifies {@code Searcher} itself.
 */
public final class CompletedRootCandidateAdapter implements Searcher.IterationListener {

    private final int expectedCandidateCount;

    private List<IterationInfo> buffer = new ArrayList<>();
    private int bufferDepth = -1;
    private boolean bufferAborted = false;
    private List<IterationInfo> lastCompleted = null;

    public CompletedRootCandidateAdapter(int expectedCandidateCount) {
        if (expectedCandidateCount < 1) {
            throw new IllegalArgumentException("expectedCandidateCount must be >= 1");
        }
        this.expectedCandidateCount = expectedCandidateCount;
    }

    /** Call before every new root search -- clears the last-completed set from a prior position. */
    public void reset() {
        buffer.clear();
        bufferDepth = -1;
        bufferAborted = false;
        lastCompleted = null;
    }

    @Override
    public void onIteration(IterationInfo info) {
        if (info.depth() != bufferDepth) {
            buffer.clear();
            bufferDepth = info.depth();
            bufferAborted = false;
        }
        if (bufferAborted) {
            return; // this depth already discarded -- ignore any further callbacks for it
        }
        if (!info.completed()) {
            bufferAborted = true;
            buffer.clear();
            return;
        }
        buffer.add(info); // pvIndex order, since Searcher's own loop calls this ascending
        if (buffer.size() == expectedCandidateCount) {
            lastCompleted = List.copyOf(buffer);
        }
    }

    /** The last fully-completed depth's candidate set, in ascending rank order, if any. */
    public Optional<List<IterationInfo>> lastCompletedSet() {
        return Optional.ofNullable(lastCompleted);
    }
}
