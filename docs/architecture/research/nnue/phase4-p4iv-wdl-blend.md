# P4IV: WDL-blended training target (RQ-4, Lever C)

**Date**: 2026-07-21. **Status**: Complete. **Decision: NOT PROMOTABLE.** The majority-population
metric (cp-only correlation, examined first per `measurement-model.md` §6) is not improved — it is
slightly *regressed* at both the selected and final checkpoint (v1-clean: 0.5941 → 0.5904/0.5903).
Every other metric in the battery is flat to within noise. Unlike P4III, this result required no
rubric-decomposition to interpret: WDL blending lives entirely inside `train()`'s loss line and
never touches `target_cp()` or any evaluation call path, so the baseline is evaluated under the
exact same rubric the candidate is — a direct, single-rubric null. Production `K` unchanged at
2.773456; no model promoted.

## Metric declaration (mandatory, per permanent methodology — declared before any numbers below)

Per this task's explicit reporting-order instruction, which matches and reaffirms
`measurement-model.md` §6's majority-population rule:

- **Reported first — cp-only correlation** (majority population, decisive per §7 condition 4).
- **Then — pooled metrics** (v1-clean and v1 pooled correlation, screening only per §1a).
- **Then — calibration** (RMSE, bias, compression — overall and per cp/mate split, regression
  guards per §7 conditions 2–3).
- **Then — exploratory diagnostics** (magnitude-bucket MAEs, prediction histograms — informative,
  never decisive per §1a).
- **Mate-only correlation** is reported alongside cp-only as a mechanism check (validates whether
  the intervention did anything to the mate-labeled population), never sufficient for promotion
  alone, per §7.

## 1. Candidate selection

Per `docs/architecture/research/nnue/phase4c-reranking-wdl-audit.md` (2026-07-21), WDL blend is
the highest-expected-value remaining Phase 4 candidate: the only lever whose mechanism (target-
source substitution) is untouched by the `σ'(p,K)` saturation wall that closed both P4II and P4III,
and its data-availability risk — the main uncertainty behind its original "Unknown, precedent-only"
rating (research doc §45) — is resolved. Not reconsidered here; Huber/log-cosh is explicitly not
run, per this task's instruction.

## 2. Graphify discovery (mandatory)

`graphify . --update --code-only` (fresh worktree): 2,562 nodes / 7,052 edges / 169 communities,
275 code files. `graphify query "PositionLabel wdl shard write_shard read_shard SHARD_DTYPE
target_cp texel_sigmoid TrainingConfig evaluate_held_out calibration_report"` traced the full WDL
data path before implementation:

| Pipeline stage | Module | Touched by P4IV? |
|---|---|---|
| Source EPD | `data/quiet-labeled.epd` (repo-external, 725,000 lines, 100% `c9`-populated per the audit) | No — read-only, joined against |
| Parser | `trainer/scripts/stockfish_label.py::_read_fens` | **No** — the existing production shard was already built from a pre-cleaned input (audit §5.3); backfilling reads the *shard*, not this parser |
| Shard format | `trainer/trainer/dataset/mmap_shard.py::SHARD_DTYPE` | **Yes** — `wdl`/`has_wdl` fields added (§3) |
| Dataset provider | `trainer/trainer/dataset/stockfish_provider.py::StockfishLabeledProvider` | No — reuses `read_shard()` unmodified, transparent to the new field |
| Labeler (target construction) | `trainer/trainer/model/train.py::train()`'s loss line | **Yes** — `TrainingConfig.wdl_lambda` blend (§3) |
| Training target | `target_cp()`, `texel_sigmoid()` | **No** — unchanged; the blend happens *after* `texel_sigmoid()`, directly on `PositionLabel.wdl`, never through `target_cp()` |
| Validation | `trainer/trainer/validation/validator.py::evaluate_held_out`, `calibration_report` | **No code change** — both call `target_cp()`/`texel_sigmoid()` with no `wdl_lambda` awareness at all, so evaluation is structurally invariant to this experiment's independent variable |
| Checkpoint selection | `_select_best_checkpoint()` (reused from `phase4_p4i_k_sweep.py`) | No |
| Reporting | New: `trainer/scripts/phase4_p4iv_wdl_blend.py` | New file, reuses `phase4_p4i_k_sweep.py`'s helpers |

