# Phase 9A Profiler Baseline

**Date:** 2026-04-03  
**Branch:** `phase/9a-performance` (rebased on `phase/8-texel-tuning` tip `f804f67`)  
**Engine version:** `0.4.10-SNAPSHOT` (post-revert — `HANGING_PENALTY = 50` constant)  
**Profiler:** Java Flight Recorder (JFR) — built-in JDK 21  
**Workload:** `java -jar engine-uci-0.4.10-SNAPSHOT-shaded.jar --bench 10`  
(6 standard positions, depth 10, 16 MB hash, TT cleared between positions)  
**JFR settings:** `profile` (default sampling interval ≈ 10 ms, `jdk.ExecutionSample` events)  
**Samples collected:** 483 (STATE_RUNNABLE, main thread)

---

## NPS Snapshot (bench depth 10, cold JVM)

| Position | nodes | qnodes | ms | NPS | q_ratio | tt_hit% |
|---|---|---|---|---|---|---|
| startpos | 59,905 | 143,181 | 365 | 164,123 | 2.4× | 34.4% |
| kiwipete | 734,525 | 2,640,100 | 3,734 | 196,712 | 3.6× | 13.3% |
| cpw-pos3 | 28,348 | 58,088 | 67 | 423,104 | 2.0× | 42.6% |
| cpw-pos4 | 136,172 | 521,888 | 689 | 197,637 | 3.8× | 29.9% |
| cp-pos5 | 64,518 | 118,499 | 209 | 308,698 | 1.8× | 32.7% |
| cp-pos6 | 211,515 | 629,466 | 975 | 216,938 | 3.0× | 24.1% |
| **Total** | **1,234,983** | **4,111,222** | **6,039** | **203,189** | **3.3×** | — |

*Note: NpsBenchmarkTest same-session baseline (warmed JVM, 10 measurement rounds) = **381,194 aggregate**.*  
*Cold bench NPS is lower due to JIT warm-up. Phase 9A NPS gate targets same-session NpsBenchmarkTest.*

---

## Top 25 Methods by Exclusive CPU Time (JFR ExecutionSample leaf frames)

| Rank | Method | Samples | % of total |
|---|---|---|---|
| 1 | `Board.makeMove(int)` | 109 | 22.6% |
| 2 | `Evaluator.hangingPenalty(Board)` | 49 | 10.1% |
| 3 | `Board.unmakeMove()` | 47 | 9.7% |
| 4 | `MoveOrderer.orderMoves(Board, int[], int, int, int, int[][], int[][])` | 43 | 8.9% |
| 5 | `Evaluator.pieceMobilityPacked(long, int, long, long)` | 34 | 7.0% |
| 6 | `Searcher.alphaBeta(...)` | 19 | 3.9% |
| 7 | `KingSafety.countAttackers(long, int, long, long)` | 18 | 3.7% |
| 8 | `Searcher.applyTtBound(TranspositionTable$Entry, int, int, int, int)` | 18 | 3.7% |
| 9 | `Evaluator.backwardPawnCount(long, long, boolean)` | 16 | 3.3% |
| 10 | `MovesGenerator.filterLegal(Board, int[], int, int)` | 15 | 3.1% |
| 11 | `MovesGenerator.genPawn(Board, int[], int, int, int)` | 14 | 2.9% |
| 12 | `MagicBitboards.getRookAttacks(int, long)` | 13 | 2.7% |
| 13 | `MovesGenerator.genSliding(Board, int[], int, int, int)` | 10 | 2.1% |
| 14 | `Evaluator.rookBehindPasserScores(long, long, long, long)` | 8 | 1.7% |
| 15 | `MovesGenerator.genKing(Board, int[], int, int, int)` | 6 | 1.2% |
| 16 | `Searcher.quiescence(Board, int, int, int, int, BooleanSupplier)` | 6 | 1.2% |
| 17 | `Board.getPiece(int)` | 5 | 1.0% |
| 18 | `StaticExchangeEvaluator.evaluate(Board, int)` | 5 | 1.0% |
| 19 | `MoveOrderer.scoreMove(Board, int, int, int, int[][], int[][])` | 5 | 1.0% |
| 20 | `StaticExchangeEvaluator.bestReplyGain(Board, int, int)` | 4 | 0.8% |
| 21 | `KingSafety.evaluate(Board)` | 4 | 0.8% |
| 22 | `Evaluator.evaluate(Board)` | 4 | 0.8% |
| 23 | `MoveOrderer.mvvLvaScore(Board, int)` | 3 | 0.6% |
| 24 | `Searcher.evaluate(Board)` | 3 | 0.6% |
| 25 | `MovesGenerator.generateCaptures(Board, int[])` | 3 | 0.6% |

