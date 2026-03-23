package coeusyk.game.chess.core.search;

import coeusyk.game.chess.core.models.Move;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TranspositionTableTest {

    @Test
    void storesAndRetrievesByZobristKey() {
        TranspositionTable table = new TranspositionTable(1);
        Move best = new Move(12, 28);

        table.store(12345L, best, 5, 37, TTBound.EXACT);
        TranspositionTable.Entry entry = table.probe(12345L);

        assertNotNull(entry);
        assertEquals(12345L, entry.key());
        assertEquals(5, entry.depth());
        assertEquals(37, entry.score());
        assertEquals(TTBound.EXACT, entry.bound());
        assertNotSame(best, entry.bestMove());
        assertEquals(best.startSquare, entry.bestMove().startSquare);
        assertEquals(best.targetSquare, entry.bestMove().targetSquare);
    }

    @Test
    void depthPreferredReplacementKeepsDeeperEntry() {
        TranspositionTable table = new TranspositionTable(1);
        long key = 777L;

        table.store(key, new Move(10, 18), 3, 10, TTBound.EXACT);
        table.store(key, new Move(11, 19), 2, 20, TTBound.LOWER_BOUND);

        TranspositionTable.Entry entry = table.probe(key);
        assertNotNull(entry);
        assertEquals(3, entry.depth());
        assertEquals(10, entry.score());

        table.store(key, new Move(12, 20), 5, 30, TTBound.UPPER_BOUND);
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

        table.store(keyA, new Move(10, 18), 8, 50, TTBound.EXACT);
        table.store(keyB, new Move(12, 20), 1, 25, TTBound.LOWER_BOUND);

        assertNull(table.probe(keyA));
        TranspositionTable.Entry entryB = table.probe(keyB);
        assertNotNull(entryB);
        assertEquals(keyB, entryB.key());
        assertEquals(1, entryB.depth());
        assertEquals(25, entryB.score());
    }
}