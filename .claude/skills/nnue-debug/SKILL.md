---
name: nnue-debug
description: Diagnose NNUE evaluation bugs — accumulator corruption, feature extraction errors, quantization drift, mirroring asymmetry, eval regressions. Use when the user says "debug the NNUE", "accumulator mismatch", "NNUE eval looks wrong", or reports NNUE symmetry/regression failures. Diagnosis only — never modifies implementation.
---

# NNUE Debug

You are a diagnostician. **Never modify implementation code.** You may write throwaway test/instrumentation code in the scratchpad, and suggest instrumentation the user can add, but the deliverable is a diagnosis, not a fix.

## Procedure

When invoked:

1. **Classify the suspected bug** (ask or infer from symptoms) into one of the classes below.
2. **Produce a debugging checklist** specific to that class.
3. **Suggest instrumentation** (assertions, dump points, counters) — as suggestions, not edits.
4. **Identify the invariants** that should hold and which one the symptom violates.
5. **Recommend a verification strategy** (which oracle to compare against, on which positions).
6. **Explain the plausible root causes** — why this bug class occurs mechanically.
7. **Recommend the smallest isolating experiment** — one position, one move, one feature if possible.

Output is investigation-oriented: hypotheses ranked by likelihood, evidence for/against, next experiment. Not patches.

## Bug classes & core invariants

### Accumulator corruption (incremental update drift)
- **Invariant:** for every node, incrementally-updated accumulator == full rebuild from scratch.
- Checklist: verify on quiet moves, captures, castling (two piece moves!), en passant (capture square ≠ target square), promotions (piece type changes), null moves, and **unmake** of each. King moves must trigger full refresh if king-bucketed.
- Smallest experiment: play one move class (e.g. one en-passant) from a fixed FEN, compare `accumulator` vs `rebuild()` element-wise; report first differing index.
- Instrumentation: debug-mode assert `incremental == rebuild` at every makeMove; binary-search the game/PV for the first divergent ply.
- Why it occurs: add/sub feature lists wrong for special moves, missed refresh on king-bucket change, unmake not exactly inverting make, dirty-piece list stale.

### Feature extraction
- **Invariant:** active feature set from the extractor == features derivable by brute force from the board (iterate all squares/pieces).
- Dump active feature indices for a position; compare against an independent naive extractor.
- Check index formula: perspective flipping (square ^ 56 vs ^ 63), piece-color encoding order, king-square bucket math, off-by-one in index = kingBucket * X + piece * 64 + square.

### Mirroring / symmetry
- **Invariant:** eval(pos) == -eval(colorFlipped(pos)) from the side-to-move perspective (or ==, depending on convention — state which the engine uses).
- Also horizontal mirror if the net is king-half symmetric.
- Run the existing mirror symmetry test (CLAUDE.md §3) over a corpus; report first asymmetric FEN, then diff the two perspectives' active-feature dumps for it.

### Quantization / int16 vs float oracle
- **Invariant:** quantized inference == float reference within a known bound (e.g. |Δ| ≤ a few centipawns); rank order of moves preserved on test positions.
- Checklist: scale factors per layer, rounding mode (truncate vs round-to-nearest), where clipping happens, accumulation width (int32 for dot products, not int16).
- Smallest experiment: single position, dump per-layer pre/post-activation values in both int and float paths; find first layer where relative error jumps.

### Saturation / overflow
- **Invariant:** no intermediate value exceeds its type's range before intended clipping.
- Instrument: counters for values hitting int16 min/max in accumulator and hidden layers; run over a corpus of extreme positions (many queens, all pieces attacking).
- Why: weight magnitude × max active features > 32767; SIMD saturating adds masking a real overflow.

### Inference tracing
- Dump per-layer: input features, accumulator halves (us/them ordering!), post-clipped-ReLU values, layer outputs, final scale. Compare against a reference implementation (trainer's Python forward pass) on the same net file.
- Check net file parsing: header, weight layout (row- vs column-major), transposition for SIMD.

### Eval regression (strength/accuracy dropped)
1. Confirm it's eval, not search: fixed-depth eval of a test suite before/after.
2. Bisect: which commit; then which component (net file vs inference code vs feature transformer).
3. Compare eval distributions over a corpus (mean/stddev shift indicates scaling bug; scattered outliers indicate position-class bug — bucket by material/king zone).
4. Cross-check against float oracle to separate quantization from logic.

## Debugging methodology for accumulator corruption

1. Reproduce with a deterministic game/PV.
2. Assert `incremental == rebuild` per ply; find first bad ply.
3. Classify the move at that ply (castle/ep/promo/king move).
4. Diff element-wise: which feature indices are wrong → tells you which add/sub was missed or doubled.
5. Verify unmake by asserting accumulator equality after make+unmake round trip.

Always end with: ranked hypotheses, the single smallest next experiment, and the invariant it tests.
