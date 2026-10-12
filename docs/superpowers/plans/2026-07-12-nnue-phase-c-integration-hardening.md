# NNUE Phase C (Integration Hardening) Implementation Plan

> **For agentic workers:** This is a specification/design plan, not a
> code-execution plan — Phase C's actual code is written only after this
> plan is separately approved and each PR below is individually reviewed.
> No implementation code is written by this plan itself. Steps use
> checkbox (`- [ ]`) syntax for tracking once execution begins.

**Goal:** Deliver PRD Phase C ("Integration hardening") in full: `NnueDebug`
mode + debug UCI commands, a categorized benchmark corpus, CI test net +
golden evals, loader/CI regression coverage, and NNUE-mode integration
hardening — without touching any frozen Phase B architecture.

**Architecture:** Every addition is additive on top of the frozen baseline
(`EvaluatorStrategy`, `ClassicalEvaluator`, `NnueEvaluator`, `NnueNetwork`,
`FeatureExtractor`, the evaluator ownership model, the copy-per-ply
accumulator design, the loader architecture, the UCI integration, and
ADR-002/003/004/005/009). Debug tools are new public methods on the
*concrete* NNUE classes, reached from `UciApplication` via
`instanceof NnueEvaluator` — mirroring the existing, already-accepted
`instanceof ClassicalEvaluator` pawn-hash passthrough pattern in `Searcher`.
No method is added to `EvaluatorStrategy` itself.

**Tech Stack:** Java 21 / Maven (unchanged), JUnit 5 with `@Tag`-based test
grouping (existing `-Dgroups=regression`/`-Dgroups=benchmark` Maven-profile
convention, unchanged), GitHub Actions (existing `ci.yml` push/PR trigger +
`nightly-sprt.yml`'s `schedule: cron` + `workflow_dispatch` pattern, reused
not reinvented).

## Global Constraints

- Branch: continue on `phase/15-nnue` (confirmed with the user — the PRD's
  internal Phase A/B/C/D/E lettering is one continuous NNUE effort under
  CLAUDE.md's single "Phase 15" branch, not five separate CLAUDE.md phases).
- GitHub issues: file one issue per PR below, labeled `phase-15`, titled
  `[P15] C-N — <short name>` mirroring the existing `[P14] A-1`/`[P14] A-2`
  convention this repo already uses for phase-internal sub-lettering
  (confirmed with the user — restores the issue-tracking convention every
  other phase 0–14 already follows, which Phase A/B broke).
- No object allocation in `EvaluatorStrategy`/`NnueEvaluator`/`Searcher` hot
  paths (CLAUDE.md §3) — every debug tool must be proven zero-cost when
  `NnueDebug` is off, not just "usually cheap."
- Perft/mirror-symmetry/NPS gates apply unchanged; NPS gate stays
  native-Windows-only per CLAUDE.md §3 (WSL measurements are not a valid
  regression gate here).
- Commit format per CLAUDE.md §7 on every PR; `Closes #N` only once every
  acceptance-criteria checkbox in that issue is verifiably met.
- Do not modify `EvaluatorStrategy`, `ClassicalEvaluator`, `NnueEvaluator`'s
  existing methods, `NnueNetwork`'s existing methods, `FeatureExtractor`'s
  existing methods, or any of ADR-002/003/004/005/009's documented decisions,
  unless a PR's own acceptance criteria include a demonstrated correctness
  defect (none identified in this plan — see Task 1).

---

## Task 1 — Phase C scope validation

### Assumptions confirmed by Phase B

- **The `instanceof`-on-concrete-type escape hatch is a proven, accepted
  pattern**, not a one-off — `Searcher` already does `instanceof
  ClassicalEvaluator` three times (pawn-hash config/stats) specifically
  because ADR-004 rejected putting classical-only concerns on
  `EvaluatorStrategy`. Every debug tool in Task 2 reuses this exact idiom
  for NNUE-only concerns; nothing new is being invented.
- **`NnueEvaluator` already exposes the two lowest-level debug primitives**:
  `currentAccumulator(int perspectiveColor)` (package-private) and
  `FeatureExtractor.activeFeatureIndices(Board, int)` (public). Phase C's
  accumulator inspector and feature dump are thin formatting layers over
  code that already exists and is already tested — not new logic.
- **The loader already validates the exact three fields Phase C's "malformed
  network" CI job needs to exercise**: magic bytes, `formatVersion`,
  `architectureId`/`featureSetId` (freeze patch), `hiddenWidth` bounds, and
  exact file length. Phase C's loader-regression-test job is filling out
  the *test matrix* for validation that already exists in production code,
  not writing new validation logic.
- **The benchmark-corpus categories PRD asks for are partially free.** Two
  of six categories already exist as committed, tested fixtures: `tactical`
  (`engine-core/src/test/resources/tactical/mate_2_3_50.epd`, already
  consumed by `TacticalSuiteTest`) and `quiet` (`tools/quiet-labeled.epd`,
  `data/quiet-labeled.epd`, already the Texel-tuning corpus). Confirmed by
  direct filesystem check, not assumed from the PRD's description alone.

### Assumptions invalidated by Phase B

- **None.** The Phase C readiness audit (already produced this session)
  found the frozen architecture requires zero structural refactoring for
  any of Phase C's PRD-scoped work — that finding is reused here, not
  re-derived.

### New constraints introduced by the frozen Phase B code

- **`NnueEvaluator.currentAccumulator` is package-private**, invisible from
  `engine-uci` (a different Maven module/package). Task 2's accumulator
  inspector needs one new *public* accessor — additive, but a real,
  concrete constraint the plan must account for (Task 2, PR C-1).
- **`ACCUMULATOR_POOL_SIZE = Board.UNMAKE_POOL_SIZE`** (ADR-003/009) means
  any debug tool reasoning about accumulator depth must key off
  `Board.UNMAKE_POOL_SIZE`, never redeclare its own pool-size constant —
  the freeze patch fixed exactly this duplication once already; Phase C
  must not reintroduce it.
- **`EvaluatorStrategy.onUnmake()` takes no arguments** (ADR-003's own
  finding) — any debug assertion comparing "what changed on this move" at
  `onUnmake` time cannot recover move/capture info from the hook call
  itself; it must be read from `Board`'s own state (`board.lastCapturedPiece()`
  is only valid in the make→unmake window, per its own Javadoc) or carried
  forward from the paired `onMake` call, not re-derived after the fact.
