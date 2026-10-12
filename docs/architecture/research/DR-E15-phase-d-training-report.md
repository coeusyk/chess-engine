# E-15 Phase D: matched training report

Governs issue #223. Executes `DR-E15-stage3-first-retraining-preregistration.md` Phase D
(matched training) only. No Phase E evaluation, no SPRT. Downstream of
`DR-E15-phase-bc-corpus-generation-report.md` (Phase B/C) and
`DR-E15-phase-d-stage2-dataset-recovery.md` (the Stage-2 base-dataset recovery this phase's
preflight depended on).

## 1. Preflight (rerun before training, all reproduced exactly)

- No machine-specific absolute paths remain in any tracked file (re-verified against the
  `chore(privacy)` cleanup commit before training started).
- Every frozen Phase-B/C Stage-3 hash (control/treatment train, held-out, equalized
  membership) reproduced unchanged.
- Base split via `combine_and_split(stage1_dir, stage2-quiet-sf-v1, seed=42)`: 36,000
  training / 4,000 held-out (v1), identical record membership and order for both arms
  (same deterministic call, same inputs).
- **v1-clean** (the sentinel-filtered held-out set `measurement-model.md` defines as the
  primary/decisive held-out benchmark): 3,992 records after removing the 8 held-out-side
  records whose `eval_cp` is one of the three confirmed sentinel values
  (`{-9605, 9605, 20000}`) -- matches `measurement-model.md`'s own documented count exactly,
  independent confirmation that `stage2-quiet-sf-v1` really is content-identical to the
  historical Stage 2 source.
- Final training-list construction: control and treatment share byte-identical base-record
  membership and order (36,000 records), each arm's own frozen equalized Stage-3 slice
  (7,155 rows) is unchanged, **43,155 final training records per arm**, no deduplication.

## 2. Initialization

One fresh model constructed under the preregistered seed: `seed_everything(42)` then
`NnueNet(hidden_width=256, qa=127, qb=64, output_scale=400)` (architecture unchanged, per
DR-E15 section 8). Saved to
`trainer/outputs/phase-e15/init/e15-init-seed42.pt`, SHA-256
`c52aecbac7c261f973fd41bf416daf93f482a48a782da0efcaacae4c1da52edd`.

**Verified, not merely assumed, byte-identical**: loaded that exact saved `state_dict()`
into two independently constructed `NnueNet` instances (one preceded by `seed_everything(999)`,
a deliberately different seed, to prove the load overrides whatever the constructor's own RNG
state would otherwise have produced) and computed a SHA-256 over each model's full parameter
tensor set (sorted by key, tensor bytes concatenated). Both hashes:
`3bc4149e26a3371ce3e517d3a07a2c015225a8a2c9868e06abe88cbcc52d1e17` -- identical. Both training
arms then loaded this exact same saved file via `train()`'s `initial_state_dict` parameter
(re-verified the init file's SHA-256 against the pinned value immediately before each arm's
`train()` call, as a hard precondition).

## 3. Matched-config verification

Both arms' `TrainingConfig` (recorded per-arm in `pre_training_manifest.json` before training
started) are byte-identical: `hidden_width=256, qa=127, qb=64, output_scale=400, k=2.773456,
learning_rate=0.01, seed=42, steps=20000, batch_size=256, lr_schedule=cosine, warmup_steps=200,
mate_weight=1.0, mate_target_distance_aware=False, wdl_lambda=1.0, aux_wdl_weight=0.0,
aux_rms_norm=False`. `v1_clean_membership_hash` (the held-out set passed to `train()` for
diagnostics/checkpoint-selection) is identical between arms:
`b384af20787868d356b3f8971eeb22a293f4a446fcec4dcd5e3c36ae45d8a111` -- confirming both arms were
evaluated against the exact same held-out records throughout training. **The only intended
difference between the two runs is `final_training_membership_hash`** (control
`9ca948de...52cf68d`, treatment `2d699253...3410b942`) -- i.e. exactly and only the Stage-3 row
content, as designed.

## 4. Control-arm training

- Wall-clock: 142.7s.
- Final dataset membership hash: `9ca948de458da534b42a73e2db3208a7e6916c4c757dc21476a0758a452cf68d`.
- Selected checkpoint: step 7999 (peak `held_out_correlation` = 0.5778 among the 20 logged
  diagnostic points), SHA-256 `e4580b579fe5287a945c0db02e8e920209f3df2f35907e7ff747e8beab49e2e8`.
- Final checkpoint: step 19999, SHA-256 `96678045d6de2fc1133a06cfc74b27a309a8664fb06ef983230340a96e6e25c0`.
- Train loss: first-logged (step 999) 0.0691, last (step 19999) 0.0052, min 0.0026, max 0.1456
  (max is the very first unlogged step, before the first `log_interval` point).
