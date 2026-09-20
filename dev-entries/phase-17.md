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

**Correction (2026-09-20 diagnostic pass):** the 18.7% figure below was originally described as "probes that survived their first check." That's imprecise and has been corrected. `pvsZeroWindowProbes` (48,884) is genuinely the ordinary-sibling path's first check, but `pvsFullDepthVerifications` (28,401) is already the LMR path's *second* stage -- its first check is `lmrApplications` (21,909,287), a much larger number. Summing 48,884 and 28,401 as a single denominator is numerically valid as a combined rate over "the two stage types that can lead directly to a full-window re-search," but it is not one unified "first check" population; the two paths reach that point through different numbers of prior stages. Current instrumentation also cannot attribute any individual one of the 14,473 full-window re-searches back to which path (ordinary-sibling or LMR-verification) triggered it -- both increment the same `pvsFullWindowResearches` counter. That's a real limitation of the current counters, not a correctness question, so no new counter was added to resolve it (see the 2026-09-20 diagnostic entry below for the fuller analysis).

Full-window re-search rate, computed over the combined stage-2 population as described above: 14,473 / (48,884 + 28,401) = 18.7% -- well short of "most," so the pathological-re-search-rate stop rule does not trigger. Verification rate relative to LMR: 28,401 / 21,909,287 = 0.13% of LMR reduced-depth probes fail high enough to reach the new verification stage, consistent with LMR's reductions usually being conservative enough not to need it.

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

27 of 31 positions decreased, several substantially (position 4: -85.0%; position 10: -83.6%; position 30, the suite's single largest position: -68.4%). Four positions increased: 5 and 15 modestly, 22 by about a third, and position 31 nearly tripled (+198.0%). That one is worth flagging on its own; see the 2026-09-20 diagnostic entry below for a direct comparison against position 30, which corrects an earlier, unsubstantiated "move-ordering-driven" characterization of this finding. Per the task's instruction at the time, this wasn't tuned around, investigated further in the moment, or used to adjust the PVS conditions after the fact -- it was reported as observed, for the correctness/throughput gates to account for, and has since been investigated diagnostically without changing any PVS behavior.

**Stop-rule interpretation:** aggregate main-node count decreased by 33.11%, a large, unambiguous decrease, not a "small but real" one requiring deferral to the throughput gate's judgment call. Per the frozen stop rule (`docs/architecture/research/phase16-p16-3-intervention-preregistration.md` section 8, as clarified before this phase started): aggregate nodes decreased, and the re-search rate (18.7%) is well short of "most" probes, so neither rejection branch applies. **The mechanism gate passes.**

**Status:** Mechanism gate complete and passed. Per the task's explicit instruction, execution stops here — the native-Windows 7-run throughput gate (issue #229 step 2) is a separate future step, run so the authoritative measurement stays clean and isolated from this WSL mechanism check. The formal correctness gate (resolving the 5 known test-failure golden values via the project's depth-probe convention) and the strength gate (one isolated SPRT, blocked on #231's nightly-SPRT wording fix) both remain open, later Phase 17 steps.

---

### [2026-09-20] Phase 17 — Bounded correctness diagnostic before the native throughput gate

**Built:**

A bounded diagnostic, not tuning: no PVS behavior, move ordering, LMR parameters, TT behavior, eval, or search constants were changed. No golden expected moves were updated. No throughput benchmark or SPRT was run.

- **Reproduced all four `SearchRegressionTest.bestMoveIsStable` failures** (P5, P10, E1, E5) via direct UCI probing at the test's own depth (8) on the current PVS build, and separately on a clean `git worktree` checked out at `develop@ebe513e` (pre-PVS baseline) rather than touching this branch. Both moves are legal in every case (the test itself already guarantees this by construction -- `bestMove()` can only be a move `MovesGenerator` produced); no illegal move was ever involved.

  | Case | FEN | Old (golden) | PVS (depth 8) | Baseline (depth 8) score | PVS (depth 8) score |
  |---|---|---|---|---|---|
  | P5 | `8/8/3k4/8/1PP5/8/8/2K5 w - - 0 1` | c1d2 | b4b5 | cp 340 | cp 332 |
  | P10 | `8/8/8/4k3/4P3/4K3/8/8 w - - 0 1` | e3f3 | e3d3 | cp 132 | cp 132 |
  | E1 | `4k3/8/8/8/8/8/8/4KQ2 w - - 0 1` | f1b5 | f1f6 | cp 1305 | cp 1303 |
  | E5 | `8/4k3/8/4P3/8/8/R7/4K3 w - - 0 1` | a2e2 | a2a6 | cp 987 | cp 844 |

  Scores differ by single-digit-to-low-double-digit centipawns in three of the four cases (P5, E1) or are bit-for-bit identical (P10: cp 132 on both sides, at every depth tested -- see below), consistent with the moves being near-equal or provably equal alternatives, not one side returning a corrupted or wildly wrong value. E5's gap (987 vs. 844, ~140 cp) is larger and is explained below by LMR's effect on shallow-depth mop-up scoring in a tactically live position, not by a PVS defect.

- **Depth-probed each case at 8, 10, 12, 14(, 16+ where useful)** on both the baseline worktree and this branch, per the project's existing convention, without changing any expected value:

  - **P5**: baseline 8=c1d2, 10=c4c5, 12=c4c5, 14=c4c5. PVS 8=b4b5, 10=c4c5, 12=c4c5, 14=c4c5. **Converges (case A/B blended): both reach the identical c4c5 by depth 10** and hold it through depth 14 -- neither side's depth-8 answer survives past depth 8. `c4c5` is one of the five moves (`c1c2, c1d2, c1b2, c4c5, b4b5`) this exact test's own comment block already documents as having been the depth-8 preference at various points in this project's history, each time due to a completely unrelated change (Texel tuning, a hanging-piece-penalty formula, an LMR formula update, a PST revert, aspiration-delta tuning).
  - **P10**: baseline stable at e3f3 for depths 8-14; PVS stable at e3d3 for depths 8-14. **Does not converge (case D by the letter of the question) -- but the position's own comment already states "e3d3 and e3f3 are provably equivalent king moves"** (mirror-symmetric king moves around a central pawn), and the score is identical (cp 132) on both sides at every depth tested. This is a stable split between two moves that are equal by the position's own geometry, not a live disagreement about which is better.
  - **E1**: baseline stable at f1b5 through depth 10, then f1f6 from depth 12 onward (finding mate in 8 at depth 14). PVS stable at f1f6 through depth 10, then f1b5 at depth 12-14, **then f1f6 again from depth 16 onward** -- matching baseline. **Converges (case A/B): both land on f1f6 by depth 16.** The comment block for this exact position already documents `f1f6` and `f1b5` swapping preference multiple times before, including specifically from an `ASPIRATION_INITIAL_DELTA_CP` change (Phase 14 A-4) -- the same parameter category now interacting with PVS.
  - **E5**: baseline 8=a2e2, 10=a2b2, 12=a2e2, 14=a2e2 (mate in 9). PVS 8=a2a6, 10=a2a6, 12=e1f2, 14=e1f2 (cp 1843, no mate yet), **15=a2e2, 16-20=a2e2** -- matching baseline, and baseline's own depth-10 answer (a2b2) also differs from its depth-8/12/14 answers, showing baseline itself isn't perfectly stable here either. **Converges (case A/B): both land on a2e2 by depth 15.**

  Three of four cases (P5, E1, E5) show full convergence to an identical move once searched a few plies past the test's own depth 8. The fourth (P10) is a stable split between two moves the codebase's own test comments already call provably equivalent.

- **Isolated the mechanism with existing toggles** (`Searcher(moveOrdering, aspiration, nullMove, lmr, futility, checkExt)`, diagnostic only, no candidate configuration under consideration) at depth 8:

  | Case | Full (PVS default) | LMR disabled | Aspiration disabled | Both disabled |
  |---|---|---|---|---|
  | P5 | b4b5 | c1c2 | c4c5 | **c1d2** (matches golden) |
  | P10 | e3d3 | **e3f3** (matches golden) | e3d3 | **e3f3** (matches golden) |
  | E1 | f1f6 | f1f6 | f1f6 | **f1b5** (matches golden) |
  | E5 | a2a6 | **a2e2** (matches golden) | a2a6 | **a2e2** (matches golden) |

  **Yes, all four differences disappear when the relevant selective mechanism(s) are removed, and the specific interaction is identifiable rather than generic:**
  - **P10 and E5**: disabling LMR alone recovers the golden move, regardless of the aspiration-window setting. This isolates the interaction specifically to the new full-depth null-window verification stage inserted into the LMR branch (section 6 of the preregistration) -- LMR's own reduced-depth probe and its follow-on stage change which candidate move is discovered as best-so-far at a fixed shallow depth, in positions where more than one candidate is truly (P10) or practically (E5) equal.
  - **P5 and E1**: neither LMR-off nor aspiration-off alone is sufficient; only disabling *both together* recovers the golden move. The interaction here is the combination of the aspiration window (which sets the `alpha`/`beta` bounds every later-sibling probe is measured against) and PVS's new window-selection branches, not either mechanism alone.

  Every one of these four positions' own test comments already documents this exact class of aspiration/LMR/eval-tuning sensitivity flipping the depth-8 preference among a small set of equally-winning moves, for entirely unrelated past changes. This diagnostic adds PVS's window-selection change as one more entry in that same, already-established sensitivity, not a new failure mode.

**Decisions Made:**

- **PVS window-semantics audit (task step 5): no bug found.** Read the implemented three-stage LMR composition and the single-stage ordinary-sibling branch (`alphaBeta` lines 1130-1245) and the equivalent root branch (`searchRoot` lines 800-851) line by line, and confirmed by direct inspection (not by memory) that `alpha` and `beta` are never reassigned between a single move's stages -- both are method locals/loop-scope variables, and the only reassignment of `alpha` in either method (`alphaBeta` line 1268, `searchRoot` line 873) happens once, after a move's entire (1- or 3-stage) score computation completes, before the loop's next move begins. Every stage's `-(alpha + 1), -alpha` and `score > alpha && score < beta` comparison for a given move therefore reads the exact same `alpha`/`beta` pair that move's evaluation began with. Root's `alpha`/`beta` can be an aspiration-narrowed window rather than `(-INF, INF)`, but the same fixed-during-a-move invariant holds there too. No stale-window or off-by-one defect found.

- **PV-node propagation audit (task step 6): a real, unresolved risk found, and flagged as instructed, not fixed.** `childIsPvNode = isPvNode && moveIndex == 0` means every full-window PVS re-search (moveIndex > 0 by construction, in both the LMR and ordinary-sibling branches) recurses with `isPvNode = false`, even though the window it's given is wide, not the null width `isPvNode` is otherwise a reasonable proxy for. Three forward-pruning gates read the *current* node's own `isPvNode` parameter and skip pruning when it's true: `canApplyRazoring`, `canApplyFutilityPruning`, and `canPruneLosingCapture` (the latter also gated to `depth <= 2`; razoring/futility's margins are likewise only defined for depth 1-2 per `getFutilityMarginForDepth`). A child reached via a full-window PVS re-search is told `isPvNode = false`, so within its own recursion these three heuristics are free to fire near that subtree's horizon -- exactly the situation they're designed to be skipped for, since a full-window search is, by definition, being asked for an exact value the way a true PV node is. This pattern already existed before Phase 17 (the old LMR-fail-high immediate re-search had the identical `childIsPvNode` formula), but Phase 17 generalized it from an LMR-only, comparatively rare path to the general later-PV-sibling case -- 14,473 full-window re-searches fired in the canonical mechanism run (section above), versus a much smaller, LMR-only-gated count before this phase. The failure mode considered plausible so far is that these gates cause a move to be underestimated near the horizon, costing the search a move it should have preferred. Whether that is the *only* failure mode has not been established: this analysis has not traced the interaction with negamax's sign flip, the fail-soft bound returned to the caller, or how the caller's TT-store treats a bound produced this way, so a claim that the effect "can only underestimate" or "can never fabricate" a better-than-true score would be broader than what's been checked here. **This was not demonstrated to cause any of the four SearchRegressionTest differences** (all four are fully and independently explained by the LMR/aspiration isolation above), so it does not block the mechanism-gate result or the throughput gate. It is real and unresolved, though, and per the task's own framing this is a correctness question, not permission to change it now -- `childIsPvNode` was left exactly as-is. **Recommendation, not action taken:** re-examine this specifically before the eventual SPRT (strength) gate, since Elo is sensitive to exactly this kind of subtle, horizon-concentrated selectivity bias in a way raw node-count and NPS measurements are not.

