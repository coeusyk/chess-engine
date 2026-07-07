# ADR-004: Evaluator lifecycle hooks on EvaluatorStrategy, not Board change-listeners
Date: 2026-07-07  Status: accepted

## Context

Phase A introduces `EvaluatorStrategy` to decouple `Searcher` from a specific
evaluator implementation, ahead of NNUE (Phase B) needing to maintain an
incremental accumulator across the search tree. `Board` is explicitly
untouched in Phase A (PRD §4): "Existing undo info must be confirmed
sufficient... if a field is missing, extend undo info, not Board's API
surface." `Board.makeMove`/`unmakeMove` sit in the hottest path in the engine,
called on the order of tens of millions of times per bench run.

## Problem

How does an evaluator implementation learn that a move was made or unmade at
a given search node, so it can maintain incremental state (e.g. an NNUE
accumulator), without that concern leaking into `Board`?

## Alternatives

### 1. Lifecycle hooks on `EvaluatorStrategy` (chosen)
Default no-op methods — `reset(Board)`, `onMake(Board, int move)`,
`onUnmake()`, `onMakeNull()`, `onUnmakeNull()` — called explicitly by
`Searcher` immediately alongside every `board.makeMove`/`unmakeMove`/
`makeNullMove`/`unmakeNullMove` call. Costs: `Searcher` must call the hook at
every one of its make/unmake sites, and a missed site is a silent bug.
Benefits: `Board` stays untouched; the call sites are exactly where the
search already knows "a move just happened," so no new plumbing is needed;
`ClassicalEvaluator` (no incremental state) pays only a monomorphic,
JIT-elidable no-op call.

### 2. Board-level change-listener registration
`Board.addListener(...)`, fired internally by `Board`'s own make/unmake
methods. Benefits: `Searcher` wouldn't need to call anything explicitly — a
whole class of "missed call site" bugs disappears by construction. Costs:
directly contradicts PRD §4's Phase A boundary (Board stays untouched);
`Board` would need to iterate a listener list on every call in its hottest
path regardless of whether anything is listening; couples the core
position-representation class to the evaluation-strategy abstraction, which
is a search-layer concern, not something `Board` should know exists.

### 3. Thread the strategy explicitly through the search call stack
Pass `EvaluatorStrategy` as a parameter through `alphaBeta`/`quiescence`
instead of holding it as a `Searcher` field. Benefits: none over the current
field — `Searcher` already holds `evaluator` as an instance field. Costs:
pure signature churn across every recursive search method for no gain.

## Tradeoffs

Option 1 accepts a manual, audited call-site list in exchange for keeping
`Board` untouched and avoiding hot-path overhead for listeners that (in
Phase A) do nothing. Option 2 buys safety-by-construction against missed
sites at the cost of violating the Phase A boundary and adding indirection to
`Board`'s hottest method. Option 3 buys nothing over the status quo.

## Decision

Option 1: lifecycle hooks on `EvaluatorStrategy`, called explicitly by
`Searcher`. Deciding factor: `Board` must stay untouched in Phase A, and the
call sites already exist at exactly the right granularity in `Searcher`.

A related, separate decision from an architecture review: pawn-hash
config/stats (`setPawnHashSizeMb`/`enablePawnHashStats`/`getPawnHashHitRate`)
were deliberately kept **off** `EvaluatorStrategy` and left on
`ClassicalEvaluator` only, accessed via `instanceof` in `Searcher` — so
Phase B doesn't reuse the "add a no-op default to the shared interface"
pattern for NNUE-only configuration.

## Consequences

Easier: `ClassicalEvaluator` needed zero changes to conform (all hooks
inherit as no-ops); Phase B can add real accumulator logic without touching
`Searcher`'s control flow, only the hook bodies. Harder: every make/unmake
site in `Searcher` is now a place a future edit (e.g. a new pruning branch
with its own make/unmake) must remember to call the matching hook — there is
no compiler enforcement of this, only the audited list and, in Phase B, the
incremental==rebuild fuzz test as a safety net.

## Revisit Conditions

- Phase B finds the hook signatures insufficient (e.g. needs the captured
  piece or other undo-info fields beyond the packed move int for feature
  deltas) — PRD §4 explicitly anticipates extending undo info in that case.
  This would add parameters to the existing hooks, not change the
  hooks-on-interface architecture itself.
- A future search feature adds a new make/unmake site outside the 8
  call-site groups audited in Phase A and the hook call is forgotten — caught
  by Phase B's incremental==rebuild fuzz test, not by this ADR.

## Supporting Evidence

All 8 call-site groups (root loop; main alpha-beta search — 1 make, 3 unmake
exits; singularity verification search; 3 quiescence stages; null-move) were
enumerated by direct `grep` against `Searcher.java` and verified paired
1:1 (or 1:many for the main-search early-exit branches) before implementation,
not assumed from documentation. Phase A's full reactor test suite (309 tests,
0 failures) and a dedicated node-count regression test pass with the hooks
wired in as no-ops, confirming no behavior change from adding them.

## Open Questions

None blocking Phase A. Phase B should re-examine whether `onMake`/`onUnmake`
need the captured-piece/promotion-piece info directly rather than deriving it
from `Board` state at hook-call time.
