# P5-AUXHEAD-RMS-001 -- pre-implementation design record

**Written before any implementation code and before either training run.** The measured
results live in the companion report
[`phase5-p5-auxhead-rms.md`](phase5-p5-auxhead-rms.md); this file is the contract that report
is graded against, kept separate so the design cannot be retro-fitted to whatever the numbers
turn out to be. It follows the same discipline as
[`phase5-p5-auxhead-design.md`](phase5-p5-auxhead-design.md).

This experiment is the reconciled successor to the auxiliary-head investigation. It replaces
the constant-divisor (`/QA`) intervention proposed in the first recovery-audit draft. That
proposal was blocked in adversarial review: under the repository's plain-Adam optimizer (no
weight decay, no gradient clipping) a constant input divisor is not an input-conditioning
normalization. Writing the auxiliary logit as `w . x / d`, Adam's invariance to constant
gradient rescaling turns the divisor into a roughly `d`-fold reduction of the auxiliary head's
effective logit-space learning rate and of its coupling into the shared backbone. With `d=QA=127`
that is a large, arbitrary throttle confounded with an effective auxiliary-weight cut, not a
test of the diagnosed magnitude-drift mechanism. This experiment uses a per-sample,
parameter-free RMS normalization instead, recomputed in the forward pass each step, which the
optimizer cannot absorb into a learning-rate change.

## Experiment ID and status

- Experiment ID: **P5-AUXHEAD-RMS-001**
- Status at authoring: preregistration. No treatment code exists yet; no arm has been trained.
- Predecessor diagnosed failure: [`phase5-auxhead-failure-diagnostics.md`](phase5-auxhead-failure-diagnostics.md) (P5-AUXHEAD-DIAG-001).
- Predecessor experiment: [`phase5-p5-auxhead-multitask.md`](phase5-p5-auxhead-multitask.md) (P5-AUXHEAD-001, not promotable).

## 1. Hypothesis

The prior auxiliary-head failure (P5-AUXHEAD-001, diagnosed in P5-AUXHEAD-DIAG-001) was caused
in part by the auxiliary head inheriting the magnitude drift of a shared representation that is
being optimized for the primary centipawn (CP) task. Over training the shared clipped-ReLU
activation's magnitude rose (measured mean roughly 1.47 to 2.35), the auxiliary logits inflated
to about [-39, +36], 76% of held-out predictions saturated into the two extreme deciles, and
held-out auxiliary BCE diverged from 0.702 to 1.162, above the constant-predictor baseline.
Ranking stayed informative (correlation 0.54 to 0.60) while calibration was destroyed, which is
the signature of logit saturation driven by an input whose scale is non-stationary.

Making the auxiliary head invariant to per-sample activation magnitude should remove that source
of conditioning drift, so the auxiliary task becomes learnable (held-out BCE below a
constant predictor and below the matched unnormalized control) without logit saturation.

**This hypothesis does not claim, and this experiment does not assume, that repairing the
auxiliary task confers any benefit on the primary CP evaluation.** Whether the primary
representation improves is a separate question, answered by the primary evaluation in section 7,
not presumed by the mechanism result.

## 2. Independent variable

Auxiliary-head input normalization, applied to the auxiliary path only:

- **control**: no normalization. The auxiliary head consumes the shared activation `combined`
  exactly as P5-AUXHEAD-001 did. This is the matched unnormalized control (see section 3).
- **treatment**: parameter-free RMS normalization of the auxiliary head's input.

Everything else is held fixed. The two arms differ only in this flag.

## 3. Mathematical treatment and its gradient geometry

Let `combined` be the shared, both-perspectives clipped-ReLU activation of shape
`(batch, 2 * hidden_width)` that both heads consume. The treatment computes the auxiliary
logit from a per-sample RMS-normalized input:

```
rms       = sqrt(mean(combined ** 2, dim=1, keepdim=True) + eps)     # eps = 1e-6
aux_input = combined / rms
aux_logit = wdl_head(aux_input)
```

with `eps = 1e-6`. The primary forward path consumes the original `combined` unchanged; the
RMS normalization exists only inside `auxiliary_wdl_logit()`.

Deliberately excluded, because each would change the treatment into a different intervention:
no centering (mean subtraction), no affine parameters, no LayerNorm, no weight normalization,
no explicit temperature, no gradient clipping, no auxiliary-weight change, no optimizer-group
change, no learning-rate change.