- Held-out (v1-clean) loss: 0.1034 (step 999) -> 0.0766 (step 19999), monotonically worsening
  after an early best near step 3999-7999 (0.0734-0.0752) -- classic overfitting-past-the-peak
  shape, not an anomaly.
- Held-out correlation: rises from 0.4985 (step 999) to a peak band of ~0.574-0.578 across
  steps 3999-19999, non-monotonic but flat within a narrow ~0.004 range for the entire back
  half of training -- consistent with the measurement model's own documented ~0.0004 same-K
  seed noise floor being an order of magnitude smaller than this band, i.e. this flatness is a
  real training-dynamics plateau, not noise.
- **Zero NaN/Inf in the loss trajectory. No runtime anomalies.**

## 5. Treatment-arm training

- Wall-clock: 153.2s.
- Final dataset membership hash: `2d699253b48fcf89c228f134ab6f4bc8a0787a15d66d431208090b8d3410b942`.
- Selected checkpoint: step 7999 (peak `held_out_correlation` = 0.5775), SHA-256
  `1a7d62046b0a1f57a7aa43f017a1252c63e47f4140e449f4e89b058a45e7d606`.
- Final checkpoint: step 19999, SHA-256 `9cb4626d8b2f28898cd3ff04c05f6de3bd32327a7ff625328f1b6bc1518db460`.
- Train loss: first-logged (step 999) 0.0755, last (step 19999) 0.0059, min 0.0032, max 0.1634.
- Held-out (v1-clean) loss: 0.1008 (step 999) -> 0.0759 (step 19999), the same
  overfitting-past-the-peak shape as control.
- Held-out correlation: rises from 0.4773 (step 999) to the same ~0.574-0.578 plateau band
  across steps 3999-19999.
- **Zero NaN/Inf in the loss trajectory. No runtime anomalies.**

Both arms independently selected **the same step (7999)** as their peak-correlation
checkpoint -- notable, not manipulated: `_select_best_checkpoint()` ran unmodified and
independently per arm, over each arm's own 20 logged diagnostic points, with no cross-arm
coordination in the selection logic itself.

## 6. What was deliberately not done in this phase

No Phase-E evaluation metric (offline held-out correlation *comparison* between arms, RMSE,
calibration, majority-population cp-only correlation) was inspected to make any training
decision -- the `held_out_correlation` values above were read only for the existing, unmodified
checkpoint-selection mechanism and for this report's anomaly-detection purpose (confirming no
NaN/divergence), not to compare control against treatment or judge the experiment's outcome.
No schedule change, early stop, retuning, or parameter adjustment occurred for either arm. No
SPRT was run. `#223` remains open for Phase E.

## 7. Frozen artifacts (this phase's provenance)

| Artifact | SHA-256 |
|---|---|
| Init (`e15-init-seed42.pt`) | `c52aecbac7c261f973fd41bf416daf93f482a48a782da0efcaacae4c1da52edd` |
| Control selected checkpoint (step 7999) | `e4580b579fe5287a945c0db02e8e920209f3df2f35907e7ff747e8beab49e2e8` |
| Control final checkpoint (step 19999) | `96678045d6de2fc1133a06cfc74b27a309a8664fb06ef983230340a96e6e25c0` |
| Treatment selected checkpoint (step 7999) | `1a7d62046b0a1f57a7aa43f017a1252c63e47f4140e449f4e89b058a45e7d606` |
| Treatment final checkpoint (step 19999) | `9cb4626d8b2f28898cd3ff04c05f6de3bd32327a7ff625328f1b6bc1518db460` |
| Control final training membership | `9ca948de458da534b42a73e2db3208a7e6916c4c757dc21476a0758a452cf68d` |
| Treatment final training membership | `2d699253b48fcf89c228f134ab6f4bc8a0787a15d66d431208090b8d3410b942` |
| Shared v1-clean held-out membership | `b384af20787868d356b3f8971eeb22a293f4a446fcec4dcd5e3c36ae45d8a111` |

## 8. Phase E readiness

Both arms trained to completion under byte-identical initialization, identical schedule, and
identical held-out evaluation set, differing only in Stage-3 training-row content. Both
selected and final checkpoints are available for both arms, as required by
`measurement-model.md` section 8's mandatory reporting rule for an experiment introducing a new
training-data subset. **Phase E (offline evaluation against the Measurement Model's primary/
secondary/exploratory metrics, section 11-12's interpretation) is ready to begin whenever
explicitly authorized -- not started by this document.**