- **`ADR-002` deliberately left the oracle's numeric drift bound
  undetermined** ("a Phase C deliverable... measured against the first real
  network rather than guessed"). Phase C's oracle comparator therefore
  ships with a *measurement* mechanism, not a hardcoded pass/fail threshold
  — the CI job reports the bound, it does not yet gate on one, until a real
  net exists to calibrate against (Phase D). This directly shapes PR C-4's
  acceptance criteria below.
- **`UciApplication` holds no persistent `Searcher`/evaluator reference
  reachable from the command loop.** Every `Searcher` — main and every Lazy
  SMP helper — is a local variable scoped inside `handleGo()`/its
  submitted lambdas (confirmed directly against
  `UciApplication.java`); by the time `handleGo()` returns and control is
  back at the command loop, alpha-beta's own make/unmake discipline has
  already unwound every `Searcher`'s accumulator stack back to `sp = 0`
  (root position) — the same invariant that makes perft/search correctness
  possible in the first place. This means an interactive debug UCI command
  issued after a `go` completes can only ever see *root-position* state,
  identical to what a fresh `reset(board)` would produce — it cannot
  observe the mid-search, in-flight accumulator state where a real desync
  would actually be visible, and a genuinely cross-thread read of a
  still-running search's live state would need real synchronization,
  directly risking ADR-009's "never shared across threads" invariant for a
  feature far bigger than Phase C's scope. **Resolution, stated explicitly
  here rather than discovered mid-implementation:** the interactive `nnue
  acc`/`nnue verify`/`eval`(NNUE) commands are scoped honestly to inspect
  the *current root position's freshly-built* accumulator — useful for
  catching load-time bugs, feature-index/mirroring errors, and confirming
  the loaded network reconstructs a given FEN correctly, but **not** a
  live mid-search desync detector. The actual mid-search desync detector is
  PR C-3's automatic, sampled `debugMode` assertion, which runs *inside*
  the live search thread during `onMake` itself — no UCI round-trip, no
  cross-thread access, no synchronization needed, and it is the one tool in
  this plan that genuinely fulfills the PRD's "primary desync diagnostic"
  framing. This distinction is carried into Task 2's table and PR C-3's
  scope below, not left implicit.

### Phase C vs. Phase D+ work (explicit boundary)

| In Phase C | Deferred to Phase D+ | Why |
|---|---|---|
| Debug tooling (all 7, Task 2) | — | **Correction from an earlier draft of this plan**: the PRD's Roadmap table actually assigns the accumulator inspector, rebuild comparator, and oracle comparator to *Phase B's* exit criteria ("core debug tooling (accumulator inspector, rebuild comparator, feature dump, oracle comparator)"); Phase C's own row names only "NnueDebug mode + debug UCI commands." Phase B shipped without them (an already-accepted scope narrowing, per the Phase C readiness audit). This plan absorbs that carried-over Phase B scope into Phase C rather than reopening frozen Phase B — the work is correctly placed here, but the PRD does not name it as *Phase C's own* scope, and this plan should not have claimed it did |
| Benchmark corpus (categories, loader, CI wiring) | Corpus *content* growth via self-play/SF-labeling | PRD Phase D scope is "self-play data generation," not Phase C |
| CI test net (hand-crafted, synthetic, deterministic) | A real trained net | PRD Phase D exit criterion is "first real net trains end-to-end" — Phase C cannot depend on something Phase D hasn't produced yet |
| Loader regression tests (malformed/truncated/oversized — already partially built by the freeze patch, Phase C fills remaining matrix) | — | Already production code; Phase C only adds test coverage |
| NNUE-mode CI regression jobs (fixed-seed PR job + nightly unfixed-seed job) | Trainer-reproducibility CI job | Explicitly named as Phase-D-dependent in the user's own task framing; PRD's own "Reproducibility check (trainer CI)" needs a trainer that doesn't exist yet |
| Oracle comparator *mechanism* (dequantize + compare, report drift) | Oracle comparator *gate* (hard pass/fail threshold) | ADR-002's own Open Questions — the threshold needs a real network to calibrate against |
| — | **Network Release Reports** | Explicitly excluded — see below |

**Network Release Reports are excluded from this Phase C plan.** Read
directly from the PRD: the report template requires "gauntlet results" and
"SPRT results," both of which are PRD Phase E scope per the Roadmap table
(row E: "Gauntlets, SPRT runs, profiling"), and requires "link to the
provenance manifest," which is PRD Phase D scope (row D: "export with
provenance manifest"). Release Reports are documented in the PRD as a
*permanent engineering artifact for candidate networks*, but Phase C has no
candidate network to report on — the CI test net is a synthetic fixture,
never a release candidate. Building report-generation tooling now would be
building it against inputs (gauntlet scores, SPRT verdicts, provenance
manifests) that don't exist until two phases later. This satisfies the
user's own conditional instruction ("release-report infrastructure if it is
explicitly part of the frozen design baseline") — it is documented in the
PRD, but its own stated dependencies place it outside Phase C's baseline.

---

## Task 2 — Debug tooling design

Ownership rule applied uniformly: **every tool lives on the concrete NNUE
class it inspects, never on `EvaluatorStrategy`.** `UciApplication` reaches
it via `instanceof NnueEvaluator`, exactly mirroring `Searcher`'s existing
`instanceof ClassicalEvaluator` pattern. This is not a new design — it is
the one ADR-004 already accepted, applied a second time.

| Tool | Ownership | Public API | Scope | Perf impact when off | Testing |
|---|---|---|---|---|---|
| **Feature dump** | `FeatureExtractor` (already exists) | `activeFeatureIndices(Board, int)` — **already public, already shipped in Phase B.** No new code. | Test/debug — already annotated as such in its own Javadoc | Zero — allocates, but only ever called from `nnue features` (guarded) or tests, never the search path | Already covered by `FeatureIndexParityTest` |
| **Accumulator inspector** | `NnueEvaluator` | New: `public String dumpAccumulators()` — formats both perspectives' current-ply values, ply index, `sp`, in a stable diffable text format (PRD's own wording) | Debug | Zero — new method, not called unless invoked; existing `evaluate()`/`onMake()` untouched | New `NnueEvaluatorDebugTest`: asserts the dump's format is stable and its numbers match `currentAccumulator()`'s raw values for a known fixture position |
| **Rebuild comparator** | `NnueEvaluator` | New: `public RebuildDiff verifyAgainstRebuild(Board board)` returning a small record (`firstDivergingIndex`, `delta`, or `NONE`) — PRD's own "first differing index and delta" wording, made a real return type instead of a formatted string so both the UCI command and the debug-assertion (below) can consume it programmatically | Debug + reused internally by the debug-assertion tool. **Note (see the new-constraints discussion in Task 1): the interactive UCI path can only ever call this against a freshly-reset, root-position accumulator** (`UciApplication` holds no live search-thread reference), so the UCI-reachable form of this tool is a load/reconstruction sanity check, not a mid-search desync catch — that job belongs to the automatic `debugMode` assertion below, which calls this same method from inside the live search thread instead | Zero when not called — computes a full rebuild into scratch arrays (not the live accumulator), compares, discards. Never touches `whiteAcc`/`blackAcc`'s live state | New `NnueEvaluatorRebuildComparatorTest`: seeds a known accumulator, corrupts one index directly (test-only, via a package-private test hook), asserts `verifyAgainstRebuild` reports exactly that index |
| **Oracle comparator** | New class: `NnueOracle` (same package, `core.eval.nnue`) — **not a method on `NnueEvaluator`**, deliberately, since it needs an independent float forward pass over the *same weights*, not the live accumulator state; keeping it a separate class means the int16 hot path never has to import or reference it | `public static OracleResult compareInt16VsFloat32(NnueNetwork network, Board board)` — batch variant iterates a FEN list, per PRD | Test-only (see critique below — this one should **not** get a debug UCI command in Phase C) | Zero — never constructed unless explicitly called; not reachable from `evaluate()` at all | New `NnueOracleTest`: dequantizes `TestNetworks`' synthetic weights via `qa`/`qb`, confirms zero divergence for hand-computable positions, confirms divergence is correctly reported when a weight is deliberately mutated |
| **Eval breakdown** | `NnueEvaluator` | New: `public String explainEval(Board board)` — same name and shape as `Evaluator.explainEval(Board)` (existing classical precedent), returns pre-activation ranges, clip counts, output contribution, int16 score, and (if `NnueOracle` is available) the float32 score and delta | Debug | Zero when not called | New test asserting output contains all PRD-named fields for a fixture position; golden-string test, not just "doesn't throw" |
| **Debug assertions** (`NnueDebug` mode: probabilistic incremental==rebuild check + stack-depth==ply invariant) | `NnueEvaluator` (internal), gated by one `boolean debugMode` constructor parameter, default `false` | No new *public* API beyond the constructor flag — internally calls `verifyAgainstRebuild()` above at a low, fixed probability (e.g. 1-in-256 `onMake` calls) when enabled | Debug, but touches the hot path when *enabled* — see performance note below. **This, not the interactive UCI commands, is the tool that actually catches mid-search desync**, since it runs from inside the same search thread that has the live, not-yet-unwound accumulator (see Task 1) | **Zero when disabled**: a single `if (debugMode)` branch check per `onMake`, same cost shape as the existing `instanceof` checks `Searcher` already pays; when *enabled*, cost is bounded by the sampling rate, not unconditional | New `NnueEvaluatorDebugModeTest`: constructs with `debugMode=true`, deliberately breaks a delta computation via a test-only seam, asserts a logged `info string` diagnostic (not a thrown exception — see Risk Review, PR C-3) fires within a bounded number of moves |
| **Debug UCI commands** (`nnue features`/`acc`/`verify`, plus NNUE's `eval`) | `UciApplication` | New private handlers, same shape as existing `handleEval()`, gated behind one new UCI option `NnueDebug` (boolean, default `false`) — reads it, then does `instanceof NnueEvaluator` on a freshly-constructed evaluator for the current root position and calls the relevant methods above. **Root-position-only, per Task 1's new-constraints discussion** — not a live-search inspector | Debug, non-standard, "documented as unstable" (PRD's own wording) | Zero when `NnueDebug` is off — commands are parsed but return "info string NnueDebug is off" without touching `NnueEvaluator` at all | New `UciApplicationIntegrationTest` cases: each command with `NnueDebug` off (inert), on + NNUE active (real output), on + Classical active (graceful "not an NNUE evaluator" message, no crash) |

### Critical evaluation: should any tool stay test-only?

**Yes — the oracle comparator should not get a debug UCI command in Phase
C, and this is a deliberate deviation from the PRD's literal
`nnue eval`-adjacent framing, not an oversight.**

Reasoning: the PRD lists `nnue eval` (breakdown) as UCI-reachable but
describes the oracle comparator only as something that "batch-run[s] int16
vs. float32 inference over a *position file*" — i.e., its natural interface
is a corpus-level batch job (matching how it's actually used: CI's "Oracle
bound" check runs it over the whole benchmark corpus), not a single-position
interactive UCI query. A UCI GUI operator issuing `nnue verify` mid-game to
check *one* position makes sense (that's the rebuild comparator's job,
already scoped for UCI). An operator wanting oracle drift statistics over a
whole corpus is doing test/CI work, not interactive debugging — routing that
through the UCI text protocol one position at a time would be strictly worse
than just running the JUnit test directly. **Recommendation: `NnueOracle`
stays test/CI-scope only in Phase C; if a real operational need for
interactive single-position oracle checks emerges post-Phase-C, add it then,
against actual demand rather than PRD-literalism.** This is presented as a
finding for approval, not a unilateral change to PRD scope — flagged
explicitly in the Risk Review (Task 7) below.

The debug-assertion tool is the second borderline case, addressed directly
in Risk Review (Task 7) rather than resolved here, since its "hidden
complexity" concern (probabilistic sampling touching the hot path) needs the
alternatives-and-rejection treatment Task 7 requires, not a one-line verdict.

---

## Task 3 — Benchmark corpus design

### Corpus categories (per PRD, cross-checked against what already exists)

| Category | Source | New curation needed? |
|---|---|---|
| Opening (dense boards) | New — no existing dense-opening-specific fixture found | Yes — curate ~50-100 FENs from ply 1-15 of existing SPRT/tactical game logs, or generate from the existing `OpeningBook`'s polyglot entries (already a committed, tested asset — `OpeningBook.java`) |
| Middlegame | New | Yes — same sourcing approach, ply 15-40 |
| Endgame (sparse boards) | Partial — `engine-core/src/test/resources/regression/draw_failures.epd` and the tactical suite include some sparse positions, but not curated *as* an endgame category | Yes — light curation from existing regression fixtures + new sparse positions |
| Tactical | **Exists**: `engine-core/src/test/resources/tactical/mate_2_3_50.epd`, consumed by `TacticalSuiteTest` | **No** — direct reuse |
| Quiet | **Exists**: `tools/quiet-labeled.epd` / `data/quiet-labeled.epd` (Texel corpus) | **No** — subset selection only, no new labeling |
| Fixed-seed random-legal | New | Yes — but this is code, not data: a seeded RNG generator (reusing `NnueIncrementalVsRebuildFuzzTest`'s existing random-legal-game machinery, already proven correct) writing out N FENs at a fixed seed, committed once, never regenerated (reproducibility requirement below) |

**Maintenance cost, stated plainly:** two of six categories are zero-cost
(direct reuse). Four need one-time curation (opening/middlegame/endgame
manually curated or book-derived; random-legal is a one-time seeded
generation script run once and the *output* committed, not regenerated per
run). None require ongoing maintenance beyond what any committed test
fixture already requires — no live data pipeline, no external service
dependency.

### Data sources

- Opening/middlegame: derived from `OpeningBook`'s existing polyglot data
  (already-trusted, already-tested) plus manual curation for
  middlegame-specific tactical/positional variety.
- Endgame: existing regression fixtures + hand-curated additions for sparse
  material coverage (KR vs K, KQ vs K, etc. — this repo already has explicit
  endgame-handling tests, `EndgameHandlingTest.java`, confirming those
  position types are already well-understood here).
- Tactical/Quiet: direct reuse, zero new sourcing.
- Random-legal: generated once by a seeded script, not sourced externally.

**No Stockfish-labeling dependency for Phase C.** PRD's "golden corpus" only
needs *expected int16 evals for the CI net* — a synthetic, hand-crafted
network the engine itself produces deterministic output for. This is
categorically different from the Texel corpus's SF-labeled quiet positions
(which already exist and need no new labeling either). No new
Stockfish-labeling infrastructure is Phase C scope.

### Versioning

- Corpus files live under a new `bench/nnue-corpus/` directory (parallel to
  the existing `bench/profiler-baseline.md`), one `.epd`/`.fen` file per
  category, committed to the repo directly — same versioning model as every
  other committed test fixture in this repo (`git` history is the version
  history; no separate versioning scheme needed).
- The golden-eval subset (category positions with pinned expected int16
  evals against the CI test net) is a **separate, smaller file**
  (`bench/nnue-corpus/golden-evals.csv`: FEN, expected eval) — versioned
  identically, but must be regenerated (not hand-edited) if the CI test
  net's weights ever change, since the expected values are net-specific.

### Reproducibility

- Random-legal category: the seed is committed as a constant in the
  generator script/test, and the *generated output* is what's committed to
  `bench/nnue-corpus/` — the generator is run once by a human, never by CI,
  so there is no risk of CI producing a different corpus on a different
  run. (This mirrors how `NnueIncrementalVsRebuildFuzzTest` already commits
  to "fixed seed in CI for reproducibility, unfixed nightly" per PRD — the
  *corpus* is fixed always; only the *fuzz test's own* per-run legal-game
  sampling varies nightly, a separate concern from this static corpus.)
- Golden evals: reproducible by construction — re-running the CI test net
  over the committed FEN list must always produce the committed expected
  values, or the golden-eval CI job (Task 4) fails by design.

### Regression thresholds and failure criteria

Both already fixed by the PRD, not new decisions:

- **Per-category regression >10%** (eval throughput or fixed-depth NPS)
  without a documented explanation blocks merge of the offending PR
  (PRD, "Regression tracking," verbatim).
- **Golden-eval mismatch is a hard failure**, not a threshold — any FEN
  whose evaluated int16 score differs from the committed expected value
  fails the build, with the offending FEN printed (PRD, "Golden position
  corpus," verbatim).
- The overall aggregate NPS gate (CLAUDE.md §3, currently 301,116 floor)
  is **unchanged and untouched** by this corpus — per-category figures are
  *tracked*, not substituted for the single gated number, exactly as PRD
  states ("The §1 performance gate remains the single overall nps number;
  per-category figures are tracked, not gated").

---

## Task 4 — Continuous validation (CI) design

All jobs reuse the existing `ci.yml` push/PR trigger structure and Maven
`-Dgroups=<tag>` profile convention (already proven for the NPS-benchmark
gate) — no new CI mechanism is introduced, only new tagged test classes and
new `mvn` invocations in the existing workflow file.

| Check | Job | Trigger | Mechanism |
|---|---|---|---|
| Deterministic loader validation | Existing suite, extended | Every PR (already in `mvn -B clean test`) | New test: parse the CI net twice from the same bytes, assert byte-identical in-memory `short[]` arrays |
| Malformed network validation | Existing suite, extended | Every PR | Fills out the remaining reject-path matrix beyond the freeze patch's two cases (bad magic — exists; oversized `hiddenWidth` — exists; truncated file — exists; **new**: corrupt UTF strings, negative `qa`/`qb`, zero `hiddenWidth` edge exactly at the boundary) |
| Golden eval tests | New, tagged `@Tag("nnue-golden")` | Every PR touching `engine-core` (PRD's own trigger condition, verbatim) | Loads the committed CI test net + `golden-evals.csv`, asserts exact int16 match per FEN |
| Incremental vs. rebuild | Existing (`NnueIncrementalVsRebuildFuzzTest`), extended | PR: fixed seed (already the case). **Nightly**: unfixed seed, larger game count | Reuses `nightly-sprt.yml`'s existing `schedule: cron` + `workflow_dispatch` pattern — either a new job in that file or a new sibling workflow, not a new CI mechanism |
| Feature extraction validation | Existing (`FeatureIndexParityTest`, `FeatureExtractorMoveTypeTest`) | Every PR (already the case) | No new mechanism — already covers this per PRD's "Active feature dump... catches mirroring and indexing errors" |
| Loader regression tests | Existing, extended per malformed-network row above | Every PR | Same mechanism, filling the test matrix |
| NNUE-mode regression tests | New: run the *existing* `SearchRegressionTest`/tactical-suite/perft-adjacent suites a second time with `EvalType=NNUE` + the CI test net, tagged `@Tag("nnue-mode")` | Every PR touching `engine-core` or `engine-uci` | Reuses existing regression test *content*, just runs it a second time under a different `EvaluatorStrategy` — this is the PRD's "bench in NNUE mode" / "tactical suite completes in NNUE mode" exit criterion made concrete |
| Classical-mode invariance | **Already exists and already passes** — every existing suite runs in classical mode today, unconditionally, since `ClassicalEvaluator` is still the default | Every PR (no change) | Confirms nothing new breaks the reference engine; PRD's own wording ("NNUE work must never perturb the reference engine") is already mechanically guaranteed by NNUE being opt-in via `EvalType`, not a new job to write |
| Benchmark execution | New: per-category eval throughput + fixed-depth NPS over `bench/nnue-corpus/`, both evaluators | Every PR (throughput tracked, not gated, per Task 3) | New Maven-tagged test or a small harness class under `tools/`, following the existing `BenchRunner`/profiler-baseline precedent |
| Oracle bound | New, **reporting only in Phase C** (Task 1's stated boundary) | Nightly (batch job over the full corpus is heavier than a PR-blocking check should be) | `NnueOracle.compareInt16VsFloat32` run over the whole `bench/nnue-corpus/`, results logged, no gate yet |

### Nightly vs. PR separation — explicit rule

**PR-blocking (fast, deterministic, fixed-seed):** loader validation
(all reject-paths), golden evals, feature extraction, NNUE-mode regression
suites, classical-mode invariance, benchmark throughput (tracked not
gated), incremental-vs-rebuild fuzz at fixed seed.

**Nightly-only (slower, or intentionally non-deterministic):**
incremental-vs-rebuild fuzz at unfixed seed with a larger game count (finds
rarer bugs, too slow/flaky to block every PR), the oracle-bound batch report.

**Explicitly excluded from this plan, per the user's own instruction:**
trainer-reproducibility CI job — depends on the Phase D trainer, which does
not exist.

---

## Task 5 — Documentation recommendations

- **Architecture docs:** none needed beyond ADR-002/003/004/005/009, already
  written and frozen. Phase C introduces no new architectural decision with
  real alternatives to weigh — every design choice in this plan (Task 2's
  `instanceof`-on-concrete-type pattern, Task 4's reuse of the existing CI
  profile convention) is a direct application of an already-accepted
  pattern, not a new one. **No new ADR is recommended for Phase C**, per
  the user's own instruction to only add one if a genuinely new decision
  exists — none does.
- **Developer docs:** one new file, `docs/nnue-debug-tooling.md` — a short,
  practical reference for the seven tools in Task 2 (what each does, when
  to reach for it, example UCI command output). This is documentation *of*
  the tools, not an architectural decision *about* them, so it's a plain doc
  file, not an ADR.
- **Debugging docs:** folded into the same `docs/nnue-debug-tooling.md` —
  splitting "developer docs" and "debugging docs" into two files for seven
  small tools would be over-structuring; one file, two clearly-headed
  sections.
- **Benchmark docs:** one new file, `bench/nnue-corpus/README.md` —
  category descriptions, regeneration instructions for the random-legal
  category (in case a future phase needs a larger corpus), and the
  golden-eval regeneration procedure. Mirrors the existing
  `bench/profiler-baseline.md`'s role (a benchmark-methodology doc living
  next to the benchmark data itself).
- **Release docs:** none — Release Reports are out of scope (Task 1).

---

## Task 6 — PR structure

Five PRs, each independently mergeable, each leaving `develop`-mergeable
`phase/15-nnue` state releasable at every point (per CLAUDE.md's
zero-functional-change-until-ready discipline already demonstrated across
Phase A/B's own PR splits).

### PR C-1 — Debug tooling core (accumulator inspector, rebuild comparator, feature dump wiring)

- **Scope:** `NnueEvaluator.dumpAccumulators()`, `NnueEvaluator.verifyAgainstRebuild()`,
  making `currentAccumulator` reachable for the inspector (either widen its
  visibility or have `dumpAccumulators` be the only reader — no public
  accessor needed if `dumpAccumulators` itself does the formatting
  internally, which is the smaller-surface choice, see Risk Review). No UCI
  wiring yet — pure `engine-core` addition, test-verified in isolation.
- **Dependencies:** none — builds directly on frozen Phase B.
- **Acceptance criteria:** new methods compile and are covered by
  `NnueEvaluatorDebugTest`/`NnueEvaluatorRebuildComparatorTest`; full
  reactor suite green; zero change to any existing method's behavior
  (provable the same way Phase A's signature-only PR proved
  behavior-neutrality — an unchanged `NodeCountRegressionTest`).
- **Rollback:** revert the single commit; nothing else in the codebase
  references these new methods yet (no UCI wiring in this PR), so rollback
  has zero blast radius.

### PR C-2 — Oracle comparator + eval breakdown

- **Scope:** New `NnueOracle` class (test/CI scope, per Task 2's critical
  evaluation — no UCI command). `NnueEvaluator.explainEval(Board)`, wired
  into the existing UCI `eval` command. **Correction from an earlier draft:**
  `handleEval()` today builds a classical `Evaluator` unconditionally, with
  no branch on `EvalType` at all — there is no existing branch to extend.
  This PR adds the first one: `handleEval()` gains an `EvalType`-based
  branch, calling `NnueEvaluator.explainEval` for NNUE and leaving the
  existing classical call path completely untouched. This is the one place
  this PR touches already-shipped UCI code, and it is a genuine small
  addition to `handleEval()`, not a no-op extension of something already
  branching — flagged here explicitly so it gets the same scrutiny as PR
  C-3's `nnue eval`-vs-existing-`eval` routing call (see Risk Review).
- **Dependencies:** PR C-1 merged (breakdown reuses the accumulator-read
  shape established there).
- **Acceptance criteria:** `NnueOracleTest` green (dequantization math
  verified against hand-computed values); `eval` command's existing
  classical-mode test cases unchanged and still passing; new NNUE-mode
  `eval` test cases pass.
- **Rollback:** revert; the `eval` command's classical branch is untouched
  code, so classical UCI behavior is unaffected even mid-revert.

### PR C-3 — `NnueDebug` mode, debug assertions, debug UCI commands

- **Scope:** New UCI boolean option `NnueDebug` (default `false`); new
  `NnueEvaluator` constructor overload taking a `debugMode` flag (default
  path unchanged — existing single-arg constructor still works, defaults to
  `false`, so every existing call site in `UciApplication` is source-
  compatible with zero edits unless explicitly opting in); the probabilistic
  incremental==rebuild assertion + stack-depth invariant, internal to
  `NnueEvaluator`; new `nnue features`/`nnue acc`/`nnue verify` UCI commands
  (not `nnue eval`, already covered by PR C-2's extension of the existing
  `eval` command).
- **Dependencies:** PR C-1 (rebuild comparator) and PR C-2 (established the
  UCI-branching pattern for NNUE-specific commands).
- **Acceptance criteria:** `NnueEvaluatorDebugModeTest` proves the assertion
  fires on injected corruption within a bounded move count **via a logged
  `info string` diagnostic, and explicitly asserts no exception propagates
  out of `onMake`** — this is Risk Review's log-not-throw decision made a
  checked test assertion, not just a design-doc statement a later
  implementer could satisfy either way; new `UciApplicationIntegrationTest`
  cases for all three commands, `NnueDebug` on/off, NNUE/Classical active;
  **explicit performance test**: bench with `NnueDebug=false` shows zero
  measurable NPS delta vs. pre-PR baseline (this is the task-8 "production
  performance unaffected when disabled" criterion, made a concrete, checked
  acceptance criterion here rather than an assumption).
- **Rollback:** revert; `NnueDebug` defaults `false`, so even a partial
  revert leaves the option present-but-inert, never a half-migrated state
  that changes default behavior.

### PR C-4 — Benchmark corpus + benchmark CI job

- **Scope:** `bench/nnue-corpus/` directory (six category files +
  `golden-evals.csv` + `README.md`); new benchmark-throughput test/harness;
  new golden-eval CI test.
- **Dependencies:** none on PR C-1/C-2/C-3 — this is pure data + a new test
  class, independently developable in parallel if desired, sequenced last
  here only because its CI job (Task 4) is easiest to review once the
  debug-tooling PRs have established the module's test-tag conventions.
- **Acceptance criteria:** all six categories present and non-empty; golden
  evals reproducible (re-running the generator against the committed CI net
  produces byte-identical `golden-evals.csv`); new CI job passes on a clean
  `develop`-based checkout, not just the feature branch.
- **Rollback:** revert; no other code references these files yet within
  this plan's scope (Task 4's CI-job additions are PR C-5, not this PR —
  keeping data and CI-wiring separate means a bad corpus file and a bad CI
  job are two independently revertible mistakes, not one).

### PR C-5 — CI workflow wiring (PR-blocking + nightly jobs)

- **Scope:** `ci.yml` additions (golden-eval job, NNUE-mode regression job,
  malformed-network matrix expansion, benchmark-throughput job — all
  PR-blocking per Task 4's table) and either a new nightly workflow or an
  addition to `nightly-sprt.yml` (unfixed-seed fuzz, oracle-bound batch
  report — both nightly-only per Task 4's table).
- **Dependencies:** PR C-1 through C-4 all merged (this PR wires CI around
  code/data that must already exist).
- **Acceptance criteria:** a full CI run (both the PR-triggered and, via
  `workflow_dispatch`, the nightly job) completes green on `phase/15-nnue`
  itself; NNUE-mode regression job explicitly confirmed to run under
  `EvalType=NNUE` + the CI test net (not silently falling back to
  Classical, which the existing `resolveNnueNetworkForSearch` fallback
  logic could otherwise mask — this must be checked, not assumed).
- **Rollback:** revert the `ci.yml`/`nightly-sprt.yml` diff; all other PRs'
  code/data remain valid and mergeable on their own, since CI wiring is the
  only thing reverting.

---

## Task 7 — Risk review

### PR C-1: exposing `currentAccumulator` vs. keeping it internal to `dumpAccumulators`

- **Why recommended:** keep `currentAccumulator` package-private and have
  `dumpAccumulators()` be the *only* public surface — smaller API, and the
  PRD's own framing ("dump both perspective accumulators... in a stable
  text format") describes a formatting tool, not a raw-array accessor.
- **Alternative:** widen `currentAccumulator` to public.
- **Why rejected:** a public `short[]`-returning accessor invites a caller
  to mutate the live accumulator array by reference (Java arrays are
  mutable, no defensive copy implied) — a debug tool that can silently
  corrupt production state on misuse is worse than one with a narrower,
  string-returning surface. `dumpAccumulators()` returning a `String` is
  read-only by construction.
- **Hidden complexity:** none — this is strictly simpler than the
  alternative.
- **Maintainability/debugging risk:** low; if a future tool genuinely needs
  raw numeric access (not just a formatted dump), that's a new, narrow,
  purpose-built accessor added when that need is real, not now speculatively.
- **CI risk:** none.

### PR C-3: probabilistic debug assertion touching the hot path when enabled

- **Why recommended:** PRD explicitly names this as Phase C scope
  ("`NnueDebug` mode enables the probabilistic incremental==rebuild
  assertion during search"), and sampling (not every call) is the PRD's own
  mitigation for cost.
- **Alternative 1:** assert on every `onMake`, not probabilistically.
- **Why rejected:** `verifyAgainstRebuild` does a full feature-set rebuild
  for comparison — running that on every single move in a debug search
  would make debug mode unusably slow for anything beyond a handful of
  plies, defeating its own purpose (interactively narrowing down a desync).
- **Alternative 2:** no debug assertion at all — rely solely on the
  interactive `nnue verify` command for manual investigation.
- **Why rejected:** the PRD names silent accumulator desync as *the*
  dominant NNUE bug class specifically because it doesn't announce itself —
  by the time a human thinks to run `nnue verify`, the search has already
  moved on. An automatic, sampled assertion catches it closer to the
  causing move, which is the entire diagnostic value of the feature.
- **Hidden complexity:** what should the assertion actually *do* on
  failure — throw (crashes the search mid-game, bad for a running UCI
  engine even in debug mode) or log-and-continue (silent in a different
  way — a logged error a human has to notice)? **This plan recommends
  log-and-continue with a loud, prefixed `info string` (UCI's own
  out-of-band channel, always visible to the GUI/harness), not a thrown
  exception** — a debug tool that crashes the process it's debugging is
  actively hostile to the diagnosis workflow it exists to support. This is
  a real design decision within PR C-3, called out here rather than buried
  in the PR's own description, precisely so it gets scrutinized before
  implementation, not discovered during it.
- **Maintainability risk:** a sampled assertion is inherently
  non-deterministic in *when* it fires — `NnueEvaluatorDebugModeTest` must
  test "fires within N moves" (bounded probabilistically), not "fires on
  move exactly K," or the test itself becomes flaky. Flagged explicitly so
  the test's own design doesn't reintroduce the flakiness this feature is
  trying to catch.
- **Debugging risk:** low once the log-vs-throw decision above is made
  correctly; high (a crashing debug mode) if it isn't.
- **CI risk:** none — `NnueDebug` defaults `false`, so CI's own regression
  suites never pay this cost unless a job explicitly opts in.

### PR C-2: `NnueOracle` as a standalone class vs. a method on `NnueEvaluator`

- **Why recommended:** keeps the int16 hot-path class (`NnueEvaluator`)
  free of any float-arithmetic code path, import, or method — even an
  unreachable one. A reader auditing `NnueEvaluator` for hot-path purity
  (exactly the kind of audit CLAUDE.md §3 invites) should never have to
  mentally filter out "this method is float32-only, ignore it."
- **Alternative:** a method on `NnueEvaluator`, e.g.
  `NnueEvaluator.floatOracleCompare()`.
- **Why rejected:** couples two genuinely separate concerns (production
  int16 inference; test-only float32 verification) into one class file,
  the exact "Divergent Change" smell — one class now has two unrelated
  reasons to change (a production inference bugfix, or an oracle-precision
  tooling change).
- **Hidden complexity:** `NnueOracle` needs read access to `NnueNetwork`'s
  currently-package-private `ftWeights()`/`outputWeights()`/etc. accessors
  — since it lives in the same `core.eval.nnue` package, this is free
  (package-private is already sufficient), no visibility widening needed.
  Confirmed by checking `NnueNetwork.java`'s existing accessor visibility
  before committing to this design.
- **Maintainability/debugging/CI risk:** low across the board — this is the
  narrowest-coupling option available.

### PR C-2: routing NNUE's breakdown through the existing `eval` command vs. a new `nnue eval` command

- **Why recommended:** the PRD names this tool `nnue eval`, and PR C-3
  groups it with the other `NnueDebug`-gated commands in that same
  namespace — but this plan instead extends the *existing*, always-available
  `eval` command to branch on `EvalType`, ungated by `NnueDebug`. This is a
  deliberate deviation from the PRD's literal naming, made explicit here for
  the same scrutiny the oracle-comparator UCI exclusion above already got
  (an earlier draft of this plan gave that decision a flag and left this one
  unflagged — an inconsistency, now corrected).
- **Alternative:** a separate `nnue eval` command, `NnueDebug`-gated, exactly
  as PRD names it, alongside `nnue features`/`acc`/`verify`.
- **Why rejected:** the classical `eval` command is already unconditional,
  ungated, always-on production UCI surface (`explainEval` is not a debug
  feature for `ClassicalEvaluator` — it's a normal diagnostic any GUI can
  call any time). Gating NNUE's equivalent behind `NnueDebug` would make the
  *same conceptual command* behave inconsistently depending on which
  evaluator is active — available for Classical, refused for NNUE unless a
  debug flag is set. Extending the one existing `eval` command to handle
  both evaluator types keeps the command's meaning ("explain the current
  static evaluation") consistent regardless of `EvalType`, which is the more
  honest interpretation of "eval breakdown" as a tool, even though it means
  diverging from the PRD's exact command-name grouping.
- **Hidden complexity:** none beyond the branch itself — `handleEval()`
  gains one `if (EvalType == NNUE)` check, mirroring how `resolveNnueNetworkForSearch()`
  already branches on `EvalType` elsewhere in the same class.
- **Maintainability risk:** low; a future reader might expect `nnue eval` to
  exist as a named command per the PRD and be briefly confused it doesn't —
  mitigated by documenting this exact deviation and its reasoning in
  `docs/nnue-debug-tooling.md` (Task 5), not just in this plan.
- **Debugging/CI risk:** none — this changes nothing about `EvaluatorStrategy`
  or either evaluator's behavior, only which UCI command string reaches
  `NnueEvaluator.explainEval`.

### Task 6 (PR structure) overall: five PRs vs. fewer/more

- **Why five:** each PR maps to one reviewable capability with a clean
  dependency edge to the next (C-1 primitives → C-2 breakdown/oracle → C-3
  debug mode/UCI → C-4 corpus data → C-5 CI wiring around all of it),
  mirroring Phase B's own three-PR precedent (hook change → infrastructure
  → integration) at a slightly finer grain because Phase C's scope is
  broader (seven tools + a corpus + a CI matrix vs. Phase B's one
  evaluator).
- **Alternative:** two PRs (all debug tooling in one; corpus+CI in another).
- **Why rejected:** a single "all debug tooling" PR bundles the oracle
  comparator's design decision (standalone class, test-only) with the debug
  assertion's design decision (log-vs-throw, sampling rate) — two
  independently-controversial-enough choices that a reviewer should be able
  to approve one while still pushing back on the other, which the
  two-PR split doesn't allow.
- **Alternative:** seven PRs, one per tool.
- **Why rejected:** several tools share almost all their review surface
  (accumulator inspector and rebuild comparator both touch
  `NnueEvaluator`'s accumulator internals; splitting them into separate PRs
  would mean reviewing the same file twice for closely related reasons —
  Fowler's "Divergent Change" in the other direction, artificially
  fragmenting one coherent change).

---

## Task 8 — Final review

- [x] **No frozen architecture is being redesigned.** Verified per-PR above:
  every addition is either a new method on an existing concrete class, a
  new standalone class in the same package, or new UCI option/command
  parsing — `EvaluatorStrategy`'s interface, `NnueNetwork`'s loader logic,
  `FeatureExtractor`'s delta logic, the ownership model, and all five ADRs
  are read but never modified by any PR in this plan.
- [x] **No unnecessary abstractions are introduced.** No new interface is
  created anywhere in this plan — `NnueOracle` is a concrete utility class
  with static methods (Task 7's own reasoning), not an interface with one
  implementation; the debug-assertion mechanism is a boolean flag plus
  reused existing methods, not a new strategy/visitor abstraction.
- [x] **No interface pollution is occurring.** Every debug method lands on
  a concrete class (`NnueEvaluator`, `NnueOracle`), never on
  `EvaluatorStrategy` — the exact discipline ADR-004 already established
  for pawn-hash config, applied consistently here.
- [x] **Debug tooling remains additive.** Confirmed per PR: PR C-1's new
  constructor overload preserves the existing single-arg constructor
  unchanged; PR C-3's `NnueDebug` UCI option defaults `false`; every new
  method is new, none replace or change the signature of an existing one.
- [x] **Production performance is unaffected when debug features are
  disabled.** Stated as an explicit, checked acceptance criterion in PR
  C-3 (a real NPS-delta bench comparison), not an assumption — this is the
  one item in this checklist elevated from "design review claim" to
  "required test," per the Global Constraints' own standard.

---

## Self-Review

**Spec coverage:** All 8 user-numbered tasks have a corresponding section
above (Task 1 scope validation; Task 2 all seven tools with the five
required attributes each, plus the critical evaluation; Task 3 all seven
required sub-points; Task 4 all ten required checks plus the nightly/PR
split, trainer-reproducibility explicitly excluded; Task 5 all five doc
categories addressed, ADR question answered with reasoning; Task 6 five PRs
each with all four required fields; Task 7 challenges the major decisions
from Tasks 2/3/6 with alternatives and rejections, not just restating the
recommendation; Task 8 all five required verifications, each tied to
concrete evidence in this plan rather than asserted). No gaps found.

**Placeholder scan:** No "TBD"/"add appropriate X" found. The one
deliberately-deferred numeric value (oracle drift threshold) is named as
deferred-with-reason (ADR-002's own Open Questions), not a placeholder.

**Type consistency:** New method names introduced in Task 2's table
(`dumpAccumulators`, `verifyAgainstRebuild`, `compareInt16VsFloat32`,
`explainEval`) are referenced identically in Task 6's PR scopes and Task
7's risk review — checked for drift across sections, none found.
