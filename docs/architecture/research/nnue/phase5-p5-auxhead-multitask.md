# Experiment P5-AUXHEAD-001 — auxiliary WDL head (multi-task learning)

Phase 5, implementing the design scoped and costed in
[`phase5-arch-scoping-auxiliary-head.md`](phase5-arch-scoping-auxiliary-head.md) (Option A)
under the pre-implementation contract in
[`phase5-p5-auxhead-design.md`](phase5-p5-auxhead-design.md). That design record was written
**before** any implementation code and is the contract this report is graded against —
gradient flow, checkpoint layout, export/inference behavior, and rollback plan are specified
there and are not restated here.

**Verdict: not promotable.** Fails all four conditions of `measurement-model.md` §7.

## 1. Implementation summary

| Item | Detail |
|---|---|
| Experiment ID | `P5-AUXHEAD-001` (§26.5 scheme) |
| Independent variable | `TrainingConfig.aux_wdl_weight`, `0.0 → 0.04` — **one variable only** |
| Production files changed | `trainer/trainer/model/network.py`, `trainer/trainer/model/train.py` (two files, both behind a default-off gate) |
| Files deliberately **not** changed | everything in `trainer/trainer/export/`, `trainer/trainer/quantization/`, and all of `engine-core/` |
| Baseline | P1-G04 via its `P3A-001` checkpoint — the current production reference, reused unmodified |
| Wall clock | 198.7 s training, 3 m 28 s total |

Two new methods on `NnueNet` — `shared_activation()` (extracted from `forward()`, which now
calls it; numerically unchanged) and `auxiliary_wdl_logit()` — plus an optional
`with_aux_wdl_head` constructor flag. At the default the auxiliary head is **not constructed
at all**, so a default-config checkpoint's `state_dict` key set is bit-identical to every
pre-existing one.

Loss composition (auxiliary term is a mean over wdl-bearing records only, denominator floored
at 1.0 so an all-missing batch contributes exactly zero rather than `NaN`):

```
total = primary_weighted_mse + 0.04 · [ Σ(has_wdl · BCEWithLogits(wdl_head(combined), wdl)) / Σ(has_wdl) ]
```

The **reported** loss series remains the primary objective only, so this run's loss curve stays
on the same scale as every prior experiment's; the total is what gets optimized.

**Strict-loading policy honored.** `phase4_p4i_k_sweep._load_model`'s `strict=True` was **not**
relaxed — six unrelated scripts import it. The experiment script defines its own local loader
that accepts `wdl_head.*` as the only permitted unexpected keys and errors on anything else.

## 2. Methodology

**IV value declared before running**, derived from measured loss magnitudes at initialization
on the real corpus (20 batches × 256 records): primary MSE **0.144562**, auxiliary masked-BCE
**0.873508** — the auxiliary term is **6.04× larger** unweighted. `aux_wdl_weight = 0.04` sets
it to ≈25% of the primary term's magnitude (exact parity 0.0414, rounded to a clean declared
value): meaningful pressure, unambiguously subordinate, satisfying the requirement that the
primary evaluation loss remains the primary objective. Not tuned post-hoc.

**Held fixed**: `K=2.773456`, architecture (256/127/64/400), P1-G04's frozen schedule (20,000
steps, lr 0.01, cosine, warmup 200, batch 256), seeds (split 42, training 42, sample-selection
999), datasets (`stage1-lichess` + backfilled `stage2-quiet-sf-wdl`), sentinel filtering,
benchmark versions (v1 / v1-clean), checkpoint-selection protocol, evaluation protocol,
promotion protocol.

**Same-rubric property**: the auxiliary head never touches `target_cp()`, `texel_sigmoid()`, or
any `validator.py` path, so baseline and candidate are graded identically — no
rubric-contamination risk (§5), the same clean property P4IV had. The auxiliary head is never
evaluated; only the primary head is scored.

Training corpus: 35,933 records (wdl-labeled 17,990 = 50.07%; mate-labeled 4,137 = 11.51%).

## 3. Measurements

Reported in the **mandatory order** — the majority-population metric is examined before any
pooled number is interpreted (§6).

### 3.1 cp-only correlation — PRIMARY DECISION METRIC

| Arm | v1-clean | Δ vs baseline | v1 | Δ vs baseline |
|---|---|---|---|---|
| baseline (P1-G04) | 0.5941 | — | 0.3721 | — |
| candidate (selected, step 3,999) | 0.5924 | **−0.0017** | 0.3594 | −0.0127 |
| candidate (final, step 19,999) | 0.5912 | **−0.0029** | 0.3569 | −0.0152 |

