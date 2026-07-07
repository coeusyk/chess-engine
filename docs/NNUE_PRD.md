# PRD: NNUE Evaluation for the Chess Engine

**Status:** Draft for review
**Date:** 2026-07-07
**Decision basis:** Ten decisions resolved via structured grilling session, amended by adversarial review (see Appendix A).

---

## 1. Executive Summary

**Problem Statement.** The engine's handcrafted evaluation (`Evaluator.java`, 726 lines) has reached the point of diminishing returns via Texel tuning, and its monolithic structure blocks experimentation with learned evaluation. There is no abstraction between search and evaluation.

**Proposed Solution.** Introduce an `EvaluatorStrategy` interface with two implementations — the existing classical evaluator and a new, self-built NNUE evaluator: plain 768-feature dual-perspective network, (768→256)×2→1 with clipped ReLU, int16 scalar inference, trained by a self-written PyTorch pipeline on staged data (public text-format datasets → local Stockfish labeling → self-play).

**Success Criteria (all three gates must pass before NNUE becomes the default evaluator):**

1. **Correctness gate:** Incremental accumulator state equals full-rebuild state after every move type (normal, capture, promotion, castling, en passant, null move), verified by fuzz tests over ≥100k random game plies and by a debug-mode assertion sampled during real searches. Zero failures. All existing regression suites (perft, draw rules, tactical suite, SearchRegressionSuite) pass unchanged in classical mode.
2. **Performance gate:** NNUE-mode search achieves ≥40% of classical-mode nps on the standard bench positions; engine memory overhead ≤ 32 MB beyond classical mode (network + accumulator stack).
3. **Strength gate:** NNUE build passes SPRT vs. the classical build with H0 = 0 Elo, H1 = +10 Elo, α = β = 0.05, at the project's established SPRT time control (per `docs/sprt-guidelines.md`), same binary, only `EvalType` flipped.
4. **Reproducibility:** A documented, scripted path from raw data to a loadable `.nnue` file that a second person could run end-to-end.

### Strength Measurement Policy (applies to every claim in this document and the project)

The engine has no formally established rating: it has not undergone a CCRL-style rating process, and informal play against Chess.com bots is neither reproducible nor calibrated. Accordingly:

- **No absolute Elo is claimed anywhere** unless obtained through a statistically valid, reproducible rating methodology (e.g., a rating-list process with documented conditions). Until then, the engine's strength is described only through objective engineering evidence: perft correctness, regression suites, tactical-suite scores, benchmark-corpus results, SPRT outcomes, and reproducible self-play experiments.
- **All Elo figures in this document are relative** — SPRT hypothesis bounds and self-play deltas between two identified builds under recorded conditions. They measure "A vs. B here," never "the engine's rating."
- **Informal testing** (Chess.com bots, ad-hoc engine matches) is welcome as a sanity check and bug hunt, but is never cited as a strength measurement, never appears in release reports as evidence, and never gates a decision.

---

## 2. User Experience & Functionality

### Personas

- **The maintainer (primary):** A developer whose explicit goal is to understand every layer of NNUE by building it. Deliverables are judged on comprehensibility and documentation as much as strength.
- **Engine users (GUI/UCI):** Interact only through UCI options; must be able to select evaluator and network file without rebuild.
- **Future contributors:** Need the classical evaluator preserved as a readable reference and the NNUE code isolated and documented.

### User Stories & Acceptance Criteria

**US-1.** As the maintainer, I want search decoupled from any specific evaluator so that evaluators can be swapped without touching search logic.

- `Searcher` depends only on an `EvaluatorStrategy` interface; no direct references to `Evaluator` or `NnueEvaluator` types in search code.
- Refactor is behavior-neutral: identical bench node counts and all existing tests pass before any NNUE code lands.
- Interface exposes lifecycle hooks: `reset(Board)`, `onMake(Board, move, undoInfo)`, `onUnmake()`, `onMakeNull()`, `onUnmakeNull()` — all default no-ops so `ClassicalEvaluator` is unaffected.

**US-2.** As the maintainer, I want a pure-NNUE evaluator selectable at runtime so that A/B comparisons are attributable to exactly one system.