- **Position 31 diagnosis (task step 7): a measurable difference from position 30 exists, but "move-ordering-driven" is not established and the earlier dev-entry wording claiming it was has been corrected.** Comparing the two positions' depth-13 counters from the existing mechanism-run data (no new run needed):

  | Metric | Position 30 (strongly improved, -68.4%) | Position 31 (worsened, +198.0%) |
  |---|---|---|
  | Full-window re-search rate (`pvsFullWindowResearches / (pvsZeroWindowProbes + pvsFullDepthVerifications)`) | 1,741 / 6,404 = 27.2% | 5,189 / 12,186 = 42.6% |
  | `pvsFullDepthVerifications / lmrApplications` | 3,340 / 3,365,392 = 0.099% | 9,999 / 4,330,940 = 0.231% |
  | First-move cutoff rate | 95.4% | 94.2% |
  | Qnodes / main nodes (PVS) | 16,634,577 / 6,969,853 = 2.39 | 50,178,302 / 12,332,161 = 4.07 |
  | Qnodes / main nodes (baseline) | 77,078,838 / 22,030,846 = 3.50 | 9,767,769 / 4,138,610 = 2.36 |

  Position 31's full-window re-search rate is about 1.6x position 30's, and its LMR-verification-to-LMR-application ratio is about 2.3x higher -- both point at genuinely more "ambiguous" probe outcomes (null-window results landing inside the window rather than being decisively resolved) in this position specifically. **But first-move cutoff rate is nearly identical between the two (94.2% vs. 95.4%)**, which is the counter most directly indicative of move-ordering quality, and it shows no meaningful degradation for position 31. If poor move ordering were driving the elevated re-search rate, a materially lower first-move cutoff rate would be the expected signature; it isn't present. The qnodes/nodes ratio also flips direction between the two positions under PVS (down for position 30, sharply up for position 31), suggesting PVS's re-search pattern is leading into lines that are less settled (more capture-resolution-heavy) specifically in position 31's tree, which is at least as consistent with this position genuinely containing more closely-contested tactical alternatives as with an ordering defect. **Conclusion: position 31 has a real, measured elevation in re-search activity relative to position 30, but the available counters do not establish move-ordering quality as the specific cause; a stronger claim would need per-node move-ordering-quality tracing beyond this diagnostic's scope, and none was added** (adding one is not required to answer any of this diagnostic's correctness questions, per the task's own instruction against unnecessary new production counters).

- **Corrected the aggregate PVS-counter interpretation (task step 8).** The original 18.7% full-window-re-search-rate figure is numerically unchanged and still valid, but its prior description ("probes that survived their first check") conflated two different-depth stages: `pvsZeroWindowProbes` genuinely is the ordinary-sibling path's first check, while `pvsFullDepthVerifications` is already the LMR path's *second* stage (its first is `lmrApplications`, a two-orders-of-magnitude larger number). The corrected framing is inline above. Also stated explicitly, per the task's instruction: current counters cannot attribute any individual full-window re-search back to which path (ordinary-sibling vs. LMR-verification) produced it, since both increment the same `pvsFullWindowResearches` counter. No new counter was added to close this gap, since resolving it wasn't necessary to answer any of this diagnostic's correctness questions (position 31's diagnosis above did not require it either).

**Broke / Fixed:** None. No source file changed in this diagnostic session; only `dev-entries/phase-17.md` (this entry and two corrections to the prior entry) was touched.

**Measurements:**

`mvn -pl engine-core -am test -Dtest=PvsExperimentTest,SearchRegressionTest,NodeCountRegressionTest,SearcherTest,PerftHarnessTest`: 83 run, 5 failures (the same, already-known and already-explained `SearchRegressionTest`/`NodeCountRegressionTest` set), 0 errors -- unchanged from before this diagnostic, as expected, since no code changed.

**Status:** Diagnostic complete. No PVS correctness/control-flow bug was found that explains the four observed regressions -- they are demonstrated, isolated, legitimate consequences of selective-search (LMR and aspiration-window) tie-break sensitivity in positions this exact test suite's own multi-year history already documents as sensitive to unrelated changes for the same reason. One separate, real, and unresolved architectural risk was found and flagged (PV-node propagation during full-window re-searches, task step 6) but was not shown to cause any observed regression and does not block this step. Mechanism evidence from the prior entry stands as originally measured; it did not need to be rerun. **The implementation is declared ready for the native-Windows throughput gate** (issue #229 step 2), with the PV-propagation question carried forward as a named item to revisit before the strength (SPRT) gate specifically. Per the task's explicit instruction, execution stops here, before running that throughput gate.

---

### [2026-09-20] Phase 17 Step 2 — Throughput gate: WSL-side prep complete, native run blocked on tooling

**Built:**

- Verified `phase/17-pvs-experiment` HEAD matched the expected `2949db9`, local/origin in sync, working tree clean. Confirmed the diagnostic commit `2949db9` touched only `dev-entries/phase-17.md` and that `Searcher.java`/`SearchResult.java` have zero diff against the `25d3453` implementation commit -- the PVS source measured by the mechanism gate is exactly what a native run would measure now.
- Updated #230's checklist to mark Step 1 (mechanism) complete, with a summary of the passed result and the diagnostic's outcome.
- Added `tools/p17-2-native-throughput.ps1`: the native-Windows script for this step, following `tools/p16-2-native-baseline.ps1`'s exact protocol (process/environment evidence, git/build/environment record, one discarded `--bench-raw` warm-up, 7 measured repetitions, a per-position debug-logged run for position-level elapsed time) with two changes: it writes under `tools/results/p17-2/<timestamp>/` instead of `p16-2/`, and its presence check looks for the Phase 17 PVS counters (`pvsZeroWindowProbes`, `pvsFullDepthVerifications`) instead of the P16-1 TT fix, so it refuses to run on a tree that's lost the PVS implementation. The JFR attribution pass and clock-bound UCI sanity check from the P16-2 script were left out -- this narrower gate's own scope (issue #229 step 2's explicit ask list) doesn't call for either, and per the task's own instruction not to add instrumentation beyond what's needed, there was no reason to carry them over. Verified only via manual bracket-balance and structural review against the already-executed, already-verified P16-2 script it's derived from (no `pwsh` available in this session to parse-check directly) -- not executed, since running it requires the native terminal it exists to hand off to.

**Decisions Made:**

- **Native-Windows execution handed off as a script, not attempted from this session, for the same reason as P16-2.** This session runs inside WSL2 and has no way to control a native Windows terminal. WSL interop remains explicitly not accepted as a substitute for this project's authoritative wall-clock measurements (this project's own established convention, first stated in P16-2's dev-entries and unchanged since), so no attempt was made to approximate it via `powershell.exe` from WSL.
- **No throughput numbers reported or estimated in this entry.** Reporting a plausible-sounding number without having actually run the measurement would misrepresent evidence that doesn't exist yet.

**Broke / Fixed:** None. `tools/p17-2-native-throughput.ps1` is new and additive; no other file changed except #230's tracker state and this entry.

**Measurements:** None yet -- this is the hand-off, not the result.

**Status:** Phase 17 Step 2 (throughput) is NOT complete. Everything doable from WSL2 is done: the branch/commit state is verified, #230's Step 1 is marked complete, and `tools/p17-2-native-throughput.ps1` is the exact, ready-to-run hand-off, matching the P16-2 precedent. Once its output is available, this entry can be finished with the real 7-run elapsed/NPS/node data, the position 30 vs. 31 timing comparison, and the throughput-gate PASS/REJECT interpretation, and #229/#230 can be updated accordingly.

---

### [2026-09-20] Phase 17 Step 2 — Throughput gate: native run complete, PASS

**Built:**

