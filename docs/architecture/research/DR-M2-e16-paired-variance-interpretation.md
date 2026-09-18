# M-2: E-16's paired deltas are not the same random variable DR-M1 measured

Governs no open issue. This is a short, standalone measurement-interpretation audit of already
closed work. It does not reopen #224, does not change E-16's recorded classification, and does not
change any reported metric. No training, no corpus generation, no rerun of anything. It corrects
how one comparison in `DR-E16-phase-e-evaluation-report.md` was framed, not what was measured.

## 1. The problem

`DR-E16-phase-e-evaluation-report.md` section 5 judged E-16's paired treatment/control cp-only
deltas against `DR-M1-cp-only-noise-floor-characterization.md`'s measured noise band (roughly
0.001 to 0.008) and concluded that every individual paired delta falls inside that band, so the
mean paired delta is not distinguishable from the configuration-matched noise DR-M1 already
measured. That framing treats DR-M1's band as a calibrated null distribution for E-16's paired
quantity. It is not one, and the two studies' own designs show why.

**What DR-M1 measures.** Three independent single-arm runs, one seed each (42, 43, 44), with no
control/treatment split at all: one model per seed, all trained on the identical 36,000-record base
set with no Stage-3 data. Each run gets its own seed, which governs both that run's model
initialization and its own independent data-order shuffle. `train.py` calls
`seed_everything(config.seed)` once per `train()` call, before either the model or the shuffle
order is constructed. The pairwise deltas DR-M1 reports (0.0033, 0.0044, 0.0011 selected; 0.0034,
0.0046, 0.0080 final) are `|run_i - run_j|` between two of these three fully independent runs.
Every source of run-to-run variance, initialization draw and data-shuffle order alike, differs
between the two runs in every one of those pairs.