- UCI options: `EvalType` (`Classical` | `NNUE`, default `Classical` until the strength gate passes) and `EvalFile` (path to network).
- NNUE mode performs no blending with classical terms.
- Invalid/missing/corrupt network file → engine reports the error via UCI `info string` and falls back to Classical rather than crashing.

**US-3.** As the maintainer, I want int16 inference validated against a float32 oracle so that quantization and implementation bugs are separable.

- Float32 reference inference exists in test scope only (never on the search path).
- Tests assert |int16 eval − float32 eval| ≤ a documented bound on a fixed position corpus.

**US-4.** As the maintainer, I want a self-written PyTorch training pipeline so that every stage from data to weights is understood.

- Separate Python project (own repo or `trainer/` top-level directory — TBD at implementation).
- Covers: dataset ingestion, mmap binary intermediate format, model, loss, quantization-aware weight clipping, checkpointing, `.nnue` export.
- Java engine has zero dependency on the training framework; the `.nnue` format is the only contract.

**US-5.** As the maintainer, I want staged training data so that the pipeline is validated quickly but the engine ultimately learns from its own play.

- Stage 1: public **plain-text labeled dataset** (EPD/CSV with evals). Binpack parsing is a non-goal.
- Stage 2: local Stockfish UCI labeling driver (fixed nodes/depth, versioned engine, position filtering), reusing `PgnExtractor`/`PositionLoader` where practical.
- Stage 3: self-play generation with the engine's own search, labels = search score blended with game outcome (λ configurable).
- Retraining runs on mixed datasets are measured (SPRT/strength tests), not assumed superior.

### Non-Goals (v1)

- HalfKP/HalfKA or any king-relative feature set (feature extractor is seamed for later replacement, but no plugin framework is built).
- Multi-layer networks; general network-topology configuration (only hidden width is read from the file header).
- int8 quantization, sparse propagation, or Vector API in the initial implementation (Vector API is a designated later phase).
- Binpack or Lc0 data-format parsers.
- Hybrid classical+NNUE blending.
- Multi-threaded search (but the copy-per-ply accumulator stack must not preclude per-thread state later).
- Removing or deprecating the classical evaluator — it is permanent reference infrastructure.

---

## 3. AI System Requirements

### Network Specification

| Item | Decision |
|---|---|
| Features | Plain 768: piece(6) × color(2) × square(64), dual perspective (white-view and black-view accumulators; black view mirrors square vertically and swaps piece color) |
| Topology | (768 → 256) × 2 → 1; feature-transformer weights shared between perspectives |
| Activation | Clipped ReLU, clamp(x, 0, QA) |
| Inference precision | int16 feature transformer & accumulator; int32 output dot product; canonical from day one |
| Output scale | Centipawn-compatible (see calibration below) |

### Weight File Format (`.nnue`)

Versioned binary header: magic bytes, format version (u32), architecture id, feature-set id, hidden width (u32), quantization scheme version, quantization scales (QA, QB, output scale). Body: FT weights (int16, 768 × width), FT biases (int16, width), output weights (int16, 2 × width), output bias (int32). Future topology changes are a version bump, not a new loader.

**Provenance (mandatory for every exported network).** Every `.nnue` must answer "what produced this network?" without guessing:

- **Embedded in the header (small, fixed-size):** network UUID, trainer git commit (short hash), creation timestamp. Enough to identify a stray file found on disk.
- **Sidecar manifest (`<uuid>.json`, exported atomically with the `.nnue`):** format version, architecture id, feature set, hidden width, quantization version, trainer version + full git commit, dataset identifier(s) with stage labels (public/SF-labeled/self-play mix proportions), complete training configuration (hyperparameters, seed, λ, K), label-engine version where applicable, and the UUID as join key.
- Manifests for candidate and released nets are committed to a `nets/` registry in the repo (manifests only — never the weight binaries, except the tiny CI net).
- The engine prints the network UUID via `info string` on load, so any test log or SPRT run is traceable to an exact training run.

### Trainer Architecture (PyTorch, self-written)

The trainer is composed of small, separately testable stages with explicit interfaces — not a plugin framework, just separation of responsibilities so new data sources never touch the training core:

