# Phase 20 Lazy SMP Qualification: Preregistration

## 0. Status and scope

This was the preregistration for Phase 20. Stage 0 and the full Stage 1 requalification passed on 2026-09-24 after the separately preregistered lifecycle repair. Stage 2 ran on native Windows on 2026-09-24; separate-process N=2 median per-worker retention fell below the observed 1T range, so the result remains historically environment-limited and stopped under the original section 13 rule. Stage 3 and all later stages remain unstarted; Stage 3 now resumes under the post-Stage-2 interpretation amendment committed as `edd406a9243fcb53c341599d8c04e3fa7f34cab7`. Repair, requalification, and Stage 2 results are recorded in `docs/architecture/research/phase20-smp-lifecycle-repair-preregistration.md` and `dev-entries/phase-20.md`. `Searcher` remains unchanged; the UCI layer retains opt-in lifecycle diagnostics and the bounded quiescence repair. The Phase 20 base is the post-#245 `develop` head `85b201a2a38f23df09ef2aa758ead77fd6d859ee`.

## 1. Corrections to the planning brief

1. The historical "1T beats 2T/4T" evidence is thinner than the brief suggested. The repository holds three pieces of evidence, and none of them measures SMP scaling:
   - Phase 7 (`dev-entries/phase-7.md`, 2026-03-29): one 2T vs 1T SPRT at 5+0.05. It stopped after 34 games at -152 +/- 120 Elo, and the entry itself blames time losses and White bias at that TC.
   - Phase 13 (`dev-entries/phase-13.md`, 2026-04-10): the "confirm 2T NPS >= 1T NPS" bench was left as "TBD" and never recorded. A later Lazy SMP SPRT "was killed at H0", and no numbers were logged.
   - All three came before the packed `AtomicLongArray` TT refactor (Phase 13), the P16-1 lock-free TT fix, and the four Phase 18 repairs.
2. Production has no result-selection mechanism that could be defective. `UciApplication.runSearch` builds `bestMoveToEmit` only from the main `Searcher`'s `SearchResult` (or `latestIterativeBestMove`). Helper results are thrown away when `iterativeDeepening` returns. So "result-selection defect" can only mean that the main-only policy wastes helper work. It cannot mean a buggy vote.
3. The canonical baseline is a node-count reference, not a timing reference. The Phase 18 P18-5 baseline (`6afc523`, 24,780,049 main nodes, depth 13) was run under WSL2. That document says its NPS is descriptive only, and CLAUDE.md section 4 says WSL2 NPS is not a valid regression signal. Every SMP timing measurement therefore needs a fresh native-Windows 1T control taken in the same session. The Phase 16 native numbers (73,089,246 nodes) came before Phase 18 and can't be used.
4. SMP can't be measured with the existing bench. `BenchRunner.run` builds one `new Searcher()` per position with a private 16 MB TT, so it only ever runs 1T. Lazy SMP lives only in `UciApplication.runSearch`. Any 1T/2T/4T measurement has to go through the UCI path, or through a harness that runs `runSearch` itself. A copy of that logic would test the copy, not production.
5. UCI node and NPS output counts the main thread only. `printInfoLine` reports `IterationInfo.nodes`, which is the main `Searcher`'s `totalNodes`. Any earlier "2T NPS" read from UCI output left out every helper node, so it could never have shown scaling.

## 2. Current SMP architecture as implemented

These are observed source facts from `engine-uci/.../UciApplication.java` (`handleGo`, `runSearch`, field declarations), `engine-core/.../search/Searcher.java` and `engine-core/.../search/TranspositionTable.java`.

