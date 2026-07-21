# P4II: mate-aware loss weighting (RQ-2, Lever B)

**Date**: 2026-07-21. **Status**: Complete. **Decision: NOT PROMOTABLE.** The airtight reason:
at the checkpoint this roadmap's own established convention actually selects (peak held-out
correlation, step 3,999), the promotion rule's regression guard fails outright — RMSE and
cp-labeled calibration both regress versus baseline (§6.3). That alone settles it, using only the
task's literal rule and the pre-existing checkpoint-selection convention, no further argument
needed. **A second, independent finding, stated explicitly because it changes what the rule itself
should check going forward**: the headline pooled v1-clean correlation gain (+0.04 to +0.05, the
largest number this roadmap has produced) is a **pooling/leverage artifact**, not a genuine
improvement — correlation measured on the cp-labeled majority alone (88.5% of the corpus) is
flat-to-regressed at every checkpoint tested, including the run's converged final step, where the
literal promotion rule as the user stated it this turn would otherwise have said "promote" (§7).
Production model unchanged; K unchanged at 2.773456; mate_weight not adopted.

This experiment continues Phase 4A's closure (`phase4-p4i-replication.md`) and the research
document's §38.5/RQ-2 pre-declared sequencing: mate-aware loss weighting, run after and separately
from the K sweep, using the original K since the K sweep's result was not promoted.

## 1. Candidate selection (why RQ-2, not Huber/log-cosh)

