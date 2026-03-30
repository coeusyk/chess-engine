package coeusyk.game.chess.core.search;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Transposition table backed by an {@link AtomicReferenceArray} for lock-free
 * concurrent access.  Each slot holds a single immutable {@link Entry} record.
 * Reference writes and reads via {@code AtomicReferenceArray} are guaranteed
 * atomic in Java (JLS §17.6), so concurrent Lazy-SMP threads can freely read
 * and write without synchronization.
 *
 * <p>Race conditions on multi-word state are tolerated: the {@code key} field
 * inside each entry is checked after every probe, so a stale or partially
 * overwritten entry is treated as a miss rather than silently corrupting search.
 *
 * <p>The replacement policy is depth-preferred with always-replace on key match:
 * a new entry replaces an existing one only when the new depth ≥ old depth or
 * the slot belongs to a different position (key mismatch / empty).
 */
public class TranspositionTable {
    private static final int DEFAULT_SIZE_MB = 64;
    private static final int APPROX_ENTRY_BYTES = 32;
    private static final int MAX_ENTRY_COUNT = 1 << 23;

    // AtomicReferenceArray gives one atomic read/write per slot.
    // Each Entry is an immutable record — fields are safely visible after construction.
    private AtomicReferenceArray<Entry> table;
    private int mask;

    // AtomicInteger/AtomicLong keep counts consistent across concurrent threads.
    private final AtomicInteger occupiedCount = new AtomicInteger(0);
    private final AtomicLong probes = new AtomicLong(0);
    private final AtomicLong hits   = new AtomicLong(0);

    public TranspositionTable() {
        this(DEFAULT_SIZE_MB);
    }

    public TranspositionTable(int sizeMb) {
        resize(sizeMb);
    }

    public void resize(int sizeMb) {
        if (sizeMb <= 0) {
            throw new IllegalArgumentException("table size must be > 0 MB");
        }

        long bytes = (long) sizeMb * 1024L * 1024L;
        long desiredEntries = Math.max(1L, bytes / APPROX_ENTRY_BYTES);
        long cappedDesiredEntries = Math.min(desiredEntries, (long) MAX_ENTRY_COUNT);

        int entryCount = 1;
        while (entryCount < cappedDesiredEntries) {
            entryCount <<= 1;
        }

        table = new AtomicReferenceArray<>(entryCount);
        mask = entryCount - 1;
        occupiedCount.set(0);
        resetStats();
    }

    public Entry probe(long key) {
        probes.incrementAndGet();
        Entry entry = table.get(indexFor(key));
        if (entry != null && entry.key() == key) {
            hits.incrementAndGet();
            return entry;
        }
        return null;
    }

    /**
     * Stores an entry using a depth-preferred replacement scheme.
     * A slot is overwritten when:
     * <ul>
     *   <li>the slot is empty, or</li>
     *   <li>it belongs to a different position (key mismatch), or</li>
     *   <li>the new depth is ≥ the stored depth (prefer deeper analysis).</li>
     * </ul>
     */
    public void store(long key, int bestMove, int depth, int score, TTBound bound) {
        int index = indexFor(key);
        Entry existing = table.get(index);
        if (existing == null || existing.key() != key || depth >= existing.depth()) {
            if (existing == null) {
                occupiedCount.incrementAndGet();
            }
            table.set(index, new Entry(key, bestMove, depth, score, bound));
        }
    }

    public int hashfull() {
        int len = table.length();
        if (len == 0) {
            return 0;
        }
        return (int) ((long) occupiedCount.get() * 1000L / len);
    }

    public int getEntryCount() {
        return table.length();
    }

    public double getHitRate() {
        long p = probes.get();
        return p == 0 ? 0.0 : (double) hits.get() / p;
    }

    public long getProbes() {
        return probes.get();
    }

    public long getHits() {
        return hits.get();
    }

    /**
     * Clears all entries.  Iterates every slot; O(N) but only called on
     * {@code ucinewgame} or explicit resize.
     */
    public void clear() {
        int len = table.length();
        for (int i = 0; i < len; i++) {
            table.set(i, null);
        }
        occupiedCount.set(0);
        resetStats();
    }

    public void resetStats() {
        probes.set(0);
        hits.set(0);
    }

    private int indexFor(long key) {
        return (int) (key ^ (key >>> 32)) & mask;
    }

    /**
     * Packed-int best move, or {@link coeusyk.game.chess.core.models.Move#NONE} if none.
     * Encoding: bits 0-5 = from, bits 6-11 = to, bits 12-15 = flag.
     */
    public record Entry(long key, int bestMove, int depth, int score, TTBound bound) {
    }
}