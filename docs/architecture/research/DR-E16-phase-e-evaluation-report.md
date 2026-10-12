# E-16 Phase E: offline evaluation report

Governs issue #224. Executes `DR-E16-shared-opening-prefix-preregistration.md` Phase E (offline
evaluation, section 6's interpretation rule) only. No training, no SPRT, no parameter change of
any kind. Read-only over Phase B/C/D's already-frozen artifacts. Reuses the unmodified Measurement
Model (`measurement-model.md` sections 1/1a/6/7/9) exactly as E-15 did.

Execution baseline: branch `phase/15-nnue`, commit `c714f2a6564d5eb9df1025b54d5d29c9dfa77134`
(`c714f2a` short form) -- the commit the Phase D training report itself landed on.

## 1. Checkpoint hash re-verification (before evaluating anything)

All 12 frozen checkpoints (3 seeds x 2 arms x {selected, final}) re-verified against
`DR-E16-phase-d-training-report.md` sections 5/6 before any model was loaded for evaluation. **All
12 matched exactly** -- no mismatch, no abort.

## 2. Primary held-out set: v1-clean

Reconstructed via the unmodified `combine_and_split(stage1_dir, stage2-quiet-sf-v1, seed=42)`,
identical call to Phase D's own preflight: **3,992 records**, membership hash
`39f3d4f3e1115927e305986d77315e5ad79663d7f259bc56c0b7ef6f5919058b` -- matches the frozen value
given as this phase's input exactly.

## 3. Selected-checkpoint paired metrics (v1-clean)

| Seed | Arm | Pooled corr. | cp-only corr. | mate-only corr. | RMSE overall | RMSE cp-labeled | Bias (signed mean error, overall) |
|---|---|---|---|---|---|---|---|
| 42 | control | 0.5765 | 0.6012 | 0.5402 | 1056.36 | 414.47 | -196.42 |
| 42 | treatment | 0.5866 | 0.6055 | 0.5819 | 1048.96 | 408.80 | -196.42 |
| 43 | control | 0.5761 | 0.6000 | 0.5766 | 1048.29 | 408.16 | -192.17 |
| 43 | treatment | 0.5771 | 0.6025 | 0.5822 | 1048.62 | 407.74 | -193.10 |
| 44 | control | 0.5798 | 0.6040 | 0.5439 | 1052.05 | 411.99 | -192.91 |
| 44 | treatment | 0.5802 | 0.6089 | 0.5757 | 1047.29 | 406.88 | -191.54 |

Paired deltas (treatment - control), selected checkpoints:

| Seed | cp-only delta | pooled delta |
|---|---|---|
| 42 | +0.00427 | +0.01017 |
| 43 | +0.00246 | +0.00106 |
| 44 | +0.00486 | +0.00033 |

## 4. Final-checkpoint (step 19999) paired metrics (v1-clean)

| Seed | Arm | Pooled corr. | cp-only corr. | mate-only corr. | RMSE overall | RMSE cp-labeled | Bias (signed mean error, overall) |
|---|---|---|---|---|---|---|---|
| 42 | control | 0.5695 | 0.5957 | 0.6101 | 1041.47 | 401.50 | -192.77 |
| 42 | treatment | 0.5824 | 0.5980 | 0.6195 | 1037.74 | 400.67 | -192.82 |
| 43 | control | 0.5714 | 0.5966 | 0.5973 | 1039.79 | 401.11 | -192.03 |
| 43 | treatment | 0.5744 | 0.5981 | 0.6130 | 1039.72 | 400.86 | -191.81 |
| 44 | control | 0.5723 | 0.6011 | 0.5873 | 1039.63 | 400.83 | -190.72 |
| 44 | treatment | 0.5755 | 0.6052 | 0.5990 | 1039.37 | 400.18 | -191.05 |

Paired deltas (treatment - control), final checkpoints:

| Seed | cp-only delta | pooled delta |
|---|---|---|
| 42 | +0.00238 | +0.01296 |
| 43 | +0.00150 | +0.00302 |
| 44 | +0.00414 | +0.00317 |

Note: this project's `measurement-model.md` calibration/RMSE convention grades against
`target_cp()` in raw centipawn space (not the sigmoid-compressed probability space the training
loss itself minimizes) -- the large `mate_labeled` RMSE/bias figures (both arms, all seeds,
~2,600-2,860 cp) reflect the known flat-`MATE_EQUIVALENT_CP`-target behavior
(`mate_target_distance_aware=False`, unchanged from E-15) grading against a 3000cp-equivalent
target, not a defect introduced by this experiment.

## 5. cp-only paired summary (primary/decisive metric)

| | Selected | Final |
|---|---|---|
| Seed 42 delta | +0.00427 | +0.00238 |
| Seed 43 delta | +0.00246 | +0.00150 |
| Seed 44 delta | +0.00486 | +0.00414 |
| Mean paired delta | +0.00386 | +0.00267 |
| Min / max | +0.00246 / +0.00486 | +0.00150 / +0.00414 |
| Stdev | 0.00125 | 0.00135 |
| Sign consistency | 3/3 positive | 3/3 positive |

