package coeusyk.game.chess.core.search;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

import coeusyk.game.chess.core.models.Move;

/**
 * Transposition table backed by an {@link AtomicLongArray} for lock-free
 * concurrent access.  Each logical entry occupies two consecutive longs:
 * index {@code i*2} holds the Zobrist key and index {@code i*2+1} holds
 * all other fields packed into a single long.
 *
 * <p>Bit layout of the data word:
 * <pre>
 *   bits 63-34: score      (30-bit signed, range ±536M — covers all engine scores)
 *   bits 33-18: bestMove   (16-bit; 0xFFFF = Move.NONE sentinel)
 *   bits 17-10: depth      (8-bit unsigned)
 *   bits  9- 8: bound      (2-bit ordinal: EXACT=0, LOWER_BOUND=1, UPPER_BOUND=2)
 *   bits  7- 0: generation (8-bit byte)
 * </pre>
 *
 * <p>Write order: data word is written before the key word (both via
 * {@code AtomicLongArray.set()}, which is a volatile write).  A reader that
 * observes the new key is therefore guaranteed by the Java Memory Model to
 * also observe the new data word (volatile happens-before).
 *
 * <p>Write-write races on the same slot are accepted: two threads may
 * independently write key and data, potentially mixing them.  A key mismatch
 * on the next probe is treated as a miss — no incorrect search behaviour.
 *
 * <p>The replacement policy is depth-preferred with always-replace on key
 * mismatch or generation-stale:
 * a new entry replaces an existing one only when the new depth &ge; old depth
 * or the stored entry is stale ({@link #AGE_THRESHOLD} generations old).
 */
public class TranspositionTable {
    private static final int DEFAULT_SIZE_MB = 64;
    private static final int APPROX_ENTRY_BYTES = 16;
    private static final int MAX_ENTRY_COUNT = 1 << 24;

    private AtomicLongArray table;
    private int entryCount;
    private int mask;

    // AtomicLong counters for optional probe/hit statistics.
    private final AtomicLong probes = new AtomicLong(0);
    private final AtomicLong hits   = new AtomicLong(0);

    // Stats are off by default — AtomicLong CAS on every probe measurably impacts NPS.
    // Call enableStats() before a dedicated stats pass; never during search.
    private volatile boolean statsEnabled = false;

    // Threshold (in generation ticks) beyond which a stored entry is considered
    // stale and always evicted on the next store() collision, regardless of depth.
    // Byte arithmetic wraps correctly: (byte)(currentGeneration - e.generation())
    // returns the age in the range [0, 127] for entries up to 127 moves old.
    // Default 4 means entries from the last 3 moves are preserved by depth-preference;
    // entries 4 or more moves old are freely replaced by any new entry.
    static final byte AGE_THRESHOLD = 4;

    // Per-search generation counter.  Incremented once at the start of each
    // new move's search.
    private volatile byte currentGeneration = 0;

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

        int ec = 1;
        while (ec < cappedDesiredEntries) {
            ec <<= 1;
        }

