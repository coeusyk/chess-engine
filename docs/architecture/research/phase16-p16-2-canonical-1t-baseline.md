# P16-2: canonical single-threaded execution baseline

Governs issue #226, Step 2. Establishes the reproducible single-threaded node-count and NPS
baseline that future search interventions in Phase 16 (Step 3 and beyond) are measured against.
No search behavior was changed to produce this baseline; the branch's only changes since P16-1
are the `--bench-raw` flag, the debug-logging override, and the native-run script and its bug
fixes, none of which touch `Searcher`, `MoveGenerator`, or `Evaluator`.

Execution baseline: branch `phase/16-search-execution-qualification`, commit
`bc64c2a0a90e637056cc9dd7522de4e485b5462e` (`bc64c2a` short form) -- the current branch tip, and
the same commit already on `origin`.

Raw evidence: `tools/results/p16-2/20260919-075505/` (native Windows, this section's authoritative
run). An earlier attempt at `tools/results/p16-2/20260919-070626/` ran at commit `63f1903`, one
fix short of the tip, and is superseded; see section 1.

## 1. Protocol validity

The run at `tools/results/p16-2/20260919-075505/` is **valid** against the frozen P16-2 protocol.
Every check below passed:

- **Native execution, not WSL interop.** `00-process-evidence.json` shows `Platform: Win32NT`,
  `CurrentProcess.Path: C:\Program Files\PowerShell\7\pwsh.exe`, and `ComputerName: RENEGADE` --
  a genuine native PowerShell process, not a WSL-mounted invocation. `tools/p16-2-native-baseline.ps1`
  independently refuses to run from a `\\wsl`-mounted path, so this evidence file existing at all
  is itself a second confirmation.
- **Expected branch and commit.** `01-git-state.txt` and `03-environment.json` both report
  `branch=phase/16-search-execution-qualification`, `commit=bc64c2a0a90e637056cc9dd7522de4e485b5462e`
  -- the branch tip, matching local and `origin`.
- **Expected JAR.** `03-environment.json` records the built JAR's path and SHA-256
  (`2B788CA7...F8`), produced from the commit above by the same script run, not a stale artifact.
- **Expected JDK and configuration.** Zulu OpenJDK 21.0.10 (`21.0.10+7-LTS`), `--add-modules
  jdk.incubator.vector`, `Threads=1`, `Hash 16 MB` (hardcoded in `BenchRunner`, independent of the
  UCI `Hash` option), Classical evaluator, depth 13, the 31-position Stockfish public-domain bench
  suite. All match the frozen protocol.
- **Warm-up completed.** `04-warmup-discarded.txt` shows all 31 positions finishing at depth 13,
  73,089,246 total nodes -- a full, non-truncated run, correctly discarded rather than counted.
- **All 7 canonical repetitions completed, all 31 positions in each.** `05-canonical-run-01.txt`
  through `05-canonical-run-07.txt` each report 31/31 positions at depth 13 and a `Nodes searched:
  73089246` total line with no truncation or error output.
- **Node-count determinism across repetitions.** All 7 canonical runs, the discarded warm-up, and
  the separate debug-logged run (`06-per-position-debug.txt`) report the **identical** node count
  at every one of the 31 positions and the identical 73,089,246 total. Nine independent executions,
  zero variance in node count. See section 3.
- **No evidence the earlier apparent stall corrupted this run.** The stall was diagnosed and fixed
  before this run was taken (see section 1a); this run's own artifacts show no truncation, no
  timeout, and a clean process exit at every step.
- **Attribution/JFR artifacts are usable.** `07-attribution.jfr` (3.3 MB binary) converts cleanly
  via `jfr print` to `07-attribution-jfr-print.txt` (183,191 lines, 13,086 `jdk.ExecutionSample`
  stack traces with resolved method names and line numbers) -- no corrupt or empty recording.
- **Clock-bound sanity passed.** `08-clock-bound-sanity.txt` completes the full UCI handshake,
  iterates depth 1 through 12 within its time bound, and ends with a proper `bestmove e2e4 ponder
  e7e5` followed by clean process exit -- confirming the earlier stall (section 1a) is fixed, not
  papered over.

### 1a. The earlier apparent stall, and why it doesn't affect this run

`tools/results/p16-2/` holds three empty directories from `2026-09-18` (`173238`, `173358`,
`173750`) and two more from `2026-09-19` before the valid run: `054817` (a run that aborted after
printing only the incubator-module warning, before any bench output) and `063641`, which added a
diagnostic thread dump. That dump's `main` thread was blocked in
`BufferedReader.readLine` inside `UciApplication.run`, i.e. waiting on stdin that would never
arrive again -- the process had already been told to quit.

