# NNUE Phase E — Validation, Bootstrap & Self-Play Roadmap

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to
> implement each PR task-by-task, one PR at a time. Do not implement more than one PR
> (E-N) per session without an explicit go-ahead — Phase D's D-1..D-8 sequencing is the
> precedent for small, independently-reviewable PRs.

**Status:** Draft — not yet approved. Requires grilling + architecture-review +
code-review + agentic-eval before any issue in this roadmap is opened, per this
planning task's own instructions.

**Goal:** Sequence Phase E ("Validation & optimization" per `docs/NNUE_PRD.md` §5
Phased Rollout) into small, independently mergeable PRs that together satisfy Phase E's
exit criteria: *"All three §1 gates pass → NNUE becomes default."* Phase E's PRD scope
is **four tracks**, not one — gauntlets/SPRT validation, performance optimization (only
if needed), self-play data generation (Stage 3), and (a finding of this planning pass)
the first real execution of the Phase D pipeline it depends on (§0's Phase Transition).

**Architecture:** `docs/architecture/NNUE_TRAINER_ARCHITECTURE.md` (§15 Invariants
binding, unchanged), `docs/architecture/research/DR-E1-self-play-data-generation.md`
(new — governs every self-play-track PR below), `docs/adr/ADR-007-staged-training-data.md`
(Stage 3 scope and sequencing), `docs/NNUE_PRD.md` §5 Phased Rollout + Risks.

---

## 0. Phase Transition: from infrastructure to first execution

**Grilled and accepted, 2026-07-14.** Phase D delivered a complete, individually
reviewed, tested, and frozen **training pipeline** — dataset ingestion, feature
encoding, trainer, quantizer, exporter, CI, and Stockfish labeling — validated against
synthetic and tiny fixture data, exactly as its own issues (#192–199) scoped and closed
it. Training the **first real trained network** is not an infrastructure milestone; it
is the first real execution of that infrastructure, and belongs to the next phase.
Track A proves the pipeline can produce a real network — it does not claim, and Track
A's own exit criterion (below) never requires, that the network is already strong;
that question belongs entirely to Track B. This is an execution milestone, not a correction to Phase D — Phase D's
closed issues are not reopened, and none of them were falsely closed (each one's
acceptance criteria were about its own pipeline component, never about "a real net
exists," which is a PRD-level criterion, not an issue-level one).

Verified directly, not assumed, ahead of this roadmap: no `nets/` registry directory
exists anywhere in the repo; the only `.nnue` file in the repo is
`engine-core/src/test/resources/nnue/d6-export-fixture.nnue` (a tiny CI test fixture);
`trainer/trainer/validation/validator.py`'s own docstring states `eval_scale_check()`
"needs a classical-eval-labeled position corpus as input, which does not yet exist
anywhere in this repository" (`bench/nnue-corpus/golden-evals.csv` is pinned to
`TestNetworks.synthetic(8)`, not classical evaluation).

**Conclusion: Phase E begins by exercising the Phase D pipeline end-to-end to produce
the first real trained network.** Track A below is this exercise. Phase E cannot
start at "gauntlets and SPRT for the existing candidate net" (there isn't one yet) or
"self-play using the current net" (ADR-007 alternative 3 already rejected bootstrapping
self-play with no working net to generate from) — Track A produces that net first, and
Tracks B/C/D depend on it.

---

## Global Constraints (unchanged from Phase D, binding on every PR below)

- Invariant 8 (`NNUE_TRAINER_ARCHITECTURE.md` §15): no Python/PyTorch dependency in
  `engine-core`/`engine-uci`/`engine-tuner`/`chess-engine-api`, ever. Track C (self-play)
  interacts with this constraint more directly than any Phase D PR did, because Stage
  3's generator is Vex's own engine, not an external Stockfish binary — see DR-E1 §5.
- Invariant 1 (DatasetProvider isolation): `SelfPlayProvider` must not import
  `TextDatasetProvider`/`StockfishLabeledProvider` or reach into `Trainer`/`Quantizer`/
  `Exporter` internals.
- Branch: stays on `phase/15-nnue` — CLAUDE.md §6, one branch per phase.
- SPRT runs happen on native Windows only (CLAUDE.md §5) — every PR below that names an
  SPRT step outputs the exact `sprt.ps1` command and stops; it is never simulated or
  skipped by an agentic worker.
- NPS bench gate is not enforced on WSL2 (CLAUDE.md §3, memory: `project_wsl_nps_gate_waived`)
  — only enforced on native Windows, same as Phase D.
- Perft/mirror-symmetry gates (CLAUDE.md §3) apply to any `Board`/`MovesGenerator`/
  `Evaluator` change — Track C's `GameLoop`/`MoveSelector` touch move generation/search
  call sites and must not regress either.

---

## PR Sequence

### Track A — First real execution of the Phase D pipeline (blocks every other track)

**Track A complete when:** the first real network has been trained end-to-end from
real Stage 1+2 data and exported successfully (a real `.nnue` + manifest in the `nets/`
registry, held-out correlation reported, eval scale checked against a real classical
corpus — the PRD §5 Phase D exit criterion, now actually satisfied).

| PR | Title | Depends on | Key refs |
|---|---|---|---|
| E-0 | Fix issue #190 (`Board.getCurrentFEN()` trailing-empty-rank bug) | none | Grilled 2026-07-14: fix directly, do not copy-paste `repairTruncatedLastRank()`-style workarounds a third time |
| E-1 | Classical-eval-labeled corpus generator (Java test-scope tool) | E-0 | Architecture doc §14 failure-modes table; `NnueCorpusGenerator.java` (template, minus the workaround now that #190 is fixed) |
| E-2 | Real Stage 1+2 data acquisition/labeling run at scale | E-1 not required but should land first (avoids validating against a still-synthetic scale check) | ADR-007, D-2's `TextDatasetProvider`, D-8's `stockfish_label.py` |
| E-3 | First real candidate net: train, quantize, export, `nets/` registry entry | E-2 | Architecture doc §5-§11, PRD §4 Provenance |

### Track B — Validation (Phase E's own named PRD scope)

**Track B complete when:** the first candidate network has completed the agreed
validation suite (gauntlet, SPRT against the three PRD §1 gates) and a promotion
decision has been recorded in a release report (PRD §4), whichever way that decision
goes — "not promoted yet" is a valid, recorded outcome, not a blocker to calling the
track complete.

| PR | Title | Depends on | Key refs |
|---|---|---|---|
| E-4 | NNUE-mode gauntlet harness (candidate net vs. classical baseline) | E-3 | `tools/match.ps1`, `tools/sprt.ps1`, PRD §1 three gates |
| E-5 | SPRT run(s) against the three release gates + release report | E-4 | CLAUDE.md §5, `docs/sprt-guidelines.md`, PRD §4 "Network Release Reports" |
| E-6 | Performance gate: NNUE-mode bench + profiling; Vector API **only if the gate fails** | E-3 (can run parallel to E-4/E-5) | PRD §5 Phased Rollout row E; CLAUDE.md §2 bench command |

### Track C — Self-play data generation (Stage 3, per DR-E1)

**Track C complete when:** the first reproducible self-play corpus has been generated
(via E-9's engine-owned Vex Self-Play Record format), ingested (E-10), and verified for
downstream training (E-11's golden-fixture + statistical-stability checks passing) —
**"verified" here means the pipeline mechanics are correct (format round-trips, shard
ingestion succeeds, statistical stability holds), not that the corpus's generating
network passed Track B validation.** Per the Bootstrap exception above, Track C can
complete mechanically even if its first corpus later turns out disposable because
Track B found the generating net unsuitable — that outcome does not reopen Track C,
it just means the disposable corpus is not the one used for the next real training
cycle.

**Bootstrap exception (grilled 2026-07-14):** during this first end-to-end training
cycle, self-play generation (E-9 onward) may begin as soon as E-3 produces the first
runnable network — it is not gated behind Track B's validation completing. Validation
and self-play generation are orthogonal consumers of the same candidate network;
running them in parallel shortens the critical path without introducing architectural
coupling. If Track B subsequently shows the network is unsuitable (regression, bug,
instability), the self-play corpus generated from it is treated as disposable — it must
not be used as the basis for future training without an explicit review. This concerns
only the scheduling of the initial bootstrap; the steady-state promotion policy (E-12)
is unchanged — only validated networks are ever eligible for promotion to the shipped
default.

**Architecture-review finding (2026-07-14), in scope for E-10/E-11:** "disposable,
must not be used without review" was originally documentation-only policy with no
mechanical enforcement — every other provenance guarantee in this project (Invariant 8,
engine-identity capture, `nnue_sha256`) is enforced structurally or by CI, not by
operator memory, and a bootstrap-provisional corpus and a validated-net corpus would
otherwise be byte-indistinguishable in storage. Fix: E-10's manifest gains one additive,
optional field (per §9.2's own additive-field policy — not a schema version bump),
`generator_validated: bool | null`, set `false` at generation time and updated to `true`
only once Track B's validation records a promotion decision for that generator network.
A `null`/absent value (for manifests written before this field existed) must be treated
as "unknown, not confirmed validated" by any future consumer, never as an implicit
`true`.

| PR | Title | Depends on | Key refs |
|---|---|---|---|
| E-7 | Extend `SHARD_DTYPE` with `wdl`/`game_id` fields | none (shared infra, can land anytime, but blocks E-9/E-10) | `mmap_shard.py`'s own docstring ("extending... is expected") |
| E-8 | `Labeler` stage (λ-blend, K-scaling) | E-7 | PRD §3 table; DR-E1 §7; `train.py`'s `target_cp` current WDL-raise |
| E-9 | Self-play mode: `GameLoop`/`MoveSelector`/`QuietWalk`/`GameRecord`/`VsprSerializer` (mechanics in `engine-core`, invocation/config/I/O in `engine-uci`) | E-3 only — **not** gated on E-4/E-5 (Bootstrap exception, above) | DR-E1 §5-§6, §12-§15 |
| E-10 | `selfplay_ingest.py` + `SelfPlayProvider` | E-7, E-8, E-9 | DR-E1 §6, §9 |
| E-11 | Self-play provenance fields + statistical-reproducibility golden-fixture test | E-9, E-10 | DR-E1 §9, §14 |

### Track D — Policy documentation (no new mechanism)

**Track D complete when:** all policy ADRs are accepted and no remaining
architectural decisions block steady-state iteration (generate → retrain → validate →
promote-or-not, repeating).

| PR | Title | Depends on | Key refs |
|---|---|---|---|
| E-12 | ADR: the iterative-retraining policy — two separate gates (no generator-eligibility gate; existing SPRT remains the sole release/promotion gate) | none — this is a policy record, fully justified by DR-E1's own research; does not need to wait for a real generation cycle (grilled 2026-07-14, matching how ADR-006/007 were written ahead of Phase D's first PR) | DR-E1 §11, §17 |

**Phase E exit = the conjunction of all four track exits above** (Track A ∧ Track B ∧
Track C ∧ Track D), not merely "all E-N issues closed" — this is the explicit fix for
the same "issues closed but the milestone wasn't really met" gap §0 found in Phase D,
applied to Phase E's own completion claim before it can recur.

**Explicitly out of scope for Phase E** (per DR-E1's own "what should be deferred"):
a generator-eligibility gate; full TD(λ) blending; multi-JVM-instance parallel
self-play; multi-host distribution; opening-book integration (PRD's own still-open
Question 3 — noted, not resolved, by DR-E1); compression of any shard format.

---

## Dependency Ordering (topological)

```
E-0 (fix #190) ─▶ E-1 (corpus generator) ─┐
                                            ├─▶ E-3 (real candidate net) ─┬─▶ E-4 → E-5 (validation track)
E-2 (real data run, dataset decided) ──────┘                              ├─▶ E-6 (perf gate, parallel to E-4/E-5)
                                                                           └─▶ E-9 (self-play — bootstrap exception: not gated on E-4/E-5)

E-7 (shard fields) ─▶ E-8 (Labeler) ─┐
                                      ├─▶ E-10 (ingestion + provider) ─▶ E-11 (provenance/repro test)
E-9 (self-play mode) ────────────────┘

E-12 (policy ADR) — no dependency; can land anytime, independent of the above
```

**Code-review finding (2026-07-14), fixed:** the previous wording of this paragraph
implied E-0 gates E-2, which neither the table nor the diagram above ever showed. E-0
must precede E-1 only (no third copy-paste of the FEN-repair workaround) — E-2 (the
real data acquisition/labeling run) has no dependency on E-0 at all and may start
immediately, in parallel with E-0/E-1. E-7/E-8/E-12 can run in parallel with all of
Track A (they touch shared trainer infra or are pure documentation, not the bootstrap
data run). The only hard join points are **E-3 must exist before E-9** (not before
E-4/E-5, per the Bootstrap exception), and **E-7+E-8+E-9 must all exist before E-10**.

---

## Repository-Specific Implementation Checklist

- [ ] **E-0** (grilled 2026-07-14, precedes E-1): Fix `Board.getCurrentFEN()` to flush
      a trailing run of empty squares on the last rank, closing issue #190 directly in
      `Board.java` — do not add another external `repairTruncatedLastRank()`-style
      workaround. Perft suite is the immediate regression gate (CLAUDE.md §3). Verify
      #190's exact acceptance criteria via `gh issue view 190` before the closing commit
      (memory: `feedback_verify_issue_before_closes_reference`).
- [ ] **E-1**: `bench/nnue-corpus/classical-golden-evals.csv` (or similar name — decide
      at PR start to avoid colliding with the existing NNUE-pinned `golden-evals.csv`)
      generated by a new JUnit test-scope tool, `@Tag("corpus-generation")`, reusing
      `NnueCorpusCategories`'s existing FEN category files (no new position sourcing —
      the categories already exist from C-4) and evaluating each with
      `ClassicalEvaluator`, not `NnueEvaluator`. With E-0 already landed, this generator
      needs no FEN-repair workaround at all.
- [ ] **E-2**: **First acceptance criterion, before any implementation**: resolve PRD §5
      Open Question 1 (Zurichess quiet set vs. Lichess evaluated positions vs. another
      candidate) — grilled 2026-07-14: this is the final deferral, no further planned
      deferrals are expected; E-2 must actually decide it, not punt a third time. Then
      run Stage 2 labeling
      (`stockfish_label.py`) at real scale — note D-8's driver is v1-sequential-only
      (§9.3 of the architecture doc, added this session); a real run may take
      substantially longer wall-clock than the tiny plumbing run `stockfish-label-tiny.json`
      exercised, and this is the first real stress test of that sequential design's
      actual throughput (DR-D8 Open Question 4).
- [ ] **E-3**: Create `nets/` registry directory for the first time; produce a real
      `.nnue` + manifest via the existing `Exporter`; run `eval_scale_check()` against
      E-1's real corpus (first real use of that function beyond its hand-built test
      fixture); report held-out correlation via `evaluate_held_out()`.
- [ ] **E-4/E-5**: Build the NNUE-mode jar (`-DEvalType=NNUE -DEvalFile=<net>` or
      whatever UCI option names Phase A actually wired — verify against
      `UciApplication.resolveNnueNetworkForSearch`, don't assume the flag names);
      reuse `tools/match.ps1`/`tools/sprt.ps1` unchanged — these already exist and are
      mature (used throughout Phase 13/14's classical-eval tuning); output the exact
      `sprt.ps1` invocation and stop, per CLAUDE.md §5.
- [ ] **E-6**: Run `--bench` in NNUE mode; compare against the existing 316,964 NPS
      baseline — but only on native Windows (WSL2 gate waived, CLAUDE.md §3). Vector
      API work is explicitly conditional: do not start it unless E-6's own measurement
      shows the performance gate actually fails.
- [ ] **E-7** (grilled 2026-07-14 — field types are decided, not left to PR-start
      judgment): Add to `SHARD_DTYPE`:
      - `wdl`: `f4` (float32) + `has_wdl` (bool) — a probability-space scalar, matching
        `PositionLabel.wdl`'s own float type; no fixed-point/int encoding (would add
        quantization error for no measurable storage benefit against the FEN field's
        own size).
      - `game_id`: `u8` (uint64), **not** a fixed-width UUID string — `game_id` is a
        pure grouping/equality key (which positions came from the same game), never
        human-facing and never needing string parsing; a monotonically assigned
        uint64 from the self-play writer is sufficient and far cheaper (8 bytes vs. 36,
        integer grouping/joins instead of string comparison). A globally unique game
        identifier (if ever needed) belongs in the game-record/manifest metadata, not
        repeated on every position row.
      Update `_encode`/`_decode` accordingly. This is a **non-breaking extension** per
      the module's own docstring precedent (D-8 already extended it once for
      `search_depth`/`search_nodes`) — confirm again at this PR's start that no
      committed `.bin` shard exists anywhere that would be invalidated by the layout
      change (true as of this writing).
      **Architecture-review finding (2026-07-14), in scope for this PR:**
      `trainer/trainer/contracts/dataset.py`'s existing `PositionMetadata.game_id` is
      typed `Optional[str]`, not `int` — this PR must also retype it to
      `Optional[int]` (matching the `uint64` decision above) rather than leaving a
      str-typed contract field feeding a uint64-typed shard field with an implicit,
      undocumented translation. No existing `DatasetProvider` populates `game_id` today
      (grep confirms zero call sites), so this is a zero-risk type change, not a
      breaking one.
      **Final refinement pass (2026-07-14) — semantic scope clarified, schema
      unchanged:** `game_id` is scoped to the single ingestion run that produced a
      given shard — an internal grouping key (which positions came from the same
      game, for split-leakage avoidance and same-game correlation), **not** a
      persistent, cross-run provenance identifier. This is a documentation
      clarification only; the field is not split into a separate local/persistent
      pair (`game_local_id` + `game_uuid`) — dataset/run-level provenance already
      lives in the manifest (generator network UUID, engine commit, etc., §9 of the
      architecture doc), the same place every other stage's provenance already lives,
      and duplicating it per-position would repeat information the manifest already
      carries for no consumer this roadmap or DR-E1 identifies. If a future need
      arises to trace a specific position back to its originating VSPR game file,
      that is a manifest-level addition (e.g., an ordered list of ingested VSPR
      files) — not a per-position field.
- [ ] **E-8**: Implement `Labeler.blend(label, lambda_, k) -> float` per DR-E1 §7's
      cited formula (`λ·sigmoid(cp/K) + (1−λ)·wdl`); wire `train.py`'s `target_cp` to
      call it instead of raising on WDL-only labels; no default `λ` hardcoded — a named,
      versioned training-config field per DR-E1 §7's own recommendation.
- [ ] **E-9** (grilled 2026-07-14 — module placement decided): `GameLoop`/
      `MoveSelector`/`QuietWalk` and VSPR encoding/decoding all live in a new
      `engine-core` package (e.g. `core.selfplay`) — self-play is a core engine
      capability, needing direct access to `Board`/`Searcher`/`MovesGenerator`
      internals (make/unmake, game-end detection) the same way engine-core's own tests
      already do, not mediated through the UCI protocol layer. `engine-uci` owns only
      invocation, configuration parsing, and file I/O (a `--selfplay` mode, analogous
      to how `--bench` is already an `engine-uci`-owned entry point over `engine-core`
      internals) — the same separation of responsibilities already used elsewhere in
      this project.
      **Final refinement pass (2026-07-14) — internal factoring, not a module-boundary
      change:** rather than one `GameRecordWriter` class doing both in-memory record
      assembly and byte-level encoding, the preferred internal shape is:
      ```
      GameLoop
          ↓
      GameRecord           (immutable in-memory value object: move list,
          ↓                 per-sampled-ply score, generator UUID, outcome)
      VsprSerializer       (byte-level VSPR encode/decode — the only class
                             that knows the on-disk layout)
      ```
      `GameRecord` and `VsprSerializer` are two separately-testable units, both still
      in `engine-core` — mirroring this project's own precedent of preferring a named
      data value plus a named encoder/converter over one class doing both (the same
      shape `CanonicalNetwork`/`Quantizer` and `QuantizedCanonicalNetwork`/`Exporter`
      already use, `NNUE_TRAINER_ARCHITECTURE.md` §5). Placement stays in `engine-core`
      (matching `NnueNetwork`'s own in-`engine-core` load/write precedent for a bespoke
      binary format) — this is not a repeat of the module-placement question already
      settled above, only a refinement of how the `engine-core` side is factored
      internally. `engine-uci`'s role is unchanged: invoking self-play mode, passing
      configuration (output path, game count, etc.), and calling
      `VsprSerializer.write(record, path)` — never re-implementing or interpreting
      VSPR bytes itself.
- [ ] **E-9 (continued)**: Game-record output format — **the "Vex Self-Play Record"
      (VSPR)**, a bespoke, internal, versioned format, treated exactly like `.nnue`
      itself (magic bytes, format version, documented byte layout — not borrowed from
      PGN or any external schema). Rationale (grilled 2026-07-14): PGN comment-tag
      metadata is not a standardized schema — "reusing PGN" would still require
      designing a bespoke comment-tag convention inside it, at which point it's a
      bespoke format wearing a PGN costume, with PGN's parsing overhead (SAN
      disambiguation, escaping) for no real benefit; a bespoke format encodes the
      generator network UUID and per-ply score as first-class typed fields directly.
      **If human inspection ever becomes important, add a PGN *exporter* as a separate,
      later tool — PGN is never the canonical storage format.**
      **Final refinement pass (2026-07-14) — versioning policy confirmed, unchanged:**
      VSPR intentionally follows the exact same evolution policy `.nnue` already uses
      (`NNUE_TRAINER_ARCHITECTURE.md` §9.2) — an explicit `formatVersion` field, a
      hard version-match check that rejects any mismatch outright, and no reserved
      bytes/flags/feature-bit space for speculative forward compatibility. This is a
      deliberate consistency with `.nnue`'s own already-reaffirmed posture ("a
      schema/format reader that tries to be clever about old shapes is exactly where
      NNUE toolchains accumulate silent correctness bugs"), not an oversight — VSPR
      evolves by version bump, same as `.nnue`, never by parsing around an old shape.
- [ ] **E-10**: `selfplay_ingest.py` mirrors `stockfish_label.py`'s shape (per DR-E1 §6)
      but does no searching/labeling — pure format translation into `PositionRecord`s
      then `write_shard`. `SelfPlayProvider` mirrors `StockfishLabeledProvider`
      structurally, `metadata().stage = "self-play"`.
- [ ] **E-11**: Golden-fixture test (fixed seed, tiny/synthetic net, handful of games,
      committed expected output) per DR-E1 §14; a statistical-stability test (WDL
      proportions stable across seeded reruns within a documented tolerance), explicitly
      **not** a byte-identical determinism test — DR-E1 §9/§18 flags mislabeling this as
      a real risk, and the test's own name/docstring must say "statistical" not
      "deterministic."
- [ ] **E-12** (grilled 2026-07-14 — write now, as policy, not retrospective): New ADR
      (numbering: next available after ADR-010) recording the two-gate split
      (generator-eligibility: none; release-promotion: existing SPRT, unchanged) per
      DR-E1 §11's recommendation — documentation only, no new code. The decision is
      already fully justified by DR-E1's primary-source research and does not depend on
      completing a real generate→retrain cycle first, matching how ADR-006/007 were
      written ahead of Phase D's first PR. The ADR must explicitly distinguish
      **normative policy** ("how the system is intended to operate": no
      generator-eligibility gate, SPRT remains the sole release/promotion gate) from
      **empirical evidence** ("what later experience may cause us to revisit") — its
      Revisit Conditions section names concrete triggers (e.g., measured evidence that
      ungated self-play materially degrades training quality, causes instability, or
      wastes unacceptable compute, after one or more real generation→retraining
      cycles), not a vague "revisit if it doesn't work out."

---

## Repository-Specific Validation Strategy

- **E-0**: perft suite (CLAUDE.md §3 gate — a `Board.java` change) plus a targeted
  regression test asserting `getCurrentFEN()` now flushes a trailing empty-square run
  on the last rank; confirm no existing test relied on the old (buggy) truncated
  output before changing it.
- **E-1**: existing `mvn -pl engine-core test -Dgroups=corpus-generation -Dcorpus.generate=true`
  convention (already established by C-4) — no new test-invocation mechanism needed.
- **E-2/E-3**: `cd trainer && uv run --extra dev --extra train pytest tests/` must stay
  green throughout (currently 135/135); the real training run itself is validated by
  `Validator.evaluate_held_out()` + `eval_scale_check()` against E-1's corpus, and by
  the existing trainer-CI reproducibility check (byte-identical quantized tensors given
  the same seed) — this is the first time that check runs against a non-tiny dataset,
  worth watching for a wall-clock/memory surprise the CI-scale fixture couldn't reveal.
- **E-4/E-5**: `tools/sprt.ps1` on native Windows only, exact command output per
  CLAUDE.md §5 — never simulated. Perft suite must stay green (CLAUDE.md §3) since
  NNUE-mode gauntlet play exercises the full make/unmake path under real games, not
  just unit fixtures.
- **E-6**: `--bench` NPS comparison, native Windows only (WSL2 gate waived).
- **E-7**: existing `mmap_shard` round-trip tests, extended to cover the new fields
  (encode → decode → assert equality), plus a regression test that a wdl-only
  `PositionLabel` (previously a guaranteed `ValueError`) now round-trips correctly.
- **E-8**: unit tests for `Labeler.blend()` at `λ=0`/`λ=1`/`λ=0.5` boundary values
  against hand-computed expected outputs — a golden-fixture-style test, matching this
  project's established preference for pinned-value tests over property-only assertions.
- **E-9**: perft suite (unaffected files, but re-run as a gate per CLAUDE.md §3 since
  this PR touches `engine-uci` and adds new move-selection logic adjacent to search);
  mirror-symmetry test (Evaluator-adjacent change, CLAUDE.md §3); a new test asserting
  `MoveSelector`'s bounded substitution never returns an illegal move (DR-E1 §14) —
  extend existing move-generation test coverage, not a new test category.
- **E-10/E-11**: `SelfPlayProvider` unit tests mirroring `test_stockfish_provider.py`'s
  existing shape; the golden-fixture + statistical-stability tests named above.
- **E-12**: documentation-only — no test suite applies; validated by review (the
  `adr-review` skill's own generation/critique discipline, plus architecture-review
  confirming the normative-policy/empirical-evidence distinction is actually present
  and the Revisit Conditions name concrete triggers, not vague language).
- **Every PR**: `verification-before-completion` discipline (memory:
  `feedback_review_passes_after_green_tests`) — architecture-review + code-review as
  background agents, agentic-eval self-check, exactly as every Phase D PR did.

---

## Risk Assessment

| Risk | Severity | Mitigation |
|---|---|---|
| Track A (bootstrap) takes substantially longer than a typical PR-sized effort — a real training run is not bounded by code-review turnaround the way Phase D's plumbing PRs were | Medium | Scope E-2/E-3 as "however long the real run takes," not calendar-boxed; PRD's own "no calendar commitments" framing (§5) applies here more than anywhere else in this project so far |
| D-8's Stage 2 driver is v1-sequential (no parallelism, no resume) — E-2's real-scale run is the first real stress test of that design's throughput ceiling | Medium | DR-D8 Open Question 4 already flagged this; if E-2 proves the sequential driver too slow at real scale, that is exactly the trigger DR-D8 §16 names for building the worker-pool architecture — a mid-roadmap scope addition to plan for, not to pre-build speculatively |
| A category error importing MCTS-specific self-play mechanics (Dirichlet noise/temperature over visit counts) into Vex's alpha-beta search | High if it happens, but explicitly headed off | DR-E1 identifies this as its single highest-consequence risk (§18) and names the correct alpha-beta-native analog (`gensfen`'s bounded multi-PV substitution) — E-9's implementation must cite DR-E1 §6/§17 directly, not the AlphaZero paper, when justifying `MoveSelector`'s design |
| Self-play's reproducibility guarantees get documented as stronger than they are (copy-pasting Stage 2's provenance language without adjustment) | Medium | DR-E1 §9/§18 flags this explicitly; E-11's test and E-10's manifest schema docstring must state "statistical, not bit-exact" in the field descriptions themselves, not just in this roadmap |
| Module-placement decision for `GameLoop`/`GameRecord`/`VsprSerializer` (`engine-core` for mechanics, `engine-uci` for invocation — ratified via grilling 2026-07-14, internal factoring refined 2026-07-14) turns out wrong once implementation starts | Low | Recorded as a ratified decision with rationale (§ "Architectural Conflicts Discovered" item 5) — architecture-review is still asked to confirm it survives scrutiny before E-9 begins, not to make the call fresh |
| Retraining cadence, λ default, and adjudication thresholds are all genuinely unresolved empirical questions (DR-E1 §19) | Low (expected, not a defect) | Explicitly deferred to post-E-3/E-11 empirical tuning, per DR-E1's own discipline of not inventing unearned numbers — not this roadmap's job to resolve |

---

## Architectural Conflicts Discovered

1. **The handoff framing ("Phase E = self-play, the next undesigned subsystem") undersold
   Phase E's actual starting point.** Not a defect in Phase D (§0's Phase Transition note
   — Phase D's issues were never falsely closed, since "a real net exists" was always a
   PRD-level criterion, not an issue-level one) — but a planning-framing correction this
   pass makes explicitly: Phase E's first real work is exercising the Phase D pipeline
   end-to-end (Track A), not self-play. This ordering is independently required anyway,
   consistent with ADR-007's own rejection of "skip straight to self-play" with no
   working net to generate from.
2. **Stage 3's recommended process model inverts Stage 2's.** D-8 concluded an
   *external*, Python-driven UCI subprocess was correct for Stage 2 (driving a
   third-party Stockfish binary). DR-E1 concludes an *internal*, Java-owned game loop
   is correct for Stage 3 (driving Vex's own search). Both conclusions are independently
   well-justified (D-8: Stockfish is an external, opaque binary with no reason to embed
   trainer logic in it; DR-E1: diversity injection and search-derived value estimates
   can only be produced by the search itself, and Vex's own code is not opaque to Vex).
   Flagged here explicitly so a future reader does not mistake this inversion for an
   inconsistency between the two Deep Research reports — it is a correct response to a
   genuinely different problem shape, not a contradiction.
3. **`SHARD_DTYPE`'s `wdl`/`game_id` gap was anticipated in writing but never scheduled
   as its own PR** — `mmap_shard.py`'s own D-2-era docstring predicted this exact
   extension ("expected... not a design flaw being deferred") but no issue existed for
   it before this planning pass (E-7, above). Not a conflict with any decision, just a
   previously-untracked piece of already-anticipated work now given a PR number.
4. **The `Labeler` stage has been named in the architecture doc's own module table
   since before Phase D's first PR, but was never implemented** — `train.py`'s own
   docstring already documents this precisely ("the actual lambda-blend becomes real
   work once a WDL-bearing source exists... not built preemptively here"). This is not
   a defect — it is exactly the deferred-until-needed discipline this project applies
   elsewhere — but it means E-8 is not truly "Stage-3-specific" despite living in
   Track C; it is shared-pipeline work that Stage 3 happens to be the first real trigger
   for. Track ordering reflects this (E-8 has no hard dependency on E-9).
5. **DR-E1 left `GameLoop`/`GameRecord`/`VsprSerializer`'s exact module placement and
   the game-record file encoding as open implementation decisions** (§5/§6 of the
   report, explicit: "exact module TBD at implementation, not this report's
   decision"). Both were resolved during this roadmap's grilling pass (2026-07-14):
   mechanics in a new `engine-core` package, invocation/config/I/O in `engine-uci`; a
   bespoke, versioned "Vex Self-Play Record" format, never PGN as canonical storage —
   internally factored (final refinement pass, 2026-07-14) as a `GameRecord` value
   object plus a separate `VsprSerializer`, not one combined writer class. Recorded
   here so a future reader sees these as ratified decisions with a rationale, not
   silently resolved by omission — architecture-review is still asked to confirm both
   survive
   scrutiny, not to make the calls fresh.

---

## Open Questions Carried Forward (not resolved by this roadmap, by design)

Per DR-E1 §19 — explicitly not settled here, each requires real measured data this
planning pass cannot fabricate: default `λ`; adjudication thresholds and technique;
retraining cadence; single-JVM-instance throughput sufficiency; opening-book vs.
engine-internal-only diversity (PRD §5 Open Question 3, still open). Also carried
forward from DR-D8: whether the Stage 2 driver's sequential v1 scope needs the
worker-pool upgrade DR-D8 §5 already designed, now that E-2 is its first real-scale
exercise.

---

## Next Steps (per the task that produced this roadmap)

1. ~~Grill this roadmap~~ — **done, 2026-07-14.** Eight substantive forks resolved via
   interactive grilling (recorded inline throughout this document, each tagged "grilled
   2026-07-14"): Track A stays under Phase E with a Phase Transition note, not a
   reopened Phase D; PRD §5 Open Question 1 gets one final, non-deferrable resolution
   at E-2's start; E-0 fixes issue #190 directly ahead of E-1; self-play mechanics live
   in `engine-core`, invocation in `engine-uci`; the game-record format is a bespoke,
   versioned "Vex Self-Play Record," never PGN as canonical storage; `SHARD_DTYPE`'s
   `wdl` is float32 and `game_id` is uint64, not a UUID string; E-9 depends only on
   E-3 (Bootstrap exception, not gated on Track B validation); E-12 is written now as a
   policy ADR, not deferred to after a real generation cycle; and track-level exit
   criteria (plus a Phase E exit defined as their conjunction) were added so this
   roadmap cannot recreate §0's own "issues closed but the milestone wasn't really met"
   gap for Phase E itself.
2. Run `architecture-review`, `code-review` (scoped to this plan document, since no
   implementation code exists yet), and `agentic-eval` as background agents.
3. If approved, open GitHub issues for E-0 through E-12, following CLAUDE.md §7's
   commit-format precedent for issue-body structure (Objective/Motivation/Scope/
   Explicitly Out of Scope/Dependencies/Acceptance Criteria/Plan Reference), matching
   every Phase D issue's own shape.
4. Do not begin implementation of any E-N PR until this roadmap is approved.
