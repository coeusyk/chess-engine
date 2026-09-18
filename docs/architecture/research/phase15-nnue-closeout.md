# Phase 15 (NNUE self-play/Stage-3) closeout

Branch: `phase/15-nnue`. This document reconciles the self-play/Stage-3 portion of Phase 15's
research record into one place: which issues built durable production infrastructure, which
built research-only tooling, and what the two retraining experiments this infrastructure enabled
actually found. It closes no open work; every issue and experiment covered here is already closed.
It does not define Phase 16.

## 1. Issue state, verified

Every issue tagged `[P15]` is closed. A search for open Phase 15 issues returns zero results.
`#223` (E-15) and `#224` (E-16), the two retraining experiments, are both closed. Nothing is
reopened by this document.

## 2. The self-play/Stage-3 infrastructure chain

Nine issues built the pipeline that made the two retraining experiments possible, in dependency
order:

- **#209 (E-9, self-play mode contract)**: the semantic contract for what a self-play game is,
  how search feeds it, and how a position/label pair gets sampled. Stayed a design document; no
  implementation landed against it directly. On-trajectory sampling is the default; an
  off-trajectory design is explicitly named as future, undecided work, not started.
- **#220 (VSPR wire format)**: the versioned Vex Self-Play Record byte layout, split out of #209
  once a second, Python-side decoder needed to exist alongside the Java one. Closed as a golden-
  fixture spec only, with no serializer or decoder code written against it at that point. The
  actual Java and Python codecs were built afterward under #211 and #210, and both are exercised
  and passing as of this closeout (`VsprCodecTest`, 21 of 21; the Python golden-fixture decoder
  tests, 25 of 25).
- **#211 (fixtures and codec cross-check)**: the checked-in golden fixtures and the deterministic
  Java/Python round-trip tests that hold #220's format contract in place on every PR.
- **#207 (shard versioning)**: versioned `SHARD_DTYPE` so `game_id` could be added without
  silently changing the on-disk record stride, plus a non-destructive legacy-format migration
  path and `PositionMetadata.game_id` typed as `Optional[int]`.
- **#210 (ingestion)**: `selfplay_ingest.py` and `SelfPlayProvider`, decoding VSPR per #220's
  spec, bounded to one frame and one write batch in memory at a time, with a dataset-generation
  manifest carrying provenance, checksums, and an assessment field. Grouped train/held-out
  splitting by game, not by row, landed here as `split_by_game()`.
- **#212 (generator-selection ADR)**: separated generator eligibility (does network N get used to
  produce Stage-3 data) from release promotion (does network N ship), and pinned the bootstrap
  generator identity. A later correction fixed a naming conflation between two distinct trained
  artifacts, `P1-G04` and `P3A-001`, caught before any generation ran under the wrong identity.
- **#221 (bounded generator CLI)**: the first real implementation issue in the chain, an
  operator-driven `GameLoop` and CLI that require an explicit generator identity (no
  newest-checkpoint fallback), enforce a mandatory finite game cap, and run an eligibility smoke
  check before generation. Its first pilot produced 3 byte-identical games out of 3, proving the
  generation-to-ingestion plumbing worked end to end without yet proving the data was useful.
- **#208 (missing-signal policy)**: decided what a WDL-only record's evaluation target should be
  when `wdl_lambda > 0`, rather than silently synthesizing one. `TrainingConfig.wdl_lambda`'s
  blend already existed; this issue closed the one real open decision left around it.
- **#222 (E-14, seeded diversity + second pilot)**: added `SeededDiversitySelector`, a bounded,
  reproducible move-diversity mechanism, and ran a second pilot showing it produces non-degenerate
  trajectories (median 0cp cost, p90 17cp) instead of #221's byte-identical games. Two real defects
  in the existing #221 code, a candidate-adapter under-width bug and a seed collision, were found
  and fixed here, the first time that code path was actually exercised under variation.

## 3. Durable production infrastructure versus research-only components