- Thread model. `handleGo` starts one `uci-search-thread`, which runs `runSearch`. `runSearch` submits `effectiveHelpers = min(threads - 1, AVAILABLE_CORES - 1)` tasks to `smpExecutor`, a cached thread pool of daemon threads at `NORM_PRIORITY - 1`. The main search then runs on the `uci-search-thread` itself.
- Helper setup. Each helper gets a fresh `new Searcher()` and a `new Board(positionFen)` built from the same snapshot string as the main board. It also gets the shared TT, the same pawn-hash size and contempt, and its own `NnueEvaluator` if NNUE is active.
- Helper search call. `helper.iterativeDeepening(board, MAX_SEARCH_DEPTH=127, startDepth, stop, stop, null)`. Soft and hard stop are the same supplier, `helperAbort.get() || stopRequested.get()`. Helpers get no `TimeManager` and no listener.
- Diversification. The only mechanism is start depth: `startDepth = (i % 2 == 1) ? (i/2) + 2 : 1`. With 2T the helper starts at depth 2. With 4T the helpers start at 2, 1 and 3. There is no per-thread variation in ordering, reductions, windows or root move order.
- Settings helpers never receive: `multiPV`, `searchMoves`, the Syzygy prober and the root TT-move hint all stay on the main thread.
- Generation. `sharedTT.incrementGeneration()` runs once per `go`, before any helper is submitted.
- Result ownership. The emitted move comes from the main `SearchResult` only. The main thread's `TimeManager` alone decides when timed searches stop, and its stability scaling (`setStabilityScale`) looks at the main thread's best move only.
- Termination. The `finally` block sets `helperAbort`, then `searchRunning = false`, then emits `bestmove`. Helpers are never joined, and no helper `Future` is kept. Hard stop is checked at every `searchRoot` move, every `alphaBeta` entry (around line 842) and every `quiescence` entry.
- Helper failures. `catch (Exception ignored)` swallows them without logging.
- Book probe. It runs after the helpers have been submitted. When the book returns a move, the helpers start and are then aborted.
- TT design. An `AtomicLongArray` holding two longs per entry, an XOR check word plus a data word, with one entry per index (direct-mapped). Every `get`/`set` is a volatile access. The replace rule is to always replace when the key differs; when the key matches, replace if the entry is `AGE_THRESHOLD`=4 or more generations old or if `depth >= existingDepth`. `probe` allocates an `Entry` record on each hit. `table`, `mask` and `entryCount` are non-volatile fields that `resize` reassigns. `clear()` zeroes the table in place.
- TT statistics. Stats are shared `AtomicLong`s. Every `iterativeDeepening` call, helpers included, runs `transpositionTable.resetStats()` on the shared table.

## 3. Ownership matrix

| State | Owner | Source |
|---|---|---|
| Transposition table (entries, generation, probe/hit stats) | Shared, one instance | `UciApplication.sharedTT`, `Searcher.setSharedTranspositionTable` |
| `stopRequested` | Shared, global across searches | `UciApplication` field |
| `helperAbort` | Shared within one `go`, one new instance per search | `runSearch` local |
| `threads`, `pawnHashSizeMb`, `contempt`, `nnueNetworkForSearch` | Read by all threads; written only by the UCI thread | `UciApplication` fields and `runSearch` local |
| `NnueNetwork` weights | Shared, read-only | `resolveNnueNetworkForSearch` |
| `EvalParams` statics | Shared statics, mutable only at startup override (`phase-13.md:473` states read-only during play) | `EvalParams` |
| Board / make-unmake state | Private: a separate `Board` per thread | `runSearch` |
| Killers, history, correction history, static-eval stack, PV table, move-list pool, root move list | Private, per `Searcher` | `Searcher` fields |
| `MoveOrderer`, `StaticExchangeEvaluator`, `scoringBuffer` | Private, per `Searcher` | `Searcher` fields, `phase-7.md:433` |
| Evaluator including pawn hash; NNUE accumulator | Private, per `Searcher` | `Searcher.evaluator`, `setPawnHashSizeMb` |
| Node and cutoff counters, `aborted`, `seldepth` | Private; only the main thread's are reported | `Searcher` fields |
| `TimeManager`, stability scale | Main thread only (`null` in helpers) | `searchWithTimeManager` |
| Emitted result | Main thread only | `runSearch` |

## 4. Hypotheses, ranked by diagnostic priority

Priority reflects how much each hypothesis would contaminate every later measurement, not how likely it is.

