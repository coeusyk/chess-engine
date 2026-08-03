# NNUE retraining research — index

This folder holds the NNUE retraining roadmap's research and experiment documentation. It
replaces a single, continuously-appended file that had grown past 5,000 lines (47 sections) by
the end of Phase 4A — everything through Phase 4A's supervision-objective analysis stays in that
one file as the historical record (moved here unmodified, `git mv`, no content changed); **every
experiment from Phase 4 (P4I) onward gets its own file in this folder**, so no single document
needs to be read end-to-end to find one result.

## Reading order

1. **[2026-07-19-nnue-improvement-analysis.md](2026-07-19-nnue-improvement-analysis.md)** — the
   original governing document, §1-§47. Covers: established facts and the central anomaly (§1-§5),
   the sequenced experiment plan (§6-§7), the post-#206/#217 performance investigation and NPS/
   calibration diagnostics (§14-§23), the retraining roadmap and Experimental Protocol (§24, §26),
   Phase 1 (optimization, §27) through Phase 3 (label quality, §31-§35), Phase 3 closure and
   Phase 4 planning (§36-§39), and Phase 4A's supervision-objective analysis and `P4I` experiment
   design (§40-§47). **Still the source of truth for every section number cited below and in
   memory files** (e.g. "§27.2", "§35.9") — those citations are not repeated here.
2. **[phase4-p4i-k-sweep.md](phase4-p4i-k-sweep.md)** — Experiment P4I: coarse-to-fine retrain
   ablation of the texel-sigmoid loss's `K` parameter. Training report, metric comparison,
   adaptive decision, and recommendation. Result: low K clears 2σ but not 3σ — a
   threshold-contingent, not clean-null, result (see file for full disclosure).
3. **[phase4-p4i-replication.md](phase4-p4i-replication.md)** — P4I low-K reproducibility check:
   one additional model at the same K, a second training seed, measuring seed-to-seed variance
   under the *actual* Phase 4 configuration rather than reusing Phase 1's cross-configuration
   noise-floor estimate. Closes Phase 4A / K exploration: exhausted under the current supervision
   objective (§0 below), not disproven.
4. **[measurement-model.md](measurement-model.md)** — permanent Measurement Model appendix:
   every metric this roadmap uses, classified Primary/Secondary/Exploratory with purpose, expected
   variance, and interpretation guidance, derived from P4I/P4II's measured findings. Read this
   before interpreting any Phase 4 report's metrics table, not after.
5. **[phase4-p4ii-mate-weight.md](phase4-p4ii-mate-weight.md)** — Experiment P4II: mate-aware loss
   weighting (RQ-2, Lever B). A large apparent pooled-correlation gain that a subset-correlation
   check (this experiment's own methodology contribution, folded into `measurement-model.md` §4)
   showed to be a composition/leverage artifact, not a genuine improvement — not promotable.
6. **[phase4-p4iii-mate-target.md](phase4-p4iii-mate-target.md)** — Experiment P4III: mate-target
   representation (RQ-3, Lever B). An even larger apparent pooled-correlation gain that turned out
   to be a pure rubric-contamination artifact — the *identical, untrained* baseline model "gains"
   the same amount just from being graded on a redefined target. Under the correct same-rubric
   comparison, no learned effect on either the majority population or the intervention's own
   intended (mate-labeled) subset — not promotable. Closes Lever B's two-intervention arc (RQ-2 +
   RQ-3); `measurement-model.md` §10 records the cross-experiment synthesis.
7. **[phase4c-reranking-wdl-audit.md](phase4c-reranking-wdl-audit.md)** — Phase 4C: investigation,
   not a trained-model experiment. Checks whether P4II/P4III's accumulated evidence changes the
   remaining candidates' expected information gain before continuing on the original ordering.
   Finds a mechanistic argument down-ranking Huber/log-cosh (same `σ'(p,K)` saturation wall as the
   closed Lever-B pair) and up-ranks WDL blend (untouched by that mechanism) — and audits WDL's
   real data-availability prerequisites directly (Stockfish binary, actual source EPD file, actual
   production shard), finding the source data 100% populated and the existing corpus backfillable
   with zero re-labeling, substantially narrowing §43's original cost estimate. No implementation
   this turn — ranking/cost-estimate update only, pending review.
