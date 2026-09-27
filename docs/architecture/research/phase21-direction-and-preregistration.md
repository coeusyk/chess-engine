# Phase 21: direction selection and preregistration

Status: planning document, written 2026-09-27 after Phase 20 closed. Nothing in this document has been implemented or measured. No code, tuning, benchmark, game or SPRT was run to write it. It selects one bounded phase, freezes its hypotheses, gates and stop rules, and records why the other directions were deferred. Any later change to a gate or threshold must be written down as an amendment before the affected stage runs, the same way Phase 20 handled its Stage 2 interpretation amendment.

Selected direction, in one line: **Phase 21 qualifies a corrected zero-window (PVS) search on the repaired Phase 18 baseline, with PV-node identity made consistent with the search window.** Branch per project convention: `phase/21-zero-window-search`.

## 1. Engine state after Phase 20

What the repository shows today, from source and from the Phase 16 to 20 records.

**Production search.** `Searcher.java` has not changed since the Phase 18 production reference `6afc523`. `git diff 6afc523 HEAD` on `engine-core/src/main` and `engine-uci/src/main` touches only `TranspositionTable.java` (Phase 20 diagnostics and the resize quiescence contract) and `UciApplication.java` (Phase 20 lifecycle repair). The search itself is negamax alpha-beta with aspiration windows at the root, iterative deepening, check extensions, a mating-threat leaf extension, pawn promotion extension, singular extensions, null-move pruning, razoring and futility pruning at depth 1 to 2, losing-capture pruning at depth 2 or less, LMR (depth >= 3, move index >= 4, quiet, not killer, not TT move, not in check, not giving check), killers, history, SEE ordering, and a pawn-keyed correction history. There is no general PVS, no reverse futility pruning, no late-move pruning, no internal iterative reduction, and no countermove or continuation history.

**Canonical 1T reference (P18-5).** Depth 13, 31 `BenchRunner.BENCH_FENS` positions, Threads=1, Hash=16 MB, Classical evaluator: 24,780,049 main nodes, identical across every run. The pre-Phase-18 figure was 73,089,246. Phase 18 did not attribute that difference per slice, and it has not been attributed since. The WSL2 timing for that run was median 73,471 ms / 337,276 NPS, which is descriptive only. The native Windows 1T separate-process NPS range measured in Phase 20 Stage 2 was 325,047 to 341,570.

**Profiling.** The last JFR attribution (P18-5, WSL2, instrumented) put `Board.makeMove` at 23.11% of method samples, `MoveOrderer.orderMoves` 11.79%, `Board.unmakeMove` 9.95%, `ClassicalEvaluator.evaluate` 6.33%, legal filtering 5.19% and evaluator mobility/attack work 5.12%, with the internal evaluator timer at 19.6%. The Phase 16 native category view (37.6% make/unmake, 18.5% evaluation, 14.5% ordering, 14.2% movegen) predates the Phase 18 repairs.

**Phase outcomes that bound this plan.**

- Phase 17: a PVS+LMR candidate cut depth-13 nodes by 44.07% and fixed-depth time by 44.63%, then accepted H0 in its SPRT (0 wins, 13 losses, 1 draw over 14 games, LLR -3.10, bounds [0, 50]). It was rejected and never merged.
- Phase 18: four search-contract repairs (null-move hash ownership, per-ply ordering metadata, singular-search bound semantics, root fail-high termination) and the canonical re-baseline. No games were played.
- Phase 19: established that the Phase 17 candidate's LMR verification gate `score > alpha && score < beta` can never pass at a null-window node, so reduced-depth scores were trusted unverified at most of the tree. A one-line counterfactual changed bestmove, score, node count and PV at all three diagnosed positions and re-enabled 10x to 35x more verification work. That this defect was the dominant cause of the Phase 17 loss is supported, not proven. The report says any corrected PVS/LMR work needs its own preregistration, its own mechanism/throughput/correctness/strength sequence and its own SPRT.
- Phase 20: SMP lifecycle repaired; fixed-depth Lazy SMP scaling and time-to-depth gains established; fixed-time depth gains observed; the decision-quality no-regression bound was not established and no strength test ran. `Threads=1` remains the production and reference setting. Phase 20 is closed and this plan does not reopen it.

