# ADR-003: Copy-per-ply accumulator stack, not in-place reverse deltas

Date: 2026-07-12  Status: accepted

## Context

`NnueEvaluator` must maintain a dual-perspective accumulator (`whiteAcc`,
`blackAcc`) that stays correct across `Searcher`'s full make/unmake tree —
root search, quiescence, null-move, singular-extension re-search — without
allocating on any hot-path call (CLAUDE.md §3: "No object allocation in hot
paths"). The PRD reserved this as its own decision
("ADR-003: Copy-per-ply accumulator stack vs. in-place reverse deltas —
Phase B", Appendix B) because it is the single highest-traffic code path in
the whole NNUE integration — every `makeMove`/`unmakeMove` pair in the search
tree calls into it. The decision was made and implemented in Phase B's PR2;
this ADR documents it retroactively against the shipped code.

## Problem

When `Searcher` makes a move, `NnueEvaluator.onMake` must produce a new,
correct accumulator reflecting that move — and when the move is later
unmade, the previous accumulator must be exactly restored, with zero
allocation and minimal per-call cost, across search-tree depths bounded only
by `Board.UNMAKE_POOL_SIZE`.

## Alternatives considered

### 1. Copy-per-ply accumulator stack (chosen)
A preallocated `short[Board.UNMAKE_POOL_SIZE][hiddenWidth]` pool per
perspective, indexed by a stack pointer `sp` mirroring `Board`'s own
`unmakePool`/`unmakeSP` idiom. `onMake` copies the current frame to
`sp + 1` via `System.arraycopy`, increments `sp`, then applies feature
deltas in place at the new top. `onUnmake` is a single `sp--`; the
superseded frame's bytes are simply abandoned in the array until
overwritten by a future `onMake` at the same depth.

### 2. In-place reverse deltas (rejected)
A single accumulator per perspective (no stack). `onMake` applies feature
deltas forward in place; `onUnmake` re-derives and applies the same deltas
in reverse (remove what was added, add back what was removed) to restore
the prior state. Rejected: `onUnmake()` (`EvaluatorStrategy` line 44) takes
**no arguments** — no `move`, no `capturedPiece` — by design (Phase A's
audited hook-timing contract; see ADR-004). Reverse-delta computation needs
the same move/capture information `onMake` had, so this approach would
either require threading that information back into `onUnmake` (reopening
Phase A's frozen hook signature) or having `NnueEvaluator` retain its own
parallel undo-info stack duplicating what `Board.unmakePool` already stores
— redundant state with its own desync risk, for a design whose only
advantage (avoiding the `arraycopy`) is a performance question, evaluated
below.

### 3. Hybrid: shallow reverse-delta window, full-copy stack beyond a fixed depth
Use in-place reverse deltas for the first N plies (fast, no allocation
concerns at shallow depth) and fall back to a copy-per-ply stack beyond
that, on the theory that most nodes are shallow and most cost lives there.
Rejected as premature complexity: it inherits Option 2's core problem
(`onUnmake()` still has no move/capture info to reverse with) *and* adds a
second code path with its own boundary-condition bugs (the depth at which
strategies switch), for a performance benefit that was never measured
against Option 1 — see Tradeoffs.

## Tradeoffs

**Performance:** Option 1's `System.arraycopy` cost is linear in
`hiddenWidth` per `onMake` call, paid on every move made in the search
tree — measurable, but `arraycopy` is a JIT-intrinsic bulk-copy operation,
not a Java loop, and the incremental feature-delta application already
dominates with far fewer operations than a full accumulator rebuild would
cost. Option 2 avoids this copy entirely but cannot be implemented without
violating Phase A's frozen `onUnmake()` signature (see above) — so it is not
actually a smaller-cost alternative available under this project's
constraints, it is an alternative that requires reopening an already-frozen,
independently-justified decision (ADR-004) to even attempt.

**Correctness:** Option 1's failure mode is bounded and cheap to verify: if
`sp` and `Board.unmakeSP` (which drives `Board.UNMAKE_POOL_SIZE`, the shared
pool-size constant per ADR-009/`Board.java`'s public constant) ever diverge,
the incremental-vs-rebuild fuzz test (`NnueIncrementalVsRebuildFuzzTest`)
catches it immediately, because every frame is independently addressable —
there is no accumulated reverse-delta rounding to diagnose. Option 2's
failure mode is more insidious: a single subtly wrong reverse-delta (e.g. a
mis-signed feature index) doesn't fail the current node, it silently
corrupts every subsequent sibling at the same depth, and the bug only
surfaces however many plies later a discrepancy becomes visible — exactly
the "dominant NNUE bug class" (silent accumulator desync) the PRD's own
Developer Tooling section names as the primary thing to guard against.

**Debugging implications:** Option 1's `currentAccumulator(int)` test hook
can read any live frame's exact byte state at any `sp` without needing to
replay history — the state at depth `d` is simply `whiteAcc[d]`, independent
of how it got there. Option 2 has no equivalent: inspecting the accumulator
at a given historical depth would require either retaining history
explicitly (defeating the whole point of not copying) or replaying moves
from the root, which is exactly the "rebuild comparator" tool's job and
would make every debug inspection as expensive as a full rebuild.

## Decision

Option 1: copy-per-ply accumulator stack, mirroring `Board`'s own
`unmakePool`/`unmakeSP` idiom exactly (`NnueEvaluator.java:27`,
`ACCUMULATOR_POOL_SIZE = Board.UNMAKE_POOL_SIZE`, confirmed to reference the
shared constant, not an independent literal, per the Phase B freeze patch).
Deciding factor: it is the only option compatible with Phase A's already-
frozen `onUnmake()` signature without reopening that decision, and it gives
the cheapest, most direct debugging story for the PRD's named dominant bug
class (silent accumulator desync).

## Consequences

Easier: every accumulator frame is independently inspectable and immutable
once written, so the rebuild-comparator and accumulator-inspector Phase C
tools need no special-case logic — they just read `whiteAcc[sp]`/`blackAcc[sp]`
directly. `onUnmake` is O(1), a pure pointer decrement. Harder: memory
footprint scales with `Board.UNMAKE_POOL_SIZE * hiddenWidth * 2 perspectives
* 2 bytes`, preallocated per `NnueEvaluator` instance (i.e. per `Searcher`,
per ADR-009's ownership model) — a real, bounded, one-time cost accepted
knowingly, not a leak, but one that scales linearly if `hiddenWidth` grows
substantially in a future network (tracked under ADR-002's revisit
conditions, not this one, since it's a network-size question, not an
accumulator-strategy question).

## Revisit Conditions

- If `Board`'s own unmake-pool depth ever needs to grow substantially beyond
  its current fixed size for unrelated search reasons, re-measure the
  resulting `NnueEvaluator` memory footprint (`hiddenWidth` × pool size ×
  4 arrays) before assuming it's still negligible.
- If profiling under a real (non-synthetic) trained network ever shows the
  `arraycopy` cost is a measurable fraction of total NNUE evaluation time,
  revisit whether a bounded reverse-delta window (Option 3) is worth
  reopening `onUnmake()`'s signature for — but only with actual profiling
  data, not the a priori reasoning in this ADR.
- If the feature set ever changes to a king-relative scheme (ADR-001's own
  revisit conditions), king moves would need a full-refresh path rather than
  a pure incremental delta — this ADR's copy-per-ply shape still works for
  that (a refresh just writes a freshly-rebuilt frame instead of an
  incrementally-updated one at the same `sp`), but the interaction should be
  re-verified explicitly when that migration happens, not assumed.

## Supporting Evidence

Verified directly against source: `NnueEvaluator.java` lines 59-84
(`onMake`/`onUnmake`/`onMakeNull`/`onUnmakeNull`), confirming the
copy-then-delta shape on make and pure-decrement shape on unmake;
`ACCUMULATOR_POOL_SIZE = Board.UNMAKE_POOL_SIZE` (line 27) confirming the
pool is sized off the same shared constant `Board`'s own unmake stack uses,
not an independent literal — this was itself a finding fixed in the Phase B
freeze patch (previously a duplicated `768` literal). The
incremental-vs-rebuild fuzz test and the six hand-picked
`FeatureExtractorMoveTypeTest` fixtures (added in the freeze patch) are the
concrete correctness evidence for this design, not just the reasoning above.

## Open Questions

None blocking Phase C. Revisit only under the profiling-driven condition
above, not preemptively.