This matches exactly the bug fixed in commit `bc64c2a`, "UCI sanity check sent quit before
bestmove": the sanity-check step wrote `quit` to the engine's stdin immediately after sending its
`go` command, instead of waiting for `bestmove` first. The engine, mid-search, never got to read
`quit` before the harness's timeout killed it, or the sequencing otherwise left the process
waiting on a stream nothing further would write to. Three commits fixed unrelated issues found in
the same investigation window (self-poisoning clean-tree check, `java` stalled to 0% CPU by
`2>&1|Out-File`, wrong-SNAPSHOT-jar selection); `bc64c2a` is the fix specific to the stall.

A complete 7-run set exists at `20260919-070626`, taken between the SNAPSHOT-jar fix (`63f1903`)
and the stall fix (`bc64c2a`). Its `01-git-state.txt` confirms it ran at `63f1903`, not the branch
tip, and its `08-clock-bound-sanity.txt` cuts off after `readyok` with no search output and no
`bestmove` -- direct evidence of the same stdin-ordering bug at the sanity-check step. That run
predates the fix and is **not** used as this baseline's evidence; `20260919-075505`, taken after
`bc64c2a`, is the frozen-protocol run, and its own clock-bound sanity check (section 1) completed
cleanly with a `bestmove` line, confirming the fix. The two runs' bench numbers are close (design
expected, since the fix touched only the sanity-check step, not the bench path) but only the
post-fix run is treated as authoritative here.

## 2. Canonical Windows environment

| Field | Value |
|---|---|
| Machine | RENEGADE, AMD Ryzen 7 7700X (8-core / 16-thread), 4501 MHz max clock |
| OS | Windows 11 Pro, build 26200 |
| JDK | Zulu OpenJDK 21.0.10 (`21.0.10+7-LTS`), `--add-modules jdk.incubator.vector` |
| Commit | `bc64c2a0a90e637056cc9dd7522de4e485b5462e` on `phase/16-search-execution-qualification` |
| JAR | `engine-uci-0.6.0-SNAPSHOT.jar`, SHA-256 `2b788ca7...219840f8` |
| Threads | 1 |
| Hash | 16 MB (hardcoded bench constant, independent of the UCI `Hash` option) |
| Evaluator | Classical |
| Bench corpus | 31-position Stockfish public-domain suite, depth 13 |

## 3. Seven-run NPS statistics (canonical, uninstrumented `--bench-raw`)

All 7 canonical runs searched the identical 73,089,246 total nodes (section 3a); only elapsed
wall-clock time varies between them.

| Run | Time (ms) | NPS |
|---|---|---|
| 1 | 222,376 | 328,674 |
| 2 | 219,859 | 332,436 |
| 3 | 217,829 | 335,534 |
| 4 | 220,922 | 330,837 |
| 5 | 217,709 | 335,719 |
| 6 | 217,758 | 335,644 |
| 7 | 218,267 | 334,861 |

Median NPS is 334,861 (run 7), min is 328,674 (run 1), max is 335,719 (run 5). Mean 333,386,
sample standard deviation 2,797, coefficient of variation 0.84%. Spread (max minus min) is 7,045
NPS, 2.10% of the median, which is tight for a 7-repetition wall-clock measurement on a
general-purpose desktop OS with no core pinning or process priority elevation applied by the
script.

Elapsed time: median 218,267 ms, min 217,709 ms (run 5), max 222,376 ms (run 1), range 4,667 ms
(2.14% of the median). Time and NPS move inversely run-to-run, as expected for a fixed node count
over a varying elapsed time. Run 1, taken immediately after the discarded warm-up, is both the
slowest and lowest-NPS run, consistent with residual JIT or OS-cache transients the warm-up didn't
fully absorb. It still sits inside the same 2%-ish band as the rest, so it doesn't stand out as an
outlier.

Against the CLAUDE.md gate: the previous native-Windows baseline was 316,964 NPS, with a floor of
301,116 (5% below it). This run's median of 334,861 is 5.65% above the previous baseline and
comfortably above the floor. That's not a regression check, though, it's a new canonical reference:
don't compare a future run against 316,964/301,116 once this baseline is adopted.

### 3a. Node-count determinism