- Read the native run at `tools/results/p17-2/20260920-065141/` (native Windows, RENEGADE, AMD Ryzen 7 7700X, JDK 21 Zulu, `phase/17-pvs-experiment` at commit `5fdd08b`). No benchmark was re-run; the captured artifacts were valid on inspection.
- Found and fast-forwarded to a new commit on `origin` (`5fdd08b`, "ignore Phase 17 throughput artifacts" -- adds `tools/results/p17-2/` to `.gitignore`, mirroring the existing `tools/results/p16-2/` convention) that had landed since this branch's previous commit (`debe3b1`) and was the commit the native run recorded. Confirmed it changes only `.gitignore`; `Searcher.java`/`SearchResult.java` remain identical to the `25d3453` implementation commit, so the native run measured exactly the PVS implementation the mechanism gate and diagnostic already characterized.
- **Environment validated as comparable to the frozen P16-2 baseline**: native Windows (`Win32NT`, `pwsh.exe` process path, not WSL interop), same machine (AMD Ryzen 7 7700X, 8c/16t), same JDK (Zulu OpenJDK 21.0.10), `Threads=1`, `Hash=16MB` (hardcoded), Classical evaluator, depth 13, identical bench-corpus SHA-256 (`02630596...b1`, matching P16-2's own recorded value exactly), fresh JAR built from the recorded commit.
- **Node-count determinism confirmed**: all 7 canonical runs and the discarded warm-up report exactly 48,892,339 main nodes -- matching the WSL mechanism-gate result to the node, and cross-validating WSL and native execution the same way P16-2 originally did for the pre-PVS baseline.
- Extracted all 7 elapsed/NPS pairs and computed statistics; extracted position 30 and 31's native elapsed time from the existing per-position `[BENCH]` debug line (no new instrumentation) in `06-per-position-debug.txt`.

**Measurements:**

| Run | Elapsed (ms) | NPS |
|---|---|---|
| 1 | 134,055 | 364,718 |
| 2 | 140,781 | 347,293 |
| 3 | 143,728 | 340,172 |
| 4 | 138,553 | 352,878 |
| 5 | 132,771 | 368,245 |
| 6 | 136,139 | 359,135 |
| 7 | 137,644 | 355,208 |

Elapsed: median 137,644 ms, mean 137,667 ms, sample stdev 3,798 ms, CV 2.76%, min 132,771 ms, max 143,728 ms (range 7.96% of the median).
NPS: median 355,208, mean 355,378, sample stdev 9,745, CV 2.74%, min 340,172, max 368,245 (range 7.90% of the median).

Both CVs (2.76%/2.74%) are wider than the pre-PVS baseline's own 0.84% NPS CV (P16-2 section 3). The node count is deterministic and identical across all 7 runs (48,892,339, section above), so the search shape itself does not vary run to run -- the wider spread cannot be attributed to re-search branching differing between runs, since it doesn't. The cause of the wider CV is not established here; it is left unexplained rather than assigned to a mechanism this data doesn't support. The spread does not affect the gate decision, which rests on the median, not the CV.

Baseline vs. PVS deltas:

| Metric | Pre-PVS (P16-2) | PVS (this run) | Delta |
|---|---|---|---|
| Main nodes | 73,089,246 | 48,892,339 | -33.11% |
| Qnodes | 226,653,985 | 133,023,392 | -41.31% |
| Combined (main+qnodes) | 299,743,231 | 181,915,731 | -39.31% |
| Median elapsed | 218,267 ms | 137,644 ms | **-36.94%** |
| Median reported NPS | 334,861 | 355,208 | +6.08% |

The median-reported-NPS delta is reported for completeness but is explicitly not the primary criterion: `nps` is main-nodes-per-elapsed-millisecond, and PVS changed the main/qnode work mix substantially (qnodes now 2.72x main nodes, versus 3.10x pre-PVS), so a change in that ratio alone can move the NPS number independent of whether the engine is actually faster. **Median elapsed wall-clock time, the primary criterion, improved by 36.94%.**

Position 30 vs. 31, native timing (matching the node counts already established in the mechanism-gate entry above):

| | Baseline (native) | PVS (native) | Delta |
|---|---|---|---|
| Position 30 nodes | 22,030,846 | 6,969,853 | -68.36% |
| Position 30 time | 69,483 ms | 19,316 ms | -72.20% |
| Position 31 nodes | 4,138,610 | 12,332,161 | +197.98% |
| Position 31 time | 9,928 ms | 34,933 ms | +251.86% |

Position 31's node expansion does translate into a real local wall-time regression (+25,005 ms), proportionally somewhat larger than the node increase alone (+197.98% nodes vs. +251.86% time), consistent with the elevated qnodes/main-node ratio already found for this position. No cause beyond what's measured here is claimed; this only answers the local-vs-aggregate magnitude question, not why position 31 behaves this way. That magnitude question has a clear answer: position 30 alone saves 50,167 ms, more than double what position 31 costs, and the full-suite median saving (80,623 ms) is about 3.2x position 31's added cost. Aggregate savings comfortably dominate.

**Decisions Made:**

- **Gate decision: PASS**, against the preregistered rule with no invented threshold. All three PASS conditions hold without qualification: the deterministic native node count matches the mechanism gate exactly (48,892,339), the environment is fully comparable to P16-2 (same machine, JDK, and configuration), and the aggregate fixed-depth wall-clock time materially improves (-36.94%, not a marginal or ambiguous figure). None of the INVESTIGATE/REJECT conditions apply: node counts are deterministic and matching, the environment is comparable, wall time improved rather than worsened, and no per-node overhead erased the tree-reduction benefit -- if anything, the benefit came through more than proportionally (NPS itself rose slightly rather than falling, despite the substantially different main/qnode mix).
- **#230's Step 3 description corrected** so the PV-node-propagation question found in Step 1's diagnostic is an explicit Gate 3 (correctness) prerequisite, not merely a pre-SPRT/Gate 4 item, per this step's instruction. That question is not resolved here.

**Broke / Fixed:** None. No source file changed; only the tracker (#229, #230) and this dev-entry.

**Status:** Phase 17 Step 2 (throughput) is complete and passed for the PVS candidate as it existed at this entry's commit (`c11b1ca`, node count 48,892,339). Steps 1 and 2 of Phase 17 are both done for that candidate. **This result no longer applies once the source changes** (see the Step 3 entry below, which repairs a correctness defect and produces a different candidate at `f9b152c`; Step 2 must be rerun natively for that candidate before Step 2 can be considered passed for it). Step 3 (formal correctness gate, now explicitly including the PV-node-propagation question) and Step 4 (strength/SPRT, blocked separately on #231's nightly-SPRT wording cleanup) remain open. #229 and #230 stay open; #231 untouched.

---

### [2026-09-20] Phase 17 Step 3, correctness gate: PV-node-propagation defect confirmed and repaired (Issues #229, #230)

**Built:**

- **Two documentation corrections made first, per this step's instructions.** (1) The prior throughput-gate entry attributed the wider PVS-vs-baseline NPS CV (2.76%/2.74% vs. baseline's 0.84%) to "a search shape carrying substantially more re-search/verification branching." That's wrong: node count is deterministic and identical across all 7 native runs (48,892,339), so the search shape does not vary run to run, and the wider CV cannot be attributed to branching that differs between runs. Corrected to state the cause is not established and is left unexplained. (2) The prior PV-propagation-risk paragraph claimed the effect "can only cause a move to be underestimated, never fabricate a better-than-true score." That claim was broader than what had actually been checked (it hadn't traced the interaction with negamax's sign flip, the fail-soft bound returned to the caller, or how the caller's TT-store treats a bound produced this way). Corrected to state only the specific failure mode considered so far (horizon-concentrated underestimation), without the "can only / can never" generalization.

- **`isPvNode` usage audit**, direct source read of `Searcher.alphaBeta()`/`searchRoot()` (no tool searches which pruning gates read `isPvNode`; all four use sites plus the two `childIsPvNode` derivations were read end to end):
  - `pvNodeEvals`/`cutNodeEvals` bookkeeping (line ~960): pure instrumentation, no effect on search result.
  - `canApplyRazoring` (line ~979): skipped when `isPvNode`. Depth-gated to 1-2 (`depth > 2` returns false).
  - `canApplyFutilityPruning` (line ~1113): skipped when `isPvNode`. Margin table (`getFutilityMarginForDepth`) is nonzero only at depth 1-2 (`default -> 0`), so this is likewise a depth-1-2-only gate.
  - `canPruneLosingCapture` (line ~1100): explicit `depth <= 2 && !isPvNode` condition.
  - Everything else that shapes the search or the returned score does **not** depend on `isPvNode`: `canApplyLmr`, `canApplyNullMove`, `canAttemptSingularity`, `applyTtBound`, and `resolveBound` (the TT bound classification, `EXACT`/`UPPER`/`LOWER` from `bestScore` vs. `alphaOrig`/`beta`) all take no `isPvNode` parameter. The PV table (`pvTable`/`pvLength`) is built unconditionally on `score > bestScore` at every node, not gated by `isPvNode`. "PV node" in the classical alpha-beta sense (a node reached with `beta - alpha > 1`, deserving an exact search) is a different thing from "a node that happens to sit on the eventual best line," and only the former is what `isPvNode` controls.
  - `canApplyLmr`'s own gate (`moveIndex >= 4`) proves the two `childIsPvNode = isPvNode && moveIndex == 0` full-window-re-search sites (inside the LMR branch and inside the `isPvNode && moveIndex > 0` ordinary-sibling branch) can never legitimately see `moveIndex == 0`, since both branches require `moveIndex > 0` to be reached at all (the LMR branch via `canApplyLmr`'s own depth/moveIndex/quiet gates in practice always firing at moveIndex >= 4, the ordinary-sibling branch via its own explicit `moveIndex > 0` guard). So the `&& moveIndex == 0` conjunct at those two re-search sites was always evaluating to `false`, unconditionally, regardless of the enclosing node's actual PV status.
  - The same defect exists at the root: `searchRoot()`'s `childIsPvNode = rootMoveIndex == 0` (computed once, before the branch) is reused unchanged at the later-root-move full-window re-search call site, which only runs when `rootMoveIndex > 0` by construction, so it too always passed `false`, despite the adjacent comment already stating "root is always the PV node."

- **Control-flow matrix** (window / depth / intended child PV status / selective pruning allowed / bound semantics), derived from the audit above, not assumed from the pre-existing expression:

  | Case | Window | Depth | Intended child PV? | Pruning allowed at child? | Bound semantics |
  |---|---|---|---|---|---|
  | A. First move at PV node | Full (`-beta,-alpha`) | Full | Yes | No (razor/futility/losing-capture skipped) | Exact-seeking |
  | B. Later PV sibling, null-window probe | Null (`-(alpha+1),-alpha`) | Full | No | Yes | Bound (scout) |
  | C. Later PV sibling, full-window re-search after `alpha<score<beta` | Full (`-beta,-alpha`) | Full | **Yes** (was `false`) | No | Exact-seeking |
  | D. LMR reduced null-window probe | Null | Reduced | No | Yes | Bound (scout) |
  | E. LMR full-depth null-window verification | Null | Full | No | Yes | Bound (scout) |
  | F. LMR full-window re-search after verified `alpha<score<beta` | Full (`-beta,-alpha`) | Full | **Yes** (was `false`) | No | Exact-seeking |
  | G. Non-PV node (any move) | Null (inherited) | Full/reduced | No | Yes | Bound (scout) |

  Rows C and F are the two sites this step corrects. Both are only reachable when the enclosing node's own `isPvNode` is `true` and the returned probe score strictly beat the current `alpha`, meaning the search has just found a genuine improvement and is being asked for that move's exact value, the same thing row A's recursion is asked for. There is no `moveIndex == 0` condition anywhere in the classical PVS description of this step; that conjunct was carried over unmodified from the two-way `if/else` this experiment replaced, where it happened to be harmless (the `else` branch used to be reached at `moveIndex == 0` too, so the expression was never exercised at a value other than `isPvNode` itself).

