# M-1: empirical noise floor for cp-only correlation (measurement-characterization follow-up to E-15)

Governs no Stage-3 issue directly -- a small, standalone measurement-characterization study,
requested as a follow-up to #223 (E-15) before designing the next Stage-3 corpus experiment. Not
a Stage-3 experiment itself: no self-play corpus generated, no NNUE architecture change, no
Measurement Model semantic change, no SPRT, no rerun of E-15 with changed parameters. Reuses
`measurement-model.md`'s own methodology (same-K repeated-seed spread, `phase4-p4i-replication.md`
and `phase1_optimization_sweep.py`'s `P1-G00`/`-S43`/`-S44` convention) rather than inventing a new
one.

## 1. Motivation

`DR-E15-phase-e-evaluation-report.md` classified E-15 as B (null/inconclusive): v1-clean pooled
screening correlation was flat and sign-unstable across checkpoints, while cp-only correlation (the
primary/decisive majority-population metric) moved consistently in treatment's favor at both
checkpoints (+0.0058 selected, +0.0079 final). `measurement-model.md` section 1's own metric
catalog flags cp-only correlation's seed variance as **"not yet independently seed-measured, but
structurally the more stable reading"** -- an assumption, not a measurement. This study measures it
directly, for the first time, before any decision is made about whether E-15's cp-only movement is
signal or noise, and before designing a follow-on Stage-3 experiment that would only be worth
running if the underlying measurement can actually resolve an effect of that size.

## 2. Design

**Minimal repeated-seed experiment, base training data only, no Stage-3 data at all**:

| | Value |
|---|---|
| Base training set | `combine_and_split(stage1_dir, stage2-quiet-sf-v1, seed=42)`'s 36,000-record training split -- byte-identical to E-15's own base-record component (`DR-E15-phase-d-training-report.md` section 1), unfiltered (no sentinel filter applied to training records, matching E-15's own arms) |
| Held-out (evaluation) | v1-clean, 3,992 records -- the same sentinel-filtered set E-15 and every prior Phase 3/4/5 experiment evaluated against |
| Architecture | `NnueNet(hidden_width=256, qa=127, qb=64, output_scale=400)` -- unchanged |
| Schedule | P3A-001's frozen values: `steps=20000, learning_rate=0.01, lr_schedule=cosine, warmup_steps=200, batch_size=256, k=2.773456` -- unchanged, identical to every E-15 arm |
| Stage-3 data | **none** -- this isolates trainer seed-to-seed variance from any Stage-3 data effect |
| Independent variable | `TrainingConfig.seed` only (42, 43, 44) -- governs both model initialization and the epoch-reshuffle data order, per existing trainer semantics (`train.py`: `seed_everything(config.seed)` before either records or init occurs) and `DR-E15` section 14's own documented seed-inventory distinction. No other parameter changed. |

**Why not reuse P3A-001 as a fourth/free data point**: checked directly --
`phase3_experiment_3a.py`'s `STAGE2_DIR = outputs/datasets/stage2-quiet-sf` (no `-v1` suffix),
while E-15 and this study both use `stage2-quiet-sf-v1`. Reusing P3A-001 would introduce a
dataset-version confound into the noise-floor estimate; three fresh, matched runs were trained
instead.

**n=3, not a larger sweep**: matches this project's own smallest-defensible-n precedent
(`P1-G00`/`-S43`/`-S44`) rather than `phase4-p4i-replication.md`'s n=2 pairwise estimate (used
there only because a third run wasn't affordable at the time). Three points give three pairwise
spreads instead of one, a modest, cheap improvement at the measured per-run cost (~115-120s/run
here, consistent with E-15 Phase D's own ~143-153s/run) -- nowhere near "a large arbitrary sweep."

Script: `trainer/scripts/dr_m1_cp_only_noise_floor.py`. Both selected (peak held-out correlation)
and final checkpoints reported for each seed, per `measurement-model.md` section 8's mandatory
both-checkpoints convention.

## 3. Results

### 3.1 Per-run metrics (v1-clean, 3,992 records)

| Seed | Checkpoint | Selected step | Pooled corr. | cp-only corr. | mate-only corr. | RMSE (overall) | Bias (overall) | RMSE (cp-labeled) | Bias (cp-labeled) |
|---|---|---|---|---|---|---|---|---|---|
| 42 | selected | 15999/19999 | 0.5931 | 0.5933 | 0.6358 | 1030.15 | -189.41 | 398.65 | 7.94 |
| 42 | final | 19999 | 0.5926 | 0.5931 | 0.6362 | 1029.39 | -188.54 | 398.24 | 8.69 |
| 43 | selected | 4999/19999 | 0.5901 | 0.5966 | 0.5960 | 1053.50 | -192.11 | 414.22 | 9.83 |
| 43 | final | 19999 | 0.5866 | 0.5898 | 0.6405 | 1030.65 | -187.89 | 398.49 | 9.83 |
| 44 | selected | 17999/19999 | 0.5898 | 0.5977 | 0.6251 | 1029.37 | -187.18 | 397.27 | 9.91 |
| 44 | final | 19999 | 0.5896 | 0.5977 | 0.6251 | 1029.26 | -186.85 | 397.22 | 10.24 |

Selected steps land at three quite different points in training (4999, 15999, 17999) -- consistent
with `phase4-p4i-replication.md` section 4's own finding that a single seed change can move which
step gets selected substantially, without that alone being anomalous (the held-out correlation
plateau for this schedule is wide and shallow, `DR-E15-phase-d-training-report.md` section 4).

### 3.2 cp-only correlation variability (the primary target of this study)

| Checkpoint | Mean | Stdev (n=3) | Min | Max | Pairwise abs deltas |
|---|---|---|---|---|---|
| **Selected** | 0.5959 | 0.0023 | 0.5933 | 0.5977 | 0.0033, 0.0044, 0.0011 |
| **Final** | 0.5935 | 0.0040 | 0.5898 | 0.5977 | 0.0034, 0.0046, 0.0080 |

**Practical empirical noise band, in `measurement-model.md`'s own cautious style (a range, not a
single precise number, per its treatment of every small-n estimate in this roadmap)**: cp-only
correlation's same-configuration seed-to-seed spread at this schedule is on the order of
**0.001-0.008**, with the observed final-checkpoint maximum (0.0080) matching E-15's own
final-checkpoint treatment-control delta almost exactly (see section 5). An n=3 stdev is a fragile
point estimate (two degrees of freedom) -- read as an order-of-magnitude characterization, not a
precision instrument, consistent with `phase4-p4i-replication.md` section 6's explicit caution
about over-trusting small-n variance estimates.

### 3.3 Pooled correlation variability (for context against the existing catalog entry)

| Checkpoint | Mean | Stdev (n=3) | Min | Max | Pairwise abs deltas |
|---|---|---|---|---|---|
| Selected | 0.5910 | 0.0019 | 0.5898 | 0.5931 | 0.0031, 0.0033, 0.0002 |
| Final | 0.5896 | 0.0030 | 0.5866 | 0.5926 | 0.0061, 0.0031, 0.0030 |

**This does not match `measurement-model.md` section 1's cited ~0.0004 pooled v1-clean noise
floor** (`phase4-p4i-replication.md`, K=2.149428, n=2, one pair). At this study's configuration
(production K=2.773456, base data only, n=3), the pooled spread is **5-15x larger**. This is the
same lesson `phase4-p4i-replication.md` section 6 already drew about its own configuration
relative to Phase 1's estimate ("do not reuse the earlier variance estimate... when interpreting a
different configuration") -- now independently reconfirmed at a third configuration. **The 0.0004
figure does not transfer here either; it is specific to the K=2.149428 configuration it was
measured under.** This does not update `measurement-model.md`'s existing table (that entry is a
historical record of what was measured under a specific past configuration, not a universal
constant) -- it is a caution against citing it as a general-purpose pooled-correlation noise floor
for any future experiment, including E-15's own.