---

## Top 3 Hotspots and Phase 9A Action Map

### #1 — `Board.makeMove` + `Board.unmakeMove` — **32.3% combined**

The make/unmake pair dominates. Every alpha-beta node and every Q-node requires one
make + one unmake. Reducing the NODE COUNT (better pruning, endgame TT, null-move
improvements) is the primary lever. The make/unmake itself is already tight bitboard code.

**Phase 9A linkage:**
- Issue #101 (incremental attacked-squares bitboard): adds per-side attacked-squares update
  inside `makeMove`/`unmakeMove`. These updates are O(N_attackers) — small constant per
  move. The benefit is eliminating `isSquareAttackedBy()` from eval hot paths, trading
  cheap ~3 ns incremental updates for expensive ~50 ns full recomputation in `hangingPenalty`.
- Issue #103 (Lazy SMP): more threads → more nodes searched in parallel → wall-clock time
  down even if per-node cost stays flat. Does not change single-thread node rate.

### #2 — `Evaluator.hangingPenalty` — **10.1%**

The `isSquareAttackedBy()` calls inside `hangingPenalty` are JIT-inlined into this method
frame. The method iterates all non-king occupancy bits (~14 pieces avg.) and calls
`isSquareAttackedBy()` twice per piece (once for attacker side, once for defender side).
At 28 pieces midgame: ≈28×2 = 56 `isSquareAttackedBy()` calls per `evaluate()`.

**Phase 9A linkage:**
- Issue #101 (incremental attacked-squares): replaces 56 `isSquareAttackedBy()` calls with
  2 bitboard lookups (`attackedByWhite`, `attackedByBlack`). `hangingPenalty` becomes:
  `long whiteHanging = whiteNonKing & attackedByBlack & ~attackedByWhite` (1 cycle).
  Expected to reduce `hangingPenalty` from 10.1% to ~1% or less.

### #3 — `MoveOrderer.orderMoves` — **8.9%**

Move ordering at each node: MVV-LVA for captures, killer heuristic, history scores,
continuation history. The method iterates all generated moves and calls `scoreMove`
for each. At an average of ~30 moves per node, this is ~30 scoring operations.

**Phase 9A linkage:**
- Issue #102 (staged capture generation in Q-search): replaces full move generation in
  Q-nodes with staged generation (non-pawn captures first, skip pawn captures on early
  cutoff). Reduces the number of scored moves in Q-nodes. Since Q-nodes dominate
  (q_ratio 3.3× — 77% of all nodes are quiescence), this targets the majority path.
  Expected ≥ 10% NPS gain in Q-search-heavy positions.

---

## Phase 9A Task Priority (data-driven)

| Priority | Task | Issue | Expected Gain | Justification |
|---|---|---|---|---|
| 1 | Incremental attacked-squares bitboard | #101 | ≥ 20% NPS | Eliminates `hangingPenalty` hot path (10.1%); frees cycles inside make/unmake too |
| 2 | Staged capture generation in Q-search | #102 | ≥ 10% NPS | Q-ratio 3.3× → 77% of nodes are Q-nodes; ordering overhead in Q dominates |
| 3 | Lazy SMP (2 helper threads) | #103 | +30–50 Elo | NPS ≥ 1.6× with 2 threads; wall-clock improvement at any TC |
| 4 | Pawn hash hit-rate measurement | #104 | Data collection | Hit-rate must be measured before resizing decision |
| 5 | NPS CI gate | #105 | CI gate | Blocks NPS regressions from future PRs |

---

## Methodology Notes

- **Proxy for TC play:** Bench at depth 10 exercises the same code paths as 10+0.1 TC
  (alpha-beta, quiescence, eval, make/unmake, TT lookups). The hot-method distribution
  is representative; relative proportions are stable across TC vs depth-limit workloads.
- **Sample count:** 483 samples at 10 ms interval → ~4.8 s of sampled execution. This
  is sufficient for method-level attribution but too small for precise sub-1% confidence.
  Methods with ≥ 15 samples (≥ 3.1%) are reliable; lower counts are directional only.
- **JIT inlining:** JFR samples JIT-compiled native code. Methods showing as leaf frames
  have their callees inlined — `hangingPenalty` at leaf includes inlined
  `isSquareAttackedBy`. This inflates its leaf count but correctly attributes CPU cost.
- **JFR raw file:** `C:\Temp\bench9a.jfr` (local, not committed — binary format).
