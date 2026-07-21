# Experiment RQ-5 — Label budget-sensitivity floor

Phase 5 candidate #1 (`phase5-roadmap.md` Deliverable 5, item 1). A measurement, not a
trained-model intervention: no trainer code, loss, target definition, dataset, or checkpoint
was touched. Answers a prerequisite question for every remaining Phase 5 candidate: how much
of the ~0.59 v1-clean correlation ceiling (three closed interventions — P4II, P4III, P4IV —
all failed to move it) is bounded by the labeler's own search-variance, versus reachable
through further objective or architecture work.

## Hypothesis

**H0**: re-labeling the same Stage 2 positions at two Stockfish node budgets (25k vs 50k)
produces a small spread (`|eval_25k − eval_50k|` tightly clustered near 0), indicating
single-fixed-node labels are not a material noise source relative to the effects Phase 4/5
are trying to isolate.

**Independent variable**: Stockfish node budget only (25,000 vs 50,000), fixed position
sample (n=1,000), fixed engine (Stockfish 18, `engine_binary_sha256`
`65c1e4dade6102e4f8219be7d24181d25e7e1b6f039a20b3ae49488623ba61e5`), fixed thread count (1,
driver-hardcoded, unchanged from production Stage 2 labeling).

**Controlled variables**: position sample identity and order (both budget arms ran over the
identical 1,000-FEN sequence, in identical order, so both are subject to identical
persistent-engine carryover context — see Limitations); `timeout_seconds` (30.0, unchanged);
`StockfishLabelConfig`/`label_positions()` itself (unmodified, zero code changes to
`stockfish_label.py`).

**Pre-registered interpretation thresholds** (declared before running, per
`measurement-model.md` §9's "declare, then report" discipline — the primary metric is the
median `|eval_25k − eval_50k|` in cp over cp-labeled, mode-matched pairs):

