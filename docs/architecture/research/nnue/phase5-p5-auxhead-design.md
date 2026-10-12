# P5-AUXHEAD-001 — pre-implementation design record

**Written before any implementation code**, per the governing task's explicit requirement to
document gradient flow, checkpoint layout, export behavior, inference behavior, and rollback
plan up front. The measured results live in the companion report
[`phase5-p5-auxhead-multitask.md`](phase5-p5-auxhead-multitask.md); this file is the design
contract that report is graded against, deliberately kept separate so the design cannot be
retro-fitted to whatever the numbers turned out to be.

Implements the design scoped (and costed) in
[`phase5-arch-scoping-auxiliary-head.md`](phase5-arch-scoping-auxiliary-head.md) — Option A,
which that pass recommended and which this experiment adopts unchanged.

## Experiment ID

**`P5-AUXHEAD-001`**, following §26.5's `<PhaseCode>[-<Tag>]-<NNN>` scheme (precedents:
`P4I-001`, `P4IV-001-wdl-blend`, `P5-WDLALT-001`). Never reused, including if this run is
superseded.

## 1. Gradient flow

The auxiliary head is a second `nn.Linear(2 * hidden_width, 1)` consuming the **same**
`combined` clipped-ReLU activation the primary `output_layer` consumes:

```
                    ┌──────────────► output_layer ──► primary eval (cp)  ──► primary MSE loss
ft (+ ft_bias)      │                                                          │
  └─► clamp(0,qa) ─►┤ combined                                                 │
      [SHARED]      │                                                          │
                    └──────────────► wdl_head ─────► logit ──► masked BCE ─────┤
                                     [TRAIN ONLY]                              │
                                                                    total = primary + w·aux
```

**What each gradient reaches:**

| Parameter | Primary MSE gradient | Auxiliary BCE gradient |
|---|---|---|
| `ft.weight`, `ft_bias` (**shared trunk**) | yes | **yes — this is the entire point** |
| `output_layer.weight/bias` (**primary head**) | yes | **no** — not on the auxiliary path |
| `wdl_head.weight/bias` (**auxiliary head**) | no | yes |

The auxiliary objective therefore influences the exported network **only** by reshaping the
shared feature transformer. It can never directly modify the primary head's own parameters,
which is exactly what distinguishes this from P4IV/P5-WDLALT: those changed the *target* the
primary head was fitted to, so the primary head's parameters were fitted to outcome-contaminated
values. Here the primary head is still fitted to a pure `eval_cp` target.

This is a **testable** claim, not a narrative one, and is asserted directly in the test suite:
backpropagating an auxiliary-only loss must leave `output_layer` gradients empty while producing
non-zero `ft` gradients.

**Loss composition:**

```
primary = Σ(w_mate · (σ(pred,K) − target)²) / Σ(w_mate)      # unchanged from today
aux     = Σ(has_wdl · BCEWithLogits(wdl_head(combined), wdl)) / Σ(has_wdl)
total   = primary + aux_wdl_weight · aux
```

The auxiliary term is a mean **over wdl-bearing records only** (not over the whole batch), so
its magnitude does not drift with the mask density of a particular batch. `Σ(has_wdl)` is
clamped to a floor of 1.0 so an all-missing batch contributes exactly zero rather than `NaN` —
a real risk, since ~50% of records carry no `wdl`.

## 2. Independent variable and its declared value

**IV: `TrainingConfig.aux_wdl_weight` only** (`0.0` → `0.04`). Everything else — architecture,
optimizer, P1-G04's frozen schedule, `K=2.773456`, datasets, split/training/shuffle seeds,
benchmark versions, checkpoint-selection protocol, evaluation protocol, promotion protocol —
is held fixed.

**Derivation of `0.04`, declared before running** (measured on the real corpus at
initialization, 20 batches × 256 records):

| Quantity | Measured |
|---|---|
| primary MSE at init | 0.144562 |
| auxiliary masked-BCE at init | 0.873508 |
| raw magnitude ratio (aux / primary) | 6.04× |
| wdl-bearing records in sample | 2,573 / 5,120 (50.3%) |

An unweighted auxiliary term would be **6× larger than the primary objective**, which would
invert the design requirement that the primary evaluation loss remains the primary objective.
`aux_wdl_weight = 0.04` sets the auxiliary contribution to ≈25% of the primary term's magnitude
at initialization (exact parity point: 0.0414, rounded to a clean declared value) — meaningful
auxiliary pressure, unambiguously subordinate. This mirrors P4II's precedent of testing a
moderate rather than maximal first value, and P4IV's of disclosing the derivation rather than
tuning post-hoc.

## 3. Checkpoint layout

