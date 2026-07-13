# Trainer Architecture Documentation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Document the future NNUE trainer as a first-class subsystem (`docs/architecture/NNUE_TRAINER_ARCHITECTURE.md`) and extend the existing graphify report generator to report Engine and Trainer architecture separately — no trainer implementation code.

**Architecture:** Deliverable 1 is a pure documentation task synthesizing existing decisions (`docs/NNUE_PRD.md` §3, Appendix A, the 5 frozen ADRs, and the shipped `NnueNetwork`/`FeatureExtractor` contracts) plus one new concept (Canonical Network IR) into one authoritative doc. Deliverable 2 is a ~30-line diff to `docs/architecture/graph-audits/generate_report.py`'s existing `module_of()`/`classify()` machinery, adding a module-group split, not new graph logic.

**Tech Stack:** Markdown (doc), Python 3 + networkx (report generator, already a project dependency per `generate_report.py`'s existing imports).

## Global Constraints

- No trainer implementation code (Python or Java) — documentation and one report-generator diff only.
- Do not write ADR-006/ADR-007 as standalone files in this plan — their content is referenced from PRD Appendix A but formal ADR extraction is a later, separate step (tracked as follow-up in the doc itself).
- Do not silently decide trainer location (separate repo vs. `trainer/` directory) — the PRD marks this open; the doc must present it as a reasoned recommendation pending explicit sign-off, not a fait accompli.
- The `.nnue` binary format (`NnueNetwork.java`) and feature-index formula (`FeatureExtractor.java`) are frozen contracts — describe them, do not redesign them.
- Follow existing ADR prose conventions (Context/Problem/Alternatives/Tradeoffs/Decision/Consequences/Revisit Conditions) for every major decision inside the new doc, per `docs/adr/ADR-002..009`.
- `generate_report.py` changes must not alter output for the existing C-1..C-5 report format beyond adding the new split — regenerating a report from the same two graph.json snapshots used for `C-5-ci-integration.md` must still produce the pre-existing sections unchanged, with the new split layered on top.

---

### Task 1: Write `docs/architecture/NNUE_TRAINER_ARCHITECTURE.md`

**Files:**
- Create: `docs/architecture/NNUE_TRAINER_ARCHITECTURE.md`

**Interfaces:**
- Consumes: `docs/NNUE_PRD.md` §3 "Trainer Architecture" (DatasetProvider/Transform/FeatureEncoder/Labeler/Trainer/Validator/Quantizer/Exporter stage table), §"End-to-End Reproducibility", Appendix A items 5–6, Appendix B ADR-006/007 stubs; `docs/adr/ADR-002-int16-canonical-inference.md` (quantization target: int16 FT weights/accumulator, int32 output dot product, `qa`/`qb`/`outputScale`); `NnueNetwork.java`'s documented binary layout (magic/version/architectureId/featureSetId/hiddenWidth/quantVersion/qa/qb/outputScale/networkUuid/trainerCommit/createdAtEpochSeconds + weight arrays); `FeatureExtractor.java`'s `featureIndex()` formula and `FeatureIndexParityTest.java`'s cross-language pinning comment.
- Produces: the canonical trainer-architecture reference every later Phase D planning task cites; a "Trainer Architecture Invariants" section that later CI/review work checks against; a stated recommendation (not a decision) on trainer repo-vs-directory location, flagged for grilling in Task 1a below.

- [ ] **Step 1: Draft the document structure**

Create the file with these top-level sections, in this order (mirrors the requested topic list, grouped so related concerns sit together):

```markdown
# NNUE Trainer Architecture

**Status:** Draft — architecture reference for Phase D, no implementation exists yet
**Date:** 2026-07-13
**Relationship to other docs:** Elaborates `docs/NNUE_PRD.md` §3 "Trainer Architecture" into
a standalone, implementation-facing reference. Where this doc and the PRD appear to
disagree, the PRD's Appendix A decision record wins; open an issue rather than
resolving the conflict silently in code.

## 1. Overview & Scope
## 2. Module Boundaries
## 3. DatasetProvider Abstraction
## 4. Feature Encoding Pipeline & Java/Python Parity
## 5. Canonical Network Intermediate Representation
## 6. PyTorch Model Responsibilities
## 7. Quantization Pipeline
## 8. Export Pipeline & .nnue File Contract
## 9. Provenance Chain
## 10. Reproducibility Guarantees
## 11. Validation Strategy
## 12. CI Strategy
## 13. Extensibility for Future Feature Sets
## 14. Failure Modes
## 15. Trainer Architecture Invariants
## 16. Open Decisions Requiring Sign-Off
## 17. Revisit Conditions
```

- [ ] **Step 2: Write Sections 1–2 (Overview, Module Boundaries)**

Section 1 states the trainer's purpose (produce real trained `.nnue` weight files, replacing today's synthetic `TestNetworks.synthetic()` networks), restates the PRD's hard boundary ("Java engine has zero dependency on the training framework; the `.nnue` format is the only contract" — PRD US-4), and names the constraints from the outer task: no JNI, no embedded Python, no PyTorch runtime dependency in the Java engine.

