# E-16 Phase D: matched training report

Governs issue #224. Executes `DR-E16-shared-opening-prefix-preregistration.md` Phase D (matched
training, seeds 42/43/44) only. No Phase E evaluation, no SPRT. Downstream of
`DR-E16-phase-c-split-and-equalization-report.md` (Phase C).

Execution baseline: branch `phase/15-nnue`, commit `4ebc4edad7adb8ee135d5ed47d3ca2db8ec3bfe9`
(`4ebc4ed` short form) -- the commit the Phase C report itself landed on.

## 1. Preflight -- final dataset hashes and counts

Base data reconstructed via the unmodified `combine_and_split(stage1_dir, stage2-quiet-sf-v1,
seed=42)`, identical call/seed/inputs to `DR-E15-phase-d-training-report.md` section 1:

| | Count | Membership hash |
|---|---|---|
| Base training records | 36,000 | `dc83b66d3b7408c9d0419ead6b79797143382c2d33012eea53394b3a4066adfe` |
| Base held-out (v1) | 4,000 | (unfiltered, not used directly) |
| v1-clean (sentinel-filtered held-out) | 3,992 | `39f3d4f3e1115927e305986d77315e5ad79663d7f259bc56c0b7ef6f5919058b` |

3,992 matches `measurement-model.md`'s own documented v1-clean count and E-15's own Phase D
preflight exactly -- independent re-confirmation that the base dataset is unchanged.

Stage-3 equalized slices re-derived by replaying Phase C's own deterministic pipeline (ingested
shard -> `split_by_fixed_game_ids` -> seeded row-budget equalization, no code changed) and
verified byte-for-byte against the frozen Phase C hashes given as this phase's input, before any
training list was assembled:

| | Rows | Equalized membership hash | Matches frozen Phase C value |
|---|---|---|---|
| Control | 6,911 | `ee1443785d21311b77e3c98261b4b6f6ede1a787b761d6adad81de07bc0df862` | yes, exact |
| Treatment | 6,911 | `cbb7246560012e3b5bb537e3f1150eca3de5ebf2faf50d0d02cfc19499454eb5` | yes, exact |

Final per-arm training lists (base records first, in established order, then the arm's frozen
Stage-3 slice, no deduplication): **control 42,911 rows, treatment 42,911 rows** -- both exactly
`36,000 + 6,911`, as pinned. No base-side subsampling occurred.

## 2. Initialization (one fresh init per seed, verified byte-identical across arms)

For each seed `s` in `{42, 43, 44}`: `seed_everything(s)` then `NnueNet(hidden_width=256, qa=127,
qb=64, output_scale=400)` (architecture unchanged), saved to
`trainer/outputs/phase-e16/init/e16-init-seed{s}.pt`:

| Seed | Init file SHA-256 | Pre-training parameter-tensor hash |
|---|---|---|
| 42 | `8954645be299c9c3705e74b20e7bdebbd37ffb8ecbd2232be534c83bd087c4eb` | `3bc4149e26a3371ce3e517d3a07a2c015225a8a2c9868e06abe88cbcc52d1e17` |
| 43 | `92fe9ddf8bc4bbb7c12419b55d3ec923df789c57a60cda725bd0d2289675701e` | `09ada047a47b7d3f36ab0ed8fdff846d4848eb4a93c6d60f359c62a283042b03` |
| 44 | `f2e2efb51591f3512742f9e8ade905f6725f045cd2bb0b721dd8a790eb866452` | `41a2e4b742f4b79377dcbca8b74438ba6b535926b1802eb3054d2ef492602047` |

**Verified, not merely assumed, byte-identical between arms**: for each seed, the exact saved
`state_dict()` was loaded into two independently constructed `NnueNet` instances -- one
constructed after `seed_everything(s)`, the other after `seed_everything(999)` (a deliberately
different seed, proving the load overrides whatever the constructor's own RNG state would
otherwise have produced) -- and a SHA-256 over each model's full parameter-tensor set (sorted by
key, tensor bytes concatenated) was computed. **Both hashes matched exactly for all three seeds**
(the "pre-training parameter-tensor hash" column above). Note `s=42`'s init reproduces E-15's own
`e15-init-seed42.pt` architecture/seed exactly but is a distinct file/artifact for this
experiment's own provenance -- not reused across experiments, per this project's frozen-artifact
convention.

Both training arms for a given seed then loaded this exact same saved `state_dict` via `train()`'s
`initial_state_dict` parameter.

## 3. Matched-pair integrity (per seed, explicit proof)