- **Verdict: pre-repair propagation was a correctness defect, not a stylistic or intentional choice.** By the criterion given for this step: if the full-window re-search is meant to obtain the exact value of a later move, and `isPvNode` intentionally suppresses pruning that would corrupt that intent, then routing that recursion through `isPvNode=false` lets razoring/futility/losing-capture pruning skip moves inside a search whose whole purpose is to search them exactly. This is real and was exercised far more than the pre-Phase-17 code: the old LMR-only fail-high re-search hit this same expression, but rarely (the old-candidate mechanism run's `pvsFullWindowResearches` was 14,473 out of the full 31-position suite at depth 13); Phase 17's general PVS generalized the same expression to every later PV sibling, not just LMR fail-highs. "Pre-existing" does not make it correct, per this step's explicit instruction; nothing in the architecture (TT bound resolution, PV table construction) depends on or corrects for this, so there is no compensating mechanism elsewhere that makes `isPvNode=false` here harmless.

- **Empirical confirmation, before any source change**, using `pvNodeEvals` as a hard-to-fake witness (it only increments when a node is entered with `isPvNode=true`, `Searcher.java` line ~964): built the current (pre-repair) tree, ran `searchDepth` on the existing `PvsExperimentTest` middlegame FEN at depth 6 and depth 8, recorded `pvNodeEvals`/`cutNodeEvals`/`pvsFullWindowResearches`/`nodesVisited`; then `git stash` on `Searcher.java` only (isolating exactly this one file, same JVM/JDK, same classpath) to get the pre-repair numbers for direct comparison; then `git stash pop` to restore. Pre-repair: depth 6 `pvNodeEvals=15`, `nodesVisited=5184`; depth 8 `pvNodeEvals=28`, `nodesVisited=20720`. Post-repair (identical position/depth): depth 6 `pvNodeEvals=33`, `nodesVisited=5210`; depth 8 `pvNodeEvals=101`, `nodesVisited=27192`. `pvNodeEvals` roughly doubles to triples at this position purely from correcting the two `childIsPvNode` expressions, with no other code path touched.

- **Repair applied**, exactly as scoped: only the two `childIsPvNode` derivations at rows C and F, plus the identical root-level site, change; nothing else in the move loop, LMR gating, PVS windows, re-search trigger conditions, move ordering, or pruning formulas is touched:
  - `Searcher.alphaBeta()`, LMR branch (row F, formerly `boolean childIsPvNode = isPvNode && moveIndex == 0;` before the stage-3 full-window re-search) -> `boolean childIsPvNode = isPvNode;`, with a comment explaining the `moveIndex == 0` conjunct is vacuous at this call site (`canApplyLmr` requires `moveIndex >= 4`).
  - `Searcher.alphaBeta()`, ordinary-later-PV-sibling branch (row C, same expression before its full-window re-search) -> the same `boolean childIsPvNode = isPvNode;` fix, with a comment pointing at the enclosing `else if (isPvNode && moveIndex > 0)` guard as the reason `isPvNode` alone is correct here.
  - `Searcher.searchRoot()`, later-root-move full-window re-search call site: the reused `childIsPvNode` local (computed as `rootMoveIndex == 0` before the branch, correct only for the first-move case at line ~811) is replaced with the literal `true` at the re-search call site only, matching the existing adjacent comment that root is always the PV node.

- **Regression test added**: `PvsExperimentTest.laterPvSiblingFullWindowResearchGetsPvNodeSemantics`, using the exact `pvNodeEvals` measurements above as thresholds (`>= 20` at depth 6, sitting strictly between the measured 15/33; `>= 60` at depth 8, sitting strictly between the measured 28/101). This test fails under the pre-repair expression and passes under the corrected one, verified directly (not asserted) by running it against both trees during the `git stash` comparison above.

- **Gate 1 (mechanism) rerun on the repaired candidate**, since the source changed and the previously-measured 48,892,339-node result no longer characterizes what would ship. Same canonical protocol as the original mechanism gate (`--bench-raw 13`, `tools/logback-debug.xml`, WSL, all 31 positions reaching depth 13):

  | Metric (depth 13, 31-position suite, aggregate) | Pre-PVS baseline | PVS (pre-repair) | PVS (repaired) | Repaired vs. pre-PVS | Repaired vs. pre-repair PVS |
  |---|---|---|---|---|---|
  | Main nodes | 73,089,246 | 48,892,339 | 40,878,283 | -44.06% | -16.39% |
  | Quiescence nodes | 226,653,985 | 133,023,392 | 100,113,245 | -55.83% | -24.74% |
  | Beta cutoffs | 61,398,676 | 41,410,058 | 34,586,121 | -43.66% | -16.48% |
  | TT hits | 8,190,621 | 6,427,137 | 5,925,534 | -27.64% | -7.80% |
  | LMR applications | 27,422,470 | 21,909,287 | 19,116,222 | -30.30% | -12.75% |
  | Null-move cutoffs | 737,269 | 767,573 | 700,624 | -4.98% | -8.72% |
  | Futility skips | 26,456,763 | 19,497,950 | 16,192,755 | -38.79% | -16.95% |
  | Delta-pruning skips | 23,016,259 | 15,567,523 | 13,119,373 | -43.00% | -15.73% |
  | `pvsZeroWindowProbes` | N/A | 48,884 | 1,067,268 | N/A | +2,082.98% |
  | `pvsFullDepthVerifications` | N/A | 28,401 | 10,111 | N/A | -64.40% |
  | `pvsFullWindowResearches` | N/A | 14,473 | 38,452 | N/A | +165.68% |

  Completed depth: all 31 positions reached depth 13 (confirmed via the `X/31 ... depth=13` progress lines), matching the fixed-depth protocol; total run reported `Nodes searched: 40878283`, matching the per-position sum exactly.

  **The `pvsZeroWindowProbes` explosion (48,884 -> 1,067,268) is the direct, expected mechanical signature of the repair, not a separate anomaly**: before the repair, a full-window re-search's child was incorrectly demoted to `isPvNode=false`, so *its own* later moves fell into the generic non-PV branch (row G) instead of the ordinary-PV-sibling branch (row B) that increments this counter. With the repair, correctly-PV subtrees stay correctly-PV one level deeper, so far more of the tree now takes the row-B probe path instead of row G. Combined re-search rate over the two stage-2 populations: `38,452 / (1,067,268 + 10,111) = 3.57%`, well short of "most," so the pathological-re-search-rate stop rule does not trigger. This rate is also *lower* than the pre-repair candidate's 18.7% (the same limitation on attributing individual re-searches to a specific path noted in the earlier diagnostic entry still applies; no new counter was added).

  Per-position deltas, repaired vs. pre-repair PVS candidate (all 31 positions; only the five largest moves in each direction shown, since the full 31-row table would just repeat the earlier per-position table's shape with a second column):

  | Pos | Pre-repair PVS nodes | Repaired PVS nodes | Delta | % |
  |---|---|---|---|---|
  | 31 | 12,332,161 | 4,049,243 | -8,282,918 | -67.17% |
  | 15 | 1,433,754 | 544,133 | -889,621 | -62.05% |
  | 11 | 1,589,933 | 885,311 | -704,622 | -44.32% |
  | 8 | 1,058,139 | 615,198 | -442,941 | -41.86% |
  | 21 | 249,659 | 168,044 | -81,615 | -32.69% |
  | 14 | 228,277 | 293,812 | +65,535 | +28.71% |
  | 9 | 1,941,084 | 3,192,392 | +1,251,308 | +64.46% |
  | 16 | 620,876 | 1,176,303 | +555,427 | +89.46% |
  | 19 | 761,651 | 2,326,456 | +1,564,805 | +205.45% |
  | 10 | 300,687 | 1,338,696 | +1,038,009 | +345.21% |

  **Position 31, the position flagged in both the mechanism-gate and throughput-gate entries as an unexplained node-count/timing outlier for the pre-repair PVS candidate, is now the single largest improvement in the whole suite**, dropping 67.17% under the repair. This was not predicted going into this step; it falls directly out of fixing the propagation defect, and is reported as an observation, not a re-litigation of the earlier "not move-ordering-driven" finding (which remains true of the pre-repair candidate this comparison is measured against). No further causal claim is made about *why* position 31 specifically benefits this much; that would need its own investigation and is out of this step's scope.

  Some positions (10, 19, 16, 9, 14) genuinely cost more nodes under the repair. This is expected, exactly the mechanism the audit predicted: these are subtrees where the repair correctly stops pruning that used to fire, so more of the tree is explored there. The aggregate is still a net decrease (-16.39% vs. the pre-repair PVS candidate, -44.06% vs. the pre-PVS baseline), so neither the "nodes increased" nor "nodes unchanged" rejection branches of the frozen stop rule apply. **Gate 1 (mechanism) on the repaired candidate: PASS.**

- **The nine stale regression expectations re-probed under the repaired candidate** (five `NodeCountRegressionTest` parameter goldens plus four `SearchRegressionTest` expected moves, across two regression suites), per this step's instruction to repeat probes rather than reuse the pre-repair diagnostic's conclusions, since the repair is itself a search-shape change:
  - `NodeCountRegressionTest`: the assertion loop asserts FEN-by-FEN and stops at the first failure, so fixing FEN[0]'s golden repeatedly unmasked further drifted goldens underneath it: FEN[1], FEN[2], FEN[3], and FEN[4] all drifted too, confirmed one at a time by re-running the full suite after each fix (not assumed from a single run). Final goldens: FEN[0] (startpos) 15362 -> 15349, FEN[1] (K+P vs K) 1192 -> 1242, FEN[2] (middlegame) 43425 -> 57103, FEN[3] (rook endgame) 9938 -> 7592, FEN[4] (middlegame) 8393 -> 13798. Direction is position-dependent (three decreases, two increases), consistent with the mixed per-position mechanism-gate deltas above.
  - `SearchRegressionTest.bestMoveIsStable`, direct-API depth probes (`Searcher.searchDepth`, matching the test's own code path exactly (not the UCI jar, which was found to diverge from the direct API's shallow-depth output for at least one of these positions during this step, and was discarded in favor of the test's actual invocation) at depths 8/10/12/14 for each of the four positions:
    - **P5** (`8/8/3k4/8/1PP5/8/8/2K5 w - - 0 1`): depth 8/10 `b4b5`, depth 12/14 `c4c5`. `b4b5` is already a long-documented member of this position's five-way equivalence class (`c1c2`, `c1d2`, `c1b2`, `c4c5`, `b4b5` all win; the test's own comment history has cycled through all five over past phases). Golden updated `c1d2` -> `b4b5`.
    - **P10** (`8/8/8/4k3/4P3/4K3/8/8 w - - 0 1`): depth 8/10/12/14 all `e3d3`, stable. Already documented as provably mirror-symmetric-equivalent to the prior golden `e3f3`. Golden updated `e3f3` -> `e3d3`.
    - **E1** (`4k3/8/8/8/8/8/8/4KQ2 w - - 0 1`, KQ vs. K): depth 8 `e1d2`, depth 10 `f1f6`, depth 12 `f1b5`, depth 14 `f1f6`; it does not stabilize. This is expected for a trivially won KQK position where nearly every reasonable move wins and the search has no single deeper "true" answer to converge to (the test's own comment history already shows this position's preference has flip-flopped between `f1f6`, `f1d3`, and `f1b5` across multiple unrelated eval-tuning phases for exactly this reason). `e1d2` is a legal, standard king-centralization move toward the KQK mate. Golden updated `f1b5` -> `e1d2`.
    - **E5** (`8/4k3/8/4P3/8/8/R7/4K3 w - - 0 1`, rook + connected passer vs. lone king): depth 8 `a2a6`, depth 10/12 `a2e2`, reverting toward the prior golden rather than confirming the new depth-8 value, unlike the other three. This is the one case in this batch with no pre-existing equivalence-class documentation to lean on, so it was checked directly: `a2a6` does not hang the rook or obstruct the pawn's promotion path, and the position remains comfortably winning either way. Golden updated `a2e2` -> `a2a6`, with a new comment (this position's first) documenting that `a2e2` reappears at depth >= 10 and that the two moves' relative merit at exactly depth 8 is search-shape-dependent, the same pattern already established for P5/P10/E1.
  - All four `bestMoveIsStable` cases and all five `NodeCountRegressionTest` FENs pass after the golden updates above; no bug was found in any of the five, consistent with this being a real, intentional search-shape change (the correctness repair), not a regression.

- **PV legality**: `PvsExperimentTest.principalVariationMovesAreAllLegalInSequence` (4 parameterized FENs, replays the PV move-by-move on a fresh `Board`, non-vacuous: each PV is confirmed non-empty before replay) passes unchanged against the repaired candidate. No change was needed to this test or to the PV-table construction code, which the audit above already established does not depend on `isPvNode`.

- **Full relevant Maven suite, final state**: `mvn -pl engine-core,engine-uci,engine-tuner -am test` -> `BUILD SUCCESS`. `engine-core`: 400 run, 0 failures, 0 errors, 5 skipped (pre-existing). `engine-uci`: 37 run, 0 failures, 0 errors, 8 skipped (pre-existing). `engine-tuner`: 131 run, 0 failures, 0 errors, 1 skipped (pre-existing). All move-generation/legality suites, `PvsExperimentTest` (now 10 cases including the new regression test), `SearchRegressionTest`, and `NodeCountRegressionTest` are green.

**Decisions Made:**

- **`isPvNode=false` on a later-PV-sibling/LMR-verified full-window re-search is confirmed a correctness defect and repaired**, not left as a documented risk. The criterion from this step's instructions is met exactly: the full-window re-search exists to obtain a later move's exact value, `isPvNode` is the mechanism that suppresses pruning specifically to protect exact-value searches, and the pre-repair code routed this exact recursion through the pruning-permitting path. "Pre-existing" (the same expression existed, rarely-exercised, before Phase 17) is explicitly not treated as evidence of correctness, per instruction.
- **The repair is scoped to exactly three call sites** (two in `alphaBeta`, one in `searchRoot`), each a one-line change from `isPvNode && moveIndex == 0` (or the root's reused `rootMoveIndex == 0` local) to `isPvNode` (or literal `true` at root). No LMR eligibility, reduction formula, PVS window, re-search trigger, move ordering, or pruning formula was touched, matching this step's explicit constraints.
- **Gate 1 was revalidated on the repaired candidate rather than reusing the pre-repair result**, since the source changed. It passes, with an even larger main-node reduction than the pre-repair candidate (-44.06% vs. pre-PVS, vs. -33.11% pre-repair).
- **Gate 2 (native throughput) is not rerun in this session.** The previously-recorded native result (48,892,339 nodes, -36.94% median elapsed) measured the pre-repair candidate, which is a materially different tree (main nodes differ by 16.39%) from the repaired candidate this step produces. That number no longer characterizes what would ship. Per this step's instruction, the native rerun is handed off rather than run here (this session has no native Windows execution capability, and WSL interop remains explicitly not an accepted substitute per this project's own P16-2 convention); `tools/p17-2-native-throughput.ps1` needs no changes to run against the repaired candidate (its presence check only looks for the PVS counters, which the repair keeps).
- **Step 3 is not marked complete.** Per this step's own framing, Step 3 cannot close until the candidate measured for throughput is the same candidate that passed correctness, and that native rerun has not happened yet. Gate 1/correctness work on the repaired candidate is done and green; Gate 2 on the repaired candidate is outstanding.

**Broke / Fixed:**

- Fixed: the `isPvNode`-propagation correctness defect described above, at all three call sites.
- Broke (expected, not a regression): the same nine pre-existing expected values (five `NodeCountRegressionTest` parameter goldens, four `SearchRegressionTest` expected moves) flagged by the original mechanism-gate commit drift again, for the same underlying reason (a real, intentional search-shape change), now re-verified and re-updated against the repaired candidate specifically rather than carried over from the pre-repair diagnostic.

**Measurements:** See the Gate 1 rerun table, per-position delta table, and `pvNodeEvals` before/after figures above.

**Status:** Gate 3 correctness evidence is complete and passes for the repaired candidate: the PV-node-propagation question is resolved (was a defect, is now repaired), Gate 1 is revalidated and passes on the repaired candidate, the full relevant test suite is green, all nine stale expected values (five `NodeCountRegressionTest` parameter goldens, four `SearchRegressionTest` expected moves) are re-verified and updated with evidence, and PV legality holds. Phase progression is blocked, not Gate 3 itself: what remains is exactly one native-Windows action, rerunning `tools/p17-2-native-throughput.ps1` (or the equivalent canonical protocol) against this branch's current HEAD, to produce a throughput number that actually characterizes the repaired candidate. The previously-recorded native result (48,892,339 nodes, 137,644 ms median) measured the pre-repair candidate and does not apply here. Step 3 is not marked complete on the tracker until that rerun confirms Gate 2 for this exact candidate, and Step 4 (SPRT, also separately blocked on #231) does not start before that. Do not run SPRT before that rerun.

---

### [2026-09-20] Phase 17 Step 2 rerun, native throughput on the repaired candidate: PASS (Issues #229, #230)

**Built:**

- **Native run executed** at `tools/results/p17-2/20260920-082614/` against HEAD `b50d72d` (docs-only on top of the repair commit `f9b152c`; source identical to `f9b152c`). Same protocol as the prior throughput gate and P16-2: `tools/p17-2-native-throughput.ps1`, native PowerShell 7 (`C:\Program Files\PowerShell\7\pwsh.exe`, not a WSL-mounted path), AMD Ryzen 7 7700X, JDK Zulu 21.0.10, `Threads=1`, `Hash=16MB` (hardcoded in `BenchRunner`), Classical evaluator, depth 13, the same 31-position `BenchRunner.BENCH_FENS` corpus (source SHA-256 unchanged from prior runs since `BenchRunner.java` was not touched), 1 discarded warm-up plus 7 measured `--bench-raw` repetitions, plus one per-position debug-logged run.

- **Determinism confirmed**: warm-up and all 7 measured runs report exactly `40878283` main nodes, matching the repaired candidate's WSL mechanism-gate result exactly. The per-position debug run's summed node total (`40878283`) and summed qnodes (`100113245`) also match the WSL mechanism-gate rerun's aggregates exactly, cross-validating WSL and native for the repaired candidate the same way P16-2 originally did for the pre-PVS baseline.

- **Seven measured runs:**

  | Run | Elapsed (ms) | NPS |
  |---|---|---|
  | 1 | 115,150 | 355,000 |
  | 2 | 120,275 | 339,873 |
  | 3 | 120,162 | 340,193 |
  | 4 | 122,249 | 334,385 |
  | 5 | 121,176 | 337,346 |
  | 6 | 121,101 | 337,555 |
  | 7 | 120,860 | 338,228 |

  Elapsed: median 120,860 ms, mean 120,139 ms, sample stdev 2,305 ms, CV 1.92%, min 115,150 ms, max 122,249 ms.
  NPS: median 338,228, mean 340,369, sample stdev 6,730, CV 1.98%, min 334,385, max 355,000.

- **Three-candidate comparison** (fixed-depth elapsed remains the primary criterion; NPS is descriptive only, since the main/qnode work mix differs across all three candidates):

  | Metric | Pre-PVS baseline | Pre-repair PVS | Repaired PVS | Repaired vs. pre-PVS | Repaired vs. pre-repair PVS |
  |---|---|---|---|---|---|
  | Main nodes | 73,089,246 | 48,892,339 | 40,878,283 | -44.07% | -16.39% |
  | Quiescence nodes | 226,653,985 | 133,023,392 | 100,113,245 | -55.83% | -24.74% |
  | Median elapsed | 218,267 ms | 137,644 ms | 120,860 ms | -44.63% | -12.19% |
  | Median NPS (descriptive) | 334,861 | 355,208 | 338,228 | +1.01% | -4.78% |

  Qnodes for the repaired candidate come from the per-position debug run's own summed counters (not invented): 100,113,245, identical to the WSL mechanism-gate rerun's figure for the same candidate.

- **Position 30/31, native timing, all three candidates:**

  | Metric | Pre-PVS | Pre-repair PVS | Repaired PVS | Repaired vs. pre-PVS | Repaired vs. pre-repair PVS |
  |---|---|---|---|---|---|
  | Position 30 nodes | 22,030,846 | 6,969,853 | 7,507,078 | -65.93% | +7.71% |
  | Position 30 time | 69,483 ms | 19,316 ms | 22,279 ms | -67.94% | +15.34% |
  | Position 31 nodes | 4,138,610 | 12,332,161 | 4,049,243 | -2.16% | -67.17% |
  | Position 31 time | 9,928 ms | 34,933 ms | 10,127 ms | +2.00% | -71.01% |

  **Position 31, the former pathological outlier, is no longer an outlier at all under the repaired candidate.** Its node count and wall time both land within about 2% of the pre-PVS baseline itself, a position this candidate had never regressed relative to before Phase 17 touched it. This is a materially different result from "shrinks": relative to the pre-PVS baseline, the elevation the pre-repair PVS candidate showed here (+197.98% nodes, +251.86% time) is gone. No claim is made that the isPvNode repair was the sole cause of this specific position's behavior; what the native data establishes is that the repaired candidate's position 31 numbers are close to the pre-PVS baseline's own numbers, which the pre-repair candidate's were not.

  Position 30 moved in the other direction, a small real increase relative to the pre-repair PVS candidate (+7.71% nodes, +15.34% time), consistent with the WSL mechanism-gate rerun already having shown mixed per-position deltas under the repair (some subtrees see less pruning and more nodes as a direct, expected consequence of the fix). Position 30 remains dramatically better than the pre-PVS baseline either way (-65.93% nodes, -67.94% time).

- **Gate 2 decision: PASS.** Deterministic node count is exactly 40,878,283 across the warm-up and all 7 measured runs. The environment is fully comparable to every prior gate in this phase (same machine, same JDK, same protocol). Aggregate fixed-depth wall time shows no regression at all, let alone an unexplained one: median elapsed improves against both the pre-PVS baseline (-44.63%) and the pre-repair PVS candidate (-12.19%). No percentage threshold was invented; the result does not require one, since it improves against both prior candidates rather than sitting in an ambiguous zone.

**Decisions Made:**

- **Gate 2 now passes for the same candidate that Gate 1 and Gate 3 already passed for (`f9b152c`/`b50d72d`).** Per the framing carried over from the prior two entries, Step 3 (correctness) can now be marked complete on the tracker, since its only blocker was exactly this rerun.
- **#230 updated**: Step 1's wording is corrected so the authoritative repaired-candidate mechanism result (73,089,246 -> 40,878,283, -44.07%) is what the checklist states, with the original pre-repair figure (73,089,246 -> 48,892,339, -33.11%) kept only as historical context inside the entry, not as the current claim. Steps 1, 2, and 3 are all marked complete. Step 4 (strength/SPRT) remains open, still separately blocked on #231's nightly-SPRT wording cleanup, and is not started here.
- **#229 updated** with the full native evidence above.

**Broke / Fixed:** None. No source file changed; only the tracker (#229, #230) and this dev-entry.

**Measurements:** See the seven-run table, three-candidate comparison, and position 30/31 table above.

**Status:** Phase 17 Steps 1, 2, and 3 are all complete and passed for the repaired candidate (`f9b152c`/`b50d72d`). Step 4 (strength/SPRT) remains open, blocked on #231. No SPRT has been run.

---

### [2026-09-20] Phase 17 Step 4 preparation, SPRT infrastructure qualified and frozen, no game played (Issues #229, #230, #231)

**Built:**

- **Existing SPRT infrastructure audited**, per this step's instruction not to assume `nightly-sprt.yml` fits Phase 17 just because it already runs SPRT:
  - `.github/workflows/nightly-sprt.yml`: checks out `develop` (not this phase's branch), downloads the latest GitHub release JAR as baseline (not a frozen commit tied to this phase), TC 5+0.05, 1000 games, `-repeat`, concurrency 2, `-sprt elo0=0 elo1=50 alpha=0.05 beta=0.05`, no `-openings` argument at all (every game starts from the same position, startpos, with only color alternation via `-repeat` for variety), no explicit `option.Hash=`/`option.Threads=` for either engine (both run whatever `UciApplication`'s UCI defaults are), runs on GitHub-hosted `windows-latest`. On `H0_ACCEPTED` it reports "regression detected" / "regression confirmed" in both the step summary and a CI-failing `Write-Error`.
  - `tools/sprt.ps1`: a general-purpose, already-working match runner. Accepts arbitrary `-New`/`-Old` JAR paths, `-EngineThreads` (applied identically to both engines via `option.Threads=`), `-TC`, `-Elo0`/`-Elo1`/`-Alpha`/`-Beta`, `-MaxGames`, `-OpeningsFile` (auto-detects `tools/noob_3moves.epd` if present), `-NewOptions`/`-OldOptions` for arbitrary per-engine UCI options (e.g. `Hash=16`), and `-repeat`/`-recover`/adjudication settings hardcoded sensibly. Does not compute or log JAR SHA-256, does not check git/source state at all (a caller can point it at any two JARs from any commits), does not verify the opening file's content (only its presence), and does not interpret SPRT verdicts (it tees raw cutechess-cli output; the "regression confirmed" problem lives entirely in `nightly-sprt.yml`, not here).
  - `docs/sprt-guidelines.md` section 1, "Standard SPRT Usage (single change)": documents `H0=0, H1=50, alpha=0.05, beta=0.05, TC=5+0.05` as this project's own general convention for testing any single isolated change, independent of Phase 16/17. `tools/README.md`'s SPRT section repeats the same bounds and confirms `tools/sprt.ps1`'s command shape; it also notes explicitly that `Hash` and any `-NewOptions`/`-OldOptions` are not logged anywhere by the script, so recovering them after a run depends on the caller's own shell history.
  - `docs/architecture/research/phase16-p16-3-intervention-preregistration.md` section 7 stage 4 and section 8's stop rule are the actual governing contract: "one isolated, same-baseline SPRT against the current `Threads=1` build. No Elo expectation is predeclared" and "proceed to exactly one isolated SPRT with frozen terms (elo0/elo1/alpha/beta fixed before the run starts, matching this project's existing SPRT convention)". Section 9 of that same document is where this session's earlier work first flagged the `nightly-sprt.yml` wording problem (later filed as #231).
  - `tools/noob_3moves.epd`: 150,932 lines, 9.4 MB, SHA-256 `2011193b4854e9a8cfdc05312ca2dbaffa6ceae3abbdee20e2ead2a18a603347`, one FEN per line (a pre-diversified position pool, not raw PGN game text). `git ls-files` and `git log -- tools/noob_3moves.epd` both return nothing for it; `.gitignore` line 72 explicitly excludes it. It is a real, usable file that happens to already exist on this session's own filesystem (and presumably on whichever machine has run every prior `tools/results/sprt_*` match in this repo's history), but it is not tracked, has no documented origin or regeneration script anywhere in the repository, and no prior SPRT run's evidence in `tools/results/` records its hash. This is a real, pre-existing measurement-infrastructure gap, not something Phase 17 introduced.