8. **[phase4-p4iv-wdl-blend.md](phase4-p4iv-wdl-blend.md)** — Experiment P4IV: WDL-blended training
   target (RQ-4, Lever C), the highest-ranked candidate per the Phase 4C audit above. A clean,
   single-rubric null result (the intervention never touches any evaluation path, so unlike P4II/
   P4III no rubric-decomposition was needed to interpret it): the majority-population metric
   (cp-only correlation) is not improved, slightly regressed at every reading — not promotable.
   Shard format extended to carry `wdl`; existing Stage 2 corpus backfilled via a pure FEN join
   (zero Stockfish re-execution). `measurement-model.md` §10.2 extends the cross-experiment
   synthesis to a third non-promotable intervention.
9. **[phase4-retrospective.md](phase4-retrospective.md)** — Phase 4 closure: per-experiment
   retrospective (P4I through P4IV, hypothesis/outcome/methodology-contribution/lessons for each),
   an intervention taxonomy spanning the whole roadmap (not just Phase 4), a precise per-class
   research-saturation assessment, and a summary of which Phase 4 infrastructure remains useful
   independent of any single experiment's outcome. Read this before starting any Phase 5 work.
10. **[phase5-roadmap.md](phase5-roadmap.md)** — Phase 5 planning: a reranked candidate list built
    from the retrospective's accumulated evidence (not the original roadmap's ordering), and a
    separate architectural-opportunities catalog (auxiliary heads, multi-task learning, alternative
    outputs, representation changes) identified but not designed in detail. Planning only — nothing
    in this file has been implemented.
11. **[phase5-rq5-label-noise-floor.md](phase5-rq5-label-noise-floor.md)** — Experiment RQ-5, Phase
    5's #1-ranked candidate: a label budget-sensitivity measurement (25k vs 50k Stockfish node
    budget, n=1,000), not a trained-model intervention — no trainer code, loss, target, dataset, or
    checkpoint touched. Result: median spread 13cp, landing in the pre-registered
    threshold-contingent band (10-25cp) — a real, disclosed ambiguous result, not resolved either
    way. Reconciles the original §32.5 duplicate-FEN finding as a distinct (unisolated) carryover
    mechanism, not the same thing this experiment measures.
