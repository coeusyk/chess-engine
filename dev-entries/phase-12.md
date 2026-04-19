# Dev Entries - Phase 12

### [2026-04-06] Phase 12 — Texel Run-2 Eval Application and Test Rebaseline

**Built:**

- Applied Texel run-2 evaluation parameters from the new corpus-driven tuning pass to `engine-core`: material values, all 12 PST tables, mobility weights, pawn-structure terms, and king-safety terms.
- Kept `DEFAULT_CONFIG` aligned with the tuned scalar set while preserving non-zero attacker, bishop-pair, tempo, and rook-file terms where the corpus did not provide enough signal.
- Added hard minimum floors in `engine-tuner` `EvalParams.buildMin()` for attacker weights, bishop-pair bonuses, and tempo so future tuning runs cannot collapse them to zero again from sparse corpus coverage.
- Rebased eval-sensitive unit tests in `EvaluatorTest` to positions that remain valid under the new PST geometry and MG/EG balance.
- Rebased six `SearchRegressionTest` expected best moves with explicit explanation comments for each changed position.
- Corrected the repeated-search TT assertion to check raw TT hits instead of hit-rate percentage when a fully cached second search visits zero non-TT nodes.

**Decisions Made:**

- Treated zeroed scalar outputs from the Texel run as a corpus-coverage failure, not as a real optimisation result. The quiet self-play corpus did not contain enough king-attack and bishop-pair signal to justify removing those terms.
- Kept the Texel-run PST and material changes, which had broad support in the corpus, but pinned lower bounds on strategically important scalar terms before the next tuning pass.
- Reworked failing tests by holding material constant and changing only the feature under test. This avoids false negatives caused by extreme PST differences dominating low-phase positions.

**Broke / Fixed:**

- Applying the run-2 values initially broke 13 `engine-core` tests.
- `EvaluatorTest` failures came from changed PST geometry and tuned constants: bishop-pair comparison squares were no longer favorable, the rook mobility test used a square with a worse endgame rook PST than `a1`, half-open king-file penalty no longer applied because `HALF_OPEN = 0`, and open/semi-open rook tests were comparing different pawn PSTs instead of isolating the file bonus.
- Fixed those regressions by redesigning the affected test positions around the new tuned values rather than weakening assertions.
- `SearcherTest` hit-rate assertion was wrong for the tuned build: the second search produced TT hits with `nodesVisited = 0`, so `ttHitRate()` legitimately returned `0.0`. Fixed the test to assert `ttHits() > 0`.
- Search regression positions `P1`, `P3`, `P5`, `P10`, `E1`, and `E2` changed best move at depth 8 after the PST update; the expected moves and rationale comments were updated together.

**Measurements:**

- Texel corpus generated for this pass: 28,901 positions.
- Texel tuning result vs prior baseline MSE: `-16.63%`.
- `engine-core` test suite: 177 tests run, 0 failures, 0 errors, 2 skipped.
- Perft depth 5 (startpos): Not measured in this cycle.
- Nodes/sec: Not measured in this cycle.
- Elo vs. baseline: Not measured in this cycle.

**Next:**

- Run a second-pass Texel tuning cycle with PST/material frozen and only scalar terms free under the new lower bounds.
- Validate any resulting eval changes with SPRT on the PC only; do not use laptop measurements for NPS or Elo decisions.

---

### 2026-04-07 — Phase 11 Issue #127: CCRL Submission Checklist (Verification Run)

**Context:** Pre-submission verification run with the current engine build (0.5.5-SNAPSHOT),
post-Texel-run-2 and post-Phase-11 changes. Tier 1 and Tier 2 selfplay stability gates
completed; bench determinism re-verified against the new eval; SPRT vs 0.4.9 run to
establish Elo.

---

#### tools/pre_submission_check.ps1 — PASS (all 4 checks)

- Run: `.\tools\pre_submission_check.ps1 -BenchDepth 13` on PC (Ryzen 7 7700X)
- Bug fixed in script: `Get-ChildItem` pipeline result wrapped in `@(...)` to force
  array type so `.Count` works when exactly one JAR exists in target/.
- Check 1 — Fat JAR exists: **PASS** (`engine-uci-0.5.5-SNAPSHOT.jar`)
- Check 2 — UCI handshake (`uci` → `uciok`): **PASS**
- Check 3 — isready handshake (`isready` → `readyok`): **PASS**
- Check 4 — bench determinism (2 consecutive runs, depth 13): **PASS** — `30 762 217 nodes`

