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

### [2026-09-24] Phase 20 — Post-Stage-2 interpretation amendment accepted before Stage 3

- The Stage 2 run remains historically recorded as stopped under the original preregistered rule and environment-limited. It was not rerun.
- The reviewed interpretation amendment was committed and pushed before any Stage 3 measurement: `edd406a9243fcb53c341599d8c04e3fa7f34cab7` (`docs(phase20): amend Stage 2 interpretation for Stage 3`). Stage 3 resumes under that post-Stage-2 amendment; its acceptance does not rewrite the original Stage 2 record.
- The amendment clarifies that main-thread node counts remove direct clock/NPS normalization but are not scheduler-independent: helper timing changes TT visibility and can change the main-thread tree, so pass-to-pass main-node variation is mechanism evidence.
- It also clarifies that aggregate-throughput loss versus the Stage 2 same-JVM ceiling establishes additional production-SMP execution overhead only. TT contention requires the frozen H3 retention evidence or later Stage 4 evidence.

### [2026-09-24] Phase 20 — Stage 3 native attempt stopped at helper drain (Issue #246)

**Run identity and frozen environment:**

- Branch `phase/20-smp-qualification`, run commit `55ca1171d3ecaf2891d4ee6e38229557c0640438`; shaded JAR SHA-256 `B7743D6C0EA9EA30F4734933D8091959710D7915F5B27CA431AF41A2301D84A5`.
- Native Windows 11 Pro `10.0.26200`, build `26200`; AMD Ryzen 7 7700X, 8 cores / 16 logical processors; Azul Zulu OpenJDK `21.0.10+7-LTS`.
- JVM flags: `-Xms512m -Xmx512m -XX:+UseG1GC --add-modules jdk.incubator.vector`. Balanced power plan, no affinity, normal Windows scheduling. The runner recorded its five-second background-load sample before starting the harness.
- The Maven package and the harness corpus/parser self-check passed. No Stage 2 rerun or environment change was made.

**Stop evidence:**

- During a Threads>1 depth-13 UCI search, the driver received the main-thread `bestmove`, then waited for the expected helper `event=exit` records. It timed out in `Phase20Stage3Harness.search` at the helper-exit wait after 10 minutes. This 10-minute watchdog was a runner execution guard, not a preregistered Stage 3 acceptance threshold.
- The partial artifact directory is `tools/results/phase20-stage3/20260924-152702/` on the native checkout. `workers.csv`, `helpers.csv` and `events.csv` contain headers only. The precise thread count, position and whether this was warm-up or a measured pass were not retained. The run cannot establish whether helpers remained active or exit diagnostics were lost; helper drainage was not verified.
- **Stage 3 stopped at the helper-drain lifecycle gate.** No complete Stage 3 sample exists, so no search-work factor, NPS retention, aggregate throughput, time-to-depth, factorization, H3, or production-SMP overhead classification is available. Stage 3 has no PASS classification. No retry, production fix, Stage 4/5, or later stage was started.

### [2026-09-26] Phase 20 — Stage 3 harness accounting repair (Issue #246)

The stopped attempt recorded in commit `99175f2616ee5d692e814e6392c977fc8137fb1b` remains historically unchanged. Its headers-only CSVs still do not establish the precise event order of that sample.

The harness defect is confirmed. Production helper `finally` blocks emit `SMPDIAG event=exit`; `runSearch` sets `helperAbort`, emits and flushes the main UCI `bestmove`, then joins helper futures and emits `event=drained`. A helper can therefore emit `event=exit` before UCI `bestmove`. `Phase20Stage3Harness.search` captured those lines but created an empty exit list after bestmove and consumed only future lines, so already-captured exits were omitted from the count. The synthetic pre-bestmove transcript failed before the repair and passes after it. The collector now seeds exits from the captured transcript, waits only for missing helpers, and rejects repeated helper IDs.

The harness also checkpoints run type, pass, position, Threads and known search ID, then writes the captured UCI/SMP transcript to `failure.txt` on failure or JVM shutdown. This prevents a stopped run from leaving only unidentified CSV headers. No `Searcher`, UCI lifecycle, or TT code changed.

Local `--validate-only` passes, including the synthetic ordering, duplicate-ID, and failure-artifact checks. A separate `-LifecycleSmoke` path runs one depth-13 search at 2T and one at 4T, validates exits/drain/exceptions/boundaries/legal bestmove, and emits no performance CSVs. The native smoke later passed; the performance run and its results are recorded below.

### [2026-09-26] Phase 20 — Stage 3 fixed-depth qualification completed (Issue #246)

**Run:** Native Windows 11 Pro build 26200; Ryzen 7 7700X, 8 cores/16 logical processors; Azul Zulu JDK 21.0.10+7-LTS; Balanced, no affinity. Classical, Hash=16 MB, PawnHashSize=1 MB, depth 13, canonical 31-position corpus; frozen JVM flags. Run commit `78d72c52fa24e455356f816aea26fb040f1450b5`; JAR SHA-256 `2871341887E480E2E2E8760DC3FD7331A7B6527106D190DCF9301858C89519E0`.