        entryCount = ec;
        table = new AtomicLongArray(ec * 2);
        mask = ec - 1;
        resetStats();
    }

    public Entry probe(long key) {
        int idx = indexFor(key);
        long storedKey = table.get(idx * 2);
        if (statsEnabled) {
            probes.incrementAndGet();
            if (storedKey != 0L && storedKey == key) {
                hits.incrementAndGet();
                return unpack(key, table.get(idx * 2 + 1));
            }
            return null;
        }
        if (storedKey != 0L && storedKey == key) {
            return unpack(key, table.get(idx * 2 + 1));
        }
        return null;
    }

    /** Unpacks the data word into an {@link Entry}. */
    private static Entry unpack(long key, long data) {
        int rawScore = (int) ((data >>> 34) & 0x3FFF_FFFFL);
        int score    = (rawScore << 2) >> 2;            // sign-extend 30-bit
        int rawMove  = (int) ((data >>> 18) & 0xFFFF);
        int bestMove = (rawMove == 0xFFFF) ? Move.NONE : rawMove;
        int depth    = (int) ((data >>> 10) & 0xFF);
        TTBound bound = TTBound.values()[(int) ((data >>> 8) & 0x3)];
        byte generation = (byte) (data & 0xFF);
        return new Entry(key, bestMove, depth, score, bound, generation);
    }

    /** Packs entry fields into the 64-bit data word. */
    private static long pack(int score, int bestMove, int depth, TTBound bound, byte generation) {
        long packedMove = (bestMove == Move.NONE) ? 0xFFFFL : (bestMove & 0xFFFFL);
        return ((long) (score & 0x3FFF_FFFF) << 34)
             | (packedMove                   << 18)
             | ((long) (depth & 0xFF)        << 10)
             | ((long) (bound.ordinal() & 0x3) << 8)
             | (generation & 0xFFL);
    }

    /**
     * Enables probe/hit statistics tracking. Off by default to avoid
     * AtomicLong CAS overhead on the hot search path. Call before a
     * dedicated stats pass; never during normal search.
     */
    public void enableStats() {
        statsEnabled = true;
        resetStats();
    }

    /**
     * Increments the generation counter.  Call once at the start of each new
     * move's search so that entries written in previous searches are treated as
     * old-generation and can be freely evicted by shallower entries in the
     * current search.
     */
    public void incrementGeneration() {
        currentGeneration = (byte) (currentGeneration + 1);
    }

    /**
     * Stores an entry using a generation-age-aware depth-preferred replacement
     * scheme.  The eviction priority order is:
     * <ol>
     *   <li>Empty slot — always fill.</li>
     *   <li>Key mismatch — always replace (different position).</li>
     *   <li>Age ≥ {@link #AGE_THRESHOLD} — always replace (stale regardless of depth).
     *       Age is computed as {@code (byte)(currentGeneration - existing.generation())}
     *       which wraps correctly for {@code byte} arithmetic.</li>
     *   <li>Same key, entry is fresh (age &lt; threshold) — replace only when
     *       the new depth ≥ existing depth (depth-preferred policy).</li>
     * </ol>
     *
     * <p>Entries from the most recent {@code AGE_THRESHOLD - 1} searches are
     * preserved by the depth-preference rule, avoiding premature eviction of
     * deep results from adjacent positions in the game tree.
     */
    public void store(long key, int bestMove, int depth, int score, TTBound bound) {
        int idx = indexFor(key);
        long existingKey  = table.get(idx * 2);
        boolean replace;
        if (existingKey == 0L || existingKey != key) {
            replace = true;
        } else {
            long existingData = table.get(idx * 2 + 1);
            byte existingGen  = (byte) (existingData & 0xFF);
            int  existingDepth = (int) ((existingData >>> 10) & 0xFF);
            replace = (byte) (currentGeneration - existingGen) >= AGE_THRESHOLD
                   || depth >= existingDepth;
        }
        if (replace) {
            long data = pack(score, bestMove, depth, bound, currentGeneration);
            table.set(idx * 2 + 1, data); // data before key (volatile happens-before)
            table.set(idx * 2,     key);
        }
    }

    /**
     * Returns occupied-entries-per-mille (0–1000) based on a 1000-slot sample,
     * counting only slots that hold a current-or-recent-generation entry
     * (age &lt; {@link #AGE_THRESHOLD}).  This gives a meaningful "live occupancy"
     * reading that falls back toward 0 between searches rather than staying
     * permanently at 1000 once the table has been fully populated.
     *
     * <p>Sampling avoids a full O(N) scan on the hot reporting path.  The
     * stride is {@code len / sampleSize} so samples are evenly distributed,
     * which is accurate enough for a GUI progress bar.
     */
    public int hashfull() {
        int len = entryCount;
        if (len == 0) {
            return 0;
        }
        final int sampleSize = Math.min(1000, len);
        final int stride = Math.max(1, len / sampleSize);
        int recent = 0;
        byte gen = currentGeneration;
        for (int i = 0; i < sampleSize; i++) {
            long storedKey = table.get(i * stride * 2);
            if (storedKey != 0L) {
                long data = table.get(i * stride * 2 + 1);
                byte entryGen = (byte) (data & 0xFF);
                if ((byte) (gen - entryGen) < AGE_THRESHOLD) {
                    recent++;
                }
            }
        }
        return recent * 1000 / sampleSize;
    }

    public int getEntryCount() {
        return entryCount;
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
            table.set(i, 0L);
        }
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
     * Immutable snapshot of a TT entry.  Constructed on-the-fly by
     * {@link #probe(long)} from the packed data long.
     * <p>bestMove is either a 16-bit packed move (bits 0-5 from, 6-11 to, 12-15 flag)
     * or {@link coeusyk.game.chess.core.models.Move#NONE} (-1) if no move was stored.
     */
    public record Entry(long key, int bestMove, int depth, int score, TTBound bound, byte generation) {
    }

    /**
     * Snapshot of transposition-table statistics at a point in time.
     *
     * @param probes   total probe calls since last {@link #resetStats()} / {@link #clear()}
     * @param hits     successful probes (key matched) in the same window
     * @param hashfull occupied entries per mille (0–1000), matching the UCI {@code hashfull} convention
     */
    public record TTStats(long probes, long hits, int hashfull) {
        /** Hit rate in [0.0, 1.0]; returns 0.0 when no probes have been made. */
        public double hitRate() {
            return probes == 0 ? 0.0 : (double) hits / probes;
        }
    }

    /** Returns a point-in-time snapshot of TT statistics. */
    public TTStats getStats() {
        return new TTStats(probes.get(), hits.get(), hashfull());
    }
}