**Critical finding from this trace, acted on before any training ran**: because `wdl_lambda`'s
blend never touches any function `validator.py` calls, this experiment carries **none** of P4III's
rubric-contamination risk — the baseline and candidate are evaluated identically, with no need for
a dual-rubric decomposition. This was determined by tracing the dependency graph, not assumed.

**A second finding, requiring a decision before implementation**: `read_shard()`/`SHARD_DTYPE` is
consumed only by `StockfishLabeledProvider` (Stage 2) — `TextDatasetProvider` (Stage 1) reads plain
CSV and never touches this module at all, confirmed by direct code read. Extending `SHARD_DTYPE`
is therefore a **breaking format change for exactly one file on disk**
(`outputs/datasets/stage2-quiet-sf/shard-0.bin`, the production Stage 2 shard every P4I–P4III
experiment trained against) — verified directly this session that `np.memmap` raises `ValueError`
(loud failure, not silent misparse) when the old file is opened under the new dtype. §3 details the
migration.

**Every module this experiment modifies, declared before implementation**: `trainer/trainer/
dataset/mmap_shard.py` (`SHARD_DTYPE` extension), `trainer/trainer/model/train.py`
(`TrainingConfig.wdl_lambda`, the loss-line blend), plus two new scripts
(`backfill_stage2_wdl.py`, `phase4_p4iv_wdl_blend.py`) and their tests. Architecture (`network.py`),
feature extraction (`batching.py`), export (`exporter.py`, `canonical.py`), quantization
(`quantizer.py`), and every evaluation function are **not** imported for modification anywhere —
confirmed both by this trace and by §8's post-implementation Graphify validation.

## 3. Experiment design

**Independent variable**: `TrainingConfig.wdl_lambda` only (1.0 → 0.5). `K` held at the production
value (2.773456, unchanged). Architecture, features, dataset *content* (only its storage format and
one added field change — see below), optimizer, checkpointing, export, quantization, seed (42),
steps (20,000), LR/schedule (0.01, cosine, warmup 200) all frozen at P1-G04/P3A-001's values.

**Shard migration, not dataset regeneration**: `SHARD_DTYPE` gained `wdl`/`has_wdl` fields.
`trainer/scripts/backfill_stage2_wdl.py` (new, one-off) reads the existing 20,000-record Stage 2
shard via a frozen, local copy of the pre-change dtype (`_LEGACY_SHARD_DTYPE_NO_WDL`, not imported
from `mmap_shard.py`, since that module's own `SHARD_DTYPE` has already moved on by the time this
script runs), joins each record's `wdl` from `data/quiet-labeled.epd` by exact FEN match, and writes
every record — **in original order, none dropped** — to a new directory
(`outputs/datasets/stage2-quiet-sf-wdl/`), leaving the original `stage2-quiet-sf/` untouched
(non-destructive). Real run: **20,000/20,000 records preserved; 19,983 (99.9%) matched a `wdl`
value; 17 (0.09%) left unmatched** (FEN absent from the EPD file or landed on one of the excluded
conflicting-outcome duplicates) — close to the audit's own pre-registered estimate (§5.3: 20,000/
20,000 matched, 248/724,127 corpus-wide conflicts; this run's 247 conflicts and 17 unmatched
records are consistent with that, not a new finding).