| Component | Responsibility | Contract |
|---|---|---|
| `DatasetProvider` | Yield raw labeled positions from one source (text dataset, Stockfish labeler output, self-play output) | iterator of (position, label, metadata) |
| `Transform` | Filtering/deduplication/phase balancing, composable in a declared order | records in → records out |
| `FeatureEncoder` | Position → 768-feature indices; the Python mirror of the Java extractor, shared by all providers | position → sparse indices |
| `Labeler` | Produce/blend targets (search score, WDL, λ blend, K scaling) | record → training target |
| `Trainer` | Model definition, loss, optimization loop; consumes only encoded shards | shards → checkpoints |
| `Validator` | Held-out metrics, correlation vs. labels, eval-scale check vs. classical | checkpoint → report |
| `Quantizer` | float32 checkpoint → int16 weights with clipping verification | checkpoint → quantized tensors |
| `Exporter` | Quantized tensors + provenance → `.nnue` + manifest | tensors → artifact |

Adding a future data source (e.g., a custom generator) means writing one `DatasetProvider`; everything downstream is unchanged. Stage boundaries are file formats (mmap shards, checkpoints), so stages can be rerun independently.

### Trainer Requirements

- **Loss/target:** blend of sigmoid-scaled eval and WDL outcome; sigmoid scale K taken from the existing `KFinder` output so NNUE eval lands on the classical centipawn scale. **This is a hard requirement** — search margins (futility, aspiration, null-move, LMR thresholds in `Searcher`) are tuned to that scale; an uncalibrated net will produce false SPRT failures attributable to margin mismatch, not the net.
- **Overflow safety is a training-time constraint:** FT weights must be clipped during training such that (bias + Σ weights of the worst-case ~32 active features) cannot exceed int16 range after quantization. The clipping bound is derived from QA and documented in the trainer.
- **Data loading:** memory-mapped binary shards + numpy batching, specified up front — Python-side loading is the known bottleneck at the 50–100M position scale.
- **Reproducibility:** seeded runs, config-file-driven hyperparameters, checkpoint + export scripts committed.

### Evaluation Strategy (how we know the AI works)

1. **Unit level:** accumulator delta tests for every move type; incremental == full rebuild fuzz over random legal games; int16 vs float32 oracle bound; golden-file test loading a tiny committed CI network (a few KB) and asserting exact eval on fixed positions.
2. **Search level:** debug-mode probabilistic assertion (incremental == rebuild) during test searches; NNUE-mode engine completes the existing tactical suite without crashes or illegal moves.
3. **Training level:** loss curves tracked per run; trained net's correlation with labels on a held-out set reported; each candidate net gets a fixed-games gauntlet before full SPRT.
4. **System level:** the three release gates in §1.

### End-to-End Reproducibility

Target property: a second developer, given the repo, the trainer repo, and the documented commands, can reproduce a released network and its validation results without asking anyone. Process requirements (not implementation details):

1. **Dataset generation/acquisition** — each dataset has an identifier, an acquisition or generation script, and recorded provenance (source URL + checksum for public data; engine version + search limits + opening source for SF-labeled and self-play data).
2. **Labeling** — labeling runs are driven by a committed config (engine version, nodes/depth, filters); output shards carry the dataset identifier.
3. **Preprocessing** — transforms and their order are declared in config, not embedded in ad-hoc scripts; shard building is deterministic given dataset + config.
4. **Training** — seeded, config-file-driven; checkpoint includes the config and dataset identifiers.
5. **Export** — produces `.nnue` + manifest atomically (see Provenance, §4).
6. **Benchmarking** — the benchmark-corpus runs (§4) executed against the exported net, results stored alongside the manifest.
7. **SPRT** — test terms, opponent build id, and network UUID recorded per run under the existing `sprt-guidelines.md` conventions.

Each stage is a documented command; the chain of identifiers (dataset id → training config → UUID → SPRT log → release report, §4) is unbroken, so every strength claim traces back to raw data. Bit-exact retraining across GPU hardware is explicitly **not** promised (floating-point nondeterminism); what is promised is: same commands + same data + same seed → statistically equivalent net, and byte-exact quantization/export given the same checkpoint.

---

## 4. Technical Specifications

### Architecture Overview

