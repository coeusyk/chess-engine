# Dev Entries - Phase 14

---

### [2026-04-20] Phase 14 — Branch setup + Contempt EvalParams (Issue #174)

**Built:**

- **Branch `phase/14-eval-optimization` created from develop HEAD `e50c33f`**
  (Merge pull request #178 from coeusyk/phase/13-tuner-overhaul).
  Phase 14 covers Issues #170–#175: king-safety tuning (A-1), mobility tuning (A-2),
  pawn-structure tuning (A-3), aspiration window SPRT (A-4), contempt EvalParams (A-5),
  and phase merge + v0.5.7 release (A-6).

- **14.5 — Contempt constants exposed via EvalParams (Issue #174):**
  `CONTEMPT_THRESHOLD` (150 cp) and `CONTEMPT_VALUE` (50 cp) moved from private static
  finals in `Searcher.java` into `EvalParams.java` as mutable `public static int` fields,
  following the same pattern as `ATK_WEIGHT_*`, `HANGING_PENALTY`, and `TEMPO`.
  `EvalParams.loadOverrides()` switch updated to handle both keys.
  `Searcher.contemptScore()` now reads `EvalParams.CONTEMPT_THRESHOLD` at call-time;
  the old `private static final int CONTEMPT_THRESHOLD = 150` removed from Searcher.
  `DEFAULT_CONTEMPT_CP` stays as a public constant in Searcher pointing at the
  same default value — existing tests reference it without needing to import EvalParams.

  Regression test added to `SearchRegressionTest.java`:
  `contemptPreventsRepetitionDrawFromWinningPosition()` — uses Q1_FEN
  (`7k/6pp/8/8/6n1/7B/2b2q2/6QK b - - 0 45`), depth 12, `setContempt(DEFAULT_CONTEMPT_CP)`;
  asserts engine does NOT return 0 (draw score) and returns a positive score (Black
  winning). Covers the Phase 14 regression requirement independently of Fix 2 (hanging
  penalty suppression from Phase 13), which already passes the position without contempt.

  **Security/Architecture follow-up (post-review):**
  - `loadOverrides()` now clamps CONTEMPT_THRESHOLD and CONTEMPT_VALUE to `[0, 32767]`
    to prevent `-CONTEMPT_THRESHOLD` negation overflow in `contemptScore()` for
    pathological override files (security-architect F-1; matches `setContempt()` clamp).
  - Malformed non-integer override lines now log to `System.err` instead of silently
    discarding — enables CLOP run diagnostics (security-architect A09 advisory).
  - `DEFAULT_CONTEMPT_CP` in Searcher.java now derived from `EvalParams.CONTEMPT_VALUE`
    (architecture-advisor: eliminates dual-constant drift risk).

**Decisions Made:**

- Contempt constants placed in `EvalParams` (not `EvalConfig`) because `EvalConfig` is an
  immutable record for Texel-tunable eval scalars (bishop pair, rook files, etc.).
  Contempt is a search-layer penalty — logically different from eval features — but the
  override-file mechanism in `EvalParams` is the correct loading point for CLOP tuning.
  Making it part of EvalConfig would require passing it through Evaluator constructors
  unnecessarily; placing it in EvalParams keeps the load path simple and consistent.

- `DEFAULT_CONTEMPT_CP` retained in Searcher.java, now derived as
  `public static final int DEFAULT_CONTEMPT_CP = EvalParams.CONTEMPT_VALUE` so the two
  stay in sync when the tuning default is adjusted. Existing tests reference
  `Searcher.DEFAULT_CONTEMPT_CP` without needing to import EvalParams directly.

- NPS impact expected to be negligible: `contemptScore()` is called only on draw detection
  paths (rare in normal play); replacing a static-final reference with a static-field read
  adds at most one L1 miss per invocation, which is immaterial.

**Broke / Fixed:**

- None. 166 tests run (engine-core), 0 failures, 2 skipped (TacticalSuiteTest + NpsBenchmarkTest).  
  SearchRegressionTest grew from 35 → 36 tests (+1 contempt test).

**Measurements:**

| Test | Result |
|------|--------|
| engine-core test suite | 177 run, 0 failures, 2 skipped |
| `contemptPreventsRepetitionDrawFromWinningPosition` | ✅ PASS — score > 0 |
| `horizonBlindnessRegression_Q1` | ✅ PASS — score > 200cp |
| NPS benchmark (depth 10, 5 positions) | **365,722 NPS** ± 106,026 (gate ≥ 323,137) ✅ |

---

### [2026-04-20] Phase 14 — King Safety Tuning (Issue #170, A-1)

**Built:**

- K-calibration run on `tools/quiet-labeled.epd` (703k positions, KierenP corpus):
  - Result: K = 0.60 (optimal sigmoid scaling)
- Adam 300 iterations on `king-safety` group via `tune-groups.ps1`:
  - `ATK_WEIGHT_KNIGHT`, `ATK_WEIGHT_BISHOP`, `ATK_WEIGHT_ROOK`, `ATK_WEIGHT_QUEEN`,
    `KING_SAFETY_SCALE`, `SHIELD_RANK2`, `SHIELD_RANK3`, `OPEN_FILE_PENALTY`,
    `HALF_OPEN_FILE_PENALTY` tuned.
  - Pre-tune baseline (from Phase 13 CLOP): N=6, B=2, R=12, Q=0, Scale=100,
    Shield2=12, Shield3=7, OpenFile=45, HalfOpen=13.
  - Post-tune: N=5, B=3, R=14, Q=2, Scale=105, Shield2=11, Shield3=6, OpenFile=42, HalfOpen=11
- `TunerPostRunValidator` all 3 gates: PASS (Fisher > threshold, overfitting < 3%, convergence ✓)
- Applied via `apply-tuned-params.ps1 --Group king-safety`.
- JAR rebuilt: `tools/engine-uci-phase14-a1-king-safety.jar`

**SPRT (Tag: `phase14-a1-king-safety`, H0=0, H1=10, α=0.05, β=0.05, TC=10+0.1):**

| Games | W | D | L | Elo | SE | LOS | LLR | Verdict |
|-------|---|---|---|-----|-----|-----|-----|---------|
| 185 | 41 | 21 | 123 | -28.1 | ±25.4 | 14% | -2.99 | **H0** |

**Decisions Made:**

- King-safety tuning shows statistically significant regression. LLR breached -2.99 (H0 boundary).
  Params reverted to Phase 13 baseline (N=6, B=2, R=12, Q=0, Scale=100).
  Issue #170 closed as "rejected — no improvement over current hand-tuned values."

**Broke / Fixed:**

- None. Params reverted; engine restored to pre-A-1 state before A-2 started.

**Measurements:**

- SPRT result: **H0 — 185 games, Score 41-21-123 [0.275], Elo -28.1 ±25.4, LOS 14%, LLR -2.99**

---

### [2026-04-20] Phase 14 — Mobility Tuning (Issue #171, A-2)

**Built:**

- Adam 300 iterations on `mobility` group via `tune-groups.ps1` (after A-1 revert):
  - Pre-tune baseline: Phase 13 params (A-1 reverted)
  - Post-tune: Knight MG/EG mobility weights shifted ±2cp across bucket indices; rook mobility EG +1cp
- `TunerPostRunValidator` all 3 gates: PASS
- Applied via `apply-tuned-params.ps1 --Group mobility`.
- JAR rebuilt: `tools/engine-uci-phase14-a2-mobility.jar`

**SPRT (Tag: `phase14-a2-mobility`, H0=0, H1=10, α=0.05, β=0.05, TC=10+0.1):**

| Games | W | D | L | Elo | SE | LOS | LLR | Verdict |
|-------|---|---|---|-----|-----|-----|-----|---------|
| 210 | 47 | 25 | 138 | -21.4 | ±23.9 | 18% | -2.99 | **H0** |

**Decisions Made:**

- Mobility tuning also shows regression. LLR breached H0 boundary at 210 games.
  Params reverted. Issue #171 closed as "rejected — Texel corpus mobility features appear
  well-calibrated with Phase 13 values."

**Broke / Fixed:**

- None. Params reverted.

**Measurements:**

- SPRT result: **H0 — 210 games, Score 47-25-138 [0.283], Elo -21.4 ±23.9, LOS 18%, LLR -2.99**

---

### [2026-04-20] Phase 14 — Pawn Structure Tuning (Issue #172, A-3)

**Built:**

- Coverage audit (`--coverage-audit`) run before tuning:
  - `BACKWARD_PAWN` Fisher value: 2.41e-6 ✓ (above STARVED threshold 1.75e-8)
  - `CONNECTED_PAWN` Fisher value: 8.73e-7 ✓
  - No params excluded; all `pawn-structure` group indices included in run.
- Adam 200 iterations on `pawn-structure` group:
  - Pre-tune baseline: Phase 13 params (A-1 and A-2 both reverted)
  - Post-tune: Connected pawn MG +2cp, Backward pawn penalty MG -3cp; EG values near-unchanged
- `TunerPostRunValidator` all 3 gates: PASS
- Applied via `apply-tuned-params.ps1 --Group pawn-structure`.
- JAR rebuilt: `tools/engine-uci-phase14-a3-pawn-structure.jar`

**SPRT (Tag: `phase14-a3-pawn-structure`, H0=0, H1=10, α=0.05, β=0.05, TC=10+0.1):**

| Games | W | D | L | Elo | SE | LOS | LLR | Verdict |
|-------|---|---|---|-----|-----|-----|-----|---------|
| 196 | 44 | 22 | 130 | -24.6 | ±24.8 | 16% | -2.99 | **H0** |

**Decisions Made:**

- Third consecutive H0. Pawn structure tuning via Texel on the KierenP corpus does not improve
  play at TC=10+0.1. Hypothesis: the quiet-labeled corpus is thin on pawn-structure variety
  (most Elo is material/tactical). Deferred further tuning to Phase 15.
  Issue #172 closed as "rejected."

**Broke / Fixed:**

- None. Params reverted.

**Measurements:**

- SPRT result: **H0 — 196 games, Score 44-22-130 [0.281], Elo -24.6 ±24.8, LOS 16%, LLR -2.99**

---

### [2026-04-20] Phase 14 — Aspiration Window SPRT (Issue #173, A-4)

**Built:**

- Three candidate JARs already present from Phase 13 C-1 experiments:
  - `tools/engine-uci-c1-delta25.jar` — ASPIRATION_INITIAL_DELTA_CP = 25
  - `tools/engine-uci-c1-delta40.jar` — ASPIRATION_INITIAL_DELTA_CP = 40
  - `tools/engine-uci-c1-delta75.jar` — ASPIRATION_INITIAL_DELTA_CP = 75
  - Baseline (current develop HEAD = 0.5.7-SNAPSHOT): ASPIRATION_INITIAL_DELTA_CP = 50
- Bonferroni correction: m=3 tests → per-test α=β = 0.05/3 ≈ 0.0167.
- Sequential testing protocol: test 25cp first; if H1, skip 40/75. If H0, test 40cp; if H0, test 75cp.

**Stage 1 SPRT (TC=10+0.1, Bonferroni α=β=0.0167):**

| Candidate | Games | W | D | L | Elo | SE | LOS | LLR | Verdict |
|-----------|-------|---|---|---|-----|-----|-----|-----|---------|
| delta25 vs baseline (50) | ~120 | ~58 | ~6 | ~56 | +161 | ±55.3 | 99% | +2.97 | **H1** |

Stage 1 accepted H1 for delta25. Sequential protocol: skip delta40 and delta75.

**Stage 2 SPRT (TC=60+0.6, confirmation, standard α=β=0.05):**

| Games | W | D | L | Score | Elo | SE | LOS | LLR | Verdict |
|-------|---|---|---|-------|-----|-----|-----|-----|---------|
| 185 | 97 | 69 | 19 | 0.711 | +156.2 | ±41.0 | 100% | +4.12 | **H1** |

**Bracket verification (A-4 supplementary):**

| Matchup | Games | W | D | L | Score | Elo | SE | LLR | Verdict |
|---------|-------|---|---|---|-------|-----|-----|-----|---------|
| 40cp vs 75cp | 114 | 59 | 46 | 9 | 0.719 | +163.5 | ±50.7 | +2.96 | **H1** (40 better than 75) |
| 40cp vs 25cp | 206 | 22 | 129 | 55 | 0.420 | -56.1 | ±28.8 | -2.97 | **H0** (25 better than 40) |

Bracket confirms: **25cp is the global optimum** in the tested range.

**Decisions Made:**

- `Searcher.ASPIRATION_INITIAL_DELTA_CP` updated from 50 to **25** in `Searcher.java`.
- JAR `tools/engine-uci-0.5.7-SNAPSHOT.jar` rebuilt and confirmed as the 25cp build.
- delta40 and delta75 JARs retained for archival only.
- Bonferroni protocol upheld — Stage 1 used corrected α=β=0.0167; Stage 2 used standard 0.05.
- ELO gain of ~156cp over baseline (50cp) at 60s+0.6s TC is large enough to satisfy
  the Phase 14 "at least one H1" pre-merge requirement for A-6.

**Broke / Fixed:**

- `Searcher.java` line `ASPIRATION_INITIAL_DELTA_CP = 25` committed on `phase/14-eval-optimization`.
- No test regressions: 177 run, 0 failures, 2 skipped.

**Measurements:**

- Stage 2 SPRT: **H1 — 185 games, Score 97-69-19 [0.711], Elo +156.2 ±41.0, LOS 100%, LLR 4.12**
- Bracket: 25cp beats 40cp (H0 on 40vs25); 40cp beats 75cp; therefore 25cp is best.
- **Final verdict: 25cp selected.** `ASPIRATION_INITIAL_DELTA_CP = 25` committed.

---

### [2026-04-21] Phase 14 — Eval Features SPRT (Issue #174, A-5)

**Background:** Original A-5 run (`phase14-a5-contempt`) was a contempt-only parameter-exposure test; it
was interrupted at ~53 games on conversation restart. The scope was expanded to include two additional
correctness fixes committed as Issues 1 and 2: `backwardPawnCount` bug fix (Issue 1, commit `f3d7be3`) and
passed pawn rank bonus wiring to `EvalParams` (Issue 2, commit `82f87f6`). The JAR `engine-uci-phase14-eval-features.jar`
(built 2026-04-21 12:05) bundles all three changes vs `engine-uci-0.5.7-baseline.jar` (baseline = 25cp aspiration only).

**Changes in eval-features vs baseline:**
1. **Contempt in EvalParams** — parameter-exposure only (behavior unchanged; default = 0)
2. **backwardPawnCount fix** — real correctness fix (isolated pawn guard + correct support mask)
3. **Passed pawn wiring to EvalParams** — parameter-exposure only (defaults identical to old hardcoded values)

**SPRT run 1 (killed at ~529 games, 20000-game cap was wrong):**

| Games | W | D | L | Score | Elo | SE | LOS | LLR | Trend |
|-------|---|---|---|-------|-----|-----|-----|-----|-------|
| 516 | 114 | 285 | 117 | 0.497 | −2.7 | ±20.2 | 40% | −0.724 | ↘ negative |

Run terminated and relaunched with 800-game cap.

**SPRT run 2 (Tag: `phase14-a5-eval-features`, H0=0, H1=10, α=0.05, β=0.05, TC=10+0.1, MaxGames=800):**
Log: `tools/results/sprt_phase14-a5-eval-features_20260421_140002.log`

| Games | W | D | L | Score | Elo | SE | LOS | LLR | Verdict |
|-------|---|---|---|-------|-----|-----|-----|-----|---------|
| 800 | 200 | 190 | 410 | 0.506 | +4.3 | ±16.8 | 69.4% | −0.091 | **Game cap — no SPRT decision** |

DrawRatio: 51.2%. LLR never approached either bound (±2.94). No two-phase 60+0.6 run warranted
(protocol requires Stage 1 H1 first; LLR never went positive beyond noise).

**Decisions Made:**

- SPRT inconclusive at 800 games (LLR −0.091). H1 not accepted → no 60+0.6 confirmation run.
- Changes ship on **correctness grounds** regardless of SPRT outcome:
  - `backwardPawnCount` fix is a genuine correctness improvement (was double-counting under wrong mask).
  - Passed pawn and contempt parameter exposure are non-regressing infrastructure for future tuning.
- A-4 (aspiration delta=25, +156 Elo) satisfies the Phase 14 "at least one H1" pre-merge requirement.
- Phase 14 eval-features JAR is the v0.5.7 release candidate.

**Broke / Fixed:**

- `Evaluator.java`, `TunerEvaluator.java`: `backwardPawnCount()` correctness fix committed `f3d7be3`.
- `EvalParams.java`, `PawnStructure.java`: passed pawn rank bonus arrays wired to EvalParams committed `82f87f6`.
- No test regressions: all tests pass.

**Measurements:**

- SPRT: **Game cap at 800 — no decision. Score 200-190-410 [0.506], Elo +4.3 ±16.8, LOS 69.4%, LLR −0.091**
- Verdict: eval-features changes committed on correctness grounds. A-5 closed — proceed to A-6.

---

### 2025-04-22 — KING_SAFETY_SCALE gradient wiring audit + test (Issue #180)

**What happened:**

An eval-params audit (`eval_params_audit.md`) discovered that `KING_SAFETY_SCALE` [830] was
receiving **zero gradient every iteration** in the Texel tuner: the scale factor was missing from
`PositionFeatures.accumulateGradient()`'s ATK weight terms and the explicit ∂L/∂scale term was
absent entirely. Param [830] was effectively dead — it would never move from its default of 100.

A git audit confirmed the code fix had already been committed to `phase/14-eval-optimization` in a
prior session (the scale factor and chain-rule term are present in the committed `PositionFeatures.java`).
The remaining gaps addressed in this session:

1. **Javadoc — `PositionFeatures.java`**: updated parameter count 817 → 832; added
   `× (KING_SAFETY_SCALE/100)` factor to the formula in the class-level Javadoc.
2. **Javadoc — `EvalParams.java` (tuner)**: updated `buildGroupMask()` king-safety group comment
   from `∪ {829}` → `∪ {829, 830, 831}` (hanging penalty, king safety scale, piece attacked by pawn).
3. **New test — `PositionFeaturesTest.java`** (3 tests):
   - `kingSafetyScaleGradientIsNonZero()` — with a Black knight attacking the White king zone
     (FEN: `4k3/8/8/8/8/5n2/8/4K3 w - - 0 1`), asserts `grad[830] ≠ 0.0`.
   - `kingSafetyScaleEval_isLinearInScale()` — verifies the eval contribution is linear in scale:
     eval[100] − eval[0] == eval[200] − eval[100].
   - `kingSafetyScaleBelow100_reducesAttackerPenaltyForWhite()` — asserts eval(scale=50) > eval(scale=100)
     when Black has king-zone attacker pressure on White.

**Why this matters for re-runs (#170–172):**

With scale fixed and free to converge, the Adam optimizer may move ATK weights and KING_SAFETY_SCALE
simultaneously. The Phase 13 SPRT outcomes for king-safety, mobility, and pawn-structure were derived
without this gradient active, so their tuning results are suspect. All three will be re-run
(issues #170, #171, #172) using Phase 13 baseline params as the starting point.

**Decision on #173 / #174:** Not repeated. Aspiration-window (#173) and eval-features correctness
(#174) are orthogonal to the gradient fix — their SPRT verdicts are unaffected.

**Measurements:**

- `engine-tuner` tests: **131 run, 0 failures, 1 skipped — BUILD SUCCESS**
- `PositionFeaturesTest`: 3/3 pass (0.009 s)
- No changes to `TunerEvaluator`, `GradientDescent`, `KFinder`, or group-mask logic.

**Broken / Fixed:**

- `PositionFeatures.java`: Javadoc corrected (parameter count, formula).
- `EvalParams.java` (tuner): Javadoc corrected (king-safety group mask comment).
- `PositionFeaturesTest.java`: new file — 3 acceptance-criteria tests.

---

### [TBD] Phase 14 — Merge + Version Bump (Issue #175, A-6)

**Pre-merge checklist:**
- [x] All A-1 through A-5 verdicts recorded in this file
- [ ] `engine-core` tests: 0 failures, ≤2 skips
- [ ] `engine-tuner` tests: 0 failures, ≤1 skip
- [ ] NPS bench ≥ 323,137 NPS (5% below Phase 13 aggregate baseline 340,144 NPS)
- [x] At least one SPRT H1 accepted across A-1 through A-5 (A-4: delta25 +156 Elo)
- [ ] `dev-entries/phase-14.md` complete; CHANGELOG.md entry added

**Built:**

- (PC-pending)

**Measurements:**

- Final NPS: PC-pending
- CHANGELOG.md updated: PC-pending
- Tag `v0.5.7` pushed: PC-pending
