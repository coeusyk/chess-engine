# Vex Phase 13 — Tuner Overhaul: Experiment Registry

**Branch:** `phase/13-tuner-overhaul`
**Date:** 2026-04-12
**Status:** Work-in-progress

This document is the single source of truth for every experiment queued under the Phase 13
tuner overhaul. Each entry specifies the hypothesis, exact parameters, acceptance criteria,
and the order in which experiments must be run (dependencies noted where they exist).

Entries are grouped into four tracks:
  A. Corpus / Coverage
  B. Eval Tuning (Texel / group-based)
  C. Search Parameter Tuning
  D. NPS / Infrastructure

Within each track, experiments are ordered by dependency and expected ROI.

---

## Track A — Corpus & Coverage Audit

### A-1 · Full Coverage Audit on Quiet-Labeled Corpus

**Hypothesis**
The 100-position toy corpus used in the existing `--coverage-audit` diagnostic showed
every parameter starved. The same audit run on the full 703k Ethereal quiet-labeled
corpus will reveal *which specific scalars* have Fisher diagonal < threshold and therefore
cannot receive a useful gradient signal in any subsequent Texel run.

**What to do**
1. Run `TunerMain --mode coverage-audit --corpus quiet-labeled.epd` (or its PowerShell
   wrapper equivalent) with the full 700k usable positions (after in-check / 32-piece
   filter).
2. Collect Fisher diagonal value per parameter index from `GradientDescent`.
3. Produce a ranked list sorted ascending by Fisher diagonal; note any parameter whose
   diagonal falls below `1e-3` per position (i.e., it activates in fewer than ~700
   positions out of 700k).

**Target parameters to watch**
- `ATK_WEIGHT_{N,B,R,Q}`
- `HANGING_PENALTY`
- Rook open/semi-open file (EG component currently 0)
- Rook-behind-passer (MG/EG both near-zero)
- Backward pawn (both 0 in DEFAULT_CONFIG)
- Connected pawn (9/4 — very small gradient expected)
- Knight outpost (40/30)
- Mobility baselines

**Acceptance criteria**
- Audit completes without OOM on the full corpus.
- A coverage report file is written with all 832+ parameter indices, their Fisher
  diagonal, and an activation-count column.
- At least one parameter is identified as adequately covered (diagonal > 1e-3).

**Dependencies**
None. This is the first experiment in the chain.

**Expected wall time**
~15 min on quiet-labeled.epd at 700k positions (single-pass, no gradient step).

---

### A-2 · Targeted Seed Set Expansion

**Hypothesis**
The two existing seed EPD files (`attacker-weights-seeds.epd`, `bishop-pair-seeds.epd`)
cover two parameter groups. The coverage audit (A-1) will reveal additional structurally
starved groups. Augmenting the corpus with hand-crafted or Stockfish-filtered seed
positions for those groups will lift the Fisher diagonal enough that Adam/L-BFGS
receives a meaningful gradient.

**What to do**
After A-1 identifies starved groups, build one seed EPD file per group:

| Seed file | Target params | Construction method |
|---|---|---|
| `backward-pawn-seeds.epd` | `backwardPawnMg/Eg` | Filter positions where `backwardPawnCount > 0` from self-play or opening book PGNs |
| `connected-pawn-seeds.epd` | `connectedPawnMg/Eg` | Filter from same source: `connectedPawnCount > 2` |
| `rook-behind-passer-seeds.epd` | `rookBehindPasserMg/Eg` | Endgame databases or late-middlegame PGN extraction |
| `mobility-high-seeds.epd` | Mobility scalars | Positions with high piece-move counts (open positions, full mobilisation) |
| `king-safety-seeds.epd` | `ATK_WEIGHT_*`, `HANGING_PENALTY` | Middlegame positions with queen-side / king-side attacks active; at least one ATK_WEIGHT piece in enemy king zone |

For each seed file: target 2,000–5,000 positions.

**Acceptance criteria**
- Re-run coverage audit (A-1 methodology) on `quiet-labeled.epd` + all seed files combined.
- Every previously-starved scalar shows Fisher diagonal improvement ≥ 2× vs A-1 result.

**Dependencies**
Must follow A-1 (need the ranked starvation list first).

---

### A-3 · WDL Corpus Construction from Self-Play PGNs

