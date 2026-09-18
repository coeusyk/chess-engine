# ADR-002: Int16 canonical inference with a float32 test-only oracle

Date: 2026-07-12  Status: accepted

## Context

Phase B's PRD scope (§4 Network Specification) calls for "int16 canonical
inference" with "float32 test oracle" as a named, separate decision
(NNUE_PRD.md Appendix B: "ADR-002: int16 canonical inference with float32
test oracle (vs. float-first migration) — Phase B"). The decision was made
and implemented during Phase B's PR2 (`NnueEvaluator`/`NnueNetwork`), but no
ADR was written at the time — this ADR documents that decision retroactively,
against the code as it actually shipped, ahead of Phase C's oracle-comparator
tool needing a documented error bound to validate against.

## Problem

Should NNUE inference run in float32 (matching the PyTorch training graph
exactly) with int16 as an optional optimization layered on later, or should
int16 be the canonical, only production inference path from the start, with
float32 existing solely as a correctness check?

## Alternatives considered

### 1. Int16 canonical, float32 test-only oracle (chosen)
`NnueNetwork` stores weights exclusively as `short[]` (`ftWeights`,
`ftBiases`, `outputWeights` — confirmed, no float fields exist in the class
at all). `NnueEvaluator.evaluate()` runs entirely in integer arithmetic:
clipped accumulators clamped to `[0, qa]`, `long` dot product, final scaling
by `outputScale / (qa * qb)`. A float32 path, when built in Phase C, exists
only as a separate, test-scope utility that dequantizes the same stored
`short[]` weights via `qa`/`qb` and re-runs the forward pass in `double`/`float`
arithmetic to catch integer-arithmetic implementation bugs (overflow,
mis-ordered clamp, wrong shift) — not to catch training-time quantization
loss, since it starts from the same quantized weights as production.

### 2. Float32 canonical, int16 as a later optimization pass
Run inference in float32 matching the PyTorch graph bit-for-bit; add int16
quantization later once correctness is established, mirroring how some NNUE
implementations bootstrap. Rejected: this project's own performance gate
(NNUE_PRD.md §1) requires int16-class throughput from the first playable
build — quiescence search alone calls `evaluate()` on the order of millions
of times per search; a float32-first path would need a second, disruptive
rewrite of the exact same hot loop this ADR is trying to get right once.
Building the throughput-critical path twice is strictly worse than building
it once and testing it against a lightweight oracle.

### 3. Dual-path production inference (float32 fallback + int16 fast path)
Ship both paths in production, selectable at runtime. Rejected: doubles the
weight-storage requirement (`.nnue` would need both `short[]` and `float[]`
copies of every tensor), doubles the surface area that has to stay correct
under every future feature-set change, and the PRD's own Non-Goals (§2)
explicitly rule out this kind of dual-maintenance surface for v1.

## Tradeoffs

Option 1 accepts that the "ground truth" oracle (float32) and the trained
network's actual weights are related only by a documented, bounded
dequantization step, not by two independently-trained models — so the oracle
catches implementation bugs (integer overflow, wrong clamp bound, mis-ordered
operations) but does **not**, by itself, catch a training-time quantization
regression that was baked into the exported weights before the loader ever
sees them (that is the trainer's own quantization-validator's job per PRD
§"Analysis tools (Python, trainer repo)" — a separate, Phase D concern).
Option 2 buys nothing over Option 1 except deferred pain: the hot-path
rewrite has to happen eventually regardless, and delaying it means writing
throwaway float32 search-integration code first. Option 3 buys a live
fallback at the cost of double the format surface and double the
weight-storage size, for a benefit ("switch inference precision at runtime")
nothing in the PRD's user stories actually asks for.

## Decision

Option 1: int16 is the only production inference path
(`NnueEvaluator.evaluate()`), sharing the exact quantized weights the `.nnue`
loader validates and stores. Float32 exists exclusively as a Phase C
test/debug-scope oracle that dequantizes those same weights and re-runs the
forward pass, reporting max/mean absolute error against the int16 path
(PRD's "Oracle comparator" tool). It is never constructed on, or reachable
from, any search call path — `Searcher.evaluate()` (line 1698 in
`Searcher.java`) calls `evaluator.evaluate(board)` through the
`EvaluatorStrategy` interface, which has exactly one `evaluate` method; there
is no second, float32 entry point for the oracle to plug into, by
construction.

**Acceptable numerical drift:** the oracle's job is bug detection, not
precision auditing — any max-absolute-error above what integer rounding in
the clamp/dot-product/scale chain alone can produce is a bug, not "expected
quantization noise." The exact numeric bound is a Phase C deliverable (it
depends on `qa`/`qb`'s actual magnitudes in the first real trained net, not
on the tiny synthetic test networks currently in the repo) — this ADR fixes
the *policy* ("any oracle divergence beyond integer-rounding noise is a
correctness bug, not tolerated drift") and defers the *numeric threshold* to
Phase C, where it will be measured against a real network rather than
guessed.

**Why the oracle is not part of production evaluation:** it duplicates the
entire forward pass in a second numeric representation purely to have an
independent check — running it on the actual search path would roughly
double NNUE evaluation cost for zero playing-strength benefit, directly
violating the performance gate this whole int16-first decision exists to
protect. It belongs in test/debug scope only, exactly as PRD
"Developer Tooling & Debugging (NNUE)" specifies ("these are development
tools, not production features... must add zero cost to the normal search
path").

## Consequences

Easier: one hot-path implementation to optimize and test, matching the
project's own no-allocation/high-NPS constraints (CLAUDE.md §3) from day one;
the oracle can be built, changed, or removed in Phase C without touching
`EvaluatorStrategy`, `NnueEvaluator`, or the `.nnue` format at all, since it
never sits on the interface. Harder: the oracle cannot, by construction,
independently verify the trainer's own quantization choices (weight
clipping, `qa`/`qb` selection) — that verification is the trainer-side
"Quantization validator" tool (PRD, Python analysis tools), a distinct,
later (Phase D) piece of tooling operating on the float checkpoint before
export, not on the already-quantized `.nnue` file this oracle reads.

## Revisit Conditions

- If a second feature set or network topology is ever approved (ADR-001's
  own revisit conditions), re-confirm the oracle's dequantization logic
  still matches the new `featureSetId`/`architectureId` — it currently
  assumes the exact `evaluate()` shape documented above.
- If profiling ever shows the int16 path itself (not the oracle) is not
  meeting the NPS gate, that is a distinct problem from this ADR — this
  decision is about *which representation is canonical*, not about further
  optimizing the int16 path (Vector API work is explicitly ADR-008,
  Phase E).

## Supporting Evidence

Verified directly against source, not from the PRD's description alone:
`NnueNetwork.java` stores `ftWeights`/`ftBiases`/`outputWeights` as `short[]`
only (no float fields in the class); `NnueEvaluator.evaluate()` (lines
104-117) performs the full forward pass in `short`/`int`/`long` arithmetic
with a single `int` return; `clamp()` (lines 119-124) is the clipped-ReLU
step, bounded `[0, qa]`. No float32 oracle exists yet in the frozen Phase B
code — the Phase C readiness audit confirmed this is correctly scoped to
Phase C, not a Phase B gap requiring reopening.

## Open Questions

The exact acceptable-drift numeric bound (mentioned above) is deferred to
Phase C, to be measured against the first real network rather than asserted
here against synthetic test data.
