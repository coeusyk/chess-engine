# NNUE retraining — Measurement Model appendix

**Status**: permanent protocol reference, alongside [the main document](2026-07-19-nnue-improvement-analysis.md)'s §26 Experimental Protocol. Updated as new metric-reliability findings land (most recently: Experiment P4IV's clean, no-rubric-contamination null result extending §10's cross-experiment synthesis) — not a per-experiment report. This is the single source of truth for metric classification; per-experiment reports cite it rather than re-deriving promotion suitability from scratch.

This appendix exists because three completed experiments (P4I's replication, P4II, P4III) each
surfaced a distinct way a metric can mislead if read without knowing its own variance, composition
sensitivity, or vulnerability to whatever rubric it's computed under. P4IV (§10.2) added no new
failure mode to the catalog below — its own methodological contribution was confirming that an
intervention scoped entirely to `train()`'s loss line, never touching any evaluation call path,
allows a direct single-rubric comparison with none of §4/§5's composition/rubric risks.

## 1. Metric catalog

| Metric | Purpose | Expected variance | Promotion suitability | Rejection suitability | Known failure modes | Interpretation guidance |
|---|---|---|---|---|---|---|
| **v1-clean pooled correlation** | Pearson correlation between predicted and target cp on the 3,992-record sentinel-filtered held-out set, all records pooled | Low at effect sizes tested so far for a *clean* comparison — same-K seed spread 0.0004 (`phase4-p4i-replication.md` §6.1), an order of magnitude below a ~0.004 effect | **Screening only** — required to show meaningful improvement before anything else is considered, but insufficient alone (§7's promotion rule) | Effective — a flat-or-worse pooled reading, under a rubric both models share fairly, is real evidence against promotion | (1) Composition/leverage: a minority subset (mate-labeled, extreme-magnitude) can move the pooled number via its own leverage without the majority improving (P4II, §4). (2) Rubric contamination: if the metric's own *definition* changes between baseline and candidate (e.g. a redefined mate target), a "movement" can be pure redefinition with zero learning (P4III, §5) — this is worse than composition drift because it isn't even about record leverage, it's about grading the two models on different rubrics. | Never interpret in isolation. Always cross-check majority-population (cp-only) correlation *first* (§6's majority-population rule) and confirm both models were evaluated under an identical target definition before trusting any delta. |
| **cp-only correlation** | Pearson correlation computed on cp-labeled records only (mate-labeled records excluded) | Same order as pooled v1-clean at small n (not yet independently seed-measured, but structurally the more stable reading — see §5) | **The primary majority-population metric** — required, per §7's promotion rule condition 3, for promotion regardless of what the pooled number shows | **The single most decisive rejection signal available**: flat-or-regressed cp-only correlation, when the pooled number looks improved, is definitive evidence the pooled improvement is not real (P4II §4, P4III §5) | Target-invariant for interventions scoped to the mate branch (P4III §5's cp_labeled rows are bit-identical regardless of target definition) — this makes it the one metric immune to the rubric-contamination failure mode above, which is exactly why it is load-bearing. Still shares ordinary composition/sample-size sensitivity with any correlation statistic. | Compute this *before* interpreting the pooled number, not after, whenever an experiment's independent variable touches a subset smaller than the full corpus (§6). |
| **mate-only correlation** | Pearson correlation computed on mate-labeled records only | Small-n by construction (472/4,000 records on v1) | **Validates mechanism only** — never sufficient for promotion alone (this task's explicit rule; also P4II's mate-subset move was itself only +0.02-0.03, too small to promote on its own even if it were sufficient) | Weak — even a flat-or-regressed mate-subset reading doesn't by itself prove the mechanism is dead, given the small n and (for target-representation changes) the same rubric-contamination risk pooled correlation has | Rubric contamination applies here even more sharply than to the pooled metric when the *target itself* is redefined for mate-labeled records (P4III): a naive baseline-old-rubric vs. candidate-new-rubric mate-subset comparison is not a fair test at all. The fair test is baseline-under-the-new-rubric vs. candidate — same weights-independent variable, same grading. | Only ever interpret this metric within a same-rubric (baseline-vs-candidate under one shared target definition) comparison. Report it as a mechanism check, not a promotion input, per this task's explicit instruction. |
| **RMSE** (overall, held-out) | Absolute-scale prediction error | Not yet measured at n>1 for any configuration in this roadmap — no seed-variance estimate exists | **Secondary regression guard** (§7 condition 2) | Effective when compared under a shared rubric; **not effective, and actively misleading, when the target's own scale changed** (P4III: baseline's RMSE dropped from 1030→448 purely from mate targets shrinking from ~3000cp to ~227-1000cp, zero learning involved) | Like correlation, a **pooled** statistic — inherits composition sensitivity. **New, sharper failure mode from P4III**: if an experiment changes what a subset's *target value* is, RMSE computed against the new target is not comparable to RMSE computed against the old one, even for the *same model* — an absolute-scale metric is not rubric-invariant the way a subset-restricted correlation can be. | Only compare RMSE across models evaluated under the identical target definition. Never cite a cross-rubric RMSE change (old-target baseline vs. new-target candidate) as evidence of anything. |
| **Calibration** (signed bias, compression ratio — overall and per mate/cp split) | Absolute-scale over/under-prediction and score-compression, from `calibration_report()` | No measured noise floor | **Secondary regression guard** (§7 condition 2) | Same caveat as RMSE — a target-scale change invalidates cross-rubric comparison entirely (P4III: mate-labeled bias moved from -1663.6 to -388.0 between rubrics, on the *identical* baseline model, purely from the target shrinking) | Same rubric-contamination risk as RMSE, plus §23.5's original single-transform trade-off (a fix that helps one subset at the cp-labeled majority's expense). | Check the cp-labeled split specifically for regression (§23.5), and confirm same-rubric comparison before trusting any calibration delta, same discipline as RMSE. |
| **Compression** (mate/cp-labeled compression ratio) | `calibration_report()`'s per-subset compression ratio | No measured noise floor; P4I's replication showed same-K seed spread (0.0209 mate / 0.0796 cp) *larger* than the effects it was used to interpret (0.0102 / 0.0304) — noise-dominated at small n | **Exploratory** | Weak, same noise-dominance issue blunts rejection power too | Same rubric-contamination exposure as RMSE/calibration when a target definition changes. | Never sufficient for promotion (this task's explicit rule) or, on its own, for rejection. Informative only alongside the majority-population and mechanism checks. |
| **Magnitude-bucket MAEs** (near-zero / moderate / large / extreme) | `_magnitude_buckets()`'s non-mate-record error by `|target_cp|` band, §32.6's finest-grained diagnostic | Same order of noise-dominance as compression (P4I replication: same-K spreads 2.2-2.3x the effects they were used to interpret) | **Exploratory** | Weak, same noise-dominance issue | Bucketed on non-mate records only by construction, so not directly exposed to mate-target rubric changes — but still inherits ordinary small-n noise-dominance. | Useful for spotting *where* error concentrates, never as a promotion or rejection criterion alone. |
| **Prediction histograms** | Binned distribution of `predicted_cp` on held-out records | No variance estimate exists or is computable in the usual sense (shape, not a scalar) | **Exploratory / qualitative diagnostic only** | None — not a quantitative signal | Cannot be quantitatively separated from seed noise; and, like RMSE, the *bins themselves* are on the raw cp scale, so a target-rescaling experiment's histograms are not directly comparable to a pre-rescaling baseline's without care. | Useful for spotting gross distributional shifts qualitatively. Never a promotion or rejection input. |

## 1a. Quick-reference table

| Metric | Promote? | Reject? | Typical role |
|---|---|---|---|
| v1-clean pooled correlation | Screening only (insufficient alone) | Effective, if same-rubric | Screening metric — must clear this to proceed, but it alone proves nothing |
| cp-only correlation | Required (majority-population condition) | **Most decisive rejection signal** | The primary majority-population metric |
| mate-only correlation | Never sufficient alone | Weak | Validates mechanism only |
| RMSE | Secondary (regression guard) | Effective if same-rubric | Regression guard |
| Calibration | Secondary (regression guard) | Effective if same-rubric | Regression guard |
| Compression | Never sufficient alone | Weak | Exploratory |
| Bucket MAEs | Never sufficient alone | Weak | Exploratory |
| Histograms | Never | None (qualitative) | Qualitative diagnostic only |

## 2. Promotion suitability, summarized

- **Screening**: v1-clean pooled correlation. Must show meaningful improvement to proceed, but on its own proves nothing (P4II, P4III).
- **Primary majority-population metric**: cp-only correlation. Required for promotion (§7 condition 3); the most decisive metric for both promoting and rejecting a candidate.
- **Mechanism validation only**: mate-only correlation. Informative about whether an intervention affected its intended target, never sufficient to promote alone.
- **Secondary regression guards**: RMSE, calibration. Required to show no material regression, but only when both models are compared under an identical target-definition rubric (§5).
- **Exploratory**: compression, magnitude-bucket MAEs, prediction histograms. Informative, never sufficient for promotion or rejection alone.

## 3. Why this hierarchy, not another one

Derived from three completed experiments' measured behavior in this exact codebase and dataset,
per this project's "declared, not tuned post-hoc, evidence not intuition" convention (research doc
§33.1, §45):

