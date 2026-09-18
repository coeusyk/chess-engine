# E-15 Phase E: offline evaluation report

Governs issue #223. Executes `DR-E15-stage3-first-retraining-preregistration.md` Phase E
(offline evaluation) only. No training, no SPRT, no parameter change of any kind. Downstream of
`DR-E15-phase-d-training-report.md` (Phase D matched training, both arms' selected and final
checkpoints). Evaluates against `measurement-model.md`'s existing, unmodified protocol per
`DR-E15-stage3-first-retraining-preregistration.md` section 11 (metrics) and section 12
(success/null/regression classification, pinned before training started).

Evaluation script: `trainer/scripts/phase_e15_phase_e_evaluation.py`. All four checkpoint SHA-256
hashes re-verified against `DR-E15-phase-d-training-report.md` section 7's frozen values
immediately before evaluation (all four matched, `MISMATCH` would have aborted the run before any
metric was computed). No target-definition rubric duality is needed here (unlike P4III) --
E-15 does not touch `target_cp()` at all, so every model below is graded under the one existing
rubric.

## 1. Held-out sets

- **Primary (v1 / v1-clean)**: `combine_and_split(stage1_dir, stage2-quiet-sf-v1, seed=42)`'s
  4,000-record held-out set, byte-identical to every prior Phase 3/4/5 experiment and to Phase D's
  own `v1_clean_membership_hash`. v1-clean (sentinel-filtered): 3,992 records -- matches
  `measurement-model.md`'s documented count exactly.
- **Secondary/exploratory (Stage-3 held-out)**: each arm's own `stage3-e15-<arm>-001` ingested
  shard, re-split via `split_by_game(seed=20261502, held_out_fraction=0.10)` -- reproducing
  `DR-E15-phase-bc-corpus-generation-report.md` section 7's exact row counts (control: 7,280
  train / 840 held-out; treatment: 7,155 train / 891 held-out).

**Train/held-out FEN overlap, computed directly (not assumed) for both arms**:

| | Control | Treatment |
|---|---|---|
| Held-out unique FENs | 140 | 882 |
| Shared with that arm's own Stage-3 training FENs | 140 | 24 |
| Held-out overlap fraction | **100.0%** | **2.72%** |

Confirms the task's stated overlap figures exactly. **Control's Stage-3 "held-out" set is not an
independent test set at all** -- every one of its 140 unique held-out FENs already appears in
control's own training data, a direct consequence of Phase B/C section 12's documented
single-repeated-trajectory collapse (all 58 control games are the same 140-ply line; whatever
subset of games `split_by_game` calls "held-out" still only contains FENs the model trained on
under a different game-ID label). **Any control Stage-3 held-out metric below is descriptive
only**, not a held-out generalization measurement, and is reported as such. Treatment's 2.72%
overlap is small but nonzero (24 shared FENs, mostly early-game positions both arms start from,
per `DR-E15-phase-bc-corpus-generation-report.md` section 7's disclosed by-construction overlap
mechanism) -- still exploratory per the preregistration, but a meaningfully more independent
reading than control's.

## 2. Primary/decisive metrics: v1-clean

Majority-population rule (`measurement-model.md` section 6) applied throughout: cp-only
correlation examined before the pooled number.

### Selected checkpoint (step 7999, both arms)

| Metric | Control | Treatment | Delta (T - C) |
|---|---|---|---|
| **cp-only correlation** (primary/decisive) | 0.6013 | 0.6071 | **+0.0058** |
| v1-clean pooled correlation (screening) | 0.5778 | 0.5775 | -0.0003 |
| mate-only correlation (mechanism only) | 0.6076 | 0.5805 | -0.0271 |
| RMSE, overall | 1049.24 | 1048.62 | -0.62 |
| RMSE, cp-labeled | 407.74 | 407.09 | -0.65 |
| Bias, cp-labeled | 8.29 | 7.00 | -1.29 |
| Compression, overall | 0.1178 | 0.1193 | +0.0015 |

### Final checkpoint (step 19999, both arms)

| Metric | Control | Treatment | Delta (T - C) |
|---|---|---|---|
| **cp-only correlation** (primary/decisive) | 0.5971 | 0.6050 | **+0.0079** |
| v1-clean pooled correlation (screening) | 0.5736 | 0.5743 | +0.0007 |
| mate-only correlation (mechanism only) | 0.6301 | 0.6078 | -0.0223 |
| RMSE, overall | 1039.02 | 1040.19 | +1.17 |
| RMSE, cp-labeled | 399.88 | 400.03 | +0.15 |
| Bias, cp-labeled | 6.91 | 7.03 | +0.12 |
| Compression, overall | 0.1385 | 0.1360 | -0.0025 |

## 3. Regression guards (RMSE / calibration, v1-clean, shared rubric)

No target definition changed in this experiment, so no rubric-duality evaluation is needed (unlike
P4III) -- every number above is directly comparable. RMSE and bias deltas are all sub-2cp on an
~400-1050cp scale, in both directions across the two checkpoints (treatment marginally better on
selected, marginally worse on final, both negligible relative to any effect this roadmap has
previously treated as material). **No meaningful RMSE or calibration regression in either
direction, at either checkpoint.**

## 4. Exploratory: Stage-3 held-out

| | Control selected | Control final | Treatment selected | Treatment final |
|---|---|---|---|---|
| n | 840 | 840 | 891 | 891 |
| Pooled correlation | 0.9987 | 0.9999 | 0.6525 | 0.6456 |
| cp-only correlation | 0.9987 | 0.9999 | 0.8360 | 0.8223 |
| mate-only correlation | n/a (0 mate samples) | n/a | 0.8724 | 0.8620 |
| RMSE, overall | 3.74 | 0.72 | 529.46 | 522.45 |

