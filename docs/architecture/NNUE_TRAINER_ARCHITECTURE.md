# NNUE Trainer Architecture

**Status:** Draft — architecture reference for Phase D, no implementation exists yet
**Date:** 2026-07-13
**Relationship to other docs:** Elaborates `docs/NNUE_PRD.md` §3 "Trainer Architecture"
into a standalone, implementation-facing reference. Where this doc and the PRD appear
to disagree, the PRD's Appendix A decision record wins — open an issue rather than
resolving the conflict silently in code. This doc does not modify or supersede any of
the five frozen ADRs (002, 003, 004, 005, 009); it documents the trainer side that sits
downstream of the contract those ADRs already fixed on the Java side.

**Grilled and accepted, 2026-07-13** (see §16 for the recorded decision record): trainer
location is `trainer/`, a top-level directory in this repository — not a separate repo.

---

## 1. Overview & Scope

The trainer is the subsystem that turns raw chess data into a real, loadable `.nnue`
weight file — replacing today's synthetic, seeded-random `TestNetworks.synthetic()`
networks (`engine-core/src/test/java/.../eval/nnue/TestNetworks.java`) with genuine
trained weights. It does not exist yet; this document defines its shape before any
line of it is written, per the outer Phase D planning task's constraint: **documentation
only, no trainer implementation code in this deliverable.**

Hard boundary (restated from PRD US-4, and non-negotiable per the outer task's
constraints): the Java engine has **zero** dependency on the training framework. The
`.nnue` file is the only contract between the two systems.

- No JNI.
- No embedded Python.
- No PyTorch runtime dependency in `engine-core`, `engine-uci`, `engine-tuner`, or
  `chess-engine-api`.
- The engine consumes only exported `.nnue` artifacts, produced out-of-band by the
  trainer and committed to the `nets/` registry (manifests only) or distributed via
  releases (full weight binaries — PRD §4 "Integration Points").

The trainer's own internal goal, per the PRD's maintainer persona (§2): every stage
from data to weights should be understood, not black-boxed. This is why the trainer is
self-written rather than an existing framework (bullet, lc0 tooling, etc.) — PRD
Appendix A item 6, "self-written PyTorch, engine-agnostic export; pure-Java trainer
rejected (compute infeasible), bullet rejected (black box)."

---

## 2. Module Boundaries

The PRD (§3 "Trainer Architecture (PyTorch, self-written)") already defines eight
stages as "small, separately testable stages with explicit interfaces — not a plugin
framework, just separation of responsibilities":

| Component | Responsibility | Contract |
|---|---|---|
| `DatasetProvider` | Yield raw labeled positions from one source | iterator of `(position, label, metadata)` |
| `Transform` | Filtering/deduplication/phase balancing, composable | records in → records out |
| `FeatureEncoder` | Position → 768-feature indices; Python mirror of the Java extractor | position → sparse indices |
| `Labeler` | Produce/blend targets (search score, WDL, λ blend, K scaling) | record → training target |
| `Trainer` | Model definition, loss, optimization loop | shards → checkpoints |
| `Validator` | Held-out metrics, correlation vs. labels, eval-scale check | checkpoint → report |
| `Quantizer` | float32 → int16 weights with clipping verification | `CanonicalNetwork` (float) → `CanonicalNetwork` (int16) |
| `Exporter` | Quantized representation + provenance → `.nnue` + manifest | `CanonicalNetwork` (int16) → artifact |

This doc adds exactly one new boundary the PRD's table doesn't yet name: a
**`CanonicalNetwork`** representation sitting between `Trainer`'s checkpoint output and
`Quantizer`/`Exporter` (§5). Everything else in the table above is unchanged from the
PRD.

**What's frozen vs. new here:**
- Frozen (do not redesign): the `.nnue` binary layout and every field in it
  (`NnueNetwork.java`, described exactly in §8); the feature-index formula
  (`FeatureExtractor.java`, §4); int16-canonical inference (ADR-002); the eight-stage
  breakdown above (PRD §3).
- New in this doc: `CanonicalNetwork` (§5), the repository-location decision (§16), and
  the permanent Invariants list (§15).

---

## 3. DatasetProvider Abstraction

Contract, verbatim from the PRD: *"Yield raw labeled positions from one source (text
dataset, Stockfish labeler output, self-play output) → iterator of (position, label,
metadata)."*

**Rationale for an iterator, not a loaded list.** PRD §"Trainer Requirements" names the
target scale as 50–100M positions with Python-side data loading as "the known
bottleneck" — a `DatasetProvider` that materializes its full output in memory before
`Transform`/`FeatureEncoder` ever run would make that bottleneck worse, not better.
Streaming iteration keeps memory bounded regardless of dataset size.

**Alternatives considered.**
1. *One monolithic loader handling every source format (rejected).* Couples
   format-specific parsing (EPD/CSV text, Stockfish-labeler binary output, self-play
   game logs) to every downstream stage. The PRD's own stated benefit of the
   stage-separated design — "Adding a future data source... means writing one
   `DatasetProvider`; everything downstream is unchanged" — is lost the moment format
   knowledge leaks past the provider boundary.
