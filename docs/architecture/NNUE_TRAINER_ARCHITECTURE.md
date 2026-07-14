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
| `Quantizer` | float32 → int16 weights with clipping verification | `CanonicalNetwork` → `QuantizedCanonicalNetwork` |
| `Exporter` | Quantized representation + provenance → `.nnue` + manifest | `QuantizedCanonicalNetwork` → artifact |

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

### 4.1 Executable Feature Specification — `docs/architecture/feature-spec/v1.json`

Grilled 2026-07-13, informed by
`docs/architecture/research/2026-07-13-executable-feature-specification.md` (a
primary-source survey of Feast, TFX, ONNX, protobuf, SemVer, and
Specification-by-Example/Pact).

**Problem.** The formula above is duplicated as prose in this document, as Java
constants in `FeatureExtractor.java`, and (from D-3 onward) as Python constants in
`feature_encoder.py`. Three independently maintained copies of "6 piece types, 2
colors, 64 squares, a8=0 numbering,
`relativeColor*384 + pieceTypeIndex*64 + relativeSquare`" is a duplication distinct
from — and upstream of — the index-*value* duplication `FeatureIndexParityTest.CORPUS`
already guards against.

**Design.** A single versioned, machine-readable JSON file is the canonical
declaration of the feature set's *shape and rules* (not its golden index values —
that stays the parity corpus's job, see below):

```json
{
  "spec_version": 1,
  "feature_set_id": "plain-768",
  "piece_types": ["pawn", "knight", "bishop", "rook", "queen", "king"],
  "colors": ["white", "black"],
  "squares": 64,
  "square_numbering": "a8=0, row-major top-to-bottom -- matches Board.getChessSquare (rank = 8 - square/8), NOT the a1=0 convention",
  "features_per_perspective": 768,
  "relative_color_rule": "0 if piece_color == perspective_color else 1",
  "relative_square_rule": "square if perspective_color == white else square XOR 56",
  "piece_type_index_rule": "0-based ordinal into piece_types, i.e. Java's pieceType - 1",
  "index_formula": "relative_color * (len(piece_types) * squares) + piece_type_index * squares + relative_square"
}
```

**Location is deliberate.** The file lives under `docs/architecture/`, not inside
`trainer/`, specifically so a Java *test* reading it never becomes a Java artifact
depending on "any `trainer/`-tree artifact" — the exact phrase Invariant 8 (§15)
prohibits for production code. Test-scope-only, and outside `trainer/` entirely: both
independently sufficient to keep this file's existence from eroding Invariant 8, even
though it's a belt-and-suspenders precaution here since Invariant 8 only binds
*production* code in the first place.

**Consumers, asymmetric by design:**
- **Python (`trainer/trainer/encoding/feature_encoder.py`, D-3):** loads `v1.json` at
  import time and builds its formula from these fields — genuinely *derives* behavior,
  satisfying this improvement's goal directly. Python carries no hot-path allocation
  constraint (CLAUDE.md §3 binds `engine-core`/`engine-uci` only), so runtime parsing
  costs nothing that matters here.
- **Java (`FeatureExtractor.java`):** stays exactly as written today — hardcoded,
  hot-path, allocation-free, zero behavior or code change. §4's already-approved
  rejection of runtime spec-parsing stands, freshly reconfirmed by the research note:
  Feast, TFX, and ONNX all compile or pin specs *before* the hot/serving path, never
  parse them on it. A new test, `FeatureSpecConformanceTest.java` (test scope only,
  never on the production classpath), loads the same `v1.json` and asserts
  `FeatureExtractor.PIECE_TYPES`, `.SQUARES`, `.FEATURES_PER_PERSPECTIVE`, and
  re-derives `featureIndex()` against the spec's documented rules for a sample of
  (color, piece, square) triples — proving equality, not merely documenting an
  intention to match.