- **P4I's replication** (`phase4-p4i-replication.md` §6.1): same-K seed-to-seed spread against
  effect size for every metric in the (pre-P4II) battery found v1-clean correlation is the only one
  where spread is small relative to effect; every other metric had spread at or above the effect
  size it was meant to interpret.
- **P4II** (§4): pooled correlation can move substantially from a composition/leverage effect
  confined to a small subset, without the majority of records improving at all — motivating the
  majority-population rule (§6).
- **P4III** (§5): even comparing the *identical* baseline model under two different target
  definitions moves pooled correlation, RMSE, and calibration substantially, with zero learning
  involved — a sharper, purely-definitional version of P4II's composition-sensitivity finding,
  motivating the rubric-consistency discipline embedded throughout §1's table above.

## 4. Case study: P4II's pooled-correlation leverage artifact

Full detail in [`phase4-p4ii-mate-weight.md`](phase4-p4ii-mate-weight.md) §5-§6.

P4II reweighted mate-labeled records' loss contribution (11.5% of the training corpus). The
mate-labeled subset's own correlation moved only modestly (+0.02 to +0.03). The pooled (overall)
v1-clean correlation moved by roughly 2-3x that amount. **The cp-labeled majority's own
correlation did not improve; it was flat-to-regressed** (v1-clean cp-subset correlation: baseline
0.5941, candidate at the officially-selected checkpoint 0.5591).

