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

- **PV-node propagation audit (task step 6): a real, unresolved risk found, and flagged as instructed, not fixed.** `childIsPvNode = isPvNode && moveIndex == 0` means every full-window PVS re-search (moveIndex > 0 by construction, in both the LMR and ordinary-sibling branches) recurses with `isPvNode = false`, even though the window it's given is wide, not the null width `isPvNode` is otherwise a reasonable proxy for. Three forward-pruning gates read the *current* node's own `isPvNode` parameter and skip pruning when it's true: `canApplyRazoring`, `canApplyFutilityPruning`, and `canPruneLosingCapture` (the latter also gated to `depth <= 2`; razoring/futility's margins are likewise only defined for depth 1-2 per `getFutilityMarginForDepth`). A child reached via a full-window PVS re-search is told `isPvNode = false`, so within its own recursion these three heuristics are free to fire near that subtree's horizon -- exactly the situation they're designed to be skipped for, since a full-window search is, by definition, being asked for an exact value the way a true PV node is. This pattern already existed before Phase 17 (the old LMR-fail-high immediate re-search had the identical `childIsPvNode` formula), but Phase 17 generalized it from an LMR-only, comparatively rare path to the general later-PV-sibling case -- 14,473 full-window re-searches fired in the canonical mechanism run (section above), versus a much smaller, LMR-only-gated count before this phase. The plausible failure mode is a conservative one: these gates can only cause a move to be *underestimated*, never fabricate a better-than-true score, so this cannot corrupt the fail-soft bound invariant into returning a value on the wrong side of the true minimax value -- but it could cause a full-window re-search to under-report a move's true value near the horizon, which in rare cases could cost the search a move it should have preferred. **This was not demonstrated to cause any of the four SearchRegressionTest differences** (all four are fully and independently explained by the LMR/aspiration isolation above), so it does not block the mechanism-gate result or the throughput gate. It is real and unresolved, though, and per the task's own framing this is a correctness question, not permission to change it now -- `childIsPvNode` was left exactly as-is. **Recommendation, not action taken:** re-examine this specifically before the eventual SPRT (strength) gate, since Elo is sensitive to exactly this kind of subtle, horizon-concentrated selectivity bias in a way raw node-count and NPS measurements are not.

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
