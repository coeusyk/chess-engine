# P16-3: search-intervention evidence review and preregistration

Governs issue #226, Step 3. Reviews existing search-execution evidence (P16-1's TT fix, P16-2's
canonical baseline) plus current `Searcher` control flow, and preregisters exactly one search
intervention for a future implementation experiment. **No intervention is implemented here.** No
SPRT is run here. This document selects and freezes a plan; the plan itself is future work.

Execution baseline for this review: branch `phase/16-search-execution-qualification`, commit
`31e2243` (the tip after P16-2 closed).

## 1. Evidence available going into this step

From P16-2 (`docs/architecture/research/phase16-p16-2-canonical-1t-baseline.md`), native Windows,
`Threads=1`, Classical evaluator, `Hash=16MB`, depth 13, 31-position suite:

- 73,089,246 nodes, deterministic across 9 independent executions.
- Canonical NPS median 334,861, CV 0.84% across 7 runs.
- JFR leaf-sample attribution: `Board.makeMove`/`unmakeMove` 37.6%, evaluation 18.5%, move
  ordering 14.5%, move generation 14.2%, everything else 11.1%.
- First-move cutoff rates cluster 78-98% across positions (move ordering is not obviously broken).
- No dead pruning path: null-move, LMR, futility, and delta-pruning counters are all populated and
  nonzero wherever depth allows them to fire.

From P16-1 (issue #227, closed): the shared-TT publication race is fixed (XOR-checksum
association); this is infrastructure correctness, not itself a candidate intervention, and is not
reopened here.

From project history: `dev-entries/phase-3.md` (2026-03-24 through 2026-03-25) recorded "implement
principal variation search behavior refinements" as the planned next step after null-move pruning,
twice, in successive entries -- and both times the next issue actually landed something else
(futility/razoring, then check extensions). General PVS was never implemented; only a narrower,
LMR-specific null-window verification exists (section 3). `docs/ccrl-submission.md` currently lists
the engine's architecture as "Negamax + Alpha-Beta, PVS, aspiration windows, Lazy SMP" -- aspiration
windows and Lazy SMP are real (`aspirationWindowsEnabled`, `searchRootWithAspiration`), but general
PVS is not, per section 3 below. That doc line is inaccurate and should be corrected separately;
noted here, not fixed as part of this step, since it's a documentation issue, not a search issue.

## 2. Candidates considered

**A. General PVS / zero-window sibling search.** Every move loop currently searches every sibling
with the same window inherited from the parent, except late quiet moves that qualify for LMR's own
null-window verification (section 3). At a genuine PV node this means every ordinary sibling after
the first, at every depth and every ply, is searched with a wide window it doesn't need. See
section 3 for the precise control-flow evidence.

**B. `Board.makeMove`/`unmakeMove` hot-path optimization.** The single largest JFR leaf-sample
bucket at 37.6%, more than double the next largest.

**C. Move ordering / history refinement.** 14.5% of JFR leaf samples, and the second-largest
per-move-loop attribution bucket after move generation ties with it.

**D. TT / SMP follow-up.** The TT itself was just made lock-free-correct in P16-1; this baseline
was taken at `Threads=1` only.

