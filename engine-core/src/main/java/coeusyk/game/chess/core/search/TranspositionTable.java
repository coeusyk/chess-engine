package coeusyk.game.chess.core.search;

import coeusyk.game.chess.core.models.Move;

public class TranspositionTable {
    private static final int DEFAULT_SIZE_MB = 64;
    private static final int APPROX_ENTRY_BYTES = 32;

    private Entry[] table;
    private int mask;

    private long probes;
    private long hits;

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

        int entryCount = 1;
        while (entryCount < desiredEntries && entryCount > 0) {
            entryCount <<= 1;
        }
        if (entryCount <= 0) {
            entryCount = 1 << 20;
        }

        table = new Entry[entryCount];
        mask = entryCount - 1;
        resetStats();
    }

    public Entry probe(long key) {
        probes++;
        Entry entry = table[indexFor(key)];
        if (entry != null && entry.key() == key) {
            hits++;
            return entry;
        }
        return null;
    }

    public void store(long key, Move bestMove, int depth, int score, TTBound bound) {
        int index = indexFor(key);
        Entry existing = table[index];
        if (existing == null || depth >= existing.depth()) {
            table[index] = new Entry(key, copyMove(bestMove), depth, score, bound);
        }
    }

    public int getEntryCount() {
        return table.length;
    }

    public double getHitRate() {
        return probes == 0 ? 0.0 : (double) hits / (double) probes;
    }

    public long getProbes() {
        return probes;
    }

    public long getHits() {
        return hits;
    }

    public void resetStats() {
        probes = 0;
        hits = 0;
    }

    private int indexFor(long key) {
        return (int) key & mask;
    }

    private Move copyMove(Move move) {
        if (move == null) {
            return null;
        }
        return new Move(move.startSquare, move.targetSquare, move.reaction);
    }

    public record Entry(long key, Move bestMove, int depth, int score, TTBound bound) {
    }
}