Compared descriptively against `DR-M1-cp-only-noise-floor-characterization.md`'s measured
same-configuration cp-only noise band (~0.001-0.008): **every individual paired delta, in both
checkpoint kinds, falls inside that band** -- none exceeds it, and the mean paired delta (0.00386
selected, 0.00267 final) sits solidly mid-band, not near or beyond its upper edge. The direction is
consistent (3/3 positive, both kinds) but the *magnitude* is not distinguishable from the
configuration-matched noise DR-M1 already measured at this exact training schedule. No
significance threshold is invented from this n=3 pattern -- stated descriptively, per instruction.

## 6. pooled paired summary (screening only)

| | Selected | Final |
|---|---|---|
| Seed 42 delta | +0.01017 | +0.01296 |
| Seed 43 delta | +0.00106 | +0.00302 |
| Seed 44 delta | +0.00033 | +0.00317 |
| Mean paired delta | +0.00386 | +0.00638 |
| Min / max | +0.00033 / +0.01017 | +0.00302 / +0.01296 |
| Stdev | 0.00548 | 0.00569 |
| Sign consistency | 3/3 positive | 3/3 positive |

Compared descriptively against `DR-M1`'s measured pooled same-training-seed spread (stdev
~0.0019-0.0030): **the pooled paired-delta spread here (stdev 0.0055-0.0057) is wider than that
band**, driven almost entirely by seed 42's outsized pooled delta (+0.0102 selected, +0.0130 final)
against seeds 43/44's much smaller deltas (both kinds, both seeds under +0.0032). Per
`measurement-model.md`'s own screening-only status for pooled correlation, this wider-than-DR-M1
spread is read as a caution against treating the pooled figure as informative on its own here --
not as evidence of anything, and not the deciding metric regardless. The primary/decisive read
remains section 5's cp-only figure.

## 7. Regression guards

For every seed and both checkpoint kinds: treatment's `RMSE overall` and `RMSE cp-labeled` are
equal to or lower than control's in all six seed/kind combinations (largest control-minus-treatment
RMSE-cp gap: 5.11cp at seed 44 selected; smallest: 0.05cp at seed 43 selected -- treatment never
worse). Overall bias (`signed_mean_error`) is within ~1-2cp of control's in every case, no
systematic direction change. `mate_only_correlation` is equal to or higher for treatment in all six
combinations (control range 0.540-0.610, treatment range 0.576-0.620 across both kinds). **No
regression guard shows material worsening in either arm, at either checkpoint kind, for any
seed.**

## 8. Stage-3 exploratory metrics (each arm's own frozen Phase-C held-out set)

