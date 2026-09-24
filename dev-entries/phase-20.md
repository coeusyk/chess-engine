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

- The original Stage 1 run failed at the Hash resize gate. The separately preregistered lifecycle repair and full Stage 0/Stage 1 requalification are recorded below; Stage 2 and later stages remain unstarted.

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

---

### [2026-09-24] Phase 20 — SMP lifecycle repair and Stage 0/1 requalification (Issue #246)

**Starting point and red-first evidence:**

- Repair started at `a8c15627f45b6a7c9403fd8a75c06d0818520ecc`, branch `phase/20-smp-qualification`, base `origin/develop` `85b201a2a38f23df09ef2aa758ead77fd6d859ee`.
- Test-only commits `b56dfdf` and `ad23201` added the active-resize regression before production edits. Against the unchanged `a8c15627` production path, assertion A failed in all four active Hash arms: `readyok` arrived before bestmove.
- Red helper TT activity after resize: T2 grow 4; T2 shrink 2; T4 grow 5 (helpers 3/0/2); T4 shrink 3 (helpers 1/0/2). No helper exceptions, no uncaught main-search exceptions, and no shrink exception. The preregistered expectation for a shrink exception was not observed.
- The preregistration's literal `go infinite` does not hold this implementation open; the parser falls through to its default depth 4. The regression uses `go depth 127`, the already-used production path for the intended active-search boundary.

**Selected repair:**

- `runSearch` now retains its helper Futures and owns their joins. Ordering is helper abort, exactly one bestmove, helper Future joins, `searchRunning=false`, then search-thread return. `searchThread` remains available for a UCI-thread join after bestmove.
- One bounded `stopAndJoinSearch()` is used for Hash resize, `ucinewgame`, and previous-search quiescence in `handleGo`. A timed-out `ucinewgame` leaves board, TT, book and new-game state unchanged and reports the timeout. Hash size changes only after join and successful resize. No timeout occurred.
- `TranspositionTable.resize()` gained only its caller-quiescence contract comment. The Hash reproducer now accepts `--threads 2|4` and `--direction grow|shrink`; it fails on post-resize helper TT activity, a missing bestmove before `readyok`, exceptions, or an illegal/duplicate result. Diagnostics remain opt-in.
- `BookFile`/`BookVariance` mutation remains a separate follow-up risk; neither option was changed.

**Stage 0 on the repaired jar — PASS:**

- Jar SHA-256: `2be9571d8c8852d654e0551ad75f270619ed5a4ce1bbc294027b11c3b95e36cb`. WSL2 Ubuntu 24.04.4, OpenJDK 21.0.12, 16 processors; JVM flags `-Xms512m -Xmx512m -XX:+UseG1GC --add-modules jdk.incubator.vector`.
- Diagnostics off and on each completed all 31 depth-13 positions and totaled exactly 24,780,049 main nodes. The full move/score/nodes/PV vector SHA-256 was identical in both modes: `f289ff4f316dfd25e2d60eaef9e9e8339335ee8b25147da0899c353576468477`.
- The five depth-8 move/score/nodes/PV vector was identical in both modes (SHA-256 `99d6ea0f9f6b5e0c50c573edc038e1cc5c805aed7b9161568ade76008f5a646b`). The diagnostics-on qnodes/TT hits matched P18-5 exactly: `(14926,39927,4908)`, `(1226,1816,1024)`, `(34694,88266,14691)`, `(6456,14776,1938)`, `(8902,16736,3982)`.
- Diagnostics-off UCI `info` lines do not expose qnodes or TT hits; those fields were directly measured in diagnostics-on result summaries and matched the frozen reference. The instrumentation-off run emitted no `SMPDIAG` events; diagnostics-on reported 36 searches with zero helpers and exceptions.
- The jar `--bench` entry also completed at 24,780,049 nodes. No timing or scaling claim was drawn from WSL2.

**Stage 1 requalification — PASS:**

- Active Hash grow/shrink at Threads=2/4: three runs per arm, 12/12 passed. Every active resize emitted bestmove before `readyok`; every helper reported `after_resize=0`; each sequence then completed one legal bestmove for the next `go`.
- Immediate resize→go passed at Threads=2/4. Active `ucinewgame`→go and stop→go passed at Threads=2/4 with no old-helper activity after clear or generation bump. Idle resize passed at T1/T2/T4, including book searches with one pending T2 helper and three pending T4 helpers at bestmove; those helpers had `after_resize=0` and no extra bestmove was emitted. T1 active resize and resize before any search passed with zero helper work.
- Repeated searches, 75 ms movetime abort then go, book hit, book hit then immediate go, searchmoves `{e2e4,d2d4}` with MultiPV=2, forced move `h8h7`, and terminal `bestmove 0000` all passed. Each `go` had exactly one legal bestmove; emitted search results matched main-thread ownership. Helper exceptions and uncaught main-search exceptions were zero. No join timeout occurred.
- All helper exits had zero activity after a later generation bump, TT clear, or Hash resize. Activity after helper abort was observed (25 TT operations in the Stage 1 diagnostic matrix) but none crossed a later lifecycle boundary.
- Bestmove-to-helper-exit latency from diagnostic timestamps: the 64-exit Stage 1 matrix had median 0 ms and maximum 3 ms; the dedicated idle-resize runs with helpers pending at bestmove had 7 exits, median 6 ms and maximum 9 ms. These are lifecycle observations, not performance claims or pass thresholds.
- `mvn -pl engine-core,engine-uci -am test` passed, including `lazySmpNoDeadlockOver1000Searches` (UCI integration class: 28 passed). `mvn -pl engine-core,engine-tuner -am test` passed. The focused active-resize regression passed all four arms after repair.

