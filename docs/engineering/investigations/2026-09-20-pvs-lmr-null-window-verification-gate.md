# Case Study: A Null-Window Verification Gate That Can Never Pass

**Date:** 2026-09-20
**Systems involved:** `engine-core` Searcher (PVS + LMR), historical commit `f9b152ca4f45e8e8aa5a48092b03416aba79b230`.
**Scope:** search correctness / control-flow methodology only. This candidate was never merged
and remains rejected (Phase 17); nothing here changes that decision. Full context and the
deterministic replay evidence are in `docs/architecture/research/phase19-pvs-diagnostic-report.md`.

---

## 1. Problem statement

Phase 17 built a PVS+LMR candidate that reduced fixed-depth search nodes by about 44% and
elapsed time by about 45%, yet lost its Gate 4 SPRT 0 wins / 13 losses / 1 draw over 14 scored
games. A candidate that visibly does less work per depth losing that decisively is worth
explaining, not just discarding — the two facts point in opposite directions unless something
in the "less work" is actually "skipped work that should have happened."

## 2. What the code looked like

The candidate added a three-stage re-search scheme for a late move that qualifies for late
move reduction (LMR):

1. Probe the move at reduced depth with a null window: `alphaBeta(reducedDepth, -(alpha+1), -alpha)`.
2. If that probe's score beats alpha, verify at full depth — still under a null window — gated
   by `score > alpha && score < beta`.
3. If the verified score is still strictly inside `(alpha, beta)`, re-search at the full window
   for an exact PV value.

Stage 2's gate looks reasonable on its own: "only bother verifying if the probe result could
plausibly become the new best score, i.e. it's strictly between alpha and beta." That's the
standard shape of a PVS re-search condition, and it's exactly what the code already used
correctly in the sibling "ordinary PV-node later-move" branch a few lines below.

## 3. Why it fails anyway

The `beta` in that gate isn't a fixed constant — it's the *current node's own* upper window
bound, inherited from whatever called into this node. And under the PVS/null-window convention,
every non-PV node in the tree is, by definition, searched with `beta = alpha + 1` at that node.
That's not a rare corner case; it's the ordinary condition for the large majority of nodes,
since a PV node is (loosely) one node per ply along the current best line and everything else
is a scout/non-PV node.

Substitute `beta = alpha + 1` into the gate: `score > alpha && score < alpha + 1`. For an
integer `score`, `score > alpha` already means `score >= alpha + 1`, which directly contradicts
`score < alpha + 1`. The AND can never be true. At every non-PV node, Stage 2 is not merely
unlikely to fire — it is arithmetically unreachable, regardless of what `score` actually is.

The sibling branch that uses the identical-looking condition doesn't have this problem, because
it's gated to run only when `isPvNode` is true, and a genuine PV node by definition carries a
wide window (`beta - alpha > 1`). The bug is specific to the LMR branch, which fires at PV and
non-PV nodes alike, and reuses a bound (the enclosing `beta`) that only means what the author
intended at one of those two node types.

When the gate fails, there's no `else` — the code falls through with `score` still holding the
*reduced-depth* probe value from Stage 1, and that value is then treated as if it had been
verified at full depth.

## 4. Confirming it wasn't just a theoretical concern

Reading the control flow is enough to prove the gate can never pass at a non-PV node, but not
enough to know whether it *matters* — a code path that's technically unreachable but never
actually attempted would be a curiosity, not an explanation. Two checks answered that:

- **Activation.** A purely additive counter (incremented whenever a probe fails high at a node
  where `beta - alpha == 1`) was added to an isolated worktree build of the frozen candidate
  commit and confirmed not to change any search outcome (identical bestmove/nodes/PV against the
  unmodified authoritative Gate 4 JAR). At three historical divergence positions from Phase 17's
  actual games, the gate was blocked 793, 1,157, and 1,370 times respectively at fixed depth 13
  — 8x to 23x more often than the small number of times it actually passed (69, 141, 60).
- **Effect.** A one-line counterfactual fix (`score > alpha` alone, matching the pre-experiment
  baseline condition) was applied in a separate isolated worktree. At all three positions, the
  fix changed the bestmove, score, node count, and PV, and increased verification work by 10x to
  35x. The direction of the node-count change wasn't uniform — one position moved much closer to
  baseline's node count, another moved further below it — which fits a real change in how
  information flows through move ordering, not a simple "more search now" effect.

Neither check alone would have been convincing. A control-flow argument without a live
measurement risks being an armchair claim about code that's dead in practice; a raw counter
without the counterfactual risks measuring something that never mattered to any actual decision.
Together they show the hole is both reachable in the real game positions and consequential when
fixed.

## 5. The generalizable lesson

A PVS-with-LMR re-search gate that checks `score > alpha && score < beta` needs to be checked
against exactly what `beta` means at the call site. If the gate is meant to ask "did the
reduced/null-window probe fail high relative to the window it was probed with," the answer
doesn't depend on the enclosing node's own beta at all — the probe's own window already encodes
that. Reusing the enclosing node's `beta` only works when the code path is provably confined to
nodes with a wide window (as the ordinary-PV-sibling branch was). Any re-search gate that fires
at both PV and non-PV nodes and borrows the enclosing `beta` for anything other than the final
full-window search itself should be checked against the degenerate case `beta = alpha + 1`
before it ships, because that case is the common case, not the edge case.

## 6. Disposition

This candidate remains rejected. The fix described here was never applied to any branch that
merges into production, and no games, SPRT, or strength claim were run against it — the
counterfactual answered one narrow question (does the gate's arithmetic degeneracy have a real
effect on search decisions) and nothing more. See the Phase 19 report for the full evidence
chain, the established/supported/unproven breakdown, and what would be required before any
corrected PVS/LMR implementation could be considered again.