- **#231 fixed**, per this step's precision requirements. The issue's own body (and the P16-3 preregistration passage it quotes) described H0's meaning as "true Elo is approximately 0 or below," a composite-interval framing. Cutechess's `-sprt elo0=X elo1=Y` implements a standard sequential likelihood-ratio test between two *simple point* hypotheses (elo=0 vs. elo=50); accepting H0 means the accumulated log-likelihood ratio favored the elo0 point over the elo1 point, not that the true Elo has been shown to lie anywhere in an interval. `.github/workflows/nightly-sprt.yml` corrected accordingly:
  - The step-summary block's three verdict lines were rewritten to state, for H1: "evidence favors the elo1=50 hypothesis over the elo0=0 hypothesis under this SPRT", not "patch improves strength, safe to merge"; for H0: "evidence favors the elo0=0 hypothesis over the elo1=50 hypothesis... this does not establish that the candidate is weaker than baseline, only that a +50 Elo gain was not demonstrated... per project policy a demonstrated +50 Elo gain is required to merge, so this build does not meet that bar. Investigate before merging; do not read this as a confirmed regression"; for inconclusive: "neither the elo0 nor the elo1 boundary was reached before the game cap."
  - The `Fail on regression` step was renamed `Fail on unmet merge bar (H0 accepted)`, and its `Write-Error` text rewritten to the same precise framing: the CI failure is now explicitly attributed to project policy (a demonstrated +50 Elo gain is required to merge), not reported as a confirmed regression. **The CI-failing behavior itself is unchanged.** An `H0_ACCEPTED` verdict still fails the nightly workflow, since that remains a legitimate policy choice; only the wording describing why it fails changed.
  - Not modified: `docs/sprt-guidelines.md`'s own already-reasonably-precise wording ("no improvement detected; investigate"), and `tools/sprt.ps1` itself, which does not interpret verdicts at all and was never the source of this problem.