Mate-only correlation showed one low outlier (seed 43 selected, 0.5960, vs. 0.62-0.64 for the other
five cells) -- consistent with `measurement-model.md`'s own documented mate-only noise-dominance
(small n=472 subset); not investigated further, since mate-only is exploratory/mechanism-only per
protocol and this study's scope is cp-only and pooled correlation specifically.

## 4. E-15's cp-only signal against the newly measured noise floor

| | Selected checkpoint | Final checkpoint |
|---|---|---|
| E-15 treatment-control cp-only delta | +0.0058 | +0.0079 |
| M-1 same-config cp-only pairwise deltas (this study, n=3, 3 pairs) | 0.0033, 0.0044, 0.0011 | 0.0034, 0.0046, **0.0080** |
| M-1 cp-only stdev (n=3) | 0.0023 | 0.0040 |
| E-15 delta vs. largest observed noise pairwise delta | 0.0058 > 0.0044 (by 32%) | 0.0079 ≈ 0.0080 (within 1%) |
| E-15 delta / M-1 stdev | ~2.5x | ~2.0x |

**Selected checkpoint**: E-15's +0.0058 delta exceeds every pairwise same-configuration noise draw
observed here (max 0.0044) and is roughly 2.5 standard deviations above zero under this study's
noise estimate -- above the noise band, but not by a wide margin, and this is one comparison
against an n=3 noise estimate, not a large-sample significance test.

**Final checkpoint**: E-15's +0.0079 delta is **statistically indistinguishable from the largest
same-configuration noise pairwise delta measured here** (0.0080, seed 43 vs. seed 44's final
checkpoints) -- almost exactly at the edge of, not clearly above, the observed noise band.

**Honest reading**: E-15's cp-only movement is directionally consistent across both its own
checkpoints (this study's own contribution is showing that "directionally consistent across
selected/final" is a weaker signal than it might look, since a pure noise draw from three unrelated
seeds already produces deltas of similar or larger magnitude at the final checkpoint alone).
The selected-checkpoint reading sits modestly above this study's noise band; the final-checkpoint
reading sits within it. **This is not a clean, comfortably-above-noise signal at either
checkpoint** -- it is small relative to a noise floor of the same order of magnitude, which is
exactly the situation `measurement-model.md` section 12's "not clearly separable from noise" null
classification describes.

## 5. E-15's classification is not changed by this result

**Per this task's explicit instruction, E-15's preregistered classification remains B --
null/inconclusive.** This study does not reopen or retroactively reclassify E-15
(`DR-E15-stage3-first-retraining-preregistration.md` section 15's no-post-hoc-retuning rule, and
`measurement-model.md` section 12's own explicit "a null result is a complete, valid outcome, not
a failed experiment requiring a silent rerun" instruction, both still apply to E-15 as already
closed). This study's contribution is narrower and forward-looking: it supplies, for the first
time, a configuration-matched empirical noise estimate that any *future* Stage-3 experiment's
cp-only delta can be judged against before that experiment is designed or interpreted -- exactly
the gap `measurement-model.md` section 1 flagged as missing.

## 6. Recommended next Stage-3 experiment

E-15's own Phase B/C report (`DR-E15-phase-bc-corpus-generation-report.md` section 12) already
identified a design flaw independent of this noise-floor question: the control arm's corpus
collapsed to one repeated 140-ply trajectory (`BestMoveSelector` is fully deterministic and
outcome-insensitive to its own seed argument), making its Stage-3 held-out reading descriptive
only (100% train/held-out FEN overlap, `DR-E15-phase-e-evaluation-report.md` section 1) and its
training contribution a near-duplicate of a single line played 58 times. This is a confound
regardless of what this noise-floor study found: a control this degenerate cannot cleanly isolate
"diversity mechanism effect" from "opening variety effect," since the control has essentially none
of either.

