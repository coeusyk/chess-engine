# NNUE Improvement Analysis: Data, Labels, and a Sequenced Experiment Plan

Phase E (NNUE), net `dfffd3da-7f8f-4fc9-92dc-b3873c97fb21`. This document supersedes the
prioritization in the initial architecture review (chat-only, 2026-07-19 AM) after that
review's own conclusions were critiqued and re-graded against repository evidence. It
distinguishes **established facts** (directly measured or directly read from code) from
**hypotheses** (graded by evidence strength) from **open questions** (no evidence either
way), and lays out a sequenced, falsifiable experiment plan rather than a single
prioritized-by-guess roadmap.

Related: `docs/NNUE_PRD.md`, `docs/architecture/research/DR-E1-self-play-data-generation.md`,
`docs/architecture/research/DR-D8-stockfish-labeling-driver.md`,
`docs/architecture/research/2026-07-16-mining-played-games-for-training-data.md`,
`tools/nnue-gauntlet-e4.md`, `nets/HISTORY.md`, GitHub issue #206 (E-6, Performance Gate).

---

## 1. Established facts (directly measured or read from code)

- Net `dfffd3da-...`: 36,000 training positions (20k Lichess Stage 1 + 20k Stockfish-relabeled
  Stage 2, 90/10 train/held-out split), 2000 steps @ batch_size=256 (~14.2 epochs over the
  corpus), Adam, fixed LR=0.01, no LR schedule, no per-epoch reshuffle, no early stopping, no
  L2/dropout (only an overflow-safety weight clamp for quantization). Held-out loss 0.0823,
  label correlation 0.504 (n=4000), mean abs eval error vs. classical 735.6cp (n=393).
  Full run completes in ~20 seconds (`trainer/configs/train-e3-real.md:126`).
- Architecture: plain 768 dual-perspective features (no HalfKP/king-buckets — `ADR-001`),
  `(768→256)×2→1`, single hidden layer, clipped-ReLU, `qa=127`/`qb=64`/`outputScale=400`.
- Targets: pure `eval_cp` regression via `texel_sigmoid(cp, K=2.773456)`, K freshly calibrated
  against the live classical `EvalParams`. No WDL/λ-blend is implemented — `train.py`'s own
  module docstring states only the eval_cp half of the PRD's designed blend exists; every
  reachable `DatasetProvider` has no WDL field populated.
- Stage 2 Stockfish labeling: `nodes=25000`, `threads=1`, **MultiPV not pinned** (Stockfish's
  UCI default of 1 governs, undocumented as a deliberate choice —
  `trainer/configs/stockfish-label-e2-real.md`), **no re-search or PV-stability check** — one
  fixed-node search per position, no confidence/variance metric. `DR-D8` names this gap
  explicitly as deferred, not overlooked.
- Mate scores are stored raw (`eval_mate`, ply count) and converted only at training time to a
  flat `MATE_EQUIVALENT_CP = 3000.0` regardless of mate distance (mate-in-1 == mate-in-20).
- Stage 1 (Lichess) has **zero quiet/tactical filtering** (`acquire_stage1_lichess.py`
  `_to_record()` only checks eval/PV presence). Stage 2 sources from `data/quiet-labeled.epd`
  (Zurichess quiet-filter, every 36th line) but **never re-verifies quietness after
  re-labeling** at a different node budget than the original corpus was built with.
- **Deduplication and phase-balancing code exists, is exported, and is never called.**
  `trainer/trainer/dataset/transform.py` implements `deduplicate()`, `phase_of()`,
  `balance_phases()`, `filter_by_ply_range()` — none are invoked by
  `train_candidate_net.py`'s `combine_and_split()`, the function that actually produced the
  36k-position training set. No duplicate-rate, phase-distribution, material-imbalance, or
  side-to-move-balance number has ever been computed for this corpus.
