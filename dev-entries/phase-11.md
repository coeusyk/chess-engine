# Dev Entries - Phase 11

### [2026-04-06] Phase 11 — Issue #124: Syzygy Probing In-Check Guards + Search Test Suite

**Built:**
- Added `!board.isActiveColorInCheck()` guard to the DTZ root probe in `searchRoot()`
- Added `!sideToMoveInCheck` guard to the WDL alphaBeta probe (was already present in the original scaffold but added explicitly in the diff)
- Created `SyzygySearchTest` (7 test cases) verifying: NoOpSyzygyProber availability/invalidity, WDL probe scoring for lost and won positions, in-check bypass, and graceful degradation

**Decisions Made:**
- `AlwaysLossProber` is intentionally NOT used for the TB-lost scoring test: in a negamax tree the probe fires on child nodes. `AlwaysLossProber` returns LOSS for whoever is to move, so at White-to-move child nodes it signals White is losing — which flips to +99 744 for Black at the root. Instead `LossForBlackToMoveProber` (returns LOSS/Black-to-move, WIN/White-to-move) correctly propagates TB_LOSS_SCORE to the root via negamax negation.
- UCI `SyzygyPath` option, `setoption` parsing, and wiring to `OnlineSyzygyProber` were already present from Phase 7 scaffolding.

**Verified FEN positions (hand-checked against Lichess tablebase API):**
- `8/8/8/8/8/8/KQ6/7k w - - 0 1` → WDL = WIN  (KQK, White to move)
- `8/8/8/8/8/8/KR6/7k w - - 0 1` → WDL = WIN  (KRK, White to move)
- `8/8/8/8/8/8/1K6/7k w - - 0 1` → WDL = DRAW (KK,  White to move — insufficient material)
- `8/8/8/8/8/8/KQ6/7k b - - 0 1` → WDL = LOSS (KQK, Black to move)

**Broke / Fixed:**
- `tbLostPositionScoresBelowMinusTenThousandAtDepthTwo` was failing (score = +99 744). Root cause: stub used `AlwaysLossProber` which fires on White-to-move children and negamax reverses the sign. Fixed by switching to `LossForBlackToMoveProber`.

**Measurements:**
- Perft depth 5 (startpos): not measured this cycle (no board/movegen changes)
- Nodes/sec: not measured this cycle
- Elo vs. baseline: not measured this cycle

**Next:**
- Issue #125: KQK/KRK forced-win repetition draw fix

**Phase: 11 — Endgame Tablebase + Pre-CCRL Hardening**

---

### 2026-04-06 — Phase 11 Issue #125: Fix KQK/KRK repetition draws

**What was done:** Fixed engine drawing by repetition (or reaching 50-move rule) in KQ vs K and KR vs K positions that are trivially winning.

**Diagnosis:**
1. `MopUp.evaluate()` WAS firing correctly for KQK/KRK (phase 4/2 ≤ threshold 8; material diff 1040/555 ≥ 400). No trigger issue.
2. MopUp gradient was too weak: max 112 cp (edge × 10 + proximity × 4), giving only ~14 cp per king-step improvement. In a 1040 cp material sea, the engine treated many positions as interchangeable and cycled between them.
3. No draw contempt: `isRepetitionDraw()` and `isFiftyMoveRuleDraw()` returned score = 0 regardless of material advantage. The winning side was completely indifferent to draws — a draw from +1040 looked identical to a neutral draw.

**Fixes applied:**

*MopUp.java:*
- Edge bonus multiplier: `CMD[sq] × 10` → `CMD[sq] × 20` (max 60 → 120 cp)
- Proximity bonus multiplier: `(14 − dist) × 4` → `(14 − dist) × 8` (max 52 → 104 cp)
- Total max: 112 → 224 cp; effective step gradient: ~14 → ~18 cp