```
engine-uci ──UCI options (EvalType, EvalFile)──▶ Searcher
                                                   │ holds
                                                   ▼
                                        EvaluatorStrategy (interface)
                                        evaluate(Board) + lifecycle hooks
                                          ├── ClassicalEvaluator (wraps existing Evaluator; hooks are no-ops)
                                          └── NnueEvaluator (package core.eval.nnue)
                                                ├── NnueNetwork      – weights, loaded from .nnue
                                                ├── FeatureExtractor – Board bitboards → active feature indices (replaceable seam)
                                                ├── AccumulatorStack – copy-per-ply, 2 × width int16 per ply (~1 KB/ply at 256)
                                                └── Inference        – scalar int16 forward pass (Vector API impl later, behind same seam)

trainer (Python/PyTorch, separate project)
  ingest (EPD/CSV → mmap shards) → train (calibrated loss, weight clipping) → export .nnue
data tooling: Stockfish UCI labeler; self-play generator (stage 2/3)
```

**Data flow during search:** `Searcher` makes/unmakes moves on `Board` exactly as today, and calls the corresponding hook at **every** make/unmake site — main search, quiescence, null-move, and any verification/singular search. `NnueEvaluator` copies the parent accumulator into the next stack slot and applies the feature delta derived from the move + undo info. `evaluate()` runs CReLU + output layer over the top-of-stack pair ordered by side to move. Unmake is a stack-pointer decrement. `reset(Board)` rebuilds from scratch on `position`/new game/root changes.

**Null move:** no piece deltas; accumulators are unchanged and only perspective ordering (side to move) changes at evaluation time. Hooks still push/pop a stack frame so ply indexing stays aligned.

### Integration Points

- **`Searcher`:** replace `private final Evaluator evaluator` (line 114) with `EvaluatorStrategy`; single eval call site (line 1652) is unchanged in shape. Hook calls added at all make/unmake sites — an explicit audit of these sites is a deliverable of Phase B, since a missed site causes silent accumulator desync (eval degrades without crashing; worst bug class).
- **`Board`:** untouched. Existing undo info (captured piece, move metadata) must be confirmed sufficient to derive feature deltas for all move types; if a field is missing, extend undo info, not Board's API surface.
- **`engine-uci`:** two new options; error-path fallback per US-2.
- **`engine-tuner`:** `PgnExtractor`, `PositionLoader`, `KFinder` reused for data extraction and scale calibration.
- **Pawn-hash / eval caches:** classical-only; `NnueEvaluator` does not share them.
- **CI:** tiny test network committed to the repo; full networks distributed via releases, never committed.

### Developer Tooling & Debugging (NNUE)

Purpose: minimize time-to-diagnosis for the dominant NNUE bug classes (silent accumulator desync, mirroring/sign errors, quantization saturation). These are development tools, not production features; they live in test scope or behind a single `NnueDebug` UCI option and must add zero cost to the normal search path.

**Inspection tools (Java, test/debug scope):**

- **Accumulator inspector:** dump both perspective accumulators for the current position (values, ply index, top-of-stack pointer) in a stable text format diffable across runs.
- **Rebuild comparator:** compute full-rebuild accumulators for the current position and report the first differing index and delta vs. the incremental state — the primary desync diagnostic. Exposed both as a test utility and via debug UCI.
- **Active feature dump:** list active (piece, square, perspective) → feature-index mappings for a FEN. Catches mirroring and indexing errors directly, without going through eval values.
- **Eval breakdown:** for one position, print accumulator pre-activation ranges, post-CReLU activation counts (how many neurons are clipped at 0 / at QA), output-layer contribution, final score in both int16 and float32 paths, and the difference. Saturation and dead-neuron problems become visible in one command.
- **Oracle comparator:** batch-run int16 vs. float32 inference over a position file; report max/mean absolute error and the positions exceeding the documented bound.

**Analysis tools (Python, trainer repo):**

- **Weight histogram + clipping report:** per-layer weight distributions at export time; flags weights at the clipping boundary (a saturated-training signal) and verifies the int16 overflow bound derived from QA.
- **Activation statistics:** run a sample corpus through the float model and report per-neuron activation frequency (dead/always-clipped neurons). Plain CSV output + a small notebook; no bespoke visualization app.
- **Quantization validator:** compares float checkpoint vs. quantized tensors vs. re-parsed `.nnue` round-trip — byte-exact on re-parse, bounded error on quantization.

