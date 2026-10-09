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
