# Phase 18 Production Search-Contract Qualification

Status: completed qualification. P18-1 through P18-4 are merged into the
canonical production line, and P18-5 records the repaired single-thread
reference before any SMP qualification or Phase 19 candidate-PVS diagnosis.

## Baseline and scope

- Production baseline at closure: `develop` at
  `6afc523a2221ac3cd4166ea1cbe701aaeba0552d` (merged PR #241).
- Historical pre-Phase-18 production baseline: `ebe513eabd50e853a4e24a0260c64b41a5a4b224`.
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

### Frozen production reference

- Commit: `6afc523a2221ac3cd4166ea1cbe701aaeba0552d`
- Branch: `develop`
- Environment: WSL2 Linux (`Linux 6.18.33.2-microsoft-standard-WSL2`), Ubuntu
  24.04.4, AMD Ryzen 7 7700X (8 cores / 16 logical CPUs), x86_64.
- Java: Ubuntu OpenJDK 21.0.12, 64-bit Server VM.
- Search options: Threads=1, Hash=16 MB, Classical evaluator, default Searcher
  options, `--add-modules jdk.incubator.vector`.
- Corpus: `BenchRunner.BENCH_FENS`, 31 Stockfish public-domain positions,
  depth 13. BenchRunner source SHA-256:
  `882f5edc5159dba94bb6faefbb456f1a12e77f2798753a6a82d58755c9b1361f`.
- Jar SHA-256:
  `55341e8d0b4d79c4d27a995d98447bc25ecdaaa2185c9dc8deabd9ff389f6a45`.

The historical Phase 16 timing reference was native Windows with Zulu
OpenJDK 21.0.10. This closure run was performed in WSL2 because no native
Windows checkout/execution was available. Its node counts are the canonical
semantic reference; its elapsed/NPS values are descriptive WSL2 measurements
and must not be treated as directly comparable to the native-Windows timing
reference.

### Five-position deterministic reference

Depth 8, Hash=16 MB, Threads=1, Classical. Two independent executions were
identical.

| Position | Best move | Score | Completed depth | Main nodes | Qnodes | TT hits | PV |
|---|---|---:|---:|---:|---:|---:|---|
| Start | e2e4 | 25 | 8 | 14926 | 39927 | 4908 | e2e4 e7e5 b1c3 b8c6 g1f3 |
| K+P vs K | e1d2 | 122 | 8 | 1226 | 1816 | 1024 | e1d2 e8d7 e2e4 d7d6 d2d3 d6e5 d3e3 e5e6 |
| Tactical middlegame | b4b2 | −63 | 8 | 34694 | 88266 | 14691 | b4b2 e3d2 b2b6 d3d4 e6c4 f1b1 b6a5 f3e5 c4b5 |
| Rook/pawn | b4f4 | 14 | 8 | 6456 | 14776 | 1938 | b4f4 h4g3 f4c4 h5c5 a5b4 c5c4 b4c4 g3g2 c4d3 g2f2 |
| Queen/king | d7d2 | 1565 | 8 | 8902 | 16736 | 3982 | d7d2 c4d5 a8a3 a1b1 c5c4 f1d1 d2c3 f2f4 |

### Canonical 31-position benchmark

The existing Phase 16 protocol was used: one discarded warm-up followed by
seven uninstrumented `--bench-raw` runs at depth 13. Every run completed all
31 positions and searched exactly 24,780,049 main nodes.

| Run | Main nodes | Elapsed (ms) | NPS |
|---:|---:|---:|---:|
| 1 | 24,780,049 | 72,164 | 343,385 |
| 2 | 24,780,049 | 72,312 | 342,682 |
| 3 | 24,780,049 | 73,881 | 335,404 |
| 4 | 24,780,049 | 73,471 | 337,276 |
| 5 | 24,780,049 | 74,619 | 332,087 |
| 6 | 24,780,049 | 75,972 | 326,173 |
| 7 | 24,780,049 | 72,734 | 340,694 |

Aggregate nodes across measured runs: 173,460,343. Median elapsed was
73,471 ms; mean 73,593 ms; population standard deviation 1,267 ms; CV 1.72%.
Median NPS was 337,276; mean 336,814; population standard deviation 5,738;
CV 1.70%. The discarded warm-up also searched 24,780,049 nodes.

The historical Phase 16 native-Windows reference was 73,089,246 nodes,
median 334,861 NPS, and median 218,267 ms. The node-count difference is a
search-contract effect, not a target to equal numerically; timing/NPS is not a
valid direct comparison because the current run is WSL2/Ubuntu Java rather
than native Windows/Zulu Java.

### JFR attribution

The existing attribution procedure was repeated once on the final WSL2
baseline with instrumentation enabled. It reported 24,780,049 nodes, 75,829
ms, 326,788 NPS, and 19.6% evaluator timing. The JFR `hot-methods` view's
largest samples were: `Board.makeMove` 23.11%, `MoveOrderer.orderMoves` 11.79%,
`Board.unmakeMove` 9.95%, `ClassicalEvaluator.evaluate` 6.33%, legal-move
filtering 5.19%, and evaluator mobility/attack work 5.12%. These are
descriptive only; no optimization was attempted. They are not numerically
equivalent to Phase 16's category aggregation (make/unmake 37.6%, evaluation
18.5%, move ordering 14.5%, move generation 14.2%).

### Phase-18 effect summary

- **P18-1:** corrected current-position hash ownership across null subtrees;
  changed five-position search shape and several moves/PVs through corrected
  TT identities.
- **P18-2:** isolated recursive ordering metadata per search ply; changed the
  five-position tree shape but not moves, scores, or PVs.
- **P18-3:** separated singularity diagnosis from caller beta; five-position
  reference remained unchanged.
- **P18-4:** stopped root siblings after valid fail-high. Four positions were
  unchanged; K+P changed from 1192 to 1226 nodes with the same score but a
  different equivalent move/PV. The E1 KQK regression changed from f1c4/1308
  to f1f6/1303. Both deltas were traced to removed invalid-window sibling work
  and its TT/PV side effects; fixtures were updated only after that analysis.

No full 31-position per-slice attribution was collected, so none is inferred.

### Closure

P18-1 through P18-4 are merged and all focused/full suites are green. The
five-position reference is repeatable, the 31-position node baseline is
complete, every known deterministic move/score/PV delta is documented, and no
correctness delta remains unresolved. Phase 18 did not run games, SPRT,
tuning, or strength testing. Phase 19 remains a separate diagnostic of the
rejected PVS candidate; its preregistration assumptions are not amended here.

## Stop rules

- Any focused invariant regression still failing: stop the slice.
- Any relevant full-suite failure: stop the slice.
- Any unexplained legal-PV, score-bound, or repetition change: stop and isolate
  before proceeding.
- A node or NPS change alone is not a rejection criterion.
- Do not begin SMP qualification, strength testing, or Phase 19 diagnosis
  until P18-5 is complete.
