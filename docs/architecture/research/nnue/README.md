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
   noise-floor estimate.
4. *(future Phase 4 experiments — P4II, WDL blend, etc. — each get their own file here, added as
   they're run; this list is updated as new files land, not maintained separately.)*

## Conventions carried forward unchanged from the original document

These are established, load-bearing project conventions — restated here only as pointers, not
redefined, so they don't drift between files:

- **Experimental Protocol** (§26 of the main document): controlled-variable declarations, the
  metric hierarchy (correlation primary, calibration/RMSE/bias secondary, gauntlet/SPRT the only
  true validation), promotion criteria, early-termination criteria, the `<PhaseCode>[-<Tag>]-<NNN>`
  Experiment ID scheme (§26.5), and the Learning Log template (§26.6) — every new file in this
  folder uses these without redefining them.
- **Benchmark versioning** (§35.8): "v1" = the original 4,000-record held-out set; "v1-clean" =
  the same set minus 8 confirmed sentinel-value records (3,992 records). Never conflate the two
  without an explicit label.
- **Rolling reference model** (§26.10 of the main document, restated as of this folder's creation):
  **P1-G04 remains the reference model** (held-out correlation 0.5315 on v1 / 0.5931 on v1-clean)
  as of Phase 4A's close. Any promotion decision in a later file updates this line — check the
  most recent phase file's own "Decision" section for the current reference model rather than
  assuming this README is live-updated on every promotion.
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