- **Candidate identity resolved**: `phase/17-pvs-experiment` at commit `e6afbb1` (docs-only on top of the isPvNode-propagation repair `f9b152c`; confirmed search-source-identical via `git diff f9b152c..e6afbb1 -- engine-core/.../search/`, empty). This is the exact candidate that already passed Gates 1-3.

- **Baseline identity resolved, not guessed**: commit `ebe513e`, the `develop` merge commit Phase 17 itself branched from, and the same commit whose search source produced every "pre-PVS baseline" figure already used throughout this phase's Gate 1 and Gate 2 evidence (confirmed search-source-identical to P16-2's own measured commit `31e2243` via an empty `git diff`). Explicitly **not** `nightly-sprt.yml`'s latest-GitHub-release convention, which serves an unrelated purpose (guarding `develop` release-to-release) with no documented tie to this phase's "same baseline" requirement. Using the release JAR would have silently substituted a different, undocumented baseline for the one this entire phase has already compared every gate against.

- **Opening-suite status**: a suitable corpus exists (`tools/noob_3moves.epd`, detailed above) but is not tracked and has no documented origin. Per this step's instruction not to invent or silently depend on an untracked file, its identity is instead frozen by SHA-256 in the new preregistration document and enforced by a refusal check in the new runner script (below); a follow-up issue proposing the smallest reproducible fix (commit a compressed copy, or a script that regenerates an equivalent public corpus) is recorded as a separate, out-of-scope measurement-infrastructure decision, not filed as part of this task.

- **Pairing semantics**: `-openings file=tools/noob_3moves.epd format=epd order=random plies=4` selects a random pre-diversified position per game; `-repeat` (already present in both `nightly-sprt.yml` and `tools/sprt.ps1`) plays each selected opening twice, once with each engine as White, giving paired colors from identical starting positions as this step required.

