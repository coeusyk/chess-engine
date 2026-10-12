# Engineering Investigation: Color-Split Asymmetry in SPRT Results

**Date:** 2026-07-16
**Trigger:** E-5 SPRT (~1600 games) shows NEW-vs-OLD ≈ equal overall (-0.4 Elo), but a
persistent White/Black split that has recurred across multiple SPRTs (including
classical-vs-classical) and flipped direction after a prior search tweak.
**Scope:** investigation only, no implementation. Codebase reviewed at commit
`df1bad0` (branch `phase/15-nnue`).

---

## 1. Statistical Significance

Given data (this run, 1610 games total):

| | W | L | D | n | Score |
|---|---|---|---|---|---|
| NEW as White | 206 | 236 | 363 | 805 | 48.14% |
| NEW as Black | 229 | 201 | 375 | 805 | 51.74% |

Per-game outcome variance computed directly from each sample's own W/L/D counts
(score ∈ {0, 0.5, 1}), not assumed binomial:

- White: variance 0.13692, SE(mean) = 1.304%, **95% CI [45.58%, 50.69%]**
- Black: variance 0.13324, SE(mean) = 1.287%, **95% CI [49.22%, 54.26%]**

Two-sample z-test on the difference (Black − White):

- Difference = **3.60 percentage points**
- SE of difference = 1.832%
- **z = 1.9665, two-tailed p ≈ 0.049**
- 95% CI on the difference: **[0.01%, 7.19%]**
- In Elo terms: White-side Elo ≈ −12.95, Black-side Elo ≈ +12.09, **color Elo delta ≈
  25.0 Elo** (overall combined: −0.43 Elo, matching the reported figure exactly).

**Interpretation:** this single result sits right at the conventional 95% significance
boundary (p≈0.049) — the 95% CI on the difference excludes zero, but only just
(lower bound 0.01%). Taken completely in isolation, one comparison at p≈0.05 is weak
evidence — it is exactly the kind of result that would not survive correction if it
were one of several splits being casually eyeballed (openings, TC, hardware, etc.).

**However, it should not be judged in isolation, for three reasons stated as fact, not
inference:**
1. It is **recurring across multiple independent SPRT runs**, including
   classical-vs-classical (i.e., NNUE is not a precondition for the effect).
2. Its **direction flipped after a search tweak** in an earlier experiment — pure
   sampling noise does not track code changes; a mechanism that responds to a specific
   tweak is evidence of a real, tunable, color-coupled effect somewhere in the
   evaluated code path.
3. **This exact codebase has a proven historical precedent of an identical-flavored,
   identical-direction bug at comparable-or-smaller magnitude** (§2 below) — this is
   not a hypothetical failure mode for this engine, it is a repeat of a previously
   real and previously fixed one.

Random variance remains possible for any single run, but the recurrence pattern is the
stronger signal here, not the single z-score.

---

## 2. Historical Precedent — This Has Happened Before (Issue #183, Phase 14)

`git log` surfaces a **directly on-point prior incident**: commit `44aea1a`
("fix(eval): correct eval asymmetry"), closing issue #183:

> "SPRT H0 at -5.7 Elo +/-7.3 (LLR -2.97) with white asymmetry (**0.481 as White vs
> 0.503 as Black, z~2.04**). Root cause: `PIECE_ATTACKED_BY_PAWN_MG` applied from
> absolute White-first perspective (penalised White active pieces in MG
> disproportionately)."

Compare directly to today's numbers:

| | Historical (#183) | Current (E-5 SPRT) |
|---|---|---|
| White score | 0.481 | 0.4814 |
| Black score | 0.503 | 0.5174 |
| Gap | 2.2pp | **3.6pp (larger)** |
| z | ~2.04 | 1.97 |

The White-side score is **almost bit-for-bit identical** to the historical bug's White
score, while the Black-side score (and therefore the total gap) is now larger than the
confirmed, fixed, real bug from Phase 14. This is the single strongest piece of
evidence in this investigation: it is not merely "an asymmetry could theoretically
exist in an eval-driven engine" — it is "this specific class and magnitude of
asymmetry has already been real, diagnosed, and fixed once in this exact codebase,"
and the current numbers look like a close relative of it, not a new phenomenon shaped
differently.

The original root cause: an evaluation term (`PIECE_ATTACKED_BY_PAWN_MG`) computed
from an absolute White-anchored frame instead of being colour-relative. The fix
(`pieceAttackedByPawnRelative`, `git show 44aea1a`) also added targeted mirror-symmetry
regression tests — but see §4: those tests are narrow, not fuzzed, and would not
necessarily catch a *different* term with the same structural mistake.

---

## 3. Ranked Likelihood of Candidate Causes

Ranked by evidence gathered this session, most to least likely:

### 1. Evaluation asymmetry (recurrence of the #183 bug class) — **most likely**
A newly introduced or re-tuned evaluation term (any of the many Phase 13/14 tuning
rounds documented in `SearchRegressionTest`'s own comments — CLOP passes, king-safety
re-tuning, aspiration-linked eval shifts, "Phase 14 asymmetry fixes" on 2026-04-25)
computed from an absolute perspective rather than colour-relative, the same mistake
class as #183. Directly supported by: the historical precedent (§2), the fact the
pattern reproduces in **classical-only** SPRTs (ruling out NNUE-specific causes and
pointing at shared classical-eval code, since NNUE mode's own accumulator code
reviewed clean — see below), and a real test-coverage gap (§4).

### 2. Search-side color-coupled interaction — **plausible but not evidenced**
Reviewed `Searcher.java` directly for every `isWhite`/`WHITE`/`BLACK` reference (only
5 in the entire file): `correctionHistory[colorIdx][pawnKey]` (correctly indexed by
side-to-move, a legitimate per-side technique, not a bug), the tempo-bonus sign flip
(correctly side-relative), a pawn-promotion-rank check (rule-based, not a bug), and
the contempt `sideToMoveAdv` sign flip (correctly side-relative). **No absolute-color
logic was found anywhere in the search code.** Aspiration windows, null-move pruning,
LMR, and SEE have zero color-conditional branches at all (grep-confirmed). Continuation
history / countermove heuristics don't exist in this codebase (nothing to be
asymmetric). The history heuristic table (`int[7][64]`, piece-type + target-square
only, no color dimension) is the one shared-across-colors structure found — this is
the industry-standard shape (Stockfish and most engines use exactly this), not
color-asymmetric by construction, and not a strong candidate: whichever color happens
to populate a given cell first in a given game is not systematically tied to color
across many games. **Ranked below evaluation** because the search code is
comparatively clean on direct inspection, while the eval layer has a proven bug
history of exactly this shape.

### 3. Random variance — **real, but not the best-supported explanation**
The single-run z≈1.97 is genuinely only marginal. If this were the *only* evidence,
"likely random variance" would be the responsible conclusion. It is demoted below #1
because of the recurrence, direction-flip, and near-exact match to a previously
real, fixed bug — three independent facts that argue against pure noise, none of which
require assuming a bug exists to observe.

### 4. Opening-suite / FEN-generation effects — **low likelihood, partially unverified**
See §5 for what's confirmed vs. assumed. `tools/sprt.ps1`'s TC, adjudication, and hash
settings are provably identical for both colors (single shared `-each`/`-resign`/
`-draw` blocks in the cutechess-cli invocation, not per-engine). The one unverified
assumption is whether `-repeat` is pairing each opening with reversed colors as
cutechess-cli is documented to do — not independently re-derived from this run's raw
PGN in this session (flagged as a concrete follow-up, not assumed away). Even if
verified, a 150,932-line opening book (`tools/noob_3moves.epd`) sampled randomly is a
weak candidate for a *systematic*, direction-flippable-by-code-change bias — book
composition doesn't change when a search parameter is tweaked, but the observed
asymmetry did.

### 5. Adjudication bias, time management, TT interaction — **very low likelihood**
Adjudication and TC are provably shared/symmetric per game pair (§5). Time management
has no color-conditional code (not found anywhere in the grep pass). TT keys are
Zobrist-hashed positions, inherently color-neutral by construction (a hash-collision
theory would need to be color-*systematic* to explain this pattern, which has no
mechanism to be color-correlated).

---

## 4. Search Implementation Review — Color-Dependent Heuristics

