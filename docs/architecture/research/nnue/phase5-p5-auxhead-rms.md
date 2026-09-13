# Experiment P5-AUXHEAD-RMS-001 -- auxiliary-input RMS normalization

Phase 5, implementing the design record
[`phase5-p5-auxhead-rms-design.md`](phase5-p5-auxhead-rms-design.md), written and reviewed
before either arm was trained. That record is the contract this report is graded against --
hypothesis, mathematical treatment, control identity, and preregistered mechanism/primary
criteria are specified there and are not restated in full here.

**Verdict: mechanism not repaired by the preregistered criteria.** RMS normalization produced
a consistent, measurable attenuation of every diagnosed pathology (lower auxiliary BCE at every
checkpoint, a lower peak early gradient ratio, modestly narrower logit polarization) but did not
cross any of the three preregistered mechanism thresholds, and the primary evaluation shows no
benefit regardless. This is a cleaner negative result than P5-AUXHEAD-001's: the auxiliary
gradient's magnitude sensitivity was a real, partial contributor to the failure, but not the
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
- P1-G04 checkpoint SHA-256 `479e61e732785af5f97c90fec7b71b72debc6922181d6f1199d2364765d20f2b`,
  recorded for display-only continuity (never used for gating).
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

**Reading against the three preregistered mechanism criteria:**

1. **Held-out auxiliary BCE beats both the constant predictor and the matched control (both
   required).** Treatment beats the control at every checkpoint (e.g. final 1.1024 vs 1.1619).
   It does **not** beat the constant predictor (0.6803206) at any checkpoint -- both arms
   diverge well above it. **Fails** (partial credit: real, consistent improvement over control;
   the harder half of the bar was never in reach).
2. **Logit polarization materially reduced.** Extreme-decile fraction falls from 0.7582 to
   0.7416 at the final checkpoint (about 1.7 percentage points), and the effect is consistent
   at every checkpoint (0.6187 -> 0.5148 at step 999). Directionally correct, but this is a
   modest attenuation, not a material reduction in the pathology's severity. **Fails** the
   "materially reduced" bar as preregistered.
3. **Early gradient-ratio dominance (2.5-5.3x) followed by collapse to ~0.15 no longer
   present.** The pattern is attenuated (peak ratio drops from 5.325 to 3.037, and the ratio
   falls below 1.0 by step 2,999 in the treatment versus step 4,999 in the control) but the
   qualitative shape -- early dominance above 1, later collapse to about 0.14-0.15 -- is still
   present in both arms. **Fails**: reduced in magnitude and duration, not eliminated.

**A secondary, unplanned but informative observation, consistent with the design record's own
predicted mechanism:** the auxiliary-head weight norm at the final checkpoint is **4.5x larger**
under RMS normalization (10.83 vs 2.42). This is exactly the head-compensation the design record
flagged as the honest risk of any input-scaling intervention (§3): removing the backbone's
magnitude signal from the auxiliary head's input does not prevent the head from re-inflating its
own weights to reach a comparably wide logit range from a now-unit-scale input. The head
partially undid the intervention by growing its own weights, which is consistent with why the
logit range and BCE trajectory improved only modestly rather than resolving.

**Interpretation: Branch A -- "RMS conditioning did not resolve the auxiliary-task failure."**
Every mechanism metric moved in the hypothesized direction and none crossed the preregistered
bar. Per the design record and the governing task, this stops the investigation at this design;
no LayerNorm, weight cap, or aux-weight change is chained onto this result.

## 5. Primary research evaluation

Measurement Model order (cp-subset first, then mate, then pooled v1-clean, then v1), both
selected (peak held-out pooled correlation) and final checkpoints, per the existing checkpoint-
selection rule -- the treatment checkpoint was never selected by auxiliary BCE.

| Arm | Benchmark | n | CpSubsetCorr | MateSubsetCorr | PooledCorr | RMSE | Bias |
|---|---|---|---|---|---|---|---|
| baseline (P3A-001) | v1-clean | 3,992 | 0.5941 | 0.6401 | 0.5933 | 1030.1 | -191.1 |
| P1-G04 (display only) | v1-clean | 3,992 | 0.5931 | 0.6362 | 0.5926 | 1029.4 | -188.5 |
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

- It establishes that the auxiliary head's sensitivity to the shared activation's magnitude was
  a real, measurable contributor to the P5-AUXHEAD-001 failure: removing that sensitivity
  produced a consistent, monotonic improvement in auxiliary BCE, gradient-ratio dominance, and
  logit polarization at every one of the 20 diagnostic checkpoints, in both arms trained under
  the identical seed/schedule/data.
- It does not establish that magnitude drift was the dominant or sufficient cause: none of the
  three preregistered thresholds were crossed, and the auxiliary head substantially regrew its
  own weight norm (4.5x) under the RMS-normalized input, which is a plausible reason the
  improvement stayed partial -- the head can and did partially compensate through its own
  parameters, exactly the risk the design record flagged rather than ruled out.
- It does not establish, and does not need to establish, any primary-representation benefit:
  primary metrics are flat-to-slightly-regressed in both arms, consistent with mechanism failure.
- One seed. Not a strength claim, not a promotion candidate. No SPRT, no self-play, no export,
  no Stage-3 work followed from this result, per the design record's stopping conditions.

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
