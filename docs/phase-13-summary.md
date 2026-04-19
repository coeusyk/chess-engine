# Phase 13 Summary — Tuner Overhaul

## Objective

Build a production-quality Texel tuning pipeline, validate all tuned parameters
via SPRT, and integrate CLOP search-parameter optimization into the workflow.

## What Was Built

### Tuner Pipeline (Issues #133–#137, #140–#141, #143, #150, #163)

A complete gradient-based parameter optimization pipeline:

1. **Corpus**: Quiet-labeled EPD with SF static-eval WDL labels (#140, #141).
   Replaced noisy self-play corpus. K-factor: `sigmoid(sf_cp / 340.0)`.
2. **Optimizer**: Adam with plateau early-stop (20-iter window, threshold 1e-5).
   L-BFGS explored (#137) but Adam retained for production runs.
3. **Constraints**: Logarithmic barrier (#134) to prevent PST absorption of
   scalar weights. Block coordinate descent (#133) for diagnostic isolation.
4. **Coverage**: D-optimal augmentation (#135) + CSV activation audit (#150) +
   STARVED threshold recalibration (#163) ensure all 831 parameters see
   sufficient gradient signal.
5. **Validation**: Three-gate post-run validator (#143) — convergence audit,
   param sanity checks (material ordering, PST bounds, mobility monotonicity),
   and 100-game smoke test — gates every param set before SPRT.

### CLOP Search Optimization (Issue #142)

Two-phase CLOP tuning at TC 1+0.01:
- Phase 1: Queen attack weight only (fastest convergence signal)
- Phase 2: King, Bishop, Rook, Hanging penalty

Final baked values: `ATK_WEIGHT_QUEEN=0, ATK_WEIGHT_KING=6,
ATK_WEIGHT_BISHOP=2, ATK_WEIGHT_ROOK=12, HANGING_PENALTY=40`.

### Search Parameter SPRTs (C-Track: #148, #153, #136, #147, #165)

| Parameter | Change | Verdict | Elo | Games |
|-----------|--------|---------|-----|-------|
| C-1 Aspiration delta | 50 (no change) | H0 ×3 | — | — |
| C-2 LMR divisor | 2.0 → 1.7 | **H1** | +41.6 | 236 |
| C-3 Futility margin | 150 (no change) | H0 ×2 | — | — |
| C-4 Singular ext. margin | 0 → −10 | **H1** | — | — |
| C-5 Null-move threshold | 3 → 4 | **H1** | — | — |
| C-6 Correction history | Resize to 4096 | **H1** | — | — |

Net strength gain from C-track: estimated +50–60 Elo (dominated by C-2 LMR
divisor change).

### Performance Optimization (D-Track: #145, #147, #151)

- Merged mobility + king-safety eval loop (#145): single-pass sliding-piece
  attack BB computation.
- Branchless hangingPenalty + king-ring pre-filter removal (#147, #152).
- Multi-size pawn hash sweep in NpsBenchmarkTest (#151).

NPS: **340,144** aggregate (Ryzen 7 7700X, BenchMain depth 10).

### Bug Fixes (#138, #146, #152, #164)

- Q-search mating-threat leaf extension (#138)
- Corpus script raw-cp fix (#146)
- King-ring pre-filter removal (#152)
- Pre-existing test failure resolution (#164)

## What Was Deferred to Phase 14

### B-Track Texel Group Tuning (#166, #167, #168 → #170, #171, #172)

All three B-track experiments (king-safety, mobility, pawn-structure scalars)
returned H0 during Phase 13. Post-mortem analysis revealed an **engine-tuner
formula mismatch**: the engine evaluated king-safety as `w²/4` (unbounded
quadratic) while the tuner modeled `SAFETY_TABLE` (piecewise-linear, capped
50 cp) × `KING_SAFETY_SCALE/100`.

The CLOP-derived ATK_WEIGHT values were calibrated against `w²/4` and performed
well at runtime. The tuner couldn't improve on them because it was optimizing a
fundamentally different penalty curve.

**Formula alignment fix**: Engine now uses `SAFETY_TABLE` + `KING_SAFETY_SCALE`
(matching tuner). SPRT accepted H1 with +75.6 ±56.9 Elo (LOS 99.5%, 84 games).

Re-tuning with the corrected formula is carried forward as Phase 14 issues
#170 (king-safety), #171 (mobility), #172 (pawn-structure).

### PST Group SPRT v2

126 games completed (28W-29L-68D, Elo ≈ −5.5) before being killed. No formal
verdict reached. PST values were reverted prior to B-track work. Inconclusive.

### CLOP Residual Tuning (#142)

Issue #142 relabeled to Phase 14. CLOP search-parameter optimization has further
headroom with the corrected king-safety formula.

## Tuning Pipeline Recommendation

For Phase 14 and beyond, the recommended pipeline is:

1. **Eval regression** (Adam, WDL loss) for bulk parameter groups (831 params).
   Use quiet-labeled EPD corpus, 500 max iterations with plateau early-stop.
2. **Post-run validator** gates every param set before SPRT (convergence,
   sanity, smoke test).
3. **Per-group SPRT** (H0=0, H1=50, TC=10+0.1) for each tuned group before
   baking into engine-core.
4. **CLOP** (TC 1+0.01) for noisy search parameters and king-safety residuals.
5. **Final integration SPRT** (TC=100+1 for low-Elo changes) before merge.

**Critical**: Ensure engine and tuner evaluate identical formulas. The Phase 13
mismatch went undetected through multiple tuning rounds because the shapes were
similar at typical attack weights. A formula-alignment unit test now exists in
`EvaluatorTest.safetyTablePenaltyTests`.

## Test Suite Status

- `engine-core`: 164 tests, 0 failures, 2 skipped (TacticalSuiteTest, NpsBenchmarkTest)
- `engine-tuner`: 106 tests, 0 failures, 1 skipped

## Files Modified (Key)

- `KingSafety.java` — SAFETY_TABLE + safetyTablePenalty()
- `Evaluator.java` — w²/4 → safetyTablePenalty()
- `EvalParams.java` (core) — KING_SAFETY_SCALE parameter
- `EvalParams.java` (tuner) — KING_SAFETY_SCALE + IDX constant
- `TunerEvaluator.java` — applies KING_SAFETY_SCALE to penalty
- `PositionFeatures.java` — king-safety feature accumulation
- `sprt.ps1` — Tag, TC, Concurrency parameters
- `apply-tuned-params.ps1` — safety-table formula support