At the close of this requalification, Stage 2 had not started. No SMP timing/scaling conclusions, games, SPRT, PVS or tuning work had been done at that point.

### [2026-09-24] Phase 20 — Stage 2 native Windows environment/JVM ceiling (Issue #246)

**Run identity and controls:**

- Continuation branch `phase/20-smp-qualification`; Stage 2 code commit `0691176d68c7f091d01c342a598c78477106812a`, based on post-#247 `develop` merge `b0f02bd79f8fbe9bff45037cdd307a9636978c91`.
- Shaded JAR SHA-256: `8D00237762376FB03040B4C0D76C101A1AE7A9B6C6B6C74EEEB81B3EB02EEA53`.
- Native Windows 11 Pro, version `10.0.26200`, build `26200`; AMD Ryzen 7 7700X, 8 cores / 16 logical processors. Azul Zulu OpenJDK `21.0.10+7-LTS`. JVM flags were `-Xms512m -Xmx512m -XX:+UseG1GC --add-modules jdk.incubator.vector` for every measured JVM. Active power plan: Balanced. No CPU affinity was set; normal Windows scheduling was used. The five-second pre-run process CPU sample recorded 0.0% machine CPU for its listed top processes.
- Classical, depth 13, 31 canonical `BenchRunner.BENCH_FENS`, private 16 MB TT per Searcher, PawnHash=1 MB, instrumentation off; no book, Syzygy, ponder or shared search state; MultiPV=1 and contempt=0.
- One discarded warm-up and seven interleaved measured passes per configuration. The harness recorded 98 worker samples and 42 aggregate samples. Every worker reproduced exactly 24,780,049 nodes over the 31 positions. Maximum process-arm start skew was 2 ms at N=2 and 4 ms at N=4; same-JVM skew was at most 0.026 ms and 0.049 ms respectively.
- Native Maven package and harness self-check succeeded. The check confirmed all 31 corpus FENs, depth 13 and the expected node total. Worker logs contained only the expected incubator-module warning; no worker exception occurred. Raw run files are in `tools/results/phase20-stage2/20260924-095910/` on the native checkout.

**Per-worker throughput:**

| Arm | N | Median NPS / worker | `r(N)` | Worker sample range | Pass-median range |
|---|---:|---:|---:|---:|---:|
| Separate processes | 1 | 338,811 | 1.0000 | 325,047–341,570 | 325,047–341,570 |
| Separate processes | 2 | 260,831 | 0.7698 | 219,206–358,533 | 220,039–355,522 |
| Separate processes | 4 | 286,903 | 0.8468 | 244,294–347,079 | 246,884–342,427 |
| Same JVM | 1 | 343,634 | 1.0000 | 328,472–367,148 | 328,472–367,148 |
| Same JVM | 2 | 272,137 | 0.7919 | 239,382–360,494 | 239,397–359,801 |
| Same JVM | 4 | 274,010 | 0.7974 | 247,045–336,115 | 247,979–334,254 |

**Aggregate throughput:**

| Arm | N | Median aggregate NPS | Pass range | Maximum start skew |
|---|---:|---:|---:|---:|
| Separate processes | 1 | 338,811 | 325,047–341,570 | 0 ms |
| Separate processes | 2 | 517,169 | 438,413–705,001 | 2 ms |
| Separate processes | 4 | 1,146,129 | 977,176–1,359,439 | 4 ms |
| Same JVM | 1 | 343,634 | 328,472–367,148 | 0 ms |
| Same JVM | 2 | 543,676 | 478,764–718,218 | 0.026 ms |
| Same JVM | 4 | 1,085,166 | 988,178–1,327,235 | 0.049 ms |

**Classification and stop:**

- The separate-process 1T NPS range was 325,047–341,570. The N=2 median per-worker NPS (260,831; `r(2)=0.7698`) and N=4 median (286,903; `r(4)=0.8468`) were both below that observed 1T range. This meets the preregistered environment-ceiling classification; no fixed percentage cutoff was applied.
- Same-JVM retention ranges overlapped the separate-process ranges at both N=2 and N=4. The seven-run evidence does not separate an additional JVM-level gap, so no JFR/GC diagnostic was triggered.
- **Stage 2 outcome: environment-limited; stop before Stage 3.** No shared-TT SMP measurement, Stage 3 work, games, SPRT, tuning or WSL2 timing claim was made.