**Debug assertions and corpus:**

- `NnueDebug` mode enables the probabilistic incremental==rebuild assertion during search (already a §3 requirement) plus accumulator stack-depth == search-ply invariant checks.
- **Golden position corpus:** a committed FEN set (drawn from the benchmark corpus categories below) with expected int16 evals for the CI net; any diff is a hard test failure with the offending FEN printed.
- **Debug UCI commands** (all gated behind `NnueDebug`, non-standard, documented as unstable): `nnue features`, `nnue acc`, `nnue verify` (rebuild comparator), `nnue eval` (breakdown). Chosen because a UCI GUI or script harness is the natural place a desync is first noticed.

### Benchmark Corpus

Overall bench nps is too coarse: NNUE cost varies with piece count (feature-delta size, refresh frequency) and search shape. Define one committed corpus, categorized, used by three consumers:

- **Categories:** opening (dense boards), middlegame, endgame (sparse boards), tactical (from the existing tactical suite), quiet positions, and a fixed-seed random-legal-position set (adversarial coverage for feature extraction).
- **Performance:** per-category eval throughput (evals/sec, both evaluators) and fixed-depth nps. Catches asymmetric regressions — e.g., an accumulator-refresh bug that only hurts endgames — that a single aggregate hides. The §1 performance gate remains the single overall nps number; per-category figures are tracked, not gated.
- **Correctness:** the corpus doubles as input to the fuzz/rebuild tests and the golden-eval set (subset with pinned expected values).
- **Regression tracking:** benchmark results recorded per commit (existing `bench` conventions); a tracked per-category regression >10% without explanation blocks merge of the offending PR.

### Continuous Validation (CI)

Permanent regression tests, run on every PR touching engine-core or the trainer-facing formats:

1. **Deterministic load:** parse the CI net twice, assert byte-identical in-memory weights; reject-path tests for truncated/corrupt/oversized files.
2. **Golden evals:** CI net over the golden corpus, exact int16 match.
3. **Incremental vs. rebuild:** fuzz over random legal games (fixed seed in CI for reproducibility, unfixed nightly) covering all special move types and null move.
4. **Oracle bound:** int16 vs. float32 within the documented error bound over the corpus.
5. **Reproducibility check (trainer CI):** a miniature end-to-end training run (tiny dataset, few steps, fixed seed) exports a `.nnue` + manifest; assert the export is bit-identical across two runs and the manifest validates against its schema.
6. **Classical invariance:** full existing suites in classical mode — NNUE work must never perturb the reference engine.

### Network Release Reports

Every candidate network that reaches gauntlet or SPRT testing produces a standardized report, generated by script (not hand-written) from the manifest, benchmark outputs, and test logs. Reports are committed to the `nets/` registry beside the network's manifest and are part of the project's permanent engineering history: two networks must be comparable from their reports alone, without inspecting training logs.

**Report contents (one template, checked into the repo):**

- **Identity:** network UUID, network version/name, creation date, trainer commit, engine commit used for testing, link to the provenance manifest.
- **Architecture:** feature set, topology, hidden width, quantization scheme + scales.
- **Data:** dataset identifiers, dataset composition (stage mix proportions: public / SF-labeled / self-play), position counts after filtering.
- **Training:** configuration summary (hyperparameters, seed, λ, K), validation metrics (held-out loss, label correlation, eval-scale check vs. classical).
- **Engine results:** benchmark-corpus results per category (eval throughput, fixed-depth nps) vs. the classical baseline and vs. the previous released net; memory usage; gauntlet results (opponents, conditions, score); SPRT results (terms, result, game count) — all relative measurements per the Strength Measurement Policy (§1).
- **Verdict:** which of the three release gates passed/failed; whether the net was released, rejected, or superseded.
- **Engineering notes:** known limitations (e.g., weak phases observed in gauntlet games), and follow-up experiments this net's results motivate.

A net without a complete report cannot be promoted to default — the report is the release artifact, the `.nnue` is just its payload.