| Seed | Identical starting param hash | Identical `TrainingConfig` | Identical base records/order | Identical total row count | Only Stage-3 content differs |
|---|---|---|---|---|---|
| 42 | yes | yes | yes | yes (42,911 = 42,911) | yes |
| 43 | yes | yes | yes | yes (42,911 = 42,911) | yes |
| 44 | yes | yes | yes | yes (42,911 = 42,911) | yes |

`TrainingConfig` per arm, per seed: `hidden_width=256, qa=127, qb=64, output_scale=400,
k=2.773456, learning_rate=0.01, seed=s, steps=20000, batch_size=256, lr_schedule=cosine,
warmup_steps=200, wdl_lambda=1.0` -- byte-identical between control and treatment within each seed
group, differing only in `seed` *between* seed groups (never between arms within one seed), exactly
as required. `v1-clean` held-out membership (`39f3d4f3...919058b`) is the identical literal record
list passed to every one of the six `train()` calls -- **identical across all six runs**, not
merely equal by coincidence. Each arm's `final_training_membership_hash` (over the full 42,911-row
concatenated list: base + that arm's Stage-3 rows) is identical across all three seeds *within* an
arm (control: `9a19f99f...e6e9a93` for all of s=42/43/44; treatment: `64eaa09d...5b60d2e89` for all
of s=42/43/44) and differs *between* arms -- confirming the only thing that ever varies between
control and treatment, for any seed, is Stage-3 row content, exactly as designed.

## 4. Training runtimes

| Seed | Arm | Wall-clock |
|---|---|---|
| 42 | control | 126.1s |
| 42 | treatment | 127.8s |
| 43 | control | 127.4s |
| 43 | treatment | 124.6s |
| 44 | control | 118.9s |
| 44 | treatment | 119.6s |

Total: 744.4s (~12.4 min) for all six runs.

## 5. Checkpoint selection (existing historical v1-clean mechanism, unchanged)

Selection rule unchanged: highest `held_out_correlation` among the 20 logged diagnostic points
(`log_interval=1000` over 20,000 steps, matching `DR-E15-phase-d-training-report.md`'s own
convention), ties broken toward the later step (`>=` comparison, unmodified). Reporting this value
is permitted as part of the frozen training protocol's own checkpoint-selection mechanism -- it is
not a Phase-E Measurement Model evaluation.

| Seed | Arm | Selected step | held_out_correlation at selection | Selected checkpoint SHA-256 |
|---|---|---|---|---|
| 42 | control | 4999 | 0.5765 | `08be35547c4c9343c5b24a2038af523fd0e4ff0bcbb7dfe673ac98ef9fcf3863` |
| 42 | treatment | 6999 | 0.5866 | `16dcab0dc7324bb7b2eb273bd06320b0701e3b99693f38f472d6f066df0e6604` |
| 43 | control | 7999 | 0.5761 | `cf0c9f2579df5d8c887a3f6794e73e9204edbc806aa18cdec2d73e20101ac2e2` |
| 43 | treatment | 7999 | 0.5771 | `b5f2ec97c00b5685f088e2f9a2c75c94080b62749099b7c7b1a4d26975da7b32` |
| 44 | control | 5999 | 0.5798 | `947e272ccc190abb006841fced5685d250ff3f006854e836050d76a6730f1a44` |
| 44 | treatment | 7999 | 0.5802 | `f351bc6851708db9090c198aefcb245bcc2ede1635c3dcb167515a7ada4fdfb5` |

Unlike E-15's own single-seed run (where both arms happened to select the identical step, 7999),
E-16's three seed replicates show the selected step moving around (4999/5999/6999/7999) --
expected once genuine seed-to-seed data-order/initialization variance enters the picture, not an
anomaly. No cross-run coordination exists in the selection logic; each of the six selections ran
independently over that run's own 20 diagnostic points.

## 6. Final checkpoints (step 19999, all six runs)

| Seed | Arm | Final checkpoint SHA-256 |
|---|---|---|
| 42 | control | `bb7d188be30caefad5a3a321968cfdc2c14dd07a20b854446880adbdc8378280` |
| 42 | treatment | `3ca102658bea31f79d30c41c58846672820c14f4d4909f51aff208fb78e5a44a` |
| 43 | control | `f08e3ee61da09938db5c38e8510467747170a7f0629142c799927ac8136c81df` |
| 43 | treatment | `d2e88c5c537a750fee9728243549cbca603ffe3d34bf6e0613aaf9088aef2e5c` |
| 44 | control | `8e7971be1c1930eeeb430d889c7ff8bc9111344086bb241e4d54065796877a29` |
| 44 | treatment | `cf177debbe82cdb656d9bb5862386a761403cd48ec9f126f5c0d5df3cdbb46c4` |