Section 2 defines module boundaries using the PRD §3 stage table verbatim as the starting point (`DatasetProvider`, `Transform`, `FeatureEncoder`, `Labeler`, `Trainer`, `Validator`, `Quantizer`, `Exporter`), and adds the new boundary this doc introduces: a `CanonicalNetwork` representation sitting between `Trainer`'s checkpoint output and `Quantizer`/`Exporter` (detailed in Section 5). State explicitly which boundaries are frozen by existing ADRs (the `.nnue` format itself, per ADR-002) vs. which are new to this doc.

- [ ] **Step 3: Write Section 3 (DatasetProvider Abstraction)**

Reproduce and expand the PRD's `DatasetProvider` contract: "Yield raw labeled positions from one source (text dataset, Stockfish labeler output, self-play output) → iterator of (position, label, metadata)". Add:
- Rationale: why an iterator-of-records contract (memory-bounded streaming over 50-100M positions per PRD §"Trainer Requirements", not a full in-memory list).
- Alternatives considered: a single monolithic loader per format (rejected — couples format-specific parsing to every downstream stage, the exact anti-pattern PRD §3 calls out: "Adding a future data source... means writing one `DatasetProvider`; everything downstream is unchanged"); a plugin-registry framework (rejected — PRD explicitly scopes this as "not a plugin framework, just separation of responsibilities", matching this project's stated aversion to speculative abstraction).
- Isolation invariant: no `DatasetProvider` implementation may depend on another `DatasetProvider` implementation or on `Trainer`/`Quantizer`/`Exporter` internals — enforceable today by directory convention (each provider in its own module file), formally checkable later the same way `generate_report.py`'s `FROZEN_BOUNDARY_CLASSES` mechanism checks Java boundaries (cross-reference Section 12/CI Strategy).

- [ ] **Step 4: Write Section 4 (Feature Encoding Pipeline & Java/Python Parity)**

Document the frozen Java side exactly as implemented: `FeatureExtractor.featureIndex(perspectiveColor, pieceColor, pieceType, square)` = `relativeColor * 384 + (pieceType-1) * 64 + relativeSquare`, where `relativeColor` is 0 for "us"/1 for "them" and `relativeSquare` is `square` for white's perspective or `square ^ 56` for black's (vertical mirror). State that `FeatureIndexParityTest.java` already commits to a hand-derived starting-position fixture (32 indices) specifically so a future Python `FeatureEncoder` can be pinned against the same corpus byte-for-byte — this test file is the parity contract's enforcement point, not a document.

Parity strategy: the Python `FeatureEncoder` re-implements the identical formula independently (no code sharing across the language boundary — no JNI, no codegen from Java, per outer constraints), validated by:
1. A shared FEN corpus (the existing `FeatureIndexParityTest.CORPUS` array, or its successor) with expected indices computed once and asserted in both languages.
2. A CI job (Section 12) that fails if either side's output for the shared corpus drifts.

Alternatives considered: generating Python from the Java formula via a build step (rejected — adds a codegen toolchain for eight lines of arithmetic, violates the "no embedded Python/no cross-language build coupling" constraint's spirit); defining the formula in a language-neutral spec file both sides parse at runtime (rejected — runtime parsing of a spec format is more moving parts than duplicating eight lines of integer arithmetic behind a shared test corpus, and the Java side must stay branch-free and allocation-free on the hot path per CLAUDE.md §3, which a shared-spec interpreter would jeopardize).

- [ ] **Step 5: Write Section 5 (Canonical Network Intermediate Representation) — the new concept**

Define `CanonicalNetwork`: a framework-agnostic, serializable representation of exactly the tensors the `.nnue` format needs — `ftWeights` (shape `[768, hiddenWidth]`), `ftBiases` (`[hiddenWidth]`), `outputWeights` (`[2, hiddenWidth]`), `outputBias` (scalar), plus the header metadata fields (`hiddenWidth`, `qa`, `qb`, `outputScale`, `architectureId`, `featureSetId`, `networkUuid`, `trainerCommit`, `createdAtEpochSeconds`) — as plain arrays/structs, not a `torch.nn.Module` or `state_dict`.

Pipeline position: `Trainer` produces a PyTorch checkpoint (`state_dict` + optimizer state, training-only) → a `CheckpointToCanonical` step extracts exactly the tensors above into a `CanonicalNetwork` (dropping optimizer state, layer naming, framework version coupling) → `Quantizer` consumes `CanonicalNetwork` (float) and produces a quantized `CanonicalNetwork` (int16) → `Exporter` serializes the quantized `CanonicalNetwork` directly to the `.nnue` byte layout.

Rationale: `Exporter` coupling directly to `state_dict` means any PyTorch internal change (layer renaming, module nesting, a `torch.compile` wrapper adding prefixes to state_dict keys) silently breaks export or requires the exporter to understand PyTorch's object model. `CanonicalNetwork` is the one place that understands "what a network *is*" (a fixed set of named tensors matching the frozen `.nnue` layout); `Exporter` only understands `CanonicalNetwork` → bytes.

Alternatives considered:
1. *Exporter reads `state_dict` directly (rejected).* Couples the export format to PyTorch's serialization internals; a training-code refactor (e.g. renaming a layer, wrapping the model in `nn.DataParallel`) becomes an export-breaking change with no compiler or test signal until export is run.
2. *ONNX as the intermediate format (rejected).* ONNX solves a different problem (cross-framework inference graphs); this project's `.nnue` format is a fixed-shape weight dump, not a computation graph — ONNX would add a heavyweight, general-purpose dependency to represent eight numbers, and the Java side would still need custom `.nnue` parsing regardless (ONNX doesn't replace the need for `NnueNetwork.load()`), so it buys nothing over a project-defined struct.
3. *No intermediate representation; `Quantizer`/`Exporter` each independently know how to pull tensors out of a checkpoint (status quo per current PRD §3 table, "checkpoint → quantized tensors").* Rejected because it's exactly the coupling this section exists to remove — two separate stages (`Quantizer`, `Exporter`) each need checkpoint-internals knowledge instead of one.

Tradeoffs: `CanonicalNetwork` is one more stage boundary (one more file format between checkpoint and `.nnue`) for a project explicitly avoiding speculative abstraction — justified here because it is the one seam PyTorch-internals churn is most likely to hit (checkpoint formats change across PyTorch versions/training-code refactors far more often than the frozen `.nnue` contract does), and because `CanonicalNetwork` is directly testable in isolation (a `CanonicalNetwork` round-trips to `.nnue` and back with no PyTorch import required at all, which also keeps the `Exporter`/`Quantizer` boundary importable and unit-testable without a full training environment).

Future evolution: if `.nnue` ever gains a version 2 (larger topology, HalfKP features per Section 13), `CanonicalNetwork` gains new optional fields behind the same `architectureId`/`featureSetId` versioning `NnueNetwork.load()` already rejects-on-mismatch — `Exporter` and the Java loader change together, `Trainer`/`Quantizer` do not need to change their checkpoint-side code.

- [ ] **Step 6: Write Section 6 (PyTorch Model Responsibilities)**

Scope the `nn.Module`: exactly the (768→hiddenWidth)×2→1 clipped-ReLU topology from PRD §3 Network Specification, feature-transformer weights shared between perspectives. State what the model is *not* responsible for: quantization (Quantizer's job, operates post-training on the extracted `CanonicalNetwork`), export format (Exporter's job), provenance metadata (attached at export time, not model state). Cite ADR-002's int16-canonical decision as the reason training itself stays float32 throughout — quantization is strictly a post-training step, never a training-time representation.

- [ ] **Step 7: Write Section 7 (Quantization Pipeline)**

Document the clipping-then-quantize contract already specified in PRD §"Trainer Requirements": FT weights clipped during training such that `(bias + Σ weights of the worst-case ~32 active features)` cannot overflow int16 after quantization; the bound is derived from `qa`. State the pipeline shape: float32 `CanonicalNetwork` → per-tensor scale application (`qa` for FT layer, `qb` for output layer, matching `NnueNetwork`'s stored `qa`/`qb` fields exactly) → round-to-nearest int16 → clipping-boundary report (flags weights at the saturation edge, per PRD's "Weight histogram + clipping report" analysis tool) → quantized `CanonicalNetwork`.

Determinism requirement (feeds the Invariants section): given the same float32 `CanonicalNetwork` and the same `qa`/`qb`, quantization must be a pure function — no RNG, no hardware-dependent rounding mode — so the same checkpoint quantizes identically on any machine. State this as testable: a quantization run repeated twice on the same input produces byte-identical int16 arrays (this is the "byte-exact on re-parse" property PRD §"Analysis tools" already names for the Quantization validator tool).

- [ ] **Step 8: Write Section 8 (Export Pipeline & .nnue File Contract)**

Reproduce `NnueNetwork.java`'s documented binary layout verbatim as the frozen contract (magic `"VNUE"`, `formatVersion=1`, `architectureId`, `featureSetId`, `hiddenWidth`, `quantVersion`, `qa`/`qb`/`outputScale`, `networkUuid` (UTF), `trainerCommit` (UTF), `createdAtEpochSeconds` (i64), then `ftWeights`/`ftBiases`/`outputWeights`/`outputBias` bodies) and mark it read-only from the trainer's perspective — the Python `Exporter` must produce bytes `NnueNetwork.load()` accepts unmodified; this doc does not get to redefine that format, only implement a writer for it. Note the loader's existing validation the exporter must satisfy on the read side (magic check, exact `formatVersion`/`architectureId`/`featureSetId` match, `hiddenWidth` in `(0, 4096]`, exact body length check against the header-declared `hiddenWidth`) — cross-reference issue #191 (currently open: loader doesn't yet reject non-positive `qa`/`qb`) as a Java-side gap the exporter cannot rely on being fixed until that issue lands.