**Sign convention, the correctness-critical step, tested explicitly**: `PositionLabel.wdl` and
`eval_cp` are both mover-relative (positive/high = good for the side to move). `c9`'s game-result
string is White-perspective. `_wdl_from_result(side_to_move, result)` converts between the two —
covered by a dedicated parametrized test
(`tests/scripts/test_backfill_stage2_wdl.py::test_wdl_from_result_sign_convention`) exercising all
four side-to-move × White-won/Black-won combinations plus draws, not just the trivial
White-to-move case (a flipped sign would train and evaluate without error, producing a
null-or-regression result indistinguishable from a genuine null — this is the one bug class that
would have silently invalidated this experiment's conclusion).

**Split-preservation, verified empirically, not merely asserted**: `combine_and_split`'s fixed-seed
shuffle depends only on list length and seed, not content — so if the backfilled shard has the
identical record count and order as the original, the train/held-out split is bit-identical to
every prior P4I–P4III experiment, with only `wdl` added. Verified directly this session: the
migrated shard's FEN sequence and every `eval_cp`/`eval_mate` value are byte-identical to the
original shard's, in the same order (`old_fens == new_fens` confirmed programmatically, zero
`eval_cp`/`eval_mate` mismatches across all 20,000 records).

**`wdl_lambda=0.5`, disclosed derivation**: DR-E1's own λ convention (1.0 = pure eval, 0.0 = pure
outcome) is scoped to Stage 3 self-play generation, not this backfilled-corpus retrofit, so no
prior value is prescribed. 0.5 — an equal-weight blend — is the single, pre-declared test point:
the natural midpoint, not the most aggressive endpoint (which would discard all search-derived
signal for ~56% of the training corpus) or the most conservative one (indistinguishable from
baseline), mirroring P4II's own precedent of testing a moderate rather than maximal value on a
one-shot test.

**Scoping note, methodologically distinct from P4II/P4III**: `wdl` is populated by FEN membership
in the backfilled Stage 2 shard (50.07% of the sentinel-filtered training corpus, measured), which
cuts across *both* the cp-labeled and mate-labeled populations — unlike P4II/P4III's changes, which
were scoped to the mate-labeled branch only. cp-only correlation remains the decisive
majority-population metric (cp-labeled records are still 88.5% of the corpus overall), but it is
not structurally immune to this intervention the way it was to P4II/P4III's mate-scoped changes —
most cp-labeled Stage 2 records are themselves part of the wdl-blended population. Stated here so
§5's reading isn't mistaken for an automatically-isolated check.

**Baseline reused, not retrained**: `P3A-001`'s checkpoint is evaluated directly — no rubric change
means no re-evaluation-under-multiple-definitions is needed (contrast P4III §3).

## 4. Training report

| Arm | Selected step | Final step | Wall clock |
|---|---|---|---|
| **P4IV-001-wdl-blend** | 16,999/19,999 | 19,999 | 169.7s |

**Monotonic convergence, like P4III, unlike P4II** — `measurement-model.md` §8's mandatory
both-checkpoints report, applied: held-out correlation rises smoothly (0.4606 at step 999 → 0.5228
at step 4,999 → 0.5264 at the selected step 16,999 → 0.5262 at the final step 19,999) with no
overshoot. Selected and final checkpoints are nearly identical (Δcorrelation = 0.0002). Per
`measurement-model.md` §8's own caution against over-generalizing: this is a third instance
(alongside P4III) of a subset-touching change converging monotonically, still not grounds to
conclude the P4II instability is confined to loss-weighting changes specifically.

## 5. Metric comparison (reported in the mandated order: cp-only → pooled → calibration → exploratory)

### 5.1 cp-only correlation (majority population, examined first per §6 — the decisive metric)

| Benchmark | Baseline | Candidate (selected) | Candidate (final) | Δ (selected) |
|---|---|---|---|---|
| v1-clean | 0.5941 | 0.5904 | 0.5903 | **−0.0037** |
| v1 | 0.3721 | 0.3663 | 0.3661 | **−0.0058** |

**The majority-population metric is not improved — it is slightly regressed at every reading.**
Per `measurement-model.md` §7 condition 4, this alone is sufficient to block promotion, independent
of anything else in this report. No rubric-contamination confound applies here (§2, §3) — this is a
direct, single-rubric comparison, the cleanest reading this roadmap has produced to date.

### 5.2 mate-only correlation (mechanism check, never sufficient alone)

| Benchmark | Baseline | Candidate (selected) | Candidate (final) | Δ (selected) |
|---|---|---|---|---|
| v1-clean | 0.6401 | 0.6392 | 0.6394 | −0.0009 (flat) |
| v1 | 0.6401 | 0.6392 | 0.6394 | −0.0009 (flat) |

