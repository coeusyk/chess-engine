# P5-AUXHEAD-DIAG-001 — why the auxiliary task was poorly learned

A **diagnostic investigation**, not an experiment. No model was trained, no architecture was
redesigned, no loss or hyperparameter was changed, and no production module was modified. Its
sole question: *why* did P5-AUXHEAD-001's auxiliary WDL task fit worse than a constant
predictor ([`phase5-p5-auxhead-multitask.md`](phase5-p5-auxhead-multitask.md) §3.6), which
that report flagged as a confound making its null uninterpretable?

**Headline: a clear bottleneck was found.** The auxiliary task was not unlearnable — it was
**mis-parameterized**. The auxiliary head has no control over its own logit scale, and drifts
into a saturated regime where binary cross-entropy and ranking quality diverge. A second,
independent finding tempers the fix: the auxiliary gradient is near-**orthogonal** to the
primary one on the shared backbone, so repairing the scale is expected to make the auxiliary
task *learnable* without necessarily making it *useful*.

## Method, and why it is read-only

P5-AUXHEAD-001 saved 20 checkpoints. Every measurement below is recomputed from those saved
checkpoints plus the corpus, via `NnueNet`'s existing public API — **`train.py` and
`network.py` are untouched.** Instrumenting the training loop was considered and rejected as
unnecessary: model state at step *N* is exactly the checkpoint, so backpropagating a loss
there measures the true gradient field at that point in the trajectory.

Two deliberate deviations, disclosed rather than glossed:

- Gradients are evaluated on **one fixed 4,096-record diagnostic batch** (seed 20260803)
  rather than the actual training batch each step used. This is a *lower-variance* estimate of
  the expected gradient, not a less faithful one, and makes checkpoints mutually comparable.
- The auxiliary gradient norms below are reported **already multiplied by `aux_wdl_weight=0.04`**,
  i.e. as the auxiliary objective's actual contribution during training, not its raw scale.

**Reproduction fidelity**: the P5-AUXHEAD-001 checkpoints had been deleted with their
worktree, so the run was re-executed to regenerate them. It reproduced **exactly** —
`selected_step=3999`, cp-only deltas −0.0017 / −0.0029, identical to the committed report.
That also independently confirms the experiment is deterministic and reproducible.

Tooling: `scripts/phase5_auxhead_diagnostics.py` (new, read-only, analysis-only). Raw output:
`outputs/phase5/P5-AUXHEAD-DIAG/diagnostics.json` (gitignored, regenerable).

## 1. Gradient flow — auxiliary gradients *dominate early, then vanish*. Never balanced.

Backbone = `ft.weight` + `ft_bias` (the shared feature transformer).

| step | ‖g_backbone‖ primary | ‖g_backbone‖ aux×0.04 | aux/primary | ‖g‖ primary head | ‖g‖ aux head |
|---|---|---|---|---|---|
| 999 | 0.0005 | 0.0025 | **5.33** | 0.0003 | 0.1527 |
| 1,999 | 0.0008 | 0.0020 | **2.59** | 0.0002 | 0.1100 |
| 2,999 | 0.0009 | 0.0022 | **2.46** | 0.0002 | 0.1225 |
| 3,999 | 0.0007 | 0.0019 | **2.79** | 0.0001 | 0.1247 |
| 7,999 | 0.0008 | 0.0006 | 0.78 | 0.0001 | 0.0330 |
| 11,999 | 0.0004 | 0.0006 | 1.65 | 0.0000 | 0.0379 |
| 15,999 | 0.0008 | 0.0001 | 0.18 | 0.0001 | 0.0027 |
| 19,999 | 0.0004 | 0.0001 | **0.15** | 0.0000 | 0.0008 |

**Answer to "vanish, dominate, or well balanced": both extremes, never balanced.** For the
first ~4,000 steps the auxiliary objective contributes **2.5–5.3× more gradient to the shared
backbone than the primary objective does** — despite being weighted at 0.04 specifically to
keep it subordinate. The 0.04 weight was calibrated on *loss magnitude* at initialization; it
did not control *gradient* magnitude, which is what actually shapes the trunk. By step 15,999
the ratio has collapsed to 0.15–0.18.