**E. Other candidate:** none identified with comparable evidentiary support. Quiescence search's
qnode/main-node imbalance (P16-2 section 4, position 4's 5.25 ratio) was considered but is a single
per-position observation on one suite, not a control-flow-level finding with a clear mechanism the
way PVS's absence is; it isn't proposed as a serious candidate here.

## 3. PVS-specific control-flow inspection

Read directly from `Searcher.alphaBeta()` (`engine-core/.../search/Searcher.java`, the move loop
starting at line 1007) and `Searcher.searchRoot()` (line 703), at commit `31e2243`:

- **First-move search behavior:** for both the root move loop (`searchRoot`, line 778) and internal
  nodes (`alphaBeta`, line 1107 else-branch), the first move (`moveIndex == 0`) is searched with the
  full inherited window `(-beta, -alpha)`. This is correct PVS behavior for the first move and
  needs no change.

- **Ordinary later-sibling behavior:** every later move that is *not* LMR-eligible -- which
  includes every capture, every killer move, every TT move, every move made while in check or that
  gives check, and every quiet move before `moveIndex == 4` -- falls into the same else-branch
  (line 1105-1120) and is searched with the *same full window* `(-beta, -alpha)` as the first move,
  regardless of `moveIndex`. At a non-PV node this full window is already narrow (the node's own
  `alpha`/`beta` gap is 1, inherited from its own parent's null-window probe), so this costs
  nothing extra there. At a genuine PV node -- root, and any node reached via `moveIndex == 0` all
  the way down -- this window can be wide, and every one of these ordinary later siblings is
  searched at full cost with that wide window. This is the concrete inefficiency PVS exists to
  remove, and it reproduces at every PV node in the tree, not just the root.

- **LMR reduced zero-window behavior:** the *only* place a narrower window is ever used is inside
  `canApplyLmr`'s branch (line 1070-1104): quiet, non-killer, non-TT, non-check moves at
  `moveIndex >= 4` and `depth >= 3` get a reduced-depth search with window `(-(alpha+1), -alpha)` --
  a true null window. `canApplyLmr` (line 1272) does not reference `isPvNode` at all, so this
  reduction and its null window apply identically whether or not the node is a PV node.

- **Existing re-search behavior:** if the LMR null-window probe fails high (`score > alpha`, line
  1089), the code re-searches immediately at full depth *and* full window
  `(-beta, -alpha)` (line 1091-1104). There is no intermediate "full-depth, still null-window"
  step. This conflates two things a combined PVS+LMR scheme normally keeps separate: verifying the
  reduction was safe (full depth, still null window) versus verifying the move is actually better
  than the rest of the line (full window). The current code jumps straight to the second on any
  first fail-high, which is more re-search work than necessary specifically at PV nodes with a wide
  window, though it costs nothing extra at non-PV nodes (where full window equals null window
  already).

- **Whether PV/non-PV semantics already partially implement PVS:** partially, but only the
  *propagation*, not the *effect*. `childIsPvNode = isPvNode && moveIndex == 0` (line 1090 and 1106,
  and `searchRoot` line 777) is exactly the bookkeeping a real PVS implementation needs -- it
  correctly identifies that only the first child of a PV node is itself a PV node. But today
  `isPvNode` only gates `pvNodeEvals`/`cutNodeEvals` accounting, `canApplyRazoring`'s and
  `canApplyFutilityPruning`'s "skip at PV nodes" checks, and (transitively) singularity search
  eligibility. It never narrows the window sent to a child. The wiring PVS needs is already
  threaded through the whole call tree; it's just not connected to anything that changes search
  cost.

- **Where a general PVS change would be inserted:** the move-loop branch structure at lines
  1069-1120, restructured so that the existing LMR branch's null-window probe generalizes to *every*
  move with `moveIndex > 0` at a PV node (not only LMR-eligible quiet moves), and the existing
  full-window re-search only fires when that null-window probe fails high *and* the node is a PV
  node (at a non-PV node, full window and null window are the same search, so re-searching would
  waste a duplicate call). This is a change entirely inside the branch selection of *which window*
  to pass to the existing `alphaBeta` recursive calls; it does not touch `canApplyLmr`,
  `canApplyFutilityPruning`, `canApplyRazoring`, `canApplyNullMove`, singularity search, or move
  ordering, all of which are independent of window width.

## 4. Candidate comparison

| | A. General PVS | B. make/unmake hot path | C. Move-ordering/history refinement | D. TT/SMP follow-up |
|---|---|---|---|---|
| Concrete current evidence | Section 3: every non-LMR-eligible later sibling at every PV node gets a full-window search; confirmed by direct code inspection at `alphaBeta` line 1105-1120 and `searchRoot` line 778, not inferred from throughput data | JFR: 37.6% of leaf samples (P16-2 section 5), by far the largest bucket | JFR: 14.5% of leaf samples; first-move cutoff rates already 78-98% across all 31 positions (P16-2 section 4) | P16-1 just fixed the shared-TT lock-free correctness bug; this baseline is `Threads=1` only, no SMP throughput/correctness data exists yet |
| Expected mechanism | Cheaper (reduced-window) verification searches replace full-window searches for most later siblings at PV nodes, cutting nodes searched at equal depth without changing which move is chosen when the null-window probe doesn't fail high | Fewer cycles per call in the two functions that dominate wall-clock time, at the same node count | Better move ordering raises first-move cutoff rate further and/or improves LMR's effectiveness by feeding it better-sorted candidates | Correctness is done; a follow-up would target Lazy-SMP throughput/scaling, which needs `Threads>1` infrastructure this baseline doesn't cover |
| Likely strength impact | Positive in the general chess-engine literature for this exact reason (search a wider tree to the same depth in less time, or deeper in the same time), but not claimed here as a specific Elo number -- see section 6 | None expected from the change itself: same node count, same move choices, only faster per-node execution; any strength change would come indirectly from reaching greater depth in the same wall-clock time (a real possibility, but conflates a throughput change with a strength claim if not measured carefully) | Uncertain and hard to isolate: first-move cutoff rate is already high, so headroom for further improvement is unclear without a specific ordering defect identified (none was found in P16-2) | Currently unmeasurable: `Threads=1` is the only configuration this baseline covers, so a Lazy-SMP change has no baseline to compare against yet |
| Likely throughput/tree-size impact | Reduces total node count at fixed depth (fewer full-window searches means less work per node visited), the intended effect; per-node cost (NPS) should be roughly unchanged since it reuses the existing `alphaBeta` recursion, just with different windows | Increases NPS at unchanged node count; does not change tree size or move choice | Unclear; a real improvement in ordering could either raise or lower node count depending on whether it changes cutoff timing, and history/killer changes risk destabilizing LMR eligibility (`canApplyLmr` checks `isKiller`) | N/A without `Threads>1` measurement infrastructure |
| Implementation risk | Moderate: touches the hottest control-flow path in the engine (the move loop) and must get the PV/non-PV window-selection logic exactly right, including the re-search condition, without disturbing the surrounding pruning/extension code that already reads `alpha`/`beta`/`isPvNode` | Low to moderate: performance work confined to two well-tested, already-covered functions (`makeMove`/`unmakeMove`), no search-decision change means no new correctness surface beyond "still produces the same board state" | Moderate to high: history/killer changes interact with `canApplyLmr`'s eligibility gate and with `moveOrderer.scoringBuffer` used for `isLosingCapture`; changing what counts as "ordered well" can shift which moves become LMR-eligible | Currently blocked: no scoped design exists yet for what the "follow-up" even is beyond "TT is now safe to share across threads" |
| Interaction risk with existing LMR/TT/extensions | Direct and intentional: PVS and LMR are meant to compose (reduced-depth null-window first, then full-depth null-window, then full window only on a genuine PV improvement); the preregistration in section 5 is designed specifically to slot into the existing LMR branch rather than duplicate it | None: does not touch search decisions, pruning, or the TT read/write path's semantics | Real: any reordering change risks moving moves in or out of `canApplyLmr`'s `moveIndex >= 4` window or changing which move is a "killer," with follow-on effects on reduction depth and re-search rate that would need to be measured together, not attributed to ordering alone | N/A |
| Measurement quality achievable now | High: node count is already established as deterministic to the node (P16-2 section 3a), so an exact node-count delta on the same 31-position suite is a clean, zero-noise mechanism signal; throughput and strength gates layer on top per section 7 | High for throughput (same 7-run canonical protocol), but weak for strength in isolation, since a pure throughput change has no principled reason to move Elo except via reaching greater depth, which is a separate, harder-to-isolate effect | Low without a specific, narrow hypothesis about what's wrong with the current ordering; "refine it" is not yet a falsifiable, minimal experiment the way PVS's window change is | Low: no experiment design exists to measure yet |
| Rollback clarity | High: a window-selection change is naturally guarded by a single boolean/flag the way `lmrEnabled`, `nullMovePruningEnabled`, and `futilityRazoringEnabled` already are in this codebase; reverting means flipping one flag or reverting one self-contained diff to the move loop | High: purely a performance change to two functions with no external behavior surface | Medium: depends on how the refinement is implemented; a history-formula tweak is easy to revert, a wholesale reordering-scheme replacement is not | N/A |

## 5. Selected intervention: A, general PVS / zero-window sibling search

B (make/unmake) has the largest JFR share, but a large share of samples in a leaf-sampling profiler
just says the program spends a lot of wall-clock time there. For `makeMove`/`unmakeMove` that's
expected for any bitboard engine regardless of whether anything is wrong, and it says nothing about
whether optimizing it would change node count, tree shape, or strength. This step's instructions
are explicit that a high JFR share is not itself a reason to optimize. B is a legitimate future
throughput experiment, but on its own merits it offers no mechanism for a strength change; any Elo
effect would only arrive indirectly through reaching greater depth in the same time, which is a
second, harder-to-isolate effect layered on top of a first, unrelated implementation-speed change.

C lacks a specific, falsifiable defect. First-move cutoff rates are already healthy (78-98%) across
the whole suite, so there's no evidence of an ordering problem to fix, only a hope that a change
might help. That's the kind of vague target this step's "do not bundle" and evidence-first framing
argues against.

D isn't actionable yet. There's no `Threads>1` baseline to measure against, and no scoped design
exists for what a "TT/SMP follow-up" would even do beyond noting that P16-1 made the TT safe to
share.

A is different. It isn't selected because PVS is absent (the instructions explicitly warn against
inferring value solely from absence) but because section 3's control-flow inspection establishes a
specific, present inefficiency with a known mechanism: every ordinary later sibling at every PV
node in the tree is searched with a wider window than a null-window verification would require, and
the PV/non-PV bookkeeping a fix needs is already threaded through the entire call tree
(`isPvNode`/`childIsPvNode`), currently unused for anything but accounting and pruning gates. The
expected effect, fewer full-window searches at PV nodes at the same depth, without changing which
move is finally chosen when the null-window probe doesn't fail high, is a tree-shape change with a
node-count-level mechanism that can be measured exactly, before any Elo claim, using the
already-established deterministic 31-position baseline (P16-2 section 3a). That measurability is
what makes it the right next experiment, not an assumption about strength: mechanism and
correctness can be fully gated before a single SPRT game is played, which isn't true of B (no
search-decision mechanism to measure) or C (no specific defect to test against).