Flat within what this roadmap's metrics can resolve. Since mate-labeled Stage 2 records also
receive `wdl` (§3's scoping note), this is a legitimate same-rubric mechanism check, not merely a
side observation — and it shows the same absence of effect the majority population shows.

### 5.3 Pooled correlation (screening only, §1a)

| Benchmark | Baseline | Candidate (selected) | Candidate (final) |
|---|---|---|---|
| v1-clean | 0.5933 | 0.5926 | 0.5924 |
| v1 | 0.5290 | 0.5264 | 0.5262 |

Consistent with §5.1/§5.2 — flat to slightly down, no leverage or rubric artifact inflating this
number the way P4II/P4III's pooled readings were inflated (no minority high-leverage subset was
redefined or reweighted here). Screening condition (§7 condition 1) does not clear.

### 5.4 Calibration (RMSE, bias, compression — regression guards, §7 conditions 2–3)

| Split (v1-clean) | Metric | Baseline | Candidate (selected) | Candidate (final) |
|---|---|---|---|---|
| cp-labeled | RMSE | 398.2 | 399.3 | 399.2 |
| cp-labeled | bias | +6.4 | +7.5 | +8.5 |
| cp-labeled | compression | 0.3033 | 0.3001 | 0.3014 |
| mate-labeled | RMSE | 2791.5 | 2792.2 | 2791.0 |
| mate-labeled | bias | −1663.6 | −1663.8 | −1662.5 |
| overall | RMSE | 1030.1 | 1030.8 | 1030.3 |
| overall | bias | −191.1 | −190.1 | −189.1 |

**No meaningful regression, but also no improvement — flat to within a few cp across every split**,
consistent with §5.1–§5.3's finding that the intervention produced no detectable learned effect in
either direction. Conditions 2–3 do not independently block promotion, but they do not rescue it
either — condition 4 (§5.1) already does.

### 5.5 Exploratory diagnostics (magnitude buckets, never decisive)

Non-mate magnitude-bucket MAE/bias (v1-clean): near-zero 53.9/+8.3 (baseline) vs. 53.6/+10.5
(candidate); moderate 72.0/−4.0 vs. 72.1/−3.5; large 328.7/+50.5 vs. 331.3/+51.6; extreme
1197.0/−547.1 vs. 1197.4/−546.4. Flat-to-negligible across every bucket, consistent with §5.1–§5.4.
No pattern worth a dedicated discussion, per this task's own rule that exploratory metrics may
never independently justify promotion or warrant extended analysis when everything decisive is
already flat. Full detail in `outputs/phase4/P4IV/summary.json`.

## 6. Promotion-rule evaluation (per `measurement-model.md` §7, and this task's explicit rule)

This task's stated promotion rule: **WDL may only be considered if the majority-population metric
improves and there is no regression in regression-guard metrics.**

1. **Majority-population improvement (cp-only correlation)**: **fails** — regressed at every
   reading (§5.1).
2. **No regression in RMSE**: technically holds (movements are within a few cp, arguably noise) —
   but this condition alone cannot rescue a candidate that already fails condition 1.
3. **No regression in calibration**: same as (2).

**Not promotable.** Condition 1 fails outright, and per this task's own rule this is sufficient by
itself — conditions 2–3 not regressing does not change the outcome. Unlike P4II (where the
promotion question turned on disentangling a leverage artifact) or P4III (where it turned on
removing a rubric-contamination artifact), this result required no such disentanglement: the
majority population simply did not improve, under a comparison with no confound to correct for.

## 7. Decision

**Not promotable.** `wdl_lambda=0.5` is not adopted. P1-G04 (via P3A-001) remains the reference
model, exactly as after P4I, P4II, and P4III. `TrainingConfig.wdl_lambda` remains in the codebase
harmlessly (default `1.0`, reproducing every pre-existing config/checkpoint's behavior exactly, the
same discipline `mate_weight`/`mate_target_distance_aware` already established) — kept because it
is required for any future re-examination of this candidate at a different λ, not because this
result promotes it. `SHARD_DTYPE`'s `wdl`/`has_wdl` fields and the backfilled
`stage2-quiet-sf-wdl/` shard also remain — genuine, reusable infrastructure regardless of this
specific λ's outcome (§9).

