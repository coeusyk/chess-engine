# Experiment P4I: coarse-to-fine retrain ablation of the texel-sigmoid loss's K parameter

**Date**: 2026-07-21. **Status**: Complete — Stage 1 (three-point coarse grid) only, per this
task's explicit instruction not to evaluate intermediate K values automatically. **Decision: at
the pre-registered 3σ bar, terminate K exploration; but low K clears 2σ (also within this design's
own declared "2-3σ" range) with a consistent secondary-metric pattern — a threshold-contingent
result, disclosed in full in §6, not a clean null. Wait for review before either concluding
termination or running §6.2's localization grid.**

This experiment continues the roadmap in
[`2026-07-19-nnue-improvement-analysis.md`](2026-07-19-nnue-improvement-analysis.md) §46's `P4I`
design (see that section for the full null hypothesis / promotion criteria / rollback criteria
this experiment was pre-registered against — not repeated here in full, only applied).

## 1. Scope and constraints

Per this task's explicit instruction, only `K` (`TrainingConfig.k`) was varied. Not modified:
architecture, features, dataset (identity/split methodology), optimizer, checkpointing, export,
quantization. New files added: `trainer/scripts/phase4_p4i_k_sweep.py` (training/evaluation
harness), `trainer/scripts/phase4_p4i_report.py` (adaptive-decision logic, reads the harness's
output, computes rather than eyeballs the decision). No existing trainer module was edited.

## 2. Graphify discovery (mandatory)

