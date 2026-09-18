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
 *
 * <p><strong>A depth can legitimately complete with fewer than {@code expectedCandidateCount}
 * candidates</strong> -- a position with fewer legal moves than the requested multiPV width
 * simply has {@code Searcher}'s own multiPV loop stop calling this listener early for that
 * depth (its {@code iteration.bestMove == null} branch), with no abort involved. This is the
 * common case whenever a caller requests multiPV &gt; 1 on a position in check or with few legal
 * replies -- discovered by #222 (the first caller to ever request multiPV &gt; 1; #221's
 * multiPV=1 usage could never hit it, since every non-terminal position has at least one legal
 * move). {@link #finish()} and the depth-transition handling in {@link #onIteration} both flush
 * whatever the previous depth actually collected, as long as it was not aborted, even if that is
 * fewer than {@code expectedCandidateCount} -- callers must call {@link #finish()} once after
 * their search call returns, so the final depth's buffer (which never sees a "next depth"
 * transition) is not silently dropped.
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
            flushIfComplete(); // the previous depth's own loop has definitely finished by now
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

    /** Call once after the enclosing search call returns. The final depth reached never gets a
     * "next depth" {@link #onIteration} transition to flush it, so without this call a
     * fewer-legal-moves-than-requested final depth would be silently dropped. Idempotent and
     * harmless to call when the buffer already published via the exact-width path in
     * {@link #onIteration}. */
    public void finish() {
        flushIfComplete();
    }

    private void flushIfComplete() {
        if (!bufferAborted && !buffer.isEmpty()) {
            lastCompleted = List.copyOf(buffer);
        }
    }

    /** The last fully-completed depth's candidate set, in ascending rank order, if any. */
    public Optional<List<IterationInfo>> lastCompletedSet() {
        return Optional.ofNullable(lastCompleted);
    }
}
