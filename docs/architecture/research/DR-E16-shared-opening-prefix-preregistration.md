# E-16 design record: shared-opening-prefix Stage-3 retrain (preregistration)

**Status: design/preregistration only. No corpus generation, no training, no SPRT executed by
this document.** Governs issue #224, "[P15] E-16 -- Stage-3 shared-opening-prefix retrain (fixes
E-15's degenerate control)." Downstream of #223 (E-15, closed, `DR-E15-stage3-first-retraining-
preregistration.md` and its Phase B/C/D/E reports) and `DR-M1-cp-only-noise-floor-characterization.md`
(the noise-floor study this document's interpretation rule is built on). Reuses `DR-E14-seeded-
diversity-audit-and-preregistration.md`'s treatment-selector parameters unchanged.

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
used for genuine UCI play (`UciApplication`'s `openingBook` field). This is real, curated opening
theory (Polyglot books encode move weights derived from real game statistics), not synthetic data.
**Explicitly not used**: `bench/nnue-corpus/opening.epd` -- its own file header reads "Opening
(ply 1-15) -- fixed-seed (20260713) **random-legal** self-play," i.e. exactly the "arbitrary random
legal prefix" this task's instruction rules out. `Performance.bin` is the only repo-native
fixed/legal/balanced-provenance opening asset that qualifies.

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

**Deterministic game-to-opening assignment**: pool entries are indexed `0..57` in the traversal's
own deterministic visitation order (a fixed DFS over `weight`-sorted candidates at each node --
reproducible from the same book file with no RNG in the extraction step itself). Game `gameId`
(0-indexed, both arms, matching `SelfPlayCli`'s existing sequential `gameId` convention) is
assigned pool entry `gameId mod 58`. **Control and treatment use the identical assignment
function against the identical pool**, so control game `i` and treatment game `i` always start
from the exact same opening FEN -- the pairing this task requires.

**Color balance**: because every pool terminal FEN has White to move (an artifact of the even
prefix length, not a deliberate choice), the *side to move next* is not a balancing lever here --
the balancing concern is instead that an individual opening line drawn from a real, unequally-
weighted book can itself be structurally favorable to one side, and repeating that line 8,000/58
~= 138 times (both arms drawing from the same 58-entry pool with wraparound reuse) would bake any
such asymmetry into the corpus twice, once per arm, in the same direction. **Mitigation,
preregistered now, implemented in E-16 Phase A**: for odd-indexed pool entries (29 of the 58), the
assigned FEN is replaced by its color-and-square-mirrored equivalent (ranks flipped, piece colors
swapped, side-to-move flipped) before either arm's game starts -- a small, additive utility
(new code, not yet written) applied identically and deterministically to both arms from the same
58-entry base pool, so structural opening asymmetry is split evenly across the corpus rather than
appearing only from one side. This mirrors, rather than discards, book-derived lines -- it does not
touch `Performance.bin` itself or `OpeningBook.java`.

## 3. Pairing

Control game `i` and treatment game `i` (`i` = 0..(gamesAttempted-1), both arms) start from
`pool[i mod 58]` (mirrored per section 2's rule), computed identically for both arms from the
identical assignment function -- no per-arm randomization of which opening a given `gameId` gets.

After the shared prefix, the two arms diverge exactly as E-15 specified and nothing else changes:

| | Control | Treatment |
|---|---|---|
| Selector after prefix | `BestMoveSelector(3)` (`--control-multipv 3`) | `SeededDiversitySelector(maxRank=3, cpLossBoundCentipawns=40, temperature=20.0)` -- `DR-E14`'s preregistered parameters, unchanged |
| multiPV | 3 | 3 |
| Search depth | 6 | identical |
| Max plies | 500 | identical |
| Network / engine build | E-15's frozen artifacts (section 1 provenance there), re-pinned at E-16 generation time | identical |
| Corpus size target | 8,000 positions/arm | identical |

## 4. Randomness sources -- separated, not conflated

Four independent seed roles, each pinned to its own named constant (no formula-derived values,
per this project's `explicit named constants` convention, `DR-E15` section 14):

| Purpose | Role | Control | Treatment |
|---|---|---|---|
| **Opening-assignment seed** | Governs which of the 58 pool entries a given `gameId` gets mirrored/not (the odd/even mirror rule, section 2) -- deterministic function of `gameId`, but the *mirror decision* itself is pinned to one named seed value so a future revision could change the balancing rule without silently changing which lines are used | shared, one value | identical (same value -- both arms must see the same mirroring, or pairing breaks) |
| **Treatment selector-generation seed** | `SeededDiversitySelector`'s own generation-run seed, exactly as `DR-E15` section 14's `Generation run seed` role (20261501 there) -- a new, distinct value for E-16, not reused from E-15, since this is a new corpus identity | n/a (control ignores its seed argument entirely, `BestMoveSelector` unchanged, `DR-E15` section 14's own finding) | one pinned value, new for E-16 |
| **Stage-3 grouped-split seed** | `split_by_game`'s held-out split, same role as `DR-E15` section 14's `20261502` | new pinned value | same value |
| **Training seeds** | `TrainingConfig.seed` -- governs model init + data-order shuffle at training time only | **42, 43, 44** (both arms, per this task's explicit instruction) | **42, 43, 44** (both arms, identical set) |

**Control remains fully deterministic after the shared prefix** -- `BestMoveSelector` ignores its
`seed` argument entirely (unchanged since E-15, `DR-E15` section 14), so control's *corpus content*
does not vary with the opening-assignment or treatment-selector seeds at all; only the shared
prefix (identical for both arms) and the resulting rank-0 continuation determine it.

**Corpus generation happens once per arm, not once per training seed.** Each arm's Stage-3 corpus
is generated a single time (frozen, hashed, and pinned, exactly as `DR-E15-phase-bc-corpus-
generation-report.md` section 13 did for E-15's artifacts), then that one frozen corpus is
concatenated with the unchanged 36,000-record base set and trained **three times**, once per
training seed (42, 43, 44), for each arm. **This measures training-seed variance only -- it does
not measure corpus-generation variance** (a second corpus-generation run with a different
generation seed, producing a materially different Stage-3 dataset, is explicitly out of scope for
E-16; that would be a separate, later experiment with its own ID, per this project's "declared, not
tuned post-hoc" convention).

## 5. Resource budget

**Generation (one-time per arm, not per training seed)**: E-15's own measured generation
wall-clock at matched search depth/multiPV/corpus size (`DR-E15-phase-bc-corpus-generation-report.md`
section 2/3) was 3,047s (control, ~50.8 min) and 3,899s (treatment, ~65.0 min). The shared 6-ply
prefix adds a fixed, small per-game overhead (6 extra searched plies at depth 6's own measured
~394ms/position, `DR-E15-stage3-first-retraining-preregistration.md` section 5 -- roughly 2.4s of
extra search per game, negligible against per-game wall-clock dominated by depth-6 search over the
game's full length). **Estimated generation budget: ~51-65 minutes per arm, ~116 minutes total for
both arms, one time** -- unchanged in order of magnitude from E-15, since neither corpus size,
search depth, nor multiPV changed.

**Training (6 runs: 2 arms x 3 seeds)**: `DR-E15-phase-d-training-report.md` section 4/5 measured
142.7s (control) and 153.2s (treatment) per training run at this exact schedule and combined
record count (43,155 = 36,000 base + ~7,155 equalized Stage-3). **Estimated training budget: 6 x
~150s ~= 900s ~= 15 minutes total.**

**Total estimated wall-clock for E-16 execution (generation + all 6 training runs): ~131 minutes**,
comfortably inside a single working session, no infrastructure change needed.

**Corpus size stays at 8,000 positions/arm, unchanged from E-15.** Per this task's explicit
instruction, this is not enlarged merely because E-15 was inconclusive -- no evidence gathered so
far (E-15's own result, or `DR-M1`'s noise-floor measurement) implicates corpus size as the
limiting factor; the design defect it identified was the control's degenerate opening distribution,
which section 2's shared-prefix mechanism fixes directly, at the same corpus size.

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
- **No corpus generation yet.** This document is Phase A (design/preregistration) only. Phase B
  (shared-prefix generator implementation: the new mirrored-pool utility and a `SelfPlayCli`
  flag to start a game from a given FEN, both additive, mirroring `DR-E15`'s own Phase A pattern
  for `BestMoveSelector`'s multiPV override) has not started.
- **E-15's classification remains untouched.** B (null/inconclusive), `DR-E15-phase-e-evaluation-
  report.md` section 6, is not revised by this document or by any future E-16 result.

## 8. Remaining blockers before Phase B can start

- **`SelfPlayCli` has no flag to start a game from an explicit FEN today** (verified directly:
  no `--start-fen`/`--opening`-style flag exists in `SelfPlayCli.CliArgs`, confirmed by the same
  grep-based check this document's section 2 traversal relied on). A small, additive flag
  (mirroring `--control-multipv`'s own additive-only precedent, `DR-E15` section 2) is required
  before Phase B can generate any game from a non-default starting position. Not implemented by
  this document.
- **The odd/even mirror utility (section 2's color-balancing mechanism) does not exist yet.** A
  small, new, additive board-mirror function (rank/file flip + color swap + side-to-move flip on a
  FEN) is required before the 58-entry pool can be mirrored per the balancing rule. Not implemented
  by this document.
- **The opening-assignment seed, treatment selector-generation seed, and Stage-3 split seed
  (section 4) are named roles, not yet assigned numeric values** -- pinned at the start of Phase B,
  following `DR-E15` section 14's own naming convention (year + experiment ordinal + purpose
  ordinal), not before, since assigning them now (before Phase B's implementation is reviewed)
  would risk the same kind of placeholder-then-forgotten gap `DR-E15` section 18 had to explicitly
  resolve for its own seeds.
- **No other blocker identified.** The shared-opening pool itself (58 unique terminal FENs, section
  2) is fully computed and reproducible from `Performance.bin` alone -- re-running the same
  traversal against the same, already-committed book file will reproduce the identical pool with no
  further dependency.