**Durable, kept regardless of any experiment's outcome**: the VSPR wire format and its golden
fixtures (#220, #211), the versioned shard format and its migration path (#207), the ingestion
pipeline and grouped train/held-out splitting (#210), the eligibility/provenance contract and CLI
(#221), and the WDL missing-signal policy (#208). None of these depend on `SeededDiversitySelector`
being useful; they are the same plumbing a different Stage-3 mechanism would also need.

**Research-only, tied to the specific mechanism under test**: `SeededDiversitySelector` itself
(#222), the shared-opening-prefix pairing design and its held-out-split hardening
(`DR-E16-shared-opening-prefix-preregistration.md`), and the two frozen E-15/E-16 corpora. These
exist to test one hypothesis, not to ship.

## 4. The two retraining experiments

**E-15 (`#223`, `DR-E15-phase-e-evaluation-report.md`)**: the first controlled Stage-3 corpus and
retraining experiment. Classified **null/inconclusive**. Its control arm, `BestMoveSelector` run
deterministically from the standard starting position every game, collapsed to one repeated
140-ply trajectory across all 58 games, a design flaw independent of the classification: the
control never independently tested anything, since its held-out set overlapped its own training
data completely.

**DR-M1 (`DR-M1-cp-only-noise-floor-characterization.md`)**: a small follow-up that measured
cp-only correlation's raw between-seed variance for the first time at this training configuration,
using three independent single-arm runs (no Stage-3 data, no pairing). Found a spread on the order
of 0.001 to 0.008. This remains exactly what it was measured as: a characterization of variance
between unrelated single runs, nothing more.

**E-16 (`#224`, `DR-E16-phase-e-evaluation-report.md`)**: fixed E-15's degenerate control by giving
both arms the same 58-opening shared prefix before diverging, and ran three seed-matched pairs
instead of one. Classified **null/inconclusive**. The paired treatment-control cp-only deltas were
consistently positive (3 of 3, both checkpoint kinds) but small: final-checkpoint deltas of
+0.00238, +0.00150, and +0.00414 (mean +0.00267). Regression guards (RMSE, bias, mate-only
correlation) held cleanly throughout. A Stage-3 exploratory reading, evaluated on each arm's own
held-out opening set, ran the opposite direction (treatment consistently worse there), recorded as
an open hypothesis, not used to override the classification.

**DR-M2 (`DR-M2-e16-paired-variance-interpretation.md`)**: corrects how E-16's Phase E report
compared its paired deltas against DR-M1's band. DR-M1's spread comes from three fully independent
single runs; E-16's paired deltas come from matched pairs sharing a verified byte-identical
starting parameter hash and an identical data-shuffle order, and are a different random variable.
DR-M2 withdraws the "inside DR-M1's band, so indistinguishable from noise" framing without
changing any metric or E-16's classification. **The corrected reading is not that E-16 is
inconclusive because it lacks a separate paired null noise floor.** It is that three matched pairs
give only a weak estimate of the distribution of `D_s = metric(treatment_s) - metric(control_s)`:
consistently signed, small, and not enough on its own to establish whether the effect is real.

## 5. Final dispositions

- **E-15 and E-16 are both null/inconclusive.** Neither promotes `SeededDiversitySelector`, and
  neither shows a regression against it.
- **DR-M1 remains a raw between-seed characterization only.** It was never a calibrated null for
  E-16's paired comparison, and DR-M2 exists specifically to correct the one place that distinction
  was blurred.
- **DR-M2 supersedes the paired-versus-unpaired interpretation** in
  `DR-E16-phase-e-evaluation-report.md` section 5. That report's own metric values are unchanged
  and remain correct; only the noise-band comparison sentence they were read alongside was
  miscalibrated.
- **The `SeededDiversitySelector` Stage-3 line is paused.** Not abandoned, not promoted: paused,
  pending either more matched-seed evidence on the existing frozen corpora or a specific, costed
  reason to pursue corpus-level replication (`DR-M2` sections 7 and 8).
- **Durable artifacts and contracts** (section 3 above) stay in the codebase regardless of this
  pause; they are general Stage-3 plumbing, not artifacts of this one mechanism.
- **Nothing here is promoted to production.** No NNUE checkpoint from either experiment ships; the
  existing promoted network is untouched by this entire chain.

## 6. Deferred and non-promoted items (technical debt, not new Phase-15 scope)

Recorded here for visibility, not opened as new work:

- **Ingestion resume mode**: `selfplay_ingest.py` explicitly refuses `--resume` rather than
  half-implementing it (module docstring, #210). A correct resume mode would need to reload a
  prior run's exact game-ID mapping state and verify new input doesn't redefine an
  already-committed game differently; not built.
- **Off-trajectory sampling**: `#209`'s contract states on-trajectory sampling is the initial
  design and that an off-trajectory design needs its own, separately decided semantics for what
  evaluation and outcome mean. Not decided, not started.
- **Full promotion-grade SPRT gate for generator eligibility**: `#212` deferred adopting
  "unconditionally use the latest candidate network for self-play" as settled policy, in favor of
  a narrower eligibility check. A fuller policy remains undecided.
- **Color-mirroring transform for the shared opening pool**: specified precisely in
  `DR-E16-shared-opening-prefix-preregistration.md` (rank flip only, color-remapped castling
  rights, rank-transformed en passant) but never implemented, since the paired-by-opening-index
  design already cancels the bias it would address.
- **A paired-configuration noise-floor study is not on this list.** DR-M2 corrects the earlier
  Phase E report's implication that one would be needed before interpreting more matched pairs;
  it is not required (`DR-M2` section 8).

## 7. Roadmap status

`docs/NNUE_PRD.md`'s Phase E row ("self-play data generation begins") now links to this document.
No Phase 16 is defined anywhere in this repository (no issue, no branch, no label), and this
document does not define one.
