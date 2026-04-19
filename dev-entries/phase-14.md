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
  - Result: K = (PC-pending)
- Adam 300 iterations on `king-safety` group via `tune-groups.ps1`:
  - `ATK_WEIGHT_KNIGHT`, `ATK_WEIGHT_BISHOP`, `ATK_WEIGHT_ROOK`, `ATK_WEIGHT_QUEEN`,
    `KING_SAFETY_SCALE`, `SHIELD_RANK2`, `SHIELD_RANK3`, `OPEN_FILE_PENALTY`,
    `HALF_OPEN_FILE_PENALTY` tuned.
  - Pre-tune baseline (from Phase 13 CLOP): N=6, B=2, R=12, Q=0, Scale=100,
    Shield2=12, Shield3=7, OpenFile=45, HalfOpen=13.
  - Post-tune: (PC-pending)
- `TunerPostRunValidator` all 3 gates: (PC-pending)
- Applied via `apply-tuned-params.ps1 --Group king-safety`.
- JAR rebuilt: `tools/engine-uci-phase14-a1-king-safety.jar`

**SPRT (Tag: `phase14-a1-king-safety`, H0=0, H1=10, α=0.05, β=0.05, TC=10+0.1):**

| Games | W | D | L | Elo | SE | LOS | LLR | Verdict |
|-------|---|---|---|-----|-----|-----|-----|---------|
| PC-pending | — | — | — | — | — | — | — | PENDING |

**Decisions Made:**

- (PC-pending)

**Broke / Fixed:**

- (PC-pending)

**Measurements:**

- SPRT result: PC-pending

---

### [2026-04-20] Phase 14 — Mobility Tuning (Issue #171, A-2)

**Built:**

- Adam 300 iterations on `mobility` group via `tune-groups.ps1` (after A-1 verdict):
  - Pre-tune baseline: (A-1 outcome determines starting point)
  - Post-tune: (PC-pending)
- `TunerPostRunValidator` all 3 gates: (PC-pending)
- Applied via `apply-tuned-params.ps1 --Group mobility`.
- JAR rebuilt: `tools/engine-uci-phase14-a2-mobility.jar`

**SPRT (Tag: `phase14-a2-mobility`, H0=0, H1=10, α=0.05, β=0.05, TC=10+0.1):**

| Games | W | D | L | Elo | SE | LOS | LLR | Verdict |
|-------|---|---|---|-----|-----|-----|-----|---------|
| PC-pending | — | — | — | — | — | — | — | PENDING |

**Decisions Made:**

- (PC-pending)

**Broke / Fixed:**

- (PC-pending)

**Measurements:**

- SPRT result: PC-pending

---

### [2026-04-20] Phase 14 — Pawn Structure Tuning (Issue #172, A-3)

**Built:**

- Coverage audit (`--coverage-audit`) run before tuning to verify BACKWARD_PAWN and
  CONNECTED_PAWN Fisher values are above the STARVED threshold (1.753763e-8):
  - Result: (PC-pending)
- Adam 200 iterations on `pawn-structure` group (STARVED params excluded if applicable):
  - Pre-tune baseline: (A-2 outcome)
  - Post-tune: (PC-pending)
- OR: Formal deferral documented with reason + new tracking issue (if Fisher < threshold).
- `TunerPostRunValidator` all 3 gates: (PC-pending)
- JAR rebuilt: `tools/engine-uci-phase14-a3-pawn-structure.jar`

**SPRT (Tag: `phase14-a3-pawn-structure`, H0=0, H1=10, α=0.05, β=0.05, TC=10+0.1):**

| Games | W | D | L | Elo | SE | LOS | LLR | Verdict |
|-------|---|---|---|-----|-----|-----|-----|---------|
| PC-pending | — | — | — | — | — | — | — | PENDING |

**Decisions Made:**

- (PC-pending)

**Broke / Fixed:**

- (PC-pending)

**Measurements:**

- SPRT result: PC-pending

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

**SPRT Results (Tag: `phase14-a4-aspiration-*`, Bonferroni α=β=0.0167, TC=10+0.1):**

| Candidate | Games | W | D | L | Elo | SE | LOS | LLR | Verdict |
|-----------|-------|---|---|---|-----|-----|-----|-----|---------|
| delta25 vs baseline | PC-pending | — | — | — | — | — | — | — | PENDING |
| delta40 vs baseline | (skip if 25 accepted) | — | — | — | — | — | — | — | — |
| delta75 vs baseline | (skip if 40 accepted) | — | — | — | — | — | — | — | — |

**Decisions Made:**

- If all three values are rejected (H0), retain current 50cp value.
- Winning delta value applied to `Searcher.ASPIRATION_INITIAL_DELTA_CP` if H1 accepted.

**Broke / Fixed:**

- (PC-pending)

**Measurements:**

- SPRT results: PC-pending

---

### [2026-04-20] Phase 14 — Contempt SPRT (Issue #174, A-5)

**SPRT (Tag: `phase14-a5-contempt`, H0=0, H1=10, α=0.05, β=0.05, TC=10+0.1):**

| Games | W | D | L | Elo | SE | LOS | LLR | Verdict |
|-------|---|---|---|-----|-----|-----|-----|---------|
| PC-pending | — | — | — | — | — | — | — | PENDING |

**Decisions Made:**

- (PC-pending)

**Broke / Fixed:**

- (PC-pending)

**Measurements:**

- NPS benchmark (depth 10, 5 positions): PC-pending (≤2% regression gate required)
- SPRT result: PC-pending

---

### [TBD] Phase 14 — Merge + Version Bump (Issue #175, A-6)

**Pre-merge checklist:**
- [ ] All A-1 through A-5 verdicts recorded in this file
- [ ] `engine-core` tests: 0 failures, ≤2 skips
- [ ] `engine-tuner` tests: 0 failures, ≤1 skip
- [ ] NPS bench ≥ 323,137 NPS (5% below Phase 13 aggregate baseline 340,144 NPS)
- [ ] At least one SPRT H1 accepted across A-1 through A-5
- [ ] `dev-entries/phase-14.md` complete; CHANGELOG.md entry added

**Built:**

- (PC-pending)

**Measurements:**

- Final NPS: PC-pending
- CHANGELOG.md updated: PC-pending
- Tag `v0.5.7` pushed: PC-pending