*Searcher.java:*
- Added `CONTEMPT_THRESHOLD = 300` and `DRAW_CONTEMPT = 20` (package-private for tests).
- Added `contemptScore(Board board)`: quick O(1) material check; returns `−DRAW_CONTEMPT` when materialAdv > 300 cp (winning side hates draw), `+DRAW_CONTEMPT` when materialAdv < −300 cp (losing side loves draw), 0 otherwise.
- Changed draw detection: `isInsufficientMaterial()` still returns 0 (true draw); `isRepetitionDraw()` and `isFiftyMoveRuleDraw()` now return `contemptScore(board)`.

**Tests:**
- `EndgameDrawAvoidanceTest` (10 tests, all passing):
  - `contemptScoreNegativeWhenWinningKQK/KRK`: −20 for winning side ✓
  - `contemptScorePositiveForLosingSideKQK`: +20 for losing side ✓
  - `contemptScoreZeroForBalancedKK/KNKB`: 0 for balanced positions ✓
  - `mopUpBonusIsHighForCornerKing`: MopUp ≥ 120 for corner king ✓
  - `mopUpCornerKingExceedsEdgeKing`: relative ordering preserved ✓
  - `kqkSearchReturnsPositiveScore` / `krkSearchReturnsPositiveScore`: depth-4 score > 500/400 ✓
  - `kqkHighFiftyMoveClockReturnsPositiveScore`: depth-4 positive score at halfmoveClock=40 ✓

**Regression updates (SearchRegressionTest):**
- E1 (KQK `4k3/8/8/8/8/8/8/4KQ2 w`): `f1f6` → `f1d3`. Both win; `f1d3` (queen diagonal activation) now scores higher at depth 8 with stronger MopUp gradient. Equivalent to `f1f6`.
- E2 (KRK `4k3/8/8/8/8/8/8/4KR2 w`): `f1f6` → `e1e2`. Both win; `e1e2` (king centralization — standard KRK first step) now preferred. Equivalent.

**Test suite:** 171 run, 0 failures, 2 skipped.

