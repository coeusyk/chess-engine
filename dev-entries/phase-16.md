# Dev Entries - Phase 16

---

### [2026-09-18] Phase 16 — Branch setup + P16-1 shared-TT publication correctness (Issue #227)

**Built:**

- **Branch `phase/16-search-execution-qualification` created from develop HEAD `9cf49d228495d6e320660b912893bd667cfa0919`**
  (Merge pull request #225 from coeusyk/phase/15-nnue). Phase 16 record: epic issue #226, "Phase
  16 — Search Execution Qualification," with three planned steps (shared-TT publication
  correctness, canonical 1T execution baseline, evidence review + preregistration of one search
  intervention). Only step 1 (#227) is in scope for this entry.

- **The race (#227):** `TranspositionTable` stored each entry as two independent
  `AtomicLongArray` words — a raw Zobrist key at `idx*2` and a packed data word at `idx*2+1` —
  and relied on "data is written before key" plus "probe reads key before data" to guarantee a
  reader who observed a new key also observed its matching data. That reasoning only orders each
  `store()` call's own two writes against each other; it says nothing about a concurrent reader's
  two independent reads. A reader probing key A could read the key word before a colliding
  `store(B, ...)` updated it (still seeing stale key A), then read the data word after that same
  store had already updated it (seeing B's data). `applyTtBound()` could then use B's score,
  depth, and bound as a cutoff for position A.

- **Fix:** replaced the raw-key word with a check word storing `key ^ data`. `probe()` now reads
  both words in either order, recomputes `candidateKey = check ^ data`, and only reports a hit
  when that matches the requested key. Any read that pairs a check word from one store with a
  data word from a different store fails this comparison with overwhelming probability (a
  coincidental 64-bit XOR collision), so a torn read now degrades to a miss instead of returning
  a corrupted hit. `store()`'s replacement-decision read of the existing entry derives the
  existing key the same way. `hashfull()`'s occupancy sample now tests the check word instead of
  the raw key word for nonzero-ness; behaviorally equivalent to the old test (both have the same
  theoretical, vanishingly unlikely blind spot for an entry whose key happens to be literal 0, a
  pre-existing quirk this issue did not change — see "Decisions Made").

- **Regression tests added to `TranspositionTableTest.java`:**
  - `probeRejectsAssociationWordPairedWithDataFromADifferentStore()` — deterministically
    reconstructs the exact torn-read state (stale association word, fresh data word from a
    colliding store) via reflection on the raw array, without relying on actual thread
    scheduling. Fails against the pre-fix raw-key scheme; passes against the XOR-check scheme.
  - `probeRejectsStaleAssociationWordAcrossAbaCollisionSequence()` — covers the ABA case the
    issue explicitly called out: store A, then B (collision), then A again with a different
    depth/score (collision back). The raw key word reads as A on both sides of the race window,
    so a fix that only re-reads the key would still accept this; the XOR check catches it because
    it depends on which specific write the data came from.
  - `concurrentStoreAndProbeNeverReturnMismatchedEntry()` — best-effort stress test: 8 writer
    threads plus 1 reader thread hammering a 1 MB (heavily-colliding) table for 50k iterations
    each, asserting every probe hit's key and score are internally consistent. Doesn't
    deterministically force the race (JIT/scheduling dependent) but exercises real interleaving
    on real hardware.
  - Existing `invalidPackedBoundOrdinalFallsBackToExact()` updated to poke the check word
    (`key ^ data`) instead of the raw key, matching the new internal representation.

**Decisions Made:**

- **XOR-checksum association over "re-verify the key" or a lock.** A naive fix of re-reading the
  key word a second time after reading data doesn't close the ABA case: the raw key word for a
  given position is identical across repeated stores to that position, so re-reading it gives no
  new information about which specific write the data word came from. The issue's own tasks
  explicitly required rejecting any fix with that gap. A per-slot lock would close the race too,
  but adds contention on the hottest path in the engine (every node probes the TT) for no benefit
  over the checksum approach, which is branch-free and adds no synchronization. XOR-checksum
  (Stockfish's approach) tags the association word with the data's own content, so a torn read
  is self-detecting without extra reads, extra writes, or locking.

- **Write order (data before check word) kept, but no longer load-bearing.** The old comment
  claimed correctness depended on this order; it didn't, for the reasons above. Kept it anyway
  since it's a reasonable "publish payload before publish marker" habit and there's no reason to
  invert it, but the class doc now says explicitly that correctness comes from the XOR
  recomputation, not from this ordering.

- **Key-equals-0 quirk left unchanged, on purpose.** The pre-existing code treated a stored entry
  whose Zobrist key happens to be exactly 0 as indistinguishable from an empty slot, both for
  probes (always a miss) and for `store()`'s replacement decision (always treated as replacing an
  empty slot, bypassing depth preference). This is out of scope per the issue ("do not redesign
  the replacement policy"). The new scheme preserves it without extra code: for a real key of 0,
  `check = 0 ^ data = data`, so `derivedKey = check ^ data = 0` always, exactly matching the old
  `storedKey != 0L` gate's behavior.

**Broke / Fixed:**

- None. `mvn -pl engine-core,engine-tuner -am test`: 131 tests run, 0 failures, 1 skipped
  (pre-existing skip, unrelated to this change). `TranspositionTableTest` grew from 7 to 11 tests
  (+2 torn-read regression tests, +1 concurrent stress test, +1 stats-enabled-probe-path test).
- Confirmed the two deterministic regression tests are genuine (not vacuous) by temporarily
  reverting `TranspositionTable.java` to the pre-fix raw-key scheme and re-running them: both
  failed with the exact predicted symptom (`probe(keyA)` returned B's entry —
  `Entry[key=1, bestMove=1292, depth=1, score=99, bound=LOWER_BOUND, generation=0]` — instead of
  `null`). Restored the fix afterward; full suite re-confirmed green.

**Measurements:**

| Check | Result |
|-------|--------|
| `mvn -pl engine-core,engine-tuner -am test` | 131 run, 0 failures, 0 errors, 1 skipped |
| `TranspositionTableTest` | 11 run, 0 failures |
| Pre-fix regression-test check (reverted source) | Both new deterministic tests fail as predicted |
| UCI bench (`--bench`, depth 13, 31 positions, WSL2, not a gate) | 73,089,246 nodes, 233.4s, **313,085 NPS** |

WSL2 NPS is not a valid regression signal per project convention (native-Windows-only gate); this
bench run is a sanity check that the fix didn't break search or tank throughput, not a performance
verdict. No SPRT was run, per the issue's explicit scope (none required for a correctness fix with
no search-behavior change under normal, non-racing single-threaded play). Threads=1 behavior is
unaffected by construction: with one thread there is never a second concurrent writer to race
against, so the fixed and pre-fix schemes are semantically identical for `Threads=1`; the bench run
above (single-threaded) completing cleanly with plausible node counts and NPS at every one of the
31 positions is the practical confirmation of that. Remaining SMP implication: the concurrent
stress test in `TranspositionTableTest` is best-effort (JIT/OS-scheduling dependent) and does not
prove the absence of the race, only that consistency held across the iterations it happened to
interleave; a real Lazy-SMP `Threads>1` SPRT/bench pass, if the team wants direct empirical SMP
confirmation beyond the code-level proof, is future work outside this issue's scope (Step 2/3 of
Phase 16, or a separate follow-up).

