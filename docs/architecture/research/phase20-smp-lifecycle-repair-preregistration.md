# Phase 20 SMP Lifecycle Repair: Preregistration

## 0. Status and scope

This document preregistered one bounded repair slice for the Phase 20 Stage 1 failure recorded in `dev-entries/phase-20.md` and issue #246. The repair and complete Stage 0/Stage 1 requalification passed on 2026-09-24; Stage 2 was not started. Executed evidence is in section 12 and `dev-entries/phase-20.md`.

Starting point: branch `phase/20-smp-qualification` at `a8c15627f45b6a7c9403fd8a75c06d0818520ecc` (diagnostics only). Governing qualification plan: `docs/architecture/research/phase20-smp-qualification-preregistration.md`. That plan puts the repair itself out of scope for Phase 20 (its section 15) and requires the repair slice to follow the Phase 18 repair gates (`docs/architecture/research/phase18-search-contract-qualification.md`, "Required gates for every repair slice"). This document is that separate slice.

What Stage 1 observed: at Threads=2, `setoption name Hash value 32` sent during an active `go depth 127` let a helper perform 32 shared-TT operations after the resize (17 reads, 15 writes), then 36 and 42 on two clean reruns. No helper exception, one legal bestmove each time, no post-generation or post-clear violation. Stage 2 and later stages have not run.

## 1. Source-backed root cause

### 1.1 The command path

`UciApplication.handleSetOption` applies Hash immediately on the UCI input thread (`engine-uci/src/main/java/coeusyk/game/chess/uci/UciApplication.java:473`):

```java
hashSizeMb = Math.max(1, Math.min(65536, value));
sharedTT.resize(hashSizeMb); // apply immediately to the shared TT
```

It does not look at `searchRunning`, does not set `stopRequested`, and does not wait for anything. Every other command in the loop that changes search inputs already stops the active search first: `ucinewgame` (line 220), `position` (line 232) and `quit` (line 261) set `stopRequested`, and `handleGo` (lines 649 to 668) sets `stopRequested` and joins the main search thread for up to 2 s. `setoption` is the one input-changing command that neither stops nor waits.

### 1.2 The TT publishes three plain fields non-atomically

`TranspositionTable` (`engine-core/src/main/java/coeusyk/game/chess/core/search/TranspositionTable.java`) holds its geometry in three plain, non-volatile fields: `table`, `entryCount`, `mask`. `resize()` overwrites them in this order:

```java
entryCount = ec;
table = new AtomicLongArray(ec * 2);
mask = ec - 1;
```

There is no volatile write, lock, or other happens-before edge between those stores and a search thread's later reads. `probe()` and `store()` read `mask` (through `indexFor`) and then `table`. `hashfull()` reads `entryCount` once and then reads `table` inside its sampling loop. The `AtomicLongArray` slots are atomic, but the reference to the array and the mask that indexes it are not published together.

So yes, the current resize can expose a worker to a mixed table/mask/entryCount epoch. Under the Java memory model a reader can see any combination of old and new values for the three fields, and the JIT may reorder the plain stores inside `resize()`. The combinations that matter:

| Reader sees | Grow (16 to 32 MB) | Shrink (32 to 16 MB) |
|---|---|---|
| new `mask`, old `table` | index past the end of the old array: `IndexOutOfBoundsException` | old array is larger: in bounds, but the write lands in an orphaned array |
| old `mask`, new `table` | in bounds, only the lower half of the new array is used | index past the end of the new array: `IndexOutOfBoundsException` |
| new `entryCount`, old `table` (in `hashfull`) | sample stride walks past the old array: exception | in bounds |
| old `entryCount`, new `table` (in `hashfull`) | in bounds | sample walks past the new array: exception |

This also explains why Stage 1 saw zero exceptions. The reproducer only grew the table. On x86 the hardware keeps store order and load order, and `resize()` writes `table` before `mask`, so a reader that sees the new `mask` usually also sees the new `table`. For growth that leaves the benign "old mask, new table" case. That is luck of the store order and the platform, not a guarantee. The shrink direction puts the out-of-bounds case exactly on the path x86 makes likely (new `table`, old `mask`). I have not run a shrink reproducer. That is a prediction, and section 6 turns it into a test arm.

A helper exception would be swallowed by the helper's `catch (Exception e)` in `runSearch` (line 813). The diagnostics count it, but production would hide it.

### 1.3 The main search thread is exposed to the same race

