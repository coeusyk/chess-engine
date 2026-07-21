# NNUE retraining — Measurement Model appendix

**Status**: permanent protocol reference, alongside [the main document](2026-07-19-nnue-improvement-analysis.md)'s §26 Experimental Protocol. Updated as new metric-reliability findings land (most recently: Experiment P4II's pooled-correlation leverage-artifact finding, §4 below) — not a per-experiment report.

This appendix exists because two completed experiments (P4I's replication, P4II) each surfaced a
way a metric can mislead if read without knowing its own variance and composition sensitivity.
Every future Phase 4 report should classify its own metrics against this table rather than
re-deriving promotion suitability from scratch each time.

## 1. Metric catalog

| Metric | Purpose | Expected variance | Promotion suitability | Interpretation guidance |
|---|---|---|---|---|
| **v1-clean correlation** | Pearson correlation between predicted and target cp on the 3,992-record sentinel-filtered held-out set | Low at the effect sizes tested so far — same-K seed spread 0.0004 (`phase4-p4i-replication.md` §6.1), an order of magnitude below a ~0.004 effect | **Primary** | The project's sole declared promotion metric (per this task's methodology update). Resolvable at small n for effect sizes ≳0.004 (§4's P4I finding). **Not immune to composition effects** — a pooled statistic that includes a small, extreme-magnitude subset (e.g. mate-labeled records) can move via that subset's leverage without the majority improving (§4's P4II finding); always cross-check with cp-subset correlation (below) when an intervention targets a minority subset. |
| **v1 correlation** | Same statistic on the unfiltered 4,000-record set (includes 8 sentinel-defect records) | Higher — same-K seed spread 0.0035, comparable to or exceeding effect sizes tested so far (§4-below table) | **Secondary** | Configuration-dependent and higher-variance than v1-clean at current effect sizes; cannot resolve a ~0.004 effect at n=2 (`phase4-p4i-replication.md` §6.1). Still informative as a directional cross-check and as the benchmark every pre-Phase-3 number in this roadmap was measured against, but do not treat a v1-only null result as disconfirming when v1-clean shows an effect, or vice versa, without checking each benchmark's own noise floor first. |
| **RMSE** (overall, held-out) | Absolute-scale prediction error | Not yet measured at n>1 for any configuration in this roadmap — no seed-variance estimate exists | **Secondary** | Used as the primary regression guard alongside calibration (this task's promotion rule: "no material regression in RMSE, calibration"). Reported as a directional delta (candidate vs. baseline), not gated by a statistical threshold that doesn't exist yet — same honesty discipline `phase4_p4i_report.py` already applies. Like correlation, RMSE is a **pooled** statistic and inherits the same composition-sensitivity caveat as v1-clean correlation. |
| **Calibration** (signed bias, compression ratio — overall and per mate/cp split) | Absolute-scale over/under-prediction and score-compression, from `calibration_report()` | No measured noise floor | **Secondary** | Second half of the regression guard. "Improves" = both `|bias|` decreases and compression moves closer to 1.0 (`phase4_p4i_report.py::_calibration_improves`) — check overall AND the cp-labeled split specifically (§23.5's single-transform trade-off: a fix that helps one subset at the cp-labeled majority's expense must be caught here, not assumed absent). |
| **Mate compression** | `calibration_report()`'s mate-labeled-subset compression ratio | No measured noise floor; P4I's replication showed same-K seed spread (0.0209) *larger* than the effect it was used to interpret (0.0102) — this metric is noise-dominated at small n | **Exploratory** | Never sufficient for promotion (per this task's explicit rule). Directly targets §33's hypothesis #2 (mate bias, 5x reconfirmed) and was RQ-2's own original promotion criterion before this methodology update demoted it — still the right diagnostic for "did the mate-aware mechanism do anything to the mate subset," just not a promotion gate on its own. |
| **Cp compression** | `calibration_report()`'s cp-labeled-subset compression ratio | Same noise-dominance finding as mate compression (spread 0.0796 vs. effect 0.0304, P4I replication) | **Exploratory** | Same status as mate compression — informative, not sufficient alone. Doubles as part of the §23.5 regression-guard check above (its role there is structural, not promotional). |
| **Magnitude-bucket MAEs** (near-zero / moderate / large / extreme) | `_magnitude_buckets()`'s non-mate-record error by `|target_cp|` band, §32.6's finest-grained diagnostic | Same order of noise-dominance as compression metrics (P4I replication: same-K spreads 2.2-2.3x the effects they were used to interpret) | **Exploratory** | Useful for spotting *where* a model's error is concentrated (e.g. the extreme-magnitude tail), never as a promotion criterion. Requires materially more samples (larger n, or a paired/blocked design) before it can corroborate or refute anything at the effect sizes this roadmap has tested so far. |
| **Prediction histograms** | Binned distribution of `predicted_cp` on held-out records | No variance estimate exists or is computable in the usual sense (shape, not a scalar) | **Exploratory / qualitative diagnostic only** | Useful for spotting gross distributional shifts (e.g. a candidate's output collapsing toward baseline's shape, P4I replication §5.5) but cannot be quantitatively separated from seed noise. Never a promotion input. |
| **Mate-subset / cp-subset correlation** (introduced by P4II, §4 below) | Pearson correlation computed on the mate-labeled or cp-labeled subset alone, rather than pooled | Not yet measured across seeds — new to this roadmap as of P4II | **Diagnostic, required whenever an intervention targets a subset smaller than the full corpus** | Not currently classified Primary/Secondary/Exploratory in the promotion sense — its role is different: it is the check that determines whether a pooled-correlation movement is genuine or a composition artifact (§4). Any future experiment whose independent variable touches a strict subset of records (mate-labeled, extreme-magnitude, a phase, etc.) must report subset-specific correlation alongside the pooled number before that pooled number is trusted. |

## 2. Promotion suitability, summarized

- **Primary**: v1-clean correlation. The only metric this roadmap has evidence is both resolvable at the effect sizes tested and load-bearing enough to gate promotion on.
- **Secondary**: v1 correlation, RMSE, calibration. Required as regression guards / corroborating context; individually noisier or narrower in scope than the primary metric, per this task's promotion rule (primary improves meaningfully AND no material regression in RMSE/calibration).
- **Exploratory**: mate compression, cp compression, magnitude-bucket MAEs, prediction histograms. Informative, never sufficient for promotion — every one of them has been measured, at least once in this roadmap, to have same-configuration seed noise at or above the effect size it was being used to interpret (`phase4-p4i-replication.md` §6.1).

## 3. Why this hierarchy, not another one

This is not an a-priori methodological preference — it is derived from two completed experiments'
measured behavior in this exact codebase and dataset, per this project's "declared, not tuned
post-hoc, evidence not intuition" convention (research doc §33.1, §45):

- **P4I's replication** (`phase4-p4i-replication.md` §6.1) measured same-K seed-to-seed spread
  against effect size for every metric in the (pre-P4II) battery and found v1-clean correlation is
  the only one where spread is small relative to effect (0.0004 vs. ~0.004, 11x smaller); every
  other metric had spread at or above the effect size it was meant to interpret.
- **P4II** (§4 below) found that pooled correlation — even v1-clean, the primary metric — can move
  substantially from a composition/leverage effect confined to a small subset, without the
  majority of records improving at all. This does not demote v1-clean correlation from Primary
  (it remains the best available promotion signal), but it does mean **any experiment whose
  independent variable is scoped to a subset of records must report subset-specific correlation
  as a required cross-check**, not an optional diagnostic — this appendix's table (§1) records that
  requirement so it is not lost between experiments.

## 4. Case study: P4II's pooled-correlation leverage artifact

Full detail in [`phase4-p4ii-mate-weight.md`](phase4-p4ii-mate-weight.md) §5-§6; summarized here
because it is a *methodology* finding, not just an experiment result, and belongs in this
permanent reference rather than only in that experiment's own file.

P4II reweighted mate-labeled records' loss contribution (11.5% of the training corpus). The
mate-labeled subset's own correlation moved only modestly (+0.02 to +0.03 across benchmarks and
checkpoints). The pooled (overall) v1-clean correlation moved by roughly 2-3x that amount in the
same direction. **The cp-labeled majority's own correlation — measured separately — did not
improve; it was flat-to-regressed** (v1-clean cp-subset correlation: baseline 0.5941, candidate at
the officially-selected checkpoint 0.5591 — a regression, not the pooled number's apparent gain).

**Mechanism**: mate-labeled targets are a two-point mass at ±`MATE_EQUIVALENT_CP` (3,000cp) —
extreme, high-leverage points in a pooled Pearson correlation computed over the whole set. Shifting
that cluster's position relative to the cp-labeled bulk (which is exactly what a mate-focused loss
term does) can mechanically move the pooled statistic without the bulk's own ranking changing at
all. This is a different mechanism from, but the same *class* of finding as, Experiment 3A's
sentinel-filtering result (research doc §35): **a metric moved because of the evaluation set's
composition, not because of what the model learned about the records that matter.**

**Practical rule this establishes**: whenever future Phase 4 work's independent variable targets a
minority subset (mate-labeled records, an extreme-magnitude band, a game phase, etc.), report that
subset's own correlation *and* the complementary majority subset's correlation, not only the pooled
number — even though the pooled number remains the formally declared primary promotion metric. A
pooled improvement with a flat-or-worse majority-subset reading should not be promoted regardless
of what the pooled number alone shows.