## 6. Preregistered minimal experiment (implementation deferred)

If and when this is implemented, the experiment is scoped exactly as follows, and no wider:

- **First searched move:** full window, unchanged from current behavior (`moveIndex == 0` at both
  `searchRoot` and `alphaBeta`).
- **Later eligible siblings:** zero window around alpha, i.e. `(-(alpha + 1), -alpha)`, for every
  move with `moveIndex > 0` at a PV node (`isPvNode == true`) that is not already covered by the
  existing LMR reduced-depth probe. At a non-PV node, the inherited window is already null width,
  so this changes nothing there by construction, and no separate branch is needed for it.
- **Full-depth/full-window re-search:** only when the null-window probe (LMR's existing
  reduced-depth one, or the new full-depth one for non-LMR-eligible siblings) fails high with
  `score > alpha`, strictly inside the node's own `(alpha, beta)` window (i.e. `score < beta`; a
  probe that returns `score >= beta` is already a cutoff at the node's own bound and gains nothing
  from a full-window re-search). This adds the missing "full-depth, still null-window" step for the
  LMR path specifically: LMR fail-high goes to a full-depth null-window check first, and only *that*
  failing high (under the same `score < beta` condition) triggers the full-window re-search.
- **LMR logic:** preserved exactly as-is (`canApplyLmr`'s eligibility gate, `lmrReductions` table,
  `improving`-based +1 adjustment) unless implementation reveals a precise interaction that requires
  an explicitly documented adaptation -- none is anticipated from control-flow inspection alone,
  since the change only adds a window-selection branch around the existing reduced-depth call, it
  does not alter when a reduction is applied or how large it is.