The main `Searcher` gets the same `sharedTT` (line 886). It calls `probe` (`Searcher.java:716`, `:884`), `store` (`:1167`) and, every time it prints an info line, `hashfull` (`:551`). Nothing about the main thread protects it from a concurrent `resize()`. The Stage 1 diagnostics could not show this because `HelperTrace` is a thread-local installed only on helper threads, so main-thread TT activity after the resize was never counted.

If the main thread hits an out-of-bounds read, `runSearch` has `try/finally` and no `catch`. The `finally` still emits a bestmove (falling back to `latestIterativeBestMove`), and then the exception escapes the `uci-search-thread` and goes to the default uncaught-exception handler on stderr. The GUI would see a legal-looking bestmove from a search that died partway.

Joining helpers alone is therefore not enough. The main search thread has to have finished too before the table can be replaced.

### 1.4 Helpers are never joined, so "idle" is not idle

`runSearch` submits helpers with `smpExecutor.submit(...)` (line 785) and discards the returned `Future`. Its `finally` block sets `helperAbort`, clears `searchRunning`, nulls `searchThread`, and emits bestmove. It never waits for helpers. Stage 1 confirmed this directly: the Threads=4 book go had all three helpers still pending at bestmove.

Consequences for the repair:

- `searchRunning == false` does not mean no worker is touching the TT. A Hash resize sent after bestmove, which is a fully legal UCI sequence, can still race a lingering helper from the search that just ended. The window is short because searchers poll the hard stop at every node (`Searcher.java:842` in the main search, `:1548` in quiescence), but it exists.
- A guard that only checks `searchRunning` (option 1 below) cannot close the race.
- `handleGo` only joins the previous main thread when `searchRunning` is true, and never joins helpers. The next search's `incrementGeneration()` can overlap old helpers. `ucinewgame` sets `stopRequested` and calls `clear()` without joining anything, not even the main thread. Stage 1 did not observe post-generation or post-clear violations, but the source allows both. They are the same defect with a less damaging symptom, because `clear()` and `incrementGeneration()` do not replace the array.

### 1.5 Root cause in one sentence

The UCI thread has no point at which it knows every searcher (main and helpers) from every earlier `go` has exited, so any whole-table mutation it performs (resize, clear, generation bump) can overlap live workers, and resize is the one where the overlap can index outside the array.

## 2. Command and lifecycle contract

### 2.1 Is `setoption Hash` during a search supported?

The UCI protocol says `setoption` "will only be sent when the engine is waiting". A GUI that sends it mid-search is outside the protocol, so the engine is free to define the behavior. It is not free to crash, corrupt state, or leave workers indexing a replaced table. Vex has no documented position on this today. The code applies the option immediately, which is an accident, not a contract.

This repair defines the contract. After the repair it becomes a supported sequence with defined, deterministic behavior. It is not promoted to a recommended one.

### 2.2 The contract

1. Search quiescence. A search is finished only when its main thread and all of its helpers have returned. The main search thread owns its helpers and joins them after emitting bestmove. The UCI thread therefore needs to join only one thread (`searchThread`) to know that no worker from any earlier search is alive.
2. Whole-table mutations happen only at quiescence. `resize()` (from `setoption Hash`), `clear()` (from `ucinewgame`) and the next search's `incrementGeneration()` (from `handleGo`) run only after the UCI thread has stopped and joined the previous search.
3. Mutating commands during a search stop it. `setoption name Hash` and `ucinewgame` sent during an active search behave like `handleGo` already does for "go during go": set `stopRequested`, join, and then apply. The interrupted search emits its bestmove as it does for `stop`. Exactly one bestmove per `go` still holds.
4. Bestmove latency is unchanged. The helper join happens after bestmove is emitted, on the search thread, so time-to-bestmove does not include helper exit latency. The cost moves to the next mutating command, which waits for at most the helpers' abort latency.
5. Join timeout. The UCI thread keeps `handleGo`'s existing 2 s bound. If the previous search is still alive after 2 s, `handleGo` keeps its current behavior (drop the go). `setoption Hash` and `ucinewgame` skip the TT mutation, print one `info string` saying so, and leave `hashSizeMb` untouched. In tests a timeout is a failure, not an accepted outcome (section 9).
6. `isready` after a mutating command answers `readyok` only after the mutation has been applied, because the UCI thread processes commands in order and the join happens inline. That is what `isready` is for after a slow `setoption`.

### 2.3 Effect on other commands

