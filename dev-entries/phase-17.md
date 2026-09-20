# Dev Entries - Phase 17

---

### [2026-09-20] Phase 17 — Branch setup + PVS mechanism gate (Issues #229, #230)

**Built:**

- **Branch `phase/17-pvs-experiment` created from `develop` HEAD `ebe513eabd50e853a4e24a0260c64b41a5a4b224`** (the Phase 16 merge commit). Phase 16's branch was cleaned up first: verified fully merged, deleted locally and on origin, `git fetch --prune` run. Phase 17 epic issue #230 and implementation issue #229 created; #231 created separately for the nightly-SPRT wording cleanup this phase's strength gate will eventually depend on.

- **Before editing anything**, re-read `docs/architecture/research/phase16-p16-3-intervention-preregistration.md` section 3 (the control-flow inspection) and section 6 (the frozen experiment scope) against current `Searcher.java` source. Confirmed byte-for-byte that the move loop at `alphaBeta` lines 1069-1120 and `searchRoot` lines 778-810 matched exactly what section 3 described — no drift since P16-3 was written, since nothing had touched `Searcher.java` between then and this branch point. No conflict found between the preregistered sequence and current semantics; proceeded rather than stopping.

- **Restated the exact state transitions** (per the task's own requirement, before writing code):
  - First move at a PV node: full window `(-beta, -alpha)`. Unchanged.
  - Later non-LMR sibling at a PV node: was full window (same as the first move); now a full-depth null-window probe `(-(alpha+1), -alpha)` first, with a full-window re-search only if the probe lands strictly inside `(alpha, beta)`.
  - Later LMR-eligible sibling at a PV node: was reduced-depth null-window probe → (on any fail-high, `score > alpha`) immediate full-depth full-window re-search. Now: reduced-depth null-window probe → (on fail-high strictly inside the window) full-depth null-window verification → (only if *that* also lands strictly inside the window) full-window re-search.
  - Non-PV node: unchanged by construction — the new ordinary-sibling branch is gated on `isPvNode`, so a non-PV node structurally cannot enter it.
  - Fail-high `>= beta`: cutoff normally; the new code explicitly does not re-search when a probe already resolves `>= beta`, closing a gap in the old code (which re-searched on any `score > alpha` regardless of whether it was already `>= beta`).
  - Fail-high strictly inside `(alpha, beta)`: triggers exactly one full-window re-search, in both the ordinary-sibling and the (now three-stage) LMR path.

- **Implemented general PVS** in `Searcher.alphaBeta()`'s move loop: the existing `if (canApplyLmr(...))` branch gained an intermediate full-depth null-window verification stage before its full-window re-search (previously it jumped straight there on any fail-high); a new `else if (isPvNode && moveIndex > 0)` branch gives ordinary non-LMR-eligible later siblings at PV nodes the same single-stage probe-then-re-search treatment; the original `else` branch is now reached only by the first move at any node, or any move at a non-PV node, exactly matching the "first move / non-PV" cell of the restated transitions above.

- **Applied the same principle at root** in `searchRoot()`: root is always the PV node by construction, so `rootMoveIndex == 0` keeps the existing full-window search, and `rootMoveIndex > 0` now gets a single-stage null-window probe with a full-window re-search only on a genuine improvement — root has no LMR of its own, so there's no three-stage composition to preserve there.

- **LMR eligibility and reduction amount left untouched**: `canApplyLmr`, `lmrReductions`, and the `improving`-based +1 adjustment are unchanged. The new verification stage sits entirely inside the branch that already existed for LMR's fail-high case; it doesn't change when a reduction is attempted or how large it is.

- **Instrumentation**: added three new counters — `pvsZeroWindowProbes` (the new ordinary-sibling probe only, deliberately kept separate from `lmrApplications` per the preregistration's own naming caution), `pvsFullDepthVerifications` (the new LMR-path verification stage), `pvsFullWindowResearches` (the final re-search, from either path). All follow the existing per-field pattern exactly: reset per `pvIndex` iteration, accumulated into `total*` locals across the iterative-deepening loop, and added to the `[BENCH]` DEBUG log line as `pvs_zw_probes`, `pvs_full_verif`, `pvs_full_resrch`. Also added to `SearchResult` (mirroring `lmrApplications`, which was already there) so the new focused tests could assert on them directly without reflection or new test-only plumbing. Production logging defaults (root level INFO, `Searcher`'s DEBUG-level `[BENCH]` line only visible via the existing opt-in `tools/logback-debug.xml` override) are unchanged.

- **Tests**: `PvsExperimentTest.java` (new file, 9 test methods): the preregistered PV-legality invariant, parameterized over four FENs (obtain the PV, replay each move on a fresh board, assert legality at the point it's played via `MovesGenerator.generate`); a test that later PV-node siblings do exercise `pvsZeroWindowProbes`; a test that not every probe becomes a full-window re-search; a test that at least some re-searches do occur on a real position; a test that `pvsFullDepthVerifications` never exceeds `lmrApplications` (structural bound: verification is only reachable from the LMR branch); and a test using the existing package-private `Searcher(moveOrderingEnabled, aspirationWindowsEnabled, nullMovePruningEnabled, lmrEnabled=false)` constructor that disabling LMR eliminates `pvsFullDepthVerifications` entirely while `pvsZeroWindowProbes` still fires (demonstrating the two paths are genuinely independent, not just nominally distinct counters). All 9 pass. Two structural properties from the task's "at minimum" list — first move never entering the new branches, and non-PV nodes never entering the ordinary-sibling branch — are guaranteed by the branch guards themselves (`moveIndex > 0` and `isPvNode`, respectively) rather than tested at runtime; no invasive per-node window-tracing hook was added to test them separately, per the task's own preference for existing counters over broad test-only plumbing.

**Decisions Made:**

- **No feature flag added to gate PVS on/off.** The preregistration's comparison table named "rollback clarity" as a strength of this option, and a boolean flag would have been the idiomatic way to get it (matching `lmrEnabled`, `nullMovePruningEnabled`, `futilityRazoringEnabled`). Decided against it: `Searcher` already has a 6-deep telescoping constructor chain, and adding a 7th boolean would touch every overload plus every call site across `BenchRunner`, `UciApplication`, and the existing test suite — a much wider diff than the task's explicit implementation scope (items A-D) called for, and the task repeatedly emphasized not broadening scope. Rollback for this experiment is a plain `git revert` of one self-contained commit instead, which is just as clear on a single-commit branch.
- **`childIsPvNode` computation left exactly as `isPvNode && moveIndex == 0` in every recursive call, including the new full-window re-search branches.** A later sibling's full-window re-search always passes `childIsPvNode = false` (since these branches require `moveIndex > 0`), matching the pre-existing convention in the old LMR fail-high branch. Considered marking a later sibling that wins a full-window re-search as a new PV node going forward, which is closer to some engines' convention, but the preregistration explicitly said this change is "entirely inside the branch selection of which window to pass," not a redesign of when a node becomes PV — changing this would have been exactly that redesign, so it was left alone.
- **`SearchResult` extended with the three new counters** rather than keeping them purely internal, to make the focused tests possible without reflection or new test-only plumbing, per the task's explicit preference for "minimal package-private instrumentation or existing counters." This mirrors `lmrApplications`, which was already exposed there for the same reason.
- **The five pre-existing test failures below are left unresolved in this commit, on purpose.** They are the direct, expected consequence of a real search-shape change — exactly the effect this experiment sets out to produce — not a bug this session introduced. Per the task's explicit framing, resolving them (via this project's existing depth-probe-then-update convention) is the formal correctness gate's job, a later Phase 17 step, not part of the mechanism-gate commit.

**Broke / Fixed:** Five pre-existing tests now fail, all consistent with a genuine, non-buggy search-shape change:

- `NodeCountRegressionTest.nodeCountsAreUnchangedFromGoldenBaseline` — golden node count for the starting position drifted (this test's entire purpose is to catch a search-shape change, and one was made deliberately).
- `SearchRegressionTest.bestMoveIsStable` — 4 of its parameterized positions (`P5`, `P10`, `E1`, `E5`) now report a different best move.

All move-generation/legality suites (`PerftHarnessTest`, `CastlingRestrictionsTest`, `EnPassantLegalityTest`, `PromotionHandlingTest`, `FiftyMoveRuleTest`, `FenLegalityValidationTest`, `GameStateLegalityTest`, `ThreefoldRepetitionTest`) remain fully green, indicating the node-count change is a genuine search-shape effect, not a move-generation or legality defect. `mvn -pl engine-core -am test`: 399 run (390 pre-existing + 9 new), 5 failures (all listed above), 0 errors, 5 pre-existing skips.

**Measurements:**

Canonical fixed-depth mechanism comparison, `--bench-raw 13`, native-Windows-frozen P16-2 baseline vs. this branch's WSL run (node counts are deterministic and hardware-independent for this fixed-depth, non-time-bounded path — P16-2 itself already cross-validated WSL and native Windows producing identical node counts for the pre-PVS baseline):

| Metric (depth 13, 31-position suite, aggregate) | Baseline (pre-PVS) | Phase 17 (PVS) | Delta | % |
|---|---|---|---|---|
| Main nodes | 73,089,246 | 48,892,339 | -24,196,907 | -33.11% |
| Quiescence nodes | 226,653,985 | 133,023,392 | -93,630,593 | -41.31% |
| Beta cutoffs | 61,398,676 | 41,410,058 | -19,988,618 | -32.56% |
| TT hits | 8,190,621 | 6,427,137 | -1,763,484 | -21.54% |
| LMR applications | 27,422,470 | 21,909,287 | -5,513,183 | -20.11% |
| Null-move cutoffs | 737,269 | 767,573 | +30,304 | +4.11% |
| Futility skips | 26,456,763 | 19,497,950 | -6,958,813 | -26.30% |
| Delta-pruning skips | 23,016,259 | 15,567,523 | -7,448,736 | -32.36% |

New PVS-specific counters (Phase 17 run only, aggregate at depth 13, no pre-PVS equivalent since these paths didn't exist before):

| Counter | Total |
|---|---|
| `pvsZeroWindowProbes` (ordinary PV-sibling probes) | 48,884 |
| `pvsFullDepthVerifications` (LMR-path verification stage) | 28,401 |
| `pvsFullWindowResearches` (final re-search, either path) | 14,473 |

Full-window re-search rate: 14,473 / (48,884 + 28,401) = 18.7% of probes that survived their first check actually needed the expensive full-window re-search — well short of "most," so the pathological-re-search-rate stop rule does not trigger. Verification rate relative to LMR: 28,401 / 21,909,287 = 0.13% of LMR reduced-depth probes fail high enough to reach the new verification stage, consistent with LMR's reductions usually being conservative enough not to need it.

Completed depth: all 31 positions reached depth 13 in both the plain and debug-logged runs (confirmed via the `X/31 ... depth=13` progress lines), matching the fixed-depth protocol.

Per-position node-count delta (all 31 positions, sorted by suite order):

| Pos | Baseline | Phase 17 (PVS) | Delta | % |
|---|---|---|---|---|
| 1 | 983,257 | 594,466 | -388,791 | -39.5% |
| 2 | 8,746,524 | 6,898,426 | -1,848,098 | -21.1% |
| 3 | 8,095 | 8,083 | -12 | -0.1% |
| 4 | 2,759,518 | 414,822 | -2,344,696 | -85.0% |
| 5 | 855,500 | 864,079 | +8,579 | +1.0% |
| 6 | 1,125,568 | 868,060 | -257,508 | -22.9% |
| 7 | 8,025,190 | 4,163,397 | -3,861,793 | -48.1% |
| 8 | 2,401,204 | 1,058,139 | -1,343,065 | -55.9% |
| 9 | 2,174,398 | 1,941,084 | -233,314 | -10.7% |
| 10 | 1,833,159 | 300,687 | -1,532,472 | -83.6% |
| 11 | 2,544,614 | 1,589,933 | -954,681 | -37.5% |
| 12 | 136,113 | 60,490 | -75,623 | -55.6% |
| 13 | 22,160 | 10,485 | -11,675 | -52.7% |
| 14 | 412,485 | 228,277 | -184,208 | -44.7% |
| 15 | 1,073,429 | 1,433,754 | +360,325 | +33.6% |
| 16 | 1,272,770 | 620,876 | -651,894 | -51.2% |
| 17 | 2,190,805 | 1,277,776 | -913,029 | -41.7% |
| 18 | 2,266,096 | 1,195,571 | -1,070,525 | -47.2% |
| 19 | 2,068,438 | 761,651 | -1,306,787 | -63.2% |
| 20 | 888,031 | 735,782 | -152,249 | -17.1% |
| 21 | 309,132 | 249,659 | -59,473 | -19.2% |
| 22 | 2,318,511 | 3,113,818 | +795,307 | +34.3% |
| 23 | 1,057,847 | 573,790 | -484,057 | -45.8% |
| 24 | 179,693 | 133,990 | -45,703 | -25.4% |
| 25 | 124,012 | 79,203 | -44,809 | -36.1% |
| 26 | 142,977 | 113,252 | -29,725 | -20.8% |
| 27 | 27,698 | 11,621 | -16,077 | -58.0% |
| 28 | 132,055 | 64,525 | -67,530 | -51.1% |
| 29 | 840,511 | 224,629 | -615,882 | -73.3% |
| 30 | 22,030,846 | 6,969,853 | -15,060,993 | -68.4% |
| 31 | 4,138,610 | 12,332,161 | +8,193,551 | +198.0% |

27 of 31 positions decreased, several substantially (position 4: -85.0%; position 10: -83.6%; position 30, the suite's single largest position: -68.4%). Four positions increased: 5 and 15 modestly, 22 by about a third, and position 31 nearly tripled (+198.0%). That one is worth flagging on its own. It looks like a known PVS pathology: the move-ordering heuristic's guess is repeatedly wrong somewhere in that position's tree, so the null-window probe keeps landing inside `(alpha, beta)` and triggering the expensive re-search instead of being decisively accepted or rejected, so the re-search cost stacks on top of the full-window cost instead of replacing it. Per the task's instruction, this wasn't tuned around, investigated further, or used to adjust the PVS conditions after the fact. It's reported as observed, for the correctness/throughput gates to account for.

**Stop-rule interpretation:** aggregate main-node count decreased by 33.11%, a large, unambiguous decrease, not a "small but real" one requiring deferral to the throughput gate's judgment call. Per the frozen stop rule (`docs/architecture/research/phase16-p16-3-intervention-preregistration.md` section 8, as clarified before this phase started): aggregate nodes decreased, and the re-search rate (18.7%) is well short of "most" probes, so neither rejection branch applies. **The mechanism gate passes.**

**Status:** Mechanism gate complete and passed. Per the task's explicit instruction, execution stops here — the native-Windows 7-run throughput gate (issue #229 step 2) is a separate future step, run so the authoritative measurement stays clean and isolated from this WSL mechanism check. The formal correctness gate (resolving the 5 known test-failure golden values via the project's depth-probe convention) and the strength gate (one isolated SPRT, blocked on #231's nightly-SPRT wording fix) both remain open, later Phase 17 steps.