| Condition | `model_state_dict` keys | `config` |
|---|---|---|
| `aux_wdl_weight == 0.0` (**default**) | `ft.weight`, `ft_bias`, `output_layer.weight`, `output_layer.bias` — **bit-identical to today** | gains `aux_wdl_weight: 0.0` |
| `aux_wdl_weight > 0.0` (this experiment) | the four above **plus** `wdl_head.weight`, `wdl_head.bias` | `aux_wdl_weight: 0.04` |

The auxiliary head is **not constructed at all** when the weight is zero, so a default-config
run produces a state_dict with exactly today's key set — no dormant parameters, no silent
format drift for any existing config, checkpoint, or test.

Every checkpoint is preserved: `train()`'s existing `checkpoint_dir` mechanism writes
`step-{step:06d}.pt` at every logged diagnostic point, unchanged.

## 4. Export behavior

**Unchanged, and structurally guaranteed rather than merely intended.**
`checkpoint_to_canonical()` extracts weights by explicit key name (`ft.weight`, `ft_bias`,
`output_layer.weight`, `output_layer.bias`), so `wdl_head.*` is never read. The scoping pass
verified executably that canonical and quantized arrays come out **bitwise identical** with an
auxiliary head attached, with `architecture_id`/`feature_set_id` unchanged; that verification is
already committed as `trainer/tests/export/test_auxiliary_head_export_isolation.py`.

Only the primary evaluation head is exported, exactly as today. The `.nnue` byte layout, the
canonical IR, and the quantizer are untouched — **no file in `trainer/trainer/export/`,
`trainer/trainer/quantization/`, or `engine-core/` is modified by this experiment.**

## 5. Inference behavior

**Unchanged.** The auxiliary head exists only inside the Python training process and is
discarded at export. `NnueNetwork.java`, `NnueEvaluator.java`, the accumulator, the Vector API
path, the SPRT workflow, and the production runtime see a `.nnue` file structurally identical to
every previous one — same magic, same `formatVersion`, same `architectureId`/`featureSetId`,
same tensor shapes. There is no runtime code path by which an auxiliary head could be observed.

## 6. Strict-loading policy

`phase4_p4i_k_sweep.py::_load_model` uses `strict=True` and is imported by six unrelated
scripts. **It is not modified.** This experiment's script defines its own local loader that
takes `strict=False` *only* for reading back its own auxiliary-head checkpoints. No existing
strict path is weakened globally — the scoping report flagged this precisely because P4III's
near-miss (an early draft flipping a shared default for every caller) is the failure mode to
avoid.

## 7. Rollback plan

Rollback is a **one-value change**, with three independent layers of safety:

1. **Config default.** `aux_wdl_weight` defaults to `0.0`. Every existing config, checkpoint,
   script, and test behaves exactly as before with no edit — the same safe-default discipline
   `mate_weight=1.0`, `mate_target_distance_aware=False`, and `wdl_lambda=1.0` already follow.
2. **No head at zero weight.** At the default the auxiliary module is never constructed, so
   even the checkpoint key set is unchanged — rollback leaves no residue in saved artifacts.
3. **Nothing shipped to revert.** Export, quantization, `.nnue`, and all Java inference are
   untouched by construction, so no deployed artifact can be affected regardless of outcome.
   If the experiment is not promotable, the reference model (P1-G04) simply remains in place;
   there is no migration, no format rollback, and no engine-side change to undo.

Full revert of the code itself is a single `git revert` of one commit touching two production
files (`network.py`, `train.py`), each behind a default-off gate.

## 8. Pre-registered evaluation and decision criteria

Per `measurement-model.md`, and in the mandated reporting order — **the majority-population
metric is examined before any pooled number is interpreted**:

1. **cp-only correlation** — primary decision metric (§7 condition 4)
2. v1-clean pooled correlation — screening (§7 condition 1)
3. v1 pooled correlation
4. RMSE — regression guard (§7 condition 2)
5. calibration — regression guard (§7 condition 3)
6. exploratory: mate-only correlation (mechanism check), compression, magnitude-bucket MAEs,
   histograms — never decisive alone

**Promotion requires all four §7 conditions.** Comparison is against the current production
reference only (**P1-G04**, via its `P3A-001` checkpoint), under an identical rubric — the
auxiliary head never touches `target_cp()`, `texel_sigmoid()`, or any `validator.py` path, so
this is a direct same-rubric comparison with no rubric-contamination risk (§5), the same clean
property P4IV had.

Both the **selected** and **final** checkpoints are reported (§8), since this experiment changes
the effective treatment of a subset (the wdl-bearing ~50%).

**Noise-floor caveat, stated in advance:** no seed-variance estimate exists for
`aux_wdl_weight`. The best available reference remains the v1-clean same-K seed spread of
0.0004 from a *different* configuration (`phase4-p4i-replication.md` §6.1). Any effect will be
reported against that borrowed estimate with the same "uncorroborated at n=1" caveat P4I's own
result carried — not presented as if a matched noise floor existed.
