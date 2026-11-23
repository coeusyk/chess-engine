package coeusyk.game.chess.core.search;

import coeusyk.game.chess.core.search.TranspositionTable.StoreOutcome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Issue #266: which entry survives when two different positions land on the same slot. The table has one
 * slot per index, so a fresh deep entry (a PV node searched to depth 12) must not be wiped by a shallow leaf
 * that merely hashes to the same place, but a stale entry, an empty slot, or an equally deep or deeper entry
 * must still be replaceable.
 */
class TranspositionTableCollisionTest {

    private static final long K1 = 0x1234_5678_9ABC_DEF1L;
    /** Same slot as K1 ({@code (key ^ key >>> 32) & mask} is unchanged) but a different position. */
    private static final long K2 = K1 ^ (1L << 40) ^ (1L << 8);

    private static TranspositionTable table() {
        TranspositionTable tt = new TranspositionTable(1);
        tt.enableStats();
        return tt;
    }

    @Test
    void theTwoKeysReallyShareASlot() {
        TranspositionTable tt = table();
        tt.store(K1, 1, 5, 0, TTBound.EXACT);
        tt.store(K2, 2, 5, 0, TTBound.EXACT); // equal depth always replaces, so this proves the collision
        assertNull(tt.probe(K1));
        assertNotNull(tt.probe(K2));
    }

    @Test
    void aFreshDepth12EntrySurvivesADepth1Collision() {
        TranspositionTable tt = table();
        tt.store(K1, 11, 12, 100, TTBound.EXACT);

        tt.store(K2, 22, 1, -5, TTBound.EXACT);

        TranspositionTable.Entry kept = tt.probe(K1);
        assertNotNull(kept, "a fresh depth-12 entry was evicted by a depth-1 collision");
        assertEquals(12, kept.depth());
        assertEquals(11, kept.bestMove());
        assertNull(tt.probe(K2), "the shallow newcomer should not have been written");
        assertEquals(1, tt.storeOutcomeCount(StoreOutcome.COLLISION_REJECTED));
        assertEquals(0, tt.storeOutcomeCount(StoreOutcome.COLLISION_EVICTED_FRESH_DEEPER));
    }

    @Test
    void aStaleDepth12EntryIsEvictedByADepth1Collision() {
        TranspositionTable tt = table();
        tt.store(K1, 11, 12, 100, TTBound.EXACT);
        for (int i = 0; i < TranspositionTable.AGE_THRESHOLD; i++) tt.incrementGeneration();

        tt.store(K2, 22, 1, -5, TTBound.EXACT);

        assertNull(tt.probe(K1));
        assertEquals(1, tt.probe(K2).depth());
        assertEquals(1, tt.storeOutcomeCount(StoreOutcome.COLLISION_EVICTED_STALE));
    }

    @Test
    void anEntryJustInsideTheFreshWindowIsStillProtected() {
        TranspositionTable tt = table();
        tt.store(K1, 11, 12, 100, TTBound.EXACT);
        for (int i = 0; i < TranspositionTable.AGE_THRESHOLD - 1; i++) tt.incrementGeneration();

        tt.store(K2, 22, 1, -5, TTBound.EXACT);

        assertNotNull(tt.probe(K1));
        assertNull(tt.probe(K2));
    }

    @Test
    void anEqualOrDeeperCollisionReplacesAFreshEntry() {
        TranspositionTable tt = table();
        tt.store(K1, 11, 6, 100, TTBound.EXACT);
        tt.store(K2, 22, 6, -5, TTBound.EXACT);
        assertNull(tt.probe(K1));
        assertEquals(6, tt.probe(K2).depth());
        assertEquals(1, tt.storeOutcomeCount(StoreOutcome.COLLISION_EVICTED_FRESH_NOT_DEEPER));

        tt.store(K1, 33, 9, 7, TTBound.EXACT);
        assertEquals(9, tt.probe(K1).depth());
    }

    @Test
    void anEmptySlotIsAlwaysFilledAndCounted() {
        TranspositionTable tt = table();
        tt.store(K1, 11, 1, 0, TTBound.EXACT);
        assertEquals(1, tt.storeOutcomeCount(StoreOutcome.EMPTY));
        assertEquals(1, tt.probe(K1).depth());
    }

    @Test
    void sameKeyRulesAreUnchangedAndCounted() {
        TranspositionTable tt = table();
        tt.store(K1, 11, 8, 0, TTBound.EXACT);
        tt.store(K1, 22, 3, 0, TTBound.EXACT);
        assertEquals(8, tt.probe(K1).depth());
        tt.store(K1, 33, 8, 0, TTBound.EXACT);
        assertEquals(33, tt.probe(K1).bestMove());
        assertEquals(1, tt.storeOutcomeCount(StoreOutcome.SAME_KEY_KEPT));
        assertEquals(1, tt.storeOutcomeCount(StoreOutcome.SAME_KEY_REPLACED));
    }

    @Test
    void theMemoryBudgetIsUnchanged() {
        assertEquals(1 << 16, new TranspositionTable(1).getEntryCount());
    }
}