**Infrastructure available.** Deterministic 31-position `--bench-raw` protocol (P16-2), five-position reference (P18-5), `[BENCH]` debug counters and `SearcherInstrumentationTest`, native Windows `sprt.ps1` and `match.ps1`, the Phase 20 Stage 3/6 harnesses with a deeper-1T-reference mechanism, and a nightly SPRT workflow whose H0 wording was already corrected (it no longer calls H0 acceptance a confirmed regression).

**Open tracker state.** One open issue, #181 (SPRT PGN post-processing), which is infrastructure and not blocking.

## 2. The finding that changes the picture

The P16-3 preregistration described the missing PVS as an efficiency gap: later siblings at PV nodes get a wider window than they need. Reading the current move loop again, the problem is also a contract inconsistency, and that part was not written down anywhere I could find.

In `alphaBeta` (`Searcher.java` lines 1074 to 1123) and `searchRoot` (lines 771 to 790):

- Every non-LMR move with `moveIndex > 0` is searched with the parent's full window `(-beta, -alpha)`, and `childIsPvNode = isPvNode && moveIndex == 0`, so it is flagged non-PV.
- An LMR move is probed at reduced depth with a null window. If that probe fails high, it is re-searched at full depth with the full window `(-beta, -alpha)`, again flagged non-PV (the move index is at least 4, so `moveIndex == 0` is always false there).
- The root does the same: moves after the first get the full aspiration window and `childIsPvNode = false`.

So at a PV node, every later sibling's subtree is searched with a wide window while flagged non-PV. The wide window then propagates down that subtree, because each of its nodes passes its own `(-beta, -alpha)` to its later children. Inside those subtrees, the non-PV gates are open: razoring (`canApplyRazoring`), futility (`canApplyFutilityPruning`) and losing-capture pruning (`canPruneLosingCapture`) all return early on `isPvNode` alone and never look at the window width. When one of those later siblings raises alpha at the root or at a PV node, its score and PV come from a search that was neither a true PV search nor a cheap null-window proof.

`dev-entries/phase-3.md` lines 149 to 150 explain how the engine got here. The first implementation derived PV status from window width. Without PVS, nearly every later sibling had a wide window, so nearly every node counted as PV, and futility and razoring barely fired. The fix at the time was to thread an explicit `isPvNode` flag instead. That flag was a workaround for the absence of zero-window search. With PVS in place, a later sibling gets a null window and is non-PV by construction, a PV re-search gets a wide window and is PV by construction, and the flag and the window agree again.

This matters for the selection in two ways. The PVS gap is not only about speed; the current arrangement also mixes PV-window scores with non-PV pruning decisions. And any corrected PVS has to settle PV identity as part of the same change, which is a reason to do the two together rather than bolt PVS onto the flag as it is.

## 3. Candidate directions considered

Grouped as the brief asked: correctness debt, demonstrated bottlenecks, speculative strength ideas, deferred research lines and infrastructure. No numeric scores are used.

### A. Corrected zero-window search with window-consistent PV identity (search correctness debt plus efficiency)

- **Evidence.** Source: section 2 above. History: P16-3 selected PVS as the intervention with a measurable mechanism; Phase 17's failure has a specific, located defect (Phase 19) in a verification gate the production code does not contain; Phase 19 explicitly left corrected PVS as future work needing its own gates. Phase 17's 44% node reduction shows the tree-size lever is large on this engine, though part of that number came from skipped verification work, so it is an upper bound on nothing.
- **Mechanism.** Later siblings at PV nodes are proved or refuted with a null window, which prunes more. Only moves that beat alpha at a PV node pay for a full-window re-search. PV identity follows the window, so non-PV pruning runs only under null windows.
- **Scope.** One method's move loop plus the root loop in `Searcher.java`, a few counters, and tests. No new data structures, no evaluator or board changes.
- **Measurement.** Exact node counts on the deterministic suite, verification and re-search counters, fixed-depth elapsed on native Windows, then an SPRT.
- **Confounders.** Pruning parameters were tuned under the current mixed regime; search regression fixtures record moves that will legitimately move; the SPRT bar is +50 Elo; the Phase 17 lesson that fewer nodes does not mean better play.
- **Now or later.** Now. It is the only candidate that combines a source-level defect, a deterministic mechanism metric, a small code surface and a direct path to a strength decision.

### B. Strength qualification of the post-Phase-18 engine against the pre-Phase-18 baseline (measurement only)

