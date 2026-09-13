# Experiment P5-AUXHEAD-RMS-001 -- auxiliary-input RMS normalization

Phase 5, implementing the design record
[`phase5-p5-auxhead-rms-design.md`](phase5-p5-auxhead-rms-design.md), written and reviewed
before either arm was trained. That record is the contract this report is graded against --
hypothesis, mathematical treatment, control identity, and preregistered mechanism/primary
criteria are specified there and are not restated in full here.

**Verdict: mechanism not repaired by the preregistered criteria.** RMS normalization produced a
real, transient early improvement (the only arm that ever beats the constant-predictor baseline,
at 3 of 20 checkpoints) and a mostly-sustained, non-material reduction in logit polarization
(16 of 20 checkpoints), but the improvement was not durable -- auxiliary BCE reverses from step
3,999 onward and ends worse than at any prior checkpoint -- and none of the three preregistered
mechanism criteria were jointly satisfied. The primary evaluation shows no benefit regardless.
This is a cleaner negative result than P5-AUXHEAD-001's: the auxiliary gradient's magnitude
sensitivity was a real, partial contributor to the failure, but not the
dominant or sufficient cause.

## 1. What this replaces

The recovery audit's first proposed experiment divided the auxiliary head's input by a fixed
constant, `QA=127`. Adversarial review rejected that design: under this repository's plain Adam
(no weight decay, no gradient clipping), a fixed divisor `d` in `wdl_head(combined / d)` is
absorbed by Adam's scale-invariance into an approximately `d`-fold reduction of the auxiliary
head's effective logit-space learning rate and of its gradient coupling into the shared
backbone. That is a disguised, extreme learning-rate/effective-aux-weight cut confounded with
the intended conditioning test, not an isolation of the diagnosed magnitude-drift mechanism.
This experiment replaces it with a parameter-free, per-sample RMS normalization recomputed on
every forward pass, so there is no fixed constant for the optimizer to fold into a rate change.

## 2. Provenance

Pinned before either arm trained, in `outputs/phase5/P5-AUXHEAD-RMS/provenance.json` (local,
gitignored per the existing `outputs/` convention):

- Stage-1 (`stage1.csv`, `manifest.json`) and Stage-2-WDL (`shard-0.bin`, `manifest.json`)
  dataset file SHA-256 hashes.
