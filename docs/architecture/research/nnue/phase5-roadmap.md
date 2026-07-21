# Phase 5 research roadmap

Rebuilt from the evidence in **[phase4-retrospective.md](phase4-retrospective.md)**, not from the
original document's priority ordering. Where a candidate's rank changed from what the original
roadmap implied, the rationale says so explicitly. This is a planning document — nothing here has
been implemented; every ranking is a recommendation pending review, consistent with this
project's "disclose, don't resolve unilaterally" convention used throughout Phase 4.

## Deliverable 5 — Phase 5 candidate ranking

Ranked by expected information gain, implementation cost, engineering risk, and likelihood of
changing the reference model (P1-G04). These four axes don't always point the same direction —
each candidate's rationale says explicitly where they diverge.

### 1. Label-noise floor (RQ-5 / Experiment 5 — measurement, not an intervention)

- **What it is:** re-label a fixed sample of positions at two different Stockfish node budgets and
  measure the resulting eval spread — establishing how much of the ~0.59 v1-clean correlation
  ceiling is bounded by the labeler's own search-variance noise, independent of any model or
  objective choice.
- **Expected information gain: High.** Three independent model-side interventions (P4II, P4III,
  P4IV) have now all failed to move the majority-population metric. That raises — more urgently
  than when this candidate was first scoped — the question this candidate directly answers: is the
  correlation ceiling set by label noise (a data property no loss/target change can fix), or is it
  set by something reachable through further objective or architecture work? Every remaining
  candidate's expected value is conditioned on this answer.
- **Implementation cost: Low.** No model training changes required — a re-labeling pass plus a
  variance calculation. This is the cheapest candidate in the entire Phase 5 list.
- **Engineering risk: Low.** No production code changes; reuses the existing Stockfish labeling
  pipeline.
- **Likelihood of changing the reference model: None — by design.** This is a diagnostic
  measurement, not a trained-model experiment. It cannot itself promote a replacement for P1-G04;
  its value is entirely in how it reshapes the expected value of every *other* candidate below.
  Do not expect this line to produce a "promotable" outcome — the outcome is a number
  (the noise floor), not a decision.
- **Why the rank changed:** this candidate is not new — it was already present in the original
  roadmap (main document §6 Exp 5, §39 RQ-5) as prerequisite context for interpreting RQ-1–RQ-4,
  and has never been run at any phase. What changed is the evidence: with three model-side
  interventions now closed not-promotable, understanding whether the plateau is data-bound or
  model-bound has become the single highest-value open question, not a background prerequisite to
  defer indefinitely.

### 2. WDL blend, alternate operating point

- **What it is:** re-run the now-fully-built WDL pipeline at a different `wdl_lambda` (e.g., a
  much smaller value such as 0.1–0.2) or restricted to high-confidence/decisive game outcomes
  only, rather than the λ=0.5 midpoint P4IV tested.
- **Expected information gain: Medium.** P4IV's null was clean (no rubric-contamination or
  leverage confound) but is a single point on a continuum; it doesn't establish that every
  operating point in this family fails, only that the midpoint does. A small-λ or
  confidence-restricted point is a materially different test, analogous to how P4II tested a
  moderate rather than an extreme `mate_weight`.
- **Implementation cost: Very low.** All infrastructure (shard format, backfill tooling,
  `wdl_lambda` field, sign-convention tests) already exists and is verified correct — this is a
  single `train()` call at a different config value, not new engineering.
- **Engineering risk: Low.** No new code paths; reuses P4IV's already-tested implementation.
- **Likelihood of changing the reference model: Low-medium.** Tempered by the existing null, but
  a genuinely different operating point in an already-built, cheap-to-test pipeline is worth the
  low cost of finding out.
- **Why the rank changed:** before P4IV, WDL blending as a class required building a shard-format
  extension, a backfill tool, and a blend mechanism from scratch — real, non-trivial engineering
  cost. That cost is now sunk and the infrastructure is proven correct, which is why testing a
  *different* point in this family is now one of the cheapest candidates available, not a new
  medium-cost engineering unit.

### 3. Architecture-level scoping (design investigation, not implementation)

- **What it is:** per Deliverable 6 below, a scoping investigation into auxiliary-head / multi-task
  designs — specifically, whether WDL signal can improve the shared representation via a separate
  loss term without being blended into the primary target the way P4IV did.
- **Expected information gain: High, as a class.** `measurement-model.md` §10's own synthesis
  states that three same-family interventions (loss reweighting, target reformulation, target-source
  blend — all modifications to the *existing* scalar objective) have failed. That is real evidence
  the next lever worth investigating is architecture-level, not another variation within the same
  family.