**Hypothesis**
Every "WDL" tuning run to date has loaded exactly zero positions because
`PositionLoader.load` found no matching format in `sf-eval-corpus.txt`.
A properly formatted WDL corpus extracted from Vex's own SPRT PGN outputs
will unlock the WDL code path in `GradientDescent` for the first time.

**What to do**
1. Collect 10–20 SPRT PGN files from `tools/results/sprt/` (already on disk from
   Phase 9B/10/12 runs).
2. Use `PgnExtractor` to extract positions with `wdl` label mode — result headers
   map to 1.0 / 0.5 / 0.0.
3. Filter: keep only positions where `!board.isActiveColorInCheck()` and
   `board.getPieceCount() <= 28` (non-trivial, non-extreme).
4. Target: 50k–100k WDL-labeled positions. Write to `wdl-selfplay.epd`.
5. Run a single validation pass: `TunerMain --label-mode wdl --corpus wdl-selfplay.epd
   --max-iters 1 --dry-run` and confirm non-zero positions loaded.

**Acceptance criteria**
- `PositionLoader` reports > 50,000 positions loaded with no skip-counter explosion.
- MSE on iteration 0 is in a plausible range (0.15–0.30 for WDL labelled data at K ~1.5).
- A 5-iteration smoke run does not diverge.

**Dependencies**
Can run in parallel with A-1/A-2 (independent pipeline).

**Note**
Do NOT attempt to run a full WDL tuning experiment and apply results to the engine until
the augmented corpus (A-2) is in place and a CP Texel baseline group run has passed SPRT.
WDL is an additional signal, not a replacement.

---

## Track B — Eval Tuning (Texel / Group-Based)

All B-track experiments MUST:
- Use the post-run validator (`TunerPostRunValidator`) before any parameter promotion.
- Run per-group SPRT at TC 50+0.5, H0=0, H1=10, α=β=0.05.
- Reject and revert if validator flags >15% of parameters as bound-hitting or crazy.

### B-1 · King Safety Scalars Group (ATK_WEIGHT_*, HANGING_PENALTY)

**Hypothesis**
`ATK_WEIGHT_Q` is now the correct sign after the Phase 13 fix. `ATK_WEIGHT_{N,B,R}`
and `HANGING_PENALTY` have never been properly tuned against a high-quality corpus.
With seed augmentation (A-2) providing coverage, Adam can find better values.

**Current values (EvalParams)**
```
ATK_WEIGHT_KNIGHT  = 6
ATK_WEIGHT_BISHOP  = 4
ATK_WEIGHT_ROOK    = 5
ATK_WEIGHT_QUEEN   = 7   (fixed sign in Phase 13)
HANGING_PENALTY    = (read from EvalParams, Phase 13 branch value)
```

**What to do**
1. Wait for A-2 (`king-safety-seeds.epd` ready).
2. Run: `TunerMain --group king-safety --corpus quiet-labeled-augmented.epd
   --max-iters 200 --optimizer adam --freeze-psts`
3. Constrain with barrier: `PARAM_MIN[ATK_WEIGHT_*] = 0`, `PARAM_MAX = 20`.
   `HANGING_PENALTY` min = 0, max = 120.
4. Post-run validator: sanity bounds, symmetry, smoke test.
5. SPRT vs. `v0.5.5` at TC 50+0.5.

**Acceptance criteria**
- Post-run validator passes all gates.
- At least two of the four ATK_WEIGHT params moved by ≥ 1 cp from their starting value.
- SPRT: H1 accepted, or inconclusive with LOS ≥ 60%.
- Revert if H0 accepted.

**Dependencies**
A-1 (coverage known), A-2 (seed file for king-safety ready).

---

### B-2 · Pawn Structure Group (PASSED_*, ISOLATED_*, DOUBLED_*, backwardPawn, connectedPawn)

**Hypothesis**
`backwardPawnMg/Eg` are both 0 in `DEFAULT_CONFIG` — they have never been tuned.
`connectedPawnMg/Eg` (9/4) are essentially zero. `PawnStructure.evaluate()` computes
passed, isolated, and doubled pawn terms whose weights are stored in `PawnStructure`
directly. These are the highest-frequency terms in any quiet-labeled corpus and should
have strong gradient signal.

