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
8. *(future Phase 4 experiments — Huber/log-cosh, WDL blend, etc. — each get their own file here,
   added as they're run; this list is updated as new files land, not maintained separately.)*

## 0. Roadmap status (updated after the Phase 4C reranking audit, 2026-07-21)

| Research direction | Status |
|---|---|
| Optimization (Phase 1) | **✓ Closed** — P1-G04 promoted, remains the reference model |
| Stage 1 data-volume scaling (Experiments 2A/2B) | **✓ Closed** — falsified under fixed and proportional compute |
| Sentinel-value filtering (Phase 3 / Experiment 3A) | **✓ Closed** — confirmed a benchmark-composition artifact, not a model fix; adopted as permanent ingestion hygiene anyway |
| K exploration (P4I + replication) | **✓ Closed — exhausted under the current supervision objective, not disproven** (see below) |
| Mate-aware loss weighting (P4II, RQ-2) | **✓ Closed** — not promotable; pooled-correlation gain was a composition/leverage artifact (`phase4-p4ii-mate-weight.md`) |
| Mate-target representation (P4III, RQ-3) | **✓ Closed** — not promotable; pooled-correlation gain was a rubric-contamination artifact, no learned effect on majority or target subset (`phase4-p4iii-mate-target.md`) |
| Huber/log-cosh loss shape | Open, unexecuted — **reranked down** (Phase 4C audit): mechanistically predicted low value on the primary metric, same `σ'(p,K)` saturation wall as the closed Lever-B pair (`phase4c-reranking-wdl-audit.md` §2) |
| WDL blend (Lever C) | Open, unexecuted — **reranked up**, now the highest-expected-value remaining candidate: the only one whose mechanism is untouched by the demonstrated saturation wall, and its data-availability risk (§40.2's flagged unknown) is now resolved — source data 100% populated, existing corpus backfillable with zero re-labeling, engineering cost narrowed from Medium-high to Medium (`phase4c-reranking-wdl-audit.md` §5, §7) |

**Remaining Phase 4 work is entirely supervision-objective territory** — every lever outside
loss/target formulation (optimization, data volume, label-outlier cleaning, the incumbent
objective's own `K` parameter, and now both Lever-B mate-specific interventions) has been tried and
closed. `measurement-model.md` §10 records a cross-experiment synthesis: P4II (loss weighting) and
P4III (target representation) both failed to produce a *promotable* effect — in both, the majority
(cp-labeled) population was unmoved and any mate-subset movement was small and non-promotable
(P4II's own mate-subset correlation did move modestly but only via a majority-flat, pooled-leverage
artifact; P4III's mate-subset movement, +0.0031, was flat within noise). **This ranking has now
been revisited** (`phase4c-reranking-wdl-audit.md`, 2026-07-21, per this task's explicit "don't
auto-continue on the original ordering" instruction): the same `σ'(p,K)` mechanism generalizes to
predict Huber/log-cosh (as originally scoped, atop the incumbent sigmoid objective) will hit the
same wall, while WDL blend's target-source-substitution mechanism is untouched by it — reranking
WDL blend above Huber/log-cosh among the remaining candidates. Neither is implemented yet; this is
a ranking and cost-estimate update, pending review before either is scheduled.

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
- **Rolling reference model** (§26.10 of the main document, restated after P4III): **P1-G04 remains
  the reference model** (held-out correlation 0.5315 on v1 / 0.5931 on v1-clean, reused via
  `P3A-001`'s checkpoint throughout P4I/P4II/P4III). None of P4I, P4II, or P4III promoted a
  replacement. Any promotion decision in a later file updates this line — check the most recent
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
