# Changelog

All notable changes to **Vex Chess Engine** are documented in this file.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

---

## [0.5.7] — Phase 14: Eval Optimization — 2026-07-03

### Search — SPRT-Validated Strength Gains

- **A-4 Aspiration window delta** (#173): `ASPIRATION_INITIAL_DELTA_CP` changed from 50 → 25.
  SPRT H1 at +156.2 Elo (TC 60+0.6, 185 games, LOS 100%). Bracket-verified: 25cp beats 40cp
  beats 75cp, confirming the global optimum in the tested range. Largest single-change Elo
  gain of the phase.

### Search — No-Change Validated

- **A-5 Contempt** (#174): Asymmetric draw-contempt implemented — avoids repetition/50-move
  draws when the side to move holds a >150cp advantage, accepts them when losing by the same
  margin — exposed via `EvalParams.CONTEMPT_THRESHOLD`/`CONTEMPT_VALUE`. Isolated SPRT
  (`phase14-a5-contempt`, 871 games) returned H0 at −16.8 ±16.8 Elo, LOS 2.5% — a clean,
  decisive regression at TC 60+0.6. `UciApplication`'s `Contempt` UCI option default reverted
  50 → 0; the constants and `Searcher.contemptScore()` logic remain available via
  `setoption Contempt <cp>` for opponent-specific or CLOP tuning.

### Evaluation — Correctness Fixes

- **Eval asymmetry fix** (#183): Corrected a White/Black scoring asymmetry traced to
  `PIECE_ATTACKED_BY_PAWN_MG` applying from the wrong perspective and opposite-flank
  king-shield over-suppression. SPRT confirmed neutral (H0, +0.1 ±4.1 Elo, 14,218 games) — a
  correctness fix shipped regardless of Elo, not a strength change.
- **`backwardPawnCount` fix**: corrected double-counting caused by an incorrect pawn-support
  mask; now excludes isolated pawns and uses the correct support-square mask.
- **`PIECE_ATTACKED_BY_PAWN_MG` taper**: full weight in the opening/middlegame, tapers to zero
  below 20% phase; made colour-relative (symmetric) as part of the eval-asymmetry fix.
- **Opposite-flank king-shield scale**: `OPPOSITE_FLANK_SHIELD_SCALE` changed 50 → 75
  (defender side only) — corrects over-suppression of attacking urgency when kings castle to
  opposite flanks.
- **`SAFETY_TABLE` extension** (18 → 32 entries, cap 50cp → 160cp): fixes a zero-gradient
  saturation region (attacker weight `w ≥ 17`) that made `ATK_WEIGHT_ROOK`/`ATK_WEIGHT_KNIGHT`
  values above the old cap indistinguishable to the Texel tuner. Pure eval-table extension —
  indices 0–17 are byte-identical, no behavior change at existing attacker-weight levels.
- **`KING_SAFETY_SCALE` gradient wiring** (#180): fixed a tuner-only bug where parameter [830]
  received zero gradient every iteration (missing term in
  `PositionFeatures.accumulateGradient()`). No engine behavior change — tuner-only fix.
- **Passed-pawn rank bonuses** wired into `EvalParams` (tunable at runtime; defaults unchanged).

### Evaluation — Texel Tuning (All Deferred, No Elo Gain)

Three parameter-group retuning attempts on the 703k-position `quiet-labeled.epd` corpus, all
showing the same pattern: trains fine, validation MSE regresses, and (where SPRT-tested) real
Elo is negative.

- **A-1 King safety** (#170): 4 attempts across the phase (1 SPRT'd, 3 diagnostic-only).
  Final attempt (post-`SAFETY_TABLE` fix): val MSE +5.6% vs. baseline;
  `ATK_WEIGHT_BISHOP`/`ATK_WEIGHT_QUEEN` gradient-dead due to corpus coverage gaps for
  bishop/queen king-zone attack positions. Deferred to Phase 15 pending corpus seed
  augmentation.
- **A-2 Mobility** (#171): val MSE +3.5% vs. baseline despite clean Fisher coverage on all 8
  mobility parameters. LR-overshoot hypothesis tested (4× LR reduction) and falsified —
  converges to the same point regardless of learning rate. Deferred as inconclusive; hand-tuned
  values likely near a local optimum for this corpus.
- **A-3 Pawn structure** (#172): val MSE +4.29% vs. baseline with clean Fisher coverage,
  ruling out corpus starvation. Third and cleanest confirmation of the pattern.
- **Task 14.7 (WDL self-play pilot)**: investigated as a direct test of the
  corpus/gameplay-distribution-mismatch hypothesis; deferred indefinitely without generating
  fresh self-play data. Uncovered two prior undocumented self-play-WDL failures in this
  project's history (Phase 12/13): a −465 Elo material-collapse regression from an
  under-sized/biased corpus, and a silently-broken WDL loader that produced a fake "tuned"
  result. Superseded by planned NNUE work (Phase 17).

### Tuner Infrastructure / Diagnostics

- **`Evaluator.explainEval()` + UCI `eval` command**: per-term evaluation breakdown for manual
  diagnosis.
- **NPS baseline re-established** on native Windows: **328,623 NPS** (was 316,964 NPS,
  now superseded). Node count on the 31-position/depth-13 bench suite shifted ~24%
  (101,771,086 → 77,265,370 nodes) as a direct consequence of the eval changes above shifting
  move ordering and pruning cutoffs — not a measurement artifact (deterministic and identical
  across both WSL2 and native-Windows runs). New regression gate floor: **312,192 NPS**
  aggregate (native Windows only — WSL2 is never a valid NPS gate).

---

## [0.5.6] — Phase 13: Tuner Overhaul — 2026-04-19

### Search — SPRT-Validated Strength Gains

- **C-2 LMR divisor** (#148): `LMR_LOG_DIVISOR` changed from 2.0 → 1.7.
  SPRT H1 at +41.6 Elo (TC 10+0.1, 236 games). Largest single-change Elo gain
  in Phase 13.
- **C-4 Singular extension margin** (#153): `SINGULAR_EXTENSION_MARGIN` changed
  from 0 → −10. SPRT H1 (TC 10+0.1).
- **C-5 Null-move depth threshold** (#147): `NULL_MOVE_DEPTH_THRESHOLD` changed
  from 3 → 4. SPRT H1 (TC 10+0.1).
- **C-6 Correction history resize** (#165): Correction history grown to 4096
  entries with `key >>> 52` addressing and `min(GRAIN, depth * 16)` weight
  formula. SPRT H1 (TC 10+0.1).

### Search — No-Change Validated

- **C-1 Aspiration window** (#153): Delta values 25, 40, 75 all failed H1 vs.
  current delta=50. No change applied.
- **C-3 Futility margin** (#136): `FUTILITY_MARGIN_DEPTH_1` values 125 and 175
  both H0 vs. current 150. No change applied.

### Evaluation — CLOP Tuning

- **CLOP king-safety params** (#142): Two-phase CLOP tuning (queen-only, then
  K/B/R/H) at TC 1+0.01. Final baked values: `ATK_WEIGHT_QUEEN=0`,
  `ATK_WEIGHT_KING=6`, `ATK_WEIGHT_BISHOP=2`, `ATK_WEIGHT_ROOK=12`,
  `HANGING_PENALTY=40`.
- **Engine-tuner formula mismatch fixed** (#166): Engine used `w²/4` (unbounded
  quadratic) for king-safety penalty; tuner used `SAFETY_TABLE` (piecewise-linear,
  capped 50 cp) × `KING_SAFETY_SCALE/100`. Fix aligns engine to use
  `SAFETY_TABLE` + `KING_SAFETY_SCALE`. SPRT H1 accepted at 84 games (TC=100+1):
  +75.6 ±56.9 Elo, LOS 99.5%. The `w²/4` formula was actively miscalibrated
  (over-penalising high attacker counts), suppressing engine strength.

### Evaluation — B-Track Texel Tuning

All three B-track group tuning experiments returned H0 and are deferred to
Phase 14 for re-evaluation with the corrected formula:

- **B-1 King safety scalars** (#166 → #170): H0 at −9.0 Elo (540 games).
- **B-2 Pawn structure scalars** (#168 → #172): H0 at −12.9 Elo.
- **B-3 Mobility scalars** (#167 → #171): H0 at −15.8 Elo.

### Performance

- **Merged eval loop** (#145): Combined mobility and king-safety attack BB
  computation into a single pass, eliminating ~50% redundant sliding-piece
  attack generation.
- **Branchless hangingPenalty** (#147): Removed per-piece loop and dead code
  in D-2/D-3 hanging penalty computation.
- **NPS baseline**: 340,144 NPS aggregate (Ryzen 7 7700X, BenchMain depth 10).

### Tuner Infrastructure

- **Quiet-labeled EPD corpus** (#140): Replaced self-play corpus with
  `quiet-labeled.epd` as primary Texel tuning base.
- **Stockfish eval regression mode** (#141): WDL labels replaced with static
  eval scores via `sigmoid(sf_cp / 340.0)` conversion.
- **Logarithmic barrier method** (#134): Fixed PST absorption of scalar eval
  weights using log-barrier constraints.
- **Block coordinate descent** (#133): Two-phase diagnostic freeze-PSTs mode
  to isolate scalar from PST parameter interactions.
- **D-optimal corpus augmentation** (#135): Coverage audit + targeted FEN
  generation for under-represented features.
- **SPRT statistical corrections** (#136): Bonferroni adjustment and batched
  H1 scaling for multiple hypothesis testing.
- **L-BFGS optimizer** (#137): Full re-tune with L-BFGS optimizer transition.
- **Post-run validator** (#143): Three-gate validator (convergence audit,
  param sanity, 100-game smoke test) before params go to SPRT.
- **Coverage audit CSV** (#150): A-1 coverage audit writes per-parameter
  activation counts.
- **Coverage threshold recalibration** (#163): A-2 STARVED threshold
  recalibrated for quiet-labeled corpus.

### Bug Fixes

- **Q-search check-giving quiet moves** (#138): Fixed horizon blindness on
  mating combinations — mating-threat leaf extension at depth 0.
- **D-2 king-ring pre-filter** (#152): Pre-filter incorrectly suppressed
  hanging penalty for distant sliding pieces. Removed.
- **Corpus script fix** (#146): Script stored pre-computed sigmoid WDL instead
  of raw SF centipawn values.
- **Search regression suite** (#144): Replaced Stockfish-agreement checks with
  self-consistency and EPD suite validation.
- **Pre-existing test failures** (#164): Resolved stale EvalParamsTest and
  GradientDescentTest expectations after CLOP partial revert.

### Tooling

- SPRT harness (`sprt.ps1`): Added `-Tag`, `-TC`, `-Concurrency`,
  `-EngineThreads`, `-MinGames` parameters. Tees stdout to `.log` file.
- Per-group SPRT scripts: `run_c1_sprt.ps1` through `run_c6_sprt.ps1`.
- Opening book: All SPRTs now use `tools/noob_3moves.epd` (EPD, random order).
- `apply-tuned-params.ps1`: Updated for safety-table formula.

---

## [0.5.5] — Phase 12: Data Pipeline

_See previous release for details._
