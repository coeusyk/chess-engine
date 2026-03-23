package coeusyk.game.chess.utils;

import coeusyk.game.chess.models.Move;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;


class TranspositionTableTest {

    private TranspositionTable tt;

    @BeforeEach
    void setUp() {
        // Use a tiny 1 MB table so the test is fast
        tt = new TranspositionTable(1);
    }

    // -------------------------------------------------------------------------
    // Basic store / probe
    // -------------------------------------------------------------------------

    @Test
    void storeThenProbeReturnsEntry() {
        long hash = 0xDEADBEEFCAFEL;
        Move move = new Move(8, 16);
        tt.store(hash, move, 3, 100, TranspositionTable.EXACT);

        TranspositionTable.TTEntry entry = tt.probe(hash);

        assertNotNull(entry);
        assertEquals(hash, entry.hash);
        assertEquals(100, entry.score);
        assertEquals(3, entry.depth);
        assertEquals(TranspositionTable.EXACT, entry.flag);
        assertNotNull(entry.bestMove);
        assertEquals(8, entry.bestMove.startSquare);
        assertEquals(16, entry.bestMove.targetSquare);
    }

    @Test
    void probeReturnsNullOnMiss() {
        assertNull(tt.probe(0x123456789ABCL));
    }

    @Test
    void probeReturnsNullAfterHashCollision() {
        // Store an entry, then probe with a different hash that maps to the same slot
        long hash1 = 0L;
        long hash2 = (long) tt.getSize(); // same slot, different key
        tt.store(hash1, new Move(0, 8), 1, 50, TranspositionTable.EXACT);

        // Probing hash2 must return null (key mismatch)
        assertNull(tt.probe(hash2));
    }

    // -------------------------------------------------------------------------
    // Bound types
    // -------------------------------------------------------------------------

    @Test
    void storesAndRetrievesLowerBound() {
        long hash = 0xAABBCCDDEEFFL;
        tt.store(hash, null, 2, -50, TranspositionTable.LOWER_BOUND);
        TranspositionTable.TTEntry e = tt.probe(hash);
        assertNotNull(e);
        assertEquals(TranspositionTable.LOWER_BOUND, e.flag);
        assertEquals(-50, e.score);
    }

    @Test
    void storesAndRetrievesUpperBound() {
        long hash = 0x112233445566L;
        tt.store(hash, new Move(10, 20), 4, 200, TranspositionTable.UPPER_BOUND);
        TranspositionTable.TTEntry e = tt.probe(hash);
        assertNotNull(e);
        assertEquals(TranspositionTable.UPPER_BOUND, e.flag);
        assertEquals(200, e.score);
    }

    // -------------------------------------------------------------------------
    // Replacement policy
    // -------------------------------------------------------------------------

    @Test
    void deeperEntryIsNotReplacedByShallowerEntry() {
        long hash = 0xFEDCBA987654L;
        tt.store(hash, new Move(1, 2), 5, 300, TranspositionTable.EXACT);
        // Attempt to overwrite with a shallower entry – should be rejected
        tt.store(hash, new Move(3, 4), 2, 999, TranspositionTable.EXACT);

        TranspositionTable.TTEntry e = tt.probe(hash);
        assertNotNull(e);
        assertEquals(5, e.depth);
        assertEquals(300, e.score);
    }

    @Test
    void entryIsReplacedByEqualOrDeeperEntry() {
        long hash = 0xFEDCBA987654L;
        tt.store(hash, new Move(1, 2), 3, 100, TranspositionTable.EXACT);
        tt.store(hash, new Move(5, 6), 3, 200, TranspositionTable.EXACT);

        TranspositionTable.TTEntry e = tt.probe(hash);
        assertNotNull(e);
        assertEquals(3, e.depth);
        assertEquals(200, e.score);
    }

    // -------------------------------------------------------------------------
    // Hit-rate tracking
    // -------------------------------------------------------------------------

    @Test
    void hitRateIsZeroInitially() {
        assertEquals(0.0, tt.getHitRate(), 1e-9);
    }

    @Test
    void hitRateNonZeroAfterCacheHit() {
        long hash = 0xCAFEBABEL;
        tt.store(hash, null, 1, 0, TranspositionTable.EXACT);
        tt.probe(hash); // hit
        tt.probe(hash); // hit

        assertEquals(1.0, tt.getHitRate(), 1e-9);
    }

    @Test
    void hitRatePartialAfterMixedProbes() {
        long hash = 0xCAFEBABEL;
        tt.store(hash, null, 1, 0, TranspositionTable.EXACT);
        tt.probe(hash);          // hit
        tt.probe(hash + 1);      // miss (different key, likely different slot)

        // At least one hit and one miss recorded
        assertTrue(tt.getHits() >= 1);
        assertTrue(tt.getLookups() >= 2);
        assertTrue(tt.getHitRate() > 0.0 && tt.getHitRate() < 1.0);
    }

    // -------------------------------------------------------------------------
    // Clear
    // -------------------------------------------------------------------------

    @Test
    void clearRemovesAllEntries() {
        long hash = 0xABCDEFL;
        tt.store(hash, new Move(0, 1), 2, 50, TranspositionTable.EXACT);
        tt.clear();

        // Counters must be reset immediately after clear() – before any probe
        assertEquals(0.0, tt.getHitRate(), 1e-9);
        assertEquals(0, tt.getLookups());
        assertEquals(0, tt.getHits());

        // Entry must have been evicted
        assertNull(tt.probe(hash));
    }

    // -------------------------------------------------------------------------
    // Size and configuration
    // -------------------------------------------------------------------------

    @Test
    void sizeIsPowerOfTwo() {
        int size = tt.getSize();
        assertTrue(size > 0);
        assertEquals(0, size & (size - 1), "size must be a power of two");
    }

    @Test
    void customSizeIsPowerOfTwo() {
        TranspositionTable small = new TranspositionTable(4);
        int size = small.getSize();
        assertTrue(size > 0);
        assertEquals(0, size & (size - 1));
    }
}
