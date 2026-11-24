# Dev Entries - Phase 21

### [2026-09-27] Phase 21 Stage 0: accepted baseline and exact reproduction

**Built:**

- Applied the three bounded amendments to `docs/architecture/research/phase21-direction-and-preregistration.md` before execution. Stage 2 will derive PV identity solely from the window, preferably removing the explicit alphaBeta flag after checking auxiliary callers. Stage 1 has no population-size threshold. Project SPRT bounds remain unchanged; any alternative needs separate preregistration after Stages 1 to 5 and before Stage 6.
- Inspected the complete local and remote develop-to-Phase-20 delta: 15 commits, 116 files, 107,165 inserted lines and 6 deletions, including the preserved binary evidence. The production engine-core tree was identical. The remaining UCI changes add opt-in work/elapsed/drain diagnostics, rather than the lifecycle repair already merged in PR #247.
- Independent review found no blocking correctness or integration-scope issue. The Phase 17 closure deliberately records Git retention for audit, and the Phase 20 dev entry records committed Stage 3 and preserved Stage 6 results. Archival provenance does not designate these files as branch-only. The complete branch was therefore intentionally included in [PR #248](https://github.com/coeusyk/chess-engine/pull/248), with no omitted files or promoted Phase 17 search implementation.
- PR #248 passed Backend CI, including module tests, search regression, the CI benchmark gate and NNUE checks, before its normal merge. Verified merged develop `d3a56ffadf0d9151a2b19fba4902a734a99bff96` locally and remotely, and verified its tree equals Phase 20 tip `2ecab419e8d8e5bd8cded24d076ba0b0db80cc9a`.
- Created `phase/21-zero-window-search` from that exact accepted develop head. Control JAR was built from this base before any production instrumentation change. SHA-256: `cdf7fe59b773d55f4a79a59ccccd9b1b6d804f82332b6200cc88b9e65fa5e95b`.
- Added `tools/Phase21ControlHarness.java` and `tools/phase21-logback.xml` for repeatable semantic controls. The harness reads the canonical BenchRunner corpus directly, uses a fresh Classical Searcher and 16 MB TT per position, sets board search mode, checks every completed depth, asserts the canonical total and asserts the complete P18-5 rows. Its TSV output excludes elapsed time.

**Decisions Made:**

- Threads=1, Hash=16 MB, Classical, default 1 MB pawn hash, neutral contempt, MultiPV=1, no book or tablebase, no abort or time manager. All iterative depths and aspiration attempts contribute to reported semantic totals. WSL2 Ubuntu 24.04.4, OpenJDK `21.0.12.1+1-1-24.04.4-Ubuntu`; JVM flags `-Xms512m -Xmx512m -XX:+UseG1GC --add-modules jdk.incubator.vector`. No timing decision uses this environment.
- Source inspection found six alphaBeta call sites: root, null move, reduced LMR probe, current LMR full-depth re-search, ordinary child and singular alternative. Null-move and singular auxiliary calls use width-one windows and flag false. No auxiliary caller intentionally needing wide-window/non-PV semantics was found. This is source evidence only; the API remains unchanged in Stages 0 and 1.

**Broke / Fixed:**

- No semantic mismatch. Context-mode calls hung and were terminated; bounded local processing was used for inspection. Local git writes required sandbox escalation because `.git` is read-only under the workspace profile.

**Measurements:**

- Stage 0 instrumentation disabled: all 31 depth-13 rows completed and totaled exactly **24,780,049 main nodes**. Complete 31-position semantic vector and five depth-8 rows are preserved in [`phase21-stage0-control.tsv`](../docs/architecture/research/phase21-stage0-control.tsv). TSV SHA-256: `ad577304b36f5296642a26f550dc5ef9e0fbc668884ef22a132982e913ae2fd5`.

| Position (zero-based corpus index) | Move | Score | Nodes | Qnodes | TT hits | PV |
|---|---|---:|---:|---:|---:|---|
| Start (0) | e2e4 | 25 | 14,926 | 39,927 | 4,908 | e2e4 e7e5 b1c3 b8c6 g1f3 |
| K+P vs K (2) | e1d2 | 122 | 1,226 | 1,816 | 1,024 | e1d2 e8d7 e2e4 d7d6 d2d3 d6e5 d3e3 e5e6 |
| Tactical middlegame (6) | b4b2 | -63 | 34,694 | 88,266 | 14,691 | b4b2 e3d2 b2b6 d3d4 e6c4 f1b1 b6a5 f3e5 c4b5 |
| Rook/pawn (13) | b4f4 | 14 | 6,456 | 14,776 | 1,938 | b4f4 h4g3 f4c4 h5c5 a5b4 c5c4 b4c4 g3g2 c4d3 g2f2 |
| Queen/king (20) | d7d2 | 1,565 | 8,902 | 16,736 | 3,982 | d7d2 c4d5 a8a3 a1b1 c5c4 f1d1 d2c3 f2f4 |

| Stage 0 command | Result |
|---|---|
| `mvn -B -pl engine-core test` | PASS: 405 tests, 0 failures/errors, 5 configured skips |
| `mvn -B -pl engine-core,engine-uci,engine-tuner -am test` | PASS: core 405/5 skipped, UCI 42/8 skipped, tuner 131/1 skipped; 0 failures/errors |
| `mvn -B -pl engine-core,engine-uci,engine-tuner -am package -DskipTests` | PASS: all selected reactor modules built |

All shell commands use the required `rtk` prefix. Repeat the controls after the reactor package build:

```bash
rtk proxy javac --add-modules jdk.incubator.vector -cp engine-uci/target/engine-uci-0.6.0-SNAPSHOT.jar -d /tmp/phase21-control-classes tools/Phase21ControlHarness.java
rtk proxy java -Xms512m -Xmx512m -XX:+UseG1GC --add-modules jdk.incubator.vector -Dlogback.configurationFile=tools/phase21-logback.xml -cp /tmp/phase21-control-classes:engine-uci/target/engine-uci-0.6.0-SNAPSHOT.jar Phase21ControlHarness false
```

The frozen control run used an identical copy of the logging configuration at `/tmp/phase21-logback.xml` and the hashed control JAR at `/tmp/phase21-control.jar`. Harness output confirmed `CONTROL PASS total=24780049 reference_rows=5 instrumentation=false`.

**Stage 0: PASS.** Exact reproduction and normal tests/builds passed. Stage 1 diagnostics are the next authorized slice; Stage 2 is not authorized.

### [2026-09-27] Phase 21 Stage 1: diagnostic population, exact neutrality

**Built:**

- Stage 0 was committed separately as `9182cbfff20a8c7c35862782aebcb2ac88a4ae63` before Stage 1 changed any production source.
- Added 15 plain `long` fields to Searcher, counter increments, resets beside the existing per-pvIndex counters, local cumulative sums and an opt-in `[BENCH] phase21 depth=N` line. Reused the existing LMR application counter for reduced probes. Counter sites allocate nothing. `setInstrumentationEnabled(true)` enables the new counters; DEBUG logging enables reporting. Instrumentation and its reporting are off by default in normal search.
- Added one check to the existing SearcherInstrumentationTest. It verifies disabled reporting, complete semantic result identity, cumulative populations, LMR invariants, exercised sibling populations and reset when the Searcher is reused. Independent review found no blocking issue and confirmed existing calls, windows, signature, ordering, pruning conditions, TT operations and result construction are unchanged.

**Counter definitions and denominators:**

- `ab_calls` is the sum of the four flag/window cells, counted at alphaBeta function entry before any abort, draw, horizon, tablebase, TT, razoring or null-move return. It includes first and later children, null moves, singular alternatives, reduced LMR probes, current full-depth LMR re-searches, every iterative depth and every aspiration retry. It excludes searchRoot itself and direct quiescence invocations. Wide means `beta - alpha > 1` at entry; valid null-window calls have width one. PV means the existing explicit flag. No PV identity was changed.
- The population is **alphaBeta invocations**, not `nodesVisited`. Main nodes are counted later, after early-return gates, immediately before move generation. The aggregate call denominator is **72,531,290**; the separate canonical main-node total is **24,780,049**. No percentages below divide call counts by main nodes.
- The three `nonpv_wide_*` pruning counters count actual razoring returns, futility-skipped moves and losing-capture-skipped moves with the existing non-PV flag and a **live** width greater than one at the event. Razoring attempts that continue are excluded. Move skips are events and can occur several times in one invocation. Entry classification remains fixed; event windows use the current alpha, so an invocation that entered wide and later narrowed to width one does not contribute a wide-window pruning event at that point.
- `pv_wide_sibling_*` counts completed, non-aborted, ordinary non-LMR searches with `moveIndex > 0`, parent flag PV and live parent width greater than one. Scores are classified in the parent's perspective against the alpha and beta used for the child call, before caller alpha updates: `score <= alpha`, `alpha < score < beta`, `score >= beta`. LMR probes/re-searches, pruned moves and singular alternatives are excluded from this outcome denominator.
- `lmr_probes` is the existing cumulative `lmrApplications`: each reduced-depth null-window invocation, including an attempted probe if it aborts. `lmr_fail_highs` counts non-aborted reduced scores greater than caller alpha. `lmr_researches` counts the ensuing current full-depth invocation, regardless of whether that re-search later aborts. All control searches are non-aborted. These are the current production full-window re-searches; no new null-window verification was implemented.
- `root_wide_sibling_*` uses the same score categories for completed later root siblings searched with a live width greater than one. Root has no LMR branch. It includes every iterative depth and aspiration retry and excludes the first sibling of each root attempt.
- All diagnostic fields reset per pvIndex and accumulate across the search's iterations, MultiPV passes and retries like the existing counters. The measurements use MultiPV=1 and the final cumulative depth-13 line per position. Five depth-8 reference searches are recorded separately and excluded from the 31-position aggregates. Existing debug-report placement can omit a final aborted, soft-stopped or mating iteration; these fixed-depth controls had none, and all 36 final reports were present.

**Broke / Fixed:**

- No deterministic mismatch and no source-model contradiction. The focused test's initial expected report-field count was corrected from 19 to 18 before its passing run; no search code change was needed.

**Validation:**

- Re-ran the exact Stage 0 harness and settings with instrumentation enabled. All 31 searches reached depth 13 and totaled **24,780,049 main nodes**. The entire 36-row TSV, including every depth-13 move/score/nodes/qnodes/TT-hit/PV row and the complete five-position depth-8 reference above, was byte-identical to Stage 0. SHA-256 remains `ad577304b36f5296642a26f550dc5ef9e0fbc668884ef22a132982e913ae2fd5`.
- Instrumented JAR SHA-256: `2d0b53b5f72257469ff4d67dbdfea173e61544a362cff3b810f187643fd39a81`. The frozen control JAR remains `cdf7fe59b773d55f4a79a59ccccd9b1b6d804f82332b6200cc88b9e65fa5e95b`.
- [`phase21-stage1-counters.tsv`](../docs/architecture/research/phase21-stage1-counters.tsv) preserves all counters per position for the 31 depth-13 and five depth-8 searches. SHA-256: `ab86e4bb311312e0a6f743bf90a8ba182202c31dfdca383d13da814f11166f6f`. The extractor checked 36 final reports, each call-table sum, zero PV/null calls and per-position LMR fail-high/re-search equality.

| Stage 1 command | Result |
|---|---|
| `mvn -pl engine-core -Dtest=SearcherInstrumentationTest test` | PASS: 6 tests, 0 failures/errors/skips |
| `mvn -B -pl engine-core test` | PASS: 406 tests, 0 failures/errors, 5 configured skips |
| `mvn -B -pl engine-core,engine-uci,engine-tuner -am test` | PASS: core 406/5 skipped, UCI 42/8 skipped, tuner 131/1 skipped; 0 failures/errors |
| `mvn -B -pl engine-core,engine-uci,engine-tuner -am package -DskipTests` | PASS: all selected reactor modules built |

The Stage 1 control command is the Stage 0 command above with `Phase21ControlHarness true`, the rebuilt instrumented JAR and the committed logging configuration. Its output confirmed `CONTROL PASS total=24780049 reference_rows=5 instrumentation=true`.

**Aggregate 2x2 invocation table (31 positions, cumulative through depth 13):**

| Existing flag | Wide window | Null window | Total |
|---|---:|---:|---:|
| PV | 3,487 | 0 | 3,487 |
| Non-PV | 20,729,984 | 51,797,819 | 72,527,803 |
| Total | 20,733,471 | 51,797,819 | 72,531,290 |

Wide-window/non-PV calls are 28.580746% of alphaBeta invocations. Every canonical position has a nonzero population.

**Pruning with a live wide window and non-PV flag:**

| Actual event | Count |
|---|---:|
| Razoring return | 878,014 |
| Futility skipped move | 5,180,238 |
| Losing-capture skipped move | 548,209 |

**Completed later-sibling outcome distributions:**

| Returned score | PV/wide ordinary non-LMR siblings | Share of 26,898 | Root wide later siblings | Share of 12,351 |
|---|---:|---:|---:|---:|
| `score <= alpha` | 26,007 | 96.687486% | 12,195 | 98.736944% |
| `alpha < score < beta` | 755 | 2.806900% | 151 | 1.222573% |
| `score >= beta` | 136 | 0.505614% | 5 | 0.040483% |
| Total | 26,898 | 100% | 12,351 | 100% |

**LMR activity:**

| Event | Count |
|---|---:|
| Reduced-depth probes | 12,815,062 |
| Non-aborted reduced-probe fail-highs | 53,656 |
| Current ensuing full-depth re-search invocations | 53,656 |

Fail-highs and current full-depth re-searches are equal in every measured position, including the five depth-8 references.

**Interpretation and stop:**

- The source hypothesis is supported: wide-window/non-PV calls exist at every canonical position, all three non-PV pruning mechanisms fire while the live window is wide, and most measured later siblings return at or below alpha. These are population and outcome observations only.
- Stage 1 **PASS**: the population is nonzero, counters agree with the source model, and all deterministic controls and normal tests remain unchanged/green. There is no negligible-population criterion. No strength estimate or Phase 17-based node-reduction prediction was made.
- Stop before Stage 2. PVS and the alphaBeta signature remain unchanged. No native timing, game or SPRT ran; project SPRT bounds remain unchanged. Stage 0 and Stage 1 are separate commits. Unrelated `.claude/agent-memory/` remains untouched.

**Per-position 2x2 invocation table:**

| Index | Main nodes | alphaBeta calls | PV/wide | PV/null | Non-PV/wide | Non-PV/null |
|---:|---:|---:|---:|---:|---:|---:|
| 0 | 528,398 | 1,708,090 | 92 | 0 | 559,845 | 1,148,153 |
| 1 | 3,475,078 | 8,558,133 | 128 | 0 | 2,032,563 | 6,525,442 |
| 2 | 7,568 | 24,723 | 109 | 0 | 14,294 | 10,320 |
| 3 | 605,659 | 1,587,164 | 218 | 0 | 314,607 | 1,272,339 |
| 4 | 444,366 | 1,391,662 | 99 | 0 | 454,159 | 937,404 |
| 5 | 1,549,645 | 4,758,744 | 86 | 0 | 1,265,387 | 3,493,271 |
| 6 | 634,214 | 1,876,120 | 94 | 0 | 317,365 | 1,558,661 |
| 7 | 994,398 | 2,838,873 | 109 | 0 | 1,237,526 | 1,601,238 |
| 8 | 944,419 | 2,779,742 | 89 | 0 | 550,651 | 2,229,002 |
| 9 | 258,683 | 815,537 | 65 | 0 | 287,488 | 527,984 |
| 10 | 1,463,870 | 4,603,207 | 94 | 0 | 1,460,538 | 3,142,575 |
| 11 | 106,151 | 280,527 | 117 | 0 | 115,195 | 165,215 |
| 12 | 19,151 | 54,819 | 141 | 0 | 34,863 | 19,815 |
| 13 | 134,731 | 415,124 | 112 | 0 | 135,775 | 279,237 |
| 14 | 1,523,227 | 4,559,169 | 91 | 0 | 1,419,381 | 3,139,697 |
| 15 | 939,472 | 2,574,597 | 95 | 0 | 568,072 | 2,006,430 |
| 16 | 815,181 | 2,318,405 | 115 | 0 | 756,856 | 1,561,434 |
| 17 | 467,210 | 1,439,446 | 90 | 0 | 367,707 | 1,071,649 |
| 18 | 1,369,277 | 4,311,263 | 90 | 0 | 1,334,181 | 2,976,992 |
| 19 | 437,737 | 1,448,261 | 82 | 0 | 378,080 | 1,070,099 |
| 20 | 150,814 | 433,316 | 92 | 0 | 46,753 | 386,471 |
| 21 | 2,172,114 | 6,170,463 | 78 | 0 | 1,737,080 | 4,433,305 |
| 22 | 581,587 | 1,600,293 | 126 | 0 | 349,001 | 1,251,166 |
| 23 | 176,857 | 623,247 | 125 | 0 | 160,316 | 462,806 |
| 24 | 57,130 | 175,825 | 107 | 0 | 45,179 | 130,539 |
| 25 | 145,819 | 431,833 | 105 | 0 | 103,133 | 328,595 |
| 26 | 47,736 | 146,596 | 138 | 0 | 95,928 | 50,530 |
| 27 | 72,278 | 250,766 | 104 | 0 | 90,417 | 160,245 |
| 28 | 1,825,906 | 7,088,801 | 149 | 0 | 2,896,222 | 4,192,430 |
| 29 | 2,053,406 | 5,215,393 | 202 | 0 | 1,091,003 | 4,124,188 |
| 30 | 777,967 | 2,051,151 | 145 | 0 | 510,419 | 1,540,587 |

### [2026-09-27] Phase 21 Stage 2: window-derived PV identity and zero-window search

**Built:**

- Repeated the call-site audit before changing the private signature. The six production call categories remain: root child, null move, reduced LMR probe, full-depth LMR verification, ordinary child, and singular alternative. Null-move and singular alternatives use width-one windows; LMR probes and verifications use `(-(alpha + 1), -alpha)`. No auxiliary production caller needs contradictory wide-window/non-PV semantics.
- Removed the explicit PV boolean from `alphaBeta`. Its first statement derives `boolean isPvNode = beta - alpha > 1`, so a recursive call cannot flag PV independently of its window.
- A node's first searched move uses the full incoming window. Every later ordinary move first searches full depth at `(-(alpha + 1), -alpha)`. A later LMR-eligible move first probes at reduced depth using that zero window; a non-aborted result above alpha always receives one full-depth zero-window verification. Only a full-depth result strictly inside the current PV window triggers a full-window re-search. Low scores require no re-search; high scores retain cutoff behavior. A null-window node cannot satisfy the PV and strict-inside gate.
- Applied the same first-move and later-move PVS sequence at the root inside the existing aspiration window. Did not change retry widening, root cutoff termination, or any pruning, ordering, TT, extension, evaluator, Board, SMP or time-management rule.
- Retained Stage 1 fields and added opt-in probe, verification, re-search ownership and all non-PV pruning counters. Stage 3 interpretation: `lmr_researches` now counts full-depth zero-window verifications, not Stage 1's former full-window re-search. The Stage 1 `pv_wide_sibling_*` and `root_wide_sibling_*` report keys are unchanged. Stage 2 uses new `pv_wide_full_window_research_*` and `root_wide_full_window_research_*` keys for completed candidate full-window re-search outcomes; the population is smaller after PVS. `nonpv_razor_returns`, `nonpv_futility_skips` and `nonpv_losing_capture_skips` count all such Stage 3 non-PV pruning events, including their now-expected null-window population; the retained `nonpv_wide_*` fields stay specific to the old wide-window cell.
- Updated the private-reflection singularity test for the new alphaBeta signature, and added a legal-PV check to every search-regression fixture. Updated only the five depth-8 node-count references and the four changed bestmove fixtures after focused tests and direct control/candidate depth probes.

**Search sequence and mechanical checks:**

| Search site | Sequence |
|---|---|
| `alphaBeta`, first searched move | `(-beta, -alpha)` full incoming window |
| Later, not LMR eligible | `(-(alpha + 1), -alpha)` full-depth probe; only a PV result strictly inside `(alpha, beta)` gets `(-beta, -alpha)` re-search |
| Later, LMR eligible | reduced-depth zero-window probe; every non-aborted `score > alpha` gets exactly one full-depth zero-window verification; only a PV verification strictly inside `(alpha, beta)` gets a full-window re-search |
| `searchRoot`, first move | `(-beta, -alpha)` current aspiration/full window |
| `searchRoot`, later moves | zero-window probe; only a result strictly inside `(alpha, beta)` gets a full-window re-search |
| Null move / singular alternative | existing width-one windows |

The signature makes contradictory PV identity impossible at alphaBeta entry. The existing opt-in report measures `ab_calls`, the derived 2x2 table, PVS zero-window probes, PV-wide and root-wide probes, full-depth zero-window probes, all full-window re-searches and PV ownership, root re-searches, LMR probes/fail-highs/verifications, and old/new pruning counts. Instrumentation increments plain fields only; no per-node allocation and no search decision depends on them.

**Deeper probe evidence for changed depth-8 regression fixtures:**

Probed both the frozen control JAR (SHA-256 `cdf7fe59b773d55f4a79a59ccccd9b1b6d804f82332b6200cc88b9e65fa5e95b`) and the clean-built Stage 2 candidate JAR (SHA-256 `4d1325c4bc3e1a4d312870b21ff53716e684a3f16637b7df1800d72341291da4`), each in a fresh JVM. Each probe used the same FEN, evaluator, fresh Searcher and normal default Hash as `SearchRegressionTest`; the search-result rows record bestmove, score and complete returned PV. The test now checks every PV move against legal moves on the evolving board.

| Fixture/depth | Control move / score / PV | Candidate move / score / PV | Reading |
|---|---|---|---|
| P5 / 9 | c1d2 / 354 / `c1d2 d6c6 b4b5 c6c5 d2c3 c5b6 c3d4 b6a5 d4c5 a5a4` | c1b2 / 354 / `c1b2 d6c6 b4b5 c6c5 b2c3 c5b6 c3d4 b6a5 d4c5 a5a4` | Equal score; king approach differs, continuation structure matches. |
| P5 / 10 | c1c2 / 410 / `c1c2 d6c6 b4b5 c6c5 c2c3 c5b6 c3d4 b6a5 d4c5 a5a4 b5b6` | c1d2 / 405 / `c1d2 d6e5 d2d3 e5e6 b4b5 e6d6 d3d4 d6e6 b5b6 e6f5` | Scores differ by 5 cp; both king activations support the connected passers. |
| P10 / 9 | e3f3 / 159 / `e3f3 e5e6 f3f4 e6f6 e4e5 f6e7 f4f5 e7d7 f5f6 d7c6` | e3f3 / 159 / same PV | Exact convergence at the probe depth. |
| P10 / 10 | e3f3 / 193 / `e3f3 e5e6 f3f4 e6f6 e4e5 f6g6 f4e4 g6g5 e5e6 g5f6 e4d5 f6f5` | e3f3 / 203 / `e3f3 e5e6 f3f4 e6f6 e4e5 f6e6 f4e4 e6d7 e4d5 d7e7 e5e6 e7f6` | Same bestmove; candidate score is 10 cp higher. |
| E2 / 9 | e1e2 / 682 / `e1e2 e8d7 e2e3 d7e7 e3e4 e7e6 f1f5 e6e7 f5c5` | e1d2 / 682 / `e1d2 e8e7 d2e3 e7d6 f1f5 d6e6 e3e4 e6e7 f5c5` | Equal score; both use king activation followed by rook/king restriction. |
| E2 / 10 | e1e2 / 694 / `e1e2 e8d7 e2e3 d7e7 e3e4 e7e6 f1f5 e6e7 e4d5 e7d7` | e1d2 / 696 / `e1d2 e8e7 d2e3 e7d6 e3d4 d6d7 d4d5 d7e7 f1f4 e7d7` | Candidate score is 2 cp higher, with a different king approach. |
| E5 / 9 | a2e2 / 1768 / `a2e2 e7e6 e1f2 e6d7 e5e6 d7e7 f2e1 e7d6 e6e7 f6f5 e2f2 f5g4` | a2a6 / 876 / `a2a6` | Candidate returned a legal one-move PV and an 892 cp lower score at depth 9. |
| E5 / 10 | a2e2 / 1804 / `a2e2 e7e6 e1f2 e6d7 e5e6 d7e7 f2e1` | a2e2 / 1760 / `a2e2 e7e6 e1f2 e6d7 e5e6 d7e7 f2e1 e7d6 e6e7 d6d5 e2d2 d5c5 e7e8q` | Same bestmove; score gap narrows to 44 cp. The depth-9 candidate PV contains only its first legal move; this remains for the later trace gate. |

The depth-8 fixture choices are deterministic alternatives from the new tree. P5 and P10 are equal-score moves; E2's move and score converge within 2 cp at depths 9 and 10. E5 remains the largest shallow score difference: it narrows by 848 cp from depth 9 to depth 10, its depth-9 one-move PV is legal, and both engines choose a2e2 at depth 10. This did not show an unverified LMR score, illegal PV move, mate-sign error, or root fail-high defect. It does leave E5's shallow evaluation and PV truncation for the later trace gate; Stage 4 has not run.

**Node-count fixture recapture:**

The focused implementation tests passed before recapturing the small depth-8 node fixture. Full core tests confirmed the candidate vector below; the 31-position Stage 0 table remains frozen in its separate control artifact.

| NodeCountRegressionTest position | Frozen Stage 0 nodes | Stage 2 candidate nodes | Delta |
|---|---:|---:|---:|
| Start | 14,926 | 14,776 | -150 |
| K+P vs K | 1,226 | 1,287 | +61 |
| Tactical middlegame | 34,694 | 32,267 | -2,427 |
| Rook/pawn | 6,456 | 6,136 | -320 |
| Queen/king | 8,902 | 7,829 | -1,073 |

These five depth-8 counts pin the new deterministic Stage 2 tree. They do not replace the frozen Stage 0 depth-13 total or predict Stage 3 aggregate reduction.

**Validation:**

- Independent read-only review of the Stage 2 diff found no remaining blocker. It asked for explicit legal-PV coverage of the five frozen reference positions, unambiguous Stage 3 full-window outcome keys, and a more complete record of E5's shallow PV/score anomaly. All three were added; the reviewer confirmed the blocking findings were resolved. E5 remains visible for the later decision-quality trace.

| Stage 2 command | Result |
|---|---|
| `mvn -B -pl engine-core -Dtest=SearcherInstrumentationTest,RootFailHighWindowTest test` | PASS: 10 tests, 0 failures/errors/skips |
| Final focused rerun: `mvn -B -pl engine-core -Dtest=SearcherInstrumentationTest,RootFailHighWindowTest,SingularSearchBoundSemanticsTest test` | PASS: 15 tests, 0 failures/errors/skips, including legal PVs for P18-5 |
| `mvn -B -pl engine-core test` | PASS: 407 tests, 0 failures/errors, 5 configured skips |
| `mvn -B -pl engine-core -Dgroups=regression test` | PASS: 38 tests, 0 failures/errors/skips |
| `mvn -B -pl engine-core,engine-uci,engine-tuner -am test` | PASS: core 407/5 skipped, UCI 42/8 skipped, tuner 131/1 skipped; 0 failures/errors |
| `mvn -B -pl engine-core,engine-uci,engine-tuner -am package -DskipTests` | PASS: selected reactor modules built |

The clean-built candidate UCI JAR SHA-256 is `4d1325c4bc3e1a4d312870b21ff53716e684a3f16637b7df1800d72341291da4`. Its alphaBeta signature is `alphaBeta(Board,int,int,int,int,BooleanSupplier,boolean,int,int,boolean)`; the explicit PV flag is absent.

**Stage 2: PASS.** Tests exercise mandatory LMR verification, strict PV full-window re-search ownership, window-derived flag cells, the root PVS path, legal regression PVs and the P18-4 root fail-high case. No fixture revealed a correctness defect under the bounded deeper probes. At the Stage 2 commit, Stage 3 measurement had not started; it is recorded below.

### [2026-09-27] Phase 21 Stage 3: deterministic mechanism gate

**Candidate and controls:**

- Candidate production code is Stage 2 commit `e90d3d4f90b244c46e8bbf75c33222bba7d58003`; candidate UCI JAR SHA-256 is `4d1325c4bc3e1a4d312870b21ff53716e684a3f16637b7df1800d72341291da4`. Frozen control JAR SHA-256 is `cdf7fe59b773d55f4a79a59ccccd9b1b6d804f82332b6200cc88b9e65fa5e95b`.
- Reused the frozen 31-position corpus at depth 13 and five P18-5 references at depth 8. Each row used a fresh Searcher, 16 MB transposition table, and the same deterministic search setup. The candidate harness required each requested depth to complete and checked every returned PV move for legality. It reported `CANDIDATE PASS total=21713284 reference_rows=5 instrumentation=true`.
- Stage 1 was not rerun. The combined control/candidate per-position table and all five reference rows are committed in [phase21-stage3-results.tsv](../docs/architecture/research/phase21-stage3-results.tsv), SHA-256 `728c1f5cf8254606af4a61504805f88d0c350ef13a5428b4593f479f191fc56d` (36 rows: 31 depth-13 positions plus five depth-8 references).
- Counter population: per position, use the final cumulative depth-13 `[BENCH] phase21` report. The local `per-pvIndex` counters reset at each depth, but the `totalPhase21*` accumulators persist across completed iterative-deepening depths and aspiration retries. Thus these counters cover the completed search through depth 13, including prior completed depths and retries; they are not limited to the depth-13 iteration. The 2x2 values sum `alphaBeta` invocation classifications over the 31 positions; they are call counts, not `nodesVisited`, and exclude quiescence calls. Root later-sibling probes are recorded in their separate root counters. Stage 1 and Stage 3 use these same cumulative accounting semantics, so their cross-tree mechanism comparison remains valid.

**Depth-13 search totals:**

| Metric | Frozen Stage 0 control | Stage 3 candidate | Candidate delta |
|---|---:|---:|---:|
| Main nodes | 24,780,049 | 21,713,284 | -3,066,765 (-12.376%) |
| Qnodes | 60,071,833 | 48,479,879 | -11,591,954 |
| TT hits | 5,726,579 | 6,134,980 | +408,401 |

**Candidate alphaBeta flag/window table (31 depth-13 searches):**

| Derived PV identity | Wide (`beta - alpha > 1`) | Null (`beta - alpha == 1`) |
|---|---:|---:|
| PV | 23,580 | 0 |
| Non-PV | 0 | 62,426,843 |
| Total calls | 23,580 | 62,426,843 |

The sum is 62,450,423 alphaBeta calls. Both contradictory cells are zero: PV/null is impossible because PV identity is derived at entry, and non-PV/wide is absent. For comparison, the frozen Stage 1 depth-13 table was PV/wide 3,487; PV/null 0; non-PV/wide 20,729,984; non-PV/null 51,797,819. The candidate's call totals traverse a different tree and are not treated as a same-tree population comparison.

**Pruning populations:**

| Pruning event | Stage 1 wide-window/non-PV | Stage 3 wide-window/non-PV | Stage 3 all non-PV/null |
|---|---:|---:|---:|
| Razoring returns | 878,014 | 0 | 2,312,723 |
| Futility skips | 5,180,238 | 0 | 11,313,611 |
| Losing-capture skips | 548,209 | 0 | 1,181,411 |

The candidate wide-window/non-PV cell is empty. Its all non-PV pruning counts are reported for the candidate tree's null-window population; they are not a same-tree comparison to Stage 1's wide-window subset.

**PVS probes and later-sibling outcomes:**

- Candidate later-sibling zero-window probes: 40,260,252 total, including 28,171,217 full-depth zero-window probes. Of these, 375,370 were at internal PV nodes and 12,273 at the root.
- There were 6,517 full-window re-searches in total; all 6,517 were PV-owned. This total includes 5,521 internal non-LMR later-sibling re-searches, 814 re-searches following LMR verification, and 182 root re-searches.
- The Stage 1-compatible internal non-LMR outcome split was: score `<= alpha` 314; `alpha < score < beta` 4,832; score `>= beta` 375. Root outcomes were 22; 154; and 6, respectively (182 total).
- Combined full-window re-search rate was 6,517 / (375,370 internal PV probes + 12,273 root probes) = 1.68%. Internal rate was 6,335 / 375,370 = 1.69%; root rate was 182 / 12,273 = 1.48%. This is not a majority of PV null-window probes.

**LMR verification:**

| Counter | Frozen Stage 1 | Stage 3 candidate |
|---|---:|---:|
| Reduced probes | 12,815,062 | 12,089,035 |
| Reduced fail-highs | 53,656 | 31,386 |
| Full-depth follow-up | 53,656 existing full-depth re-searches | 31,386 full-depth zero-window verifications |

Every candidate non-aborted reduced-probe fail-high had exactly one counted full-depth zero-window verification: `lmr_fail_highs == lmr_researches == 31,386`. Verification activity is 58.49% of the frozen Stage 1 re-search count (1.71x lower), not an order-of-magnitude collapse. These counts are mechanism evidence across different trees; the comparison does not assume identical populations.

**Per-position depth-13 main-node changes:**

| BENCH index | Stage 0 control | Stage 3 candidate | Delta |
|---:|---:|---:|---:|
| 0 | 528,398 | 503,502 | -24,896 |
| 1 | 3,475,078 | 3,719,319 | +244,241 |
| 2 | 7,568 | 9,081 | +1,513 |
| 3 | 605,659 | 856,243 | +250,584 |
| 4 | 444,366 | 421,737 | -22,629 |
| 5 | 1,549,645 | 983,092 | -566,553 |
| 6 | 634,214 | 532,753 | -101,461 |
| 7 | 994,398 | 761,682 | -232,716 |
| 8 | 944,419 | 1,065,327 | +120,908 |
| 9 | 258,683 | 224,527 | -34,156 |
| 10 | 1,463,870 | 371,551 | -1,092,319 |
| 11 | 106,151 | 113,241 | +7,090 |
| 12 | 19,151 | 19,767 | +616 |
| 13 | 134,731 | 189,541 | +54,810 |
| 14 | 1,523,227 | 1,172,659 | -350,568 |
| 15 | 939,472 | 777,022 | -162,450 |
| 16 | 815,181 | 639,344 | -175,837 |
| 17 | 467,210 | 452,728 | -14,482 |
| 18 | 1,369,277 | 1,206,922 | -162,355 |
| 19 | 437,737 | 381,362 | -56,375 |
| 20 | 150,814 | 169,381 | +18,567 |
| 21 | 2,172,114 | 1,759,077 | -413,037 |
| 22 | 581,587 | 612,390 | +30,803 |
| 23 | 176,857 | 172,663 | -4,194 |
| 24 | 57,130 | 53,667 | -3,463 |
| 25 | 145,819 | 216,932 | +71,113 |
| 26 | 47,736 | 48,453 | +717 |
| 27 | 72,278 | 64,657 | -7,621 |
| 28 | 1,825,906 | 1,442,772 | -383,134 |
| 29 | 2,053,406 | 1,789,736 | -263,670 |
| 30 | 777,967 | 982,156 | +204,189 |

**Five-position P18-5 depth-8 semantic reference:** Full PV strings are in the TSV; these rows record move, score, nodes, qnodes and TT hits for control and candidate.

| BENCH index | Control: move / score / nodes / qnodes / TT hits | Candidate: move / score / nodes / qnodes / TT hits |
|---:|---|---|
| 0 | e2e4 / 25 / 14,926 / 39,927 / 4,908 | e2e4 / 25 / 14,776 / 38,388 / 6,408 |
| 2 | e1d2 / 122 / 1,226 / 1,816 / 1,024 | e1d2 / 122 / 1,287 / 1,855 / 1,002 |
| 6 | b4b2 / -63 / 34,694 / 88,266 / 14,691 | b4b2 / -55 / 32,267 / 74,334 / 14,840 |
| 13 | b4f4 / 14 / 6,456 / 14,776 / 1,938 | b4f4 / 14 / 6,136 / 13,640 / 1,913 |
| 20 | d7d2 / 1,565 / 8,902 / 16,736 / 3,982 | d7d2 / 1,567 / 7,829 / 13,552 / 4,259 |

**Decision:** Stage 3 mechanism gate **PASS**. The node total decreased; both contradictory flag/window cells were zero; LMR fail-high and verification counts matched exactly; every measured full-window re-search was PV-owned; and the full-window rate was not pathological under the frozen “most PV null-window probes” criterion. Verification activity did not trigger the order-of-magnitude stop. This is mechanism evidence only, with no strength estimate. Per the task boundary, execution ends here; Stage 4 has not been run.

### [2026-09-27] Phase 21 Stage 4: decision-quality trace

**Stage 3 documentation correction:** Commit `66a79c5` corrects the counter population description above. The per-`pvIndex` locals reset at each depth, while `totalPhase21*` persists and is reported on the final depth-13 line. Stage 1 and Stage 3 both use cumulative counts through the completed search up to depth 13, including prior completed depths and aspiration retries. Stage 3 numeric results and its committed table were not changed, and neither Stage 1 nor Stage 3 was rerun for this correction.

**Trace setup:**

- Control: frozen Stage 0 JAR, SHA-256 `cdf7fe59b773d55f4a79a59ccccd9b1b6d804f82332b6200cc88b9e65fa5e95b`.
- Candidate: Stage 2 JAR, SHA-256 `4d1325c4bc3e1a4d312870b21ff53716e684a3f16637b7df1800d72341291da4`.
- The depth-13 comparison reuses the frozen Stage 3 rows for all 31 positions: 8 different bestmoves and 23 the same. No Stage 1 or Stage 3 measurement was repeated. For each of the eight changed positions, control and candidate were each run at depths 14 and 15 in fresh JVMs, using a new Searcher, a cold 16 MB TT, fixed-depth `searchDepth`, single-threaded direct search, and instrumentation disabled. All 32 searches completed at the requested depth, and each returned PV passed a legal-move check. A separate no-search validation replayed all 16 frozen depth-13 control/candidate PVs against legal move generation; all passed.
- Full depth-13/14/15 rows, including score, main nodes, qnodes, TT hits and PV, are in [phase21-stage4-decision-trace.tsv](../docs/architecture/research/phase21-stage4-decision-trace.tsv), SHA-256 `e5470c9d6b08e86633d78db331ce3d22e68d1e4d5dd75e71f3d56ff4da31ee45`. `d13_source` marks frozen Stage 3 rows; depth 14 and 15 are fresh cold searches. The reproducible helper is `tools/Phase21DecisionTrace.java`, driven by `tools/phase21-stage4-trace.py`.

**Classification by changed BENCH index:**

| Index | Control move/score at 13 → 14 → 15 | Candidate move/score at 13 → 14 → 15 | Trace reading |
|---:|---|---|---|
| 5 | `d1d3/416` → `g1h1/418` → `g1h1/418` | `g1h1/410` → `g1h1/411` → `g1h1/418` | Converges to `g1h1` at depth 14; score also matches at 15. |
| 7 | `h3f5/-295` → `g2g4/-285` → `h3f5/-305` | `f3d4/-300` → `f3d4/-299` → `f3d4/-317` | Distinct move choices remain at 15. Raw score gaps are 5, 14 and 12 cp; no near-equality threshold is applied. Control changes back to its depth-13 choice. |
| 10 | `c6d4/432` → `a7a6/426` → `c6e5/421` | `c8d7/445` → `a7a6/421` → `c6d4/431` | Converges at 14, then diverges at 15. The score ordering changes with depth; no stable score difference is established. |
| 14 | `a2a4/59` → `d1d3/54` → `d1d3/52` | `f1e1/65` → `d1d3/58` → `d1d3/59` | Converges to `d1d3` at 14 and 15; raw score gaps are 4 and 7 cp. |
| 16 | `d5e4/-51` → `d5e4/-51` → `d5e4/-54` | `f6e4/-38` → `d5e4/-44` → `d5e4/-41` | Converges to `d5e4` at 14 and 15. Candidate scores remain 7 and 13 cp higher. |
| 21 | `c6a6/-37` → `g6g5/-32` → `g6g5/-38` | `g6g5/-35` → `g6g5/-35` → `g6g5/-46` | Converges to `g6g5` at 14 and 15; raw score gaps are 3 and 8 cp. |
| 26 | `a6a5/422` → `a6a5/397` → `a6a5/442` | `b5b4/281` → `a6a5/388` → `a6a5/1064` | Same move from 14, but candidate score rises sharply at 15 (622 cp above control). This is depth-sensitive, not a stable score difference. Candidate's one-move depth-13 PV extends to 14 plies at depth 14 and 10 plies at depth 15; every returned move is legal. |
| 30 | `f2f5/-99` → `f2f3/-118` → `f2f3/-113` | `b1a2/-121` → `h4h5/-111` → `f2f3/-114` | Converges to `f2f3` at 15; final scores differ by 1 cp. Candidate/control score ordering reverses at 14 and again at 15. |

No trace established a transposition-equivalent continuation. The listed convergence classifications refer to the root bestmove; complete PVs are preserved in the TSV. No score is in the engine's mate-score range. Stage 2's mandatory full-depth LMR verification path and root P18-4 fail-high regression remain unchanged; Stage 3's cumulative fail-high/verification counters were exact. Stage 4 exposed no illegal PV, mate-sign, unverified-score or root-window defect. The large index-26 depth-15 score change is recorded as search-depth sensitivity, not treated as a correctness defect on its own.

Stage 3 node increases at indexes 1, 3, 8, 25 and 30 were +244,241, +250,584, +120,908, +71,113 and +204,189 respectively. They are context only; no node-increase gate was added. Indexes 1, 3, 8 and 25 had no depth-13 bestmove difference, so the frozen Stage 4 protocol required no deeper trace for them. Index 30 was traced because its depth-13 bestmove changed.

**Historical depth-8 fixtures:** The four changed regression moves were P5 `c1b2`, P10 `e3d3`, E2 `e1d2` and E5 `a2a6`. E5's FEN (`8/4k3/8/4P3/8/8/R7/4K3 w - - 0 1`) is not among the 31 canonical BENCH positions, so the conditional Stage 4 E5 search did not apply. The existing depth-9/10 probes remain the E5 trace: at depth 9, control chose `a2e2/1768` with a longer PV and candidate chose `a2a6/876` with a one-move legal PV; at depth 10 both chose `a2e2`, with scores 1804 and 1760. Thus the one-move E5 PV was a shallow result and did not persist at depth 10. No additional E5 search was run.

**Stage 4: PASS.** None of the traced decision differences exposed a correctness defect. Ordinary move and score differences, including the recorded depth-sensitive index-26 score, remain search-tree evidence. No Stage 5 work was run.

### [2026-09-27] Stage 5 runner draft: stopped by user

Implementation of `tools/phase21-stage5.ps1` was in progress when the user requested an immediate stop. The current draft is preserved for review; implementation and validation are incomplete, and it is not ready for a native timing session. No further runner changes were made after the stop request.

PowerShell syntax/validation attempts through WSL interop failed before PowerShell started (`UtilBindVsockAnyPort: socket failed 1`). They provide no script-validation evidence. No native builds, semantic preflights, warm-ups, measured benchmarks, games or SPRT were run. Stage 5 has no measurement or PASS/FAIL decision; Stage 4 remains the last completed stage.

### [2026-10-09] Stage 5 runner preparation completed; native validation pending

**Built:**

- Completed the existing `tools/phase21-stage5.ps1` draft. The schedule now matches the frozen workload: one discarded warm-up per arm followed by seven measured runs per arm, alternating control/candidate. Removed the two extra benchmark preflights; node totals are checked by the warm-ups and every later invocation.
- The runner requires native Windows 11 Pro build 26200, Ryzen 7 7700X, Balanced power plan, and Azul Zulu JDK 21.0.10+7-LTS. It rejects WSL, inherited JVM option variables, dirty worktrees, unexpected pre-existing build outputs, and nonmatching benchmark source.
- Control and candidate build from detached worktrees at `d3a56ffadf0d9151a2b19fba4902a734a99bff96` and `e90d3d4f90b244c46e8bbf75c33222bba7d58003`, using the same Maven and Java executables. The run directory records source identities, JAR hashes, environment, build logs, raw outputs, planned order, per-run rows and summary. Reported elapsed time comes from the engine's raw `Time` field, outside build and warm-up work.
- Verified both production commits contain the same canonical `BenchRunner.java` blob, SHA-256 `882f5edc5159dba94bb6faefbb456f1a12e77f2798753a6a82d58755c9b1361f`; the candidate commit object is present locally.
- Cleanup removes only worktrees created by this invocation, without force, and removes the temporary parent only when empty. A dirty or partial worktree is left in place for inspection; run artifacts remain in the unique results directory.
- Added `-ValidateOnly` fixtures for parser acceptance and rejection, exact node totals, schedule cardinality/order, elapsed summaries and ratios, median and aggregate NPS floors, nonempty-directory preservation, and failed-command output retention. The gate retains the preregistered elapsed bound, the requested candidate median NPS floor, and the repository aggregate NPS floor.

**Validation:**

- Native Windows PowerShell 5.1 and PowerShell 7 interop were both attempted with `-ValidateOnly`. Both failed before PowerShell started with `UtilBindVsockAnyPort: socket failed 1`, so the PowerShell parser and fixtures have not executed in this session. Source-level review confirms the validation branch returns before any native host probe, Maven build, Java launch or benchmark.
- A context-mode static synthetic audit passed for the frozen constants and schedule, parser fixture shape, elapsed/NPS calculations and gate boundaries, cleanup guard, and failure-log ordering. This is not a PowerShell parse or runtime result.
- No native builds, timing runs, games or SPRT were run. Stage 5 has no measurement or PASS/FAIL decision; Stage 4 remains the last completed stage. Run `.\tools\phase21-stage5.ps1 -ValidateOnly` in native PowerShell before the measurement command.

### [2026-10-09] Stage 5 machine identity follows computer name and CPU

The user reported Windows build 26300 and removed the fixed Windows edition/build requirement because routine system updates change it. The runner now identifies the benchmark desktop by computer name `RENEGADE` (case-insensitive) and Ryzen 7 7700X CPU. The P16-2 native baseline records `RENEGADE`, matching the current WSL hostname. Windows caption, version and build remain recorded as environment evidence. Native Windows execution checks and the JDK/power-plan protocol requirements remain in place. Stage 5 timing has not run; native PowerShell validation remains pending because interop could not start.

### [2026-10-09] Stage 5 source hash accepts Windows checkout line endings

The user's native invocation stopped at the control corpus hash check before building or benchmarking. Converting the frozen LF source to CRLF reproduces the reported SHA-256 exactly (`02630596d4ceb14e43e13befe971ed8e07ce5c68d33e9588369542b1be682bb1`). The corpus check now hashes UTF-8 text with CRLF normalized to LF, retaining the frozen SHA-256 and rejecting source-content changes. Identity metadata records this normalization; JAR hashes remain byte-exact.

Added a regression fixture for LF/CRLF equivalence and changed-content rejection. Windows PowerShell 5.1 `-ValidateOnly` passed after running interop with sandbox escalation; this also exposed and fixed a quoting issue in the synthetic failed-command fixture. PowerShell 7 stopped at execution policy before loading the script. No build, JVM launch or benchmark was executed. The user requested that all further script execution be left to them. Stage 5 remains unmeasured with no PASS/FAIL decision.

### [2026-10-09] Stage 5 native throughput: PASS, complete evidence preserved

The user completed native run `20261009T142702733Z` on `RENEGADE`, Ryzen 7 7700X, Windows build 26300, Balanced power plan, and Azul Zulu 21.0.10+7-LTS. [The review](../docs/architecture/research/phase21-stage5-native-throughput-review.md) independently verified all 16 raw invocations, the exact schedule, 31-position node vectors, environment, production identities and recorded JAR hashes, build logs, and gate calculations. Control remained `d3a56ffadf0d9151a2b19fba4902a734a99bff96`; candidate remained `e90d3d4f90b244c46e8bbf75c33222bba7d58003`.

Control elapsed min/median/max was 68.156/71.795/80.462 s; candidate was 61.268/62.732/67.600 s. Candidate median was 5.424 s below the control minimum and 12.623% below control median. Candidate median/aggregate NPS was 346,127/342,664, both above 301,116. Node totals were exactly 24,780,049 and 21,713,284 in every respective invocation, including discarded warm-ups. **Stage 5: PASS.** This establishes throughput improvement, not playing strength.

All 25 original successful-run files and the available earlier failed-startup transcript are archived under [phase21-stage5-native](../docs/architecture/research/phase21-stage5-native/README.md), with per-directory checksum manifests and text conversion disabled. The archive index preserves the user-authorized OS-build amendment and source-hash correction. Copies were verified against the Windows originals; no benchmark was rerun. Temporary native worktrees were removed by the completed runner. Stage 6 remains unstarted and its policy has not been selected.

### [2026-10-09] Stage 6 policy comparison proposed, no policy selected

Inspected `docs/sprt-guidelines.md`, `tools/sprt.ps1`, `tools/match.ps1`, nightly SPRT settings, the archived Phase 17 preregistration and closure, Phase 19's diagnostic records, and Phase 21's strength conditions. [The short preregistration proposal](../docs/architecture/research/phase21-stage6-strength-policy-proposal.md) compares `[0,50]`, `[0,10]` and `[-5,0]`, with endpoint verdict/error meanings, promotion consequences, illustrative sequential game-count/runtime estimates, exact unchanged production identities, and common match conditions.

Recommendation: `[0,10]` with alpha = beta = 0.05, pending the user's choice. No alternative policy was selected, and the existing `[0,50]` project policy remains in force. The proposal explicitly records the mismatch between the documented policy and the generic runner's `[0,10]`/`60+0.6` defaults, and distinguishes non-inferiority with a 5-Elo margin from proof of no loss. Numerical workload estimates use stated statistical/runtime assumptions, not measured Phase 21 games or simulations. Stage 5 JAR hashes remain archived provenance; native match rebuild hashes must be frozen before an approved run because the temporary binaries were cleaned up. No SPRT, games, additional benchmarks, or production-code changes were performed.

### [2026-10-09] Stage 6 decision frozen; native workflow prepared, no games

The maintainer approved one `[0,10]` SPRT with alpha=beta=0.05, BonferroniM=1, MinGames=0 and MaxGames=20000. Recorded the [dated amendment](../docs/architecture/research/phase21-direction-and-preregistration.md#15-approved-stage-6-amendment--2026-10-09), converted the [proposal into the frozen execution preregistration](../docs/architecture/research/phase21-stage6-strength-policy-proposal.md), and documented the Phase 21 exception in `docs/sprt-guidelines.md`. The generic `[0,50]` policy remains unchanged. H1 means eligible for promotion after evidence review; H0 rejects; the cap without a boundary is INCONCLUSIVE with no promotion. No rescue SPRT or post-hoc change is permitted.

Exact sources remain control `d3a56ffadf0d9151a2b19fba4902a734a99bff96` and candidate `e90d3d4f90b244c46e8bbf75c33222bba7d58003`. Frozen conditions are TC `5+0.05`, concurrency 6, Threads=1, Hash=16, PawnHashSize=1, Classical, MultiPV=1, Contempt=0, OwnBook=false, SyzygyOnline=false and empty SyzygyPath, with other defaults unchanged. Native host is RENEGADE/Ryzen 7 7700X, Balanced/full affinity, Zulu 21.0.10+7-LTS, Maven 3.9.16, Cute Chess 1.5.1. Record OS build without pinning it. Opening SHA-256 remains `2011193b4854e9a8cfdc05312ca2dbaffa6ceae3abbdee20e2ead2a18a603347`, random EPD/plies=4 with swapped-color repeat; resignation 5 moves/400 cp and draw move 40/8 moves/10 cp.

Added `tools/phase21-stage6.ps1`, pure configuration/result helpers in `tools/phase21-stage6-common.ps1`, and `tools/phase21-stage6-validation.ps1`. The wrapper has no default execution action; `-ValidateOnly` uses fixtures, while `-ExecuteNative` requires a native checkout/host, builds both exact commits in fresh detached worktrees with the same toolchain, rejects dirty sources/stale outputs, freezes executable JAR hashes and complete argument manifests, then asks for the explicit native operator launch phrase. JARs, corpus, environment/source/tool identities, logs/PGN, summary/color/pair audits and checksums remain in a unique `tools/results/phase21-stage6/<UTC>-<suffix>/` directory. Only owned processes/worktrees are stopped/removed; dirty or partial worktrees and all run evidence are preserved.

Reused `tools/sprt.ps1` match construction and stopping logic. Added opt-in preparation/argument-manifest support, actual argument comparison, immediate log flushing and nonzero exit rejection. Scoped native stderr handling for PowerShell 5.1 so Java/git stderr does not abort preflight before logging. Generic defaults and arguments remain unchanged: its opening input is still optional, `MinGames` is not a minimum-stop barrier, and `-recover` remains. The wrapper enforces approved configuration/corpus, monitors fault lines, and stops its owned child tree on a detected fault; any recovery before observation is retained and blocks a valid promotion.

**Validation:** 75 assert-based synthetic checks passed in both Windows PowerShell 5.1 and PowerShell 7 through interop. They covered frozen identities/configuration, host/tool/affinity rejection and accepted OS-build updates, asymmetric/missing parameters, exact argument export and mismatch rejection, JAR structure/missing binaries, W/L/D and color orientation, paired openings, H0/H1/cap/incomplete/interruption classifications, boundary cancellations, duplicate/mismatched/truncated evidence, crash/timeout/option faults, failed-command stdout/stderr retention, and owned cleanup/preservation. Executable fixtures were inert; the only native command fixture was harmless `cmd.exe` output plus a deliberate exit code. No installed Cute Chess, Java or Maven executable was launched.

An independent read-only review found five gaps, all fixed and re-reviewed: PowerShell 5 stderr handling, hardware-based affinity mask, winner-on-time/stall fault wording, immutable parameter/prepared-argument manifests across confirmation, and protecting final evidence reads so cleanup/checksums still run after a read error. WSL static checks verified the corpus hash, documentation file links, whitespace and unchanged engine/Stage 5 archive scope. No additional blocker was found.

**Remaining native checks:** actual host/tool versions, clean isolated native builds and resulting JAR identities, prepared native arguments, and live child-process/interrupt behavior. These have not run as experimental evidence. Native commands are `.\tools\phase21-stage6.ps1 -ValidateOnly`, then eventually `.\tools\phase21-stage6.ps1 -ExecuteNative`; execution asks for `RUN-PHASE21-STAGE6` before game 1. Hard terminal/OS termination can prevent final summary generation; preserve raw evidence for review. **Stage 6 remains unstarted.** No games, SPRT, native builds, extra benchmarks, production changes or search-parameter changes were performed; `.claude/agent-memory/` was untouched.

### [2026-10-11] Phase 21 closure and v0.6.0 release preparation

**Built:**

- Added the [Phase 21 closure](../docs/architecture/research/phase21-closure.md) and v0.6.0 release notes. The closure records the source-backed PV/window contract, implementation and regression coverage, all stage gates, exact candidate source identity, and the accepted provenance limitation.
- Reworked the release workflow to validate an existing stable version tag and module POMs, verify source ancestry, build and run CI regression groups, smoke-test the exact UCI JAR, generate a checksum and source manifest, and create or resume a draft release.

**Decisions Made:**

- Keep all four Maven modules on v0.6.0 for release, then move development to 0.6.1-SNAPSHOT.
- Keep throughput and strength as separate measurements. Stage 5 passed with 12.6234% lower median elapsed time. Stage 6 accepted H1 under [0,10], alpha = beta = 0.05, after 13,118 scored games; the estimate was +6.6 +/- 4.6 Elo. H1 does not prove a minimum +10 Elo gain.
- Record the first attempt's setup fault separately from the successful-run record. The maintainer's later statement was: “The first SPRT attempt encountered an error, so I reran it.” Missing records prevent reconstruction of the exact fault and any unrecorded outcomes. This is not machine proof that no earlier game finished. The maintainer accepted the limitation and authorized release preparation.
- Preserve evidence commit 8085d99 and its full history unchanged.

**Broke / Fixed:**

- The old release workflow could select an ambiguous JAR, ignored release type for SNAPSHOT versions, directly pushed version commits to develop with broad staging, and could leave partially published releases. The tag-based workflow has no source-branch push or automatic publication; failed uploads leave a draft that can be resumed.

**Measurements:**

- Stage 3: 21,713,284 candidate main nodes versus 24,780,049 control nodes; 48,479,879 versus 60,071,833 quiescence nodes; candidate PVs passed legality checks.
- Stage 4: 8 of 31 depth-13 best moves changed; 32 deeper searches completed with legal PVs, and all 16 frozen depth-13 PVs replayed legally. No correctness defect was exposed.
- Stage 5: throughput gate PASS, 12.6234% median elapsed-time improvement.
- Stage 6: 4,080 wins, 3,829 losses, 5,209 draws; 13,118 scored games; LLR 2.97 above the 2.94 upper boundary; H1 accepted. Five cancellations were not scored.

### [2026-10-12] v0.6.0 against v0.5.7 SPRT preregistered, nothing run

**Built:**

- A [preregistration](../docs/architecture/research/release-strength-sprt-v0.5.7-v0.6.0/preregistration.md) for one SPRT between the published `engine-uci-0.6.0.jar` and `engine-uci-0.5.7.jar`. Proposed, not approved. No game, pilot or search has been played.
- `tools/release-strength-sprt.ps1` (with `-ValidateOnly`, `-PreflightNative` and `-ExecuteNative`), its common helpers and validation, a frozen 10,000-position opening list with a reproducible generator, a post-match analyzer, and Python contract tests.
- UCI handshake transcripts for both published JARs under `compatibility/`.

**Decisions Made:**

- The baseline is the published v0.5.7 JAR, not a build of the `v0.5.7` tag, because the tag points at older source than the JAR was built from.
- Reused `sprt.ps1` and the Phase 21 result parser instead of writing a second match path. The one change to `sprt.ps1` is an `-OpeningOrder` parameter that defaults to `random`, so every existing caller gets an identical argument list.
- Replaced the Phase 21 `order=random` opening selection with a fixed list used in order. The list is the 10,000 lowest ranks of the archived 150,932-line book under a salted SHA-256 ranking, so it does not depend on a random-number generator or on the source file's order.
- The option lists differ in one place. v0.5.7 has no `EvalType` option, so it is not sent one, and the runner and handshake check both refuse it. `Contempt=0` is set explicitly on both.
- Every runner invocation writes an attempt record before any probe and never deletes anything, because the Phase 21 first attempt could not be reconstructed.
- Kept zero tolerance for time forfeits, as in Phase 21, but listed it as an open decision.
- Corrected the release comparison report's Contempt sentence (maintainer-approved), keeping the original wording and the evidence in a Corrections section. The report said the UCI `Contempt` default is 50 in v0.5.7. That is true of the source the `v0.5.7` git tag names (`7b5b3a4`) and false of the published JAR (source `beaef36`, default 0): the default was reverted in `29aafac` on 2026-07-03. The report had conflated the tag with the release, which is the distinction it documents elsewhere. My first note on this said the 50 was an unused constant; that was wrong and is fixed.

**Broke / Fixed:**

- The analyzer crashed on a small one-sided sample because the 95% interval left (0, 1) and the Elo logarithm is undefined there. A synthetic test caught it. Elo is now `None` at those bounds.
- `Get-Command java` returns two paths on Linux; the lookup now takes the first.
- An independent Codex review (gpt-6-luna, xhigh) found no blocker and seven should-fix items, all taken. Runner: the attempt record is now written before the platform, dirty-tree and host checks, so a refused invocation is recorded (exercised on Linux, where the platform check refuses); a machine-wide mutex stops two concurrent attempts; script hashes are rechecked after the confirmation prompt; 16 logical processors are enforced, not just recorded. Document: the LLR cross-check tolerance went from 0.02 to 0.1 because Cute Chess and the analyzer use different likelihood models and one calibration point is not enough to justify 0.02; the Elo intervals are labelled nominal fixed-sample intervals; the game-level SPRT's independence assumption across paired games is listed as a limitation. The review also confirmed from the Cute Chess 1.5.1 source that `-repeat` plays each opening twice with sides swapped, that sequential EPD order follows the file, and that 20,000 game starts use exactly the 10,000 openings without wraparound.

**Measurements:**

- The analyzer reproduces the archived Phase 21 result: 13,118 scored games, 4,080-3,829-5,209, LLR 2.974 against Cute Chess's 2.97, Elo 6.65 with a 95% half-width of 4.6 against 6.6 +/- 4.6. 6,558 completed pairs plus 2 unpaired games equal the scored total.
- Planning numbers at [0,20] with a 39.7% draw ratio: P(accept H1) is 0.05 at 0 Elo and 0.95 at 20 Elo; expected games are about 960 at either end and about 1,580 at 10 Elo. The Phase 21 match took 7 hours 13 minutes for 13,123 games at this concurrency.
- Validation: 70 new synthetic PowerShell checks, 8 Python tests, and the 75 Phase 21 checks still pass, under PowerShell 7.6.6 on Linux. Windows PowerShell 5.1 was not tested.
- Develop CI on the merge commit `b5f54ab` passed. All three archived `SHA256SUMS` manifests verified from a clean checkout: 16, 36 and 36 files OK.

**After maintainer review (2026-10-12):** configuration approved, with the cap defined on game starts, cap means INCONCLUSIVE, Cute Chess authoritative for stopping, zero tolerance for unexplained time forfeits, crashes and protocol failures, and a bounded smoke test authorized after a passed preflight. No production SPRT.

- Replaced the analyzer's normal-approximation LLR with a port of the cutechess-cli 1.5.1 SPRT model (read from its source: Jeffreys +0.5 per class, BayesElo with an estimated draw Elo). It reproduces the archived Phase 21 value (2.9709 against 2.97 printed) and an independent worked example (-2.819), so the reconciliation tolerance is 0.01 again instead of 0.1. The analyzer is read-only, has no path to the stopping decision, and a test enforces that it imports nothing that can write or launch.
- Read the Cute Chess source for opening handling: `-repeat` is two plays with sides swapped, sequential EPD order rewinds only after the end of file, so 20,000 game starts use exactly the 10,000 openings. Cute Chess rewrites the PGN FEN move counters, so reconciliation compares the first four fields, and the analyzer now checks that the openings used are a gap-free prefix used at most twice.
- The runner's Read-Stage6Results calls the cap INCONCLUSIVE only when 20,000 games were scored. The cap is on game starts, so a run ending with 19,998 scored and 2 cancelled would have been INCOMPLETE. `Get-ReleaseSprtVerdict` fixes that, and the validation checks that neither the cap nor an interruption can become H0 or H1.
- Added `-SmokeNative`: 20 games on a separate 10-position list (the ranks after the production list, no shared position), the production command line otherwise, its own directory and confirmation phrase, refused unless an intact passed preflight exists for the same commit and scripts, and no path from it to production.
- A second independent review (Codex gpt-6-luna, xhigh) of the smoke and reconciliation work found no blocker and four items, all fixed. The smoke gate accepted an empty `SHA256SUMS`, which would let a truncated manifest hide tampered evidence; it now needs a non-empty manifest that lists every file, and a preflight started within 24 hours. The analyzer picks the opening list from the attempt's recorded kind and labels a smoke attempt as not a result. A third game on one opening no longer overwrites a scored game in the pair statistics. The review confirmed the model port against `sprt.cpp` at v1.5.1, including the NEW-side perspective and the draw-Elo scaling.

### [2026-10-19] Release SPRT smoke audited, runner hardened before production, nothing run in production

**Built:**

- An audit of the native smoke attempt `smoke-20261019T065359346Z-7b3787d4` (commit `c9816ae`): checksums, script, JAR, opening and argument identity, an independent reconciliation of PGN against log by opening pair and colour, and the lock and attempt-record behaviour. Verdict: infrastructure GO, conditional on the gaps below being closed.
- [`tools/release-sprt-audit.py`](../tools/release-sprt-audit.py), an independent audit of an archived smoke attempt against twelve explicit criteria. It writes `audit-<UTC>-<random>/smoke-audit.json` with its own `SHA256SUMS`, naming the evidence manifest and runner commit it approved, and never writes into the smoke directory.
- Runner changes in `tools/release-strength-sprt.ps1` and `tools/release-strength-sprt-common.ps1`: `-AssetDirectory` resolved once to a full path; the preflight gate now covers `-ExecuteNative` as well as `-SmokeNative` and binds the whole frozen configuration and archived inputs; a second gate for production that requires an approved audit; lock acquisition moved into `Enter-ReleaseSprtLock` and `Exit-ReleaseSprtLock`.
- 191 new validation checks (111 to 302), 54 new Python tests, and [`runner-qualification.md`](../docs/architecture/research/release-strength-sprt-v0.5.7-v0.6.0/runner-qualification.md), the revised qualification procedure.

**Decisions Made:**

- Do not trust `SMOKE_PASSED`. The runner that plays the games also writes that label, so production would be authorizing itself. The audit is a separate program that re-derives the verdict from raw files and keeps its own copy of the frozen identities, pinned to the PowerShell config by a test. The runner's summary is corroboration only.
- The audit record lives in its own `audit-*` directory, not inside the smoke attempt, because the existing smoke and preflight evidence must stay byte-for-byte unchanged. At production time the gate re-verifies the record's checksums, the audit program and analyzer hashes, the commit and script hashes, and the smoke attempt's manifest hash and every file in it, and does not accept the stored verdict alone. It repeats both gates right before launch, after the confirmation prompt, and anchors the launch digests in memory and in the child's command line so an edit to the attempt directory during the prompt cannot move them.
- Bind the audit program into `identity.json` by adding it to the hashed script list. As a consequence the existing preflight and smoke cannot authorize production: they recorded the old scripts, and the audit program did not exist. A fresh preflight, smoke and audit on the commit carrying this change are required.
- Keep the 24-hour rule as in section 17 of the preregistration (started within 24 hours) and also require completion within 24 hours. The preregistration, the frozen openings, the hypotheses and every experiment parameter are untouched.
- Skip a time limit on the smoke attempt. It is bound to the exact commit and scripts, and the 24-hour preflight covers host conditions. Add one only if the maintainer wants it.
- Test the machine-wide lock with real second processes and a private lock name, never the production name.
- Hold a second machine-wide lock (`Global\VexReleaseSprtMatch`) in the launched child for as long as it lives. The wrapper's own lock goes away when the wrapper is killed, but the child, and the match it runs, does not.
- Accept that hash-then-use cannot be made atomic in PowerShell without copying the scripts somewhere only the runner can write. The child re-hashes all scripts and both JARs right before it loads or runs them, which narrows the race to milliseconds. A writer racing the runner in `tools/` is outside the threat model, and the document says so.
- Left alone: `Assert-Stage6Jar` in `phase21-stage6.ps1`, which is where the relative-path failure surfaced. It is shared with the frozen Phase 21 runner, so the fix is at the entry point instead.

**Broke / Fixed:**

- A relative `-AssetDirectory` failed with `Could not find a part of the path 'C:\Users\release-sprt-assets\engine-uci-0.6.0.jar'` on 2026-10-12 and 2026-10-19. `Get-FileHash` resolves against the PowerShell location and `ZipFile.OpenRead` against the .NET process directory. Fixed by resolving once at the entry point. The preregistration's example commands (`..\release-sprt-assets`) work now.
- `-ExecuteNative` had no preflight check. Fixed.
- The validation suite's mutex test only searched the source text. Replaced by cross-process tests.
- A third review of the committed fixes found more: a killed wrapper freed the lock while its match child kept playing; the JARs and scripts could change between the last check and their use; the idle reading was reused after the prompt; the audit did not bind recorded paths to the audited directory; an end timestamp 100 ns before its start passed after truncation to microseconds; `true` and `false` satisfied numeric config checks in Python; file links passed the plain-file manifest check. Each is fixed or, for the race, narrowed and documented. A fourth review of the committed fixes found no HIGH issue and one MEDIUM: a refusal made by the launched child (for example the match lock taken between the wrapper's probe and the child's) was recorded as a started match. A fifth look at that fix found the exit-code approach incomplete: a child that failed to parse, or died at startup, never reached the guard and was still recorded as started. The evidence is now inverted. The child writes `match-launched.txt` as the last step before it invokes the runner, and the wrapper decides `MatchStarted` from that file alone (exact content, so a half-written file does not count), in its closing block, so an exception between `Start-Process` and the check cannot leave a match marked as started. The closing steps (attempt record, transcript close, `SHA256SUMS`) are each guarded on their own. "Started" means the runner was invoked, not that a game was played, and an absent marker counts for nothing when the child could not be stopped. The first `attempt-start.json` write stays unguarded because there is nowhere earlier to record its failure. Validation parses the child script with its placeholders filled and checks its shape with the syntax tree (setup, one guard that ends with the marker write, then the runner call), since the child is never executed there.
- The first version of the abandoned-lock test asserted that a lock left by a killed process is reported as recovered. It was not: on .NET 10.0.12 the next waiter gets the lock with no `AbandonedMutexException`. The test now asserts what matters (the next attempt acquires the lock) and checks the take-over path with a thread that exits holding the lock, where the runtime does signal it. A `LockRecoveredFromAbandoned` of false is therefore not proof of a clean predecessor.

**Measurements:**

- Smoke audit: 20 games started and finished once each, 10 color-reversed pairs, NEW 13-4-3 (7-2-1 as White, 6-2-2 as Black), 17 adjudications and 3 threefold repetitions, no fault, logged LLR 0.603 against 0.6034 recomputed. This is a plumbing check, not a result.
- The new audit program run read-only on that attempt: 11 of 12 criteria pass; `script-identity` fails because the old runner never recorded the audit program's hash. The smoke evidence verified against its checksums before and after.
- Mutation check of the new gates: disabling each of five checks (age limit, commit comparison, criterion result, audit manifest hash, script hash comparison) made the validation fail.
- Not tested: Windows PowerShell 5.1, and `-ExecuteNative` itself (the audit gate is covered by unit tests and a source-order check). No game, benchmark or SPRT was started.
- Final correction before merge: a child that could not be stopped and left no launch marker was recorded as `MatchStarted=true`, which overstated what the evidence showed. It is now `LaunchState=UNKNOWN_INTERRUPTED` with `MatchStarted` null. A valid marker is `STARTED`, a confirmed pre-launch failure is `NOT_STARTED`. All gates and the audit already required a strict true or false, so they reject the unknown state. Validation went from 302 to 312 checks and the audit tests gained one case; disabling the helper branch, the null assignment or the NOT_STARTED verdict condition each failed the suite.

### [2026-11-21] Checkmate precedence at the fifty-move threshold and terminal root results (#261)

**Built:**

- `Searcher.alphaBeta`: on the fifty-move branch (`halfmoveClock >= 100`) the node now returns `-MATE_SCORE + ply` when the side to move is in check and has no legal move, and the contempt-adjusted draw otherwise. Before, `isRepetitionDraw() || isFiftyMoveRuleDraw()` returned the draw score first, so `Qg7#` played at clock 99 scored as a draw.
- `Searcher.searchRoot` records `rootTerminal` (no legal moves at all, before the `searchmoves` and MultiPV filters). Iterative deepening treats a terminal root as a completed result: score is the terminal score (`-MATE_SCORE` or 0), `bestMove` stays null, `aborted` is false, and the depth loop stops after that iteration instead of deepening a position with no moves.
- New `TerminalAdjudicationTest` (11 tests) and two API tests asserting the terminal score of a checkmated root (`mate 0`) and a stalemated root (`cp 0`).

**Decisions Made:**

- The legal-move check sits behind `isActiveColorInCheck()` inside the clock >= 100 branch, so ordinary nodes pay nothing new and even a clock >= 100 node only runs move generation when it is in check. `Board.isCheckmate()` allocates a generator, which is acceptable on a path this rare.
- Repetition draws were left on the old path. The issue is about the fifty-move threshold and the code did not change repetition adjudication.
- A terminal root is distinguished with a field set in `searchRoot` rather than by inspecting the result, because an empty move list after the `searchmoves` filter (nothing matched) or the MultiPV exclusion list (no more lines) is not a terminal position and keeps its old behavior. A test pins the `searchmoves` case.
- A cancelled search still reports `aborted == true`, including on a terminal root when the stop request is already set, because the soft-stop check runs before the root is searched.
- `depthReached` for a terminal root is the depth of the iteration that produced it (1 in practice). The API reports a checkmated root as `mate 0` where it used to report `cp 0`.

**Broke / Fixed:**

- First version of the "genuine draw with a legal evasion" test used a position where the white king guarded g8, so `Qg8` was mate and the assertion failed. That was a test fixture error, not a search bug. The replacement uses a king-far position where `Kxg8` is legal, and a paired test shows the guarded and unguarded cases differ only in the mate score.
- With the `Searcher.java` change reverted, 5 of the 11 new tests fail (mate at clock 99 scores 0, and checkmated and stalemated roots report `aborted == true`), so they do reproduce the reported behavior.

**Measurements:**

- engine-core 443 tests, engine-uci 47, chess-engine-api 29, all with 0 failures. The node-count regression test passes unchanged.
- No bench and no NPS run: WSL NPS is not a valid regression signal here and the change only adds a branch that executes at halfmove clock >= 100. The native-Windows NPS gate has not been re-run for this change.
- Not covered: how often this occurs in play, and any Elo effect (the issue marks both as unmeasured). Interaction with the transposition table across halfmove clocks is #259 and was not touched.

### [2026-11-21] Pin-aware SEE, h1 shift fix and non-active captureGainFor (#262)

**Built:**

- `StaticExchangeEvaluator.bitboardLva` uses logical `>>>` for the Black pawn attacker shifts. With `>>` on bit 63 (h1) the sign bit was extended, which invented a pawn on h2 attacking h1 and made a blocked pawn "capture" a rook for 1,300 cp.
- `bestReplyGain` now takes the least-valuable attacker that is legal: after making the recapture it checks `board.isColorKingInCheck(side)` and, if the capturer left its own king attacked, undoes the move, adds that attacker to an exclusion mask and tries the next one. This covers an absolutely pinned piece moving off its ray and a king stepping onto a defended square. The recursion passes the opposite of the side explicitly instead of reading `board.getActiveColor()`.
- `captureGainFor` for a side that is not to move passes the turn with a null move first, so the second attacker no longer recaptures its own piece (it returned 220 instead of 320 on `4k3/8/8/3n4/4P3/8/8/3QK3 b`).
- Tests: 10 new in `StaticExchangeEvaluatorTest` and a new `StaticExchangeEvaluatorOracleTest` that compares SEE with a slow oracle taking every recapture from full legal move generation, on 16 fixed positions and on seeded 60-ply random playouts from each (960 positions, 2,540 captures).

**Decisions Made:**

- Legality is checked with one `isColorKingInCheck` per exchange step, not full legal move generation. SEE already does make and unmake per step, so the extra cost is a few magic-bitboard lookups. A separate pin-mask pass was not needed to be correct and would have been a second code path to keep in sync.
- An illegal attacker is skipped, not treated as "no recapture". Skipping lets the next legal attacker recapture, which the `Rf8xf5` test pins (still -230 when a rook also defends f5).
- The oracle test is the main evidence. It is independent of SEE's bitboard shortcuts, so a mistake in the pin logic cannot hide behind the same mistake in the test.
- Only the two golden node counts that actually moved were updated (see below). The rest of the file was not touched.

**Broke / Fixed:**

- `NodeCountRegressionTest` failed after the change. Investigated before touching it: at depth 8, best move, score and PV are identical for all five positions, and only two node counts changed (startpos 14776 to 14312, position 3 32267 to 32082). With the old SEE reinstated the oracle test fails, including on a naturally reached playout position (`1rb1k2r/ppp1bppp/q2p3n/n3p3/3NP1P1/7P/PPPPNPR1/R1BQ1K2 b k - 3 15`, 220 vs 320), so the new numbers come from correcting wrong SEE values, not from a regression. The two golden values were then updated with a comment.
- A first oracle fixture had an illegal position (black to move with the white king capturable); fixed.

**Measurements:**

- Old versus new SEE on the playout corpus: 24 of 2,540 capture values differ (0.9%), and the `SEE < 0` quiescence pruning decision flips on 7 (0.3%).
- Native Windows (Zulu 21) `--bench-raw 13`, two runs each, develop `448c98f` versus this change: total nodes 24,909,290 to 23,222,204 (-6.8%), node count changed in 24 of 31 positions (largest -34.8% and +33.0%). Time 75.5 s and 75.2 s to 72.2 s and 71.6 s. NPS 329,784 and 331,126 to 321,499 and 324,386 (about -2.5%), still above the 301,116 floor.
- Depth 13 over the same 31 positions through UCI: best move differs in 6, score in 15, PV in 16. The search tree changed materially, as expected when SEE ordering and pruning decisions change. This is not a strength measurement.
- Not run: SPRT, the WAC/tactical corpus pruning count the issue asks for, and any Elo claim. The change is a correctness fix to SEE and its effect on strength is unmeasured.

### [2026-11-23] #262 qualification: SEE decision diff, bench, SPRT status

**Built:**

- `tools/SeeDecisionDiff.java`, a read-only harness that compares the pre-fix SEE (52fee7c, extracted and renamed `OldStaticExchangeEvaluator`) with the candidate SEE (b26569f) on every legal capture in a full-width tree below each corpus position. No SEE or search code was touched.

**Decisions Made:**

- Identities: baseline is exactly 52fee7c (the parent of b26569f, pre-SEE develop) and candidate is exactly PR #283 head b26569f. Both jars were built from `git archive` snapshots of those commits in scratch space (no worktree, no local modifications). The issue text names 61a3712 as its baseline, but 52fee7c is the exact pre-fix commit the maintainer specified, so that was used.
- The decision tested is the `SEE < 0` test that quiescence uses to prune and that main-search ordering uses to label a capture losing. Counts are visits, not unique positions, because the tree walk revisits transpositions.
- `wac.epd` holds only 20 mostly trivial endgame positions, so draw_failures.epd (3) and the 31 BENCH positions were added to get a meaningful population.

**Measurements:**

- WAC alone, 3 plies: 10,702 capture decisions, 0 differ.
- WAC + draw_failures + BENCH (54 positions), 3 plies: 3,713,211 capture decisions, SEE value differs on 19,169 (0.52%). `SEE < 0` flips: 3,017 pruned to searched (old < 0, new >= 0) and 222 searched to pruned (old >= 0, new < 0), 3,239 in total (0.087%). Per ply: ply 0 74 captures, 2 differ; ply 1 2,077, 7; ply 2 100,863, 1,727; ply 3 3,610,197, 17,433.
- WSL `--bench` (default depth), three alternating runs, 52fee7c versus b26569f, informational only because WSL NPS is not a valid regression signal: NPS 342,720 / 357,378 / 357,358 versus 348,101 / 363,454 / 354,764. Cut evals 28,092,760 versus 26,202,800 (-6.7%), consistent with the native node drop already recorded above. No NPS regression visible.
- Native SPRT: not run. See the issue comment for the blocker and the command.

### [2026-11-23] #262 native non-regression SPRT: H1 accepted, adjudication audit

**Built:**

- Ran the preregistered `[-5,0]` non-regression SPRT natively (alpha = beta = 0.05, cap 30,000, 5+0.05, concurrency 6, 1 thread, `noob_3moves.epd`, `-repeat`). Baseline 52fee7c, candidate 25a3889 (SEE commit b26569f plus tooling-only commits). Protocol: `docs/architecture/research/see-262-nonregression-protocol.md`.
- `tools/adjudication_audit.py` and the 200-game sample `docs/architecture/research/see-262-adjudication-audit-sample.csv`.
- `tools/sprt.ps1` resolves `-LogPath` and `-PgnPath` against the PowerShell location (4557869).

**Decisions Made:**

- Non-regression was the right question. The change is a correctness fix expected near 0 Elo, so an improvement test ([0,50] or [0,10]) could not answer it.
- The adjudication audit was run after the match stopped, on the final PGN and log, and was not used to reinterpret the SPRT result.

**Broke / Fixed:**

- The first set of commands wrote the log and PGN outside the run folder because of relative paths. Copied in with hash verification and fixed in `sprt.ps1`.
- I reported 1,535 finished games when the categories summed to 1,935. A transcription error, corrected in the case study.

**Measurements:**

- LLR 2.96 against bounds of +/-2.94, H1 accepted. 5,713 scored games: 1,969 wins, 1,872 losses, 1,872 draws, score 0.5085, +5.9 Elo, 95% interval -1.5 to +13.3. 5 cancelled games unscored. No crashes, forfeits, illegal moves or timeouts.
- Stockfish 18 depth 24 on 200 seeded resign-adjudicated final positions: 194 supported, 0 uncertain, 6 potentially incorrect (3.0%, 95% interval 1.4% to 6.4%, unweighted stratified sample). All six are drawn or nearly drawn endings that the shared classical evaluation scored as 4 to 7 pawns. Details in `docs/engineering/investigations/2026-11-23-see-262-sprt-adjudication-audit.md`.

### [2026-11-23] UCI search lifecycle: one bestmove per go, held infinite and ponder, ponderhit race (#268)

**Built:**

- A per-go `GoSession` (hold flag, ponder flag, `TimeManager`, `ponderHit` and `bestmoveEmitted` flags) built on the command thread and published before the search worker starts.
- `stop` only requests a stop. It never prints a bestmove, so an idle stop and a stop after a finished search print nothing. The worker is the only writer of its own bestmove.
- `go infinite` and `go ponder` are held: the worker finishes (or hits the depth cap, or finds a mate, or has a forced move) and then waits on a monitor until `stop` or, for ponder, `ponderhit`. Helpers are already aborted and drain as before. Untimed held searches run to `MAX_SEARCH_DEPTH` instead of 4.
- The `TimeManager` for movetime and clock searches is now built in `handleGo` (`buildTimeManager`), so `ponderhit` finds it even when it arrives before the worker has started.
- If a search is aborted before depth 1 completes in a non-terminal position, the worker emits the first legal root move instead of `bestmove 0000`.
- `position`, `setoption BookFile` and `setoption BookVariance` join the worker before changing state. `BookVariance` closes the book it replaces. `quit` and end of input stop and drain the worker.
- Tests: `UciSearchLifecycleTest` (14 cases, subprocess based, waits on output lines and an isready/readyok barrier, no sleeps).

**Decisions Made:**

- A held search with a forced move or a mate still waits for `stop`. Terminal positions (no legal move) keep their immediate `bestmove 0000`, because there is nothing to analyse and the old behaviour was already one null move.
- `go nodes`, `go mate` and a bare `go` keep their depth 4 default. They are not supported modes, and making them run to the depth cap would hang a GUI that expected them to end. Only held modes changed.
- A replaced search (a second `go` without a `stop`) still ends the first with a stop, so it prints one bestmove and the second `go` prints its own.
- No fixed sleeps: `stop` and `ponderhit` are written in the same flush as `go` so the engine reads them back to back.

**Broke / Fixed:**

- Reproduced on `develop` (a5eff3c) before the fix, all red: `go infinite` never passed depth 4; an idle `stop` printed `bestmove 0000` and a `stop` after a finished search printed a second bestmove; `go infinite` immediately followed by `stop` printed `bestmove 0000` on the first iteration; `ponderhit` right after `go ponder` was dropped with 1 and 2 threads; a forced move and a mate-in-1 under `go infinite` printed before `stop`; a `stop` in a stalemate printed a duplicate null bestmove.
- The `UciHarness` in `UciApplicationIntegrationTest` is now package visible with a few accessors, and its `close()` ignores a closed pipe after the engine has exited.

**Measurements:**

- Full reactor: engine-core 455, engine-uci 61, chess-engine-api 29, engine-tuner 131, all passing. The lifecycle class was run five more times with no failure.
- No SPRT, no NPS or search change: the search code is untouched.

### [2026-11-23] #268 lifecycle-safety verification of PR #284

**Built:**

- `drainSearch()`: stops the running search and waits for its worker, and through it the helpers, for as long as that takes. `go`, `position`, `ucinewgame`, `setoption Hash`, `BookFile` and `BookVariance` use it. Only `quit` and end of input keep the bounded 2 s `stopAndJoinSearch()`.
- A test seam, the system property `vex.test.drainDelayMs`, which keeps a finished worker alive that many milliseconds to simulate a worker that is slow to stop. Zero and unused outside the tests. A `search=N event=hold` line in the SMP diagnostics marks the moment a held worker starts waiting.
- Three new tests (slow drain, quit with a slow worker, ponderhit and stop after a forced-move answer) and two stronger ones (the forced-move and mate holds now wait for `event=hold`).

**Decisions Made:**

- **Drain timeout was a real defect.** After 2 s the old code returned from `go` with no output at all (no bestmove for that `go`), and skipped `position`, `ucinewgame`, `Hash`, `BookFile` and `BookVariance` with only an `info string`. When the slow worker finally ended, the next command ran against the old board, TT or book. Reproduced with the seam: `go infinite`, then `position startpos moves e2e4` and `go depth 1` sent together answered `e2e4`, a move from the old position. The commands now wait. A worker that was told to stop and never ends makes the engine unresponsive instead of silently wrong, and `info string search is slow to stop` is printed once.
- **Quit and end of input need no change.** The search worker and all `smp-helper` threads are daemon threads, so none can outlive the JVM. The 2 s wait is a courtesy to let the worker print its bestmove. Verified with a worker held for 30 s: the engine still exits promptly. The guarantee is "at most 2 s, then the process exits", not "every helper drained", and the comment says so.
- **Stale `GoSession` is inert, no change.** Forced-move and terminal answers leave the previous session referenced. That cannot matter because a worker only ever uses the session object it was handed, `handleGo` drains the previous worker before it replaces `session`, and a `ponderhit` or `stop` that reaches a finished session changes only state nobody reads (an unused `TimeManager`, and the `stopRequested` flag that every `go` resets). A test sends `ponderhit`, `stop`, `ponderhit` after a forced-move answer and checks nothing is printed and the next search is unaffected.
- **Test synchronization.** `readyok` is printed by the command thread, so it orders stdout lines printed by the command thread and, after a join, the worker's bestmove (the join is a happens-before). It does not order stderr diagnostics, and it says nothing about a worker that has not yet reached its emission point. The rapid go/stop test read `drained` diagnostics straight after readyok, which was racy, and now waits for them. The forced-move and mate hold tests asserted "no bestmove" after readyok, which a worker that was merely slow would also satisfy; they now wait for `event=hold` first.

**Broke / Fixed:**

- First version of the strengthened forced-move test asserted the exact line `bestmove a1a2`, but the search now also returns a ponder move. Test bug, not an engine bug.

**Measurements:**

- engine-uci 64 tests (17 in `UciSearchLifecycleTest`), engine-core 455, chess-engine-api 29, engine-tuner 131, all passing. The lifecycle class passed three runs in a row after the fix. No search, evaluation or time-allocation code changed.

### [2026-11-23] Float32-versus-quantized parity validation (#265)

**Built:**

- `trainer/trainer/quantization/inference.py`: `eval_quantized_canonical()` and `eval_quantized_fens()` mirror `NnueEvaluator.evaluate()` exactly (wrapping int16 accumulator, clamp to `[0, qa]`, int64 sum, `long` multiply and divide truncating toward zero, `int` cast). `eval_float_canonical()` is the float32 reference built from `CanonicalNetwork`, `eval_dequantized_float()` the float64 counterpart of `NnueOracle`, and `accumulator_bound_violations()` mirrors the #264 loader check, so a network Java would refuse to load is refused here.
- `trainer/trainer/validation/quantization_parity.py`: `compute_quantization_parity()` (MAE, RMSE, max |delta|, signed mean, float-vs-quantized Pearson, label-correlation change, a split into weight-rounding and integer-arithmetic error, the validation FEN hash), `ParityThresholds` (report only unless set), `threshold_violations()` and `quantize_validate_export()`, which raises `QuantizationParityError` before anything is written and otherwise exports with the report in the manifest.
- `trainer/trainer/export/nnue_reader.py` (inverse of the exporter, so committed fixtures can be evaluated), the optional `quantization_parity` manifest field and its schema check, and the parity report wired into `scripts/train_candidate_net.py` for the held-out positions.
- Java: a second committed fixture `parity-w32-fixture.nnue`, golden CSVs `bench/nnue-corpus/fixture-{d6,w32}-golden-evals.csv`, `NnueFixtureGoldenEvalTest`, and an opt-in `NnueFixtureGoldenGenerator`.

**Decisions Made:**

- The existing numerical contract is untouched: QA/QB-native units, bare `np.round` in `quantize()`, no export-time scaling, no architecture change. This work only measures it.
- Java stays the arithmetic authority. The Java side pins its own output for each fixture in a committed CSV; Python is compared with the same CSV, so the two languages are tied together by one file and a drift on either side fails its own CI job. No cross-language process is started in CI (that is #269's job).
- A second, wider fixture was added because the frozen D-6 fixture has `hidden_width = 2`, which cannot exercise the `us`/`them` output-weight stride or the clamp at both ends across many units. The wide one has 7.3% of accumulators above `qa`, 37.6% below zero (over all 393 FENs), and scores from -2831 to +1913 cp (173 negative), so truncation toward zero is covered.
- No release threshold was chosen. `ParityThresholds` defaults to report-only. The tests use explicit round numbers (MAE 20 cp, max 60 cp, correlation 0.99) that are local to the tests and separate two synthetic networks by a wide margin; they are not proposed gates. The figures suggested in the issue (MAE 5 cp, max 25 cp, correlation delta 0.005) stay provisional until a real checkpoint exists.
- `accumulator_bound_violations()` follows the Java rule (the 32 largest positive and 32 most negative weights per neuron), which is never stricter than the quantizer's `bias + 32 * max|w|`, so anything `quantize()` accepts is also loadable here.

**Broke / Fixed:**

- A hand-computed test value was wrong (a neuron's bias was 20, so it ended at 5 and not -5). The code was right; the test input was fixed.
- The first design of the "well-scaled" synthetic network gave 15 cp MAE, because output weights with a standard deviation of 40 are tiny next to their rounding step at that score scale. Realistic centipawn scale needs output weights nearer 12 per unit with FT weights across the int16 range, which gives MAE about 6 cp; the poorly scaled case (FT weights O(1), output weights 150) gives MAE about 70 cp and max about 264 cp on the same 393 FENs. Both are synthetic.

**Measurements (synthetic networks only, 393-FEN corpus, deterministic seeds):**

- Python versus Java on the committed fixtures: 0 cp difference on all 393 FENs for both fixtures.
- Well-scaled net (FT sigma 16, output sigma 12): MAE 5.8 cp, RMSE 7.0 cp, max 19.1 cp, float-vs-quantized correlation 0.9994, score spread about 190 cp.
- Poorly scaled net (FT sigma 1, output sigma 150): MAE 70.3 cp, RMSE 89.7 cp, max 263.7 cp, correlation 0.9507, score spread about 237 cp.
- Integer arithmetic alone (rounded weights, float64 versus integer score) never differs by 1 cp or more.
- Three deliberate arithmetic mutations (floor division, no clamp at `qa`, swapped `us`/`them` weights) each fail the parity tests.
- Trainer suite 496 passed. Reactor: engine-core 457, engine-uci 64, chess-engine-api 29, engine-tuner 131.

**Not done / cannot be qualified yet:**

- No real checkpoint is available here (`P5-AUXHEAD-001`, E-3, E-15 and E-16 are not in the checkout), so the actual float32-versus-quantized error of any trained network is still unmeasured, and the scale-contract question (Option A or B in the issue) is not decided. Run `compute_quantization_parity()` on each surviving checkpoint first.
- The numeric acceptance thresholds are uncalibrated. #263 (dataset audit and checkpoint qualification) and #269 (a CI job that runs Java and Python together) are separate and untouched.

### [2026-11-23] Transposition-table score validity: fifty-move clock and evaluator identity (#259)

**Built:**

- `Searcher.fiftyMoveSensitive(clock, depth, maxExtensions)`: true when `clock + depth + extension budget >= 100`. The fifty-move rule is checked on entry to every `alphaBeta` node, so that is the deepest clock a subtree can see (captures and pawn moves only lower it). A sensitive node does not store its score. A probed entry whose own depth makes it sensitive at the current clock is not used for a cutoff or the singular-extension test, but its best move still orders the moves.
- `UciApplication` resolves the effective evaluator at the start of every search (Classical, or NNUE plus the network UUID, after any fallback), before any helper is submitted and after `handleGo` has joined the previous worker, and clears the shared table when it differs from the one the table last served. It prints `info string transposition table cleared: evaluator changed`.
- Tests: `TranspositionTableScoreValidityTest` (6, both clock orders, the move hint, ordinary-clock reuse, the predicate) and `UciEvaluatorSwitchTest` (3, including a two-thread run that checks every search drained before the next clear).

**Decisions Made:**

- The Zobrist key stays as it is. Mixing the clock into it would make quiet transpositions miss and cost far more than the guard does.
- Guard on the subtree horizon, not a fixed clock threshold. `clock >= 90` alone would miss `clock 85, depth 15`; `clock + depth + budget >= 100` is exact for what the subtree can reach. The budget is `maxExtensions` (every extension type draws from it), the conservative upper bound.
- Not storing a sensitive score is simpler and safer than storing it with a flag. The cost is that the move hint is lost for those nodes, which only exist at clocks of roughly 70 and above.
- The evaluator check is done at search start, not in `setoption`, because whether `EvalType NNUE` actually resolves to NNUE (a file is set and loads) is only known at `go` time. That also means a fallback to the same Classical evaluator, or setting the same option twice, does not clear anything.
- Repetition draws against the game history are not guarded. That is the usual graph-history interaction; the table lives for one game and is cleared on `ucinewgame`, and adding it here would restrict cutoffs well beyond the defect.

**Broke / Fixed:**

- Reproduced before the change: a clock-0 score came back at clock 98 as +605 cp (cold 0), a clock-98 draw verdict reached a later clock-0 search, and after switching to NNUE a depth-5 startpos search reported +36 cp (the classical value) where a cold NNUE search gives -503 cp.
- Self-review found two misplaced edits of mine (a field splitting an existing comment block, and the clear block sitting between a comment and the line it described). Moved, no behaviour change.

**Measurements (WSL, so node counts are valid and NPS is only indicative):**

- Baseline `develop` at `8d33edc` versus this branch at `157e88c`, both built from `git archive` snapshots, `--bench` depth 13, 31 positions: total nodes 23,222,204 for both, every position's node count identical, repeatable run to run. The bench FENs all have halfmove clocks of 6 or less, so the guard never fires there, and the table is not cleared in a bench run.
- NPS (two alternating runs each): baseline 327,456 and 338,782, candidate 337,169 and 343,594. Within run-to-run noise, no regression.
- Full reactor: engine-core 463, engine-uci 67, chess-engine-api 29, engine-tuner 131, all passing, no golden count changed.

**Not done:**

- No SPRT and no native NPS gate. Search behaviour changes only at halfmove clocks of about 70 and above and across an evaluator switch, neither of which the bench or ordinary games reach, so I do not expect a measurable strength change and have not tried to measure one. If one is wanted, it would need a position set built around long quiet endgames, since random self-play seldom reaches those clocks.
- TT hit rate and cutoff counters were not separately instrumented; identical node counts on the bench imply identical table behaviour there.

**Follow-up review (2026-11-23):**

- The probe-time guard had used the current iteration's extension allowance. Entries outlive the iteration, the go command and the helper that stored them, so a deep entry was trusted by a shallower iteration (clock 85, entry depth 13, returned -777 against a true result above +300). The guard now always adds `MAX_CHECK_EXTENSIONS`, so its answer does not depend on who asks.
- The evaluator identity was the UUID inside the network file, so a different network carrying the same UUID kept the old entries (1423 nodes against 844 cold). It is now the EvalFile path plus the UUID. A file rewritten in place at the same path between searches is not detected; that is accepted.
- Quiescence checked: it never reads the fifty-move rule or the table, so it adds nothing to the horizon.
- Cost of the guard: it fires when `clock + depth + 16 >= 100`, so at depth 14 from clock 70, and for every node at clock 84 and above. Those nodes lose score reuse but keep the move hint. This is a performance cost only; a tighter per-path extension count would recover some of it and is not worth doing without a measurement.
- Bench at `1f1b09e`: 23,222,204 nodes, identical to `8d33edc` at every position.

### [2026-11-23] Transposition-table generation age is unsigned (#266, part 1)

**Built:** `TranspositionTable.ageOf(current, entry) = (current - entry) & 0xFF`, used by `store()` and `hashfull()` in place of the signed `(byte)` comparison, which read every entry 128..255 generations old as brand new (protected from replacement, counted live). `TranspositionTableAgeTest` covers distances 0, 1, 3, 4, 5, 127, 128, 129, 200, 255, 256, 260 for replacement and 0, 1, 3, 4, 127, 128, 200, 255, 256 for `hashfull()`.

**Decisions Made:** only the age arithmetic. The 1-slot collision policy and the `Integer` boxing in `applyTtBound` stay for separate measured work, as the issue itself says. A distance of 256 reads as age 0 (8-bit generation); the test pins it as inherent, not a bug.

**Measurements:** red before the fix on exactly the 128..255 cases (7 failures), green after. Bench numbers are in the PR.

### [2026-11-24] CI and validation gates that did not gate (#273)

**Built:** CI now runs `mvn -pl engine-core -Psearch-regression test`, which is the only way to run `SearchRegressionSuite` (WAC pass rate, depth stability, WAC material blunders). The old step passed `-Dgroups=regression`, which selects `SearchRegressionTest` again and leaves the `search-regression` tag excluded by the default surefire config, so that suite had never run in CI. The profile's `combine.self="override"` dropped `--add-modules jdk.incubator.vector`; the argLine is repeated there. CI runs all of `tools/tests` instead of one file. `nightly-sprt.yml` gets a mandatory, hash-checked opening book and the vector module flag on both engine wrappers.

**Decisions Made:** the separate `nnue-mode`, `nnue-golden` and `regression` steps were deleted only after the last green develop run (`0b66656`) showed the default `mvn clean test` already executing `SearchRegressionTest` (37 tests), `NnueModeSearchRegressionTest` (34), `NnueGoldenEvalTest` (1), `NnueFixtureGoldenEvalTest` (2) and `EvalMirrorSymmetryPropertyTest` (1) with the same counts in the second invocation. Unresolved WAC SANs, and malformed WAC rows (missing `bm`, too few fields), now fail the test with a line number instead of shrinking the denominator. The draw-failure gate asserts a positive score and a repetition-free PV, because with contempt active a repetition draw scores `-50` for the winning side, so the old `!= 0` check passed on the draw it was written to catch. A KBvK negative control keeps the score check honest and a cycling-PV control keeps the repetition check honest; the PV check is supporting evidence only, since a transposition-table cutoff can truncate the returned line (found in review). The singular-extension test used `Move.of(12, 28, 0)` (e7e5, not a legal move here) with `maxExtensions = 0`, so the extension could never fire and the node-count difference came from the probe alone. It now uses the generator's e2e4 and asserts a new `singularExtensionsApplied` counter (1 with budget, 0 without, 0 without a TT move). The hanging-penalty test passed on 330 cp of extra material; the replacements evaluate one board with the penalty at its value and at 0.

**Broke / Fixed:** nothing in the engine. Pinned, not changed: `Evaluator.hangingPenalty` treats a king-defended rook attacked by a bishop as safe. Changing that is a strength change and needs an SPRT.

**Measurements:** red-first, each reverted afterwards. Disabling the singular extension condition fails `verifiedSingularMoveStillGetsTheExistingExtension` (expected 1, was 0). Making `hangingPenalty` return 0 fails both charge tests (expected -40 and 40, was 0). The original `ci.yml` fails the new workflow contract test. The three seeds in `draw_failures.epd` score above 0 with repetition-free PVs at depth 12. Remaining skips on develop CI are intentional: `TacticalSuiteTest` (needs `-Dtactical.enabled`), `DatasetLoadingTest` (needs `TUNER_DATASET`), property-gated benchmarks that later steps enable, the Syzygy test that needs network, and one KBNK test.

**Deferred (active SPRT or frozen protocol):** `sprt.ps1` auto-detect fallback to startpos and the dead `-MinGames`; `release-strength-sprt.ps1` tail drain; `release-sprt-analyze.py` zero-game division and population variance; benchmark timing hygiene in `BenchRunner`/`NpsBenchmarkTest`/`p16-2-native-baseline.ps1` (needs old-versus-new bench numbers on a quiet machine).

### [2026-11-24] NNUE CI gaps: width-256 SIMD coverage, dropped vector flag, trainer triggers (#269)

**Built:** `NnueIncrementalVsRebuildFuzzTest` now runs at widths 8, 256 and 264. Width 8 never reaches a SIMD iteration (`loopBound(8) == 0` for 16 or more short lanes); 256 runs whole vector iterations and 264 leaves a scalar tail after them on 16- and 32-lane hardware. The test also compares the incremental accumulators against a plain scalar rebuild written in the test. The existing rebuild oracle goes through the evaluator's own vector-or-scalar dispatch, so a vector bug appears on both sides and cancels. New `NnueColorFlipSymmetryTest` checks `eval(P) == eval(colorFlip(P))` over the 393-position golden corpus at widths 8 and 256. `engine-uci/pom.xml` passes `--add-modules jdk.incubator.vector` to surefire (it did not, so every NNUE test in that module ran the scalar fallback), with a guard test. The UCI integration harness runs the engine in a child JVM that surefire's argLine does not reach, so the harness passes the flag itself (found in review). `tools/nnue-gauntlet.ps1` passes the flag to both engines. `OracleArchitecturalBoundaryTest` converts paths with either separator and has a test for it. `trainer-ci.yml` also triggers on `fixtures/vspr/**`, `engine-core/src/test/resources/nnue/**` and `bench/nnue-corpus/**`, which the trainer tests read. The `search-regression` profile argLine and the `nightly-sprt.yml` flag shipped with #273's change; this change adds `nnue.requireVector` to that profile too, since it replaces the whole surefire configuration (found in review).

**Decisions Made:** the engine keeps degrading to the scalar path when the module is absent. Both Maven modules set `nnue.requireVector=true` in surefire, and only when it is set do the wide fuzz cases and the uci guard fail on a missing module, so an IDE run without the flag still works. Already fixed on develop and left alone: the Stage 1 `zstandard` skip (`--extra acquire` plus `REQUIRE_ACQUIRE_TESTS=1`) and Python-versus-Java quantized parity (`trainer/tests/quantization/test_java_parity.py`). The real-Stockfish trainer tests stay skipped when no binary is installed, which is a documented earlier decision.

**Measurements:** red-first, each reverted afterwards. Doubling the vector add in the loop body fails the 256 and 264 cases at the scalar-only oracle and leaves width 8 green; the shared-dispatch comparison next to it still passed, which is why the independent oracle was needed. Always reading the white accumulator as the side to move's makes the colour-flip test fail at both widths. Deleting the engine-uci argLine fails the guard test. On the 32-lane host used here, 256 is eight vector iterations and 264 adds an 8-lane tail.

**Deferred:** `NnueModeSearchRegressionTest` and the UCI integration tests at wide widths need new golden moves and a larger network fixture. The cross-language Python-versus-Java evaluation check on `d6-export-fixture.nnue` belongs with #265, which is still open.

### [2026-11-24] Scale-utilization diagnostics and a checkpoint audit entry point (#265, part 2)

**Built:** `trainer/trainer/validation/scale_utilization.py` measures how a float32 network uses the QA and int16 range on a FEN set: the share of accumulators below zero, in range and at or above `qa`; mean and RMS of the clamped activations; the fraction of neurons that are never positive; FT and output weight spread and int16 headroom; the fraction of FT weights that round to zero; the RMS rounding error over the RMS FT weight; and `cp_per_accumulator_unit_*`, the centipawns one accumulator unit moves on an unclamped neuron (`|w_out| * output_scale / (qa * qb)`), a per-accumulator sensitivity, not a bound on total score error. `quantize_validate_export` records it in the manifest next to the parity report (optional `scale_utilization` field, schema-checked, sampled on the first 5000 FENs by default). `trainer/scripts/audit_checkpoint_quantization.py` loads one `.pt` checkpoint with the existing `checkpoint_to_canonical`, runs `compute_quantization_parity` and `compute_scale_utilization`, and prints a JSON record with the checkpoint's SHA-256 and file name, the trainer commit and seed recorded inside it, and the FEN source names. No absolute path is written. It is report-only unless a `--max-*` or `--min-pearson` bound is given, in which case a violation exits with status 1.

**Decisions Made:** nothing else was added because the rest of #265 already exists from part 1 (bit-exact Python inference, the parity report and gate, the manifest field, the committed fixtures and goldens). No threshold was chosen: with no real checkpoint there is nothing to calibrate against, and the issue's figures (MAE 5 cp, max 25 cp, correlation delta 0.005) stay provisional. The default audit corpus is the committed 393-position `bench/nnue-corpus`, which is not a checkpoint's held-out distribution, so the record says its scope is that FEN set and `--fens` takes a real held-out file. The scale contract (Option A or B in the issue) is not decided and ADR-002 is unchanged, since no unit convention changed.

**Measurements (synthetic networks only, 393-FEN corpus, deterministic seeds; not evidence about any trained network):**

| | well scaled (FT sigma 16, out sigma 12) | poorly scaled (FT sigma 1, out sigma 150) |
|---|---|---|
| accumulators at or above qa | 9.3% | 0.0% |
| activation mean / RMS | 42.8 / 62.2 | 28.8 / 37.6 |
| dead neurons | 0.0% | 15.6% |
| FT weight RMS | 15.9 | 0.99 |
| FT weights rounding to 0 | 2.6% | 38.3% |
| rounding error / FT weight RMS | 0.018 | 0.292 |
| output weight L2 | 105 | 1318 |
| cp per accumulator unit (RMS / max) | 0.65 / 1.53 | 8.1 / 19.1 |

The poorly scaled row is the regime the issue describes for a default-initialised `EmbeddingBag`, and the diagnostics separate it from the well scaled one on every axis the issue names. The activation mean and RMS in the issue (1.47 to 2.35 and 4.61 for P5-AUXHEAD-001) are far below either synthetic row, so no synthetic network here reproduces that checkpoint; only the real file can.

Tests: a hand-computed case pins every arithmetic definition exactly (fractions, mean, RMS, dead fraction, norms, cp per unit); the two regimes are separated; the manifest round trip validates and rejects a non-number; the sample cap applies; the audit entry point writes the record, records no absolute path, exits 0 in report-only mode and 1 once a bound is exceeded, and does nothing on import. The audit tests use a freshly initialised `NnueNet` saved to a temp directory.

**Not done / cannot be qualified yet:** real-checkpoint evidence. No `.pt` checkpoint is in this checkout, so the audit has been exercised only on synthetic checkpoints. The first run on a surviving checkpoint (P5-AUXHEAD-001, E-3, E-15 or E-16) should be `python -m scripts.audit_checkpoint_quantization CHECKPOINT.pt --fens HELD_OUT.epd --output audit.json`, and its numbers, not these, decide whether a scale redesign is needed and what thresholds to set. Issue #265 stays open for that.

### [2026-11-24] Linux launcher for the cutechess SPRT (WSL qualification groundwork)

**Built:** `tools/sprt_linux.py`, the small separate launcher proposed in the 2026-11-23 addendum to the SPRT throughput investigation, because `tools/sprt.ps1` builds `bin\java.exe`, documents `cutechess-cli.exe` and cannot run under WSL as it stands. Stdlib-only Python. It builds the same cutechess argument array token for token (engine groups with `--add-modules jdk.incubator.vector`, `-each tc`, `-games`, `-repeat`, `-recover`, resign and draw adjudication, the `-sprt` group, `-concurrency`, `-ratinginterval`, `-pgnout`, the `-openings` group), writes the same `{Executable, Arguments}` manifest, supports `--prepare-only`, and refuses to launch when `--expected-argument-manifest-path` differs. The log and PGN path rules and the exit-code handling match. Numbers are printed the way PowerShell prints them, 15 significant digits (`alpha = 0.05 / 3` is `0.0166666666666667`). I first assumed pwsh 7 printed the shortest round-trip form; the differential test caught that on the CI runner, and only the Bonferroni case differed. The frozen runs use `BonferroniM = 1`, where both forms are `0.05`. `--provenance-path` writes a record with the launcher's SHA-256 and git commit, both engine JAR hashes, the opening book hash, the parameters, the SHA-256 of the argument array, Java and cutechess version lines, a WSL flag, and the Stage 0 host facts (CPU count, memory and swap totals, load average, raw `/proc/pressure` text), with start and finish times and the exit code. It records file names and hashes only, no absolute path; engine options whose value is an absolute path (an `EvalFile`, say) are recorded as `Name=<path omitted: file name>` (found in review), while the argument manifest keeps the real value, as `sprt.ps1`'s does.

**Decisions Made:** `sprt.ps1`, `tools/release-strength-sprt*.ps1` and every preregistration are untouched, as the addendum said. The launcher is deliberately not a port of the whole PowerShell script. It leaves out the informational colour-balance printout, which never affects stopping. It copies two `sprt.ps1` quirks on purpose, because the contract is equivalence: `--min-games` only raises the default game cap and is not enforced as a minimum, and the resign threshold is `score=400`. The equivalence is defined for literal paths (`sprt.ps1`'s `Resolve-Path` also expands wildcards in `-New` and `-Old`, which this launcher does not) and for numbers below 1e15 in magnitude (the exponent form starts there and that boundary is not covered by the differential test, so larger values are refused), and an empty `--openings-file` is replaced by the default book before the existence check, in the same order as `sprt.ps1`. It deviates in one place: with no `tools/noob_3moves.epd` and no `--openings-file` it stops unless `--allow-startpos` is passed, where `sprt.ps1` silently plays from the initial position (the deferred #273 item, fixed here only in the new file). The provenance record always says `authoritative: false`, and the launcher prints a notice on WSL. This is not a qualification: the staged protocol has not been run, no game has been played with the launcher, and CLAUDE.md is unchanged.

**Measurements:** none, no games were launched. Tests (`tools/tests/test_sprt_linux.py`, 29 tests): a hand-written golden argument list for the frozen #266 shape; a static parse of `sprt.ps1`'s own argument statements compared token for token with the Python builder (changing `-ratinginterval 10` or `score=400` on the Python side fails both); manifest shape, prepare-only, accept-and-refuse of an expected manifest, fail-closed book handling, log/PGN rules, missing cutechess and jars; log tee-ing and exit-code propagation using a fake shell script as cutechess; the provenance contents, that it records no absolute path and is never authoritative; WSL detection; host parsing. A differential test runs the real `sprt.ps1 -PrepareOnly` and the Linux launcher on six parameter sets (the output paths contain spaces in every case, and one case uses very small and very large doubles) and compares the manifests, and checks that each accepts the other's manifest and refuses a changed one. It needs pwsh 7. pwsh is not installed in the WSL environment used here, so that test has not run locally: CI sets `REQUIRE_PWSH=1` so that on the runner a missing pwsh fails instead of skipping, and the first CI run is its first execution.

**Not done:** the staged qualification itself (environment record, relative throughput, A/A pilot, A/A at size, cross-platform replication), cutechess-cli is not installed in this environment, and nothing was run end to end against a real engine pair. Changing the policy in CLAUDE.md is the owner's decision after that evidence exists.

### [2026-11-24] #290 rejected after the native Windows SPRT

**Built:** The experimental TT policy retained fresh, deeper entries on different-key collisions. It used the same one-slot layout, memory budget and XOR publication as develop. The frozen baseline was `a14a664fa40a80a9c2bfab952fbb34e788af6f62`; the candidate was `c9d20e607203283ecbd175701147cf976ccd863c`. The [experiment branch](https://github.com/coeusyk/chess-engine/tree/fix/tt-collision-policy) retains the candidate, and the [frozen build and launch procedure](https://github.com/coeusyk/chess-engine/commit/945bc0f) records the exact arguments and opening-book checksum.

**Decisions Made:** Reject the candidate and close PR #290. It failed the approved +10 Elo strength criterion. #266 remains open for the remaining TT work. This decision authorizes no new replacement-policy experiment or multi-thread SPRT.

**Measurements:** Two cold, fixed-depth passes per arm agreed on all 31 positions at depth 13. At 16 MB, nodes fell from 23,222,204 to 19,146,122 (17.5%). At 1 MB, nodes fell from 44,040,937 to 24,719,457 (43.9%). At 16 MB the best move matched on every position, although scores changed by up to 33 cp. A warm 4 MB pass saved about 7% of nodes and had a lower probe hit rate, 24.2% versus 27.2%.

The native Windows match used Classical evaluation, Threads=1, Hash=16 MB, 5+0.05, paired openings, concurrency 6 and the frozen [0, +10] Elo bounds at alpha=beta=0.05. It accepted H0 after 3,552 scored games (1,163 wins, 1,209 draws, 1,180 losses). Terminal LLR was -2.97 against a lower bound of -2.94. Estimated Elo was -1.7, with a 95% interval of [-10.95, +7.62]. There was one baseline time forfeit and no recorded crash, stall, disconnect or illegal move. Both arms averaged 0.1336 seconds per move.

**Broke / Fixed:** Five games were in flight when cutechess stopped. Two scored losses had unfinished partners. The prescribed trinomial rule crossed the boundary with those losses included. A separate complete-pair check over 1,775 pairs yielded LLR -3.14 and Elo -1.47 (95% interval [-10.37, +7.43]). The [original audit](https://github.com/coeusyk/chess-engine/commit/3d236ce7f02bdb53998c78dcd9050851ac2a9503) records the pair counts, LLR progression and provenance checks.

A cold fixed-depth node saving did not clear the match's strength criterion. The warm-table measurements show why hit rate and reuse across moves are worth measuring alongside nodes. The match did not log in-game hashfull, so it cannot establish whether table pressure or reuse explains the result.

The raw files are preserved in the native checkout at `tools/results/tt-266/20261124T120052`. All six artifact hashes were recomputed and matched the audit. The original `SHA256SUMS` covers the four pre-run files and also passed verification.

| File | SHA-256 |
|---|---|
| `SHA256SUMS` | `be211655c48b7d3a286748d3cd0551dfd25af2a3bcab40739cbc0af9bed88a95` |
| `base.jar` | `cabf6a805bb5335bd28a19f4ea03648c31702be73ae833769c46fbd3e5cf6f9b` |
| `cand.jar` | `fa8186cb21d94db13e2a616de28b879463ed2208d89e7e64c8f774918ee670fc` |
| `prepared-arguments.json` | `876b55ac7990017b8006ec4fa2bd581280f65112ef0e15f9991af6bd08545335` |
| `provenance.json` | `a7c0c33d65df29d2c239362826015b95ed0914d4b0b06487246643427f4eb832` |
| `match.log` | `fc6d00df7ae8c61367e8fed8f56682835fc5fdb359519449241e361e56e0e27d` |
| `match.pgn` | `f4dae62f41c308ca3eda2958a09fd6a793e133eff5bd41614aa0c85884ca2bc0` |

### [2026-11-24] Stage 2 ingest and labeling fixes, shard format version 2 (#275, part A)

**Built:** (1) Shard format version 2: the FEN field is 100 bytes, matching VSPR's own cap, where version 1 had 90. `write_shard` writes version 2; `read_shard` still reads version 1, picking the record dtype from the header's version. (2) `selfplay_ingest.py` converts the mate distance from plies to moves. (3) `stockfish_label.py` skips `lowerbound` and `upperbound` scores, sends `ucinewgame` and waits for `readyok` before each position, and resolves a bare engine name such as `stockfish` on PATH.

**Decisions Made:**
- Shard version 2, with version 1 still readable and no migration of existing datasets. The issue called the format "v3"; it was version 1, so there was nothing to be v3 of. A 100-byte FEN could be produced by VSPR (`_MAX_FEN_BYTES` is 100) but failed to ingest into a 90-byte field. Rather than raise the cap in place, which would have made every existing shard unreadable, the header's version selects the dtype. Anything else is rejected with a `ShardFormatError` that names the problem: an unsupported version, a record size that does not match the declared version, a file size that is not the header plus whole records, and a record count that disagrees with the file. The record count is still never inferred from the file size.
- The writer rejects a FEN that is non-ASCII, contains a NUL, or is over 100 bytes. This matters because numpy's fixed-width `S` dtype silently truncates, so without the check a 101-byte FEN would be stored as a different, valid-looking string.
- The two legacy migrations (`migrate_legacy119_to_v1`, `migrate_legacy124_to_v1`) still write version 1. Their names say v1, their source FENs were already capped at 90 bytes, and nothing needs them to change. Every consumer was checked and none needed code changes: `read_shard` is reached through `StockfishLabeledProvider`, `SelfPlayProvider` and `train_candidate_net._load_all` (which is how the phase 3, 4 and 5 scripts and training load shards); the writers `stockfish_label.py`, `selfplay_ingest.py` and `backfill_stage2_wdl.py` all go through `write_shard`, so they now emit version 2; `selfplay_ingest.py` imports `_read_header` for a record-count check, which is version-independent. `backfill_stage2_wdl.py` keeps its own frozen 90-byte reader for pre-header files, which is unaffected.
- Mate distance: `GameLoop.decodeScore` records plies (the winner mates on an odd ply, the loser is mated on an even one) and the trainer documents `eval_mate` as moves. Ingestion copied the number across, so a mate in 2 moves (3 plies) was stored as 3. The conversion is `ceil(|plies| / 2)` with the sign kept, so 1 and 2 give 1, 3 and 4 give 2, and 0 stays 0. The default training target (`mate_target_distance_aware` is false) does not read the distance, so no current run is affected; it matters only if that option is turned on.
- Bound scores: a `score cp 900 lowerbound` line is a fail-high from an aspiration window, not the position's value. The driver took the last score line before `bestmove`, so a trailing bound would have been recorded as exact. It now ignores bound lines and keeps the last exact score; a position with only a bound score is a protocol error and is skipped like the existing no-score case.
- `ucinewgame` before every position, followed by `isready`. Without it the engine keeps its hash and history from the previous position, so a label could depend on what was labeled before it.
- A bare engine name is resolved with `shutil.which`; the committed config uses `"engine_path": "stockfish"`, which failed at the hash step because a bare name is not a file. A name that is not on PATH fails with the name in the message. The manifest still records the configured value, not the resolved one. Review found two flaws in the first version of these, fixed in a second commit: an engine file that exists is now returned as an absolute path, because a bare name passed to `Popen` is searched on PATH and could start a different binary than the one that was hashed; and a bound line is now skipped whole, depth and nodes included, because the first version kept the exact score but took the depth from the later bound line, so the shard claimed a deeper search than the one that produced the label. Review also found the miscounted-header test was rejected for the wrong reason (it read the record size from the wrong offset), and that the version 1 fixture shared its layout with the reader. The fixture is now written field by field with `struct`, with every optional field populated and a full-width 90-byte FEN case.

**Disproved:** the issue says `mate 0` should not be kept. Stockfish prints `mate 0` for a side that is already checkmated, and `target_cp` maps a non-positive mate to a negative target, which is right for that case, so I left it as is.

**Broke / Fixed:** two existing assertions changed meaning and were updated, not loosened: the test that rejected `format_version=2` now rejects 3 (2 is valid), and the migrated-shard header test now expects the version 1 record size, since migrations deliberately keep version 1.

**Measurements:** no dataset was opened, labeled or migrated. Trainer suite: 547 passed locally. Mutations, each reverted: dropping version 1 reading fails 4 of the new shard tests; removing the length check fails 1; setting the version 2 width back to 90 fails 8 with 3 errors; copying the mate distance raw fails 1; removing the bound skip fails 2; removing `ucinewgame` fails 1; removing PATH resolution fails 1.

### [2026-11-24] Trainer utility fixes: validator, deduplication, loss floor, device and NUL (#275, part B)

**Built:** (1) The three validator metrics (`evaluate_held_out`, `fit_affine_calibration`, `calibration_report`) skip records with neither `eval_cp` nor `eval_mate`, warn with the count, and raise a clear error if nothing is left. (2) `deduplicate` and `combine_and_split` treat two records as the same position when their piece placement and side to move match (new `position_key`), and `combine_and_split` now removes duplicates before the split instead of only counting them. (3) The weighted loss divides by `weights.sum()` floored at the smallest normal float32. (4) `checkpoint_to_canonical` calls `.cpu()` before `.numpy()`. (5) `_write_utf` rejects a NUL. (6) `texel_sigmoid` uses a Python float base.

**Decisions Made:**
- Validator: `target_cp` raises for a WDL-only record, so a held-out set containing one crashed all three metrics. These metrics compare predicted centipawns with a centipawn target, so a WDL-only record has nothing to contribute; it is left out with a warning rather than silently, and `position_count` reports what was used.
- Deduplication key: the full FEN, clocks included, meant the same position with a different halfmove or fullmove counter was not a duplicate. The network's input is the placement and the side to move, so those two fields are the position; castling rights and en passant are not in the key. Without removal before the split, a duplicate could land in both the training and the held-out set and make the held-out loss measure memorisation. The first record wins, in load order, so a position present in both stages keeps its Stage 1 label. This is a behaviour change for anyone relying on the old key, and the dataset quality report now counts more duplicates than before for the same data. Review found that removing records before the seeded shuffle changes the held-out set, and more than twenty experiment and evaluation scripts (including the Phase E evaluation of frozen checkpoints) call `combine_and_split` with a fixed seed to rebuild the exact split a checkpoint was trained on; the 40,000-record corpus has documented duplicates, so those evaluations would have used a held-out set containing positions the checkpoints trained on. Removal is therefore behind `drop_duplicates`, default False, and only `train_candidate_net.main` (new training runs) passes True; a test checks the default keeps all records. The candidate export path still calls `target_cp` on every held-out record, which would fail on a WDL-only record; the current providers do not produce one, so I left it.
- Loss floor: with `mate_weight=0.0` an all-mate batch has a weight sum of 0 and the loss was 0/0, NaN, which then spread to every parameter. The floor is the smallest normal float32, so any real weight sum is untouched and only an exact zero gives a loss of 0.
- `.cpu()`: a checkpoint saved from a CUDA run holds CUDA tensors and `.numpy()` raises on one. No GPU is assumed here, so the test wraps the four tensors in objects that fail `.numpy()` unless `.cpu()` came first. This is the same on CPU, where `.cpu()` is a no-op.
- `_write_utf`: Java's modified UTF-8 writes NUL as C0 80 and plain UTF-8 writes 00, so a NUL would not decode the same way in the Java reader. The values it is called with (a UUID and a commit hash) never contain one, so this closes a gap and fixes nothing that happened.
- `texel_sigmoid`: I could not reproduce a device failure without a GPU, so this is hardening, not a confirmed defect. A bare `torch.tensor(10.0)` is a CPU tensor that has to be reconciled with a CUDA input; a Python float base stays on the input's device. A test checks the result against the old formula for float32 and float64 within 1e-6 relative.

**Measurements:** the trainer suite passes (524). Mutations, each reverted: dropping the validator filter fails 2; weakening the key to the full FEN fails 4; not removing duplicates before the split fails 1; removing the loss floor fails 1; removing `.cpu()` fails 1; removing the NUL check fails 1. No training, labeling or dataset run.

**Not done:** the Java side (part C), and the `texel_sigmoid` change has no failing-before test because there is nothing to fail without a GPU.

### [2026-11-24] Self-play and VSPR fixes: strict UTF-8, write bounds, option parsing, streamed digest, aborted MultiPV line (#275, part C)

**Built:** (1) `VsprCodec` decodes and encodes UTF-8 strictly and the writer enforces the reader's bounds. (2) `SelfPlayCli` rejects an odd argument count, an unknown option and a repeated option, and computes the VSPR SHA-256 by streaming. (3) `Searcher.IterationListener` gets a default no-op `onIterationAborted(depth, multipv)`, `Searcher` calls it when a line after the first is stopped before finding a move, and `CompletedRootCandidateAdapter` overrides it.

**Decisions Made:**
- Strict UTF-8: the reader built strings with `new String(bytes, UTF_8)`, which replaces a malformed sequence with U+FFFD, while the Python decoder is strict, so Java accepted files Python refuses. Reads now use a decoder that reports malformed input and raise `VsprFormatException` (`STRUCTURAL`). The writer used `getBytes`, which turns an unpaired surrogate into `?` and so writes a different string than the one given; it now throws `IllegalArgumentException` naming the field. A valid non-ASCII string (2 and 3 byte sequences) still round-trips.
- Write bounds: the reader rejects an opaque config payload over 4,096 bytes, more than 218 candidates and a PV over 128 moves, but the writer only checked strings. A 4,097-byte payload wrote a file the reader then refused, and a 65,536-byte one wrote its `u16` length as 0 and corrupted the stream. The writer now throws for all three, at exactly the reader's limits; a payload of exactly 4,096 bytes round-trips.
- Option parsing: `--name value` pairs were read two at a time with `args.length - 1` as the bound, so a dangling flag was dropped, a misspelt flag was ignored and left its default in force, and a repeated flag kept only the last value. All three are errors now. This is stricter than before: a script that passed an unknown option or a duplicate will start failing, which is the point.
- Digest: the VSPR file was read whole with `Files.readAllBytes` to hash it. It is hashed in 1 MiB chunks now; the test uses a file larger than one chunk that ends on a partial chunk, and an empty file.
- Aborted MultiPV line: `Searcher` reports a line to its listener only when it found a move. When the stop landed inside line 2 or later and no move was found, nothing was reported, so `CompletedRootCandidateAdapter` saw the earlier lines of that depth and then silence, which is exactly what a position with fewer legal moves than the requested width looks like. `finish()` then published the depth as complete. Reproduced end to end first, with a stop flipped when line 2 of depth D is reported: at depths 2 to 5 the adapter published depth D with 2 of the 3 requested lines, replacing the full depth D-1 set. I added a default no-op listener method rather than a new `IterationInfo`, because UCI `printInfoLine`, `AnalysisService` and its evaluate listener do not check `completed()` and would have printed or buffered a line with no move and a meaningless score. Existing lambda listeners compile and receive exactly the events they did before; a test checks that. Self-play itself passes `() -> false` as its stop suppliers, so this could not happen in a fixed-depth self-play run today; it is a latent defect for any caller that stops a MultiPV search early.

**Measurements:** `VsprCodecTest` 27, `SelfPlayCliTest` 29, `CompletedRootCandidateAdapterTest` 18, `GameLoopTest` 9, `SearcherTest` 31 and `TerminalAdjudicationTest` 11 pass (125). Against develop's `VsprCodec` five of the six new codec tests fail (the valid non-ASCII round trip passes both ways, as a control). With the three option checks disabled, three of the four new parsing tests fail. With the Searcher call removed, the end-to-end abort test fails. No benchmark was run; the Searcher change adds one branch on a path that only runs when a line is stopped, and the default method does nothing, so search results and node counts are unchanged, which is an argument from the code and not a measurement.

**Not done:** the trainer side (#298, #299), any change to how self-play handles a stop, and a `completed=false` event for UCI or the API.

### [2026-11-24] Maximum search ply guard (#267, item 1)

**Built:** `Searcher.MAX_SEARCH_PLY = MAX_PLY - MAX_Q_DEPTH - MAX_Q_CHECK_EXTENSION` (119) and a guard in `alphaBeta`, placed after the draw checks and before any array indexing: a node at that ply is adjudicated by `boundaryScore` (a legal-move test, so checkmate and stalemate keep their exact scores, otherwise the static eval) and does not recurse. `SearcherPlyBoundsTest` has six tests.

**Decisions Made:** the defect was real. `staticEvalStack` holds `MAX_PLY + 2 = 130` entries and nothing bounded `ply`, while iterative deepening reaches depth 127 and check extensions add up to 16 plies, so a node can sit at ply 143. Reproduced before the fix: `alphaBeta` called at ply 128 recursed to ply 130 and threw `ArrayIndexOutOfBoundsException: Index 130 out of bounds for length 130`. The limit is 119 and not 127 because of mate-score semantics: `scoreToTT` and `isMateWindow` only treat a score as mate when `|score| >= MATE_SCORE - MAX_PLY`, which means a mate scored at ply 129 or deeper is classified as an ordinary score, and quiescence can recurse another `MAX_Q_DEPTH + MAX_Q_CHECK_EXTENSION - 1 = 8` plies past the last searching ply. With 119, the deepest quiescence mate lands at ply 126. A guard at 127 would have fixed the array overflow and left quiescence mates at ply 129 to 136 misclassified. Draw adjudication still comes first (a repetition, fifty-move or insufficient-material draw at the boundary keeps its score). Independent review found one more issue, fixed in a second commit: a node a few plies above the boundary searches only the plies that fit but stored its result at the requested depth, so a later, shallower visit of the same position could reuse it as a full-depth bound. The stored depth is now `min(effectiveDepth, MAX_SEARCH_PLY - ply)`, which equals `effectiveDepth` for any node with room to spare, so the normal path is unchanged.

**Measurements:** red first, then green: the ply-past-the-array test failed on develop and passes; disabling the guard fails two of the six tests; `SearcherTest` (31), `SingularSearchBoundSemanticsTest`, `TerminalAdjudicationTest` and `NodeCountRegressionTest` pass unchanged, including the node-count golden. No benchmark was run (the #290 SPRT is using the machine). The guard cannot change a search that never reaches ply 119, so bench node counts are expected to be identical, but that is an argument from the code and is not measured; the owed check is a native bench node-count identity run once #290 finishes. Behaviour does change for a search that would have passed ply 119: it now stops there instead of continuing.

**Not done:** the other items in #267 are separate (see the follow-up entries): singular verification on mate scores, the seventh-rank SEE gate, and the search-strength items (PV-node TT cutoffs and null move, `correctionHistory`, Q-search ordering and `bigDelta`, history).

### [2026-11-24] Seventh-rank pawn extension: remove the always-true SEE condition (#267, item 5)

**Built:** `shouldApplyPawnPromotionExtension` now ends with `return seeEnabled;` where it ended with `staticExchangeEvaluator.evaluateSquareOccupation(board, targetSquare) >= 0`. `PawnPromotionExtensionGateTest` has four tests.

**Decisions Made:** confirmed, and reproduced on unchanged code. `evaluateSquareOccupation` returns `occupantValue - bestReplyGain`. For a pawn that is `100 - bestReplyGain`, and `bestReplyGain` is a capture gain bounded by the pawn's own value, so the result lies in `[0, 100]` and `>= 0` cannot fail. A pawn pushed to g7 and left attacked by a rook with no defender scores exactly 0 and passes, so the push is extended although the pawn is lost; across a scan of random playouts from tactical seeds, 200 or more seventh-rank pawns were evaluated and none scored below 0. Because the condition is always true, removing it cannot change any decision: this is a behaviour-preserving change, and the test shows it with an independent copy of the old gate evaluated beside the new one for a hanging, a defended and a black pawn, with SEE on and off. A red-first test is therefore not possible, which is itself the evidence that the condition did nothing. The `seeEnabled` condition stays so the extension still switches off with the other SEE-dependent logic.

**Not done:** a gate that really rejects a pawn that hangs for nothing (it would need `> 0`, or a different measure) changes which pawn pushes are extended, so it is a search-strength experiment and needs its own SPRT. It is deliberately not part of this change. The only caller of `evaluateSquareOccupation` is gone, so the method is now unused; it is left in place to keep this change to one line of logic.

**Measurements:** `SearcherTest`, `NodeCountRegressionTest`, `SearchRegressionTest` and `TerminalAdjudicationTest` pass unchanged. No bench was run (the #290 SPRT is using the machine). For a fixed-depth search the node count is identical by construction, since the removed expression was always true and the SEE call changes no position state (it only invalidates the board's attacked-squares cache, which affects cache warmth, not results). That is an argument from the code. A timed search can stop at a different point, because the removed work changes timing, so its node count is not expected to match. The owed check is a native bench node-count identity run once #290 finishes.