**What to do**
1. Map all `PawnStructure` constants and `EvalConfig.backwardPawnMg/Eg`,
   `connectedPawnMg/Eg` into `EvalParams` (if not already) so the tuner can reach them.
2. Add `backward-pawn-seeds.epd` and `connected-pawn-seeds.epd` to the corpus (from A-2).
3. Run: `TunerMain --group pawn-structure --corpus quiet-labeled-augmented.epd
   --max-iters 300 --optimizer adam --freeze-psts`
4. Constrain: `backwardPawnMg` in [0, 30], `backwardPawnEg` in [0, 25].
   `connectedPawnMg/Eg` in [0, 20].
5. Post-run validator + SPRT.

**Acceptance criteria**
- `backwardPawnMg` and/or `backwardPawnEg` land above 5 cp (non-trivial signal).
- SPRT: H1 accepted or inconclusive LOS ≥ 60%.

**Dependencies**
A-1, A-2 (`backward-pawn-seeds.epd`, `connected-pawn-seeds.epd`).

---

### B-3 · Mobility Group (MG_MOBILITY_*, EG_MOBILITY_*, MOBILITY_BASELINE_*)

**Hypothesis**
EG mobility values (knight: 1, bishop: 3, rook: 2, queen: 6) and baselines
(knight: 4, bishop: 7, rook: 7, queen: 14) were set by hand, never tuned.
The current values keep EG mobility effectively near-zero for the minor pieces.
An Adam run constrained to keep all EG mobility ≥ 0 should find better values.

**What to do**
1. Expose `MG_MOBILITY`, `EG_MOBILITY`, `MOBILITY_BASELINE` arrays as tunable
   EvalParams (currently static final in Evaluator — need wiring into EvalParams or
   TunerEvaluator override).
2. Add `mobility-high-seeds.epd` to corpus (from A-2).
3. Constrain: all `EG_MOBILITY` ≥ 0, all `MG_MOBILITY` ≥ 0, baselines ≥ 0.
4. Freeze PSTs and material. Run 200 iterations.
5. Post-run validator must flag 0 params at bounds (all mobility must stay interior).

**Acceptance criteria**
- At least one mobility value changes by ≥ 2 cp.
- SPRT vs. B-2 result (chain: king-safety → pawn-struct → mobility).

**Dependencies**
B-2 result applied and released. A-2 (mobility seeds ready).

**Implementation note**
If the mobility arrays are `static final`, they cannot be overridden at runtime.
Either: (a) make them instance fields on `Evaluator` (like `EvalConfig`), or (b)
use `TunerEvaluator`'s override mechanism exclusively. Option (b) is lower risk.

---

### B-4 · Importance-Weighted Texel on King Safety (Stretch)

**Hypothesis**
Even with seed augmentation, king-safety positions remain a minority. Applying a
position-level weight of 3–5× for positions where at least one `ATK_WEIGHT` piece
is in the enemy king zone will amplify the gradient for those parameters without
distorting material/PST convergence.

**What to do**
1. Add a `--importance-weight` flag to `TunerMain` and `GradientDescent`.
2. Weight rule: `w_i = 3.0` if `kingSafetyFeatures(position) > threshold`, else `1.0`.
   Apply in the MSE loss: `loss += w_i * (sigmoid(K * eval) - result)^2`.
3. Full-batch only (not mini-batch) — `v_t` in Adam must see a stable distribution.
4. Run king-safety group (B-1 params) with weighting ON vs. OFF; compare MSE
   and post-run validator output.

**Acceptance criteria**
- Weighted run MSE on king-safety positions is lower than unweighted run.
- Non-king-safety params do not change by more than 2 cp vs. B-1 result.
- SPRT: H1 accepted over B-1 result.

**Dependencies**
B-1 completed. Implementation of weighting (new code).

**Risk**
High. If the weight threshold is wrong, king-safety params overfit to the seeded
positions and material/PST converge to wrong values. Revert gate: if non-king-safety
params shift > 2 cp, abort and revisit threshold.

---

### B-5 · WDL Tuning Run (Stretch, Post A-3)

**Hypothesis**
CP Texel and WDL Texel are complementary. A WDL run on `wdl-selfplay.epd` (A-3)
may find different optimal K and slightly different eval weights, particularly for
late-middlegame / endgame positions where game result diverges from cp eval.