**Path resolution.** Both consumers locate `v1.json` by walking upward from their own
source file's location to the repository root (the first ancestor directory
containing `.git`), then resolving `docs/architecture/feature-spec/v1.json` from
there — avoiding the working-directory fragility of hardcoded relative-segment counts
across Maven's and `uv`'s differing invocation conventions.

**No new Java dependency (architecture-review finding, resolved).** `engine-core`'s
`pom.xml` has zero JSON libraries today, in any scope — confirmed by inspection before
finalizing this design. Adding one (Jackson, Gson, `org.json`) solely for one
test-scope file, whose schema is small, flat, and fixed in advance, would be a
disproportionate dependency for the need. `FeatureSpecConformanceTest.java` (D-3)
reads `v1.json` with a purpose-built minimal parser for this file's known shape
(string/int/string-array fields only, no nesting) — not a general JSON library and not
a general parser. Python needs no equivalent decision; `json` is stdlib.

**Honest scoping of "derive."** Python derives literally. Java is *provably and
continuously verified equal to* the contract via a dedicated CI-gated test — the
strongest relationship achievable without reopening the hot-path constraint that
Invariant 8 and CLAUDE.md §3 already freeze. This asymmetry is the deliberate
resolution of a real tension between eliminating duplicated constants and the
pre-existing, still-binding engine/trainer isolation boundary — not an oversight. A
future reader should not mistake Java's hardcoded constants for an unfinished
migration; they are permanent, by design.