**This is not a harmless scalar rescale, and it is not the blocked constant divisor.** Two
distinct properties matter, both intentional and both part of what is being tested:

1. **It removes the auxiliary objective's sensitivity to the magnitude (radial) component of
   the shared activation.** Write `combined = r * u`, where `r = ||combined|| / sqrt(2*hidden_width)`
   is the per-sample RMS scale and `u` is the unit-RMS direction. After normalization the
   auxiliary head sees only `u`; the scalar `r` is divided out. The auxiliary BCE gradient can
   therefore reshape the shared feature transformer only through the direction of the
   activation, never through a term that uniformly scales the whole activation up or down. The
   primary head still consumes the full `r * u`, so the primary objective is unchanged. The
   diagnosed failure was precisely that the auxiliary head rode the growth of `r`; removing `r`
   from the auxiliary path is the mechanism this experiment tests.

2. **It is per-sample and data-dependent, and the optimizer cannot absorb it.** Because `rms`
   is recomputed from the activation on every forward pass rather than being a fixed constant,
   there is no fixed `1/d` factor sitting between the loss and the head weights for Adam's
   scale-invariance to convert into a learning-rate change. The auxiliary head learns at the
   normal schedule on a unit-scale input. This is the specific respect in which RMS
   normalization differs from the rejected `/QA` divisor.

Honest cost of the treatment, disclosed rather than glossed: if the magnitude `r` itself
carried genuine win/draw/loss signal (for example, position sharpness correlating with
activation norm), RMS normalization discards it from the auxiliary path. That is not a defect to
be worked around; it is the test. If auxiliary calibration recovers, magnitude drift was the
dominant cause; if it does not, magnitude was not the (sole) cause. Either outcome is a clean
result.

## 4. What must not change

Held fixed across both arms and versus the historical frozen schedule: hidden width 256,
QA 127, QB 64, output scale 400, K = 2.773456, `aux_wdl_weight = 0.04`, `wdl_lambda = 1.0`,
`mate_weight = 1.0`, distance-aware mate targets off, Adam optimizer with a single parameter
group, learning rate 0.01, cosine schedule, 200 warmup steps, 20,000 steps, batch size 256,
split seed 42, training seed 42, train-sample-selection seed 999, the Stage-1 and
Stage-2-WDL datasets, split-then-sentinel-filter order, sample membership and order, and every
target definition. No runtime, export, quantizer, `.nnue`, search, UCI, or shard-format change.
No GitHub issue mutation. The state-dict and export contract are unchanged: RMS normalization
introduces no new parameters, so a treatment checkpoint's key set is identical to a control's.

## 5. Control identity

Baseline roles are kept explicit and are never silently renamed into one another:

- **Classical** -- the shipped, default runtime evaluator. Not involved in this experiment.
- **E-3** (`dfffd3da-7f8f-4fc9-92dc-b3873c97fb21`) -- retired historical release candidate. Not
  involved.
- **P1-G04** -- the promoted historical research reference (selected step 15,999). Display-only
  here.
- **P3A-001** -- the operational no-auxiliary control used by the later Phase-4/Phase-5
  pipeline (selected step 16,999, `outputs/phase3/P3A-001/checkpoints/step-016999.pt`), reused
  unmodified.

Gating roles for this experiment:

- **Primary control (pass/fail)**: the matched unnormalized auxiliary-head run under the same
  current environment (the control arm of this experiment). This is the arm the treatment must
  beat to claim the mechanism was repaired.
- **No-auxiliary control (primary-regression guard)**: P3A-001.
- **P1-G04**: historical continuity, displayed alongside but excluded from pass/fail gating.

## 6. Control recovery or regeneration

The archived P5-AUXHEAD-001 raw checkpoints are searched for first. They are absent here
(`outputs/phase5/` does not exist in this working tree; `outputs/` is gitignored). Per the
governing task's contingency, exactly one matched unnormalized control is regenerated under the
current environment, and a matched control is preferred over any recovered archive regardless,
because the treatment must be compared against a control produced by the same toolchain.

Before either run, provenance is pinned and recorded in each arm's meta: SHA-256 of the
Stage-1 and Stage-2-WDL dataset files and manifests, the ordered train and held-out split
membership hashes and counts, the P3A-001 and P1-G04 checkpoint identities, and the
Python / PyTorch / NumPy / CUDA environment.