- **Evidence.** Phase 18 cut depth-13 nodes from 73,089,246 to 24,780,049 and played no games. Nobody has measured what the repairs did to playing strength.
- **Mechanism.** None to intervene on. It is an observation of four correctness repairs.
- **Scope.** One or two native SPRT runs.
- **Measurement.** SPRT between `ebe513e` and the current production jar.
- **Confounders.** The node reduction mixes four repairs with no per-slice attribution.
- **Now or later.** Deferred. The outcome would not change any decision: correctness repairs are not reverted for Elo, and every future candidate is tested against current production, not against `ebe513e`. It would be worth running as a side measurement if native SPRT time is idle, and it would help anyone reading the Phase 17 result against today's engine. It is not a phase.

### C. `makeMove`/`unmakeMove` throughput (demonstrated bottleneck)

- **Evidence.** Largest profile share in both P16-2 and P18-5 JFR runs (roughly a third of samples combined).
- **Mechanism.** Fewer cycles per node at identical node count.
- **Scope.** `Board.java` hot path; perft and node-count identity are strong gates.
- **Measurement.** Node counts must be identical; native 7-run elapsed.
- **Confounders.** WSL2 timing is not valid for this; JIT sensitivity; a large profile share says where time goes, not that the code is slow.
- **Now or later.** Deferred. The best plausible outcome is a single-digit to low-double-digit NPS change, which is smaller than the tree-size lever in A, and there is no specific inefficiency identified yet, only a share. If A is rejected early, C is the clean fallback because its gates need no games.

### D. Move ordering and missing standard pruning (speculative strength ideas)

- **Evidence.** The engine lacks RFP, LMP, IIR, countermove and continuation history. First-move cutoff rates were 78 to 98% before the Phase 18 repairs and have not been remeasured since.
- **Mechanism.** Each is a known technique elsewhere, but here there is no measured defect that any of them addresses.
- **Scope.** Each is small; together they are unbounded.
- **Confounders.** Every one of them interacts with PV identity and window width. RFP and LMP in particular are non-PV techniques. Adding them while later-sibling subtrees are wide-window and flagged non-PV would apply them in exactly the inconsistent regime section 2 describes.
- **Now or later.** Deferred until A settles PV identity. After A, these become cleaner single-change experiments.

### E. Transposition table behavior (deferred research line)

- **Evidence.** TT cutoffs are taken at PV nodes as well as non-PV nodes (`applyTtBound` has no PV guard). Hash=16 MB reached hashfull 1000 in a few Phase 20 SMP searches, but that was at 2T/4T. Phase 20 Stage 4 capacity characterization was omitted.
- **Mechanism.** Possible PV truncation and replacement pressure; nothing measured at 1T.
- **Now or later.** Deferred. There is no 1T evidence of a defect. PV-node TT cutoffs are listed as a non-goal of A so they do not confound it.

### F. Time management (deferred)

- **Evidence.** `TimeManager` uses a move-number divisor, soft/hard limits and a stability scale. Phase 20 Stage 6 found no time inflation. No defect has been reported.
- **Mechanism.** Unknown. Allocation changes can only be judged by games.
- **Now or later.** Deferred. It needs a strength test from the start, with no deterministic mechanism metric in front of it.

### G. NNUE roadmap (deferred research line)

- **Evidence.** Classical is production. The NNUE research arc closed several supervision-objective interventions as not promotable, and the net that E-5 would test showed severe miscalibration in #219.
- **Now or later.** Deferred. The cost is high, the blocking issue is training-side, and nothing in Phases 18 to 20 changed that.

### H. Evaluator cost (demonstrated share, no defect)

- **Evidence.** About 19 to 20% of time in `evaluate()`.
- **Now or later.** Deferred for the same reason as C, with the added requirement that any change pass the mirror-symmetry test.

### I. Infrastructure

- `BookFile`/`BookVariance` mutation while a search is live was left as a follow-up risk in Phase 20. It is a small UCI lifecycle item and should be its own issue, not a phase.
- `sprt.ps1` applies the same `Threads` to both engines. Only relevant if SMP returns, which it does not here.
- #181 PGN post-processing: open, unrelated to search decisions.

### J. SMP

Excluded by the brief and by the Phase 20 closure. Not considered further.

