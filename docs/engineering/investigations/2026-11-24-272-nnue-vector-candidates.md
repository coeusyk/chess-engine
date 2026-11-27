# NNUE vector path: optimization candidates for #272

2026-11-24. This is an inspection of what the NNUE evaluator does per node today, a ranking of the smallest changes worth trying, and the first of them prepared and verified for exactness. Nothing here has been timed. The issue's speedup claims are still hypotheses, and there is no NNUE-mode timing breakdown on file (the `eval_pct` figures in the archived bench output are for the Classical evaluator, 20.2 percent in the P16-2 baseline).

## What the code does now

- `NnueEvaluator.evaluate()` runs a scalar loop over the 2 x width output products with branching clamps and `long` products. `NnueAccumulatorVectorOps` vectorizes only `addFeature` and `subtractFeature`.
- `onMake()` copies both accumulators for the new ply (two `System.arraycopy` of width shorts) and then applies each feature change in place, which reads and writes the accumulator again for every added or removed piece. `onMakeNull()` copies both accumulators although no piece moved.
- Updates are eager: `onMake` runs for every searched move whether or not the node is later evaluated. #307 already moves `onMake` after the losing-capture and futility pruning decisions.

## Candidates, smallest first

1. Vectorized output dot product. It runs once per evaluated node and is entirely scalar. It is self-contained (one new kernel and one dispatch line), it does not touch search or the accumulator stack, and it must give exactly the same integer, which the existing equivalence and fuzz tests can check. Exactness needs one condition: summing products in int lanes is only exact if `qa * sum(|output weight|)` fits in an int. That is checked once per network, with the existing `long` loop as the fallback. Prepared below.
2. Fuse the first accumulator copy with the first delta in `onMake`. For a quiet move this replaces copy, subtract, add by copy-and-subtract, then add, per perspective, one pass fewer out of three. Medium size: new fused kernels and a "first change" flag through the change callback. Verified by the fuzz test.
3. O(1) null move. Saves a 2 x width copy on every null move. The simple forms alias pool arrays and need care when the search unmakes the null move, and the gain is small, so it comes after 1 and 2 are measured.
4. Lazy materialization of dirty plies. Largest potential, because plies that are never evaluated cost nothing, but it revisits ADR-003 and ADR-004 and changes the lifecycle. It should wait for a count of how many updated plies are never evaluated, which is cheap to instrument and does not need a timing run.
5. The microbenchmark in `NnueCorpusBenchmarkTest` uses a constant feature index and an integer division, so it cannot rank 1 to 3. It should vary the feature indices over realistic positions and report fractional ns/op before any of them is judged.

## Candidate 1, prepared

`NnueAccumulatorVectorOps.dotCrelu` clamps each short vector with `max(0).min(qa)`, widens both operands to two int halves, multiplies and accumulates in int lanes, reduces to `long`, and finishes the remainder scalar, for any width. `NnueEvaluator` takes it only when the vector module is available and `dotFitsInt(outputWeights, qa)` holds; otherwise it uses `dotCreluScalar`, which is the previous loop unchanged and is the reference the tests compare against.

The guard holds with a wide margin on the real checkpoints measured for #265. At today's QA 127 and at QA 1016, the worst-case output sum is about 0.2 to 2 percent of the int range (`2026-11-24-weight-scale-alternatives.md`), so the vector path would be taken for all of them.

Verification reused from #269: the scalar-versus-vector equivalence test (widths 8 to 512, now also `dotCrelu` for QA 127, 255 and 1016 with full-range accumulators and the int16 boundary values), the incremental-versus-rebuild fuzz test at wide widths, the fixture and corpus golden evaluation tests, and the oracle tests. All run with the vector module enabled and none were skipped. A separate test pins the guard, including that a large weight set at a large QA falls back to the `long` path and still gives the exact sum.

## Not done

No timing, no NPS comparison, no node-count run beyond the existing exact-count regression test. Whether the dot product is a large enough share of NNUE time to matter is the first thing to measure once the native hosts are free, with the protocol used for #307 (interleaved variants, node-count equality first). Candidates 2 to 4 are not started.