- H1, lifecycle and correctness. Helpers from search N keep running into search N+1, or past `ucinewgame`'s `clear()`, or past `setoption Hash`'s `resize()`. The source allows all three, because helpers are never joined. Possible effects are stale stores into the TT at the new generation, CPU contention with the next search, and an inconsistent `table`/`mask` read during resize, which a helper would swallow silently. There is also a smaller source-backed issue: the shared TT stats get reset by every helper start. If H1 holds, every timing number taken afterwards is suspect.
- H2, environment and JVM contention. Several busy threads in one JVM lose per-thread throughput to things other than the shared TT: GC and safepoints (the per-hit `Entry` allocation is multiplied across threads), memory bandwidth, SMT sibling placement, turbo behavior, or the `NORM_PRIORITY - 1` helper priority on Windows.
- H3, TT contention. Coherence traffic on shared cache lines (four entries per 64-byte line) plus volatile stores lowers per-thread NPS beyond what H2 explains.
- H4, TT capacity and destructive replacement. At Hash=16 MB (1M entries), the always-replace-on-collision rule lets shallow helper entries evict deeper main-thread entries, so TT help saturates or turns negative as thread count grows.
- H5, under-diversification. The helpers, especially at 2T with only a one-depth stagger, search mostly the same nodes as the main thread, and their TT deposits are mostly redundant.
- H6, time management and result ownership. Under a clock, the main thread's stability heuristic and main-only move selection waste helper depth. A helper may have finished a deeper iteration with a different move, and that iteration gets discarded.
- H7, ordinary parallel-search scaling limits. Everything above is clean and scaling is simply sublinear, as expected for Lazy SMP. This is the null outcome for "defect", not a failure.

These can occur together, so the result is a multi-label classification (section 12).

## 5. Experimental stages in minimum order

Every stage fixes the controls in section 6. The stages are ordered so that no timing number is collected before the things that could contaminate it have been ruled out.

### Stage 0: freeze and harness validity

- Question. Does the SMP measurement path reproduce the canonical 1T semantics exactly?
- Measurement. Drive the production UCI path at `Threads=1`, `Hash=16`, Classical. For each of the 31 `BenchRunner.BENCH_FENS` positions, send `ucinewgame`, `position fen`, `go depth 13`, and sum main nodes. Repeat for the five-position depth-8 reference. Run once with the diagnostic instrumentation off and once with it on.
- Control. P18-5: 24,780,049 nodes over 31 positions, plus the five-position table (moves, scores, nodes, qnodes, TT hits, PVs). The UCI defaults `contempt = 0` and `pawnHashSizeMb = 1` match the bench defaults (`Searcher.contemptCp = 0`, `Evaluator.DEFAULT_PAWN_HASH_MB = 1`), so a match is expected, but this stage has to confirm it.
- Pass. Node counts match exactly, both with instrumentation off and with it on.
- Next. On pass, go to Stage 1. On fail, look for an input difference between the UCI and bench paths (for example `searchDepth` vs `iterativeDeepening`, or a cleared TT vs a fresh TT). Record a UCI-path 1T reference only if the difference is explained. If instrumentation changes node counts, that is a harness bug and has to be fixed before continuing.

### Stage 1: lifecycle and result-ownership correctness gates (no timing claims)

- Question. Is Lazy SMP correct and well contained at 2T and 4T?
- Measurement. The gates in section 11, using the lifecycle instrumentation in section 6.
- Control. Threads=1 on the same command sequences, where no helpers exist.
- Pass. Every gate in section 11 holds.
- Next. On pass, go to Stage 2. On any failure, classify as correctness flawed or lifecycle limited (section 12), stop the timing stages, and preregister a repair slice under the Phase 18 repair gates. The repair itself is out of scope for Phase 20 (section 15).

### Stage 2: environment and JVM ceiling (H2)