- `ucinewgame`: now joins before `clear()`. Behavior when idle is unchanged apart from waiting out lingering helpers.
- Subsequent `go`: `handleGo` joins the previous search thread whenever one exists, not only when `searchRunning` is true. After a normal bestmove this waits for helpers that are already aborting. That adds helper abort latency to the start of the next go at Threads>1. At Threads=1 there are no helpers and the join returns immediately on a dead thread.
- `stop`, `ponderhit`, `position`, `isready`, `eval`, `nnue`: unchanged.
- Other `setoption` names: unchanged. They are read at search start or by `runSearch` and have no demonstrated defect in this slice. See section 4.5 for the book options, which have a separate suspected race that this slice does not touch.
- `quit`: unchanged. Helpers are daemon threads and the process exits. Joining on quit would make shutdown depend on helper exit for no correctness gain.
- `bench`: unchanged. `BenchRunner` owns its own searchers and does not use `sharedTT`.

## 3. Considered repair options

### Option 1: reject or defer Hash while a search is active

Reject: when `searchRunning` is true, ignore the Hash value (or print an `info string`). Defer: store a pending size and apply it at the next idle point.

- Small diff in `handleSetOption`.
- Does not fix the bug. Section 1.4 showed `searchRunning` goes false before helpers exit, so a Hash sent right after bestmove still races. Defer has the same problem at its apply point, which still needs a "no worker alive" moment that does not exist today.
- Reject silently leaves the GUI's configured Hash and the engine's actual Hash different.
- Does nothing for `ucinewgame`'s `clear()` or the next go's generation bump.
- A variant that blocks until the search finishes on its own (Stockfish waits for the search to finish before applying `setoption`) deadlocks Vex on `go infinite` and `go ponder`, because the UCI thread that would read `stop` is the one blocked.

### Option 2: stop and join the active search (main and helpers) before resize

- Uses a pattern the file already has: `handleGo` sets `stopRequested` and joins `searchThread` with a 2 s bound.
- Needs helpers to be joinable. Today their futures are thrown away. The change is to keep the futures in `runSearch` and wait on them in `finally` after bestmove is emitted.
- Covers resize, clear and generation with the same call.
- Cost: a mid-search Hash change interrupts the search. The next command after bestmove may wait for helper abort latency (per-node polling, so expected to be sub-millisecond to low milliseconds, but measured, not assumed; see section 8).

### Option 3: publish a new TT instance and let existing workers finish on the old one

`sharedTT` becomes a volatile reference. Resize constructs a new `TranspositionTable` and swaps it in. Each search captures the reference once at start.

- Old workers keep a consistent table, so no mixed epoch.
- Two tables are alive at once until old workers exit. `MAX_ENTRY_COUNT = 1 << 24` at 16 bytes per entry caps one table at 256 MB. Two of those is 512 MB, the entire heap under the frozen `-Xmx512m`. Resizing to a large Hash mid-search could then fail with `OutOfMemoryError`.
- Does not fix `clear()`: clearing the current instance while a lingering helper holds the same instance still leaves stale-game entries. Swapping instances on `ucinewgame` too would double memory there as well.
- Leaves old helpers burning CPU into the next search, which is the lingering-helper problem Stage 3 timing was already worried about (qualification preregistration section 16, assumption 1).
- Touches every place that reads `sharedTT` and changes the injection model. Larger than the problem.

### Option 4: make the TT geometry swap atomic inside `TranspositionTable`

Pack `table`, `mask` and `entryCount` into one immutable holder published through a single volatile field, and have `probe`, `store` and `hashfull` read the holder once.

- Removes the out-of-bounds case completely, with no UCI change.
- Adds a volatile read and an extra indirection to every `probe` and `store`. That is the hottest path in the engine, and it changes TT code the qualification plan puts out of scope (section 15, "TT buckets or a new replacement policy" and SMP redesign).
- Fixes only the symptom. Helpers still write entries into the table after resize, `ucinewgame` still overlaps live writers, and L2 as preregistered ("no helper stores into the TT after ... `setoption Hash`'s `resize()`") still fails. Relaxing L2 after seeing the failure would be moving the gate to fit the result.

## 4. Selected repair and why

### 4.1 Selection

Option 2, implemented as a single quiescence helper on the UCI thread plus helper ownership by the main search thread.

### 4.2 Why