### Security & Privacy

- Network loader must validate header magic, version, width, and exact expected file length before allocation; reject oversized or malformed files (a `.nnue` path is user-supplied input to the UCI process).
- No user data involved; training data provenance (dataset source, Stockfish version used for labels) recorded per net for licensing/originality transparency.

---

## 5. Risks & Roadmap

### Phased Rollout

Phases are sequential milestones with acceptance criteria; no calendar commitments.

| Phase | Scope | Exit criteria |
|---|---|---|
| **A — Evaluator seam** | `EvaluatorStrategy` interface, `ClassicalEvaluator` wrapper, hook plumbing (no-ops), UCI `EvalType`/`EvalFile` parsing | Behavior-neutral: identical bench node counts, all suites green, nps within noise |
| **B — NNUE core** | `.nnue` format + loader, feature extractor, accumulator stack, scalar int16 inference, float32 oracle, hand-crafted tiny net, make/unmake site audit; core debug tooling (accumulator inspector, rebuild comparator, feature dump, oracle comparator) | All §3 unit-level tests pass, incl. fuzz and special-move deltas; engine plays legal games with the tiny net |
| **C — Integration hardening** | `NnueDebug` mode + debug UCI commands, CI test net + golden evals, benchmark corpus + CI jobs (§4), error-path fallback, bench in NNUE mode | Tactical suite completes in NNUE mode; performance gate measured per category and reported (pass not yet required) |
| **D — Training pipeline** | Modular PyTorch trainer (§3 components), text-dataset provider, mmap shards, calibrated loss + clipping, quantization validator + weight histograms, export with provenance manifest, trainer reproducibility CI; Stockfish labeling driver | First real net trains end-to-end reproducibly with a complete manifest; held-out correlation reported; eval scale verified against classical on a corpus |
| **E — Validation & optimization** | Gauntlets, SPRT runs, profiling; Vector API optimization only if the performance gate demands it (validated against scalar); self-play data generation begins | All three §1 gates pass → NNUE becomes default. Otherwise iterate on the net (not the gates) |

Small, focused PRs per logical step; accumulator implementation and trainer loss/quantization code get mandatory review passes. Each phase produces an ADR where a decision was made (already drafted in Appendix A).

### Technical Risks

| Risk | Severity | Mitigation |
|---|---|---|
| Missed make/unmake hook site → silent accumulator desync | High | Phase B site audit; debug-mode incremental==rebuild assertion; fuzz tests |
| Eval scale mismatch destabilizes tuned search margins | High | KFinder-calibrated training targets; corpus-level scale comparison in Phase D exit criteria |
| Java scalar inference misses the nps gate | Medium | Copy-per-ply stack keeps updates cheap; Vector API phase held in reserve; width reduction (128) as last resort |
| int16 overflow from insufficient weight clipping | Medium | Clipping is a trainer requirement with a derived bound; oracle-vs-int16 tests catch saturation |
| First bootstrap net lands near parity → motivation/goalpost risk | Medium | Pre-committed SPRT terms; staged data plan expects iteration; gauntlet before SPRT saves compute |
| Training-data quality (stale labels, unbalanced phases) | Medium | Recorded provenance; position filtering in the labeling driver; held-out validation |
| Two-language project raises maintenance burden | Low | Hard contract boundary (.nnue format only); trainer fully documented and seeded |

### Open Questions (TBD before the affected phase)

1. Which public text-format dataset for Stage 1 (candidates: Zurichess quiet set, Lichess evaluated positions) — decide at Phase D start.
2. Training hardware (local GPU vs. cloud) — affects Stage 2/3 data volume targets only.
3. Self-play opening variety source (existing book vs. external opening suite) — Phase E.
4. Trainer location: separate repo vs. `trainer/` directory — Phase D start.

---

## Appendix A — Decision Record (from grilling session, 2026-07-07)

