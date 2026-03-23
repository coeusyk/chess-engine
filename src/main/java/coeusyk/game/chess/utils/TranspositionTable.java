package coeusyk.game.chess.utils;

import coeusyk.game.chess.models.Move;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;


/**
 * Fixed-size transposition table keyed by Zobrist position hash.
 * <p>
 * Each slot stores a {@link TTEntry} containing the best move found, the search
 * depth at which the entry was produced, the score, and a bound type:
 * <ul>
 *   <li>{@link #EXACT} – score is precise; return it directly when depth is sufficient.</li>
 *   <li>{@link #LOWER_BOUND} – caused a beta cut-off; used to tighten the alpha bound.</li>
 *   <li>{@link #UPPER_BOUND} – all moves failed low; used to tighten the beta bound.</li>
 * </ul>
 * <p>
 * The table uses power-of-two sizing for fast index computation via bitwise AND.
 * The default capacity is 64 MB.  Collisions are resolved with a depth-preferred
 * replacement policy: an existing entry is only overwritten when the incoming
 * entry has an equal or greater depth.
 */
public class TranspositionTable {

    // Bound-type constants
    public static final int EXACT = 0;
    public static final int LOWER_BOUND = 1;
    public static final int UPPER_BOUND = 2;

    private static final int DEFAULT_SIZE_MB = 64;
    /**
     * Approximate heap footprint of one TTEntry (object header + 4 fields).
     * Used only to compute the initial entry count from a requested MB limit.
     */
    private static final int BYTES_PER_ENTRY = 40;

    private static final Logger log = LoggerFactory.getLogger(TranspositionTable.class);

    // -------------------------------------------------------------------------
    // Inner record
    // -------------------------------------------------------------------------

    /**
     * A single entry stored in the transposition table.
     */
    public static class TTEntry {
        /** Full Zobrist key – verified on lookup to guard against index collisions. */
        public final long hash;
        /** Best move found for this position (may be {@code null} for leaf nodes). */
        public final Move bestMove;
        /** Search depth at which this entry was produced. */
        public final int depth;
        /** Evaluated score for this position. */
        public final int score;
        /** One of {@link TranspositionTable#EXACT}, {@link TranspositionTable#LOWER_BOUND},
         *  or {@link TranspositionTable#UPPER_BOUND}. */
        public final int flag;

        public TTEntry(long hash, Move bestMove, int depth, int score, int flag) {
            this.hash = hash;
            this.bestMove = bestMove;
            this.depth = depth;
            this.score = score;
            this.flag = flag;
        }
    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final TTEntry[] table;
    private final int size;   // power-of-two entry count
    private final int mask;   // size - 1, used for index computation

    private long hits = 0;
    private long lookups = 0;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /** Creates a transposition table with the default size of 64 MB. */
    public TranspositionTable() {
        this(DEFAULT_SIZE_MB);
    }

    /**
     * Creates a transposition table with the requested size.
     *
     * @param sizeMB desired maximum memory consumption in megabytes (≥ 1)
     */
    public TranspositionTable(int sizeMB) {
        int requestedEntries = (sizeMB * 1024 * 1024) / BYTES_PER_ENTRY;
        // Round down to the nearest power of two so index masking works correctly
        this.size = Math.max(1, Integer.highestOneBit(requestedEntries));
        this.mask = size - 1;
        this.table = new TTEntry[size];
        log.info("TranspositionTable initialised: {} entries (~{} MB)", size, sizeMB);
    }

    // -------------------------------------------------------------------------
    // Core operations
    // -------------------------------------------------------------------------

    /**
     * Looks up the table for an entry matching {@code hash}.
     * <p>
     * Returns {@code null} on a miss (key mismatch or empty slot).
     *
     * @param hash the Zobrist key of the position to look up
     * @return the matching {@link TTEntry}, or {@code null}
     */
    public TTEntry probe(long hash) {
        lookups++;
        TTEntry entry = table[(int) (hash & mask)];
        if (entry != null && entry.hash == hash) {
            hits++;
            return entry;
        }
        return null;
    }

    /**
     * Stores an entry in the table using a depth-preferred replacement policy.
     * <p>
     * An existing entry is only replaced when the new {@code depth} is at least
     * as large as the stored depth.  This keeps higher-quality (deeper) results
     * in the table for as long as possible.
     *
     * @param hash     Zobrist key of the position
     * @param bestMove best move found (may be {@code null})
     * @param depth    search depth that produced this entry
     * @param score    evaluated score
     * @param flag     {@link #EXACT}, {@link #LOWER_BOUND}, or {@link #UPPER_BOUND}
     */
    public void store(long hash, Move bestMove, int depth, int score, int flag) {
        int index = (int) (hash & mask);
        TTEntry existing = table[index];
        // Depth-preferred replacement: only overwrite a deeper entry if we match or beat its depth
        if (existing == null || depth >= existing.depth) {
            table[index] = new TTEntry(hash, bestMove, depth, score, flag);
        }
    }

    /**
     * Clears all entries and resets the hit-rate counters.
     */
    public void clear() {
        Arrays.fill(table, null);
        hits = 0;
        lookups = 0;
    }

    // -------------------------------------------------------------------------
    // Statistics
    // -------------------------------------------------------------------------

    /**
     * Returns the fraction of {@link #probe} calls that returned a valid entry.
     *
     * @return hit rate in the range {@code [0.0, 1.0]}
     */
    public double getHitRate() {
        return lookups == 0 ? 0.0 : (double) hits / lookups;
    }

    /** Returns the total number of probe calls since the last {@link #clear()}. */
    public long getLookups() {
        return lookups;
    }

    /** Returns the total number of cache hits since the last {@link #clear()}. */
    public long getHits() {
        return hits;
    }

    /**
     * Logs the current hit rate at INFO level.
     */
    public void logHitRate() {
        log.info("TranspositionTable: {} lookups, {} hits, hit rate: {}%",
                lookups, hits, String.format("%.2f", getHitRate() * 100));
    }

    /** Returns the number of slots in the table (always a power of two). */
    public int getSize() {
        return size;
    }
}