**Mechanism**: mate-labeled targets are a two-point mass at ±`MATE_EQUIVALENT_CP` (3,000cp) —
extreme, high-leverage points in a pooled Pearson correlation. Shifting that cluster's position
relative to the cp-labeled bulk mechanically moves the pooled statistic without the bulk's own
ranking changing at all. Same *class* of finding as Experiment 3A's sentinel-filtering result: a
metric moved because of the evaluation set's composition, not what the model learned.

## 5. Case study: P4III's rubric-contamination artifact (new, sharper than P4II's)

Full detail in [`phase4-p4iii-mate-target.md`](phase4-p4iii-mate-target.md) §5-§7.

P4III replaced the flat mate target with a distance-aware one (`target_cp()`'s mate branch). Because
`target_cp()` is consumed by both training *and* evaluation, naively comparing the existing baseline
(trained toward the old flat target) against the new candidate (trained toward the new
distance-aware target) grades the two models on **different rubrics** — the candidate is graded on
exactly what it was optimized for; the baseline never had that chance. This is a *different, more
severe* mechanism than P4II's leverage artifact: it isn't about record composition or leverage at
all, it's about redefining what "correct" means between the two models being compared.

**Measured magnitude of the pure-definitional effect** (identical baseline model weights, two
target definitions): pooled v1-clean correlation 0.5933 (old rubric) → 0.6467 (new rubric), overall
RMSE 1030.1 → 448.2, mate-labeled bias −1663.6 → −388.0 — **all from redefining the target alone,
zero training involved.** A naive report comparing the old-rubric baseline against the new-rubric
candidate would show a "+0.053 correlation gain" and a "582cp RMSE improvement" that are entirely
artifacts of the rubric change, dwarfing this roadmap's every genuine effect size to date (P4I's
entire K-sweep effect was ~0.004).

**The correct protocol, established by this experiment and now permanent** (§6/§7 below): evaluate
the baseline under *both* the old rubric (historical continuity) and the new rubric (the only
valid basis for comparison against the candidate), and treat the majority-population metric
(cp-only correlation, target-invariant since cp records never touch the mate branch) as decisive.
Under the *same*-rubric comparison (baseline-new-target vs. candidate), both fair readings —
cp-only correlation (0.5941→0.5931) and mate-only correlation (0.6453→0.6484, the intervention's
own intended target) — show **no meaningful learned effect in either direction**. See the
experiment's own report for the full analysis and scope-capped conclusion.