Direct grep + read of `Searcher.java` (all `isWhite`/`WHITE`/`BLACK` occurrences, 5
total) and the historical fix's diff:

| Heuristic | Color-dependent? | Verdict |
|---|---|---|
| Correction history | Yes, by design (`colorIdx` = side-to-move) | Correct, side-relative |
| Tempo bonus | Yes, by design (sign flips on side-to-move) | Correct, side-relative |
| Contempt | Yes, by design (`sideToMoveAdv` sign flips) | Correct, side-relative |
| History heuristic | No (piece-type + square only, no color axis) | Standard shape, not asymmetric by construction |
| Killer moves | No (indexed by ply only) | Not color-dependent |
| Continuation/countermove history | N/A — not implemented in this codebase | N/A |
| LMR, null-move, aspiration windows, SEE | No color-conditional branches found | Clean |
| `PIECE_ATTACKED_BY_PAWN_MG` (historical #183 bug) | Was absolute-White-anchored; fixed to be colour-relative (`pieceAttackedByPawnRelative`) | **Fixed in Phase 14 — but see below** |
| `OPPOSITE_FLANK_SHIELD_SCALE` (historical #183 secondary factor) | Legitimately game-state-dependent (discounts whichever side is under *more* attack pressure), not an absolute-color bug | Correct as designed |

**The open question this review cannot close:** whether some *other* evaluation term,
added or re-tuned in one of the many Phase 13/14 tuning passes referenced in
`SearchRegressionTest`'s own inline history (CLOP rounds, king-safety re-tuning,
aspiration-linked shifts), reintroduced the *same class* of mistake (`#183`'s "absolute
perspective instead of colour-relative") in a different term. This requires either
(a) a fuzzed mirror-symmetry test across many random positions (§ below — doesn't
exist today), or (b) a manual audit of every `EvalParams`-driven term added since
`44aea1a`, which is beyond this investigation's scope (research only, no
implementation).

**Test-coverage gap, concretely identified:** `EvaluatorTest.java` has mirror-symmetry
assertions on exactly **5-6 hand-picked positions** (`evalSymmetryMirroredPositions`,
`evalSymmetryForMultiplePieceImbalances`, a king-safety mirror check, a mop-up mirror
check, `pawnAttackPenaltyMirrorSymmetryRegression`). There is **no random/fuzzed
mirror-symmetry test** anywhere in the test tree (confirmed by grep — zero matches for
`Random`/`fuzz` in `EvaluatorTest.java`). A newly introduced absolute-perspective term
would only be caught by this suite if it happens to trigger on one of those five
specific positions — a real, quantifiable coverage gap directly relevant to this
investigation, independent of whether such a term actually exists.

---

## 5. SPRT Framework Guarantees — Verified vs. Assumed

Checked directly against `tools/sprt.ps1` source (this session's own earlier work
extending it for E-5, so already read in full):

| Guarantee | Status | Evidence |
|---|---|---|
| Equal time controls | **Verified** | Single `"-each", "tc=$TC"` block — not per-engine, structurally cannot differ between NEW/OLD. |
| Identical adjudication | **Verified** | Single global `-resign`/`-draw` block, applied by cutechess-cli to both sides identically. |
| Identical hash settings | **Verified** | Neither engine gets an explicit `option.Hash` — both fall back to `UciApplication`'s own identical default (64MB, `UciApplication.java:166`). |
| Identical thread count | **Verified** | `option.Threads=$EngineThreads` applied to both `-engine` blocks with the same variable. |
| Every opening played twice with reversed colors | **Assumed, not independently verified this session** — relies on cutechess-cli's documented `-repeat` behavior (well-established, widely-relied-upon in the wider engine-testing community), not re-derived from this run's actual PGN. **Concrete follow-up:** grep the real PGN for consecutive game pairs sharing an identical opening sequence with swapped engine-color assignment, to convert this from "assumed" to "verified." |
| Equal distribution of FENs | **Follows from the above if true** — same book file, same pairing mechanism, not independently re-verified. |

**If the pairing assumption were false** (e.g., `-repeat` not actually pairing colors
1:1, or an odd-length run breaking a pair): this would inject genuine confound — one
color could disproportionately see structurally different openings than the other,
producing exactly this kind of aggregate split without any engine defect at all. The
805/805 even split in this run is *consistent with* correct pairing (not proof of it —
an even split can also arise from an unpaired-but-large-enough random sample).

---

## 6. Recommendation: Should This Become a GitHub Issue?

**Yes — filed as issue #213, priority High.** This investigates a core correctness
invariant of the evaluation function (colour-symmetry). If violated, the impact
extends well beyond SPRT interpretation: Texel tuning (`quiet-labeled.epd` labels
implicitly assume symmetric eval), NNUE label quality (`eval_scale_check` against
classical eval), self-play quality (Stage 3, once built), any future training dataset,
and benchmark validity all inherit an eval-correctness bug silently. That blast radius
— not the SPRT Elo number itself, which is small — is why this is High priority and a
dedicated issue rather than a follow-up note.

### Investigation plan — reordered for information gained per unit of effort

The previous draft of this plan led with a manual audit of evaluation terms. That is
the *most expensive* step and the one least likely to be conclusive on its own (an
audit can miss the offending term, or "pass" without proving the invariant holds
elsewhere). Reordered so the one test that can actually **discriminate** between "an
eval term is broken" and "look elsewhere" runs first, right after the cheapest
verification step:

1. **Verify `-repeat` pairing directly from generated PGNs — do not rely on documented
   cutechess-cli behavior.** Parse an actual SPRT PGN and confirm every opening
   appears exactly twice with reversed colors (same move sequence into the position,
   `White`/`Black` header swapped between the pair). This is the cheapest step and
   rules out a tooling confound before spending effort on the engine itself.
2. **Implement a randomized mirror-symmetry property test — the highest-leverage
   step, run before any manual audit.** Generate a large corpus of random *legal*
   positions (≥100,000). For each: evaluate it, construct its full mirror (pieces,
   side to move, castling rights, en passant square all mirrored), evaluate that, and
   assert `eval(original) == -eval(mirror)` (within any deliberate, documented
   quantization tolerance only — none is expected for the classical evaluator, which
   is exact integer arithmetic). **On any failure: save the offending FEN and its
   mirror FEN, dump every evaluation term's individual contribution for both, and stop
   immediately** rather than continuing to fuzz past a known-bad case. This becomes a
   **permanent regression/property test**, not a one-off diagnostic script.
3. **Only if the property test fails:** audit `EvalParams`-driven terms introduced or
   re-tuned since commit `44aea1a` (the #183 fix) for the same "absolute perspective
   instead of colour-relative" mistake shape — now a targeted search for a term the
   property test has already proven exists, not a blind audit.
4. **Only if the property test passes:** the evaluation-asymmetry hypothesis is
   substantially weakened (though not to zero — see the tolerance/quantization caveat
   for any future NNUE-mode extension of this test), and search-side interactions
   become the primary hypothesis to revisit, with a corresponding follow-up
   investigation into the search code paths already reviewed in §4 above, this time
   with instrumentation rather than static code reading.

**Diagnostics to collect:**
- Direct PGN evidence confirming or refuting the `-repeat` pairing assumption (§5).
- Full results of the ≥100,000-position mirror-symmetry property test: pass/fail,
  and on any failure, the captured FEN pairs + per-term evaluation breakdowns.
- If step 3 is reached: a term-by-term diff of `EvalParams`/`Evaluator.java` changes
  since `44aea1a`, annotated for which terms are/aren't colour-relative by
  inspection.
- If step 4 is reached: search instrumentation output (e.g., logging which heuristic
  eliminates or reorders the losing side's best defensive line) on a matched pair of
  mirrored positions from real SPRT games.

**Acceptance criteria:**
- [x] `-repeat` pairing verified (or refuted) using actual SPRT PGNs, not assumed from
      documented behavior. — Verified, §8: 729/729 pairs clean across two runs.
- [x] A randomized mirror-symmetry property test is implemented as a permanent
      regression test. — `EvalMirrorSymmetryPropertyTest.java`, §8.
- [x] At least 100,000 random legal positions evaluated by that test. — 100,000, fixed
      seed, ~33s runtime (0-120 ply random walks).
- [x] Any failing case automatically captures the offending FEN, its mirror FEN, and a
      full per-term evaluation breakdown for both. — implemented (`target/mirror-symmetry-failure.txt`
      + inline in the JUnit assertion message); exercised during development, not left
      failing in the committed state.
- [ ] A documented conclusion identifies one of: a specific offending evaluation term
      (fixed, with a dedicated SPRT showing the color gap closes, mirroring #183's own
      validation shape), a search-side interaction (with supporting instrumentation
      evidence), an SPRT/tooling issue (with the PGN evidence from step 1), or a
      statistical demonstration that no systematic asymmetry survives the property
      test and a larger sample. — **Eval term identified and fixed** (§8); only the
      "dedicated SPRT showing the color gap closes" half remains, pending a
      native-Windows run (CLAUDE.md §5).

The goal of this issue is to convert a statistically suspicious aggregate pattern into
either a reproducible engineering failure with a specific root cause, or a ruled-out
hypothesis backed by a permanent test — not to rest on aggregate SPRT statistics alone.

---

## 7. Should the SPRT Framework Emit an Automatic Warning?

**Yes, recommended as a lightweight, informational-only diagnostic** — not a
termination mechanism (explicitly out of scope per the request). Concrete design:

| Parameter | Recommended value | Rationale |
|---|---|---|
| Minimum game threshold | **400 total games (200/color)** | Below this, per-game variance (~0.135, matching this run's own measured value) gives a standard error too large for the check to be informative; 200/color keeps false-positive risk low while still catching effects of this run's actual magnitude within a reasonable fraction of a full SPRT. |
| Check cadence | **Every 200 games after the minimum** | Avoids checking on every single game result (which would compound the sequential-testing "peeking" problem `docs/sprt-guidelines.md` already warns about for the main SPRT decision itself); 200-game steps give a handful of checkpoints across a typical run without excessive noise. |
| Metrics computed at each checkpoint | White-only score + 95% CI, Black-only score + 95% CI, color score difference + 95% CI, z-score | Exactly the statistics computed in §1 above — reuse the same formula, don't invent a new one. |
| Warning threshold | **`\|z\| ≥ 2.0`** | Slightly more conservative than the conventional 1.96, chosen deliberately given repeated checking (some cushion against the multiple-checkpoints problem) without requiring persistence tracking machinery. |
| Persistence requirement | **None required to fire, but recommend flagging "single checkpoint" vs. "recurring across ≥2 checkpoints" explicitly in the warning text** — this investigation's own strongest evidence was recurrence, not a single z-score; the warning should teach that lesson forward rather than over-trust one crossing. |
| Artifact preservation | **On any warning: copy the current `.pgn`/`.log` into a `tools/results/flagged/` subfolder, plus append one row (timestamp, game count, White score, Black score, z) to a running `tools/results/color_split_log.csv`.** Cheap, and turns "I noticed a pattern across multiple runs" (this session's actual trigger) from an anecdotal user observation into a queryable, permanent record. |

This is deliberately the smallest addition that produces real signal: reuse this
investigation's own statistics, bolt onto the existing `sprt.ps1`/`nnue-gauntlet.ps1`
output-writing convention (append a line via the same `Tee-Object` pattern already
used), no new statistical framework, no new file format.

---

## Conclusion

**Evaluation is the leading hypothesis, but causality has not yet been demonstrated.**

This is deliberately not phrased as "likely evaluation issue." Every observation
gathered so far — recurrence, direction-flip, historical precedent, a clean search
review, a mirror-test coverage gap — is a correlational or circumstantial signal, not
a demonstrated mechanism. None of it, individually or combined, has actually shown a
specific evaluation term producing an asymmetric score on a controlled input. That is
the difference between "best-supported hypothesis" and "confirmed diagnosis," and this
investigation has only established the former. §8 below reorders the investigation
plan specifically to close that gap as efficiently as possible, rather than committing
effort to a manual term-by-term audit before running the one test that can actually
discriminate between "an eval term is broken" and "look elsewhere."

Justification for the hypothesis ranking (not a causal claim), weighing codebase
evidence and current SPRT results together:

- The current single-run statistic (z≈1.97, p≈0.049) is only marginally significant
  and would not, alone, justify this conclusion — stated plainly, not hedged.
- What tips the conclusion is the **convergence of three independent facts**: the
  pattern **recurs** across multiple runs (including classical-only, ruling out
  NNUE-specific causes), its **direction flipped in correlation with a search
  tweak** (inconsistent with pure noise, consistent with a real, tunable mechanism),
  and its **magnitude and direction closely match a previously real, diagnosed, and
  fixed evaluation bug in this exact codebase** (#183) — the White-side score today
  (48.14%) is nearly identical to that bug's White-side score (48.1%).
- A direct review of `Searcher.java` found **no** absolute-color-dependent logic in
  any search heuristic (LMR, null-move, aspiration, SEE, killer moves, TT, history
  heuristic all clean) — this weighs against a search-side cause and toward the
  evaluation layer, where the historical bug of this exact shape previously lived.
- A concrete, quantifiable test-coverage gap exists (mirror-symmetry tested on only
  5-6 hand-picked positions, no fuzzed coverage) that would allow a *recurrence* of
  the #183 bug class, in a different term, to go undetected today.
- The SPRT tooling itself (TC, hash, threads, adjudication) is verified symmetric;
  the one unverified assumption (`-repeat` color pairing) is a standard,
  well-established cutechess-cli behavior and a comparatively weak candidate given it
  wouldn't explain a *direction-flip tied to a search tweak*.

This is not a certainty — it is the best-supported conclusion given what a research-only
pass can establish, and the dedicated issue in §6 (specifically: a fuzzed
mirror-symmetry test, and a term-by-term audit of post-#183 eval changes) is the
concrete next step to convert "likely" into "confirmed and fixed," mirroring exactly
how #183 itself was resolved.

---

## 8. Resolution (2026-07-17)

The investigation plan in §6 was executed in full, in order.

**Step 1 — `-repeat` pairing, verified directly from real PGNs.** Parsed two
independent real SPRT PGNs end-to-end (`sprt_phase14-a5-contempt_20260702_192824.pgn`,
872 games / 436 pairs; `sprt_phase14-a1-king-safety_20260501_011943.pgn`, 587 games /
293 pairs — 729 pairs, 1459 games total). Every single pair shares an identical
opening FEN with White/Black swapped between NEW and OLD. **Zero mismatches.** The
`-repeat` pairing assumption in §5 is now confirmed, not assumed — the SPRT tooling
itself is not a confound.

**Step 2 — randomized mirror-symmetry property test, implemented as permanent
regression coverage.** Added
`engine-core/src/test/java/coeusyk/game/chess/core/eval/EvalMirrorSymmetryPropertyTest.java`:
100,000 random legal positions (fixed seed, reproducible), each generated by a random
legal-move walk (0-120 plies, widened from an initial 0-60 during review — see §9) from
the start position via the existing `MovesGenerator`. Reuses `EvaluatorTest.colorFlipFen`
(made package-visible) for the full board mirror (pieces, side to move, castling rights,
en passant). On the **first run, this failed at sample 5/100,000** — not a rare tail
case.

**How the single-root-cause claim was reached — direct per-term measurement, not
inference by elimination.** A throwaway (uncommitted) scratch harness ran the same
generator/mirror pipeline for 5,000 samples without stopping at the first failure, and
for every one of the 1,339 failing samples, called `Evaluator.explainEval()` on *both*
the original and mirrored position and diffed every individual labelled term (material,
mobility, king safety, pawn structure, knight outpost, bishop pair, rook terms,
connected pawns, backward pawns, rook/passer) against its expected antisymmetric value
(`mirroredTerm == -originalTerm`). **`connected pawns` was the only term that ever
failed this per-term check, in all 1,339/1,339 failing samples.** This is a direct
empirical measurement (every term, every failing sample), not an inference drawn from
"fixing `connectedPawnCount` made the aggregate test pass." The same per-term diff was
re-run **after** the fix, at 20,000 samples (0-120 plies): **zero aggregate failures,
zero per-term offenders** — directly confirming, rather than assuming, that no other
term is asymmetric. This closes the gap between "the top-level score is now symmetric"
and "every individual term is now symmetric," which are not logically identical claims
(two terms could in principle miscancel across all sampled positions; the direct
per-term re-scan rules this out empirically for the samples tested, though it remains,
like any finite empirical test, evidence rather than a mathematical proof for all
possible positions).

**Step 3 — root cause found and fixed (property test failed, so this step
triggered).** `connectedPawnCount` (`Evaluator.java`) computed its diagonal-support
bitboard using four shift terms, but two of them duplicated the *same* shift direction
(up-left, down-left) with the *wrong* file mask instead of computing the two genuinely
missing directions (up-right, down-right) — silently producing board-wraparound noise
for edge-file pawns instead of real diagonal support. Fixed by correcting the two
mis-masked terms to their intended shift amounts (`>>>7`/`<<9`), a 2-line change with
no other logic touched. `git log -S` shows this bug was introduced 2026-04-02 (commit
`4eaa7b2`) and never modified since — **it predates issue #183 (2026-04-29) entirely
and was untouched by that fix.** It is a second, independent instance of the same
"absolute/mismasked-perspective instead of colour-relative" mistake shape, not a
regression of #183's fix.

**Verification after the fix:**
- `EvalMirrorSymmetryPropertyTest` now **passes at the full 100,000-position scale**
  (~33s at the widened 0-120 ply range; ~12-15s at the original 0-60 range).
- The independent 20,000-sample per-term re-scan above (broader ply range) found zero
  offending terms, directly, not by inference.
- Full `EvaluatorTest` suite (46 tests, including the original 5-6 hand-picked mirror
  fixtures) still passes.
- Full `engine-core` test suite passes (267 tests, 0 failures, 4 pre-existing skips)
  after recapturing 4 of 5 golden values in `NodeCountRegressionTest` (a documented,
  expected consequence of an intentional eval-shape change — see that test's own
  docstring: "If intentional, re-capture golden values"). Only the pure king+pawn
  endgame fixture (`FEN[1]`) was unaffected; the other four all shifted node counts
  under the corrected eval.
- **Bit-shift correctness independently cross-validated** against this codebase's own
  existing `Attacks.whitePawnAttacks`/`blackPawnAttacks` (`bitboard/Attacks.java`,
  used throughout search/movegen and exercised by the passing Perft suite): those
  functions compute the identical four diagonal shift/mask pairs the fix uses, just
  expressed as destination-side masking rather than source-side masking — algebraically
  equivalent, confirmed by direct derivation. A separate concern raised during review
  (whether rank-1/rank-8 board-edge pawns could cause a similar row-boundary
  wraparound) was traced through Java's 64-bit shift semantics and ruled out: bits
  shifted past position 63 or below position 0 are silently dropped rather than
  wrapping, and the one case that stays in-register (a same-row column wraparound) is
  already excluded by the existing file mask. No change was needed for this; it is
  recorded here because the concern was real enough to check, not because it found a
  defect. Separately, this case is unreachable in practice: pawns cannot legally occupy
  rank 1 or rank 8 in any position produced by this engine's own move generation
  (promotion is mandatory), so the property test itself never exercises it.

**Step 4 — not triggered** (the property test failed at step 2, so per the plan's own
branching logic, the search-side pivot does not apply here).

**SPRT color-imbalance warning mechanism** (§7's design) implemented directly in
`tools/sprt.ps1`: parses cutechess-cli's own `Finished game N (White vs Black): result`
lines as they stream by, tallies a running White/Black score, and once ≥400 games have
completed, prints an informational White/Black score + 95% CI + z-score line every 200
games thereafter (same empirical-variance methodology as §1). Purely additive — it
does not touch `-sprt`/`-repeat`/adjudication arguments, engine invocation, or
cutechess-cli's own stopping logic in any way; it only observes lines already streaming
to stdout and prints one extra line periodically. Two robustness issues were found and
fixed during review: (1) the completed-game denominator was originally an independent
counter incremented alongside the W/L/D tally, which could theoretically desync from
the tally if cutechess-cli ever emitted an unrecognized result string — now derived
directly as `whiteWins + whiteLosses + draws`, so numerator and denominator can never
disagree regardless of input; (2) the log file was being reopened and appended on every
single line (`Add-Content`), which is correct but reopens a file handle thousands of
times over a multi-hour run — replaced with a single `StreamWriter` opened once and
closed in a `finally` block, so a run interrupted mid-stream (e.g. Ctrl+C) still leaves
a properly flushed, non-corrupted log. Not validated against a live cutechess-cli run
(no native-Windows environment available in this session); the PowerShell was manually
reviewed line-by-line and balanced-delimiter-checked, but has not been executed.

### Updated conclusion

This investigation now rests on four distinct kinds of evidence, and they should not be
collapsed into one:

1. **Demonstrated causality (mechanistic, not statistical):** `connectedPawnCount`'s
   diagonal-shift bug in `Evaluator.java` is a specific, isolated, reproducible defect.
   Its effect on eval-symmetry was measured directly, term-by-term, via `explainEval()`
   diffs — not inferred from "the aggregate test started passing" — both before the fix
   (100% of 1,339 failures in a 5,000-sample scan attributable to this one term) and
   after it (zero offending terms in a fresh 20,000-sample scan). This part is as close
   to proven as an empirical software test can get, though it remains a finite sample,
   not a mathematical proof over all possible positions — the property test exists
   precisely so this claim keeps getting re-checked on every future change, rather than
   resting on this session's numbers alone.
2. **Statistical evidence (from the original SPRT):** the z≈1.97, p≈0.049 color split
   in §1, and the historical-precedent comparison to #183 in §2. This evidence
   motivated the investigation but does not, by itself, identify or confirm a specific
   root cause — it is what made the mechanistic finding worth looking for, not a
   substitute for it.
3. **Empirical evidence (from this session's engineering work):** the `-repeat` pairing
   check (§8 step 1) and the per-term explainEval scans are direct measurements against
   real artifacts (real PGNs, real evaluator output), stronger than the purely
   statistical evidence in #2 but still bounded by what was sampled.
4. **Remaining hypothesis, not yet evidence either way:** whether this specific,
   now-fixed bug is *the* explanation (fully or partially) for the ~25 Elo color split
   observed in the original E-5 SPRT (§1). **Evaluation symmetry has been restored for
   this bug** — that part is settled. Whether it explains the originally observed
   color split is a separate, still-open question that only a follow-up SPRT can
   answer: it must show whether the White/Black score gap has *materially decreased*
   relative to §1's baseline. A clean property test does not, by itself, demonstrate
   that this bug was the (or a) cause of that specific historical statistical pattern;
   it demonstrates that the invariant the bug violated is now restored. This satisfies
   the mechanistic half of §6's acceptance criteria ("a specific offending evaluation
   term, fixed") but not yet the validation half ("...with a dedicated SPRT showing the
   color gap closes") — that SPRT must run on native Windows per CLAUDE.md §5 and has
   not been run from this session. **Issue #213 stays open until that SPRT shows
   whether the observed asymmetry has materially decreased.**

---

## 10. Supplementary Data Point (2026-07-17) — the Original SPRT's Own Larger Sample

The E-5 (#205) SPRT referenced as this investigation's original trigger (§1, ~1600 games)
kept running through this entire investigation and was manually stopped by the user on
2026-07-17 at 2617 games (LLR -1.32, never crossed either SPRT bound — inconclusive on
strength, recorded in `nets/dfffd3da-7f8f-4fc9-92dc-b3873c97fb21-release-report.md`).

Its own White-vs-Black split at the larger sample size:

| | at ~1600 games (§1) | at 2617 games (this run's final tally) |
|---|---|---|
| White score | 48.14% | 49.33% |
| Black score | 51.74% | 50.67% |
| Gap | 3.60pp | 1.34pp |

**This entire run predates the `connectedPawnCount` fix (commit `ce1e23e`)** — every one
of its 2617 games used the pre-fix evaluator. It is therefore not a validation of the fix.
What it is: evidence that, under the *unfixed* code, the originally observed gap did not
hold up as more games accumulated — consistent with (though not proof of) the original
z≈1.97 result having been partly sampling variance, exactly the caution §1 itself raised
("this single result sits right at the conventional 95% significance boundary... it is
exactly the kind of result that would not survive... if it were one of several splits
being casually eyeballed"). It does not change the conclusion in §9 (the `connectedPawnCount`
bug is real, isolated, and fixed regardless of how much or little it contributed to any
one SPRT's aggregate color split) and it does not substitute for the dedicated post-fix
validation SPRT §9 still calls for.
