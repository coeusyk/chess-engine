# Phase 4 Retrospective

Phase 4 (K exploration through the WDL blend) is closed. This is a synthesis document, not a
new experiment: it summarizes what every Phase 4 experiment actually found, classifies the
interventions tried so far across the whole roadmap (not just Phase 4), and states precisely
what remains untested. It supersedes no experiment report — every number here traces back to
the experiment file it came from; if this document and a phase file ever disagree, the phase
file is authoritative.

Companion document: **[phase5-roadmap.md](phase5-roadmap.md)** — the reranked candidate list and
architectural-opportunities catalog that follow from the evidence summarized here.

## Deliverable 1 — Phase 4 experiment retrospective

Six entries: P4I, the P4I replication, P4II, P4III, the Phase 4C audit, and P4IV. Each states
what was actually learned, not just the headline result.

### P4I — K sweep (`phase4-p4i-k-sweep.md`)

- **Hypothesis:** retraining at a different texel-sigmoid `K` (the loss's steepness constant)
  produces a held-out correlation change large enough to justify moving off the production value.
- **Independent variable:** `K` only — three points, baseline 2.773456, low 2.149428 (-22.5%),
  high 3.397484 (+22.5%). Baseline reused from the existing P3A-001 checkpoint, not retrained.
- **Implementation summary:** new standalone scripts (`phase4_p4i_k_sweep.py`,
  `phase4_p4i_report.py`) with an adaptive decision procedure; zero trainer-module changes — `K`
  was already a first-class parameter of `texel_sigmoid()`.
- **Measured outcome:** low K improved v1-clean correlation by +0.0044 (2.32σ against the
  then-available noise estimate) and v1 by +0.0038 (2.00σ); high K regressed on both (-0.0065 /
  -0.0048). All four secondary metrics moved in the direction predicted for low K, and the
  cp-labeled majority did not regress.
- **Reason for rejection:** the effect cleared the pre-registered 2σ screening bar but not the
  pre-registered 3σ promotion bar. This is explicitly a **threshold-contingent** result, not a
  clean null — disclosed as such rather than resolved by picking whichever bar favored promotion.
- **Methodology improvements discovered:** established the project's "declare the bar before
  looking at the number" discipline in practice, not just on paper; established that a
  configuration-reused baseline must be verified by config inspection, not assumed; produced the
  first working confirmation that `K` retraining trains a genuinely different model rather than
  applying an affine transform to a fixed one (correcting an over-generalization from earlier
  algebraic-only reasoning about `K`).
- **Infrastructure retained:** the shared helper battery in `phase4_p4i_k_sweep.py`
  (`_is_sentinel`, `_load_model`, `_select_best_checkpoint`, `_predictions`, `_magnitude_buckets`,
  `_histogram`, `_eval_cell`) — reused, unmodified, by every subsequent Phase 4 script (P4I
  replication, P4II, P4III, P4IV). This is the single most load-bearing piece of code produced in
  Phase 4.
- **Lessons learned:** a promising screening-level result needs a second, independent read before
  it can be trusted at all — which is exactly what the next experiment supplied.

### P4I replication — K seed reproducibility (`phase4-p4i-replication.md`)

- **Hypothesis:** the low-K correlation gain reproduces at a second training seed (43, vs the
  original's 42).
- **Independent variable:** training seed only, holding K, data, and schedule fixed at the
  low-K configuration.
- **Implementation summary:** `phase4_p4i_replication.py`, reusing every P4I helper; no new
  trainer code.
- **Measured outcome:** v1-clean correlation reproduced tightly (+0.0044 → +0.0040, seed-to-seed
  spread 0.0004 — eleven times smaller than the effect it's meant to corroborate). Every other
  metric read the opposite way: v1 correlation's seed spread (0.0035) is comparable to its own
  effect size (0.0038), and every secondary metric's seed spread (mate compression 0.0209 vs
  effect 0.0102; cp compression 0.0796 vs 0.0304; near-zero MAE 11.73 vs 4.99; extreme MAE 76.17
  vs 34.57) exceeds the effect outright. These are unresolved by noise, not "reversed."
- **Reason for rejection:** the project's AND-gated promotion rule needs magnitude *and*
  secondary-metric corroboration. At n=2 seeds, only one metric (v1-clean correlation) has small
  enough noise to corroborate anything; every other channel is silent by construction. Terminated
  as **uncorroborated, not disproven** — production K unchanged at 2.773456.
- **Methodology improvements discovered:** the single most consequential methodology result in
  the whole Phase 4 arc. Established that v1-clean correlation is the *only* metric whose
  seed-to-seed noise is small relative to typical Phase 4 effect sizes at this sample size — every
  other metric this roadmap reports (v1 correlation, compression ratios, bucket MAEs) has noise at
  or above the effect size it would need to confirm or refute. This is the direct origin of
  `measurement-model.md`'s entire metric hierarchy (§1/§1a): metrics are classified by whether
  they can actually resolve an effect of this roadmap's typical size, not by how intuitively
  meaningful they sound.
- **Infrastructure retained:** the seed-variance-at-actual-configuration estimate itself (v1
  spread 0.0035, v1-clean spread 0.0004) — the "best available" noise floor at this exact
  schedule, superseding a stale cross-configuration estimate from Phase 1.
- **Lessons learned:** never reuse a noise-floor estimate from a different configuration; a metric
  that looks like it "reversed" between two runs is not evidence of anything unless its
  seed-to-seed spread is already known to be smaller than the effect under discussion.

### P4II — mate-aware loss weighting (`phase4-p4ii-mate-weight.md`)

- **Hypothesis (RQ-2):** up-weighting mate-labeled records in the training loss
  (`mate_weight=3.0`, 39% of full class-frequency equalization) improves mate-subset behavior and
  pooled v1-clean correlation without regressing the cp-labeled majority.
- **Independent variable:** `TrainingConfig.mate_weight` (1.0 → 3.0) only.
- **Implementation summary:** new `mate_weight` field (safe default 1.0), a weighted-mean loss
  line keyed on an `is_mate` boolean mask, three new tests (identity at 1.0, default-matches-
  explicit, non-unit value actually changes the trained model).
- **Measured outcome:** pooled v1-clean correlation rose +0.038 to +0.050 — the largest apparent
  gain anywhere in the roadmap. A mate-subset-vs-cp-subset decomposition (this experiment's own
  methodological contribution) showed the cp-subset correlation flat-to-regressed at every
  checkpoint (0.5941 → 0.5591 at the selected checkpoint), while the mate subset moved only
  modestly (+0.02–0.03) — the pooled number was almost entirely a leverage artifact of a small,
  high-variance minority subset. Training also produced the roadmap's first non-monotonic
  trajectory: pooled correlation spiked at step 3,999 (0.5739) then receded to a 0.564–0.565
  plateau (final step 19,999 = 0.5652), and peak-correlation checkpoint selection picked the
  transient spike.
- **Reason for rejection:** two independent grounds. (1) At the selected (spike) checkpoint, RMSE
  and calibration regress outright. (2) Even at the final, converged checkpoint — where the
  literal promotion rule's wording could be read as satisfied — the cp-labeled majority never
  actually improved; the pooled gain was composition, not learning.
- **Methodology improvements discovered:** origin of two permanent protocols. The
  majority-population rule (`measurement-model.md` §6): no pooled metric may be interpreted before
  the majority (cp-labeled) subset has been examined on its own. The checkpoint-selection protocol
  (§8): report both the selected and the final checkpoint whenever a subset-targeted loss is in
  play, because peak-pooled-correlation selection is demonstrably vulnerable to a non-monotonic
  spike under this kind of loss.
- **Infrastructure retained:** the `mate_weight` field itself (harmless at its 1.0 default); the
  weighted-mean loss-line pattern, reused conceptually by P4IV's `wdl_lambda` blend; the
  `is_mate` boolean-mask convention, reused throughout every later script's subset decomposition.
- **Lessons learned:** a pooled correlation can be moved substantially by a small, high-leverage
  subset without the majority population improving at all — the majority subset must always be
  checked first, and a training curve should never be trusted to be monotonic just because prior
  experiments' curves were.

### P4III — mate-target representation (`phase4-p4iii-mate-target.md`)

- **Hypothesis (RQ-3):** replacing the flat `MATE_EQUIVALENT_CP=3000` mate target with a
  distance-aware target (linear interpolation from `MATE_BASE_CP=1000` down to
  `MATE_FLOOR_CP=227.8` over 0–64 observed moves-to-mate) restores gradient signal lost to the
  flat target's saturation and improves mate-subset and pooled behavior without regressing the cp
  majority.
- **Independent variable:** `target_cp()`'s mate branch, gated by a new `distance_aware: bool`
  parameter (default `False`) and `TrainingConfig.mate_target_distance_aware` (default `False`).
- **Implementation summary:** new constants with an explicit saturation-formula derivation. A
  design flaw was caught and fixed mid-implementation: an early draft flipped `target_cp()`'s own
  default to `True`, which would have silently changed behavior for every unrelated future caller
  of that function (it's consumed by both training *and* evaluation) — corrected back to `False`
  before commit.
- **Measured outcome:** a naive cross-rubric comparison (old-rubric baseline vs new-rubric
  candidate) showed a spectacular +0.053 pooled correlation gain and a 582cp RMSE improvement —
  and this entire gain reproduces on the *identical, untrained* baseline model graded under both
  rubrics, with zero training involved (0.5933 → 0.6467 from redefinition alone). Under the
  correct same-rubric comparison: cp-only correlation flat (0.5941 → 0.5931, -0.0010), mate-only
  correlation flat (0.6453 → 0.6484, +0.0031, plausibly noise). No learned effect in either subset.
- **Reason for rejection:** the screening-level number fails on the only valid (same-rubric)
  reading, and the majority-population condition is independently flat-to-negative.
- **Methodology improvements discovered:** origin of the rubric-contamination case study
  (`measurement-model.md` §5) — a sharper failure mode than P4II's leverage artifact, because it
  isn't about record composition at all, it's about grading two models on two different
  definitions of "correct." Established the permanent protocol that any intervention changing a
  *target definition* (not just a loss weight) requires re-evaluating the baseline under the new
  definition before any comparison is valid. Also a near-miss worth remembering on its own: an
  unpromoted candidate almost shipped a silent default-behavior change for every future caller of
  a shared function.
- **Infrastructure retained:** `mate_target_distance_aware` and `target_cp()`'s `distance_aware`
  parameter (harmless at their `False` defaults); the `MATE_BASE_CP`/`MATE_FLOOR_CP` constants and
  their saturation-derivation reasoning, directly reused by the Phase 4C audit's chain-rule
  argument against Huber/log-cosh.
- **Lessons learned:** the largest apparent gain anywhere in this roadmap was 100% artifact — any
  intervention that redefines what "correct" means must clear the bar of "does the baseline model,
  completely unchanged, look better just from being graded differently" before any other number
  from it is trusted.

### Phase 4C — reranking audit (`phase4c-reranking-wdl-audit.md`)

- **Hypothesis / purpose:** not a trained-model experiment. Checks whether P4II's and P4III's
  accumulated evidence should change the expected information gain of the roadmap's remaining
  candidates (Huber/log-cosh, WDL blend) before continuing on the original ordering.
- **Independent variable:** none — investigation only, zero production code changed.
- **Implementation summary:** a chain-rule derivation (Huber/log-cosh atop the incumbent
  sigmoid objective inherits `σ'(p,K)` via `dL/dp = Huber'(σ(p,K)−σ(t,K))·σ'(p,K)`, plus a
  second argument that Huber ≡ MSE within its δ-radius, so it changes nothing for the majority
  population) combined with direct empirical inspection of the real Stockfish binary, the real
  source EPD file, and the real production shard.
- **Measured outcome:** Huber/log-cosh (as originally scoped) was mechanistically down-ranked from
  "Medium-high" to "Low-medium" expected gain — it's predicted to hit the same saturation wall as
  P4II/P4III. WDL blend was up-ranked: the source EPD (`data/quiet-labeled.epd`, 725,000 lines) is
  100% populated with the `c9` outcome field, the production shard's stored FEN field is already
  clean, and a FEN-join backfill test matched 20,000/20,000 (100%) records with only 248 (0.03%)
  conflicting-outcome duplicates — narrowing the engineering-cost estimate from "Medium-high, four
  prerequisites" to "Medium, two bounded tasks."
- **Reason for acceptance:** not applicable — this is an investigation, not a candidate to
  accept/reject. Its own conclusion (rerank WDL above Huber) was acted on in the very next
  experiment, P4IV.
- **Methodology improvements discovered:** the first time in this roadmap a mechanism derived from
  one pair of experiments (P4II/P4III's saturation wall) was used to make an *a-priori* prediction
  about a different, not-yet-run candidate — rather than only being used to interpret results after
  the fact.
- **Infrastructure retained:** no code; but the empirical findings themselves (source data 100%
  populated, backfill feasible with zero Stockfish re-execution, the `_read_fens()` parsing gap
  confirmed harmless against a real binary) are durable facts that any future WDL-adjacent work can
  build on without re-verifying.
- **Lessons learned:** before implementing "whatever's next on the roadmap," check whether
  accumulated evidence has actually changed its expected value — and verify feared blockers against
  the real artifact (the real binary, the real file) rather than continuing to cite them as
  untested indefinitely.

### P4IV — WDL-blended training target (`phase4-p4iv-wdl-blend.md`)

- **Hypothesis (RQ-4, Lever C):** blending the training target with a game-outcome-derived WDL
  signal (`wdl_lambda=0.5`, an explicitly disclosed midpoint) improves cp-only correlation without
  regressing anything else.
- **Independent variable:** `TrainingConfig.wdl_lambda` (1.0, pure eval, → 0.5, even blend) only.
- **Implementation summary:** `SHARD_DTYPE` extended with `wdl`/`has_wdl` fields (a breaking
  format change, affecting exactly one file on disk, `stage2-quiet-sf/shard-0.bin` — Stage 1 is
  CSV-based and never touches this format); `backfill_stage2_wdl.py`, a FEN-join migration script
  using a frozen legacy-dtype copy to read the pre-change shard; the blend logic added to
  `train()`'s loss line, keyed on the `has_wdl` mask; a dedicated parametrized test for the
  mover-relative-vs-White-relative sign conversion (the one correctness-critical step); empirical
  verification that the migration preserves the exact train/held-out split (byte-identical FEN
  sequence and eval fields, same record order).
- **Measured outcome:** the real backfill matched 19,983/20,000 (99.9%) records with WDL, 17
  unmatched, 247 conflicting duplicates dropped — WDL now covers roughly 50% of the corpus, cutting
  across both cp- and mate-labeled populations (unlike P4II/P4III's mate-scoped changes). Training
  converged monotonically (selected step 16,999 ≈ final step 19,999, Δcorr = 0.0002 — no
  P4II-style overshoot). Per the mandated reporting order: cp-only correlation **regressed**
  (v1-clean 0.5941 → 0.5904/0.5903, -0.0037; v1 0.3721 → 0.3663/0.3661, -0.0058); mate-only flat
  (-0.0009); pooled flat-to-down (0.5933 → 0.5926/0.5924); calibration flat within a few cp;
  exploratory diagnostics flat.
- **Reason for rejection:** the majority-population condition (cp-only correlation) fails outright
  — sufficient by itself to reject under the permanent promotion rule.
- **Methodology improvements discovered:** the first Phase 4 experiment with *no* artifact to
  disentangle — because the intervention lives entirely inside `train()`'s loss line and never
  touches any evaluation-path function, the same-rubric comparison is direct, with none of P4II's
  leverage or P4III's rubric-contamination confounds. This clean result let `measurement-model.md`
  §10 draw an explicit distinction between "the same demonstrated mechanism" (P4II/P4III's
  `σ'(p,K)` saturation wall) and "a genuinely different, mechanistically unestablished null"
  (P4IV — its majority-population records were never in the saturated zone, so the wall doesn't
  explain this result) — a discipline against over-generalizing a mechanism to a result it doesn't
  actually explain.
- **Infrastructure retained:** the `wdl`/`has_wdl` shard fields; `TrainingConfig.wdl_lambda`; the
  backfilled `stage2-quiet-sf-wdl/` shard itself (a data artifact, reusable for a different λ
  without re-running the migration); the frozen-legacy-dtype migration pattern as a reusable
  template for any future `SHARD_DTYPE` extension.
- **Lessons learned:** split-preservation and record-integrity claims from a shard migration should
  be verified empirically (byte-identical field comparison), not just asserted from algebraic
  reasoning about a fixed-seed shuffle; a sign-convention bug in a mover-relative-vs-White-relative
  conversion would have been silently indistinguishable from a genuine null, and needed its own
  dedicated test rather than incidental coverage.

## Deliverable 2 — Intervention taxonomy

Every experiment across the *whole* roadmap (not just Phase 4) is classified below, because
several taxonomy classes (Optimization, Data quality) have no Phase 4 members at all — their only
evidence comes from Phase 1–3.

**Boundary rule between Calibration and Gradient shaping**, since both classes touch the loss and
could otherwise be confused: **Calibration** covers where the target *transform* saturates or is
scaled (`K`, the sigmoid steepness constant — a property of the transform applied to every
record identically). **Gradient shaping** covers per-record or per-residual weighting of the
*already-transformed* loss (`mate_weight`, Huber/log-cosh) — interventions that change how much
gradient a given record or residual magnitude contributes, not how the transform itself is shaped.

| Class | Experiments performed | Evidence accumulated | Current confidence | Remaining uncertainty |
|---|---|---|---|---|
| **Optimization** | Phase 1 LR-schedule/warmup/step-count grid search (main document §27) | P1-G04 promoted from that grid; remains the reference model, reused unmodified through every Phase 4 experiment | High confidence that P1-G04 is a reasonable optimization point for the current architecture/objective | No optimizer family, weight-decay, or batch-size sweep has been run; the grid searched only LR schedule and warmup |
| **Data quality** | Stage 1 volume scaling (Experiments 2A/2B, 20k→100k records, fixed and proportional compute); sentinel-value filtering (Phase 3A) | Volume scaling falsified under both compute regimes; sentinel filtering confirmed as a benchmark-composition artifact (fixes the *held-out set*, not the model) but adopted as permanent ingestion hygiene anyway | High confidence Stage 1 volume alone isn't the lever; high confidence sentinel filtering is correct hygiene regardless of model effect | Stage 2's own label-noise floor (RQ-5/Experiment 5, node-budget search variance) has never been measured at any phase |
| **Calibration** | P4I (K sweep, 3 points) + P4I replication (1 seed at the low endpoint) | Low K: uncorroborated +0.004 v1-clean gain, reproduced at one seed, unresolved by every other metric at n=2 | Low; explicitly "uncorroborated, not disproven" | Only the low endpoint was replicated (not baseline or high); no point outside ±22.5% of production K tested; no finer grid between 2.149 and 2.773 |
| **Gradient shaping** | P4II (`mate_weight=3.0`, one point). Huber/log-cosh **not run** — mechanistically predicted only (Phase 4C audit) | P4II: not promotable, pooled gain was a pure leverage artifact, cp majority flat-to-regressed | Low-medium (P4II); no empirical confidence on Huber, prediction only | No other `mate_weight` value tested; Huber/log-cosh has zero empirical data points despite a mechanistic prediction |
| **Target representation** | P4III (distance-aware mate target, one parameterization: `MATE_BASE_CP=1000`, `MATE_FLOOR_CP=227.8`, linear, 0–64 ply) | Not promotable — the entire apparent gain was a rubric-contamination artifact; no learned effect under the correct same-rubric comparison | Low for this specific parameterization; no evidence on the general idea class | No other anchor points, non-linear interpolation, or interaction with a different K has been tested |
| **Target source** | P4IV (WDL blend, `wdl_lambda=0.5`, one point, Stage 2 backfilled corpus only) | Not promotable — clean, single-rubric null; cp-only correlation regressed slightly | Low-medium; this is the only class with a *clean* (non-artifactual) null result | No other λ value, no restriction to high-confidence/decisive outcomes, and no Stage 3 self-play-derived WDL source (doesn't exist yet) tested |
| **Architecture** | None | None | No confidence either way — zero evidence | Entire class untested: no auxiliary head, no multi-task objective, no alternative output representation, no feature-representation change |

## Deliverable 3 — Research saturation

Per-class saturation status, stated precisely — no class is described more broadly than the
evidence actually supports.

- **Optimization: Partially explored.** Phase 1's LR-schedule/warmup/step-count grid search
  promoted P1-G04; no alternative optimizer, weight-decay, or batch-size configuration has been
  tested at any phase.
- **Data quality: Partially explored.** Stage 1 volume scaling from 20,000 to 100,000 records has
  been tested twice (fixed and proportional compute) and found not to improve correlation;
  sentinel-value filtering (8 of 4,000 v1 records) has been tested and confirmed as a
  benchmark-composition artifact, not a model-quality fix. No other data-quality lever — broader
  outlier detection, per-source normalization, or Stage 2's own label-noise floor
  (RQ-5/Experiment 5) — has been tested.
- **Calibration: Partially explored.** K has been swept at exactly 3 points (2.149428 / 2.773456 /
  3.397484, ±22.5% from production) once, and replicated at a second seed at the low endpoint
  only. The low-K gain (~+0.004 v1-clean) reproduced at that one endpoint but is uncorroborated by
  any secondary metric at n=2. No K value outside ±22.5% has been tested, and only the low
  endpoint — not baseline or high — was replicated.
- **Gradient shaping: Partially explored.** Mate-aware loss weighting has been tested once, at
  `mate_weight=3.0` (≈39% of full class-frequency equalization), and found not promotable via a
  pooled-correlation leverage artifact. No other `mate_weight` value has been tested. Huber/log-cosh
  loss shape (atop the incumbent sigmoid objective, as originally scoped) has **not been run** —
  its expected value was mechanistically down-ranked by the Phase 4C audit, but this is a
  prediction, not an empirical result.
- **Target representation: Partially explored.** Mate-target distance-aware reformulation
  (`MATE_BASE_CP=1000`, `MATE_FLOOR_CP=227.8`, linear interpolation over 0–64 observed
  moves-to-mate) has been evaluated once and found not promotable — no learned effect on either the
  cp-labeled majority or the mate-labeled target subset, under the correct same-rubric comparison.
  No other target-representation parameterization (different anchor points, non-linear
  interpolation, or interaction with a different K) has been tested.
- **Target source: Partially explored.** WDL blend using λ=0.5 on the Stage 2 FEN-join-backfilled
  corpus (19,983/20,000 records, ≈50% coverage) has been evaluated once and found not promotable —
  cp-only correlation regressed slightly, every other metric flat. No other λ value, no restriction
  to high-confidence or decisive-only outcomes, and no Stage 3 self-play-derived WDL source (which
  does not exist yet) has been tested.
- **Architecture: Open.** No architecture-level intervention — auxiliary head, multi-task
  objective, alternative output representation, or feature-representation change — has been
  implemented or tested in this project at any phase.

## Deliverable 4 — Infrastructure summary

Permanent infrastructure produced during Phase 4, and whether it stays useful independent of any
single experiment's outcome.

| Asset | What it is | Useful independent of outcomes? |
|---|---|---|
| **Measurement Model** (`measurement-model.md`, all 10 sections) | The permanent metric-classification, majority-population, promotion, and checkpoint-selection framework | Yes — governs interpretation of *any* future experiment, not tied to which Phase 4 candidate was promoted (none were) |
| **Metric hierarchy** (§1/§1a) | Per-metric classification: screening / primary majority-population / mechanism-validation / regression-guard / exploratory | Yes — reusable verbatim for Phase 5 candidates |
| **Majority-population rule** (§6) | Mandatory reporting order: majority subset → modified subset → pooled, in that order; no pooled number trusted before the majority subset is examined | Yes — directly prevented over-crediting P4II and P4III; applies to any future subset-differentiated intervention |
| **Promotion protocol** (§7) | 4-condition AND-gate (v1-clean pooled gain, no RMSE regression, no calibration regression, majority-population improvement or no degradation) | Yes — already used consistently across P4II/III/IV; the reusable bar for Phase 5 |
| **Checkpoint-selection protocol** (§8) | Report both selected and final checkpoint whenever subset weighting/treatment changes | Yes — cheap insurance against another P4II-style transient-spike selection; costs nothing to apply even where it doesn't change anything (P4III, P4IV) |
| **Experimental design protocol** (§9) | Declare Primary/Secondary/Exploratory metrics before reporting numbers | Yes — process discipline, zero code dependency |
| **WDL shard format** (`mmap_shard.py`'s `wdl`/`has_wdl` fields) | Extended `SHARD_DTYPE` carrying an optional WDL value and validity flag | Yes — reusable regardless of λ=0.5's null result; any future WDL-adjacent experiment (different λ, restricted-outcome blend, eventual Stage 3 self-play) reads/writes this format without redoing the work |
| **Backfill tooling** (`backfill_stage2_wdl.py`) | One-off FEN-join migration script that produced `stage2-quiet-sf-wdl/` | The *script* is a one-off (already run); the **migration pattern** it establishes (frozen local copy of the legacy dtype, so old-format shards remain readable during a breaking format change) is a reusable template for any future `SHARD_DTYPE` extension |
| **TrainingConfig extensions** (`mate_weight`, `mate_target_distance_aware`, `wdl_lambda`) | Three new fields, each with a safe (no-op) default | Yes — all three remain in the codebase, available for re-examination at different parameter values without re-implementing the mechanism |
| **`MATE_BASE_CP`/`MATE_FLOOR_CP`/`MATE_MAX_OBSERVED_N`** and their saturation-formula derivation | Constants plus the reasoning behind them | Yes — already reused once, by the Phase 4C audit's chain-rule argument against Huber; reusable for any future mate-target work |
| **Backfilled corpus** (`stage2-quiet-sf-wdl/`) | Data artifact, not code | Yes — reusable for a different λ or restricted-outcome test without re-running the FEN-join migration |
| **P4I helper battery** (`phase4_p4i_k_sweep.py`'s `_is_sentinel`, `_load_model`, `_select_best_checkpoint`, `_predictions`, `_magnitude_buckets`, `_histogram`, `_eval_cell`) | Shared evaluation/reporting helpers | Yes — reused unmodified by P4I replication, P4II, P4III, and P4IV; the most load-bearing single artifact of Phase 4 |

## Cross-experiment synthesis (pointer, not restated)

`measurement-model.md` §10.1/§10.2 already records the permanent synthesis across P4II, P4III, and
P4IV — that P4II and P4III share a demonstrated `σ'(p,K)` saturation-wall mechanism, while P4IV is
a mechanistically different, unestablished-cause null. This retrospective does not restate that
analysis; see the Measurement Model directly.