- Question. How much per-thread throughput does this machine and JVM keep with N busy searchers when no state is shared?
- Measurement. Per-thread NPS retention `r(N) = median per-thread NPS at N / 1T NPS`, measured three ways on the 31-position depth-13 set:
  - (a) N separate JVM processes, each running 1T with a private 16 MB TT.
  - (b) N independent `Searcher`s in one JVM, each with a private 16 MB TT and no shared state.
  - (c) The production shared-TT configuration, measured in Stage 3.
- Control. A 1T run in the same session, interleaved with the other arms.
- Decision. If (a) is low, the ceiling is hardware or OS; record it and don't blame SMP for it. If (b) is meaningfully below (a), that is JVM-level contention, and GC logs and JFR get attached to the (b) arm only. What counts as "meaningfully below" is unresolved. It has to be outside the run-to-run spread measured in this stage, and there is no repository-backed fixed percentage.
- Next. Always go to Stage 3. Stage 2 is the denominator for Stage 3's contention attribution.

### Stage 3: fixed-depth 1T/2T/4T mechanism qualification

- Question. Does production Lazy SMP make the main thread reach depth faster, and where does the extra CPU go?
- Measurement. Per position at `go depth 13`: main-thread time to depth (TTD), main-thread nodes to depth, total nodes across all threads, per-thread NPS retention `r_shared(N)`, and final `hashfull`.
- Control. The 1T arm in the same session, plus Stage 2 arms (a) and (b).
- Decision. TTD speedup at 2T and 4T outside the 1T spread, together with falling main nodes to depth, means SMP helps through the TT. `r_shared(N)` below Stage 2 (b) means TT contention (H3). TTD flat while total nodes grow and `r_shared` stays close to (b) means the extra work is wasted (H4 or H5), and Stages 4 and 5 separate the two.
- Next. Stages 4 and 5 run when Stage 3 shows no speedup, sublinear speedup that Stage 2 doesn't explain, or 4T worse than 2T. If Stage 3 shows speedup beyond noise at both 2T and 4T and nothing looks anomalous, Stages 4 and 5 become optional characterization and the plan goes to Stage 6.

### Stage 4: TT capacity and contention controls (H3, H4)

- Question. Is the SMP result limited by TT size or by destructive replacement?
- Measurement. Repeat Stage 3 at a larger Hash. The value is unresolved; it should be chosen from Stage 3's `hashfull` at 16 MB so that 4T isn't saturated. Also collect replacement telemetry per writer thread: same-key overwrite count, different-key eviction count, and evictions where the evicted entry's depth was >= the new entry's depth, split by whether the evicted writer was the main thread.
- Control. 1T at both Hash sizes, so the main effect of Hash on 1T is measured separately from its effect on SMP.
- Decision. If N-thread TTD speedup improves with Hash while 1T barely changes, the result is capacity limited. If evictions of deeper main-thread entries by helpers make up a large share of main-thread entry losses at 16 MB and shrink at the larger Hash, the problem is destructive replacement. If neither, H4 is ruled out.
- Next. Stage 5 if H5 is still open. Replacement is not redesigned in this phase.

### Stage 5: worker overlap and diversification (H5)

- Question. How redundant is the helpers' search, and does the start-depth stagger change that?
- Measurement. A diagnostic build only, never used for timing. For each thread and depth, sample the set of node Zobrist keys visited. Compute overlap of the main thread with each helper as Jaccard similarity per depth, and record each helper's depth lead over the main thread over time.
- Ablation. All helpers start at depth 1, with no stagger. This is diagnostic only and is not a promotion candidate. It tests whether the only diversification mechanism does anything.
- Control. Two independent 1T searches of the same position with private TTs give the overlap ceiling for identical deterministic search, which should be close to 1. The production arm is compared against that ceiling and against the ablation.
- Decision. High overlap, no difference in overlap or TTD between stagger and no stagger, and a clean Stage 4 together mean under-diversified. Low overlap without a TTD gain points back to H4 or H6.
- Next. Stage 6 only if the entry conditions in section 8 are met. Otherwise classify and stop.

### Stage 6: fixed-time practical qualification

Conditional; see section 8.

### Stage 7: strength-test justification

A decision only. Nothing is run in Phase 20; see section 14.