The control arm is `aux_rms_norm = False` with `aux_wdl_weight = 0.04` on the frozen schedule,
so it is byte-identical in code path to P5-AUXHEAD-001. Its **reproduction gate** is that it
reproduces the historical direction and gross pathology:

- auxiliary held-out BCE worse than the constant predictor at the final step,
- auxiliary logit polarization / inflation (wide logit range, extreme-decile mass),
- early auxiliary/backbone gradient ratio well above 1 (historically 2.5 to 5.3 in the first
  few thousand steps), collapsing later,
- primary CP-only correlation regression direction versus P3A-001 (historically small and
  negative).

Bit-identical historical floats are not required across a changed environment. But if the
regenerated control materially contradicts the historical mechanism (for example, the auxiliary
task learns cleanly with no logit inflation, or the gradient ratio never shows early
dominance), the experiment **STOPS**: no treatment arm is trained, and the outcome is reported
as a reproducibility failure rather than a normalization result.

## 7. Preregistered measurements

### 7.1 Mechanism measurements

At the same 20 diagnostic checkpoints P5-AUXHEAD-001 used, for both arms, on the same fixed
diagnostic batch (seed 20260803) and held-out WDL set:

- auxiliary held-out BCE,
- auxiliary logit mean, standard deviation, min/max, selected percentiles, extreme-decile
  fraction,
- auxiliary prediction entropy,
- shared-activation RMS before normalization,
- auxiliary-head weight norm,
- primary/backbone gradient norm,
- auxiliary/backbone gradient norm,
- auxiliary-to-primary backbone gradient ratio,
- primary vs auxiliary backbone gradient cosine similarity.

**Mechanism success (all three required):**

1. treatment held-out auxiliary BCE beats **both** the train-fitted constant predictor
   evaluated on the identical held-out WDL set **and** the matched unnormalized control's
   held-out auxiliary BCE;
2. auxiliary logit polarization is materially reduced versus the control (narrower logit range,
   lower extreme-decile mass, higher prediction entropy);
3. the gradient-ratio dynamics no longer show the prior early dominance (roughly 2.5 to 5.3)
   followed by a collapse to about 0.15.

These are mechanism criteria, not model-promotion criteria. No exact acceptable gradient-ratio
band is fixed after seeing results. The constant baseline is recomputed from the pinned data
(the previous recovery review recomputed approximately 0.68032 on held-out; that is verified,
not hardcoded).

### 7.2 Primary research evaluation

Reported in the Measurement Model order exactly:

1. CP-only correlation -- the primary majority metric,
2. mate-only correlation where informative,
3. v1-clean correlation,
4. v1 pooled correlation,
5. RMSE,
6. bias / calibration and the existing guards.

Both the selected and final checkpoints are reported. The checkpoint-selection rule is the
existing one (peak held-out pooled correlation, `_select_best_checkpoint`). The treatment
checkpoint is never selected by auxiliary BCE. The treatment is compared against its matched
unnormalized control and against the P3A-001 no-auxiliary control; P1-G04 is shown separately
for historical continuity only.

## 8. Interpretation branches

- **A. Mechanism fails.** "RMS conditioning did not resolve the auxiliary-task failure." Stop.
- **B. Mechanism passes, primary flat or regressed.** "The auxiliary task is now learnable under
  this conditioning, but this configuration provides no primary-representation benefit." This is
  a clean negative result for this multi-task variant.
- **C. Mechanism passes and primary metrics improve enough to satisfy the existing offline
  promotion protocol.** Record as a candidate requiring replication. Do not run SPRT
  automatically. One seed is not sufficient for a strength claim.

## 9. Stopping conditions, abort, rollback

- Abort before the treatment arm on missing or mismatched pinned data, or on unexplained control
  drift, or if the control fails its reproduction gate (section 6).
- Abort training on non-finite loss or gradients, or on any checkpoint / schema failure.
- Stop after exactly one control arm and one treatment arm plus the report. No parameter sweep,
  no additional seeds, no training beyond 20,000 steps, no new source data, no LayerNorm or
  weight-cap follow-on inside this slice, no statistical strength claim.
- Rollback: revert only the new neutral-gated code if the default (`aux_rms_norm = False`) path
  or export isolation ever changes behavior. Keep all run artifacts and failure records. Do not
  restore or delete historical outputs, and do not alter reference defaults to make tests pass.
- A failed normalization hypothesis is a completed result, not permission for an unplanned
  second intervention. Stage 3 is not started.