- [ ] **Step 9: Write Section 9 (Provenance Chain)**

Reproduce PRD §4's provenance requirements: embedded header fields (`networkUuid`, `trainerCommit`, `createdAtEpochSeconds`) plus the sidecar `<uuid>.json` manifest (format version, architecture id, feature set, hidden width, quantization version, trainer version + full git commit, dataset identifiers with stage labels, full training config including seed/λ/K, label-engine version, UUID as join key). State the chain explicitly as a sequence: dataset id → training config → checkpoint → `CanonicalNetwork` → quantized `CanonicalNetwork` → `.nnue` + manifest (atomic pair) → benchmark-corpus results → SPRT log → release report (PRD §4 "Network Release Reports") — every link identified by the network UUID, unbroken per PRD §"End-to-End Reproducibility".

- [ ] **Step 10: Write Section 10 (Reproducibility Guarantees)**

State precisely what is and isn't promised, matching PRD §"End-to-End Reproducibility" verbatim on the hard boundary: "same commands + same data + same seed → statistically equivalent net, and byte-exact quantization/export given the same checkpoint." Explicitly note this doc does NOT promise bit-exact retraining across GPU hardware (floating-point nondeterminism) — only export/quantization determinism (Section 7) and config/seed reproducibility are guaranteed.

- [ ] **Step 11: Write Section 11 (Validation Strategy)**