The auxiliary head's own gradient collapses by ~190× (0.1527 → 0.0008) across training.

**This directly implicates the selected checkpoint.** Peak-pooled selection chose step 3,999 —
inside the window where the auxiliary objective was dominating the backbone by ~2.8×.

## 2. Target distribution — class imbalance is *not* the explanation

| class | count | fraction |
|---|---|---|
| `wdl=0.0` (loss) | 7,786 | 43.28% |
| `wdl=0.5` (draw) | 4,959 | **27.57%** |
| `wdl=1.0` (win) | 5,245 | 29.16% |

17,990 of 35,933 training records carry WDL (50.1%). Mean `wdl` = 0.4294.

43/28/29 is **mild** imbalance — nothing like the severe skew that causes majority-class
collapse, and the observed failure mode (§3) is overconfidence, not collapse. **Hypothesis
rejected.**

But the distribution matters for a different, sharper reason. Draws carry `y=0.5`, and binary
cross-entropy against a soft target of exactly 0.5 is **minimised at p=0.5 with value
log 2 = 0.693** — irreducibly. Across the corpus:

- **Irreducible BCE floor: 0.1911** — what a *perfect* predictor would still pay.
- Constant-predictor BCE: 0.6831.
- P5-AUXHEAD-001 achieved: 0.702 → **1.162** (rising).

So there was ample headroom (0.683 → 0.191) and the head moved **away** from it. The 27.57%
draw mass is what converts §3's saturation into catastrophic BCE: a confident 0-or-1
prediction on a `y=0.5` target is the single most expensive thing this loss can produce.

## 3. Auxiliary prediction behaviour — polarization, not collapse

| step | logit std | logit range | prob std | prediction entropy | held-out BCE |
|---|---|---|---|---|---|
| 999 | 4.34 | [−20.3, 16.3] | 0.345 | 0.267 | 0.702 |
| 10,999 | 6.37 | [−28.6, 26.2] | 0.417 | 0.225 | 0.873 |
| 19,999 | **8.78** | **[−39.2, 35.9]** | 0.439 | **0.172** | **1.162** |

Held-out probability histogram (n=1,993), deciles `[0.0,0.1) … [0.9,1.0]`:

```
step    999:  1013  167  124   91   85   89   54   61   89  220
step 10,999:   828  121   79   73   59   53   71   73  110  526
step 19,999:   886   90   67   51   35   47   55   55   82  625
```

**Predictions do not collapse toward a constant — they polarize toward 0 and 1.** By the final
step **76% of predictions sit in the two extreme deciles**, entropy has fallen 36% (0.267 →
0.172), and logits reach ±39 — `sigmoid(±39)` is 0 or 1 to float precision. **Hypothesis
"predictions collapse to a constant" rejected**; the opposite occurred.

This is the mechanism behind the report's original puzzle — good ranking (correlation
0.54–0.60) alongside BCE worse than a constant predictor. Ranking depends only on the *order*
of logits, which stays informative. BCE depends on their *calibration*, which saturation
destroys — and the 27.6% draw mass (§2) makes that destruction maximally expensive.

### 3.1 Where the logit growth comes from

| step | ‖W_aux‖ | ‖W_output‖ | mean activation | activations = 0 | activations at qa=127 | globally dead dims |
|---|---|---|---|---|---|---|
| 999 | 1.568 | 80.1 | 1.471 | 53.94% | **0.00%** | 0/512 |
| 3,999 | 1.563 | 286.8 | 1.844 | 55.99% | 0.00% | 0/512 |
| 11,999 | 1.840 | 511.0 | 2.263 | 57.70% | 0.00% | 0/512 |
| 19,999 | **2.422** | 546.0 | **2.353** | 57.58% | 0.00% | 0/512 |

Logit scale grows from **two compounding sources**: the auxiliary head's own weights (×1.55)
and the shared activation's magnitude (×1.60) — product ≈2.5×, consistent with the observed
logit-std growth of ×2.02.