12. **[phase5-p5-wdlalt-lambda.md](phase5-p5-wdlalt-lambda.md)** — Experiment P5-WDLALT, Phase 5's
    #2-ranked candidate: WDL blend at an alternate operating point (λ=0.8, a light outcome touch,
    vs P4IV's λ=0.5 midpoint) — resolves a self-contradiction in `phase5-roadmap.md`'s own text
    (disclosed, confirmed with the user, not resolved unilaterally). Result: cp-only correlation
    still regresses slightly (-0.0011, vs P4IV's -0.0037 at λ=0.5) — not promotable, but the
    dose-response pattern (~3.4x smaller regression at a lighter touch) is suggestive that the
    outcome signal itself carries no positive ranking information, not merely that λ=0.5 was too
    heavy a dose. RMSE/calibration flat at both checkpoints.
13. **[phase5-arch-scoping-auxiliary-head.md](phase5-arch-scoping-auxiliary-head.md)** — Phase 5's
    #3-ranked candidate: an architecture **scoping investigation** (not a trained-model experiment,
    per the roadmap's own definition of this candidate), covering the auxiliary WDL head / shared
    feature-transformer design. Finds the export path structurally immune to extra parameters —
    canonical and quantized arrays are bitwise identical with an auxiliary head attached, so the
    `.nnue` format and the whole Java inference side need zero changes. Corrects
    `phase5-roadmap.md`'s "Medium-high / ADR-001-adjacent" cost estimate for this candidate to
    **Low** (that rating belonged to the win-probability-native design, a different catalog item).
    Recommends **go**; deliberately does not implement.
14. **[phase5-p5-auxhead-design.md](phase5-p5-auxhead-design.md)** — P5-AUXHEAD-001's
    **pre-implementation design record**, written before any code: gradient flow, checkpoint
    layout, export behavior, inference behavior, strict-loading policy, rollback plan, and the
    measured derivation of the declared IV value. Kept separate from the results report so the
    design cannot be retro-fitted to the numbers.
15. **[phase5-p5-auxhead-multitask.md](phase5-p5-auxhead-multitask.md)** — Experiment
    P5-AUXHEAD-001: the auxiliary WDL head (multi-task) the #3 scoping pass recommended, now
    implemented and run. A training-only second head shares the feature transformer; the primary
    target stays pure `eval_cp` (structurally unlike the P4IV/P5-WDLALT blend family). **Not
    promotable** — fails all four §7 conditions (cp-only −0.0017/−0.0029, pooled −0.026, RMSE and
    calibration both regressed). Includes an auxiliary-task validity check showing the auxiliary
    head was fitted *worse than a constant predictor* while still carrying ranking signal, which
    makes this null **confounded**: it does not establish that outcome signal is unhelpful to the
    shared representation, only that this parameterization regressed. Recommends re-running with
    a properly conditioned auxiliary path before down-ranking the multi-task class.
16. **[phase5-auxhead-failure-diagnostics.md](phase5-auxhead-failure-diagnostics.md)** —
    P5-AUXHEAD-DIAG-001: a **read-only diagnostic investigation** (no training, no architecture
    change, no production module touched) into *why* P5-AUXHEAD-001's auxiliary task fitted worse
    than a constant predictor. Finds a clear bottleneck: the auxiliary head has no logit-scale
    control, so its logits inflate to ±39 and predictions polarize (76% in the extreme deciles,
    entropy −36%), which combined with 27.6% draw targets (`y=0.5`, irreducible BCE floor 0.191)
    destroys calibration while leaving ranking intact. Also measures that auxiliary gradients
    **dominated the backbone 2.5–5.3×** for the first 4,000 steps despite the 0.04 weight, and
    that primary/auxiliary backbone gradients are **near-orthogonal** (mean cos ≈ +0.02), not
    opposed. Rejects six hypotheses on evidence (class imbalance, prediction collapse, negative
    transfer, clipped-ReLU saturation, dead dimensions, intrinsic unlearnability). Recommends one
    minimal next experiment — normalize the auxiliary head's input — while stating explicitly that
    a primary-metric gain should not be expected from it.
17. *(future Phase 5 experiments each get their own file here, added as they're run; this list is
    updated as new files land, not maintained separately.)*

## 0. Roadmap status (updated after Phase 4 closure, 2026-07-21 — Phase 4 is now CLOSED)

**Phase 4 is closed.** P4I through P4IV are implemented, reviewed, merged, and documented; none
promoted a replacement for the reference model. See
**[phase4-retrospective.md](phase4-retrospective.md)** for the full retrospective and
**[phase5-roadmap.md](phase5-roadmap.md)** for the evidence-based Phase 5 candidate ranking. The
table below is retained as the per-experiment status record; it is not live-updated once a phase
closes — the retrospective is the current source of truth for synthesis-level claims.

| Research direction | Status |
|---|---|
| Optimization (Phase 1) | **✓ Closed** — P1-G04 promoted, remains the reference model |
| Stage 1 data-volume scaling (Experiments 2A/2B) | **✓ Closed** — falsified under fixed and proportional compute |
| Sentinel-value filtering (Phase 3 / Experiment 3A) | **✓ Closed** — confirmed a benchmark-composition artifact, not a model fix; adopted as permanent ingestion hygiene anyway |
| K exploration (P4I + replication) | **✓ Closed — exhausted under the current supervision objective, not disproven** (see below) |
| Mate-aware loss weighting (P4II, RQ-2) | **✓ Closed** — not promotable; pooled-correlation gain was a composition/leverage artifact (`phase4-p4ii-mate-weight.md`) |
| Mate-target representation (P4III, RQ-3) | **✓ Closed** — not promotable; pooled-correlation gain was a rubric-contamination artifact, no learned effect on majority or target subset (`phase4-p4iii-mate-target.md`) |
| Huber/log-cosh loss shape | Open, unexecuted — **reranked down** (Phase 4C audit): mechanistically predicted low value on the primary metric, same `σ'(p,K)` saturation wall as the closed Lever-B pair (`phase4c-reranking-wdl-audit.md` §2). Not started — deliberately not run this cycle, per this task's explicit "do not start Huber/log-cosh" instruction. |
| WDL blend, λ=0.5 (P4IV, Lever C) | **✓ Closed** — not promotable; majority-population (cp-only) correlation regressed slightly at every reading, no rubric-contamination confound (unlike P4II/P4III, this intervention never touches evaluation) — a clean, direct null (`phase4-p4iv-wdl-blend.md`) |
| Label budget-sensitivity (RQ-5, Phase 5 #1) | **✓ Closed — diagnostic, not an intervention.** Median 25k-vs-50k spread 13cp, threshold-contingent (10-25cp pre-registered band) — a disclosed ambiguous result (`phase5-rq5-label-noise-floor.md`) |
| WDL blend, λ=0.8 (P5-WDLALT, Phase 5 #2) | **✓ Closed** — not promotable; cp-only correlation regressed slightly (-0.0011), ~3.4x smaller than P4IV's λ=0.5 regression (-0.0037) — dose-response evidence suggestive that the outcome signal carries no positive ranking information for the majority population (`phase5-p5-wdlalt-lambda.md`) |
| Auxiliary WDL head (Phase 5 #3) | **✓ Scoping closed — investigation, not an intervention.** Export/quantization/Java-inference blast radius measured at **zero** (bitwise-identical arrays, regression-guarded by a committed test); roadmap cost estimate corrected Medium-high → **Low**. Recommends go; implementation not started (`phase5-arch-scoping-auxiliary-head.md`) |
| Auxiliary WDL head, `aux_wdl_weight=0.04` (P5-AUXHEAD-001) | **✓ Closed — not promotable**, fails all four §7 conditions (cp-only −0.0017/−0.0029, pooled −0.026, RMSE +9.1/+37.7, calibration regressed). **Null is confounded**: the auxiliary head itself fitted worse than a constant predictor (BCE 0.74–1.16 vs 0.68) while still carrying ranking signal (corr ≈0.54–0.60), so this does *not* establish that outcome signal is unhelpful to the shared representation. Multi-task class **not** closed — re-run with a conditioned auxiliary path first (`phase5-p5-auxhead-multitask.md`) |
| Auxiliary-head failure diagnostics (P5-AUXHEAD-DIAG-001) | **✓ Closed — read-only investigation, bottleneck identified.** Cause of the confound: unbounded auxiliary logit scale (±39, 76% of predictions in extreme deciles) interacting with 27.6% draw targets; auxiliary gradients also dominated the backbone 2.5–5.3× early despite the 0.04 weight, and are near-orthogonal (cos ≈ +0.02) to the primary gradient — not opposed. Six hypotheses rejected on evidence. Recommends one minimal follow-up (normalize auxiliary input), with the caveat that a primary-metric gain is *not* expected (`phase5-auxhead-failure-diagnostics.md`) |

**Remaining Phase 4 work is entirely supervision-objective territory** — every lever outside
loss/target formulation (optimization, data volume, label-outlier cleaning, the incumbent
objective's own `K` parameter, and every Lever-B/C intervention tried so far) has been tried and
closed. `measurement-model.md` §10 records a cross-experiment synthesis, now covering three
independent, structurally different interventions: P4II (loss weighting), P4III (target
reformulation), and P4IV (target-source blend) each failed to produce a *promotable* effect on the
majority population. P4II/P4III share a demonstrated mechanism (the `σ'(p,K)` saturation wall,
§10.1); P4IV's null is **not** explained by that same mechanism (its majority-population records
were never in the saturated zone) and its own cause remains unestablished (§10.2) — a genuinely
different kind of null, not a repeat of the first two. **The ranking was revisited before P4IV ran**
(`phase4c-reranking-wdl-audit.md`, 2026-07-21, per that task's explicit "don't auto-continue on the
original ordering" instruction): the `σ'(p,K)` mechanism was used to predict Huber/log-cosh (as
originally scoped, atop the incumbent sigmoid objective) would likely hit the same wall, reranking
WDL blend above it — WDL blend was then run and closed not-promotable. Huber/log-cosh remains
unexecuted, deliberately not started this cycle.

**K exploration closure, stated precisely (per this task's explicit language requirement)**: K
retraining changes optimization behavior (P4I: three genuinely different models trained, not an
affine transform of one). K retraining can produce a small, reproducible improvement in v1-clean
correlation (P4I's replication: +0.004, reproduced at a second seed with seed-to-seed spread an
order of magnitude below the effect — `phase4-p4i-replication.md` §6.1). That improvement is too
small, and too uncorroborated by any other metric at this sample size, to justify further
optimization effort along this axis. Production K remains **2.773456**. K exploration is
**exhausted under the current supervision objective and architecture** — not disproven, not
falsified, not shown to have no effect. A future objective change (a different target
representation, a different loss family) may reopen K as a *dependent* variable requiring
re-tuning once that change lands — it is not, on the evidence gathered so far, an independent
research direction worth pursuing further on its own.

## Conventions carried forward unchanged from the original document

These are established, load-bearing project conventions — restated here only as pointers, not
redefined, so they don't drift between files:

- **Experimental Protocol** (§26 of the main document): controlled-variable declarations,
  early-termination criteria, the `<PhaseCode>[-<Tag>]-<NNN>` Experiment ID scheme (§26.5), and the
  Learning Log template (§26.6) — every new file in this folder uses these without redefining them.
  **Metric hierarchy and promotion rule superseded**: §26's original "correlation primary,
  calibration/RMSE/bias secondary" statement, and every prior version of the promotion rule in
  this folder's own files, are superseded by [`measurement-model.md`](measurement-model.md)'s
  current, permanent versions — §1/§1a's per-metric classification (pooled v1-clean correlation as
  screening only; cp-only correlation as the primary majority-population metric; mate-only
  correlation as mechanism-validation only; RMSE/calibration as regression guards; compression/
  bucket MAEs/histograms as exploratory) and §6/§7's majority-population and promotion rules. Use
  that file, not this bullet or any single experiment's own report, as the current source of truth
  for metric classification and promotion criteria.
- **Benchmark versioning** (§35.8): "v1" = the original 4,000-record held-out set; "v1-clean" =
  the same set minus 8 confirmed sentinel-value records (3,992 records). Never conflate the two
  without an explicit label.
- **Rolling reference model** (§26.10 of the main document, restated after P4IV): **P1-G04 remains
  the reference model** (held-out correlation 0.5315 on v1 / 0.5931 on v1-clean, reused via
  `P3A-001`'s checkpoint throughout P4I/P4II/P4III/P4IV). None of P4I, P4II, P4III, or P4IV promoted
  a replacement. Any promotion decision in a later file updates this line — check the most recent
  phase file's own "Decision" section for the current reference model rather than assuming this
  README is live-updated on every promotion.
- **Graphify-first discovery**: `graphify . --update` (or `--code-only` when no LLM key is
  configured) before reading implementation files for a new phase/experiment, and a validation
  pass after — established across every phase in the main document (§31/§34/§35.1/§35.10/§40/§47),
  continued in every file here.

## Why this split, and what wasn't done

This reorganization is a **relocation and a going-forward convention change, not a retroactive
rewrite**: the original document's internal `§N` numbering, cross-references, and content are
completely unchanged — the file was moved, not edited, so it remains a stable citation target
(a link like "§35.9" still means the same thing it always did). Splitting the *existing* 47
sections into multiple files (so that, e.g., Phase 1's report and Phase 3's report were separate
documents) was considered and deliberately not done in this pass — it would require converting
every internal `§N` cross-reference into a cross-file link, a large, error-prone rewrite of already
-committed, heavily-cross-referenced content, orthogonal to the actual experiment this reorganization
accompanied (P4I). If retroactively splitting the historical document is wanted later, it should be
its own explicit, reviewed task — not bundled silently into an experiment's own documentation.