- **New counters required** (none exist today, per direct inspection of the counter fields at lines
  94-100): a zero-window-search counter (incremented for every null-window probe taken under this
  scheme, LMR-driven or PVS-driven) and a full-window-re-search counter (incremented only when the
  fail-high-inside-window re-search actually fires), mirrored in the existing `[BENCH]` per-depth
  logging line (`Searcher`'s DEBUG output already surfaces `nmp_cuts`, `lmr_apps`, `fut_skips`,
  `delta_prune` the same way).
- **No move-ordering or history changes** in this experiment. `moveOrderer`, `historyHeuristic`,
  and `killerMoves` are read, not modified, by this change.

## 7. Measurement plan

Four gated stages, each a precondition for the next. No stage is skipped.

**1. Mechanism** (native Windows, canonical 31-position depth-13 suite, `Threads=1`, `Hash=16MB`,
Classical evaluator -- the exact P16-2 protocol):

- Exact node-count delta against the frozen 73,089,246-node baseline, per position and in
  aggregate. Since the baseline is already established as bit-for-bit deterministic across 9
  independent executions (P16-2 section 3a), any node-count change on an unmodified suite is
  attributable to the intervention, not measurement noise.
- `qnodes` delta (quiescence node count should not shift for reasons unrelated to a search-window
  change; a large, unexplained `qnodes` shift would itself be a flag to investigate before
  proceeding).