The second source is the important one: **the activation scale is driven by the primary
objective, not the auxiliary one.** `‖W_output‖` grows 80 → 546 as the primary head learns to
produce centipawn-scale outputs, and the trunk's activations grow with it. The primary path
compensates downstream (`raw_sum * output_scale / (qa·qb)`); **the auxiliary head has no
equivalent normalization**, so it inherits a growing input scale it cannot control and its
logits inflate as a side effect of the primary task training normally.

Also measured and rejected: upper-clamp saturation (**0.00%** of activations reach `qa=127` at
any checkpoint) and structurally dead dimensions (**0/512**). The 57.6% zero rate is ordinary
per-example ReLU sparsity, stable across training, not degeneration.

## 4. Representation interaction — near-orthogonal, not negative transfer

Cosine similarity between the primary and auxiliary gradient vectors on the shared backbone:

```
step:   999  1999  2999  3999  4999  5999  6999  7999  8999  9999
cos:  -0.067 0.168 -0.193 0.135 0.064 0.046 0.177 -0.126 -0.111 -0.165
step: 10999 11999 12999 13999 14999 15999 16999 17999 18999 19999
cos:  -0.030 0.059 0.161 0.235 0.116 0.072 0.086 -0.121 -0.119 -0.094
```

Mean ≈ **+0.02**, oscillating about zero, |cos| ≤ 0.235 throughout. **There is no systematic
gradient conflict** — the auxiliary objective does not push the backbone *against* the primary
one. **Hypothesis "negative transfer via directly opposed gradients" rejected.**

What the data shows instead is **orthogonality**: the auxiliary gradient points in a direction
essentially unrelated to the primary objective's descent direction. Combined with §1, the
picture is that for the first ~4,000 steps the trunk was being pushed **2.5–5.3× harder in a
primary-irrelevant direction than in the primary-relevant one**. That is a plausible route to
the observed primary-metric regression without any gradient *conflict* — but note this is an
interpretation of two measurements, not an independently tested causal claim.

## 5. Learning dynamics — summary

```
                    step 999    →    step 19,999      direction
primary backbone ‖g‖  0.0005    →    0.0004           flat/declining (normal convergence)
aux backbone ‖g‖×w    0.0025    →    0.0001           collapses 25×
aux/primary ratio     5.33      →    0.15             dominant → negligible
aux head ‖g‖          0.1527    →    0.0008           collapses 190×
logit std             4.34      →    8.78             grows 2.0×
prediction entropy    0.267     →    0.172            falls 36% (more confident)
held-out aux BCE      0.702     →    1.162            RISES 66% (worse than constant, 0.683)
held-out aux corr     ~0.60     →    ~0.54            roughly preserved
pooled val corr       0.4334    →    0.5004           improves then plateaus
```

**Is the auxiliary objective learning, or producing ranking signal without calibration?**
Definitively the latter. Its ranking correlation is real and roughly preserved (~0.54–0.60)
while its calibration degrades monotonically past the point of being worse than a constant
predictor. It is not learning the task it was given; it is learning an ordering and then
inflating it without bound.

## Supported hypotheses

1. **Auxiliary logit-scale explosion (primary mechanism).** The auxiliary head has no scale
   control — no normalization, no weight decay — and its input magnitude is set by the primary
   task's needs. Logits reach ±39; predictions saturate. Directly measured, §3/§3.1.
2. **Draw mass makes saturation maximally costly.** 27.57% of targets are `y=0.5`, where BCE
   punishes confident predictions hardest and cannot go below 0.693. Measured, §2.
3. **Gradient weighting was calibrated on the wrong quantity.** `aux_wdl_weight=0.04` was
   derived to make the auxiliary *loss* ≈25% of primary at init; the resulting *gradient* on
   the backbone was 2.5–5.3× primary for the first 4,000 steps. Measured, §1.

## Rejected hypotheses

