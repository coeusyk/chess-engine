# Phase 18 Production Search-Contract Qualification

Status: preregistration. This phase repairs and qualifies four independently
source-confirmed production search contracts before any SMP qualification or
Phase 19 candidate-PVS diagnosis.

## Baseline and scope

- Production baseline: `develop` at
  `ebe513eabd50e853a4e24a0260c64b41a5a4b224`.
- Phase 17 remains closed and rejected. Its PVS branch is archival evidence
  only and is not a production input.
- No chess games, SPRT, tuning, SMP work, evaluator work, or PVS revival are
  part of this phase.
- The existing Phase 19 document remains a deferred diagnostic of the rejected
  candidate. It is not a repair plan for production.

The phase has four isolated repair slices followed by a single-threaded
re-baseline:

1. **P18-1:** null-move/current-hash ownership
2. **P18-2:** move-order/SEE metadata lifetime
3. **P18-3:** singular-search bound semantics
4. **P18-4:** root fail-high termination and window validity
5. **P18-5:** canonical single-thread re-baseline

Each slice gets a separate commit. A later slice must begin from the preceding
slice's verified commit. The comparison for a slice is always against the
immediately preceding repaired commit, not directly against the historical
buggy baseline.

## Required gates for every repair slice

1. Add the focused regression first and demonstrate that it fails on the
   preceding production commit.
2. Implement the smallest semantic repair that satisfies the regression.
3. Run the focused regression and require it to pass.
4. Run the relevant full Maven test suite and require it to pass.
5. Run deterministic search/regression comparisons against the immediately
   preceding repaired commit.
6. Record every node, qnode, TT, PV, score, depth, and move change and explain
   the mechanism that caused it.
7. Commit the slice separately.

Unchanged node count is not a correctness criterion. No Elo or strength gate
belongs before P18-5.

## P18-1: null-move/current-hash ownership

### Contract

After any ordinary legal `makeMove()` followed by its matching
`unmakeMove()`, the incremental Zobrist key must equal the key of the current
board position. This must remain true when the ordinary move occurs after a
search-only null move.

The null position must not be added to repetition history merely to make this
invariant hold. Current-position hash restoration and repetition-history
membership are separate contracts.

### Investigation and repair constraint

Before changing code, inspect `UnmakeInfo` fields and lifecycle, the actual
responsibilities of `zobristStack`, repetition methods, FEN initialization,
normal move history, and `searchMode` behavior.

A strong candidate is for ordinary `makeMove()` to record the exact previous
Zobrist key in its existing `UnmakeInfo`, with `unmakeMove()` restoring that
key directly. This is only a candidate until the ownership inspection confirms
that it is the smallest correct design.

### Required regressions

- ordinary make/unmake;
- null → child make/unmake;
- null → child 1 make/unmake → child 2 make/unmake;
- reachable en-passant target before null;
- final `unmakeNullMove()` restores the exact parent;
- incremental hash equals a recomputed hash throughout;
- repetition semantics remain unchanged, with null positions excluded from
  game-history membership.

## P18-2: move-order/SEE metadata lifetime

Do not implement this slice in P18-1.

The acceptable design space is:

- score or classification storage indexed by search ply and consistent with
  the existing DFS move-list ownership model; or
- independent recomputation of only the SEE classification needed by the
  shallow losing-capture pruning gate.

Reject per-node heap allocation/copy solely to preserve `scoringBuffer`, and
reject reliance on one Searcher-global mutable score vector across recursion.
Choose between the two acceptable designs from code simplicity and measured
cost, not presumed NPS.

Required evidence is a deterministic recursive-overwrite regression covering
both false pruning and missed pruning.

## P18-3: singular-search bound semantics

Do not tune the singular margin, depth threshold, or extension amount.

The repair must correct only the logical contract: an alternative satisfying
`singularBeta` disproves singularity. It does not establish caller score
`>= callerBeta` unless that has independently been searched and proven.

The regression must construct the relation
`singularBeta <= alternativeScore < callerBeta` and verify that the enclosing
node continues normal search instead of returning caller beta solely from the
singular diagnostic.

## P18-4: root fail-high termination and window validity

Do not alter aspiration widths.

Once root `alpha >= beta`, root search must not invoke another child with a
closed or inverted window. Verify three separate behaviors:

- every internal child window is valid;
- aspiration fail-high still retries as designed;
- an aborted retry cannot replace the last completed iteration.

The regression must distinguish invalid internal windows and state pollution
from externally returned move semantics.

## P18-5: canonical single-thread re-baseline

Run the existing deterministic single-thread search/regression protocol only
after P18-1 through P18-4 are independently verified. Record main nodes,
qnodes, NPS, completed/selective depth, TT probes/hits/stores/replacements,
root move and PV changes, aspiration retries, and any changed expected moves.

This is a new clean production reference. It must explain behavioral deltas
from the old baseline rather than reject them because the old baseline was
numerically different.

## Stop rules

- Any focused invariant regression still failing: stop the slice.
- Any relevant full-suite failure: stop the slice.
- Any unexplained legal-PV, score-bound, or repetition change: stop and isolate
  before proceeding.
- A node or NPS change alone is not a rejection criterion.
- Do not begin SMP qualification, strength testing, or Phase 19 diagnosis
  until P18-5 is complete.
