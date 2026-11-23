package coeusyk.game.chess.core.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #266: entry age is the unsigned distance between the table's generation and the entry's, taken
 * modulo 256. The generation is a byte, and a signed comparison treated every entry 128..255 generations
 * old as brand new, so it was protected from replacement and counted as live in hashfull().
 *
 * <p>A distance of 256 is the same generation again, so it reads as age 0; that aliasing is inherent to an
 * 8-bit generation and is pinned here so nobody mistakes it for the bug.
 */
class TranspositionTableAgeTest {

    private static final long KEY = 0x1234_5678_9ABC_DEF1L;

    private static void advance(TranspositionTable tt, int generations) {
        for (int i = 0; i < generations; i++) {
            tt.incrementGeneration();
        }
    }

    private static boolean stale(int distance) {
        return (distance & 0xFF) >= TranspositionTable.AGE_THRESHOLD;
    }

    /** A deep entry stored now, then a shallow entry for the same key after {@code distance} generations. */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 3, 4, 5, 127, 128, 129, 200, 255, 256, 260})
    void shallowEntryReplacesADeepOneExactlyWhenTheDeepOneIsStale(int distance) {
        TranspositionTable tt = new TranspositionTable(1);
        tt.store(KEY, 11, 12, 100, TTBound.EXACT);
        advance(tt, distance);

        tt.store(KEY, 22, 1, -50, TTBound.EXACT);

        TranspositionTable.Entry e = tt.probe(KEY);
        assertNotNull(e);
        if (stale(distance)) {
            assertEquals(1, e.depth(), "an entry " + distance + " generations old must be replaceable");
            assertEquals(22, e.bestMove());
        } else {
            assertEquals(12, e.depth(), "an entry " + distance + " generations old is still fresh");
            assertEquals(11, e.bestMove());
        }
    }

    @Test
    void equalOrDeeperEntriesAlwaysReplaceRegardlessOfAge() {
        TranspositionTable tt = new TranspositionTable(1);
        tt.store(KEY, 11, 5, 100, TTBound.EXACT);
        tt.store(KEY, 22, 5, 7, TTBound.EXACT);
        assertEquals(22, tt.probe(KEY).bestMove());
        tt.store(KEY, 33, 9, 8, TTBound.EXACT);
        assertEquals(33, tt.probe(KEY).bestMove());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 3, 4, 127, 128, 200, 255, 256})
    void hashfullCountsAnEntryAsLiveExactlyWhileItIsFresh(int distance) {
        TranspositionTable tt = new TranspositionTable(1);
        Random random = new Random(266);
        // Eight keys per slot on average leaves every sampled slot occupied, whatever the index function is.
        for (long i = 0, n = 8L * tt.getEntryCount(); i < n; i++) {
            tt.store(random.nextLong() | 1L, 1, 3, 0, TTBound.EXACT);
        }
        int full = tt.hashfull();
        assertTrue(full >= 990, "the table should read as full before aging, was " + full);

        advance(tt, distance);

        int live = tt.hashfull();
        if (stale(distance)) {
            assertEquals(0, live, "nothing stored " + distance + " generations ago is live");
        } else {
            assertEquals(full, live, "everything stored " + distance + " generations ago is still live");
        }
    }
}
