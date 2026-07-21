# P4III: mate-target representation (RQ-3, Lever B)

**Date**: 2026-07-21. **Status**: Complete. **Decision: NOT PROMOTABLE.** Under the only fair,
same-rubric comparison (baseline re-evaluated against the new target definition vs. the candidate
trained toward it), the majority population (cp-only correlation) is flat, and — the sharper
finding — **the intervention's own intended target subset (mate-only correlation) did not move
meaningfully either.** A naive cross-rubric comparison (old-rubric baseline vs. new-rubric
candidate) would show a spectacular +0.05 pooled-correlation gain and a 582cp RMSE improvement;
both are entirely artifacts of redefining the target, present identically on the unretrained
baseline model. Production `K` unchanged at 2.773456; no model promoted.

## Metric declaration (mandatory, per updated methodology — declared before any numbers below)

- **Primary**: v1-clean pooled correlation (screening only, per `measurement-model.md` §1a).
- **Secondary**: v1 correlation, RMSE, calibration (regression guards, valid only under a
  consistent target-definition rubric — see §5).
- **Majority analysis**: cp-only correlation, cp-only RMSE, cp-only calibration (the decisive
  reading, per the majority-population rule, `measurement-model.md` §6).
- **Target subset**: mate-only correlation, mate-only RMSE, mate-only calibration (validates
  mechanism only, never sufficient for promotion).
- **Exploratory**: compression, magnitude-bucket MAEs, prediction histograms.

## 1. Candidate selection

RQ-3 is next per the roadmap's own pre-declared sequencing, not reconsidered here: research doc
§38.5/RQ-2's own definition already stated RQ-3 "runs after RQ-2... compared against what RQ-2's
loss-weighting approach alone achieves" — RQ-2 (P4II) is now closed, so RQ-3 is the next, and only,
candidate this task specifies. No other candidate (Huber/log-cosh, WDL, multi-head) was considered.

## 2. Graphify discovery (mandatory)

`graphify . --update --code-only` (fresh worktree): 2,539 nodes / 6,988 edges / 162 communities,
274 code files. `graphify query "target_cp mate distance representation dependencies"` traced the
full supervision pipeline and, critically, `target_cp()`'s consumers:

