# ADR-005: Pure NNUE runtime vs. hybrid classical+NNUE blending
Date: 2026-07-07  Status: accepted

## Context

Phase A adds the UCI-visible `EvalType` option (`Classical` | `NNUE`) that
will, once Phase B lands a real NNUE evaluator, select which
`EvaluatorStrategy` `Searcher` uses. The PRD's decision record (Appendix A,
item 7) already settled this as "one binary, UCI-switched, pure NNUE,
classical evaluator permanent" following the project's grilling session; this
ADR records that decision formally since Phase A is where it becomes visible
in the option surface (`EvalType` has exactly two values — there is no third
"Hybrid" or blend-weight option).

## Problem

When NNUE becomes available, should the engine ever combine classical and
NNUE evaluation for a single position (a weighted blend, or NNUE-with-
classical-safety-terms), or should each `EvalType` value select one
evaluator used exclusively, with no mixing?

## Alternatives

### 1. Pure NNUE runtime, no blending (chosen)
`EvalType Classical` uses only `ClassicalEvaluator`; `EvalType NNUE` (once
Phase B lands) uses only the NNUE evaluator — never both for the same
`evaluate(Board)` call. Benefits: any strength or behavior difference
observed in an SPRT run is attributable to exactly one evaluator (PRD US-2:
"A/B comparisons are attributable to exactly one system"); no blend-weight
hyperparameter to tune, calibrate, or accidentally miscalibrate against the
search margins tuned for classical's centipawn scale. Costs: forgoes any
strength that a hybrid might have captured (e.g. classical mop-up/endgame
terms NNUE hasn't learned well from a small first dataset).

### 2. Hybrid blend (classical + NNUE weighted average, or NNUE + classical fallback terms)
Benefits: could paper over early NNUE weaknesses (e.g. sparse endgame
training data) with classical's hand-tuned endgame knowledge. Costs: breaks
attributability — an SPRT result would test "this blend ratio," not "NNUE
vs. classical," undermining the Strength Measurement Policy (PRD §1); adds a
blend-weight parameter that itself needs tuning/SPRT infrastructure just to
validate the mixing function, before NNUE's own strength can even be
assessed; doubles the per-node evaluation cost (both evaluators run) with no
UCI-surfaced way to disable it selectively.

## Tradeoffs

Pure NNUE accepts a possibly-lower early ceiling (a young NNUE net cannot
lean on classical's tuned endgame terms) in exchange for clean, attributable
A/B testing and no extra tunable surface. A hybrid buys a potentially
stronger early net at the cost of an untestable-in-isolation strength claim
and real implementation cost (two evaluators running per node) for a
temporary bootstrapping problem that better training data (PRD's staged
Stage 1→2→3 data plan) addresses directly instead.

## Decision

Option 1: pure NNUE runtime. `EvalType` selects one evaluator exclusively;
no blending is implemented or planned. Deciding factor: attributability of
every strength claim to exactly one evaluator is a project-wide policy (PRD
§1 Strength Measurement Policy), and blending would violate it for every
future NNUE SPRT run, not just the first.

## Consequences

Easier: every SPRT/gauntlet result trivially answers "which evaluator did
this" — no confound. `EvaluatorStrategy`'s contract stays simple (one
`evaluate(Board)` call, one active implementation at a time). Harder: if an
early NNUE net underperforms classical in specific position classes (e.g.
sparse endgames), the fix must be better training data or a
larger/different net — there is no blend-weight escape hatch to patch over a
weak phase.

## Revisit Conditions

- NNUE passes all three PRD §1 release gates and becomes default, and a
  *specific, measured* strength gap in one position class (e.g. via the
  benchmark-corpus per-category tracking in PRD §4) persists across
  multiple retrained nets despite data/architecture improvements — at that
  point a hybrid could be evaluated as its own SPRT-tested proposal, with
  its own ADR.
- Never revisit purely to "hedge" against an early, still-improving net —
  the staged data plan (PRD §2 US-5) already expects early nets to be weak
  and iterates via retraining, not blending.

## Supporting Evidence

PRD Appendix A item 7 (grilling-session decision) and §1 Strength
Measurement Policy. Phase A's `UciApplication` `EvalType` option declares
exactly two combo values (`Classical`, `NNUE`) — verified in
`UciApplicationIntegrationTest.uciListsEvalTypeAndEvalFileOptions` — with no
blend-weight option, consistent with this decision from the first UCI
surface exposing it.

## Open Questions

None blocking Phase A or B. Should be revisited only under the conditions
above, after NNUE is already the measured default.