- It is the only option that closes all three overlap windows the source allows (resize, clear, generation) with one mechanism.
- It is the smallest change that satisfies the existing L2 gate without editing the gate.
- It reuses the stop-and-join idiom already in `handleGo` instead of inventing a new one.
- It touches no search, evaluation, TT layout or replacement code, so Phase 18 canonical search semantics cannot change. Threads=1 fixed-depth node counts must be byte-identical (section 8).
- It keeps bestmove latency unchanged by joining helpers after emission.

### 4.3 The shape of the change

In `runSearch`:

- Collect the `Future<?>` returned by each `smpExecutor.submit(...)` in a local list.
- In `finally`, after `emitBestMove(...)` and the existing diagnostic line, wait on each future. `helperAbort` is already true at that point, so helpers are exiting. An `ExecutionException` means a helper threw something the helper's own `catch (Exception)` did not catch (an `Error`). Log it and carry on. `InterruptedException` restores the interrupt flag and stops waiting.
- Stop nulling `searchThread` in `finally`. The UCI thread needs the reference to join a search that has emitted bestmove but is still joining its helpers. Joining a dead thread returns immediately. The `@SuppressWarnings("unused")` note on the field goes away because it is now used.

In `UciApplication`, one private method, roughly:

```java
// Returns true when no search thread (and so no helper) is alive.
private boolean stopAndJoinSearch() {
    Thread current = searchThread;
    if (current == null) {
        return true;
    }
    stopRequested.set(true);
    try {
        current.join(2000);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    }
    return !current.isAlive();
}
```

Called from three places:

- `handleSetOption`, Hash branch: `if (stopAndJoinSearch()) sharedTT.resize(hashSizeMb); else` print the `info string` and keep the previous size. `hashSizeMb` is assigned only when the resize happens.
- `ucinewgame`: replaces the bare `stopRequested.set(true)` before `sharedTT.clear()`. The `clear()` is skipped with an `info string` on timeout.
- `handleGo`: replaces the `if (searchRunning) { ... join ... }` block. The existing "bail out if still running" branch becomes "bail out if `stopAndJoinSearch()` returned false", which is the same behavior with the correct condition.

`stopAndJoinSearch()` is a UCI-thread-only method. The only other writer of `searchThread` is `handleGo`, which also runs on the UCI thread, so no new synchronization is needed.

### 4.4 Why the join lives in `runSearch`, not in `handleGo`

Keeping helper futures in a field that the UCI thread joins would also work. It needs a shared collection written by the search thread and read by the UCI thread, plus rules for which search's helpers are in it. Having the search thread join its own helpers keeps ownership where the helpers are created, keeps the field count the same, and makes "join `searchThread`" mean "join everything" by construction.

### 4.5 Deliberately not in this slice

- `BookFile` and `BookVariance` replace or reopen `openingBook` on the UCI thread while `runSearch` may call `openingBook.probe(...)` on the search thread. That looks like the same class of race, but Stage 1 did not test it and no failure has been observed. It gets a separate issue, not a silent fix here.
- Applying `stopAndJoinSearch()` to every `setoption` name would be one line at the top of `handleSetOption`. It would change behavior for options with no demonstrated defect, so it is rejected for this slice and can be revisited with the book issue.
- No change to `TranspositionTable`. The plain-field publication stays as it is, because after the repair `resize()` only ever runs with no reader alive. A code comment on `resize()` will state that it must only be called while no search is running, since that is now a caller obligation.

## 5. Rejected alternatives, summarized

| Option | Reason for rejection |
|---|---|
| 1. Reject or defer while `searchRunning` | Does not close the race, because helpers outlive `searchRunning`. Blocking variant deadlocks on `go infinite`. |
| 3. New TT instance per resize | Can double TT memory to the full 512 MB heap, does not fix `clear()`, leaves old helpers running into the next search, larger diff. |
| 4. Atomic holder inside the TT | Hot-path change to TT code that is out of scope. Fixes the crash but not the lifecycle, and needs L2 relaxed after the fact. |
| Join helpers before emitting bestmove | Adds helper exit latency to every bestmove, which is a time-management change. Joining after emission gets the same guarantee for the next command. |
| `stopAndJoinSearch()` on all `setoption` | Broader behavior change with no demonstrated defect. Deferred with the book-option issue. |

## 6. Red regression design

### 6.1 Placement