**What to do**
1. Run `TunerMain --label-mode wdl --corpus wdl-selfplay.epd --max-iters 200
   --optimizer adam` *without* applying results immediately.
2. Compare resulting params to the CP Texel baseline from B-3.
3. If WDL params are within ±5 cp of CP params on all scalars: not worth pursuing further.
4. If WDL params diverge on specific terms (e.g., endgame values): create a blended
   set and SPRT.

**Acceptance criteria**
- WDL run completes and produces a params file without divergence.
- MSE improvement vs. random baseline (initial params) > 1%.
- Blended-params SPRT vs. CP-only result: H1 or inconclusive LOS ≥ 60%.

**Dependencies**
A-3 complete, B-3 complete (need a CP baseline to compare against).

---

## Track C — Search Parameter Tuning

All C-track experiments use SPRT only (no Texel). Each is a single-issue change
validated by TC 50+0.5, H0=0, H1=10, α=β=0.05.

### C-1 · Aspiration Window Initial Delta

**Current value**
`ASPIRATION_INITIAL_DELTA_CP = 50` (set in Phase 9B, issue 116)

**Hypothesis**
50 cp is conservative for an ~1800 Elo engine but has never been SPRT-verified.
The fail-rate from aspiration widening in search logs may justify narrowing to 25–40 cp
(fewer wasted re-searches) or widening to 75 cp (fewer first-time fail-highs in
unbalanced positions). The growth policy (double on failure, full-window on 2nd
consecutive failure) is reasonable but the initial delta drives most of the cost.

**What to do**
1. Expose `ASPIRATION_INITIAL_DELTA_CP` as a UCI option or compile-time constant.
2. Run 3 SPRTs: delta = 25, delta = 40, delta = 75 vs. current delta = 50.
3. Keep the value that maximises LOS (or H1-accept) at TC 50+0.5.

**Acceptance criteria**
- At least one value produces H1 (LOS ≥ 95%) vs. current 50 cp.
- If all three are inconclusive: keep 50.

**Dependencies**
None (isolated constant change).

**Note**
The current widening policy (shift by `DELTA << consecutiveFailures`) doubles on first
failure and goes full-window on second. This is aggressive enough — do not change the
policy in this experiment, only the initial delta.

---

### C-2 · LMR Formula Constant Tuning

**Current formula**
`max(1, floor(1 + ln(d)*ln(m) / (2*ln(2))))`  — divisor = `2*ln(2) ≈ 1.386`
Threshold: `moveIndex >= 4`

**Hypothesis**
The divisor of `2*ln(2)` was chosen to match the Phase 9B spec without an SPRT.
Stockfish's equivalent constant is ~1.5–1.75 for weaker engines. Vex's move ordering
(~97–99% first-move cutoff rate) is excellent, meaning later moves in the list are
reliably bad. This argues for a *larger* divisor (more aggressive reduction). However,
the threshold of 4 is already conservative — testing divisor = 1.7, 2.0, 2.5 is the
right next step.

**What to do**
1. Parameterise the divisor in `precomputeLmrReductions()` — make it a `static final double`.
2. SPRT: divisor = 1.7, 2.0, 2.5 vs. current 1.386.
3. Also test: threshold = 3 (more aggressive entry) vs. current 4, with divisor fixed at best.

**Acceptance criteria**
- Best divisor: H1 accepted vs. current formula.
- Combined (best divisor + threshold test): H1 vs. divisor-only.
- If no improvement: revert to 1.386 / threshold 4.

**Dependencies**
None (isolated formula change). But run after C-1 so aspiration delta is settled.

**Sanity check**
At `depth=4, moveIndex=8`, current R = floor(1 + ln4*ln8/1.386) = floor(1 + 1.386*2.079/1.386)
= floor(1 + 2.079) = 3. At divisor=2.0: floor(1 + 2.079*1/1) — confirm table is as expected.

---

### C-3 · Futility and Razoring Margins

**Current values**
```
FUTILITY_MARGIN_DEPTH_1 = 150 cp
FUTILITY_MARGIN_DEPTH_2 = 300 cp
RAZOR_MARGIN_DEPTH_1    = 300 cp
RAZOR_MARGIN_DEPTH_2    = 600 cp
```
(Razoring is currently disabled — margins are defined but the `canApplyRazoring`
guard returns false for all practical positions.)

