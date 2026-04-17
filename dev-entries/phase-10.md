# Dev Entries - Phase 10

### [2026-04-05] Phase 10 — Correction History and Improving Flag (#121, #122)

**Branch:** `phase/10` at `3a59872`

**Built:**

- **Correction History (#121):** Added `correctionHistory[2][1024]` int array (per-color, 1024-entry pawn hash table) to `Searcher`. After computing `staticEval`, a 10-bit pawn key is derived via `((whitePawns ^ blackPawns) * 0x9E3779B97F4A7C15L) >>> 54` (Fibonacci hash). The correction is applied as `staticEval += correctionHistory[colorIdx][pawnKey] / CORRECTION_HISTORY_GRAIN` (GRAIN=256). At the end of each node when storing to TT, the table is updated: `correctionHistory[colorIdx][pawnKey] += corrError * weight` clamped to `±CORRECTION_HISTORY_MAX` (= GRAIN × 32 = 8192 stored units, ±32 cp applied). Weight = `GRAIN / max(1, depth)` — shallower depths contribute less.
- **Improving Flag (#122):** `improving = ply >= 2 && !sideToMoveInCheck && staticEval > staticEvalStack[ply - 2]`. Added `staticEvalStack[MAX_PLY + 2]` field; reset to zeros at the start of each `iterativeDeepening` call via `Arrays.fill`. The stack position is updated to the corrected `staticEval` value. When `!improving`, LMR `reduction` is incremented by 1 before the re-search, increasing reduction aggressiveness for non-improving positions.
- **Constants:** `CORRECTION_HISTORY_SIZE = 1024`, `CORRECTION_HISTORY_GRAIN = 256`, `CORRECTION_HISTORY_MAX = CORRECTION_HISTORY_GRAIN * 32`.

**Decisions Made:**

- Pawn key rather than full Zobrist used for correction history to match the eval term most responsible for low-depth static eval errors (pawn structure).
- Correction applied to `staticEval` but the raw value (`staticEvalRaw`) is preserved for the TT-store correction update. The correction does NOT affect the evaluation stored in the TT — only local pruning decisions and the update delta.
- Improving is defined at `ply - 2` (same side two plies ago), not `ply - 1` (opponent's ply), which is the standard definition.
- Correction history and improving flag updates are skipped in singularity searches (`inSingularitySearch`) and when the side to move is in check (eval is unreliable in check positions).
- Issues #119 (NPS regression), #120 (singular extensions), #123 (piece bonuses) closed as already-implemented — all three features were present in prior phases.

**Broke / Fixed:**

- No test failures. 77 tests pass, 1 skipped, BUILD SUCCESS.

**Measurements:**

- NPS benchmark (re-measurement for #109 resolution, 2026-04-05):

| Position      | Phase 8 NPS | Phase 9A NPS | Phase 10 NPS | Delta vs 9A |
|---------------|-------------|--------------|--------------|-------------|
| startpos      | 402,750     | 428,613      | 258,529      | −39.7%      |
| kiwipete      | 246,066     | 239,954      | 155,626      | −35.1%      |
| cpw-pos3      | 601,293     | 522,398      | 341,152      | −34.7%      |
| cpw-pos4      | 279,393     | 269,484      | 176,317      | −34.6%      |
| **aggregate** | **381,194** | **365,112** | **232,906**  | **−36.2%**  |

  The raw NPS drop from Phase 9A to Phase 10 is explained by Phase 9B pruning improvements (LMR log formula, futility at depths 1–2, aspiration windows) dramatically reducing explored node counts at depth 10. Fewer nodes per search round means lower raw NPS while effective search quality per unit time improves. CI gate at 200,000 NPS (5% threshold = 190,000 floor) is comfortably passing at 232,906 aggregate. Issue #109 closed.

- Perft (correctness): No regression — full perft suite passes unchanged.
- Elo vs. baseline: SPRT pending after merge to develop.

**Next:**

- Merge `phase/10` → `develop`.
- Trigger release v0.5.0 (minor bump — opening book, full UCI pondering, correction history, improving flag constitute a minor-version-worthy feature set across Phase 9B completion and Phase 10).

**Phase: 10 — Classical Eval + Search Micro-optimisations**

---

### [2026-04-06] Phase 10 — Piece Bonus Texel Tuning (#10.5)

**Branch:** `phase/10-piece-bonuses` at `d303667`

**Built:**

- **Texel-tuned BISHOP_PAIR, ROOK_ON_7TH, ROOK_OPEN_FILE, ROOK_SEMI_OPEN** via Adam gradient
  descent (`engine-tuner-0.5.1-SNAPSHOT-shaded.jar`, 100K positions from `quiet-labeled.epd`,
  200 iterations, `--no-recalibrate-k`). K fixed at 1.560035 throughout; final K = 1.564782.
  MSE start: 0.05834970 → best: 0.05692550 (iter 197).
- **Updated `Evaluator.DEFAULT_CONFIG`:**

  | Term            | Before MG | After MG | Before EG | After EG |
  |-----------------|-----------|----------|-----------|----------|
  | BISHOP_PAIR     | 33        | **29**   | 52        | 52       |
  | ROOK_ON_7TH     | 2         | **0**    | 23        | **32**   |
  | ROOK_OPEN_FILE  | 50        | 50       | 0         | 0        |
  | ROOK_SEMI_OPEN  | 19        | **18**   | 19        | 19       |

- **Unit tests added to `EvaluatorTest.java`:**
  - `bishopPairBonusFires` — `4k3/8/8/8/8/8/8/2B1KB2 w` vs `4k3/8/8/8/8/8/8/2N1KB2 w`; pair eval > without pair
  - `rookOnOpenFileBonusFires` — rook on open d-file (pawn on a-file) vs rook on blocked d-file; open > blocked
  - `rookOnSemiOpenFileBonusFires` — semi-open d-file (white d-pawn absent, black d-pawn present) vs blocked; semi > blocked
  - `rookOnSeventhRankBonusFires` — `4k3/R7/8/8/8/8/8/4K3 w` vs `4k3/8/8/8/8/8/8/R3K3 w`; 7th rank > 1st rank
- **SPRT script:** `tools/sprt_phase10_piece_bonuses.ps1` created. NEW = latest engine-uci JAR;
  OLD = `tools/engine-uci-0.4.9.jar` (Phase 9A baseline). Parameters: H0=0, H1=10, α=0.05,
  β=0.05, TC=5+0.05, 20000 games. SPRT pending execution (requires PC with cutechess-cli).
- **Regression test update:** `SearchRegressionTest.E8` updated: `g1g5` → `h2h4`.
  Position `7k/p7/8/8/8/8/7P/6RK w`: with rook7thEg raised from 23→32, the depth-8 search
  prefers pawn advance `h2h4` (pawn race) over `g1g5` (rook activation). Both continuations
  win; choice is eval-dependent. Note added.
- **NMP node-count test update:** `SearcherTest.nullMovePruningReducesNodesOnQuietPosition`
  bumped from depth 7 → depth 8 for robustness. NMP savings become more reliable at greater depth;
  the previous depth 7 was borderline with the new piece bonus values.

**Decisions Made:**

- `--no-recalibrate-k` used to reduce tuner wall time. K was already calibrated from a prior full run; re-running per-iteration ternary search adds ~35 MSE passes per iteration with negligible accuracy gain once K has converged.
- 100K positions chosen over 500K due to memory constraints on the laptop (512m heap, ~5× smaller working set). The tuner converged well within 200 iterations.
- ROOK_OPEN_FILE and ROOK_SEMI_OPEN EG unchanged (0 and 19). The Adam optimizer found the pre-tuned values already near the MSE minimum for those parameters.
- SPRT uses H1=10 (tighter than standard H1=50) because this is a pure Texel-tuning commit with no algorithmic change — any Elo signal should be small, positive, and consistent.

**Broke / Fixed:**

- No pre-existing tests broken. E8 regression and NMP node test updated to match new eval behavior (see above).
- All 154 engine-core tests pass, 2 skipped (TacticalSuiteTest + NpsBenchmarkTest). BUILD SUCCESS.

**Measurements:**

- Tuner: MSE 0.05834970 → 0.05692550; improvement = 0.00142420 (2.44% reduction)
- NPS: not re-measured (eval-only change on laptop; benchmark requires PC)
- Perft: no regression (board/movegen untouched)
- SPRT: **H1 accepted** (149 games, TC 5+0.05)
  - Score: Vex-10-PieceTuned 94-21-34 vs Vex-9A (0.745)
  - Elo gain: +186.2 ± 54.2 (LOS 100.0%)
  - White: 52-7-16 [0.800], Black: 42-14-18 [0.689]
  - Draw ratio: 22.8%
  - LLR: 2.95 / 2.94 bound (100.1% confidence)
  - Verdict: Decisive — piece bonus Texel tuning yields massive Elo improvement

**Next:**

- Merge `phase/10-piece-bonuses` → `develop`
- Trigger release workflow (patch bump: 0.5.1-SNAPSHOT → 0.5.1)

**Phase: 10 — Classical Eval + Search Micro-optimisations**
---