| Pipeline stage | Module | Touched by P4III? |
|---|---|---|
| Label generation | `dataset/text_provider.py`, `contracts/dataset.py` | No |
| **Target transformation** | `model/train.py::target_cp()`, `TrainingConfig.mate_target_distance_aware` | **Yes — the only production-code changes** |
| Batching | `model/batching.py::encode_batch()` | No |
| Loss computation | `model/train.py::train()` (loss line, calls `target_cp()`) | One-line change — now threads `config.mate_target_distance_aware` through explicitly (default `False`, matching `mate_weight`'s own opt-in discipline; this experiment's own `TrainingConfig` sets it `True`) |
| **Validation** | `validation/validator.py::evaluate_held_out()`, `calibration_report()` | **No code change** — both call `target_cp()` with no toggle, so they stay on the historical flat rubric automatically (`target_cp()`'s own default is `False`); confirmed via this query |
| Checkpoint selection | `_select_best_checkpoint()` (reused from `phase4_p4i_k_sweep.py`) | No |
| Reporting | New: `scripts/phase4_p4iii_mate_target.py` | New file, reuses `phase4_p4i_k_sweep.py`'s helpers where they remain valid; adds small local helpers that evaluate under an explicit, disclosed `distance_aware` choice where it doesn't (§3's design note) |

**Critical finding from this trace, acted on before any training ran (not discovered after)**:
`target_cp()` is consumed by both `train()` (training) and every evaluation path
(`evaluate_held_out`, `calibration_report`, and every downstream script that imports them). Left as
a bare function-level default, this would mean the moment `target_cp()`'s mate branch changes,
every historical baseline evaluation is silently regraded under whichever rubric happens to be
"live" — resolved by keeping `target_cp()`'s own default at the historical flat behavior (so
nothing changes for any caller that doesn't opt in) and threading the actual opt-in through
`TrainingConfig` for training, and through an explicit parameter for every evaluation call this
experiment's own script makes — the design
basis for §3's decomposition protocol below, not an afterthought.

**Every module this experiment modifies, declared before implementation**: `trainer/model/train.py`
(`target_cp()`'s mate branch, new `MATE_BASE_CP`/`MATE_FLOOR_CP`/`MATE_MAX_OBSERVED_N` constants,
and `TrainingConfig.mate_target_distance_aware`, default `False`) and
`trainer/tests/model/test_train.py` (new tests). Architecture, feature extraction
(`batching.py`, `network.py`), dataset generation, checkpointing, export, and quantization are not
imported for modification anywhere — confirmed by this trace and by §9's post-implementation
validation.

## 3. Experiment design

**Independent variable**: `target_cp()`'s mate-labeled branch only — replacing the flat
`MATE_EQUIVALENT_CP=3000` with a distance-aware linear interpolation between `MATE_BASE_CP` and
`MATE_FLOOR_CP`. `K` held at the production value (2.773456, unchanged, per RQ-2's precedent).
Everything else (architecture, features, dataset, optimizer, schedule, seed) frozen at
P1-G04/P3A-001's values.

**Target-value derivation — from `texel_sigmoid`'s own saturation formula, not chosen by eye**:
`eval_mate` is confirmed (this session, against both label sources — UCI's `score mate N` and
Lichess Cloud Eval's `mate` field) to be in **moves**, not plies. In the sentinel-filtered training
corpus, mate-labeled records span 0-64 moves (median 5, mean 7.9, n=4,137).

- `MATE_BASE_CP=1000.0` (mate-in-0/1, the shortest/most-common distance): anchored near the 99th
  percentile of `|eval_cp|` in the training corpus (measured: 976cp, rounded to 1000) — keeps short
  mates more extreme than ~99% of ordinary evaluations, at the cost of near-zero residual gradient
  at this end (gradient-ratio `sigma'(1000)/sigma'(0) ≈ 4.7e-7`) — an accepted trade-off since the
  shortest, most-certain mates are also the easiest for a reasonably-trained net to already predict
  confidently.
- `MATE_FLOOR_CP=227.8` (the longest observed mate distance, 64 moves): the point where residual
  gradient has decayed to exactly 10% of its zero-crossing peak (`sigma'(x)/sigma'(0) =
  4*sigma(x)*(1-sigma(x))`, solved numerically) — genuinely differentiable, restoring real training
  signal for the longest, least-certain mates.

**A disclosed confound, inherent to the mechanism, not an engineering oversight**: any target held
above roughly 1,000-1,200cp is float32-indistinguishable from full saturation under `K=2.773456`
(verified directly this session — `sigma(1200, K)` rounds to exactly `1.0`). This means
distance-awareness and magnitude de-scaling **cannot be decoupled** under this loss: keeping mate
targets "as extreme as the old flat 3000cp constant" for every distance would reproduce the exact
zero-gradient problem RQ-3 exists to fix. This experiment tests both together, by mechanistic
necessity — see §8 for the scope this caps the conclusion at.

**Decomposition protocol (§2's Graphify finding, operationalized)**: `target_cp()` gained a
`distance_aware: bool = False` parameter — the function's own default stays at the pre-Phase-4C
flat behavior, so every caller that doesn't explicitly opt in (every evaluation path, and any
future `train()` call that doesn't set the new config field) is unaffected. `train()`'s loss line
only uses the distance-aware formula when `TrainingConfig.mate_target_distance_aware=True` is
explicitly set (default `False`, same discipline as `mate_weight`'s own default) — this
experiment's own config sets it `True`. The new script's evaluation helpers
(`_eval_cell_with_target()` and friends) always take an explicit `distance_aware` argument rather
than relying on `target_cp()`'s bare default either way, so the baseline's "old rubric" and "new
rubric" numbers are each computed consistently across correlation *and* RMSE/calibration in one
function — an earlier draft of this script instead reused `_eval_cell()` (which calls the bare
default) for the "new rubric" reading while separately overriding only the correlation field for
"old rubric," which silently mixed old-rubric correlation with new-rubric RMSE; caught and fixed
before this report was written, not left in.

**Baseline reused, not retrained**: `P3A-001`'s checkpoint's *weights* are unaffected by which
`target_cp()` grades them — only training changes weights, evaluation is read-only. The baseline is
evaluated under both rubrics; only the candidate is trained.

## 4. Training report

| Arm | Selected step | Final step | Wall clock |
|---|---|---|---|
| **P4III-001 (mate-target)** | 16,999/19,999 | 19,999 | 154.8s |

**Monotonic convergence, unlike P4II** — `measurement-model.md` §8's mandatory both-checkpoints
report, applied: held-out correlation rises smoothly (0.3577 at step 999 → 0.4306 at step 8,999 →
0.4335 at the selected step 16,999 → 0.4334 at the final step 19,999) with no overshoot. The
selected and final checkpoints are nearly identical (Δcorrelation = 0.0001), in sharp contrast to
P4II's non-monotonic "overshoot-then-recede" trajectory (`phase4-p4ii-mate-weight.md` §5). Per
`measurement-model.md` §8's own caution against over-generalizing from one instance either way:
this shows the checkpoint-selection instability P4II found does not automatically recur under a
*target-representation* change, not that it never can.

## 5. Metric comparison

### 5.1 Primary — v1-clean pooled correlation (screening only, §1a)

| Comparison | v1-clean pooled correlation | Same rubric? |
|---|---|---|
| Baseline, old rubric (flat target) | 0.5933 | — (historical reference, matches every prior report) |
| Baseline, new rubric (distance-aware target) | 0.6467 | — |
| Candidate, final checkpoint (new rubric) | 0.6466 | — |

**Read naively (old-rubric baseline vs. new-rubric candidate: 0.5933 → 0.6466), this looks like the
largest gain in the entire roadmap (+0.053). §5.2 shows this reading is invalid.**

### 5.2 The decisive check: same-rubric baseline vs. candidate

Comparing the baseline **under the rubric the candidate is actually graded on** (both new-target)
isolates the pure training effect, with the pure-definitional shift removed:

| Metric | Baseline (new rubric) | Candidate (final) | Δ |
|---|---|---|---|
| Pooled correlation (v1-clean) | 0.6467 | 0.6466 | **−0.0001 (flat)** |
| Pooled correlation (v1) | 0.4335 | 0.4334 | **−0.0001 (flat)** |

**Every genuine training effect this experiment produced, on the pooled metric, is flat to within
rounding.** The entire apparent +0.053 gain in §5.1 is the pure-definitional artifact
`measurement-model.md` §5 now documents permanently: the *identical, untrained* baseline model
"gains" +0.053 pooled correlation purely from being graded on a rubric its own targets were never
optimized for, with zero learning involved.

**The v1 pooled-correlation drop between old and new rubric (0.5290 → 0.4335, baseline-vs-itself)
is likewise definitional, not learned** — most plausibly the 8 sentinel-defect records (present
only in v1, not v1-clean) interacting with the target rescale in a way that happens to reduce
pooled correlation on that benchmark specifically. Not investigated further: v1 is not the primary
benchmark, and the same-rubric comparison above (which is what actually matters) shows this
benchmark, too, is flat between baseline and candidate.

### 5.3 Secondary — v1 correlation, RMSE, calibration (regression guards, same-rubric only)

| Metric (v1-clean, new rubric) | Baseline | Candidate (final) | Δ |
|---|---|---|---|
| RMSE (overall) | 448.2 | 448.1 | flat |
| Bias (overall) | −40.2 | −39.1 | flat |
| Compression (overall) | 0.3062 | 0.3065 | flat |

**No regression, but also no improvement — flat across the board**, consistent with §5.2's pooled
finding. **Do not cite the old-rubric-vs-new-rubric RMSE change (1030.1→448.2, both baseline) as a
regression-guard result** — that is the same pure-definitional artifact as §5.1's correlation
number, present on the untrained baseline, carrying zero information about the candidate.

### 5.4 Majority analysis — cp-only correlation, RMSE, calibration (the decisive metric, §6)

| Metric (v1-clean) | Baseline (either rubric — target-invariant) | Candidate (final) | Δ |
|---|---|---|---|
| cp-only correlation | 0.5941 | 0.5931 | **−0.0010 (flat, slightly down)** |
| cp-only RMSE | 398.2 | 398.2 | flat |
| cp-only bias | +6.4 | +7.6 | flat (small) |
| cp-only compression | 0.3033 | 0.3045 | flat |

**cp-labeled records never touch `target_cp()`'s mate branch, so this reading is immune to both
P4II's leverage artifact and P4III's rubric-contamination artifact — the single most trustworthy
number in this entire report.** The majority population (88.5% of the corpus) shows no meaningful
change in any metric. Per `measurement-model.md` §7 condition 4, this alone is sufficient to block
promotion, independent of anything else in this report.

### 5.5 Target subset — mate-only correlation, RMSE, calibration (mechanism validation, not promotion)

| Metric (v1-clean, new rubric — the only fair comparison for this subset, §3) | Baseline | Candidate (final) | Δ |
|---|---|---|---|
| mate-only correlation | 0.6453 | 0.6484 | **+0.0031 (small, likely noise)** |
| mate-only RMSE | 718.7 | 718.1 | flat |
| mate-only bias | −388.0 | −387.5 | flat |
| mate-only compression | 0.3259 | 0.3242 | flat |

**This is the sharper, more decisive finding of this experiment, not a footnote**: RQ-3's
mechanism was designed specifically to restore differential gradient signal for mate-labeled
records. Under the one fair, same-rubric comparison, **the mate-labeled subset itself — the
intervention's own intended target — did not move meaningfully either.** This is not the
"tautological, expect-it-to-move" reading a naive cross-rubric mate-subset comparison would
produce (baseline-old vs. candidate-new mate correlation: 0.6401→0.6484, which *would* look like a
real gain) — under the correct, same-rubric reading, the mechanism produced no detectable effect on
its own target, let alone the majority population.

### 5.6 Exploratory — compression, magnitude buckets, histograms

Consistent with §5.3-§5.5: reviewed and found flat-to-negligible across every exploratory metric on
both subsets, no pattern worth a dedicated table. Full detail in `outputs/phase4/P4III/summary.json`
if needed for a future comparison; not reproduced here since none of it changes §6's conclusion and
this task's own rule states exploratory metrics may never independently justify promotion or, by
the same logic, warrant extended discussion when everything decisive is already flat.

## 6. Promotion-rule evaluation (per `measurement-model.md` §7)

1. **Screening (pooled v1-clean correlation)**: passes at face value only under the invalid
   cross-rubric reading (§5.1); under the correct same-rubric reading (§5.2), flat — does not
   clear the screening bar in any meaningful sense.
2. **RMSE regression guard**: no regression under the valid same-rubric comparison (§5.3) — but
   also no improvement; this condition alone would not block promotion.
3. **Calibration regression guard**: same as (2) — flat, would not alone block promotion.
4. **Majority-population condition (cp-only correlation)**: flat, slightly down (§5.4) — **fails to
   show improvement, the decisive condition.**

**Not promotable.** Condition 1 fails on the only valid reading; condition 4 independently fails
regardless. Unlike P4II (where the promotion question turned on interpreting a genuine but
uncorroborated pooled signal), this result is unambiguous: once the rubric-contamination artifact
is removed, there is no signal to interpret at all.

## 7. Decision

**Not promotable.** Per this task's stop condition and the roadmap's checkpoint-scoped isolation
policy (research doc §26.5), **this experiment's checkpoint is not promoted and does not become the
reference model** — P1-G04 (via P3A-001) remains the reference model, exactly as after P4I and
P4II. `target_cp()`'s `distance_aware` parameter and `TrainingConfig.mate_target_distance_aware`
remain in the codebase (harmless: both default to `False`, reproducing every pre-existing
config/checkpoint's behavior exactly, so no other caller or future experiment is silently affected
by this code existing) — kept because they are required for any future re-examination of this
candidate, not because this result promotes them as an improvement.

## 8. What this does and does not establish (scope-capped, per the disclosed confound in §3)

- **It does not establish that mate-target representation is a dead end as a class.** This
  experiment tested one specific, disclosed parameterization (`MATE_BASE_CP=1000`,
  `MATE_FLOOR_CP=227.8`, linear interpolation) that necessarily bundles distance-awareness with a
  large magnitude de-scaling (§3's confound). The null result falsifies this *instantiation* under
  the current `K`, not the general idea of distance-aware mate targets.
- **It does establish**, independent of the specific parameterization, that RQ-2 (loss weighting)
  and RQ-3 (target representation) — two structurally different interventions — both found **no
  purchase on the mate-labeled subset's own metrics**, under the only fair, same-rubric readings
  each experiment produced. `measurement-model.md` §10 promotes this into a durable, forward-looking
  synthesis: under the current single-scalar-output-plus-sigmoid-loss architecture, mate-specific
  loss/target reshaping appears structurally capped, and future mate-handling work is more likely
  to need an architecture- or output-level change (a separate head, a WDL target source) than
  further reshaping within the existing loss. This is evidence for future candidate-ranking
  discussions, not a re-ranking performed here.
- **It does establish a second, independent methodology finding**, now permanent in
  `measurement-model.md` §5/§6: whenever an experiment's independent variable is a target
  *definition* (not just a weighting), any metric that isn't structurally rubric-invariant must be
  computed under both the old and new definitions for any pre-existing model before a comparison is
  trusted — this experiment is the concrete case that forced this rule into existence, the same way
  P4II's leverage artifact forced the majority-population rule into existence.

## 9. Graphify validation (post-implementation)

```
$ graphify . --update --code-only
[graphify extract] incremental scan of .../p4iii-mate-target
[graphify extract] --code-only: skipping 105 non-code file(s) (105 docs, 0 papers, 0 images) -- no LLM extraction
[graphify extract] 3 code, 0 docs, 0 papers, 0 images changed; 272 unchanged; 0 deleted
[graphify extract] AST extraction on 3 code files...
[graphify extract] wrote .../graphify-out/graph.json: 2569 nodes, 7038 edges, 166 communities
[graphify extract] incremental summary: 272 files cached/unchanged, 3 re-extracted, 0 deleted
```

**Architecture unchanged, confirmed directly**: exactly 3 code files changed since this
experiment's Graphify discovery baseline (§2) — `trainer/model/train.py`,
`trainer/tests/model/test_train.py`, and the new `trainer/scripts/phase4_p4iii_mate_target.py` —
272 unchanged, 0 deleted. No file under `dataset/`, `export/`, `quantization/`, `network.py`,
`batching.py`, or any `engine-*` module appears in the changed-file list, matching §2's
pre-implementation declaration exactly.

**Dependency check, re-queried for real** (`graphify query "phase4_p4iii_mate_target.py
dependencies"`): resolves to `train.py`'s `train()`/`TrainingConfig`/`target_cp()`, the new script's
own `_eval_cell_with_target()`/`_predictions_with_target()`/`_train_p4iii_arm()`, and
`phase4_p4i_k_sweep.py`'s reused helpers (`_eval_cell`, `_predictions`, `_load_model`,
`_select_best_checkpoint`, `_magnitude_buckets`, `_train_arm`) plus
`train_candidate_net.py::combine_and_split()`/`report_dataset_quality()`/`_load_all()` — same
community (1, alongside `train.py` itself) as every prior Phase 4 script's own footprint. No edge
into `network.py`, `batching.py`, `exporter.py`, `canonical.py`, `quantizer.py`, or any Java engine
code appears anywhere in this traversal — the target-representation change is training/
evaluation-time only, exactly as §41.5's original complexity estimate ("high compatibility...
zero export/inference impact") predicted for this class of change.

**No new architectural coupling, no technical debt introduced**: `TrainingConfig.
mate_target_distance_aware` follows the exact pattern `mate_weight` (P4II) already established —
one new boolean field, default preserving all prior behavior, no new abstraction layer. The only
new indirection is `target_cp()`'s own `distance_aware` parameter, which is a plain default
argument, not a new class or protocol.

## 10. Learning log entry

```
## Experiment ID: P4III-001-mate-target
Hypothesis:            Replacing the flat MATE_EQUIVALENT_CP target with a mate-distance-aware
                        target restores differential gradient signal for mate-labeled records
                        (RQ-3's stated mechanism), improving mate-labeled bias/correlation and,
                        potentially, the primary promotion metric (v1-clean correlation) without
                        regressing the cp-labeled majority.
Independent variable:  target_cp()'s mate branch only (flat MATE_EQUIVALENT_CP=3000 ->
                        distance-aware linear interpolation, MATE_BASE_CP=1000 to
                        MATE_FLOOR_CP=227.8 over 0-64 observed moves-to-mate). K held at
                        production value (2.773456), unchanged.
Controlled variables:   steps=20000, lr=0.01, cosine schedule, warmup=200, batch=256, seed=42,
                        architecture (unchanged), sentinel-filtered training corpus (35,933
                        records), split seed=42.
Expected outcome:       Mate-labeled correlation/bias improve under a same-rubric comparison if
                        the distance-aware target restores real gradient signal; cp-labeled
                        metrics essentially unaffected (isolated by design) or regress if
                        SS23.5's single-transform trade-off recurs.
Observed outcome:       A naive cross-rubric comparison (old-target baseline vs. new-target
                        candidate) showed a spectacular +0.053 pooled correlation gain and a
                        582cp RMSE improvement -- entirely a definitional artifact, present
                        identically on the untrained baseline model graded under the new rubric
                        (SS5.1-SS5.2). Under the correct same-rubric comparison: cp-only
                        correlation flat (-0.0010, the decisive majority-population reading,
                        SS5.4); mate-only correlation -- the intervention's own intended target --
                        also flat (+0.0031, SS5.5). No meaningful learned effect in either
                        subset. Checkpoint selection converged monotonically this run (unlike
                        P4II), selected and final checkpoints nearly identical.
Metrics:                See SS5 above; full detail in outputs/phase4/P4III/summary.json.
Decision:               NOT PROMOTABLE. Do not adopt this parameterization as the reference
                        model. Do not begin another Phase 4 experiment automatically.
Reason rejected:        Screening condition fails under the only valid (same-rubric) reading;
                        majority-population condition independently fails. The apparent large
                        pooled gain is a rubric-contamination artifact (measurement-model.md SS5),
                        not evidence of anything learned.
Next action:            No further RQ-3 variant or Phase 4 experiment begins automatically, per
                        this task's explicit stop condition. Two permanent methodology additions
                        recorded in measurement-model.md (SS5 rubric-consistency requirement,
                        SS6 majority-population rule extended to target-definition changes) for
                        every future Phase 4 experiment to inherit. SS10's cross-experiment
                        synthesis (P4II+P4III both found no purchase on mate-labeled metrics
                        within the current loss architecture) recorded as a candidate-ranking
                        input for future planning, not acted on here.
Artifacts:              trainer/outputs/phase4/P4III/
```