2. *A plugin-registry framework (dynamic discovery, provider registration API,
   configuration-driven provider selection) (rejected).* The PRD is explicit this is
   "not a plugin framework, just separation of responsibilities." A registry buys
   nothing when there are three known sources (Stage 1 text, Stage 2 SF-labeled, Stage
   3 self-play) and adding a fourth is already cheap (one new file implementing the
   same iterator contract) — this matches the project's stated aversion to speculative
   abstraction (CLAUDE.md: "no interface with one implementation... no config for a
   value that never changes").

**Isolation invariant (feeds §15):** no `DatasetProvider` implementation may import or
depend on another `DatasetProvider` implementation, or reach into `Trainer`/
`Quantizer`/`Exporter` internals. This is enforceable today by directory convention
(`trainer/dataset/`, one file per source) and, once graphify indexes the `trainer/`
tree (§12), the same `FROZEN_BOUNDARY_CLASSES` mechanism `generate_report.py` already
uses for the Java side (`docs/architecture/graph-audits/generate_report.py`) can be
extended to flag a `DatasetProvider`-to-`DatasetProvider` edge as a boundary violation
the same way it flags production→debug edges today.

---

## 4. Feature Encoding Pipeline & Java/Python Parity

**The frozen Java formula** (`FeatureExtractor.java`, `featureIndex()`):

```
relativeColor   = 0 if pieceColor == perspectiveColor else 1
relativeSquare  = square             if perspectiveColor == White
                = square XOR 56      if perspectiveColor == Black   (vertical mirror)
pieceTypeIndex  = pieceType - 1
index           = relativeColor * 384 + pieceTypeIndex * 64 + relativeSquare
```

768 features per perspective (`2 * 6 * 64`), dual-perspective (white-view and
black-view accumulators), non-king-relative — the v1 plain-768 feature set per
ADR-001. `FeatureIndexParityTest.java` already commits to a hand-derived
starting-position fixture (32 indices, symmetric since the starting position looks
numerically identical from both perspectives) plus a four-position corpus, specifically
so a future Python `FeatureEncoder` can be pinned against the same values byte-for-byte
— **this test file is the parity contract's enforcement point**, not this document.

**Parity strategy.** The Python `FeatureEncoder` (`trainer/trainer/encoding/`)
re-implements the identical formula independently — no code sharing across the
language boundary (no JNI, no codegen from Java, per §1's hard boundary). Validated by:

1. A shared FEN corpus (today's `FeatureIndexParityTest.CORPUS`, or its successor)
   with expected indices computed once and asserted identically in both languages.
2. A CI job (§12) that fails if either side's output for the shared corpus drifts —
   this is a **cross-repo-boundary, same-repo-tree** check, made possible specifically
   by the §16 in-repo decision: both the Java fixture and the Python encoder live in
   one commit, so a PR that changes one without the other fails CI in that same PR,
   not in a follow-up coordination effort across two repos.

**Alternatives considered.**
1. *Codegen: generate the Python formula from the Java source at build time
   (rejected).* Adds a codegen toolchain to translate eight lines of integer
   arithmetic, and risks silently laundering a "no embedded Python" boundary violation
   into the build if the generator ever needs to *run* Java to introspect the formula.
2. *A language-neutral spec file both sides parse at runtime (rejected).* Runtime
   parsing of a spec format is more moving parts than duplicating eight lines behind a
   shared test corpus, and — more importantly — the Java side must stay branch-free
   and allocation-free on the hot path (CLAUDE.md §3: "No object allocation in hot
   paths"); an interpreted-spec indirection on `FeatureExtractor`'s call path would
   jeopardize that constraint for a benefit (avoiding eight duplicated lines) that
   doesn't justify it.

**Chosen:** independent re-implementation, pinned by a shared test corpus, enforced by
CI. Duplication of eight lines of arithmetic is cheaper and safer here than either
alternative's added machinery.

---

## 5. Canonical Network Intermediate Representation

**Definition.** `CanonicalNetwork`: a framework-agnostic, serializable representation
of exactly the tensors and metadata the `.nnue` format needs — nothing else.

```python
@dataclass
class CanonicalNetwork:
    hidden_width: int
    quantized: bool             # False until Quantizer runs — Exporter refuses quantized=False
    ft_weights: np.ndarray      # shape [768, hidden_width], row-major per feature
    ft_biases: np.ndarray       # shape [hidden_width]
    output_weights: np.ndarray  # shape [2, hidden_width], perspective-major ("us" then "them")
    output_bias: int
    qa: int
    qb: int
    output_scale: int
    architecture_id: int
    feature_set_id: int
    network_uuid: str | None             # None until Exporter assigns one — see Pipeline position
    trainer_commit: str | None           # None until Exporter assigns one
    created_at_epoch_seconds: int | None # None until Exporter assigns one
```

This is deliberately minimal: **one dataclass, one converter function
(`checkpoint_to_canonical(model) -> CanonicalNetwork`), one round-trip test.** It is not
a class hierarchy, not a plugin interface, and it does not grow beyond what `.nnue`
actually needs — resisting the temptation to make it "general" is itself part of the
design (see Tradeoffs below).

**Why `quantized: bool`, not two separate dataclasses.** `Quantizer` and `Exporter`
otherwise share every other field verbatim; a second dataclass would duplicate all of
them for one bit of information. The flag is checked at the two points precision
actually matters: `Quantizer` asserts its input has `quantized=False`, `Exporter`
asserts its input has `quantized=True` — turning "was this network quantized before
export" from an implicit, array-dtype-inferred convention (the ADR-002-style bug this
review flagged) into an explicit, checkable field. If the trainer later needs more than
one axis of type state, revisit — two dataclasses would be the better call at that
point, not this one.

**Provenance timing.** `network_uuid`, `trainer_commit`, and `created_at_epoch_seconds`
are `None` from `checkpoint_to_canonical()` through `Quantizer` — a network only
becomes identifiable once `Exporter` decides to actually emit it, matching §6's "model
is not responsible for provenance metadata... attached at export time." `Exporter`
populates all three immediately before serializing and refuses to write a `.nnue` file
if any of the three is still `None`.

**Pipeline position:**

```
Trainer (PyTorch, state_dict + optimizer state, training-only)
    │  checkpoint_to_canonical()
    ▼
CanonicalNetwork (float32, quantized=False, provenance fields None)
    │  Quantizer
    ▼
CanonicalNetwork (int16, quantized=True, provenance fields still None)
    │  Exporter (assigns network_uuid / trainer_commit / created_at_epoch_seconds)
    ▼
.nnue bytes  +  <uuid>.json manifest
```

**Rationale.** Two downstream stages — `Quantizer` and `Exporter` — both need to pull
the same fixed set of tensors out of whatever the training loop produced. Without a
named intermediate, that extraction logic either duplicates across both stages, or
`Quantizer`'s output becomes a private, undocumented shape that only `Exporter` happens
to understand — an implicit contract with no test surface of its own.
`CanonicalNetwork` is what stops that duplication: it is the one place that understands
"what a network *is*" (the fixed set of named tensors matching the frozen `.nnue`
layout, §8); `Exporter` and `Quantizer` only understand `CanonicalNetwork`, never a raw
`state_dict`.

A secondary but real benefit: PyTorch checkpoint internals (layer naming, module
nesting, a `torch.compile` wrapper adding key prefixes to `state_dict`) are far more
likely to change across the project's lifetime than the frozen `.nnue` contract is.
`CanonicalNetwork` absorbs that churn in one place (`checkpoint_to_canonical`) instead
of leaking it into `Exporter`, which is also the piece of code with the highest cost of
a silent bug (a malformed `.nnue` byte layout fails far more obscurely than a Python
`KeyError` on a renamed checkpoint key would).

**Alternatives considered.**
1. *Exporter reads `state_dict` directly (rejected).* Couples the export format to
   PyTorch's serialization internals. A training-code refactor becomes an
   export-breaking change with no signal until export is actually run — no compiler
   error, no type error, just a wrong or crashing `.nnue` file.
2. *ONNX as the intermediate format (rejected).* ONNX solves a different problem
   (cross-framework inference graphs). This project's `.nnue` format is a fixed-shape
   weight dump, not a computation graph; ONNX would add a heavyweight, general-purpose
   dependency to represent eight numbers, and the Java side would still need custom
   `.nnue` parsing regardless — `NnueNetwork.load()` doesn't go away. ONNX buys nothing
   here.
3. *No intermediate representation — status quo per the PRD's current table
   ("checkpoint → quantized tensors") (rejected).* This is exactly the coupling this
   section exists to remove: two stages independently reaching into checkpoint
   internals instead of one shared, tested seam.

**Tradeoffs accepted.** This is one more stage boundary in a project that explicitly
avoids unrequested abstraction (CLAUDE.md, ponytail conventions). It is justified here
specifically because: (a) it costs no more code than `Exporter` would need to write
anyway — it's the same tensor-extraction work, just named and given its own test
surface instead of inlined; (b) it is the one seam most likely to be hit by churn
outside this project's control (PyTorch version upgrades), unlike the frozen `.nnue`
format which changes only by this project's own deliberate version bump; (c) it makes
`Quantizer` and `Exporter` unit-testable without a full PyTorch training environment —
a `CanonicalNetwork` round-trips to `.nnue` bytes and back with no `torch` import
required at all.

**Future evolution.** If `.nnue` ever gains a version 2 (larger topology, HalfKP
features per §13), `CanonicalNetwork` gains new optional fields behind the same
`architecture_id`/`feature_set_id` versioning `NnueNetwork.load()` already
rejects-on-mismatch. `Exporter` and the Java loader change together; `Trainer` and
`Quantizer` do not need to change their checkpoint-side code at all.

---

## 6. PyTorch Model Responsibilities

The `nn.Module` implements exactly the topology PRD §3 "Network Specification" fixes:
`(768 → hiddenWidth) × 2 → 1`, clipped ReLU (`clamp(x, 0, QA)`), feature-transformer
weights shared between the two perspectives. Only `hiddenWidth` is configurable, read
from the file header at load time (PRD Appendix A item 3, amended from "configurable
topology" — general topology config was rejected as untested-surface overengineering).

**What the model is *not* responsible for:**
- **Numeric quantization (float32 → int16)** — strictly `Quantizer`'s job (§7),
  operating on the extracted `CanonicalNetwork` after training completes. Per ADR-002,
  int16 is the *inference* representation; training itself stays float32 throughout.
  The model never sees a quantized weight during the forward/backward pass.
- **Export format** — `Exporter`'s job (§8); the model has no knowledge of `.nnue`'s
  byte layout.
- **Provenance metadata** — attached at export time (§9), not carried as model state.

**Reconciling with §7's training-time clipping.** This is *not* a contradiction with
§7's overflow-safety requirement, but the boundary needs to be stated precisely: PRD
US-4 lists "quantization-aware weight clipping" as part of the trainer pipeline, and
§7 requires FT weights to be clipped **during training** so post-quantization values
cannot overflow int16. That clipping is a `Trainer`-owned constraint (a projection or
loss term applied during the optimization loop, using `qa` as an input), not a
`Quantizer`-owned one — `Quantizer` only performs the numeric float→int16 conversion
itself. In other words: `Trainer` is responsible for keeping weights *within* the range
`Quantizer` will later map cleanly to int16; `Quantizer` is responsible for the mapping
itself. Both are real responsibilities, on different sides of the checkpoint boundary
(§5), and neither substitutes for the other.

**Alternatives considered.**
1. *Quantization-aware training (QAT): the model simulates int16 rounding during the
   forward pass so the optimizer learns weights already robust to quantization
   (rejected for v1).* This is a legitimate, well-known NNUE-training technique, and
   PRD US-4's "quantization-aware weight clipping" language could be read as implying
   it. It's rejected here for the same reason ADR-002 rejected a float-first inference
   migration: it would mean the model's forward pass needs two modes (float32 for
   normal training, simulated-int16 for QAT epochs), adding a second numeric path to
   test and maintain before the simpler clip-only approach has even been shown
   insufficient. Overflow-safety clipping (§7) is the minimum viable version of this
   idea — it constrains the *range* weights can reach without simulating the
   *rounding*. If real trained nets show a measurable accuracy gap attributable to
   post-training quantization (not just overflow), QAT is the natural escalation —
   tracked as a revisit condition (§17), not built preemptively.
2. *Quantizer performs both clipping and quantization, model trains unconstrained
   (rejected).* Simpler model code, but defers overflow detection to after a full
   training run completes — a wasted training run if the final weights turn out
   unclippable without unacceptable accuracy loss. Training-time clipping catches this
   continuously instead of as a post-hoc failure.

**Tradeoffs.** Clip-only training is cheaper to implement and test than QAT, at the
cost of not modeling quantization rounding error during optimization — accepted for
v1 because the correctness gate (PRD §1) is about the *engine's* int16 path matching
its own float32 oracle within a documented bound (ADR-002), not about the *trainer*
squeezing maximum accuracy out of quantization; that's a strength concern, deferred
until a real net's results motivate it (§17).

**Future evolution.** If clip-only training turns out insufficient (§17's QAT revisit
condition triggers), the model gains a second forward-pass mode behind a training-config
flag — `CanonicalNetwork`, `Quantizer`, and `Exporter` are unaffected, since QAT only
changes how training-time weights are *produced*, not the shape of what
`checkpoint_to_canonical()` extracts from the resulting checkpoint.

---

## 7. Quantization Pipeline

**Overflow-safety contract**, from PRD §"Trainer Requirements" (a hard requirement, not
a suggestion): FT weights must be clipped during training such that
`(bias + Σ weights of the worst-case ~32 active features)` cannot exceed int16 range
after quantization. The clipping bound is derived from `QA` and documented in the
trainer's training config, not hardcoded.

**Pipeline shape:**

```
CanonicalNetwork (float32)
    │  per-tensor scale application: qa for FT layer, qb for output layer
    │  (matching NnueNetwork's stored qa/qb fields exactly — §8)
    ▼
round-to-nearest int16
    ▼
clipping-boundary report (flags weights at the saturation edge —
    PRD's "Weight histogram + clipping report" analysis tool)
    ▼
CanonicalNetwork (int16, quantized)
```

**Determinism requirement (feeds §15 Invariant 5).** Given the same float32
`CanonicalNetwork` and the same `qa`/`qb`, quantization must be a pure function: no
RNG, no hardware-dependent rounding mode. The same checkpoint quantizes identically on
any machine. This is directly testable: running quantization twice on the same input
produces byte-identical int16 arrays — the "byte-exact on re-parse" property PRD
§"Analysis tools" already names for the Quantization validator tool.

---

## 8. Export Pipeline & .nnue File Contract

**The frozen contract**, reproduced verbatim from `NnueNetwork.java`'s documented
binary layout (big-endian):

```
u8[4]  magic = "VNUE"
i32    formatVersion        (currently 1)
i32    architectureId       (currently 1 — one topology supported)
i32    featureSetId         (currently 1 — plain 768, per ADR-001)
i32    hiddenWidth
i32    quantVersion
i32    qa, qb, outputScale
utf    networkUuid          (DataOutput#writeUTF-compatible)
utf    trainerCommit
i64    createdAtEpochSeconds
i16[FeatureExtractor.FEATURES_PER_PERSPECTIVE * hiddenWidth]  ftWeights (row-major per feature)
i16[hiddenWidth]                                              ftBiases
i16[2 * hiddenWidth]                                          outputWeights (perspective-major: "us" then "them")
i32                                                            outputBias
```

This is **read-only from the trainer's perspective** — `Exporter` must produce bytes
`NnueNetwork.load()` accepts unmodified. This document does not get to redesign this
format, only implement a writer for it.

**Validation the exporter cannot assume is already correct on the read side.**
`NnueNetwork.load()` currently validates: magic bytes, exact `formatVersion`/
`architectureId`/`featureSetId` match (any mismatch is rejected outright, not
best-effort interpreted), `hiddenWidth` in `(0, 4096]`, and exact body length against
the header-declared `hiddenWidth`. **Known current gap:** issue
[#191](https://github.com/coeusyk/chess-engine/issues/191) — the loader does not yet
reject non-positive `qa`/`qb`, which would otherwise surface as a divide-by-zero at
evaluation time rather than at load time. The exporter cannot rely on this being fixed
until #191 lands; the trainer's own export-time validation (assert `qa > 0`,
`qb > 0` before writing) should not be skipped just because the Java loader is expected
to eventually reject it too — defense in depth, not redundant work, since the exporter
and loader are maintained independently and can drift.

---

## 9. Provenance Chain

Reproduced from PRD §4 "Provenance (mandatory for every exported network)":

- **Embedded in the header** (small, fixed-size): `networkUuid`, `trainerCommit`
  (short hash), `createdAtEpochSeconds`. Enough to identify a stray file found on disk.
- **Sidecar manifest** (`<uuid>.json`, exported atomically with the `.nnue`): format
  version, architecture id, feature set, hidden width, quantization version, trainer
  version + full git commit, dataset identifier(s) with stage labels (public/
  SF-labeled/self-play mix proportions), complete training configuration
  (hyperparameters, seed, λ, K), label-engine version where applicable, and the UUID as
  join key.
- Manifests for candidate and released nets are committed to a `nets/` registry in the
  repo (manifests only — never the weight binaries, except the tiny CI net).
- The engine prints the network UUID via `info string` on load, so any test log or
  SPRT run is traceable to an exact training run.

**The full chain**, identified end-to-end by the network UUID, unbroken per PRD
§"End-to-End Reproducibility":

```
dataset id → training config → checkpoint → CanonicalNetwork (float)
    → CanonicalNetwork (quantized) → .nnue + manifest (atomic pair)
    → benchmark-corpus results → SPRT log → release report (PRD §4)
```

The §16 in-repo decision makes the `trainerCommit` field strictly simpler than a
separate-repo trainer would: one commit hash in one repository covers the trainer
code, the exporter, the engine's loader, the documentation, the benchmarks, and the CI
configuration that validated the release — no cross-repo commit-pinning is needed to
answer "what exact state of everything produced this file."

---

## 10. Reproducibility Guarantees

Stated precisely, matching PRD §"End-to-End Reproducibility" verbatim on the hard
boundary between the two guarantee classes:

- **Training:** same commands + same data + same seed → **statistically equivalent**
  net. Bit-exact retraining across GPU hardware is explicitly **not** promised
  (floating-point nondeterminism in GPU kernels is outside this project's control).
- **Quantization and export:** byte-exact, given the same checkpoint. This is a
  strictly stronger, pure-function guarantee (§7) — no hardware or seed dependence at
  all once a float32 `CanonicalNetwork` exists.

Every stage in the chain (§9) is a documented, scripted command — PRD §"End-to-End
Reproducibility"'s target property is that a second developer, given the repo and the
documented commands, can reproduce a released network and its validation results
without asking anyone.

---

## 11. Validation Strategy

Reproduced from PRD §3 "Evaluation Strategy", scoped to what's trainer-side:

- **Training level:** `Validator` stage — held-out loss, label correlation, eval-scale
  check vs. classical (the KFinder-calibrated requirement, §"Trainer Requirements" —
  this is a **hard requirement**, since search margins tuned to classical's centipawn
  scale will produce false SPRT failures attributable to margin mismatch, not the net,
  if the scale is off).
- **Quantization level:** the Quantization validator tool — float checkpoint vs.
  quantized tensors vs. re-parsed `.nnue` round-trip; byte-exact on re-parse, bounded
  error on quantization itself.
- **Cross-language level:** the `FeatureEncoder`/`FeatureExtractor` parity check (§4).
- **System level:** the three PRD §1 release gates (correctness, performance,
  strength) — these are engine-side gates the trainer's output must eventually pass,
  not trainer-side tests themselves.

---

## 12. CI Strategy

**Trainer-side CI** (PRD §"Continuous Validation (CI)" item 5): a miniature
end-to-end training run (tiny dataset, few steps, fixed seed) exports a `.nnue` +
manifest; asserts the export is bit-identical across two runs and the manifest
validates against its schema. This is additive to, not a replacement for, the
Java-side CI gates C-5 already wired (`nnue-mode`, `nnue-golden`, `nnue-benchmark` tag
groups in `.github/workflows/ci.yml`).

**Path-scoping requirement (grilled decision, §16).** `.github/workflows/ci.yml`
currently has a single `push`-triggered job with no path filters — every push runs the
full Java build. This must not become "every push also sets up PyTorch": a trainer
workflow needs its own path-filtered trigger (`paths: ['trainer/**']`) so that
Java-only PRs never pay Python/PyTorch environment setup cost, and — symmetrically — a
future trainer-only CI job should not run on engine-only changes. This is a concrete,
not-yet-implemented follow-up; it is called out here so the first real trainer PR
doesn't accidentally fold Python setup into the existing unscoped job.

**Feature-parity CI** (§4): fails if the Java `FeatureExtractor` fixture and the Python
`FeatureEncoder`'s output diverge on the shared corpus. Made practical by the §16
in-repo decision — both sides are checked in the same PR, in the same CI run.

---

## 13. Extensibility for Future Feature Sets

Cross-referencing ADR-001's revisit conditions (HalfKP/HalfKA deferred until **all** of:
v1 passes all three release gates; self-play data sustains 5–10× current volume; a
measured strength plateau across ≥2 retrained nets at plain-768; the
incremental==rebuild test infrastructure is mature enough to validate a king-refresh
path).

**Trainer-side implication:** a new feature set touches exactly two places —
`FeatureEncoder` (a new implementation, §4) and `CanonicalNetwork` (whose
`feature_set_id` field, §5, already provides the versioning seam `NnueNetwork.load()`
checks). Adding a feature set is a new `FeatureEncoder` plus a format-version bump, not
a rewrite of `DatasetProvider`, `Trainer`, or `Quantizer` — those stages consume
already-encoded feature indices and never inspect what feature set produced them.

---

## 14. Failure Modes

| Failure mode | Detection |
|---|---|
| Java/Python feature-index drift | Shared-corpus parity CI check (§4) |
| int16 overflow from insufficient weight clipping | Quantization validator's clipping-boundary report (§7); ADR-002's oracle-bound test on the Java side catches it post-export |
| Non-deterministic quantization/export | Byte-identical re-run assertion (§7, §12 trainer CI) |
| Malformed/truncated `.nnue` written by Exporter | `NnueNetwork.load()`'s header/length validation (§8) — but only if the Java-side loader is itself correct; issue [#191](https://github.com/coeusyk/chess-engine/issues/191) (qa/qb=0 not rejected) is a known current gap the exporter must not rely on |
| Missing/incomplete provenance manifest | Manifest schema validation in trainer CI (§12); "a net without a complete report cannot be promoted to default" per PRD §4 |
| Eval-scale mismatch destabilizing tuned search margins | KFinder-calibrated training targets (PRD §"Trainer Requirements"); corpus-level scale comparison, a named Phase D exit criterion (PRD §5 Risks table) |
| `CanonicalNetwork` drifting from what `Exporter` actually writes | Round-trip test: `CanonicalNetwork` → `.nnue` bytes → `NnueNetwork.load()` (Java, via a committed test fixture) → assert loaded values equal the original `CanonicalNetwork` |
| Engine accidentally gaining a Python/PyTorch dependency | Boundary check (§15 Invariant 8), enforceable the same way `generate_report.py`'s Architectural Boundary Report already flags unapproved production→debug edges — extended to flag any `engine-core`/`engine-uci`/`engine-tuner`/`chess-engine-api` node depending on a `trainer/` node |

---

## 15. Trainer Architecture Invariants

Permanent, tracked across every future trainer PR — analogous to how
`generate_report.py`'s `FROZEN_BOUNDARY_CLASSES` mechanism tracks the five ADR-frozen
Java classes today. A PR that violates one of these needs either a fix or a new ADR
explicitly revising the invariant — never a silent regression.

1. **DatasetProvider isolation.** No `DatasetProvider` implementation imports or
   depends on another `DatasetProvider`, or on `Trainer`/`Quantizer`/`Exporter`
   internals. Adding a new data source is a new file, not a modification to existing
   providers.
2. **Java/Python feature parity.** `FeatureEncoder` (Python) and `FeatureExtractor`
   (Java, `engine-core/.../eval/nnue/FeatureExtractor.java`) produce identical feature
   indices for every position in the shared parity corpus. Any change to either side's
   formula requires updating both and re-passing the parity check in the same PR.
3. **Canonical Network intermediate representation.** `Exporter` and `Quantizer`
   consume only `CanonicalNetwork`, never a raw PyTorch `state_dict` or `nn.Module`.
   Training-code refactors (layer renaming, module wrapping) must not require
   `Exporter`/`Quantizer` changes unless the actual tensor shapes or semantics change.
4. **Export contract.** `Exporter`'s output byte-for-byte satisfies
   `NnueNetwork.load()`'s documented format without requiring any Java-side loader
   change. A `.nnue` format version bump is a coordinated change to both sides in one
   PR (made practical by the §16 in-repo decision).
5. **Quantization determinism.** The same float32 `CanonicalNetwork` + the same
   `qa`/`qb` always quantizes to byte-identical int16 arrays, on any machine, with no
   RNG or hardware-dependent rounding involved.
6. **Provenance chain.** Every exported `.nnue` has a sidecar manifest satisfying PRD
   §4's schema, and every field in that chain (dataset id → config → checkpoint → UUID
   → SPRT log → release report) is traceable without asking the author.
7. **Reproducibility guarantees.** Same commands + same data + same seed →
   statistically equivalent trained net (not bit-exact across GPU hardware);
   byte-exact quantization/export given the same checkpoint (a pure-function
   guarantee, stronger than "statistical").
8. **Engine/trainer runtime isolation.** No Java production code
   (`engine-core`/`engine-uci`/`engine-tuner`/`chess-engine-api`) may depend on Python,
   PyTorch, or any `trainer/`-tree artifact at build or runtime. The only sanctioned
   integration boundary is the exported `.nnue` artifact plus the documented feature
   specification (§4). No JNI, no embedded Python, no PyTorch runtime dependency in the
   Java engine — ever. This is the single most consequential invariant on this list:
   every other invariant governs trainer-internal quality; this one governs whether
   the trainer can exist in this repository at all without compromising the engine's
   own frozen constraints (CLAUDE.md §3 "Critical Constraints": "`engine-core` must
   never have Spring/HTTP dependencies" — this is the same discipline, extended to the
   trainer boundary).

Trainer and engine evolve independently except at these eight seams. A PR that only
touches `trainer/dataset/` should never need to touch `engine-core`, and vice versa —
if it does, that's a signal one of these invariants is being violated, not a normal
cross-cutting change.

---

## 16. Repository Location — Accepted Decision

**Status: accepted, 2026-07-13**, resolved via `/grilling` against the alternative of a
separate repository.

**Decision:** the trainer lives at `trainer/`, a top-level directory in this
repository — not a separate repo.

**Rationale:**
- The trainer and engine share several versioned contracts: the feature specification
  (§4), the `.nnue` binary format (§8), `architectureId`, `featureSetId`, the
  quantization format (§7), and provenance metadata (§9). Keeping them in one
  repository allows atomic evolution of these contracts in a single commit/PR — a
  `.nnue` format version bump is one PR touching both the Python exporter and the Java
  loader together, not a coordinated two-repo release.
- One repository preserves end-to-end provenance: a single commit hash covers the
  trainer, exporter, engine, documentation, benchmarks, and CI configuration that
  produced and validated a given network (§9) — this aligns with the project's
  existing architecture-audit workflow (graphify, `docs/architecture/graph-audits/`),
  which already assumes one graph covering the whole repository.
- This repo already models mixed-concern module isolation successfully
  (`engine-core`/`engine-uci`/`engine-tuner`/`chess-engine-api`/`tools`, per CLAUDE.md
  §1) — `trainer/` extends that same pattern rather than introducing a new one.
- The project has a single maintainer (PRD §2 persona) building this to understand
  every layer personally — the classic justifications for a separate repo
  (independent release cadence, different teams, different access-control boundaries)
  don't apply at this project's current scale.

**Rejected alternative — separate repo:** cleaner dependency isolation (zero
PyTorch/CUDA footprint in this repo) and independent CI/release cadence, at the cost
of cross-repo commit-pinning for provenance, two-repo coordination for any `.nnue`
format change, and losing single-graphify-report visibility into the trainer. The PRD's
own risk table already notes "two-language project raises maintenance burden" as a
**Low**-severity risk, mitigated by "hard contract boundary (.nnue format only);
trainer fully documented and seeded" — that mitigation holds equally well whether the
two languages share a repository or not, so it doesn't favor either option.

**What this decision does NOT do:** it does not weaken §15 Invariant 8. Being in the
same repository is a convenience for atomic contract changes and provenance — it is
not permission for the engine to import trainer code or vice versa. CI must enforce
this via path-scoped workflows (§12): Java-only PRs must not execute PyTorch jobs, and
trainer-only PRs must not unnecessarily execute engine-only workflows. This is a
concrete follow-up (not yet implemented — `ci.yml` currently has no path filters at
all) tracked for the first real trainer PR, not an assumption already true today.

**Proposed top-level layout** (structure only — no files created by this doc):

```
trainer/
    README.md
    pyproject.toml
    requirements.txt          (or uv/pdm equivalent, decided at implementation)
    trainer/
        dataset/              # DatasetProvider implementations
        encoding/              # FeatureEncoder
        model/                 # PyTorch nn.Module (§6)
        export/                # CanonicalNetwork, checkpoint_to_canonical, Exporter (§5, §8)
        quantization/          # Quantizer (§7)
        validation/            # Validator, quantization validator (§11)
        cli/                   # scripted entry points (§10 reproducibility)
    tests/
    configs/                   # seeded, versioned hyperparameter configs (§10)
    scripts/                   # dataset acquisition/labeling drivers (PRD §2 US-5)
    outputs/                   # gitignored — checkpoints, shards, exported nets
```

---

## 17. Revisit Conditions

- Re-confirm this entire document if ADR-001's feature-set revisit conditions are ever
  met (§13) — a HalfKP/HalfKA migration touches §4, §5, and §13 directly.
- Re-confirm §5's `CanonicalNetwork` field list if the `.nnue` format version ever
  bumps (§8).
- Re-confirm §12's CI strategy once a real (non-synthetic) trained network exists —
  mirrors ADR-002's own deferred oracle-bound-numeric-threshold revisit condition,
  which explicitly waits for a real network rather than guessing against synthetic
  test data.
- Re-confirm §16's repository-location decision only if the project ever gains
  additional maintainers with a genuine need for independent release cadence or
  access-control separation — not preemptively, and not merely because CI cost grows
  (that's a path-scoping problem, §12, not a repository-topology problem).
- Re-confirm §6's clip-only-training decision (revisit toward quantization-aware
  training) if a real trained net's held-out validation (§11) shows a measurable
  accuracy gap attributable specifically to post-training quantization rather than to
  data volume, feature-set ceiling (ADR-001), or search-margin miscalibration — not
  preemptively, and not on the first trained net (PRD §5 Risks: "first bootstrap net
  lands near parity" is an expected, not diagnostic, outcome).

---

## 18. Contract Matrix

Every cross-module and internal contract this document defines, in one table, so
ownership and evolution rules are explicit before any implementation lands.
**"Owner" means whose sign-off is required to change the contract's *shape*** (schema,
format, interface) — not who happens to write the code implementing it today. Every
Owner cell below is phrased the same way — *which side holds authority* (Engine /
Trainer / Shared-via-ADR / Shared-via-this-doc), plus the artifact that enforces it —
so the column answers one consistent question instead of naming whatever artifact
happens to be closest to each contract.

| Contract | Producer | Consumer | Owner | Versioned? | Scope |
|---|---|---|---|---|---|
| **Feature specification** (§4) | `FeatureExtractor.java` (Java) and `FeatureEncoder` (Python) — dual, independent implementations of one formula | `NnueEvaluator`/`NnueNetwork` (Java, inference); `Trainer`/model (Python, training) | Split: the *feature-set choice* (768, dual-perspective, non-king-relative) is shared via ADR-001; the *exact index-layout arithmetic* within that choice has no ADR of its own — it's enforced purely by Invariant 2 plus the parity corpus below, so a layout-only tweak needs a coordinated PR, not a superseding ADR | Yes — `featureSetId`, checked by `NnueNetwork.load()` (gates the feature-set choice; layout changes within the same `featureSetId` are not independently version-gated today) | Shared |
| **Feature parity corpus** (§4) | `FeatureIndexParityTest.java`'s `CORPUS` (Java) today; a future Python parity test consumes the same values | Both `FeatureExtractor.java` and (once it exists) `FeatureEncoder`'s test suite — the fixture both sides are pinned against | Shared — the enforcement mechanism for the index-layout arithmetic (see previous row); a change to this corpus alone (no formula change) is a normal code review, not an ADR matter | No explicit version field — a plain FEN list; a change is a normal code review on the shared fixture, not a format bump | Shared |
| **`.nnue` binary format** (§8) | `Exporter` (Python) | `NnueNetwork.load()` (Java) | Engine, enforced by `NnueNetwork.java` — "read-only from the trainer's perspective" (§8); the exporter conforms to it, not the reverse | Yes — `formatVersion`, `architectureId`, `featureSetId`, `quantVersion` fields | Shared |
| **Canonical Network IR** (§5) | `checkpoint_to_canonical()` (Python) | `Quantizer`, `Exporter` (Python) | Trainer, enforced by this document's §5 | No independent version field — versioned indirectly via the `architecture_id`/`feature_set_id` it carries through to `.nnue` | Internal trainer detail |
| **`DatasetProvider` API** (§3) | Each `DatasetProvider` implementation (`text_provider.py`, future Stockfish/self-play providers) | `Transform`/`FeatureEncoder`/`Labeler` pipeline (Python) | Trainer, enforced by this document's §3 — the iterator contract itself, not any one provider implementation | No — an in-process interface, not a serialized/persisted format | Internal trainer detail |
| **Quantization pipeline** (§7) | `Quantizer` (Python), consuming training-time-chosen `qa`/`qb` | `Exporter` — writes `qa`/`qb` into the `.nnue` header | Trainer owns the *procedure* (enforced by this document's §7); the resulting `qa`/`qb` *values* fall under the Engine-owned `.nnue` contract once exported (see row above) | Yes — `quantVersion`, reserved in the `.nnue` header for a future quantization scheme change, not yet used (`NnueNetwork.java` reads and discards it today) | Internal trainer detail (procedure); shared (its output values, via the `.nnue` row) |
| **Provenance manifest** (§9) | `Exporter` (Python) | `nets/` registry (humans, release reports); the engine reads only the embedded UUID via `info string` | Trainer owns the manifest schema (enforced by this document's §9); the UUID join-key falls under the Engine-owned `.nnue` header contract | Yes — the manifest's own "format version" field (PRD §4) | Shared — mostly trainer-internal, but the UUID crosses into the `.nnue` header |
| **Golden evaluation corpus** (`bench/nnue-corpus/`, Phase C) | `NnueCorpusGenerator` (Java, `engine-core` test scope) | `NnueGoldenEvalTest` (Java CI) consumes `golden-evals.csv`'s pinned values exact-match against `TestNetworks.synthetic()` specifically; `Validator`'s eval-scale check (Python, §11) reuses only the underlying position corpus (the `.epd` category files) and computes its own comparison — it does not and cannot consume `golden-evals.csv`'s pinned values, which are valid only for the one synthetic network they were generated against | Engine, enforced by `engine-core` test scope — created in Phase C on the Java side; the trainer consumes the position files read-only, never the pinned-value CSV | Yes — a provenance section (seeds, generator commit, network identity) per the C-4 README | Shared |
| **Validation reports** (Network Release Reports, PRD §4; §11) | A release-report generation script (not hand-written, per PRD §4) combining `Validator` output (Python), benchmark-corpus results (Java), and SPRT/test logs | Maintainer, before promoting a net to default; committed to `nets/` beside the manifest | Shared via this document's "Network Release Reports" content (PRD §4) — schema changes are a doc update, not ad hoc reviewer discretion, even though the report spans both sides' outputs | No runtime version field — the template itself is versioned via normal git history | Shared |

**Reading this matrix against §15's Invariants.** Rows map directly to an invariant
where one exists: feature specification and feature parity corpus ↔ Invariant 2 (both
enforce the same cross-language formula agreement, from different directions —
definition vs. test fixture), `.nnue` format ↔ Invariant 4, Canonical Network IR ↔
Invariant 3, quantization pipeline ↔ Invariant 5, provenance manifest ↔ Invariant 6.
`DatasetProvider` API maps to Invariant 1. The golden
evaluation corpus and validation reports have no dedicated invariant today — both are
lower-risk (test-scope-to-test-scope, or a human-reviewed artifact) than the five
contracts that gate whether a `.nnue` file loads at all or whether the engine can be
made to depend on the trainer. If either ever becomes load-bearing enough to need one
(e.g. the golden corpus becoming CI-gating for the trainer, not just the engine), add
a ninth invariant then — not preemptively, matching this document's own stated
aversion to speculative structure (§3, §5 Tradeoffs).

---

## Follow-up work (not part of this document)

- **ADR-006** (self-written PyTorch trainer vs. pure-Java trainer vs. existing
  framework) and **ADR-007** (staged training data; binpack exclusion) exist today only
  as decision-record entries in `docs/NNUE_PRD.md` Appendix A (items 5–6) and as named
  stubs in Appendix B. Extracting them into standalone ADR files following the
  `docs/adr/ADR-002..009` format is a separate, later task — not performed by this
  document.
- A micro-ADR formally recording §16's repository-location decision (in the same
  format as the existing ADRs) is a reasonable candidate for that same follow-up pass,
  since it is a genuine architecture decision this document only records inline.
- The Phase D implementation plan (which PR/issue implements which piece of §2's
  module breakdown first) is produced only after this document is reviewed
  (`/architecture-review`, `/agentic-eval`) — not included here.