## 8. What this does and does not establish (scope-capped)

- **It does not establish that WDL blending is a dead end as a class.** This experiment tested one
  specific, disclosed point (λ=0.5, on the existing backfilled Stage 2 corpus only, Stage 1
  contributing no wdl signal at all — §3's scoping note). The null result falsifies this
  *instantiation*, not the general idea of outcome-derived supervision, and not other λ values.
- **It does not, by itself, mean WDL blending "produced no effect."** §5.1–§5.4 show it moved
  nothing detectably in *either* direction on any metric — a genuinely flat result, not a
  regression large enough to indicate active harm, and not a signal large enough to indicate
  benefit. This is the cleanest null in the Phase 4 roadmap to date (no artifact to disentangle).
- **It does establish, alongside P4II and P4III, a third independent Phase 4 intervention that
  produced no promotable effect** — a loss reweighting (P4II), a target reformulation (P4III), and
  now a target-source blend (P4IV) have each been tried once, each finding no purchase on the
  majority population's own correlation. `measurement-model.md` §10's synthesis is extended (not
  reopened) to reflect this third data point — see that file's own update.
- **It does establish reusable infrastructure independent of this result**: `SHARD_DTYPE`'s `wdl`
  field, the backfill script, and `TrainingConfig.wdl_lambda` are all available for a future
  re-examination (a different λ, a swept λ, or a genuine Stage 3 self-play source once that exists)
  without repeating this session's shard-migration or sign-convention work.
- **It does not establish anything about a swept λ.** Per this task's stop condition, no further λ
  value is tested this turn — a null at λ=0.5 is not chased with a sweep, which would be a second
  experiment.

## 9. Graphify validation (post-implementation)

```
$ graphify . --update --code-only
[graphify extract] incremental scan of .../p4-wdl-blend
[graphify extract] --code-only: skipping 106 non-code file(s) (106 docs, 0 papers, 0 images) -- no LLM extraction
[graphify extract] 7 code, 0 docs, 0 papers, 0 images changed; 271 unchanged; 0 deleted
[graphify extract] wrote .../graphify-out/graph.json: 2603 nodes, 7087 edges, 164 communities
[graphify extract] incremental summary: 271 files cached/unchanged, 7 re-extracted, 0 deleted
```

**Architecture unchanged, confirmed directly**: exactly 7 code files changed since this
experiment's Graphify discovery baseline (§2) — `trainer/trainer/dataset/mmap_shard.py`,
`trainer/trainer/model/train.py`, `trainer/scripts/backfill_stage2_wdl.py`,
`trainer/scripts/phase4_p4iv_wdl_blend.py`, and their three test files — 271 unchanged, 0 deleted.
Matches §2's pre-implementation declaration exactly; `git status` independently confirms no file
under `trainer/trainer/model/network.py`, `trainer/trainer/model/batching.py`,
`trainer/trainer/export/`, `trainer/trainer/quantization/`, `trainer/trainer/dataset/
text_provider.py`, or any `engine-*` module changed.

**Dependency check, re-queried for real** (`graphify query "phase4_p4iv_wdl_blend.py
backfill_stage2_wdl.py dependencies"`): `phase4_p4iv_wdl_blend.py` resolves to `train.py`'s
`train()`/`TrainingConfig`, `phase4_p4i_k_sweep.py`'s reused helpers, and
`train_candidate_net.py::combine_and_split()` — same community as every prior Phase 4 script's own
footprint. `backfill_stage2_wdl.py` resolves *only* to `mmap_shard.py::write_shard()` and
`trainer.contracts` — deliberately no edge into `stockfish_label.py` (the labeling driver it does
not need and does not import, per its own docstring's design note). No edge into `network.py`,
`batching.py`, `exporter.py`, `canonical.py`, `quantizer.py`, or any Java engine code appears
anywhere in either traversal — the WDL blend is training/dataset-generation-time only, zero export/
inference impact, exactly as the audit's cost estimate predicted.

**No new architectural coupling, no technical debt introduced**: `TrainingConfig.wdl_lambda`
follows the exact pattern `mate_weight`/`mate_target_distance_aware` already established — one new
field, default preserving all prior behavior, no new abstraction layer.
`backfill_stage2_wdl.py::_LEGACY_SHARD_DTYPE_NO_WDL` is a deliberately local, frozen dtype copy
(not a general shard-versioning mechanism) — a one-off migration script's own concern, not a new
standing abstraction other code depends on.

**CLAUDE.md §4 mirror-symmetry/regression suite**: `EvalMirrorSymmetryPropertyTest` (Java) passes
(`mvn -pl engine-core -am test -Dtest=EvalMirrorSymmetryPropertyTest`, exit 0) — expected and
confirmed directly, not assumed, since this change never touches `network.py`, `canonical.py`,
`quantizer.py`, or any Java code. Full trainer suite: **198/198 passing**.

## 10. Learning log entry

```
## Experiment ID: P4IV-001-wdl-blend
Hypothesis:            A lambda-blend of the sigmoid-scaled eval target with an outcome-derived
                        wdl value (DR-E1's formula, wdl_lambda=0.5, on the FEN-join-backfilled
                        Stage 2 corpus) improves held-out correlation on the majority (cp-labeled)
                        population without regressing RMSE or calibration, per this task's stated
                        promotion rule.
Independent variable:  TrainingConfig.wdl_lambda only (1.0 -> 0.5). K held at production value
                        (2.773456), unchanged. Dataset content unchanged (wdl backfilled via pure
                        FEN join, no Stockfish re-execution, no new records, verified
                        order/count/eval_cp/eval_mate-preserving).
Controlled variables:  steps=20000, lr=0.01, cosine schedule, warmup=200, batch=256, seed=42,
                        architecture (unchanged), sentinel-filtered training corpus (35,933
                        records, 50.07% wdl-labeled, 11.51% mate-labeled), split seed=42.
Expected outcome:      cp-only correlation improves without RMSE/calibration regression if the
                        outcome-derived signal adds information the eval-only target lacks;
                        essentially unaffected or regressed if it doesn't (majority-population
                        rule applied prospectively, per this task's own reporting-order mandate).
Observed outcome:       cp-only correlation (majority, decisive) regressed slightly at every
                        reading (v1-clean -0.0037, v1 -0.0058, SS5.1). Mate-only correlation flat
                        (SS5.2). Pooled correlation flat-to-down, no leverage/rubric artifact
                        (SS5.3 -- this experiment's own mechanism never touches evaluation, so no
                        such artifact was possible). Calibration/RMSE flat within a few cp on both
                        splits (SS5.4). Exploratory diagnostics flat (SS5.5). Checkpoint selection
                        converged monotonically (selected step 16,999 nearly identical to final
                        step 19,999, SS4).
Metrics:                See SS5 above; full detail in outputs/phase4/P4IV/summary.json.
Decision:               NOT PROMOTABLE. Do not adopt wdl_lambda=0.5. Do not begin any follow-on
                        supervision-objective experiment automatically.
Reason rejected:        The majority-population condition (cp-only correlation) fails outright --
                        this task's own promotion rule requires it to improve; it did not.
Next action:            No further Phase 4 experiment begins automatically, per this task's
                        explicit stop condition. TrainingConfig.wdl_lambda, the backfilled Stage 2
                        shard, and SHARD_DTYPE's wdl field remain in the codebase as reusable
                        infrastructure for any future re-examination (a different lambda, a swept
                        lambda, or a real Stage 3 self-play source) -- not begun here.
                        measurement-model.md SS10 extended (not reopened) to a three-intervention
                        synthesis (loss reweighting, target reformulation, target-source blend --
                        all three tried once, none promotable).
Artifacts:              trainer/outputs/phase4/P4IV/, trainer/outputs/datasets/stage2-quiet-sf-wdl/
```