- Split-seed-42 training/held-out membership and order, each pinned as a SHA-256 of the
  ordered FEN sequence: 36,000 training / 4,000 held-out records (pre-sentinel-filter, matching
  the audit's reconciled 40,000-record corpus).
- P3A-001 checkpoint SHA-256 `c2c33d1dd7af5345d8772aa0761e79d5b9bc8bff7aff409b946078684c08d0ef`
  -- matches the recovery review's independently computed value exactly.
- P1-G04 checkpoint SHA-256 `acd24d68bc21d34e09ad71f651e7f0c5055adb32f90b0cae9480ea3343036cd8`
  (`outputs/phase1/P1-G04/checkpoints/step-015999.pt`, the selected checkpoint -- see the
  correction below), recorded for display-only continuity (never used for gating).

**Correction (post-review):** an earlier version of this report and its driver script pinned
`outputs/phase1/P1-G04/final.pt` (SHA-256 `479e61e732785af5f97c90fec7b71b72debc6922181d6f1199d2364765d20f2b`)
as "the P1-G04 checkpoint," disagreeing with the recovery audit's recorded hash for the same
name. Root cause: `final.pt` and `checkpoints/step-015999.pt` are two genuinely different,
both-legitimate files from the same training run -- `final.pt` is the run's last-step (19,999)
checkpoint with additional saved state (optimizer state, experiment metadata), while
`step-015999.pt` is the periodic checkpoint at the run's *selected* step (peak held-out pooled
correlation), which is what "P1-G04" canonically refers to throughout this research line (the
audit's own recomputed v1 correlation, 0.5315414, matches `step-015999.pt` exactly and does not
match `final.pt`'s 0.5310997). This was a wrong-file bug in this experiment's own driver script
(`P1_G04_CHECKPOINT`), not a data-integrity problem, a corrupted checkpoint, or a discrepancy in
the audit. It is fixed in `trainer/scripts/phase5_auxhead_rms.py` and the provenance and
display-only table below are regenerated from the corrected path (read-only re-evaluation, no
retraining). **This does not affect P5-AUXHEAD-RMS-001's validity**: P1-G04 was declared
display-only in the design record (§5) and never participated in the mechanism or primary
gating, which compare only the matched control, the treatment, and the P3A-001 baseline --
none of which this bug touched.
- Environment: PyTorch 2.13.0+cu130, NumPy 2.5.1, CUDA available (RTX 4060) but **not used** --
  training in this trainer is CPU-only (no `.cuda()`/`.to(device)` call exists in the training
  loop), matching every prior Phase-4/5 run's execution path.

## 3. Control recovery

`outputs/phase5/` was absent from this working tree (`outputs/` is gitignored, and no archived
P5-AUXHEAD-001 checkpoints were found), so per the design record's contingency exactly one
matched unnormalized control was regenerated under the current environment, at
`aux_wdl_weight=0.04`, `aux_rms_norm=False`, on P1-G04's frozen 20,000-step schedule.

**Reproduction gate: passed, near-exact.** The regenerated control reproduces P5-AUXHEAD-001's
reported trajectory closely enough to treat as the same experiment re-executed, not a different
one:

| Metric | Historical P5-AUXHEAD-001 / DIAG-001 | Regenerated control (this run) |
|---|---|---|
| Selected checkpoint (peak held-out pooled correlation) | step 3,999 | step 3,999 |
| Early aux/backbone gradient ratio (step 999) | 5.33 | 5.325 |
| Final aux/backbone gradient ratio (step 19,999) | 0.15 | 0.150 |
| Auxiliary logit range, step 999 | [-20.3, 16.3] | [-20.3, 16.3] |
| Auxiliary logit range, step 19,999 | [-39.2, 35.9] | [-39.2, 35.9] |
| Held-out auxiliary BCE, step 999 -> 19,999 | 0.702 -> 1.162 | 0.702 -> 1.162 |
| Constant-predictor BCE (training-fitted) | 0.6831 | 0.6831 |
| Train-fitted constant BCE, scored on held-out | ~0.68032 (recovery review) | 0.6803206 (recomputed, pinned) |
| Primary cp-only v1-clean delta vs baseline, selected / final | -0.0017 / -0.0029 (historical) | -0.0017 / -0.0029 |

All four reproduction-gate conditions from the design record (auxiliary BCE worse than
constant, logit inflation, early gradient-ratio dominance, primary regression direction) are
satisfied, and the match is close enough that the regenerated control is treated as fully
equivalent to the historical P5-AUXHEAD-001 run for gating purposes. No unexplained control
drift; the treatment arm proceeded.

## 4. Mechanism results

Both arms: `aux_wdl_weight=0.04`, seed 42, identical schedule, identical data, differing only in
`aux_rms_norm` (False for control, True for treatment). 20 diagnostic checkpoints, same fixed
4,096-record diagnostic batch (seed 20260803) and the same 1,993-record held-out WDL set used
throughout Phase 5.

| step | metric | control | treatment |
|---|---|---|---|
| 999 | held-out aux BCE | 0.7022 | **0.5445** |
| 999 | aux/backbone gradient ratio | 5.325 | **3.037** |
| 999 | extreme-decile fraction | 0.6187 | **0.5148** |
| 3,999 | held-out aux BCE | 0.7448 | **0.6909** |
| 3,999 | aux/backbone gradient ratio | 2.786 | **1.128** |
| 3,999 | extreme-decile fraction | 0.6598 | **0.6152** |
| 19,999 | held-out aux BCE | 1.1619 | **1.1024** |
| 19,999 | aux/backbone gradient ratio | 0.150 | 0.143 |
| 19,999 | extreme-decile fraction | 0.7582 | **0.7416** |
| 19,999 | shared-activation RMS (pre-normalization) | 4.6118 | 6.1355 |
| 19,999 | auxiliary-head weight norm | 2.4219 | **10.8296** |

Train-fitted constant BCE, recomputed from pinned data and scored on the identical held-out set
(not hardcoded): **0.6803206** for both arms (the constant baseline does not depend on which
arm trained it, since it comes from the training corpus's WDL distribution, but it was
recomputed independently for each arm's diagnostic run and matched to seven decimal places).

**Correction (post-review):** an earlier version of this report stated that treatment "does not
beat the constant predictor (0.6803206) at any checkpoint." That statement was false and is
corrected below against the full 20-checkpoint trajectory (recomputed from the pinned diagnostic
JSON, not re-estimated). No raw measurement changed; only the interpretation of measurements
already in this report's table is corrected. The preregistration (design record §7.1) is
unchanged and is not being reinterpreted after the fact -- the three criteria below are exactly
those stated before either arm trained.

**Reading against the three preregistered mechanism criteria, checkpoint by checkpoint:**

1. **Held-out auxiliary BCE beats both the constant predictor and the matched control (both
   required).** Against the constant predictor (0.6803206): treatment beats it at exactly the
   first **3 of 20** diagnostic checkpoints -- steps 999 (0.5445), 1,999 (0.5928), and 2,999
   (0.6267) -- a real, transient early success covering roughly the first 15% of training. It
   does not sustain: from step 3,999 onward (0.6909) treatment is worse than the constant
   predictor at every remaining checkpoint, monotonically, reaching 1.1024 at the final step. The
   control never beats the constant predictor at any checkpoint (minimum 0.7022, at step 999),
   so the transient early win is a real, treatment-specific effect, not shared background noise.
   Against the matched control: treatment beats it at **11 of 20** checkpoints, not all of
   them -- it wins throughout the transient-success window and again from step 15,999 onward, but
   is worse than the control across a middle stretch (steps 5,999-14,999, 9 checkpoints). It does
   win at both checkpoints the rest of this report actually reads against (selected, step 3,999:
   0.6909 vs 0.7448; final, step 19,999: 1.1024 vs 1.1619). **Fails** the joint "beats both"
   requirement at the checkpoints that matter for a promotion read (selected and final): the
   constant-predictor half of the bar is not met at either one, even though the control-beating
   half is.
2. **Logit polarization materially reduced.** Extreme-decile fraction: treatment beats control at
   **16 of 20** checkpoints (all except a mid-training dip at steps 8,999-11,999), including at
   both the selected checkpoint (0.6152 vs 0.6598) and the final checkpoint (0.7416 vs 0.7582, a
   1.7-percentage-point reduction). This is the most consistent of the three criteria, and
   directionally correct throughout, but a roughly 2-point reduction against a baseline already at
   75-76% is a modest attenuation, not the qualitative break in the pathology "materially reduced"
   was meant to describe. **Fails** the preregistered bar, on the same reading applied
   consistently: a real, mostly-sustained but small effect is not what the design record's
   "materially reduced" language was set up to accept.
3. **Early gradient-ratio dominance (2.5-5.3x) followed by collapse to ~0.15 no longer
   present.** The very-early peak is genuinely lower (3.037 vs 5.325 at step 999, 0.426 vs 2.463
   at step 2,999), but by step 3,999 the treatment ratio is back to 1.128 -- comparable order to
   the control's 2.786 -- and for the remainder of training both arms show the same noisy,
   non-monotonic pattern between roughly 0.15 and 1.7 before collapsing to 0.14-0.15 at the final
   step. **Fails clearly**: the peak is attenuated, but the qualitative shape -- early dominance
   above 1, eventual collapse -- is present in both arms and was not eliminated in the treatment.

**All three criteria were required for mechanism success (design record §7.1: "all three
required").** Criterion 1 is met only transiently (3 of 20 checkpoints) and fails at the
checkpoints that matter for a read (selected, final); criterion 2 is a real but non-material
effect; criterion 3 fails clearly. The joint requirement is not satisfied, so **the overall
mechanism verdict is unchanged: failure.** The transient early success at criterion 1 does not
become a promotion claim -- it is evidence the mechanism is real, not evidence the intervention
worked.

**A secondary, unplanned observation:** the auxiliary-head weight norm at the final checkpoint is
4.5x larger under RMS normalization (10.83 vs 2.42). This is consistent with -- not proof of --
the head-compensation risk the design record flagged (§3): removing the backbone's magnitude
signal from the auxiliary head's input does not by itself prevent the head from re-inflating its
own weights to reach a comparably wide logit range from a now-unit-scale input. The correlation
between the weight-norm growth and the reversal after step 2,999-3,999 is suggestive, not
established as causal; no experiment here isolates weight-norm growth as the mechanism of the
reversal from other explanations (for example, the same optimization dynamics that caused the
original drift continuing to operate on a different geometry).

**Interpretation: Branch A -- mechanism failure, precisely characterized.** RMS normalization
substantially improved auxiliary learning early in training -- at steps 999-2,999 it is the only
arm of the two that ever beats the constant predictor -- but the improvement was not durable: the
auxiliary head re-entered an overconfident, BCE-worsening regime from step 3,999 onward while its
weight norm grew substantially. This supports the hypothesis that magnitude sensitivity
contributed to the original pathology, but RMS normalization alone was insufficient to repair it.
Per the design record and the governing task, this stops the investigation at this design; no
LayerNorm, weight cap, aux-weight change, or scheduler change is chained onto this result without
a new, mechanism-driven hypothesis.

## 5. Primary research evaluation

Measurement Model order (cp-subset first, then mate, then pooled v1-clean, then v1), both
selected (peak held-out pooled correlation) and final checkpoints, per the existing checkpoint-
selection rule -- the treatment checkpoint was never selected by auxiliary BCE.

| Arm | Benchmark | n | CpSubsetCorr | MateSubsetCorr | PooledCorr | RMSE | Bias |
|---|---|---|---|---|---|---|---|
| baseline (P3A-001) | v1-clean | 3,992 | 0.5941 | 0.6401 | 0.5933 | 1030.1 | -191.1 |
| P1-G04 (display only) | v1-clean | 3,992 | 0.5933 | 0.6358 | 0.5931 | 1030.2 | -189.4 |
| control, selected | v1-clean | 3,992 | 0.5924 | 0.5552 | 0.5673 | 1067.8 | -196.2 |
| control, final | v1-clean | 3,992 | 0.5912 | 0.6149 | 0.5668 | 1039.2 | -193.5 |
| **treatment, selected** | v1-clean | 3,992 | 0.5938 | 0.5551 | 0.5606 | 1067.3 | -198.1 |
| **treatment, final** | v1-clean | 3,992 | 0.5918 | 0.6134 | 0.5540 | 1042.7 | -194.7 |

**Primary decision metric (cp-only, v1-clean):**

| Arm | vs P3A-001 baseline | vs matched unnormalized control |
|---|---|---|
| control, selected | -0.0017 | +0.0000 |
| control, final | -0.0029 | -0.0012 |
| treatment, selected | **-0.0003** | **+0.0014** |
| treatment, final | **-0.0022** | **-0.0006** |

The treatment's cp-only regression versus P3A-001 is smaller in magnitude than the control's at
both checkpoints (-0.0003 vs -0.0017 selected; -0.0022 vs -0.0029 final), and the treatment beats
its own matched control at the selected checkpoint (+0.0014). These deltas are small relative to
the historically established pooled-correlation noise floor (sigma is approximately 0.0019 for
this benchmark family) and are not read as a primary-representation improvement -- they are
consistent with noise around a flat effect, which is also what the mechanism result predicts:
repairing (or here, only partially attenuating) the auxiliary task's own calibration is a
different question from whether the shared representation improves, and here neither the
mechanism criteria nor the primary metrics show a benefit.

**Reading against the interpretation branches:** this is closest to Branch A (mechanism fails)
rather than Branch B (mechanism passes, primary flat), since the mechanism criteria were not met.
The primary metrics happen to also be flat-to-negligibly-regressed, which is additional evidence
against promotion but is not the headline finding -- the headline finding is that RMS
normalization was an insufficient, not merely inconclusive, fix for the diagnosed pathology.

## 6. What this does and does not establish

Wording here is deliberately no stronger than the experiment supports -- "establishes" is
avoided in favor of "supports" throughout, per the correction above.

- It **supports** the hypothesis that the auxiliary head's sensitivity to the shared activation's
  magnitude was a real contributor to the P5-AUXHEAD-001 failure: removing that sensitivity
  produced a genuine, treatment-specific early improvement (the only arm of the two that ever
  beats the constant predictor, at 3 of 20 checkpoints) and a mostly-consistent, non-material
  reduction in logit polarization (16 of 20 checkpoints). It does not establish this as the sole
  or dominant cause -- see below.
- It does **not** establish that magnitude drift was the dominant or sufficient cause, and it does
  not establish that RMS normalization repairs the failure: none of the three preregistered
  criteria were jointly satisfied, the early improvement reversed by step 3,999, and the auxiliary
  head's weight norm grew substantially (4.5x) under the RMS-normalized input over the same
  window. That growth is **consistent with, not proof of,** the head-compensation risk the design
  record flagged -- no experiment here isolates it as the specific cause of the reversal, as
  distinct from other candidate explanations for why the intervention did not durably resolve the
  pathology.
- A bounded statement of what the evidence supports: RMS normalization substantially improved
  auxiliary learning early in training, but the improvement was not durable. The auxiliary head
  later re-entered an overconfident regime while its weight norm grew strongly. This supports the
  hypothesis that magnitude sensitivity contributed to the original pathology, but RMS
  normalization alone was insufficient to repair it.
- It does not establish, and does not need to establish, any primary-representation benefit:
  primary metrics are flat-to-slightly-regressed in both arms, consistent with mechanism failure.
- One seed. Not a strength claim, not a promotion candidate. No SPRT, no self-play, no export,
  no Stage-3 work followed from this result, per the design record's stopping conditions. Further
  multi-task work on this auxiliary head requires a new, mechanism-driven hypothesis -- not an
  automatic follow-on (LayerNorm, weight cap, temperature, aux-weight change, or scheduler
  change) chained onto this result.

## 7. What was and was not changed

Changed: `trainer/trainer/model/network.py` (`NnueNet.aux_rms_norm` flag and the RMS branch,
auxiliary-only, zero new parameters), `trainer/trainer/model/train.py`
(`TrainingConfig.aux_rms_norm` field, default `False`, plumbed into model construction only),
`trainer/scripts/phase5_auxhead_diagnostics.py` (parameterized run root/output root/tag,
auto-detects `aux_rms_norm` from each checkpoint's own saved config, added shared-activation
RMS, auxiliary-head weight norm, logit percentiles, extreme-decile fraction, and the train-
fitted-constant-on-held-out recomputation), new `trainer/scripts/phase5_auxhead_rms.py`
(two-arm driver plus provenance pinning and the evaluation-matrix report), new
`trainer/tests/model/test_auxiliary_rms_norm.py` (11 tests: default-off, no new state-dict keys,
old-config-dict compatibility, unnormalized-path identity, primary-forward identity, per-sample
unit RMS, manual-formula match, all-zero-activation finiteness, gradient isolation under RMS,
canonical/quantized export-array identity).

Not changed, and verified unchanged: primary `forward()` output (byte-for-byte, tested), the
default (`aux_rms_norm=False`) path (byte-for-byte, tested), the state-dict/export contract
(`checkpoint_to_canonical` and `quantize` produce bitwise-identical primary arrays under the
treatment, tested), any Java, exporter, quantizer, `.nnue`, search, UCI, or shard-format code,
any GitHub issue.