## 4. Selected Phase 21 problem

Production searches every later sibling of a PV node, and every LMR fail-high re-search, with the parent's full window while flagging the child non-PV. That wastes work that a null-window proof would avoid, and it applies non-PV pruning under wide windows. Phase 21 replaces this with a standard zero-window scheme in which the PV flag equals `beta - alpha > 1` at every call, every reduced-depth fail-high is verified at full depth under a null window before it is trusted, and only a PV node re-searches with a full window. The phase is qualified by deterministic mechanism gates first and a strength test last.

This is an implementation phase, not only a diagnostic, because the source already identifies the defect and the Phase 19 counterfactual already located the one way the previous attempt went wrong. Stage 1 below is still a pure diagnostic. It stops if the wide-window/non-PV population is zero, instrumentation contradicts the source model, or instrumentation changes deterministic behavior. Any nonzero population consistent with the source model proceeds later to Stages 2 and 3; the frozen node and time gates decide materiality.

## 5. Evidence supporting the selection

1. Source (current HEAD, `Searcher.java` unchanged since `6afc523`): the move-loop and root-loop window/flag assignments quoted in section 2.
2. `dev-entries/phase-3.md` lines 149 to 150: the PV flag was introduced because window width stopped meaning anything without PVS.
3. `phase16-p16-3-intervention-preregistration.md`: PVS selected over make/unmake, ordering and TT/SMP on mechanism grounds; its stop rules are reused below.
4. `phase17-closure.md`: the node lever is large on this engine; the candidate still failed.
5. `phase19-pvs-diagnostic-report.md` and `docs/engineering/investigations/2026-09-20-pvs-lmr-null-window-verification-gate.md`: the failure has a located, reproducible defect; the fix is known; a corrected attempt was explicitly left open.
6. `phase18-search-contract-qualification.md`: a deterministic 1T reference exists on a repaired baseline, so node-count comparisons carry no noise.

## 6. Explicit non-goals

- No change to LMR eligibility (`canApplyLmr`), the LMR reduction table, the `improving` adjustment, null-move conditions or reduction, razoring and futility margins, losing-capture pruning conditions, singular-extension logic, check/promotion/mating-threat extensions, aspiration-window widths, quiescence search, move ordering, killers, history, correction history, TT replacement, TT probing rules (including cutoffs at PV nodes) or time management.
- No new pruning technique (RFP, LMP, IIR, ProbCut) and no new history table.
- No evaluator, `Board`, move generator or NNUE change.
- No SMP work. Helpers run the same `alphaBeta`, so the change reaches them, but no SMP measurement or qualification is part of this phase and `Threads=1` stays the reference.
- No parameter tuning, before or after the change.
- No attempt to reconstruct or reuse the Phase 17 branch. The implementation is written fresh against current production, and Phase 17 remains archival evidence only.
- No attribution of the Phase 18 node-count change (candidate B).
- No change to the SPRT merge bar inside this phase. See section 11.

## 7. Hypotheses

- **H1 (contract population).** On the canonical suite at depth 13, calls with a wide window while flagged non-PV occur, and non-PV pruning fires inside that population. Stage 1 measures alphaBeta invocations and pruning events, with the call denominator labeled separately from main nodes. A zero population or evidence contradicting the source model stops the phase (section 10). No population-size threshold is applied.
- **H2 (mechanism).** Most full-window later-sibling searches at wide-window nodes return a score at or below alpha, so a null-window search would establish the same bound. PVS pays off only when refutation is more common than improvement, so this is the condition that matters.
- **H3 (tree size).** The corrected scheme searches fewer depth-13 main nodes than 24,780,049 on the canonical suite, while full-depth LMR verification work stays present. It must not collapse toward zero the way it did in Phase 17.
- **H4 (time).** Fixed-depth elapsed time on native Windows falls outside the baseline 7-run spread.
- **H5 (strength).** The corrected scheme is not weaker than production and is expected to be stronger. This is tested only if H3 and H4 hold and the correctness gates pass.

The competing explanation I have to take seriously: Phase 17's loss was caused, fully or partly, by PVS itself interacting badly with this engine's pruning, which was tuned while later-sibling subtrees were wide and non-PV. Under PVS those subtrees become null-window, so null-move pruning (`staticEval >= beta`) will fire far more often in them than before. If that is the real cause, H3 and H4 can pass and H5 can still fail. Section 12 treats this as the highest-risk assumption.