**Relationship to the existing parity corpus.** The spec file and
`FeatureIndexParityTest.CORPUS` are complementary, not redundant: the spec is the
*shallow* structural contract (shape, ordering, rule names — human-legible; drift here
is a copy-paste-level bug); the corpus is the *deep* enforcement layer (golden index
values for real positions — drift here is a formula-level bug the spec alone can't
catch, e.g. an off-by-one in `relativeSquare`'s XOR that still produces the "right
shape" of output). Both stay. D-3 decides the corpus's cross-language sharing
mechanism; this improvement decides the shape contract's.

**Versioning.** `spec_version` (starts at 1) tracks the JSON schema's own shape,
independent of `feature_set_id`. A new feature set (HalfKP, §13) gets a new file
(`v2.json`, its own `feature_set_id`) rather than mutating `v1.json` — mirroring
ONNX's per-operator opset versioning and this document's existing "frozen contract,
describe don't redesign" treatment of the `.nnue` format. `v1.json` is the
authoritative definition of what today's `.nnue` `featureSetId` byte *means*
structurally; the two are the same concept viewed from two contracts (§18, updated).

**Alternatives considered (beyond §4's own, which still stand):**
1. *Codegen Java from `v1.json` at build time (rejected).* The same objection §4
   already raised for the reverse direction — a build-time toolchain for eight lines
   of arithmetic, requiring a code-generation step before every `engine-core` compile
   that nothing else in this project currently needs.
2. *A single spec file both sides parse at runtime, including Java (rejected,
   reconfirmed).* Exactly what §4 already rejected, and the fresh research found no
   counterexample to among Feast/TFX/ONNX — parsing a spec on the hot path is not how
   any surveyed system achieves parity.
3. *No spec file — keep three independent copies, pinned only by the golden corpus
   (rejected).* The status quo this improvement was requested to move past; the golden
   corpus alone gives no single legible place to read "6 piece types, a8=0 numbering"
   without reverse-engineering it from index values.

**Chosen:** the file above — Python derives from it directly; Java is tested against
it. Resolves this improvement's goal on the side that can safely bear runtime parsing,
and strengthens (via a new conformance test) rather than weakens the guarantee on the
side that cannot.

---

## 5. Canonical Network Intermediate Representation

**Revised 2026-07-14** (pre-D-5 architectural improvement), informed by
`docs/architecture/research/2026-07-14-immutable-canonical-network.md` (primary-source
survey of MLIR's SSA-value immutability, `torch.export`'s "functionalized... no
operations are mutations" graph contract, and the PT2E `prepare_pt2e`/`convert_pt2e`
pure-function usage pattern). Supersedes the original single-dataclass-plus-`bool`
design; see that design's own text below for what changed and why.

**Definition.** Two framework-agnostic, immutable, serializable dataclasses — not one
dataclass with a state flag — each representing exactly the tensors and metadata the
`.nnue` format needs at its stage, nothing else:

```python
@dataclass(frozen=True)
class CanonicalNetwork:
    """Mathematical network state -- float32, framework-agnostic, not yet deployable."""
    hidden_width: int
    ft_weights: np.ndarray      # float32, shape [768, hidden_width], row-major per feature
    ft_biases: np.ndarray       # float32, shape [hidden_width]
    output_weights: np.ndarray  # float32, shape [2, hidden_width], perspective-major ("us" then "them")
    output_bias: float
    qa: int
    qb: int
    output_scale: int
    architecture_id: int
    feature_set_id: int


@dataclass(frozen=True)
class QuantizedCanonicalNetwork:
    """Deployable engine state -- int16 (int32 for output_bias), byte-for-byte what
    Exporter needs to write into the .nnue body (§8); still framework- and
    provenance-agnostic."""
    hidden_width: int
    ft_weights: np.ndarray      # int16, shape [768, hidden_width], row-major per feature
    ft_biases: np.ndarray       # int16, shape [hidden_width]
    output_weights: np.ndarray  # int16, shape [2, hidden_width], perspective-major
    output_bias: int
    qa: int
    qb: int
    output_scale: int
    architecture_id: int
    feature_set_id: int
```

Both are minimal — **two dataclasses, two converter functions
(`checkpoint_to_canonical(checkpoint) -> CanonicalNetwork`,
`quantize(network: CanonicalNetwork) -> QuantizedCanonicalNetwork`), round-trip tests
for each.** Neither is a class hierarchy or a plugin interface, and neither grows
beyond what `.nnue` actually needs (see Tradeoffs below) — this restraint is carried
over unchanged from the original design; only the flag-vs-type-split decision changed.

**Genuinely immutable, not just `frozen=True`.** `@dataclass(frozen=True)` blocks
attribute reassignment but does not block in-place mutation of a `numpy.ndarray`
field's contents (`network.ft_weights[0] = 5` still succeeds on a frozen dataclass).
Both dataclasses set `array.flags.writeable = False` on every ndarray field in
`__post_init__`, matching NumPy's own documented mechanism
(`numpy.ndarray.flags`) for a read-only buffer — a one-line-per-array fix, not a custom
immutable-array wrapper class. Attempting an in-place write raises
`ValueError: assignment destination is read-only`, the same failure class
`frozen=True` already gives for attribute reassignment.

**Honest limits of this guarantee (architecture-review + agentic-eval finding,
resolved by documentation, not code).** `flags.writeable = False` is a permission bit,
not tamper-proofing: any holder of the array object can flip it back
(`network.ft_weights.flags.writeable = True`), the same way `object.__setattr__` can
always bypass `frozen=True` for anyone determined to. Neither this design nor the
stdlib primitives it builds on defend against a caller deliberately working around
Python's access controls — that has never been this project's threat model. The one
real, non-adversarial gap is **aliasing**: a NumPy array created via `.numpy()` on a
live PyTorch tensor is a zero-copy view sharing that tensor's underlying buffer, so
setting the *view's* `writeable=False` does not stop the original tensor from being
mutated elsewhere, which would silently change the "immutable" array's values through
the shared buffer. `checkpoint_to_canonical()` and `quantize()` must call `.copy()`
(or NumPy's own `np.array(..., copy=True)`) on any array sourced from a live tensor or
another array before constructing either dataclass — this is a real implementation
requirement, not a documentation nicety, and D-5's tests must cover it (mutate the
source tensor/array after construction, assert the `CanonicalNetwork`/
`QuantizedCanonicalNetwork` field is unaffected).

**Pickling gotcha (implementation finding, D-5).** `pickle` does not preserve a NumPy
array's `writeable` flag across a round-trip — unpickling reconstructs a fresh, plain
writeable array regardless of the source's flags — and a frozen dataclass's default
unpickling restores `__dict__` directly, bypassing `__post_init__` entirely. Without an
explicit `__setstate__` that re-applies the same freeze helper `__post_init__` uses,
`pickle.loads(pickle.dumps(network))` would silently return an object that claims
immutability but isn't. Both dataclasses define `__setstate__` for exactly this reason;
D-5's tests cover it directly (round-trip through `pickle`, assert the restored array is
still read-only).

**Why two dataclasses now, not the `quantized: bool` flag this section previously
specified.** The original design shared one dataclass with a boolean, reasoned as: "if
the trainer later needs more than one axis of type state, revisit — two dataclasses
would be the better call at that point, not this one." This revision is exactly that
revisit, prompted directly by the requirement that `Quantizer` must not mutate its
input: a single mutable-in-spirit object toggling a flag makes "does quantizing mutate
the network I already have a reference to, or hand me a new one" ambiguous by
construction — the research note found no compiler or ML system surveyed representing
"before this pass" / "after this pass" as the same object with a state flag; MLIR's
passes and PyTorch's own `prepare_pt2e`/`convert_pt2e` both produce a distinct value
per stage. The type itself now carries what the bool used to (`isinstance` answers
"is this quantized" with the same certainty `quantized=True` did, with no way to
construct a mixed-precision object the old design's runtime-only bool couldn't prevent
either).

**Provenance fields removed from both types (a second, related simplification).** The
original design carried `network_uuid`, `trainer_commit`, `created_at_epoch_seconds` as
`Optional[...] = None` on `CanonicalNetwork`, unpopulated through both `Quantizer` and
into `Exporter`'s input. Splitting into two types makes this dead weight visible: three
always-`None` fields would now duplicate across *two* frozen dataclasses instead of
one, for information neither stage produces or consumes. `CanonicalNetwork` represents
mathematical network state; `QuantizedCanonicalNetwork` represents deployable engine
state — provenance is neither, it is artifact identity assigned once, at the moment
`Exporter` (D-6) decides to actually emit a file, unchanged from this document's
existing §6 framing ("the model is not responsible for provenance metadata... attached
at export time"). `Exporter`'s own signature (a D-6 design decision, not this
section's) will accept a `QuantizedCanonicalNetwork` plus the provenance triple
separately, rather than mutating fields on a network that was otherwise fully immutable
one stage earlier.

**Pipeline position:**

```
Trainer (PyTorch, state_dict + optimizer state, training-only)
    │  Checkpoint Loader (checkpoint_to_canonical())
    ▼
CanonicalNetwork (immutable, float32)
    │  Quantizer (quantize() -- pure function, does not mutate its input)
    ▼
QuantizedCanonicalNetwork (immutable, int16)
    │  Exporter (D-6; takes QuantizedCanonicalNetwork + ExperimentMetadata (§10.1,
    │            already travels with the checkpoint, not with the IR) + a freshly
    │            assigned network_uuid/created_at_epoch_seconds; assembles the
    │            provenance triple from outside the IR, never as IR fields)
    ▼
.nnue bytes  +  <uuid>.json manifest
```

**Rationale (carried over, still the core justification for having an IR at all).**
Two downstream stages — `Quantizer` and `Exporter` — both need to pull the same fixed
set of tensors out of whatever the training loop produced. Without a named
intermediate, that extraction logic either duplicates across both stages, or
`Quantizer`'s output becomes a private, undocumented shape that only `Exporter` happens
to understand — an implicit contract with no test surface of its own. The
`CanonicalNetwork`/`QuantizedCanonicalNetwork` pair is what stops that duplication:
together they are the one place that understands "what a network *is*" at each stage
(the fixed set of named tensors matching the frozen `.nnue` layout, §8); `Exporter` and
`Quantizer` only understand these two types, never a raw `state_dict`.

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
4. *One dataclass with a `quantized: bool` flag (this section's own prior design,
   rejected on revisit).* Superseded above — ambiguous mutation semantics, and no
   surveyed compiler/ML system represents a transformed value this way.
5. *A `TypeVar`/generic `CanonicalNetwork[Precision]` parameterized over dtype
   (rejected).* Would express "same shape, different dtype" with less field
   duplication than two plain dataclasses, but adds a generics layer for two concrete
   instantiations that will only ever be two — speculative flexibility CLAUDE.md
   already warns against ("no config for a value that never changes"). Two plain
   dataclasses is the more boring, more readable choice for exactly two cases.

**Tradeoffs accepted.** This is one more stage boundary in a project that explicitly
avoids unrequested abstraction (CLAUDE.md, ponytail conventions). It is justified here
specifically because: (a) it costs no more code than `Exporter` would need to write
anyway — it's the same tensor-extraction work, just named and given its own test
surface instead of inlined; (b) it is the one seam most likely to be hit by churn
outside this project's control (PyTorch version upgrades), unlike the frozen `.nnue`
format which changes only by this project's own deliberate version bump; (c) it makes
`Quantizer` and `Exporter` unit-testable without a full PyTorch training environment —
both `CanonicalNetwork` and `QuantizedCanonicalNetwork` round-trip to `.nnue` bytes and
back with no `torch` import required at all. Splitting into two types over one
flag-bearing type adds one more class definition (a handful of lines) in exchange for
removing an entire class of "did this mutate or not" ambiguity — a cheap trade.

**Future evolution.** If `.nnue` ever gains a version 2 (larger topology, HalfKP
features per §13), both `CanonicalNetwork` and `QuantizedCanonicalNetwork` gain new
optional fields behind the same `architecture_id`/`feature_set_id` versioning
`NnueNetwork.load()` already rejects-on-mismatch. `Exporter` and the Java loader change
together; `Trainer` does not need to change its checkpoint-side code at all.

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

**Pipeline shape (revised 2026-07-14 alongside §5's immutable-IR split):**

```
CanonicalNetwork (immutable, float32)
    │  quantize() -- pure function, does not mutate its input (§5)
    │  round-to-nearest int16 for ft_weights / ft_biases / output_weights
    │  (no additional x qa / x qb multiply -- see "No additional scale multiply" below)
    │  round-to-nearest int32 for output_bias -- a separate, wider range (§8: `i32
    │  outputBias`), not part of the int16 tensor clip
    │  qa / qb / output_scale / architecture_id / feature_set_id / hidden_width pass
    │  through unchanged -- already integers, not transformed by quantize() at all
    ▼
QuantizedCanonicalNetwork (immutable, int16 tensors + int32 output_bias)
```

**The clipping-boundary report is a separate function, not a `quantize()` return
value.** `quantize()`'s signature stays exactly `CanonicalNetwork ->
QuantizedCanonicalNetwork` — no tuple, no side-channel result — for the same reason §5
rejected bundling `quantized: bool` onto one dataclass: don't overload one return value
with two concerns. A second, independent function
(`clipping_report(network: CanonicalNetwork) -> ...`) inspects the pre-rounding float
values directly (flags values at the int16 saturation edge — PRD's "Weight histogram +
clipping report" analysis tool) and can be called whether or not `quantize()` is ever
invoked. `output_bias`'s int32 range is not a realistic overflow target and is not part
of this report.

**No additional scale multiply at quantization time.** FT weights already train in
qa-native (int16-scale) float units, established in §6/D-4: `NnueOracle.java`'s float64
reference oracle clamps to the *same* `qa` ceiling as the int16 inference path, proving
training happens directly in the target int16 scale, not a normalized range requiring a
later `x qa`. The identical reasoning holds for the output layer: D-4's `NnueNet.forward()`
substitutes `self.output_layer`'s raw float weight/bias directly into the same formula
`NnueEvaluator.java` uses with `outWeight`/`outputBias` (int16/int32) — no `x qb` step
appears anywhere in that formula on either side. `quantize()` is therefore
round-to-nearest (ties-to-even, `numpy.round`'s default) plus an int16 range clip for
every tensor, uniformly — never a scale-then-round step. "qa for FT layer, qb for
output layer" (this section's original phrasing) describes which scale each layer's
values were already trained against, not an operation `Quantizer` performs.

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
dataset id → training config → checkpoint → CanonicalNetwork
    → QuantizedCanonicalNetwork → .nnue + manifest (atomic pair)
    → benchmark-corpus results → SPRT log → release report (PRD §4)
```

The §16 in-repo decision makes the `trainerCommit` field strictly simpler than a
separate-repo trainer would: one commit hash in one repository covers the trainer
code, the exporter, the engine's loader, the documentation, the benchmarks, and the CI
configuration that validated the release — no cross-repo commit-pinning is needed to
answer "what exact state of everything produced this file."

### 9.1 Integrity checksum placement — resolved D-6, recorded here

**Decision (D-6, 2026-07-14).** The manifest carries an `nnue_sha256` field (SHA-256 of
the exported `.nnue` file's bytes) — **not** a checksum embedded in the `.nnue` binary
itself. This closes the same integrity-detection goal a from-scratch design would
solve with an embedded checksum, without touching the frozen `.nnue` byte layout (§8)
at all.

**Consumer.** `nnue_sha256` is an offline/tooling check — a CI or release-report step
(PRD §4 "Network Release Reports") re-hashes the committed `.nnue` and compares against
the manifest before a net is promoted, and anyone auditing the `nets/` registry can spot
a mismatched or corrupted file. **The Java engine never reads or verifies this field at
runtime** — the manifest lives in the `nets/` registry, not alongside a distributed
`.nnue` release binary the engine loads, so there is no runtime consumer by design, not
by oversight. This is a deliberate scope boundary: `NnueNetwork.load()`'s own existing
header/length validation (§8) is the runtime integrity check; `nnue_sha256` is the
release-pipeline integrity check, layered on top, not a replacement for it.

**Why not embed it in the binary (the alternative a from-scratch design might pick).**
`NnueNetwork.load()` is frozen (§8) — adding a trailing checksum field would be a
binary-format version bump requiring a coordinated Java loader change, exactly the
scope this decision avoids. The manifest already exists as an atomically-exported
sidecar (this section, above) with plenty of room for one more field.

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

### 10.1 Reproducibility Infrastructure — `trainer/trainer/reproducibility/`

Grilled 2026-07-14, informed by
`docs/architecture/research/2026-07-14-reproducibility-infrastructure.md` (primary-source
survey: PyTorch's own randomness/DataLoader/checkpoint-saving docs, NeurIPS's
reproducibility checklist, PyTorch Lightning's `deterministic=` flag docs).

**Problem.** Before any training loop exists (D-4), RNG seeding, deterministic-execution
configuration, and run-identification metadata need one owner — otherwise each future
script (`train.py`, a future eval/sweep script) reinvents its own seeding convention,
and drift between them silently breaks the "same seed → statistically equivalent net"
guarantee this section already promises.

**Design.** A dedicated package, three narrow modules, no training-loop or checkpoint-
serialization code (that lands in D-4's `train.py`, which *uses* this package):

- **`seeding.py`** — `seed_everything(seed)` threads one top-level seed through
  `random`, `numpy.random`, and `torch.manual_seed`/`torch.cuda.manual_seed_all`.
  `dataloader_generator(seed)` returns a seeded `torch.Generator` for
  `DataLoader(generator=...)`. `worker_init_fn(worker_id)` reseeds `numpy`/`random`
  inside each `DataLoader` worker process — closing a real, easy-to-miss gap: PyTorch's
  own multi-worker default only reseeds *its own* RNG per worker, so `numpy`/`random`
  calls inside `Dataset.__getitem__` silently duplicate across workers otherwise
  (research note, idea #3).
- **`determinism.py`** — `configure_deterministic_execution(enabled)`, opt-in only
  (default off for real training runs). When enabled: `torch.use_deterministic_algorithms(True)`,
  `cudnn.deterministic=True`, `cudnn.benchmark=False`. Useful for small/CI-scale runs
  (§12) where the throughput cost is negligible; not the default because some ops have
  no deterministic kernel and raise at runtime when forced (research note, idea #5).
- **`experiment_metadata.py`** — `ExperimentMetadata` (seed, trainer git commit,
  start timestamp, resolved config) and `capture(seed, config)`. The lighter of two
  reproducibility tiers PyTorch's own docs distinguish: "identify this run" vs. "exact
  mid-training resume" (full RNG-state dumps). Vex deliberately implements only the
  first — full RNG-state checkpoint fields would be dead weight given this section's
  own "not bit-exact across GPU hardware" disclaimer already rules out exact resume as
  a goal (research note, idea #4).

**Relationship to §9's provenance manifest.** `ExperimentMetadata` is not the manifest
— it is lighter, and scoped to what a *training run* needs to be identified while it is
still running or freshly checkpointed. `Exporter` (D-6) assembles the full §9 manifest
at export time, using a checkpoint produced with this metadata as one of its inputs, not
the reverse. Keeping these separate avoids forcing every checkpoint to carry
export-time-only fields (dataset stage mix, quantization version) that don't exist yet
when a checkpoint is written mid-training.

**Single seed, not per-component seeds.** Data-shuffle, weight-init, and (future)
augmentation seeds are not split apart. Per-component seeds are a variance-isolation
tool for sweep-scale research operations; Vex is single-maintainer scale with no
demonstrated need to isolate those sources today (research note, idea #2) — revisit if
Stage 3 self-play variance debugging (Phase E) actually needs it, not preemptively.

**Alternatives considered.**
1. *Fold seeding/determinism directly into `train.py`, no separate package (rejected).*
   Works for exactly one script; the moment a second script needs the same seeding
   convention (an eval/sweep script, or a future Stage 3 self-play driver), the choice
   is duplicate-and-drift or retrofit a shared module under time pressure. Establishing
   ownership now costs three small files.
2. *RNG-state-exact checkpoint resumability (rejected for v1).* PyTorch's own
   saving/loading tutorial recommends `state_dict` + optimizer + epoch and does not
   include RNG state; exact resume is a separate, heavier tier the tutorial itself
   doesn't bundle in by default (research note, idea #4). Nothing in Invariant 7 or
   this section's own guarantees requires it — adding it now would be unrequested
   scope with no consumer.
3. *Full determinism as the training default (rejected).* PyTorch's own
   `use_deterministic_algorithms(True)` raises for ops with no deterministic kernel,
   and disabling `cudnn.benchmark` costs real throughput industry-wide (research note,
   idea #1, #5) — matches this section's existing "not bit-exact on GPU" framing
   exactly, so keeping full determinism opt-in requires no change to that framing.

**Chosen:** the three-module package above. `configure_deterministic_execution`,
`seed_everything`, `dataloader_generator`, `worker_init_fn`, `ExperimentMetadata`, and
`capture` are the only public surface; D-4's `train.py` is this package's first
consumer.

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
| Eval-scale mismatch destabilizing tuned search margins | KFinder-calibrated training targets (PRD §"Trainer Requirements"); corpus-level scale comparison, a named Phase D exit criterion (PRD §5 Risks table). **Current status (D-6):** `Validator.eval_scale_check()` (§11) is implemented and tested against a synthetic fixture, but has no classical-eval-labeled corpus to run against yet — `bench/nnue-corpus/golden-evals.csv` is pinned to the synthetic CI test net, not classical evaluation. Generating a real corpus needs a small Java test-scope tool (analogous to `NnueCorpusGenerator`), tracked as separate follow-up work, not yet built. **This must be resolved before NNUE is promoted past a candidate net** (PRD §1's Strength gate depends on search margins being correctly calibrated) — do not treat the mechanism's existence as satisfying the release gate itself. |
| `QuantizedCanonicalNetwork` drifting from what `Exporter` actually writes | Round-trip test: `QuantizedCanonicalNetwork` → `.nnue` bytes → `NnueNetwork.load()` (Java, via a committed test fixture) → assert loaded values equal the original `QuantizedCanonicalNetwork` |
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
   Enforced at two depths (§4.1): the shallow structural contract
   (`docs/architecture/feature-spec/v1.json`, which `FeatureEncoder` derives from
   directly and `FeatureSpecConformanceTest.java` verifies Java against), and the deep
   golden-value corpus (`FeatureIndexParityTest.CORPUS`).
3. **Canonical Network intermediate representation.** `Quantizer` consumes only
   `CanonicalNetwork`, never a raw PyTorch `state_dict` or `nn.Module`; `Exporter`
   consumes only `QuantizedCanonicalNetwork`. Both types are immutable (§5) —
   `Quantizer` is a pure function that never mutates the `CanonicalNetwork` it is given.
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
| **Feature specification** (§4, §4.1) | `docs/architecture/feature-spec/v1.json` (shape contract, both sides derive-from/tested-against); `FeatureExtractor.java` (Java, hardcoded, hot-path) and `FeatureEncoder` (Python, derives from `v1.json` at runtime) | `NnueEvaluator`/`NnueNetwork` (Java, inference); `Trainer`/model (Python, training); `FeatureSpecConformanceTest.java` (Java test scope, verifies Java against `v1.json`) | Split: the *feature-set choice* (768, dual-perspective, non-king-relative) is shared via ADR-001; the *exact index-layout arithmetic* within that choice has no ADR of its own — it's enforced by Invariant 2, `v1.json`, and the parity corpus below together, so a layout-only tweak needs a coordinated PR (`v1.json` + both languages + the corpus), not a superseding ADR | Yes — `spec_version` inside `v1.json` (schema shape), `feature_set_id` inside `v1.json` mirroring the `.nnue` header's `featureSetId` byte checked by `NnueNetwork.load()` (layout changes within the same `featureSetId` are not independently version-gated today) | Shared |
| **Feature parity corpus** (§4) | `FeatureIndexParityTest.java`'s `CORPUS` (Java) today; a future Python parity test consumes the same values | Both `FeatureExtractor.java` and (once it exists) `FeatureEncoder`'s test suite — the fixture both sides are pinned against | Shared — the enforcement mechanism for the index-layout arithmetic (see previous row); a change to this corpus alone (no formula change) is a normal code review, not an ADR matter | No explicit version field — a plain FEN list; a change is a normal code review on the shared fixture, not a format bump | Shared |
| **`.nnue` binary format** (§8) | `Exporter` (Python) | `NnueNetwork.load()` (Java) | Engine, enforced by `NnueNetwork.java` — "read-only from the trainer's perspective" (§8); the exporter conforms to it, not the reverse | Yes — `formatVersion`, `architectureId`, `featureSetId`, `quantVersion` fields | Shared |
| **Canonical Network IR** (§5) | `checkpoint_to_canonical()` produces `CanonicalNetwork`; `quantize()` produces `QuantizedCanonicalNetwork` (both Python) | `quantize()` consumes `CanonicalNetwork`; `Exporter` (D-6) consumes `QuantizedCanonicalNetwork` | Trainer, enforced by this document's §5 | No independent version field — versioned indirectly via the `architecture_id`/`feature_set_id` both types carry through to `.nnue` | Internal trainer detail |
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