Every one of the 31 positions produced the identical node count in every one of 9 independent
executions: the discarded warm-up, all 7 canonical `--bench-raw` runs, and the separately-taken
debug-logged run. Total: **73,089,246 nodes** in all 9. Per-position node counts ranged from 8,095
(position 3) to 22,030,846 (position 30, the single largest position, itself ~30% of the suite's
total nodes). Zero node-count variance across 9 runs on a single-threaded, hash-fixed,
non-time-bounded (depth-bounded) search is exactly the determinism this baseline exists to confirm
-- there is no search-order or hashing nondeterminism to control for when comparing a future
intervention's node counts against this one.

## 4. Per-position findings and search counters (from the debug-logged run)

`06-per-position-debug.txt` (uninstrumented, with the `Searcher` DEBUG-level `[BENCH]` counters
enabled via `tools/logback-debug.xml`) reproduced the same 73,089,246-node run as the 7 canonical
repetitions, with per-iteration search counters at every depth for every position.

Per-position depth-13 NPS ranges from 259,182 (position 4) to 791,371 (position 27), a much wider
spread than the 7-run aggregate in section 3. That's expected: aggregate NPS averages over 73M
nodes and ~218 seconds, while a single position's NPS is one depth-13 iteration's node count
divided by that iteration's own elapsed time. Small-node positions like position 27 (27,698 nodes
at depth 13) are dominated by fixed per-call overhead and JIT/scheduling noise rather than
sustained throughput. Positions with very low node counts (3, 12, 13, 21, 24, 25, 26, 27, 28)
aren't individually meaningful throughput signals; their depth-13 NPS shouldn't be used to judge
per-position performance. The aggregate 7-run number in section 3 is the one that carries
statistical weight.

Position 4 is worth flagging on its own. It has a respectable node count (2,759,518 main nodes)
yet the suite's lowest depth-13 NPS (259,182), and also the suite's highest quiescence-to-main-node
ratio (qnodes/nodes = 5.25, against a suite-wide typical range of roughly 2-3). The `[BENCH]`
counter's `nps` field is main-search `nodes` divided by elapsed time; it doesn't include `qnodes`
in its numerator. So a position whose search spends unusually long in quiescence relative to the
main tree shows a disproportionately low `nps` even though the engine is doing real, counted work
(`qnodes`) during that time. The elapsed-time denominator includes that work; the `nps` numerator
doesn't. That's a property of what the metric measures, not evidence of a slow code path specific
to position 4, but worth remembering the next time a per-position NPS number looks anomalously
low.

Other search counters from the same run, aggregated at depth 13 across all 31 positions:
first-move cutoff rates cluster tightly in the 78-98% range (healthy move ordering across
essentially every position), TT hit counts scale with node count as expected, and null-move/LMR/
futility/delta-pruning counters are all populated and nonzero at every position that reaches
sufficient depth for them to fire -- no position shows a counter stuck at zero where the others
show it active, which would have suggested a pruning path silently disabled for that position.

## 5. JFR hot-path attribution

`07-attribution.jfr` (3.3 MB, one `ExecutionSample` event per sampling tick on the `main` thread)
converts via `jfr print` to 13,086 stack-trace samples. Leaf-frame (self-time proxy) attribution,
aggregated by subsystem:

| Subsystem | Leaf samples | Share |
|---|---|---|
| `Board.makeMove` / `Board.unmakeMove` | 4,916 | 37.6% |
| Evaluation (`Evaluator`, `ClassicalEvaluator`) | 2,421 | 18.5% |
| Move ordering (`MoveOrderer.orderMoves`, `.scoreMove`) | 1,893 | 14.5% |
| Move generation (`MovesGenerator`) | 1,857 | 14.2% |
| Magic-bitboard attack lookup (`MagicBitboards`) | 310 | 2.4% |
| Static exchange evaluation (`StaticExchangeEvaluator`) | 237 | 1.8% |
| Everything else (TT probe, search-tree bookkeeping, extension checks, etc.) | 1,452 | 11.1% |

`Board.makeMove`/`unmakeMove` is the single largest attribution bucket by a wide margin, more than
double the next largest. This is a throughput observation about where wall-clock time goes at
depth 13 on the current position set, not a claim that make/unmake is inefficient in absolute
terms or a suggestion to change it -- Step 3's preregistration is the place to decide whether any
of this is worth acting on, and profiling was explicitly out of scope before Step 1 closed per
issue #226.