- New counters: zero-window search count and full-window re-search count/rate, both in aggregate
  and per-position, to characterize how often the re-search path actually fires.
- Completed depth: confirm every position still reaches the target depth in the fixed-depth bench
  protocol (this should be unaffected, since depth is fixed, not time-bounded, in `--bench-raw`).

**2. Throughput** (native Windows, same protocol, 7-run canonical `--bench-raw`):

- 7-run median NPS, compared descriptively against this baseline's 334,861 NPS / 0.84% CV. A
  meaningful mechanism change (fewer nodes searched) at roughly unchanged per-node cost should show
  in the same 7-run protocol; this comparison is throughput reporting, not a strength claim by
  itself, and is not used to infer Elo.

**3. Correctness** (WSL2 or native, whichever is available; this gate does not require native
Windows):

- Full relevant Maven suite: `mvn -pl engine-core,engine-tuner -am test` (currently 131+ tests
  green per P16-1's verification in this step).
- Tactical/search regression suites: `TacticalSuiteTest`, `SearchRegressionTest`,
  `SearchRegressionSuite`, `PerftHarnessTest` -- all must remain green with no expected-move drift
  beyond what the project's existing stale-move convention already accounts for (a depth-probe
  check before updating any expected move, not a silent update).
- PV/legal-move invariants: no dedicated PV-line legality replay test currently exists (only
  implicit legality checks inside the existing bestmove-discovery regression tests, e.g.
  `SearchRegressionTest`'s tactical-position `RESULT`-style assertions observed during this step's
  own test run). The implementation experiment should add an explicit check that replays the
  reported PV from each search and confirms every move in it is legal in sequence -- a PVS bug that
  corrupts the PV table (e.g. from a botched re-search condition) is exactly the class of bug this
  invariant would catch and the existing suites might not.

**4. Strength** (native Windows only, per CLAUDE.md Section 6; run only after gates 1-3 pass):

- One isolated, same-baseline SPRT against the current `Threads=1` build. No Elo expectation is
  predeclared, and fixed-depth node reduction from gate 1 is explicitly not used as a substitute
  for an Elo measurement -- a smaller tree at fixed depth is a mechanism observation, not a
  strength result, since a smaller tree could in principle come from pruning something useful just
  as easily as from removing genuine waste.