- **Implementation cost / engineering risk: Medium-high** for full implementation — new output
  shape, export-path changes, ADR-001-adjacent scope. This is why the recommendation here is a
  **scoping/design investigation first**, not full implementation: understand the interface and
  blast radius (following the architecture-review skill's axes — coupling, module boundaries,
  export/inference impact) before committing engineering time.
- **Likelihood of changing the reference model: Unknown — this is exactly what makes it worth
  scoping.** No project-specific evidence exists either way; the value of the scoping step is
  turning "unknown" into an informed estimate before the higher-cost implementation step is taken.
- **Reconciliation with Deliverable 6:** Deliverable 6 below calls architecture "the most
  evidence-motivated next *class* of intervention" — that claim is about which class the evidence
  points to, not about immediate priority. Ranked #3 here, below RQ-5 and the WDL alternate-point
  test, specifically because of cost/risk: both higher-ranked candidates answer real open questions
  at near-zero incremental engineering cost, while architecture work carries meaningfully higher
  cost and risk even at the scoping stage. The evidence *motivates* architecture as the next
  frontier; the cost profile says test the cheap things first.

### 4. Huber/log-cosh loss shape (as originally scoped, atop the incumbent objective)

- **What it is:** replace the texel-sigmoid MSE with a Huber or log-cosh loss applied in
  sigmoid-probability space, atop the existing objective — the original next-in-line candidate
  before the Phase 4C audit reranked it.
- **Expected information gain: Low-medium**, unchanged from the Phase 4C audit's own conclusion.
  The audit's chain-rule argument (`dL/dp = Huber'(σ(p,K)−σ(t,K))·σ'(p,K)`) predicts this
  candidate inherits the same `σ'(p,K)` saturation wall that made both P4II and P4III fail on the
  majority population, plus Huber ≡ MSE within its δ-radius (so it changes nothing for records
  already inside that radius, which is most of the cp-labeled majority).
- **Implementation cost: Low.** A loss-function swap with no data-pipeline or format changes —
  cheaper than the architecture candidate, comparable to the WDL alternate-point test.
- **Engineering risk: Low.**
- **Likelihood of changing the reference model: Low**, per the mechanistic prediction — but this
  prediction has never been empirically tested. If run, its primary value is closing out the
  "loss-shape-in-probability-space" question with an actual data point instead of a prediction,
  not because anyone expects it to promote a replacement.
- **Why the rank stays low:** this is the audit's own conclusion, reaffirmed here with no new
  evidence since — nothing in P4IV changes the argument, since P4IV tested a target-source change,
  not a loss-shape change, and doesn't bear on the saturation-wall mechanism either way.

### 5. Huber/log-cosh in raw-cp-space (a distinct, never-scoped candidate)

- **What it is:** apply Huber/log-cosh directly to the *cp residual* rather than atop the
  sigmoid-transformed target — a fundamentally different construction from #4, since it escapes
  the `σ'(p,K)` chain-rule argument by construction (no sigmoid transform in the loss at all).
  Flagged as a real candidate by the Phase 4C audit but never scoped in detail.
- **Expected information gain: Medium**, specifically because it is *not* subject to the same
  mechanistic down-ranking as #4 — this is a genuinely different intervention that happens to share
  a loss-family name.
- **Implementation cost: Medium.** Combines two changes at once (loss family + loss space) relative
  to the incumbent objective, which this project's one-variable-at-a-time discipline (see
  `measurement-model.md` §9) would require a deliberate, declared justification to combine rather
  than split into two experiments.
- **Engineering risk: Low-medium.** No data-pipeline changes, but raw-cp-space losses interact with
  the unbounded magnitude of mate-labeled cp targets differently than sigmoid-space losses do —
  needs explicit scoping before implementation, not assumed to be a drop-in loss swap.
- **Likelihood of changing the reference model: Unclear — genuinely untested territory**, unlike
  #4 where a specific mechanism predicts a low likelihood.
- **Why ranked below #2–#4 despite comparable information gain:** never scoped, so its true
  implementation cost isn't yet known with confidence — the medium-cost estimate above is
  provisional. Worth a scoping pass before a full ranking commitment, not worth jumping ahead of
  cheaper, already-scoped candidates.

### 6. Ranking losses (pairwise / listwise objectives)

- **What it is:** replace the pointwise regression objective with a ranking-based loss that
  directly targets correlation/ordering rather than a magnitude proxy.
- **Expected information gain: Potentially high (highest ceiling of any candidate in this list)**,
  since it targets the actual metric this roadmap has used as its primary success criterion
  (correlation) rather than a proxy for it.
- **Implementation cost: High.** Requires new pairing/sampling infrastructure that does not exist
  anywhere in this pipeline — record-pair or list construction, a different batch structure, likely
  a different training loop shape. Nothing in Phase 1–4's infrastructure is reusable here.
