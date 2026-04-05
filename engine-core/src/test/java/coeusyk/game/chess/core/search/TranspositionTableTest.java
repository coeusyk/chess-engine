package coeusyk.game.chess.core.search;

import coeusyk.game.chess.core.models.Move;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TranspositionTableTest {

    @Test
    void storesAndRetrievesByZobristKey() {
        TranspositionTable table = new TranspositionTable(1);
        int best = Move.of(12, 28);

        table.store(12345L, best, 5, 37, TTBound.EXACT);
        TranspositionTable.Entry entry = table.probe(12345L);

        assertNotNull(entry);
        assertEquals(12345L, entry.key());
        assertEquals(5, entry.depth());
        assertEquals(37, entry.score());
        assertEquals(TTBound.EXACT, entry.bound());
        assertEquals(Move.from(best), Move.from(entry.bestMove()));
        assertEquals(Move.to(best), Move.to(entry.bestMove()));
    }

    @Test
    void depthPreferredReplacementKeepsDeeperEntry() {
        TranspositionTable table = new TranspositionTable(1);
        long key = 777L;

        table.store(key, Move.of(10, 18), 3, 10, TTBound.EXACT);
        table.store(key, Move.of(11, 19), 2, 20, TTBound.LOWER_BOUND);

        TranspositionTable.Entry entry = table.probe(key);
        assertNotNull(entry);
        assertEquals(3, entry.depth());
        assertEquals(10, entry.score());

        table.store(key, Move.of(12, 20), 5, 30, TTBound.UPPER_BOUND);
        entry = table.probe(key);
        assertNotNull(entry);
        assertEquals(5, entry.depth());
        assertEquals(30, entry.score());
        assertEquals(TTBound.UPPER_BOUND, entry.bound());
    }

    @Test
    void tableSizeIsPowerOfTwoAndConfigurable() {
        TranspositionTable table = new TranspositionTable(1);
        int smallCount = table.getEntryCount();
        assertTrue((smallCount & (smallCount - 1)) == 0);

        table.resize(64);
        int largeCount = table.getEntryCount();
        assertTrue((largeCount & (largeCount - 1)) == 0);
        assertTrue(largeCount >= smallCount);
    }

    @Test
    void differentKeyCanReplaceOnIndexCollision() {
        TranspositionTable table = new TranspositionTable(1);
        long keyA = 1L;
        long keyB = 1L << 32;

        table.store(keyA, Move.of(10, 18), 8, 50, TTBound.EXACT);
        table.store(keyB, Move.of(12, 20), 1, 25, TTBound.LOWER_BOUND);

        assertNull(table.probe(keyA));
        TranspositionTable.Entry entryB = table.probe(keyB);
        assertNotNull(entryB);
        assertEquals(keyB, entryB.key());
        assertEquals(1, entryB.depth());
        assertEquals(25, entryB.score());
    }

    @Test
    void ageEligibleEntryReplacedRegardlessOfDepth() {
        // An entry aged AGE_THRESHOLD generations is always evicted, even by a shallower store.
        TranspositionTable table = new TranspositionTable(1);
        long key = 42L;

        table.store(key, Move.of(10, 18), 10, 100, TTBound.EXACT);
        // Advance generation until the stored entry hits the eviction threshold.
        for (int i = 0; i < TranspositionTable.AGE_THRESHOLD; i++) {
            table.incrementGeneration();
        }
        // A shallower entry must evict the stale deep entry.
        table.store(key, Move.of(11, 19), 1, 50, TTBound.LOWER_BOUND);

        TranspositionTable.Entry entry = table.probe(key);
        assertNotNull(entry);
        assertEquals(1, entry.depth(), "shallow entry should replace stale deep entry");
        assertEquals(50, entry.score());
    }

    @Test
    void freshEntryWithinAgeThresholdPreservedByDepthPreference() {
        // An entry aged less than AGE_THRESHOLD is kept over a shallower replacement.
        TranspositionTable table = new TranspositionTable(1);
        long key = 99L;

        table.store(key, Move.of(10, 18), 10, 100, TTBound.EXACT);
        // Advance to AGE_THRESHOLD - 1 (one tick below eviction threshold).
        for (int i = 0; i < TranspositionTable.AGE_THRESHOLD - 1; i++) {
            table.incrementGeneration();
        }
        // A shallower entry must NOT replace the still-fresh deep entry.
        table.store(key, Move.of(11, 19), 1, 50, TTBound.LOWER_BOUND);

        TranspositionTable.Entry entry = table.probe(key);
        assertNotNull(entry);
        assertEquals(10, entry.depth(), "deep fresh entry should be preserved over shallow replacement");
        assertEquals(100, entry.score());
    }
}