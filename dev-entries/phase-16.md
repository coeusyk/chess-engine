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

- **XOR-checksum association over "re-verify the key" or a lock.** Four lock-free association
  strategies were on the table:

  | Strategy | Correctness | Replacement semantics | Memory | Hot-path reads/writes | Single-thread cost | SMP behavior |
  |---|---|---|---|---|---|---|
  | Do nothing (status quo) | Broken — the torn-read race this issue exists to fix | Unaffected | 2 longs/entry (unchanged) | 1 read + 1 read (probe), 2 writes (store) | Baseline | Can serve a wrong cutoff to `applyTtBound()` |
  | Re-read the key a second time after reading data, compare | Still broken for the ABA case: `store(A)`, `store(B)` (collision), `store(A)` again — the raw key value is A both before and after the race window, so a second raw-key read can't tell "old A" from "new A" apart. Rejected outright, as the issue required. | Unaffected | 2 longs/entry (unchanged) | 1 extra read on every probe hit | Small extra read per hit | Still broken |
  | Per-slot lock (e.g. `synchronized`/`ReentrantLock` array, or a spinlock word) | Correct | Unaffected | +1 word/entry for a lock, or an external lock array | Every probe and store now takes/releases a lock — turns the hottest path in the engine from lock-free into contended, on every node | Adds acquire/release overhead even with zero contention | Direct contention between search threads sharing a TT, which is the normal Lazy-SMP configuration this table exists to serve |
  | XOR-checksum: check word = `key ^ data`, probe derives `key = check ^ data` and compares (selected) | Correct, including the ABA case — the check is tied to the specific data write, not just the key value | Unaffected — replacement decision derives the same `existingKey` via XOR, same logic as before | 2 longs/entry (unchanged; the check word replaces the key word, no new field) | Same read/write count as the status quo, branch-free | No additional cost — same two reads, same two writes, just XOR instead of literal compare | Torn reads degrade to a miss (already an accepted outcome per the class doc's existing "write-write races... treated as a miss" policy), no new lock, no new contention |

  XOR-checksum was selected because it is the only option that closes the ABA case while adding no
  new reads, writes, fields, or synchronization to the hot path — it changes what the check word
  means, not how many times it's touched. This is the same scheme Stockfish uses for its own
  lock-free TT.

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