This task instructed implementing "the first objective already ranked highest," without
reconsidering the ranking. The main document's decision matrix (§45) has a bare Priority-2 row for
Hybrid mate and a Priority-3 row for Huber/log-cosh carrying a forward-looking annotation ("natural
Phase 4B candidate **if `P4I` is inconclusive**") — exactly the branch this roadmap landed in. That
annotation is real but subordinate to a more specific, earlier-declared statement: **§38.5 point 4
(written before P4I ran) states explicitly that a K-sweep result "does not substitute for Lever B
(mate-aware loss, run immediately after per the existing sequencing)."** RQ-2's own definition
(§39) independently confirms this: its "Independent variable" line says to run "using RQ-1's best K
(or the original K, if RQ-1 shows no improvement, per §26.1's own stated fallback)" — precisely
P4I's replicated outcome. RQ-3 (mate-target representation) explicitly defines itself as running
*after* RQ-2, comparing against "what RQ-2's loss-weighting approach alone achieves" — a second,
independent confirmation that RQ-2 comes first. The Huber/log-cosh annotation is not taken as
controlling because it is the less specific of two applicable statements in the same document, not
because it was reconsidered.

**Within Hybrid mate, the specific candidate is RQ-2 (loss weighting), not RQ-3 (target
representation)** — §38.5 names the sequenced candidate "mate-aware loss" (RQ-2's own title), and
RQ-3's own text confirms it runs second, as a comparison against RQ-2's result.

## 2. Graphify discovery (mandatory)

`graphify . --update --code-only` (fresh worktree, so a full scan): 2,532 nodes / 6,968 edges / 163
communities, 273 code files. `graphify query "train.py mate weighting loss dependencies"` traced
the full supervision pipeline:

| Pipeline stage | Module | Touched by P4II? |
|---|---|---|
| Label generation | `trainer/dataset/text_provider.py`, `trainer/contracts/dataset.py` | No |
| Target transformation | `trainer/model/train.py::target_cp()`, `texel_sigmoid()` | No — `target_cp()`/`texel_sigmoid()` themselves are unchanged; only the loss line that *consumes* their output changes |
| Batching | `trainer/model/batching.py::encode_batch()` | No |
| **Loss computation** | `trainer/model/train.py::train()` (the loss line) | **Yes — the only production-code change** |
| Validation | `trainer/validation/validator.py::evaluate_held_out()`, `calibration_report()` | No (imported, not modified) |
| Checkpoint selection | `_select_best_checkpoint()` (per-script duplicate, `trainer/scripts/phase4_p4i_k_sweep.py`, reused here) | No — reused as-is; see §7 for a checkpoint-selection *finding*, not a code change |
| Reporting | New: `trainer/scripts/phase4_p4ii_mate_weight.py` | New file, reuses `phase4_p4i_k_sweep.py`'s `_is_sentinel`/`_load_model`/`_select_best_checkpoint`/`_predictions`/`_magnitude_buckets`/`_histogram`/`_eval_cell` and `train_candidate_net.py`'s `combine_and_split` — no duplication |

**Every module Phase 4B modifies, declared before implementation**: `trainer/trainer/model/train.py`
(one new `TrainingConfig` field, `mate_weight: float = 1.0`; one changed loss line) and
`trainer/tests/model/test_train.py` (three new tests). Architecture (`network.py`), feature
extraction (`batching.py`), dataset generation (`dataset/`, `contracts/`), checkpointing (`train()`'s
own mechanism), export (`exporter.py`, `canonical.py`), and quantization (`quantizer.py`) are
**not** imported for modification anywhere in this change — confirmed both by this trace and by
§9's post-implementation Graphify validation.

## 3. Experiment design

**Independent variable**: `TrainingConfig.mate_weight` only. `K` held at the production value
(2.773456, unchanged) — per RQ-2's own fallback and Phase 4A's closure (§0 below). Architecture,
features, dataset, optimizer, checkpointing, export, quantization, seed (42), steps (20,000),
LR/schedule (0.01, cosine, warmup 200) all frozen at P1-G04/P3A-001's values.

**`mate_weight` value — disclosed derivation, not a round-number guess.** Mate-labeled records are
11.51% of the sentinel-filtered training corpus (measured directly this session: 4,137/35,933).
Full class-frequency equalization — giving the mate-labeled subset equal aggregate loss weight to
the cp-labeled majority — requires `w = (1-p)/p = 7.6858`. **This experiment does not use that
value.** Full equalization is the single point most likely to trip the cp-labeled-majority
regression guard (§23.5's failure mode) by construction; since this is a one-shot, non-swept
experiment (per this task's explicit "exactly one Phase 4B experiment" instruction), the most
aggressive point risks being the least informative one — it would show "this much weight breaks cp
metrics" without indicating whether the mechanism has any viable operating point at all. This
experiment uses **`mate_weight = 3.0`** (≈39% of full equalization) instead: a substantial,
pre-declared single test point, not tuned after seeing results.

**A known mechanism limitation, stated in advance**: §23.7's mate-bias mechanism is
saturation-induced gradient vanishing — at `K=2.773456`, `σ'(3000cp) ≈ 0` to numerical precision. A
finite loss weight rescales whatever gradient signal remains at that point; it cannot restore
gradient magnitude the target representation has already collapsed to near-zero. This is why RQ-3
(mate-target *representation*, not weighting) exists as a separate future candidate — this
experiment tests the loss-weighting mechanism specifically, not a claim it can substitute for a
target-representation change.

**Baseline reused, not retrained**: `mate_weight=1.0` (default) makes the weighted-loss formula
algebraically identical to a plain unweighted mean (proved directly,
`test_weighted_mean_formula_reduces_to_plain_mean_at_uniform_weight`), and the implicit default
trains an identical checkpoint to an explicit `mate_weight=1.0`
(`test_train_default_mate_weight_matches_explicit_mate_weight_one`, both new this session). Both
checks compare the current code against itself, not against the pre-Phase-4B code path (which no
longer exists to compare against) — on that basis, not a literal bit-for-bit replay of history,
`P3A-001`'s existing checkpoint is treated as the `mate_weight=1.0` arm, reused directly, matching
P4I's own pattern.

## 4. Implementation

`trainer/model/train.py`: added `mate_weight: float = 1.0` to `TrainingConfig`; the loss line now
computes a per-record weight (`is_mate` mask, the same pattern `validator.py:205`'s calibration
split already uses) and a **normalized** weighted mean —
`(weights * (predicted_prob - targets) ** 2).sum() / weights.sum()` — rather than an unnormalized
weighted sum, so the loss stays on the same scale as every prior experiment's and the
`mate_weight=1.0` case is algebraically `torch.mean(...)`, not a scaled variant of it.

Two correctness checks added before running the real experiment (`tests/model/test_train.py`):
1. `test_weighted_mean_formula_reduces_to_plain_mean_at_uniform_weight` — the normalized-weighted-
   mean formula, algebraically, equals `torch.mean()` when every weight is 1.0.
2. `test_train_default_mate_weight_matches_explicit_mate_weight_one` /
   `test_train_mate_weight_above_one_changes_the_trained_model` — end-to-end: the implicit default
   and an explicit `mate_weight=1.0` train identical checkpoints (proving the baseline-reuse claim
   above is actually true of the code, not just the formula), and a non-unit weight measurably
   changes the trained model (proving the new parameter is not dead code).

Full suite: 173/173 passing (170 pre-existing + 3 new).

## 5. Training report

| Arm | Selected step | Wall clock | v1 corr. @ selected |
|---|---|---|---|
| Baseline (P3A-001, reused) | 16,999/19,999 | — (reused) | 0.5290 |
| **P4II-001 (mate_weight=3.0)** | **3,999/19,999** | 154.5s | 0.5739 |

**A trajectory shape not seen anywhere else in this roadmap**: every prior run (baseline, both
P4I endpoints, the P4I replication) shows correlation rising and then *plateauing* — approaching a
stable value monotonically or near-monotonically. This run's held-out correlation **peaks at step
3,999 (0.5739) and then recedes**, settling into a lower, stable plateau (~0.564-0.565) for the
remaining 80% of training:

```
step   999: corr=0.5073   step  6999: corr=0.5675   step 13999: corr=0.5644
step  1999: corr=0.5439   step  7999: corr=0.5658   step 14999: corr=0.5644
step  2999: corr=0.5598   step  8999: corr=0.5663   step 15999: corr=0.5646
step  3999: corr=0.5739  <- selected (peak)   step 16999: corr=0.5653
step  4999: corr=0.5701   step  9999: corr=0.5641   step 17999: corr=0.5652
step  5999: corr=0.5680   step 10999: corr=0.5650   step 18999: corr=0.5652
                          step 11999: corr=0.5644   step 19999: corr=0.5652 (final)
                          step 12999: corr=0.5637
```

The baseline's own trajectory (`outputs/phase3/P3A-001/training_diagnostics.json`, reproduced for
comparison) rises from 0.4603 to a stable ~0.528-0.529 with no such overshoot — its selected
checkpoint (16,999) is the *last* logged step, a genuinely converged value. This run's selected
checkpoint is an early, transient spike the run's own later training recedes from and never
revisits.

**This is a real, disclosed finding about the checkpoint-selection convention itself, not just this
experiment's result — with one attribution caveat stated precisely, not left implicit**:
`_select_best_checkpoint()` (peak held-out *correlation*, reused unmodified from
`phase4_p4i_k_sweep.py`) was implicitly validated against monotonic-approach trajectories — every
run in this roadmap before this one had that shape. It has never been exercised against an
overshoot-then-recede trajectory before, and applied here, it selects the transient spike rather
than the run's own converged, stable value. **But the non-monotonicity itself is very likely the
same pooled-correlation leverage instability §6.2 identifies, not a generic property of this
training regime's curve shape** — the metric `_select_best_checkpoint()` selects on (pooled
correlation) is exactly the metric §6.2 shows is unstable under this loss, so a spike in the
selection signal is the expected consequence of selecting on a leverage-sensitive statistic, not
an independent, unrelated instability. The generalizable lesson is narrower than "any non-monotonic
curve breaks this convention": **it is that peak-correlation selection is specifically fragile when
the loss itself is designed to move a leverage-sensitive subset**, which any future experiment
touching a record subset (not just mate-labeled records) should anticipate. **Per this project's
"disclose, don't resolve unilaterally" convention** (`phase4-p4i-k-sweep.md` §6,
`phase4-p4i-replication.md` §6.1), this report does not silently substitute the final-step
checkpoint for the selected one — both are reported below (§6), and §7 discusses what this means
for interpreting the result, not just which number is "prettier."

## 6. Metric comparison

### 6.1 Pooled correlation, RMSE, calibration — the headline numbers

| Arm | Benchmark | n | Correlation | RMSE | Bias |
|---|---|---|---|---|---|
| Baseline | v1 | 4,000 | 0.5290 | 1240.4 | −215.2 |
| Baseline | v1-clean | 3,992 | 0.5933 | 1030.1 | −191.1 |
| **Candidate (selected, step 3,999)** | v1 | 4,000 | **0.5739** | 1261.6 | −213.4 |
| **Candidate (selected, step 3,999)** | v1-clean | 3,992 | **0.6433** | 1054.0 | −189.3 |
| Candidate (final, step 19,999) | v1 | 4,000 | 0.5652 | 1227.8 | −203.0 |
| Candidate (final, step 19,999) | v1-clean | 3,992 | 0.6316 | 1016.5 | −179.0 |

Read at face value, this looks like the strongest result in the entire Phase 4 arc: pooled
v1-clean correlation up +0.050 (selected) or +0.038 (final) — roughly 10x P4I's entire effect
size. **§6.2 shows this reading is wrong.**

### 6.2 The decisive check: mate-subset vs. cp-subset correlation

Per this task's own new requirement ("mate-subset-specific" correlation, §38.4's flagged unknown)
and this experiment's own design concern (mate-labeled records are a two-point mass at ±3,000cp —
high-leverage points in any pooled correlation):

| Arm | Benchmark | Pooled corr. | Mate-subset corr. (n=472) | **Cp-subset corr.** |
|---|---|---|---|---|
| Baseline | v1 | 0.5290 | 0.6401 | 0.3721 |
| Baseline | v1-clean | 0.5933 | 0.6401 | **0.5941** |
| Candidate (selected) | v1 | 0.5739 | 0.6598 | 0.3667 |
| Candidate (selected) | v1-clean | 0.6433 | 0.6598 | **0.5591** |
| Candidate (final) | v1 | 0.5652 | 0.6684 | 0.3807 |
| Candidate (final) | v1-clean | 0.6316 | 0.6684 | **0.5883** |

**The cp-labeled majority (88.5% of the corpus) did not improve.** At the selected checkpoint,
cp-subset correlation on v1-clean *regresses* from 0.5941 to 0.5591 — a clear, substantial drop.
At the final checkpoint it is 0.5883, still below baseline (not improved, roughly flat within what
this roadmap's metrics can resolve). Mate-subset correlation moved only modestly (+0.02 to +0.03).
**A change confined to reweighting 11.5% of records, which improved that subset's own correlation
by ~0.02-0.03, cannot legitimately improve the *pooled* correlation by 2-3x that amount unless the
pooled number is not measuring what it appears to.**

**Mechanism**: mate-labeled targets sit at a fixed, extreme value (±3,000cp) — a high-leverage
cluster in pooled Pearson correlation. `mate_labeled` bias moved from −1663.6 (baseline) to
−1621.5 (candidate, final checkpoint) and mate compression moved from 0.0998 to 0.1107 — i.e., the
mate cluster's *position relative to the cp bulk* shifted. Shifting a leverage cluster's position
mechanically moves a pooled correlation statistic, independent of whether the bulk's own ranking
improved. This is a different mechanism from, but the same *class* of finding as, Experiment 3A's
sentinel-filtering result (research doc §35): a metric moved because of the evaluation set's
composition and a subset's leverage within it, not because the model learned to predict the
records that make up most of the corpus any better. See `measurement-model.md` §4 for this
finding's permanent record.

### 6.3 Calibration and the regression guard (§23.5's failure mode, directly)

| Split | Metric | Baseline | Candidate (selected) | Candidate (final) |
|---|---|---|---|---|
| cp-labeled (v1-clean) | bias | +6.4 | +11.3 (worse) | +14.4 (worse) |
| cp-labeled (v1-clean) | compression | 0.303 | 0.176 (worse, further from 1.0) | 0.306 (~flat) |
| cp-labeled (v1-clean) | RMSE | 398.2 | 425.2 (worse) | 399.0 (~flat) |
| mate-labeled (v1-clean) | bias | −1663.6 | −1685.5 (worse) | −1621.5 (better) |
| mate-labeled (v1-clean) | compression | 0.0998 | 0.0685 (worse) | 0.1107 (better) |
| overall (v1-clean) | RMSE | 1030.1 | 1054.0 (worse) | 1016.5 (better) |

**At the officially-selected checkpoint, calibration regresses on both splits** — cp-labeled bias/
compression/RMSE all move away from baseline, and mate-labeled compression/bias also move away
from baseline (despite the mechanism's intent). This is §23.5's specific, named failure mode
(fixing one thing at another's expense) manifesting as *neither* subset improving at the selected
checkpoint. **At the final checkpoint, mate-labeled bias/compression genuinely improve** (closer to
zero/1.0, the theoretically-predicted direction) **while cp-labeled metrics are roughly flat**
(bias worsens modestly, compression/RMSE approximately unchanged) — a more mechanistically
coherent, if still not clearly promotable, picture. Overall RMSE improves at the final checkpoint
(1030.1→1016.5) but this is very likely inheriting the same pooling sensitivity as §6.2's
correlation finding (the mate cluster's improved bias pulls the pooled RMSE down without the cp
majority's own RMSE moving), not independently verified against a cp-subset RMSE in this pass.

## 7. Promotion-rule evaluation

This task's promotion rule: **(1)** meaningful improvement in the primary metric (v1-clean
correlation) **AND (2)** no material regression in RMSE or calibration. Exploratory metrics
(compression, bucket MAEs, histograms) are informative but never sufficient alone.

**Ground 1 — airtight, uses only the literal rule and the pre-existing checkpoint-selection
convention.** At the officially-selected checkpoint (step 3,999 — `_select_best_checkpoint()`'s own
answer, not a choice this report makes), condition (2) fails outright: overall RMSE regresses
(1030.1→1054.0), cp-labeled compression regresses substantially (0.303→0.176), cp-labeled bias
regresses (+6.4→+11.3). Whatever is true of condition (1) at this checkpoint, condition (2) alone
already fails the AND-gate. **This is sufficient by itself to decide not-promotable, and does not
depend on the pooling-artifact finding below.**

**Ground 2 — a second, independent finding, surfaced explicitly because it is new information the
user should have, not merely a restatement of Ground 1.** At the *final* (converged, step 19,999)
checkpoint, the literal rule as stated this turn would say **promote**: pooled v1-clean correlation
improves meaningfully (+0.038), RMSE improves (1030.1→1016.5), and cp-labeled bias's regression
(+6.4→+14.4) sits under this project's own 10cp guard band (`phase4_p4i_report.py::
_cp_majority_regressed`'s existing convention) — condition (2) does not clearly fail here. **§6.2's
subset-correlation check shows this reading is wrong anyway**: the cp-labeled majority's own
correlation is not improved at this checkpoint either (0.5941→0.5883, roughly flat-to-regressed,
not the +0.038 the pooled number implies), and the RMSE improvement likely inherits the same
pooling sensitivity rather than reflecting the cp-labeled subset's own error going down. **Without
this check, the literal promotion rule as stated this turn would have green-lit an artifact** — a
model that looks better on the one number the rule reads, while the 88.5% of records that number
is meant to represent did not improve. This is why `measurement-model.md` §4 now requires
subset-specific correlation as a mandatory cross-check whenever an intervention targets a record
subset, not an optional diagnostic: this experiment is the concrete case that motivated adding it.

**Neither ground rescues promotion, and they are independent of each other** — Ground 1 alone
would already block promotion even if the pooling-artifact question were never asked; Ground 2
shows that asking it was necessary regardless, because the rule's literal reading at the other
checkpoint would otherwise have passed a non-improvement.

## 8. Decision

**Not promotable. Terminate this test point; do not adopt `mate_weight=3.0`.** Per §7 Ground 1:
the officially-selected checkpoint fails the regression guard outright. Per §7 Ground 2: even at
the checkpoint where the literal rule would otherwise pass, the cp-labeled majority (88.5% of
records) did not actually improve — the pooled correlation number that made it look like it did is
a composition/leverage artifact (§6.2). This is a genuine, mechanistically-explained negative
result, not an inconclusive one — distinct from P4I's "uncorroborated, insufficient evidence either
way" outcome (`phase4-p4i-replication.md` §8). Do not begin RQ-3 (mate-target representation) or
any further Phase 4B experiment automatically, per this task's explicit stop condition.

**What this does and does not establish**:
- It does **not** establish that mate-aware loss weighting can never help the mate subset in
  isolation — mate-labeled bias/compression *did* move in the predicted direction at the final
  checkpoint, a real, if modest, mechanism confirmation consistent with §41.5's design intent (an
  additive term scoped to the mate-labeled branch). It establishes that, at `mate_weight=3.0`, this
  benefit does not transfer to (and at the selected checkpoint, comes with a real cost to) the
  cp-labeled majority that the pooled promotion metric is dominated by.
- It **does** establish a durable methodology finding, recorded permanently in
  `measurement-model.md` §4: pooled correlation, even the declared primary metric, is not immune
  to composition/leverage effects from a minority subset, and any future experiment whose
  independent variable targets a subset must report that subset's own correlation (and the
  complementary majority's) before trusting a pooled-metric movement.
- It **does** establish a second, independent methodology finding: `_select_best_checkpoint()`'s
  peak-*pooled-correlation* convention, validated only against monotonic-approach trajectories
  until this experiment, can select a transient, non-representative checkpoint when the metric it
  selects on is itself leverage-sensitive to the loss change under test (§6.2's mechanism, not a
  generic property of non-monotonic curves in general). This is preserved here per CLAUDE.md §7
  (engineering methodology findings belong in the durable record) as a caution for any future
  experiment whose independent variable targets a record subset small enough to act as a leverage
  point in pooled correlation — inspect the full trajectory and the subset-specific correlation
  (§6.2), not just the selected step's pooled number, before trusting a "peak" reading.
- It does **not** by itself rule out mate-target representation (RQ-3) or a smaller `mate_weight`
  — those remain open, unexecuted candidates per this roadmap's own sequencing, not addressed by
  this single test point.

## 9. Graphify validation (post-implementation)

```
$ graphify . --update --code-only
[graphify extract] incremental scan of .../p4ii-mate-weight
[graphify extract] --code-only: skipping 104 non-code file(s) (104 docs, 0 papers, 0 images) -- no LLM extraction
[graphify extract] 3 code, 0 docs, 0 papers, 0 images changed; 271 unchanged; 0 deleted
[graphify extract] AST extraction on 3 code files...
[graphify extract] wrote .../graphify-out/graph.json: 2546 nodes, 6976 edges, 156 communities
[graphify extract] incremental summary: 271 files cached/unchanged, 3 re-extracted, 0 deleted
```

**Architecture unchanged, confirmed directly**: exactly 3 code files changed since this
experiment's Graphify discovery baseline (§2) — `trainer/trainer/model/train.py`,
`trainer/tests/model/test_train.py`, and the new `trainer/scripts/phase4_p4ii_mate_weight.py` —
271 unchanged, 0 deleted. No file under `trainer/trainer/dataset`, `trainer/trainer/export`,
`trainer/trainer/quantization`, `trainer/trainer/model/network.py`,
`trainer/trainer/model/batching.py`, or any `engine-*` module appears in the changed-file list —
matching §2's pre-implementation module declaration exactly.

**Dependency check, re-queried for real** (`graphify query "phase4_p4ii_mate_weight.py
dependencies"`): resolves to `train.py`'s `train()`/`TrainingConfig`,
`phase4_p4i_k_sweep.py`'s reused helpers (`_eval_cell`, `_predictions`, `_magnitude_buckets`,
`_histogram`, `_load_model`, `_is_sentinel`, `_train_arm`), and
`train_candidate_net.py::combine_and_split()`/`report_dataset_quality()`/`_load_all()` — same
community (9) as `train.py` itself, and the same dependency shape as `phase4_p4i_replication.py`'s
own footprint before it. No edge into `network.py`, `batching.py`, `exporter.py`, `canonical.py`,
`quantizer.py`, or any Java engine code appears anywhere in this traversal — the weighted-loss
computation is training-time-only, exactly as §41.5's original complexity estimate ("high
compatibility... zero export/inference impact") predicted.

## 10. Learning log entry

```
## Experiment ID: P4II-001-mateweight
Hypothesis:            An additive mate-aware loss weight (mate_weight=3.0, cp-labeled records
                        unchanged at weight 1.0) reduces mate-labeled bias magnitude and/or
                        improves the primary promotion metric (v1-clean correlation) without
                        materially regressing RMSE or calibration on the cp-labeled majority.
Independent variable:  TrainingConfig.mate_weight only (1.0 -> 3.0). K held at production value
                        (2.773456), per RQ-2's own fallback and Phase 4A's closure.
Controlled variables:  steps=20000, lr=0.01, cosine schedule, warmup=200, batch=256, seed=42,
                        architecture (unchanged), sentinel-filtered training corpus (35,933
                        records, 11.51% mate-labeled), split seed=42.
Expected outcome:      Mate-labeled bias/compression move toward the theoretically-predicted
                        direction (per SS41.5's additive-term design); cp-labeled metrics
                        essentially unaffected (isolated by design) or regress if SS23.5's
                        single-transform trade-off recurs; pooled correlation movement, if any,
                        assumed to reflect genuine ranking improvement unless checked otherwise.
Observed outcome:       Pooled v1-clean correlation rose substantially (+0.038 to +0.050
                        depending on checkpoint) -- the largest number in this roadmap. Decisive
                        check (mate-subset vs. cp-subset correlation, SS6.2) showed this is a
                        pooling/leverage artifact: cp-subset correlation (88.5% of records) was
                        flat-to-regressed at every reading, not improved. Mate-labeled
                        bias/compression improved modestly at the final (converged) checkpoint,
                        consistent with the mechanism's intent, but calibration on both splits
                        regressed at the officially-selected (peak-correlation) checkpoint.
                        Discovered a non-monotonic ("overshoot-then-recede") training trajectory
                        not seen in any prior experiment in this roadmap -- the peak-correlation
                        checkpoint-selection convention selected a transient, non-representative
                        spike (step 3,999/19,999) rather than the run's own converged value.
Metrics:                See SS5-SS6 above; full detail in outputs/phase4/P4II/summary.json.
Decision:               NOT PROMOTABLE. Do not adopt mate_weight=3.0. Do not begin RQ-3 or any
                        further Phase 4B experiment automatically.
Reason rejected:        The primary metric's apparent improvement does not survive a composition
                        check (SS6.2) -- the cp-labeled majority, which the pooled metric is
                        dominated by, did not improve. This is a genuine, mechanistically-
                        explained negative result (a pooled-metric leverage artifact), not an
                        inconclusive one.
Next action:            No further Phase 4B experiment begins automatically, per this task's
                        instruction. Two durable methodology findings recorded permanently in
                        measurement-model.md SS4 (pooled-correlation composition sensitivity;
                        require subset-specific correlation whenever an intervention targets a
                        record subset) for every future Phase 4 experiment to inherit.
Artifacts:              trainer/outputs/phase4/P4II/
```
