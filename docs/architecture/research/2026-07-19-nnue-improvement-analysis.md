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

### 2.1 Ranked hypotheses (2026-07-19 follow-up — no root cause asserted)

The two explanations above are a starting split, not an exhaustive account. A closer read of
`Searcher.java`'s pruning/aspiration code surfaces a wider hypothesis space. Ranked by
plausibility; no hypothesis below is asserted as *the* cause — each lists the minimum single
experiment that would distinguish it from the others.

1. **(Highest) Evaluation-scale/volatility mismatch interacting with hardcoded cp-margin
   heuristics.** Every relevant pruning/aspiration constant is a literal, evaluator-agnostic
   number tuned exclusively against the classical evaluator: `ASPIRATION_INITIAL_DELTA_CP = 25`
   (`Searcher.java:34`), `NULL_MOVE_DEPTH_THRESHOLD = 4` — whose own in-code comment states it
   was tuned via SPRT *against the classical evaluator specifically* ("C-5 SPRT: threshold=4
   won (+90.3 Elo vs baseline, H1 accepted)", `Searcher.java:35`), `FUTILITY_MARGIN_DEPTH_1/2 =
   150/300` (`:37-38`), `RAZOR_MARGIN_DEPTH_1/2 = 300/600` (`:39-40`), and a ±32cp correction-
   history cap (`CORRECTION_HISTORY_MAX = 256*32`, `:63-64`, applied at `:852`). None of
   `canApplyNullMove`/`canApplyRazoring`/`canApplyFutilityPruning` branch on which
   `EvaluatorStrategy` is active — they consume `staticEval` identically regardless of source.
   Given NNUE's 735.6cp mean abs error is enormous relative to a 25cp aspiration window or even
   a 600cp razor margin, these fixed thresholds could misfire (extra re-searches, wrong prune
   decisions) in a way that costs disproportionately more at a tight, re-search-intolerant fast
   TC than at a slow one — a mechanism distinct from both raw NPS cost and "the eval is just
   worse."
   *Minimum distinguishing experiment*: surface the aspiration-window re-search count (the
   `consecutiveFailures` mechanism at `Searcher.java:622-641` already tracks this internally —
   exposing it as a counter is a one-line change) and compare its rate between NNUE-mode and
   classical-mode runs over the same bench positions at the same depth.
2. **(High) Raw NPS / search-depth starvation.** Entirely unmeasured today (issue #206 open).
   Plausible on priors, no direct evidence either way.
   *Minimum distinguishing experiment*: Experiment 1 (§6) itself.
3. **(High) Real, depth-independent eval-quality gap.** Also plausible on priors — 735.6cp mean
   abs error is large by any standard — but not yet separated from #1/#2 by any experiment.
   *Minimum distinguishing experiment*: Experiment 2 (§6), fixed-depth gauntlet.
4. **(Medium) NNUE accumulator incremental-update cost, uncounted by existing benchmarks.**
   `NnueEvaluator.onMake()`/`onUnmake()` (`NnueEvaluator.java:96-107,150-153`) run two
   `System.arraycopy` calls plus sparse feature updates on every make/unmake in the tree — a
   real, fixed per-node cost that `NnueCorpusBenchmarkTest.evalThroughput()` structurally
   cannot see (it calls `reset()` fresh per FEN and never exercises the incremental path a real
   search runs thousands of times per move). Not competing with #2 — a previously-uncounted
   component of it.
   *Minimum distinguishing experiment*: the accumulator-cost instrumentation in §12 (Search
   Diagnostics), run during a real search rather than the isolated throughput test.
5. **(Lower) Broader search-parameter interaction — LMR.** `LMR_LOG_DIVISOR = 1.7`'s reduction
   formula (`Searcher.java:66-71,1300-1330`) depends only on `depth`/`moveIndex`, with **no
   direct dependency on eval magnitude or cp-scale** — structurally unlike the margin-based
   heuristics in #1. Ranked lower specifically because it lacks that direct cp-scale coupling;
   any interaction (e.g. via move-ordering quality, which does depend on eval quality) would be
   second-order.
   *Minimum distinguishing experiment*: compare `lmrApplications`/`futilitySkips`/
   `nullMoveCutoffs` counts (already tracked and surfaced via the existing `[BENCH]` log line,
   `Searcher.java:96-99`) between NNUE-mode and classical-mode at fixed depth over the same
   positions — zero new code required, just a comparative run that has never been done.
6. **(Lowest) Implementation/wiring bug (e.g. silent classical fallback).** Substantially
   argued against already: `tools/nnue-gauntlet-e4.md`'s own evidence-strength note observes
   that a clean 636-Elo blowout with realistic termination reasons and a normal 48/47
   White/Black split is inconsistent with a silent both-sides-fell-back-to-classical bug
   (which would produce a near-50/50 result instead). Not eliminated, but the cheapest to rule
   out definitively.
   *Minimum distinguishing experiment*: re-run a short gauntlet sample with `cutechess-cli
   -debug` enabled, confirming `info string NNUE network loaded` appears in-band throughout —
   `nnue-gauntlet-e4.md` already names this exact follow-up trigger.

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

### 5.1 Evaluator calibration diagnostics

MAE and Pearson correlation (table above) say how *loosely* predictions track targets, not
whether they're *systematically* biased. Calibration is a distinct, additional question, and
every metric below is computable directly from arrays `validator.py` already holds in memory
(`predicted`, `target` per held-out record) — no plotting library or external tool needed. The
pipeline's job is to persist the underlying numeric aggregates; rendering a scatter plot or
reliability curve from that output is a separate, later concern for whoever wants a picture.

- **Predicted-vs-target relationship without a scatter plot**: bin held-out records by
  target-cp decile (or fixed-width bucket) and report `mean(predicted) - mean(target)` per
  bucket. This is the numeric content a calibration curve would plot — a groupby/aggregate,
  not a rendering step.
- **Systematic overestimation / underestimation**: signed mean error, `mean(predicted -
  target)`, overall and per bucket — currently absent; `validator.py` only reports MAE, which
  discards sign by construction and cannot distinguish a biased net from a noisy-but-unbiased
  one.
- **Score compression / expansion**: `std(predicted) / std(target)`. Near 1 means the net's
  output spans a comparable cp range to the target; well below 1 is compression (the net is
  "flatter" than reality — plausible for a severely undertrained net regressing toward the
  mean, per §1's 735.6cp error and 0.504 correlation); above 1 is expansion (overconfident).
- **Mate-score handling**: since `target_cp()` already collapses every mate score to a flat
  ±3000cp (§1, established fact), calibration should be reported separately for mate-labeled
  vs. cp-labeled held-out records. Mixing them would hide whether a bias/compression effect is
  specific to the mate-handling constant or a general property of the cp regression.
- **Phase-specific calibration**: bucket by `phase_of()` (already exists, unused per §1) and
  report per-phase signed-mean-error and compression ratio — the same unused function Issue A
  wires in for the dataset side, applied here to held-out predictions instead of raw position
  counts.

None of this requires new infrastructure beyond what `evaluate_held_out`/`_pearson_correlation`
already load into memory — every metric above is an aggregate over data the pipeline already
has.

## 6. Sequenced experiment plan

Ordered for information-gained-per-engineering-cost. Instrumentation and measurement come
before any data-scaling or architecture change. **Tier** distinguishes reusable Infrastructure
(should generally become a GitHub issue once, ahead of the experiments that consume it) from
one-off Experiments (stay documented until their supporting infrastructure exists, run ad hoc
rather than issue-tracked).

| # | Experiment | Tier | Hypothesis | Effort | Runtime | Success criteria | If successful | If unsuccessful |
|---|---|---|---|---|---|---|---|---|
| 1 | Run existing fixed-depth NPS benchmark against real net (= issue #206/E-6, no new issue needed) | Experiment (infra already exists) | NNUE search meets/fails the ≥40% NPS gate; result bounds how much of the fast-gauntlet loss is NPS-attributable | Near-zero (infra exists) | Minutes | Recorded NPS number + pass/fail | Gate passes → -636 Elo is real eval weakness, raise priority of Exp. 5/6 | Gate fails badly → re-run Exp. 2 before trusting any gauntlet result |
| 2 | Re-run E-4 gauntlet at fixed depth instead of fixed TC | Experiment (supporting infra — fixed-depth mode in `nnue-gauntlet.ps1` — doesn't exist yet; build only if Exp. 1 doesn't cleanly resolve the anomaly) | A fixed-depth gauntlet shrinks the Elo gap toward the SPRT's near-0 result if NPS was the confound | Low | ~1hr range | Elo number comparable to -636 and to the SPRT's ~0 | Gap shrinks → NPS/search-depth was the dominant confound, redirect to performance engineering | Gap stays near -636 → eval weakness is real and depth-independent, deprioritize NPS work |
| 3 | Wire existing `deduplicate`/`phase_of`/`balance_phases` into `combine_and_split` as a reporting pass | **Infrastructure — Issue A** | Current 36k set has a hidden duplicate-leak or phase skew | Near-zero (code exists, unused) | Seconds | Duplicate rate, phase distribution report | Non-trivial skew found → re-run training with dedup/balance before any data-scale conclusion | Negligible → rules out this confound cheaply |
| 4 | Persist train-loss curve + add held-out-loss-vs-step logging | **Infrastructure — Issue B** | Loss curve shape distinguishes data/architecture/label/optimizer-limited per §4 | Low | ~same as existing run (~20s + trivial eval overhead) | `(step, train_loss, held_out_loss)` table for the existing run, bit-identical seed | Still-decreasing + tight gap → proceed to Exp. 7 | Diverging gap → investigate reshuffle/regularization before scaling data |
| 5 | Label-noise floor: re-label same 1,000 positions at two node budgets (25k vs 50k) | Experiment (existing `stockfish_label.py` config knob; cheap enough to run ad hoc, not worth issue overhead) | Single fixed-node labels carry non-trivial variance, bounding achievable held-out loss | Low | Minutes | Distribution of \|eval_25k − eval_50k\| in cp | Large spread → reprioritize toward higher node budget / stability check over raw volume | Small spread → strengthens case for Exp. 6/7 as the real levers |
| 6 | Add free WDL signal (`c9`) to Stage 2, train λ-blended variant on the *same* 36k corpus | Infrastructure-shaped, but deferred (depends on Exp. 4/Issue B landing first so the A/B is interpretable) | Blended target improves held-out metrics at fixed data volume | Medium (extend `SHARD_DTYPE`, thread `c9` through, implement blend in `train.py`) | Same as existing (~20s) + one-time format work | Held-out loss/correlation, blended vs. cp-only, controlled A/B | Improves → target formulation was leaving signal on the table, reprioritize above pure scaling | No change → strengthens the case that volume is the real bottleneck |
| 7 | Data-scale ablation as a log-spaced curve (40k → 100k → 400k), not one 10-100x jump | Experiment, gated behind Exp. 3/4 (Issues A/B) so each scale point is diagnosable | Held-out metrics improve monotonically or plateau as a function of corpus size | Medium-high (hours of labeling compute) | Hours, scaling with size | Curve of held-out loss/correlation/eval-error at each scale point, each diagnosable via Exp. 4's logging | Still improving at largest scale → continue scaling | Plateaus early → data volume was not the dominant lever, revisit architecture/HalfKP with a real ADR |
| 8 | Search-time instrumentation: eval latency, accumulator-update cost, PV/cut-node eval frequency | **Infrastructure — Issue C** | Isolates whether NNUE changes search *behavior* (re-search rate, pruning-trigger rates), not just raw eval accuracy — the direct enabler for §2.1 Hypotheses 1, 4, 5 | Low (three single-call-site additions, no architectural change — see §12) | Same as existing bench suite | Eval-time %, accumulator-update-time %, PV/cut-node counts, comparable NNUE vs. classical | Localizes which §2.1 hypothesis the anomaly is consistent with | Rules hypotheses out, narrowing the remaining search space |

## 7. Roadmap — Infrastructure / Experimental / Evaluator-improvement tiers

Infrastructure generally precedes the experiments that depend on it; experiments generally
precede any change to the evaluator itself. Not sorted by expected Elo alone — front-loads
uncertainty reduction. Dependency changes from the 2026-07-19 follow-up review are called out
explicitly where they differ from the original sequencing.

**Tier 1 — Infrastructure (build once, reusable, no evaluator change)**

| Improvement | Expected Elo gain | Effort | Risk | Prerequisites | Information gained |
|---|---|---|---|---|---|
| Wire dedup/phase-balance reporting (Exp. 3 / Issue A) | None directly | Near-zero | None | None | Confirms/refutes a hidden data-quality confound, permanently, for every future run |
| Persist loss curves (Exp. 4 / Issue B) | None directly | Low | None | None | Prerequisite for correctly interpreting Exp. 6/7; reuses `engine-tuner`'s own convergence-logging precedent |
| Search-time instrumentation (Exp. 8 / Issue C) | None directly | Low | None | None | Prerequisite for distinguishing §2.1's Hypotheses 1/4/5 from each other — without it, any future search-behavior question re-derives from scratch |

**Tier 2 — Experimental (one-off validation runs, use Tier 1's output to interpret)**

| Improvement | Expected Elo gain | Effort | Risk | Prerequisites | Information gained |
|---|---|---|---|---|---|
| Run existing NPS benchmark against real net (Exp. 1 / #206) | None directly | Near-zero | None | Real `.nnue` file (exists) | Resolves the single highest-value unknown: is the fast-gauntlet loss an NPS artifact or real |
| Fixed-depth gauntlet re-run (Exp. 2) | None directly | Low | Low | Exp. 1 (build only if Exp. 1 doesn't resolve the anomaly — **dependency added this pass**, was previously unconditional) | Isolates eval quality from search-depth confound |
| Label-noise floor (Exp. 5) | None directly | Low | None | None | Establishes a hard floor on achievable held-out loss |
| Data-scale ablation (Exp. 7) | Unknown — previously asserted as "large" without support | Medium-high | Low (compute cost only) | **Now explicitly gated on Issues A/B landing first** (was "Exp. 1–6 ideally first") | The actual data needed before "scale 10-100x" is anything but a guess |
| Re-verify quietness of relabeled `quiet-labeled.epd` positions | Unknown | Medium | Low | **Gated on Exp. 5's result** (large node-budget variance justifies this; small variance doesn't — dependency added this pass) | Bounds label noise from a second angle beyond Exp. 5 |

**Tier 3 — Evaluator improvements (change the net/target itself; wait for Tier 1+2 signal)**

| Improvement | Expected Elo gain | Effort | Risk | Prerequisites | Information gained |
|---|---|---|---|---|---|
| WDL-blend from `c9` (Exp. 6) | Unknown, plausible (Stockfish/nnue-pytorch precedent) | Medium | Low-medium (touches training-target contract; re-run mirror-symmetry/regression suite per CLAUDE.md) | Exp. 4 / Issue B | Answers "is target formulation the bottleneck," independent of volume |
| HalfKP / king-buckets | Plausibly large per literature; excluded from v1 by design (ADR-001) | High | Medium-high (new feature extractor, new ADR) | Tier 1+2 largely exhausted | Only informative once cheaper levers are ruled out |
| Deeper/wider architecture | Unknown | Medium-high | Medium | Exp. 4 showing an architecture-limited signature | Same — premature before diagnostics point at architecture |

## 8. Task triage and issue determination (2026-07-19 follow-up review)

Re-triage of every proposed task (the original five §8 candidates plus the broader §7 roadmap
items) against four categories — **Infrastructure** (reusable capability, generally an issue
now), **Experiment** (one-off validation run, stays documented until its supporting
infrastructure exists), **Investigation** (reading/analysis, no new capability), **Documentation**
(deliberately deferred roadmap entry):

| Task | Category | Create issue now? | Reasoning |
|---|---|---|---|
| Run NPS gate against real net | Experiment (infra exists) | **No** | Already fully covered by existing issue **#206** — see §8.1 below, no duplicate needed |
| Fixed-depth gauntlet re-run | Experiment (supporting infra doesn't exist yet) | **No — stays documented** | Building the fixed-depth mode before #206's result lands risks a wasted issue if #206 alone resolves the anomaly |
| Wire dedup/phase-balance reporting into `combine_and_split` | **Infrastructure** | **Yes — Issue A** | Zero prerequisites, near-zero effort, permanent capability for every future training run |
| Persist train-loss curve + held-out logging | **Infrastructure** | **Yes — Issue B** | Same reasoning; reuses `engine-tuner`'s own convergence-logging precedent |
| Label-noise floor (relabel at two node budgets) | Experiment (existing config knob) | **No** | Cheap, ad hoc, minutes-long; issue-tracking overhead is disproportionate to its scope |
| WDL-blend from `c9` | Infrastructure-shaped, but deferred | **No, not yet** | Depends on Issue B landing first so the A/B result is interpretable |
| Data-scale ablation | Experiment | **No** | Explicitly gated behind Issues A/B — uninterpretable before that diagnostic infrastructure exists |
| Quietness re-verification (PV-stability check) | Infrastructure-shaped, unproven value | **No** | `DR-D8` already named this optional future work; gate its justification on the label-noise-floor experiment's result rather than building speculatively |
| HalfKP / king-buckets | Documentation | **No** | Explicit PRD/ADR-001 non-goal for v1; nothing in this pass changes that |
| Deeper/wider architecture | Documentation | **No** | Premature before any diagnostic shows an architecture-limited signature |
| Search-time instrumentation (eval latency, accumulator cost, PV/cut-node frequency) | **Infrastructure** *(newly surfaced this pass — see §12)* | **Yes — Issue C** | Prerequisite for distinguishing §2.1's ranked hypotheses; zero prerequisites, single-call-site additions |

Net effect: **3 issues recommended** (down from the original 5 candidates), all Infrastructure-
tier; everything else stays documented pending a named prerequisite.

### 8.1 Issue #206 scope determination

`gh issue view 206`'s actual acceptance criteria: run `--bench` in NNUE mode on native Windows,
record NPS against the 316,964 baseline (301,116 floor), make a pass/fail call, and — only if
the gate fails — profile and open a Vector-API follow-up. This is **exactly** Experiment 1's
scope, word for word; `--bench` is the same fixed-workload measurement
`NnueCorpusBenchmarkTest` performs at finer grain. **No new issue needed, and #206's text does
not need editing** — it already fully covers Experiment 1 as written.

Experiment 2 (fixed-depth *gauntlet*, an Elo/game-outcome diagnostic aimed at the §2 anomaly,
requiring `cutechess-cli` and a full match) is a different measurement in kind, not degree —
folding it into #206 would conflate a raw-throughput pass/fail gate with a game-outcome
diagnostic. It should become its own issue only if #206's result doesn't cleanly resolve the
anomaly on its own; for now it stays documented (§6/§7) rather than filed.

### 8.2 Recommended new issues (3, not 5)

**Issue A — Wire `deduplicate`/`phase_of`/`balance_phases` into `combine_and_split` as a
reporting pass**
- *Objective*: surface duplicate-FEN rate and opening/middlegame/endgame distribution for the
  real 36k-position training corpus, using the already-built, already-exported
  `trainer/trainer/dataset/transform.py` functions that `train_candidate_net.py` never calls.
- *Long-term direction (two-stage, this issue is Stage 1 only)*:
  - **Stage 1 (this issue)**: reporting only. No change to what data actually trains the net,
    no change to the split logic. Purely surfaces numbers that don't exist today.
  - **Stage 2 (future, separate issue, not scoped here)**: optional enforcement — actually
    deduplicating and/or phase-balancing the corpus before training — gated on Stage 1's
    report showing a non-trivial skew or duplicate rate. Do not build Stage 2 speculatively;
    a clean Stage 1 report (negligible duplicates, reasonable phase spread) means Stage 2 may
    never be justified at all.
- *Acceptance criteria*: `combine_and_split` (or a thin wrapper) reports duplicate count/rate
  and phase-bucket counts for the actual Stage1+Stage2 union before the 90/10 split; report
  committed alongside the next training run record (matching the `train-e3-real.md` convention).
- *Dependencies*: none.
- *Estimated effort*: XS.
- *Expected long-term value*: every future training run gets this report for free; directly
  answers whether current held-out metrics are compromised by a duplicate leak or phase skew,
  and whether Stage 2 enforcement is ever actually warranted.

**Issue B — Persist train-loss curve + intermediate held-out-loss logging**
- *Objective*: stop discarding `train()`'s per-step loss list; call `evaluate_held_out` every N
  steps instead of once, post-hoc, at the end.
- *Additional training diagnostics considered for this issue*:
  - **Learning rate — include.** Currently fixed (LR=0.01, no schedule, §1), so logging it per
    step is a constant today — but it's a zero-cost single scalar to persist alongside loss,
    and becomes valuable the moment a schedule is ever introduced (forward-compatible; avoids
    re-opening this issue later for one field).
  - **Gradient norm — include.** Directly strengthens §4's Criterion 4 (optimizer-limited:
    "train loss oscillates/diverges") — a gradient-norm blowup or collapse is a more direct,
    standard optimizer-pathology signal than loss-curve shape alone, and is a single
    total-norm-over-parameters computation per step in the existing PyTorch loop. Cheap,
    directly useful.
  - **Weight norm — exclude from this issue's scope.** No §4 falsifiable criterion depends on
    weight norm specifically, and quantization's existing overflow-safety clamp
    (`clip_ft_weights_()`, §1) already bounds weight magnitude for a different reason (numeric
    safety, not training diagnosis). Would not distinguish any of §4's four bottleneck
    categories. Add later only if a specific future hypothesis needs it.
  - **Activation statistics — exclude from this issue's scope.** A materially similar tool
    already exists on the inference side: `NnueEvaluator.explainEval()`'s
    `rangeAndClipCount()` reports per-perspective pre-activation min/max/clip-count today.
    Building a parallel training-side version here would duplicate that idea rather than reuse
    it. If training-time activation statistics become genuinely necessary, scope that as its
    own follow-up rather than folding it into this issue's already-defined loss/held-out-loss
    scope.
- *Acceptance criteria*: a `(step, train_loss, held_out_loss, learning_rate, gradient_norm)`
  series persisted for a full training run (re-running the bit-identical E-3 config validates
  it); §4's falsifiable criteria become checkable from this output without further code changes.
- *Dependencies*: none.
- *Estimated effort*: S.
- *Expected long-term value*: converts every future training run from "one final number" into
  a diagnosable curve — the single highest-leverage instrumentation gap identified across both
  review passes, directly reusing `engine-tuner`'s own convergence-logging pattern.

**Issue C — Add search-time instrumentation for evaluator latency, accumulator-update cost,
and PV/cut-node eval frequency**
- *Objective*: add the three cheap, single-call-site additions from §12 (`Searcher.java:845`'s
  bare `evaluate()` call; `NnueEvaluator.onMake`/`onUnmake`; the already-in-scope `isPvNode`
  boolean) so NNUE-mode and classical-mode searches can be compared on time-in-eval, time-in-
  accumulator-maintenance, and PV-vs-cut-node eval frequency — none of which exist today, all
  of which are prerequisites for cleanly running §2.1's distinguishing experiments (Hypotheses
  1, 4, 5).
- *Acceptance criteria*: `SearchResult` (or a debug-gated extension, following the existing
  `[BENCH]` log convention) reports eval-time %, accumulator-update-time %, and PV/cut-node
  eval counts; validated by running the bench suite under both evaluators and confirming
  classical-mode's accumulator-update-time reads ~0 (per `EvaluatorStrategy`'s documented
  no-op default lifecycle hooks) while NNUE-mode does not.
- *Dependencies*: none — all additions are localized to existing call sites.
- *Estimated effort*: S.
- *Expected long-term value*: a permanent, reusable capability for every future NNUE-vs-
  classical search-behavior comparison, not a one-off measurement for this anomaly alone.

Each issue is measured by its own acceptance criteria, not by an Elo delta — these are
diagnostic infrastructure, and success is "produced a number that changes what we do next,"
not "made the engine stronger" directly.

## 9. Search Diagnostics

The dataset/label/training/evaluation diagnostics above (§§1–7) do not cover whether NNUE
changes **search behavior** — node counts, depth reached, pruning-trigger rates — as opposed
to just evaluator accuracy. Read in full for this section: `Searcher.java` (aspiration
window, pruning gates, LMR table), `SearchResult.java`, `TranspositionTable.java`,
`NnueEvaluator.java`.

### 9.1 Already exists — no new instrumentation needed to use these

| Diagnostic | Citation |
|---|---|
| Nodes searched (total, leaf, quiescence) | `Searcher.java:87,455-456,473-475`; `SearchResult.java:12-14` |
| Search depth reached (per search) | `SearchResult.java:10`; `Searcher.java:494` |
| Effective branching factor | `Searcher.java:577-588,602` (`SearchResult.ebf()`) |
| TT hit rate | `Searcher.java:598`; `TranspositionTable.java:287-289` (tracked twice, independently) |
| Pruning-trigger counts (NMP cutoffs, LMR applications, futility/delta-pruning skips, beta cutoffs, first-move-cutoff %) | `Searcher.java:96-99,459-463,579-582`; `SearchResult.java:16-24` |
| Aggregate NPS | `Searcher.java:574` (existing `[BENCH]` debug log line) |

This is a materially larger existing surface than a first read would suggest — most of what a
"search diagnostics" request wants is already counted, per search, with zero new code. It's
gated behind `LOG.debug` (needs debug logging enabled, not new instrumentation) and has never
been **run comparatively** between NNUE and classical mode over the same position set.

### 9.2 Missing, and what a realistic addition costs

| Diagnostic | Status | Minimum realistic addition |
|---|---|---|
| Average evaluator latency / eval time as % of search wall-clock | Absent — `evaluate(board)` is a bare, untimed call site | Wrap the one call site (`Searcher.java:845`) with a `long evalNanos` accumulator, compare against the already-tracked per-iteration `elapsedMs` (`:571-573`). One field, one call site. |
| NNUE accumulator incremental-update cost (distinct from raw `evaluate()` cost) | Absent, and invisible to `NnueCorpusBenchmarkTest.evalThroughput()` (which never exercises the incremental `onMake`/`onUnmake` path a real search runs thousands of times per move) | Wrap `NnueEvaluator.onMake()`/`onUnmake()` (`:96-107,150-153`) with nanoTime accumulation. Reads ~0 under classical mode for free (`EvaluatorStrategy`'s lifecycle hooks default to no-ops, `docs/NNUE_PRD.md:46`), giving a clean isolated measurement without a second code path. |
| Cut-node vs. PV-node evaluation frequency | Absent, but `isPvNode` is already in scope at the `evaluate()` call site (`alphaBeta(...)`, `:774`–`845`) | Two counters incremented via the already-available boolean. Trivial. |
| Search depth *distribution* (shape across many positions, not one search's depth) | Partially exists — `depthReached` is already returned per search | No new `Searcher` code — aggregation only: run the bench suite under each evaluator and histogram the already-returned values. Glue code in the bench harness. |
| CPU cache behavior | **Not realistically instrumentable from this codebase** | JVM code has no clean, portable way to read hardware cache-miss counters — that needs OS/hardware perf counters (`perf stat` wrapping the JVM externally, or async-profiler's `perf_events` integration), not code inside `Searcher.java`/`NnueEvaluator.java`. Recommend not committing to this until the cheaper JVM-level metrics above have already localized a bottleneck worth cache-profiling. |

Bottom line: three cheap, single-call-site additions (eval-time wrap, accumulator-update-time
wrap, PV/cut-node counters) plus one aggregation exercise (depth distribution, no new
`Searcher` code) close the realistic gap. CPU cache behavior is out of reach from inside this
codebase and should not be promised. See Issue C (§8.2) for the scoped implementation task.

### 9.3 Search behavior instrumentation (beyond timing)

§9.1/9.2 cover per-node *cost*. A separate question: does NNUE change what the search *does* —
node exploration pattern, pruning-trigger rates, root-move stability — not just how expensive
each node is. Evaluated for realistic low-effort/high-diagnostic-value against what
`Searcher.java` already tracks; not every candidate is recommended.

**Recommended (low effort, directly useful, not currently exposed):**

- **Aspiration fail-high/fail-low frequency and re-search count**: the `consecutiveFailures`
  mechanism (`Searcher.java:622-641`) already tracks this internally — exposing it as a counter
  is a one-line change, and it's already named as §2.1 Hypothesis 1's own minimum
  distinguishing experiment. Not new scope, just made visible.
- **Null-move attempt-vs-success rate**: `nullMoveCutoffs` (successes) is already tracked
  (§9.1); adding an attempt counter at the same call site (`canApplyNullMove`) turns a raw
  count into a rate — one more field, same call site, no new mechanism.
- **Root-move stability across iterative-deepening iterations**: the search already knows its
  own best move per iteration (needed to report the final `bestmove`); comparing it against the
  previous iteration's and incrementing a counter on change is a small, localized addition with
  real behavioral signal — an NNUE-specific root-move-instability pattern would be direct
  evidence of a search-behavior effect distinct from raw eval-quality or NPS cost.

**Not recommended now (real engineering cost, speculative value given current evidence):**

- **TT replacement-scheme behavior** (e.g., depth-preferred vs. always-replace overwrite
  counts): would need new bookkeeping inside `TranspositionTable`'s store path beyond the hit
  rate already tracked (§9.1), and isn't obviously informative for the NNUE-specific question
  at hand. Lower priority than the three items above.
- **Full LMR reduction-amount histogram**: a coarse min/max/mean reduction per search would be
  cheap if ever needed, but a full per-value histogram is disproportionate effort given LMR was
  already ranked lowest-plausibility among §2.1's hypotheses (Hypothesis 5, no direct cp-scale
  coupling). Not worth building ahead of evidence that LMR specifically is implicated.

These three recommended items are **not** folded into Issue C's scope (§8.2) — Issue C is
deliberately limited to the three items in §9.2 (eval-time %, accumulator-update-time %,
PV/cut-node frequency) per its own acceptance criteria. They remain documented future
instrumentation, to be scoped as their own follow-up only if Issue C's results (or §2.1's
distinguishing experiments) point toward a search-behavior explanation specifically.

## 10. Open questions (no evidence either way — do not treat as established)

- Whether `acquire_stage1_lichess.py` position sampling and Stage 2's Zurichess-derived
  positions overlap (both public corpora, no dedup step currently run to check).
- Whether re-labeling nominally-quiet Zurichess positions at a different node budget than the
  corpus was originally filtered at reintroduces tactical noise, or simply produces a more
  accurate label for a genuinely quiet position — unresolved without a PV-stability check.
- Whether the SPRT's near-0 result and the gauntlet's -450-to-640 Elo result will converge
  once Experiments 1–2 are run, or whether both are independently real signals about
  different aspects of the net's behavior.
- Which of §2.1's ranked hypotheses (evaluation-scale/pruning-margin mismatch, NPS cost,
  real eval weakness, accumulator-update cost, LMR interaction, implementation bug) actually
  explains the fast-gauntlet/slow-SPRT discrepancy — unresolved until Issue C's instrumentation
  and Experiments 1–2 are run.

## 11. Completed experiments

**Experiment 1 / Issue #206 — completed 2026-07-19 PM.** Native Windows, commit `afc26d8`,
net `dfffd3da-...`, 31-position bench suite (post-#217 replacement, see §14.4): Classical
311,669 NPS, NNUE 107,714 NPS. **Ratio 34.6% — the ≥40% gate fails.** Full decomposition,
accumulator review, Vector API staged plan, and node-count investigation in §14 below.

Prior to this: this review pass (re-triage, #206 scope determination, search-diagnostics
investigation, ranked-hypothesis expansion) was an analysis/documentation update, not an
executed experiment.

## 12. Future experiments

See §6 in full; §8.2 lists the three recommended immediately-actionable issues (A, B, C) —
all Infrastructure-tier. Everything else in §6/§7 stays a documented, sequenced future
experiment pending a named prerequisite. §14.7 adds two new recommended issues (D, E) from
the 2026-07-19 PM performance investigation.

## 13. Research Debt

Distinct from §10 (Open Questions — narrower, resolvable by a specific named experiment
already in §6) and from §7 (Roadmap — sequenced, actionable work items). Research debt is
**intentionally deferred, larger-scope engineering questions** with no near-term experiment
attached and no roadmap entry — preserved here so they aren't lost, not because they're
scheduled. Do not convert these into issues or roadmap items without a concrete triggering
result from the actual roadmap work first.

- **STC-vs-LTC discrepancy, full resolution.** §2/§2.1 narrow this to a ranked hypothesis list
  with minimum distinguishing experiments, but even after those experiments run, a full
  mechanistic account (exactly how much of the gap is NPS, how much is pruning-margin
  mismatch, how much is real eval weakness, and how they interact) may remain partially open —
  this is the single most important piece of debt this document carries forward.
- **Evaluator calibration, as an ongoing property.** §5.1 defines the metrics; it does not
  establish what "well-calibrated" should mean for this specific engine/evaluator pairing, or
  whether calibration should ever gate promotion the way the SPRT strength gate does today.
  That's a policy question, not an instrumentation question, and is out of this document's
  scope.
- **λ-blend effectiveness, beyond the single fixed-volume A/B in Experiment 6.** Even a
  positive Experiment 6 result wouldn't establish the right blend weight, whether it should
  vary by training stage, or how it interacts with a larger corpus (Experiment 7) — those are
  follow-on questions Experiment 6 can't answer by itself.
- **Transition from Stockfish labels to self-play labels.** DR-E1's self-play design exists on
  paper; nothing in this document's experiment plan addresses *when* (what data-scale,
  eval-quality, or calibration threshold) makes self-play data generation worth its
  engineering cost, versus continuing to scale Stockfish-labeled data.
- **Architecture scaling (deeper/wider nets), the general question.** §7 Tier 3 gates this on
  a specific diagnostic signature (an architecture-limited loss-curve shape). The debt is
  broader: this repo has no established methodology yet for *how* to scale architecture
  incrementally and re-validate at each step, only a gate for *whether* to start.
- **Future HalfKP/HalfKA migration.** ADR-001 and the PRD's Non-Goals already settle this for
  v1. The debt is that "40× more data" (ADR-001's own estimate) is a rough figure, not a
  validated threshold — nothing in this document establishes what data volume would actually
  justify revisiting that ADR.

## 14. Post-#206/#217 performance investigation (2026-07-19 PM)

Issue #206 (Experiment 1, §11) is now measured and closed with this section as its profiling
report. This section separates **measured facts** (this run, or read directly from code),
**supported hypotheses** (evidence-backed, not proven), and **estimates** (explicitly labeled,
used only where no instrumentation exists) per Tasks 1–4 of the triggering investigation.

### 14.1 The headline number, decomposed

Measured (commit `afc26d8`, native Windows, 31-position suite):

| | Classical | NNUE |
|---|---|---|
| Nodes | 73,089,246 | 251,406,555 |
| Time | 234.509 s | 2334.018 s |
| NPS | 311,669 | 107,714 |
| ns/node | 3,208 | 9,284 |
| Eval time % | 20.0% | 29.1% |
| Accumulator time % | — | 36.3% |

The 10x wall-clock ratio (2334.0/234.5 = 9.95x) is arithmetically **two independent factors**,
and it matters which one a fix targets:

```
9.95x slower  =  3.44x more nodes (251.4M / 73.1M)  ×  2.89x slower per node (1 / 0.346)
```

**NPS is nodes/time — so reducing node count alone does not move the ≥40% gate.** Fewer nodes
at proportionally less time leaves throughput roughly unchanged. Only per-node speed
(evaluation + accumulator cost) moves NPS. This splits the roadmap (§14.7) into two levers that
target different things:

- **Lever A — per-node speed.** The only lever that can pass the #206 NPS gate.
- **Lever B — node count.** Affects time-to-depth and real playing strength (and, per §2, is a
  candidate explanation for the gauntlet/SPRT discrepancy) but is **invisible to the NPS gate**.
  A recommendation to "reduce node count to pass #206" would be a category error — flagged here
  explicitly so it isn't proposed later without this context.

**Instrumentation-overhead caveat (measured effect, bounded, does not change the gate
outcome):** issue #216's accumulator timing wraps `onMake`/`onUnmake` with `System.nanoTime()`
— a real call, not free (historically ~20–30ns on Windows via `QueryPerformanceCounter`).
NNUE pays 4 such calls per node (2 in `onMake`, 2 in `onUnmake`) that Classical's no-op hooks
never execute at all (`EvaluatorStrategy`'s default lifecycle hooks, confirmed in code). At
~25ns × 4 ≈ 100ns against NNUE's measured 9,284ns/node, this is a **~1% differential tax NNUE
pays that Classical does not** — correcting for it moves the ratio from 34.6% to roughly 35%,
still well below the 40% gate. Noted for completeness; not re-measured (a re-run was not
performed, per the instruction to treat the 34.6% figure as established).

### 14.2 Performance decomposition (Task 1)

After eval (measured) and accumulator maintenance (measured, NNUE-only), the remainder:

| | Classical | NNUE |
|---|---|---|
| Eval + accumulator | 20.0% (641 ns/node) | 65.4% (6,072 ns/node) |
| **Remainder** | **80.0% (2,566 ns/node)** | **34.6% (3,212 ns/node)** |

The remainder covers move generation, make/unmake, transposition-table probe/store, and core
search-logic control flow (pruning-condition checks, LMR lookup, move-ordering sort,
killer/history bookkeeping) — **all evaluator-agnostic code, identical for both runs.** That
the two remainders are close in absolute per-node terms (2,566ns vs 3,212ns — NNUE's is ~25%
higher, plausibly a mix of the instrumentation tax above, a different move mix from more
captures/checks reached in a much larger tree, and ordinary run-to-run JIT/OS-scheduling noise)
is itself informative: it means the shared code paths are *not* where the NNUE-specific cost
lives, and are not what a performance PR here should target.

No per-component timing exists inside this remainder (TT/move-gen/search-logic are not
separately instrumented) — the following split is an **estimate**, grounded in code reading
(bitboard move generation, array/bitboard make-unmake with no allocation per CLAUDE.md §3, a
single-array-probe TT, insertion-sort move ordering) and general alpha-beta engine profiling
priors, not a measurement:

| Component | Estimated share of remainder | Basis |
|---|---|---|
| Move generation | ~30–35% | Bitboard-based, no allocation observed; typically one of the larger non-eval costs in engines without staged/lazy generation |
| Make/unmake | ~15–25% | Array/bitboard mutation, zero allocation (confirmed by code read); cheap relative to move generation |
| Transposition table | ~10–15% | Single hash-indexed array probe/store, no synchronization, no allocation observed |
| Search-logic control flow | ~25–35% | Pruning-condition checks (`canApplyNullMove`/`canApplyRazoring`/`canApplyFutilityPruning`), LMR table lookup, move-ordering insertion sort, killer/history updates — executed at every node regardless of outcome |
| Misc (JIT/OS scheduling, residual instrumentation) | ~5–10% | Not separately measurable from this codebase |

If a future PR wants real percentages here, it needs new instrumentation (out of scope for
this investigation, which was asked to assess, not implement).

### 14.3 Accumulator investigation (Task 2)

Reviewed: `NnueEvaluator.onMake`/`onUnmake`/`addFeature`/`subtractFeature`/`evaluate`
(`NnueEvaluator.java:104-249`), `FeatureExtractor.forEachChange` (`FeatureExtractor.java:105-142`).

**What's there:**
- `onMake` does two full-width `System.arraycopy` calls (whiteAcc/blackAcc, push-forward to the
  next stack slot) unconditionally, then applies the actual feature deltas via
  `addFeature`/`subtractFeature` — plain scalar `for` loops over `width` (256) shorts.
- A quiet move produces 1 remove + 1 add = 2 feature changes; each change touches both
  perspectives, so 2 changes × 2 perspectives × 256 = 1,024 short read-modify-writes per quiet
  move, versus 2 × 256 = 512 shorts for the arraycopy. Captures/en passant/castling touch more
  (3–4 changes), so the delta-application cost is 1,536–2,048 elements, always ≥ the copy.
- `ftWeights` is laid out row-major per feature (`feature*width + i`), so each feature update
  touches a **contiguous** 256-short slice — already cache-friendly; no gather/scatter pattern
  found.
- No heap allocation in any of these hot-path methods (confirmed by code read and the class's
  own documented invariant) — `debugMode`'s allocating path (`verifyAgainstRebuild`) is
  gated behind a flag that defaults `false` and is not used in the bench runs measured here.

**Candidate optimizations:**

| Candidate | Effort | Expected speedup | Risk |
|---|---|---|---|
| SIMD-vectorize `addFeature`/`subtractFeature` (Vector API, `ShortVector`) | Medium | High — these are the actual per-node-cost drivers (1,024+ scalar ops/move) | Medium (incubator API, see §14.4) |
| Restructure away from copy-forward (single mutable accumulator + reverse-delta on unmake, avoiding the arraycopy) | Low-Medium | **None — likely a regression.** Copy-forward costs 512 (copy) + 1,024 (deltas, quiet move) = 1,536 total across make; an in-place/reverse-delta design costs 1,024 (make) + 1,024 (unmake, to reverse) = 2,048 — the current design is already cheaper at this branching factor. **Not recommended.** | N/A — do not implement |
| Reduce `hiddenWidth` (256→128) | Low (config change) | Would roughly halve per-move accumulator element count | High — PRD §5's own "last-resort mitigation," requires network retrain, directly trades off eval quality; explicitly out of scope until Vector API is tried first (matches PRD's stated ordering) |
| Batch/lazy accumulator materialization (only rebuild at `evaluate()` time from a change log, à la some engines' "lazy update") | High | Unclear — trades a fixed per-node cost for a variable one proportional to tree depth since last materialization; would need its own experiment to know if it's net-positive here | High (architectural change, new correctness-verification surface) |

The clear, low-risk win is the first row. The others are either a wash (copy-forward removal),
a last resort (width reduction), or unproven without further study (lazy materialization) — not
recommended to pursue ahead of Vector API.

### 14.4 Vector API assessment (Task 3)

**SIMD-friendly loops identified**, both already operating on contiguous `short[]` data:
- `addFeature`/`subtractFeature` (`NnueEvaluator.java:237-249`): `acc[i] = acc[i] ± weights[base+i]` for `i` in `[0, 256)` — a pure elementwise short add/subtract, the textbook Vector API case.
- `evaluate` (`NnueEvaluator.java:220-227`): `sum += clamp(us[i], qa) * outWeights[i] + clamp(them[i], qa) * outWeights[width+i]` — a clamped dot-product reduction. The `clamp` branch (`value < 0 ? 0 : min(value, qa)`) and the widening `short → long` accumulate are exactly the shapes that block C2's auto-vectorizer historically for short-typed reductions; **whether this loop is currently auto-vectorized was not verified** (would need `-XX:+PrintAssembly`/JIT-log inspection, not done here) — stated as an open question, not assumed either way. If unvectorized today, this is real, not just theoretical, headroom.

**Portability / maintenance cost:** `jdk.incubator.vector` remains an incubator module through
JDK 21 (this project's compiler target — `release 21` confirmed from the build output) and
JDK 25 (the runtime used for all native-Windows measurements here). Incubator status means:
requires `--add-modules jdk.incubator.vector` at both compile and run time; the API is not
finalized and has had source-incompatible changes across JDK feature releases; and the
engine-uci fat JAR's module-info shading (already triggering `maven-shade-plugin` warnings in
this build) adds a second layer of packaging risk. PRD §5 already names "validated against
scalar" as a requirement for any Vector API PR — that validation must include both a
correctness check (bit-identical output vs. the scalar path) and a JDK-version compatibility
check if the project ever changes its target JDK.

**Staged plan** (no code written, scope/impact/portability/maintenance only):

| Stage | Scope | Expected speedup | Portability | Maintenance cost |
|---|---|---|---|---|
| 1 | `addFeature`/`subtractFeature` only (the two hottest, simplest loops) | Rough, hedged: a 3–4x reduction in the scalar-loop portion of accumulator time is a defensible target for width-256 short-elementwise ops on `PREFERRED_SPECIES` hardware (AVX2/256-bit lanes = 16 shorts/op); translates to accumulator time dropping from 36.3% toward roughly 10–12% of NNUE wall-clock | Needs `--add-modules`; scalar fallback path required for platforms without a usable vector species | Low — two small, self-contained methods; scalar path kept as fallback/reference for the required validation |
| 2 | `evaluate`'s dot-product loop | Similar order of magnitude, contingent on resolving the clamp/widening auto-vectorization question above; may need restructuring the clamp to a vector-friendly form (e.g. `VectorOperators.MAX`/`MIN` instead of a branch) before SIMD helps | Same as Stage 1 | Low-medium — touches the score-critical path, needs the mirror-symmetry + NNUE regression suites re-run (CLAUDE.md §4) |
| 3 (only if 1+2 don't clear the gate) | Batch accumulator updates across sibling moves at the same ply, or explore `MemorySegment`-based weight layout for better vector alignment | Unclear, would need Stage 1/2's actual measured result to size | Same incubator caveats, larger surface | Medium-high — architectural, larger regression-test surface |

**Correction (2026-07-19 PM, superseding the sizing below):** an earlier version of this
paragraph stated Stage 1 alone reaches ~52–62%. That was an arithmetic error — the 52–62%
figure actually requires Stage 2 as well (it implicitly assumed eval time also shrinks, which
only Stage 2 touches). The corrected, fully-derived model is in §15.1.

### 14.5 Node-count investigation (Task 4) — now measured, not just reasoned

§2.1 Hypothesis 1 (evaluation-scale/pruning-margin mismatch) was previously graded "highest
plausibility, no distinguishing experiment run." This investigation ran that experiment:
existing counters (`betaCutoffs`, `firstMoveCutoffs`, `nullMoveCutoffs`, `futilitySkips`,
`deltaPruningSkips`, `ttHitRate`) dumped for both evaluators on two positions (suite position 2
and the new post-#217 position 15), same depth (13), same TT size (16MB), no code changes.

**Measured:**

| Position | Evaluator | Nodes | deltaPruningSkips | rate (skips/node) | futilitySkips | rate | firstMoveCutoff% | ttHitRate |
|---|---|---|---|---|---|---|---|---|
| 2 | Classical | 8,746,524 | 4,901,108 | 56.0% | 3,579,348 | 40.9% | 96.47% | 9.32% |
| 2 | NNUE | 30,358,338 (3.47x) | 145,308 | **0.48%** | 8,590,080 | 28.3% | 94.87% | 7.38% |
| 15 (new) | Classical | 1,073,429 | 427,321 | 39.8% | 499,070 | 46.5% | 94.75% | 12.16% |
| 15 (new) | NNUE | 22,586,638 (21.0x) | 28,579 | **0.13%** | 3,986,918 | 17.7% | 90.15% | 5.91% |

**Ranked hypotheses (updated from §2.1, most-to-least supported by this new measurement):**

1. **(Confirmed mechanism, root cause of the scale mismatch itself still open) Delta pruning
   and futility pruning trigger far less often under NNUE, relative to node-growth
   opportunity.** Delta-pruning rate collapses ~100–300x (56.0%→0.48%, 39.8%→0.13%); futility
   rate drops roughly by half (40.9%→28.3%, 46.5%→17.7%). Both are `staticEval + margin <=
   alpha`-style checks against fixed, evaluator-agnostic cp constants
   (`FUTILITY_MARGIN_DEPTH_1/2 = 150/300`, and delta pruning's own margin — both consumed
   identically regardless of which `EvaluatorStrategy` produced `staticEval`). This is now a
   **measured, mechanical fact**, not just a plausible reading of the code. What is *not*
   established: whether the underlying cause is score compression (§5.1's `std(predicted) /
   std(target)` metric, never computed for this net), a systematic scale/bias offset, or both —
   that requires the calibration diagnostics §5.1 already specifies, not yet run.
2. **(Secondary, smaller effect) Move-ordering quality is modestly, not dramatically, worse.**
   First-move-cutoff% drops 96.47%→94.87% (pos 2) and 94.75%→90.15% (pos 15) — real, but far
   smaller in magnitude than the pruning-rate collapse above. Move ordering's algorithm
   (MVV-LVA/SEE for captures, killer/history for quiets) is identical code for both evaluators
   and does not itself call `evaluate()` — this is a downstream symptom of TT-move and
   history-heuristic quality (both transitively eval-quality-dependent), not an independent
   mechanism, and not the dominant driver.
3. **(Present, smaller magnitude) Null-move pruning also under-triggers, less severely than
   delta pruning.** `nullMoveCutoffs` rate drops too (0.622%→0.256% pos 15) — consistent with
   the same root cause (its gate also checks `staticEval >= beta`, `Searcher.java:1189`), but
   the coarser beta-threshold gate is less sensitive to scale mismatch than delta pruning's
   tighter margin.
4. **TT hit rate is lower for NNUE** (9.32%→7.38%, 12.16%→5.91%) — assessed as a **consequence**
   of the larger, more divergent tree (more distinct positions reached, naturally fewer repeats
   per node), not an independent cause.
5. **Not evidence of a bug.** A clean, monotonic-with-node-growth pattern across two unrelated
   positions, fully explained by a single coherent mechanism (margin/scale mismatch), is
   inconsistent with an implementation defect — consistent with §2.1 Hypothesis 6's existing
   "argued against" status.

This directly upgrades §2.1 Hypothesis 1 from "highest-plausibility, unconfirmed" to
"mechanically confirmed root mechanism (fixed margins × NNUE score distribution), underlying
scale/calibration cause still open." It does **not** by itself resolve §2's central anomaly
(gauntlet vs. SPRT discrepancy) — that still needs Experiment 2 (fixed-depth gauntlet) — but it
substantially narrows what a pruning-margin recalibration effort would need to fix.

### 14.6 Benchmark methodology cross-reference

For completeness in one place: the #206 measurement above used the **post-#217 benchmark**.
Issue #217 found bench position 15 (`rnbq1k1r/pp1Pbppp/...`) was TT-history-dependent —
non-monotonic node-count blowups and PV/score divergence driven by transposition-table size
alone, independent of evaluator, violating the "roughly comparable search trees across
evaluators" assumption an NPS benchmark depends on. It was replaced (commit `afc26d8`) with a
canonical Stockfish-bench position, verified legal and TT-stable at 1/16/64MB before adoption,
following the same precedent as commit `44aea1a`'s earlier `BENCH_FENS[8]` replacement. #217
remains open as an independent search-instability investigation, decoupled from #206 — the
benchmark accommodation did not wait on, and #206 did not wait on, #217's root cause.

### 14.7 Updated roadmap and issue recommendations (Tasks 5–6)

Building on §7's existing tiers — this adds the Lever A/B split as an explicit organizing axis,
since §7 predates the #206 measurement.

**Quick wins (Lever A, moves the NPS gate):**

| Item | Impact | Effort | Dependencies | Success criteria |
|---|---|---|---|---|
| Vector API Stage 1 (`addFeature`/`subtractFeature`) | High — plausibly clears the 40% gate alone (§14.4 sizing) | Medium | None | Re-run `--bench` NNUE mode; NPS ratio ≥40%; bit-identical scores vs. scalar path on the existing NNUE regression/mirror-symmetry suites |

**Medium effort (mixed):**

| Item | Impact | Effort | Dependencies | Success criteria |
|---|---|---|---|---|
| Vector API Stage 2 (`evaluate` dot-product) | Medium, contingent on Stage 1 result and the auto-vectorization open question | Medium | Stage 1 landed and measured | NPS ratio measurement; bit-identical scores vs scalar |
| NNUE-specific pruning-margin recalibration investigation (Lever B) | None on NPS; real playing-strength/time-to-depth impact; the most direct lead on §2's gauntlet/SPRT anomaly | Medium | §5.1's calibration diagnostics (signed mean error, compression ratio — no new infra, just running existing `validator.py` arrays through new aggregates) | A calibration report (bias/compression per §5.1) + a recommendation on whether margins need per-evaluator tuning, retraining for better calibration, or both |

**Large architectural work (do not start without a Lever A/B result in hand first):**

| Item | Impact | Effort | Dependencies | Success criteria |
|---|---|---|---|---|
| Vector API Stage 3 (batched updates / memory-layout rework) | Unclear | High | Stages 1–2 measured and insufficient | N/A until Stages 1–2 conclude |
| Lazy/batched accumulator materialization | Unclear, possibly negative (§14.3) | High | Stage 1 Vector API result (may make this moot) | N/A |
| Per-evaluator pruning-margin constants (if recalibration investigation confirms need) | Could resolve node-count blowup directly | High (touches tuned, SPRT-validated constants; needs its own Texel-style or SPRT re-tune, this time NNUE-aware) | Medium-effort recalibration investigation above | New margins Texel/SPRT-validated against NNUE specifically, node counts on the bench suite drop toward parity with Classical at the same depth |

**Recommended new issues** (extending §8.2's A/B/C lettering; neither duplicates an existing
open issue — checked against all open `phase-15`-labeled issues):

- **Issue D — [#218](https://github.com/coeusyk/chess-engine/issues/218), Vector API for NNUE accumulator/evaluator hot loops.** Required by #206's own
  acceptance criteria ("a follow-up issue is opened for Vector API work"). Scope: §14.4 Stage 1
  (required), Stage 2 (if Stage 1 alone doesn't clear the gate). Validated-against-scalar per
  PRD §5. Not Stage 3 (architectural, gated on Stages 1–2's result).
- **Issue E — [#219](https://github.com/coeusyk/chess-engine/issues/219), NNUE evaluation-scale/pruning-margin calibration investigation.** Scope: run
  §5.1's existing (unbuilt-on, not un-implemented — the arrays already exist in `validator.py`)
  calibration diagnostics against the real net; compare against the measured pruning-rate
  collapse in §14.5; recommend whether pruning margins need per-evaluator values, whether the
  net needs recalibration (K-value/output-scale), or both. This is the direct continuation of
  §2.1 Hypothesis 1 and the most promising lead on §2's central gauntlet/SPRT anomaly — Lever B,
  does not move the NPS gate, but is the higher-value lead for actual playing strength.

### 14.8 First implementation task (recommendation)

**Vector API Stage 1** (Issue D/#218, `addFeature`/`subtractFeature` only). Reasoning: it is the only
item that can pass #206's own gate (Lever A), it is mandated by #206's acceptance criteria
regardless, it is low-risk (two small, self-contained, easily-validated methods with a required
scalar fallback), and §15.1's corrected sizing suggests a real, though thin-margin, chance of
clearing 40% from this step alone — cheaper to try first than committing to Stage 2 or the
architecturally-larger Lever B work before knowing whether Stage 1 alone suffices. Issue E/#219
(Lever B) should start in parallel, not sequentially — it targets a different problem (real
playing strength via §2's anomaly) that Stage 1 does not address either way.

## 15. Pre-implementation validation pass (2026-07-19 PM, before #218/#219 begin)

Requested before any Vector API or calibration work starts: strengthen the plan with
measurement design and a corrected performance model. No code changed in this section — all of
§14's numbers stand except the Stage 1 sizing error corrected below.

### 15.1 Refined Stage 1 performance model (Task 1)

**Measured** (repeated from §14.1, no new measurement here):

| Quantity | Value |
|---|---|
| NNUE ns/node | 9,284 |
| NNUE eval time | 2,702 ns/node (29.1%) |
| NNUE accumulator time | 3,370 ns/node (36.3%) |
| NNUE remainder | 3,212 ns/node (34.6%) |
| Classical NPS | 311,669 |

**Derived** (arithmetic on the measured values above, no assumptions):
- Gate threshold in ns/node terms: to reach exactly 40% of Classical NPS (124,668 NPS), NNUE
  needs ≤ 8,021 ns/node.

**Assumptions** (each stated explicitly; this is where §14.4's error lived — a prior draft
applied a speedup to the *entire* eval+accumulator bucket without separating what Stage 1
actually touches):
- **A1 (moderate confidence)**: the 3,370 ns/node accumulator bucket splits into a
  non-accelerable floor (~337 ns/node — the `System.nanoTime()` instrumentation tax estimated in
  §14.1, plus `System.arraycopy`, already a JIT intrinsic Stage 1 cannot improve on) and an
  accelerable portion (~3,033 ns/node — the scalar `addFeature`/`subtractFeature` loops, Stage
  1's actual target). This 90/10 split is **not measured** — no per-sub-component timing exists
  inside the accumulator bucket; it is inferred from which operations exist in the code
  (§14.3), not derived from an experiment.
- **A2 (weaker — Stage 2 only)**: the 2,702 ns/node eval bucket splits similarly into a ~540
  ns/node floor (function-call overhead, output-bias add, final integer scaling — plus the
  `clamp` branch's cost if it resists vectorization) and ~2,162 ns/node accelerable. **Weaker
  than A1** because whether the eval loop is even auto-vectorized *today* is unverified (§14.4),
  and the `clamp`/widening-accumulate pattern is a known historical vectorization blocker that
  may need restructuring before any speedup applies at all — Stage 2's floor could be larger
  than assumed here if that restructuring doesn't fully succeed.
- **A3**: a Vector API speedup of 2–4x on the accelerable fraction (not the whole bucket) is a
  defensible hedge for width-256 short-elementwise operations on 256-bit-lane hardware (16
  shorts/op theoretical) — real-world JIT/Vector-API speedups typically land well below
  theoretical peak, so 2–4x, not 16x, is used throughout.
- **A4**: move generation/make-unmake/TT/search-logic (the 3,212 ns/node remainder) is
  unaffected by either stage — this is a strong assumption, not weak (that code is evaluator-
  agnostic, confirmed by reading it in §14.2), included here only for completeness.

**Upper-bound estimates** (derived from measured + stated assumptions — ranges, not single
numbers; **do not read any of these as an expected outcome**):

| Speedup (S) | Stage 1 alone (accum. only) | Stage 1 + Stage 2 (both) |
|---|---|---|
| 2x | 7,768 ns/node → 128,733 NPS → **41.3%** | 6,687 ns/node → 149,543 NPS → **48.0%** |
| 3x | 7,262 ns/node → 137,701 NPS → **44.2%** | 5,821 ns/node → 171,791 NPS → **55.1%** |
| 4x | 7,009 ns/node → 142,674 NPS → **45.8%** | 5,388 ns/node → 185,598 NPS → **59.6%** |

**Corrected headline**: under stated assumptions, **Stage 1 alone projects to roughly 41–46%**
— not the previously-stated 52–62%, which actually requires Stage 2 as well. Solving the
threshold equation directly: Stage 1 alone needs only **S ≈ 1.71x** on the accelerable fraction
to cross exactly 40% — a modest speedup relative to the 2–4x hedge range, which is *why* a pass
is plausible, but the projected margin above the gate (1–6 percentage points at the low end of
the hedge) is thin enough that **this is a projection to test, not a result to expect.** If A1's
90/10 split is optimistic (i.e., the accelerable fraction is smaller than assumed, or the floor
larger), the low end of this range could fail the gate even with real Stage 1 work landed.

**Weakest assumption, flagged explicitly**: A2 (Stage 2's floor/accelerability), because it
rests on an unverified claim (current auto-vectorization state) stacked on an unprototyped fix
(restructuring the clamp). Stage 1's own A1 is comparatively firmer — the accumulator's
operations are simpler and better understood from the code read alone.

### 15.2 Measurement-first plan for #219 (Task 2)

Before any pruning-margin change, characterize *how* NNUE's score distribution differs from
Classical's. Split by what already exists vs. what's genuinely new, so #219's effort estimate is
honest:

**Already exists — aggregate only, zero new code:**

| Metric | Existing source |
|---|---|
| Futility-skip rate | `futilitySkips` / nodes (`SearchResult`, #216) — already used in §14.5 |
| Delta-pruning-skip rate | `deltaPruningSkips` / nodes — already used in §14.5 |
| Null-move-cutoff rate | `nullMoveCutoffs` / nodes — already used in §14.5 |
| LMR-application rate | `lmrApplications` / nodes — tracked, not yet compared NNUE-vs-Classical in §14.5 (included here as a control: §2.1 Hypothesis 5 predicts *no* cp-scale coupling, so this rate should differ far less than the pruning rates above — worth confirming, not just assuming) |
| First-move-cutoff % | `firstMoveCutoffs`/`betaCutoffs` — already used in §14.5 |
| TT hit rate | already used in §14.5 |

**Genuinely new — requires instrumentation, not yet built:**

| Metric | Where it would hook in | Notes |
|---|---|---|
| Score histogram / mean / median / stddev / percentiles | New: sample `staticEval` at the `evaluate()` call site (`Searcher.java:897`) | **Must capture both raw and corrected values separately** — delta pruning gates on the *raw*, uncorrected `standPat` from `evaluate()` inside quiescence (`Searcher.java:1593`), while futility/razoring gate on the correction-history-adjusted `staticEval` (`Searcher.java:909`). A single histogram of only one would be unable to explain both of §14.5's measured pruning-rate collapses — this is not optional given what's already been measured. |
| Fraction of scores within common pruning windows (e.g. within ±150/±300/±600cp of alpha/beta — the existing margin constants) | Derived from the histogram above, bucketed against `FUTILITY_MARGIN_DEPTH_1/2`/`RAZOR_MARGIN_DEPTH_1/2` | No new mechanism beyond the histogram; a specific bucketing of it |
| Aspiration fail-high / fail-low rate | `searchRootWithAspiration`'s local `failLow`/`failHigh` booleans (`Searcher.java:681-682`) — computed every iteration, discarded, never counted | One-line counter addition each, confirming §2.1's own note that this is "already tracked internally, exposing it is a one-line change" |
| Null-move / futility / delta-pruning / razor **attempt** counts (as opposed to the existing **success/skip** counts) | `canApplyNullMove`/`canApplyFutilityPruning`/`canPruneLosingCapture`/`canApplyRazoring` call sites | Needed to turn the existing raw counts into *rates against opportunity*, not just rates against total nodes — e.g. "of the times a futility check was even attempted, how often did it fire" is a cleaner signal than "futility skips per node," which conflates attempt frequency with success frequency |
| LMR trigger distribution (reduction amount, not just application count) | `canApplyLmr` call site + `lmrReductions` table lookup (`Searcher.java:1069-1073`) | Lower priority — §2.1 Hypothesis 5 already notes `canApplyLmr` has no direct dependency on `staticEval`/depth-independent cp-scale (confirmed again by this session's code read: its condition is `depth`/`moveIndex`/quiet/killer/TT-move/check flags only) — included per the requested list, but not expected to show the same signal as the margin-gated techniques |

**Explicitly not proposed here**: any change to `FUTILITY_MARGIN_DEPTH_1/2`, `RAZOR_MARGIN_DEPTH_1/2`,
`ASPIRATION_INITIAL_DELTA_CP`, or the correction-history constants. This section is
instrumentation design only, per the task's own constraint.

**What the output would determine**: whether the measured pruning-rate collapse (§14.5) is
explained by score *compression* (narrow `std(predicted)`, staying inside pruning windows too
often to trigger margin-based cuts), a systematic *bias/offset* (scores shifted in one direction
relative to alpha/beta), or something else — directly answering §219's own acceptance criteria,
using only the instrumentation proposed above plus §5.1's already-specified training-side
calibration metrics (signed mean error, compression ratio) run against the real net.

### 15.3 Vector API scope re-confirmation (Task 3)

**Highest-ROI confirmation**: `addFeature`/`subtractFeature` remain the correct Stage 1 target.
Re-confirmed against §15.1's model — they are the entire accelerable portion of the single
largest bucket (36.3% accumulator time) that Stage 1 can address without also solving Stage 2's
unverified auto-vectorization/clamp-restructuring question.

- **Memory access pattern**: contiguous. `ftWeights[feature*width .. feature*width+width-1]` is
  a sequential 256-short (512-byte) slice per feature update (confirmed §14.3); `whiteAcc[sp]`/
  `blackAcc[sp]` are likewise contiguous `short[256]` arrays. No gather/scatter needed — a
  straight sequential vector load/store pattern, the simplest case for the Vector API.
- **Expected vector width**: `width=256` (production net, confirmed via `nets/dfffd3da-...json`
  `hidden_width: 256`) divides evenly by every common short-lane species (128-bit=8 lanes,
  256-bit=16 lanes, 512-bit=32 lanes) — 256/16=16 full vector iterations at `SPECIES_256`, no
  scalar remainder/tail loop needed at that width specifically. A different net width would not
  have this guarantee — worth a bounds/remainder-handling check in the implementation regardless
  of what today's specific net happens to satisfy.
- **Alignment concerns**: none identified. The Vector API's `fromArray`/`intoArray` operate on
  plain heap `short[]`, not requiring explicit memory alignment the way native SIMD intrinsics
  sometimes do — the JVM does not expose (or need) manual alignment control here.
- **Portability constraints**: repeated from §14.4 — `jdk.incubator.vector` is still incubator
  status through both the project's compile target (`release 21`) and the runtime used for all
  measurements here (Zulu 25); requires `--add-modules jdk.incubator.vector` at compile and run
  time; API has had source-incompatible changes across JDK feature releases historically.
- **Fallback behavior**: a scalar fallback path is required (not optional) for two reasons — (1)
  incubator API stability risk, (2) hardware without a usable vector species (rare on modern
  server/desktop targets, but the existing scalar loop is what the fallback *is*, not new code to
  write). The validation requirement (bit-identical vs. scalar) doubles as this fallback's own
  correctness test.
- **Prerequisite refactoring**: **none identified as required.** `addFeature`/`subtractFeature`
  are already minimal, self-contained, allocation-free loops over contiguous arrays — the
  simplest possible starting shape for a Vector API port. No data-layout change, no method
  extraction, no interface change needed before Stage 1 can start.

### 15.4 #218 implementation sequencing (Task 4)

**Recommendation: multiple PRs, not one** — reviewability over commit-count minimization, per
the task's own stated preference, and because Stage 1 and Stage 2 have materially different
risk profiles (§15.1: Stage 1 rests on the firmer A1; Stage 2 rests on the weaker, unprototyped
A2) that shouldn't be reviewed or landed as a single unit.

| PR | Scope | Acceptance criteria | Benchmark to re-run | Regression tests |
|---|---|---|---|---|
| 1 | Vector API scalar-fallback scaffolding: species detection, `--add-modules` wiring, build/CI changes needed to compile against `jdk.incubator.vector` — **no algorithm change**, scalar path untouched and still the only path executed | Project builds and existing test suite passes unchanged with the new module dependency wired in; no behavior change | None required (no algorithmic change) | Full existing suite (regression-neutral change) |
| 2 | `addFeature`/`subtractFeature` SIMD implementation (Stage 1) | Vectorized output bit-identical to scalar on the existing NNUE fuzz/regression tests; `NnueEvaluator`'s own `verifyAgainstRebuild` debug check passes; mirror-symmetry test passes (CLAUDE.md §4) | Native Windows `--bench` NNUE mode, **multi-run median** (§15.5) | NNUE regression suite, mirror-symmetry test, `NnueEvaluator` fuzz/rebuild tests |
| 3 | Native-Windows NPS measurement + gate determination for Stage 1 alone | NPS ratio recorded, pass/fail against 40% stated explicitly (§15.5's decision tree governs what happens next) | Same as PR 2 | None (measurement-only PR, doc/issue update) |
| 4 (only if PR 3's gate fails) | `evaluate()` dot-product SIMD (Stage 2) — includes resolving the clamp/widening auto-vectorization question first (verify current state via JIT inspection before committing to a restructuring approach) | Same bit-identical-vs-scalar bar as PR 2, applied to `evaluate()` | Native Windows `--bench` NNUE mode, multi-run median | Same suite as PR 2, plus full NNUE gauntlet spot-check (score-critical path) |
| 5 (only if PR 4 lands) | Re-measurement + final gate determination | Same as PR 3 | Same as PR 3 | None |

PRs 1–3 are Stage 1's complete, independently-revertible unit — matching this project's
established one-scope-per-PR discipline (CLAUDE.md's own commit-format convention). PR 4/5 exist
conditionally and should not be started before PR 3's result is known.

### 15.5 Benchmark plan after Stage 1 lands (Task 5)

1. **Microbenchmark (if applicable)**: a JMH or ad hoc scalar-vs-vector micro-benchmark of
   `addFeature`/`subtractFeature` in isolation, confirming the raw per-call speedup *before*
   running the full native-Windows suite — cheaper to iterate on and catches an implementation
   that isn't actually vectorizing (e.g., a species mismatch or unintended scalar fallback)
   before spending a native-Windows session on it. Recommended, not currently built.
2. **Native Windows benchmark**: `--bench 13` in both Classical and NNUE mode, same commands and
   environment as #206's own measurement (`docs/architecture/research/2026-07-19-nnue-improvement-analysis.md`
   §14.1) — same JVM, same OS, same net, same 31-position (post-#217) suite.
3. **NPS comparison methodology — multi-run, not single-run**: this project's own established
   convention (commit `44aea1a`: "middle-3-of-5 runs") should be used at the gate specifically,
   not a single measurement. §15.1's own projection (41–46% at the low end) is close enough to
   the 40% line that the documented ±12,584 NPS (~4%) baseline run-to-run variance could flip a
   single run's pass/fail call. Report the median of 5 runs, not the first run.
   Note for context, not for re-litigating #206's already-accepted 34.6%: `--bench` runs with
   `setInstrumentationEnabled(true)` always on (§14.1's own ~1% tax) — the measured ratio at any
   stage is very slightly conservative relative to a real, non-instrumented game; this does not
   change which number should be compared to the 40% gate, since Classical pays a matching
   (smaller) instrumentation cost too and both sides of the ratio use the same setting.
4. **Pass/fail criteria**: median NPS ratio ≥ 40% of Classical's median NPS from the same
   session (not the historical 311,669 figure verbatim — re-measure Classical too, since JIT
   warmup/OS/hardware state can drift between sessions).
5. **Decision tree**:
   - **Gate passes (≥40%, median-of-5)**: Stage 2/PR 4-5 not started. Recommend proceeding
     directly to E-5 (SPRT), per the roadmap's existing dependency ordering — Lever A's job is
     done; #219/Lever B continues in parallel as its own, non-gating investigation.
   - **Gate fails**: before any further optimization work (Stage 2 or otherwise), the next
     measurement is a **JIT/vectorization verification** — confirm via `-XX:+PrintAssembly` or
     equivalent whether Stage 1's code is actually executing the vector path at the expected
     species width (ruling out "the code is right but not actually vectorizing at runtime"
     before concluding the *speedup itself* was insufficient). Only after that check should
     Stage 2 (PR 4) begin.

### 15.6 Documentation categorization pass (Task 6)

§14.4's original sizing paragraph mixed a measured percentage (65.4%) with an unstated-as-such
assumption (speedup applied to the whole bucket) and presented the result as if it were a
tighter, more confident range than the underlying assumptions supported — corrected in §15.1
above; the original paragraph is struck through with a pointer, not deleted, so the correction
is traceable. Every quantitative claim newly added in this section is one of exactly five
categories, stated inline rather than left implicit: **established fact** (§14.1's measured
table, repeated verbatim), **derived calculation** (the gate-threshold ns/node figure, the
9.95x = 3.44x × 2.89x decomposition), **projection** (§15.1's Stage 1/Stage 1+2 ranges — never
stated as an expected result), **supported hypothesis** (§14.5's pruning-rate-collapse
mechanism — measured, but the underlying scale/bias cause remains unconfirmed), and **future
work** (§15.2's proposed-but-unbuilt instrumentation, §15.4's conditional PRs 4-5). No wording
in this section states a projection as if it were a measurement, or a hypothesis as if it were
an established cause.

## 16. Implementation readiness review (2026-07-19 PM, before #218 PR 1 starts)

Most of this review's asks are already answered by §15 — this section cross-references rather
than restating, and spends its effort on what's genuinely new: two implementation-correctness
findings and the readiness verdict.

### 16.1 Stage 1 acceptance criteria (four levels)

- **Level 1 (microbenchmark)**: `NnueCorpusBenchmarkTest.perCategoryThroughputAndNps()` already
  exists and measures exactly this (eval throughput, ev/s, both evaluators) — **but it defaults
  to `TestNetworks.synthetic(8)`, not the production width of 256.** Width 8 is half a single
  AVX2 short-lane (16 lanes) — a vectorized port measured at width 8 will understate, possibly
  hide entirely, any real speedup. **Required**: re-parameterize this run (or the equivalent ad
  hoc microbenchmark) to width 256 before trusting its number. Success criterion: measured
  scalar-vs-vector speedup on `addFeature`/`subtractFeature` at width 256, compared against
  §15.1's assumed 2–4x hedge.
- **Level 2 (generated code)**: do not assume vectorization succeeded. Verification method:
  `-XX:+UnlockDiagnosticVMOptions -XX:+PrintAssembly` (or `-XX:+TraceNewVectors`/JITWatch) on the
  Stage 1 methods, confirming actual vector-width instructions (e.g. `vpaddw`/`ymm` registers on
  AVX2) are emitted, not a silently-scalarized loop. Acceptable fallback: the scalar path
  (already the entire current implementation) if a platform's JVM reports no usable species —
  this must be an explicit, tested branch (§15.3), not an assumed no-op.
- **Level 3 (benchmark)**: §15.5's plan verbatim — accumulator time %, eval time %, NPS, NPS
  ratio, native Windows, median-of-5, compared against the same-session Classical re-measurement
  (not the historical 311,669 figure).
- **Level 4 (gate)**: §15.5's decision tree verbatim — median ratio ≥40% passes; below 40%
  triggers the JIT-verification check (Level 2, in effect) before Stage 2 starts.

### 16.2 Risk review — every §15.1 assumption reclassified

| Assumption | Classification | Uncertainty |
|---|---|---|
| NNUE/Classical ns-per-node, eval%/accumulator% (§14.1) | **Measured** | None — this is the input, not a risk |
| Gate-threshold ns/node, 9.95x decomposition | **Derived** | None — arithmetic on measured values |
| A1: accumulator 90/10 accelerable/floor split | **Implementation-dependent** | Moderate — depends on how much of the 337ns floor (nanoTime tax + arraycopy) the actual implementation carries; not verified until Level 1/2 above run |
| A2: eval 80/20 split, clamp/widen vectorizability | **JVM-dependent** (and implementation-dependent) | **Highest of all four** — rests on C2's current, unverified auto-vectorization behavior for this exact loop shape, stacked on an unprototyped clamp restructuring |
| A3: 2–4x speedup on accelerable fraction | **JVM-dependent** | High — real Vector API speedups are JIT/hardware-species dependent, not portable across machines; the native-Windows measurement machine's actual vector width was never queried |
| A4: remainder (movegen/make-unmake/TT/search-logic) unaffected | **Derived** (from code read, not an experiment) | Low — these are evaluator-agnostic code paths, confirmed by reading them |

**Greatest uncertainty**: A2 (Stage 2's eval-loop assumption) and A3 (real-hardware speedup
magnitude) — both JVM/hardware-dependent, neither measurable until Level 1/2 actually run. This
reinforces §15.1's own framing: the Stage 1 projection is a plan to test, not a result to expect.

### 16.3 Implementation review (Task 3) — determinism can be proven, not just hoped for

Two findings correct in-place what was previously left as "needs validation":

- **`addFeature`/`subtractFeature` are elementwise** (`acc[i] = acc[i] ± weights[base+i]`, no
  cross-lane interaction) — a vectorized port is **bit-identical by construction**, not by luck,
  since each output element depends on exactly one input pair regardless of lane grouping.
- **`evaluate`'s reduction is integer summation**, hence associative — SIMD lane-grouping does
  not change the sum's value (unlike floating-point reduction, where reassociation changes
  rounding). This is a stronger basis for the "bit-identical vs. scalar" validation requirement
  than an empirical hope: it's a property of the arithmetic, checkable by inspection.
- **Stage 2 overflow trap** (new finding, not previously flagged): `evaluate`'s worst-case term
  is `clamp(v, 127) * int16Weight` summed over 512 terms (256 width × 2 perspectives) — worst
  case ≈ 127 × 32,767 × 512 ≈ 2.13 billion, within ~17M of `int32`'s ~2.147B ceiling. The
  existing scalar code already defends against this deliberately (`long sum`, `NnueEvaluator.java:221`)
  — **a Stage 2 vector reduction must accumulate in 64-bit too, not narrow to `int` lanes for
  throughput.** This is a concrete implementation trap for whoever writes PR 4, not a
  hypothetical.
- **Recommended regression tests beyond the existing suite**: **none required for Stage 1.** The
  existing `NnueIncrementalVsRebuildFuzzTest` (`assertArrayEquals` over random legal games,
  every move type) and `NnueGoldenEvalTest` (exact int16 pinned values) already exercise exactly
  the bit-identical-output property Stage 1 needs, given the elementwise/associative properties
  above — this is a stronger, more precise finding than assuming new tests are needed. Stage 2
  should additionally spot-check the overflow boundary above (a position engineered to approach
  the 2.13B worst case) before relying on the existing suites alone.

### 16.4 Benchmark reproducibility (Task 4)

| Field | Value / requirement |
|---|---|
| Compile-target JDK | `release 21` (this project's Maven compiler target — confirmed from build output) |
| Runtime JDK | Zulu 25.0.3 (`OpenJDK 25.0.3`, native Windows — the actual JVM every measurement in §14/§15 ran on) |
| **Record both, do not collapse them** | The incubator-module risk (§14.4/§15.3) lives exactly in this gap — `jdk.incubator.vector` compiled against `release 21` and run on 25 is not a guaranteed-stable combination across an incubator API. PR 1's scaffolding work (species detection, `--add-modules` wiring) is precisely what de-risks this combination — it doesn't need a separate validation step; landing PR 1 *is* the validation. |
| JVM flags | None currently documented for `--bench` (plain `java -jar`). Recommend recording the exact invocation verbatim in every benchmark report, as #206's completion comment already did. |
| Warm-up strategy | None currently in `BenchRunner` (fresh `Searcher`/`Board` per position, no JIT warm-up loop) — consistent with treating `--bench` as a fixed-workload measurement, not a JIT-steady-state one; note this explicitly so a future reader doesn't assume warm-up happened. |
| Number of runs | 5 (median-of-5, per §15.5 / the `44aea1a` precedent) |
| Median calculation | Sort the 5 total-NPS values, take the middle one — matching `44aea1a`'s own stated methodology, not a mean (a mean is more sensitive to a single outlier run) |
| Acceptable variance | ±~4% (the documented ±12,584 NPS around the historical 316,964 baseline) — not re-derived for the NNUE path specifically; if a future 5-run NNUE sample shows materially wider spread, that itself is a finding worth recording, not silently averaged over |
| Reporting format | Match #206's closing comment format: commit hash, network UUID, JVM version (both compile and runtime per above), OS build, exact command, Classical NPS, NNUE NPS, ratio, pass/fail stated explicitly |

### 16.5 Documentation review (Task 5)

Re-audited §14–§15 against the six-category scheme (established fact / derived calculation /
projection / assumption / supported hypothesis / future work). No statement found that presents
a projection as an expected outcome or a hypothesis as an established cause — §15.1 already
carries explicit "do not read as an expected outcome" language on its projection table, and
§14.5 already separates its ranked hypotheses from the one "confirmed mechanism" finding within
them. No further rewording needed beyond this session's own two new findings above, which are
labeled inline (overflow trap: an implementation risk / future-work item for PR 4; determinism
argument: a derived-from-arithmetic-properties finding, not a projection).

### 16.6 Implementation readiness assessment (Task 6)

**#218 is ready. Implementation can begin, starting with PR 1.**

No blocking prerequisite remains. PR 1 (scaffolding — species detection, `--add-modules`
wiring, build/CI changes, zero algorithm change per §15.4) is itself the validation of the last
open unknown (compile/runtime JDK combination, §16.4) — proceeding and validating are the same
step at this point, not two sequential ones.

**Execution checklist, in order:**

1. PR 1 — scaffolding: `--add-modules jdk.incubator.vector` wiring, vector-species detection,
   build/CI changes. No algorithm change. Acceptance: existing full test suite passes unchanged.
2. Re-parameterize `NnueCorpusBenchmarkTest` (or an equivalent ad hoc microbenchmark) to width
   256 — required before PR 2's Level 1 measurement means anything (§16.1).
3. PR 2 — `addFeature`/`subtractFeature` SIMD implementation. Validate: existing
   `NnueIncrementalVsRebuildFuzzTest` + `NnueGoldenEvalTest` pass unchanged (§16.3 — sufficient,
   no new test category needed); mirror-symmetry test passes (CLAUDE.md §4).
4. Level 1 + Level 2 checks (§16.1): width-256 microbenchmark speedup measured; JIT/assembly
   inspection confirms real vectorized instructions are emitted, not a silent scalar fallback.
5. PR 3 — native-Windows measurement, median-of-5 (§16.4's reproducibility fields recorded in
   full), Classical re-measured in the same session, gate pass/fail stated explicitly.
6. Decision point (§15.5's tree): gate passes → stop here, recommend E-5; gate fails → verify
   Level 2's vectorization actually landed as expected before starting PR 4 (Stage 2).
7. (Conditional) PR 4 — `evaluate()` SIMD, with the 64-bit-accumulation requirement (§16.3)
   designed in from the start, not retrofitted after an overflow is found.
8. (Conditional) PR 5 — re-measurement, final gate determination.

## 17. PR 1 completion (2026-07-19 PM)

**Completed** — commit `b78edf6`. Scope matched §15.4/§16.6 exactly: build wiring + species
detection, zero algorithm change, `NnueEvaluator`/`Searcher` untouched.

**Measured facts:**
- `engine-core/pom.xml`: `--add-modules jdk.incubator.vector` added to `maven-compiler-plugin`
  (`compilerArgs`) and `maven-surefire-plugin` (`argLine`).
- New package-private `VectorCapabilities` (`AVAILABLE: boolean`, `PREFERRED_SHORT_LANES: int`),
  not referenced by any existing production class in this PR.
- Full regression suite: engine-core 274/274 passing (4 pre-existing skips), engine-uci 36/36
  passing (8 pre-existing skips, syzygy-tablebase-gated, unrelated to this change) — commit
  `b78edf6`'s own build log.
- **§16.4's compile-21/runtime-25 claim, actually validated this time** (the prior review round
  had asserted this without testing it — corrected here, not repeated as an unverified claim):
  built the shaded jar with WSL JDK 21.0.11, copied to native Windows, ran under Zulu 25.0.3.
  Both JVMs (WSL 21.0.11 and native-Windows Zulu 25.0.3) agree: with `--add-modules
  jdk.incubator.vector` passed to the launching `java`/`jshell`, `AVAILABLE=true,
  PREFERRED_SHORT_LANES=32`; without the flag, `AVAILABLE=false, PREFERRED_SHORT_LANES=0`, no
  crash on either JVM.

**Implementation observations (new, not previously documented):**
- The two-JVM validation above is the actual test of the "PR 1 de-risks the compile-21/
  runtime-25 combination" claim from §16.4 — a prior pass had stated this as PR 1's effect
  without running it on Zulu 25 specifically (WSL-only testing would have exercised 21/21, not
  21/25). Recorded here so the claim is now evidence-backed, not asserted.
- `PREFERRED_SHORT_LANES=32` on **both** environments (WSL and native Windows) — a data point,
  not yet a conclusion about identical underlying hardware vector width on both machines (the
  JVM's "preferred species" reflects a JIT/runtime policy choice, not a direct hardware probe
  from this vantage point); relevant for PR 2's Level 1 width-256 microbenchmark, which should
  record its own environment's lane count rather than assume the value carries over.
- No new test category was needed beyond `VectorCapabilitiesTest` itself — the existing NNUE
  regression suite (`NnueIncrementalVsRebuildFuzzTest`, `EvalMirrorSymmetryPropertyTest`,
  `NnueModeSearchRegressionTest`) passing unchanged is the evidence for "zero behavioral change,"
  stronger than a redundant node-count re-run would have been (§16.3's own reasoning: a
  build-config change plus an unreferenced class cannot alter codegen of classes that don't
  reference it).

**Deviations from the original plan:** none in scope. One correction to prior documentation: an
XML-comment authoring mistake (`--` sequences inside `<!-- -->`, invalid per the XML spec) was
caught by the POM failing to parse on the first build attempt, fixed immediately — worth noting
only because it's a real "measurement caught an error before it shipped" instance, not a
planning deviation.

**Future work (unchanged from §16.6, not started):** PR 2 (Stage 1 SIMD implementation) is next,
gated on re-parameterizing the width-8 microbenchmark to width 256 first (§16.1, §16.6 step 2).

## 18. PR 2 completion (2026-07-19 PM) — Stage 1 SIMD implementation

**Completed** — commit `9780dd0`. Scope matched §15.4/§16.6 exactly: `addFeature`/
`subtractFeature` only, `evaluate()` untouched, no search change.

### 18.1 Implementation (measured facts)

- New `NnueAccumulatorVectorOps` (isolated incubator-type class, same lazy-resolution safety
  pattern as PR 1's `VectorCapabilities` — see §17): vectorized `addFeature`/`subtractFeature`
  over `ShortVector.SPECIES_PREFERRED`, scalar remainder loop for any width not evenly divisible
  by the species length.
- `NnueEvaluator`'s original loops renamed `addFeatureScalar`/`subtractFeatureScalar`
  (package-private — kept as the fallback path and as the equivalence-test reference), with a
  `VectorCapabilities.AVAILABLE`-gated dispatch preserving the original call sites unchanged.
- `NnueCorpusBenchmarkTest` re-parameterized from `TestNetworks.synthetic(8)` to `synthetic(256)`
  (production width) and extended with a direct scalar-vs-vector microbenchmark.

### 18.2 Correctness validation (measured facts)

- New `NnueAccumulatorVectorOpsEquivalenceTest`: `assertArrayEquals` between
  `addFeatureScalar`/`subtractFeatureScalar` and `NnueAccumulatorVectorOps`'s methods, across
  widths {8,16,31,32,33,64,128,200,256,512}, 20 random trials each, plus an explicit
  `Short.MAX_VALUE + Short.MAX_VALUE` overflow-boundary case — all bit-identical, 3/3 passing.
- Full regression suite: engine-core 278/278 (5 pre-existing skips), engine-uci 36/36 (8
  pre-existing skips, syzygy-gated, unrelated), `search-regression` profile 3/3.
- `NnueGoldenEvalTest` (exact pinned int16 eval values) passes unchanged — and because
  `VectorCapabilities.AVAILABLE=true` in this build environment, this run **actually exercised
  the vectorized path**, not just the scalar fallback: the golden values are bit-identical
  through the new code, not merely through an untouched fallback.
- `NodeCountRegressionTest` and the 37-case `SearchRegressionTest` unchanged — confirms zero
  search-behavior change, as required.

### 18.3 Runtime verification (measured facts)

- `VectorCapabilities.AVAILABLE=true`, `PREFERRED_SHORT_LANES=32` in this build/test
  environment (WSL OpenJDK 21.0.11) — consistent with PR 1's own native-Windows Zulu 25.0.3
  reading (§17), though this is two data points, not a guarantee the same holds on every future
  machine.
- Dispatch correctness (Vector API selected when `AVAILABLE`, scalar otherwise) is exercised
  structurally, not just asserted: every regression-suite run in this environment already goes
  through the `AVAILABLE=true` branch (confirmed by the golden-eval/fuzz tests above passing
  against the vectorized path), and `NnueAccumulatorVectorOpsEquivalenceTest` independently
  proves the scalar fallback produces the same result if that branch is ever taken instead.
- **Not done this round**: JIT/assembly-level inspection (`-XX:+PrintAssembly`) confirming the
  emitted machine code for `NnueAccumulatorVectorOps` uses real vector-width instructions rather
  than a silent scalarized loop — deferred, since §18.4's finding below makes this check
  materially more important for the *scalar* comparator than originally scoped, and it belongs
  with the native-Windows PR 3 work rather than this microbenchmark round.

### 18.4 Performance validation — a genuine confound found, not a clean number

**Measured**: `--add-modules jdk.incubator.vector`, width 256, `PREFERRED_SHORT_LANES=32`,
20 measurement rounds after 5 warmup rounds, 200,000 ops/round:

| Harness variant | Scalar ns/op | Vector ns/op | Speedup |
|---|---|---|---|
| First attempt (functional-interface indirection, `FeatureOp` lambda) | 12.1 (±0.2) | 9.0 (±0.0) | 1.34x |
| Corrected (direct calls, no lambda) — 3 independent JVM runs | 91.1–92.1 (±~1) | 7.4–7.7 (±~1) | 11.87x–12.33x |
| Standalone control, default JVM flags | 22.4 | — | (SuperWord reference point) |
| Standalone control, `-XX:-UseSuperWord` | 76.7 | — | (SuperWord reference point) |

**Derived**: the `-XX:-UseSuperWord` control directly confirms the mechanism — C2's
SuperWord/SLP auto-vectorizer targets this exact loop shape (a counted `short[]` elementwise
add), and **triggers inconsistently depending on calling context** (12ns/22ns/91ns are all real
measurements of the *same scalar source code*, differing only in whether/how much C2
auto-vectorized it in that specific harness shape).

**Supported hypothesis, explicitly not resolved**: the practical Stage 1 speedup is genuinely
**a range (roughly 1.3x–12x), not a single number**, and which end of that range the *real
search* lands on depends on whether C2 auto-vectorizes `addFeatureScalar` inside the actual
`Searcher`/`NnueEvaluator` call pattern — a different inlining/polymorphism/warmup context than
either isolated microbenchmark variant above. **This cannot be resolved by microbenchmarking in
isolation.** Explicit Vector API is reliably faster than a *genuinely non-vectorized* scalar
baseline (confirmed structurally, not just by one lucky reading); whether the production
scalar path is itself already partially auto-vectorized — shrinking Stage 1's real-world
benefit — is unknown until measured in place.

**CPU-bound vs. memory-bound**: the vector arm's own footprint (256 shorts × 2 arrays ≈ 1KB
per call) is far smaller than L1 cache, and the vector-side speedup (up to ~12x against a
confirmed-non-vectorized baseline) tracks closer to the lane-count improvement (32 lanes) than a
bandwidth ceiling would allow — consistent with this operation being **instruction-throughput/
loop-overhead-bound**, not memory-bandwidth-bound, at this data size. This is a **projection
about mechanism**, not a directly measured bandwidth figure (no hardware perf-counter
measurement was taken).

**Do NOT plug either 1.34x or ~12x into §15.1's model as if it settles the Stage 1 gate
projection** — both are real measurements of genuinely different conditions (autovectorized vs.
non-autovectorized scalar baseline), and neither is confirmed to match what the real search's
scalar path does. §15.1's projection stands as previously stated (41–46% under its own
assumptions); this microbenchmark neither confirms nor refutes it — it establishes that a real,
positive, meaningful speedup exists, which is what gates proceeding to PR 3, not a specific
number to plug in.

### 18.5 Recommendation

**Proceed to PR 3** (native-Windows measurement) as the next step — not run in this round, per
scope. The gate for proceeding ("microbenchmark demonstrates a meaningful improvement") is met
under either interpretation above: even the conservative 1.34x reading is a real, reproducible,
positive result, and the corrected/SuperWord-off readings show substantially more headroom is
plausible. Frame PR 3 as **the actual arbiter of Stage 1's real-world benefit**, not a
confirmation exercise — it is the only measurement that captures what C2 actually does to the
scalar comparator inside the real, integrated build, which this isolated microbenchmark
structurally cannot replicate.

**Deviation from the original plan**: none in implementation scope. The microbenchmark itself
required one in-flight correction (lambda-indirection removed after producing an implausibly
fast, and as it turned out, misleading, scalar reading) — documented in the test's own javadoc
so the caveat travels with the number for any future reader running it, not just in this
document.

## 19. PR 3 — native-Windows integrated-engine measurement and model validation (2026-07-20)

Per §15.5's plan: the microbenchmark (§18.4) exposed a JIT confound and was explicitly ruled
out as the arbiter of Stage 1's real-world benefit. This section reports the native-Windows,
integrated-engine measurement that resolves it — same commit (`652645b`, PR 1 + PR 2), same net
(`dfffd3da-…nnue`), same 31-position post-#217 suite, same `--bench 13`, median-of-5 per §15.5
Task 3.

### 19.1 Native-Windows benchmark results (Task 1)

**Measured** (Zulu 25.0.3, `C:\vex-bench-206\`, median of 5 runs each):

| Run set | Flags | Individual NPS (5 runs) | Median NPS | Nodes (all runs) |
|---|---|---|---|---|
| Classical | (none) | 330,126 / 336,525 / 338,805 / 335,570 / 340,955 | **336,525** | 73,089,246 (identical) |
| NNUE, vector path | `--add-modules jdk.incubator.vector` | 150,418 / 152,379 / 157,268 / 159,715 / 159,715 | **157,268** | 251,406,555 (identical) |

**Derived**: NNUE-vector / Classical, same session = 157,268 / 336,525 = **46.7%** — clears the
≥40% gate.

**Single-run checks** (not median-of-5, one run each — used for path/JIT verification, Tasks 4–5,
not for the gate decision itself):

| Run | Flags | NPS | Eval % | Accumulator % | Nodes |
|---|---|---|---|---|---|
| NNUE, scalar fallback | (no `--add-modules`, `VectorCapabilities.AVAILABLE=false`) | 111,863 | 29.1% | 36.3% | 251,406,555 (identical) |
| NNUE, scalar fallback + no SuperWord | (no `--add-modules`, `-XX:-UseSuperWord`) | 108,159 | 28.3% | 38.5% | 251,406,555 (identical) |

### 19.2 Before/after comparison table (Task 2)

Pre-PR2 figures are §14.1's original #206 measurement (commit `afc26d8`); post-PR2 figures are
§19.1's median-of-5 (commit `652645b`). Both same benchmark suite, same net, same machine.

| Quantity | Pre-PR2 (#206) | Post-PR2 (PR 3) | Delta |
|---|---|---|---|
| Classical NPS | 311,669 | 336,525 | +24,856 (+8.0%) — session variance, Classical code unchanged |
| NNUE NPS | 107,714 | 157,268 | +49,554 (**+46.0%**) |
| Ratio (NNUE/Classical, same session) | 34.6% | **46.7%** | **+12.1 points** |
| Classical elapsed | 234.509 s | 217.19 s | −17.32 s (−7.4%, session variance) |
| NNUE elapsed | 2,334.018 s | 1,598.59 s | −735.43 s (**−31.5%**) |
| NNUE ns/node (total) | 9,284 | 6,359 | −2,925 (−31.5%) |
| NNUE eval % / ns-node | 29.1% / 2,702 | ~41.2% / ~2,620 | ns/node flat (−3.0%, noise); % share rose only because the denominator (total ns/node) shrank |
| NNUE accumulator % / ns-node | 36.3% / 3,370 | ~10.5% / ~674 | **−2,696 ns/node (−80.0%, ≈5.0x)** |
| NNUE remainder % / ns-node | 34.6% / 3,212 | ~48.3% / ~3,065 | ns/node flat (−4.6%, noise) |
| Classical nodes | 73,089,246 | 73,089,246 | 0 (identical) |
| NNUE nodes | 251,406,555 | 251,406,555 | 0 (identical) |

**Note on the eval/accumulator % split for the vector median run**: read from the same
per-run instrumentation used for the scalar-fallback rows above (which reproduce §14.1's
pre-PR2 percentages almost exactly — 29.1%/36.3% vs 29.1%/36.3% — confirming the instrumentation
itself is unchanged and comparable across sessions); the vector-run figures are rounded to one
decimal and should be read as approximate, not to three-significant-figure precision.

**Causal attribution**: node counts are bit-identical across every measurement occasion —
#206's original run, all 5 vector runs, both scalar-fallback runs — confirming Task 4's
zero-behavioral-change requirement directly (not inferred). Of the three ns/node buckets, only
the accumulator moved outside noise (−80%); eval and remainder both stayed flat within the
~3–5% band already established as this benchmark's run-to-run variance (§15.5). The entire NPS
gain traces to exactly the code Stage 1 touched and nowhere else — the improvement is not an
artifact of session drift.

### 19.3 §15.1 model validation (Task 3)

| Assumption | §15.1 statement | Measured outcome | Verdict |
|---|---|---|---|
| A1 (accumulator 90/10 accelerable/floor split, ~3,033 / ~337 ns/node) | Not measured, inferred from code read | Post-accumulator ≈ 674 ns/node. If the ~337 ns/node floor (instrumentation + `arraycopy`, neither touched by Stage 1) held fixed, the accelerable portion dropped 3,033 → ~337 ns/node, i.e. **implied speedup on the accelerable fraction ≈ 9.0x** | **Partially held** — the floor/accelerable split shape was directionally right, but A3's speedup magnitude on that fraction was badly underestimated (see below) |
| A2 (eval floor/accelerability, Stage 2 only) | Weakest assumption, explicitly flagged | Not tested — Stage 1 doesn't touch `evaluate()`; eval ns/node stayed flat (2,702→~2,620, within noise), consistent with A2 simply not having been exercised | **Untested, as scoped** — correctly out of PR 3's scope; still open for a future Stage 2 decision |
| A3 (2–4x speedup hedge on the accelerable fraction) | "Real-world JIT/Vector-API speedups typically land well below theoretical peak, so 2–4x, not 16x" | Implied real speedup ≈9.0x (derived above) | **Disproven as too conservative** — actual speedup on the accelerable fraction was more than double the top of the hedged range |
| A4 (remainder unaffected — move-gen/make-unmake/TT/search-logic) | Strong assumption, evaluator-agnostic code | Remainder ns/node flat (3,212→~3,065, within noise) | **Held** |

**Projected vs. actual**: §15.1's Stage-1-alone table topped out at S=4x → 45.8%. The measured
46.7% sits just above that top row — consistent with an effective speedup well past 4x on the
accelerable fraction, as A1/A3's re-derivation above shows directly (≈9.0x, not 2–4x). The
**shape** of §15.1's model (accumulator-only levers project into the low-to-mid 40s%) was
correct; its **speedup magnitude hedge** was conservative by roughly 2x.

### 19.4 Execution-path validation (Task 4)

**Measured**: node counts are bit-identical (251,406,555 for NNUE, 73,089,246 for Classical)
across every run in §19.1 — all 5 vector runs, both scalar-fallback runs, and #206's original
pre-PR2 measurement. No PV divergence or score difference was observed in any run's output.

**Conclusion**: both execution paths (Vector API enabled vs. scalar fallback) produce
identical search trees and identical evaluation output in the integrated engine, confirming
Stage 1 introduced zero behavioral change — the same conclusion the unit-level equivalence
tests (§18.2, `NnueAccumulatorVectorOpsEquivalenceTest`) established at the method level, now
independently confirmed at the whole-search level.

### 19.5 JIT verification (Task 5)

**Observation** (measured, no interpretation): in the integrated engine, disabling SuperWord on
the scalar-fallback path changes NPS by only ~3% (111,863 → 108,159). In §18.4's isolated
microbenchmark, the identical flag change moved scalar timing by ~3.4x (22.4ns/op →
76.7ns/op).

**Interpretation** (separated from the observation above): the small in-engine SuperWord
delta indicates C2 is **not** meaningfully auto-vectorizing `addFeatureScalar`/
`subtractFeatureScalar` inside the real `Searcher`/`NnueEvaluator` call pattern — unlike some
isolated microbenchmark harness shapes, where inlining budget and call-site monomorphism let
SuperWord fire. This resolves §18.4's open question in favor of the **corrected/SuperWord-off
microbenchmark reading (~9–12x)**, not the lambda-confounded reading (~1.34x), as the more
representative estimate of what Stage 1 actually replaced in production. It is independently
corroborated by §19.3's arithmetic: an implied ≈9.0x accelerable-fraction speedup is only
possible if the scalar baseline it replaced was close to fully non-vectorized — a ~1.3x-baseline
world would have produced a post-Stage-1 accumulator figure far above what was actually
measured. Two independent measurements (microbenchmark SuperWord control, integrated-engine
accumulator-share collapse) triangulate on the same conclusion.

**Not done**: `-XX:+PrintAssembly`/`hsdis` disassembly inspection of either path was not
performed in PR 2 or PR 3. The SuperWord-flag differential above is treated as sufficient
evidence for this decision (it directly answers the load-bearing question — "is the scalar path
already vectorized" — without requiring instruction-level inspection), not as a substitute for
disassembly if a future investigation needs the actual emitted instruction sequence (e.g. to
confirm species width or a specific intrinsic). The explicit Vector API path is not similarly
verified by disassembly either; its correctness rests on JEP 338's documented behavior (lowers
via `VectorSupport` intrinsics independent of the SuperWord pass) plus the bit-identical
equivalence tests (§18.2), not a disassembly read in this session — labeled here as
documented-behavior, not measured-this-session.

### 19.6 Decision (Task 6)

**Decision A: Stage 1 cleared the gate.** 46.7% ≥ 40%, measured via median-of-5 on both sides
with a same-session Classical baseline, with node-count identity confirming zero behavioral
change. Per §15.5's own decision tree ("Gate passes (≥40%, median-of-5): Stage 2/PR 4-5 not
started. Recommend proceeding directly to E-5"), Stage 2 is **not** justified by this result.

**Blocking precondition found and fixed during this PR**: `tools/sprt.ps1` and `tools/match.ps1`
(the actual E-5 SPRT/match launchers) constructed their `cutechess-cli` engine commands as
`cmd=$Java arg=-jar arg=<path>` — **no `--add-modules jdk.incubator.vector`**. Under that
invocation, `VectorCapabilities.AVAILABLE` resolves `false` and the engine silently runs the
scalar-fallback path, which measures 111,863/336,525 = **33.2%, failing the gate** in the same
session that the vector path passes it. This was not a hypothetical — it is exactly how E-5
would have launched the engine before this fix. Fixed in this PR (both scripts now pass
`--add-modules jdk.incubator.vector` to both engines under test; `tools/launch_vex.ps1`, which
CLAUDE.md's Cutechess docstring names as an alternative launcher, received the same fix for
consistency). **E-5 must not start without this fix in place** — it now is, but any future
change to these scripts should preserve the flag.

**Scope note, not a strength claim**: clearing the NPS gate removes E-6 as a blocker on E-5; it
says nothing about playing strength. §14.1's Lever A/B split still applies — Stage 1 is
Lever A (throughput) only. The ~3.44x node-count excess and the pruning-margin/calibration
question (§14.5, #219, Lever B) are untouched by this work and remain open; E-5's SPRT
*outcome* depends on eval strength, not on this gate.

### 19.7 Summary and stop condition

Per this task's instruction: **stop after PR 3.** No Stage 2 work, no `evaluate()` changes, no
search changes were made. The only code changes beyond PR 1/PR 2 are the three-script
`--add-modules` launcher fix in §19.6, none of which touch `evaluate()`, search behavior, or
evaluator semantics.

## 20. Issue #217 closure (2026-07-20) — practical impact fully characterized as null

#217 (iterative-deepening/TT-size search instability on the former bench position 15) was
already decoupled from #206 (§14.6): the benchmark's dependency was resolved by removing the
position, independent of root cause. This closure asks the remaining question the issue itself
never answered: **could the same pathology cause a real problem in an actual (SPRT) game?**

**New investigation, this session** — read `Searcher.iterativeDeepening`'s time-management
wiring and `BenchRunner`'s call path, to determine whether time is checked only *between* ID
iterations (in which case a single pathological iteration, like the depth-13 blowup measured
at 15+ minutes, could run unchecked past a real time control) or *within* an iteration.

**Measured facts (code read, this session):**
- `BenchRunner.java:187` calls `searcher.searchDepth(board, depth)`, which is
  `iterativeDeepening(board, depth, () -> false)` (`Searcher.java:366-367`) — bench runs with an
  **always-false abort predicate, i.e. no time bound at all**, by design (bench measures
  fixed-depth node counts, not wall-clock behavior). This is *why* the pathology can run
  uninterrupted for 15+ minutes in bench.
- Real UCI games do not go through this path. `searchWithTimeManager` (`Searcher.java:378-395`)
  supplies `timeManager::shouldStopHard` as the hard-stop predicate, which is checked at the top
  of **every** `alphaBeta` node (`Searcher.java:837-840`, `if (shouldStopHard.getAsBoolean())
  { aborted = true; return alpha; }`) and every `quiescence` node — on every node visited, with
  no node-count throttle/modulo gating found anywhere in the search loop.
  `TimeManager.shouldStopHard()` (`TimeManager.java:137-138`) is a direct `elapsedMs() >=
  hardLimitMs` comparison.
- Once `aborted` is set, every enclosing recursive frame checks it immediately after each
  recursive call and unwinds without further work — overrun past the deadline is bounded by
  roughly one node's cost, not by depth or subtree size.

**Conclusion**: the node-count blowup this issue documents **cannot cause a time forfeit or
even a meaningfully late move in a real, time-controlled game** (SPRT or otherwise) — a genuine
per-node hard deadline is already enforced independent of this issue, and would interrupt an
87M-node-scale blowup within microseconds/single-digit-ms of the configured limit, same as any
other search that runs long. The pathology is a **bench-harness artifact**: it is only
observable because `--bench`/`searchDepth` intentionally searches with no time bound at all, to
get clean fixed-depth node counts. It does not indicate a time-management defect (none was
found; the hard-stop mechanism is unconditional and unthrottled) and does not indicate a
correctness defect (already established in the issue itself — every completed run returns a
well-formed PV with `depthReached() == 13`, no early termination).

**Disposition**: combined with the issue's own existing mitigation (position removed from the
bench suite, §14.6), this closes the practical-impact question definitively: **no**, neither
the benchmark nor real games are at risk. Root cause (why TT carryover across ID iterations
produces non-monotonic node counts at some sizes) remains scientifically open, exactly as the
issue itself already scoped ("Root cause — intentionally left open; not claimed here") — this
closure does not claim to have found it, only to have shown it has no practical consequence
worth further investigation ahead of E-5. The deferred no-TT-vs-TT experiment documented in the
issue remains available as a future curiosity-driven investigation, not a blocker for anything.

**Closed**: #217, closing comment posted with this summary.

## 21. Issue #218 closure (2026-07-20)

All acceptance criteria are now satisfied:

- Stage 1 implemented with scalar fallback — PR 2, §18.1.
- Vectorized output bit-identical to scalar — unit-level (§18.2,
  `NnueAccumulatorVectorOpsEquivalenceTest`) and whole-search-level (§19.4, identical node
  counts/PVs across vector and scalar-fallback runs).
- `--bench` re-run in NNUE mode on native Windows, NPS recorded — §19.1 (median-of-5,
  157,268 NPS).
- Pass/fail against the ≥40% gate recorded — **46.7%, pass** — §19.1/§19.6.
- Stage 1 alone clears the gate → Stage 2 not implemented, per §19.6's Decision A.

**One invariant not yet directly checked before this closure**: the issue's own "Architecture
Invariants That Must Remain True" section requires no object allocation in hot paths
(CLAUDE.md §3), and no prior PR in this sequence measured this for the new vector path
specifically (only reasoned informally that Vector API objects are typically escape-analyzed
away). Checked now, directly:

**Measured** (same-package probe class, `--add-modules jdk.incubator.vector`, WSL —
allocation-count is a JIT-behavior/code-shape property, not an NPS-magnitude one, so unlike the
NPS gate itself this check is valid off native Windows): after a 500,000-iteration warmup,
`ThreadMXBean.getThreadAllocatedBytes` measured across 2,000,000
`addFeature`+`subtractFeature` call pairs on `NnueAccumulatorVectorOps` reports **0 bytes
allocated**. C2's escape analysis eliminates the `ShortVector` intermediate objects entirely
once the loop is JIT-compiled, consistent with the Vector API's documented design intent
(JEP 338). The "no allocation in hot paths" invariant holds for Stage 1's vector path,
confirmed directly rather than assumed.

**Disposition**: all acceptance criteria met, no follow-up work remains in scope, Stage 2
intentionally not pursued (Stage 1 sufficient). **Closed**: #218, closing comment posted with
this summary.

## 22. Issue #219 — calibration diagnostics against the real net (2026-07-20)

§5.1 specified these diagnostics but they had never been run against the real net
(`dfffd3da-…`, checkpoint `trainer/outputs/nets/checkpoint.pt`). This session implements and
runs them, per #219's Objective items 1-3.

### 22.1 Implementation

Added `calibration_report()` to `trainer/trainer/validation/validator.py` — a new function
alongside the existing `evaluate_held_out`/`eval_scale_check`, computing signed mean error
(`mean(predicted - target)`) and score-compression ratio (`std(predicted)/std(target)`) as
`CalibrationBucket`s, split overall / mate-labeled / cp-labeled / per-phase, per §5.1's exact
spec. No new data collection — reuses the same `predicted_cp`/`target_cp` arrays
`evaluate_held_out` already computes internally, via the existing `encode_batch`/`target_cp`/
`phase_of` infrastructure. 4 new unit tests added (`trainer/tests/validation/test_validator.py`);
one against a hand-computed reference value, one for the empty-input rejection, one covering
mate/cp/phase bucket-count accounting, one checking every bucket with >1 record is finite (a
single-record bucket's compression ratio is genuinely undefined, 0/0 — not a bug). Full trainer
suite: 157/157 passing.

### 22.2 Measured — real net, real held-out set (n=4,000)

Reproduced via the exact snippet in `trainer/configs/train-e3-real.md` (same seed=42 split,
same checkpoint) — `evaluate_held_out` reproduced bit-for-bit against the committed manifest
(`held_out_loss=0.08225942403078079`, `label_correlation=0.5043603181838989`), confirming no
train/held-out leakage and that the checkpoint/split pairing is exactly the one the manifest
documents.

| Bucket | n | Signed mean error (cp) | Compression ratio |
|---|---|---|---|
| **Overall** | 4,000 | **−219.6** | **0.063** |
| Mate-labeled | 472 (11.8%) | −1,726.6 | 0.055 |
| Cp-labeled | 3,528 (88.2%) | −18.0 | 0.075 |
| Phase: opening | 1,473 | −5.8 | 0.100 |
| Phase: middlegame | 1,236 | −47.3 | 0.071 |
| Phase: endgame | 1,291 | −628.6 | 0.058 |

**Cross-tabulation** (why endgame's bias is so much larger than opening's): mate-labeled
records are heavily concentrated in endgame (342/472 = 72.5% of all mate-labeled records are
endgame; only 23/472 = 4.9% are opening). Endgame's 1,291-record bucket is 26.5% mate-labeled
(342/1291) vs. opening's 1.6% (23/1473) — endgame's large aggregate bias is substantially a
**composition effect** from mate-label concentration, not solely evidence of an independent,
phase-specific miscalibration beyond what the mate-handling problem already explains. (Note:
the per-phase buckets above mask on phase only — mate-labeled and cp-labeled records mixed
together — they are not a cp-only view; no phase×mate-label intersection was computed.) The
mate-free **cp-labeled overall bucket alone** (0.075, n=3,528) already shows the compression
problem is not solely a mate-handling artifact — it is present in the pure cp-regression
signal too, independent of any phase breakdown.

### 22.3 Cross-reference against §14.5's pruning-rate collapse (Objective item 2)

**Derived, mechanistic plausibility argument** (not a controlled experiment isolating this one
variable — see caveat below): delta/futility pruning are `staticEval ± margin` gates against
fixed cp constants (150/300cp, tuned against Classical's scale). A compression ratio of
0.055-0.100 means NNUE's static eval spans roughly **10-18x less cp range** than the scale
those margins were tuned against. A position Classical would evaluate as lost by, say, 1,500cp
(comfortably triggering delta pruning) would land at roughly 1,500 × 0.06-0.10 ≈ **90-150cp**
under NNUE's compressed scale — at or below the smaller (150cp) fixed margin, meaning the
`staticEval + margin <= alpha`-style gate frequently fails to fire where it should. This is
**directly consistent in mechanism and rough magnitude** with §14.5's measured ~100-300x
delta-pruning-rate collapse (56.0%→0.48%, 39.8%→0.13%): a margin gate that used to trigger
reliably against Classical's scale becomes marginal-to-inactive against a static eval compressed
by an order of magnitude.

**Caveat, stated explicitly**: this is a plausibility argument from two independently measured
quantities (compression ratio here; pruning-rate collapse in §14.5), not a single experiment
that varies compression and observes the pruning rate directly — the two measurements were
taken on different data (held-out training records here; two specific bench positions in
§14.5) and are connected by reasoning about the pruning-gate arithmetic, not by a shared
measurement run. It should be read as **a supported hypothesis for the mechanism**, materially
strengthened by this session's evidence, not a proven causal chain.

### 22.4 Recommendation (Objective item 3 — exactly one of four)

**(b): the net itself needs recalibration (K-value/output-scale) before margins can be
judged.** Not (a), not (c), not (d). Reasoning:

- **(d) is ruled out.** Compression is severe (0.055-0.100, i.e. real, not marginal) and
  consistent across every independent slice checked (mate-labeled, cp-labeled, and all three
  phases) — this is not a measurement artifact confined to one bucket.
- **(a) alone is ruled out.** Tuning margins against the *current* net's arbitrary compressed
  scale would need re-tuning again after any future net recalibration or retrain — margins
  would be chasing a moving target. §14.7's own roadmap table already lists calibration
  diagnostics as a *dependency* of the margin-recalibration investigation, not a parallel,
  independent track — this session's result confirms that ordering was right.
- **(b) is the parsimonious fix.** The compression is a global scale property of the net (present
  in every slice, not gate-specific), so fixing it once at the source is lower total effort than
  compensating for it separately in every fixed-margin constant that consumes `staticEval`.
  Recalibration (K-value and/or `output_scale`) is also the more durable choice: if it
  successfully restores the compression ratio toward ~1, the *existing* Classical-tuned margins
  may turn out to already be adequate, avoiding a separate NNUE-specific margin re-tune
  entirely — worth checking before assuming (a) or (c) is also needed.
  **"Recalibration" here is not a free one-line `output_scale` multiply**, and this is worth
  being explicit about: `NnueNet.forward` (`network.py:100`) applies `output_scale` as a single
  multiplicative factor on the whole raw sum (`raw_sum * output_scale / (qa * qb)`), so naively
  scaling `output_scale` up by roughly 1/0.063 ≈ 16x to fix the compression ratio would scale
  the −219.6cp bias by the same factor too, to roughly −3,500cp — clearly worse, not better.
  A real fix needs a re-fit (retraining, or at minimum an affine scale-**and**-shift correction
  derived from these numbers, not a pure scale multiply) — reinforcing that (b) targets the
  scale/pruning problem specifically and is a distinct effort from Track B's correlation/
  strength problem, not a quick config change.
- **(c) (both) is not recommended as the starting point**, for the same reason as (a) alone:
  doing a margin re-tune concurrently with recalibration risks tuning against a scale that is
  about to change. Recalibrate first, then re-measure §14.5's pruning-rate collapse against the
  recalibrated net; only pursue per-evaluator margins if the collapse persists after
  recalibration.

**Explicitly out of scope for this issue** (per its own Non-Scope section, unchanged): no
margin retune, no net retrain/recalibration performed here — this is the diagnosis this issue
was asked to produce, not the fix. The recalibration work itself (and the re-measurement of
§14.5's counters against a recalibrated net) is follow-up work for whoever picks up the
roadmap's Lever B track next; not started in this session.

### 22.5 Closure

All three acceptance criteria satisfied: calibration diagnostics computed and recorded (§22.2);
explicit cross-reference against the pruning-rate collapse made, with its evidentiary strength
stated honestly (§22.3); exactly one recommendation made, supported only by the evidence
collected (§22.4). **Closed**: #219, closing comment posted with this summary.

## 23. #219(b) follow-up — can affine post-hoc calibration correct compression/bias without retraining? (2026-07-20)

§22.4 recommended "(b): net recalibration needed" but stopped short of testing whether that
recalibration could be as cheap as a post-hoc `score' = a*score + b` transform, or whether it
requires retraining. This investigation answers that directly, analytically, before touching
the network. **No retraining performed** (out of scope, per instruction).

### 23.1 Implementation

Added `fit_affine_calibration()` and an `affine` parameter on `calibration_report()` to
`trainer/trainer/validation/validator.py` — closed-form ordinary least squares
(`scale = cov(predicted, target) / var(predicted)`, `shift = mean(target) - scale * mean
(predicted)`), and extended `CalibrationBucket` with `mae`/`rmse` alongside the existing
signed-mean-error/compression-ratio fields (§22's `CalibrationBucket` gets two new fields, no
existing field removed or renamed). 3 new unit tests, including a direct check of OLS's own
guarantee (zero mean residual on the fitting set). Full trainer suite: 160/160 passing.

### 23.2 Method — honest out-of-sample evaluation, not just in-sample

The held-out set (n=4,000) was split again (`random.Random(219)`, independent of the training
seed) into a 2,000-record calibration-fit half and a 2,000-record calibration-eval half, so the
reported diagnostics are out-of-sample **relative to the affine fit itself**, not merely
relative to the original NNUE training. A separate affine fit on the full 4,000 records was
also computed as an in-sample cross-check.

**Fit stability check**: `scale=8.117, shift=147.0` (fit on the 2,000-record half) vs.
`scale=8.003, shift=124.8` (fit on the full 4,000) — close agreement confirms the 2-parameter
fit is not sensitive to which half of the data it saw, as expected at this sample size.

### 23.3 Measured — original vs. affine-calibrated (calibration-eval half, n=2,000, out-of-sample)

| Bucket | Original bias | Calibrated bias | Original compression | Calibrated compression | Original MAE | Calibrated MAE | Original RMSE | Calibrated RMSE |
|---|---|---|---|---|---|---|---|---|
| Overall (n=2,000) | −193.78 | **+47.57** | 0.062 | **0.504** | 567.91 | 582.42 | 1280.75 | **1138.04** |
| Mate-labeled (n=234) | −1,600.36 | −798.96 | 0.051 | 0.410 | 2,899.22 | 2,112.16 | 2,901.62 | 2,328.70 |
| Cp-labeled (n=1,766) | **−7.40** | **+159.74** | 0.075 | 0.611 | **259.00** | **379.72** | 861.44 | 864.98 |

(In-sample cross-check on the full 4,000 — same qualitative pattern, overall bias exactly
0.00 as OLS guarantees on its own fitting set: original compression 0.063 → calibrated 0.504;
original overall MAE 569.59 → calibrated 562.83; cp-labeled bias −18.01 → +128.89.)

### 23.4 The binding constraint is correlation, not scale — and no affine transform can cross it

**Measured, the decisive fact**: Pearson correlation is mathematically invariant under any
affine transform with positive scale (`corr(a·X + b, Y) = corr(X, Y)` for `a > 0`) — a property
of the correlation coefficient itself, not an empirical claim. The calibrated overall
compression ratio (0.504) lands almost exactly on the pre-calibration label correlation
(0.5044, `evaluate_held_out`, §22.2) — **confirming directly, not just in theory, that
compression is capped at the predictor's correlation with the target, and an affine transform
can approach that cap but never exceed it.**

**Measured — the ceiling is lower than the global figure suggests once mate/cp are separated**:
correlation computed *within* the cp-labeled subset alone is **0.362**, lower than the global
0.504. The higher global figure is a pooling effect — mate vs. cp-labeled records are two
widely separated clusters (target ≈ ±3,000 vs. target std ≈ 880), and a predictor that merely
gets the *cluster* right scores a moderate correlation even with weak within-cluster ranking.
This directly answers the obvious follow-up question ("what if mate and cp were calibrated
separately?") **before it needs a second experiment**: a piecewise affine fit restricted to
cp-labeled records would have an even lower compression ceiling (~0.36), not a higher one — the
correlation problem is not a byproduct of mixing mate and cp records, it is present, and
somewhat worse, within the ordinary (non-mate) positions that dominate real search.

**Conclusion**: the net's fundamental problem is not scale, it is **signal quality** — its
relative ranking of positions (especially ordinary, non-mate ones) is weak. Bias is a scale/
offset property, fully correctable by an affine shift (confirmed: in-sample overall bias
→ 0.00 exactly, out-of-sample → within noise of 0). Compression is a scale property too, but
only correctable up to the correlation ceiling. **Correlation itself is not a scale property —
it is invariant under literally any monotonic-increasing transform, affine or otherwise — so no
post-hoc transform, however cleverly chosen, can improve it.** This is the reason affine
calibration cannot suffice, stated as precisely as the mathematics allows, not as an empirical
guess.

### 23.5 The single-transform trade-off — a concrete cost, not just an insufficiency

Beyond hitting the correlation ceiling, the global affine fit actively **worsens** the
cp-labeled majority while fixing the mate-labeled tail: cp-labeled bias moves from −7.40
(already small) to **+159.74** (worse in magnitude), and cp-labeled MAE moves from 259.00 to
**379.72** (worse). Mate-labeled bias improves substantially (−1,600 → −799) but remains huge.
This is not a bug in the fit — OLS minimizes *squared* error, so a single global transform is
pulled toward correcting the mate subset's enormous residuals (which dominate the sum of
squares) at the direct expense of the cp-labeled majority (~88% of held-out records, and the
subset that dominates ordinary, non-mate search positions). A single `(a, b)` cannot serve both
regimes well — reinforcing, from a second independent angle, that this is not a "recalibrate
and ship" fix.

### 23.6 Decision (Task 6): affine calibration is not sufficient; retraining is required

Not sufficient, for two independent, both load-bearing reasons:
1. **Correlation ceiling** (§23.4) — compression cannot be pushed past ~0.50 globally (~0.36
   within ordinary cp-labeled positions specifically) by any post-hoc transform, affine or
   otherwise. This alone rules out "calibration is enough."
2. **Single-transform trade-off** (§23.5) — even accepting the correlation ceiling, a single
   global affine actively degrades the bias/MAE of the ~88% majority (ordinary positions) to
   partially fix the mate-labeled tail. There is no affine choice that improves both
   simultaneously.

**This refines #219(b)'s own recommendation**, not just confirms it needed follow-up: #219
framed the problem as scale/bias and recommended "net recalibration (K-value/output-scale)."
This investigation shows the scale/bias component *is* analytically fixable (bias → 0,
compression 0.06 → 0.50) — **and the net is still not usable**, because the dominant residual
failure is correlation, which no rescaling of any kind — post-hoc affine, or a retrained
`output_scale`/K alone — can touch. The corrected framing: **retrain for evaluation quality
(correlation); scale/bias is downstream of that and comparatively minor.**

### 23.7 Task 7 — what in the training pipeline would address the remaining error

**Primary, load-bearing target: correlation (label_correlation=0.504, cp-only=0.362).** This is
a signal-quality problem — nothing observed in this investigation identifies *why* correlation
is this low (architecture capacity, training duration, data quality/label noise, and loss
weighting are all plausible, undistinguished contributors) — that diagnosis is future work this
investigation does not claim to have done. What is established here is *that* correlation, not
scale, is the binding constraint, and that any retraining effort's success should be measured
primarily by whether correlation improves, not by whether compression/bias improve (those are
easier to move and can improve without the net becoming more useful, as §23.3-23.5 just
demonstrated).

**Secondary, hypothesized contributor to the compression symptom specifically (not to
correlation) — stated with an explicit caveat, not ablated in this session:** `texel_sigmoid`'s
loss (`train.py:87`, `sigma(s) = 1/(1+10^(-K·s/400))`, `K=2.773456`) saturates fast — at this
K, 90% saturation is reached by ≈138cp and 99% by ≈288cp of predicted-vs-target error. Beyond
that magnitude, the training loss gradient is near zero, so the loss provides little pressure
to emit large-magnitude outputs — a plausible mechanism contributing to compression, given
target_cp's mate-equivalent constant (±3,000cp) and even ordinary cp-labeled targets (std≈880cp)
are frequently well beyond this saturation point. **Important caveat, to avoid overclaiming**:
WDL-sigmoid training inherently compresses the cp scale relative to a hand-tuned evaluator for
*any* reasonable `K` — this is not simply "the wrong constant was used." `K=2.773456` was
calibrated against Classical's score distribution specifically (`trainer/configs/
train-e3-real.md`'s own K-provenance note), which is a property of the *training labels'*
cp-to-WDL mapping, not the net's own natural output scale — so re-deriving K is not a clean,
side-effect-free fix: lowering K widens the unsaturated band (reducing compression) but also
changes what WDL calibration the loss is targeting, and **does nothing for correlation**, the
binding constraint identified above. Any future retraining investigation should treat this as
one hypothesis to test (e.g., an ablation training run with a different K, or a loss less prone
to early saturation), not as an already-diagnosed root cause.

**Explicitly out of scope, per this session's instruction**: no retraining was started, no `K`
or `output_scale` was changed, no pruning margins were touched. This section documents what a
future retraining effort should prioritize and why, not a completed fix.

## 24. Retraining roadmap (2026-07-20) — no code written, no retraining started

§23 established that retraining is necessary (the binding constraint is correlation, not scale).
This section reviews the full training pipeline end to end, classifies every component that
could plausibly move correlation/compression/bias, ranks candidate interventions, and produces
a phased experimental roadmap. **No code was written and no retraining was performed** — one
additional read-only diagnostic (§24.0, train-set calibration) was run against the existing
checkpoint to resolve an underfitting-vs-overfitting question the roadmap's ordering depends on;
this is the same category of measurement as every other diagnostic in this document, not a
training run.

### 24.0 Prerequisite diagnostic — optimization-limited is the best-supported hypothesis, not confirmed underfitting

The ranking below (steps/optimization before data volume before regularization) depends on
whether the net is under- or over-fit. The train/held-out **loss** gap alone (0.0667 vs 0.0823,
§22/train-e3-real.md) doesn't settle this — loss and correlation can diverge. Computed directly:

| | Train (n=36,000) | Held-out (n=4,000) |
|---|---|---|
| Label correlation | **0.564** | 0.504 |
| Sigmoid MSE (loss) | 0.0667 | 0.0823 |
| Overall bias (cp) | −207.65 | −219.63 |
| Overall compression | 0.069 | 0.063 |

**Measured**: train correlation (0.564) is only marginally higher than held-out (0.504) — an
11% relative gap, not the large train/held-out split a classically overfit model would show.
Train correlation *itself* is mediocre — the model has not extracted a strong signal even from
the 36,000 positions it was shown repeatedly (up to ~14 times each, see §24.1).

**What this does and does not establish**: a small train/held-out gap with both numbers mediocre
is **strong evidence that current performance is optimization-limited rather than
generalization-limited** — if the model were already overfitting (memorizing training positions
at the expense of generalizing), train correlation would be much higher than held-out, and it
isn't. That rules out "classic overfitting" as the *current* explanation. It does **not**, on its
own, uniquely identify *why* optimization hasn't converged further — this single comparison
cannot distinguish among several live possibilities that would each produce the same train ≈
held-out, both-mediocre pattern: insufficient optimization (too few steps, a poorly tuned LR),
insufficient model capacity, noisy labels capping what any amount of optimization could achieve
on this data, or conflicting targets (the same or near-identical positions carrying different
labels across Stage 1/Stage 2 sources). The roadmap below still treats optimization as the first
hypothesis to test — not because this measurement proves it's the cause, but because it is the
cheapest of these explanations to rule in or out, and a negative result from Phase 1 directly
narrows the remaining candidates (§24.5).

**Baseline sanity check**: a "predict-the-mean" model (no signal at all) scores sigmoid-MSE
0.145 on the held-out targets; the trained model reaches 0.0823 (a 43% reduction) — the net has
learned a real, substantial signal, not nothing. It is merely far short of what the architecture
and data should support, consistent with "undertrained," exactly as `nets/HISTORY.md` already
flagged this net at creation time ("undertrained (2000 steps...); expected outcome for this
stage of Track A/B, not a defect") — though, per the paragraph above, "undertrained" here should
be read as a working hypothesis this session's evidence supports, not a settled diagnosis.

This justifies ranking optimization/duration and data volume ahead of regularization and
architecture-capacity changes below (§24.2) — the current train/held-out pattern gives no
indication that the model is already overfitting or over-capacity, so regularizing or shrinking
it now has little evidenced upside and a real risk of making correlation worse. It does not by
itself rule out capacity or label noise as *eventual* limits further down the roadmap.

### 24.1 End-to-end pipeline review

Traced every stage from data acquisition to the exported `.nnue`, noting what's fixed, what's
configurable, and what's actually exercised by the one real training run to date (`dfffd3da`,
E-3/#203):

| Stage | File(s) | What it does | What the real E-3 run actually used |
|---|---|---|---|
| Stage 1 acquisition | `scripts/acquire_stage1_lichess.py` | Streams `database.lichess.org`'s eval dump (~394.7M positions available), picks the highest-depth `evals` entry per position, writes normalized CSV. `target_count` is a plain CLI arg — **acquiring more is a config change, not new engineering.** | `target_count=20,000` (0.005% of the available dump) |
| Stage 2 labeling | `scripts/stockfish_label.py` | Runs Stockfish UCI per position from `quiet-labeled.epd`-derived FENs, single-threaded, node-budgeted. Compute-bound: ~66 pos/sec at `nodes=25,000` on this session's hardware (Stockfish 18). | 20,000 positions, `nodes=25,000` (chosen for a ~5-minute session-scoped run, `trainer/configs/stockfish-label-e2-real.md` explicitly: "not claimed as the production-scale optimum") |
| `Transform` stage | `trainer/dataset/transform.py` | `deduplicate`/`filter_by_ply_range`/`balance_phases` all exist and are tested. | **None applied** — `combine_and_split` shuffles+splits the raw tagged union directly; `report_dataset_quality`'s duplicate/phase numbers are printed informationally only, never used to filter |
| `combine_and_split` | `scripts/train_candidate_net.py` | Shuffles the Stage1+Stage2 union with `random.Random(seed)`, splits 90/10 | seed=42, 40,000 → 36,000 train / 4,000 held-out |
| Feature encoding | `trainer/encoding/feature_encoder.py`, `docs/architecture/feature-spec/v1.json` | Plain 768-feature dual-perspective, **not king-relative** (ADR-001, deliberate) | Fixed, shared with Java `FeatureExtractor` |
| Batching | `trainer/model/batching.py` | FEN → `EmbeddingBag` (indices, offsets), two perspectives | No sampling logic here — batches are whatever `train()` hands it |
| Network | `trainer/model/network.py` | `(768→256)×2→1`, clipped-ReLU (`clamp(acc, 0, qa)`), FT weights hard-clamped post-step to `±1020` (overflow safety) | `hidden_width=256` fixed by config |
| Loss | `trainer/model/train.py` | `mean((sigmoid(pred, K) - sigmoid(target, K))^2)`, `K=2.773456` sourced from a **Classical-evaluator-specific** `KFinder` run, not re-derived for NNUE | Single fixed K for the whole run |
| Optimizer/schedule | `trainer/model/train.py` | Adam, **fixed `lr=0.01`, no schedule, no warmup/decay** | 2000 steps, batch 256 |
| Sampling | `trainer/model/train.py`'s `train()` loop | `records[cursor % len(records)]` — cycles through the **same fixed order every pass**, no per-epoch reshuffle (a `DataLoader`+`worker_init_fn` exists in `trainer/reproducibility/` but is unused — module's own docstring: "the natural next step then, not built preemptively here") | 2000 steps × 256 batch = 512,000 samples-seen over 36,000 unique records ≈ **14 passes**, same order every time |
| Regularization | — | None: no weight decay (`torch.optim.Adam` default), no dropout. The FT weight clamp is an overflow-safety projection, not a generalization regularizer. | — |
| Quantization | `trainer/quantization/quantizer.py` | Round-to-nearest + int16 clip, pure function, no additional scale multiply | **Ruled out as a correlation-affecting stage**: `clipping_report` on this net shows 0 clipped values across `ft_weights`/`ft_biases`/`output_weights` (train-e3-real.md) |
| Export | `trainer/export/canonical.py`/`exporter.py` | Clean pass-through of `qa`/`qb`/`output_scale`/quantized tensors into the manifest + `.nnue` | **Ruled out** — no transformation logic that could affect correlation |
| Validation | `trainer/validation/validator.py` | `evaluate_held_out`, `eval_scale_check`, `calibration_report`, `fit_affine_calibration` (§22/§23, this session) | Measurement only, never influences training |

**One additional diagnostic run against the existing checkpoint** (read-only, no retraining):
FT/output weight magnitudes are nowhere near their clip bounds — `ft.weight` max ≈ 8.4 vs. a
clip bound of 1020 (0.00% of weights within 5% of the boundary), `output_layer.weight` max ≈
11.5, `output_layer.bias` ≈ −2.3. **The weight clamp is not, and was never close to being, a
binding constraint on this net** — ruled out as a contributor to compression with direct
evidence, not just reasoning from the bound's arithmetic.

### 24.2 Ranked intervention matrix

Each component, whether changing it could theoretically move correlation / compression / bias,
and a rank by (expected improvement × isolatability) ÷ (effort × risk) — training itself is
cheap on this net's scale (2000 steps ≈ 20 seconds, `train-e3-real.md`), so **effort here is
almost entirely about engineering/data-acquisition time, not compute time**, except where noted.

| # | Component | Could improve | Effort | Experimental cost | Risk | Isolatable? | Rank |
|---|---|---|---|---|---|---|---|
| 1 | Optimization: steps × LR/schedule | Correlation (primary) | Low (config only) | Minutes (training is ~20s/2000 steps; a small grid is still cheap) | Low | High, if read via the logged loss curve, not the final number alone | **1** |
| 2 | Training data volume (Stage 1, cheap) | Correlation (primary), compression/bias (secondary, more signal) | Low (CLI `target_count`, streaming acquisition) | Near-free (network I/O, no compute) | Low | High | **2** |
| 3 | Training data volume (Stage 2, compute-bound) | Same as above | Low (existing driver, larger position count) | Moderate (~66 pos/sec single-threaded at 25K nodes; e.g. ~4.2h for 1M positions) | Low | High | **3** |
| 4 | Per-epoch reshuffling (currently absent) | Correlation (minor, standard best practice) | Very low (~3 lines) | Negligible | Very low | High | 4 (bundle into #1's re-run, not its own phase) |
| 5 | Label quality (Stockfish node budget) | Correlation (bounds the ceiling — see §24.3), compression | Low (existing driver, higher `nodes`) | Moderate, trades throughput for quality | Low | High, if position count is held fixed | 5 |
| 6 | Mate-aware loss weighting/shape | Bias (primary, targets the −1,726cp mate bias directly), correlation (secondary, mate subset only) | Low-medium (a loss-shape change, additive) | Cheap (reuses existing pipeline) | Low-medium (must not regress the cp-labeled majority — the exact failure mode §23.5 found for affine) | Yes, if implemented as an isolated additive term | 6 |
| 7 | K / sigmoid re-derivation | Compression (primary, §23.7's saturation hypothesis), bias (secondary), correlation (secondary/indirect at best) | Low (empirical sweep, or a KFinder-equivalent run against NNUE's own output) | Cheap | Low-medium (changes what cp scale the loss targets — re-verify calibration after, don't assume "correlation went up" means "scale is still fine") | High | 7 |
| 8 | Network capacity (`hidden_width`) | Correlation, but **no evidence yet that capacity is the current bottleneck** (§24.0: the train/held-out pattern is consistent with optimization-limited performance; capacity cannot be ruled in or out from this measurement alone) | Low (config number) | Cheap | Low | High | 8 — hold until 1-3 plateau |
| 9 | Regularization (weight decay, etc.) | **Little evidenced upside right now** (§24.0: no sign of overfitting yet — the train/held-out gap is small, not the widening split regularization targets) | Low | Cheap | **Medium — actively not recommended yet** | High | 9 — do not pursue until overfitting is observed (train/held-out gap widening after data scale-up) |
| 10 | Feature representation (HalfKP/HalfKA) | Correlation (largest theoretical ceiling — ADR-001's own "strongest known ceiling") | **Very high** (new `FeatureExtractor.java` + `FeatureEncoder.py`, format version bump, king-refresh path, full regression re-verification) | High (~40× input space, ADR-001's own estimate of required data) | High (explicitly named risks in ADR-001: king-move accumulator invalidation, bug-prone) | No — a wholesale pipeline change | **Gated, not ranked** — ADR-001's revisit conditions are not met (v1 hasn't passed all three release gates; no self-play data at 5-10× volume; no demonstrated plateau across ≥2 retrained nets at plain-768) |

### 24.3 The label-noise ceiling — a bound on every phase below, stated up front

Stage 2 labels come from Stockfish at `nodes=25,000` (a shallow budget, chosen for session-scoped
throughput, `stockfish-label-e2-real.md`'s own words: "not claimed as the production-scale
optimum") and Stage 1 labels are heterogeneous-depth Lichess community evals. **Correlation
against a noisy target is mathematically capped below correlation against the true value** — no
amount of additional volume or training crosses this ceiling; only better-quality labels do.

**Why this doesn't reorder Phases 1-2 below it, based on current evidence**: if label noise were
already the binding constraint, the model's *train-set* correlation should already sit close to
that ceiling (it has direct, repeated access to those exact labels) while held-out lagged behind
due to generalization. §24.0 measured the opposite: train correlation (0.564) is only barely
above held-out (0.504), both mediocre — meaning the model has not yet extracted the signal
available even from data it already has, which argues the noise ceiling is not yet what's
binding. **This should be re-checked, not assumed, after Phases 1-2** (§24.4): if train
correlation approaches a plateau well below "good" even with more steps and more data, that
plateau is direct evidence of the label-noise ceiling asserting itself, and Phase 3 (label
quality) should be promoted ahead of further volume scaling at that point.

### 24.4 Phased experimental roadmap

Each phase modifies one thing wherever practical (steps×LR is a deliberate small-grid exception,
justified below). Elo (gauntlet, then SPRT) is **not** run per-phase — it is the roadmap's exit
gate, applied once a candidate net clears a pre-registered held-out bar, not a per-experiment
cost (a full SPRT is a multi-day commitment, per `dfffd3da`'s own inconclusive 2,617-game run).

**Phase 0 — Pre-training baseline (one-time, already measured — not a retraining run)**

Before judging any trained checkpoint's improvement, an absolute reference point: a freshly
initialized, **untrained** network (same architecture/config as `dfffd3da` — `hidden_width=256`,
`qa=127`, `qb=64`, `output_scale=400` — random init only, zero optimizer steps, seed 0) run
through the same `evaluate_held_out`/`calibration_report` diagnostics against the same held-out
set. This requires no training (a random-init forward pass involves no gradient descent) and no
new code — the same validator.py functions already used throughout §22-§24.

**Measured** (held-out, n=4,000):

| | Untrained (random init) | Trained (`dfffd3da`) |
|---|---|---|
| Label correlation | **0.066** | 0.504 |
| Sigmoid MSE (loss) | 0.146 | 0.082 |
| Overall bias (cp) | −233.21 | −219.63 |
| Overall compression | 0.0000 | 0.063 |
| Overall MAE / RMSE | 605.01 / 1320.98 | 569.59 / 1279.88 |
| Mate-labeled MAE / RMSE | 3000.01 / 3000.01 | 2892.07 / 2894.48 |

The untrained baseline's correlation (0.066) and near-zero compression are consistent with a
network that has learned nothing — its outputs carry almost no position-dependent signal, and
its near-constant output produces almost no output variance (compression ≈ 0). Its loss (0.146)
lands almost exactly on the "predict-the-mean" floor computed in §24.0 (0.145), as expected for
an untrained network that has not yet learned to differ from a constant prediction.

**Why this matters**: the existing checkpoint's 0.504 correlation should be read against this
0.066 floor, not just against an abstract "1.0 is perfect" target — the trained net has already
captured most of the *qualitative* jump from "no signal" to "some signal," and the remaining gap
(0.504 → whatever the roadmap eventually reaches) is a different, harder kind of progress than
the jump already made. This baseline is a fixed reference point for every later phase's
reporting, not itself a phase to iterate on — no further experiments are introduced here beyond
this one-time measurement.

**Phase 1 — Optimization (steps × LR/schedule), same 40,000-position dataset**
- **Hypothesis**: 2000 steps (512,000 samples-seen, ≈14 passes over 36,000 unique positions,
  fixed LR=0.01, no schedule) under-trains the model; more steps and/or a better-tuned
  LR/schedule materially improve held-out correlation, consistent with §24.0's finding that
  performance is currently better explained as optimization-limited than generalization-limited.
- **Why steps and LR are grouped, not separated**: a null result from steps-alone at a poorly
  tuned fixed LR is uninterpretable — it could mean "duration wasn't the constraint" or "the
  fixed LR prevented convergence regardless of duration." Training is cheap enough (~20s/2000
  steps) that a small grid (e.g. steps ∈ {2,000 (baseline), 10,000, 30,000} × LR ∈ {0.01
  (baseline), 0.003, 0.001}, optionally with a simple decay schedule at the largest step count)
  costs minutes, not hours.
- **Implementation**: re-run `train_quantize_export` with each grid cell, identical dataset/
  seed/K/architecture/qa/qb/output_scale otherwise. Add per-epoch reshuffling (item #4 above) to
  this same re-run rather than as a separate phase — bundled because it's a ~3-line change with
  no plausible interaction risk worth isolating separately.
- **Acceptance criteria**: at least one grid cell improves held-out label correlation
  materially over baseline (0.504) without the train/held-out gap widening alarmingly (which
  would indicate overfitting has begun, not been fixed) — judged from the full trajectory below,
  not the final number alone.
- **Measurements — full optimization trajectory, not just the final checkpoint**: the existing
  `TrainingDiagnostic` series (logged every `log_interval` steps) currently records step, train
  loss, learning rate, and gradient norm, with held-out loss optional. Phase 1's implementation
  needs to extend this logging (not built in this planning task) so every logged checkpoint
  records at least:
  - training step,
  - training-set label correlation (not just training loss — loss and correlation can diverge,
    as this section's own `evaluate_held_out`/`calibration_report` split already demonstrated),
  - held-out (validation) label correlation,
  - RMSE (train and held-out, via `calibration_report`),
  - loss (train and held-out).

  **How to interpret the resulting curves**:
  - **Train and held-out correlation both still rising at the final logged step** → training has
    not converged; continue training (more steps) before concluding anything about this grid
    cell.
  - **Train correlation still rising while held-out correlation has flattened or started to
    fall** → emerging overfitting; the checkpoint at or just before the held-out peak is the one
    to keep, not the final step — and this cell's *later* steps should not be used to argue
    "optimization didn't help."
  - **Both train and held-out correlation plateau together, at a similar (mediocre) level** →
    optimization is exhausted for this configuration; further steps at this LR are unlikely to
    help, and the plateau level itself becomes evidence for whether capacity, data volume, or
    label noise (§24.3) is the next binding constraint.
  - **Loss or correlation oscillates without a clear trend (up-down-up across consecutive log
    points, not a smooth curve)** → the learning rate is likely too high for stable convergence;
    prefer a lower-LR grid cell's result over trying to read a trend out of a noisy one, and
    don't count an oscillating run's endpoint as this experiment's answer either way.

  Alongside the trajectory: `evaluate_held_out` + `calibration_report` (bias, compression, MAE,
  RMSE, overall/mate/cp split) at the final selected checkpoint for every grid cell. No
  gauntlet/SPRT yet — the objective here is a diagnostic (what does the trajectory look like),
  not a final score.

**Phase 2 — Data volume, Stage 1 first (cheap), then Stage 2 (compute-bound)**

Stage 1 scaling is deliberately split into two **sequential**, not simultaneous, experiments
rather than one jump straight to a large target count. Learning curves against data volume are
rarely linear — if correlation saturates after a first, smaller increase, generating a much
larger next increase is unjustified spend; if it's still climbing, that itself is the signal to
keep scaling. Jumping straight to the largest planned size would forfeit that mid-course
information and risk paying for data volume that turns out not to be the binding lever. Stage 2
(Stockfish labeling) is unchanged from the original plan — scaled once, after Stage 1's two-step
result is in, not split the same way (its own separate lever, label *quality* via node budget,
is Phase 3, not this phase).

- **Experiment 2A — Stage 1, 20,000 → 100,000 positions.**
  - **Hypothesis**: at Phase 1's best optimization settings, 36,000 unique training positions
    (20,000 Stage 1 + 20,000 Stage 2) caps correlation; a 5× increase in Stage 1 volume alone
    (Stage 2 held at 20,000) improves it measurably.
  - **Implementation**: re-acquire Stage 1 at `target_count=100,000` (near-free streaming
    acquisition against the existing Lichess source); retrain at Phase 1's best steps/LR,
    scaled to keep a comparable number of *passes* over the now-larger training set (not the
    same absolute step count, or the comparison conflates "more data" with "fewer effective
    epochs").
  - **Acceptance criteria**: correlation improves over Phase 1's best result. Explicitly compare
    the *shape* of this gain (how much per additional position) against Phase 1's own
    trajectory, to start characterizing whether returns are already diminishing at this scale.
  - **Measurements**: same battery as Phase 1 (trajectory + final `evaluate_held_out`/
    `calibration_report`), plus an explicit before/after against Phase 1's best result.
- **Experiment 2B — Stage 1, 100,000 → 1,000,000 positions — run only if 2A shows continued
  improvement, not a plateau.**
  - **Hypothesis**: if 2A's correlation gain has not yet saturated, a further 10× increase
    continues to help; if 2A already plateaued, this experiment is not justified and should be
    skipped in favor of promoting Phase 3 (label quality) or Phase 4 (loss/K), per §24.3's own
    "re-check, don't assume" guidance.
  - **Implementation**: identical in kind to 2A, just at `target_count=1,000,000`.
  - **Acceptance criteria**: correlation improves further beyond 2A's result, and by an amount
    that justifies the extra acquisition/training time relative to 2A's own per-position return.
  - **Measurements**: same battery, directly compared against both Phase 1 and Experiment 2A to
    build the full data-volume learning curve (three points: 36k/136k/1,020k-ish total training
    records), not just a single before/after pair.
- **Then, Stage 2 (compute-bound), unchanged from the original plan**: scale Stage 2
  (Stockfish labeling) similarly, budgeting for its ~66 pos/sec throughput, using whichever of
  Phase 1/2A/2B produced the best result as the starting configuration.
- **Overall Phase 2 acceptance criteria**: correlation improves further beyond Phase 1's best
  result, with the 2A/2B split producing an explicit read on where (if anywhere) Stage 1 volume
  gains diminish — informing how much of Phase E's later self-play/data effort is actually
  needed before further gains, rather than assuming "more is always better" without a data
  point to support it.

**Phase 3 — Label quality (Stockfish node budget), a matched-subset ablation**
- **Hypothesis**: label noise (Stage 2's 25,000-node budget; Stage 1's heterogeneous eval
  depth) imposes a correlation ceiling independent of volume/training duration (§24.3).
- **Implementation**: re-label a fixed-size Stage 2 subset (e.g. the same 20,000 FENs already
  used, or a fresh matched-size sample) at a substantially higher node budget (e.g. 100,000-
  250,000 nodes) and compare correlation against an equal-size 25,000-node-labeled subset,
  holding position count, architecture, and optimization settings fixed — an apples-to-apples
  label-quality ablation, not conflated with volume.
- **Acceptance criteria**: does higher-node-budget labeling raise correlation for otherwise-
  identical positions? Per §24.3, promote this phase ahead of further Phase 2 volume scaling if
  Phase 1-2's train correlation plateaus below a reasonable bar despite more steps/data.
- **Measurements**: same battery, on the matched subsets.

**Phase 4 — Loss/K reformulation, targeting the mate-specific bias**
- **Hypothesis**: (a) `K=2.773456`'s sigmoid saturates by ≈288cp (§23.7), suppressing gradient
  for large-magnitude targets and contributing to compression; (b) the flat
  `MATE_EQUIVALENT_CP=3,000` target combined with (a) specifically explains the disproportionate
  −1,726.6cp mate-labeled bias (§22.2).
- **Implementation**: (i) an empirical K sweep (holding Phase 1-3's best data/steps config
  fixed) against held-out correlation/compression/bias; (ii) a more surgical follow-up — a
  mate-aware loss weighting or a separate slope for mate-labeled records — checked specifically
  against whether it improves mate-subset bias *without* regressing the cp-labeled majority
  (the exact failure mode §23.5 found for a single global affine transform).
- **Acceptance criteria**: mate-labeled bias magnitude decreases substantially with no
  corresponding regression in cp-labeled bias/MAE.
- **Measurements**: same battery, split by mate/cp subset explicitly (`calibration_report`
  already returns this split) — re-run the full `calibration_report`/`fit_affine_calibration`
  pair after any loss change, not just correlation, since a loss change could shift compression/
  bias independent of correlation and must be re-verified, not assumed fixed.

**Phase 5 — Architecture capacity / regularization, gated on 1-4's outcome**
- Not pursued now. §24.0 shows no evidence of overfitting at the current data/step scale (the
  train/held-out gap is small, not widening) — increasing `hidden_width` or adding
  regularization now has little evidenced upside and a real risk of making correlation worse by
  constraining an already-modest signal further. Revisit only if Phases 1-4 plateau *and* a
  widening train/held-out gap (the actual overfitting signature) appears at that point.

**Exit gate (all phases)**: once a candidate net clears a pre-registered held-out bar
substantially better than `dfffd3da`'s 0.504 correlation / 0.063 compression / −220cp bias, run
a cheap gauntlet first (E-4-style, ~100 games) before committing to a full SPRT — mirroring the
project's own existing two-step strength-measurement pattern, not skipping straight to a
multi-day SPRT on a net that hasn't cleared even the offline diagnostics.

**Feature representation (HalfKP/HalfKA)**: explicitly not part of this roadmap. ADR-001's
revisit conditions are unmet; this roadmap's own Phase 1-4 results are exactly the evidence that
would eventually satisfy condition (c) ("a plateau in measured relative strength... demonstrated
across ≥2 retrained nets at plain-768") — HalfKP should be reconsidered only after that evidence
exists, not before.

### 24.5 Recommended first experiment

**Phase 1 (optimization: steps × LR/schedule, same dataset)** — for four reasons, each grounded
in evidence gathered across this session and the broader project history, not preference:

1. **§24.0's direct measurement** shows no evidence of overfitting, and rules nothing else in —
   among the remaining explanations that pattern is consistent with (insufficient optimization,
   insufficient capacity, noisy labels, conflicting targets), optimization is the cheapest to
   test, and doing anything else first (more data, more regularization, a different
   architecture) risks spending more expensive effort before ruling out the cheapest candidate.
2. **It is the cheapest possible test**: training itself costs ~20 seconds per 2,000 steps on
   this net's scale; a full steps×LR grid costs minutes, not hours, with zero new data
   engineering.
3. **It directly tests the project's own already-recorded expectation**: `nets/HISTORY.md`
   flagged this exact net as "undertrained (2000 steps...)" at creation time — this experiment
   is the first time that suspicion is actually measured rather than merely noted.
4. **Either outcome is informative and cheap to obtain**: if optimization alone substantially
   closes the correlation gap, every later phase starts from a better baseline. If it does not
   — if the logged loss/correlation curve shows a genuine plateau rather than an artifact of a
   bad LR — that is itself strong, cheap evidence that data volume (Phase 2) is where the
   binding constraint actually lives, sharpening where the next, more expensive phase should
   focus.

### 24.6 Risks and open questions

- **Overfitting risk during Phase 1**: watch the logged train/held-out loss gap at every grid
  cell, not just the final correlation number — more steps on a still-fixed 36,000-position
  dataset could eventually tip into overfitting even though current evidence points toward
  optimization-limited performance, not overfitting, at today's step count.
- **Stage 2 relabeling cost**: compute-bound at ~66 pos/sec single-threaded; measure a small
  calibration sample's throughput at any new node budget before committing to a full run,
  mirroring `stockfish-label-e2-real.md`'s own existing practice, to avoid an open-ended
  multi-hour commitment.
- **Loss/K changes need re-verification, not assumption**: any Phase 4 change could shift the
  net's cp scale independent of correlation — re-run `calibration_report`/
  `fit_affine_calibration` after every such change, don't infer calibration is fine just
  because correlation improved.
- **Open, not measured in this review**: whether Stage 1's Lichess records' `search_depth`
  metadata (an optional field the contract supports, `PositionMetadata.search_depth`) is
  actually populated and could be used to filter or confidence-weight shallow-depth labels — a
  cheap follow-up check before Phase 3, not investigated here.
- **Open**: whether mate-labeled positions are inherently too rare (≈11.8% of held-out, similar
  proportion expected in training) for ordinary volume scaling to meaningfully improve their
  calibration — may need deliberate oversampling or a dedicated mate-focused data source,
  unexamined here.
- **Open, deferred by design**: whether plain-768 has a strength ceiling below what's needed for
  competitive play regardless of data/training improvements — unanswerable until Phases 1-4 are
  actually run and a plateau is, or isn't, observed (ADR-001's own revisit condition (c)).
- **Downstream, not addressed by this roadmap**: once a materially better-correlated net exists,
  §14.5/§22/§23's specific pruning-margin/compression numbers are only valid for `dfffd3da` —
  the calibration/pruning-rate cross-reference should be re-measured against any new candidate
  net, not assumed to carry over.

**Explicitly out of scope, per this task's instruction**: no code was written, no retraining was
started, no `K`/`output_scale`/pruning margins were changed. This section is a plan, not an
implementation.

## 25. Hypotheses tested (running summary, 2026-07-20)

A compact index of the major hypotheses investigated across the NNUE performance and
calibration work (§14-§24), for a reader who wants the outcomes without re-reading the full
derivations. Status is marked **Confirmed** / **Rejected** / **Supported** (evidence-backed but
not conclusively proven) / **Pending** (experiment designed, not yet run) — never stronger than
what the cited evidence actually shows.

| Hypothesis | Evidence | Outcome |
|---|---|---|
| Runtime/instrumentation overhead (`System.nanoTime()` calls in `onMake`/`onUnmake`) dominates NNUE's NPS shortfall | §14.1: measured at ~1% of NNUE's per-node cost; correcting for it moves the ratio 34.6%→~35%, still far below the 40% gate | **Rejected** — the dominant cost was accumulator/eval per-node work, not instrumentation |
| Copy-forward accumulator update (`addFeature`/`subtractFeature`) is the primary SIMD target | §14.3 identified it from code review (no cross-lane interaction, contiguous memory); §18-§19 measured a ~5.0x accumulator-cost reduction and a ≈9.0x implied speedup on the accelerable fraction after vectorizing exactly these two methods | **Confirmed** |
| Stage 1 SIMD (vectorizing only `addFeature`/`subtractFeature`) alone clears the ≥40% NPS gate | §19.1: native-Windows median-of-5, 157,268/336,525 = 46.7% | **Confirmed** |
| An isolated microbenchmark's SuperWord-driven scalar/vector swing (~1.3x-12x) reflects the same effect inside the real engine's scalar path | §19.5: disabling SuperWord in the integrated engine changed scalar NPS by only ~3%, vs. ~3.4x for the identical flag in the isolated microbenchmark (§18.4) | **Rejected** — the isolated-microbenchmark confound did not reproduce in the real `Searcher`/`NnueEvaluator` call pattern |
| The #217 TT-carryover node-count blowup poses a real time-forfeit risk in SPRT/timed games | §20: `BenchRunner`/`searchDepth` searches with an unconditional no-time-bound predicate (why the blowup is visible in bench); real games use `searchWithTimeManager`, whose hard-stop check fires at every node with no throttling | **Rejected** — bench-harness-only artifact, not a game-time risk |
| Affine calibration (`score' = a·score + b`) alone fixes evaluator quality (compression/bias) | §23.4: calibrated compression (0.504) lands almost exactly on pre-calibration label correlation (0.5044) — Pearson correlation is affine-invariant, so no affine transform can cross that ceiling | **Rejected** |
| A single global affine transform serves mate-labeled and cp-labeled positions equally well | §23.5: the same global fit moved cp-labeled bias from −7.40cp to +159.74cp (worse) while partially fixing the mate-labeled tail — OLS trades one regime against the other | **Rejected** |
| The FT-layer weight hard-clamp (`±1020`, overflow safety) constrains model capacity or contributes to compression | §24.1: measured `ft.weight` max ≈ 8.4, `output_layer.weight` max ≈ 11.5 — both ~2 orders of magnitude below their bounds; 0.00% of weights within 5% of the clip boundary | **Rejected** — clamp is not, and was never close to, a binding constraint |
| `K=2.773456`'s texel-sigmoid loss saturation (≈288cp) contributes to the compression symptom | §23.7: saturation math is exact (99% saturated by ≈288cp at this K); mechanism is plausible and consistent with the measured compression, but not ablated (no retrain with a different K has been run) | **Supported** — plausible, evidenced mechanism, not yet isolated by experiment |
| The current network (`dfffd3da`) is overfitting the training set | §24.0: train correlation (0.564) only marginally exceeds held-out (0.504) — the small, non-widening gap is inconsistent with classic overfitting, whose signature would be a much larger split | **Rejected** |
| Current NNUE evaluation quality is (partially) optimization-limited rather than purely data/generalization-limited | §27 (Phase 1): P1-G04 (steps=20,000, cosine LR schedule) reached 0.5315 held-out correlation vs. `dfffd3da`'s 0.5044 — a +0.0271 gain, ~14x the directly-measured seed-noise std (0.0019, §27.2), with architecture/data/loss unchanged | **Confirmed, partially** — optimization alone recovered a real gain, but the same configuration also shows a genuine, within-run plateau (§27.8), so optimization is not the *sole* remaining lever; data volume is next per §27.9 |
| Plain-768 (non-king-relative) features are the binding ceiling on correlation right now | Not evaluated — ADR-001's revisit conditions (release gates passed, 5-10x self-play data, a demonstrated plateau across ≥2 retrained plain-768 nets) are unmet; this roadmap's own Phase 1-4 results are the evidence that would eventually settle this | **Pending** — deliberately not tested ahead of its gate, per ADR-001 |
| §26.3's provisional 0.06 (train/held-out gap) promotion threshold approximates real seed-to-seed correlation variance | §27.2: directly measured via 3 training-seed replicates of the same configuration — std ≈ 0.0019, range 0.0036, roughly 30x smaller than the 0.06 placeholder | **Rejected** — the placeholder was far too conservative; superseded by the measured value for all §27.7 promotion decisions (n=3, one configuration — not yet a fully general constant, §27.2's own caveat) |
| A cosine LR decay mitigates the emerging-overfitting pattern a flat, high LR shows over a long run | §27.3: P1-G04 (cosine) vs. P1-G01 (flat, same steps/peak-LR) — held-out loss's late-run relative rise is 8% (G04) vs. 17% (G01), and held-out correlation plateaus rather than mildly declining | **Confirmed** — both predicted effects observed in a single head-to-head comparison; not yet seed-replicated (§27.6's caveat) |
| Optimization changes (steps/schedule) alone can close the mate-labeled bias gap that motivates Phase 4 | §27.5: mate-labeled bias stays −1,651 to −1,729cp across every Phase 1 cell, including the two promoted ones — no meaningful movement despite real correlation/compression gains elsewhere | **Rejected** — confirms, via optimization rather than calibration this time, that mate bias is a structural (loss/target-shape) problem Phase 1 cannot touch, exactly as §23.7 hypothesized |
| Increasing Stage 1 data 20k→100k improves held-out correlation beyond P1-G04, at a fixed 20,000-step compute budget | §28.2 (Experiment 2A, `P2A-001`): held-out correlation 0.5048, indistinguishable from `dfffd3da`'s original 0.5044 and a regression vs. P1-G04's 0.5315 (~14x the measured noise floor) | **Rejected, at fixed compute** — but confounded with training-step count: 20,000 steps is ~142 passes over 36k records but only ~44 over 116k, so this does not establish that more Stage 1 data cannot help under proportionally more compute (§28.3, open question) |
| Experiment 2A's regression was primarily an optimization-exposure artifact, resolvable by scaling steps to restore P1-G04's effective pass count | §29 (Experiment 2B, `P2B-001`): steps derived to hold passes constant (20000→64444) yielded held-out correlation 0.5000 — *lower* than P2A-001's 0.5048, still far below P1-G04's 0.5315, with the run's own trajectory showing classic overfitting (early peak at ~20% through, then sustained decline) rather than recovery | **Rejected** — the steps/passes confound flagged in §28.3 is ruled out as the explanation; Stage 1 volume increases (20k→100k) show no benefit under either fixed or proportional compute (§29.4) |
| Removing Stage 1's 75 confirmed sentinel-value label outliers (§32.4) from training improves the learned model, not just the reported metric | §35 (Experiment 3A, `P3A-001`): retrained P1-G04's exact schedule on a training set with 67 sentinel records removed, evaluated both models on both the original (v1) and sentinel-filtered (v1-clean) held-out sets — Model B vs. Model A differs by +0.0002 correlation on v1-clean (noise-floor-sized), while both models jump ~+0.06 correlation identically when the *benchmark* changes | **Rejected as a model-quality fix, confirmed as a metric artifact** — the +0.06 correlation gain (§32.4) is entirely a property of which held-out records the metric is computed over, not of which model produced the predictions (§35.7); filtering is still recommended as data-generation hygiene (§35.9), just not as something that changes model quality |

## 26. Experimental Protocol (2026-07-20)

The roadmap in §24 identifies *what* to try. This section defines *how every future retraining
experiment is conducted and evaluated* — a fixed contract, written before Phase 1 starts, so
results are judged against criteria set in advance rather than interpreted after the fact. This
section is documentation only: no trainer code was modified, no retraining was performed, no
hyperparameters were changed to produce it.

### 26.0 Metric hierarchy

Every metric this protocol collects (§26.2) has a different job. Conflating them — treating a
calibration number as if it were itself something to maximize — is exactly the failure mode
§23's affine-calibration investigation warned about: a metric can improve while the thing that
actually matters does not. This hierarchy is stated once, explicitly, so no later section reads
as if any offline number is the goal in itself.

- **Primary optimization objective — held-out Pearson correlation.** The one metric Phase 1 (and
  every later phase) is actually trying to move. Chosen as primary specifically because it is
  affine-invariant (§23.4) — the one quality dimension calibration provably *could not* fix
  post-hoc, so it is the one dimension a training-side change can meaningfully claim credit for
  moving, as opposed to a scale/bias artifact a cheaper transform could have fixed anyway.
- **Secondary diagnostic metrics — RMSE, evaluation bias, compression ratio, calibration
  diagnostics (mate/cp split), loss.** These describe *how* a configuration is performing and
  *why* — they rank and characterize candidates, catch failure modes correlation alone would
  miss (§23.5's cp-labeled-majority regression, invisible to a single correlation number), and
  feed the promotion criteria (§26.3) and early-termination checks (§26.4). None of them is
  itself an optimization target: a configuration that improves RMSE or compression while
  correlation stays flat has not made progress by this protocol's own definition, and a
  configuration should never be chosen *because* one of these numbers moved if correlation
  did not.
- **Promotion validation — engine gauntlet, then SPRT.** The only measurements that actually
  validate playing-strength improvement (§26.3's gauntlet-then-SPRT gate, reusing the PRD's
  existing gate 3). Offline metrics exist to make this expensive step worth taking selectively,
  not to substitute for it.

**Stated plainly**: offline metrics (correlation included) exist to **rank and prioritize**
candidates efficiently, so this project does not have to run a multi-day SPRT against every
configuration to find out which ones are worth it. **Playing strength (Elo), validated through
gauntlets and ultimately SPRT, remains the project's actual final objective** — this hierarchy
does not change that (§26.9's success definition states it in full); it exists to make clear that
even *within* the offline-metric tier, not all metrics play the same role, and none of them,
correlation included, is a substitute for the SPRT result that actually decides anything.

### 26.1 Controlled-variable policy

**Rule**: every experiment changes exactly one independent variable and holds everything else
listed below fixed. Where the roadmap already groups two knobs into one experiment (Phase 1's
steps×LR grid, §24.4), that grouping is itself declared in advance, with the reason stated — it
is never an accident of "we happened to change two things."

**Why this project treats this as a hard rule, not a preference** — two concrete precedents from
this document, not a hypothetical:

- **§19.2**: Classical NPS drifted from 311,669 (pre-PR2, #206) to 336,525 (post-PR2, PR 3) —
  an 8% swing — with **zero code change** to the Classical path between those two measurements.
  Comparing NNUE's post-PR2 result against the *stale* pre-PR2 Classical figure would have
  overstated Stage 1's real gain by attributing session-to-session drift to the code change.
  §15.5's same-session-baseline requirement exists precisely to prevent this.
- **§18.4/§19.5**: the identical scalar source code measured 22.4ns/op with SuperWord
  auto-vectorization enabled and 76.7ns/op with it disabled — a ~3.4x swing from one JIT flag,
  in an isolated microbenchmark. A reader who didn't control for (or even know about) that
  variable could have drawn opposite conclusions about Stage 1's benefit from the same code.

Both are cases where an uncontrolled variable (session/environment state; JIT/harness context)
produced a large enough effect to flip or inflate a causal conclusion about something else
entirely. Retraining experiments have the same exposure — e.g. two different random seeds alone
can move correlation by an amount that could be mistaken for a data-volume or optimization
effect if seed weren't held fixed (or explicitly varied and reported, see §26.7).

**Seed inventory** — every deterministic-randomness source in the pipeline as it exists today,
each its own controlled variable, held fixed unless a row below explicitly names it as that
row's independent variable (no phase in this roadmap ever does):

- **Split seed** — the `seed` argument to `combine_and_split()` (`scripts/train_candidate_net.py`),
  driving `random.Random(seed).shuffle(tagged)` before the 90/10 train/held-out split. This
  determines *which specific positions* land in the held-out set. `42` for the real (`dfffd3da`)
  run.
- **Model-init/training seed** — `TrainingConfig.seed`, consumed by `train()`'s
  `seed_everything(config.seed)` (`random`, `numpy.random`, `torch.manual_seed` CPU+CUDA). This
  determines the network's initial weights (PyTorch's default parameter init draws from the
  seeded global RNG) and any other RNG consumption inside `train()`. Also `42` for the real run —
  **numerically identical to the split seed today only because one CLI `--seed` value was passed
  to both call sites; they are two logically distinct seeds**, tracked as two separate entries in
  this inventory, not one. A future experiment must not assume "seed=42" fixes both just because
  today's single CLI flag happens to.
- **Batch-ordering/shuffle seed** — does not exist yet. Today's `train()` loop cycles through
  `records` in a fixed, unshuffled order every pass (`cursor % len(records)`, §24.1). Phase 1
  explicitly adds per-epoch reshuffling (§24.4); whichever seed governs that reshuffle (a
  dedicated seed, or reuse of the model-init/training seed above) must be decided and explicitly
  documented as part of Phase 1's own implementation — not decided in this planning task — and
  then held fixed the same way across every subsequent phase, exactly like the other two entries
  above.
- **`DataLoader` worker seed** (`trainer/reproducibility/seeding.py`'s `worker_init_fn`) —
  infrastructure exists but is unused (`train()` still loads records into a plain Python list,
  §24.1); not applicable until a future phase adopts a `DataLoader`, at which point it joins this
  inventory as a fourth controlled seed.

Unless a row below says otherwise, every phase holds the split seed (42) and the model-init/
training seed (42, or whichever value Phase 1 promotes) fixed, **in addition to** whatever else
its own "Held constant" column lists — the per-row seed notes below flag only the cases where
this needs explicit emphasis, not a substitute for the rule stated once, here.

**Per-phase declaration** (extending §24.4's own phase definitions with an explicit
independent/held-constant split for each):

| Phase | Independent variable(s) | Held constant |
|---|---|---|
| 0 — Baseline | *(none — a fixed reference point, not an experiment)* | Architecture/config identical to `dfffd3da` (`hidden_width=256`, `qa=127`, `qb=64`, `output_scale=400`); zero training steps by construction. **Seed note**: uses a dedicated seed (`0`) for reproducibility of the random-init forward pass, deliberately distinct from the training seed (42) — not itself compared to trained checkpoints on a seed-matched basis, since Phase 0 is a structural (zero-steps) reference point, not a trained configuration |
| 1 — Optimization | Steps × LR/schedule, as **one declared 2-D grid**, not two separate single-variable experiments — grouped because a steps-only null result at a poorly tuned fixed LR is uninterpretable (§24.4's own justification) | Dataset (Stage 1: 20,000 / Stage 2: 20,000), labels, architecture (`hidden_width=256`), feature representation (plain-768), loss function shape and `K=2.773456`, `qa`/`qb`/`output_scale`, quantization/export pipeline, evaluation pipeline (`validator.py`), split seed (42), model-init/training seed (42). **Seed note**: this phase is where per-epoch reshuffling is introduced (seed inventory above) — its seed must be fixed across every grid cell in this same phase, the same as steps/LR are the *only* declared independent variables |
| 2A — Data volume (Stage 1, 20k→100k) | Stage 1 position count only | Stage 2 position count (20,000) and node budget (25,000, unchanged), Phase 1's promoted optimization settings, architecture, loss/K, feature representation, export/eval pipeline, split seed (42, unchanged — the held-out evaluation set must stay identical across every data-volume experiment), model-init/training seed (42, or Phase 1's promoted value), shuffle seed (whatever Phase 1 fixed it to). **Note**: total training steps is *derived*, not independently varied — it is scaled to hold the number of *passes* over the (larger) dataset constant, per §24.4; this is bookkeeping to keep "amount of optimization per position" constant, not a second free variable |
| 2B — Data volume (Stage 1, 100k→1M) | Stage 1 position count only, continuing from 2A | Same as 2A (including all seeds), relative to 2A's result rather than Phase 1's |
| 2, Stage 2 scaling | Stage 2 position count only | Stage 1's best count from 2A/2B, node budget (25,000, unchanged — quality is Phase 3's variable, not this one), Phase 1's optimization settings, split/training/shuffle seeds unchanged from 2A/2B |
| 3 — Label quality | Stockfish node budget only, on a matched-size position subset | Position count (held equal to the 25,000-node comparison arm), Stage 1 data, optimization settings, architecture, loss/K, split/training/shuffle seeds unchanged |
| 4(i) — K sweep | `K` only | Best data/optimization config from Phases 1-3, architecture, feature representation, loss *shape* (still texel-sigmoid), split/training/shuffle seeds unchanged |
| 4(ii) — Mate-aware loss | Loss shape/weighting for mate-labeled records only, run **after and separately from** 4(i), never simultaneously with it | Best `K` from 4(i) (or the original K, if 4(i) shows no improvement), everything else as in 4(i), including all seeds |
| 5 — Capacity/regularization (gated) | `hidden_width` **or** a regularization term — one at a time, never both — only if scheduled at all (§24.4's Phase 5 gate) | Everything from the best configuration found in Phases 1-4, including all seeds |

**Repeated-seed variance experiments are the one deliberate exception to "seeds are always held
constant"**: §26.3 below relies on eventually varying the model-init/training seed *only*,
holding every other variable in whichever phase is being re-run fixed, specifically to measure
how much correlation moves from seed alone — this is itself a declared, single-variable
experiment (independent variable: training seed), not a violation of this section's rule.

### 26.2 Mandatory measurements

Every experiment reports the same battery, organized into four categories. No experiment report
is considered complete without all applicable fields — "applicable" is noted where a category
doesn't apply to a given phase (e.g. Phase 1 has no NPS effect).

**Training** (per grid cell / configuration, at the selected checkpoint — see §26.1's curve
guide in §24.4 for how "selected" is determined):
- training loss, validation (held-out) loss
- training correlation, validation (held-out) correlation
- RMSE (train and held-out)
- bias (train and held-out, overall)

**Calibration** (held-out set, via `calibration_report` — already built, §22-§23):
- compression ratio (overall)
- cp-only metrics: bias, compression, MAE, RMSE (the `cp_labeled` bucket)
- mate metrics: bias, compression, MAE, RMSE (the `mate_labeled` bucket)

**Runtime**:
- training wall-clock time (cheap to log; also an anomaly signal — Phase 1's baseline is
  ~20s/2,000 steps, §24.1; a configuration that takes far longer or shorter than its step count
  predicts is worth a second look before trusting its other numbers)
- evaluation throughput (positions/sec) where applicable — chiefly Phase 3's Stockfish relabeling
  (already has an established measure-before-committing practice, `stockfish-label-e2-real.md`)
  and any future large-`evaluate_held_out`/`calibration_report` run at much larger held-out sizes

**Engine** (only where the experiment plausibly affects it):
- NPS — not affected by any of Phases 1-5 as scoped (all are training/data/loss changes, none
  touch `evaluate()`, search, or the accumulator hot path); if a future phase ever does touch
  engine-side code, the existing native-Windows, same-session, median-of-5 methodology (§15.5)
  applies unchanged, not a new one
- gauntlet Elo — required once a phase produces a *candidate* worth engine-testing (§26.3's exit
  gate), not per training configuration
- SPRT — only for a candidate that has already passed gauntlet, per the PRD's own gate 3 (H0=0,
  H1=+10, α=β=0.05, `docs/NNUE_PRD.md` §1) — never run as a per-experiment screening step

### 26.3 Promotion criteria

Every threshold below is stated as a concrete, checkable condition — not "looks better" or
"appears promising." Where a threshold is illustrative rather than fixed by prior project policy,
it is stated as such, tied to an already-measured quantity rather than an arbitrary round number.

**On the ~0.06 figure used below — provisional, not a statistical standard.** Every "noise
floor" reference in this section uses the single observed train/held-out correlation gap
(0.564 vs. 0.504, §24.0) as a stand-in for "how much correlation could plausibly move without a
real effect." This is a **placeholder, not a permanent statistical threshold**: it comes from one
comparison on one trained checkpoint, not from repeated-seed variance, so it says nothing
rigorous about how much correlation moves from ordinary run-to-run noise (seed, data order,
optimizer stochasticity — §26.7) versus a genuine effect of the variable under test. Treating
0.06 as if it were a validated confidence bound would overstate what a single number can support.
Consequently:
- **During Phase 1 specifically, configurations should primarily be ranked relative to one
  another** (which grid cell has the highest selected-checkpoint held-out correlation, by how
  much, and with what trajectory shape per §24.4), not evaluated one-by-one against the 0.06 bar
  as if it were a pass/fail line. The 0.06 figure is a coarse, defensible-for-now filter for
  "is this plausibly more than noise," not a precision instrument.
- **The permanent promotion threshold should be established only after a repeated-seed variance
  experiment** (§26.1's declared exception: re-run one configuration at several different
  model-init/training seeds, everything else held fixed, and measure how much correlation moves
  from seed alone) **actually quantifies natural variance.** Until that experiment runs, every
  threshold below that cites "the noise floor" is explicitly provisional and should be revisited,
  not treated as settled policy. **Update (§27.2, Phase 1's own P1-G00/-S43/-S44 cells)**: this
  experiment has now run — measured std ≈ 0.0019 (n=3, one configuration), roughly 30x tighter
  than the 0.06 figure below. §27.7's promotion decisions use the measured value, not 0.06 — the
  text immediately below is left as originally written, for an honest record of what was known
  before that measurement existed, not silently updated to look more precise in hindsight.
- This does not change the roadmap's ordering or its promotion *philosophy* (rank candidates,
  require evidence of a real effect before promoting, don't promote on a single offline metric
  alone) — only the confidence with which any specific number in this section should be read.

- **Phase 1 (Optimization)** — rank every grid cell's selected checkpoint by held-out correlation
  first (relative ranking, per the paragraph above), then promote the best-ranked cell as the new
  baseline configuration only if **all** of:
  1. Its selected checkpoint's held-out correlation exceeds 0.504 by at least the currently
     observed train/held-out gap (~0.06 absolute, §24.0) — a provisional filter, not a validated
     statistical cutoff (see above). It is the only measured indication so far of how much
     correlation can move between two samples of the same underlying data without a real effect,
     used because *something* concrete is better than an undefined "looks better," not because
     0.06 itself is known to be the right number.
  2. The checkpoint is selected via §24.4's curve-interpretation rules (peak held-out
     correlation before decline, not the final step by default).
  3. Held-out loss at the selected checkpoint is not worse than baseline (0.0823) — a
     correlation gain paired with a worse calibrated loss is not a clean win and must be
     explained before promoting, not promoted on correlation alone.
  If no grid cell clears this bar, Phase 1 is **not promoted** — this is a valid, informative
  result ("optimization exhausted at this data scale"), not a failure to fix before moving on;
  proceed to Phase 2 per §26.8's decision flow.
- **Phase 2A/2B (Stage 1 volume)** — promote (i.e., adopt the larger dataset and, for 2A,
  proceed to 2B) only if held-out correlation improves beyond the prior stage's promoted
  baseline by at least the same noise floor as Phase 1, measured under identical optimization
  settings to the prior stage (isolating volume, not re-conflating with optimization). If 2A
  does not clear the bar, do **not** run 2B — proceed to Phase 3 instead.
- **Phase 2, Stage 2 scaling** — promote only if it produces a measurable improvement *beyond*
  what Stage 1 scaling alone already achieved (2A's or 2B's promoted result), using the same
  noise floor — not "did correlation improve since the very first baseline," which would credit
  Stage 2 for gains Stage 1 already produced.
- **Phase 3 (Label quality)** — promote (adopt the higher node budget going forward) only if the
  matched-subset comparison shows a correlation improvement attributable to label quality
  specifically (same position count, different node budget only) that exceeds the noise floor.
  Flagged explicitly: this comparison runs on a smaller, matched subset than the full training
  set, so its noise floor may need to be wider, not narrower, than Phase 1/2's — see §26.7.
- **Phase 4 (Loss/K)** — promote only if mate-labeled bias magnitude decreases by at least half
  from its current value (−1,726.6cp → at least as good as ≈−863cp) **and** cp-labeled bias/MAE
  does not regress beyond the noise floor — directly operationalizing §23.5's finding that a
  naive single-transform fix can improve the mate tail while quietly damaging the cp-labeled
  majority; this criterion exists specifically so that failure mode cannot recur unnoticed here.
- **Phase 5 (Capacity/regularization)** — not scheduled under current evidence (§24.4); no
  promotion criteria are defined until it is actually scheduled, to avoid pre-committing to
  thresholds for an experiment whose design may change based on Phases 1-4's outcomes.
- **Gauntlet gate (before any SPRT)** — a candidate that clears the offline promotion criteria
  above proceeds to an E-4-style gauntlet (~100 games) as a cheap regression filter, not a
  strength proof (a ~100-game gauntlet has much lower resolving power than SPRT — `dfffd3da`'s
  own gauntlet result, −636.4 ± 224.0 Elo, was only usable *because* the gap was enormous and
  unambiguous). **Pass condition**: the gauntlet result's confidence interval must not indicate a
  large, unambiguous loss against Classical of the kind `dfffd3da` showed — a candidate this
  roadmap should actually consider for SPRT is expected to be at or above Classical, or close
  enough that the gauntlet's own interval includes parity, not decisively behind it. A gauntlet
  result that fails this bar routes back to "investigate cause" (§26.8), not forward to SPRT.
- **SPRT (final gate)** — exactly the PRD's existing, un-modified gate 3: H0 = 0 Elo, H1 = +10
  Elo, α = β = 0.05, at the project's established SPRT time control (`docs/NNUE_PRD.md` §1).
  This protocol does not introduce a new strength bar — it reuses the one the project already
  committed to.

### 26.4 Early termination criteria

An experiment stops before its planned step count completes when any of the following is
observed, checked against the logged trajectory (§24.4's `TrainingDiagnostic` extension):

- **Obvious divergence**: loss increases sustained over multiple consecutive log intervals (not
  a single noisy uptick), or `gradient_norm` grows without bound.
- **Unstable optimization**: loss or correlation oscillates without a discernible trend across
  consecutive log points — the "learning rate too high" case from §24.4's curve guide. Stop and
  record the cell as unstable at this LR, don't extend it hoping it settles.
- **Validation collapse**: held-out loss/correlation gets *worse* than Phase 0's untrained
  baseline (§24.4) at any point after early training — a sign something is broken (e.g. a data
  or label wiring bug), not merely "this configuration isn't working."
- **Plateau after predefined patience**: held-out correlation does not improve by more than the
  noise floor (§26.3) for a predefined number of consecutive log intervals (e.g. 5, at whatever
  `log_interval` the run uses) — stop rather than burning further (cheap, but not free) compute
  chasing a flat curve.
- **Implementation bug discovered mid-run**: any correctness issue found in the experiment's own
  setup (wrong dataset path, mismatched seed, stale checkpoint, etc.) — stop immediately, fix,
  and restart; a run known to be measuring the wrong thing is not salvageable by finishing it.

**Stopping early under any of these predefined conditions is not a failed experiment** — it is
the protocol working as designed. A run that "just didn't get to finish" without triggering one
of these conditions is the actual failure mode to avoid (an incomplete, uninterpretable result),
not an early stop that did trigger one.

### 26.5 Checkpoint preservation policy

**Experiment ID scheme** (defined here because checkpoint naming depends on it; reused verbatim
by §26.6's learning log and everywhere else listed below): every experiment run gets a unique
identifier of the form `<PhaseCode>[-<Tag>]-<NNN>`:
- `PhaseCode` — one of `P0`, `P1`, `P2A`, `P2B`, `P2S2` (Phase 2's Stage 2 scaling), `P3`, `P4I`
  (K sweep), `P4II` (mate-aware loss), `P5`.
- `Tag` — an optional short descriptor when a phase has more than one concurrent line of
  investigation (e.g. a label-quality subset comparison run inside Phase 3 might be
  `P3-LQ-001`).
- `NNN` — a zero-padded three-digit sequence number, incrementing per new run within that
  `PhaseCode`/`Tag` pair, **never reused** even if a run is abandoned or superseded.

Examples matching this scheme: `P1-G03` (Phase 1, grid cell 3), `P2A-002` (Phase 2A, second run),
`P3-LQ-001` (Phase 3, label-quality comparison, first run).

**Policy**:
- **Checkpoints must never be overwritten during an experiment.** Each run's `checkpoint.pt`
  (and its accompanying `training_diagnostics.json`, canonical/quantized export, and manifest)
  is written to a location that includes its Experiment ID (directory or filename), never to a
  shared path a later run could clobber — `train_quantize_export`'s current default of a single
  `output_dir/checkpoint.pt` (§24.1) must be parameterized by Experiment ID once Phase 1's
  implementation begins; this protocol does not implement that change, it establishes the
  requirement the implementation must satisfy.
- **Every checkpoint receives this unique identifier**, and that identifier — not a description
  like "the LR=0.003 run" — is what every reference to the checkpoint uses from then on.
- **All intermediate checkpoints remain available**, not just the final promoted one per phase —
  including checkpoints from grid cells or configurations that were *not* promoted.

**Why**: future investigations may need to compare calibration, correlation, or other
diagnostics from an earlier checkpoint — including a non-promoted one — without re-running
expensive training to reconstruct it (a full Phase 2B run, for instance, is a real, if modest,
time cost; a $50k-relabeling-scale Phase 3 run is not free either, §24.6). Storage of a
`checkpoint.pt` (a few MB at this net's scale, §17/train-e3-real.md) is inexpensive compared to
the cost of regenerating it. This mirrors the project's own existing convention for released
nets (`nets/HISTORY.md`'s append-only ledger, `nets/<uuid>.json` manifests never overwritten) —
this policy extends the same never-overwrite discipline to *every* experimental checkpoint, not
only ones that reach release.

**Traceability**: the same Experiment ID must appear in — checkpoints, training logs,
calibration reports, evaluation reports, gauntlet runs, and SPRT runs (§26.6's learning log
template records it explicitly) — so any artifact found later can be traced back to the exact
run that produced it without ambiguity.

### 26.6 Learning log template

Every completed experiment (each grid cell in Phase 1; each of 2A/2B; Phase 3's comparison;
each of 4(i)/4(ii); anything in Phase 5 if it runs) fills in this template, so results are
comparable across the whole roadmap without re-deriving context each time. Every entry begins
with its **Experiment ID** (§26.5's scheme) — the same identifier used for this run's
checkpoint, training log, calibration report, evaluation report, and, if the run reaches that
far, its gauntlet and SPRT results, so any of those artifacts can be traced back to this exact
log entry and vice versa.

**Rejected hypotheses are retained, never discarded.** A "not promoted" entry is not a failed
log — it is exactly the kind of result §25's "Hypotheses tested" table already exists to
preserve. An experiment whose hypothesis was rejected has still told this project something
true about the network (e.g. "more steps alone did not move correlation beyond the noise floor
at LR=0.01" is real, reusable information, not a null result to delete). The **Reason rejected**
field below exists specifically so that information survives — future phases should be able to
read *why* a configuration was rejected without re-running it, the same way §24-§25 already
reused every earlier session's findings rather than re-deriving them.

```
## Experiment ID: <e.g. "P1-G03">

Hypothesis:          <what this experiment expects to show, one or two sentences>
Independent variable: <exactly what changed, per §26.1's table>
Controlled variables:  <cross-reference to §26.1's row, plus anything cell-specific, including
                        which seeds (split/training/shuffle, per §26.1's seed inventory) were fixed>
Expected outcome:     <a falsifiable prediction, stated before running>
Observed outcome:     <what actually happened, stated after running>

Metrics:
  Training   — train loss / held-out loss / train correlation / held-out correlation / RMSE / bias
  Calibration — compression ratio / cp-labeled bias,compression,MAE,RMSE / mate-labeled bias,compression,MAE,RMSE
  Runtime    — wall-clock time / throughput (if applicable)
  Engine     — NPS (if applicable) / gauntlet result (if applicable) / SPRT result (if applicable)

Decision:            <promoted / not promoted / stopped early — cite §26.3/§26.4's specific criterion met>
Reason rejected:      <if not promoted — the specific criterion that failed and by how much;
                       "did not clear the noise floor" is a complete, valid answer, not a gap
                       to fill in later. Omit this field only when Decision is "promoted".>
Next action:          <what this result implies for the next phase/cell, per §26.8>

Artifact locations (same Experiment ID throughout):
  Checkpoint:        <path, per §26.5's never-overwrite policy>
  Training log:       <path>
  Calibration report:  <path or inline>
  Evaluation report:   <path or inline>
  Gauntlet run:       <ID/path, if applicable>
  SPRT run:          <ID/path, if applicable>
```

### 26.7 Threats to validity

| Threat | Description | Mitigation in this protocol |
|---|---|---|
| **Dataset representativeness** | Stage 1 (Lichess) positions are drawn from whatever games users submitted for analysis — not a controlled sample of positions this engine's own search would actually reach | Not fully mitigable within this roadmap's scope (would require self-play data, Phase E/ADR-001's own longer-term plan); noted as a standing caveat on every phase's external validity, not just an internal one |
| **Shallow Stockfish labels** | Stage 2 labels come from `nodes=25,000` (§14.1/§24.1's own finding — chosen for session-scoped throughput, not label quality) | Directly targeted by Phase 3's matched-subset node-budget ablation; until Phase 3 runs, treat all Stage-2-derived correlation numbers as bounded above by this label quality (§24.3's noise-ceiling discussion) |
| **Mate/non-mate imbalance** | Mate-labeled records are ≈11.8% of held-out (§22.2) with a flat, discontinuous target (±3,000cp) very different in character from cp-labeled records | `calibration_report`'s mate/cp split (already built) is a *mandatory* measurement (§26.2) precisely so this imbalance can't hide inside an "overall" number the way §23.5's affine result initially could have |
| **Random initialization variance** | A single seed's result (train or Phase 0's baseline) could be unusually lucky or unlucky; §26.3's noise floor currently rests on one train/held-out comparison, not repeated-seed variance | Documented as a known gap in §26.3 itself; the roadmap should graduate to multi-seed replication for any result close to a promotion threshold, and this protocol's noise floor should be revisited once that data exists |
| **Hardware differences** | Training wall-clock and Stage 2 relabeling throughput are both hardware-dependent (§24.1's ~20s/2,000-steps and ~66 pos/sec figures are this session's machine, not a portable constant) | Runtime measurements (§26.2) are reported for anomaly detection and session-local planning, not cross-machine comparison; only correlation/RMSE/bias/compression (data-dependent, not hardware-dependent) are compared across sessions |
| **Stochastic optimization** | Adam's per-step updates and the fixed-order (or, post-Phase-1, reshuffled) data cycling both introduce run-to-run variation independent of the variable under test | Same mitigation as random-init variance above — the noise floor exists specifically to avoid attributing this kind of variation to the independent variable; multi-seed replication is the long-run fix |
| **Measurement noise** | `calibration_report`/`evaluate_held_out` are themselves computed over a fixed, finite held-out set (n=4,000, or a smaller matched subset for Phase 3) — any finite-sample statistic has its own sampling error | Same noise-floor mechanism; Phase 3's smaller matched-subset comparisons are explicitly flagged (§26.3) as needing a wider floor than the full-held-out-set comparisons in Phases 1-2 |

### 26.8 Decision flow

```mermaid
flowchart TD
    P0["Phase 0: untrained baseline\n(reference only, already measured)"] --> P1["Phase 1: Optimization\n(steps x LR grid)"]
    P1 --> Q1{"Held-out correlation\nclears §26.3's promotion bar?"}
    Q1 -->|No| P1x["Conclude 'optimization exhausted\nat this data scale' (not a failure)\n-- proceed to Phase 2 anyway"]
    Q1 -->|Yes| P1p["Promote: adopt best\nsteps/LR as new baseline"]
    P1x --> P2A
    P1p --> P2A["Experiment 2A: Stage 1\n20k -> 100k positions"]
    P2A --> Q2A{"Correlation still\nimproving beyond noise floor?"}
    Q2A -->|No| P3["Phase 3: Label quality\n(node-budget ablation)"]
    Q2A -->|Yes| P2B["Experiment 2B: Stage 1\n100k -> 1M positions"]
    P2B --> Q2B{"Still improving?"}
    Q2B -->|No| P2S2["Phase 2: scale Stage 2\n(compute-bound)"]
    Q2B -->|Yes| P2S2
    P2S2 --> Q2S2{"Stage 2 scaling adds\nmeasurable gain beyond Stage 1?"}
    Q2S2 -->|No| P3
    Q2S2 -->|Yes| P3
    P3 --> Q3{"Higher node budget\nimproves correlation?"}
    Q3 -->|No| P4["Phase 4: Loss/K\nreformulation"]
    Q3 -->|Yes| P4
    P4 --> Q4{"Mate bias improves\nwithout cp-subset regression?"}
    Q4 -->|No| P5{"Phases 1-4 all plateaued\nAND overfitting signature appears?"}
    Q4 -->|Yes| CAND["Candidate net selected"]
    P5 -->|Yes| P5run["Phase 5: capacity/regularization\n(only now in scope)"]
    P5 -->|No| CAND
    P5run --> CAND
    CAND --> GAUNT{"Gauntlet (~100 games):\nno large unambiguous loss?"}
    GAUNT -->|No| INVEST["Investigate cause\n(not a promotion)"]
    GAUNT -->|Yes| SPRT["Run SPRT\n(PRD gate 3: H0=0, H1=+10, a=b=0.05)"]
    INVEST -.-> P1
```

Text form, for a non-mermaid reader:

```
Phase 0 (baseline, reference only)
  -> Phase 1 (optimization)
       correlation clears promotion bar?
         no  -> "optimization exhausted", proceed anyway (not a stop)
         yes -> promote as new baseline
       -> Experiment 2A (Stage 1, 20k->100k)
            still improving beyond noise floor?
              no  -> skip 2B, go to Phase 3
              yes -> Experiment 2B (Stage 1, 100k->1M)
                       still improving?
                         no  -> Phase 2 Stage 2 scaling
                         yes -> Phase 2 Stage 2 scaling
       -> Phase 2 Stage 2 scaling
            measurable gain beyond Stage 1 alone?
              no/yes either way -> Phase 3 (label quality)
       -> Phase 3 (label-quality ablation)
            higher node budget helps?
              no/yes either way -> Phase 4 (loss/K)
       -> Phase 4 (loss/K reformulation)
            mate bias improves without cp regression?
              yes -> candidate net selected
              no  -> Phases 1-4 all plateaued AND overfitting appears?
                       yes -> Phase 5 (capacity/regularization) -> candidate net selected
                       no  -> candidate net selected anyway (best available)
       -> Candidate net
            gauntlet: no large unambiguous loss?
              no  -> investigate cause, back to relevant phase (not a promotion)
              yes -> run SPRT (PRD gate 3)
```

The purpose of fixing this flow now is to prevent ad hoc branching once real numbers exist —
every arrow above is a predefined rule from §26.3/§26.4, not a judgment call made in the moment.

### 26.9 Success definition

**The objective of the retraining roadmap is not to maximize any single offline metric.** Held-
out correlation, RMSE, compression, and bias (§26.2's Training/Calibration categories) are
diagnostic and comparative tools — they make it cheap to tell which candidate configurations are
worth the expensive step of engine testing, exactly as §26.3's promotion criteria use them. None
of them is the actual goal.

**The objective is to increase playing strength (Elo)**, measured the only way this project
accepts as valid: a passing SPRT result against the PRD's own gate 3 (§26.3), following a
gauntlet that first rules out an obvious regression. A configuration that wins on every offline
metric in §26.2 but does not translate into a positive SPRT result has not succeeded by this
roadmap's own definition — and, symmetrically, offline metrics exist so that this project does
not have to find that out the expensive way (a multi-day SPRT, `dfffd3da`'s own precedent) for
every candidate configuration, only for the ones that clear the cheap screens first.

**Explicitly confirmed, per this task's instruction**: no trainer code was modified, no
retraining was performed, no hyperparameters were changed to produce this section. This is the
experimental contract §24's roadmap will be run against — Phase 1 has not started.

### 26.10 Rolling baseline policy

**Every future experiment compares against the latest promoted network, not the original
historical baseline.** As each phase promotes a new candidate, that candidate becomes the
reference point every subsequent phase's "did this help" question is asked against — comparing
a new phase against a stale, superseded baseline would credit or blame it for gains an earlier
phase already captured (exactly the attribution error §26.1's controlled-variable discussion
warns about generally, applied here to the choice of comparison point itself).

**Recorded baselines** (append-only — a new row is added when a phase promotes a candidate; no
row is ever deleted or overwritten, per §26.5's own never-overwrite spirit):

| Baseline | Source | Held-out correlation | Role |
|---|---|---|---|
| Original production baseline | `dfffd3da` (E-3, #203) | 0.5044 | **Historical context only** — the net #219/#206's SPRT precedent and every pre-roadmap measurement refer to; no longer the active comparison point for new experiments |
| Phase 1 promoted baseline | `P1-G04` (§27.11) | 0.5315 | **Current reference model** — every experiment from Experiment 2A onward compares against this, not against `dfffd3da` |

**How to apply**: a phase's promotion criteria (§26.3) and its "did this help" framing always
read as "beyond the current reference model's result," not "beyond 0.504." `dfffd3da`'s number
stays in every report for historical/narrative continuity (e.g. "up from the original production
net's 0.504") but is not itself the pass/fail comparison point once a later baseline has been
promoted. Whoever runs the next phase should update this table's final row (and only add to it,
never edit prior rows) the moment that phase promotes a new candidate.

## 27. Phase 1 completion report (2026-07-20)

Phase 1 (§24.4/§26): determine whether `dfffd3da`'s 0.504 held-out correlation is
optimization-limited, using only steps/LR/schedule changes, per §26.1's controlled-variable
table (architecture, dataset, split, features, labels, loss, `K`, export/quantization all
unchanged — confirmed in §27.1's config table below). Infrastructure added to `trainer/model/
train.py` (extended `TrainingDiagnostic`, per-epoch reshuffle, optional cosine LR schedule,
per-checkpoint preservation) — commits `caf41f8`/`a4f05a3`, 170/170 tests passing before any
run. Grid executed via `trainer/scripts/phase1_optimization_sweep.py`, ~10.9 minutes total
wall-clock for all 7 cells.

### 27.1 Grid executed

| Experiment ID | Seed | Steps | LR | Schedule | Wall-clock |
|---|---|---|---|---|---|
| P1-G00 | 42 | 2,000 | 0.01 | constant | 26.9s |
| P1-G00-S43 | 43 | 2,000 | 0.01 | constant | 25.5s |
| P1-G00-S44 | 44 | 2,000 | 0.01 | constant | 25.0s |
| P1-G01 | 42 | 20,000 | 0.01 | constant | 182.0s |
| P1-G02 | 42 | 2,000 | 0.001 | constant | 25.3s |
| P1-G03 | 42 | 20,000 | 0.001 | constant | 183.1s |
| P1-G04 | 42 | 20,000 | 0.01 | cosine (warmup=200) | 183.7s |

All seven share: `hidden_width=256`, `qa=127`, `qb=64`, `output_scale=400`, `K=2.773456`,
`batch_size=256`, Stage 1 (20,000) + Stage 2 (20,000) dataset, split seed 42 (identical
`combine_and_split` call to `dfffd3da`'s own training run) — architecture, features, labels,
loss, and export/quantization untouched, matching §26.1's declared Phase 1 row exactly. Every
checkpoint (one per logged diagnostic point, ~20 per cell) was preserved under
`trainer/outputs/phase1/<experiment_id>/checkpoints/`, per §26.5 — none overwritten.

### 27.2 Repeated-seed variance — the placeholder noise floor, now actually measured

P1-G00/-S43/-S44 (§26.3's declared exception: training seed varied only, seeds 42/43/44,
everything else identical to P1-G00) is the repeated-seed variance experiment §26.3 called for
before treating any threshold as permanent. **Measured**: held-out correlation = 0.5029 / 0.5065
/ 0.5039 (mean 0.5044, sample std ≈ **0.0019**, range 0.0036). Held-out loss = 0.0829 / 0.0830 /
0.0830 (essentially identical across seeds).

**This directly supersedes §26.3's provisional 0.06 placeholder for practical use** — the real
measured natural variance at this configuration is roughly **30x smaller** than the placeholder
that stood in for it. Two consequences, both stated explicitly since they change how the rest of
this report reads a "did this clear the bar" question:
- Using the old 0.06 placeholder, none of this grid's deltas (largest: +0.027, P1-G04) would
  have cleared the promotion bar — the placeholder would have wrongly concluded "no real effect
  detected" for changes that are, in fact, 10-15x the actual measured noise.
- Using the real measured std (0.0019), a delta needs to exceed roughly 0.004-0.006 (2-3
  standard deviations) to be called a real effect with reasonable confidence — a bar every
  promotion decision below actually uses.
- **Caveat, stated with the same honesty §26.3 already required of the placeholder**: n=3 is a
  small sample for a definitive population variance, and this variance was measured only at one
  configuration (2,000 steps, LR=0.01, constant) — it is not yet confirmed to generalize to
  every other point in the grid (e.g. the 20,000-step cells were each run at a single seed, not
  replicated). Treat 0.0019 as a much better estimate than 0.06, not as a final, universally
  applicable constant — a future phase with spare budget should still widen this replication.
- **Bonus finding**: the mean of the three seed replicates (0.5044) lands almost exactly on
  `dfffd3da`'s own measured value (0.5044, §22.2) — the reshuffle addition (§26.1, applied
  uniformly to every Phase 1 cell) appears correlation-neutral at this step count, though its
  effect was never isolated as its own single-variable experiment (§26.1 always said it wouldn't
  be) and this is a coincidental confirmation, not a controlled measurement of reshuffle's own
  effect.

### 27.3 Learning curves and interpretation

Full per-step trajectories in `trainer/outputs/phase1/<id>/training_diagnostics.json`. Classified
against §24.4's four cases, using the *actual* logged curve shape, not just the endpoint:

- **P1-G00/-S43/-S44 (2,000 steps, LR=0.01)**: held-out correlation rises smoothly and
  monotonically for the entire run (e.g. G00: 0.195→0.323→0.399→...→0.503 across the logged
  points), train correlation tracks just above it throughout, held-out loss falls monotonically
  the whole time. **"Both train and validation still improving" — continue training.** This is
  directly confirmed by P1-G01 (same LR, 10x steps): stopping at 2,000 steps left real gains on
  the table.
- **P1-G01 (20,000 steps, LR=0.01 constant)**: held-out correlation climbs to a peak at step
  14,999 (0.5262), then **wobbles/mildly declines** (15,999→0.5261, 16,999→0.5252, 17,999→
  0.5256, 18,999→0.5259, 19,999→0.5246) while train correlation keeps climbing the entire run
  (0.613 at step 6,999 → 0.633 at step 18,999) and train loss falls toward zero (0.0009 by the
  end). **Held-out loss itself troughs at step 5,999 (0.0715) and then rises for the rest of the
  run, reaching 0.0835 by step 19,999** — a clearer overfitting signal than correlation alone,
  which stays in a plateau/mild-decline band rather than collapsing. **"Train improving while
  validation plateaus" — emerging overfitting**, confirmed directly from the logged trajectory,
  not inferred. Note for future phases: held-out *loss* trending up while held-out *correlation*
  stays flat is itself a valid, earlier overfitting signal — don't wait for correlation to
  visibly decline before treating a run as past its useful point.
- **P1-G02 (2,000 steps, LR=0.001 constant)**: starts at *negative* correlation (train −0.175,
  held-out −0.197 at step 99) and is still climbing steadily at the final step (train 0.357,
  held-out 0.340) — nowhere near converged. Train loss is noisy without a strong downward trend
  for most of the run (bounces in a 0.12-0.16 band). **"Both still improving"**, not oscillation
  in the unstable/diverging sense — this LR is simply too slow to make meaningful progress in
  this step budget, not unstable.
- **P1-G03 (20,000 steps, LR=0.001 constant)**: held-out correlation rises **monotonically for
  the entire run**, never plateauing, reaching 0.4979 only at the final logged step. **"Both
  still improving"** — this run was stopped before reaching its ceiling at this LR; its true
  plateau (if any) at `LR=0.001` is unknown from this data, only that 20,000 steps was not
  enough to reach it.
- **P1-G04 (20,000 steps, LR=0.01 cosine, warmup=200)**: held-out correlation rises fast early
  (mirroring G01, same starting LR), then **plateaus tightly from step ~10,999 onward** (0.5302,
  0.5300, 0.5312, 0.5312, 0.5309, 0.5315, 0.5311, 0.5313, 0.5310, 0.5311 across the last 10
  logged points — a genuine flatline, not a slow decline). Held-out loss stays in a **much
  tighter band than G01's** (0.0741→0.0800, an 8% relative rise, vs. G01's 0.0715→0.0835, a 17%
  relative rise) even as train loss and train correlation continue the same near-memorization
  trend as G01 (train correlation 0.623 by the end). **"Both plateau together" — optimization
  exhausted for this configuration**, and the plateau is visibly gentler than G01's — direct,
  within-run evidence that decaying the LR toward the end of a long run does what it was
  hypothesized to do (§24.4: "LR=0.01 held flat for 20,000 steps is exactly the condition that
  tends to oscillate/degrade near a minimum").
- **No cell showed the fourth case (unstable/oscillating, LR-too-high)** — worth stating
  explicitly since it was one of four possible readings and did not occur; LR=0.01 was not too
  high for this network/data scale at any step count tested.

### 27.4 Final metrics table (selected checkpoint per cell, held-out set, n=4,000)

Selected checkpoint = the peak-held-out-correlation point on each cell's own trajectory
(§24.4's rule), not necessarily the final step — see §27.1 for which step was selected.

| Experiment ID | Selected step | Correlation | Loss | RMSE | Bias (cp) | Compression |
|---|---|---|---|---|---|---|
| P1-G00 | 1,899/1,999 | 0.5029 | 0.0829 | 1280.55 | −216.71 | 0.0612 |
| P1-G00-S43 | 1,899/1,999 | 0.5065 | 0.0830 | 1280.54 | −220.39 | 0.0618 |
| P1-G00-S44 | 1,999/1,999 | 0.5039 | 0.0830 | 1280.15 | −221.71 | 0.0632 |
| P1-G01 | 14,999/19,999 | **0.5262** | 0.0806 | 1231.64 | −215.68 | **0.1433** |
| P1-G02 | 1,999/1,999 | 0.3403 | 0.1322 | 1311.69 | −223.69 | 0.0180 |
| P1-G03 | 19,999/19,999 | 0.4979 | 0.0846 | 1282.40 | −218.61 | 0.0593 |
| P1-G04 | 15,999/19,999 | **0.5315** | **0.0797** | **1239.86** | −213.47 | **0.1255** |
| *(reference)* `dfffd3da` | — | 0.5044 | 0.0823 | 1279.88 | −219.63 | 0.0630 |
| *(reference)* Phase 0 baseline | — | 0.0663 | 0.1460 | 1320.98 | −233.21 | 0.0000 |

**A finding beyond Phase 1's own stated objective**: compression roughly **doubles to
triples** for G01/G04 (0.143 / 0.126) relative to every other cell (0.06-0.06, matching
`dfffd3da`'s own 0.063) — purely from more steps and a schedule, with `K`/loss/architecture
completely unchanged. This connects directly to §23.4's finding that compression is capped by
correlation: since correlation also improved for these two cells, some of the compression gain
is exactly what that ceiling predicts, but the improvement (2-2.3x) is larger than correlation's
own gain (1.04-1.05x) would alone explain by the strict OLS-ceiling argument — consistent with
(not proof of) §23.7's separate, still-unablated hypothesis that more optimization simply lets
the network's raw output magnitude grow further against the sigmoid's saturating loss. Recorded
as a **supported, not confirmed, observation** — no K ablation was run in Phase 1 to isolate this.

### 27.5 Calibration comparison — mate/cp split (§26.2's mandatory measurement)

| Experiment ID | Overall bias | Mate bias | Mate compression | Cp bias | Cp compression |
|---|---|---|---|---|---|
| P1-G00 (mean of 3 seeds) | −219.6 | −1,727.3 | 0.0536 | −17.9 | 0.0746 |
| P1-G01 | −215.7 | −1,651.0 | 0.1105 | −23.7 | 0.1856 |
| P1-G04 | −213.5 | −1,661.1 | 0.0996 | −19.8 | 0.1599 |
| `dfffd3da` (reference) | −219.6 | −1,726.6 | 0.0551 | −18.0 | 0.0753 |

**Overall and mate-labeled bias barely move** (−1,651 to −1,729cp across every cell, including
the untouched baseline) — confirming, with a second independent line of evidence, §23's finding
that the mate-labeled bias is a *structural* problem (target/loss shape, §23.7's mate-handling
hypothesis), not something optimization duration or schedule touches. Compression improves for
both cp-labeled and mate-labeled buckets in the optimized cells (G01/G04), consistent with
§27.4's observation, but the improvement is proportionally similar in both — **optimization
helps the network's overall scale, it does not close the mate-vs-cp calibration gap** that
motivated Phase 4. This is exactly the outcome the roadmap's phase separation predicted:
Phase 1 (optimization) and Phase 4 (loss/mate reformulation) target different failure modes, and
this data confirms they don't substitute for each other.

### 27.6 Relative ranking

By held-out correlation (primary metric, §26.0): **P1-G04 (0.5315) > P1-G01 (0.5262) >
P1-G00-S43 (0.5065) > P1-G00-S44 (0.5039) ≈ P1-G00 (0.5029) ≈ `dfffd3da` (0.5044) > P1-G03
(0.4979) >> P1-G02 (0.3403)**. G01 and G04 are the only cells clearing the measured noise floor
(§27.2) above the baseline cluster; G03 is statistically indistinguishable from baseline noise
(−0.0065, within ~3.5 std, and independently known to be not-yet-converged, §27.3); G02 is an
unambiguous large negative outlier, not a promotion candidate. G04 leads G01 by +0.0053 — larger
than the measured single-seed noise floor, but this specific A/B was not itself seed-replicated
(§27.2's caveat applies), so it is reported as the better *single* result, directionally
consistent with the schedule hypothesis, not an independently-confirmed win over G01.

### 27.7 Promotion decision, applying §26.3 formally

| Cell | Correlation clears noise floor? | Selected via curve rule? | Held-out loss ≤ baseline? | Promoted? |
|---|---|---|---|---|
| P1-G01 | Yes (+0.0218, ~11.5σ) | Yes | Yes (0.0806 < 0.0830) | **Yes** |
| P1-G04 | Yes (+0.0271, ~14.3σ) | Yes | Yes (0.0797 < 0.0830) | **Yes — best result** |
| P1-G03 | No (−0.0065, ~3.5σ, ambiguous) | Yes | No (0.0846 > 0.0830) | No |
| P1-G02 | No (large negative) | Yes | No (far worse) | No |

Both P1-G01 and P1-G04 independently clear all three of §26.3's Phase 1 promotion criteria,
using the noise floor actually measured in this run (§27.2) rather than the provisional
placeholder. **P1-G04's configuration (steps=20,000, LR=0.01, cosine schedule, warmup=200) is
promoted as the new Phase 1 baseline.**

### 27.8 Is optimization exhausted?

**Yes, for this dataset, at the configurations tested** — and this is shown *within a single
run's own trajectory*, not just by comparing across cells. P1-G04's held-out correlation
flatlines for the last ~10,000 of its 20,000 steps (§27.3) even as the LR continues decaying
toward zero and train correlation continues climbing — textbook "both plateau together" (held-
out) paired with continued train-side improvement, the exact overfitting signature §24.4's guide
describes, occurring at the *best* optimization configuration tested. Further optimization-only
tuning (a different schedule shape, more warmup, a different peak LR) faces genuinely diminishing
expected returns: the mechanism already identified (§24.0/§27.3) — the model overfitting a fixed
36,000-position dataset — is not something schedule tuning can keep resolving; it is a data-size
ceiling, and G04 already tested the specific hypothesis (§24.4) that a decayed schedule would
mitigate exactly this failure mode, which it did, partially, without eliminating it.

### 27.9 Recommendation

**Proceed to Stage 1 data scaling (Experiment 2A, 20k→100k), adopting P1-G04's optimization
configuration (steps=20,000, LR=0.01, cosine, warmup=200) as the new baseline**, not "continue
optimization" and not "revise the optimization strategy." Reasoning, each point tied to
measured evidence above:
- Phase 1's own question — is current performance optimization-limited? — is answered **yes,
  partially**: P1-G04 recovered a real, noise-floor-clearing +0.0271 correlation gain (0.504→
  0.532, ~5.4% relative) purely from steps/schedule, with architecture/data/loss untouched.
  Phase 1 was not a null result; the roadmap should carry this gain forward, not discard it.
- But the *same* winning configuration shows a genuine, within-run plateau (§27.8) — further
  optimization-only search now has a lower expected marginal return than the untried lever
  (data volume) that plateau itself implicates as the next binding constraint, exactly per
  §24.3's reasoning (a model that has stopped improving on its own training data's held-out
  split, while still fitting the training data harder, is data-limited at that data size, not
  merely under-optimized).
- This matches the Experimental Protocol's own gate for this decision (§26.3/§24.4's decision
  flow, §26.8): "both plateau → optimization exhausted → proceed to the next phase in roadmap
  order" — Stage 1 data scaling, unchanged ordering, per this task's explicit instruction not to
  reorder the roadmap.

**Not started in this report**: Experiment 2A itself (Stage 1 acquisition at `target_count=
100,000`, retraining at the new baseline configuration). This report's scope is Phase 1's
completion and recommendation; beginning 2A is a distinct next step for explicit confirmation.

### 27.10 Learning log entries

```
## Experiment ID: P1-G00
Hypothesis:          Reproducing dfffd3da's hyperparameters (steps=2000, LR=0.01) under the
                      new reshuffle-enabled pipeline reproduces its held-out correlation.
Independent variable: (none within this cell -- part of the seed-replication trio, §26.3)
Controlled variables:  Dataset/split seed 42, architecture, K=2.773456, seed=42 (training/init)
Expected outcome:     Held-out correlation close to dfffd3da's 0.5044.
Observed outcome:     0.5029 at selected step 1899/1999 -- matches within measured seed noise.
Metrics:              See §27.1/§27.4/§27.5.
Decision:             Not promoted (baseline reference point, not a candidate for improvement).
Reason rejected:      N/A -- reference cell, not evaluated for promotion.
Next action:          Used as the noise-floor reference for P1-G01/G02/G03/G04.
Artifacts:            trainer/outputs/phase1/P1-G00/

## Experiment ID: P1-G00-S43 / P1-G00-S44
Hypothesis:           Same config as P1-G00, different training seed -- measures natural
                       correlation variance from seed alone.
Independent variable:  Training/init seed only (43, 44).
Controlled variables:   Everything else identical to P1-G00.
Expected outcome:      Correlation close to P1-G00's, within some unknown natural variance.
Observed outcome:      0.5065 / 0.5039 -- std across all three ≈ 0.0019 (§27.2).
Decision:              Not promoted (reference), but load-bearing: this IS the repeated-seed
                       variance experiment §26.3 required before any permanent threshold.
Next action:           §26.3's 0.06 placeholder is superseded by this measured 0.0019 for
                       every promotion decision in §27.7.
Artifacts:             trainer/outputs/phase1/P1-G00-S43/, P1-G00-S44/

## Experiment ID: P1-G01
Hypothesis:           More steps alone (10x, same LR=0.01) improves held-out correlation.
Independent variable:  Steps (2000 -> 20000).
Controlled variables:   LR=0.01, schedule=constant, seed=42, dataset/split/architecture/loss.
Expected outcome:      Correlation improves if optimization-limited; unchanged if not.
Observed outcome:      0.5262 at step 14999 (peak) -- +0.0218 over baseline mean, ~11.5x the
                       measured seed std. Confirmed: more steps alone helps. Held-out loss
                       troughs at step 5999 then rises -- emerging overfitting past that point.
Metrics:               See §27.1/§27.3/§27.4/§27.5.
Decision:              Promoted (clears all three §26.3 criteria, §27.7).
Next action:           Superseded by P1-G04's better result; not the final recommendation, but
                       confirms the steps hypothesis independent of the schedule question.
Artifacts:             trainer/outputs/phase1/P1-G01/

## Experiment ID: P1-G02
Hypothesis:           A more standard, lower Adam LR (0.001) improves convergence at the
                       original step count (2000).
Independent variable:  LR (0.01 -> 0.001), steps unchanged (2000).
Controlled variables:   Steps=2000, schedule=constant, seed=42, dataset/split/architecture/loss.
Expected outcome:      Correlation similar to or better than baseline, if 0.01 was too high.
Observed outcome:      0.3403 -- far WORSE than baseline (-0.164, unambiguous). Still climbing
                       steadily at the final step, starting from negative correlation early on.
Metrics:               See §27.1/§27.3/§27.4/§27.5.
Decision:              Not promoted -- fails all three §26.3 criteria.
Reason rejected:       LR=0.001 is far too slow to converge within 2000 steps at this data
                       scale/batch size -- this is a real, informative negative result (not an
                       LR that's simply worse in the limit, see P1-G03), not noise or a bug.
Next action:           Confirms that if a lower LR is used, it needs many more steps -- exactly
                       what P1-G03 tests next.
Artifacts:             trainer/outputs/phase1/P1-G02/

## Experiment ID: P1-G03
Hypothesis:           LR=0.001 needs more steps than 2000 to be competitive; 20000 steps should
                       let it catch up to or exceed the LR=0.01 baseline.
Independent variable:  Steps (2000 -> 20000), holding LR=0.001 fixed (continuing from P1-G02).
Controlled variables:   LR=0.001, schedule=constant, seed=42, dataset/split/architecture/loss.
Expected outcome:      Correlation approaches or exceeds baseline (0.504) given enough steps.
Observed outcome:      0.4979 at the final step (19999) -- still rising monotonically the whole
                       run, never plateaued. Close to but slightly below baseline; loss (0.0846)
                       slightly worse than baseline (0.0830).
Metrics:               See §27.1/§27.3/§27.4/§27.5.
Decision:              Not promoted -- fails §26.3's noise-floor and loss criteria (narrowly).
Reason rejected:       Within ~3.5x the measured seed std of baseline -- not clearly
                       distinguishable from noise, AND the run was stopped before converging
                       (still rising at the final step), so this is an inconclusive result at
                       this step budget, not evidence that LR=0.001 is a worse ceiling than
                       LR=0.01 -- only that it needs more than 20000 steps to show its own
                       ceiling, which this experiment did not determine.
Next action:           Not pursued further in Phase 1 (P1-G04 already found a better
                       configuration via a different lever -- schedule, not raw LR reduction);
                       left as an open question for future work if ever revisited.
Artifacts:             trainer/outputs/phase1/P1-G03/

## Experiment ID: P1-G04
Hypothesis:            A cosine LR decay (peak 0.01, 200-step warmup, decaying to 0 over 20000
                        steps) mitigates the emerging-overfitting pattern a flat LR=0.01 shows
                        at this step count (predicted from P1-G01's own expected trajectory
                        shape before this cell was run, per §24.4/advisor review).
Independent variable:   LR schedule (constant -> cosine), steps=20000 (same as P1-G01).
Controlled variables:    Peak LR=0.01, seed=42, dataset/split/architecture/loss.
Expected outcome:       Correlation at or above P1-G01's; held-out loss trajectory more stable
                        (smaller late-run rise) than P1-G01's.
Observed outcome:       0.5315 at step 15999 -- the best result in the grid (+0.0053 over
                        P1-G01, +0.0271 over baseline). Held-out correlation plateaus tightly
                        for the last ~10000 steps; held-out loss rise (8% relative) is much
                        gentler than P1-G01's (17% relative). Both predictions confirmed.
Metrics:                See §27.1/§27.3/§27.4/§27.5.
Decision:               Promoted -- best result, clears all three §26.3 criteria (§27.7).
Next action:            Adopted as Phase 1's recommended new baseline configuration for
                        Experiment 2A (§27.9) -- not started in this report.
Artifacts:              trainer/outputs/phase1/P1-G04/
```

### 27.11 Promoted optimization schedule (frozen default for all subsequent phases)

| Field | Value |
|---|---|
| Experiment ID | `P1-G04` |
| Total training steps | 20,000 |
| Initial (peak) learning rate | 0.01 |
| Learning-rate schedule | `cosine` (linear warmup, then cosine decay to 0) |
| Warmup configuration | 200 steps, linear ramp from ~0 to the peak rate |
| Shuffle policy | Per-epoch reshuffle, driven by `seed_everything(config.seed)`'s seeded `random` module — not a separate dedicated shuffle seed (§26.1's seed-inventory choice, confirmed unchanged for this schedule) |
| Training/init seed | 42 |
| Promotion decision | **Promoted** (§27.7) — clears all three §26.3 criteria using the directly-measured noise floor (§27.2); the single best result in Phase 1's grid |

**This becomes the default optimization schedule for every subsequent roadmap phase** (2A, 2B,
Stage 2 scaling, Phase 3, Phase 4, Phase 5 if it runs) **unless a later experiment explicitly
supersedes it** — i.e. a future phase that finds a *better* schedule (through its own declared,
single-variable experiment, not an incidental side effect of some other change) updates this
table and this table alone; no phase silently reverts to `dfffd3da`'s original flat
`steps=2,000, LR=0.01` or invents a new schedule ad hoc. Everything in this table is optimization
schedule only — it does not include dataset size, architecture, or loss, each of which is its
own separately-controlled variable per §26.1's table and may legitimately change across phases
while this schedule stays fixed.

**Resolves an open tension with §24.4's original phrasing**: §24.4 (written before Phase 1 ran)
suggested scaling total steps to hold the number of *passes* over a larger dataset constant when
Stage 1 volume increases. Experiment 2A (§28) instead holds this table's step count exactly
fixed at 20,000 and varies only Stage 1 volume, per this task's explicit instruction ("held
constant: promoted optimization schedule... unchanged unless a protocol-defined reason requires
otherwise") — the simpler, more strictly single-variable design takes precedence over the
earlier passes-scaling idea, which would have varied two things (steps and data volume)
simultaneously. If a future phase has reason to revisit passes-scaling, it should be run as its
own declared experiment, not silently folded into a data-volume phase.

## 28. Experiment 2A completion report (2026-07-20) — Stage 1, 20k → 100k

### 28.1 What was run

Independent variable: Stage 1 position count (20,000 → 100,000). Held constant, per §26.1's
Experiment 2A row and this task's explicit instruction: P1-G04's frozen schedule (§27.11 —
steps=20,000, LR=0.01, cosine, warmup=200, seed=42, unchanged), architecture, feature
representation, labels, loss, `K`, export/quantization, evaluation pipeline, Stage 2 (unchanged
at 20,000), split seed (42).

**Held-out-set preservation** (the one real methodological subtlety, resolved before training):
re-running `combine_and_split` over a larger Stage 1 pool would produce a *different* held-out
set (still 10%, but a different, larger sample), confounding "more data" with "a different
evaluation set." Instead: a fresh Stage 1 pull was made at `target_count=100,000`
(`outputs/datasets/stage1-lichess-100k/`), and its first 20,000 lines were verified
**byte-for-byte identical** to the original 20,000-position file before proceeding (confirmed
via a separate reproducibility check: re-running the acquisition driver at `target_count=20,000`
independently reproduced the existing file's SHA-256 exactly, `a9d338b2...`, confirming the
source and the driver are both deterministic). The **exact original 4,000-record held-out set**
and **exact original 36,000-record training set** from Phase 1 were reused unchanged; the 80,000
new (verified non-overlapping) Stage-1-only positions were added to the training set only.
Training set: 116,000 records (36,000 original + 80,000 new). Held-out: 4,000, identical to
every other experiment in this roadmap.

Experiment ID: `P2A-001`. Wall-clock: 156.3s (vs. P1-G04's 183.7s — both runs are 20,000 steps
at the same batch size, so per-step cost should be pool-size-independent; the difference is
plausibly ordinary machine-load variance between separate runs, not a real effect of the larger
pool, and is not investigated further here).

### 28.2 Result — plain reading first

**Held-out correlation: 0.5048** at the selected checkpoint (step 10,999/19,999) — indistinguishable
from `dfffd3da`'s original 0.5044 (delta +0.0004, well inside the measured noise floor,
§27.2), and **−0.0267 below the P1-G04 reference** (§26.10) — roughly 14x the measured seed-noise
std (0.0019), clearly outside noise, in the negative direction. Held-out loss: 0.0996, worse
than both `dfffd3da` (0.0823) and P1-G04 (0.0797).

**Not promoted.** Fails §26.3's Phase 1-style criteria against the current reference model
(P1-G04): correlation does not exceed the reference by the noise floor (it's below it), and
held-out loss is worse, not better. **§26.10's rolling-baseline table is not updated — P1-G04
remains the reference model.** This is a valid, informative, honestly-reported result on its own
terms, not an inconclusive non-result to be explained away.

### 28.3 What the trajectory shows, and what it does and does not establish

| Step | 2A held-out corr. | P1-G04 held-out corr. (same step, from §27.3) |
|---|---|---|
| 999 | 0.4660 | 0.4549 |
| 1,999 | 0.4645 | 0.5042 |
| 10,999 (2A's selected step) | 0.5048 | 0.5302 |
| 19,999 | 0.5020 | 0.5311 |

**2A was briefly *ahead* of P1-G04 at step 999, then fell behind by step 1,999 and stayed
behind for the rest of the run** — not a clean, monotonic "more data helps" or "more data
hurts" story either way; a genuine reversal early in training. 2A's held-out loss falls from
0.134 (step 999) to ~0.099 and then **stays flat** (0.0996→0.0988→0.0987 across the back half)
— unlike P1-G01's pattern (§27.3, loss troughs then *rises*), 2A's loss settles at a lower
plateau and stays there. Held-out correlation shows the same shape: rises then plateaus in a
tight 0.499-0.505 band for the last ~9,000 steps, while train correlation keeps climbing (0.528
→0.591) and train loss keeps falling (0.093→0.022) — a widening train/held-out gap, but arriving
at a *lower* held-out plateau than P1-G04's.

**One real, unresolved methodological limitation, stated as an open question, not a
conclusion**: P1-G04's schedule (20,000 steps, fixed per this task's instruction) represents
~142 passes over the original 36,000-record training set, but only **~44 passes** over the new
116,000-record set (20,000 × 256 ÷ 116,000). This experiment therefore cannot distinguish
between two live hypotheses:
1. **More Stage 1 data of this quality, at this compute budget, does not improve held-out
   correlation here** — the plain reading of §28.2's result.
2. **More Stage 1 data would help, but 20,000 steps is not enough optimization for a 116,000-
   record pool** — i.e. this run is itself under-optimized relative to what the larger dataset
   could support, and a fair test of "does data volume help" requires proportionally more
   compute, not the same step budget.

**This experiment does not distinguish these two hypotheses, and this report does not claim to
know which is true.** There is no confound-free way to vary data volume in isolation: holding
steps fixed (as done here, per this task's explicit instruction) risks under-fitting the larger
set; holding *passes* fixed instead (§24.4's original, superseded idea) would require roughly
3.2x more total steps — a real additional compute cost, and a test of a different question
("data + proportional compute") than the one just run ("data at fixed compute"). Neither is
more "correct" than the other; they answer different questions. The step-999-vs-step-1,999
reversal above (§28.3's table) is itself evidence against confidently picking either hypothesis
from this data alone.

Not filed under §26.4's "implementation bug discovered mid-run" — the run executed correctly and
measured exactly what it was designed to measure; this is a design-interpretation limitation of
the experiment as scoped, not a defect in its execution.

### 28.4 Metrics comparison

| | `dfffd3da` (historical) | P1-G04 (current reference) | P2A-001 |
|---|---|---|---|
| Held-out correlation | 0.5044 | **0.5315** | 0.5048 |
| Held-out loss | 0.0823 | **0.0797** | 0.0996 |
| RMSE | 1279.88 | 1239.86 | 1274.30 |
| Overall bias (cp) | −219.63 | −213.47 | −209.51 |
| Overall compression | 0.0630 | **0.1255** | 0.0696 |

### 28.5 Calibration comparison (mate/cp split)

| | `dfffd3da` | P1-G04 | P2A-001 |
|---|---|---|---|
| Mate bias (cp) | −1,726.6 | −1,661.1 | −1,703.4 |
| Mate compression | 0.0551 | 0.0996 | 0.0551 |
| Cp bias (cp) | −18.0 | −19.8 | −9.6 |
| Cp compression | 0.0753 | 0.1599 | 0.0861 |

Mate-labeled bias again barely moves (−1,703 to −1,727cp across all three) — consistent with
every prior finding (§23, §27.5) that this is a structural loss/target problem, unaffected by
either optimization schedule or (now) Stage 1 data volume alone. Compression sits between
`dfffd3da`'s and P1-G04's for both buckets, roughly tracking correlation's own position between
the two — consistent with, not independent evidence beyond, §23.4's correlation-caps-compression
finding.

### 28.6 Recommendation — decision deferred to review, per this task's explicit instruction

**Do not proceed to Experiment 2B.** Not because saturation was cleanly observed (§28.3 explains
why this run cannot make that claim), but because P2A-001 was not promoted and this task's own
instruction is to stop and wait for review regardless.

**Two options for how to proceed, both consistent with the roadmap's unchanged ordering,
presented for review rather than one prescribed as correct** (§28.3's reasoning: neither is
obviously the "fix," they test different questions):
1. **Re-run Stage 1 at 100k with steps scaled to restore ~142 passes** (~64,000 steps,
   everything else identical) — tests "does more Stage 1 data help, given proportionally more
   compute." Real additional cost: roughly 3.2x P1-G04's wall-clock (~10 minutes at this scale).
2. **Accept 0.5048 as the answer to the question actually asked** ("does more Stage 1 data help
   at a fixed 20,000-step compute budget") — answer: no, not here — and move to a different
   lever (Phase 3 label quality, or Phase 4 loss/K reformulation) rather than spending more
   compute on Stage 1 volume specifically.

Both are legitimate next steps; this report does not pick one. **Stopping here for review, as
instructed — Experiment 2B has not been started.**

### 28.7 Learning log entry

```
## Experiment ID: P2A-001
Hypothesis:            Increasing Stage 1 training data 20k -> 100k (116k total training
                        records vs. 36k), with everything else held fixed at P1-G04's
                        promoted schedule, improves held-out correlation beyond 0.5315.
Independent variable:   Stage 1 dataset size only (20,000 -> 100,000 positions).
Controlled variables:    P1-G04's frozen schedule (steps=20000, lr=0.01, cosine, warmup=200,
                         seed=42), architecture, feature representation, labels, loss, K,
                         export/quantization, split seed=42, held-out set (exact same 4,000
                         records reused byte-for-byte from Phase 1), Stage 2 count (20,000,
                         unchanged).
Expected outcome:       Held-out correlation at or above P1-G04's 0.5315, per §24's data-
                        volume hypothesis.
Observed outcome:       0.5048 at step 10999/19999 -- indistinguishable from the original
                        dfffd3da baseline (0.5044) and a regression vs. P1-G04 (-0.0267, ~14x
                        the measured noise floor). Held-out loss/correlation plateau tightly
                        for the final ~9000 steps while train correlation keeps climbing
                        (widening train/held-out gap); trajectory briefly led P1-G04's at
                        step 999 before falling behind by step 1999 and staying behind (§28.3).
                        Expectation not confirmed.
Metrics:                See §28.4/§28.5.
Decision:               Not promoted -- fails §26.3's criteria against the current reference
                        model (P1-G04): correlation below reference by more than the noise
                        floor, held-out loss worse, not better.
Reason rejected:        Held-out correlation regressed relative to the current reference
                        (P1-G04, 0.5315 -> 0.5048, -0.0267, ~14x the measured seed-noise std of
                        0.0019) and did not clear dfffd3da's original baseline either. A
                        genuine confound is flagged, not resolved: 20,000 steps fixed is ~142
                        passes over 36k records but only ~44 over 116k, so this result cannot
                        be read as clean evidence that more Stage 1 data does not help --
                        only that it did not help at this fixed compute budget (§28.3).
Next action:            Deferred to review per this task's explicit instruction ("do not
                        begin Experiment 2B automatically"). Two options recorded, neither
                        prescribed (§28.6): a passes-matched re-run (~64000 steps) as its own
                        declared experiment, or treat data-volume-at-fixed-compute as answered
                        and move to Phase 3/4.
Artifacts:              trainer/outputs/phase1/P2A-001/
```

## 29. Experiment 2B completion report (2026-07-20) — Stage 1, passes-matched to P1-G04

### 29.1 Step-count derivation

Per this task's instruction, the training-duration variable is *derived*, not hard-coded.
P1-G04 trained `steps_1=20,000` steps at `batch_size=256` over `n_1=36,000` training records:

```
passes_1 = steps_1 * batch_size / n_1 = 20000 * 256 / 36000 = 142.2222 passes
```

Experiment 2B's training set is `n_2b=116,000` records (identical composition to Experiment
2A: the original 36,000 + the same 80,000 new, non-overlapping Stage-1-only positions). Holding
passes constant instead of steps:

```
steps_2b = passes_1 * n_2b / batch_size = steps_1 * n_2b / n_1
         = 20000 * 116000 / 36000 = 64444.44 -> 64444 (rounded)
```

`64444 * 256 / 116000 = 142.2212` effective passes — within 0.001 of P1-G04's 142.2222.
LR (0.01), schedule shape (cosine), warmup (200 steps, unchanged in absolute terms per this
task's constraint), seed (42), and every other Phase 1-frozen field are otherwise identical to
P1-G04 and to Experiment 2A. Held-out set: the same 4,000 records as every experiment in this
roadmap (§28's byte-for-byte preservation methodology, reused unchanged — the same source
directory match was re-verified before this run).

Experiment ID: `P2B-001`. Wall-clock: 486.1s (vs. P1-G04's 183.7s and P2A-001's 156.3s — roughly
2.6-3.1x, in the range expected for ~3.2x the steps).

### 29.1a Architecture validation (Graphify): does `config.steps` couple into anything besides the schedule?

Requested as an isolation check on Experiment 2B's single-variable design; run against the
already-completed 2B result rather than strictly before it, since the request arrived
interleaved with 2B's execution (noted here for the record, not glossed over). A scoped
knowledge graph was built (`graphify`, AST-only, code corpus — no LLM extraction needed) over
`train.py`, `validator.py`, `train_candidate_net.py` (`combine_and_split`), `exporter.py`,
`quantizer.py`, and all three Phase 1/2A/2B driver scripts: 118 nodes, 212 edges, health check
clean (no dangling/missing/collapsed edges).

**Every read of `config.steps` inside `train()`** (confirmed by direct source inspection, cross-
checked against the graph's call edges):
1. `_learning_rate_at_step`'s `decay_steps = max(1, config.steps - config.warmup_steps)` —
   the cosine schedule's decay span. **Intended** — this *is* the schedule coupling the task
   asked to preserve (shape unchanged, span tracks total steps by construction of a cosine
   schedule).
2. `for step in range(config.steps)` — the training loop's iteration count. **Intended** — this
   is the independent variable itself, not a side effect of it.

**One derived, step-dependent quantity outside `train()` itself**: every calling script
(`phase1_optimization_sweep.py`, `phase1_experiment_2a.py`, `phase1_experiment_2b.py`) computes
`log_interval = max(1, steps // 20)` and passes it into `train()`, which uses it to gate both
diagnostic logging (`evaluate_held_out`/`calibration_report` calls) and checkpoint writes
(`train.py:237,275-279`). This *is* a real coupling from `steps` into checkpointing/evaluation
*sampling density* — a longer run gets its ~20 diagnostic/checkpoint points spaced further
apart in absolute step terms. **Classified as benign, not invalidating**: it changes how finely
the trajectory is sampled, not what evaluation/calibration compute (same held-out set, same
metrics, at every point that is sampled), and does not bias checkpoint selection, which still
picks the point of peak held-out correlation among whatever points were logged — the same
convention used for P1-G04 and P2A-001, so the three runs remain comparable.

**Export/quantization/dataset-loading: zero coupling, confirmed structurally.** The graph shows
no edge, direct or indirect, from `train()`/`TrainingConfig`/`config.steps` into `exporter.py`
or `quantizer.py`. Querying callers of `train()` in the graph returns exactly:
`phase1_experiment_2a_main`, `phase1_experiment_2b_main`, `phase1_optimization_sweep_run_cell`,
and (separately) `train_candidate_net_train_quantize_export` — the production end-to-end
pipeline, which *does* call `exporter.export()`/`quantizer.quantize()` after training, but is a
different caller entirely, never invoked by any Phase 1/2A/2B script. `combine_and_split`
(dataset loading/splitting) is called exactly once, before `steps` is even computed, and has no
edge to or from `TrainingConfig`/`train()`.

**Conclusion: no hidden coupling was found that invalidates Experiment 2B's single-variable
design.** `config.steps` affects only the optimization schedule (as intended) and, via a
documented and benign path, the trajectory's sampling density — not evaluation, calibration,
export, quantization, or dataset composition. §29.4's disentanglement (data effectiveness vs.
optimization budget vs. interaction) stands on this basis.

### 29.2 Result

**Held-out correlation: 0.5000**, at the selected checkpoint (step 12,887/64,443) — *lower*
than both P2A-001's fixed-compute result (0.5048) and the original `dfffd3da` baseline
(0.5044), and still well below the P1-G04 reference (0.5315, −0.0315). Held-out loss: 0.0989,
comparable to P2A-001's (0.0996) and still worse than both `dfffd3da` (0.0823) and P1-G04
(0.0797). **Giving the larger dataset proportionally more optimization exposure did not close
the gap — it produced an equal-or-lower peak.**

### 29.3 Trajectory — this is now unambiguous overfitting, not a plateau

| Step | Train corr. | Held-out corr. | Held-out loss |
|---|---|---|---|
| 3,221 | 0.5465 | 0.4761 | 0.1151 |
| 9,665 | 0.5892 | 0.4989 | 0.0996 |
| **12,887 (selected)** | 0.5939 | **0.5000** | **0.0989** |
| 19,331 | 0.6161 | 0.4967 | 0.1024 |
| 38,663 | 0.6355 | 0.4942 | 0.1096 |
| 64,443 (final) | 0.6394 | 0.4955 | 0.1112 |

Unlike P1-G04 (held-out correlation plateaus tightly, no decline) and unlike P2A-001 (held-out
correlation and loss both plateau flat, no decline), **P2B-001 shows a genuine early peak
followed by sustained decline**: held-out correlation rises to 0.5000 by step ~12,887 (~20% of
the run), then *declines* to a 0.489-0.496 floor for the remaining ~51,000 steps, while train
correlation keeps climbing the entire time (0.547→0.639) and train loss keeps falling toward
near-zero (0.057→0.0037). Held-out loss bottoms at the same point (0.0989) and then *rises*
12.4% relative and stays elevated. This is the textbook train-up/held-out-down overfitting
signature (§26.2's four-case guide, case 2) — the extra optimization exposure did not sit idle;
it was spent overfitting the 116,000-record training set past the point that generalizes.

**A striking, unplanned cross-experiment observation**: P1-G04 peaked at absolute step 15,999
(of 20,000, ~80% through its run); P2A-001 peaked at absolute step 10,999 (of 20,000, ~55%
through); P2B-001 peaked at absolute step 12,887 (of 64,444, only ~20% through). All three
peaks cluster in the same **~11,000-16,000 absolute-step** neighborhood regardless of total
training-set size (36k vs. 116k) or total step budget (20k vs. 64k). This is not proof of a
general law from n=3, but it is directly visible in the data and worth recording: whatever is
capping held-out correlation in this regime appears tied to absolute optimizer steps taken, not
to passes over the data or to dataset size — the opposite of what "more data needs more passes"
would predict (that hypothesis predicts a *later* peak for the larger dataset; the peak instead
came at a comparable, even slightly lower, absolute step).

### 29.4 Analysis — disentangling the three effects, as this task requires

**1. Data effectiveness (does more Stage 1 data raise the achievable held-out correlation
ceiling?).** No, not observed in either compute regime tested. The best held-out correlation
either dataset-scaling run achieved — 0.5048 (2A, fixed compute) or 0.5000 (2B, proportional
compute) — is essentially flat against the *original* `dfffd3da` result (0.5044), obtained from
just the original 36,000 records. Tripling the training data, under two different and
individually reasonable compute allocations, did not raise the ceiling above what the smaller
dataset already achieved.

**2. Optimization budget (does more compute alone explain the gap to P1-G04?).** No. P2B-001
received ~3.2x P2A-001's steps (and ~3.2x P1-G04's steps, matched in passes) and its peak
held-out correlation (0.5000) was not better than P2A-001's (0.5048) — if anything, marginally
worse. The additional budget's only clearly visible effect was to let the model overfit further
past its early peak (§29.3), not to find a better generalizing optimum. This directly answers
this task's required question:

**Experiment 2A's regression was *not* primarily explained by reduced optimization exposure.**
Restoring P1-G04's effective pass count did not recover P1-G04's correlation — it produced a
comparable-or-slightly-lower peak that then degraded with continued training. The steps/passes
confound flagged in §28.3 as unresolved is now resolved: it does not account for the gap.

**3. Interaction between data volume and optimization budget.** No evidence of a positive
interaction (more data + more budget together outperforming either alone) was observed; if
anything the interaction observed is mildly negative — the larger budget on the larger dataset
overfit sooner, in absolute-step terms, than P1-G04 did on the smaller dataset (§29.3's peak-
step comparison). The data most consistent with all three results (P1-G04, P2A-001, P2B-001) is
that **held-out correlation in this regime is capped well below P1-G04's 0.5315 by something
Stage 1 volume does not move** — most plausibly data quality/distribution or the label/loss
formulation already flagged as Phase 3/4 candidates (§24, §25), not sheer position count.

### 29.5 Metrics comparison

| | `dfffd3da` (historical) | P1-G04 (current reference) | P2A-001 (fixed compute) | P2B-001 (passes-matched) |
|---|---|---|---|---|
| Held-out correlation | 0.5044 | **0.5315** | 0.5048 | 0.5000 |
| Held-out loss | 0.0823 | **0.0797** | 0.0996 | 0.0989 |
| RMSE | 1279.88 | 1239.86 | 1274.30 | 1269.29 |
| Overall bias (cp) | −219.63 | −213.47 | −209.51 | −208.34 |
| Overall compression | 0.0630 | **0.1255** | 0.0696 | 0.0789 |

### 29.6 Calibration comparison (mate/cp split)

| | `dfffd3da` | P1-G04 | P2A-001 | P2B-001 |
|---|---|---|---|---|
| Mate bias (cp) | −1,726.6 | −1,661.1 | −1,703.4 | −1,697.0 |
| Mate compression | 0.0551 | 0.0996 | 0.0551 | 0.0615 |
| Cp bias (cp) | −18.0 | −19.8 | −9.6 | −9.2 |
| Cp compression | 0.0753 | 0.1599 | 0.0861 | 0.0995 |

Mate-labeled bias again barely moves across all four models (−1,661 to −1,727cp) regardless of
data volume or optimization budget — the fifth independent confirmation (§23, §27.5, §28.5,
now §29.6) that this is a structural loss/target-shape problem, unrelated to both optimization
schedule and Stage 1 volume. Compression tracks correlation's own ranking across all four
models, consistent with §23.4's correlation-caps-compression relationship holding here too.

### 29.7 Recommendation

**Conclude that Stage 1 data scaling provides little additional signal, even after proportional
optimization.** Neither the fixed-compute test (2A) nor the proportional-compute test (2B)
raised held-out correlation above the original 36,000-record baseline, and 2B's own trajectory
shows the extra data/compute combination overfitting rather than generalizing better. This is a
real, negative, single-variable-isolated result — not a confounded one — since the passes
confound that made 2A's result ambiguous has now been directly tested and ruled out as the
explanation (§29.4).

**Do not continue Stage 1 scaling to 1M positions.** The mechanism most consistent with all
data gathered so far (P1-G04, P2A-001, P2B-001, plus §23/§25's calibration findings) is that
correlation is capped by something Stage 1 volume does not address — data quality/label
distribution or the loss/target formulation. Per §24's roadmap ordering, the next candidates are
**Phase 3 (label quality)** or **Phase 4 (loss/K reformulation)**, not further Stage 1 volume
or Stage 2 scaling.

**Stopping here for review, as instructed — Phase 3 and Phase 4 have not been started.**

### 29.8 Learning log entry

```
## Experiment ID: P2B-001
Hypothesis:            Restoring P1-G04's effective pass count (142.22) over the larger
                        116,000-record Stage 1 training set, by scaling total steps
                        proportionally (20000 -> 64444, derived not hard-coded), recovers
                        held-out correlation to at or above P1-G04's 0.5315, resolving
                        Experiment 2A's steps/passes confound in data's favor.
Independent variable:   Training step count, derived from Stage 1 dataset size to hold
                        effective passes constant at P1-G04's value (142.22).
Controlled variables:    Same as Experiment 2A (§28's table) plus: LR=0.01, cosine schedule
                        shape, warmup=200 steps (absolute, unchanged), seed=42, held-out set
                        (identical 4,000 records), architecture/features/labels/loss/K/
                        export/quantization/evaluation pipeline all unchanged.
Expected outcome:       Held-out correlation at or above P1-G04's 0.5315 if Experiment 2A's
                        regression was primarily an optimization-exposure artifact.
Observed outcome:       0.5000 at step 12887/64443 -- lower than both P2A-001 (0.5048) and
                        the original dfffd3da baseline (0.5044), well below P1-G04 (0.5315).
                        Trajectory shows a genuine early peak (~20% through the run) followed
                        by sustained held-out correlation decline and held-out loss rise while
                        train correlation/loss keep improving -- classic overfitting, not a
                        plateau. Expectation not confirmed; confound ruled out, not resolved
                        in data's favor (§29.4).
Metrics:                See §29.5/§29.6.
Decision:               Not promoted -- correlation below both the current reference (P1-G04)
                        and the historical baseline (dfffd3da); fails §26.3's criteria.
Reason rejected:        Held-out correlation (0.5000) did not recover P1-G04's level even with
                        matched effective passes, and the run's own trajectory overfits past
                        an early peak -- direct evidence that reduced optimization exposure was
                        not the primary explanation for Experiment 2A's regression (§29.4).
Next action:            Per §29.7: do not scale Stage 1 further (no 2C/1M experiment). Roadmap
                        moves to Phase 3 (label quality) or Phase 4 (loss/K reformulation) for
                        review -- neither started in this report, per this task's instruction.
Artifacts:              trainer/outputs/phase1/P2B-001/
```

## 30. Stage 1 retirement and roadmap confidence (2026-07-20)

### 30.1 Stage 1 data scaling: experimentally exhausted

Two controlled, single-variable experiments tested whether increasing Stage 1 volume
(20,000 → 100,000 Lichess positions, 36,000 → 116,000 total training records) improves
held-out correlation beyond the promoted P1-G04 reference (0.5315):

- **Experiment 2A** (§28, fixed compute — steps held at P1-G04's 20,000): held-out correlation
  0.5048 — flat against the historical `dfffd3da` baseline (0.5044), a regression against
  P1-G04. Left one open question: whether the fixed step count under-trained the 3.2x larger
  set (~44 effective passes vs. P1-G04's ~142).
- **Experiment 2B** (§29, proportional compute — steps derived to match P1-G04's ~142.22
  effective passes, 20,000 → 64,444): held-out correlation 0.5000 — *lower* than 2A's
  fixed-compute result, with the trajectory showing genuine overfitting (early peak at ~20%
  through the run, then sustained decline) rather than a recovery. This directly answers 2A's
  open question: **the regression was not primarily an optimization-exposure artifact**
  (§29.4).

**Conclusion**: neither compute regime raised held-out correlation above what the original
36,000-record corpus already achieved. Stage 1 volume scaling (in this quality/representation)
is exhausted, not merely paused.

**Remaining uncertainty, stated plainly rather than glossed over**: three data points
(P1-G04, P2A-001, P2B-001) is a small sample for a general claim. The unplanned observation
that all three runs' held-out-correlation peaks cluster in the same ~11,000-16,000 absolute-step
range regardless of dataset size or total step budget (§29.3) is suggestive, not proven — it
was not the target of a dedicated experiment. Phase 3's own findings below (§32) surface a more
specific, higher-confidence explanation for *why* more Stage 1 volume didn't help: a small
number of extreme-magnitude label outliers in the Stage 1 source, not a generic "volume doesn't
matter" property of the data.

**Reopening Stage 1 requires new evidence, not repetition.** Neither re-running 2A/2B nor
scaling further (a hypothetical "2C" at 1M positions) is warranted without a specific, falsifiable
reason distinct from what's already been tested — e.g. a demonstrated fix to the label-quality
issues Phase 3 identifies (§32-§33), after which a *fresh* data-volume experiment would be
testing a different corpus, not repeating this one.

### 30.2 Roadmap confidence

| Phase | Status |
|---|---|
| Optimization (Phase 1) | ✓ **Confirmed** — P1-G04 promoted, +0.0271 held-out correlation over the noise floor (§27) |
| Stage 1 data scaling (Phase 2, Experiments 2A/2B) | ✓ **Rejected**, under both fixed-compute and proportional-compute regimes (§28, §29) |
| Stage 2 data scaling | Deferred (§24.4) — not attempted; Stage 1's rejection does not by itself imply Stage 2 would behave the same way (different label source, different quality profile per §32) |
| **Next investigation** | **Phase 3 — Label Quality** (§31-§34 below) |

## 31. Graphify discovery phase (Phase 3, mandatory)

Per this task's instruction, the repository knowledge graph was refreshed (`graphify update`,
code-only, no LLM cost) before reading any label-pipeline implementation file: 4,195 nodes,
8,175 edges, 550 communities, built from commit `5ce6336c`. `GRAPH_REPORT.md`/`graph.json`/
`graph.html` reviewed; the full-repo graph is dominated by `engine-core`/`engine-tuner` Java
communities (chess engine internals), with the NNUE trainer's Python pipeline as a much smaller
slice.

A targeted BFS trace ("Stockfish evaluation, position generation, FEN processing, dataset
generation, label conversion, mate-score conversion, centipawn normalization, dataset splitting,
validator, trainer inputs") surfaced every component this phase needs: `train()`,
`TrainingConfig` (`train.py`), `PositionRecord`/`PositionLabel`/`PositionMetadata`
(`contracts/dataset.py`), `StockfishLabelConfig`/`label_positions()` (`stockfish_label.py`),
`read_shard`/`write_shard` (`mmap_shard.py`), `TextDatasetProvider`/`StockfishLabeledProvider`,
`combine_and_split` (`train_candidate_net.py`), `evaluate_held_out`/`calibration_report`
(`validator.py`), and `Deep Research Report — D-8: Stage 2 Stockfish Labeling Driver`
(`docs/architecture/research/DR-D8-stockfish-labeling-driver.md`).

**Discovery outcome: no undocumented implementation paths found.** Every component the graph
surfaced is already named and cross-referenced in this document's §1 ("Established facts") or
in `DR-D8` directly — §1 already states, ahead of this phase, that Stage 2 has no
confidence/variance metric per position (line item on `nodes=25000`/`MultiPV` unpinned), that
mate scores flatten to a fixed `MATE_EQUIVALENT_CP=3000` regardless of distance, that
`deduplicate`/`phase_of`/`balance_phases` exist but are unused by the real training pipeline,
and that Stage 1 has zero quiet/tactical filtering. This phase's job (§32-§33) is to put numbers
on those already-identified gaps, not discover new ones — consistent with the instruction that
"reflected in the research document" already covers this ground; nothing required backfilling
before proceeding.

**One structural confirmation the graph made explicit** (traced via `contracts/dataset.py`, not
previously stated this plainly): `PositionMetadata` has exactly four fields —
`ply`/`game_id`/`search_depth`/`search_nodes` — no `multipv` field and no
`termination_reason` field exist anywhere in the schema. Their absence from Stage 1/Stage 2 data
is therefore not a gap in what was *recorded*; it is structurally impossible to record today
without a contract change. §32.4 relies on this distinction directly.

No hidden coupling, duplicated logic, or dead code beyond what §1 already documents (the unused
`deduplicate`/`phase_of`/`balance_phases` transforms) was found in this trace. No architecture,
feature-representation, optimizer, schedule, export, or quantization code was read for
implementation purposes beyond what this discovery pass and the audit script (§32) needed.

## 32. Phase 3: label-quality audit (2026-07-20)

Analytical only, per this task's constraint — no training occurred except one narrowly scoped
validation computation (§32.7), which re-evaluates the already-trained, already-promoted P1-G04
checkpoint on existing held-out predictions; it does not train, retrain, or modify any model.
Audited corpus: the exact 40,000-record union (Stage 1 20,000 Lichess + Stage 2 20,000
Stockfish-labeled) every promoted/rejected checkpoint in this roadmap (`dfffd3da`, P1-G04,
P2A-001, P2B-001) has trained against, split with `combine_and_split(seed=42)` into the same
36,000/4,000 train/held-out partition every prior experiment used. Script:
`trainer/scripts/phase3_label_audit.py`, full machine-readable output:
`trainer/outputs/phase3/label-audit.json` (gitignored, regenerate via the script).

### 32.1 Dataset composition

| | Stage 1 (Lichess) | Stage 2 (Stockfish) | Combined |
|---|---|---|---|
| Total positions | 20,000 | 20,000 | 40,000 |
| Exact-duplicate FENs | 0 (0.000%) | 2 (0.010%) | 2 (0.005%) |
| Cross-stage FEN overlap | — | — | 0 |
| Phase distribution | — | — | opening 14,835 / middlegame 12,212 / endgame 12,953 |
| Side-to-move | — | — | white 20,664 / black 19,336 |

**Train/held-out split verification**: `combine_and_split(seed=42)` produces 36,000
training / 4,000 held-out records; the held-out FEN set and training FEN set have **zero
overlap** — confirmed by direct set intersection, not inferred from the shuffle's construction.
This directly answers the highest-priority question for this audit: **no train/held-out
leakage exists** in any experiment in this roadmap. This is a clean, positive result, not a
gap — reported as such rather than searched for a problem that isn't there.

Duplication is negligible (2 records total, both within Stage 2, both discussed in §32.5) and
was already measured, combined-only, as a side effect of every `combine_and_split` call
(`report_dataset_quality`, #214); this section adds the per-source split those log lines don't
show. `deduplicate`/`phase_of`/`balance_phases` remain unused by the real training pipeline
(§1), confirmed still true — this phase does not change that, only measures around it.

### 32.2 Centipawn distribution

| | Stage 1 (Lichess) | Stage 2 (Stockfish) | Combined |
|---|---|---|---|
| n (cp-labeled) | 16,060 | 19,331 | 35,391 |
| Mean | 135.9 | −82.3 | 16.7 |
| Median | 18.0 | −20.0 | 1.0 |
| Std | 1,126.9 | 402.0 | 822.4 |
| Skew | 13.630 | −0.583 | 16.200 |
| Excess kurtosis | 228.185 | 10.226 | 376.639 |
| Min | −9,605 | −7,358 | −9,605 |
| Max | 20,000 | 1,413 | 20,000 |

Stage 1's skew/kurtosis are extreme — driven almost entirely by the outlier cluster identified
in §32.4, not by the bulk of the distribution (visible in the histogram below, which is
dominated by a normal-looking peak near 0). Stage 2 is far better-behaved: mildly left-skewed
(consistent with Stage 2 sourcing from `quiet-labeled.epd`'s Zurichess-derived positions, not
uniformly sampled), no extreme kurtosis.

```
Stage 1 (Lichess), clipped to [-1000, 1000] for readability:
  [   -1000,     -900)     58
  [    -900,     -800)     22
  [    -800,     -700)     45
  [    -700,     -600)    107
  [    -600,     -500)    146
  [    -500,     -400)    238 #
  [    -400,     -300)    204
  [    -300,     -200)    260 #
  [    -200,     -100)    441 ##
  [    -100,        0)   2705 #############
  [       0,      100)   8318 ########################################
  [     100,      200)    956 ####
  [     200,      300)    540 ##
  [     300,      400)    470 ##
  [     400,      500)    339 #
  [     500,      600)    298 #
  [     600,      700)    327 #
  [     700,      800)    178
  [     800,      900)    103
  [     900,     1000)    305 #

Stage 2 (Stockfish), clipped to [-1000, 1000]:
  [   -1000,     -900)    150 #
  [    -900,     -800)    218 ##
  [    -800,     -700)    657 ########
  [    -700,     -600)    963 ############
  [    -600,     -500)   1198 ###############
  [    -500,     -400)   1663 ####################
  [    -400,     -300)   1245 ###############
  [    -300,     -200)   1069 #############
  [    -200,     -100)   1049 #############
  [    -100,        0)   2574 ################################
  [       0,      100)   3190 ########################################
  [     100,      200)   1086 #############
  [     200,      300)    854 ##########
  [     300,      400)    832 ##########
  [     400,      500)   1011 ############
  [     500,      600)    692 ########
  [     600,      700)    443 #####
  [     700,      800)    274 ###
  [     800,      900)     85 #
  [     900,     1000)     78
```

Stage 1 is sharply peaked near 0 (its own cloud-eval sampling appears biased toward
near-balanced positions — 8,318/16,060 = 51.8% of its cp-labeled records fall in
[0,100)cp alone); Stage 2's cp values spread more evenly across a wider negative range,
consistent with `quiet-labeled.epd`'s own game-derived (not artificially rebalanced) position
mix and its own systematic negative bias (mean −82.3cp — plausibly a side-to-move or
Stockfish-perspective convention artifact, not yet explained; flagged as an open question, not
resolved here).

### 32.3 Mate distribution

| | Stage 1 (Lichess) | Stage 2 (Stockfish) | Combined |
|---|---|---|---|
| Mate labels | 3,940 (19.70%) | 669 (3.35%) | 4,609 (11.52%) |
| Positive (mover mates) | 3,327 | 368 | 3,695 |
| Negative (mover mated) | 613 | 269 | 882 |

Stage 1 carries almost 6x Stage 2's mate-label rate, and its positive/negative split is far more
lopsided (84.4% positive vs. Stage 2's 55.0%) — Stage 1's Lichess-cloud-eval source evidently
over-represents "found forced mate for the side to move" positions relative to Stage 2's
Stockfish-at-fixed-node-budget labeling, which more often reports a large-but-not-mate cp score
instead at the same node budget (a shallower search finds fewer forced mates, converts more of
them to "just" a large winning cp score — consistent with the two sources' different mate rates
being a search-budget effect, not a position-selection difference). Mate-depth distributions
(plies) for both sources are right-skewed with a long tail (Stage 1 reaching mate-in-64, Stage 2
capped at mate-in-12 — plausibly because Stage 2's 25,000-node budget cannot find or confirm
longer forced mates the way a stronger/deeper cloud-eval analysis can) — full per-depth counts
in `label-audit.json`.

### 32.4 Extreme evaluations — the headline finding

| | Stage 1 (Lichess) | Stage 2 (Stockfish) | Combined |
|---|---|---|---|
| p99(\|cp\|) | 1,496 | 928 | 1,028 |
| max(\|cp\|) | 20,000 | 7,358 | 20,000 |
| Count at max(\|cp\|) | 36 | 1 | 36 |
| IQR outliers (k=3) | 2,793 (17.39%) | 5 (0.03%) | 628 (1.77%) |

Stage 1's `max(\|cp\|)=20,000` is not a single anomaly — **36 distinct records carry exactly
`eval_cp=20000`**, and a further 33 carry exactly `eval_cp=9605`.

**Correction to this section, made during Experiment 3A's preparation (2026-07-20)**: the
original pass through this section stated "6 records fall below −5,000, none at a matching round
sentinel." That was wrong — a precise recheck (`Counter` over the full negative tail, not just
the minimum value) found **all 6 of those records are exactly `eval_cp=-9605`**, the exact
negative mirror of the already-identified `+9605` sentinel. The original check only inspected
the single minimum value (`-9605`) without checking its *frequency*, so it silently missed that
this one value repeats six times. Total confirmed sentinel-value records: **75**, not 69 —
36 at `+20000`, 33 at `+9605`, 6 at `-9605` (no `-20000` occurrences exist; confirmed by full
negative-tail enumeration, not merely the minimum). This correction does not change §32.4's
correlation-impact numbers below, which already included `-9605` in their exclusion mask when
originally computed — only this paragraph's prose count was stale. Preserved here rather than
silently fixed, per this project's standing practice of keeping the investigation's error record
visible, not just its final state.

Both values are implausible as
genuine centipawn evaluations (no chess engine's normal cp output lands on a suspiciously round
20,000, repeated identically 36 times across unrelated positions) and were traced directly to
Lichess's own cloud-eval JSON (`acquire_stage1_lichess.py`'s `_to_record()` reads `pv["cp"]`
verbatim, with no bounds check, no cross-validation against a null `mate` field, and no
clamping — confirmed by reading the acquisition script, not inferred). The FENs at `cp=20000`
are overwhelmingly large-material-advantage or forced-technical-win endgames (e.g. KBN vs. K),
consistent with a known category of Lichess cloud-eval behavior: an analyzing engine's internal
near-mate score occasionally surfaces as a very large plain `cp` value rather than a `mate`
field. **This is a genuine Stage 1 source-data defect, not a parsing bug in this repository's
own code** — `_to_record()` correctly reports what Lichess's API returned; the defect is
upstream, and this repository currently has no sanity check that would catch it.

**Quantified impact on the held-out correlation metric** (a narrowly scoped validation
computation, per this task's own allowance — inference only against the already-trained,
already-promoted P1-G04 checkpoint, no retraining): the held-out set (4,000 records) contains
only **8 records**
at these sentinel values (±9605, +20000 — 0.2% of the set; this mask already included `-9605`
when originally computed, so the numbers below are unaffected by the count correction above).
Held-out Pearson correlation:

| | Correlation | n |
|---|---|---|
| Full held-out set (as reported everywhere else in this roadmap) | 0.5315 | 4,000 |
| Excluding the 8 sentinel-value (±9605/+20000) records | **0.5931** | 3,992 |
| Excluding all 12 non-mate records with \|target_cp\| > 5,000 | **0.5953** | 3,988 |

**Removing 0.2-0.3% of the held-out set raises the reported held-out correlation by
+0.060 to +0.064 — more than double Phase 1's entire optimization gain (+0.0271,
`dfffd3da`→P1-G04, §27).** This is a mechanical consequence of Pearson correlation's known
sensitivity to extreme values inflating the target's variance term: the model (correctly) never
predicts anywhere near ±20,000cp for any position, so these 8-12 records contribute an
enormous, label-artifact-driven residual that the correlation statistic weights heavily,
independent of how well the model ranks the other 99.7-99.8% of positions. **This is the
single most information-dense finding of this audit** — see §33's ranked hypotheses.

### 32.5 Noise investigation

- **Repeated FENs with different labels**: the corpus's only 2 duplicate FENs (both within
  Stage 2, §32.1) **both carry different labels on their two occurrences** — i.e. re-running
  Stockfish on the same position at the same nominal node budget did not reproduce the same
  eval. Direct, if small-n (n=2), evidence that Stage 2's single fixed-node search is not even
  self-consistent — supporting the label-noise-floor hypothesis §6's Experiment 5 was already
  designed to test (re-label 1,000 positions at two node budgets, measure the spread).
- **Shallow-search artifacts / search effort variance**: Stage 2's `search_nodes` is populated
  for 19,967/20,000 records (99.8%) and is **tightly clustered around the 25,000 budget**
  (median 25,015; p5-p95 range 25,000-25,034) for the large majority — search effort is
  materially consistent for most of the corpus, correcting an over-broad initial read of the
  raw distinct-value range (490-25,176). A small tail deviates meaningfully: 0.75% of records
  (150/19,967) searched fewer than 10,000 nodes, 0.25% (50/19,967) fewer than 5,000 — plausibly
  near-immediate terminations (forced mate found instantly, single legal move, or similar) —
  a small, quantifiable, non-uniform-effort tail rather than a pervasive problem.
- **Search depth reached**: populated for all 20,000 Stage 2 records (structurally absent from
  Stage 1, §31's confirmed schema point — Stage 1 has no search_depth field populated at all,
  0/20,000). Depth ranges 0-245, mean 17.22, median 14 — the wide upper tail (Stockfish's
  reported `depth` includes check/capture extensions, which can exceed nominal ply count in
  forcing lines) is expected UCI behavior, not an anomaly. 32 records report `search_depth=0`
  (immediate resolution, e.g. an already-mate/stalemate position or a position with one legal
  reply).
- **Mate-score discontinuities**: `target_cp()`'s flat `MATE_EQUIVALENT_CP=3000` (§1, established
  fact) sits *below* Stage 1's 20,000/9,605 cp-labeled outliers — meaning some records labeled
  as ordinary cp evaluations already exceed, in raw magnitude, the flattened value assigned to
  genuine forced mates. Because `texel_sigmoid(cp, K=2.773456)` saturates by ≈288cp (§23.7,
  established), both 3,000 and 20,000 saturate to the same ≈1.0 training-loss target — this
  discontinuity is very likely harmless for the texel-sigmoid loss itself, but directly harmful
  for the raw-cp Pearson correlation metric, per §32.4.
- **Clipping artifacts**: no evidence of a hard clip boundary in either source (no repeated
  cluster at a single bound other than the two Stage 1 sentinel values already discussed, which
  are a source-data artifact, not a pipeline clip).
- **Evaluation quantization**: Stage 1 cp values are divisible by round numbers far above a
  uniform-random baseline (divisible by 50: 15.2% vs. 2.0% baseline, a 7.6x enrichment;
  divisible by 25: 17.4% vs. 4.0%, 4.4x). Stage 2 tracks its uniform baseline closely (divisible
  by 50: 3.8% vs. 2.0% baseline, divisible by 10: 11.7% vs. 10.0%) — consistent with Stage 2
  reporting raw, unrounded Stockfish centipawn output and Stage 1's cloud-eval source applying
  some rounding/quantization not present in Stage 2. A previously undocumented, source-specific
  data-quality difference.
- **Duplicate leakage / train-val contamination**: none found (§32.1) — the held-out set used
  by every promoted/rejected checkpoint in this roadmap is clean.

### 32.6 Correlation by label region — is a specific region harder to learn?

Bucketed error using the promoted P1-G04 checkpoint's predictions on the 4,000-record held-out
set (the same P1-G04-inference validation computation used in §32.4; extends §5.1/§27/§28/§29's existing mate/cp/phase
calibration split with the magnitude and search-depth buckets this phase's checklist asks for):

| Bucket | n | MAE (cp) | RMSE (cp) | Bias (cp) |
|---|---|---|---|---|
| Near-zero (\|target\| ≤ 25cp, non-mate) | 873 | 55.2 | 76.4 | +9.5 |
| Moderate (25-200cp, non-mate) | 1,141 | 72.4 | 96.6 | −3.8 |
| Large (200-800cp, non-mate) | 1,412 | 331.3 | 365.3 | +53.0 |
| Extreme (>800cp, non-mate) | 102 | 2,245.8 | 4,715.3 | −1,456.3 |
| Mate (all) | 472 | 2,784.8 | 2,791.1 | −1,661.1 |
| Mate favoring mover | 379 | 2,768.5 | 2,774.2 | −2,768.5 |
| Mate against mover | 93 | 2,851.6 | 2,858.8 | +2,851.6 |
| Stage 2, shallow depth-reached (≤ median 14) | 1,210 | 185.8 | 249.1 | +41.4 |
| Stage 2, deep depth-reached (> median 14) | 784 | 551.7 | 880.6 | +16.0 |

Error grows monotonically and sharply with label magnitude — near-zero/moderate positions are
predicted well (MAE 55-72cp), large positions much worse (MAE 331cp), extreme non-mate
positions dramatically worse (MAE 2,246cp, with a severe −1,456cp bias — the net systematically
under-calls extreme wins), and mate positions worst of all with a large, direction-symmetric
compression toward zero (bias flips sign but stays ≈2,800cp in magnitude either direction) —
the same compression effect §23/§27.5/§28.5/§29.6 have already established, now quantified per
magnitude bucket for the first time rather than only per mate/cp split.

**Stage 2 depth-reached bucket is a genuine new observation, reported with its caveat**: MAE is
3x higher for deep-depth-reached positions (551.7) than shallow-depth-reached ones (185.8). The
most likely explanation is a confound with the magnitude effect just described, not an
independent signal: for a fixed node budget, reaching greater search depth correlates with
narrower branching factor (forced sequences, sparse endgames) — the same category of position
that tends to produce more decisive, extreme evaluations. This bucket is plausibly re-detecting
the extreme-magnitude effect through a different axis, not a new, independent cause — flagged as
such rather than overclaimed.

### 32.7 Search information availability (checklist item, explicit)

| Field | Stage 1 | Stage 2 |
|---|---|---|
| Search depth | Absent (0/20,000 populated) | Present (20,000/20,000; range 0-245, mean 17.2, median 14) |
| Search nodes | Absent (0/20,000 populated) | Present (19,967/20,000; tightly clustered near the 25,000 budget, small early-termination tail) |
| MultiPV | **Structurally unavailable** — no field in `PositionMetadata` (§31) | Same — structurally unavailable |
| Search termination reason | **Structurally unavailable** — no field in `PositionMetadata` (§31) | Same — structurally unavailable |

Stage 1's absence of search metadata is expected and consistent with its source (a public
cloud-eval dump, not a search this repository ran) — not a defect, just a hard limit on what
can ever be known about Stage 1 label provenance without switching sources. Stage 2's
`search_depth`/`search_nodes` population confirms `mmap_shard.py`'s D-8-era extension (§1) is
working as designed. MultiPV and termination-reason are absent from the *schema*, not merely
this dataset — extending `PositionMetadata` would be required before either could ever be
recorded, for any future stage.

## 33. Ranked hypotheses and Experiment 3A recommendation

No fixes are implemented in this section — hypotheses and a recommendation only, per this
task's explicit instruction. Ranked by evidence strength × expected impact ÷ implementation
cost, not by which is most novel.

| # | Hypothesis | Evidence | Confidence | Expected impact | Cost | Recommended experiment |
|---|---|---|---|---|---|---|
| 1 | **Stage 1's 75 extreme-magnitude label outliers (sentinel-like `eval_cp` ∈ {−9605, 9605, 20000}, source-side Lichess cloud-eval defect, §32.4) suppress the held-out correlation metric and plausibly distort training** | Directly measured: excluding 8 held-out records (0.2%) raises measured correlation +0.060 to +0.064 — over 2x Phase 1's entire optimization gain. Traced to a real, upstream Lichess API behavior, not a bug in this repo's parsing | **High** | Potentially large on the *reported metric*; unknown but plausibly positive on *learned model quality* if the same records also distort training gradients (untested — see caveat below) | Low — filter/clip at ingestion, no architecture/loss change | **§33.1, Experiment 3A (recommended)** |
| 2 | Extreme-magnitude and mate-labeled evaluations are severely compressed/miscalibrated (§32.6's monotonic magnitude-bucketed error, reconfirming §23/§27.5/§28.5/§29.6) | Very strong, independently reconfirmed five times across four different experiments now, at a finer magnitude resolution than before | High (already established, not new this phase) | Likely the dominant remaining lever for the mate-bias problem specifically, distinct from #1 | Medium-high — requires a loss/target-formulation change (WDL blend or mate-distance-aware target), not a data-cleaning fix | Phase 4's existing scope (§6 Exp 6, WDL blend) — not a Phase 3A candidate, but this audit strengthens the case for prioritizing Phase 4 after 3A |
| 3 | Stage 2's single fixed-node-budget search (no re-search, MultiPV unpinned, §1) produces non-trivial label noise/variance | The corpus's only 2 duplicate FENs (both Stage 2) carry *different* labels on their two occurrences — 100% inconsistency rate, but n=2, not statistically decisive on its own | Medium — plausible mechanism, weak direct sample size from this corpus alone | Unknown, bounded by the experiment's own success criteria | Low — existing `stockfish_label.py` config knob, minutes to run | §6's pre-existing **Experiment 5** (label-noise floor: re-label 1,000 positions at 25k vs. 50k nodes) — already scoped, reprioritize rather than re-design |
| 4 | Stage 1 cp values carry systematic quantization/rounding not present in Stage 2 (§32.5) | Measured: divisible-by-50 enriched 7.6x over a uniform baseline in Stage 1, near-baseline in Stage 2 | Medium — real and measured, impact on training quality untested | Low-medium — near-zero-region predictions are already the model's best-performing bucket (§32.6), so rounding here is unlikely to be a dominant lever | Low to test, but no clear fix without a source change | Lower priority; could be folded into 3A's data-cleaning pass as a secondary check, not a standalone experiment |
| 5 | Stage 1's near-equality-heavy sampling (52.5% of cp-labeled records within ±50cp, §32.2) limits usable training signal | Measured directly | Low confidence this is a *quality* problem — near-zero is the model's best-predicted bucket (§32.6), so this looks like a sampling-distribution property of real online games, not noise | Low | — | Not recommended as a standalone experiment; informational |
| 6 | Missing MultiPV/termination-reason metadata (structurally absent from `PositionMetadata`, §31/§32.7) limits diagnosability of label quality | Confirmed absent from the schema; no evidence yet that this specifically caps correlation (no way to test until the schema is extended) | Low confidence of *impact* (high confidence of *absence*) | Unknown — not yet testable | Medium-high — contract + shard-format extension | Not actionable as a cheap Phase 3A pick; revisit only if #1/#3 are exhausted and the label-noise question is still open |

**A live, non-default outcome remains explicitly on the table**: if Experiment 3A (below) finds
the outlier fix does not move held-out correlation once the model is actually retrained on
cleaned data — not just re-evaluated post-hoc — that would be a real, reportable negative
result pointing the roadmap back toward Phase 4 (loss/target formulation) or Phase 5
(capacity/regularization, ADR-001's plain-768 ceiling) rather than further label work. Nothing
in this ranking presupposes hypothesis #1 will pan out under retraining.

### 33.1 Recommended Experiment 3A (not started — analysis and recommendation only)

**Objective**: determine whether removing Stage 1's extreme-magnitude label outliers changes
held-out correlation *under retraining*, not merely under post-hoc evaluation exclusion (which
§32.4 already measured and which only proves the correlation *metric* is outlier-sensitive — it
does not yet show that training without these labels produces a better model).

**Independent variable**: Stage 1 outlier filtering — remove or clip records with
`eval_cp` beyond a defined sanity bound (e.g. the two confirmed sentinel values 9605/20000
specifically, or a broader principled bound such as `|eval_cp| > 3000` non-mate, chosen and
declared before running, not tuned post-hoc) — applied to **both** the training set and the
held-out set (held-out must be cleaned too, or the comparison is apples-to-oranges against
P1-G04's un-cleaned held-out correlation).

**Controlled variables**: P1-G04's frozen optimization schedule (§27.11, unchanged per this
task's constraint), architecture, feature representation, loss function, export, quantization,
Stage 2 (untouched — the defect is Stage-1-specific), split seed=42 for whatever records remain
after filtering.

**Success criteria**: held-out correlation, measured on the *cleaned* held-out set, for the
retrained-on-cleaned-data model exceeds P1-G04's correlation measured the same way (i.e.
against the reference numbers this audit already computed: 0.5931-0.5953, §32.4) by more than
the measured noise floor (std≈0.0019, §27.2) — not against the uncleaned 0.5315, which would
conflate the metric-cleaning effect (already known) with any real training-quality effect
(not yet known).

**Estimated cost**: Low — same schedule, ~36,000 records (minus ~62-69 filtered, negligible
volume change), comparable wall-clock to P1-G04 (~180s).

**This report recommends Experiment 3A as the single highest-information next experiment, and
does not run it. Per this task's explicit instruction: waiting for review before proceeding.**

## 34. Graphify validation pass (2026-07-20)

Per this task's instruction, Graphify was refreshed after the analysis above to confirm no
architectural drift was introduced and documentation references still resolve.

```
$ graphify update
Re-extracting code files in /home/coeusyk/projects/chess-engine (no LLM needed)...
  AST extraction: 32/32 uncached files (100%) [16 workers]
[graphify] backed up curated graph (5 files) -> graphify-out/2026-07-20/
[graphify watch] Rebuilt: 4228 nodes, 8251 edges, 552 communities
```

**Verification checklist**:
- **No architectural changes introduced**: confirmed — this phase added exactly one new file
  (`trainer/scripts/phase3_label_audit.py`, an analysis script under `scripts/`, structurally
  identical in kind to the existing `phase1_optimization_sweep.py`/`phase1_experiment_2a.py`/
  `phase1_experiment_2b.py` scripts already in the graph) and one research-doc update. No file
  under `trainer/trainer/model`, `trainer/trainer/dataset`, `trainer/trainer/export`,
  `trainer/trainer/quantization`, or `engine-core`/`engine-tuner`/`engine-uci` was modified.
  Node/edge count grew (4,195→4,228 nodes, 8,175→8,251 edges) by exactly the amount a single
  ~370-line analysis script with no cross-module architectural change would add.
- **Documentation references remain correct**: re-queried the refreshed graph directly
  (`graphify query "phase3_label_audit.py"`) rather than assuming it — confirms
  `phase3_label_audit.py` correctly resolves edges to `combine_and_split()`/
  `report_dataset_quality()`/`_load_all()` (`train_candidate_net.py`), `TextDatasetProvider`/
  `StockfishLabeledProvider`, `PositionRecord` (`contracts/dataset.py`), `deduplicate()`
  (`transform.py`), `encode_batch()` (`batching.py`), and `target_cp()` (`train.py`) — every
  dependency this phase's script actually uses, correctly reflected, nothing orphaned or
  misattributed. `DR-D8-stockfish-labeling-driver.md`, `mmap_shard.py`, `stockfish_label.py`,
  and `validator.py`'s `evaluate_held_out`/`calibration_report` all still resolve to the same
  locations this document already cited before this phase.
- **Label-generation pipeline documentation matches implementation**: confirmed. §31's schema
  claim (`PositionMetadata` has exactly four fields, no `multipv`/`termination_reason`) was
  read directly from `trainer/contracts/dataset.py`, cross-checked against the graph's node
  list for that file, and matches.
- **Newly discovered relationships reflected in the research document**: the two-sentence
  finding this phase's own discovery pass produced beyond what §1 already stated — the exact
  `PositionMetadata` field enumeration (§31) — is now recorded there. No other new relationship
  requiring documentation was found (§31.1's discovery outcome already stated this).

**Architectural observations, summarized**: the trainer's label pipeline is small and already
well-decomposed (`DatasetProvider` → `Transform` → `combine_and_split` → training), matching
`docs/architecture/NNUE_TRAINER_ARCHITECTURE.md`'s own design — this phase's findings are data-
quality issues within that pipeline (a source-data defect, a metric-sensitivity property, a
schema gap), not structural or architectural problems with the pipeline itself. Nothing found
in this phase motivates a pipeline redesign.

## 35. Experiment 3A: Stage 1 sentinel-value filtering (2026-07-20)

Approved with one refinement: determine whether filtering Stage 1's confirmed sentinel-value
labels (§32.4, corrected count) improves the *learned model*, not merely the *reported metric*
— §32.4's post-hoc exclusion already proved the latter; this experiment tests the former, which
requires retraining. Script: `trainer/scripts/phase3_experiment_3a.py`. Experiment ID: `P3A-001`.

### 35.1 Graphify discovery (pre-implementation)

Per this task's instruction, `graphify update` ran before any implementation: 4,228→4,238 nodes
(the prior state, post-Phase-3-audit), 8,251→8,276 edges. A BFS trace from
`phase3_experiment_3a.py` confirmed its only dependencies are `combine_and_split()`,
`train()`/`TrainingConfig`, `evaluate_held_out()`/`calibration_report()`, and `NnueNet` —
the same primitives every prior experiment script in this roadmap reuses unmodified. No edge to
`exporter.py`, `quantizer.py`, `engine-core`, or `engine-tuner` exists from the new script,
confirming the filtering implementation is isolated to the Stage 1 dataset-generation path, with
no unintended dependency into architecture, export, quantization, or inference code — matching
this task's explicit constraint list.

### 35.2 Filtered Stage 1 dataset

**Exact filtering criterion (no additional rules introduced)**: exclude Stage 1 records whose
`eval_cp` is exactly one of the three confirmed sentinel values from §32.4's corrected count —
`{-9605, 9605, 20000}`.

| | Value |
|---|---|
| Original Stage 1 count | 20,000 |
| Removed | 75 (36 at `+20000`, 33 at `+9605`, 6 at `-9605`) |
| Removed, percentage | 0.375% |
| Kept | 19,925 |

**Justification**: these are the exact records §32.4 traced to a confirmed Lichess cloud-eval
API defect (implausibly round, exactly-repeating `cp` values, no matching `mate` field, not a
parsing bug in this repository's own `acquire_stage1_lichess.py`). No other Stage 1 record, no
Stage 2 record, and no broader magnitude threshold is touched — the filter is exactly the named
defect and nothing more, per this task's "no additional filtering rules" constraint. A standalone
filtered artifact was written to `outputs/datasets/stage1-lichess-filtered/` (gitignored,
regenerate via the script) as the literal deliverable; it is not itself used for the training
split below (§35.3 explains why).

### 35.3 Leakage-safe split methodology

Filtering Stage 1 *before* calling `combine_and_split` would re-shuffle a differently-sized pool
(19,925 instead of 20,000) and produce a *different* held-out set membership than P1-G04's —
the same category of confound Experiment 2A's held-out-set-preservation methodology (§28) was
designed to avoid. Worse here: it would risk actual leakage, since some of a re-split model's
training records could then coincide with P1-G04's original held-out set, invalidating a
same-benchmark comparison between the two models entirely.

Instead: `combine_and_split(seed=42)` was called once, reproducing P1-G04's *exact* original
36,000/4,000 split (byte-identical to every prior experiment in this roadmap). The sentinel
filter was then applied to the **already-split training list only**:

| | Count |
|---|---|
| P1-G04 original training set | 36,000 |
| Sentinel records removed from training | 67 |
| Model B's filtered training set | 35,933 |
| P1-G04 original held-out set (Benchmark v1) | 4,000 |
| Sentinel records removed from held-out (for Benchmark v1-clean) | 8 |
| Benchmark v1-clean | 3,992 |

This guarantees Benchmark v1 stays byte-identical to P1-G04's original held-out set (valid for
evaluating both models with zero new leakage risk — Model B's training set is a strict subset of
P1-G04's, so nothing new could have entered it) while the only difference between the two
models' training data is exactly the 67 removed records — a strict, single-variable filter.

### 35.4 Training

P1-G04's frozen schedule (§27.11) held exactly constant: steps=20,000, LR=0.01, cosine schedule,
warmup=200, seed=42, batch_size=256. The only independent variable is the 67-record training-set
filter. Wall-clock: 151.0s (comparable to P1-G04's 183.7s and Experiment 3A's slightly smaller
training set).

**Training curves** (full trajectory in `outputs/phase3/P3A-001/training_diagnostics.json`):

| Step | Train loss | Held-out loss | Train corr. | Held-out corr. |
|---|---|---|---|---|
| 999 | 0.0980 | 0.1034 | 0.5645 | 0.4603 |
| 2,999 | 0.0396 | 0.0767 | 0.6450 | 0.5105 |
| 4,999 | 0.0245 | 0.0735 | 0.6759 | 0.5229 |
| 8,999 | 0.0063 | 0.0759 | 0.6943 | 0.5266 |
| 12,999 | 0.0038 | 0.0785 | 0.6989 | 0.5257 |
| **16,999 (selected)** | 0.0027 | 0.0797 | 0.7036 | **0.5290** |
| 19,999 (final) | 0.0033 | 0.0797 | 0.7035 | 0.5287 |

Shape matches P1-G04's own trajectory closely (§27.3): rises then plateaus in a tight band from
roughly step 9,000 onward, no decline (unlike P2B-001's overfitting pattern, §29.3) — the
67-record training-set reduction (0.19% of the training set) did not change the qualitative
training dynamics at all, only shifted the selected peak from step 15,999 (P1-G04) to step
16,999 (Model B), both well within the same plateau band.

### 35.5 2×2 evaluation matrix

Both models evaluated on both benchmarks, held-out-eval computation identical for both (no
retraining involved in producing this table):

| Model | Benchmark | n | Correlation | RMSE | Bias | Compression |
|---|---|---|---|---|---|---|
| P1-G04 (Model A) | v1 (original) | 4,000 | 0.5315 | 1,239.9 | −213.5 | 0.1255 |
| P1-G04 (Model A) | v1-clean | 3,992 | **0.5931** | 1,030.2 | −189.4 | 0.1481 |
| Filtered Retrain (Model B) | v1 (original) | 4,000 | 0.5290 | 1,240.4 | −215.2 | 0.1259 |
| Filtered Retrain (Model B) | v1-clean | 3,992 | **0.5933** | 1,030.1 | −191.1 | 0.1487 |

**Reading the matrix along its two axes separately is the whole point of this design:**

- **Across benchmarks (v1 → v1-clean), holding the model fixed**: both models jump by
  essentially the same amount — Model A: +0.0616 correlation, −209.7 RMSE; Model B: +0.0644
  correlation, −210.3 RMSE. This is the metric-sensitivity effect §32.4 already found, now
  confirmed to apply identically regardless of which model produced the predictions.
- **Across models (A → B), holding the benchmark fixed**: on v1-clean (the fair,
  apples-to-apples comparison — both models scored on data neither trained on with sentinel
  values already removed from the benchmark itself), the difference is **+0.0002 correlation,
  −0.1 RMSE, −1.7 bias** — an order of magnitude below the measured seed-to-seed noise floor
  (std≈0.0019, n=3, §27.2). On v1 (original), Model B is actually marginally *lower*
  (−0.0025 correlation) than Model A, also within noise. **Removing the model's own influence
  from training data it never saw the sentinel values in produces no detectable change in how
  either model performs on either benchmark.**

### 35.6 Calibration analysis

Mate/cp split (`calibration_report`, same convention as §27.5/§28.5/§29.6/§32.6):

| | Model A on v1 | Model A on v1-clean | Model B on v1 | Model B on v1-clean |
|---|---|---|---|---|
| Mate bias (cp) | −1,661.1 | −1,661.1 | −1,663.6 | −1,663.6 |
| Mate compression | 0.0996 | 0.0996 | 0.0998 | 0.0998 |
| Cp bias (cp) | −19.8 | +7.9 | −21.4 | +6.4 |
| Cp compression | 0.1599 | **0.3016** | 0.1607 | **0.3033** |

**Mate calibration is completely unaffected by the benchmark choice** (identical to four
decimal places between v1/v1-clean, for both models) — expected, since every sentinel-value
record is cp-labeled, not mate-labeled; the mate bucket (n=472) is untouched by the filter
either way. **Cp-labeled compression nearly doubles** (0.16→0.30) purely from removing 8 records
out of 3,528 cp-labeled held-out records — the compression-ratio metric (`std(predicted) /
std(target)`) is exactly as outlier-sensitive as Pearson correlation, for the same reason
(`target`'s variance is dominated by a handful of ±9,605/20,000cp values). Both models again
move together, near-identically, across every cell.

### 35.7 Analysis: which explanation fits?

Per this task's four possibilities:

- **A. Filtering improves only the evaluation metric.** ✅ **Best fit.** Every metric in §35.5
  and §35.6 that changes does so almost identically for Model A *and* Model B when the benchmark
  changes (v1→v1-clean), and barely changes at all for either benchmark when the *model* changes
  (A→B). The effect tracks the benchmark, not the model.
- **B. Filtering improves the learned function.** ❌ Not supported. Model B vs. Model A on the
  same benchmark differs by amounts (+0.0002 to −0.0025 correlation) an order of magnitude below
  the established noise floor — statistically indistinguishable from re-running P1-G04 with a
  different training seed (§27.2's own measured std≈0.0019).
- **C. Filtering improves both.** ❌ Not supported — requires B, which the evidence rejects.
- **D. No meaningful improvement.** ❌ Incomplete as a full answer — the *metric* improvement
  (+0.06 correlation, compression nearly doubling) is real, large, and reproduced under
  retraining, not just under the original post-hoc exclusion. "No improvement" would
  mischaracterize a genuine, substantial, reproducible finding as null.

**Conclusion: A.** Training-set filtering of these 67 records changes essentially nothing about
what the model learns (§35.4's trajectory is qualitatively identical to P1-G04's; §35.5's
model-vs-model deltas are noise-floor-sized). The correlation/compression jump this roadmap has
now measured twice (§32.4's post-hoc exclusion, this section's retrain-and-reevaluate) is
entirely a property of *which held-out records the metric is computed over*, not of which model
produced the predictions being scored. This is consistent with §32.4's own mechanism
explanation (Pearson correlation and `std(predicted)/std(target)` are both sensitive to a
handful of extreme-target-variance points, independent of prediction quality on the rest of the
data) — the retrain confirms the mechanism, it does not add a second, independent effect.

### 35.8 Benchmark versioning

Per this task's explicit instruction, the historical benchmark is **not** replaced:

| Benchmark | Definition | Role |
|---|---|---|
| **v1** | P1-G04's original 4,000-record held-out set (unchanged since `dfffd3da`, §1) | **Historical benchmark — all comparisons in §27/§28/§29 and every prior roadmap entry continue to use v1 unless explicitly noted otherwise.** |
| **v1-clean** | The same 4,000 records with the 8 confirmed sentinel-value records removed (3,992 records) | New, introduced this section — for evaluating whether a model's *ranking quality* on non-defective labels differs, not a replacement for v1 |

**Stated clearly, per this task's instruction**: every held-out correlation number reported
before this section (`dfffd3da`'s 0.5044, P1-G04's 0.5315, P2A-001's 0.5048, P2B-001's 0.5000)
was measured against **Benchmark v1** and remains valid, comparable, and unchanged. Nothing in
this section retroactively revises those numbers. Any future reference to a "0.59-ish"
correlation number must specify **v1-clean** explicitly — it is not comparable to any v1 number
without that qualification, precisely because §35.7 established the difference is a benchmark
property, not a model property.

### 35.9 Recommendation: should label cleaning become permanent pipeline policy?

**Recommended: yes, for the specific, narrow, confirmed defect — not as a general policy.**
Filtering these 75 confirmed sentinel-value records (or an equivalent bounds check in
`acquire_stage1_lichess.py`'s `_to_record()`, catching this exact Lichess API pattern before it
enters any future Stage 1 pull) costs nothing in demonstrated model quality (§35.7) and removes
a data artifact that would otherwise distort every future correlation/compression measurement
against Stage-1-derived held-out data, including in experiments that have nothing to do with
label quality (§32.4's finding would have silently confounded a future Phase 4/5 experiment's
own held-out evaluation if left unaddressed). This is a low-cost, well-evidenced, narrowly
scoped change, unlike a general "filter anything unusual" policy, which this experiment does not
justify and which would risk discarding genuine, informative extreme evaluations.

**What this recommendation is not**: it is not a recommendation to broaden the filter, to
similarly filter Stage 2, or to treat the extreme-magnitude/mate-compression problem (§33's
hypothesis #2, the much larger effect on RMSE/bias) as solved — that remains Phase 4's scope,
unaffected by this experiment's null result on the learned function.

**Per this task's explicit instruction: no further Phase 3 experiments begin automatically.
Waiting for review.**

### 35.10 Graphify validation (post-implementation)

```
$ graphify update
Re-extracting code files (no LLM needed)...
  AST extraction: 34/34 uncached files (100%)
[graphify] backed up curated graph -> graphify-out/2026-07-20/
[graphify watch] Rebuilt: 4238 nodes, 8276 edges, 552 communities
```

**Verification checklist**:
- **Only Stage 1 generation changed**: confirmed — this experiment added exactly one new
  script (`phase3_experiment_3a.py`) and one gitignored dataset artifact
  (`outputs/datasets/stage1-lichess-filtered/`). No file under `trainer/trainer/model`,
  `trainer/trainer/dataset` (the shared providers/transforms themselves, as opposed to a new
  script that calls them), `trainer/trainer/export`, `trainer/trainer/quantization`, or
  `engine-core`/`engine-tuner`/`engine-uci` was modified.
- **No pipeline regressions**: re-queried the refreshed graph
  (`graphify query "phase3_experiment_3a.py Stage 1 filtering dependencies"`) — confirms the
  new script's only edges are to `combine_and_split()`, `train()`/`TrainingConfig`,
  `evaluate_held_out()`/`calibration_report()`, and `NnueNet`, identical in kind to every prior
  experiment script's dependency footprint (§31's discovery trace). No edge into `exporter.py`,
  `quantizer.py`, or any Java engine code.
- **Documentation remains synchronized**: this section (§35) and §32.4's correction (§32.4)
  are the only research-doc changes; both are reflected in the graph as document nodes with
  correct source locations, re-verified via the query above rather than assumed.

**Architectural observations**: none beyond what §31/§34 already stated — the Stage 1 filtering
implemented here required zero changes to `DatasetProvider`, `Transform`, or any contract type,
confirming the pipeline's existing decomposition already supports this kind of data-quality
intervention without modification, exactly as designed.

### 35.11 Learning log entry

```
## Experiment ID: P3A-001
Hypothesis:            Retraining P1-G04's frozen schedule on a Stage 1 training set with the
                        75 confirmed sentinel-value records (eval_cp in {-9605, 9605, 20000})
                        filtered improves held-out correlation, beyond what post-hoc evaluation
                        exclusion alone already showed (SS32.4: +0.06-0.064 on the metric).
Independent variable:   Stage 1 training-set sentinel-value filtering (67 records removed from
                        the 36,000-record training split; held-out set unchanged, SS35.3).
Controlled variables:    P1-G04's frozen schedule (steps=20000, lr=0.01, cosine, warmup=200,
                         seed=42, batch_size=256), architecture, feature representation, loss,
                         K, export/quantization, split seed=42, Stage 2 (untouched).
Expected outcome:       Held-out correlation on the cleaned benchmark (v1-clean) exceeds
                        P1-G04's correlation on the same cleaned benchmark by more than the
                        noise floor (std~=0.0019) if the sentinel records also distorted
                        training, not just evaluation.
Observed outcome:       Model B vs. Model A on v1-clean: +0.0002 correlation, -0.1 RMSE, -1.7
                        bias -- an order of magnitude below the noise floor. Model B vs. Model A
                        on v1 (original): -0.0025 correlation, also within noise. Both models'
                        training trajectories are qualitatively identical (SS35.4). The
                        metric-level jump (+0.06 correlation, cp-compression nearly doubling,
                        SS35.5/SS35.6) reproduces identically for both models when the benchmark
                        changes. Expectation not confirmed -- filtering these 67 training
                        records produced no detectable change in the learned function.
Metrics:                See SS35.5/SS35.6.
Decision:               Not promoted as a model change -- Model B is statistically
                        indistinguishable from P1-G04 (Model A remains the reference model,
                        SS26.10 unchanged). Filtering IS recommended as a permanent Stage 1
                        data-generation policy (SS35.9) -- a data-hygiene fix, not a model
                        promotion.
Reason rejected:        No held-out correlation or calibration improvement survives past the
                        benchmark-composition effect once the same benchmark is held fixed
                        across both models (SS35.7's explanation A). This is a clean negative
                        result on "does this fix the learned function," not an ambiguous one.
Next action:            Per this task's instruction, no further Phase 3 experiments begin
                        automatically. SS33's hypothesis #2 (extreme-magnitude/mate compression,
                        Phase 4 territory) remains the most promising unexplored lever.
Artifacts:              trainer/outputs/phase3/P3A-001/, trainer/outputs/datasets/
                        stage1-lichess-filtered/
```

## 36. Phase 3 closure and roadmap status (2026-07-20)

Phase 3 (label quality, §31-§35) is complete and approved. This section closes it formally: a
consolidated hypothesis table, an explicit falsified/confirmed/artifact classification, and an
updated roadmap status extending §30.2's table. No new analysis is performed here — every claim
below cites an experiment already completed and documented above.

### 36.1 Closed hypotheses

| Hypothesis | Experiment | Result | Status |
|---|---|---|---|
| Steps/LR/schedule optimization alone recovers correlation beyond `dfffd3da`'s 0.5044, at the original 40,000-record dataset | Phase 1 grid (P1-G00…G04, §27) | P1-G04 (steps=20,000, LR=0.01, cosine, warmup=200) reached 0.5315, +0.0271 over baseline, ~14.3σ above the measured noise floor (std≈0.0019, §27.2) | **Confirmed** — promoted, current reference model |
| The winning optimization configuration is not itself exhausted — further schedule tuning still has headroom | P1-G04's own trajectory (§27.3, §27.8) | Held-out correlation flatlines for the last ~10,000/20,000 steps while train correlation keeps climbing — textbook plateau-with-continued-train-improvement | **Falsified** — optimization is exhausted at this data scale; further schedule search has low expected marginal return (§27.9) |
| Optimization changes alone close the mate-labeled bias gap | Every Phase 1 cell's mate bias (§27.5) | −1,651 to −1,729cp across every cell including the untouched baseline — no meaningful movement | **Falsified** — mate bias is structural (loss/target-shape), not an optimization artifact |
| Scaling Stage 1 volume 20k→100k improves held-out correlation beyond P1-G04, at fixed compute (20,000 steps) | Experiment 2A, `P2A-001` (§28) | 0.5048 — indistinguishable from the original `dfffd3da` baseline, a regression vs. P1-G04 | **Falsified**, at fixed compute — left an open steps/passes confound |
| Experiment 2A's regression was an optimization-exposure artifact, resolvable by scaling steps to hold passes constant | Experiment 2B, `P2B-001` (§29) | 0.5000 at 64,444 steps (matched passes) — *lower* than 2A, with a genuine early-peak-then-overfit trajectory | **Falsified** — rules out the confound; the regression is real, not a compute-matching artifact |
| Stage 1 data volume scaling (composite, 2A+2B) | §30.1 | Neither compute regime raised correlation above the original 36,000-record corpus | **Rejected / retired** — "exhausted," not disproven-forever; reopening requires new evidence (§30.1) |
| Stage 1 carries 75 extreme-magnitude sentinel-value labels (`eval_cp` ∈ {−9605, 9605, 20000}) that are a genuine upstream Lichess API defect | Label-quality audit, §32.4 | Traced directly to `acquire_stage1_lichess.py`'s unchecked `pv["cp"]` read; 36 records at +20000, 33 at +9605, 6 at −9605 | **Confirmed** — real source-data defect, not a parsing bug in this repo |
| Removing the 8 sentinel-value held-out records changes the *reported held-out correlation metric* | §32.4 post-hoc exclusion | 0.5315 → 0.5931–0.5953 (+0.060 to +0.064) — over 2x Phase 1's entire optimization gain, from excluding 0.2% of the held-out set | **Confirmed** — real, large, mechanically explained (Pearson correlation's sensitivity to extreme target variance) |
| Removing the same sentinel-value records from *training* improves the *learned model* | Experiment 3A, `P3A-001` (§35) | Model B vs. Model A on the matched benchmark v1-clean: +0.0002 correlation, −0.1 RMSE, −1.7 bias — an order of magnitude below the noise floor | **Falsified** — no detectable change to the learned function; the correlation jump is entirely a benchmark-composition artifact (§35.7's explanation A), not a model-quality effect |
| Sentinel-value filtering should still become permanent Stage 1 ingestion hygiene, despite showing no model-quality effect | §35.9 | Costs nothing in demonstrated model quality; prevents this artifact from silently confounding every future Stage-1-derived held-out evaluation, including unrelated future phases | **Adopted as data-hygiene policy** — not a model promotion, a benchmark/ingestion-quality fix |

**Classification, stated explicitly per this task's instruction:**
- **Falsified**: further optimization tuning past P1-G04 (§27.8); optimization fixing mate bias (§27.5); Stage 1 volume scaling under fixed compute (§28) and under proportional compute (§29); sentinel filtering improving the learned model (§35.7).
- **Confirmed**: optimization-limited performance was real and recoverable once, at P1-G04 (§27.7); the Stage 1 sentinel defect is real and upstream (§32.4); the correlation metric is sentinel-sensitive (§32.4).
- **Benchmark artifact, not a model fix**: the entire +0.06 correlation gain from sentinel exclusion (§32.4, §35.5–§35.7) — reproduced identically for two different models, tracking the benchmark, not the model.

### 36.2 Updated roadmap status

Extends §30.2's table with Phase 3's outcome:

| Phase | Status |
|---|---|
| Optimization (Phase 1) | ✓ **Confirmed, exhausted** — P1-G04 promoted as reference model (+0.0271 over noise floor, §27); further optimization-only tuning has low expected marginal return (§27.8) |
| Stage 1 data scaling (Phase 2, Experiments 2A/2B) | ✓ **Rejected**, under both fixed-compute and proportional-compute regimes (§28, §29); retired, not reopened without new evidence (§30.1) |
| Label quality — sentinel-value audit and filtering (Phase 3) | ✓ **Closed** — real source defect confirmed and now filtered as ingestion hygiene (§32.4, §35.9); filtering has **zero measured effect on model quality** (§35.7) — **Phase 3 is closed because it definitively ruled a lever out, not because it found one that moved the model** |
| Extreme-magnitude / mate-labeled compression (§33 hypothesis #2) | **Open, highest-confidence remaining lever** — reconfirmed independently 5 times across every experiment run so far (§23, §27.5, §28.5, §29.6, §32.6) |
| **Next investigation** | **Phase 4 — Loss/K reformulation, targeting the mate-specific bias and, where possible, correlation itself (§24.4, §38 below)** |

## 37. Research state after Phase 3 (2026-07-20)

Stated explicitly, per this task's instruction, with a citation for every claim:

- **Optimization is no longer the dominant bottleneck.** Phase 1 recovered a real, noise-floor-clearing gain once (`dfffd3da` 0.5044 → P1-G04 0.5315, §27.7), but the winning configuration's own trajectory plateaus with a classic overfitting signature in its final ~10,000 steps (§27.3, §27.8) — further schedule search on this dataset has diminishing expected return. Independently, mate-labeled bias is completely unmoved by any optimization change tested (−1,651 to −1,729cp across every Phase 1 cell, §27.5) — confirming optimization cannot reach the failure mode Phase 4 targets, regardless of further optimization-only tuning.
- **Stage 1 volume is not the dominant bottleneck.** Two controlled, single-variable experiments — fixed compute (Experiment 2A, §28: 0.5048, a regression vs. P1-G04) and proportional compute (Experiment 2B, §29: 0.5000, *lower* than 2A, with a genuine overfitting trajectory) — both failed to beat P1-G04. §30.1 concludes Stage 1 volume scaling is experimentally exhausted at this data quality/representation, not merely under-tested.
- **Isolated Stage 1 label outliers are not the dominant bottleneck.** The 75 sentinel-value records are a real, confirmed defect (§32.4) and materially distort the *held-out correlation metric* (+0.06 to +0.064, §32.4) — but Experiment 3A's retrain-and-reevaluate (§35, `P3A-001`) showed the *learned model* is statistically indistinguishable with or without them in training (+0.0002 correlation on the matched benchmark, an order of magnitude below the measured noise floor, §35.7). This is a clean negative result on model quality, not an ambiguous one.

**What the accumulated evidence does point to, stated with the same rigor:**
- **Correlation — not scale — remains the binding constraint** (§23.4): Pearson correlation is affine-invariant, so no post-hoc rescaling can move it, and no retraining lever tested so far (optimization, volume, outlier filtering) has moved it meaningfully beyond P1-G04's 0.5315. Within the cp-labeled subset alone (the majority of real search positions), correlation is markedly lower still — 0.362 (§23.4) — meaning the ranking-quality problem is *not* an artifact of pooling mate and cp positions; it is present, and worse, in ordinary positions.
- **The compression/mate-bias symptom is severe, monotonic in label magnitude, and has now been reconfirmed five independent times** across every experiment run in this roadmap (§23.4/§23.7's original finding; §27.5's Phase 1 reconfirmation; §28.5's Experiment 2A reconfirmation; §29.6's Experiment 2B reconfirmation; §32.6's magnitude-bucketed audit, the finest-grained confirmation yet — near-zero positions MAE 55–72cp, extreme non-mate positions MAE 2,246cp with a −1,456cp bias, mate positions MAE ~2,785cp with a ≈2,800cp-magnitude bias). This is §33's ranked hypothesis #2 and is exactly Phase 4's originally-scoped target (§24.4).
- **A specific, evidenced mechanism exists for the compression symptom, not yet ablated**: `K=2.773456`'s texel-sigmoid loss saturates by ≈288cp (§23.7) — beyond that residual magnitude, training gradient for large-magnitude targets is near zero, plausibly suppressing the network's ability to emit large-magnitude outputs. §23.7's own explicit caveat still applies unmodified: **this mechanism, even if fully confirmed, addresses compression/bias, not correlation** — "re-deriving K... does nothing for correlation, the binding constraint" (§23.7, direct quote). Phase 4 must be read against this distinction, not around it: a successful K sweep would still leave the primary, correlation-based problem open.

**Net position entering Phase 4**: every cheap-to-moderate lever outside loss/target formulation (optimization, Stage 1 volume, isolated label-outlier cleaning) has now been tried and has plateaued or been ruled out. The loss/target-formulation family (§24.4's original Phase 4 scope, plus the candidates below) is the only remaining family of levers this roadmap has not yet tested empirically — and is also the only family with a plausible mechanism (WDL-style blending, §6 Exp 6) to touch correlation itself, as opposed to only compression/bias.

## 38. Phase 4 research plan (planning only — no experiments run, no code changed)

Per this task's explicit instruction: this section defines candidate directions and ranks them
using accumulated evidence from Phases 1–3. **No experiments are performed, no trainer code is
modified, no retraining occurs as part of producing this section.** §24.4 already scoped Phase 4
in outline (K sweep, then mate-aware loss); §26.1/§26.5 already assigned controlled-variable rows
and Experiment IDs (`P4I` for the K sweep, `P4II` for mate-aware loss) before Phase 3 began. This
section extends that existing scaffolding to the six required candidate directions rather than
replacing it, and does not re-litigate decisions §24.4/§26 already made.

### 38.1 The evidence-driven ranking spine

Every candidate below is sorted first by one discriminator, established directly by §23.4/§23.7's
already-completed analysis: **does the candidate have an evidenced or plausible mechanism to move
correlation (the primary, binding metric, §26.0) — or does it only address compression/bias (a
secondary, scale-type symptom that no amount of post-hoc or in-training rescaling can convert into
a correlation gain)?**

- **Cannot move correlation, by direct prior finding** — any pure rescaling of the loss/target
  (K re-derivation, monotonic target transforms, label normalization/standardization, extreme-
  value loss reweighting): §23.4 established correlation is invariant under any monotonic-
  increasing transform, and §23.7 states this explicitly for K: "does nothing for correlation, the
  binding constraint." These candidates are cheap, well-evidenced for their narrow effect
  (compression/bias), and **should not be expected to solve the primary problem** even if they
  succeed completely on their own terms.
- **Plausible mechanism to move correlation, but no direct evidence yet — precedent only**: a
  fundamentally different regression objective (WDL blend, §6 Exp 6; alternative loss shapes that
  change what the network is rewarded for ranking correctly, not merely how hard it's penalized at
  extremes). §6 itself already marks Exp 6's expected gain "Unknown, plausible (Stockfish/
  nnue-pytorch precedent)" — this project has never run it, so its evidence tier is precedent from
  outside this codebase, not internal measurement.
- **Targets the mate-specific bias directly, with strong problem-evidence but no fix-evidence
  yet**: mate-aware loss weighting, mate-distance-aware target representation. §33's hypothesis #2
  is "very strong, independently reconfirmed five times" on the *existence and severity* of the
  problem — but zero ablation has been run on any proposed *fix* for it. This is exactly §24.4's
  original Phase 4(ii) scope.

**The tension this ranking must not paper over**: §24.4's original Phase 4 description targets
"the mate-specific bias" — a secondary symptom by §26.0's own metric hierarchy. §23.4 establishes
that *correlation*, especially the low within-cp-subset correlation (0.362), is what's actually
binding, and that mate-bias fixes do not automatically fix correlation. A "successful" Phase 4(i)
K sweep, even one that fully halves mate bias per §26.3's existing promotion bar, would leave the
primary problem — weak ranking of ordinary, non-mate positions — completely untouched. This
section's ranking makes that explicit rather than treating "mate bias improved" as if it were
"the roadmap's real problem improved."

### 38.2 Candidate catalog (six required directions, mapped onto three distinct experimental levers)

The six candidate names given in this task's instruction overlap substantially as actual
experiments — presented individually below (as instructed) but explicitly cross-referenced to
avoid inventing six independent runs where three suffice.

**Lever A — Loss/target rescaling (cannot move correlation; addresses compression/bias only)**

| # | Candidate | Rationale | Supporting evidence | Expected impact | Implementation complexity | Experimental cost | Scientific risk |
|---|---|---|---|---|---|---|---|
| 4(i) | **K sweep** *(already scoped, §24.4/§26.1/§26.5 as `P4I`)* | Directly ablates §23.7's saturation hypothesis, unablated since first proposed | §23.7 (mechanism, not yet isolated by experiment) | **Zero on correlation, by §23.7's own explicit statement** — Low-medium on compression | Low (empirical sweep, existing pipeline) | Cheap — same schedule as P1-G04, ~180s | Low-medium — re-verify calibration after (§24.6) |
| 2 | **Target transformation** (e.g. a compressive transform of raw `eval_cp` before the sigmoid, or training directly against a transformed target scale) | If the raw cp scale (std≈880cp, extremes to ±20,000 pre-filtering) is what drives sigmoid saturation, transforming the target before the loss could widen the effectively-unsaturated band without touching `K` itself | §23.7 (saturation mechanism); §32.2 (Stage 1/Stage 2 cp distributions, extreme skew/kurtosis even post-sentinel-filtering) | **Low on correlation** (§23.4: no monotonic transform of the target changes the underlying ranking) — **Medium on compression/bias**, unablated | Low (loss-function-local change, reuses existing `train.py:87` texel_sigmoid call site) | Cheap — same schedule as P1-G04 | Low-medium — must re-verify calibration after, per §24.6's standing caution |
| 6 | **Label normalization strategies** (standardizing/rescaling `eval_cp` per-source or globally before it enters `target_cp()`) | Stage 1 and Stage 2 have measurably different cp distributions (mean 135.9 vs. −82.3, std 1,126.9 vs. 402.0, §32.2) — normalizing per-source before combining could reduce a cross-source scale mismatch the current pipeline doesn't correct for | §32.2 (measured per-source distribution divergence); §32.5 (Stage 1's rounding/quantization enrichment, a second per-source difference) | **Low on correlation** (same affine-invariance argument, §23.4, if normalization is a monotonic per-source shift/scale) — **Low-medium on compression/bias**, entirely unablated | Low-medium (touches `combine_and_split`/dataset assembly, not just the loss) | Cheap | Medium — a normalization fit on the training set only must be applied identically to the held-out set, or introduces a *new* leakage-adjacent bug distinct from anything tested so far |
| 4 | **Extreme-value handling** (a bounded/robust *loss* term for large-magnitude targets, distinct from §35's data-level sentinel-record filtering) | §32.6's magnitude-bucketed error is monotonic and severe (extreme non-mate MAE 2,246cp vs. near-zero MAE 55cp) — a loss-level intervention at the same extreme-magnitude boundary is the one part of this hypothesis §35 did **not** test (§35 removed 75 specific defective records; it did not reweight or bound the loss for the thousands of legitimate large-magnitude records that remain) | §32.6 (finest-grained magnitude-bucketed evidence in the roadmap); §35's explicit scope limit (data-record filtering only, not loss reweighting) | **Low on correlation** (same invariance argument, if implemented as monotonic loss reweighting) — **Medium-high on compression/bias for legitimate extreme values specifically**, distinct territory from §35's already-tested, already-rejected extreme-*record*-removal | Low-medium (a weighted-loss term, reuses existing loss infrastructure) | Cheap | Low-medium — risk of §23.5's single-transform trade-off (fixing the extreme tail at the cp-labeled majority's expense) if not designed as an *additive*, not *replacing*, term |

**Lever B — Mate-specific loss/target structure (targets the reconfirmed mate-bias symptom directly; §24.4's original Phase 4(ii) scope)**

| # | Candidate | Rationale | Supporting evidence | Expected impact | Implementation complexity | Experimental cost | Scientific risk |
|---|---|---|---|---|---|---|---|
| 1 (mate half) | **Loss reformulation — mate-aware weighting/shape** *(already scoped, §24.4/§26.1/§26.5 as `P4II`, run after and separately from `P4I`)* | A separate loss slope or weighting for mate-labeled records, checked specifically against cp-labeled regression, per §24.4(ii)'s existing design | §33 hypothesis #2 (problem severity, 5x reconfirmed); §23.5 (the specific failure mode — single-transform trade-off — this must avoid) | **Medium-high on mate bias** (this is what §26.3's existing Phase 4 promotion criterion — mate bias magnitude halved, cp bias/MAE not regressed — was written to evaluate); **low, secondary, on correlation**, since mate-labeled records are ~11.5% of the corpus (§32.1) | Medium (an additive loss term, reuses existing pipeline per §24.4) | Cheap — same schedule scale | Medium — §26.3's own promotion criterion exists specifically to catch a recurrence of §23.5's failure mode |
| 3 | **Mate-target representation** (replace the flat `MATE_EQUIVALENT_CP=3,000` with a mate-distance-aware target, e.g. scaling by plies-to-mate) | The flat constant sits *below* Stage 1's now-filtered 9,605/20,000 sentinel values and collapses every mate distance to one target — §32.5 notes both 3,000 and the (now-removed) 20,000 sentinel saturate to the same ≈1.0 training-loss target under texel-sigmoid, meaning the current representation cannot distinguish "mate-in-2" from "mate-in-40" in the loss at all | §22.2 (−1,726.6cp mate bias, established); §32.3 (mate-depth distributions, Stage 1 reaching mate-in-64, right-skewed with a long tail — real information the flat constant discards); §32.5 (the constant/sentinel saturation-collision observation) | **Medium on mate-labeled bias directly**; **low-medium, secondary, on correlation** — plausibly moves mate-subset correlation specifically (untested) | Medium (touches `target_cp()`'s mate-handling branch, contract-adjacent but not a schema change — mate distance is already computed, just flattened) | Cheap-moderate (needs its own unit tests before training, per `feedback_review_passes_after_green_tests`) | Medium — same failure-mode risk as mate-aware weighting; must be checked as an *isolated additive* change per §24.4(ii)'s own design note |

**Lever C — Alternative regression objective (the only lever with a plausible, though unproven, path to moving correlation itself — the binding constraint per §23.4)**

| # | Candidate | Rationale | Supporting evidence | Expected impact | Implementation complexity | Experimental cost | Scientific risk |
|---|---|---|---|---|---|---|---|
| 5 | **Alternative regression objectives** (most concretely, §6's pre-existing Experiment 6: WDL-blended target using Lichess's free `c9` field, λ-blending win/draw/loss probability with the cp-sigmoid target) | Unlike Lever A/K, a WDL blend changes *what the network is rewarded for getting right* — not merely how the existing cp target is rescaled — giving it a mechanism (untested in this project) to actually move ranking quality, not just calibration | §6 (Experiment 6, pre-scoped, "Unknown, plausible (Stockfish/nnue-pytorch precedent)"); indirectly, §23.4's own finding that nothing tried so far can move correlation — motivating a genuinely different objective as the remaining untested category | **Unknown but the only candidate here with a plausible path to correlation itself**, per §6's own honest framing — not "large," "plausible" | Medium (§6's own estimate: extend `SHARD_DTYPE`, thread `c9` through the pipeline, implement blend in `train.py` — one-time format work) | Same as existing (~180s) once the one-time format work lands | Medium — touches the training-target contract; CLAUDE.md §4 requires re-running the mirror-symmetry/regression suite after any Evaluator-adjacent change |
| 1 (shape half) | **Loss reformulation — objective shape** (e.g. Huber/robust loss in place of pure sigmoid-MSE, independent of mate-specific weighting) | A robust loss changes the effective weighting of outliers vs. inliers globally, a different mechanism than either Lever A's rescaling or Lever B's mate-specific additive term | No direct evidence in this project; general ML precedent only (weaker precedent tier than WDL blend, which has Stockfish/nnue-pytorch-specific precedent) | Unknown — weakest-evidenced candidate in the catalog | Low-medium (a loss-function swap) | Cheap | Medium — changes loss *shape* globally, the broadest-blast-radius change in this catalog; hardest to attribute a resulting correlation change to a specific mechanism |

**Explicit overlap map** (so the decision matrix below does not double-count): candidate #2
(target transformation) and #6 (label normalization) are both instances of Lever A's monotonic-
rescaling family, evidentially indistinguishable from the already-scoped K sweep (`P4I`) in their
effect on correlation (none, by §23.4) — they differ only in *where* in the pipeline the rescaling
happens. Candidate #4 (extreme-value handling) is Lever A's one genuinely distinct sub-case (a
loss-level reweighting of legitimate extreme values, not yet tested by anything in Phases 1-3,
unlike simple rescaling). Candidate #3 (mate-target representation) and the mate half of #1 (loss
reformulation) are the same experiment as Lever B / `P4II`, described from two angles. Candidate #5
(alternative regression objectives) and the shape half of #1 are Lever C, with WDL blend as the
concretely scoped, evidence-precedented instance and generic robust-loss reformulation as the
weaker, unscoped instance.

### 38.3 Decision matrix

Ranked by accumulated evidence, not intuition — per this task's explicit instruction. "Evidence
strength" grades how well-supported the *predicted effect* is (not the problem's existence);
"Expected benefit" is graded against §26.0's metric hierarchy (correlation first, compression/bias
second) rather than against the candidate's own narrowest success criterion.

| Candidate (lever) | Evidence strength | Expected benefit | Engineering effort | Risk | Priority |
|---|---|---|---|---|---|
| K sweep — `P4I` (Lever A) | **High** — §23.7's saturation math is exact, mechanism-level evidence, only the retrain-ablation is missing | **Low** (correlation, primary metric — §23.4/§23.7's own explicit statement) / Medium (compression/bias) | Low | Low-medium | **1 — run first, as a cheap diagnostic and Phase 4(ii) prerequisite, not because it solves the primary problem** |
| Mate-aware loss weighting — `P4II` (Lever B) | **High on problem existence** (§33 hyp. #2, 5x reconfirmed) / **None yet on this specific fix** (unablated) | Medium-high (mate bias, secondary metric) / Low (correlation, primary metric) | Medium | Medium (§23.5's failure mode; §26.3 already has a promotion gate for it) | **2 — the roadmap's originally-scoped Phase 4 target; run after `P4I` per §26.1's existing sequencing** |
| Mate-target representation (Lever B) | Medium — motivated by a real, specific gap (§32.5's flat-constant/sentinel-saturation-collision finding) but no prior ablation of *this* fix | Medium-high (mate bias) / Low-medium (correlation, mate-subset only, untested) | Medium | Medium — same failure-mode risk as mate-aware weighting | 3 — a candidate implementation of `P4II`, not a separate phase; decide alongside mate-aware weighting, not before it |
| WDL blend / alternative regression objective — Exp. 6 (Lever C) | **Precedent-only** (§6: "Unknown, plausible... Stockfish/nnue-pytorch precedent") — no internal-project evidence yet | **Unknown, but the only candidate with a plausible path to correlation itself**, the binding constraint (§23.4) | Medium (one-time format work, §6) | Medium (target-contract change, mirror-symmetry re-verification required) | **4 — highest ceiling, least evidence; schedule as Phase 4's second major experiment after the K-sweep/mate-loss pair, not deferred indefinitely** |
| Target transformation (Lever A) | High (same affine-invariance argument as K sweep) | Low (correlation) / Medium (compression, unablated) | Low | Low-medium | 5 — redundant with `P4I`'s expected finding; only worth running if `P4I` shows an unexpected correlation effect that contradicts §23.4's theory |
| Label normalization (Lever A) | Medium (real per-source distribution divergence measured, §32.2, but untested as a fix) | Low (correlation) / Low-medium (compression) | Low-medium | Medium (held-out leakage risk if normalization parameters aren't fit train-only) | 6 — lowest priority; the per-source divergence is documented but not yet shown to be a quality problem (§32.2 itself flags Stage 2's negative bias as "an open question, not resolved") |
| Extreme-value loss handling (Lever A, distinct sub-case) | Medium — motivated by §32.6's finest-grained evidence yet, but genuinely untested (distinct from §35's already-rejected record-removal) | Low (correlation) / Medium-high (compression/bias for legitimate extreme values) | Low-medium | Low-medium (additive-term risk, same as mate-aware weighting) | 7 — worth a look after the Lever B pair, as a possible complement to mate-aware weighting rather than a replacement for it |
| Generic robust-loss reformulation (Lever C, unscoped) | **Low** — no project-specific evidence, weakest precedent of the catalog | Unknown | Low-medium | Medium — broadest blast radius, hardest to attribute | 8 — not recommended as a standalone Phase 4 experiment; folds into the WDL-blend candidate if that is scheduled, not run separately |

### 38.4 Remaining unknowns

- **Whether within-mate-subset correlation moves under either mate-aware loss candidate** — no
  experiment run so far has reported mate-subset correlation specifically (only overall and
  cp-only, §23.4); Phase 4's own measurement battery should add this split, since it's the one
  place Lever B could plausibly show a correlation effect, not just a bias effect.
- **Whether cross-source label-scale divergence (§32.2, Stage 1 vs. Stage 2) is a genuine quality
  problem or a benign property of two different real-world label sources** — flagged as an open
  question in §32.2 itself, unresolved by any experiment since.
- **Whether Stage 2's `c9` WDL field is actually populated in the existing 20,000-record corpus**
  at a usable rate — §6's Experiment 6 scope assumes it is available but no audit-level check
  (of the kind §32 ran for `search_depth`/`search_nodes`) has confirmed this for `c9` specifically.
  This is a prerequisite fact to establish before scoping WDL-blend implementation effort.
- **Whether Stage 1's Lichess records carry an analogous WDL/game-outcome signal** at all, or
  whether a WDL blend would be Stage-2-only, changing what fraction of the corpus benefits —
  unexamined.
- **Whether the label-noise floor (§6 Experiment 5, pre-existing, not yet run) bounds how much
  any Phase 4 loss/target change could realistically achieve** — if Stage 2's single fixed-node
  search has substantial re-labeling variance (§32.5's n=2 duplicate-FEN finding is suggestive but
  not decisive), that variance caps correlation regardless of loss formulation, and should ideally
  be measured before or alongside Phase 4, not after.
- **Whether plain-768 (non-king-relative) features themselves cap correlation below what Phase 4
  could reach even with a perfect loss/target formulation** — ADR-001's revisit conditions remain
  unmet (§24.4); Phase 4's own results are part of what would eventually inform this, not
  something Phase 4 can resolve on its own.

### 38.5 Recommendation for the first Phase 4 experiment

**Run the K sweep (`P4I`) first — reconciling, not overriding, §24.4's existing "K sweep before
mate-aware loss" sequencing.** This recommendation optimizes for **cheapest-diagnostic-first**,
not **highest-ceiling-first** — both are defensible axes, and this section names which one it
picked and why:

1. **It is a prerequisite for correctly interpreting `P4II`** — §26.1's own controlled-variable
   table already declares 4(ii)'s "Held constant" column as "Best `K` from 4(i) (or the original
   K, if 4(i) shows no improvement)." Running mate-aware loss work before the K sweep would leave
   that row's own precondition unresolved.
2. **It closes a specific, named, still-unablated hypothesis** (§23.7's saturation mechanism) that
   has been carried forward, explicitly caveated as "supported, not confirmed," since §25's own
   hypothesis table — Phase 4 is the first opportunity to actually test it rather than continue
   citing it as plausible-but-unproven.
3. **It is cheap and low-risk** — an empirical sweep against the existing pipeline, comparable
   wall-clock cost to any other single Phase 1-style run (§24.4's own estimate).
4. **Its expected outcome is known in advance not to solve the primary problem, and that is stated
   here explicitly, not discovered after the fact**: per §23.7's own words, K re-derivation "does
   nothing for correlation, the binding constraint." A successful K sweep closes off one specific
   compression-mechanism question; it does not substitute for Lever B (mate-aware loss, run
   immediately after per the existing sequencing) or Lever C (WDL blend, this section's
   highest-ceiling candidate, §38.3's priority 4) — both of which should be scheduled as Phase 4
   continues, not treated as optional follow-ups contingent on the K sweep's result.

**If instead the objective were highest-ceiling-first**, the WDL blend (Lever C, §6 Experiment 6)
would be the recommended first experiment — it is the only candidate in this catalog with a
plausible mechanism to move correlation itself, the metric every other candidate in Lever A is
already known, in advance, not to move. This section does not recommend that ordering, for the
reasons in points 1-3 above (sequencing dependency, cheap diagnostic value, low risk), but states
it as the explicit alternative rather than leaving the choice of axis implicit.

## 39. Phase 4 research questions (rewritten as testable hypotheses, 2026-07-20)

Per this task's instruction: every open question below is restated with a null hypothesis,
independent variable, dependent variables, promotion criteria, and possible failure modes — no
question is left as a vague "investigate X." Where §26.1/§26.3 already define the controlled-
variable and promotion-criteria machinery for a question, this section cites and reuses it rather
than redefining it.

**RQ-1 — K sweep (`P4I`, Lever A)**
- **Null hypothesis (H0)**: no value of `K` in a reasonable empirical sweep improves held-out
  correlation beyond P1-G04's 0.5315 (v1) / 0.5931 (v1-clean) by more than the measured noise
  floor (std≈0.0019, §27.2).
- **Independent variable**: `K` only (§26.1's existing 4(i) row) — data/optimization config held
  at P1-G04's promoted values.
- **Dependent variables**: held-out correlation (primary); compression, bias, MAE, RMSE, mate/cp
  split (secondary, §26.2's mandatory battery).
- **Promotion criteria**: reuses §26.3's existing Phase 4 language, adapted to K specifically —
  correlation gain beyond noise floor (unlikely per §23.4/§23.7, but must still be checked, not
  assumed null) **or**, more likely per §23.7's own prediction, a compression/bias improvement
  with correlation unchanged — recorded as informative even if H0 (on correlation) is not
  rejected, since §23.7's saturation mechanism is itself the thing being tested.
- **Possible failure modes**: a K value that improves compression by moving predictions further
  from calibration in a way `calibration_report`'s mate/cp split would catch (re-verify per §24.6,
  don't assume "correlation is unchanged, therefore calibration is fine"); an unexpected
  correlation *movement* (positive or negative) would itself falsify the affine-invariance-based
  expectation and warrant a dedicated follow-up, not a footnote.

**RQ-2 — Mate-aware loss weighting (`P4II`, Lever B)**
- **Null hypothesis (H0)**: an additive mate-aware loss term does not halve mate-labeled bias
  magnitude (current: −1,661 to −1,729cp depending on configuration — halved from the current
  reference model P1-G04's −1,661.1 is ≈−830cp; §26.3's own anchor, −863cp, was computed against
  `dfffd3da`'s −1,726.6cp before P1-G04's promotion — both are cited so the criterion isn't
  ambiguous about which reference it's halving from) without regressing cp-labeled bias/MAE beyond
  the noise floor — i.e., §26.3's existing Phase 4 promotion criterion is not met.
- **Independent variable**: loss shape/weighting for mate-labeled records only (§26.1's existing
  4(ii) row), run after and separately from RQ-1, using RQ-1's best K (or the original K if RQ-1
  shows no improvement, per §26.1's own stated fallback).
- **Dependent variables**: mate-labeled bias magnitude (primary for this question); cp-labeled
  bias/MAE (regression guard); held-out correlation overall and mate-subset-specific (secondary
  but newly tracked, per §38.4's flagged unknown — no prior experiment reported mate-subset
  correlation alone).
- **Promotion criteria**: exactly §26.3's existing Phase 4 language — mate bias magnitude
  decreases by at least half **and** cp-labeled bias/MAE does not regress beyond the noise floor.
- **Possible failure modes**: repeating §23.5's single-transform trade-off (fixing mate bias at
  the cp-labeled majority's expense) — this is precisely why cp-bias/MAE is a hard co-requirement,
  not an optional secondary check; an additive term that is not actually isolated (e.g. leaks
  gradient into cp-labeled records' loss in an unintended way) would need to be caught via the
  cp-labeled metrics before promotion, not assumed isolated from the implementation alone.

**RQ-3 — Mate-target representation (Lever B alternative/complement to RQ-2)**
- **Null hypothesis (H0)**: replacing the flat `MATE_EQUIVALENT_CP=3,000` target with a
  mate-distance-aware target does not improve mate-labeled bias/MAE beyond what RQ-2's loss-
  weighting approach alone achieves, and/or regresses cp-labeled bias/MAE beyond the noise floor.
- **Independent variable**: mate target *representation* (a target-construction change in
  `target_cp()`), distinct from RQ-2's *loss-weighting* change — run as its own single-variable
  experiment, not bundled with RQ-2, per §26.1's controlled-variable discipline (one independent
  variable per experiment unless a grouping is declared in advance with a stated reason, which
  this pairing does not have).
- **Dependent variables**: same battery as RQ-2 (mate bias/MAE, cp bias/MAE regression guard,
  mate-subset and overall correlation), plus a direct comparison against RQ-2's result on the same
  metrics, to determine whether target-representation or loss-weighting is the more effective of
  the two mate-specific interventions, or whether they compose (a follow-up question, not this
  one).
- **Promotion criteria**: same structural form as §26.3's Phase 4 criterion (mate bias/MAE
  improves substantially, cp-labeled metrics do not regress beyond noise floor), evaluated
  independently of RQ-2 first, then compared.
- **Possible failure modes**: mate distance is right-skewed with a long tail (Stage 1 to
  mate-in-64, §32.3) — a naive linear scaling by ply-distance could itself introduce a new
  extreme-value problem structurally similar to the one §32.4/§35 just spent an entire experiment
  ruling in and back out for raw cp values; any distance-scaled target should be checked against
  the same magnitude-bucketed analysis §32.6 already established as this project's diagnostic of
  choice for exactly this failure mode.

**RQ-4 — WDL blend / alternative regression objective (Exp. 6, Lever C)**
- **Null hypothesis (H0)**: a λ-blended WDL+cp-sigmoid target, at fixed data volume and
  optimization settings, does not improve held-out correlation beyond the best result from RQ-1/
  RQ-2/RQ-3 (whichever is promoted first) by more than the noise floor.
- **Independent variable**: target/loss formulation (cp-sigmoid-only vs. WDL-blended), λ swept
  or fixed per §6's own original design — a prerequisite check (§38.4) must first confirm Stage 2's
  `c9` field is populated at a usable rate before this experiment is scoped further. **Held
  constant**: the best data/optimization configuration from Phases 1-3 (P1-G04's schedule on the
  40,000-record corpus, or RQ-1/RQ-2/RQ-3's promoted result if any clears its own bar first),
  architecture, feature representation (§26.1's 4(i)/4(ii) row pattern, extended).
- **Dependent variables**: held-out correlation overall and cp-only (primary — this is the one
  candidate in the catalog with a plausible path to moving the cp-only 0.362 figure specifically,
  §23.4); full §26.2 battery (secondary).
- **Promotion criteria**: correlation improves beyond the current best promoted result by more
  than the noise floor, **and** the project's mirror-symmetry/regression suite passes unchanged
  (CLAUDE.md §4's standing requirement for any Evaluator-adjacent change, applicable here because
  this changes the training *target* contract, not just a hyperparameter).
- **Possible failure modes**: `c9` may be sparsely populated or entirely absent in the existing
  Stage 2 corpus (untested, §38.4), which would block this experiment at fixed data volume without
  new data acquisition; λ chosen post-hoc rather than declared in advance would violate this
  project's "declared, not tuned post-hoc" convention (established explicitly in §33.1's own
  Experiment 3A design); a blend that improves correlation but shifts the network's raw output
  scale would still need §24.6's standing re-calibration check, not an assumption that a
  correlation gain implies calibration is automatically fine.

**RQ-5 — Label-noise floor (Exp. 5, pre-existing, prerequisite context for interpreting RQ-1–RQ-4)**
- **Null hypothesis (H0)**: re-labeling the same 1,000 Stage 2 positions at two node budgets
  (25k vs. 50k) produces a small spread (|eval_25k − eval_50k| distribution tightly clustered
  near 0), indicating single-fixed-node labels are not a material noise source relative to the
  effects Phase 4 is trying to isolate.
- **Independent variable**: Stockfish node budget only (25k vs. 50k), fixed position sample.
- **Dependent variables**: distribution of `|eval_25k − eval_50k|` in cp (primary); whether the
  spread differs by position phase or magnitude bucket (secondary, reusing §32.6's bucketing
  scheme).
- **Promotion criteria**: per §6's original design — large spread reprioritizes toward stability/
  higher node budget over any Phase 4 loss change (a Phase 3-adjacent finding that would bound
  what Phase 4 could achieve); small spread strengthens the case that Phase 4's loss/target levers
  (not label re-collection) are the right next investment, without itself promoting anything.
- **Possible failure modes**: the existing n=2 duplicate-FEN finding (§32.5, both records differing
  in label) is suggestive of non-trivial variance but is not statistically decisive at that sample
  size — this experiment is exactly the properly-powered (n=1,000) version of that observation;
  a result that contradicts the n=2 finding's direction would need explicit reconciliation, not a
  silent overwrite of the earlier, smaller-sample note.

**Explicitly out of scope, per this task's instruction**: no trainer code was changed, no loss
function was modified, no labels were changed, no retraining occurred, no dataset or checkpoint
was modified in the process of writing §36-§39. This is planning only.