Raw artifacts are committed under `tools/results/phase20-stage3/20260926-071358/`. Maven packaging succeeded; unit tests were skipped as specified by the runner. The harness self-check passed.

**Schedule and validity:** One discarded warm-up and seven interleaved passes completed: 651 measured searches (217 per arm; 31 positions × 7 passes at 1T/2T/4T). The warm-up 1T check and each measured 1T pass matched 24,780,049 nodes; every 1T position repeated the same node count across all seven passes. Every search reached depth 13. The CSVs contain 651 each of begin/bestmove/drained events and 868 each of helper submit/start/exit events; helper IDs are unique per search. Every go had one legal main-result bestmove. Helper and main exceptions were zero; drained records had no pending helpers. All helper TT counters after generation, clear and resize were zero. No failure artifact was produced.

Per-search distributions cover 217 position/pass observations per arm. Values are median (P10–P90; full range). Corpus main NPS is summed main nodes divided by summed depth-13 main TTD per pass; total NPS is summed total nodes divided by summed drain elapsed time.

| Threads | Main nodes/search | Main TTD ms/search | UCI main NPS/search | Hashfull at depth 13 |
|---:|---:|---:|---:|---:|
| 1 | 581,587 (57,130–1,825,906; 7,568–3,475,078) | 1,623 (84.6–4,904.4; 6–11,781) | 398,061 (317,235–786,403; 269,035–1,261,333) | 372 (38–771; 2–925) |
| 2 | 427,971 (46,466–1,385,665; 6,532–14,119,997) | 1,158 (69.6–3,828.4; 6–50,917) | 383,991 (319,514–757,539; 258,600–1,127,166) | 410 (33–845; 2–1,000) |
| 4 | 314,927 (41,453–1,066,534; 3,730–7,861,316) | 888 (64.4–3,251; 3–30,557) | 362,383 (292,634–695,027; 242,476–1,243,333) | 514 (47–933; 2–1,000) |

The seven 1T corpus NPS values were 361,341; 361,536; 361,209; 363,216; 361,879; 363,195; and 348,858. Median 361,536 (range 348,858–363,216) is inside the Stage 2 same-JVM range 328,472–367,148, so the Stage 2 ceiling transfers.

| Threads | Main-node work factor, paired | Main-NPS retention, paired | `r_shared` from corpus-pass medians | TTD speedup, paired | Corpus TTD speedup, pass median (range) |
|---:|---:|---:|---:|---:|---:|
| 2 | 1.305 (0.865–1.804; 0.246–2.745) | 0.972 (0.922–1.024; 0.739–1.154) | 0.9560 (pass range 0.887–0.988) | 1.259 (0.819–1.782; 0.230–2.824) | 1.132 (0.729–1.362) |
| 4 | 1.656 (0.976–2.545; 0.322–4.869) | 0.926 (0.872–0.993; 0.656–1.150) | 0.9024 (pass range 0.856–0.927) | 1.537 (0.893–2.459; 0.280–4.756) | 1.448 (1.058–1.574) |

Corpus-pass work factors were 1.184 (0.822–1.378) at 2T and 1.562 (1.236–1.773) at 4T. Pass-by-pass, `TTD_1 / TTD_N = (mainNodes_1 / mainNodes_N) × (mainNPS_N / mainNPS_1)` matched to the recorded precision (maximum absolute difference 0). Work-factor variation is not scheduler-independent.

| Threads | Helper NPS per exit (median; P10–P90; range) | Corpus total NPS/pass (median; range) | Total NPS / same-session 1T NPS (median; range) |
|---:|---:|---:|---:|
| 1 | — | 361,318 (348,685–363,029) | 1.000 |
| 2 | 381,693 (314,546–728,275; 264,174–998,947) | 687,408 (621,151–714,347) | 1.902 (1.781–1.967) |
| 4 | 360,868 (294,772–706,710; 236,871–978,985) | 1,317,484 (1,232,626–1,329,952) | 3.638 (3.447–3.682) |

Stage 2 same-JVM aggregate NPS was 543,676 (478,764–718,218) at 2T and 1,085,166 (988,178–1,327,235) at 4T. Stage 3 throughput did not fall below either range's lower bound, so no additional production-SMP overhead is detected by aggregate throughput. Hashfull reached 1,000 in 3/217 2T searches and 2/217 4T searches. Median helper TT reads/writes were 658,285/431,133 at 2T and 493,920/309,054 at 4T; these counters are descriptive and do not establish contention.

**Classification:** Stage 3 fixed-depth PASS; structurally sound under the frozen criteria. Median TTD speedup is outside the 1T spread at both thread counts and greater at 4T. `r_shared(2)=0.9560` is above H3 bound 0.652; `r_shared(4)=0.9024` is above 0.673: **H3 not detected at this power**. Mandatory Stage 4/5 triggers did not fire. Five searches reached full hashfull, making Stage 4 optional capacity characterization only; Stage 5 is not indicated by the positive work-factor/TTD result. Stage 6 entry conditions are met but Stage 4/5/6 were not run. The first stopped attempt remains unchanged and its exact event order remains unrecoverable.