- **Execution-host decision**: native Windows, non-WSL, is required (matching CLAUDE.md section 6's "SPRTs run on native Windows only, never WSL"), but the *same physical* Ryzen 7 7700X used for the throughput gate is not required for strength testing, and this document says so explicitly with its own reasoning rather than silently inferring it from the throughput rule: the throughput gate's hardware requirement exists because wall-clock nodes-per-second is directly hardware-sensitive, while a paired-color SPRT measures game outcomes, and both engines in a given game always run on whichever single host is playing it (cutechess-cli starts both processes locally), so a different native host does not reintroduce a hardware asymmetry between the two engines being compared.

- **Engine settings frozen explicitly rather than left at defaults**: Threads=1 for both (this is the Threads=1 experiment's substrate), Hash=16 MB for both via explicit `option.Hash=16` on each side (the UCI default is 64 MB; no Phase 16/17 document freezes a strength-testing Hash value, so 16 MB was chosen for consistency with the rest of this phase's evidence and documented as such, not silently inherited).

- **SPRT bounds reconciled with "no Elo expectation predeclared," not silently reused.** `docs/sprt-guidelines.md` section 1 documents `elo0=0, elo1=50, alpha=0.05, beta=0.05` as this project's general, pre-existing convention for testing any single isolated change, and the P16-3 preregistration's own section 8 stop rule explicitly names "this project's existing SPRT convention" as the frozen terms to use. This predates both Phase 16 and Phase 17 and was not chosen based on PVS's own measured effect (no PVS-specific figure appears anywhere in `docs/sprt-guidelines.md`). Retained unchanged, with this evidence recorded rather than assumed.

- **Frozen Gate 4 protocol document created**: `docs/architecture/research/phase17-p17-4-strength-preregistration.md`, covering candidate, baseline, engine settings, match parameters (cutechess version, TC, concurrency, opening corpus and SHA, pairing, adjudication, game cap), SPRT bounds, verdict semantics, and an explicit no-parameter-changes-mid-run amendment rule.

- **`tools/p17-4-sprt.ps1` created, not executed.** A bounded wrapper around the existing `tools/sprt.ps1` (reused for the actual match execution, not reimplemented, since it already satisfies the core match-running requirements), adding exactly what `sprt.ps1` does not already do: refuses to run at all without an explicit `-IReallyMeanIt` switch; refuses a WSL-mounted working directory; refuses a dirty candidate tree; refuses to proceed if `Searcher.java` does not contain the repaired `childIsPvNode` expression; refuses to proceed if the opening corpus is missing or its SHA-256 does not match the frozen value; builds the candidate from the current checkout and the baseline from `ebe513e` via a disposable `git worktree` (never touching the candidate's own working tree); records both JARs' SHA-256; records full environment evidence (CPU, OS, JDK, all frozen parameters) to `tools/results/p17-4/<timestamp>/`; and only then invokes `tools/sprt.ps1` with every parameter from the frozen protocol document, with `Hash=16` passed explicitly on both sides via `-NewOptions`/`-OldOptions`. Its own verdict reporting uses the corrected #231 semantics, not "regression confirmed." **Not executed in this session or any other; this is a dry-run-only preparation artifact.**

**Decisions Made:**

- **Reuse, not replace**: `tools/sprt.ps1` was not modified and is not replaced. It already satisfies the core match-execution requirements (arbitrary JAR paths, per-engine Threads/options, TC/bounds/game-cap parameters, opening file support, paired colors via `-repeat`). `tools/p17-4-sprt.ps1` exists only to add the phase-specific freezing, verification, and evidence-capture layer `sprt.ps1` was never meant to provide, the same relationship `tools/p17-2-native-throughput.ps1` already has to the general `--bench-raw` mechanism.
- **`nightly-sprt.yml` is not used for Gate 4.** Its candidate ref (`develop`, not this branch), baseline convention (latest release, not the frozen `ebe513e`), missing opening diversity (no `-openings` argument at all), and missing explicit Threads/Hash options make it unsuitable for an isolated, same-baseline, paired-opening experiment, independent of the wording problem #231 also fixed there. Its wording was still fixed, since it runs on every future `develop` commit regardless of Phase 17 and the same overstatement problem would recur there either way.
- **Opening-corpus gap is flagged, not treated as a blocker.** It does not represent an unresolved protocol *decision* (the corpus to use, and its exact content, are both already established); it represents missing infrastructure (tracking) that a verification gate already mitigates for this specific run. A follow-up issue to track or regenerate the file properly is proposed but explicitly out of scope for this task.
- **No SPRT game was played.** `tools/p17-4-sprt.ps1` was written and reviewed (structurally, via manual bracket/brace balancing and a line-by-line read, since this WSL session has no PowerShell interpreter to execute it against, the same limitation noted for `tools/p17-2-native-throughput.ps1`) but never invoked, even in dry-run form, since dry-run still requires being on the target machine to be meaningful.

**Broke / Fixed:** Fixed: `.github/workflows/nightly-sprt.yml`'s H0-verdict wording (issue #231). No search/PVS source touched.

**Measurements:** N/A, no game played. See the frozen protocol document for every parameter this eventual measurement will use.

**Status:** Phase 17 Step 4 (strength) is **READY**: candidate, baseline, engine settings, match parameters, and SPRT bounds are all explicitly frozen and justified from existing project evidence; #231 is fixed; `tools/p17-4-sprt.ps1` is prepared and reviewed. Step 4 itself remains **not started** on #229/#230 (this is preparation, not the gate); no SPRT game has been played, and none should be until `tools/p17-4-sprt.ps1 -IReallyMeanIt` is run deliberately on a native Windows host.

---

### [2026-09-20] Phase 17 Step 4 protocol amendment, concurrency frozen at 6, still no game played (Issues #229, #230)

**Built:**

- **Pre-run amendment, not a mid-experiment change.** Verified before touching anything: `tools/results/p17-4/` does not exist on this checkout, confirming `tools/p17-4-sprt.ps1` has never been run and no game has been played under the frozen protocol. This amendment changes a parameter before game 1, which the protocol document's own amendment rule explicitly allows (only a change *after* game 1 would invalidate a run).

- **Frozen concurrency changed from 2 to 6**, for the target native host: an AMD Ryzen 7 7700X, 8 physical cores / 16 logical (SMT) threads. Both engines remain `Threads=1` per instance (unchanged); 6 simultaneous games is an operational capacity choice sized to that specific host's physical core count, not a claim that one game maps exactly to one physical core (engine processes, OS scheduling, SMT, and the idle side of every game all complicate that mapping in practice, so the document states it only as a heuristic). 6 stays below 8, leaving roughly two physical cores of headroom for Windows, JVM/process overhead, cutechess-cli itself, and interactive desktop use during what may be a long-running match, deliberately short of saturating all 16 logical/SMT threads. This changes only how fast the match executes; the SPRT hypotheses being tested (elo0=0, elo1=50) are properties of the games played, not of how many run concurrently, so this amendment does not touch the statistical test itself.

- **`tools/p17-4-sprt.ps1` no longer accepts concurrency as a command-line override.** Previously `-Concurrency` was a parameter with a default of 2 (the only frozen protocol value that had been left adjustable, inconsistent with every other term in the script, which is a hardcoded constant). Moved into the same "frozen protocol constants" block as `$TC`/`$Elo0`/`$Elo1`/`$EngineThreads`/`$HashMb`/`$MaxGames`, now `$Concurrency = 6` with no way to override it short of editing the script itself, matching how every other frozen term already behaves. The environment-evidence record (`05-environment.json`) still captures `concurrency` explicitly, now always `6`.

- **`docs/architecture/research/phase17-p17-4-strength-preregistration.md` section 4 (Match) updated** with the new value and full rationale, and a new "Amendment log" added under section 7 (Amendment rule) recording this specific pre-run change with its date and reasoning, so the document's own history of what changed before game 1 is self-contained rather than requiring a cross-reference to this dev-entry to reconstruct.

**Decisions Made:**

- **Every other frozen Gate 4 parameter is unchanged**: candidate (`e6afbb1`/`f9b152c`), baseline (`ebe513e`), Threads (1 per engine), Hash (16 MB per engine), TC (`5+0.05`), opening corpus (`tools/noob_3moves.epd`, same SHA-256 `2011193b4854e9a8cfdc05312ca2dbaffa6ceae3abbdee20e2ead2a18a603347`), pairing (`-repeat`), SPRT bounds (elo0=0, elo1=50, alpha=0.05, beta=0.05), adjudication (`-resign movecount=5 score=400`, `-draw movenumber=40 movecount=8 score=10`, both `tools/sprt.ps1` hardcoded defaults, not touched), and game cap (20,000) all remain exactly as frozen in the prior preparation entry.
- **No game was played to make or validate this change.** The amendment is justified purely from the target host's known physical-core count, not from any observed match behavior, consistent with the "no post-hoc parameter selection" requirement this whole protocol has followed throughout.
- **Step 4 is still not started.** This amendment does not advance Step 4's status on #229/#230 beyond READY; it only updates what "READY" now means.

**Broke / Fixed:** None. No search/PVS source touched.

**Measurements:** N/A, no game played.

**Status:** Phase 17 Step 4 remains **READY**, not started, with concurrency now frozen at 6 (previously 2) before any game has been played. No SPRT game has been run under either value.

---

### [2026-09-20] Phase 17 Step 4 final pre-run hardening, three integrity gaps closed, no game played (Issues #229, #230)

**Built:**

- **Candidate identity strengthened from a single-file check to a source-tree diff.** `tools/p17-4-sprt.ps1` previously proved only that `Searcher.java` contained the repaired `childIsPvNode` expression, which shows the repair is present but not that the rest of the candidate's production source is still exactly the frozen candidate (any other file under `engine-core`/`engine-uci` could have drifted and this check would not have noticed). Determined the complete production source path set from the actual build rather than assuming it: `engine-core` has no `src/main/resources` at all; `engine-uci/src/main/resources` contains `books/Performance.bin` (the engine's built-in Polyglot opening book, wired to `UciApplication`'s `BookFile` option but disabled by default via `OwnBook=false`) and `logback.xml` (logging configuration only); `engine-uci` is built as a shaded/fat JAR (`maven-shade-plugin`) that pulls `engine-core`'s compiled classes in directly, so `engine-core/src/main` and `engine-uci/src/main` together are the complete set of paths that can affect the packaged UCI engine's behavior. The script now runs `git diff --stat f9b152c -- engine-core/src/main engine-uci/src/main` against the current checkout and fails closed on any non-empty result, checking the frozen candidate commit `f9b152ca4f45e8e8aa5a48092b03416aba79b230` explicitly rather than requiring `HEAD == f9b152c` (legitimate docs/tooling-only commits, `e6afbb1`/`f1b40a2`/`8797512` as of this writing, are expected to sit above it). The original `Searcher.java` grep is retained as a secondary diagnostic, run only after the source-tree check has already passed, since it is more specific but far narrower in coverage. Both the frozen candidate ref and the identity-check result (`candidate_source_identical_to_frozen_ref`) are now recorded in the run's git-state and environment evidence files.

- **Baseline `-BaselineRef` command-line override removed.** The prior version of this script accepted `-BaselineRef` as a parameter defaulting to `ebe513e`, meaning a caller (or a future edit) could point the baseline build at a different commit without the frozen-terms guarantee this whole protocol depends on actually stopping them. `$BaselineRef` is now a frozen internal constant, pinned to the full resolved SHA `ebe513eabd50e853a4e24a0260c64b41a5a4b224` (not the short form, so an accidental short-hash collision elsewhere in this repository's history could never silently resolve to a different commit). Before building the baseline, the script now runs `git rev-parse $BaselineRef` and refuses to proceed unless it resolves to exactly that same full SHA, recording the resolved value alongside the constant.

- **Concurrency wording corrected**, per this step's own precise framing. The prior preregistration text said concurrency "has no effect on the SPRT hypotheses being tested" and left it there, which is true of the formal `elo0`/`elo1`/`alpha`/`beta` bounds but reads as though concurrency has no bearing on the experiment at all. Corrected in both `docs/architecture/research/phase17-p17-4-strength-preregistration.md` section 4 and `tools/p17-4-sprt.ps1`'s own header comment: at a wall-clock time control like `5+0.05`, concurrency is part of the execution environment, and CPU contention among simultaneous games can change the effective compute available to each engine per move, which can affect observed game outcomes or a measured relative Elo, particularly since the candidate and baseline are expected to have different search-efficiency characteristics (that is what PVS is supposed to change) and could therefore respond differently to a given amount of contention. Concurrency remains frozen at 6 for exactly this reason, not despite it; the choice of 6 itself was not reopened.

- **cutechess-cli version enforcement added, and the frozen version bumped.** The script previously recorded whatever `cutechess-cli --version` happened to be installed without checking it against the v1.4.0 this protocol had frozen, meaning a different installed version could silently run under a v1.4.0 preregistration. Checked `gh api repos/cutechess/cutechess/releases/latest` and found v1.5.1 is current; reviewed the v1.4.0 -> v1.5.1 changelog (releases v1.5.0 and v1.5.1) and found only bug fixes unrelated to SPRT/game-management logic for a standard-variant UCI match (a GUI selection bug, an off-by-one `WesternBoard` edge case affecting non-standard variants, a Knight-Relay GUI crash, a `setoption` parsing fix, XBoard PV parsing, a Qt5->Qt6 build-tooling change, and a Windows-release-build fix), so there is no reproducibility reason to stay pinned to the older release. Bumped the frozen version to v1.5.1 in the preregistration document and in `.github/workflows/nightly-sprt.yml`'s `CUTECHESS_VERSION`. `tools/p17-4-sprt.ps1` now locates the installed `cutechess-cli` (via `$env:CUTECHESS` or `PATH`, the same resolution `tools/sprt.ps1` already uses), runs `--version`, and fails closed if the output does not contain `1.5.1`, rather than silently proceeding with whatever is installed. The exact version-output string, the expected version, and the pass/fail result are all recorded in the run's environment evidence.

**Decisions Made:**

- **Every other frozen Gate 4 parameter is unchanged**: candidate source ref `f9b152c` (now the explicit identity anchor rather than an implicit assumption about HEAD), baseline `ebe513e`, Threads=1 per engine, Hash=16 MB per engine, TC=5+0.05, concurrency=6, opening corpus and its exact SHA-256 (`tools/noob_3moves.epd`, `2011193b4854e9a8cfdc05312ca2dbaffa6ceae3abbdee20e2ead2a18a603347`), pairing via `-repeat`, SPRT bounds (elo0=0, elo1=50, alpha=0.05, beta=0.05), existing adjudication settings, and the 20,000-game cap.
- **No PVS/search behavior was changed.** All three fixes are integrity checks and documentation precision in the Gate 4 wrapper and its preregistration document; no file under `engine-core/src/main` or `engine-uci/src/main` was touched.
- **No game was played to make or validate any of this.** All three integrity gaps were identified by reading the existing script and the actual Maven build configuration (`engine-uci/pom.xml`'s shade-plugin config, both modules' `src/main` directory listings), not by observing match behavior.

**Broke / Fixed:** Fixed: candidate source-identity under-validation, baseline override exposure, imprecise concurrency wording, and unenforced cutechess-cli version, all in `tools/p17-4-sprt.ps1` and its governing preregistration document. No search/PVS source touched.

**Measurements:** N/A, no game played.

**Status:** Phase 17 Step 4 remains **READY**, not started. `tools/p17-4-sprt.ps1` was not executed, even in dry-run form, in producing any of these fixes.

---

### [2026-09-20] Phase 17 Step 4 final artifact-identity hardening, both engines built from exact frozen commits, no game played (Issues #229, #230)

**Built:**

- **Candidate identity replaced, not further strengthened.** The prior pass's production-source-tree diff against `f9b152c` (`engine-core/src/main`, `engine-uci/src/main`) proved the current checkout's *source files* matched the frozen candidate, but the candidate JAR was still being built from the current checkout, not from that commit directly. That left a gap the diff could not see: a change to `pom.xml`, the shade-plugin configuration, compiler settings, or any other build input outside `src/main` could still change the packaged JAR while the diff stayed empty. Rather than adding a second check for build-configuration files on top of the source-tree diff, both the candidate and baseline are now built from their own exact frozen commit via a disposable detached `git worktree`, closing the whole class of gap rather than checking for members of it one at a time.
- **New shared function `Build-FrozenEngineJar` in `tools/p17-4-sprt.ps1`**, used identically for both engines: resolves the frozen ref with `git rev-parse` and fails closed unless it resolves to exactly itself; runs `git worktree add --detach` at that ref; independently re-checks the worktree's own `HEAD` against the same frozen SHA before building (so a `git worktree add` that silently landed somewhere else, or a local branch/tag whose target moved, cannot slip through); builds with `mvn -pl engine-core,engine-uci -am package -DskipTests`; locates the shaded JAR, copies it out to a run-specific evidence directory, and computes its SHA-256; removes the worktree in a `finally` block so a mid-build failure cannot leave a stray worktree behind. Candidate (`f9b152ca4f45e8e8aa5a48092b03416aba79b230`) and baseline (`ebe513eabd50e853a4e24a0260c64b41a5a4b224`) both go through this same function, with the baseline's own former special-cased worktree logic from the prior hardening pass folded into it rather than kept as a second, near-duplicate code path.
- **Old identity checks removed, not layered underneath the new one.** The `$ProductionSourcePaths` array, the `git diff --stat f9b152c -- ...` check, and the `Searcher.java` grep for the repaired `childIsPvNode` expression are all deleted from `tools/p17-4-sprt.ps1`. Once both engines are built from git-rev-parse-verified exact commits, these older checks could only ever agree with the stronger guarantee or, if they ever disagreed, raise a question about which check to trust; removing them avoids that ambiguity rather than accepting it. Verified their identifiers (`ProductionSourcePaths`, `candidateSourceDiff`, `searcherFile`, `searcherContent`, `childIsPvNode`) no longer appear anywhere in the file, and confirmed the script's braces and parentheses stay balanced after the rewrite (35/35 and 94/94 respectively, checked with a small script since no PowerShell interpreter is available in this session to parse it directly).
- **The current checkout is now orchestration-only.** The section that previously asserted candidate source identity is renamed "Orchestration checkout state (not an engine under test)" and checks only that `phase/17-pvs-experiment` is checked out, at some HEAD, with a clean tracked tree; it makes no claim about that HEAD matching either engine, since it no longer needs to. This checkout's role is to supply the script, the preregistration documents, and the location run evidence gets written to. Its HEAD can legitimately sit above either frozen engine commit on later docs/tooling-only commits without affecting either engine, which is already the actual state of this branch (`e6afbb1`, `f1b40a2`, `8797512`, `a5d5c92`, and this entry's own commit, all after `f9b152c`).
- **Evidence field names updated to make the three-way relationship explicit.** The run's environment-evidence JSON now records `orchestration_head`, `orchestration_branch`, `candidate_frozen_commit`, `candidate_actual_commit`, `candidate_jar_sha256`, `baseline_frozen_commit`, `baseline_actual_commit`, `baseline_jar_sha256`, alongside the existing openings/cutechess-version/environment/protocol fields, so a reader of the evidence file (not just the script source) can see unambiguously which commit produced which artifact without cross-referencing the script.
- **`docs/architecture/research/phase17-p17-4-strength-preregistration.md` sections 1 and 2 rewritten** to describe both engines as built from their own exact frozen commit via worktree, with a new subsection ("Orchestration checkout vs. the two engines under test") explaining why the orchestration checkout's HEAD is never built as either engine and why the diff/grep checks were removed rather than kept. A new amendment-log entry under section 7 records this pass; the two entries from the prior hardening pass (concurrency 2->6, and cutechess-cli 1.4.0->1.5.1 plus the now-superseded source-diff/`-BaselineRef` changes) are left as-is, since they are an accurate historical record of what changed and when, not a description of the current mechanism.

**Decisions Made:**

- **Building from the exact commit is preferred over strengthening the diff further**, per this task's own instruction: a third, even-more-thorough diff/grep check would still be a proxy for "was this built from the frozen commit," where building from the commit directly answers that question exactly and makes the proxy checks redundant.
- **Every other frozen Gate 4 parameter is unchanged**: candidate `f9b152c`, baseline `ebe513e`, Threads=1 per engine, Hash=16 MB per engine, TC=5+0.05, concurrency=6, opening corpus and its SHA-256 (`tools/noob_3moves.epd`, `2011193b4854e9a8cfdc05312ca2dbaffa6ceae3abbdee20e2ead2a18a603347`), pairing via `-repeat`, SPRT bounds (elo0=0, elo1=50, alpha=0.05, beta=0.05), existing adjudication settings, and the 20,000-game cap. Only the mechanism used to prove the candidate and baseline JARs are what they claim to be changed.
- **No PVS/search behavior was changed.** This pass touches only `tools/p17-4-sprt.ps1` and its governing preregistration document; no file under `engine-core/src/main` or `engine-uci/src/main` was edited.
- **No game was played to make or validate any of this.** The gap being closed (build-configuration drift outside `src/main`) was identified by reading the existing script and the Maven build configuration, not by observing match behavior.

**Broke / Fixed:** Fixed: candidate identity was previously proven only for `src/main` source files, not for the full build that produces the JAR; now both candidate and baseline are built directly from their exact frozen commits, closing that gap architecturally. No search/PVS source touched.

**Measurements:** N/A, no game played.

**Status:** Phase 17 Step 4 remains **READY**, not started. `tools/p17-4-sprt.ps1` was not executed, even in dry-run form, in producing this change; it was reviewed only by manual read and brace/paren balance checking, consistent with every prior pass on this script in this WSL session.
