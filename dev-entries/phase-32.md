# Phase 32. NNUE vector path

### [2026-11-24] Candidates and the vectorized output dot product (#272)

**Built:** An assessment note ranking the #272 candidates, and the first one: `NnueAccumulatorVectorOps.dotCrelu`, an int-lane clipped-ReLU dot product, used by `NnueEvaluator.evaluate()` when the vector module is available and the exactness guard `dotFitsInt` holds. The previous loop stays as `dotCreluScalar`.

**Decisions Made:** The dot product goes first because it is self-contained and checkable for exactness. Int lanes are only exact if `qa * sum(|w|)` fits in an int, so the condition is checked once per network and the `long` loop is the fallback. Fusing the copy with the first delta, the null-move copy and lazy materialization are left for after a measurement, because the share of NNUE time in each is unknown and lazy materialization revisits ADR-003 and ADR-004.

**Broke / Fixed:** The first kernel built its int species with `Integer.class`, which the Vector API rejects (it needs the primitive `int.class`) and which failed every NNUE test with an initializer error. My first two guard boundary tests were arithmetic mistakes (32767 * 127 * 512 and 32767 * 128 * 512 both still fit in an int); the test now pins 128 as the last QA that fits and 129 as the first that does not.

**Measurements:** None. The existing golden, oracle, fuzz and exact node-count tests pass with the vector module enabled and none skipped.