## 6. Frozen controls, measurement protocol and instrumentation

### Frozen controls

- The Stage 0 commit SHA and jar SHA-256.
- JDK vendor and version, with JVM flags frozen across all arms: explicit `-Xmx`, explicit GC choice, `--add-modules jdk.incubator.vector`. `phase-13.md` recommends `-Xmx512m`, or larger with more threads. The exact heap value is unresolved. It must cover the TT, one pawn hash per thread and GC headroom at 4T with the largest Stage 4 Hash, and it stays the same for every arm.
- UCI options: Classical evaluator, `OwnBook=false`, Syzygy off, `MultiPV=1`, no ponder, `Contempt=0`, `PawnHashSize=1`, and `Hash=16` everywhere except Stage 4.
- Thread counts are 1, 2 and 4 only. The Ryzen 7 7700X has 8 physical cores and 16 logical CPUs, so 4T fits on physical cores. Whether to pin affinity to one thread per physical core is unresolved (see section 16, item 2). Whatever is chosen gets recorded and used in every arm.
- Native Windows for every timing number (CLAUDE.md sections 4 and 6). WSL2 is acceptable for Stage 0, Stage 1 and the node and overlap parts of Stage 5, because none of them depend on timing.
- No other load on the machine, and the power plan recorded.

### Protocol

- Timing arms follow the Phase 16/18 pattern: one discarded warm-up, then seven measured passes over the 31 positions.
- Arms are interleaved (1T, 2T, 4T, 1T, ...) rather than run as blocks, so drift and heat don't line up with any one arm.
- SMP runs don't repeat exactly, so median and spread are reported for each arm. The 1T spread is the noise floor. P18-5 reported a CV of 1.70% under WSL2, which is a reference only.
- Before each position: `ucinewgame`, then confirm every helper from the previous search has exited (lifecycle instrumentation), then `isready`. The harness must not start a position while old helpers are still running, or Stage 3 is contaminated. This rule depends on the Stage 1 lifecycle telemetry.

### Instrumentation

Each item is listed because a decision depends on it.

1. Per-thread node counts and elapsed time, reported at search end. UCI `nodes` counts the main thread only, so per-thread NPS and total work (Stages 2 and 3) can't be measured without this. The helper `Searcher`s already count `nodesVisited`; the counts only need to be exposed.
2. A helper lifecycle record: submit time, start time, exit time relative to `helperAbort` and `bestmove`, exit cause, and exception count with stack trace. Every Stage 1 gate and the Stage 3 guard between positions need it. The swallowing `catch` has to become counted and logged, at least in the diagnostic build.
3. Last TT store time per helper, and counts of stores after `helperAbort`, after the next `incrementGeneration`, and after `clear()`. This lets gate L2 be decided from observation rather than inference.
4. Deepest completed depth and best move for each helper at exit. Needed for H6 in Stage 6. It is passive and does not change the emitted move.
5. TT counters per thread (thread-local, not shared atomics) and the replacement classes from Stage 4. The existing shared `AtomicLong` stats are reset by every helper's `iterativeDeepening` and would add their own contention. Needed for H3 and H4.
6. Node-key overlap sketch. Diagnostic build only, allocation allowed, never used in a timing run. Needed for H5.
7. GC log and JFR, only in the Stage 2 (b) arm and only if Stage 2 shows a JVM gap. Nothing is attached by default.

Not added: a thread tag inside production TT entries, or any change to search decisions. Items 1 through 4 must be free at `Threads=1` (exact Stage 0 nodes), and must show no 1T NPS change beyond the noise floor on native Windows before any timing stage uses them.

## 7. Fixed-depth 1T/2T/4T qualification

This is Stage 3, with Stage 2 as the denominator and Stages 4 and 5 as conditional follow-ups.

Fixed depth measures mechanism only. At a given nominal depth, a Threads>1 main thread may read deeper helper TT entries, so a different move or score from 1T is expected nondeterminism and not a correctness failure. Moves and scores are recorded but not used as gates at this stage.

## 8. Fixed-time qualification (only if earlier gates pass)

