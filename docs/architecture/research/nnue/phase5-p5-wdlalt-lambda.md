# Experiment P5-WDLALT — WDL blend, alternate operating point (λ=0.8)

Phase 5 candidate #2 (`phase5-roadmap.md` Deliverable 5, item 2). Extends P4IV
(`phase4-p4iv-wdl-blend.md`) to a different `wdl_lambda` operating point rather than
repeating its λ=0.5 midpoint — the now-fully-built WDL pipeline (shard format, backfilled
corpus, blend logic, sign-convention tests) reused completely unmodified.

## λ value: a disclosed resolution of a roadmap self-contradiction

`phase5-roadmap.md`'s own text for this candidate says "a much smaller value such as
0.1-0.2" while describing the test as "moderate rather than extreme ... analogous to how
P4II tested a moderate rather than an extreme `mate_weight`." Under `TrainingConfig.
wdl_lambda`'s actual convention (DR-E1: 1.0 = pure eval, 0.0 = pure outcome), 0.1-0.2 is
the *extreme* outcome-weighted end, not a moderate one — the two halves of that sentence
contradict each other under the code's own semantics.

This was surfaced to the user before training, per this project's "disclose, don't resolve
unilaterally" convention (used throughout Phase 4 for exactly this kind of judgment call) —
not resolved silently. **Confirmed: test the light-touch end, λ=0.8** (80% eval-derived
target, 20% game-outcome), not the literal "0.1-0.2" text. This is also the more
information-dense choice independent of the disclosure: P4IV's own two undistinguished
candidate causes (`measurement-model.md` §10.2 — (a) outcome signal carries no ranking
information regardless of weight, (b) λ=0.5 diluted a more precise eval signal) both
predict an outcome-heavy λ (0.1-0.2) simply regresses further, a near-certain,
low-information result. λ=0.8's result was genuinely uncertain beforehand.

## Hypothesis

**H0**: a light outcome touch (λ=0.8) still fails to produce a majority-population
(cp-only) correlation improvement over baseline — extending P4IV's null across a wider
span of the target-source-blend family rather than being specific to the λ=0.5 midpoint.

**Independent variable**: `TrainingConfig.wdl_lambda` only (0.5 → 0.8).

**Controlled variables**: `K=2.773456` (`K_BASE`, unchanged), architecture
(`hidden_width=256`/`qa=127`/`qb=64`/`output_scale=400`), P1-G04's frozen schedule
(`steps=20000`, `lr=0.01`, cosine, `warmup=200`, `seed=42`, `batch_size=256`), split seed
42, sentinel filtering, the identical `stage2-quiet-sf-wdl` backfilled corpus P4IV used,
baseline reused unmodified from `P3A-001` (same-rubric comparison — `wdl_lambda` never
touches any evaluation path, so this candidate inherits P4IV's own "no
rubric-contamination risk" property, `measurement-model.md` §5, rather than re-deriving
it).

**Success/failure criteria**: identical to P4IV — `measurement-model.md` §7's 4-condition
AND-gate (meaningful v1-clean pooled gain; no RMSE regression; no calibration regression;
cp-only improvement or no degradation), majority-population reporting order (cp-subset →
mate-subset → pooled), both selected and final checkpoints reported per §8.

**Expected information gain**: Medium (roadmap's own rating) — doesn't establish the whole
target-source-blend family is dead (would require exhausting the λ continuum), but
materially updates confidence about whether λ=0.5 specifically was the wrong dose or the
mechanism is dose-independent.

## Implementation

New script: `trainer/scripts/phase5_wdl_lambda_alt.py`, a near-exact structural mirror of
`phase4_p4iv_wdl_blend.py` (imports the same shared constants from `phase4_p4i_k_sweep.py`:
`HIDDEN_WIDTH`/`QA`/`QB`/`OUTPUT_SCALE`/`K_BASE`/`SPLIT_SEED`/`STEPS`/`LEARNING_RATE`/
`LR_SCHEDULE`/`WARMUP_STEPS`/`TRAINING_SEED`/`BATCH_SIZE`/`TRAIN_SAMPLE_SELECTION_SEED`/
`TRAIN_SAMPLE_SIZE`/`P3A_001_BASELINE_CHECKPOINT`/`_is_sentinel`/`_load_model`/
`_select_best_checkpoint`/`_eval_cell`, all unmodified). Zero changes to any existing
trainer or scripts module — the only difference from P4IV's script is the `WDL_LAMBDA`
constant (0.5 → 0.8), the `TAG`/`OUTPUT_ROOT` identifiers, and the module docstring.

No new pure-Python logic was introduced (a constant-value change to an already-tested
mechanism), so no new unit tests were added — the existing `wdl_lambda` blend-formula
tests (P4IV's own coverage, `trainer/model/train.py`'s weighted-mean loss line) already
cover the mechanism this script exercises at a different parameter value; the full
existing trainer test suite was re-run to confirm nothing regressed (see Verification).

## Graphify findings

`graphify . --update --code-only` run before implementation and after. The new script adds
exactly one new node importing only pre-existing, unmodified symbols from
`scripts.phase4_p4i_k_sweep`, `scripts.train_candidate_net`, `trainer.model.train`, and
`trainer.validation.validator` — the identical dependency set P4IV's own script has,
confirming no new coupling was introduced. No architectural drift.

## Measured outcome (real training run, 2026-07-21)

```
training set: 35,933 records (wdl-labeled: 17,990, 50.07%; mate-labeled: 4,137, 11.51%)
v1: 4,000  v1-clean: 3,992
wall_clock: 171.9s
selected_step=16,999/19,999  final_step=19,999  (near-identical -- monotonic convergence,
  no P4II-style overshoot, matching P4IV's own convergence pattern)
```

Per the mandated reporting order (majority subset → modified subset → pooled):

| Arm | Benchmark | n | cp-subset corr | mate-subset corr | pooled corr | RMSE | Bias |
|---|---|---|---|---|---|---|---|
| baseline | v1-clean | 3992 | 0.5941 | 0.6401 | 0.5933 | 1030.1 | -191.1 |
| candidate (selected) | v1-clean | 3992 | 0.5930 | 0.6394 | 0.5911 | 1031.2 | -191.0 |
| candidate (final) | v1-clean | 3992 | 0.5930 | 0.6396 | 0.5909 | 1030.8 | -190.0 |

- **cp-only correlation (majority-population, decisive metric)**: 0.5941 → 0.5930/0.5930,
  **Δ = -0.0011** — a small regression, present at both checkpoints.
- **mate-only correlation (mechanism check)**: 0.6401 → 0.6394/0.6396, Δ = -0.0005/-0.0007
  — flat within the noise this roadmap has previously measured for this metric.
- **Pooled v1-clean correlation (screening)**: 0.5933 → 0.5911/0.5909, Δ = -0.0022/-0.0024
  — also regressed, so the screening condition fails outright (no need to invoke the
  majority-population rule to catch an inflated pooled number here, unlike P4II/P4III).
- **Calibration (regression guard)**: cp-labeled RMSE 398.2 → 398.7/398.6 (Δ < 0.5cp),
  cp-labeled signed bias 6.4 → 6.7/7.7cp (both near-zero); mate-labeled bias -1663.6 →
  -1665.6/-1664.3cp (Δ < 2cp), mate-labeled RMSE 2791.5 → 2794.4/2793.2cp (Δ < 3cp) — flat,
  no material regression on either subset.

Full run: `outputs/phase5/P5-WDLALT/summary.json` (gitignored, regenerable).
**Reproduction**: `cd trainer && uv run python -m scripts.phase5_wdl_lambda_alt` (requires
`outputs/datasets/stage1-lichess`, `outputs/datasets/stage2-quiet-sf-wdl`, and
`outputs/phase3/P3A-001/checkpoints/step-016999.pt` present locally — the same
session-local, gitignored, regenerable-by-script artifacts every prior Phase 4 script
required).

## Interpretation

**Verdict: not promotable.** Promotion requires all 4 conditions of `measurement-model.md`
§7; this candidate fails condition 1 (screening: pooled correlation regressed, not
improved) and condition 4 (majority-population: cp-only correlation regressed, however
slightly) — sufficient by itself to reject, independent of conditions 2/3 (which do pass:
no RMSE or calibration regression).

**The dose-response finding is the actual information gain here.** λ=0.8's cp-only
regression (-0.0011) is roughly **3.4x smaller** than λ=0.5's (-0.0037, P4IV). This is a
second data point on the same target-source-blend family, and its magnitude scaling
roughly with outcome weight (more outcome weight → larger regression, less outcome weight
→ smaller regression, not a sharp threshold in between) is more consistent with P4IV's
candidate cause (a) — the outcome-derived `wdl` signal carries no positive ranking
information for the majority population, so any nonzero weight on it nudges cp-only
correlation down roughly in proportion to that weight — than cause (b), which would
predict a dilution effect with some more threshold-like onset rather than a roughly
linear scaling across two tested points. **This is suggestive, not decisive**: two points
do not establish a functional form, and no third point (e.g. λ=0.9 or λ=0.95, approaching
the P4IV/near-baseline boundary) was tested this session.

**No noise-floor estimate exists for `wdl_lambda` specifically** (unlike `K`, which P4I's
replication measured at n=2 seeds). The best available reference is the general v1-clean
same-K seed spread (0.0004, `phase4-p4i-replication.md` §6.1, a different configuration) —
under that borrowed estimate, -0.0011 is about 2.75x the noise floor, plausibly real but
not independently corroborated at a second seed, the same "uncorroborated, not disproven"
caveat P4I's own K-sweep result carried at n=1.

## Limitations

- **Single seed, no replication.** Like P4IV itself (and unlike P4I, which got a dedicated
  replication experiment), this result is n=1. The noise-floor comparison above borrows a
  different configuration's estimate, per this roadmap's established (if imperfect)
  convention when no better estimate exists.
- **Two points do not establish a dose-response curve.** The λ=0.5-vs-0.8 comparison is
  suggestive of a roughly-linear relationship between outcome weight and cp-only
  regression, but this is an observation from two data points, not a fitted or validated
  functional form.
- **Restricted-outcome design not tested.** The roadmap's other stated design for this
  candidate (restricting WDL blending to high-confidence/decisive outcomes only, at the
  original λ=0.5) was not attempted here — a genuinely different intervention (changes
  which records carry a WDL value, not the blend weight), out of scope for this
  single-experiment task.

## Recommendation

Do not promote — the majority-population condition fails, however slightly. The
dose-response pattern (smaller regression at higher λ) does not, on its own, motivate
pushing further toward λ=1.0 (that direction converges toward the untouched baseline by
construction, not toward a promotable improvement). Two concrete next steps this result
motivates, neither implemented here (single-task scope, per this task's stop condition):

1. If the target-source-blend family is to be closed definitively (not just at λ=0.5 and
   λ=0.8), a third point or the restricted-outcome design (roadmap's other stated option)
   would be needed — this result narrows but does not close the family.
2. Per `phase5-roadmap.md`'s own ranking, proceed to candidate #3 (architecture scoping —
   auxiliary head / WDL as an auxiliary loss) — this result is consistent with, and adds
   modest further weight to, `measurement-model.md` §10's synthesis that architecture-level
   change is the more promising remaining lever, without being decisive on its own.

This experiment's own stop condition (per the governing task): exactly one Phase 5
experiment completed; the next-ranked candidate is not started here.