`engine-uci/src/test/java/coeusyk/game/chess/uci/UciApplicationIntegrationTest.java`. The existing `UciHarness` launches the real `UciApplication` in a child JVM. It currently sends stderr to `INHERIT`. The regression needs the `SMPDIAG` lines, so the harness gets a second factory that passes `-Dvex.smp.diagnostics=true` and reads stderr into its own queue. The existing `start()` stays as is so existing tests do not change.

Each SMP test uses `assumeTrue(Runtime.getRuntime().availableProcessors() >= N)`. `effectiveHelpers = min(threads - 1, AVAILABLE_CORES - 1)` means a machine with fewer cores silently runs fewer helpers, and a Threads=4 test that ran one helper would pass for the wrong reason. The test also asserts the `event=begin` diagnostic reports `helpers=N-1`.

### 6.2 The primary red test: active-search Hash resize

Sequence, parameterized over Threads in {2, 4} and direction in {grow 16 to 32, shrink 32 to 16}:

1. `uci`, then the Stage 1 frozen options (`Hash` at the starting size, `EvalType Classical`, `OwnBook false`, `SyzygyOnline false`, `MultiPV 1`, `Contempt 0`, `PawnHashSize 1`), `isready`, `ucinewgame`, `isready`, `position startpos`.
2. `go depth 127`. Wait for every `event=start helper=i` for search 1 and for the first `info depth` line on stdout. The literal `go infinite` falls through to this implementation's depth-4 default, so depth 127 keeps the intended active-search condition until the resize command.
3. Send `setoption name Hash value <new>`, then immediately `isready`. Do not send `stop`.
4. Assertions:
   - A. A `bestmove` line for search 1 appears on stdout before `readyok`. This is the deterministic red signal. On `a8c1562` the setoption does not stop an infinite search, so `readyok` arrives with no bestmove before it. The test fails every time, not by timing luck. (The test then sends `stop` in its cleanup so the child process shuts down.)
   - B. The bestmove is legal for the start position.
   - C. Every `event=exit` for search 1 has `after_resize=0`.
   - D. The `event=bestmove` diagnostic and all exit events report `exception=-` and `helper_exceptions=0`.
   - E. The number of exit events equals the number of submit events (every helper finished).
   - F. No uncaught exception text from `uci-search-thread` appears on stderr (covers the main thread, which the helper trace cannot see).
5. Then `go depth 6`, and assert one legal bestmove and zero helper exceptions for search 2, and that search 2's `event=begin` shows the resize epoch already in place (search 2's helpers report `after_resize=0` against their own start snapshot).

Assertion A turns red deterministically. C is expected red on the current code for the grow arm, based on the 3 of 3 Stage 1 runs. For the shrink arm, D or F is predicted to go red as well (section 1.2), and that is the highest-risk prediction in this document. Before the fix, the red run records which of A to F failed in each arm. If the shrink arm does not show an exception, that is written down as observed, and the prediction is marked unconfirmed rather than dropped.

### 6.3 Supporting tests

| Test | Threads | Sequence | Must hold after repair |
|---|---|---|---|
| Resize then immediate go | 2, 4 | active `go depth 127`, `setoption Hash`, `go depth 6` with no `isready` in between | two bestmoves in order, both legal; search 1 helpers `after_resize=0`, search 2 helpers see no later boundary; zero exceptions |
| Resize then `isready` | 2, 4 | covered by 6.2 step 3; also idle `setoption Hash` then `isready` | `readyok` arrives; for the active case it comes after bestmove |
| Idle resize | 1, 2, 4 | `go depth 6`, wait for bestmove, `setoption Hash 32`, `go depth 6` | no extra bestmove emitted by the setoption; search 1 helpers `after_resize=0` (this is the lingering-helper case from 1.4) |
| Idle resize with nothing ever searched | 1 | `setoption Hash 32`, `isready` | `readyok`, no bestmove, no exception (`searchThread == null` path) |
| stop then go | 2, 4 | existing Stage 1 stop-to-go sequence | unchanged: one legal bestmove per go, old helpers `after_generation=0` |
| `ucinewgame` during active search | 2, 4 | `go infinite`, `ucinewgame`, `isready` | one bestmove before `readyok`; old helpers `after_clear=0`; zero exceptions |
| `ucinewgame` then go | 2, 4 | existing Stage 1 clear-to-go sequence | unchanged: `after_clear=0` |
| Threads=1 active resize | 1 | 6.2 sequence at Threads=1 | bestmove before `readyok`; zero helpers submitted |
| Existing 1000-search stress | 2 | `lazySmpNoDeadlockOver1000Searches` | still passes inside its 2 s per-search bound |
| Existing `stopReturnsBestMovePromptly` | 1 | existing | still under 1500 ms |