- Entry conditions. All of Stage 1 passes, and Stage 3 shows main-thread TTD speedup outside the 1T spread at 2T.
- Question. At equal wall time, does Threads>1 reach deeper or pick moves that agree better with a deeper reference, and does the time manager behave the same way?
- Measurement. `go movetime T` over the 31 positions, recording main-thread completed depth, nodes, the move, whether it agrees with a deeper 1T reference move, and the helper records from instrumentation item 4. Separately, `go wtime/btime/winc/binc` from fixed clock states, recording time used per move and the stability-scale path for 1T vs NT. The values of T, the clock states and the reference depth are unresolved. They should match the TC a later strength test would use.
- Control. 1T at the same T and clock states, interleaved.
- Decision. Depth gain and reference agreement both no worse than 1T, with time usage not inflated, means SMP is practically useful at this T. If a helper often finished deeper with a different move that agrees better with the reference, the result is result-selection limited; how often counts as "often" is unresolved, so the distribution is reported rather than a cutoff. If time used rises at NT with no depth gain, the result is time-management limited.

## 9. TT capacity and contention controls

This is Stage 4 plus the Stage 2 and 3 comparison of `r_shared(N)`, `r(b)(N)` and `r(a)(N)`. Capacity and contention are separated because contention shows up in per-thread NPS whatever the Hash size, while capacity shows up in TTD as a function of Hash with NPS unchanged.

## 10. Worker overlap and diversification measurement

This is Stage 5: Jaccard overlap per depth, the helper depth-lead profile, and the stagger vs no-stagger ablation, all compared against the ceiling set by two identical deterministic 1T searches. Overlap alone decides nothing. It only counts together with the Stage 3 TTD result and a clean Stage 4.

## 11. Lifecycle and result-selection correctness gates (Stage 1)

Each gate is an invariant checked at 2T and 4T over repeated search sequences. The existing `UciApplicationIntegrationTest` 1000-search `Threads=2` `go movetime 5` stress test is a starting point, but it only checks liveness and legality.

- L1. Every `go` produces exactly one `bestmove`, and that move is legal. This includes the forced-move, zero-move and book-move paths.
- L2. No helper stores into the TT after the next `go`'s `incrementGeneration`, after `ucinewgame`'s `clear()`, or after `setoption Hash`'s `resize()`. The source allows all three because helpers aren't joined. Any violation is a lifecycle defect whatever it does to strength. This rule comes from the source, not from a number.
- L3. Helper exceptions total zero, across both normal runs and the L2 race sequences. A resize race inside a helper would surface as a swallowed exception.
- L4. Helper exit latency after `helperAbort` is measured and reported. Whether the invariant is "no helper outlives `bestmove`" or only "no helper overlaps the next search" is a design decision to settle during preregistration review, and is unresolved here. L2 is the hard gate either way.
- L5. `go searchmoves` and `MultiPV>1` under Threads>1: the emitted move stays inside `searchmoves`, and the MultiPV lines are well formed. Helpers ignore both options (section 2), so the concern is TT contamination of the main thread's root, not a crash.
- L6. The book-hit path with Threads>1, where helpers are started and then aborted, does not break L1 or L2 for the next search.
- L7. The emitted move comes from the main thread's last completed iteration, or from `latestIterativeBestMove` on abort. This matches the documented main-only policy and is checked against the instrumentation.
- L8. At `Threads=1`, no helper is ever submitted and Stage 0 still reproduces exactly.

## 12. Classification criteria

Labels can combine, and one is marked primary.