1. **Goal:** learning-first; build inference and training pipeline personally.
2. **Features:** plain 768 dual-perspective; HalfKA deferred; extractor behind a minimal replaceable seam (no plugin framework — amended by review).
3. **Network:** (768→256)×2→1 CReLU; only hidden width configurable, via file header (amended by review from "configurable topology").
4. **Precision:** int16 canonical inference + float32 test-only oracle; no float-first migration.
5. **Data:** staged bootstrap; Stage 1 restricted to plain-text datasets, binpack out of scope (amended by review); Stockfish labeling driver and self-play as Stages 2–3; mixed-data retraining measured.
6. **Trainer:** self-written PyTorch, engine-agnostic export; pure-Java trainer rejected (compute infeasible), bullet rejected (black box).
7. **Runtime:** one binary, UCI-switched, pure NNUE, classical evaluator permanent.
8. **Wiring:** lifecycle hooks on the evaluator interface; copy-per-ply accumulator stack; Board untouched; null-move hooks added (amended by review).
9. **Acceptance:** three independent gates; SPRT terms pinned at H0=0/H1=+10, α=β=0.05 (amended by review from "consistently outperforms").
10. **SIMD:** scalar reference first; Vector API as a validated optimization phase.

Review amendments rationale: binpack parser failed cost/benefit vs. text datasets; general topology config was untested-surface overengineering; eval-scale calibration and int16 clipping were missing hard requirements; SPRT terms pinned to prevent goalpost drift.

---

## Appendix B — Architecture Decision Records

ADRs live in `docs/adr/`, numbered, immutable once accepted (superseded, never edited). Each records: context, alternatives considered, decision, consequences, and revisit conditions.

### ADR-001 (full text): Plain 768 features instead of HalfKP/HalfKA

**Context.** The input feature set determines inference code complexity, accumulator maintenance rules, and training-data volume. It is the least reversible NNUE decision: changing it invalidates trained networks, the Java extractor, the Python encoder, and all golden tests.

**Alternatives considered.**

1. *HalfKP / HalfKA (king-relative features, Stockfish lineage).* Strongest known ceiling — eval becomes conditioned on king position, which plain features cannot express. Rejected for v1 because: (a) every king move invalidates that perspective's accumulator, requiring a full-refresh path — an additional, bug-prone code path in exactly the component with the worst silent-failure mode; (b) input space grows ~40×, requiring roughly an order of magnitude more training data than the staged data plan produces early on; (c) debugging is harder — feature indices depend on king placement, so the active-feature dump is no longer directly readable.
2. *Single-perspective 768.* Simplest correct implementation, but loses tempo/side-to-move asymmetry, measurably weaker, and the dual-perspective structure is the part worth learning. Rejected as a false economy.
3. *Plain 768, dual perspective (chosen).* No refresh path (accumulator updates are uniform for all moves), trainable at 50–100M positions, feature indices are human-decodable, and it is the proven hobby-engine baseline.

**Tradeoffs accepted.** Lower strength ceiling than king-relative sets; the net cannot learn king-context piece values. Accepted because v1's binding constraints are correctness, comprehension, and data volume — not ceiling.

**Revisit when ALL of:** (a) v1 has passed all three release gates; (b) self-play data generation sustainably produces ≥5–10× current volume; (c) a plateau in measured relative strength (SPRT/gauntlet deltas per the Strength Measurement Policy, §1) is demonstrated across ≥2 retrained nets at 768 (i.e., data and training improvements stop helping); (d) the incremental==rebuild test infrastructure is mature enough to validate a king-refresh path. Migration cost at that point: new `FeatureExtractor`/`FeatureEncoder` pair, format version bump, retrain — evaluator interface and search untouched by design.

### ADRs to write during implementation (one per phase where the decision lands)

- **ADR-002:** int16 canonical inference with float32 test oracle (vs. float-first migration) — Phase B.
- **ADR-003:** Copy-per-ply accumulator stack vs. in-place reverse deltas — Phase B.
- **ADR-004:** Evaluator lifecycle hooks vs. Board change-listeners — Phase A.
- **ADR-005:** Pure NNUE runtime vs. hybrid blending — Phase A.
- **ADR-006:** Self-written PyTorch trainer vs. pure-Java trainer vs. existing framework (bullet) — Phase D.
- **ADR-007:** Staged training data (public text → SF labeling → self-play); binpack exclusion — Phase D.
- **ADR-008:** Scalar-first inference with Vector API as a validated optimization phase — Phase E (written even if the Vector API is never needed, recording why).