"No stale worker accesses a replaced TT" is the selected contract, so it is asserted directly by `after_resize=0` for helpers and by the join ordering for the main thread (a thread that has been joined cannot touch the table).

### 6.4 Red evidence required before any fix

Following the Phase 18 gate 1, the new tests are committed first and run against the unchanged production code (`a8c1562`). Record for each test and arm: pass or fail, which assertion failed, and the diagnostic counts. Tests that pass on the unchanged code (for example the idle and Threads=1 regression guards) are expected to pass and are recorded as guards, not red tests. At least 6.2 assertion A must fail in every SMP arm. If it does not, stop (section 9).

Executed against unchanged production source `a8c15627`, with test-only commit `b56dfdf`: assertion A failed in all four SMP arms because `readyok` preceded the search's bestmove. Helper TT operations after resize were T2 grow 4, T2 shrink 2, T4 grow 5 (helper counts 3, 0, 2), and T4 shrink 3 (1, 0, 2). Every helper exit reported `exception=-`; there were no uncaught main-search exceptions. The shrink exception prediction was not observed.

## 7. Implementation scope

Expected files:

- `engine-uci/src/main/java/coeusyk/game/chess/uci/UciApplication.java`: `stopAndJoinSearch()`; call sites in `handleSetOption` (Hash only), `ucinewgame`, `handleGo`; helper futures and post-bestmove join in `runSearch`; stop nulling `searchThread`.
- `engine-core/src/main/java/coeusyk/game/chess/core/search/TranspositionTable.java`: comment on `resize()` stating the caller obligation. No code change.
- `engine-uci/src/test/java/coeusyk/game/chess/uci/UciApplicationIntegrationTest.java`: diagnostics-capable harness factory and the tests in section 6.
- `tools/phase20_hash_resize_repro.py`: kept, extended with a shrink option, and its pass condition inverted once the repair lands (see section 11).
- `dev-entries/phase-20.md`: repair entry with red and green evidence.
- `docs/architecture/research/phase20-smp-qualification-preregistration.md`: a short status note pointing at this slice, and the resolution of its section 16 assumption 1 (see section 10).

Not touched: `Searcher`, evaluation, move generation, `TimeManager`, TT layout and replacement, helper start-depth stagger, result selection, `BenchRunner`, `chess-engine-api`, `engine-tuner`.

## 8. Validation matrix

| Check | Command or evidence | Pass criterion |
|---|---|---|
| Red run | new tests on `a8c1562` | 6.2 assertion A fails in every SMP arm; per-assertion results recorded |
| Focused green | new tests on the repair commit | all pass |
| UCI module tests | `mvn -pl engine-core,engine-uci -am test` | all pass, no `-DskipTests`, no disabled tests |
| engine-core + tuner | `mvn -pl engine-core,engine-tuner -am test` | all pass (TT file only gains a comment, but it is in engine-core) |
| Threads=1 canonical search | Stage 0 protocol from the qualification plan: 31-position depth 13 through UCI at Threads=1, plus the five depth-8 reference rows | 24,780,049 main nodes; five rows identical in move, score, nodes, qnodes, TT hits and PV |
| Bench | `java -jar engine-uci/target/engine-uci-<version>-SNAPSHOT.jar --bench` | node totals identical to the pre-repair commit. NPS is not gated here: this is WSL2 and the change is outside the search loop |
| Helper join latency | new diagnostic field, or computed from existing `exit_ms` minus `bestmove_ms`, over the 2T and 4T tests | reported as a distribution (max and median). No threshold is preregistered because there is no repository evidence for one. Any single join over 100 ms is investigated before merge |
| Diagnostic neutrality | Threads=1 depth-13 corpus with `-Dvex.smp.diagnostics` on and off | identical node totals, as in Stage 0 |
| Reproducer | `tools/phase20_hash_resize_repro.py` grow and shrink, Threads=2 and 4, three runs each | every run: bestmove before the post-resize `readyok`, `after_resize=0`, zero helper exceptions |
| Review | `chess-engine-reviewer` or `/code-review` on the slice diff | no unresolved correctness finding |

Node counts are compared exactly because this slice must not change search. Any node, qnode, TT-hit, PV, score, depth or move difference at Threads=1 is a stop condition, not something to explain away (Phase 18 gate 6 requires the mechanism for every change, and this slice has no mechanism that could produce one).

