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
6. *(future Phase 4 experiments — RQ-3, WDL blend, etc. — each get their own file here, added as
   they're run; this list is updated as new files land, not maintained separately.)*

## 0. Roadmap status (updated after Experiment P4II, 2026-07-21)

| Research direction | Status |
|---|---|
| Optimization (Phase 1) | **✓ Closed** — P1-G04 promoted, remains the reference model |
| Stage 1 data-volume scaling (Experiments 2A/2B) | **✓ Closed** — falsified under fixed and proportional compute |
| Sentinel-value filtering (Phase 3 / Experiment 3A) | **✓ Closed** — confirmed a benchmark-composition artifact, not a model fix; adopted as permanent ingestion hygiene anyway |
| K exploration (P4I + replication) | **✓ Closed — exhausted under the current supervision objective, not disproven** (see below) |
| Mate-aware loss weighting (P4II, RQ-2) | **✓ Closed** — not promotable; pooled-correlation gain was a composition artifact (`phase4-p4ii-mate-weight.md`) |
| Mate-target representation (RQ-3) | Open, unexecuted — natural next Lever-B candidate per the existing sequencing, not begun (this task's explicit stop condition) |
| Huber/log-cosh loss shape | Open, unexecuted |
| WDL blend (Lever C) | Open, unexecuted — the only candidate with a plausible mechanism to move correlation via target-source substitution rather than gradient-reshaping; still highest-ceiling, still requires the §40.2 data prerequisites |

**Remaining Phase 4 work is entirely supervision-objective territory** — every lever outside
loss/target formulation (optimization, data volume, label-outlier cleaning, and now the incumbent
objective's own `K` parameter) has been tried and closed.

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

- **Experimental Protocol** (§26 of the main document): controlled-variable declarations, promotion
  criteria, early-termination criteria, the `<PhaseCode>[-<Tag>]-<NNN>` Experiment ID scheme
  (§26.5), and the Learning Log template (§26.6) — every new file in this folder uses these without
  redefining them. **Metric hierarchy superseded**: §26's original "correlation primary,
  calibration/RMSE/bias secondary" statement is superseded by
  [`measurement-model.md`](measurement-model.md)'s per-metric classification (v1-clean correlation
  specifically as Primary; v1 correlation/RMSE/calibration as Secondary; mate/cp compression,
  magnitude-bucket MAEs, and histograms as Exploratory) — use that file's table, not this bullet,
  when classifying a metric.
- **Benchmark versioning** (§35.8): "v1" = the original 4,000-record held-out set; "v1-clean" =
  the same set minus 8 confirmed sentinel-value records (3,992 records). Never conflate the two
  without an explicit label.
- **Rolling reference model** (§26.10 of the main document, restated after P4II): **P1-G04 remains
  the reference model** (held-out correlation 0.5315 on v1 / 0.5931 on v1-clean, reused via
  `P3A-001`'s checkpoint throughout P4I/P4II). Neither P4I nor P4II promoted a replacement. Any
  promotion decision in a later file updates this line — check the most recent phase file's own
  "Decision" section for the current reference model rather than assuming this README is
  live-updated on every promotion.
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