Control's near-1.0 correlation is the expected artifact of section 1's 100% train/held-out overlap
-- it is reading the model's fit to positions it trained on, not generalization, and is reported
descriptively only, per the task's explicit instruction. Treatment's reading (2.72% overlap) is a
meaningfully more independent, if still exploratory, self-play generalization check; its
correlation (0.65 pooled / 0.82-0.84 cp-only) is far below its own v1-clean numbers, consistent
with Stage-3 self-play positions being a different, harder distribution than the Stage 1/2 base
set -- informative, never decisive per `measurement-model.md`'s exploratory-tier rule.

## 5. Historical P3A-001 context (secondary, never the deciding comparison)

| | P3A-001 (v1-clean) |
|---|---|
| Pooled correlation | 0.5933 |
| cp-only correlation | 0.5941 |
| mate-only correlation | 0.6401 |

Shown per section 12's instruction as context only. P3A-001 was trained without any Stage-3 data
at all (`step-016999.pt`, Experiment 3A's checkpoint) -- both E-15 arms' cp-only readings
(0.597-0.607) sit close to or modestly above P3A-001's, but this is not evidence about the E-15
research question (Stage-3 data source effect), which is answered exclusively by the
treatment-vs-control comparison in sections 2-3. **Per the task's explicit instruction, this is
not reinterpreted as evidence that Stage-3 augmentation (either arm) beats no-Stage-3 training** --
P3A-001 differs from both E-15 arms in more than one respect (no Stage-3 data, different
random-seed trajectory through 20,000 steps on a different training-record concatenation) and was
never a matched control for that question.

## 6. Preregistered classification (section 12)

Applying section 12's pinned rule, unmodified, no new threshold invented:

- **Condition 1 (meaningful v1-clean pooled improvement, clearly exceeding the ~0.0004 same-K
  seed noise floor)**: **not met**. Selected-checkpoint pooled delta is -0.0003 (treatment
  slightly *below* control); final-checkpoint pooled delta is +0.0007. Both magnitudes are at or
  barely above the measured noise floor, and the sign is inconsistent between the two checkpoints
  -- not the "clearly exceeding, not merely nonzero" bar section 12.A requires.
- **Condition 2/3 (no meaningful RMSE/calibration regression)**: met, both checkpoints (section 3).
- **Condition 4 (majority-population/cp-only improvement or no degradation)**: met, both
  checkpoints, with a consistent-direction improvement (+0.0058 selected, +0.0079 final) -- larger
  in magnitude than the pooled screening delta, though `measurement-model.md` notes no independent
  seed-noise floor has yet been measured for cp-only correlation specifically, so this movement is
  suggestive, not independently calibrated against its own noise floor.

**Classification: B -- Null / inconclusive.** Condition 1 (the mandatory screening gate) is not
clearly met, which is sufficient on its own to rule out classification A regardless of condition
4's favorable reading -- per section 12.A, the screening condition and the majority-population
condition are both required, not alternatives. The result is also not classification C
(regression): cp-only correlation, the single most decisive rejection signal per
`measurement-model.md` section 1a, moved in treatment's favor at both checkpoints, and RMSE/
calibration show no regression in either direction. This is a valid, complete, and non-manufactured
outcome per section 12's own explicit instruction ("a null result is a complete, valid E-15
outcome, not a failed experiment requiring a silent rerun with different parameters").

## 7. Selected-vs-final consistency

The classification is **stable across both checkpoints**: both selected (step 7999) and final
(step 19999) independently show the same qualitative pattern -- a flat-to-negligible, sign-unstable
pooled v1-clean delta at or near the noise floor; a small, consistently-signed positive cp-only
delta; no RMSE/calibration regression; a treatment-side mate-only correlation dip (-0.022 to
-0.027) that is exploratory-tier and small-n (472 mate-labeled v1-clean records), not acted on per
section 12's own scope (mate-only is validated mechanism, never a promotion/rejection input). No
divergent conclusion would have been reached by reporting only one checkpoint instead of both,
satisfying `measurement-model.md` section 8's mandatory both-checkpoints requirement in substance,
not just form.

## 8. What this does and does not conclude

This is the primary causal comparison (treatment-trained vs. control-trained, section 12's pinned
comparison) -- not treatment vs. historical P3A-001 (section 5, context only). The result does not
show that seeded-diversity Stage-3 self-play data (`DR-E14`'s mechanism) improves the NNUE relative
to a matched rank-0 control by a margin distinguishable from measurement noise on the primary
screening metric, though the majority-population (cp-only) metric moved consistently in treatment's
favor at both checkpoints, a direction worth carrying forward as a data point (not a promotable
finding) for any future, differently-scoped Stage-3 experiment. No SPRT is run (section 13's
boundary, unchanged). No preregistered parameter (corpus size, selector config, search depth,
mixing rule, schedule, seed, checkpoint-selection rule, or the section 12 classification rule
itself) was changed after seeing these results.

## 9. Issue #223 final state

All five phases (A-E) are complete. No SPRT was run at any point in this issue's lifecycle. Every
parameter pinned in `DR-E15-stage3-first-retraining-preregistration.md` before Phase B started
remains unchanged through Phase E. Per section 15's own rule, this null result closes E-15 as a
complete experiment -- a future differently-scoped Stage-3 experiment (a different selector
parameter, corpus size, or search budget) would require a new experiment ID, not a reopening of
this one.