No SPRT, no games, no timing claims. Native-Windows NPS is not required for this slice because the hot path is untouched. If a reviewer disagrees, the native-Windows bench floor from CLAUDE.md section 4 is the gate, run on Windows only.

## 9. Stop conditions

- 6.2 assertion A passes on the unchanged code in any SMP arm. That would mean `setoption` already interacts with the search in a way this reading missed. Stop and re-read before changing anything.
- Any Threads=1 fixed-depth node, move, score or PV difference after the repair.
- Any helper or main-thread exception in any green run.
- Any `stopAndJoinSearch()` timeout (the 2 s bound is hit) in any test. That means a searcher does not honor `stopRequested` promptly, which is a separate defect, and extending the timeout to make the test pass is not allowed.
- The existing 1000-search stress test or `stopReturnsBestMovePromptly` regresses.
- Making the tests pass would need a change to `Searcher`, the TT's hot path, the replacement policy, or result selection. That is outside this slice and goes back to planning.
- The repair turns out to need a second mechanism beyond `stopAndJoinSearch()` plus helper futures. Stop and revise this document instead of growing the slice.

## 10. Criteria for returning to Phase 20 Stage 1

Qualification may resume only when all of the following hold on the repair commit:

1. Every row of section 8 passes and the red-then-green evidence is in `dev-entries/phase-20.md`.
2. The slice is committed on its own, per the Phase 18 gates.
3. A fresh jar is built and its SHA-256 recorded. The Stage 0 freeze is re-established on it: Stage 0 must be rerun in full (31-position depth-13 total and the five depth-8 rows, instrumentation on and off), because the jar under test has changed even though search has not.

Stage 1 gates to rerun, all at Threads=2 and Threads=4 with Threads=1 as control:

- L1 in full: every go path (normal, forced, zero-move, book, searchmoves, MultiPV) gives exactly one legal bestmove, now including go after an active-search Hash change and after an active-search `ucinewgame`.
- L2 in full: the next-go generation, `ucinewgame` clear, and `setoption Hash` resize boundaries, the last one both active and idle, grow and shrink. The resize arm at Threads=4 was never run in the original Stage 1 and must be run now.
- L3: zero helper exceptions across all of the above, plus zero uncaught main-search-thread exceptions.
- L4: helper exit latency after abort, reported. The repair settles L4's open design question: the invariant becomes "no helper overlaps the next mutating command or the next search", not "no helper outlives bestmove".
- L6: book hit then immediate go.
- L8: Threads=1 submits no helpers and Stage 0 reproduces exactly.

L5 (searchmoves, MultiPV) and L7 (main-only result ownership) are not affected by the change, but Stage 1 is a gate on the jar, not on the diff, so they are rerun too rather than carried over. That makes the rerun the whole of Stage 1.

The qualification plan's section 16 assumption 1 ("whether a lingering helper is a defect to fix first") is resolved by this slice: it is treated as a defect and removed. The note added to that document says so, and Stage 3's harness may then rely on production joining helpers instead of waiting for them itself.

Stage 2 does not start until the Stage 1 rerun passes.

## 11. Treatment of the existing diagnostic instrumentation

Keep all of it. It stays off by default behind `-Dvex.smp.diagnostics`, and Stage 0 showed it does not change node counts in either state.

- The helper TT activity counters (`after_abort`, `after_generation`, `after_clear`, `after_resize`) are what the red and green tests assert on, and what the Stage 1 rerun measures. Removing them would remove the evidence.
- `TranspositionTableTest.helperDiagnosticsTrackActivityAfterAbortAndLifecycleBoundaries` stays as is.
- `tools/phase20_hash_resize_repro.py` stays as the standalone reproducer. After the repair its exit condition flips: today it exits non-zero when it fails to reproduce the resize activity. After the repair it must exit non-zero when it does see `after_resize > 0` or a missing bestmove before `readyok`. It also gains a Hash-direction argument for the shrink arm. Its `rtk proxy java` launch prefix only works on a machine with `rtk` installed, which is fine for a tools script but is recorded here so nobody copies it into a test.
- One gap is noted and not fixed in this slice: main-thread TT activity is not traced, because `HelperTrace` is installed only on helpers. After the repair the main thread is covered by join ordering, and the uncaught-exception check in 6.2 F covers crashes. Adding main-thread tracing would be a diagnostics change for a later stage if Stage 1 needs it.
- Whether the diagnostics are removed after Phase 20 is decided when Phase 20 closes, not here.

