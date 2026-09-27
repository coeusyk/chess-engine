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