## 7. Loss trajectory and anomaly summary

| Seed | Arm | Train loss (first logged / last / min / max) | Any NaN/Inf |
|---|---|---|---|
| 42 | control | 0.0916 / 0.0044 / 0.0032 / 0.1638 | no |
| 42 | treatment | 0.0874 / 0.0037 / 0.0031 / 0.1634 | no |
| 43 | control | 0.0818 / 0.0092 / 0.0033 / 0.1651 | no |
| 43 | treatment | 0.0830 / 0.0098 / 0.0031 / 0.1618 | no |
| 44 | control | 0.0823 / 0.0072 / 0.0031 / 0.1553 | no |
| 44 | treatment | 0.0791 / 0.0074 / 0.0031 / 0.1550 | no |

**Zero NaN/Inf across all six runs' full per-step loss trajectories and all logged diagnostic
points (train loss, held-out loss, gradient norm). No runtime anomalies. No run crashed or
deviated.**

## 8. What was deliberately not done in this phase

No Phase-E evaluation metric (offline held-out correlation *comparison* between arms, RMSE,
calibration, majority-population cp-only correlation) was inspected to make any training decision
-- the `held_out_correlation` values in section 5 were read only for the existing, unmodified
checkpoint-selection mechanism and for this report's anomaly-detection purpose (confirming no
NaN/divergence across the six runs), not to compare control against treatment or judge the
experiment's outcome. No schedule change, early stop, retuning, checkpoint-selection-rule change,
deduplication, or per-opening rebalancing occurred for any run. No SPRT was run. `#224` remains
open for Phase E.

## 9. Frozen artifacts (this phase's provenance)

| Artifact | SHA-256 |
|---|---|
| Init, seed 42 | `8954645be299c9c3705e74b20e7bdebbd37ffb8ecbd2232be534c83bd087c4eb` |
| Init, seed 43 | `92fe9ddf8bc4bbb7c12419b55d3ec923df789c57a60cda725bd0d2289675701e` |
| Init, seed 44 | `f2e2efb51591f3512742f9e8ade905f6725f045cd2bb0b721dd8a790eb866452` |
| Control final training membership (all seeds) | `9a19f99f21e14f24fb0b0cee72a98ba6423cf2c96f40250fc85e04080e6e9a93` |
| Treatment final training membership (all seeds) | `64eaa09d0903804e730c68a957b9b3cf8a6351aedbb3f324f94005a5b60d2e89` |
| Shared v1-clean held-out membership | `39f3d4f3e1115927e305986d77315e5ad79663d7f259bc56c0b7ef6f5919058b` |
| Control selected checkpoints (seeds 42/43/44) | `08be35547c...f9fcf3863` / `cf0c9f2579...20101ac2e2` / `947e272ccc...6730f1a44` |
| Treatment selected checkpoints (seeds 42/43/44) | `16dcab0dc7...6df0e6604` / `b5f2ec97c0...975da7b32` / `f351bc6851...ada4fdfb5` |
| Control final checkpoints (seeds 42/43/44) | `bb7d188be3...adbdc8378280` / `f08e3ee61d...136c81df` / `8e7971be1c...5796877a29` |
| Treatment final checkpoints (seeds 42/43/44) | `3ca102658b...fb78e5a44a` / `d2e88c5c53...9088aef2e5c` / `cf177debbe...cdbb46c4` |

Training seeds 42, 43, 44 and the frozen schedule (`NnueNet(256,127,64,400)`, 20,000 steps, lr
0.01, cosine schedule, warmup 200, batch 256, k=2.773456, wdl_lambda=1.0) were used exactly as
preregistered; none were changed after seeing any result.

## 10. Phase E readiness

All six runs (3 seeds x 2 arms) trained to completion under byte-identical, verified-not-assumed
initialization per seed, identical `TrainingConfig` within each seed pair, identical base
records/order and total row count, and identical held-out evaluation set -- differing only in
Stage-3 training-row content. Zero NaN/Inf, zero crashes, zero deviations from the frozen protocol.
Both selected and final checkpoints are available for all six runs, as required by
`measurement-model.md` section 8's mandatory reporting rule. **Phase E (offline evaluation against
the Measurement Model's primary/secondary/exploratory metrics, paired-by-training-seed
interpretation across all three seed pairs) is ready to begin whenever explicitly authorized -- not
started by this document.** No SPRT was run.