**Hypothesis A — Futility depth-1**
150 cp was raised from 100 in Phase 9B (issue 114) without an SPRT. Testing 125 and 175
will confirm the optimal value.

**Hypothesis B — Futility depth-2**
300 cp is the standard for depth-2 and was verified in Phase 9B. No change needed unless
C-2 (LMR) changes the effective depth-2 node count significantly.

**Hypothesis C — Re-enable razoring**
Razoring was disabled in Phase 3 due to tactical regressions at a time when the engine
had no SEE, no correction history, and no improving flag. All three are now in place.
Re-enabling with the existing margins (300/600) may be safe.

**What to do**
1. (C-3a) SPRT: `FUTILITY_MARGIN_DEPTH_1 = 125` vs. current 150.
2. (C-3b) SPRT: `FUTILITY_MARGIN_DEPTH_1 = 175` vs. current 150.
3. Keep best of 125/150/175 from C-3a and C-3b.
4. (C-3c) Re-enable razoring at depth 1 only (`RAZOR_MARGIN_DEPTH_1 = 300`) with
   a SEE guard (skip if move has positive SEE). Run WAC tactical suite first to verify
   no regressions, then SPRT.

**Acceptance criteria**
- C-3a/b: best margin H1 vs. current or inconclusive — keep direction of improvement.
- C-3c: WAC pass rate must not drop below Phase 10 baseline. SPRT: H1 or inconclusive
  LOS ≥ 55%.

**Dependencies**
C-2 should be settled first so futility margin optimisation is not contaminated by
simultaneous LMR changes.

---

### C-4 · Singular Extension Margin Tuning

**Current values**
```
SINGULAR_DEPTH_THRESHOLD  = 8
SINGULAR_MARGIN_PER_PLY   = 8 cp
```

**Hypothesis**
`SINGULAR_MARGIN_PER_PLY = 8` was set during Phase 3 issue 59 (conservative
singular extension). The standard range in open-source engines is 1–3 cp per ply
for Vex's current strength tier. 8 cp is so wide that the singularity search rarely
fires except when there is an overwhelming TT move. Narrowing to 2–4 cp will cause
more genuine singular extensions and reduce horizon blindness on forced sequences.

**What to do**
1. (C-4a) SPRT: `SINGULAR_MARGIN_PER_PLY = 4` vs. current 8.
2. (C-4b) SPRT: `SINGULAR_MARGIN_PER_PLY = 2` vs. best of C-4a.
3. (C-4c) SPRT: `SINGULAR_DEPTH_THRESHOLD = 6` vs. current 8, at best margin.
   (Lower threshold fires singular earlier, extending more lines.)

**Acceptance criteria**
- C-4a: H1 vs. current or inconclusive LOS ≥ 60%.
- C-4b: H1 vs. C-4a result.
- C-4c: H1 vs. C-4b result.
- Revert any step that accepts H0.

**Dependencies**
Run after C-2 (LMR) and C-3 (futility) so search changes do not interact.

**Risk**
Lower margin + lower threshold together can cause depth explosion in tactical positions
(many lines become "singular"). Add a hard limit: if `singularExtensionsApplied > depth * 2`
per root-call, re-check for any infinite recursion path.

---

### C-5 · Null Move Reduction Boundary

**Current values**
`R = 3` when `effectiveDepth > 6`, `R = 2` otherwise (fixed in Phase 9B issue 112).
`NULL_MOVE_DEPTH_THRESHOLD = 3` (minimum depth to attempt null move).

**Hypothesis**
The boundary at `depth > 6` for R=3 is arbitrary. Testing R=3 starting from depth=5
(more aggressive) may recover nodes in typical middlegame search trees where depth 6
nodes are common.

**What to do**
1. SPRT: Change `effectiveDepth > 6` to `effectiveDepth > 5` (R=3 kicks in one depth earlier).
2. Verify no increase in zugzwang-related blunders with the existing endgame guard
   (`hasNonPawnMaterial` check).

**Acceptance criteria**
- H1 accepted or inconclusive LOS ≥ 60%.
- No new WAC regressions on endgame tactical positions.

**Dependencies**
C-2 and C-3 settled. Null move boundary interacts weakly with LMR.

---

### C-6 · Correction History — Table Size & Update Weight