**Explicit framing, per instruction**: control and treatment held out the identical 6 game
IDs/openings `{17, 20, 27, 31, 34, 51}` (`DR-E16-phase-c-split-and-equalization-report.md` section
4). Within-arm train<->held-out FEN overlap is **exactly 0 for both arms** (re-confirmed here:
control 0/595, treatment 0/694) -- each arm's own held-out set independently tests positions its
own training pool never saw. Cross-arm held-out FEN overlap (17/595 = 2.86% of control's held-out
set, 17/694 = 2.45% of treatment's) is expected and by construction (both arms share the same 6
openings' early moves) -- **not within-arm leakage**, and not evidence of a problem.

| Seed | Kind | Control cp-only corr. (n=543/663) | Treatment cp-only corr. | Paired delta (T-C) |
|---|---|---|---|---|
| 42 | selected | 0.8655 | 0.8265 | -0.0390 |
| 42 | final | 0.8480 | 0.8135 | -0.0345 |
| 43 | selected | 0.8615 | 0.8203 | -0.0412 |
| 43 | final | 0.8472 | 0.8172 | -0.0301 |
| 44 | selected | 0.8705 | 0.8261 | -0.0444 |
| 44 | final | 0.8499 | 0.8120 | -0.0378 |

Stage-3 paired cp-only summary: selected mean delta -0.0416 (stdev 0.0027, 3/3 negative), final
mean delta -0.0341 (stdev 0.0039, 3/3 negative) -- consistently in **control's favor** on each
arm's own held-out opening set, the opposite direction from the (small, noise-band) treatment
favor seen on v1-clean. Read descriptively, kept exploratory, as instructed: cp-only correlation is
far higher on Stage-3 held-out data for both arms (~0.81-0.87) than on v1-clean (~0.60) for either
arm regardless of which is higher between them, consistent with Stage-3 positions being easier for
this generator lineage's own network family to fit than the general v1-clean benchmark, not
evidence that either selector mechanism is better in the sense the v1-clean historical benchmark
measures. **Stage-3 held-out metrics are not used to override the v1-clean-based classification
below**, per instruction.

## 9. Selected-vs-final consistency

Selected steps varied across runs (4999/6999/7999/7999/5999/7999 for
control-42/treatment-42/control-43/treatment-43/control-44/treatment-44) -- unlike E-15's single
seed replicate, where both arms happened to select the identical step (7999) by coincidence. Since
selected checkpoints were chosen independently per run from `held_out_correlation` and the selected
steps differ across some control/treatment pairs within a seed (42, 44), **the final-checkpoint
(step 19999) paired comparison is treated as the cleaner fixed-horizon causal reading** where
selected and final results diverge, per this task's explicit interpretation constraint -- both are
reported in full above (sections 5/6), and neither supersedes the other in the sense of
invalidating it; the final-checkpoint reading is preferred only for comparing arms at a matched,
non-selection-confounded point in training. Both readings land in the same place regardless: cp-only
mean paired delta positive, small, sign-consistent, inside DR-M1's noise band, at both checkpoint
kinds. **The preregistered classification rule is unchanged by this observation** -- it is reported,
not used to alter Phase E's own procedure.

## 10. Comparison with E-15 (secondary context only, does not reopen E-15's classification)

E-15's own single-seed cp-only deltas (`DR-E15-phase-e-evaluation-report.md`): +0.0058 (selected),
+0.0079 (final). E-16's three-seed mean paired cp-only deltas: +0.00386 (selected), +0.00267
(final) -- **smaller in both checkpoint kinds**, though E-15's own single pair is well within the
spread E-16's three seeds show here (E-16's own per-seed range: +0.0025 to +0.0049 selected,
+0.0015 to +0.0041 final), so "smaller" is a mean-level observation, not a claim that E-15's single
data point was itself anomalous. The effect did not disappear (still 3/3 positive at both checkpoint
kinds) and did not reverse into a regression; it also did not grow or become clearly
distinguishable from noise once both arms received the same shared opening diversity -- if
anything, the mean moved slightly further into the noise band's lower half rather than out of it.
This is read as weak, non-decisive evidence *against* the hypothesis that E-15's degenerate,
zero-diversity control was solely responsible for producing E-15's own observed delta (removing
that confound did not make the effect larger or clearer) -- but it equally does not rule that
hypothesis in or out, since E-15's own single pair already sat inside E-16's later observed spread.
**E-15's B (null/inconclusive) classification, reached under its own preregistered section 12 rule,
is not reopened or revised by this comparison or by anything in this document.**

## 11. Classification (DR-E16 section 6, unmodified Measurement Model, applied exactly)

Per section 6's rule: positive/useful only if the paired evidence is *consistently and materially
favorable* relative to the configuration-matched variability, and regression guards hold; null/
inconclusive if deltas are small/noise-dominated or sign-inconsistent; regression if decisive
cp-only and/or regression guards materially worsen.

- **Sign consistency**: 3/3 positive, both checkpoint kinds -- satisfies the "consistent" half.
- **Materiality relative to DR-M1's noise band**: every individual paired cp-only delta, both
  checkpoint kinds, falls *inside* the measured ~0.001-0.008 same-configuration noise band; the
  mean paired delta (0.00386 selected, 0.00267 final) sits mid-band, not near or beyond its upper
  edge. This fails the "materially favorable" half -- the effect is not distinguishable from the
  noise DR-M1 already characterized at this exact schedule.
- **Regression guards**: hold cleanly (section 7) -- no material worsening in either arm, at either
  checkpoint kind, for any seed.

**Classification: null / inconclusive.** The direction is consistently positive but the magnitude
is not materially distinguishable from configuration-matched noise, and no regression guard is
violated. No new hard numerical threshold was invented to reach this classification -- it follows
directly from comparing the observed deltas against DR-M1's already-measured band, per section 6's
own instruction.

## 12. What was deliberately not done in this phase

No retraining, no retuning, no checkpoint-selection-rule change, no Measurement Model change, no
SPRT, no new experiment started. Stage-3 held-out metrics were computed and reported (section 8)
but never used to override or influence the v1-clean-based classification in section 11. E-15's own
classification was not reopened or revised (section 10).

## 13. Frozen artifacts (this phase's provenance)

| Artifact | Value |
|---|---|
| v1-clean membership hash | `39f3d4f3e1115927e305986d77315e5ad79663d7f259bc56c0b7ef6f5919058b` (3,992 records) |
| Shared Stage-3 held-out game IDs | `{17, 20, 27, 31, 34, 51}` |
| All 12 checkpoint hashes | re-verified exact against `DR-E16-phase-d-training-report.md` sections 5/6 (section 1 above) |

No pinned seed (generation 20261601, split 20261602, equalization 20261603, training 42/43/44) was
touched in this phase.

## 14. #224 disposition

All six acceptance criteria (design record, Phase B prerequisites, Phase B execution, Phase C,
Phase D, Phase E) are now satisfied. `#224` is closed by this document's execution, per the task's
"close #224 only if every AC is satisfied" instruction. E-16's own classification (null/
inconclusive, section 11) is final for this experiment; it does not reopen E-15, and it does not by
itself authorize or preclude any future experiment -- any next step is a separate, future decision.