**Practical rule this establishes, promoted to permanent methodology (§6)**: whenever an
experiment's independent variable is a *target definition* (not just a loss weighting), any metric
that isn't structurally rubric-invariant (pooled correlation, RMSE, calibration, compression) must
be computed for the baseline under **both** the old and new definitions before any comparison is
trusted. A metric that only appears to improve because the grading rubric changed is not evidence
of anything learned.

## 6. Majority-population rule (permanent protocol)

Whenever an experiment changes the weighting or treatment of a subset of records (a loss weighting,
a target redefinition, an extreme-magnitude band, a game phase, etc.), the report **must** contain,
computed and interpreted in this order:

1. **Majority-subset correlation** (cp-only, or whatever subset is *not* the intervention's target)
   — examined *first*, before any pooled number is interpreted.
2. **Modified-subset correlation** (mate-only, or whichever subset the intervention targets) —
   validates whether the mechanism did anything to its own intended target; never sufficient alone.
3. **Pooled correlation** — reported last, and only ever interpreted in light of (1) and (2), never
   in isolation.

**No pooled metric may be interpreted before the majority subset has been examined.** This is not
a suggestion — P4II and P4III both independently produced a large, wrong-looking pooled-metric
"improvement" that (1) alone would have caught immediately.

**If the intervention also changes a target *definition*** (not just a weighting), §5's
rubric-consistency requirement applies in addition: evaluate any non-rubric-invariant metric
(anything except a subset-restricted correlation) under both the old and new definitions for any
model whose training predates the change.

## 7. Promotion rule (permanent protocol, supersedes prior versions)

Promotion requires **all** of:

1. **Meaningful improvement in v1-clean pooled correlation** — the screening condition (§1a).
2. **No meaningful regression in RMSE**, computed under a shared, consistent target-definition
   rubric for every model compared (§5).
3. **No meaningful regression in calibration**, same rubric-consistency requirement as (2).
4. **Majority-population improvement, or at minimum no degradation, in the cp-labeled subset's own
   correlation** — the decisive condition; a candidate that fails this fails regardless of what the
   pooled number in condition 1 shows.

**Exploratory metrics (compression, bucket MAEs, histograms) may never independently justify
promotion.** **Mate-only (modified-subset) improvements alone may never independently justify
promotion** — they validate mechanism, not majority-population value.

## 8. Checkpoint-selection protocol (permanent, implementation unchanged)

`_select_best_checkpoint()`'s peak-pooled-correlation implementation is **not** changed by this
protocol — the methodology governs how its output is *used*, not the selection code itself.

**Demonstrated finding (P4II, one configuration)**: peak-pooled-correlation selection is vulnerable
under at least one subset-targeted objective — P4II's loss-weighting change produced a non-monotonic
("overshoot-then-recede") training trajectory, and the convention selected a transient, early
spike rather than the run's converged value, because the metric it selects on is exactly the
leverage-sensitive one under that loss (`phase4-p4ii-mate-weight.md` §5).

**Contrasting finding (P4III, one configuration)**: the same convention, applied to a
target-*representation* change (not a loss weighting), converged monotonically with no instability
(`selected_step=16999` vs. `final_step=19999` — nearly identical, `phase4-p4iii-mate-target.md`
§4). **Do not generalize beyond the observed evidence**: this does not mean target-representation
changes are immune to the instability P4II found, nor that loss-weighting changes always produce
it — only that this specific pair of experiments showed the pattern once and its absence once.

**Mandatory going forward**: whenever an experiment changes the effective weighting *or* treatment
of a subset, always report **both** the selected checkpoint and the final checkpoint. Future
subset-targeted experiments must validate that the selected checkpoint is representative (compare
against the final checkpoint; inspect the full trajectory for non-monotonicity) before drawing any
conclusion from it.

## 9. Experimental design protocol (permanent)

Every future Phase 4 experiment's report must declare, **before reporting any numbers**:

- **Primary metrics** — what will screen the candidate in or out.
- **Secondary metrics** — what will serve as regression guards.
- **Exploratory metrics** — what is informative but never decisive.

This ordering (declare, then report, in that order) is mandatory methodology, not a stylistic
preference — every prior experiment in this roadmap that skipped this step (P4I, before its own
promotion criteria were pre-registered in §26.3) had to retrofit its own interpretation after the
fact; every experiment since has pre-declared and been easier to review as a result.

## 10. Synthesis: three independent interventions, three non-promotable results (P4II + P4III + P4IV)

**§10.1 — the structural wall under mate-specific interventions (P4II + P4III together)**

Both completed Lever-B experiments — P4II (loss weighting) and P4III (target representation) —
independently hit the **same mechanistic wall**, via two structurally different interventions:

Under `K=2.773456`'s calibrated sigmoid (99% saturation at ≈288cp), any mate-related target that
stays clearly more extreme than ordinary cp evaluations is deep enough in saturation to be
float32-indistinguishable from full saturation — producing near-zero gradient regardless of the
loss weight applied to it (P4III's own derivation, `train.py`'s `MATE_BASE_CP`/`MATE_FLOOR_CP`
comments). The only way to restore differentiable gradient is to bring mate targets into the same
sub-~1,200cp band ordinary cp evaluations already occupy — at which point "mate distance" and
"ordinary evaluation magnitude" become the same signal, and short/common mate distances (the bulk
of the mate-labeled corpus, median 5 moves) still sit in near-zero gradient at any target extreme
enough to remain distinguishable from a large ordinary advantage.

**Forward implication for candidate ranking (as it stood after P4III)**: two independent
interventions within the current single-scalar-output-plus-sigmoid-loss architecture (a loss
reweighting, and a target reformulation) both failed to produce a *promotable* effect. In both,
the majority (cp-labeled) population was unmoved, and any mate-subset movement was small and
non-promotable: P4II's own mate-subset correlation did move modestly, but only via a
majority-flat, pooled-leverage artifact (§4); P4III's mate-subset movement (+0.0031) was flat
within noise (§5). Neither shows the mechanism did *nothing* to mate records — both show it
produced nothing promotable. At that point, `measurement-model.md` flagged a WDL target source as
the more promising remaining lever, precisely because it is untouched by the `σ'(p,K)`
saturation mechanism (below) — that lever has since been tried (§10.2).

**§10.2 — P4IV extends the synthesis: a third, mechanistically *different* intervention, also not
promotable**

P4IV (`phase4-p4iv-wdl-blend.md`) tested a target-*source* blend (λ-weighted mix of the
sigmoid-scaled eval target and an outcome-derived `wdl` value, on the FEN-join-backfilled Stage 2
corpus) — a structurally different mechanism from P4II/P4III's loss/target reshaping *within* the
mate branch: the blend touches ~50% of the whole training corpus (both cp- and mate-labeled
records that happen to carry `wdl`), not a subset scoped to mate handling, and it changes the
target's *information content* rather than its position relative to the sigmoid's saturation
point. **This result is not evidence for or against the §10.1 saturation-wall mechanism** — P4IV's
majority-population records were never in the saturated zone in the first place, so a null there
cannot be explained by `σ'(p,K)` vanishing. The mechanism behind P4IV's null is not established by
this single test point: candidates include (a) the outcome-derived `wdl` signal, at λ=0.5 and on
this specific backfilled 20,000-record subset, simply not carrying ranking information beyond what
Stockfish's fixed-node search-derived `eval_cp` already provides for these positions, or (b)
single-game-outcome data being noisy enough (one game's result is a high-variance, low-information
estimator of a *position's* value) that blending it in at λ=0.5 dilutes a more precise existing
signal for the majority of records — neither is distinguished by this experiment, and no further
λ value or blend design was tested this session (§9's stop condition).

**Forward implication for candidate ranking, updated**: three independent interventions —
a loss reweighting (P4II), a target reformulation (P4III), and a target-source blend (P4IV) — have
now each been tried once and found non-promotable, via at least two distinct mechanisms (a
demonstrated saturation wall for P4II/P4III; an unestablished, plausibly information-content or
noise-related mechanism for P4IV). This does not, by itself, mean every remaining candidate is
doomed — architecture- or output-level changes (a separate auxiliary head, a differently-scoped WDL
blend, e.g. a smaller λ or restricted to high-confidence outcomes) remain untested. It does mean the
"try a target-source substitution" lever specifically has now produced one real data point, not
zero — future ranking discussions should weigh P4IV's result alongside P4II/P4III's, not treat WDL
blend as still-untested-and-therefore-promising. This is a candidate-ranking input, not a
re-opening of P4II, P4III, or P4IV (all three remain closed) and not a re-ranking of Huber/
multi-head performed here — it is evidence to weigh the next time that ranking is revisited.