---

#### Bench determinism — 3 consecutive runs (depth 13, PC)

```
Run 1: Bench: 30762217 nodes 117829ms 261075 nps | q_ratio=3.2x
Run 2: Bench: 30762217 nodes 115628ms 266044 nps | q_ratio=3.2x
Run 3: Bench: 30762217 nodes 118392ms 259833 nps | q_ratio=3.2x
```

Node count is identical across all 3 runs. NPS variation is normal thermal/scheduling
noise; node count is the determinism signal. *(Note: bench node count changed from the
previous record of 16 621 621 nodes because Texel run-2 changed PST values and thus
search decisions.)*

---

#### Tier 1 — Crash/Illegal-Move Gate

- File: `tools/results/selfplay_20260406_141223.pgn`
- Command: `.\tools\selfplay_batch.ps1 -Games 200 -TC "10+0.1" -Concurrency 2`
- **Games played: 200**
- **Results:** 61 × 1-0, 96 × ½-½, 43 × 0-1
- **Crashes: 0 | Time forfeits: 0 | Illegal moves: 0**
- Terminations: 88 adjudication, 112 natural (draw / 3-fold / stalemate)
- **Gate: PASS ✅**

---

#### Tier 2 — CCRL TC Time-Forfeit Check

- File: `tools/results/selfplay_20260406_180146.pgn`
- Command: `.\tools\selfplay_batch.ps1 -Games 50 -TC "40/240" -Concurrency 1`
- **Games played: 50**
- **Results:** 19 × 1-0, 20 × ½-½, 11 × 0-1
- **Crashes: 0 | Time forfeits: 0 | Illegal moves: 0**
- Terminations: 28 adjudication, 22 natural
- **Gate: PASS ✅**

---

#### SPRT vs engine-uci-0.4.9.jar — ⚠️ REGRESSION FOUND

- Command: `.\tools\sprt.ps1 -New engine-uci\target\engine-uci-0.5.5-SNAPSHOT.jar -Old tools\engine-uci-0.4.9.jar`
- TC: 10+0.1 | elo0=0, elo1=50, alpha=0.05, beta=0.05
- **Verdict: H0 ACCEPTED** after 18 games (LLR −3.02, crossed lower bound −2.94)
- Score: 1W – 15L – 2D (1 No result)
- **Elo difference: −361 ± nan, LOS: 0.0%**
- PGN: `tools/results/sprt_20260407_213607.pgn`

This is a catastrophic regression. The engine 0.5.5-SNAPSHOT is significantly weaker
than the 0.4.9 baseline. The regression is most likely caused by Texel run-2 PST/material
values producing a broken evaluation — likely incorrect material weights causing the engine
to misjudge piece values and accept losing trades.

**CCRL submission is blocked until this regression is diagnosed and resolved.**

Regression investigation priorities:
1. Compare material constants (PAWN_MG/EG, KNIGHT_MG/EG, BISHOP_MG/EG, ROOK_MG/EG,
   QUEEN_MG/EG) in the current `DEFAULT_CONFIG` against the 0.4.9 values.
2. Check whether king-safety terms went to zero (attacker weights collapsed), leaving
   the engine unable to detect mating attacks.
3. Run a targeted SPRT between 0.5.5-SNAPSHOT-pre-texel-run2 and 0.5.5-SNAPSHOT to
   isolate whether Phase 11 or Texel run-2 is the regression source.

---

#### Issue #127 Checkpoint (2026-04-07)

| Criterion | Status |
|---|---|
| docs/ccrl-submission.md committed | ✅ |
| tools/pre_submission_check.ps1 — all PASS | ✅ |
| Bench determinism (3 runs, depth 13): 30 762 217 nodes | ✅ |
| Tier 1 — 200 games, 10+0.1, 0 crashes / 0 illegal | ✅ |
| Tier 2 — 50 games, 40/240, 0 time forfeits | ✅ |
| All engine-core tests pass (177 run, 0 fail) | ✅ |
| CCRL submission completed | ❌ Blocked — SPRT regression −361 Elo vs 0.4.9 |

**Phase: 11 — Endgame Tablebase + Pre-CCRL Hardening**