**The majority population regressed at both checkpoints.** This alone fails §7 condition 4 and
is sufficient to reject, independent of everything below.

### 3.2 mate-only correlation (mechanism check, never decisive alone)

| Arm | v1-clean | Δ |
|---|---|---|
| baseline | 0.6401 | — |
| candidate (selected) | 0.5552 | **−0.0849** |
| candidate (final) | 0.6149 | **−0.0252** |

A large regression — an order of magnitude bigger than the cp-subset movement, and far larger
than anything the WDL-blend family produced (P4IV/P5-WDLALT both left mate-only flat to within
±0.001).

### 3.3 Pooled correlation (screening — interpreted last, per §6)

| Arm | v1-clean | Δ | v1 | Δ |
|---|---|---|---|---|
| baseline | 0.5933 | — | 0.5290 | — |
| candidate (selected) | 0.5673 | −0.0260 | 0.5007 | −0.0283 |
| candidate (final) | 0.5668 | −0.0265 | 0.5004 | −0.0286 |

Fails §7 condition 1 (screening requires *improvement*). Note the arithmetic: pooled fell
0.0265 while the cp-labeled majority fell only 0.0029 and the mate subset fell 0.0252. The
pooled number tracks the small, high-leverage mate subset far more than proportionally — the
**same composition/leverage mechanism `measurement-model.md` §4 documented for P4II, operating
here in the negative direction.** This is a useful confirmation that §6's ordering rule is not
merely defensive bookkeeping: read pooled-first, this experiment would look catastrophic;
read majority-first, the majority damage is real but small and the pooled figure is
substantially a leverage artifact of a subset that is not the promotion criterion.

### 3.4 RMSE and calibration (regression guards)

| Arm | v1-clean RMSE | Δ | v1-clean bias | Δ |
|---|---|---|---|---|
| baseline | 1030.1 | — | −191.1 | — |
| candidate (selected) | 1067.8 | +37.7 | −196.2 | −5.1 |
| candidate (final) | 1039.2 | +9.1 | −193.5 | −2.4 |

Both regress (§7 conditions 2 and 3 fail), the selected checkpoint more than the final.

### 3.5 Checkpoint-selection audit (§8, mandatory — both checkpoints reported)

Peak-pooled-correlation selection chose **step 3,999** against a **final step of 19,999** — a
wide separation that, on P4II's precedent, warrants checking whether the selected checkpoint is
representative. The full v1 trajectory:

```
step   999: 0.4334    step  7999: 0.4955    step 14999: 0.5001
step  1999: 0.4779    step  8999: 0.4984    step 15999: 0.4994
step  2999: 0.4922    step  9999: 0.4984    step 16999: 0.5000
step  3999: 0.5007 ←  step 10999: 0.4989    step 17999: 0.5005
step  4999: 0.4975    step 11999: 0.4988    step 18999: 0.5005
step  5999: 0.4969    step 12999: 0.4985    step 19999: 0.5004
step  6999: 0.4961    step 13999: 0.4995
```

**This is not a P4II-style transient spike.** The curve rises, then plateaus in a narrow
0.4955–0.5007 band for the remaining 16,000 steps; step 3,999 wins by 0.0003 over the final
step, which is noise at this resolution. The selected checkpoint is representative, and the
selection convention behaved acceptably here — reported explicitly so this is not silently
assumed. (The two checkpoints do differ materially on the *mate* subset — 0.5552 vs 0.6149 —
which the pooled selection metric is largely blind to; another instance of why §8 requires
reporting both.)

### 3.6 Auxiliary-task validity check (post-hoc, non-pre-registered — role bounded below)

**Standing of this subsection, stated before its numbers** (§9's declare-then-report
discipline cuts against introducing a metric after the fact, so its role must be explicit):
this check was **not** pre-registered in the design record's §8 metric list. It therefore
plays **no part in the promotion decision** — §5's rejection rests entirely on the four
pre-registered §7 conditions, all of which fail on pre-registered metrics alone, and would be
unchanged if this subsection did not exist. Its only role is *negative and bounding*: it
constrains how much mechanism may be claimed from that rejection. Using a post-hoc measurement
to **narrow** a causal claim is a different act from using one to justify promotion, which
§7 forbids and which is not done here.

A null result from a multi-task experiment is only meaningful if the auxiliary task actually
received and used signal. Evaluating the auxiliary head directly on the 1,993 wdl-bearing
held-out records:

| Checkpoint | auxiliary BCE | constant-predictor baseline | verdict | pred-vs-wdl correlation |
|---|---|---|---|---|
| step 3,999 | 0.7448 | 0.6801 | **worse than constant** | 0.5979 |
| step 19,999 | 1.1619 | 0.6801 | **worse than constant** | 0.5409 |

Two things are simultaneously true, and both matter:

- The auxiliary head **does carry real ranking signal** — correlation ≈0.54–0.60 between its
  predicted probability and actual game outcome is far above zero, so the gradient flowing into
  the shared trunk was not noise.
- The auxiliary head is nevertheless **badly calibrated, and degrades over training** — its BCE
  is *worse than simply predicting the corpus mean*, and worsens from 0.745 to 1.162.

A plausible and concrete contributing factor, stated as an observation rather than an
established mechanism: the auxiliary head is a bare `Linear` over the same qa-scaled activation
(elements in [0, 127], 512 of them) that the primary head consumes — but the primary head's
output is divided by `output_scale / (qa · qb)` downstream, while the auxiliary logit has no
comparable rescaling. Logits on that scale saturate `BCEWithLogits` easily, which is consistent
with high ranking correlation alongside poor calibration. **This was not tested and is not
claimed as the cause.**

## 4. Graphify findings

Baseline captured **once** at the start of this task, per the once-at-start policy (not re-run
between intermediate steps): **4,264 nodes / 8,048 edges / 533 communities** on the full repo.
A single validation pass was run at the end.

Pre-declared expected delta: two new methods plus one new constructor parameter on `NnueNet`,
one new `TrainingConfig` field, one new experiment script, and two new test modules — with
**no new edges into the export, quantization, or engine-core subgraphs.**

Final pass, tracing every edge incident on the 34 auxiliary-related nodes, finds they reach
only:

```
trainer_trainer_model_network_nnuenet{,_forward,_accumulate}
trainer_trainer_model_train_{train,trainingconfig}
trainer_trainer_contracts_dataset_position{record,label,metadata}
trainer_trainer_validation_validator_evaluate_held_out
trainer_scripts_{phase4_p4i_k_sweep,train_candidate_net{,_combine_and_split}}
```

**Edges into export / quantization / canonical / engine-core: NONE** — the design's central
invariant, confirmed in the graph rather than only in prose. The one place an export module is
referenced from new code is
`tests/export/test_auxhead_checkpoint_exports_unchanged_format.py`, which imports it precisely
to *assert* format invariance; a test asserting non-drift is not itself drift.

No unexpected coupling, no architectural drift.

## 5. Promotion decision

**Not promotable.** `measurement-model.md` §7 requires all four conditions; this candidate
fails all four:

| § | Condition | Result |
|---|---|---|
| 7.1 | Meaningful improvement in v1-clean pooled correlation | **FAIL** — regressed 0.0260–0.0265 |
| 7.2 | No meaningful RMSE regression | **FAIL** — +9.1 to +37.7 |
| 7.3 | No meaningful calibration regression | **FAIL** — bias worsened 2.4–5.1 cp |
| 7.4 | Majority-population (cp-only) improvement or no degradation | **FAIL** — regressed 0.0017–0.0029 |

**P1-G04 remains the reference model.** No SPRT is warranted or emitted: per CLAUDE.md §6 and
the trainer-workflow rule, SPRT adjudicates strength for a *promotable* candidate, and this one
does not clear the offline gate. Running one here would spend native-Windows compute on a net
already rejected on its own primary metric.

## 6. Why it failed — stated without overclaiming mechanism

The honest summary is that **this run does not cleanly answer the question the scoping report
posed**, and saying otherwise would overclaim.

What is established: at `aux_wdl_weight = 0.04`, an auxiliary WDL head sharing the feature
transformer regressed the majority-population metric slightly, the mate subset substantially,
and every regression guard. What is **not** established is *why* — and specifically, whether
the auxiliary *idea* failed or this auxiliary *parameterization* did. §3.6 is the reason for
that caution: the auxiliary objective was, by its own metric, fitted worse than a constant
predictor and got worse as training proceeded. An auxiliary task in that state is emitting
large, persistent gradients into the shared trunk throughout the run. Whether the trunk damage
came from "outcome signal is unhelpful to the representation" or from "a poorly-conditioned
auxiliary head pushed hard on the trunk for 20,000 steps" is **not distinguished by this
experiment**, and no further point was tested (single-experiment stop condition).