**Current values**
```
CORRECTION_HISTORY_SIZE  = 1024
CORRECTION_HISTORY_GRAIN = 256
CORRECTION_HISTORY_MAX   = GRAIN * 32  (= 8192 stored units → ±32 cp applied)
```

**Hypothesis**
1024 entries is a very small table for a 64-bit hash space. With the Fibonacci hash
modulo 1024, many distinct pawn structures will alias. Increasing to 4096 or 8192 reduces
collision noise at negligible memory cost (4096 entries × 2 colors × 4 bytes = 32 KB).
The update weight (`GRAIN / max(1, depth)`) also means shallow nodes dominate the
table — inverting to `min(GRAIN, depth * 16)` would weight deeper observations more.

**What to do**
1. (C-6a) SPRT: `CORRECTION_HISTORY_SIZE = 4096` vs. current 1024. (Memory-only change.)
2. (C-6b) SPRT: Update weight formula → `min(CORRECTION_HISTORY_GRAIN, depth * 16)` vs.
   current `GRAIN / max(1, depth)`.
3. Both changes combined vs. current baseline.

**Acceptance criteria**
- C-6a: H1 or inconclusive. Table resize is near-zero risk.
- C-6b: H1 vs. combined if both are positive.

**Dependencies**
None. Can run in parallel with B-track if needed.

---

## Track D — NPS / Infrastructure

### D-1 · Tuner Leak Audit in Engine-Core Hot Path

**Hypothesis**
During Phase 13 Texel debugging, several diagnostic helpers (`coverage-audit` Fisher
computation, `EvalParams.isScalarParam`, barrier gradient helpers) were added inside
`engine-tuner`. Confirm none of these leaked into `engine-core` or `engine-uci`.
Any dead code from Phase 13 inside `Evaluator.java`, `KingSafety.java`, or `Searcher.java`
that was added for diagnostics and left in should be pruned.

**What to do**
1. `grep -r "GradientDescent\|TunerEvaluator\|PositionFeatures\|CoverageAudit"
   engine-core/src engine-uci/src` — expect zero hits.
2. In `Evaluator.evaluate()`, verify:
   - `hangingPenalty`: the `pieceAttacks` dispatch loop runs only when `bEscapes <= 1`
     (already guarded). Confirm no unconditional `pieceAttacks` calls.
   - No `System.out` or `Logger` calls in the hot path.
3. Run `NpsBenchmarkTest` before and after any cleanup to confirm ≥ 0% NPS delta.

**Acceptance criteria**
- Zero tuner-module references in engine-core/uci.
- NPS benchmark unchanged or improved.

**Dependencies**
None. Do this first in D-track.

---

### D-2 · hangingPenalty Short-Circuit Before pieceAttacks

**Current behaviour**
When `bEscapes <= 1` (trapped king scenario), `hangingPenalty` iterates over all
`whiteHanging` pieces and calls `pieceAttacks(board, sq, true, allOcc)` for each —
a full magic bitboard dispatch per piece. This branch runs in every mating-attack
position.

**Hypothesis**
A pre-filter on `whiteHanging` before entering the loop can eliminate most `pieceAttacks`
calls: a hanging piece that is not adjacent to the king ring can never be "attacking the
king ring" and can be cleared from `whiteHanging` with a single bitboard AND before
the loop.

**Optimisation**
```java
// Before the per-piece loop:
long bKingRingExpanded = bKingRing | (bKingRing << 8) | (bKingRing >> 8)
                       | ((bKingRing & NOT_A_FILE) >> 1) | ((bKingRing & NOT_H_FILE) << 1);
// Only pieces within two squares of the king ring could possibly attack it
whiteHanging &= bKingRingExpanded; // eliminates far-off pieces immediately
```
This reduces the inner loop from O(all_white_hanging) to O(pieces_near_king) — typically
0–2 pieces instead of 5–8.

**What to do**
1. Implement the pre-filter.
2. Run `NpsBenchmarkTest` and capture delta.
3. Run full test suite to confirm no logic regression.

**Acceptance criteria**
- NPS improvement ≥ 1% on Kiwipete / CPW-pos4 (positions with frequent mating attacks).
- Zero test failures.

**Dependencies**
D-1 (clean baseline).

---

### D-3 · Attack Bitboard Reuse in hangingPenalty