**What E-16's decisive comparison measures.** For a fixed training seed `s`, `treatment(s) minus
control(s)`. Per `DR-E16-phase-d-training-report.md` sections 2 and 3, both arms for that seed
share a verified byte-identical starting parameter hash, not merely "the same seed passed": the
exact saved `state_dict()` was loaded into both. Because `train()` calls `seed_everything(s)` with
the identical `s` for both arms, and both arms' final training lists have the identical length
(42,911 records each), the identical epoch-shuffle permutation is applied to both arms' record-index
sequences. The only real difference between `control(s)` and `treatment(s)` is which 6,911 Stage-3
rows sit at the tail of that shared, identically shuffled index sequence. Initialization noise and
data-order noise, the two things DR-M1's between-run pairwise deltas are entirely made of, are
shared between the two arms of an E-16 pair rather than drawn independently. A matched-pairs design
like this exists specifically to cancel the variance sources DR-M1's between-run comparison cannot
separate.

These are different random variables. DR-M1's pairwise spread reflects `Var(run_i) + Var(run_j)`
minus twice the covariance, for two runs that share nothing but a training configuration and whose
covariance is close to zero, since nothing forces their init or shuffle draws to agree. E-16's
paired-delta variance reflects the same formula applied to two runs with a verified identical
initialization and shuffle order, which pushes the covariance term substantially positive and
shrinks the paired-delta variance relative to what two fully independent runs at the same
configuration would show. DR-M1 never measured this covariance. It could not have: three unrelated
single runs give nothing to measure a pairing effect from.

## 2. Verified against the actual code, not just the docs

`trainer/trainer/model/train.py`'s `train()` calls `seed_everything(config.seed)` as its first
line, before model construction and before `epoch_order = list(range(len(records)))` and
`random.shuffle(epoch_order)`. One seed governs both.

`DR-E16-phase-d-training-report.md` section 2 records that the exact saved `state_dict` was loaded
into two independently constructed `NnueNet` instances, and that a SHA-256 over each model's full
parameter-tensor set matched exactly for all three seeds. Shared initialization was checked
bit-for-bit, not assumed from "same seed."

`DR-E16-phase-d-training-report.md` section 1 records that both arms' final training lists are
42,911 records each (36,000 base plus 6,911 Stage-3), so `len(records)` is identical between arms
for a given seed. That is the precondition for `random.shuffle(epoch_order)` to produce the same
permutation under the same `seed_everything(s)` call.

`DR-M1-cp-only-noise-floor-characterization.md` section 2 describes three runs with
`TrainingConfig.seed` as the only independent variable, no Stage-3 data at all, and no notion of a
pair anywhere in the design. Its three points are trained fully independently of one another.

The distinction is real, not a semantic quibble. It is the difference between measuring how much
two unrelated single runs at this configuration disagree, which is what DR-M1 measured, and
measuring how much a matched pair sharing init and data order by construction disagrees, which is
what E-16's decisive comparison actually is.

## 3. Corrected interpretation

DR-M1 remains valid, unmodified evidence that raw seed-to-seed variance at this training
configuration is substantial. A spread of 0.001 to 0.008 for two unrelated single runs' cp-only
correlation is a real, useful number, and nothing here revises DR-M1's own measurement, method, or
conclusion about itself. What this document withdraws is only the downstream use of that number.

DR-M1's pairwise range is not a calibrated null distribution for E-16's matched paired deltas.
Because the paired design shares initialization and data order by construction, its true noise
floor is a different, currently unmeasured quantity, plausibly smaller than DR-M1's between-run
figure since shared randomness cancels in a matched pair. This document does not offer a corrected
number, because no experiment in this repository has actually measured a paired-configuration noise
floor. Saying that E-16's paired deltas fall inside DR-M1's band, and are therefore
indistinguishable from noise, overstates what was established. `DR-E16-phase-e-evaluation-report.md`
section 5's phrasing is corrected here rather than by editing that report, per this task's
no-rewriting-history instruction. That report's metric values are all still correct; only the
noise-band sentence they were read alongside was miscalibrated.

This does not flip E-16's classification. Section 5 below explains why: without a real paired
noise floor to compare against, the honest reading of E-16's own paired evidence is that it is
small, positive, and consistent, not that it is proven noise, and not that it is proven signal.
Null/inconclusive was already the correct classification before this correction and remains
correct after it, for a slightly different reason than originally stated: the absence of a
calibrated null, rather than the presence of one that happens to swallow the result.

## 4. E-16's paired fixed-horizon evidence, restated directly

`DR-E16-shared-opening-prefix-preregistration.md` section 9 already notes that selected checkpoints'
steps differ across some control/treatment pairs, so the final-checkpoint (step 19999) comparison
is the cleaner fixed-horizon reading. The primary evidence is:

**Final checkpoint, cp-only paired deltas (treatment minus control):**

| Seed | Delta |
|---|---|
| 42 | +0.00238 |
| 43 | +0.00150 |
| 44 | +0.00414 |

Mean +0.00267, stdev (n=3) about 0.00135, sign consistency 3/3 positive.

**Selected checkpoint, cp-only paired deltas**, reported in full as required, though treated as the
noisier reading given the selection-step mismatch: mean +0.00386, stdev (n=3) about 0.00125, sign
consistency 3/3 positive.

No p-value, confidence interval, significance threshold, or retrospective success criterion is
computed or implied by these numbers. n=3 is too small to support any of them, and this document
does not attempt it. The numbers are reported exactly as measured, nothing more.

## 5. Reading the evidence without a calibrated null

Three consistently signed, small, positive deltas at a fixed horizon is a real observation. What it
is not, absent a paired noise-floor measurement, is proven noise (the original, now-corrected
framing) or proven signal (a claim this document also does not make). E-16's own paired data cannot
currently distinguish those two possibilities, which is what null/inconclusive is supposed to mean
under `measurement-model.md`'s own discipline: a null result is a complete, valid outcome, not a
verdict that nothing is there. E-16 stays null/inconclusive. #224 stays closed. No metric or result
in either document changes.

## 6. Stage-3 exploratory reversal, recorded as a hypothesis

`DR-E16-phase-e-evaluation-report.md` section 8 found that on each arm's own frozen Phase-C
held-out set, treatment's cp-only correlation is consistently lower than control's: a mean delta of
-0.0416 at the selected checkpoint and -0.0341 at the final checkpoint, 3 out of 3 negative at both
checkpoint kinds. That is the opposite direction from the small positive reading on v1-clean above.

One possible explanation, offered strictly as a hypothesis for future work: a coverage or
generalization tradeoff, where `SeededDiversitySelector`'s broader move-selection distribution
produces a Stage-3 corpus that the resulting model fits slightly worse on its own held-out slice
than `BestMoveSelector`'s narrower, more repetitive corpus fits its own, while generalizing
marginally better to the unrelated, historical v1-clean benchmark. This is one candidate
explanation among others. Sampling noise on held-out sets of only 595 to 694 records is equally
plausible, and this document makes no attempt to distinguish between them. Nothing here treats the
reversal as established.

## 7. Research-line recommendation

Recommendation: pause the seeded-diversity Stage-3 line now, rather than immediately justifying a
replication experiment.

E-16 already used three seed replicates, and three seeds is this project's own established
smallest-defensible-n precedent, matching DR-M1's own design. There is no existing convention in
this repository for treating three as insufficient without a specific, costed reason to go further.
The corrected interpretation in section 3 does not create new urgency either: it downgrades an
overstated "proven noise" claim to an honest "inconclusive, no calibrated null available" one. It
does not surface a previously hidden positive signal worth chasing. And the Stage-3 exploratory
reversal in section 6 is explicitly a hypothesis, not a finding. Pursuing it now would mean
designing a second experiment around a pattern that has not yet been separated from held-out-set
sampling noise at n of 595 to 694 records.

This is not a recommendation to abandon the shared-opening-prefix design, or to conclude the
diversity mechanism does nothing. E-16's own null/inconclusive classification already carries that
caveat. It is a recommendation not to spend more generation or training budget on this specific
comparison without a clearer reason than "the last one was ambiguous," which is consistent with
`measurement-model.md` section 12's standing instruction that a null result does not by itself
justify a silent rerun.

## 8. If a pure replication were pursued instead

This section is prospective design guidance only. It is not a recommendation this document is
making (section 7 recommends pausing); it answers "if that path were chosen anyway, what would need
to be true."

A pure replication on the existing, already-frozen E-16 corpora, with no new corpus generation,
would add more training seeds against the same `stage3-e16-control-001` and
`stage3-e16-treatment-001` equalized data, at the same roughly 120 to 130 seconds per run this
session already measured (`DR-E16-phase-d-training-report.md` section 4: 118.9s to 127.8s per run).
A defensible target seed count should follow from the observed paired-delta spread rather than be
picked arbitrarily. The final-checkpoint cp-only paired deltas span +0.0015 to +0.0041, with a
stdev of about 0.00135, across the existing three seeds. Roughly doubling the seed count, to six
total replicates (three new seed pairs on top of 42, 43, and 44, twelve new runs, on the order of
24 to 26 minutes of additional training wall-clock at the measured per-run rate), would be the
smallest step that plausibly sharpens the mean-delta estimate's precision without a qualitatively
larger commitment. It would not guarantee a decisive answer: three additional points is still a
small-n regime by this project's own standing caution about fragile point estimates. Any such
replication would also need its own paired noise-floor characterization, an M-1-style study built
from matched pairs sharing init and data order rather than independent single runs, to have a
calibrated null to compare against. Without that, the gap this document identifies in section 1
would simply recur one experiment later.

## 9. Learning log entry

```
## Experiment ID: DR-M2-e16-paired-variance-interpretation
Hypothesis:            E-16's decisive paired treatment-control cp-only deltas were judged against
                        DR-M1's between-run noise band as if that band were a calibrated null
                        distribution for the paired quantity. Verified directly against both
                        studies' implementations that they measure different random variables.
                        DR-M1's pairwise deltas come from three fully independent single runs with
                        no shared initialization or data order. E-16's paired deltas come from
                        matched pairs sharing a verified byte-identical starting parameter hash and
                        an identical data-shuffle permutation, differing only in Stage-3 row
                        content.
Independent variable:  None. This is a re-interpretation of already collected E-16/DR-M1 data, not
                        a new experiment. No training, no corpus generation.
Controlled variables:  N/A.
Observed outcome:      DR-M1's 0.001 to 0.008 band remains valid evidence of substantial raw
                        seed-to-seed variance, but is withdrawn as a null distribution for E-16's
                        paired deltas. E-16's final-checkpoint cp-only paired deltas (+0.00238,
                        +0.00150, +0.00414; mean +0.00267, stdev about 0.00135, 3/3 positive) and
                        selected-checkpoint deltas (mean +0.00386, stdev about 0.00125, 3/3
                        positive) are restated without a noise-band comparison. E-16's
                        classification stays null/inconclusive, now because no calibrated paired
                        null exists rather than because the deltas were shown to fall inside one.
                        The Stage-3 exploratory reversal (treatment worse on its own held-out set,
                        better on v1-clean) is recorded as an unresolved coverage/generalization
                        hypothesis. Recommended research-line decision: pause the seeded-diversity
                        Stage-3 line rather than immediately replicate. If replication is chosen
                        later, six total seed replicates (three new, on the existing frozen E-16
                        corpora) is the smallest defensible next step, offered as prospective design
                        guidance only.
```