---

### [2026-04-08] Phase 12 — Time Management: Move-Number Divisor + Stability Multiplier

**Problem:**
Two structural gaps in time allocation:
1. Flat `remaining / 20` divisor assumed 20 moves left regardless of game phase.
   At TC 5+0.05 move 1 this allocates 15 s soft limit — 5% of the full clock on a
   single opening move where the answer is almost always stable by depth 10.
2. No best-move stability signal. The engine always searched to the full soft budget
   even when depths 8–12 agreed on the same move.

**Root cause analysis:**
- Opening over-allocation: at move 1 the game has ~49 moves ahead; dividing by 20
  wastes ~2.5× as much time as necessary. This directly front-loads the clock.
- No stability shortcut: Stockfish and other competitive engines apply a scale
  multiplier (typically 0.5–0.75×) when the best move is stable over multiple
  consecutive depths, and an extension (1.2–1.5×) when it just changed. Vex had
  neither, spending identical time on a trivially stable e4 as on a tactical crisis.

**Changes:**

`TimeManager.java`:
- Added `stabilityScale` field (default 1.0), written/read only by main search thread.
- `setStabilityScale(double)` clamped to [0.4, 2.0].
- `shouldStopSoft()` now returns `elapsedMs() >= (long)(softLimitMs * stabilityScale)`.
- `configureClock()` overloaded with `moveNumber` parameter.
  Divisor = `clamp(50 - moveNumber, 15, 40)`:
  - Move 1:  divisor=40 → soft ≈ 6.1 s (was 15 s at 5+0.05, full clock)
  - Move 20: divisor=30 → soft ≈ 5 s
  - Move 35: divisor=15 → soft ≈ 4 s (endgame, fewer moves left → more time per move)
- Hard limit reduced from `soft * 2.5` to `soft * 2` (tighter cap; prevents single
  depth from running to 37 s on a 5-minute game).
- All configure*() methods reset `stabilityScale = 1.0` for the new position.
- Zero-arg overloads preserved for backward compatibility (moveNumber defaults to 0).

`Searcher.java` (iterativeDeepening):
- `stableDepthCount` counter and `bestMoveBeforeDepth` snapshot added to ID loop.
- After each completed depth at depth ≥ 5, updates stability scale:
  | Condition               | Scale |
  |-------------------------|-------|
  | Best move changed       | 1.20  |
  | Stable 1 depth          | 1.00  |
  | Stable 2 depths         | 0.85  |
  | Stable 3+ depths        | 0.75  |
- Only active when a TimeManager is present; fixed-depth searches unaffected.

`UciApplication.java`:
- Passes `(boardStates.size() - 1) / 2` as `moveNumber` to `configureClock()`.
- Added `ponderMoveNumber` field; saved on `go ponder`; passed to `configurePonderHit()`.

**Tests:**
- `moveNumberAwareDivisorAllocatesLessTimeInOpening`: move 1 soft < move 35 soft at equal remaining.
- `stabilityScaleRestoresAfterConfigureClock`: re-configure resets scale to 1.0.
- `stabilityScaleClampedToLegalRange`: 0.1 → 0.4, 5.0 → 2.0.
- `shouldStopSoftRespectsStabilityScale`: low scale does not fire immediately.
- Full suite: 160 run, 0 fail, 2 skipped.

**Measurements / SPRT:**
- SPRT: Vex-new (0.5.5-SNAPSHOT, bb68366) vs Vex-old (v0.4.9 baseline), TC=5+0.05
- Parameters: ELO0=0, ELO1=50, alpha=0.05, beta=0.05
- Result: **H1 accepted** — LLR 3.08 (104.4%), lbound=-2.94, ubound=2.94
- Score: 29W – 9L – 8D [0.717] over 46 games
- Elo: +161.8 ± 103.3, LOS: 99.9%, DrawRatio: 17.4%
- Old engine time forfeits: 19 losses (9 "Black loses on time" + 10 "White loses on time") — confirms opening over-allocation bug in flat divisor
- New engine time forfeits: 0

**Next:**
- Update baseline reference: copy `engine-uci-0.5.5-SNAPSHOT.jar` → `tools/engine-uci-0.5.5.jar`
- Bump pom.xml version 0.5.5-SNAPSHOT → 0.5.5 and release
- Continue Phase 12 data pipeline work

---