## 12. Execution record — 2026-09-24

### Repair and red/green evidence

- The repair is confined to `UciApplication`, a caller-obligation comment on `TranspositionTable.resize()`, the opt-in resize reproducer, integration regressions, and these records. No Searcher or TT hot-path/replacement code changed.
- `runSearch` retains helper Futures, sets the helper abort flag, emits one bestmove, joins its helpers, then clears `searchRunning`; `searchThread` remains available for UCI-thread joins. `stopAndJoinSearch()` uses the existing 2 s bound at Hash resize, `ucinewgame`, and previous-search quiescence in `handleGo`.
- `ucinewgame` applies board, TT, book and new-game changes only after a successful join. On timeout it reports the timeout and leaves those fields unchanged. Hash size is updated only after join and successful resize. No timeout occurred in the validation matrix.
- The primary active-resize regression passed in all four T2/T4 grow/shrink arms after repair. The retained reproducer ran three times per arm: 12/12 passed, with one legal bestmove per `go`, bestmove before the post-resize `readyok`, zero helper exceptions, and `after_resize=0` for all helpers. Search 2 also had zero `after_resize` activity.
- The idle resize with helpers still pending at bestmove passed at T2 and T4: one and three helpers respectively were pending; each then exited with `after_resize=0`, and resize emitted no extra bestmove.

### Stage 0 on the repaired jar

- UCI jar SHA-256: `2be9571d8c8852d654e0551ad75f270619ed5a4ce1bbc294027b11c3b95e36cb`. Environment: WSL2 Ubuntu 24.04.4, OpenJDK 21.0.12, 16 processors; JVM flags `-Xms512m -Xmx512m -XX:+UseG1GC --add-modules jdk.incubator.vector`.
- Diagnostics off and on: all 31 positions reached depth 13; both summed to 24,780,049 main nodes. Both produced the same move/score/nodes/PV vector (SHA-256 `f289ff4f316dfd25e2d60eaef9e9e8339335ee8b25147da0899c353576468477`). The five-position depth-8 visible vector also matched between modes (SHA-256 `99d6ea0f9f6b5e0c50c573edc038e1cc5c805aed7b9161568ade76008f5a646b`).
- With diagnostics enabled, all five depth-8 rows matched P18-5 including qnodes and TT hits: `(nodes, qnodes, TT hits)` = `(14926,39927,4908)`, `(1226,1816,1024)`, `(34694,88266,14691)`, `(6456,14776,1938)`, `(8902,16736,3982)`. All 36 instrumented searches reported zero helpers and no exceptions. The diagnostics-off UCI info format does not expose qnodes or TT hits; those fields were directly checked in the diagnostics-on summaries against the frozen reference.
- `java -jar ... --bench` completed all 31 depth-13 positions at exactly 24,780,049 nodes. No timing or scaling conclusion was drawn from WSL2.

### Stage 1 requalification

- Full `engine-core,engine-uci` Maven suite passed; this includes `lazySmpNoDeadlockOver1000Searches`. Full `engine-core,engine-tuner` suite passed. The focused active-resize regression passed all four arms.
- The Stage 1 UCI matrix passed at T2 and T4: active Hash resize with `isready`; active resize then immediate `go`; active `ucinewgame` then `go`; stop then go; idle resize with lingering helpers; repeated searches; 75 ms movetime abort; book hit and immediate go; searchmoves `{e2e4,d2d4}` with MultiPV=2; forced move; and zero-legal-move path. The T1 active resize and no-prior-search idle resize also passed, with no helper submissions.
- Every `go` emitted exactly one legal bestmove, consistent with main-thread-only result ownership. Helper and uncaught main-search exception counts were zero. No join timeout occurred. All helper exits had zero activity after generation bumps, TT clears and Hash resizes. Activity after abort was observed (25 operations across the Stage 1 diagnostic matrix); it did not cross any later lifecycle boundary.
- Bestmove-to-helper-exit latency from the diagnostic timestamps: across the 64-exit full matrix the median was 0 ms and maximum 3 ms; in the dedicated idle-resize runs with helpers pending at bestmove, 7 exits had median 6 ms and maximum 9 ms. These integer-millisecond values are lifecycle observations only; no performance claim or threshold is applied.
- `BookFile`/`BookVariance` mutation while a search may read the book remains the separately identified follow-up risk. Neither option was changed here. Stage 2 was not started.
