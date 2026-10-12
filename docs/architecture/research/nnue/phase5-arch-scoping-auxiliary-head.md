# Phase 5 candidate #3 — Architecture-level scoping: auxiliary WDL head

Phase 5 roadmap candidate #3 (`phase5-roadmap.md` Deliverable 5, item 3; design catalog in
Deliverable 6). **This is a scoping investigation, not a trained-model experiment** — the
roadmap defines this candidate that way in its own title ("design investigation, not
implementation") and in its recommendation ("a scoping/design investigation first, not full
implementation ... understand the interface and blast radius before committing engineering
time"). Precedent: the Phase 4C reranking audit (`phase4c-reranking-wdl-audit.md`), which
was likewise an investigation with zero production code changed.

## Purpose, and how the standard planning template maps onto an investigation

This candidate has **no independent variable and no trained model** — the same structural
situation Phase 4C was in. The governing task's planning template is therefore mapped as
follows, explicitly rather than silently:

| Template field | How it applies here |
|---|---|
| **Hypothesis** | Not a falsifiable model hypothesis. The claim under test is an *engineering* one: that an auxiliary WDL head can be added without disturbing the export/quantization/inference path, and that the roadmap's "Medium-high cost / ADR-001-adjacent" estimate for this candidate is therefore too high. |
| **Independent variable** | **None** — investigation only. Zero production code changed (see Verification). |
| **Controlled variables** | Not applicable; nothing was trained. The probes below construct models in memory only and write nothing into the repository or any dataset. |
| **Primary metric** | Blast radius: the set of modules that must change for a minimal auxiliary-head implementation, established by direct inspection and executable probes rather than estimation. |
| **Secondary metrics** | Revised cost/risk estimate per axis; identification of friction points that a naive implementation would hit. |
| **Success criteria** (pre-registered) | The investigation succeeds if it converts "unknown blast radius" into a per-module answer backed by evidence that can be re-run, and states an explicit go/no-go with a revised cost estimate. |
| **Failure criteria** (pre-registered) | The investigation fails if the blast radius cannot be determined without actually implementing the head, or if a blocker is found that makes the design infeasible — in which case the correct output is "infeasible, here is why," not a speculative design. |

**Expected information gain**: High, as the roadmap states — but note precisely *what* is
gained. This pass cannot say whether an auxiliary head *helps* the model; it can only say
what it would cost to find out, and whether the cost estimate the ranking was built on is
accurate. That is exactly the roadmap's stated purpose for ranking a scoping step ahead of
implementation.

## Why this answers a question the completed WDL-family experiments cannot

P4IV (λ=0.5) and P5-WDLALT (λ=0.8) both changed **what the primary head is graded
against** — the WDL signal was blended *into* the single target the model is scored on.
Both regressed the majority-population metric, and P5-WDLALT's dose-response reading
suggested the outcome signal carries no positive ranking information *as a target
component*.

An auxiliary head asks a structurally different question: can outcome signal improve the
**shared feature-transformer representation** through a separate gradient path, while the
primary head continues to be graded 100% on `eval_cp` with its target completely
untouched? No experiment in this roadmap has tested that, and no result so far bears on it
directly:

- The WDL-blend family's null is about **target composition**. It says a blended target is
  worse than a pure one. It says nothing about whether an auxiliary gradient into the
  shared trunk helps or hurts, because in that design the primary target is never modified.
- `measurement-model.md` §10's synthesis explicitly points at architecture as the next
  lever precisely because three interventions *within* the scalar objective have failed.
  An auxiliary head is the cheapest available member of that architecture class.

This is a genuine distinction, not a re-labelling: the two designs differ in which tensor
the outcome signal's gradient reaches and what the primary head's loss is computed
against. It is, however, **not** a guarantee of a different outcome — see Limitations.

## Findings

Discovery was Graphify-first, then direct source inspection, then executable probes
against the real modules (the Phase 4C discipline: verify feared blockers against the real
artifact rather than continuing to cite them as untested).

### Finding 1 — the export path is structurally immune to extra parameters

`checkpoint_to_canonical()` (`trainer/trainer/export/canonical.py:120-155`) extracts
tensors from a checkpoint **by explicit key name**, not by iteration, key count, or
positional order:

```python
ft_weights        = state_dict["ft.weight"]
ft_biases         = state_dict["ft_bias"]
output_weight     = state_dict["output_layer.weight"]
output_bias_tensor= state_dict["output_layer.bias"]
```

Parameters belonging to any additional head are therefore never read. `architecture_id`
and `feature_set_id` are module-level constants (`ARCHITECTURE_ID = 1`,
`FEATURE_SET_ID = 1`), not derived from the model, so they cannot drift either — which
matters because `NnueNetwork.java` rejects any file whose architecture id is not its
`SUPPORTED_ARCHITECTURE_ID`.

**Verified executably, not merely read.** A probe built a primary-only model and an
aux-head model with identical primary parameters, canonicalized both, and compared:

```
ft_weights bitwise equal:     True
ft_biases bitwise equal:      True
output_weights bitwise equal: True
output_bias equal:            True
architecture_id / feature_set_id (aux): 1 1
```

Extended through the quantizer (the last stage before `.nnue` bytes): quantized int16
arrays also bitwise equal. These checks are committed as
`trainer/tests/export/test_auxiliary_head_export_isolation.py` (5 tests) so the claim
stays verified rather than asserted — if a future change makes the export path sensitive
to extra parameters, that suite fails and this document's cost estimate is invalidated
loudly, at the point the assumption breaks.

**Consequence**: the export path, the quantization path, the `.nnue` byte layout, and the
entire Java inference side (`NnueNetwork.java`, `NnueEvaluator.java`, the accumulator, the
vector path) require **zero changes**. The auxiliary head exists only inside the training
process and is discarded at export, exactly as Deliverable 6 envisaged ("only the primary
head is exported/used at inference") — that intent is achieved by the *existing* export
code with no modification at all.

### Finding 2 — the auxiliary head does not perturb the primary forward pass

With primary parameters held identical, the primary output of the aux-head model and the
primary-only model are `torch.allclose` equal. The head is an additional consumer of the
shared activation, not a modification of the primary computation. (This is about the
*forward* pass at fixed weights; it is emphatically **not** a claim that training with an
auxiliary loss leaves the learned weights unchanged — see Limitations.)

### Finding 3 — the one real friction point: strict checkpoint loading

Every existing checkpoint-loading helper uses PyTorch's default `strict=True`:

| Call site | Line |
|---|---|
| `scripts/phase4_p4i_k_sweep.py::_load_model` | 110 |
| `scripts/phase1_optimization_sweep.py` | 146 |
| `scripts/phase1_experiment_2a.py` | 154 |
| `scripts/phase1_experiment_2b.py` | 175 |
| `scripts/phase3_label_audit.py` | 406 |
| `scripts/phase3_experiment_3a.py` | 138 |
| `tests/validation/test_validator.py` | 46 |

Loading an aux-head checkpoint into a plain `NnueNet` under `strict=True` raises
`RuntimeError`. Under `strict=False` it succeeds cleanly, reporting
`missing=[]`, `unexpected=['wdl_head.weight', 'wdl_head.bias']` — i.e. every parameter the
primary model needs is present, and only the auxiliary parameters are surplus.

**This is a one-argument change confined to the new experiment's own loader, not a
migration.** `phase4_p4i_k_sweep.py::_load_model` is imported and reused by every later
Phase 4/5 script, so the correct move is for the new experiment script to define its own
loader (or pass `strict=False`) rather than mutate the shared helper — changing the shared
helper's strictness would silently weaken a real correctness guard for six unrelated
existing scripts, the same class of near-miss P4III caught when an early draft flipped
`target_cp()`'s default for every caller.

### Finding 4 — the evaluation path needs no changes whatsoever

Because the primary head is unchanged and loads cleanly into a plain `NnueNet`
(Finding 3), the entire existing evaluation battery works unmodified:
`evaluate_held_out()`, `calibration_report()`, `fit_affine_calibration()`,
`eval_scale_check()` (`trainer/trainer/validation/validator.py`) all take an `NnueNet` and
call its primary forward pass. `phase4_p4i_k_sweep.py`'s shared helper battery
(`_eval_cell`, `_predictions`, `_magnitude_buckets`, `_histogram`,
`_select_best_checkpoint`) is reusable as-is.

**This also preserves the same-rubric property.** Like P4IV and P5-WDLALT — and unlike
P4III — an auxiliary head never touches `target_cp()`, `texel_sigmoid()`, or any
evaluation path, so baseline and candidate are graded under an identical rubric with no
rubric-contamination risk (`measurement-model.md` §5). The auxiliary head is never
evaluated at all; only the primary head is scored, against exactly the target it has
always been scored against.

### Finding 5 — `TrainingConfig` already has an established safe-default pattern

Three precedents (`mate_weight=1.0`, `mate_target_distance_aware=False`,
`wdl_lambda=1.0`) each added an opt-in field whose default reproduces prior behavior
exactly. A fourth field (e.g. `aux_wdl_weight: float = 0.0`) follows the same pattern with
the same guarantee: at the default, no auxiliary head is constructed and no auxiliary loss
term contributes, so every pre-existing config, checkpoint, and test is bit-for-bit
unaffected.

## Blast radius summary

| Axis | Changes required | Assessed cost/risk |
|---|---|---|
| `.nnue` byte format | **None** | None — verified |
| Java inference (`NnueNetwork`, `NnueEvaluator`, accumulator, vector path) | **None** | None — verified |
| `trainer/export/exporter.py` | **None** | None — verified |
| `trainer/export/canonical.py` | **None** | None — verified |
| `trainer/quantization/quantizer.py` | **None** | None — verified |
| `trainer/validation/validator.py` | **None** | None — Finding 4 |
| `trainer/model/network.py` | One optional, default-off head (~10 lines) | Low |
| `trainer/model/train.py` | One `TrainingConfig` field + one masked loss term (~15 lines) | Low |
| New experiment script | Mirrors `phase4_p4iv_wdl_blend.py`, own `strict=False` loader | Low (boilerplate reuse) |
| **ADR-001 revisit** | **Not triggered** — feature representation, output shape, and export format are all unchanged | None |

## Revised cost estimate, and a correction to the roadmap

`phase5-roadmap.md` rates this candidate **"Implementation cost / engineering risk:
Medium-high — new output shape, export-path changes, ADR-001-adjacent scope."**

That rating is **not accurate for the auxiliary-head design**, and the evidence above is
specific about why: there is no new *exported* output shape (the second head is never
exported), there are no export-path changes (verified bitwise), and ADR-001 is not
implicated (no feature-representation or format change). The rating appears to have been
carried over from the **win-probability-native output** item, which sits in the same
Deliverable 6 catalog and genuinely *is* ADR-001-class — that one replaces the primary
output's meaning and breaks every cp-scale consumer. The two were not distinguished when
the cost estimate was written.

**Revised estimate for the auxiliary-head candidate specifically: cost Low, risk Low** on
every infrastructure axis; the remaining risk is entirely *scientific* (will it help?), not
engineering. `phase5-roadmap.md` is updated accordingly, with this document cited as the
rationale.

Note this does **not** re-rank the candidate ordering: candidates #1 and #2 were already
completed, and this is #3 — the correction lowers its cost, which if anything strengthens
its position, and changes nothing about what runs next.

## Proposed design (recommended shape, not implemented here)

**Option A — auxiliary head on the shared post-activation vector (recommended).**
A second `nn.Linear(2 * hidden_width, 1)` consuming the same clipped-ReLU `combined`
activation that `output_layer` consumes, trained with masked binary cross-entropy against
`PositionLabel.wdl`:

```
total_loss = primary_weighted_mse
           + aux_wdl_weight * masked_bce_with_logits(wdl_head(combined), label.wdl)
```

masked by the existing `has_wdl` per-record mask (already built for P4IV, covering ~50% of
the training corpus on the backfilled `stage2-quiet-sf-wdl` shard). Gated on
`TrainingConfig.aux_wdl_weight` (default `0.0` → head not constructed, exact no-op).

Rejected alternatives, briefly: attaching the head to the raw pre-activation accumulator
(bypasses the clipped-ReLU the primary path uses, making the two heads see different
representations and muddying what "shared representation" means); and a separate optimizer
or learning rate for the head (a second independent variable, against
`measurement-model.md` §9's one-variable discipline on a first test).

**If run, the eventual experiment would be**: IV = `aux_wdl_weight` only; primary metric =
cp-only correlation (unchanged, per §7 condition 4); everything else in P1-G04's frozen
schedule held fixed; both selected and final checkpoints reported (§8); baseline reused
from `P3A-001` under an identical rubric. Infrastructure reuse would be near-total, which
is what makes the cost Low.

## Limitations

- **This pass says nothing about whether an auxiliary head improves the model.** It
  establishes cost and blast radius only. The roadmap's "Likelihood of changing the
  reference model: Unknown" is unchanged by this investigation — turning that unknown into
  a measured answer requires actually running the experiment, which this task's stop
  condition excludes.
- **An auxiliary loss *can* degrade the primary metric.** The auxiliary gradient reaches
  the shared feature transformer, so it genuinely perturbs the learned representation —
  Finding 2's forward-pass equivalence holds at *fixed* weights and must not be
  misread as a claim that training with the head is harmless. Insulation from the *export
  path* is not insulation from the *primary metric*.
- **The probes use a small `hidden_width` (8) and untrained weights.** That is sufficient
  for the structural claims made (key handling, shape, bitwise identity of the export
  arrays, load strictness), all of which are width-independent, but no numerical or
  convergence behavior at production width (256) was exercised.
- **Only Option A was scoped in detail.** The broader multi-task class named in
  Deliverable 6 (auxiliary phase-classification, auxiliary mate-distance heads) shares the
  same zero-export-blast-radius property by the same mechanism, but their target
  availability and loss shapes were not investigated.
- **No `ucinewgame`/data-quality interaction was considered.** The WDL labels this design
  would consume are the same backfilled game-outcome values P4IV/P5-WDLALT used, inheriting
  whatever noise properties those carry (RQ-5 measured a distinct, unrelated channel).

## Recommendation

**Go** — the auxiliary-head experiment is worth running, and it is substantially cheaper
than the roadmap assumed. Concretely:

1. Implement Option A behind `TrainingConfig.aux_wdl_weight` (default `0.0`), with a new
   experiment script mirroring `phase4_p4iv_wdl_blend.py` and defining its own
   `strict=False` loader rather than mutating the shared helper (Finding 3).
2. Do **not** modify `phase4_p4i_k_sweep.py::_load_model`'s strictness — six unrelated
   scripts depend on that guard.
3. Treat the result under the unchanged promotion rule (`measurement-model.md` §7); the
   same-rubric property (Finding 4) means no rubric-decomposition is needed, exactly as in
   P4IV.

Per this task's stop condition, none of the above is implemented here — this pass
completes the scoping candidate only, and the next roadmap item is not started.

## Architectural impact of *this* task

None. Zero production modules were modified; the only additions are this document, one
test file containing a test-local probe subclass, and a roadmap cost-estimate correction.
No new abstraction, dependency, or module boundary was introduced.
