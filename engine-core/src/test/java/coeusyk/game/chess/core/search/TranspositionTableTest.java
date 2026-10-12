package coeusyk.game.chess.core.search;

import coeusyk.game.chess.core.models.Move;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class TranspositionTableTest {

    @Test
    void helperDiagnosticsTrackActivityAfterAbortAndLifecycleBoundaries() {
        TranspositionTable table = new TranspositionTable(1, true);
        AtomicBoolean aborted = new AtomicBoolean();
        table.incrementGeneration();
        TranspositionTable.DiagnosticSnapshot searchStart = table.diagnosticSnapshot();
        table.beginHelperDiagnostics(7, 1, searchStart, aborted::get);

        table.probe(11L);
        aborted.set(true);
        table.store(11L, Move.NONE, 1, 0, TTBound.EXACT);
        table.resetStats();
        table.incrementGeneration();
        table.probe(22L);
        table.clear();
        table.store(33L, Move.NONE, 1, 0, TTBound.EXACT);
        table.resize(1);
        table.probe(44L);

        TranspositionTable.HelperActivity activity = table.endHelperDiagnostics();
        assertEquals(7, activity.searchId());
        assertEquals(1, activity.helperId());
        assertEquals(3, activity.reads());
        assertEquals(2, activity.writes());
        assertEquals(1, activity.other());
        assertEquals(2, activity.readsAfterAbort());
        assertEquals(2, activity.writesAfterAbort());
        assertEquals(1, activity.otherAfterAbort());
        assertEquals(2, activity.readsAfterGeneration());
        assertEquals(1, activity.writesAfterGeneration());
        assertEquals(3, activity.afterGeneration());
        assertEquals(1, activity.readsAfterClear());
        assertEquals(1, activity.writesAfterClear());
        assertEquals(2, activity.afterClear());
        assertEquals(1, activity.readsAfterResize());
        assertEquals(0, activity.writesAfterResize());
        assertEquals(1, activity.afterResize());
        assertEquals(5, activity.afterAbort());
    }

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
    void statsEnabledProbePathTracksHitsAndMissesCorrectly() {
        TranspositionTable table = new TranspositionTable(1);
        table.enableStats();

        table.store(555L, Move.of(8, 16), 4, 15, TTBound.EXACT);

        TranspositionTable.Entry hit = table.probe(555L);
        assertNotNull(hit);
        assertEquals(555L, hit.key());
        assertEquals(15, hit.score());

        TranspositionTable.Entry miss = table.probe(999L);
        assertNull(miss);

        assertEquals(2, table.getProbes());
        assertEquals(1, table.getHits());
        assertEquals(0.5, table.getHitRate(), 1e-9);
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

    @Test
    void invalidPackedBoundOrdinalFallsBackToExact() throws Exception {
        TranspositionTable table = new TranspositionTable(1);
        long key = 123_456_789L;
        int idx = (int) key & (table.getEntryCount() - 1);

        Field tableField = TranspositionTable.class.getDeclaredField("table");
        tableField.setAccessible(true);
        java.util.concurrent.atomic.AtomicLongArray rawTable =
                (java.util.concurrent.atomic.AtomicLongArray) tableField.get(table);

        long dataWithInvalidBound = (1L << 8) * 3; // bound ordinal 3 (invalid)
        rawTable.set(idx * 2 + 1, dataWithInvalidBound);
        rawTable.set(idx * 2, key ^ dataWithInvalidBound); // check word, not raw key

        TranspositionTable.Entry entry = table.probe(key);
        assertNotNull(entry);
        assertEquals(TTBound.EXACT, entry.bound());
    }

    /**
     * Reproduces the torn-read window from #227 without relying on actual thread
     * scheduling: capture the association word right after storing A, overwrite
     * the slot with B (a same-index collision), then roll the association word
     * back to what it was before B's write while leaving B's data word in place.
     * That is exactly the state a concurrent reader of {@code probe(keyA)} could
     * observe: an association word that predates B's store, paired with a data
     * word that postdates it. A correct implementation must treat this as a miss
     * rather than return B's payload as if it belonged to A.
     */
    @Test
    void probeRejectsAssociationWordPairedWithDataFromADifferentStore() throws Exception {
        TranspositionTable table = new TranspositionTable(1);
        long keyA = 1L;
        long keyB = 1L << 32; // same slot index as keyA (collides mod entryCount)

        table.store(keyA, Move.of(10, 18), 5, 37, TTBound.EXACT);

        Field tableField = TranspositionTable.class.getDeclaredField("table");
        tableField.setAccessible(true);
        java.util.concurrent.atomic.AtomicLongArray rawTable =
                (java.util.concurrent.atomic.AtomicLongArray) tableField.get(table);
        int idx = (int) keyA & (table.getEntryCount() - 1);
        long assocWordBeforeB = rawTable.get(idx * 2);

        table.store(keyB, Move.of(12, 20), 1, 99, TTBound.LOWER_BOUND);

        // Simulate a reader whose read of the association word raced ahead of
        // B's store, but whose read of the data word landed after it.
        rawTable.set(idx * 2, assocWordBeforeB);

        assertNull(table.probe(keyA),
                "torn read must not surface B's data as a hit for A's key");
    }

    /**
     * Extends the previous test to the ABA case the fix is explicitly required to
     * handle: A is stored, then B (collision), then A again with different
     * depth/score (collision back). A reader who observed the association word
     * from the first A-store and the data word from the intervening B-store must
     * not get a hit, even though "the raw key" would read as A on both sides of
     * that race window — the check has to depend on which specific write the
     * data came from, not just on the key value.
     */
    @Test
    void probeRejectsStaleAssociationWordAcrossAbaCollisionSequence() throws Exception {
        TranspositionTable table = new TranspositionTable(1);
        long keyA = 1L;
        long keyB = 1L << 32; // same slot index as keyA

        table.store(keyA, Move.of(10, 18), 5, 37, TTBound.EXACT);

        Field tableField = TranspositionTable.class.getDeclaredField("table");
        tableField.setAccessible(true);
        java.util.concurrent.atomic.AtomicLongArray rawTable =
                (java.util.concurrent.atomic.AtomicLongArray) tableField.get(table);
        int idx = (int) keyA & (table.getEntryCount() - 1);
        long assocWordAfterFirstA = rawTable.get(idx * 2);

        table.store(keyB, Move.of(12, 20), 1, 99, TTBound.LOWER_BOUND);
        long dataWordAfterB = rawTable.get(idx * 2 + 1);

        table.store(keyA, Move.of(20, 28), 9, -12, TTBound.UPPER_BOUND);

        // Simulate a reader that assembled the first A-store's association word
        // with the intervening B-store's data word.
        rawTable.set(idx * 2, assocWordAfterFirstA);
        rawTable.set(idx * 2 + 1, dataWordAfterB);

        assertNull(table.probe(keyA),
                "a re-read-the-key-only check would wrongly accept this: the raw "
                        + "key is A on both sides of the race, only the data-dependent "
                        + "check word differs");
    }

    /**
     * Stress test: many threads racing store()/probe() on a table small enough
     * that every key collides into a handful of slots. Whenever a probe returns
     * a non-null entry, its key must equal the requested key and, since keys in
     * this test are unique-per-store-call encodings, the returned fields must be
     * internally consistent with that key. This does not deterministically force
     * the exact race window (JIT/scheduling dependent), but across enough
     * iterations on a multi-core host it exercises the real interleaving, and a
     * regression that reintroduces a torn-read-tolerant scheme would be expected
     * to fail this intermittently.
     */
    @Test
    void concurrentStoreAndProbeNeverReturnMismatchedEntry() throws Exception {
        TranspositionTable table = new TranspositionTable(1); // smallest size: forces heavy collisions
        int writerThreads = 8;
        int iterationsPerThread = 50_000;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(writerThreads + 1);
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicReference<AssertionError> failure = new java.util.concurrent.atomic.AtomicReference<>();

        java.util.List<java.util.concurrent.Future<?>> writers = new java.util.ArrayList<>();
        for (int t = 0; t < writerThreads; t++) {
            final int threadId = t;
            writers.add(pool.submit(() -> {
                for (int i = 0; i < iterationsPerThread && !stop.get(); i++) {
                    long key = ((long) threadId << 40) | i;
                    int depth = i & 0x7F;
                    int score = (int) (key & 0x3FFF_FFFF);
                    table.store(key, Move.NONE, depth, score, TTBound.EXACT);
                }
            }));
        }
        java.util.concurrent.Future<?> reader = pool.submit(() -> {
            for (int i = 0; i < iterationsPerThread && !stop.get(); i++) {
                for (int threadId = 0; threadId < writerThreads; threadId++) {
                    long key = ((long) threadId << 40) | i;
                    TranspositionTable.Entry entry = table.probe(key);
                    if (entry != null) {
                        int expectedScore = (int) (key & 0x3FFF_FFFF);
                        if (entry.key() != key || entry.score() != expectedScore) {
                            failure.compareAndSet(null, new AssertionError(
                                    "mismatched TT entry for key " + key
                                            + ": got key=" + entry.key() + " score=" + entry.score()
                                            + " (expected score=" + expectedScore + ")"));
                            stop.set(true);
                        }
                    }
                }
            }
        });

        for (java.util.concurrent.Future<?> f : writers) {
            f.get();
        }
        reader.get();
        pool.shutdown();

        if (failure.get() != null) {
            throw failure.get();
        }
    }
}