**Current behaviour**
`computeMobilityAndAttack` already computes per-piece attack bitboards for mobility and
king-zone counting. `hangingPenalty` then calls `pieceAttacks(...)` again for the same
pieces — a second magic bitboard lookup for each piece in the suppression branch.

**Hypothesis**
If `computeMobilityAndAttack` stored a packed bitboard of "pieces currently attacking
the enemy king ring" as a side-effect (like `tempWhiteAttackWeight`), `hangingPenalty`
could use that cached result instead of re-dispatching `pieceAttacks`.

**What to do**
1. Add `tempWhiteKingRingAttackers` and `tempBlackKingRingAttackers` fields to
   `Evaluator` (populated in `computeMobilityAndAttack`, same loop that sets
   `atkWeight`).
2. In `hangingPenalty`, replace `pieceAttacks(board, sq, ...) & bKingRing != 0`
   with `(tempWhiteKingRingAttackers & (1L << sq)) != 0`.
3. Run `NpsBenchmarkTest`.

**Acceptance criteria**
- NPS improvement ≥ 1% on positions where `bEscapes <= 1` fires frequently.
- Zero logic regressions.

**Dependencies**
D-2 (both optimise `hangingPenalty`; D-3 is a cleaner version of D-2's fix).

---

### D-4 · Pawn Hash Size Tuning

**Current value**
`DEFAULT_PAWN_HASH_MB = 1` → 65,536 entries.

**Hypothesis**
At 1 MB, the pawn hash covers ~65k unique pawn structures. In long games with deep
search, the working set of pawn structures seen across all TT-resident positions likely
exceeds this. Increasing to 4 MB (262k entries) may improve hit rate and reduce
`PawnStructure.evaluate()` calls without a meaningful memory cost on modern hardware.

**What to do**
1. Enable pawn hash stats in `NpsBenchmarkTest` (call `searcher.enablePawnHashStats()`).
2. Measure hit rate at 1 MB, 2 MB, 4 MB across all 6 bench positions at depth 10.
3. Select smallest size that gives hit rate ≥ 92%.
4. Update `DEFAULT_PAWN_HASH_MB` to that size.

**Acceptance criteria**
- Hit rate ≥ 92% at selected size.
- NPS benchmark at selected size ≥ NPS at 1 MB.

**Dependencies**
None. Can run any time.

---

## Experiment Execution Order (Recommended)

```
A-1 → A-2 (depends on A-1)
A-3 (parallel with A-1/A-2)

D-1 (ASAP, cleanup — no dependencies)
D-4 (ASAP, measurement only)

C-1 (no deps — run while A-track is in progress)
C-2 (after C-1)
C-3 (after C-2)
C-4 (after C-3)
C-5 (after C-2/C-3)
C-6 (parallel with C-3/C-4)

B-1 (after A-1 + A-2 king-safety seeds)
B-2 (after B-1 + A-2 pawn seeds)
B-3 (after B-2 + A-2 mobility seeds)
D-2 → D-3 (after D-1, any time)

B-4 (stretch — after B-1, if B-1 king-safety SPRT is inconclusive)
B-5 (stretch — after A-3 + B-3)
```

---

## Hard Stop Rules (Never Override)

These rules apply to every experiment without exception:

1. **NPS regression gate:** Any code change to `engine-core` that causes > 5% NPS
   regression on the aggregate NpsBenchmarkTest result is an automatic revert. No Elo
   justification overrides this.

2. **Same-JAR SPRT is invalid:** Baseline and candidate JARs must be different builds.
   The same-JAR guard in `tools/sprt.ps1` must remain in place.

3. **Post-run validator is mandatory before param promotion:** No tuned parameters are
   written to `Evaluator.java`, `EvalParams.java`, or `PawnStructure.java` without the
   validator returning a clean run (convergence, sanity, symmetry, smoke all green).

4. **SPRT is mandatory before merge:** Every change to search constants or eval
   parameters must have a completed SPRT entry in `DEV_ENTRIES.md` with verdict before
   the branch is merged to `develop`.

5. **No bulk re-tune while a search experiment is open:** Running Texel while an LMR
   or futility SPRT is in flight invalidates both results. One experiment at a time per
   track (eval vs. search tracks can run in parallel with each other).

---

*End of experiment registry — Phase 13 / Vex tuner overhaul*
