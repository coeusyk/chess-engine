# P4I replication: is the low-K correlation gain reproducible under the actual Phase 4 regime?

**Date**: 2026-07-21. **Status**: Complete. **Decision: terminate K exploration.** The correlation
gain on the primary benchmark (v1-clean) reproduces tightly (+0.0044 → +0.0040, seed-to-seed
spread only 0.0004 — an order of magnitude below the effect itself). Every other readout — v1
correlation, and all four secondary metrics (mate/cp compression, magnitude-bucket MAE) — has a
same-K seed-to-seed spread *larger than* the baseline-vs-low difference it was being used to
interpret, which means none of them can confirm or deny the effect at this sample size; they are
not evidence of "reversal," they are noise. **This experiment terminates K exploration not because
the effect disproved itself, but because the only signal that clears seed noise is a small,
single-benchmark correlation gain (+0.004) with no secondary metric able to corroborate it** — the
task's AND-gated promotion rule requires corroboration this experiment cannot supply either way.
This experiment continues [`phase4-p4i-k-sweep.md`](phase4-p4i-k-sweep.md), whose §6 disclosed low
K's result as threshold-contingent (2σ, not 3σ) rather than a clean signal — this experiment tests
that contingency directly, by measuring variance under the configuration that actually matters
instead of leaning on Phase 1's cross-configuration estimate.

## 1. Scope and constraints

Per this task's explicit instruction: only the random seed changed (43, vs. P4I-001-low's 42) —
continuing this project's own established repeated-seed convention (research doc §27.2's
P1-G00/-S43/-S44). K is unchanged (2.149428, identical to P4I-001-low). No additional K values
were evaluated. The production K value was not changed. One new file added:
`trainer/scripts/phase4_p4i_replication.py`, which imports and reuses `phase4_p4i_k_sweep.py`'s
own training/evaluation helpers (`_train_arm`-equivalent inline call to `train()`, `_eval_cell`,
`_load_model`, `_select_best_checkpoint`) rather than duplicating them — the training/evaluation
code path is byte-identical to P4I's own; only the `TrainingConfig.seed` value passed in differs.

## 2. Graphify discovery (mandatory)