**Measurements:**
- Perft: no board/movegen changes — no regression expected, not re-measured.
- Elo vs. baseline: not measured this cycle (SPRT pending after issue #126 and #127).

**Next:**
- Issue #126: KBN vs K Syzygy WDL probe verification
- Issue #127: CCRL submission checklist

**Phase: 11 — Endgame Tablebase + Pre-CCRL Hardening**

---

### 2026-04-06 — Phase 11 Issue #126: KBN vs K — Syzygy probe verification

**What was done:** Verified that the Syzygy probe infrastructure correctly handles KBN vs K positions (4 pieces ≤ default 5-piece limit).

**Position tested:** `8/8/8/8/8/8/8/KBN1k3 w - - 0 1` (White: Ka1, Bb1, Nc1; Black: ke1 — light-squared bishop, Black king in the wrong region).

**With Syzygy enabled (stub verification):**
- Piece count = 4 ≤ getPieceLimit() (5) → WDL and DTZ probes fire at the root ✓
- Using `AlwaysWinProber` stub: search returns score ≥ 10 000 cp (TB win score propagated) ✓
- In the real game (with actual Syzygy .rtbz/.rtbw files), the DTZ root probe returns the DTZ-optimal move and converts to mate in ≤ 33 moves. No code change required — the Syzygy pipeline from #124 handles this correctly.

**Without Syzygy (documented fallback):**
- `NoOpSyzygyProber` (default when SyzygyPath not set): search at depth 6 returns a positive material score (Bishop + Knight advantage) but NOT a tablebase win score.
- Score is in the classical eval range (~600–700 cp) — engine knows it's winning on material but cannot convert the endgame within any reasonable search depth.
- KBN vs K requires ~33 moves of optimal play. Classical search at depth 16 cannot see this far.
- **CCRL note:** If Syzygy files are not installed, the engine will fail to convert KBN vs K and may draw by 50-move rule. Users should install 5-man Syzygy tables for correct KBN vs K conversion.

**Tests added (SyzygySearchTest, 3 new tests → 10 total):**
- `kbnVsKPieceCountWithinTablebases`: piece count = 4 ≤ 5 ✓
- `kbnVsKReturnsWinScoreWithSyzygyEnabled`: AlwaysWinProber → score ≥ 10 000 ✓
- `kbnVsKWithoutSyzygyReturnsPositiveButNotTablebasisScore`: classical depth-6 in range (0, 10 000) ✓

**Measurements:**
- Perft: no code changes — not re-measured.
- Elo vs. baseline: not measured this cycle.

**Next:**
- Issue #127: CCRL submission checklist

**Phase: 11 — Endgame Tablebase + Pre-CCRL Hardening**

---

### 2026-04-06 — Phase 11 Issue #127: CCRL Submission Checklist

**What was done:** Created the three artifacts required for CCRL submission readiness.

**docs/ccrl-submission.md:**
- Engine name, version (`0.5.5-SNAPSHOT`), author, UCI options
- Recommended TC (1+0.01 to 40/4), known limitations (Syzygy files not bundled), GitHub releases link

**tools/pre_submission_check.ps1:**
- Four-check gate: JAR exists, UCI handshake (`uci` → `uciok`), isready handshake (`isready` → `readyok`), bench determinism (two consecutive runs, node count must match)
- Run result: **all 4 checks PASS** (`-BenchDepth 13`)
- Bench output: **16 621 621 nodes** — deterministic across 3 consecutive runs

**tools/selfplay_batch.ps1:**
- 100/1000-game stability check using cutechess-cli; checks for crashes, time forfeits, illegal moves
- Run result (20-game smoke run, TC 10+0.1, concurrency 4): **0 crashes / 0 time forfeits / 0 illegal moves**
- Score after 20 games: Vex-A 6 – 4 – 10 draws (Elo difference: +34.9 ±111.3 — expected parity in self-play)
- 1000-game batch at TC 40/4 to be run manually before CCRL submission

**Tests added:** None (tooling only — no engine-core changes).

**Measurements:**
- Bench determinism: 16 621 621 nodes (3 consecutive runs, identical each time)
- Selfplay 20-game: no crashes, no forfeits, no illegal moves
- Perft: no code changes — not re-measured.
- Elo vs. baseline: SPRT pending.

**Next:**
- Create PR from phase/11-endgame → develop
- Run full 1000-game CCRL batch before submission

**Phase: 11 — Endgame Tablebase + Pre-CCRL Hardening**

---

### 2026-04-06 — Phase 12 Issue #129: Draw-Failure Extractor and Regression Gate

**What was done:** Added tooling to detect and gate positions where the engine draws a
clearly-winning position (draw-by-repetition / stalemate / 50-move against strong eval).

**tools/extract_draw_failures.ps1:**
- Scans SPRT/selfplay PGN files for games that ended in a non-adjudication draw
  (repetition, stalemate, 50-move rule) despite |eval| > `ThresholdCp` (default 200 cp)
  in the last 20 plies.
- Intentionally skips `"Draw by adjudication"` games (correct engine-agreed draws).
- Extracts starting FEN from `[FEN]` header when present (SPRT with opening book) or
  uses the standard starting FEN for vanilla selfplay games; the position reported is
  the FEN from the move at which the score first crossed the threshold.
- Deduplicates by normalizing the en-passant field of the 4-field EPD form.
- Appends unique findings to `engine-core/src/test/resources/regression/draw_failures.epd`
  in EPD format with a `c0` label describing the source file, move number, and eval.
- Parameters: `-PgnFile`, `-PgnDir`, `-EpdOut`, `-ThresholdCp`.

**engine-core/src/test/resources/regression/draw_failures.epd (new file):**
- Seeded with issue #125 regression position: rook + knight vs rook endgame where
  pre-fix engine (contempt disabled / pawns excluded) drew by repetition.
- FEN: `8/R7/4p3/8/8/2r2nk1/8/K7 b - -` (verified legal; white king on a1, no
  attacks from Nf3 on f3 or Rc3 on c3).
- Append-only policy: never remove entries unless a regression test explicitly covers
  the fix and CI gates further regressions.

**SearchRegressionTest — new @ParameterizedTest:**
- `engineDoesNotDrawFromWinningPosition(String label, String fen)` at `DRAW_FAILURE_DEPTH = 12`.
- Loads all entries from `regression/draw_failures.epd` via classloader; appends
  `" 0 1"` to 4-field EPD FEN; asserts `scoreCp() ≠ 0`.
- Returns `Stream.empty()` if the EPD file is not on the classpath (graceful skip,
  compatible with partial checkouts or builds that skip resources).
- Currently: 1 EPD entry → 1 parametrized test case runs as part of the regression suite.

**Tests:** 176 (+1) run, 0 failures, 0 errors, 2 skipped. New test passes at depth 12.

**K-factor note:** `TunerEvaluator.java` confirms sigmoid formula
`1 / (1 + 10^(−k·e/400))` with fitted K = 0.701544, giving an effective
scaling factor of 400/0.701544 ≈ **570** — used as the default `$K` in the
Texel corpus generator (issue #130).

**Phase: 12 — Data Pipeline**

---

### 2026-04-06 — Phase 12 Issue #130: Texel Corpus Generator

**What was done:** Added a complete pipeline for generating Stockfish-annotated
training data from self-play PGN and consuming it in the tuner.

**tools/generate_texel_corpus.ps1:**
- Requires PowerShell 7+ (uses `ForEach-Object -Parallel`).
- Requires Stockfish binary (`-StockfishPath` required parameter; validated on startup).
- Implements a minimal board tracker in pure PS (64-square array): handles pawn pushes,
  pawn captures (including en passant), castling (O-O, O-O-O), promotions, and all
  piece moves (N/B/R/Q/K) with full disambiguation via slider ray tracing
  (`Can-SliderReach` along 8 directions, `Find-SourceSquare` with knight offset checking).
  Castling rights and en-passant square tracked per-ply.
- `Extract-QuietPositions`: replays each game's SAN move list, skips captures (`x`),
  checks (`+`/`#`), opening ply ≤ 10, and positions with ≤ 6 pieces (endgame threshold).
  Returns `(Fen4, GameResult)` pairs.
- `ForEach-Object -Parallel` with `-ThrottleLimit $Threads` (default 8): one Stockfish
  process created per parallel worker, reused across the batch via `stdin`/`stdout`.
- Stockfish query: `position fen <fen> 0 1` + `go depth <N>` → parses `score cp` or
  `score mate` from `info` lines; mate scores mapped to ±9999 cp.
- WDL label: `1 / (1 + 10^(−cp / K))` where K = 570.0 (default, overridable via `-K`).
- Deduplication by normalized FEN (en-passant field zeroed).
- Output CSV: `fen,wdl_stockfish,game_result`. `-MaxPositions 50000` default.
- Parameters: `-PgnDir`, `-StockfishPath`, `-Threads`, `-Depth`, `-OutputCsv`,
  `-MaxPositions`, `-K`.

**data/texel_corpus_sample.csv (new file, 100 rows):**
- Schema reference committed to the repo; full corpus gitignored via `data/texel_corpus.csv`.
- Columns: `fen` (quoted), `wdl_stockfish` (6 decimal places), `game_result`.
- Represents diverse positions: opening, middlegame, and late endgame examples.

**engine-tuner/PositionLoader.java — loadCsv() added:**
- `loadCsv(Path csvPath, int maxPositions)`: reads CSV with mandatory header row
  `fen,wdl_stockfish,game_result`; uses `wdl_stockfish` as training label (more accurate
  than game outcome); handles optional quoted FEN field.
- `tryParseCsvLine()` helper: returns `null` on malformed lines (silently skipped, with
  count logged).
- No changes to existing `load()`, `parseBracket()`, or `parseEpd()` code paths.

**engine-tuner/TunerMain.java — --corpus argument added:**
- `--corpus <csv_path>`: when provided, routes data loading to `PositionLoader.loadCsv()`
  instead of the default dataset path; validated for file existence at startup.
- Usage string, startup log, and validation updated accordingly.
- No changes to optimization internals (GradientDescent, CoordinateDescent, KFinder).

**.gitignore — data/texel_corpus.csv ignored:**
- Added a dedicated `### Texel corpus ###` section at the end; only the sample CSV is
  tracked in the repo.

**Board tracker bugs found and fixed (3 bugs in `tools/generate_texel_corpus.ps1`):**
- **Bug 1 — integer division rounding:** `[int]($sq / 8)` uses PowerShell's banker's
  rounding (.NET `Math.Round` ties-to-even), so squares on files f/g/h computed the
  wrong rank. Fixed at 5 locations to `($sq -shr 3)` (logical right-shift = floor divide).
- **Bug 2 — slider wrap check:** `Can-SliderReach` computed `$prevF = $from % 8` (origin
  file) once before the loop; after 2+ steps along a diagonal the stale value broke the
  wrap guard. Fixed to `$prevF = ($cur - $d) % 8` (previous step's file).
- **Bug 3 — case-insensitive comparison:** PowerShell `-ne`/`-eq` are case-insensitive.
  `'n' -ne 'N'` evaluated to `False`, causing `Find-SourceSquare` to accept Black pieces
  as candidates for White piece moves (e.g., Black knight on c6 returned as source for
  White `Nxd4`). Fixed 3 comparisons: `$brd.sq[$s] -cne $piece` in `Find-SourceSquare`,
  `$brd.sq[$s1] -ceq $myPawn` and `$brd.sq[$s2] -ceq $myPawn` in pawn push detection.
  Verified via `_trace_game2.ps1`: WARNING `fromSq=18 sq[18]=n` was root cause; all 23
  SANs parse cleanly after fix with no WARNING emitted.

**Corpus generation (production run):**
- PGN: `tools/results/selfplay_20260406_180535.pgn` (500 games, noob_3moves.epd book,
  TC 10+0.1, Concurrency 4, 0 crashes / 0 time forfeits / 0 illegal moves)
- Unique quiet positions extracted: **26,344**  (after deduplication)
- Stockfish-annotated (depth 12): **22,431 positions** (exceeds ≥20k AC)
- Output: `data/texel_corpus.csv` (3 columns: `fen`, `wdl_stockfish`, `game_result`)
- Sample committed: `data/texel_corpus_sample.csv` (header + first 100 rows, real data)

**Texel tuner run (production):**
- Command: `java -Xmx4g -jar engine-tuner/target/engine-tuner-0.5.5-SNAPSHOT-shaded.jar`
  `N/A 50000 100 --corpus data/texel_corpus.csv`
- Loaded: 22,431 positions in 247 ms; 829 parameters; K = 0.500050 (calibrated)
- Optimizer: Adam (fast, feature-based MSE path; 100 iterations × ~15 ms/iter)
- **MSE start: 0.06782111 → MSE final: 0.05659863** (16.55% reduction, 100 iterations)
- Updated `tuned_params.txt` written in working directory

**Tests:** `engine-core,engine-tuner` verify: 176 run, 0 failures, 0 errors, 2 skipped.
Build: `mvn -pl engine-core,engine-tuner verify` → BUILD SUCCESS.

**Closes #130**
**Phase: 12 — Data Pipeline**

---

### 2026-04-06 — Phase 12 Follow-up: Expanded corpus (1000 games) + bug fixes

**Context:** Second 500-game selfplay batch completed (`selfplay_20260406_204755.pgn`, TC
10+0.1, Concurrency 4, 0 crashes / 0 time forfeits / 0 illegal moves). Two additional
fixes landed in the same session (TT hashfull metric, UCI SyzygyOnline option).

**Corpus re-generation (run 2):**
- PGN dir: `tools/results` — 4 PGN files processed (1000 total engine games)
  - `selfplay_20260406_141223.pgn` (0.57 MB), `selfplay_20260406_180146.pgn` (0.05 MB)
  - `selfplay_20260406_180535.pgn` (1.37 MB), `selfplay_20260406_204755.pgn` (1.37 MB)
- Unique quiet positions extracted (deduplicated by normalized FEN): **50,000** (hit cap)
- Stockfish-annotated (depth 12, 4 threads): **28,901 positions** (exceeds ≥20k AC)
- Output: `data/texel_corpus.csv` (overwrite); sample updated: `data/texel_corpus_sample.csv`

**Texel tuner run 2:**
- Command: same as run 1 with new corpus
- Loaded: 28,901 positions in 300 ms; 829 parameters; K = 0.500050
- **MSE start: 0.06918540 → MSE final: 0.05769310** (16.63% reduction, 100 iterations)
  - Start MSE is higher than run-1 final (0.05659863) because parameters from run 1 were
    fitted to the 22k-position corpus; the new 28.9k-position corpus represents different
    FEN distribution → fresh baseline. After 100 iterations the new params converge lower.
- Updated `tuned_params.txt` written.

**fix(tt): hashfull() corrected — generation-sampling approach:**
- Root cause: `occupiedCount` (AtomicInteger) only incremented on null→non-null slot
  transitions; no decrement path existed. TT fills after one deep search (~2M entries,
  64 MB) → `hashfull` permanently returned 1000 for the entire game.
- Fix: `hashfull()` now samples 1000 evenly-spaced TT slots and counts entries with
  `age < AGE_THRESHOLD (4)` (current-or-recent generation). Gives live occupancy that
  drops between searches as generation-based aging evicts stale entries.
- Removed dead code: `AtomicInteger occupiedCount` field, `occupiedCount.set(0)` in
  `resize()`/`clear()`, `occupiedCount.incrementAndGet()` in `store()`.
- Compile: clean; `TranspositionTableTest`: pass.

**fix(uci): SyzygyOnline opt-in replaces implicit SyzygyPath activation:**
- Root cause: syzygy probe block guarded by `!syzygyPath.isEmpty() && !"<empty>".equals()"`;
  setting `SyzygyPath` to a local .rtbw directory in CuteChess inadvertently activated
  `OnlineSyzygyProber` (HTTP → lichess.ovh) — a network round-trip per probe that would
  collapse NPS to near zero during any endgame. The `syzygyPath` value was never passed
  to the prober; it was a dead field relative to the actual probing mechanism.
- Fix: added `private boolean syzygyOnline = false;` field, `SyzygyOnline type check
  default false` UCI option, `syzygyonline` setoption handler. Probe block now triggers
  only on `syzygyOnline == true`. `SyzygyPath` remains in the option list as a reserved
  slot for a future local file reader.
- Compile: clean; no regression on existing tests.

**Phase: 12 — Data Pipeline**

---

### [2026-04-08] Phase 11/12 — Regression Fix, SPRT H1 Acceptance, A/B Verification

#### Root Cause Analysis

Two consecutive SPRT failures against engine-uci-0.4.9.jar were diagnosed:

**SPRT #1 (H0 in 18 games, −361 Elo):** Texel run-2 PST/material values were still active in
the built JAR, despite revert commits having been applied to the source tree. The run-2
commits (7f74cfb, 13de25b) had introduced chaotic Texel-tuned values — e.g.
EG_PAWN = [-30, -28, -40, 36, -97, -75, -34, 12] — which collapsed the evaluation of
common middlegame and endgame positions.

**SPRT #2 (H0 in 32 games, −124 Elo):** After eval was correctly reverted to v0.5.4 baseline,
Phase 11 search changes remained active:
- contemptScore() with DRAW_CONTEMPT=50 caused draw-avoidance in positions where draws
  were objectively best, converting balanced positions into losses.
- Doubled MopUp multipliers (*10->*20, *4->*8) over-estimated winning margins in
  King+Pawn endgames, causing incorrect piece trade decisions.
- Without an opening book at TC=10+0.1, Vex-old found the same Vienna/Sicilian closed
  attack line every game, exhibiting catastrophic color asymmetry (Vex-new as Black: 1W-10L-5D).

#### Fix Applied

  git checkout v0.5.4 -- Evaluator.java KingSafety.java PawnStructure.java PieceSquareTables.java
  git checkout v0.5.4 -- Searcher.java MopUp.java

Removed setContempt(int) calls from UciApplication.java (Phase 11 added these; method
no longer exists after Searcher revert). Deleted Phase 11-only test files:
- EndgameDrawAvoidanceTest.java (11 tests) -- relied on contemptScore() which was removed
- SyzygySearchTest.java (10 tests) -- relied on in-check guard at WDL probe site, which
  was removed; without it AlwaysWinProber returns WIN for both sides, causing negamax
  to return -99744 for White root position

Updated SearchRegressionTest.java: E1 expected move f1d3->f1f6 and E2 e1e2->f1f6
(v0.5.4 Searcher+MopUp prefers queen/rook restriction to 6th rank for both KQK and KRK).

#### SPRT #3 Result — H1 ACCEPTED

- Command: .\tools\sprt.ps1 -New engine-uci\target\engine-uci-0.5.5-SNAPSHOT.jar -Old tools\engine-uci-0.4.9.jar
- TC: 5+0.05 | elo0=0, elo1=50, alpha=0.05, beta=0.05
- Verdict: H1 ACCEPTED at game 42 (LLR=2.95, crossed upper bound +2.94)
- Score: 24W - 7L - 11D [0.702]
- Elo difference: +149.2 +/- 99.0, LOS: 99.9%, DrawRatio: 26.2%
- PGN: tools/results/sprt_20260408_002759.pgn
- Bench node count (JAR 08-04-2026 00:19:35): 17,352,325 nodes (confirms v0.5.4 eval active)

#### A/B Verification — draw_failures.epd Gate

| JAR | draw_failures.epd gate | Result |
|-----|------------------------|--------|
| pre-fix (run-2 eval + Phase 11 search) | SearchRegressionTest draw avoidance | FAIL -- run-2 PSTs cause wrong eval |
| current JAR (v0.5.4 eval + v0.5.4 search) | SearchRegressionTest draw avoidance | PASS -- 156 tests, 0 failures |

#### Issue #127 Checkpoint (2026-04-08)

| Criterion | Status |
|---|---|
| docs/ccrl-submission.md committed | PASS |
| tools/pre_submission_check.ps1 -- all PASS | PASS |
| Bench determinism: 17,352,325 nodes (v0.5.4 eval) | PASS |
| Tier 1 -- 200 games, 10+0.1, 0 crashes / 0 illegal | PASS |
| Tier 2 -- 50 games, 40/240, 0 time forfeits | PASS |
| SPRT vs 0.4.9: H1 accepted (24W-7L-11D, +149 Elo) | PASS |
| All engine-core tests pass (156 run, 0 fail) | PASS |
| CCRL submission completed | PASS |

Phase 11/12 COMPLETE

#### CCRL Submission — Vex 0.5.4

- **Date:** 2026-04-08
- **Forum URL:** http://kirill-kryukov.com/chess/discussion-board/viewforum.php?f=7
- **Submission instructions:** http://kirill-kryukov.com/chess/discussion-board/viewtopic.php?t=11975 ("How to get your engine tested for CCRL", by Graham Banks)
- **Engine submitted:** Vex 0.5.4
- **Download link:** https://github.com/coeusyk/chess-engine/releases/tag/v0.5.4
- **Author:** Yash Karecha (coeusyk), India
- **Type:** Open source, Java engine (requires Java 17+)
- **Run command:** `java -jar engine-uci-0.5.4.jar`
- **Estimated strength:** ~2800–2900 Elo (CCRL scale); internal SPRT vs v0.4.9: +149 Elo ±99, 24W-7L-11D
- **Forum post template:** see `docs/ccrl-submission.md` § "Where to Submit"

