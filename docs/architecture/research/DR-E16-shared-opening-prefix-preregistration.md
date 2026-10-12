# E-16 design record: shared-opening-prefix Stage-3 retrain (preregistration)

**Status: preregistration complete; Phase B prerequisite implementation complete, including the
per-game opening schedule mechanism (sections 8-10); no corpus generation, no training, no SPRT
executed yet.** Governs issue #224, "[P15] E-16 -- Stage-3 shared-opening-prefix retrain (fixes
E-15's degenerate control)." Downstream of #223 (E-15, closed,
`DR-E15-stage3-first-retraining-preregistration.md` and its Phase B/C/D/E reports) and
`DR-M1-cp-only-noise-floor-characterization.md` (the noise-floor study this document's
interpretation rule is built on). Reuses `DR-E14-seeded-diversity-audit-and-preregistration.md`'s
treatment-selector parameters unchanged.

## 1. Estimand -- reframed precisely, distinct from E-15's

E-15 remains valid for the question it actually answered: **does training on
`SeededDiversitySelector`'s bounded, diverse Stage-3 self-play labels improve the NNUE relative to
an otherwise-matched `BestMoveSelector` rank-0 control, when both start every game from the
standard starting position?** That estimand's answer (null/inconclusive, `DR-E15-phase-e-
evaluation-report.md`) is not touched, revised, or reinterpreted by this document.

**E-16 asks a different, narrower question**: *given that both arms already receive the same
exogenous opening diversity (a shared, deterministic prefix played identically by both selectors),
does `SeededDiversitySelector` still add anything over `BestMoveSelector` once the degenerate-
control confound is removed?* This isolates the diversity mechanism's effect specifically to what
happens **after** a shared, non-degenerate opening distribution, rather than testing "diversity
mechanism vs. no diversity mechanism starting from one fixed position" (E-15's estimand, which let
the control's own total absence of position diversity confound the comparison, per `DR-E15-phase-
bc-corpus-generation-report.md` section 12's disclosed single-repeated-trajectory collapse).

## 2. Shared-opening mechanism

**Source, chosen over inventing a new one**: `engine-uci/src/main/resources/books/Performance.bin`
-- the engine's existing production Polyglot opening book, added in commit `060ad2f` (#106/#107,
"implement Polyglot opening book ... and UCI pondering"), already read by `OpeningBook.java` and
used for genuine UCI play (`UciApplication`'s `openingBook` field). **Explicitly not used**:
`bench/nnue-corpus/opening.epd` -- its own file header reads "Opening (ply 1-15) -- fixed-seed
(20260713) **random-legal** self-play," i.e. exactly the "arbitrary random legal prefix" this
task's instruction rules out. `Performance.bin` is the only repo-native fixed, legal opening asset
that isn't self-labeled as synthetic random-legal data.

**Provenance, stated precisely -- no more than commit history actually supports**: `060ad2f`'s own
commit message says only "Bundle Performance.bin (92,954 positions) as classpath resource"; it
cites no external source, no generation method, and no game corpus it was derived from. No other
file in this repository (`dev-entries/`, `docs/`, issue history) documents where `Performance.bin`
originally came from. **This document does not claim it is "real, curated opening theory derived
from real game statistics"** -- that would be an assumption about Polyglot books in general, not a
documented fact about this specific file, and this task's instruction is explicit that only
documented provenance should be claimed. What is verifiable and stated here instead, all checked directly against the committed file: it
is a well-formed binary Polyglot book (16-byte fixed-width entries, `OpeningBook.java`'s own header
comment documents the exact layout; entries are sorted ascending by key, matching the binary-search
precondition `OpeningBook.bisectKeyLeft()` requires -- verified over the first 5,000 entries), it
has been present in the repository and in active use for real engine self-play/UCI move selection
since #106/#107, and its move weights are genuinely non-uniform: 700 distinct weight values across
the file (range 1-9,426), and 11,166 of its 77,872 keys carry more than one weighted candidate move
(e.g. one key has candidates weighted 117 and 14) -- ruling out a trivially-uniform or degenerate
file. This distinguishes it from a synthetically generated random-legal position list in exactly
the way that matters for this task (it encodes *some* real weighting structure, not per-position
uniform-random legal move choice), even though its ultimate upstream source is undocumented.

**File-level provenance, verified directly** (parsed the documented 16-byte big-endian entry
format `OpeningBook.java`'s own header comment specifies, read-only, no engine code modified):
1,487,264 bytes = 92,954 entries, 77,872 distinct Zobrist-keyed positions.

**Deterministic pool extraction** (exhaustive, not sampled -- verified by direct traversal against
the compiled `engine-core` classpath via `jshell`, reusing `Board`/`MovesGenerator`/`PolyglotKey`
exactly as `OpeningBook.probe()`/`decodeMove()` already do, no new decoder invented, no production
code modified): starting from the initial position, recursively expand **every** `weight > 0`
book entry at each reachable position (not a single weighted probe -- a complete enumeration of
every in-book branch) to a fixed depth of **6 plies (3 full moves per side)**, discarding any
branch that exhausts the book before reaching that depth.

| Quantity | Value |
|---|---|
| Prefix length | 6 plies (3 full moves), fixed |
| Total edges expanded (all branches, all depths) | 139 |
| Branches that ran out of book before depth 6 (rejected) | 4 |
| Root-to-leaf paths reaching depth 6 | 66 |
| **Unique terminal FENs after transposition dedup** | **58** |
| Terminal side-to-move | White in all 58 (mechanical: an even-length prefix from White's own turn always returns to White) |

**Terminal FEN semantics**: each pool entry is a full FEN (board, side-to-move, castling rights,
en-passant target, halfmove/fullmove counters) exactly as `Board.toFen()` emits it after 6
`makeMove` calls from the standard starting position -- not a move-list, not a partial state. A
generator arm starts its game directly from this FEN, matching `GameLoop`'s existing FEN-based
game-start capability (used already for corpus/regression fixtures elsewhere in the codebase, e.g.
`bench/nnue-corpus/*.epd`).

**Illegal/terminal-prefix rejection**: a branch is discarded at generation time (not retried,
not replaced) if either (a) the book has no `weight > 0` entry at the current position before
depth 6 is reached (4 such dead ends observed, disclosed above, matching this task's "reject,
don't silently repair" convention already established for E-15's degenerate-control disclosure),
or (b) the decoded book move fails to match any move in `MovesGenerator.getActiveMoves()`'s legal
set at that position (none observed in this traversal, but the check is applied identically to
every step, matching `OpeningBook.decodeMove()`'s own existing legality gate). No prefix in the
final 58-entry pool terminates in checkmate/stalemate before depth 6 (a terminal game state would
have produced an empty legal-move list at some intermediate depth, itself a rejection condition).

**Deterministic game-to-opening assignment -- identity mapping, no wraparound, no RNG**: pool
entries are indexed `0..57` in the traversal's own deterministic visitation order (a fixed DFS,
candidates at each node ordered by weight descending then raw move value ascending -- a total
order independent of incidental book-file iteration order, not merely "however the file happens to
store same-key entries"). Per section 3's hardened pairing rule (exactly 58 games/arm, one opening
each, no reuse), game `gameId` (0-indexed, both arms) is assigned pool entry `gameId` directly --
`pool[gameId]`, the identity permutation. Every one of the 58 openings is used exactly once per
arm, and control game `i`/treatment game `i` always start from the identical FEN. **No random
permutation, and no seed, is needed for this assignment** -- see section 4's removal of the
previously-proposed "opening-assignment seed."

**Materialized as a tracked, reviewable artifact, not hand-edited**:
`bench/nnue-corpus/e16-shared-opening-pool.txt` -- 58 lines, one full 6-field FEN per line, in the
exact traversal order above (line `i` is pool entry `i`). SHA-256:
`c8f02240f3ddbaf8043de0cf4b7fa3d96914cf91ed89f20e19ac8094566b2ce0`. Generated by
`E16OpeningPoolGenerator` (`engine-core/src/test/java/coeusyk/game/chess/core/selfplay/
E16OpeningPoolGenerator.java`), a one-time, excluded-from-the-default-suite JUnit test --
`@Tag("corpus-generation")` plus an `Assumptions.assumeTrue(Boolean.getBoolean("corpus.generate"))`
guard, the exact same convention `NnueCorpusGenerator` (issue #188) already established for
`bench/nnue-corpus/opening.epd` et al. Regenerate with:
```
mvn -pl engine-core test -Dtest=E16OpeningPoolGenerator -Dcorpus.generate=true
```
**Independently re-derived and verified byte-identical, not merely run once**: the generator was
run twice in separate invocations (the file deleted between runs, not merely overwritten) against
the same committed `Performance.bin`. Both runs reported identical statistics (`keys=77872
expanded_edges=139 dead_ends=4 leaves_total=66 unique_leaves=58`, matching this section's own
published counts exactly) and produced byte-identical output (`diff` empty, identical SHA-256
above both times). This confirms the extraction is a pure function of the committed book file, not
dependent on `HashMap` iteration order, JVM version, or any other incidental state.

**Color balance -- reconsidered, dropped**: the earlier draft of this document proposed mirroring
half the pool to cancel any single-sided structural bias a given book line might carry. Per this
task's explicit preference, that mitigation is **not adopted**: E-16's primary/decisive comparison
(section 6) is a *paired* treatment-control delta, computed separately for each opening index (via
the paired-by-training-seed design) -- if opening `i` happens to favor one side, **both** control
game `i` and treatment game `i` inherit the identical bias in the identical direction (same FEN,
same network, same search budget), so the bias cancels in the delta that actually matters. Mirroring
would only change the *pooled*, un-paired corpus-level distribution, which is not this experiment's
decisive metric, and it would add new, untested transform code and its own legality risk for no
demonstrated benefit to the estimand. **If future evidence shows the pooled/aggregate reading is
materially affected by per-opening side bias, a legality-preserving mirror could be added later as
a separately-evaluated change**, specified precisely enough now to avoid re-litigating it: rank
flip (rank 1<->8, 2<->7, ... -- vertical mirror only), piece-color swap (every White piece becomes
the corresponding Black piece and vice versa), side-to-move swap, castling rights remapped by color
only (`K`<->`k`, `Q`<->`q` -- kingside stays kingside, queenside stays queenside, since files are
never touched), en-passant target square's rank transformed by the same rank flip (file unchanged),
and **no file flip** (a file flip would not correspond to any real symmetry of a chess position,
since queenside/kingside castling and en-passant files are file-dependent, not rank-dependent).
Not implemented, not blocking Phase B.

## 3. Pairing -- hardened: exact game count, not an independent position-count stop

**Original (superseded) design**: each arm ran independently to `maxPositions=8000`, with openings
assigned by `gameId mod 58` (wraparound reuse) -- this does not guarantee every opening is used, or
used equally, if the two arms' games end up different lengths (exactly the kind of asymmetry E-15's
own control/treatment game-length mismatch demonstrated, 58 vs. 52 games for the same 8,000-position
target).

**Hardened design**: **exactly 58 complete games per arm** (`GeneratorConfig.maxGames = 58`,
`GeneratorConfig.maxPositions = null` -- the existing, unmodified stop-condition logic already
supports an unset `maxPositions`, `DR-E15-stage3-first-retraining-preregistration.md` section 7a's
own quoted loop only checks it "if `config.maxPositions() != null`"). Game `i` in both arms starts
from `pool[i]` (section 2's identity assignment) -- **every one of the 58 openings is used exactly
once per arm**, and control/treatment are paired at the opening level by construction, not by
chance alignment of independently-stopped runs. **The resulting Stage-3 position count is now an
emergent property of how long these 58 real games actually run, not a controlled target** -- section
5 gives an expected range, not a guarantee, and downstream row-budget equalization
(`DR-E15-stage3-first-retraining-preregistration.md` section 7's existing seeded-truncation
mechanism, unchanged) handles whatever count mismatch results between the two arms' *training-side*
pools after `split_by_game`, exactly as it did for E-15's own 7,280-vs-7,155 mismatch.

After the shared prefix, the two arms diverge exactly as E-15 specified and nothing else changes:

| | Control | Treatment |
|---|---|---|
| Selector after prefix | `BestMoveSelector(3)` (`--control-multipv 3`) | `SeededDiversitySelector(maxRank=3, cpLossBoundCentipawns=40, temperature=20.0)` -- `DR-E14`'s preregistered parameters, unchanged |
| multiPV | 3 | 3 |
| Search depth | 6 | identical |
| Max plies | 500 | identical |
| Network / engine build | E-15's frozen artifacts (section 1 provenance there), re-pinned at E-16 generation time | identical |
| Stopping rule | **exactly 58 complete games** (`maxGames=58`, `maxPositions=null`) | identical |
| Corpus size | ~8,000 positions/arm **expected**, not a stopping criterion (section 5) | identical |

## 4. Randomness sources -- separated, not conflated, all pinned now

**The previously-proposed "opening-assignment seed" is removed.** Section 3's hardened pairing
rule (`pool[gameId]`, identity mapping, no wraparound, no mirroring) contains no randomness at
all -- there is nothing for a seed to govern. Pinning a seed for a fully deterministic identity
function would have been exactly the kind of meaningless placeholder this task's instruction warns
against; it is deleted rather than kept as an unused constant.

**Three real seed roles remain, following `DR-E15` section 14's naming convention (`2026` + `16`
experiment ordinal + purpose ordinal) -- pinned now, before any generation, not deferred to Phase B
as the prior draft of this document proposed**:

| Purpose | Role | Value | Control | Treatment |
|---|---|---|---|---|
| Generation run seed | `GeneratorConfig`'s own run seed, passed to both arms identically (`DR-E15` section 14's own "matched-seed rationale": costs nothing since `BestMoveSelector` ignores it, keeps both arms structurally comparable) | **20261601** | ignored (`BestMoveSelector` unchanged, deterministic regardless of seed) | drives `SeedDerivation.derive(gameSeed, ply)` per-ply seeding for `SeededDiversitySelector`, unchanged mechanism from `DR-E14`/E-15 |
| Stage-3 grouped-split seed | Shared held-out game-ID selection (`select_held_out_game_ids(range(58), seed=..., held_out_count=6)`, section 4a below) -- same role as `DR-E15`'s `20261502`, mechanism hardened prior to Phase C execution | **20261602** | same value | same value |
| Row-budget-equalization seed | Seeded sub-selection truncating whichever arm's Stage-3 training pool is larger after `split_by_game`, same role as `DR-E15`'s `20261503` -- needed here because section 3's fixed-game-count design still produces two independently-sized training pools once real game lengths differ | **20261603** | applied only to whichever arm's pool is larger (unknown until generation; symmetric rule, `DR-E15` section 7, unchanged) | same |
| Training seeds | `TrainingConfig.seed` -- governs model init + data-order shuffle at training time only | **42, 43, 44** | all three, both arms | all three, both arms |

**Control remains fully deterministic after the shared prefix** -- `BestMoveSelector` ignores its
`seed` argument entirely (unchanged since E-15, `DR-E15` section 14), so control's *corpus content*
does not vary with the generation run seed at all; only the shared, fixed prefix (identical for
both arms, section 2/3) and the resulting rank-0 continuation determine it.

### 4a. Phase C split hardening -- pre-execution pairing correction, not a response to results

Found and fixed before Phase C was ever run, while reviewing how the pinned split seed `20261602`
would actually be consumed: the existing `trainer.dataset.split.split_by_game(records, seed, held_out_fraction)`
chooses held-out games by shuffling a *single arm's own* game-ID list and then accumulating whole
games until that arm's *own row count* crosses `held_out_fraction` of its *own total*. That's the
right rule for Stage 1/2 data (independent, unpaired rows), but wrong here: Phase B's own generation
report (`DR-E16-phase-b-generation-report.md` section 6) already shows control and treatment game
lengths diverge sharply for the *same* opening index (control min/median/max 43/128/483 plies,
treatment 61/112/383) -- the row count at which the accumulation loop stops differs by arm even
though the shuffle itself is seeded identically. Two independent `split_by_game(..., seed=20261602)`
calls, one per arm, are not guaranteed to stop after selecting the same game IDs, so the two arms
could silently end up with *different* held-out openings -- breaking the exact opening-level pairing
section 3 exists to guarantee, at the one point downstream (the held-out evaluation set) where it
matters most.

**Correction**: two new, additive functions in `trainer/trainer/dataset/split.py`. `split_by_game()`
itself is untouched -- every existing Stage 1/2 caller keeps its current behavior unchanged.

- `select_held_out_game_ids(game_ids, seed, held_out_count)` -- shuffles the *shared* game-ID
  universe (`range(58)`, identical for both arms since both ran the same 58 openings) exactly once
  with `seed=20261602` and takes the first `held_out_count=6` (~10% of 58) IDs. This is a pure
  function of the shared universe and the seed alone -- it has no notion of "rows" at all, so no
  arm's own row count can perturb which IDs are chosen. For this experiment's exact inputs it
  deterministically returns `{17, 20, 27, 31, 34, 51}`.
- `split_by_fixed_game_ids(records, held_out_game_ids)` -- partitions one arm's records using that
  *already-chosen* set, instead of computing its own selection. Called once per arm with the
  identical set from above. Every record sharing one `game_id` still lands entirely on one side
  (same no-leakage guarantee as `split_by_game()`), and raises `ValueError` if a supplied held-out ID
  isn't present in that arm's own records (a missing/mistyped ID fails loudly rather than silently
  holding out nothing for it) or if any record lacks `metadata.game_id`.

**Resulting contract**: both arms hold out the identical 6 opening IDs (`{17, 20, 27, 31, 34, 51}`)
and train on the identical 52 remaining opening IDs -- membership is now guaranteed by construction,
not by chance alignment of two independent shuffles. Held-out/training *row counts* are explicitly
not forced equal at this step (control and treatment will have different row counts for the same 6
held-out games, exactly as Phase B's length data predicts) -- that asymmetry is left alone here and
handled entirely by the existing, unchanged row-budget-equalization step (seed `20261603`,
training-side only, section 4 table above).

**This is a pre-Phase-C correction, found by inspecting the split mechanism before running it against
the real corpora -- no ingestion, split, or equalization of the real E-16 data had occurred yet, and
none of Phase C's pinned seeds (`20261601` generation, `20261602` split, `20261603` equalization) or
training seeds (`42`/`43`/`44`) changed as a result.** Nothing here responds to a training or
evaluation outcome, because no training or evaluation had happened yet at either arm.

Tests: `trainer/tests/dataset/test_split_by_game.py` -- determinism of `select_held_out_game_ids`
for a fixed seed, rejection when `held_out_count` exceeds the universe, identical game-ID partition
applied to two synthetic corpora with the same 58 game IDs but wildly different per-game row counts
(200 rows/game vs. `(gid % 7) + 1` rows/game, mirroring Phase B's real control/treatment length
asymmetry) confirming both arms land on `{17, 20, 27, 31, 34, 51}` with differing row counts and no
leakage, row-order preservation, and the two "fails loudly" cases (unmatched held-out ID, missing
`game_id`). 19/19 tests pass (12 pre-existing `split_by_game` tests unchanged plus 7 new).

**Corpus generation happens once per arm, not once per training seed.** Each arm's Stage-3 corpus
is generated a single time (frozen, hashed, and pinned, exactly as `DR-E15-phase-bc-corpus-
generation-report.md` section 13 did for E-15's artifacts), then that one frozen corpus is
concatenated with the unchanged 36,000-record base set and trained **three times**, once per
training seed (42, 43, 44), for each arm. **This measures training-seed variance only -- it does
not measure corpus-generation variance** (a second corpus-generation run with a different
generation seed, producing a materially different Stage-3 dataset, is explicitly out of scope for
E-16; that would be a separate, later experiment with its own ID, per this project's "declared, not
tuned post-hoc" convention).

## 5. Resource budget -- re-derived for the 58-game stopping rule, not reused blindly

**Per-game generation rate, from E-15's own measured timings**
(`DR-E15-phase-bc-corpus-generation-report.md` section 2/3): control 3,047s / 58 games = **52.5
s/game**; treatment 3,899s / 52 games = **75.0 s/game**. These two rates diverged in E-15 mostly
*because* control's degenerate single-trajectory collapse (`DR-E15-phase-bc-corpus-generation-
report.md` section 12) made every control game an identical, comparatively short 140-ply line --
an artifact of the confound this document exists to remove, not a property of `BestMoveSelector`
itself. **E-16's control will no longer collapse this way** (58 distinct real book openings, not
one repeated line), so E-15's own 52.5 s/game control rate is not a safe estimate here -- it
understates what a real, varied-length control game costs. **This document uses treatment's own
75.0 s/game rate as the better-justified proxy for both arms**, since both now start from real,
distinct positions and diverge only in selector policy after an identical prefix, with no
structural reason to expect one arm's average game length to differ sharply from the other's the
way E-15's degenerate control did. This is stated as an estimate with real uncertainty, not a
measured fact for E-16 specifically -- the actual rate will only be known once Phase B generates.

| | Control | Treatment |
|---|---|---|
| Games (hardened, section 3) | 58 | 58 |
| Estimated per-game rate | 75.0 s/game (proxy, see above) | 75.0 s/game (E-15's own measured rate, unchanged mechanism) |
| Estimated generation wall-clock | 58 x 75.0s ~= 4,350s ~= **72.5 min** | 58 x 75.0s ~= 4,350s ~= **72.5 min** |

Both estimates sit under the 90-minute/arm cap (`DR-E15-stage3-first-retraining-preregistration.md`
section 6) with ~17.5 minutes of headroom -- tighter than E-15's own margin (E-15's treatment arm
used 65.0 of its 90 minutes), since 58 games at treatment's real per-game rate costs more than
either arm's E-15 run did. **If actual generation time materially exceeds this estimate**, section
6's resource cap requires the same response E-15's own preregistration specifies for its own
projection (`DR-E15` section 6): re-scope explicitly, do not silently let a run continue past the
cap. `GeneratorConfig.maxGames=58` is a hard, fail-closed ceiling regardless of timing (no game
count above 58 is possible even if per-game cost is far off from this estimate).

**Expected corpus size -- an outcome, not a target** (section 3): using treatment's own observed
~154.7 samples/game (8,046 samples / 52 games, `DR-E15-phase-bc-corpus-generation-report.md`
section 10) as the same proxy rate for 58 games in both arms: 58 x 154.7 ~= **~8,970 positions/arm
expected** -- close to, but not pinned at, E-15's original 8,000/arm figure. **Corpus size is not
enlarged to hit a round number**: this is what 58 real games are expected to produce, not a chosen
target, consistent with this task's explicit "keep ~8k as an expectation, not the stopping
criterion" instruction. The actual count, once generated, is whatever it is.

**Training (6 runs: 2 arms x 3 seeds)**: `DR-E15-phase-d-training-report.md` section 4/5 measured
142.7s (control) and 153.2s (treatment) per training run at this exact schedule and a similarly
sized combined record set (43,155 = 36,000 base + ~7,155 equalized Stage-3 -- E-16's own combined
count will differ slightly given the ~8,970-per-arm expectation above, but training wall-clock is
governed by `steps=20000` at a fixed `batch_size=256`, not dataset size, so this rate transfers).
**Estimated training budget: 6 x ~150s ~= 900s ~= 15 minutes total.**

**Total estimated wall-clock for E-16 execution (generation + all 6 training runs): ~72.5 x 2 +
15 ~= ~160 minutes (~2h40m)** -- revised upward from the prior draft's ~131-minute estimate, since
that draft incorrectly reused E-15's own degenerate-control generation rate for the hardened
design's non-degenerate control. Still a single working session's worth of compute, no
infrastructure change needed.

## 6. Success/null/regression interpretation

Reuses the unmodified Measurement Model (`measurement-model.md` sections 1/1a/6/7/9) exactly as
E-15 did -- no new metric, no changed semantics. **What changes from E-15's interpretation is the
variability estimate used to judge a delta, not the metrics themselves**: this experiment uses
`DR-M1-cp-only-noise-floor-characterization.md`'s configuration-matched cp-only and pooled
same-training-seed spread (cp-only: ~0.001-0.008; pooled: stdev 0.0019-0.0030) in place of the
stale, non-transferable 0.0004 pooled figure `measurement-model.md` section 1 cites from a
different configuration (K=2.149428, n=2) -- per `DR-M1` section 3.3's own explicit caution against
reusing that figure outside the configuration it was measured under.

**Paired-by-training-seed comparison** (the design this task requires, exploiting the fact that
both arms now train under the identical training-seed set):

| Pair | Comparison |
|---|---|
| seed 42 | treatment(seed 42) - control(seed 42) |
| seed 43 | treatment(seed 43) - control(seed 43) |
| seed 44 | treatment(seed 44) - control(seed 44) |

For cp-only correlation (primary/decisive) and pooled correlation (screening), report per pair:

- the three paired deltas themselves (not just a pooled average across all six runs, which would
  discard the pairing structure the matched-training-seed design exists to provide)
- **mean paired delta** across the three pairs
- **spread** (min/max, or stdev) of the three paired deltas
- **sign consistency**: how many of the three pairs agree in direction

**No significance threshold is invented from n=3.** Per this task's explicit instruction and
`measurement-model.md` section 12's own established discipline (E-15's own null classification
was reached without inventing a threshold), this experiment states the paired deltas' relationship
to `DR-M1`'s measured noise band descriptively: whether the mean paired delta and its sign
consistency look large and consistent relative to that noise band, or small and noise-dominated --
not a p-value, not a sigma-multiple treated as decisive (the same caution `phase4-p4i-
replication.md` section 6 applied to its own n=2 estimate applies with equal force to an n=3 paired
design here). RMSE and calibration are reported as regression guards under the same shared rubric
E-15 used (no target-definition change in E-16 either).

**This interpretation rule does not reopen or reclassify E-15.** E-15's B (null/inconclusive)
classification, reached under its own preregistered section 12 rule, stands unchanged regardless
of what E-16 eventually finds -- E-16 is a new experiment answering a narrower, different estimand
(section 1), not a re-analysis of E-15's own data.

## 7. Explicit non-scope (per this task's instruction)

- **No SPRT in E-16.** Entirely an offline training-data experiment, same boundary as E-15
  (`DR-E15` section 13).
- **No selector retuning.** `SeededDiversitySelector`'s parameters (`maxRank=3,
  cpLossBoundCentipawns=40, temperature=20.0`) are `DR-E14`'s preregistered values, reused
  unmodified -- not reopened, not re-tuned, regardless of E-16's own eventual result.
- **No architecture, schedule, or `wdl_lambda` change.** `NnueNet(256, 127, 64, 400)`, P3A-001's
  frozen schedule (`steps=20000, lr=0.01, cosine, warmup=200, batch=256, k=2.773456`), and
  `wdl_lambda=1.0` (`#208` stays closed) are all unchanged from E-15.
- **No corpus generation yet.** Phase B's prerequisite implementation (the `--start-fen` and
  `--start-fen-file` seams, sections 8-10) is now closed, but **no self-play corpus has been
  generated** -- no VSPR file, no ingestion, no decision record for either E-16 arm exists. Actual
  generation is a separate, explicitly-authorized step, same phase-gating convention `DR-E15` used
  at every one of its own phase boundaries.
- **E-15's classification remains untouched.** B (null/inconclusive), `DR-E15-phase-e-evaluation-
  report.md` section 6, is not revised by this document or by any future E-16 result.

## 8. Phase B prerequisites -- closed

**Corrected from the prior draft**: that draft closed `--start-fen` and called Phase B unblocked,
but `--start-fen` applies exactly one FEN to *every* game in a single invocation -- it cannot
express "game `i` uses `pool[i]`" for 58 distinct openings within one arm's own run. That gap is
what this pass actually closes, via a second, additive flag.

- **`SelfPlayCli --start-fen`**: an optional flag, added additively. Absent means #221's original
  default-start behavior, byte-identical (verified by test: the default-start VSPR's first sample
  FEN still equals `new Board().toFen()`). When given, the FEN is validated once, eagerly, at
  `CliArgs.parse()` time -- before `EligibilitySmoke`, before the network loads, before any game is
  attempted -- so a malformed FEN fails loudly before generation, not partway through the first
  game (verified by test: an invalid `--start-fen` throws `IllegalArgumentException` at parse time
  and writes neither a VSPR nor a decision-record file). Per game, the FEN is parsed fresh into a
  new `Board` (a `Board` is mutated in place by `makeMove`, so one instance cannot be shared across
  games) and handed to `GameLoop`'s **already-existing** `playGame(gameId, gameSeed, Board)`
  overload -- the same entry point `GameLoopTest`'s own terminal-state fixtures
  (`checkmatePositionTerminatesImmediatelyWithCorrectWinner` et al.) have used since #221/#222.
  **No change was made to `GameLoop`, `Board`, or `Searcher`** -- this is a CLI-level seam onto a
  seam that already existed.
- **The `maxGames=58` / `maxPositions=null` combination**: proven by a new regression test
  (`SelfPlayCliTest.exactlyMaxGamesCompleteWhenMaxPositionsIsUnset`) -- exactly 58 complete games
  are emitted with `maxPositions` left unset (not just set to a large value), confirming no
  position-budget stop is involved at all in this configuration.
- **`SelfPlayCli --start-fen-file <path>`** (this pass): an optional flag, mutually exclusive with
  `--start-fen` (one starting-position mode per run, same discipline `--control-multipv`/
  `--diversity-*` already established -- never inferred implicitly from which flags happen to be
  present). The file is read and validated **eagerly, in full, before any generation** -- every
  non-empty line must parse as a legal-shaped FEN (`new Board(line)`, the same check `--start-fen`
  itself uses, applied per line with its line number in the error message), and the schedule must
  have at least `--max-games` entries, or `CliArgs.parse()` throws before `EligibilitySmoke`, the
  network load, or any game starts. Game `gameId` uses schedule line `gameId` (0-indexed, blank
  lines skipped and never counted as an entry) -- `GameLoop`'s existing
  `playGame(gameId, gameSeed, Board)` overload is called with a fresh `Board` parsed from that
  line, exactly the same seam `--start-fen` itself uses. **`gameId`/`gameSeed` derivation
  (`config.seed() + gameId`) is completely untouched** -- neither flag changes which game gets
  which id or seed, only which position it starts from. No change to `GameLoop`, `Board`,
  `Searcher`, VSPR encoding, or selector logic.

**Verified, not merely implemented**: default-start behavior unchanged; a supplied single FEN is
exactly the first searched position with every FEN field (side-to-move, partial per-color castling
rights, en-passant target, halfmove clock, fullmove number) surviving intact; natural termination
(checkmate) still fires correctly from a supplied non-default starting position, reached through
the CLI, not just `GameLoop` directly; a 3-entry schedule with `--max-games 3` puts opening `i` on
game `i`'s first sample for all three games, with `gameId`s remaining `0, 1, 2` in the one emitted
VSPR file; a control-arm invocation and a treatment-arm invocation consuming the **same schedule
file** produce identical first-sample FENs at every game index; an invalid FEN anywhere in the
schedule (not just line 0) fails before generation; a schedule with fewer entries than `--max-games`
fails before generation; `--start-fen` and `--start-fen-file` together are rejected; and every
`--start-fen`/default-start test from the prior pass remains green. Full detail:
`engine-core/src/test/java/coeusyk/game/chess/core/selfplay/SelfPlayCliTest.java`'s `--start-fen`/
`--start-fen-file` test groups (24 tests total in that class now; engine-core+engine-uci full
suite: 386+36=422 passed, 0 failures, unchanged skip counts, `BUILD SUCCESS`).

## 9. E-16 execution contract (Phase B, once authorized -- not started by this document)

With both the schedule mechanism and the 58-entry pool artifact in place, one arm's corpus
generation is exactly one `SelfPlayCli` invocation:

- **One control invocation**: 58 games, one VSPR file, one `runId`, `--control-multipv 3`,
  `--start-fen-file bench/nnue-corpus/e16-shared-opening-pool.txt`, `--seed 20261601`.
- **One treatment invocation**: 58 games, one VSPR file, one `runId`,
  `--diversity-max-rank 3 --diversity-cp-loss-bound 40 --diversity-temperature 20.0`,
  `--start-fen-file bench/nnue-corpus/e16-shared-opening-pool.txt` (the **same** file, same SHA-256
  `c8f02240f3ddbaf8043de0cf4b7fa3d96914cf91ed89f20e19ac8094566b2ce0`), `--seed 20261601`.
- Both invocations pass `--max-games 58`, no `--max-positions` (section 3's hardened stop rule).
- Game `i` in both invocations starts from the identical `pool[i]` line (section 2/8's schedule
  mechanism) -- pairing is enforced by construction, not by post-hoc alignment.
- Downstream (Phase C, hardened -- section 4a): `select_held_out_game_ids(range(58), seed=20261602,
  held_out_count=6)` computed once against the shared game-ID universe, then
  `split_by_fixed_game_ids(records, held_out_game_ids)` applied to each arm with that identical set
  (`{17, 20, 27, 31, 34, 51}`), then the seeded row-budget equalization (`seed=20261603`) on
  whichever arm's training pool is larger, exactly as section 4 pins -- seed values unchanged by
  either pass, only the split mechanism itself was hardened.

**No corpus has been generated by this document.** This section states the contract Phase B
executes under, once explicitly authorized -- it does not run it.

## 10. Phase B readiness -- genuinely unblocked

The shared-opening pool (58 unique terminal FENs, materialized and hash-pinned, section 2), all
three real generation/split/equalization seeds (section 4, re-verified unchanged), and now both
halves of the starting-position seam (`--start-fen` for a single fixed position, `--start-fen-file`
for the per-game schedule E-16's own pairing rule actually requires) are implemented and tested.
**No implementation gap remains for Phase B corpus generation.** Actual generation is a separate,
later, explicitly-authorized step -- not started by this document, per this project's phase-gating
convention (`DR-E15` section 16's own precedent).