**Recommended design for the next Stage-3 experiment (not implemented by this study)**: give both
arms the **same** reproducible opening/start-position diversity mechanism for a short shared
prefix (a small, fixed pool of opening lines or a bounded random-opening-book draw, seeded
identically for both arms), then diverge: control continues with `BestMoveSelector` (rank-0,
deterministic) after the shared prefix, treatment continues with `SeededDiversitySelector`
(`DR-E14`'s preregistered parameters, unchanged) after the same prefix. Every other setting
(network, engine build, search depth, max plies, adjudication, corpus size, mixing rule, schedule,
training seed) stays matched to E-15's own configuration, so this design change is additive and
narrowly scoped -- it fixes the degenerate-control confound without touching anything the
measurement or noise-floor characterization above depends on.

**Why this study's finding matters for that design, not just for E-15's own interpretation**: this
study establishes that a real cp-only effect at this training configuration needs to clear
something on the order of a 0.004-0.008 same-seed noise band before it is safely distinguishable
from chance with a single treatment-control pair (n=1 comparison, as E-15 ran). Any future
Stage-3 experiment that wants a decisive answer, rather than another borderline reading like
E-15's, should budget for **at least one seed replicate per arm** (matching this study's own
n=3-per-configuration precedent) rather than the single-seed-per-arm design E-15 used -- a design
choice this study's own empirical result now directly motivates, not a generic best-practice
guess.

A new issue, #224 ("[P15] E-16 -- Stage-3 shared-opening-prefix retrain (fixes E-15's degenerate
control)"), has been opened to track designing and scoping that experiment -- not to begin
generation. **No Stage-3 corpus generation, training run, or SPRT is started by this study.**

## 7. Learning log entry

```
## Experiment ID: DR-M1-cp-only-noise-floor
Hypothesis:            cp-only correlation's same-configuration seed-to-seed noise floor, measured
                        directly for the first time at E-15's exact training configuration (base
                        data only, P3A-001's frozen schedule, production K=2.773456), determines
                        whether E-15's own +0.0058/+0.0079 treatment-control cp-only deltas are
                        distinguishable from chance.
Independent variable:   TrainingConfig.seed only (42, 43, 44) -- governs both model init and
                        data-order shuffle, per existing trainer semantics. No Stage-3 data, no
                        architecture change, no schedule change, no Measurement Model change.
Controlled variables:   36,000-record base training set (combine_and_split(stage1, stage2-v1,
                        seed=42), unfiltered), v1-clean held-out set (3,992 records), K=2.773456,
                        steps=20000, lr=0.01 cosine, warmup=200, batch=256, architecture
                        (256/127/64/400).
Observed outcome:       cp-only correlation seed-to-seed spread: selected checkpoint stdev 0.0023
                        (pairwise deltas 0.0011-0.0044), final checkpoint stdev 0.0040 (pairwise
                        deltas 0.0034-0.0080). E-15's own cp-only deltas (+0.0058 selected,
                        +0.0079 final) sit modestly above the selected-checkpoint noise band
                        (~2.5 sigma) but within the final-checkpoint noise band (final noise
                        pairwise max 0.0080 essentially equals E-15's own final delta of 0.0079).
                        Pooled v1-clean correlation's noise (stdev 0.0019-0.0030) is 5-15x larger
                        than the previously-cited ~0.0004 figure (measured at a different
                        configuration, K=2.149428, n=2) -- that figure does not transfer to this
                        configuration.
Metrics:                See section 3 above; full detail in
                        trainer/outputs/phase-m1/cp-only-noise-floor/noise_floor_results.json.
Decision:               E-15's preregistered classification (B, null/inconclusive) is unchanged --
                        this study does not reopen or retroactively reclassify a closed experiment.
                        cp-only correlation is confirmed as noise-measurable for the first time,
                        and E-15's own signal is characterized as small relative to that noise, not
                        clearly separable from it at n=1.
Next action:            A tracking issue is opened for a follow-on Stage-3 experiment that (a)
                        fixes E-15's degenerate-control confound via a shared reproducible opening
                        prefix before the two selectors diverge, and (b) budgets at least one seed
                        replicate per arm, motivated directly by this study's noise-floor estimate.
                        No corpus generation, training, or SPRT begins here.
Artifacts:              trainer/outputs/phase-m1/cp-only-noise-floor/
```