## 8. Baseline and control

- **Control commit.** Current production search. `Searcher.java` is unchanged since `6afc523`. Before Stage 0, inspect the complete diff between `develop` and `phase/20-smp-qualification` and determine whether the full Phase 20 branch is intended to land under repository convention. Integrate through the normal reviewed PR path only if that intent is established. If archival branch-only material should be excluded or its integration is unresolved, stop and report the exact decision required. Phase 21 branches from the exact accepted and verified `develop` head. The control jar is built from that commit, and its SHA-256 is recorded.
- **Semantic reference.** 24,780,049 main nodes at depth 13 over the 31 positions, plus the P18-5 five-position depth-8 table (moves, scores, nodes, qnodes, TT hits, PVs). Both must reproduce on the control commit before anything else happens, because `TranspositionTable.java` changed in Phase 20.
- **Timing reference.** Native Windows only (Ryzen 7 7700X host, Zulu JDK 21.0.10, Balanced plan, no affinity, the Phase 20 Stage 2/3/6 environment): one discarded warm-up plus seven `--bench-raw` runs of the control jar in the same session as the candidate. WSL2 timing is not used for any Phase 21 decision.
- **Strength reference.** The control jar, through `sprt.ps1` on native Windows, Threads=1, Hash=16 MB, the standard opening corpus and TC.

## 9. Staged plan

Every stage produces a dev entry in `dev-entries/phase-21.md` and is committed separately. No stage starts before the previous one's gate is recorded.

### Stage 0: freeze and reproduce

1. Resolve the complete Phase 20 integration scope, integrate through the normal reviewed PR path, verify the accepted `develop` head, and cut `phase/21-zero-window-search` from that exact head.
2. Build the control jar and record its SHA-256.
3. Reproduce the 31-position depth-13 node total and the five-position reference (WSL2 is fine for node counts).
4. Run `mvn -pl engine-core test` and the engine-core/engine-uci/engine-tuner reactor build and record the results.

Gate: exact reproduction. Any node or PV mismatch stops the phase until it is explained.

### Stage 1: diagnostic instrumentation, no behavior change

Add opt-in counters to `Searcher` following the existing `lmrApplications` pattern: plain `long` fields, reset per iteration, summed into the `[BENCH]` totals, no allocation.

- alphaBeta invocation classification into a 2x2 table: flag PV or non-PV, crossed with window wide (`beta - alpha > 1`) or null. Define the counted call population and label its denominator separately from `nodesVisited`.
- Non-PV pruning events fired at wide-window nodes: razoring returns, futility skips, losing-capture skips.
- Later-sibling full-window searches at PV/wide nodes (`moveIndex > 0`, non-LMR branch), split by outcome: returned at or below alpha, inside the window, or at or above beta.
- LMR reduced probes, reduced-probe fail-highs and the current full-depth re-searches that follow them.
- The same later-sibling split at the root.

Gate: node totals and the complete five-position move/score/nodes/qnodes/TT-hit/PV reference are identical to Stage 0, and normal tests remain green. Stage 1 stops only if the wide-window/non-PV population is zero, instrumentation contradicts the source model, or instrumentation changes deterministic behavior. Otherwise proceed later to Stages 2 and 3 and let the frozen node/time gates decide materiality. Output: per-position and aggregate classification, pruning, later-sibling, LMR and root outcome tables, recorded in the dev entry. This is mechanism population evidence for H1 and H2 only. Do not estimate strength or predict Phase 21 node reduction from Phase 17.

### Stage 2: implementation, one commit

After Stage 1 measures the current flag/window mismatch, make the alpha-beta window the sole source of PV identity. Prefer removing the explicit `isPvNode` parameter from `alphaBeta` and deriving `isPvNode = beta - alpha > 1` at function entry. All recursive call sites then express semantics only through their search window. Inspect every auxiliary alphaBeta call first. If any intentionally needs wide-window/non-PV semantics, STOP and report it before changing the API. No signature or search-behavior change is authorized during Stages 0 and 1.

Stage 2 changes are limited to that signature and entry derivation, the corresponding call-site argument removal, the move loop in `alphaBeta`, and the loop in `searchRoot`:

1. `moveIndex == 0` at any node: full window; child PV identity is derived from that window.
2. `moveIndex > 0`, LMR-eligible: reduced-depth null-window probe `(-(alpha + 1), -alpha)`. If it returns `score > alpha`, a full-depth null-window verification `(-(alpha + 1), -alpha)`. The gate is `score > alpha` only, never involving `beta`; this is the Phase 19 fix, and it is written from scratch, not ported.
3. `moveIndex > 0`, not LMR-eligible: full-depth null-window search `(-(alpha + 1), -alpha)`.
4. After step 2 or 3, only when the node is a PV node and `alpha < score < beta`: full-depth full-window re-search `(-beta, -alpha)`, which makes the child PV by its window.
5. The root follows the same pattern inside its aspiration window. Aspiration fail-low and fail-high handling is unchanged.
6. Every alphaBeta call expresses PV semantics through its window alone. Null-move and singularity searches retain their null windows. Razoring qsearch windows are unchanged.

At a non-PV node the window is already null, so steps 3 and 4 collapse to what production already does, except that the LMR full-depth re-search in step 2 now uses the null window explicitly instead of `(-beta, -alpha)`. At a null-window node these are the same window, so non-PV behavior should be unchanged. Stage 3 checks that claim.

Tests added in the same commit:

- A consistency counter: calls where the PV flag differs from `beta - alpha > 1` must be zero over the five-position reference and the 31-position suite.
- A verification-completeness counter: every non-aborted reduced-probe fail-high is followed by a full-depth null-window verification. The two counts must be equal. This is the direct regression test for the Phase 19 defect.
- A PV-only re-search check: no full-window re-search at a non-PV node.
- PV legality over the five-position reference and the regression suite.
- The existing search regression profile. Expected-move changes are allowed but each one is checked with a depth probe before its fixture is updated, following the project's existing practice for stale fixtures.

Perft and mirror-symmetry tests are not affected by this change, but perft runs anyway as part of the full suite.

### Stage 3: deterministic mechanism gate (WSL2 acceptable)

Measured on the candidate with the Stage 1 counters retained:

- Aggregate and per-position depth-13 main nodes, qnodes and TT hits, against Stage 0.
- The Stage 1 table again. The wide-window/non-PV cell must now be empty, and the consistency counter must be zero.
- Full-window re-search rate at PV nodes.
- LMR verification counts, compared with Stage 1's re-search counts.

Decision rules, reusing P16-3's stop rules:

- Node total increases: reject.
- Node total unchanged: reject.
- Node total decreases: proceed to Stage 4. No post-hoc magnitude threshold is added.
- Consistency or verification-completeness counter nonzero: reject as a correctness failure.
- Full-window re-searches fire on most null-window probes at PV nodes: reject regardless of the node count, because the null window is not doing its job.
- LMR verification activity drops by an order of magnitude against the Stage 1 re-search count: stop and investigate before proceeding. That is the Phase 17 signature. It can have an innocent explanation, since fewer wide-window nodes reach the LMR branch, so this is a stop-and-explain rule, not an automatic reject.

### Stage 4: decision-quality trace (diagnostic, not a statistical gate)

For each of the five reference positions and each of the 31 suite positions, compare the candidate's depth-13 bestmove and score with the control's. For every changed bestmove, run both engines one and two plies deeper on that position and record whether the difference is an equal-score alternative, a transposition, or a score change that persists or reverses with depth. Reuse the Phase 20 Stage 6 deeper-reference mechanism where it fits.

Gate: the phase stops only if a traced difference exposes a defect, such as an unverified reduced score being returned, a corrupted PV, or a mate score changing sign. Differences on their own are expected and are not failures.