Reproduce PRD §3 "Evaluation Strategy" four levels (unit/search/training/system) as they apply to the trainer specifically: `Validator` stage held-out metrics + label correlation + eval-scale check vs. classical (PRD's KFinder-calibrated requirement); the Quantization validator tool (float checkpoint vs. quantized tensors vs. re-parsed `.nnue` round-trip, byte-exact on re-parse); the cross-language `FeatureEncoder`/`FeatureExtractor` parity check (Section 4).

- [ ] **Step 12: Write Section 12 (CI Strategy)**

Reproduce PRD §"Continuous Validation (CI)" item 5 (trainer CI: miniature end-to-end training run, tiny dataset, few steps, fixed seed, exports `.nnue` + manifest, asserts bit-identical export across two runs, manifest validates against its schema) as the trainer-side CI gate, and note it is additive to (not a replacement for) the existing Java-side CI gates C-5 already wired (`nnue-mode`, `nnue-golden`, `nnue-benchmark` tag groups in `.github/workflows/ci.yml`). State that a future trainer CI job is a separate workflow file, gated on changes under the trainer's own directory (exact path pending the Section 16 location decision), not folded into the existing `ci.yml` Java build.

- [ ] **Step 13: Write Section 13 (Extensibility for Future Feature Sets)**

Cross-reference ADR-001's revisit conditions (HalfKP/HalfKA deferred, revisit only after all v1 gates pass, self-play data sustains 5-10x volume, a measured strength plateau across ≥2 retrained nets, and incremental==rebuild test infra matures enough for a king-refresh path). State the trainer-side implication: `FeatureEncoder` and `CanonicalNetwork` are the two places a new feature set touches — `CanonicalNetwork`'s `featureSetId` field (Section 5) already provides the versioning seam; a new feature set is a new `FeatureEncoder` implementation plus a `CanonicalNetwork`/`.nnue` format-version bump, not a rewrite of `DatasetProvider`, `Trainer`, or `Quantizer`.

- [ ] **Step 14: Write Section 14 (Failure Modes)**

Table format, one row per failure mode with detection mechanism:

```markdown
| Failure mode | Detection |
|---|---|
| Java/Python feature-index drift | Shared-corpus parity CI check (Section 4) |
| int16 overflow from insufficient weight clipping | Quantization validator's clipping-boundary report (Section 7); ADR-002's oracle-bound test on the Java side catches it post-export |
| Non-deterministic quantization/export | Byte-identical re-run assertion (Section 7, Section 12 trainer CI) |
| Malformed/truncated `.nnue` written by Exporter | `NnueNetwork.load()`'s existing header/length validation (Section 8) — but only if the Java-side loader is itself correct; issue #191 (qa/qb=0 not rejected) is a known current gap |
| Missing/incomplete provenance manifest | Manifest schema validation in trainer CI (Section 12); "a net without a complete report cannot be promoted to default" per PRD §4 |
| Eval-scale mismatch destabilizing tuned search margins | KFinder-calibrated training targets (PRD §"Trainer Requirements"); corpus-level scale comparison, a named Phase D exit criterion (PRD §5 Risks table) |
| `CanonicalNetwork` drifting from what `Exporter` actually writes | Round-trip test: `CanonicalNetwork` → `.nnue` bytes → `NnueNetwork.load()` (Java, via a committed test fixture) → assert loaded values equal the original `CanonicalNetwork` |
```

- [ ] **Step 15: Write Section 15 (Trainer Architecture Invariants) — the permanent tracking section**

This section must persist across every future trainer PR (analogous to how `generate_report.py`'s `FROZEN_BOUNDARY_CLASSES` tracks Java-side boundaries). List, at minimum, exactly these seven invariants, each as a one-paragraph checkable statement:

```markdown
1. **DatasetProvider isolation.** No `DatasetProvider` implementation imports or depends on
   another `DatasetProvider`, or on `Trainer`/`Quantizer`/`Exporter` internals. Adding a new
   data source is a new file, not a modification to existing providers.
2. **Java/Python feature parity.** `FeatureEncoder` (Python) and `FeatureExtractor` (Java,
   `engine-core/.../eval/nnue/FeatureExtractor.java`) produce identical feature indices for
   every position in the shared parity corpus. Any change to either side's formula requires
   updating both and re-passing the parity check in the same PR.
3. **Canonical Network intermediate representation.** `Exporter` and `Quantizer` consume only
   `CanonicalNetwork`, never a raw PyTorch `state_dict` or `nn.Module`. Training-code refactors
   (layer renaming, module wrapping) must not require `Exporter`/`Quantizer` changes unless the
   actual tensor shapes or semantics change.
4. **Export contract.** `Exporter`'s output byte-for-byte satisfies `NnueNetwork.load()`'s
   documented format (magic, version, architecture/feature-set ids, hiddenWidth, quant fields,
   provenance strings, then the four weight/bias arrays) without requiring any Java-side loader
   change. A `.nnue` format version bump is a coordinated change to both sides in one PR.
5. **Quantization determinism.** The same float32 `CanonicalNetwork` + the same `qa`/`qb`
   always quantizes to byte-identical int16 arrays, on any machine, with no RNG or
   hardware-dependent rounding involved.
6. **Provenance chain.** Every exported `.nnue` has a sidecar manifest satisfying PRD §4's
   schema, and every field in that chain (dataset id → config → checkpoint → UUID → SPRT log →
   release report) is traceable without asking the author.
7. **Reproducibility guarantees.** Same commands + same data + same seed → statistically
   equivalent trained net (not bit-exact across GPU hardware); byte-exact quantization/export
   given the same checkpoint (this is stronger — a pure-function guarantee, not "statistical").
```

- [ ] **Step 16: Write Section 16 (Open Decisions Requiring Sign-Off) and Section 17 (Revisit Conditions)**

Section 16 lists exactly one open decision at this stage: trainer location (separate repo vs. `trainer/` top-level directory in this repo). State the current recommendation and rationale as a *recommendation*, explicitly not a decision — mark it "PENDING — requires explicit sign-off, to be finalized via ADR-006's companion location note or a dedicated micro-ADR before Phase D implementation starts." (The actual rationale content is produced by Task 1a below, after grilling — do not fill this in with unstested reasoning.)

Section 17 (Revisit Conditions) lists: re-confirm this doc if ADR-001's feature-set revisit conditions are ever met (Section 13); re-confirm the Canonical IR's field list if `.nnue` format version ever bumps; re-confirm CI strategy once a real (non-synthetic) trained network exists (mirrors ADR-002's own deferred oracle-bound-threshold revisit condition).

- [ ] **Step 17: Self-review against the source PRD**

Re-read `docs/NNUE_PRD.md` §3, §"Trainer Requirements", §"End-to-End Reproducibility", and Appendix A items 5–6 side-by-side with the new doc. Confirm every PRD statement referenced above appears faithfully (no invented requirements, no contradictions). Confirm the doc does not implicitly decide ADR-006 or ADR-007's content beyond what Appendix A already states, and does not implicitly decide trainer location (Section 16 must read as open).

---

### Task 1a: Grill the architecture decisions (not a file-writing task)

**Files:** None created/modified — this task produces input for Task 1's Section 5 and Section 16 content, and for Task 1 Step 16 specifically.

**Interfaces:**
- Consumes: Task 1's draft Sections 2, 5, and 16 (module boundaries, Canonical Network IR, trainer location).
- Produces: a finalized rationale for the Canonical Network IR (feeding back into Section 5) and a finalized, honestly-labeled recommendation for trainer location (feeding into Section 16) — or, if grilling surfaces a genuine blocker, a documented open question instead of a forced answer.

- [ ] **Step 1: Run `/grilling` against three decisions**

Challenge, in order: (a) whether `CanonicalNetwork` is justified complexity or a speculative abstraction given the trainer doesn't exist yet; (b) whether `trainer/` directory vs. separate repo has a defensible answer given the graphify-reporting-split requirement (Deliverable 2) needs the trainer's source tree to be indexable by the same graphify instance that indexes `engine-core` — a separate repo would need its own graphify instance/graph.json, undermining "one Engine + Trainer report" as currently scoped); (c) whether the seven Invariants in Section 15 are the right minimum set or missing something load-bearing.

- [ ] **Step 2: Update Task 1's Section 5 and Section 16 drafts with grilling's outcome**

If grilling changes the Canonical Network IR's justification or the trainer-location recommendation, edit the already-written doc sections directly (Task 1 Steps 5 and 16) — do not leave the pre-grilling draft in place.

---

### Task 2: Extend `generate_report.py` for Engine/Trainer report split

**Files:**
- Modify: `docs/architecture/graph-audits/generate_report.py`

**Interfaces:**
- Consumes: existing `module_of(node)` (currently returns `source_file.split("/", 1)[0]`, e.g. `"engine-core"`, `"engine-uci"`), existing `classify(node)` (production/debug for `src/main/*.java` nodes), existing `build_graph(data)`/`load(path)` helpers — reused, not duplicated.
- Produces: a new `architecture_group(node)` function returning `"engine"` / `"trainer"` / `None`, and report output containing two clearly labeled subsections ("Engine Architecture" / "Trainer Architecture") wrapping the existing per-class/per-module analysis, so a future trainer-only PR's report doesn't force a human to mentally filter out irrelevant Java-class sections (and vice versa).

- [ ] **Step 1: Add the module-group classifier**

In `generate_report.py`, immediately after the existing `module_of()` function (currently ending around line 47), add:

```python
# Deliverable 2 (NNUE_TRAINER_ARCHITECTURE.md, Phase D prep): split reports into
# Engine Architecture vs Trainer Architecture sections. "trainer" is presently a
# forward-declared prefix — the directory doesn't exist until Phase D lands, so
# every current report's Trainer Architecture section is expected to be empty.
ENGINE_MODULE_PREFIXES = {"engine-core", "engine-uci", "engine-tuner", "chess-engine-api"}
TRAINER_MODULE_PREFIXES = {"trainer"}


def architecture_group(node):
    """'engine' / 'trainer' / None (docs, CI config, graphify's own dev-entries, etc.
    — neither engine nor trainer, excluded from both split sections)."""
    mod = module_of(node)
    if mod in ENGINE_MODULE_PREFIXES:
        return "engine"
    if mod in TRAINER_MODULE_PREFIXES:
        return "trainer"
    return None
```

- [ ] **Step 2: Partition nodes by group before building the report body**

Locate the existing `main()` function's node/edge computation block (after `before_g, after_g = build_graph(before_data), build_graph(after_data)`, before the `lines = []` block that starts building output, roughly line 198-258 in the current file). Add:

```python
    before_engine_ids = {n for n in before_ids if architecture_group(before_g.nodes[n]) == "engine"}
    after_engine_ids = {n for n in after_ids if architecture_group(after_g.nodes[n]) == "engine"}
    before_trainer_ids = {n for n in before_ids if architecture_group(before_g.nodes[n]) == "trainer"}
    after_trainer_ids = {n for n in after_ids if architecture_group(after_g.nodes[n]) == "trainer"}
```

- [ ] **Step 3: Add a `group_summary_lines` helper and call it for each group**

Immediately before the existing `lines.append(f"# Architecture Graph Audit — ...")` header line, add a small helper function (module level, alongside the other helpers like `top_by_abs_delta`):

```python
def group_summary_lines(title, before_group_ids, after_group_ids, before_ids, after_ids):
    """One-paragraph summary for an architecture-group split section: node/edge counts
    scoped to that group only. Detailed per-class/centrality analysis stays whole-graph
    (splitting betweenness centrality etc. per group would be misleading — centrality is
    a whole-graph measure), so this is deliberately just a scoping summary, not a
    duplicate of the sections below."""
    lines = [f"## {title}"]
    if not before_group_ids and not after_group_ids:
        lines.append("- No nodes in this group in either snapshot (expected until this "
                      "subsystem's source tree exists).")
        lines.append("")
        return lines
    added = after_group_ids - before_group_ids
    removed = before_group_ids - after_group_ids
    lines.append(f"- Before: {len(before_group_ids)} nodes; After: {len(after_group_ids)} nodes")
    lines.append(f"- Node delta: +{len(added)} / -{len(removed)}")
    lines.append("")
    return lines
```

Then, right after the existing summary block (`lines.append("")` that follows the `Edge delta` line, current line 267), insert:

```python
    lines.append("## Architecture Split")
    lines.append("_Scoping summary only — the detailed sections below (centrality, degree, "
                 "boundary report, etc.) remain whole-graph, since those measures are not "
                 "meaningful computed on a subgraph alone._")
    lines.append("")
    lines.extend(group_summary_lines("Engine Architecture", before_engine_ids, after_engine_ids, before_ids, after_ids))
    lines.extend(group_summary_lines("Trainer Architecture", before_trainer_ids, after_trainer_ids, before_ids, after_ids))
```

- [ ] **Step 4: Regenerate the C-5 report from its existing snapshots and diff against the committed version**

Run:

```bash
find /home/coeusyk/projects/chess-engine/graphify-out -maxdepth 2 -iname "graph.json" | head -5
```

Identify the before/after snapshot pair used for `C-5-ci-integration.md` (before commit `4f6f7c9`, after commit `4dbc24d`, per that report's header). If both snapshots are still present under `graphify-out/`, regenerate into a scratch path and diff:

```bash
/home/coeusyk/.local/share/uv/tools/graphifyy/bin/python \
  docs/architecture/graph-audits/generate_report.py \
  --before <before-snapshot-path> --after <after-snapshot-path> \
  --pr-id C-5 --pr-title "CI Integration" \
  --out /tmp/C-5-regenerated.md
diff docs/architecture/graph-audits/C-5-ci-integration.md /tmp/C-5-regenerated.md
```

Expected: the diff shows only the new "## Architecture Split" section inserted (with both Engine and Trainer subsections — Engine non-empty since `engine-core`/`engine-uci`/`engine-tuner` nodes exist, Trainer showing the "No nodes in this group" line since no `trainer/` tree exists yet) and the "## Narrative" section's placement shifted accordingly; every other existing section (centrality, degree, boundary report, etc.) byte-identical to the committed report. If the exact snapshot pair is no longer available, instead run the script against the two most recent available `graphify-out` snapshots and confirm the script runs to completion without a traceback and produces both new subsections — the specific counts don't matter for this check, only that no exception is raised and both group labels appear.

- [ ] **Step 5: Commit**

```bash
git add docs/architecture/graph-audits/generate_report.py
git commit -m "$(cat <<'EOF'
feat(architecture): split graph-audit reports into Engine/Trainer sections

Why: Phase D introduces a trainer subsystem that must be reviewable
independently of engine-core changes; a single undifferentiated report
would bury trainer-relevant deltas in unrelated Java noise once the
trainer/ tree exists.
What: added architecture_group() classifying nodes by module prefix
(engine-core/engine-uci/engine-tuner/chess-engine-api vs trainer), and a
scoping-only "Architecture Split" summary section; all existing
whole-graph analysis (centrality, degree, boundary report) is unchanged.
Left out: no trainer/ tree exists yet, so the Trainer Architecture
section is expected to report empty until Phase D implementation lands;
no change to graphify itself, only this repo's report generator.
Phase: 15 — nnue
EOF
)"
```

---

## Self-Review Notes (writing-plans skill requirement)

**Spec coverage:** All 16 requested doc topics map to Task 1 Steps 2–16 one-to-one (Overview→2, Module Boundaries→2, DatasetProvider→3, Feature Encoding/parity→4, Canonical IR→5, PyTorch responsibilities→6, Quantization→7, Export/.nnue contract→8, Provenance→9, Reproducibility→10, Validation→11, CI→12, Extensibility→13, Failure modes→14, Invariants→15, plus location sign-off→16). Grilling requirement→Task 1a. Graphify split→Task 2. Both improvements from the outer task (Trainer Architecture Invariants section; separate Engine/Trainer graphify reporting) are covered by Task 1 Step 15 and Task 2 respectively.

**Placeholder scan:** No TBD/TODO markers left in task steps; Section 16's "PENDING" label is a deliberate, explicit status value (not a placeholder for missing plan content) — the plan itself specifies exactly what produces the real content (Task 1a).

**Out-of-scope guard:** No task writes ADR-006/007 as standalone files or writes any trainer/Python/PyTorch code — confirmed against Global Constraints.
