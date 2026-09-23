# Dev Entries - Phase 20

---

### [2026-09-23] Phase 20: Lazy SMP qualification preregistration

**Built:**

- Started `phase/20-smp-qualification` from `origin/develop` at `85b201a` (the merge of PR #245, Phase 19 closure). Confirmed that `git diff 6afc523 HEAD -- engine-core/src/main engine-uci/src/main` is empty, so the production search source is still the Phase 18 frozen reference.
- Wrote `docs/architecture/research/phase20-smp-qualification-preregistration.md`. It is a plan only. No code, instrumentation, benchmarks, games or SPRT runs were produced.
- Before writing it I read the Lazy SMP path end to end: `UciApplication.handleGo` and `runSearch`, the `smpExecutor` setup, `Searcher.iterativeDeepening` (the start-depth overload used by helpers), `Searcher.setSharedTranspositionTable`, `TranspositionTable` in full, `BenchRunner.run`, and the existing `Threads=2` tests in `UciApplicationIntegrationTest`. The preregistration's architecture description and ownership matrix come from that reading, not from the Phase 7 description of the feature.

**Decisions Made:**

- Treated the historical "1T beats 2T" story as weak evidence rather than a finding to overturn. What the repository actually holds is one 34-game Phase 7 SPRT at 5+0.05 (-152 +/- 120, H0), a Phase 13 "2T NPS >= 1T NPS" bench that was left as TBD, and a Phase 13 SMP SPRT killed at H0 with no numbers logged. All of it predates the packed TT, the P16-1 lock-free fix and the Phase 18 repairs.
- Recorded several source facts that change how SMP has to be measured:
  - Result selection is main-thread only, and helper results are discarded.
  - UCI `nodes` counts the main thread only, so any past "2T NPS" read from UCI output excluded helper work.
  - `BenchRunner` can only run 1T, so every SMP measurement has to drive the real `runSearch` path.
  - Helpers are never joined, and their exceptions are swallowed silently.
  - The only diversification is start-depth staggering (`(i % 2 == 1) ? (i/2) + 2 : 1`).
  - Every searcher, helpers included, resets the shared TT stats when it starts.
- Ordered the stages so that nothing gets timed before its contaminants are ruled out. Stage 0 checks harness validity against the P18-5 node counts. Stage 1 runs lifecycle and correctness gates. Stage 2 sets an environment/JVM ceiling from independent searchers, both as separate processes and in one JVM. Only then comes Stage 3, the fixed-depth 1T/2T/4T time-to-depth measurement, followed by the conditional TT-capacity (Stage 4) and overlap/diversification (Stage 5) stages. The fixed-time stage runs only if Stage 1 passes and Stage 3 shows speedup outside noise.
- Did not invent numeric thresholds. The noise floor comes from the 1T run-to-run spread measured in the same session. The heap size, larger Hash value, movetime and clock states, affinity policy and extension budget are explicitly marked unresolved.
- Kept repairs, SMP redesign, stagger tuning, NNUE under SMP, more than 4 threads and any strength testing out of scope. Found defects go to a separately preregistered repair slice.
- Noted that `tools/sprt.ps1` applies `option.Threads=$EngineThreads` to both engines, so an eventual 2T vs 1T SPRT needs per-engine thread plumbing that doesn't exist yet.

**Broke / Fixed:**

- Nothing. Documentation only.

**Measurements:**

- None taken. The only numbers quoted in the preregistration are existing references: P18-5's 24,780,049 main nodes at depth 13 and its 1.70% CV (WSL2, descriptive only), and the Phase 7 SPRT result.

**Next:**

- Stage 0 and Stage 1 execution is recorded below. Stage 1 failed at the Hash resize gate; Stage 2 and later stages were not run.

---

### [2026-09-23] Phase 20 — Stage 0/1 UCI qualification (Issue #246)

**Frozen run:**

- Branch `phase/20-smp-qualification`, Stage 0 head `64b5d21f9df3917ccb34319f8caa183c65f566ed` (base `origin/develop` `85b201a`).
- Stage 0 UCI jar SHA-256: `ae83410d40500d785b07c0439101b51b898842528a7b301ab9a8c5b8680d40a8`. The first diagnostic build used for Stage 0 was `e7d7cab05285c24129f4fde95f29e56d168ff96708564fcd6d3812db032816b6`; the final diagnostic build used by the retained reproducer was `e0c5f612a55557a6964df6dbd8658ed5891b47a76a8d6f286839f87701bdca7d`.
- WSL2 Ubuntu 24.04.4, OpenJDK 21.0.12, Ryzen 7 7700X, 16 processors visible to the JVM. JVM flags: `-Xms512m -Xmx512m -XX:+UseG1GC --add-modules jdk.incubator.vector`.
- All UCI arms used Classical, Threads=1 for Stage 0, Hash=16, OwnBook=false, Syzygy off, MultiPV=1, Contempt=0, PawnHashSize=1. No timing or scaling conclusion was drawn from WSL2.

**Stage 0 — PASS:**

- Production UCI at Threads=1 reproduced 24,780,049 main nodes over all 31 positions at depth 13. Every position reached depth 13. The instrumentation-on per-position node vector matched the pre-instrumentation run; the post-change instrumentation-off arm matched the exact corpus total.
- The five depth-8 rows matched P18-5 exactly, including move, score, nodes, qnodes, TT hits and PV. The reference FENs are BenchRunner indexes 0, 2, 6, 13 and 20; an initial probe used the wrong indexes for two named positions and was corrected by matching the frozen move/score/node tuples. No baseline was recaptured.

| Position | Best move / score | Nodes | Qnodes | TT hits | PV |
|---|---:|---:|---:|---:|---|
| Start | e2e4 / 25 | 14,926 | 39,927 | 4,908 | e2e4 e7e5 b1c3 b8c6 g1f3 |
| K+P vs K | e1d2 / 122 | 1,226 | 1,816 | 1,024 | e1d2 e8d7 e2e4 d7d6 d2d3 d6e5 d3e3 e5e6 |
| Tactical middlegame | b4b2 / −63 | 34,694 | 88,266 | 14,691 | b4b2 e3d2 b2b6 d3d4 e6c4 f1b1 b6a5 f3e5 c4b5 |
| Rook/pawn | b4f4 / 14 | 6,456 | 14,776 | 1,938 | b4f4 h4g3 f4c4 h5c5 a5b4 c5c4 b4c4 g3g2 c4d3 g2f2 |
| Queen/king | d7d2 / 1,565 | 8,902 | 16,736 | 3,982 | d7d2 c4d5 a8a3 a1b1 c5c4 f1d1 d2c3 f2f4 |

- Instrumentation neutrality: off and on produced the same 31-position node total and the same five-position best move, score, nodes and PV. The instrumentation-on UCI summaries also matched the reference qnodes and TT hits. Threads=1 submitted and started zero helpers.

**Stage 1 diagnostics retained:**

- `-Dvex.smp.diagnostics=true` enables UCI search/helper submit, start, exit, search identity, exit cause, exceptions with stack traces, main result ownership, completed depth/best move, and helper TT reads/writes/stats activity after abort, generation, clear and resize. It is off by default.
- `TranspositionTableTest.helperDiagnosticsTrackActivityAfterAbortAndLifecycleBoundaries` passed (12 tests in the focused class).
- Threads=1, Threads=2 and Threads=4 each passed the exercised repeated-search, movetime abort, searchmoves, MultiPV=2, book, forced-move and zero-move paths: every go emitted one legal bestmove, searchmoves stayed within `{e2e4,d2d4}`, MultiPV lines were well formed, book hits came from the book path, and helper exceptions were zero. Threads=1 did no helper work.
- The book-hit path followed immediately by another go passed at Threads=2 and Threads=4. The 4T book go had all three helpers started and pending at bestmove; none performed TT activity after the following generation began.
- Stop→go and ucinewgame-clear→go sequences passed at Threads=2 and Threads=4: one legal bestmove per go, zero helper exceptions, and no old-helper TT activity after the new generation or clear. A Threads=4 repeated-search trace recorded two helper TT operations after helper abort, before any later boundary; that alone is permitted.

**Stage 1 — FAIL; stopped at Hash resize:**

- Reproducer at Threads=2: start `go depth 127`, wait for the helper-start diagnostic, set Hash from 16 to 32 while the search is active, wait 50 ms, then send `stop`. The helper performed 32 shared-TT operations after the resize boundary: 17 reads and 15 writes. It raised no exception and the main thread emitted one legal bestmove.
- The minimal runnable driver is [`tools/phase20_hash_resize_repro.py`](../tools/phase20_hash_resize_repro.py). Two clean reruns recorded 36 operations (19 reads, 17 writes) and 42 operations (22 reads, 20 writes), each with zero helper exceptions and one legal bestmove. One trace reported helper submit/start/exit at 5/9/27 ms, helper depth 2 and move d2d4; the main thread emitted its `latestIterativeBestMove` e2e4 at 27 ms. These times are lifecycle timestamps only.
- The responsible path is `UciApplication.handleSetOption` applying `sharedTT.resize()` immediately while `runSearch` helpers continue using the table; the helper search has no resize-boundary guard/join. `TranspositionTable.probe()` and `store()` observed the post-resize accesses. No production behavior was changed.
- No Threads=4 resize arm was run after the Threads=2 hard failure. Stage 2 and all later stages were not started.

**Retained changes:** opt-in UCI/TT diagnostics, the focused TT diagnostics test, and the Hash-resize reproducer. No SMP redesign, search change, timing claim, game or SPRT work.