- `data/quiet-labeled.epd` (Stage 2's own position source, 725,000 lines) carries a `c9`
  game-result field on every line. Stage 2 explicitly discards it
  (`stockfish-label-e2-real.md:55-56`) in favor of a fresh Stockfish eval. `PositionLabel.wdl`
  already exists as a contract field (`trainer/trainer/contracts/dataset.py:96-98`),
  unpopulated by any current provider; `SHARD_DTYPE` has no wdl column yet
  (`mmap_shard.py:59-67`, explicit "out of scope until that stage lands" docstring).
- `train()` computes a full per-step training-loss list and it is thrown away — only the final
  value is printed (`train_candidate_net.py:124`). Held-out loss is computed **exactly once**,
  post-hoc, after all 2000 steps (`validator.py:73-91`) — there is no intermediate validation
  loss at any point during training.
- `engine-tuner`'s classical Texel-tuning pipeline already has convergence-based early stopping
  (`GradientDescent.java:40-42`, relative-delta threshold `5e-4` sustained over a patience
  window) and an explicit, numerically-thresholded train/val overfitting check
  (`TunerMain.java:347-354`, `msGap > 0.002` triggers a warning). None of this pattern is
  reused by the NNUE trainer.
- A fixed-depth NPS/eval-throughput benchmark harness already exists and is fully built:
  `engine-core/src/test/java/.../nnue/NnueCorpusBenchmarkTest.java` (`evalThroughput()`,
  `fixedDepthNps()`). It has never been run against the real candidate net — only against a
  tiny synthetic fixture — and is not wired into CI.
- The PRD's own performance gate (NNUE search ≥40% of classical NPS) has **never been measured
  or recorded anywhere in this repo, on any platform.** Confirmed via `gh issue view 206`
  (OPEN) and the release report's own "deferred to E-6, not measured or gated here" statement.
- Fast gauntlet (TC=10+0.1, unbounded depth within the clock): NNUE loses decisively across two
  independent runs (0-95-5, Elo diff -636.4±224.0; and 1-87-12, -449.4±107.9 — overlapping
  confidence intervals, same underlying result, not a new regression).
- Slow SPRT (TC=60+0.6, H0=0/H1=+10, α=β=0.05, bounds ±2.94): two checkpoints, both trending
  toward H0 — 1039 games (0.496 score, LLR -1.25) and 2617 games (0.502 score, LLR -1.32),
  manually stopped by the operator before resolution, not exhausted by design.

## 2. The central unexplained anomaly

**The fast gauntlet shows the net losing by ~450–640 Elo. The slow SPRT shows it at
approximately parity (LLR trending to exactly 0).** Both measure the same net. This gap is the
single most important open question in the pipeline and was under-weighted in the first-pass
review (filed as a footnote rather than the headline finding it is). Two explanations are
plausible and not yet distinguished:

1. NNUE-mode search has a real per-node cost penalty (unmeasured — see §1) that starves search
   depth at TC=10+0.1 far more than at TC=60+0.6, and the fast gauntlet is measuring depth
   starvation more than eval quality.
2. The net's eval quality really is that much weaker, and the SPRT's near-0 result is itself
   the artifact — the SPRT's tight ±10 Elo bound, run at high concurrency, might be dominated
   by search-quality convergence (both engines eventually find similar moves at 60+0.6 given
   enough search depth) even when the raw evaluator is much worse in isolation.

Experiments 1–2 below are designed specifically to resolve which of these is true, or how much
of each is in play.

## 3. Hypothesis evidence grading (critique of the first-pass review)

| Conclusion | Grade | Basis |
|---|---|---|
| Data scale (36k positions) is *the* dominant bottleneck, top priority | **Weak hypothesis** (was overstated as near-proven in the first-pass review) | No controlled comparison exists at any other data volume. Grounded in generic NNUE-literature priors, not a repo measurement. Downgraded from "priority #1, near-zero risk" — the "near-zero risk" framing was itself wrong (a 10-100x re-label run costs hours with no diagnostic in place to interpret the result). |
| Architecture depth / HalfKP premature for v1 | **Strong hypothesis** | Grounded in a real, explicit repo fact: `docs/NNUE_PRD.md` "Non-Goals (v1)" and `ADR-001` deliberately exclude both, for stated reasons (data requirement, accumulator-refresh complexity). Sequencing "diagnose before restructuring" survives the critique even though "data is the answer" does not. |
| NPS cost confounds the fast gauntlet | **Weak hypothesis, correctly flagged as unmeasured, but previously mis-stated as "partly attributed"** | No NPS number exists for NNUE mode anywhere. This is a plausible confound with existing-but-unrun infrastructure to test it (§1), not a finding. |
| SPRT bounds "under-powered" at 2617 games | **Retracted — was a mischaracterization** | The SPRT was manually stopped, not exhausted by an under-powered design; LLR was trending toward H0 (evidence of no effect), not stuck ambiguously. The real finding is the gauntlet/SPRT discrepancy (§2), not that the SPRT needed more games. |
| LR schedule / no-reshuffle is second-order | **Weak hypothesis**, correct conclusion for the wrong stated reason | No LR ablation exists. Should be deprioritized because no instrumentation yet exists to tell whether training is even near a minimum (§4), not because LR scheduling is inherently low-value. |
| Pure cp-regression (no WDL) may be leaving signal on the table | **Strong hypothesis, upgraded this pass** | A WDL signal (`c9` on 725k Stage-2-source positions) already exists on disk, unused, for free — this is now a scoped, buildable experiment (Exp. 6), not speculation. |

## 4. Falsifiable diagnostic criteria (architecture- vs. data- vs. label- vs. optimizer-limited)

Requires only: log train loss every N steps (already computed, currently discarded) and
held-out loss every N steps (currently computed once, post-hoc). No architecture or data
change needed to collect this.

1. **Data-limited, not yet plateaued**: train loss still falling >5e-4 relative over the
   trailing 200 steps at step 2000, AND held-out loss tracks train loss within a ~15% relative
   gap → more steps/data plausibly still help.
2. **Overfitting / architecture-or-regularization-limited**: train loss keeps falling while
   held-out loss flattens or rises (the `msGap`-style divergence `engine-tuner` already checks
   for elsewhere in this repo) → more data is the wrong first move; check reshuffle-per-epoch
   and regularization before scaling.
3. **Label-limited**: held-out loss plateaus early and label correlation (0.504 today) stays
   low even as train loss keeps falling → labels carry noise the model can't out-learn.
   Falsifiable directly via Experiment 5's node-budget-variance measurement.
4. **Optimizer-limited**: train loss oscillates/diverges rather than monotonically decreasing
   at fixed LR=0.01 → checkable **today** from the already-computed, currently-discarded loss
   list, zero new instrumentation required.

## 5. Evaluation framework: separating evaluator quality from search quality

- **Fixed-depth / fixed-node NPS and eval-throughput** (`NnueCorpusBenchmarkTest`, already
  built, never run against the real net) isolates eval-function cost from search-depth
  reached under a clock. This is what the PRD's ≥40% gate is actually asking for, and answers
  it directly — no new design work needed, only execution (issue #206 / E-6).
- **Fixed-TC gauntlet/SPRT** (already run twice) is the end-to-end signal: eval quality +
  NPS cost + reachable search depth, conflated by design. Useful as a final promotion gate,
  not as a diagnostic on its own.
- **Regression-metrics checklist every training run should produce** (checked against what
  exists today):

  | Metric | Exists today? |
  |---|---|
  | Train loss vs. step (curve) | Computed, discarded |
  | Held-out loss vs. step (curve) | Absent — post-hoc single value only |
  | Train/val gap with a numeric threshold | Absent (precedent exists in `TunerMain.java`) |
  | Label (Pearson) correlation | Exists, post-hoc |
  | MAE vs. training label itself | Absent (only MAE vs. a separate classical-golden corpus) |
  | RMSE | Absent |
  | Phase-specific accuracy (opening/mid/endgame) | Absent, despite `phase_of()` existing unused |
  | Calibration (reliability curve) | Named as deferred future work in `validator.py`'s own docstring |
  | Fixed-depth NPS/throughput | Built, unrun against real net |

## 6. Sequenced experiment plan

Ordered for information-gained-per-engineering-cost. Instrumentation and measurement come
before any data-scaling or architecture change.

| # | Experiment | Hypothesis | Effort | Runtime | Success criteria | If successful | If unsuccessful |
|---|---|---|---|---|---|---|---|
| 1 | Run existing fixed-depth NPS benchmark against real net (closes #206/E-6) | NNUE search meets/fails the ≥40% NPS gate; result bounds how much of the fast-gauntlet loss is NPS-attributable | Near-zero (infra exists) | Minutes | Recorded NPS number + pass/fail | Gate passes → -636 Elo is real eval weakness, raise priority of Exp. 5/6 | Gate fails badly → re-run Exp. 2 before trusting any gauntlet result |
| 2 | Re-run E-4 gauntlet at fixed depth instead of fixed TC | A fixed-depth gauntlet shrinks the Elo gap toward the SPRT's near-0 result if NPS was the confound | Low | ~1hr range | Elo number comparable to -636 and to the SPRT's ~0 | Gap shrinks → NPS/search-depth was the dominant confound, redirect to performance engineering | Gap stays near -636 → eval weakness is real and depth-independent, deprioritize NPS work |
| 3 | Wire existing `deduplicate`/`phase_of`/`balance_phases` into `combine_and_split` as a reporting pass | Current 36k set has a hidden duplicate-leak or phase skew | Near-zero (code exists, unused) | Seconds | Duplicate rate, phase distribution report | Non-trivial skew found → re-run training with dedup/balance before any data-scale conclusion | Negligible → rules out this confound cheaply |
| 4 | Persist train-loss curve + add held-out-loss-vs-step logging | Loss curve shape distinguishes data/architecture/label/optimizer-limited per §4 | Low | ~same as existing run (~20s + trivial eval overhead) | `(step, train_loss, held_out_loss)` table for the existing run, bit-identical seed | Still-decreasing + tight gap → proceed to Exp. 7 | Diverging gap → investigate reshuffle/regularization before scaling data |
| 5 | Label-noise floor: re-label same 1,000 positions at two node budgets (25k vs 50k) | Single fixed-node labels carry non-trivial variance, bounding achievable held-out loss | Low | Minutes | Distribution of \|eval_25k − eval_50k\| in cp | Large spread → reprioritize toward higher node budget / stability check over raw volume | Small spread → strengthens case for Exp. 6/7 as the real levers |
| 6 | Add free WDL signal (`c9`) to Stage 2, train λ-blended variant on the *same* 36k corpus | Blended target improves held-out metrics at fixed data volume | Medium (extend `SHARD_DTYPE`, thread `c9` through, implement blend in `train.py`) | Same as existing (~20s) + one-time format work | Held-out loss/correlation, blended vs. cp-only, controlled A/B | Improves → target formulation was leaving signal on the table, reprioritize above pure scaling | No change → strengthens the case that volume is the real bottleneck |
| 7 | Data-scale ablation as a log-spaced curve (40k → 100k → 400k), not one 10-100x jump | Held-out metrics improve monotonically or plateau as a function of corpus size | Medium-high (hours of labeling compute) | Hours, scaling with size | Curve of held-out loss/correlation/eval-error at each scale point, each diagnosable via Exp. 4's logging | Still improving at largest scale → continue scaling | Plateaus early → data volume was not the dominant lever, revisit architecture/HalfKP with a real ADR |

## 7. Roadmap (not sorted by expected Elo alone — front-loads uncertainty reduction)

| Improvement | Expected Elo gain | Effort | Risk | Prerequisites | Information gained |
|---|---|---|---|---|---|
| Run existing NPS benchmark against real net (Exp. 1 / #206) | None directly | Near-zero | None | Real `.nnue` file (exists) | Resolves the single highest-value unknown: is the fast-gauntlet loss an NPS artifact or real |
| Fixed-depth gauntlet re-run (Exp. 2) | None directly | Low | Low | Exp. 1 | Isolates eval quality from search-depth confound |
| Wire dedup/phase-balance reporting (Exp. 3) | None directly | Near-zero | None | None | Confirms/refutes a hidden data-quality confound |
| Persist loss curves (Exp. 4) | None directly | Low | None | None | Prerequisite for correctly interpreting Exp. 6/7 |
| Label-noise floor (Exp. 5) | None directly | Low | None | None | Establishes a hard floor on achievable held-out loss |
| WDL-blend from `c9` (Exp. 6) | Unknown, plausible (Stockfish/nnue-pytorch precedent) | Medium | Low-medium (touches training-target contract; re-run mirror-symmetry/regression suite per CLAUDE.md) | Exp. 4 | Answers "is target formulation the bottleneck," independent of volume |
| Data-scale ablation (Exp. 7) | Unknown — previously asserted as "large" without support | Medium-high | Low (compute cost only) | Exp. 1–6 ideally first | The actual data needed before "scale 10-100x" is anything but a guess |
| Re-verify quietness of relabeled `quiet-labeled.epd` positions | Unknown | Medium | Low | None | Bounds label noise from a second angle beyond Exp. 5 |
| HalfKP / king-buckets | Plausibly large per literature; excluded from v1 by design (ADR-001) | High | Medium-high (new feature extractor, new ADR) | Exp. 1–7 largely exhausted | Only informative once cheaper levers are ruled out |
| Deeper/wider architecture | Unknown | Medium-high | Medium | Exp. 4 showing an architecture-limited signature | Same — premature before diagnostics point at architecture |

## 8. First implementation tasks (observability before evaluator changes)

Per the operating principle for this document: instrumentation/diagnostics work
(Experiments 1–5) is first-class engineering work in its own right — it shortens every future
feedback loop, independent of whether it moves Elo directly — and is sequenced ahead of any
task that changes the evaluator itself (Experiments 6–7 and beyond). Candidate GitHub issues,
not yet filed:

1. **Run the NNUE performance gate (closes/advances #206, E-6)** — point
   `NnueCorpusBenchmarkTest` (or `--bench -EvalType NNUE`) at the real net on native Windows,
   record NPS vs. classical. Effort: XS. Depends on: nothing. Outcome: a number that resolves
   §2's central anomaly's first half.
2. **Fixed-depth NNUE-vs-classical gauntlet** — add a fixed-depth/fixed-node mode to
   `tools/nnue-gauntlet.ps1`, re-run E-4. Effort: S. Depends on: #1 (interpret together).
   Outcome: resolves §2's anomaly directly.
3. **Wire `deduplicate`/`phase_of`/`balance_phases` into `combine_and_split` as a reporting
   step** — no retraining required for the report itself. Effort: XS. Depends on: nothing.
   Outcome: a data-quality report for the existing 36k corpus.
4. **Persist train-loss curve + intermediate held-out-loss logging in `train.py`/
   `train_candidate_net.py`** — stop discarding `losses`, call `evaluate_held_out` every N
   steps. Effort: S. Depends on: nothing. Outcome: makes every future training run
   diagnosable per §4's falsifiable criteria, not just a single final number.
5. **Label-noise floor measurement** — re-label 1,000 sample positions at two node budgets,
   report the eval_cp delta distribution. Effort: S. Depends on: nothing. Outcome: a hard
   floor on achievable held-out loss given the current labeling policy.

Each of these is measured by the "success criteria" column in §6's experiment table, not by
an Elo delta — they are diagnostic infrastructure, and their success is "produced a number
that changes what we do next," not "made the engine stronger" directly.

## 9. Open questions (no evidence either way — do not treat as established)

- Whether `acquire_stage1_lichess.py` position sampling and Stage 2's Zurichess-derived
  positions overlap (both public corpora, no dedup step currently run to check).
- Whether re-labeling nominally-quiet Zurichess positions at a different node budget than the
  corpus was originally filtered at reintroduces tactical noise, or simply produces a more
  accurate label for a genuinely quiet position — unresolved without a PV-stability check.
- Whether the SPRT's near-0 result and the gauntlet's -450-to-640 Elo result will converge
  once Experiments 1–2 are run, or whether both are independently real signals about
  different aspects of the net's behavior.

## 10. Completed experiments

None yet — this document defines the plan; §6 items are not yet executed as of 2026-07-19.

## 11. Future experiments

See §6 in full; §8 lists the first five as immediately actionable.