Two further observations, offered as observations only:

- The damage concentrated in the **mate subset** (−0.0252 to −0.0849) rather than the cp
  majority (−0.0017 to −0.0029). This is a different signature from the WDL-blend family, which
  left mate-only flat. It is consistent with the shared representation being reshaped in the
  extreme-evaluation region, but no test here isolates that.
- This result does **not** transfer to `measurement-model.md` §10's saturation-wall mechanism
  (§10.1) — that mechanism concerns `σ'(p,K)` vanishing on mate-target gradients in the
  *primary* loss, which this intervention does not touch. Nor is it a fourth instance of the
  target-source-blend null: the primary target here was pure. It is a genuinely new kind of
  negative result, and is recorded as such rather than folded into an existing story.

## 7. Remaining risks

- **n=1, single seed.** No seed-variance estimate exists for `aux_wdl_weight`. Against the best
  available (borrowed, different-configuration) reference — v1-clean same-K seed spread 0.0004
  (`phase4-p4i-replication.md` §6.1) — the cp-only regression of 0.0017–0.0029 is roughly
  4–7× that floor, so it is plausibly real, but it is **uncorroborated at n=1**, the same caveat
  P4I's own result carried.
- **One operating point.** `aux_wdl_weight = 0.04` is a single point; a much smaller weight was
  not tested, and §3.6 gives specific reason to think the head's conditioning — not only its
  weight — is in play.
- **Auxiliary-head conditioning is a confound, not a controlled variable.** This is the largest
  interpretive risk and is the reason §6 declines to conclude the auxiliary-signal idea is dead.
- **Diagnostic scope note**: `_gradient_norm(model)` now includes the auxiliary head's
  parameters on enabled runs, so that diagnostic is not directly comparable to prior
  experiments' values. It is a logged diagnostic only, not a decision metric.

## 8. Recommended next experiment

**Do not** proceed to roadmap candidate #4 (Huber/log-cosh) on the strength of this result
alone, and do not record the auxiliary/multi-task class as closed — this experiment does not
support that. Recommended, in priority order:

1. **Re-run the auxiliary head with a properly conditioned auxiliary path** — e.g. normalizing
   or rescaling the activation the auxiliary head consumes, so the auxiliary task can actually
   be fitted (target: auxiliary BCE below the 0.6801 constant-predictor baseline). Only with a
   *learnable* auxiliary task does a null on the primary metric cleanly mean "outcome signal
   does not help the shared representation." This is the single highest-value follow-up and it
   is cheap — the infrastructure now exists.
2. If that also regresses, **then** the auxiliary-signal-into-shared-representation idea has had
   a fair test and the multi-task class can be down-ranked on evidence rather than on this
   confounded point.
3. Only after that, resume the roadmap ordering at candidate #4.

Per this task's stop condition, none of the above is started here.

## 9. Reproducible experiment checklist (trainer-workflow)

```markdown
## Experiment: P5-AUXHEAD-001 — 2026-08-03
- [x] Trainer commit: 13c8a01 (parent)   Engine commit: unchanged (no engine-core edits)
- [x] Dataset source + generation command: stage1-lichess (acquire_stage1_lichess.py) +
      stage2-quiet-sf-wdl (stockfish_label.py -> backfill_stage2_wdl.py); unchanged from P4IV
- [x] Dataset counts: 40,000 total; training 35,933 post-sentinel-filter; v1 4,000 / v1-clean 3,992
- [x] Split: combine_and_split(seed=42), byte-identical to every prior experiment; dedup rate 0.01% (2 records)
- [x] Labeler: Stockfish 18, sha256 65c1e4da…61e5, nodes=25000, threads=1, mover-relative
- [x] Feature set + encoder: plain-768, unchanged; no encoding change in this experiment
- [x] Training config: in-script TrainingConfig (scripts/phase5_auxhead_multitask.py); seed 42
- [x] Framework/hardware: PyTorch (uv --extra train), CPython 3.13, WSL2 / Ryzen 7 7700X
- [x] Best (selected) checkpoint 3,999 + final 19,999 — both reported per MM §8
- [ ] Quantization float-delta: N/A — no net exported (candidate not promotable)
- [ ] .nnue hash + engine round-trip: N/A — nothing exported; format-invariance verified by test instead
- [ ] Provenance manifest: N/A — no net released
- [x] SPRT command emitted: deliberately NOT emitted — candidate fails the offline promotion gate
```