This is a deliberate change from Phase 20 Stage 6, which made a strict agreement lower bound on 31 positions a hard no-regression gate. With 31 positions, a single disagreement already pushes that lower bound below zero (Stage 6's bounds were in steps of 1/31, about 0.032), so the gate could only pass with near-perfect agreement. Phase 21 uses this stage to find bugs before games and leaves the no-regression question to the SPRT.

### Stage 5: native throughput gate

On native Windows, in one session: warm-up plus seven `--bench-raw` runs each for control and candidate, interleaved or back to back as the P16-2 protocol specifies.

- Primary: median fixed-depth elapsed. The candidate's median must fall below the control's observed 7-run range (H4).
- Secondary: NPS. Per-node cost can rise slightly, because null-window nodes cut earlier and re-searches revisit nodes, so NPS alone is not a pass or fail signal. The project floor (aggregate NPS >= 301,116 on native Windows) still applies and is checked.

If elapsed does not improve beyond the control range, the phase stops before the SPRT even though nodes decreased, per the P16-3 rule that a small real node decrease is only worth games if it shows up in wall time.

### Stage 6: strength test

Only after Stages 3 to 5 pass. The current project SPRT policy remains in force. Any alternative strength gate must be decided and preregistered separately before Stage 6, after Stages 1 to 5 have produced evidence (section 11). The SPRT runs on native Windows only. Under the project rule, at this point the exact `sprt.ps1` command is written out and execution stops for the native run. It is never simulated or skipped.

## 10. Decision gates and stop conditions, collected

| Point | Proceed if | Stop or reject if |
|---|---|---|
| Stage 0 | Exact node and five-position reproduction | Any mismatch (stop until explained) |
| Stage 1 | Deterministic behavior is identical; nonzero wide-window/non-PV call population agrees with the source model | Wide-window/non-PV population is zero; instrumentation contradicts the source model; or instrumentation changes deterministic behavior |
| Stage 2 | All tests green, consistency and completeness counters zero | Any correctness failure not fixable within the Stage 2 scope |
| Stage 3 | Node total decreases, re-search rate not pathological, verification activity explained | Increase, unchanged, nonzero consistency counters, pathological re-search, unexplained verification collapse |
| Stage 4 | No defect found in traced differences | A defect is found (fix and restart from Stage 3, once; a second defect closes the phase) |
| Stage 5 | Median elapsed below control range; NPS floor held | Elapsed within control range, or NPS floor violated |
| Stage 6 | H1 accepted under the frozen bounds: promote | H0 accepted: reject, record, no rescue SPRT under changed bounds |

Global stop rules:

- Any change outside the files and methods named in Stage 2 (other than tests and counters) is scope creep. It gets its own issue instead.
- No parameter re-tuning to rescue a failing gate. If Stage 6 fails and the evidence points at pruning margins tuned under the old regime, that becomes the preregistered hypothesis for a later phase, not an extension of this one.
- At most one restart after a Stage 4 defect.

## 11. What would justify a strength test, and the bounds question

A strength test is justified when all of the following are true:

1. Stage 3 shows a node decrease with zero consistency and completeness violations and a non-pathological re-search rate.
2. Stage 4 finds no defect in any traced decision difference.
3. Stage 5 shows fixed-depth elapsed below the control's range on native Windows.
4. The current project SPRT policy is retained, or an alternative strength gate has been separately decided and preregistered after Stages 1 to 5 and before Stage 6.

The project's documented merge bar is SPRT [elo0 = 0, elo1 = 50], alpha = beta = 0.05, TC 5+0.05 (`docs/sprt-guidelines.md`, `nightly-sprt.yml`). This policy remains unchanged during Stages 0 and 1. A correct PVS on a mature alpha-beta engine could plausibly gain less than 50 Elo. Under [0, 50], a real but smaller gain can end in H0 acceptance, which would reject a correct and beneficial change. That is a project-policy decision. After Stages 1 to 5 produce evidence, an alternative strength gate may be considered, but it must be decided and preregistered separately before Stage 6:

- keep [0, 50] and accept that a smaller real gain will be rejected, or
- amend `docs/sprt-guidelines.md` for search-contract changes to a narrower gain hypothesis (for example [0, 10]), accepting longer runs, or
- run a non-regression SPRT (for example [-5, 0] style bounds) as the gate for a change that is also a contract repair, arguing that a correctness-motivated change needs to show it is not worse rather than that it is better.

These are alternatives for a later policy decision, not amendments made by this plan. Until a separate preregistration is accepted, the current project policy applies.

## 12. Risks and unresolved assumptions

**Highest-risk assumption.** That Phase 17's strength loss came mainly from the verification hole Phase 19 found, and not from zero-window search interacting badly with this engine's pruning. Phase 19 established that the hole existed and mattered at three positions. It did not establish that it explained the 0-13-1 result. The specific mechanism for the alternative: once later-sibling subtrees get null windows, null-move pruning, razoring and futility fire under conditions they were never tuned for, because their margins were tuned when those subtrees had wide windows. Stages 3 to 5 cannot detect this. Only Stage 6 can. Mitigation: Stage 1 and Stage 3 record pruning-event counts before and after, so a Stage 6 failure can be analyzed without new instrumentation.

Other risks and assumptions:

- **Stage 1 may contradict the case.** A zero wide-window/non-PV population or instrumentation that contradicts the source model stops the phase. A small nonzero population is mechanism evidence; the frozen node/time gates later decide its materiality.
- **Phase 17's numbers are not a prediction.** The 44% node reduction included the unverified-score shortcut. The corrected scheme will reduce less, possibly much less.
- **Non-PV behavior claim.** Section 9 Stage 2 says non-PV nodes should behave identically. That holds only if every non-PV node already has a null window, and today it does not (the subtrees in section 2). Behavior inside those subtrees is supposed to change. What should not change is behavior at nodes that already had null windows. Stage 3's per-position counters are the check.
- **Search regression fixtures.** Expected moves will shift. Each shift must be verified by a depth probe before a fixture changes. Updating fixtures to match output without checking would hide a defect.
- **TT cutoffs at PV nodes.** Left unchanged on purpose. They can truncate PVs and interact with re-searches (a null-window probe stores a bound entry that the subsequent full-window search then probes). `applyTtBound` respects bounds, so this should be safe, but PV-truncation effects will appear in Stage 4 traces and must not be misread as defects.
- **Aspiration interaction.** Root re-searches inside a narrow aspiration window can produce fail-high/fail-low sequences that did not happen before. P18-4's root fail-high termination contract must still hold; its regression tests stay in the suite.
- **SMP helpers run the same code.** Out of scope, but nothing in Phase 21 may break the Phase 20 lifecycle tests, which stay in the suite.
- **Native Windows availability.** Stages 5 and 6 need the native host. Without it, the phase can complete Stages 0 to 4 and then has to wait.
- **The unattributed Phase 18 node change.** The baseline is valid as a semantic reference whatever caused that change. Its strength relative to `ebe513e` is unknown (candidate B). This does not affect Phase 21's comparison, which is candidate against current production.

## 13. Why the other directions are deferred

- **B (post-Phase-18 strength check):** its result would not change what gets done next, since correctness repairs stay and all future SPRTs compare against current production. Worth running as a side measurement when native SPRT time is free.
- **C (make/unmake throughput):** a profile share with no identified inefficiency, a smaller expected effect than A's tree-size lever, and no dependence on A. It is the fallback if Stage 1 closes the phase early.
- **D (ordering and new pruning):** depends on A. Adding non-PV techniques before PV identity is consistent would put them in the same mixed regime section 2 describes.
- **E (TT):** no 1T evidence of a defect. PV-node TT cutoffs are a named non-goal so they do not confound A.
- **F (time management):** no reported defect and no deterministic metric; it would need games from the start.
- **G (NNUE):** blocked on training-side calibration, high cost, nothing new since Phase 15.
- **H (evaluator cost):** same reasoning as C, plus mirror-symmetry obligations.
- **I (infrastructure):** `BookFile`/`BookVariance` should become its own small issue; #181 is unrelated.
- **J (SMP):** closed by Phase 20 and excluded by the brief.

## 14. First execution slice

Stage 0 and Stage 1 together, in two commits on `phase/21-zero-window-search`:

1. Inspect and resolve the full Phase 20 integration scope, integrate through the normal reviewed PR path, verify the accepted `develop` head, branch from it, build the control jar, record its hash, and reproduce 24,780,049 nodes plus the complete five-position reference. Record the results in `dev-entries/phase-21.md` (and add that file to `dev-entries/README.md`).
2. Add the Stage 1 counters (opt-in, allocation-free, following the `lmrApplications` pattern), prove the node totals and five-position reference are unchanged, and record the per-position and aggregate 2x2 table plus the later-sibling outcome split.

No search-behavior change in either commit. Commit Stage 0 separately from Stage 1 instrumentation, push the Phase 21 branch, and verify local HEAD equals origin with a clean tracked tree. Leave unrelated `.claude/agent-memory/` untouched. Stop after Stage 1. Do not implement PVS, change the alphaBeta signature, run native timing, or run games or SPRT. A Stage 1 PASS records eligibility for later work; it does not authorize Stage 2.