- Correctness flawed: L1, L3, L5 or L7 fails.
- Lifecycle limited: L2 fails, or L4 shows helpers overlapping the next search. Timing stages stop until a repair slice lands.
- JVM or environment limited: Stage 2 (a) or (b) retention sits below the 1T spread, with (b) below (a) pointing at the JVM. SMP scaling is then capped by this ceiling, not by the design.
- TT or contention limited: `r_shared(N)` is below `r(b)(N)` (contention), or Stage 4 shows TTD depending on Hash or helpers destroying deeper main entries (capacity or replacement).
- Under-diversified: Stage 3 TTD falls short, Stage 4 is clean, overlap is near the deterministic ceiling, and the stagger ablation makes no difference.
- Result-selection or time-management limited: a Stage 6 H6 outcome.
- Structurally sound: Stage 1 passes, and TTD speedup at 2T and 4T is outside noise, larger at 4T than at 2T, and consistent with the Stage 2 ceiling, with the fixed-time depth gain holding if Stage 6 was run. This label can sit alongside ordinary scaling limits (H7) when speedup is sublinear and no mechanism is flagged.
- Inconclusive: Stage 3 speedup is inside the noise band and no mechanism is flagged, or the arm spread is too wide to separate 1T from NT at the planned number of runs.

## 13. Stop conditions

- Stage 0 fails and the difference can't be explained: stop and fix the harness. No SMP data is collected.
- Any Stage 1 failure: stop the timing stages, classify, and preregister a repair slice separately.
- Stage 2 shows the platform can't keep throughput at two independent threads: stop with an environment classification. The engine is not tuned against the platform.
- Stage 3 is inconclusive and Stages 4 and 5 flag nothing: stop as inconclusive and skip Stage 6.
- Any step that would need a production change to search behavior (as opposed to instrumentation) in order to continue: stop, record it, and defer to a later phase.
- Hard budget. Each timing stage gets the protocol in section 6 and no extra runs "to see if it firms up". Whether one pre-declared extension is allowed for an inconclusive result is unresolved, and has to be decided before execution, not after.

## 14. Evidence that would justify a later strength test

All of these must hold:

1. Stage 1 passes fully.
2. Stage 3 shows TTD speedup at 2T outside noise.
3. Stage 6 shows depth gain with no time-usage inflation at the TC that will be tested.
4. The match configuration keeps `concurrency x threads <= physical cores`. The Phase 7 and Phase 13 runs don't show that they respected this.

The test itself would then be a same-jar `Threads=2` vs `Threads=1` SPRT on native Windows, and Phase 20 only writes out the `sprt.ps1` command. Note that `tools/sprt.ps1` currently sets `option.Threads=$EngineThreads` for both engines, so an asymmetric 2T vs 1T match needs a per-engine option that doesn't exist yet. That has to be settled before any such test.

## 15. Out of scope

- Any SMP redesign: ABDADA, YBWC, voting or deepest-thread selection, TT buckets or a new replacement policy, per-thread ordering noise.
- Stagger tuning. The Stage 5 ablation is diagnostic only.
- Repairing Stage 1 defects, which goes to a separate preregistered slice.
- More than 4 threads, NNUE under SMP, Syzygy, ponder, and MultiPV beyond gate L5.
- Time-manager changes, search-constant changes and PVS work.
- Any SPRT or game-based strength measurement.

## 16. Assumptions to review before execution

1. Resolved by the lifecycle repair executed 2026-09-24: a search thread owns and joins its helpers after emitting bestmove, and the UCI thread joins that search thread before the next generation, TT clear or resize. A helper may outlive bestmove during this drain; it may not perform shared-state activity across a later lifecycle boundary. The Stage 1 requalification passed, so a Stage 3 harness can rely on production quiescence without adding a separate helper wait.
2. Native-Windows timing with a fixed thread-placement policy is available and representative. Phase 18 had no native Windows run. SMT placement, what `NORM_PRIORITY - 1` means on Windows, and turbo behavior at 1 vs 4 busy cores can each move `r(N)` more than any SMP mechanism does. If native Windows isn't available, only Stages 0, 1 and 5 can run.
3. Main-thread time to depth is the right mechanism metric at Hash=16 MB. In Lazy SMP, deeper helper TT entries can make a nominal depth-N main search effectively deeper, so TTD undercounts the benefit and fixed-depth move differences look like noise when they are really quality. On top of that, 16 MB was picked for 1T comparability, not because it represents SMP use. Stage 4 might show that the headline Stage 3 number is mostly a capacity artifact.