`graphify . --update --code-only`: 2,529 nodes / 6,961 edges / 164 communities, 272 code files
found (270 pre-P4I + P4I's own 2 scripts) — matching the file count expected if nothing changed
since P4I's commit. **Directly confirmed via `git diff --stat bdc2177 HEAD` (empty) before any
new file was written** — the working tree was byte-identical to P4I's own commit at the start of
this task, not merely graph-inferred. **No additional modules have changed since P4I.**

## 3. Replication setup

| | P4I-001-low (original) | P4I-003 (replicate) |
|---|---|---|
| K | 2.149428 | 2.149428 (unchanged) |
| Seed | 42 | **43** |
| Steps / LR / schedule / warmup | 20,000 / 0.01 / cosine / 200 | identical |
| Training corpus | 35,933 (sentinel-filtered, §35.9) | identical |
| Split seed | 42 | identical |
| Checkpoint selection | peak held-out correlation | identical mechanism |

Training records, held-out sets (v1/v1-clean), and the diagnostic sample are all reproduced via
the identical `combine_and_split(seed=42)` + sentinel-filter call — byte-identical to every prior
experiment's training corpus in this roadmap.

## 4. Replication report

| Arm | Selected step | Wall clock | Held-out corr. (v1) @ selected |
|---|---|---|---|
| P4I-001-low (seed 42) | 10,999/19,999 | 158.4s | 0.5328 |
| **P4I-003 (seed 43)** | **4,999/19,999** | 154.4s | 0.5292 |

**The selected step differs substantially between seeds** (10,999 vs. 4,999) — both are within the
established plateau region (§27.3/§35.4's pattern: correlation rises then flattens from
~5,000-11,000 steps onward for this schedule), so this is not itself anomalous, but it is a first
concrete illustration of how much a single seed change can move even *which step* gets selected,
before looking at the correlation numbers themselves.

## 5. Metric comparison

### 5.1 Correlation, RMSE, calibration

| Arm | Seed | Benchmark | n | Correlation | RMSE | Bias | Compression |
|---|---|---|---|---|---|---|---|
| Baseline (=P3A-001) | 42 | v1 | 4,000 | 0.5290 | 1240.4 | −215.2 | 0.1259 |
| Baseline (=P3A-001) | 42 | v1-clean | 3,992 | 0.5933 | 1030.1 | −191.1 | 0.1487 |
| Original low | 42 | v1 | 4,000 | 0.5328 | 1232.5 | −214.7 | 0.1389 |
| Original low | 42 | v1-clean | 3,992 | 0.5977 | 1021.3 | −190.6 | 0.1640 |
| **Replicate** | **43** | v1 | 4,000 | **0.5292** | 1250.5 | −217.9 | 0.1086 |
| **Replicate** | **43** | v1-clean | 3,992 | **0.5974** | 1040.9 | −193.7 | 0.1282 |

### 5.2 Correlation deltas vs. baseline — the reproducibility question, directly

| Benchmark | Original low Δ (seed 42) | Replicate Δ (seed 43) | Same-K seed spread | Resolvable at n=2? |
|---|---|---|---|---|
| v1 | +0.0038 | **+0.0003** | 0.0035 | No — spread ≈ effect, unresolvable |
| v1-clean | +0.0044 | **+0.0040** | 0.0004 | Yes — spread ≪ effect, gain holds |

**This divergence is itself explained by seed noise, not by v1 "failing to reproduce" as a separate
finding.** §6 below measures the same-K (seed 42 vs. seed 43) spread directly: on v1 it is 0.0035
— larger than either seed's own delta from baseline (+0.0038, +0.0003). On v1-clean it is 0.0004 —
an order of magnitude smaller than the ~0.004 effect. In other words: v1's number isn't resolvable
either way at n=2 (the "−92%" figure above is well within one seed-swap's worth of noise, not a
demonstrated loss of effect); v1-clean's is resolvable, and there the gain holds up. This is
consistent with — and reinforces — v1's own established noise-sensitivity (§32.4): a result that
is measurable on the cleaner benchmark and unmeasurable (not "gone," *unmeasurable*) on the noisier
one is exactly the pattern §32.4's mechanism predicts.

### 5.3 Mate-labeled and cp-labeled calibration (v1-clean) — seed-noise-dominated, not diagnostic

| Arm | Mate bias | Mate compression | Cp bias | Cp compression |
|---|---|---|---|---|
| Baseline | −1663.63 | 0.0998 | +6.39 | 0.3033 |
| Original low (seed 42) | −1646.01 | 0.1100 | +4.57 | 0.3337 |
| Replicate (seed 43) | −1673.85 | 0.0891 | +4.75 | 0.2541 |

**The same-K seed spread is larger than the effect it was being used to interpret.** Same-K
(seed 42 vs. seed 43) compression spread: mate 0.0209, cp 0.0796. Original low's own delta from
baseline: mate +0.0102, cp +0.0304. Both spreads exceed the corresponding deltas — a single seed
swap at *fixed* K moves these metrics further than the K change itself was credited with moving
them. That makes "original low improved, replicate regressed" a description of two noisy draws,
not a reversal of a real effect: neither run's compression number, alone, tells us anything about
K at this sample size. (Numeric check: mate spread |0.1100−0.0891|=0.0209 ≥ 0.0102; cp spread
|0.3337−0.2541|=0.0796 ≥ 0.0304.)

### 5.4 Magnitude-bucketed error (v1-clean) — seed-noise-dominated, not diagnostic

| Bucket | Baseline MAE | Original low MAE (seed 42) | Replicate MAE (seed 43) | Same-K spread | Original's Δ from baseline |
|---|---|---|---|---|---|
| Near-zero (≤25cp) | 53.90 | 58.89 | 47.16 | 11.73 | 4.99 |
| Moderate (25-200cp) | 72.00 | 74.15 | 69.08 | 5.07 | 2.15 |
| Large (200-800cp) | 328.66 | 314.60 | 345.67 | 31.07 | 14.06 |
| Extreme (>800cp) | 1196.98 | 1162.42 | 1238.58 | 76.17 | 34.57 |

**Same pattern as §5.3: in every bucket, the same-K seed spread exceeds original low's own delta
from baseline.** The original low-K run's trade-off (worse near-zero, better large/extreme) looked
like the saturation mechanism's predicted signature — but a metric that moves 11-76 units between
two identically-configured seeds cannot be used to confirm a ~2-35-unit effect. This bucket table
cannot resolve whether K=2.149428 helps, hurts, or does nothing relative to baseline; it can only
say the question is unresolved at n=2. The prediction histogram (below) shows the same
seed-driven spread, not a mechanistic reversal.

### 5.5 Prediction histogram (v1-clean) — visually different, not independently diagnostic

```
Replicate (K=2.149428, seed=43):        Original low (K=2.149428, seed=42):     Baseline (K=2.773456, seed=42):
  [-200,-100)  440 ##########             [-200,-100)  415 #############           [-200,-100)  425 #############
     [-100,0) 1227 ############################  [-100,0) 1021 #################      [-100,0) 1019 ###############
       [0,100) 1310 ##############################   [0,100) 1219 ########################    [0,100) 1299 ########################
     [100,200)  446 ##########             [100,200)  449 ##############           [100,200)  465 ##############
     [300,400)  108 ##                     [300,400)  157 #####                    [300,400)  125 ###
     [700,800)    2 #                      [700,800)    6 #                        [600,700)    9 #
```
Full histograms in `trainer/outputs/phase4/P4I/P4I-003-low-repl-s43/summary.json`.

**The replicate's distribution is narrower/more peaked than the original low-K run's** (1310 at
`[0,100)` vs. 1219; almost nothing past `[400,500)` vs. the original's mass reaching `[800,900)`)
— closer in shape to *baseline's* histogram than to the same-K original low-K run. Same K, same
schedule, same data — a visibly different output distribution driven by seed alone. No variance
estimate exists for histogram shape (unlike §5.3/§5.4's numeric metrics, there is no baseline
same-K spread to compare against here), so this cannot be quantitatively separated from noise
either — but qualitatively it is consistent with §5.3/§5.4's finding that this configuration's
seed-to-seed variance is large enough to visibly move every secondary readout tested. It should be
read as further illustration of that variance, not as independent evidence against the mechanism.

## 6. Updated variance estimate (this configuration, not Phase 1's)

Per this task's explicit instruction: **do not reuse the earlier Phase 1 variance estimate
(§27.2's std≈0.0019, measured at 2,000 steps/constant LR/K=2.773456/unfiltered corpus) when
interpreting this result.** Estimated directly from the two seed-42/seed-43 runs at the actual
Phase 4 configuration (K=2.149428, 20,000 steps, cosine schedule, sentinel-filtered corpus):

| Benchmark | Seed 42 | Seed 43 | Spread | "Sample std" (n=2, spread/√2) |
|---|---|---|---|---|
| v1 | 0.5328 | 0.5292 | 0.0035 | 0.0025 |
| v1-clean | 0.5977 | 0.5974 | 0.0004 | 0.0003 |

**This must be read with more caution than §27.2's own already-cautious n=3 estimate, not less**:
n=2 gives exactly one degree of freedom — a "sample std" computed from a single pairwise spread is
an extremely fragile point estimate of the true population variance (a 90%-confidence interval on
a std estimated from n=2 alone spans roughly 0.36× to 3.4× the point estimate — this is not a
precision instrument). **Two observations follow, both stated plainly rather than smoothed over**:
- The v1 spread (0.0035) is nearly **double** Phase 1's own cross-configuration estimate
  (0.0019) — directly contradicting any assumption that Phase 1's noise floor safely transfers to
  this configuration. This is exactly the risk research doc §27.2 itself flagged ("not yet
  confirmed to generalize... a future phase with spare budget should still widen this
  replication") — now directly observed, not merely hypothesized.
- The v1-clean spread (0.0004) is much *smaller* than Phase 1's estimate — but this single low
  value should not be read as "v1-clean's true noise floor is 0.0003" with any confidence; it is
  one realized draw from a distribution this experiment cannot characterize precisely at n=2. It
  is, however, directly consistent with §5.2's observation that v1-clean is empirically the more
  stable benchmark of the two at this sample size — a qualitative conclusion the n=2 estimate
  supports even though its exact numeric value should not be over-trusted.

**Do not compute a sigma-multiple for the original correlation delta against this n=2 estimate and
report it as decisive** (e.g. "+0.0044 is 14.7σ under the v1-clean n=2 std") — that number is
technically well-defined but would overstate precision this experiment cannot actually deliver at
n=2. §7's decision is based on the direct comparison of realized outcomes (§5.2-§5.5), not on a
sigma-multiple manufactured from a two-point variance estimate.

### 6.1 The actual finding: seed variance is metric- and benchmark-dependent, and only one readout clears it

The single most load-bearing number in this whole replication is not "does the effect reproduce"
in the abstract — it's *which metrics are even capable of answering that question* at n=2. Putting
every metric's same-K seed spread next to the effect size it was being used to interpret:

| Metric (v1-clean unless noted) | Same-K seed spread | Effect (original low − baseline) | Resolvable at n=2? |
|---|---|---|---|
| Correlation, v1-clean | 0.0004 | +0.0044 | **Yes — spread is 11x smaller than the effect** |
| Correlation, v1 | 0.0035 | +0.0038 | No — spread ≈ effect |
| Mate compression | 0.0209 | +0.0102 | No — spread > effect (2x) |
| Cp compression | 0.0796 | +0.0304 | No — spread > effect (2.6x) |
| Near-zero MAE | 11.73 | 4.99 | No — spread > effect (2.3x) |
| Extreme MAE | 76.17 | 34.57 | No — spread > effect (2.2x) |

**v1-clean correlation is the only readout in this entire battery where the seed-to-seed spread is
small relative to the effect it's meant to detect.** Every other metric this experiment
measured — v1 correlation and all four secondary metrics — has noise at or above the size of the
effect, which means §5.3-§5.5's "reversal" is exactly what noise-dominated metrics look like when
sampled twice, not evidence the underlying effect doesn't exist. This is the corrected framing
that supersedes the "does NOT reproduce" language in §5.3/§5.4's original headers: those metrics
didn't fail to reproduce a real pattern, they were never capable of measuring one at n=2.

**Practical consequence for future Phase 4 experiments**: report v1-clean correlation as the
primary (and, at small n, often *only*) resolvable signal; treat v1 correlation and every secondary
metric tested here as requiring a substantially larger n (or a paired/blocked design) before they
can corroborate or refute anything at this effect size.

## 7. Reproducibility assessment

**Not "mixed" in the sense of pointing partly one way and partly the other — mixed in the sense
that most of the battery cannot vote at all.** The task's decision rule requires *both* comparable
magnitude *and* comparable secondary-metric behavior. §6.1's per-metric spread-vs-effect table
determines what each half of that rule can actually say:

- **Magnitude**: on v1-clean — the benchmark where seed noise (0.0004) is small relative to the
  effect (0.004) — the gain reproduces tightly: +0.0044 → +0.0040. On v1, seed noise (0.0035) is
  comparable to the effect itself (+0.0038 → +0.0003); this benchmark cannot confirm *or* rule out
  the effect at n=2, so its near-zero replicate reading is not evidence against reproduction, it's
  a null result from an underpowered measurement.
- **Secondary-metric behavior**: cannot be assessed. Mate compression, cp-labeled compression, and
  the magnitude-bucket MAEs all have a same-K seed spread larger than the effect they were meant to
  corroborate (§5.3/§5.4/§6.1) — the apparent "reversal" in §5.3-§5.5 is what a noise-dominated
  metric looks like on a second draw, not a demonstrated absence of the mechanism. None of these
  four metrics can currently confirm or refute the saturation story either way.

**Conclusion**: the only readout that actually resolves at this sample size is v1-clean
correlation, and it reproduces at a small but consistent magnitude (~+0.004, both seeds). Nothing
else in the battery is corroborating evidence *or* disconfirming evidence — it's unresolved. The
honest characterization is: **a small, single-benchmark correlation gain that reproduced, with no
secondary metric able to corroborate it at this n** — not "disproven," and not "confirmed." A
reviewer who weights the v1-clean reproduction more heavily than this report's default reading
could reasonably argue for the localization grid [`phase4-p4i-k-sweep.md`](phase4-p4i-k-sweep.md)
§6.2 proposed (recommended, not executed, at that time); §8 recommends against pursuing it now
because an uncorroborated +0.004 gain does not meet the task's AND-gated promotion bar as written,
not because the effect has been shown not to exist.

## 8. Decision

**Terminate K exploration.** Per this task's explicit branch: "Otherwise: Terminate K exploration
and document that the apparent improvement was not reproducible." Read precisely against §7's
corrected finding: the AND-gated criterion is not met because secondary-metric corroboration
cannot be established — not because it was established and came back negative. v1-clean
correlation reproduced; nothing else in the battery is currently capable of confirming or
contradicting it at n=2. An uncorroborated single-benchmark gain of +0.004 is the task's own
example of "weak positive evidence, not a promotable result" — insufficient to clear the AND-gated
bar, but not a disproven effect either. **Do not begin a localization sweep.** The production K
value (2.773456) is unchanged; P1-G04 (reused via P3A-001) remains the reference model; nothing is
promoted.

**What this does and does not establish**, with the same care as every prior result in this
roadmap:
- It does **not** establish that K retraining can never move correlation — research doc §46.6's
  own pre-declared bound still applies: this narrows the specific ±22.5% de-saturation mechanism
  tested here, not loss/target reformulation as a class (Huber/log-cosh, hybrid-mate, WDL remain
  independently-justified, per the main document's §45 ranking, unaffected by this result).
- It does **not** establish that the original run's secondary-metric pattern was wrong or the
  saturation mechanism doesn't operate — §6.1/§7 show those metrics have seed noise larger than the
  effect being measured, so this replication simply lacks the power to adjudicate them either way.
  Treating the second seed's numbers as a "reversal" (this report's own first-draft framing, since
  corrected) would have been the same mistake the original P4I report's §6 was careful to avoid
  when it disclosed the 2σ/3σ knife-edge rather than resolving it unilaterally.
- It **does** establish that this project's Phase 1 noise floor (§27.2, std≈0.0019) does not
  safely transfer to the actual Phase 4 configuration — the v1 correlation spread measured here
  (0.0035) is nearly double it, and every secondary metric's spread is larger still relative to the
  effects those metrics are used to interpret. Any future Phase 4 correlation-delta interpretation
  should use a configuration-matched estimate, not §27.2's, should prefer v1-clean correlation as
  the primary (often only) resolvable readout at small n (§6.1), and should treat secondary metrics
  as requiring materially more samples before they can corroborate anything at effect sizes this
  small.
- It **does** demonstrate, concretely rather than hypothetically, why this project's own
  "declared, not tuned post-hoc" discipline (research doc §33.1, §26.6) exists: a +0.004
  correlation gain, uncorroborated, is exactly the kind of result the pre-declared AND-gated
  promotion rule was designed to catch and hold back from promotion — without requiring the report
  to overstate what the secondary metrics actually showed in order to justify that same call.

## 9. Learning log entry

```
## Experiment ID: P4I-003-low-repl-s43
Hypothesis:            P4I-001-low's correlation gain (K=2.149428 vs. baseline 2.773456) and its
                        accompanying secondary-metric pattern reproduce at a second training seed
                        (43, vs. the original's 42), everything else held identical.
Independent variable:   Training seed only (43 vs. 42). K, schedule, dataset, preprocessing,
                        checkpoint-selection mechanism all identical to P4I-001-low.
Controlled variables:   K=2.149428, steps=20000, lr=0.01, cosine schedule, warmup=200, batch=256,
                        architecture (unchanged), sentinel-filtered training corpus (35,933
                        records), split seed=42 (unchanged -- only the model-init/training seed
                        varies, per research doc SS26.1's seed-inventory distinction).
Expected outcome:       Comparable-magnitude correlation gain and a consistent secondary-metric
                        pattern (RMSE/calibration/mate/extreme-eval all moving the same direction
                        as P4I-001-low) if the original result reflected a real K effect;
                        divergent or reversed secondary metrics if it was seed-specific noise.
Observed outcome:       Correlation gain reproduces tightly on v1-clean (+0.0044 -> +0.0040,
                        same-K seed spread only 0.0004, 11x smaller than the effect). v1
                        correlation and all four secondary metrics (mate/cp compression,
                        near-zero/extreme MAE) have a same-K seed spread LARGER than the effect
                        they were meant to interpret (v1: 0.0035 vs 0.0038 effect; mate: 0.0209 vs
                        0.0102; cp: 0.0796 vs 0.0304; near-zero MAE: 11.73 vs 4.99; extreme MAE:
                        76.17 vs 34.57) -- these readouts cannot confirm or refute the effect at
                        n=2; they are not a "reversal," they are noise exceeding signal. New
                        seed-variance estimate at this configuration: v1 spread=0.0035 (nearly 2x
                        Phase 1's SS27.2 estimate), v1-clean spread=0.0004 (n=2, both estimates
                        too fragile to lean on precisely).
Metrics:                See SS5-SS6 above; full detail in outputs/phase4/P4I/
                        P4I-003-low-repl-s43/summary.json.
Decision:               Terminate K exploration. Do not begin a localization sweep. Production K
                        (2.773456) unchanged.
Reason rejected:        The task's AND-gated reproducibility criterion (comparable magnitude AND
                        comparable secondary-metric behavior) is not met -- not because secondary
                        metrics disproved the effect, but because none of them can resolve it
                        either way at this sample size (seed noise >= effect size on every one of
                        them). An uncorroborated +0.004 gain on one benchmark does not clear the
                        AND-gated bar as written; it is neither disproven nor promotable.
Next action:            No further K-specific experiment begins automatically, per this task's
                        instruction. K exploration is closed pending new evidence distinct from
                        what's already been tested (matching research doc SS30.1's precedent for
                        closing a lever "exhausted, not disproven-forever").
Artifacts:              trainer/outputs/phase4/P4I/P4I-003-low-repl-s43/
```

## 10. Graphify validation (post-implementation)

```
$ graphify . --update --code-only
[graphify extract] incremental scan
[graphify extract] --code-only: skipping 102 non-code file(s) -- no LLM extraction
[graphify extract] 1 code, 0 docs, 0 papers, 0 images changed; 272 unchanged; 0 deleted
[graphify extract] wrote graphify-out/graph.json: 2532 nodes, 6965 edges, 169 communities
[graphify extract] incremental summary: 272 files cached/unchanged, 1 re-extracted, 0 deleted
```

**Architecture unchanged, confirmed directly**: the incremental scan itself shows exactly 1 code
file changed (`phase4_p4i_replication.py`), 272 unchanged, 0 deleted — independently confirmed via
`git status --short`/`git diff --stat`, which show the same one new script plus this documentation
and the folder `README.md` update; no file under `trainer/trainer/model`,
`trainer/trainer/dataset`, `trainer/trainer/export`, `trainer/trainer/quantization`,
`trainer/trainer/contracts`, or any `engine-*` module was modified.

**Dependency footprint, re-queried for real (`graphify query "phase4_p4i_replication.py
dependencies"`)**: resolves to `combine_and_split()` (`train_candidate_net.py`) and
`phase4_p4i_k_sweep.py`'s own helper functions (`_eval_cell`, `_predictions`, `_magnitude_buckets`,
`_load_model`, `_is_sentinel`, `_select_best_checkpoint`, `_histogram`) — same graph community
(16) as `phase4_p4i_k_sweep.py` itself, confirming the reuse-not-duplicate design actually landed
in the dependency graph, not just in the source comment. No edge into `exporter.py`,
`quantizer.py`, `canonical.py`, or any Java engine code from the new script.