- **Engineering risk: Medium-high.** Genuinely new territory for this codebase; no prior
  correctness scaffolding (sign-convention tests, split-preservation checks) exists yet for this
  family.
- **Likelihood of changing the reference model: Unknown**, no empirical grounding in this project
  at all.
- **Why ranked low for this cycle despite the highest ceiling:** cost and risk are both
  substantially higher than every candidate above, and zero scoping work has been done. Flagged
  explicitly as the highest-ceiling unexplored idea worth a dedicated future investigation, not
  dismissed — just not the next thing to build given cheaper, better-understood options are
  available.

### 7. Multi-head architecture / win-probability-native output (full implementation)

Full implementation-level treatment is out of scope here — see Deliverable 6. Ranked lowest for
immediate action because it is ADR-001-class in blast radius (breaks every downstream cp-scale
consumer: search margins, calibration, export, quantization, Java inference) and no evidence from
this roadmap changes that assessment. Unchanged from where the original roadmap ranked it.

## Deliverable 6 — Architectural opportunities

Separated deliberately from the optimization-research candidates above: these ideas change what
the model *is*, not how the existing scalar objective is trained. Per the task's own scope, these
are identified as future research directions, not designed in detail.

- **Auxiliary head with WDL as an auxiliary loss (not blended into the primary target).** A second
  output head trained on `wdl` directly (e.g. binary cross-entropy), sharing the feature-transformer
  layer with the primary cp-output head, but only the primary head is exported/used at inference.
  This is structurally different from P4IV: P4IV blended `wdl` *into* the one target the model is
  graded on and found no improvement; an auxiliary head instead asks whether outcome signal can
  improve the *shared representation* through a separate gradient path, without ever changing what
  the primary head is scored against. Justified by: (a) P4IV ruling out the blend-into-target
  approach specifically, not the auxiliary-signal idea generally; (b) `measurement-model.md` §10's
  synthesis pointing at architecture as the next lever after three same-family failures; (c) the
  WDL data infrastructure Phase 4 already built (shard format, backfilled corpus) is directly
  reusable for this, at zero additional data-engineering cost. Not designed further here — this is
  exactly the candidate #3 above recommends scoping first.
- **Multi-task learning generally** (a broader class than the WDL-auxiliary case above) — any
  second objective sharing the feature-transformer layer, e.g. an auxiliary phase-classification
  head or an auxiliary mate-distance-prediction head. No project-specific evidence exists for or
  against this broader class; flagged as a category worth a dedicated design investigation of its
  own, separate from the WDL-specific case.
- **Win-probability-native output** (the network's own output *is* a probability, with no external
  cp-scale sigmoid applied downstream) — the most invasive candidate in this catalog. It breaks
  every existing cp-scale consumer (search margins, calibration reporting, export format,
  quantization bounds, Java inference) and is ADR-001-class in scope. Not justified as a near-term
  Phase 5 candidate given that cost; named here as the "ceiling" option if every cheaper avenue is
  exhausted, not as something to schedule.
- **Feature-representation change** (revisiting the plain-768, non-king-relative feature encoding
  itself, flagged in the original document §38.4 as an unmet ADR-001 revisit condition) — completely
  untested at any phase of this roadmap, and orthogonal to every loss/target intervention tried in
  Phase 4. Worth naming because it offers an alternative explanation for the same plateau that
  motivates the label-noise-floor candidate (#1 above): if the ceiling isn't label-noise-bound, a
  representation limit is at least as plausible an explanation as an objective-design limit, and
  three failed objective-side interventions are circumstantial evidence for widening the search
  beyond the objective. Recommend checking the label-noise floor first (#1) — it's far cheaper and
  could independently explain the same plateau, making a costly representation change either better
  motivated or unnecessary depending on the outcome.

## Summary: candidate ranking at a glance

| Rank | Candidate | Info gain | Cost | Risk | Chance of changing reference model |
|---|---|---|---|---|---|
| 1 | Label-noise floor (RQ-5) | High | Low | Low | None — diagnostic only, not an intervention |
| 2 | WDL blend, alternate operating point | Medium | Very low | Low | Low-medium |
| 3 | Architecture scoping (auxiliary head) | High (as a class) | Medium-high (full impl.) | Medium-high (full impl.) | Unknown — the point of scoping |
| 4 | Huber/log-cosh, atop incumbent (as scoped) | Low-medium | Low | Low | Low |
| 5 | Huber/log-cosh, raw-cp-space (unscoped) | Medium | Medium (provisional) | Low-medium | Unclear |
| 6 | Ranking losses | Potentially high | High | Medium-high | Unknown |
| 7 | Multi-head / win-probability-native (full impl.) | Unknown | Very high | High | Unknown |

This ranking is a recommendation pending review — nothing above has been implemented, and per the
governing task's stop condition, Phase 5 implementation does not begin from this document alone.
