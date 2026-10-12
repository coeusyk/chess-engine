# ADR-009: NNUE evaluator ownership — one instance per Searcher, never shared

Date: 2026-07-09  Status: accepted

## Context

Phase B (`core.eval.nnue`) introduced `NnueNetwork` (immutable weights) and
`NnueEvaluator` (mutable per-search accumulator state, implementing
`EvaluatorStrategy`). Lazy SMP runs one `Searcher` per thread, each with its
own `Board`, sharing only the `TranspositionTable`
(`UciApplication`'s helper-spawn loop). This ownership split was treated as a
non-negotiable constraint throughout Phase B's implementation and review, but
it was only ever recorded in `NnueEvaluator`'s class Javadoc and in
session-local planning notes — not in a permanent, repo-tracked decision
record. This ADR closes that gap before Phase B is frozen.

## Problem

What object graph keeps NNUE evaluation correct under Lazy SMP, where
multiple `Searcher` threads run concurrently against the same loaded network?

## Decision

Three-tier ownership, enforced by construction wherever an `EvaluatorStrategy`
is assigned (`UciApplication.resolveNnueNetworkForSearch` plus the main
`Searcher` and each Lazy SMP helper's `setEvaluatorStrategy` call):

1. **One immutable `NnueNetwork` may be shared.** Weights are loaded once per
   `EvalFile` and never mutated after `load()` returns — safe to hold by
   reference from every thread.
2. **One mutable `NnueEvaluator` per `Searcher`.** Constructed fresh for the
   main search thread and, separately, fresh inside each Lazy SMP helper's own
   spawn-loop lambda — never constructed once and handed to more than one
   thread.
3. **One accumulator stack per `NnueEvaluator`.** The dual (white/black)
   accumulator pool lives inside the evaluator instance, sized off
   `Board.UNMAKE_POOL_SIZE`, and is pushed/popped in lockstep with that
   evaluator's own `Board`'s make/unmake calls.

**Evaluator instances must never be shared across Lazy SMP helper threads.**
Two threads mutating the same accumulator stack concurrently would corrupt
both searches' evaluations silently — no exception, no crash, just wrong
scores that are extremely hard to trace back to this cause.

## Alternatives considered

### 1. Per-thread evaluator, shared network (chosen)
As above. Matches how Lazy SMP already isolates `Searcher`/`Board` per thread;
no new concurrency primitive needed since nothing is actually shared except
already-immutable weight arrays.

### 2. Single shared `NnueEvaluator` with per-thread accumulator maps
Keep one evaluator instance, key its accumulator state by thread ID internally.
Rejected: adds a lookup (and a synchronization concern) to the hottest path in
the engine for no benefit over just constructing separate instances, which
Lazy SMP's existing per-thread `Searcher` construction already makes free.

### 3. Thread-local accumulator storage
Rejected for the same reason as #2 — solves a problem that doesn't exist once
construction is per-thread; `ThreadLocal` lookups are pure overhead here.

## Consequences

Easier: no locking, no shared mutable state to reason about beyond the
already-immutable `NnueNetwork`; the invariant is enforced structurally by
"always construct a new `NnueEvaluator`" rather than by runtime checks.
Harder: nothing prevents a future edit from accidentally hoisting an
`NnueEvaluator` out of a per-thread scope and reusing it — there is no
compiler or runtime guard against this beyond code review and the existing
`UciApplicationIntegrationTest` Threads=2 + NNUE test, which exercises the
per-helper construction path but does not assert non-sharing directly.

## Revisit Conditions

- If a future evaluator strategy needs state that's expensive to construct
  per-thread (unlike the current preallocated-array accumulator), revisit
  whether a pooled/reused-instance scheme is worth the added complexity.
- If Lazy SMP's own threading model changes (e.g. shared `Board` instances),
  this ADR's ownership split must be re-derived from the new model, not
  assumed to still hold.

## Supporting Evidence

Verified directly against `UciApplication.java`'s helper-spawn loop and
`resolveNnueNetworkForSearch()`: the network is resolved once per `go` and a
new `NnueEvaluator(network)` is constructed separately for the main
`Searcher` and inside each helper's own lambda. `NnueEvaluator`'s accumulator
pool size is tied to `Board.UNMAKE_POOL_SIZE` (public, documented as
load-bearing) rather than an independent literal, keeping the per-evaluator
accumulator stack in lockstep with its own `Board`'s unmake stack.

## Open Questions

None blocking Phase B. Phase C (real trainer-produced networks) should
re-confirm this ADR still holds if evaluator construction cost changes
enough to motivate pooling/reuse across searches.