| Hypothesis | Verdict | Evidence |
|---|---|---|
| Class imbalance caused poor BCE | **Rejected** | 43/28/29 is mild; failure mode is overconfidence, not majority collapse (§2) |
| Predictions collapsed to a constant | **Rejected** | Opposite: 76% in extreme deciles, entropy ↓36% (§3) |
| Negative transfer via opposed gradients | **Rejected** | cos oscillates about +0.02, never systematically negative (§4) |
| Clipped-ReLU upper saturation starved gradients | **Rejected** | 0.00% of activations reach `qa` at any checkpoint (§3.1) |
| Backbone dimensions died | **Rejected** | 0/512 globally dead at every checkpoint (§3.1) |
| Auxiliary signal is intrinsically unlearnable here | **Rejected** | Ranking correlation 0.54–0.60 ≫ 0; floor 0.191 vs achieved 1.162 — headroom exists and was not used |

## Remaining unknowns

- **Whether fixing the scale makes the auxiliary task *useful*.** §4's near-orthogonality is an
  independent reason to expect a learnable auxiliary task might still not improve the primary
  metric. The diagnosis explains *why the task wasn't learned*; it does **not** establish that
  a learned version would help.
- **Whether the early 2.5–5.3× dominance permanently biased the trunk**, or whether it would
  have recovered given more steps. Not separable from this single run.
- **n=1.** One seed, one weight, one architecture. No seed-variance estimate exists for any
  quantity measured here.
- **Causality of the primary-metric regression.** §1+§4 are consistent with "trunk pushed hard
  in a primary-irrelevant direction," but this investigation measured gradients, not a
  controlled intervention, so that link remains inferred rather than demonstrated.

## Ranked smallest changes that address the diagnosed mechanism

Ordered by information gained per unit of change. Each is *one* variable.

1. **Normalize the auxiliary head's input** — divide `combined` by `qa`, or apply a
   `LayerNorm` before `wdl_head`. Smallest possible change (one line), targets the dominant
   measured mechanism (§3.1) at its source, and leaves the primary path untouched. Would move
   logits into a range where BCE is optimizable and convert the confounded null into a clean
   test.
2. **Calibrate `aux_wdl_weight` on gradient norm rather than loss magnitude**, or ramp it from
   0. Directly addresses §1's 2.5–5.3× early dominance. Complementary to (1) but a second
   variable — should not be combined with it in one experiment (§9).
3. **Treat draws as a distinct class** (3-way softmax, or exclude/downweight `y=0.5`) instead
   of soft-BCE at 0.5. Addresses §2, but is a larger design change and is only worth doing if
   (1) does not resolve the calibration failure.
4. **Weight decay on the auxiliary head only.** Would bound ‖W_aux‖ (×1.55 growth), but that is
   the *smaller* of the two logit-growth sources — (1) subsumes it more cheaply.

## Recommendation — single highest-information next experiment

**Re-run the auxiliary head with its input normalized (change 1), holding everything else at
P5-AUXHEAD-001's values.** One independent variable; near-zero cost; all infrastructure exists.

Its value is diagnostic-completing rather than promotion-seeking, and the pre-registration
should say so: the success criterion is **auxiliary held-out BCE below the 0.6831
constant-predictor baseline** (ideally approaching the 0.1911 floor), which is what makes the
auxiliary task genuinely learned. *Only then* does the primary metric's response become
interpretable as evidence about whether outcome signal helps the shared representation.

Stated plainly, and against optimism: given §4's near-orthogonal gradients, a primary-metric
*improvement* should not be expected as the likely outcome. The experiment's worth is that it
converts P5-AUXHEAD-001's confounded null into a clean one either way — which is precisely what
is needed before the multi-task class can be honestly ranked or closed.

**The multi-task class remains open**, and this investigation does not change the Phase 5
roadmap ordering. No architectural change is recommended on the strength of these diagnostics
alone beyond the single one-line normalization above.

## Graphify

Phase-scoped, per the governing instruction: run once at the start of this diagnostic phase and
once at the end, not per step. Baseline and final figures plus the module-boundary check are
recorded in the Verification section of the accompanying commit; the expected delta was exactly
one new read-only script under `trainer/scripts/`, with **no new edges into production training,
export, quantization, or engine-core paths** — the diagnostic imports `NnueNet`'s public API and
nothing else.