| Median spread | Verdict | Implication |
|---|---|---|
| ≥ 25cp | **Material** | At or above the `near_zero` magnitude-bucket boundary (this roadmap's finest distinction band) — reprioritize toward label stability/higher node budget over further loss/target work |
| < 10cp | **Negligible** | Strengthens the case that Phase 4-style objective levers, not label re-collection, remain the right next investment |
| 10–25cp | **Threshold-contingent** | Disclosed as such, not resolved by picking whichever bar is convenient — P4I's own precedent |

**Success/failure criteria**: this experiment has no promotion/rejection outcome — per the
roadmap's own explicit framing, "the outcome is a number (the noise floor), not a decision."
Success is producing a real, correctly-scoped measurement; failure would be a data or
methodology defect (e.g. sample non-reproducibility, mode-mismatch mishandling) that makes the
number untrustworthy.

## Why this candidate is ranked first

Three independent model-side interventions (P4II — loss reweighting, P4III — target
reformulation, P4IV — target-source blend) have each failed to move the majority-population
(cp-only) correlation metric, via at least two distinct mechanisms (`measurement-model.md`
§10). That raises the question this experiment directly answers: is the ~0.59 ceiling set by
label noise — a data property no loss/target change can fix — or is it set by something
reachable through further objective or architecture work? Every remaining Phase 5 candidate's
expected value is conditioned on this answer. It is also the cheapest candidate in the entire
list (no training run, no model, existing labeling infrastructure reused unmodified) and has
never been run at any phase despite being flagged since the original roadmap document (research
doc §6 Exp. 5, §39 RQ-5).

## What this measures, precisely (and what it does not)

Single-threaded, fixed-node Stockfish search is deterministic — same FEN, same node budget,
same persistent-engine hash state → same eval. Re-running one budget twice would reproduce the
same numbers exactly. What this experiment measures is **budget-sensitivity**: how much a
position's eval moves between a 25,000-node and a 50,000-node search, a convergence/bias proxy
for how far from "settled" the production Stage 2 label (25k nodes) is — not run-to-run
statistical noise in the ordinary sense. This is a deliberate, disclosed reframing of the
original "label-noise floor" language (research doc §39 RQ-5, `phase5-roadmap.md`): the
underlying question (does the labeler's own search process bound achievable correlation?)
is unchanged, but "noise" implied a stochastic source this design does not actually isolate.

**Reconciling with the research doc's own §32.5 duplicate-FEN finding** (two occurrences of the
same FEN in the existing corpus carrying different labels, the original motivating evidence for
this experiment): that finding is very likely a *different* mechanism from budget-sensitivity.
`stockfish_label.py`'s `UciEngine` never sends `ucinewgame` between positions (matching
production Stage 2 labeling exactly — left unchanged here, per the "diagnostic, keep production
behavior unchanged" rule), so the persistent engine's hash-table state carries over from
whatever position preceded it in the input stream. Two identical FENs at different points in a
stream can get different evals purely from that carryover context, independent of node budget.
This experiment does not attempt to isolate the carryover channel — a same-budget replication
arm would be a second independent variable, breaking the one-variable-at-a-time discipline
(`measurement-model.md` §9) — it is recorded as a limitation and a candidate follow-up, not
measured here. Both budget arms in this experiment ran over the identical FEN sequence in the
identical order specifically so that both are exposed to matched carryover context, isolating
node budget as the one thing that differs between them.

## Implementation

New script: `trainer/scripts/phase5_rq5_label_noise_floor.py`. Reuses
`stockfish_label.py`'s `StockfishLabelConfig`/`label_positions()` and `mmap_shard.py`'s
`read_shard()` completely unmodified — zero changes to any existing trainer or scripts module.

- **Position sample**: reconstructs the same 20,139-FEN Stage 2 source population via the
  documented every-36th-line convention (`stockfish-label-e2-real.md`) applied to
  `data/quiet-labeled.epd` (725,000-line Zurichess-derived corpus), then takes an evenly-spaced
  1,000-position sub-sample (every 20th of the population) — deterministic and representative
  of the Stage 2 population. Not claimed to be byte-identical to the specific 20,000-record
  production shard, which is not present in this checkout (data artifacts are gitignored,
  regenerated per session per existing project convention).
- **Labeling**: `label_positions()` called twice over the identical 1,000-FEN file, once at
  `nodes=25000`, once at `nodes=50000`, both against the real Stockfish 18 binary
  (`/home/coeusyk/.local/bin/stockfish`).
- **Analysis**: matches records by position (both shards same fixed order, same length),
  classifies each pair by label mode (cp vs mate) before diffing — a cp eval and a mate eval
  are not on a comparable scale, so mode mismatches are counted and reported separately rather
  than folded into the primary cp-spread statistic. Magnitude-bucket boundaries reused verbatim
  from `phase4_p4i_k_sweep.py`'s `MAGNITUDE_BUCKETS` for cross-experiment consistency.

Test coverage: `trainer/tests/scripts/test_phase5_rq5_label_noise_floor.py`, 11 tests over the
pure-Python logic (FEN extraction, deterministic sub-sampling, magnitude bucketing, mode-mismatch
handling, threshold verdicts) — no real Stockfish required, matching `test_stockfish_label.py`'s
own two-tier strategy.

## Graphify findings

`graphify . --update --code-only` run before implementation (400 files unchanged/cached, 0
re-extracted) and after (new script + test added, no unexpected dependency changes — the new
module imports only `scripts.stockfish_label` and `trainer.dataset.mmap_shard`, both pre-existing
and both left unmodified). No architectural drift: this experiment adds exactly one new script
and one new test file, touches zero existing modules.

## Measured outcome (real Stockfish 18 run, 2026-07-21)

```
sample size: 1,000 positions (Stage 2 population: 20,139 FENs)
matched cp pairs: 967
mode mismatches (cp <-> mate flip between budgets): 7 (0.7%)
both-mate pairs: 26 (2.6%), mean |mate-ply spread| = 0.15

cp_spread (n=967):
  mean:    21.46 cp
  median:  13    cp
  stdev:   32.94 cp
  p90:     48    cp
  p99:     154   cp
  max:     534   cp

magnitude buckets (by |eval_25k|, non-mate records):
  near_zero  (0-25cp):    n=148, median spread = 11.0cp
  moderate   (25-200cp):  n=246, median spread = 11.0cp
  large      (200-800cp): n=541, median spread = 13.0cp
  extreme    (800+cp):    n=32,  median spread = 19.0cp
```

Full run: `outputs/phase5/RQ5-001/summary.json` (gitignored, regenerable — command below).
Runtime: 47.4s total (25k arm: 15.8s, 50k arm: 30.2s), matching the throughput scaling implied
by `stockfish-label-e2-real.md`'s own node-budget throughput table.

**Reproduction**: `cd trainer && uv run python -m scripts.phase5_rq5_label_noise_floor`
(requires `data/quiet-labeled.epd` present locally and a Stockfish binary at
`/home/coeusyk/.local/bin/stockfish` — both pre-existing, session-local artifacts per this
repo's established convention, not new prerequisites this experiment introduces).

## Interpretation

**Verdict: threshold-contingent (13cp median, between the 10cp negligible and 25cp material
bars).** This is a real, disclosed ambiguous result, not resolved by picking whichever bar is
convenient — the same disclosure discipline P4I's own K-sweep used for its 2σ/3σ threshold
result. Two readings that both stay within the pre-registered bounds:

- The median (13cp) sits closer to the negligible than the material bar, and is small relative
  to the near_zero/moderate bucket boundary (25cp) — most matched positions show budget-agreement
  within roughly half a magnitude-bucket width.
- The tail is not small: p90=48cp and p99=154cp mean a non-trivial minority of positions move
  by more than a full bucket width, and the max (534cp) is comparable in magnitude to entire
  Phase 4 target-representation constants (`MATE_FLOOR_CP=227.8`). A labeler-noise-bound
  argument cannot be dismissed purely from the median.

**On connecting this to the ~0.59 correlation ceiling**: the classical attenuation relation
(observed correlation ≈ true correlation × reliability, where reliability shrinks with
measurement-error variance) is the principled tool for turning a budget-sensitivity number into
a ceiling estimate, but applying it here would require treating the 25k-vs-50k spread as a
proxy for the labeler's true error variance — an approximation this experiment's own scope
(§ above) explicitly does not validate, since budget-sensitivity and the carryover-driven
variance implicated in the original §32.5 finding are distinct, unseparated channels here. This
report does not compute a specific ceiling estimate; the deliverable is the measured spread
distribution above, not a resolved verdict on how much of the plateau is label-bound.

**Bucket pattern**: median spread is roughly flat across near_zero/moderate/large (11–13cp) and
rises only in the extreme bucket (19cp, n=32) — no evidence that budget-sensitivity is
concentrated in any one magnitude regime among the buckets with adequate sample size.

## Limitations

- **Carryover-variance channel not isolated.** As discussed above, the original §32.5
  duplicate-FEN motivating evidence is plausibly explained by persistent-engine hash-state
  carryover, a mechanism this design does not measure (would require a same-budget replication
  arm — a second independent variable, deliberately not added here per the one-variable
  discipline). The number reported here is a budget-sensitivity floor, not a complete
  label-noise floor; a same-budget carryover-isolation follow-up would need `ucinewgame` sent
  before each position (or a fresh engine per position) to separate the two channels — noted as
  a candidate future experiment, not attempted here.
- **A 2x node-budget increase is a modest step.** Small spread at this doubling demonstrates
  stability across *this specific increment*, not label-noise-freedom in any absolute sense — a
  much larger budget (e.g. 500k+ nodes, or a different search algorithm) could still reveal
  larger movement that this design cannot detect.
- **Sample is representative, not identical to the production shard.** The 1,000-position
  sample is drawn from the reconstructed Stage 2 source population (same every-36th-line
  convention), not verified against the literal 20,000-record production shard (not present in
  this checkout).
- **Mode-mismatch and both-mate subsets are small-n** (7 and 26 respectively) — informative but
  not independently decisive, consistent with this roadmap's general small-n-subset discipline.

## Recommendation

Do not treat this result as resolving whether the ~0.59 ceiling is label-bound — the
threshold-contingent verdict, by design, does not support a clean directional recommendation
either way. Two concrete next steps this result motivates, neither implemented here (single-task
scope, per this task's stop condition):

1. If the carryover-vs-budget distinction matters for future work, a same-budget
   carryover-isolation follow-up (§ Limitations) would cleanly separate the two channels the
   original §32.5 evidence conflated.
2. Per `phase5-roadmap.md`'s own ranking, proceed to candidate #2 (WDL blend at an alternate
   operating point) or candidate #3 (architecture scoping) — this result does not change either
   candidate's expected value, since it neither confirms nor rules out a label-bound ceiling.

This experiment's own stop condition (per the governing task): exactly one Phase 5 task
completed; the second-ranked candidate is not started here.