As a cross-check against the independently-instrumented run: the instrumented `--bench` run's own
`eval_pct` counter (Searcher's internal nanoTime-based accounting, not JFR sampling) reports 20.2%
total time in `evaluate()` calls (`07-attribution-console.txt`, final line). JFR's independent
statistical leaf-sample attribution for the same subsystem gives 18.5%. Two different measurement
methods, one explicit timing instrumentation and one statistical sampling, agree within 1.7
percentage points on the same run's evaluation-time share. That agreement is itself a reason to
trust the attribution data, not just an artifact of either measurement method.

## 6. Instrumented vs. raw: do not compare directly as a regression signal

The instrumented run (`07-attribution-console.txt`, JFR recording active, `Searcher`'s
nanoTime-based per-evaluate timing active) reports **324,660 NPS** total, against this baseline's
**334,861 NPS** median from the uninstrumented canonical runs -- roughly 3% lower. This is
expected instrumentation overhead (the nanoTime pairs around every `evaluate()` call, plus the JFR
recording itself), not a search regression, and per this baseline's own purpose it must **not** be
compared against the raw canonical number as if it were: the raw number is what future
interventions get measured against; the instrumented number exists only to attribute where time
goes (section 5), and carries its own, different overhead every time it's taken.

## 7. WSL2 diagnostic comparison (non-authoritative)

Recorded during P16-2's WSL2-side prep (`dev-entries/phase-16.md`, 2026-09-18 entry), before the
native run was possible, both single-run, uninstrumented and instrumented respectively, on a
shared, noisy WSL2 VM:

| Run | Nodes | Time | NPS |
|---|---|---|---|
| WSL2 `--bench` (instrumented) | 73,089,246 | 233,448 ms | 313,085 |
| WSL2 `--bench-raw` (uninstrumented) | 73,089,246 | 240,378 ms | 304,059 |

Both match this baseline's node count exactly (73,089,246), confirming the search itself behaves
identically under WSL2 and native Windows; only throughput differs. Against this baseline's native
canonical median (334,861), WSL2's uninstrumented single run is **9.20% lower** (304,059 vs.
334,861) and its instrumented single run is **6.50% lower** (313,085 vs. 334,861). Per CLAUDE.md
Section 4, WSL2 NPS is explicitly not a valid regression signal and is not gated on; these numbers
are reported here only as a sanity cross-check that WSL2's known overhead (~6-9% here) is in a
plausible, unsurprising range, not as a second baseline.

## 8. Clock-bound sanity

`08-clock-bound-sanity.txt`: full UCI handshake (`uci` / `uciok`, `isready` / `readyok`) followed
by a `go` command that iterated cleanly from depth 1 through depth 12 within its time bound, each
line's `nodes`/`nps`/`time` fields internally consistent (e.g., depth 12: 367,547 nodes, 1,147 ms,
320,442 nps -- 367,547 / 1.147 = 320,439, matching within reporting rounding), ending with
`bestmove e2e4 ponder e7e5` and clean process exit. This is the artifact that previously
demonstrated the stdin-ordering stall (section 1a) before the `bc64c2a` fix; its clean completion
here is direct confirmation the fix holds.

## 9. Implications for future search interventions (no selection made here)

This baseline establishes, for `Threads=1`, Classical evaluator, depth 13, the 31-position suite:

- A **73,089,246-node, deterministic reference node count** any future intervention's own node
  count can be diffed against exactly -- no statistical test needed for "did this change search
  behavior at all," since the current baseline itself has zero run-to-run node variance.
- A **334,861 NPS median, 0.84% CV reference throughput** any future intervention's own 7-run
  median can be compared against, with the understanding that a change genuinely worth measuring
  should move NPS by more than roughly this baseline's own ~2% run-to-run spread to be
  distinguishable from measurement noise at this repetition count.
- A **hot-path attribution baseline** (section 5) that a future intervention's own JFR pass can be
  compared against qualitatively -- e.g., a pruning change that meaningfully shifts the
  make/unmake or evaluation share of leaf samples would be visible against this 37.6% / 18.5%
  reference, though that comparison is descriptive, not a statistical test.

None of the above should be read as evidence for or against any specific search intervention, and
per issue #226's explicit scope, no intervention is selected, ruled in, or ruled out here --
low aggregate NPS at a per-position level (section 4) says nothing about search efficiency
(nodes-per-move-found), and this baseline makes no efficiency claim of any kind. Step 3 of #226 is
where the existing SPRT/bench evidence gets reviewed and exactly one intervention gets
preregistered against this baseline.