`graphify . --update --code-only` refreshed the graph (2,503 nodes / 6,898 edges / 160
communities — code-only, no LLM key configured in this session, matching every prior pass in this
roadmap). Every reference to `K`/`config.k` across the repository was traced by direct grep,
cross-checked against the graph's own BFS query, not assumed from the research doc's §40 trace
alone (that trace is reused as prior evidence; this pass re-verified it against the current
worktree's code, since new files existed since §40 ran):

- **`K` flows through exactly one function, `texel_sigmoid()`** (`trainer/model/train.py:99`),
  called from exactly two sites: `train()`'s loss computation (`train.py:220,228`) and
  `evaluate_held_out()` (`trainer/validation/validator.py:80,84`).
- **`calibration_report()`, `fit_affine_calibration()`, `eval_scale_check()` never call
  `texel_sigmoid`** — confirmed by grep, they operate on raw `predicted_cp`/`target_cps` only,
  unaffected by `K`.
- **`network.py` (architecture), `batching.py` (features), `quantizer.py`/`canonical.py`/
  `exporter.py` (export/quantization) have zero code-level reference to `K`** — the only match in
  `network.py` is a docstring comment ("the two must agree for the K-calibrated loss... to be
  meaningful"), not a runtime dependency.
- **No Java code** (`engine-core`/`engine-tuner`/`engine-uci`) references the Python trainer's
  `K` — `KFinder.java`/`TunerEvaluator.java`'s own `.sigmoid()` is a historically-related but
  code-independent Classical-evaluator-specific value.

**Confirmed: only the target-transformation path (`texel_sigmoid`, consumed by the loss and by
held-out-loss/correlation evaluation) depends on `K`.** This matches this task's explicit
discovery requirement and is consistent with §40's earlier trace.

## 3. Experiment setup

### 3.1 K grid

| Arm | K | vs. baseline | 99%-saturation point |
|---|---|---|---|
| Baseline | 2.773456 | — | ≈288cp |
| Low | 2.149428 | −22.50% | ≈371cp |
| High | 3.397484 | +22.50% | ≈235cp |

±22.5% is the midpoint of this task's requested "approximately 20-25%" band — chosen once and
declared before running, not tuned after seeing results, per this project's "declared, not tuned
post-hoc" convention (research doc §33.1).

### 3.2 Baseline reused, not retrained

**The baseline arm is `trainer/outputs/phase3/P3A-001/checkpoints/step-016999.pt`** (Experiment
3A, research doc §35) — P1-G04's frozen schedule, `K=2.773456` (unchanged), trained on the
sentinel-filtered Stage 1 training set research doc §35.9 adopted as permanent ingestion hygiene.
This is *exactly* the "baseline (current K)" configuration this design calls for; retraining an
identical configuration would be a wasted, duplicate run. Verified via direct checkpoint-config
inspection (`k=2.773456`, `steps=20000`, `lr_schedule=cosine`, `seed=42`, matching P1-G04's frozen
schedule exactly) before reuse, not assumed from the filename alone.

### 3.3 Leakage-safe training data (identical methodology to Experiment 3A, §35.3)

`combine_and_split(seed=42)` reproduces P1-G04's exact 36,000/4,000 split; the confirmed sentinel
filter (`eval_cp ∈ {-9605, 9605, 20000}`) is applied to the already-split training list only —
35,933 training records (67 removed), held-out set unchanged (4,000 for v1, 3,992 for v1-clean).
**All three arms train on the identical 35,933-record corpus** — `K` is the only difference.

### 3.4 Near-miss during implementation (disclosed, not glossed over)

While verifying the correct invocation pattern for this project's phase scripts, `python -m
scripts.phase3_experiment_3a` was run without realizing it has no `--help`/dry-run guard and
unconditionally executes its full `main()`, which writes to `outputs/phase3/P3A-001/` — the same
directory this experiment's baseline reuse depends on. It was killed after ~120s, before it
reached the `step-016999.pt` checkpoint file this experiment actually reuses (confirmed
byte-identical via md5 before and after). It had, however, already written 16 stray checkpoint
files (steps 000999-015999, today's date) into that directory before being stopped, and had
refreshed (deterministically, content-equivalent) `outputs/datasets/stage1-lichess-filtered/`.
The stray checkpoint files were deleted, restoring the directory to its authentic four-file state
(steps 016999-019999, original 2026-07-20 timestamps). No experiment result in this roadmap was
corrupted. **Recorded here per this project's standing practice of preserving the investigation's
error record** (research doc §32.4's own precedent), not because it affected any reported number.

## 4. Training report

| Arm | Selected step | Wall clock | Held-out loss @ selected | Train corr. @ selected | Held-out corr. @ selected |
|---|---|---|---|---|---|
| Baseline (reused) | 16,999/19,999 | (P3A-001's own, §35.4: 151.0s) | 0.0797 | 0.7036 | 0.5290 (v1) |
| Low (K=2.149428) | 10,999/19,999 | 158.4s | 0.0692 | 0.7076 | 0.5328 (v1) |
| High (K=3.397484) | 17,999/19,999 | 153.7s | 0.0858 | 0.6947 | 0.5241 (v1) |

Both new runs show the same qualitative trajectory shape as every prior Phase 1/3 run at this
schedule (§27.3/§35.4's established pattern): held-out correlation rises for the first
~9,000-11,000 steps then flattens into a tight band, train correlation keeps climbing slightly
while held-out plateaus — "both plateau together," not divergence or instability. No
§26.4-early-termination condition triggered for either run (no sustained loss increase, no
oscillation, no validation collapse below Phase 0's untrained baseline). Full per-step
trajectories: `trainer/outputs/phase4/P4I/P4I-00{1,2}-{low,high}/training_diagnostics.json`.

**Note, stated plainly**: held-out *loss* is not directly comparable across arms (`K` changes what
scale the sigmoid target/loss itself is computed on — low K's lower held-out loss and high K's
higher held-out loss reflect the loss function's own K-dependent scale, not necessarily "better
fit" in a K-independent sense; held-out *correlation*, computed on raw `predicted_cp`, is the
metric that's actually comparable across arms, per research doc §26.0's metric hierarchy).

## 5. Metric comparison

### 5.1 Correlation, RMSE, overall calibration

**"Baseline" here means `P3A-001`, this experiment's correct single-variable isolation point
(§3.2) — the identical schedule and sentinel-filtered corpus as Low/High, differing only in `K`.
It is not the roadmap's reference model, P1-G04 (0.5315 on v1 / 0.5931 on v1-clean, trained on the
*unfiltered* corpus) — comparing Low/High against P1-G04 directly would confound `K` with the
sentinel filter, exactly the confound Experiment 3A's methodology (§35.3) was designed to avoid.
Both comparisons are given below** so a reader evaluating "does this look promising" sees the
number against the actual current reference, not only against the isolation baseline.

| Arm | Benchmark | n | Correlation | RMSE | Bias | Compression |
|---|---|---|---|---|---|---|
| Baseline (=P3A-001) | v1 | 4,000 | 0.5290 | 1240.4 | −215.2 | 0.1259 |
| Baseline (=P3A-001) | v1-clean | 3,992 | 0.5933 | 1030.1 | −191.1 | 0.1487 |
| Low | v1 | 4,000 | 0.5328 | 1232.5 | −214.7 | 0.1389 |
| Low | v1-clean | 3,992 | 0.5977 | 1021.3 | −190.6 | 0.1640 |
| High | v1 | 4,000 | 0.5241 | 1250.4 | −215.6 | 0.1093 |
| High | v1-clean | 3,992 | 0.5869 | 1041.6 | −191.5 | 0.1290 |

| Comparison | Low Δcorr (v1) | Low Δcorr (v1-clean) | High Δcorr (v1) | High Δcorr (v1-clean) |
|---|---|---|---|---|
| vs. P3A-001 (isolation baseline, single-variable-clean) | +0.0038 | +0.0044 | −0.0048 | −0.0065 |
| vs. P1-G04 (roadmap reference model, confounds K with the filter) | +0.0012 | +0.0046 | −0.0074 | −0.0062 |

Low K's gain shrinks to +0.0012 on v1 against the actual reference model (P1-G04) — the confounded
comparison is given for context only; **§6's decision uses the isolation-clean P3A-001 comparison,
correctly, since that is the only comparison this experiment's design actually controls for.**

### 5.2 Mate-labeled and cp-labeled calibration (v1 / v1-clean identical for mate — sentinel
records are all cp-labeled, §35.6's precedent)

| Arm | Mate bias | Mate compression | Mate MAE | Cp bias (v1) | Cp bias (v1-clean) | Cp compression (v1-clean) |
|---|---|---|---|---|---|---|
| Baseline | −1663.63 | 0.0998 | 2785.33 | −21.36 | +6.39 | 0.3033 |
| Low | −1646.01 | 0.1100 | 2762.41 | −23.16 | +4.57 | 0.3337 |
| High | −1684.56 | 0.0859 | 2814.65 | −19.12 | +8.73 | 0.2647 |

**Mate compression moves monotonically with K, in the theoretically predicted direction**: lower
K (wider unsaturated band) → higher mate compression (0.1100, closer to 1.0, less compressed);
higher K (narrower unsaturated band) → lower mate compression (0.0859, further from 1.0, more
compressed). Same monotonic pattern in cp-labeled compression (0.2647 → 0.3033 → 0.3337 as K
decreases). This is the saturation mechanism (research doc §23.7/§41.0) manifesting exactly as
predicted — the *qualitative* mechanism is confirmed; §5.4 below addresses whether the
*quantitative* effect on correlation clears this experiment's pre-declared bar.

### 5.3 Magnitude-bucketed error (v1-clean, non-mate, research doc §32.6's boundaries reused)

| Bucket | n | Baseline MAE | Low MAE | High MAE | Baseline bias | Low bias | High bias |
|---|---|---|---|---|---|---|---|
| Near-zero (≤25cp) | 845 | 53.90 | 58.89 | 48.66 | +8.27 | +8.88 | +7.47 |
| Moderate (25-200cp) | 1,163 | 72.00 | 74.15 | 69.24 | −4.04 | −6.29 | −2.56 |
| Large (200-800cp) | 1,418 | 328.66 | 314.60 | 345.66 | +50.53 | +47.79 | +55.33 |
| Extreme (>800cp) | 94 | 1196.98 | 1162.42 | 1236.71 | −547.13 | −551.99 | −543.45 |

**A real, interpretable trade-off along the saturation axis, not a uniform improvement**: low K
improves the large/extreme buckets (less saturation → more gradient at large magnitudes) but
*worsens* the near-zero bucket (58.89 vs. baseline's 53.90 MAE) — less gradient concentration
right at zero. High K shows the mirror-image pattern (best near-zero MAE, 48.66, but worst extreme
MAE, 1236.71). This is exactly the mechanism §41.0/§42 described (K trades near-zero conditioning
against large-magnitude gradient), now observed directly under retraining for the first time.

### 5.4 Prediction histograms (predicted_cp, v1-clean, 100cp buckets, clipped to [-1000,1000])

```
Baseline (K=2.773456):                 Low (K=2.149428):                      High (K=3.397484):
  [-300,-200)  196 ######                [-300,-200)  224 #######                [-300,-200)  162 ####
  [-200,-100)  425 #############         [-200,-100)  415 #############         [-200,-100)  422 ############
     [-100,0) 1019 ###############      ...  [-100,0) 1021 #################        [-100,0) 1105 ###############################
       [0,100) 1299 ########################  [0,100) 1219 ########################    [0,100) 1387 ########################################
     [100,200)  465 ##############        [100,200)  449 ##############          [100,200)  450 ############
     [200,300)  253 #######                [200,300)  232 #######                [200,300)  243 #######
     [300,400)  125 ###                    [300,400)  157 #####                  [300,400)  105 ###
     [400,500)   57 #                      [400,500)   71 ##                     [400,500)   31 #
     [600,700)    9 #                      [700,800)    6 #                      [600,700)    2 #
     [800,900)    0                        [800,900)    1 #                      [800,900)    0
```
(Abbreviated to the informative range; full 20-bucket histograms for all three arms × two
benchmarks in `trainer/outputs/phase4/P4I/summary.json`'s `prediction_histogram` fields.)

**Directly, visually confirms the saturation mechanism**: High K produces the most peaked/narrow
distribution (1387 at `[0,100)`, tail essentially ends by `[600,700)`); Low K produces the widest
spread (more mass in every bucket beyond `[300,400)`, reaching `[800,900)`); Baseline sits between
the two, as expected for its intermediate K. This is a clean, qualitative confirmation of the
theoretical mechanism (§23.7/§41.0) — the quantitative correlation question is addressed next.

## 6. Adaptive decision

Computed by `trainer/scripts/phase4_p4i_report.py` (not eyeballed) — reused rather than restated
here in narrative form, since a hand-transcribed table risks a rounding-induced misread this close
to the threshold. Primary benchmark: **v1-clean** (research doc §46.4's declared choice). Noise
floor: std ≈ 0.0019 (n=3, research doc §27.2 — measured at a *different* configuration, 2,000
steps/constant LR, not yet re-measured at this 20,000-step cosine schedule; used as the best
available estimate with this generalization caveat carried forward explicitly, exactly as §27.2
itself requires).

**The threshold choice is outcome-determining here and must be shown, not just applied.** Research
doc §46.5 declared "2-3σ" as the meaningful-improvement convention — a *range*, not a single
number. The decision script hardcodes 3σ (the conservative end, fixed in code before this run,
not chosen after seeing results — so it is not post-hoc tuning). But the actual deltas land inside
that declared range, not cleanly outside it:

| Arm | Δ correlation (v1) | σ (v1) | Δ correlation (v1-clean) | σ (v1-clean) |
|---|---|---|---|---|
| Low | +0.0038 | **2.00σ** | +0.0044 | **2.32σ** |
| High | −0.0048 | −2.54σ | −0.0065 | −3.39σ |

**At the pre-registered 3σ bar: neither endpoint clears it — decision is TERMINATE.** Low K's
2.00σ/2.32σ misses 3σ on both benchmarks. High K's −2.54σ/−3.39σ is a regression regardless of
which end of the range is used.

**At 2σ — also within this design's own stated "2-3σ" range — low K clears the bar on both
benchmarks (2.00σ, 2.32σ).** Under this experiment's own pre-declared rules, clearing the
threshold plus the secondary-criterion check (§6.1 below) would route to the task's *other*
branch: "recommend the minimum additional experiments required to localize the optimum," not
terminate. **This is surfaced explicitly for the reviewer, not resolved unilaterally in either
direction** — the report defaults to the conservative 3σ reading (TERMINATE, §7) because 3σ is
the stricter, false-positive-protecting choice and because the noise floor itself is a
cross-configuration estimate (not yet re-measured at this schedule, per above) rather than a
tightly-characterized number precise enough to lean on its looser end with confidence. But the
result is genuinely threshold-contingent, and a reviewer who judges 2σ appropriate should read
this as "promotable, pending the localization experiments in §6.2," not as a closed question.

### 6.1 A genuine nuance, disclosed rather than smoothed over

Low K's correlation gain, borderline as it is, is not an isolated fluke: **all four secondary
metrics move in the same, theoretically-predicted direction simultaneously** — RMSE improves
(+8.86cp better), overall calibration improves (bias magnitude and compression both move toward
their ideal), mate behavior improves (bias magnitude down, compression up), extreme-evaluation
behavior improves (extreme-bucket MAE down) — and the cp-labeled majority does **not** regress
(§23.5's failure-mode check, explicitly passed). High K shows the near-mirror-image pattern
(correlation regresses, RMSE/calibration/mate all regress; only the near-zero bucket improves).

**This pattern is more internally consistent than uncorrelated noise would typically produce.**
Combined with §6's 2σ/3σ knife-edge, this is genuinely ambiguous evidence, not a clean null — it
is reported as such rather than smoothed into either "clearly promising" or "clearly nothing."

### 6.2 If treated as meaningful: minimal localization (recommended, not executed)

Per this task's instruction — recommend, do not run. **Correlation is monotonic in K across all
three tested points**: high (3.397, 0.5869) < baseline (2.773, 0.5933) < low (2.149, 0.5977) on
v1-clean — the best result sits *at the tested low endpoint itself*, not interior to the grid.
This means the coarse-to-fine grid did not bracket a turning point; the optimum (if the trend
continues) is plausibly *at or below* 2.149428, not between baseline and low. A localization grid
that only probed *between* baseline and low would re-confirm "lower is better" without finding
where it stops — the minimum additional points instead extend **outward from the low endpoint**:

| K | Rationale |
|---|---|
| ≈1.94 | ~-30% from baseline — one step further out than the tested low endpoint, checks whether the trend continues |
| ≈1.72 | ~-38% — a second step out; if correlation is still rising here, the grid needs to extend further still before a peak can be localized |
| ≈2.46 | ~-11% — one interior point, between baseline and the low endpoint, to distinguish "monotonically increasing all the way down" from "a local bump near 2.15" |

Three points (not the full range re-swept) — consistent with this task's own "coarse-to-fine,"
not "dense sweep" instruction, applied to a localization step exactly as §46.5's "recommend the
minimum additional experiments" language calls for. **Not executed as part of this task.**

## 7. Recommendation

**At the pre-registered 3σ bar: stop K exploration at this coarse-to-fine stage.** Per this task's
explicit instruction: document that the current evidence does not support further K tuning within
the ±22.5% band tested, at that threshold. Do not evaluate intermediate K values, and do not begin
the next Phase 4 experiment automatically — wait for review.

**Stated plainly, per §6's disclosure**: this is the conservative reading of a threshold-contingent
result, not an unambiguous null. Low K clears 2σ (also within this design's own declared "2-3σ"
range) on both benchmarks, with all four secondary metrics moving consistently in the predicted
direction and no cp-labeled regression. If the reviewer judges 2σ the appropriate bar here, §6.2's
three-point localization (K ≈ 2.60, 2.46, 2.30) is the recommended next step — not executed, per
this task's instruction to recommend only.

**What this result does and does not establish**, stated with the same care as every prior
negative result in this roadmap:
- It does **not** establish that `K` retraining can never move correlation — research doc §40's
  correction (retraining ≠ a post-hoc affine transform) is unaffected; this is one specific,
  narrow band (±22.5%) tested once, not an exhaustive search.
- It **does** establish, for the first time under actual retraining, the qualitative mechanism
  §23.7/§41.0 hypothesized (K trades near-zero conditioning against large-magnitude gradient,
  visible directly in §5.3's bucket table and §5.4's histograms) — a real, if secondary, finding
  independent of whether it clears the correlation bar.
- It **does** establish that the specific ±22.5% grid tested here does not produce a
  correlation gain large enough to distinguish from noise at this project's only measured
  threshold — narrowing, not closing, the K-retraining question (research doc §46.6's own
  pre-declared bound: a null here is evidence against *this* de-saturation mechanism specifically,
  not against loss/target reformulation as a class — Huber/log-cosh, hybrid-mate, and WDL remain
  independently-justified next candidates per §45's ranking, unaffected by this result).

## 8. Learning log entries

```
## Experiment ID: P4I-001-low
Hypothesis:            Retraining at K=2.149428 (-22.5% vs. baseline 2.773456), otherwise
                        identical to P1-G04/P3A-001's frozen schedule, improves held-out
                        correlation beyond baseline by more than the noise floor (research doc
                        SS27.2, std~=0.0019, threshold 3sigma~=0.0057).
Independent variable:   K only.
Controlled variables:   Steps=20000, lr=0.01, cosine schedule, warmup=200, seed=42, batch=256,
                        architecture (hidden_width=256/qa=127/qb=64/output_scale=400), sentinel-
                        filtered training corpus (35,933 records, identical to P3A-001's Model B).
Expected outcome:       Correlation gain clearing noise floor if K's saturation point is a
                        material lever on correlation; sub-threshold movement with directionally
                        consistent secondary-metric improvement if the mechanism is real but not
                        large enough at this band to move correlation distinguishably from noise.
Observed outcome:       Correlation +0.0038 (v1, 2.00sigma) / +0.0044 (v1-clean, 2.32sigma) --
                        clears 2sigma but not 3sigma, both within this design's own declared
                        "2-3sigma" range (research doc SS46.5) -- a genuine threshold-contingent
                        result, not a clean miss. RMSE, overall calibration, mate behavior, and
                        extreme-evaluation behavior all improve directionally; cp-labeled majority
                        does not regress. Mate/cp compression move toward 1.0 (less compressed) as
                        predicted by the saturation mechanism.
Metrics:                See SS5/SS6 above; full detail in outputs/phase4/P4I/summary.json.
Decision:               Not promoted at the pre-registered 3sigma bar (conservative reading, the
                        report's default per SS6). At 2sigma this would clear the correlation
                        criterion and, with the secondary-metric criterion also met, would route
                        to "recommend localization" instead -- surfaced explicitly for review
                        (SS6.2's minimal localization grid given, not executed), not resolved
                        unilaterally either way.
Reason rejected:        At the stricter, pre-registered end of the design's own declared range
                        (3sigma), correlation delta does not exceed it -- but this is a threshold
                        choice, not an unambiguous null; see SS6 for the full disclosure.
Next action:            No further K-specific experiment begins automatically. Per this task's
                        instruction, wait for review before either terminating conclusively or
                        running SS6.2's localization grid.
Artifacts:              trainer/outputs/phase4/P4I/P4I-001-low/

## Experiment ID: P4I-002-high
Hypothesis:            Retraining at K=3.397484 (+22.5% vs. baseline), otherwise identical,
                        improves held-out correlation beyond baseline by more than the noise floor.
Independent variable:   K only.
Controlled variables:   Identical to P4I-001-low above.
Expected outcome:       Same structural expectation as P4I-001-low, opposite direction on K.
Observed outcome:       Correlation -0.0048 (v1) / -0.0065 (v1-clean) -- a regression, not an
                        improvement; magnitude exceeds the noise floor in the wrong direction.
                        RMSE, overall calibration, and mate behavior all regress directionally;
                        only the near-zero magnitude bucket and extreme-bucket MAE improve
                        (a partial, mixed pattern, not a consistent one like P4I-001-low's).
Metrics:                See SS5 above; full detail in outputs/phase4/P4I/summary.json.
Decision:               Not promoted -- correlation regresses.
Reason rejected:        Correlation moved in the wrong direction, exceeding the noise floor as a
                        regression rather than an improvement.
Next action:            Same as P4I-001-low -- terminate K exploration, wait for review.
Artifacts:              trainer/outputs/phase4/P4I/P4I-002-high/
```

## 9. Graphify validation (post-implementation)

```
$ graphify . --update --code-only
[graphify extract] incremental scan
[graphify extract] --code-only: skipping 101 non-code file(s) — no LLM extraction
[graphify extract] 2 code, 0 docs, 0 papers, 0 images changed; 270 unchanged; 0 deleted
[graphify extract] wrote graphify-out/graph.json: 2531 nodes, 6955 edges, 158 communities
[graphify extract] incremental summary: 270 files cached/unchanged, 2 re-extracted, 0 deleted
```

**Only the two new files changed** — confirmed by the incremental scan itself (2 re-extracted,
270 unchanged, 0 deleted), and independently by `git status --short`/`git diff --stat`: exactly
`trainer/scripts/phase4_p4i_k_sweep.py` and `trainer/scripts/phase4_p4i_report.py` are new; no
file under `trainer/trainer/model`, `trainer/trainer/dataset`, `trainer/trainer/export`,
`trainer/trainer/quantization`, `trainer/trainer/contracts`, or any `engine-*` module was modified.

**Dependency footprint, re-queried for real (`graphify query "phase4_p4i_k_sweep.py
dependencies"` / `"phase4_p4i_report.py dependencies"`), not assumed**: `phase4_p4i_k_sweep.py`
resolves only to `combine_and_split()`, `PositionRecord`, `NnueNet`, and its own internal
functions — the same graph community (20/29) as every prior experiment script
(`phase3_experiment_3a.py`, `phase1_optimization_sweep.py`, `phase1_experiment_2a.py`/`2b.py`,
`train_candidate_net.py`), identical in kind to their dependency footprint (research doc
§31/§35.1's established pattern). `phase4_p4i_report.py` is entirely self-contained — its only
external dependency is `pathlib.Path` (it reads `summary.json`, no trainer imports at all). **No
edge into `exporter.py`, `quantizer.py`, `canonical.py`, or any Java engine code from either new
script.** Architecture boundaries preserved; documentation synchronized (this file plus the
folder `README.md`).