## 8. Stop rules (predeclared)

- **Correctness regression:** reject. Any test suite in gate 3 failing, or the PV-legality
  invariant catching a corrupted PV line, ends the experiment without proceeding further.
- **Pathological re-search rate, or an unfavorable node-count outcome:** reject before SPRT. If the
  new full-window-re-search counter fires on most zero-window probes (defeating the purpose of the
  narrower window), reject regardless of the node-count result. Otherwise, since the mechanism gate
  measures aggregate node count on the canonical suite exactly (P16-2's 9-execution determinism
  means this comparison carries no measurement noise to argue away), the three possible outcomes
  are handled as follows:
  - Node count increases: reject. A window-narrowing change that searches more nodes at the same
    depth has failed on its own terms.
  - Node count is unchanged: reject. No mechanism effect occurred; there is nothing for a later
    stage to confirm.
  - Node count shows a small but real decrease: do not reject on magnitude alone, and do not invent
    a post-hoc percentage threshold to decide whether the decrease is "big enough." Proceed to the
    throughput gate (section 7, stage 2) and evaluate the change's practical wall-time effect using
    the frozen native 7-run `--bench-raw` protocol -- a small, real node-count decrease is only
    worth carrying to a correctness/strength gate if it produces a wall-time effect distinguishable
    from this baseline's own ~2% run-to-run spread (section 3 of the P16-2 report); if it doesn't,
    that in itself is a legitimate reason to stop before spending SPRT time, decided on the
    measured wall-time result, not on an arbitrary node-count percentage.
- **Clear throughput collapse unexplained by tree reduction:** investigate, then reject if
  unexplained. If 7-run median NPS drops by materially more than this baseline's own ~2% run-to-run
  spread and the node-count delta does not account for it (e.g. per-node cost itself got slower),
  that is a regression in the change's implementation, not a tradeoff worth accepting, and should
  be understood before any further step.
- **Otherwise:** proceed to exactly one isolated SPRT with frozen terms (elo0/elo1/alpha/beta fixed
  before the run starts, matching this project's existing SPRT convention), and no further search
  changes bundled into that same run.

## 9. Measurement-infrastructure cleanup identified (not part of this intervention)

`.github/workflows/nightly-sprt.yml` runs its gate at `elo0=0 elo1=50` (line 162) and, on
`H0_ACCEPTED`, both its summary text and its failing step describe the result as "regression
detected" (lines 207, 227). That's not what H0 acceptance means under these bounds: `elo0=0` is the
null hypothesis "true Elo is approximately 0 or below," and accepting it only means the SPRT failed
to demonstrate a gain of `elo1=50`; a genuinely neutral (0 Elo) patch is expected to accept H0 under
these exact bounds just as reliably as an actual regression would, since 0 sits inside H0's own
region rather than outside it. Calling that outcome "regression confirmed" overstates what the test
showed. This was already flagged as out-of-scope in #227's issue body ("noted for a separate fix")
and is repeated here because it directly matters for the future strength experiment in section 7,
stage 4: a neutral PVS result under the current wording would print as a false "regression
detected," which could cause a genuinely non-regressive PVS change to be rejected on a
mischaracterized verdict. This should be fixed as measurement-infrastructure cleanup before that
SPRT is run, tracked as its own issue, not folded into the PVS experiment itself.

## 10. Summary

Selected: **A, general PVS / zero-window sibling search.** No implementation in this step. The
preregistered scope (section 6), measurement plan (section 7), and stop rules (section 8) are
frozen; any deviation during implementation should be documented as a deviation from this
preregistration, not silently substituted. Phase 16's three planned steps are now all addressed:
P16-1 (TT correctness, closed via #227), P16-2 (canonical baseline, this document's own
prerequisite), and P16-3 (this document). No intervention has been implemented, and no SPRT has
been run.
