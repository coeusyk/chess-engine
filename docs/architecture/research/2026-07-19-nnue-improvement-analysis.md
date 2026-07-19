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
- *Acceptance criteria*: `combine_and_split` (or a thin wrapper) reports duplicate count/rate
  and phase-bucket counts for the actual Stage1+Stage2 union before the 90/10 split; report
  committed alongside the next training run record (matching the `train-e3-real.md` convention).
- *Dependencies*: none.
- *Estimated effort*: XS.
- *Expected long-term value*: every future training run gets this report for free; directly
  answers whether current held-out metrics are compromised by a duplicate leak or phase skew.

**Issue B — Persist train-loss curve + intermediate held-out-loss logging**
- *Objective*: stop discarding `train()`'s per-step loss list; call `evaluate_held_out` every N
  steps instead of once, post-hoc, at the end.
- *Acceptance criteria*: a `(step, train_loss, held_out_loss)` series persisted for a full
  training run (re-running the bit-identical E-3 config validates it); §4's falsifiable
  criteria become checkable from this output without further code changes.
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

None yet — this document defines the plan; §6 items are not yet executed as of 2026-07-19.
This review pass (re-triage, #206 scope determination, search-diagnostics investigation,
ranked-hypothesis expansion) is an analysis/documentation update, not an executed experiment.

## 12. Future experiments

See §6 in full; §8.2 lists the three recommended immediately-actionable issues (A, B, C) —
all Infrastructure-tier. Everything else in §6/§7 stays a documented, sequenced future
experiment pending a named prerequisite.
