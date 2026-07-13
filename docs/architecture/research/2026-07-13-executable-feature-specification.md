# Research: Executable Feature Specification Patterns

**Date:** 2026-07-13
**Question:** Are there better patterns than Vex's current choice (independent
re-implementation pinned by a shared golden-value test corpus, per
`NNUE_TRAINER_ARCHITECTURE.md` §4) for keeping `FeatureExtractor.java` and the
not-yet-written `trainer/trainer/encoding/feature_encoder.py` in sync?
**Constraint:** any idea must not force Java's hot-path `FeatureExtractor` to
parse a spec at runtime (CLAUDE.md §3: no allocation on the hot path;
`NNUE_TRAINER_ARCHITECTURE.md` §4 already rejected this for that reason).

## 1. Feature stores define once, share via registry — not runtime parsing

Feast registers a `FeatureView`'s schema at `feast apply` time into a
**registry**; both `get_historical_features` (training) and
`get_online_features` (serving) read the pre-compiled registry entry, not a
spec file parsed per-call. (docs.feast.dev/getting-started/concepts/feature-view)
TFX/tf.Transform goes further: it embeds the transform logic **into the
TensorFlow graph itself**, so the identical graph runs at train and serve
time — "it's guaranteed to be consistent... eliminates one source of
training/serving skew." (tensorflow.org/tfx/guide/transform) Both systems
solve parity by compiling the spec once, ahead of the hot path, not by
parsing it at inference time — consistent with what Vex already rejected.

## 2. Cross-language numeric parity: independent impls + reference/test suite is the norm

ONNX ships a **Python reference runtime** used only "to clarify the
semantics... and help understand and debug ONNX tools" — real runtimes
(TensorRT, ONNX Runtime, etc.) are independent, optimized implementations
validated against operator tests, not a shared interpreter every consumer
runs. Drift is caught by **opset versioning** (each operator has a numbered
version), not runtime spec-parsing. (onnx.ai/onnx/intro/concepts.html) This
is architecturally the same shape as Vex's choice: independent
implementations, pinned by a shared test corpus/version number — not codegen,
not a shared runtime parser.

## 3. Version drift detection: explicit ID check beats implicit trust

Protobuf's wire format has **no built-in way to detect a definition
mismatch** ("doesn't provide a way to detect fields encoded using one
definition and decoded using another") — safety comes entirely from
discipline (never reuse field numbers, `reserved` lists).
(protobuf.dev/programming-guides/proto3/#updating) SemVer only encodes
*intent* in the version string; it does not mechanically detect breakage —
consumers must check the number themselves. (semver.org) Vex's
`NnueNetwork.load()` already does the stronger thing both of these lack:
it hard-rejects on `formatVersion`/`architectureId`/`featureSetId` mismatch
at load time (read from repo file `NNUE_TRAINER_ARCHITECTURE.md` §8) —
closer to a monotonic-int gate than either primary source's own mechanism.

## 4. Executable specification: tests as the spec, not prose beside it

Gojko Adzic's *Specification by Example* frames this precisely: "An
automated specification with examples... becomes an executable
specification" (gojko.net/books/specification-by-example). Pact's own docs
describe contract tests the same way — not a schema artifact like OpenAPI,
but "a collection of test cases, each of which describes a single concrete
request/response pair" (docs.pact.io) — the test *is* the contract, so it
cannot silently drift from the implementation the way prose can.
`FeatureIndexParityTest.java`'s `CORPUS` (read from repo file
`NNUE_TRAINER_ARCHITECTURE.md` §4) already is this pattern for Vex.

## Applicable ideas for Vex

1. **Keep independent re-implementation + shared golden-value corpus as CI
   gate** (current plan). Matches ONNX's operator-parity model and Pact's
   "test is the contract" model. Requires no Java runtime spec-parsing.
2. **Do not add a runtime-parsed spec file for `FeatureExtractor.java`.**
   Confirmed against three more primary sources (Feast, TFX, ONNX) — none of
   them put spec-parsing on the hot/serving path either. Would require Java
   runtime spec-parsing — stays rejected.
3. **Optional: a Python-side-only YAML/JSON feature spec that
   `feature_encoder.py` loads at runtime, cross-checked against the Java
   constants by a test (not by `FeatureExtractor.java` itself).** This is
   the Feast/TFX "define once, compile before the hot path" idea applied
   asymmetrically — Python is not perf-critical (per the task brief), so it
   may parse a spec; Java stays hardcoded. Does NOT require Java runtime
   spec-parsing — only if scope ever grows past 8 lines of arithmetic
   (currently not justified, ponytail: YAGNI at today's size).
4. **Consider a monotonic `featureLayoutVersion` companion to the existing
   `featureSetId`**, since `NNUE_TRAINER_ARCHITECTURE.md` §18 notes
   layout-only tweaks within a `featureSetId` aren't independently
   version-gated today. Mirrors ONNX opset versioning / protobuf's explicit
   version fields, stronger than protobuf's own wire format (which has none).
   Does NOT require Java runtime spec-parsing — it's a compile-time constant
   checked at `.nnue` load, same mechanism `NnueNetwork.load()` already uses.
