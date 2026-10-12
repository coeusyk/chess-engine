# Seeded self-play diversity: candidate-gap audit and preregistration (E-14 design record)

**Status:** audit complete, mechanism implemented, preregistration pinned before the pilot ran.
Governs issue #222. Downstream of `DR-E9-stage3-game-search-label-contract.md` (candidate-set and
randomized-selection contract, section 7) and `DR-E12-stage3-generator-selection.md` (bootstrap
generator identity). Does not modify VSPR V1, shard V1, or #210 ingestion.

Baseline verified against commit `f1b4f9d` on `phase/15-nnue`.

## 1. Why this document exists

#221 proved the Stage-3 generation plumbing end to end, but its only `MoveSelector`
(`BestMoveSelector`) is deterministic rank-0-always, so its first pilot produced 3/3
byte-identical games. #222 asks for exactly one preregistered diversity mechanism, chosen from
evidence about the generator's own candidate geometry rather than invented constants. Section 2
is that evidence; section 3 is the mechanism it motivates; section 4 is the preregistration
pinned before the second pilot ran (section 8 reports what that pilot actually produced).

## 2. Stage A: candidate-gap audit

### 2.1 Method

Replayed the exact #221 bootstrap trajectory (network `P3A-001`,
`sha256 8dc03caa0429bf03ca92165fd4de95ad9550d57796ba9a2f45aecfa4e05bb02d`, search depth 4, max
plies 500, deterministic rank-0 selection), but with `Searcher.setMultiPV(3)` so rank-2 and
rank-3 candidates could be observed alongside rank-1. Move selection always played rank-0 --
Stage A never alters what move is played, only what is recorded.

**A real implementation gap surfaced immediately and changed the harness.** #221's
`CompletedRootCandidateAdapter`, sized to `expectedCandidateCount`, only ever publishes a
candidate set when the buffer reaches that *exact* count. At multiPV=3, any position with fewer
than 3 legal moves (common in check, endgames, and forced lines) never reaches 3 callbacks and
the adapter simply never publishes -- silently dropping exactly the low-candidate-count plies
this audit most needed to observe. The audit harness used its own listener that flushes whatever
a depth actually produced (not aborted, any count 1-3) instead. Section 6 below carries this
same fix into production `CompletedRootCandidateAdapter`, since #222 is the first caller ever to
request multiPV > 1 (#221's multiPV=1 usage could never trigger the bug -- every non-terminal
position has at least one legal move).

**Caveat on trajectory identity**: requesting multiPV=3 (to see rank-2/3) causes `Searcher`'s
transposition table to be populated by the extra per-ply re-searches at pvIndex 1-2, which can
occasionally change which move ties are broken toward at rank-0 in later plies. The audited game
diverged from #221's original 294-ply, fifty-move-rule-draw trajectory into a 174-ply forced-mate
finish. This does not undermine the audit -- the question Stage A answers is about candidate-gap
*geometry* (how close are the alternatives to the best move, how often is there only one
candidate, how often is the position a forced mate), not about reproducing #221's exact game --
but it is why the numbers below describe one 174-ply game, not the original 294-ply one.

### 2.2 Results (174 plies, single game, one trajectory)

| Metric | Value |
|---|---|
| Candidate count: 1 (forced/single legal reply) | 7 plies (4.0%) |
| Candidate count: 3 (full multiPV width reached) | 167 plies (96.0%) |
| Candidate count: 2 | 0 plies (never observed in this trajectory) |
| Rank-1 is a mate score | 4 plies (2.3%) -- 2 winning, 2 losing, all in the last 5 plies |
| Rank-1/rank-2 CP gap: n | 165 (excludes single-candidate and mate-rank-1 plies) |
| min / median / p75 / p90 / p95 / max | -33 / 14 / 31 / 75 / 107 / 463 |
| Rank-1/rank-3 CP gap: n | 165 |
| min / median / p75 / p90 / p95 / max | -171 / 25 / 48 / 96 / 174 / 556 |
| Rank1/rank2/rank3 kind-mixing (any pair CP vs. mate) | 2 plies (1.1%) |

Early/mid/late-third breakdown (58 plies each): single-candidate and mate-score plies were
entirely absent from the early and mid thirds and concentrated in the late third (12% and 7% of
the late third respectively) -- consistent with forced lines and mating sequences appearing near
game endings, not as a general property of the position.

**A second finding, load-bearing for the mechanism's eligibility rule**: rank-2 (and, more
rarely, rank-3) sometimes scored *better* than rank-1 -- the negative end of the gap range above
(min -33cp for rank1/2, min -171cp for rank1/3). This is an ordinary MultiPV artifact (each
`pvIndex` re-searches with the previous best move excluded, under a slightly different
alpha-beta window, and ties/near-ties do not always resolve the same way across pvIndex), not a
data-quality defect. It means an eligibility rule based on raw rank order alone is not safe --
"rank <= N" must be paired with an actual score-loss bound, and that bound must be clamped at
zero rather than assuming loss is always non-negative.

### 2.3 What this audit rules in and out

- Candidate counts are usually 3 (the ceiling this audit requested) but meaningfully collapse to
  1 in forced/near-forced positions (4% here, concentrated late-game) -- any selector must handle
  the single-candidate case as a first-class deterministic path, not an edge case.
- Score gaps between rank-1 and its alternatives are usually small (median 14cp for rank-2,
  25cp for rank-3) with a long right tail (p95 ~107-174cp, max into the hundreds) -- a fixed
  small CP-loss bound will admit a real, near-equal alternative on a majority of ordinary
  positions while excluding the small fraction of much-worse moves in the tail.
  A bounded, evidence-anchored CP-loss cutoff is well supported; an unbounded or very large
  cutoff is not.
- Mate incidence is low (2.3% here) but real and concentrated late-game -- any mechanism must
  define mate handling explicitly rather than leaving it as an implicit edge case.
- Root-candidate diversity is clearly present (96% of plies have a real, non-forced alternative
  to sample among) -- #222's non-scope fallback ("no root-candidate diversity possible, file a
  separate opening-book design problem") does not apply. The preferred family (bounded
  stochastic sampling among near-best candidates) is directly supported by this audit and is
  adopted without modification in section 3.

## 3. Stage B: selected diversity mechanism

**Bounded stochastic sampling among near-best completed root candidates**, implemented as
`SeededDiversitySelector` (`engine-core/.../selfplay/SeededDiversitySelector.java`), the one new
`MoveSelector` alongside the unmodified `BestMoveSelector` control.

### 3.1 Eligible-set rule

For candidate ranks 2..`maxRank` (rank-1 is always eligible):

- same score kind (CP vs. MATE) as rank-1. The audit found kind-mixing at 1.1% of plies and
  there is no defined CP-equivalent for a mate-distance loss (#222 section 7 forbids inventing
  one).
- if rank-1 itself is a mate score (winning **or** losing): no diversity at all --
  deterministic rank-1. See section 5.
- single-candidate plies: deterministic (nothing to sample from).
- CP loss versus rank-1, **clamped to >= 0** (the audit's negative-gap finding, section 2.2) --
  must not exceed `cpLossBoundCentipawns`.

### 3.2 Weighting

`weight(candidate) = exp(-max(0, rank1.score - candidate.score) / temperature)` -- rank-1 always
has weight 1; weight decays smoothly with score loss (softmax-style). Selection draws
`Uniform(0, sum(weights))` from a seeded `Random` and picks the first candidate whose cumulative
weight covers the draw.

### 3.3 Parameters, tied to the audit (section 2.2), not invented

| Parameter | Value | Rationale |
|---|---|---|
| `maxRank` | 3 | Matches the audit's own multiPV width; the audit found candidate counts saturate at whatever width is requested (96% of plies reached the full 3), so there is no evidence a wider window changes the picture, and a wider window would cost more search time per ply for no observed benefit. |
| `cpLossBoundCentipawns` | 40 | Between the audit's rank1/rank2 p75 (31cp) and rank1/rank3 p75 (48cp) gaps -- admits a real alternative on a clear majority of ordinary positions (roughly 75%) while excluding the long right tail (p90+ is 75-174cp) of much-worse moves. Rounded to 40 for a readable value between the two p75s rather than picking one arbitrarily. |
| `temperature` | 20.0 | Between the audit's rank1/rank2 median gap (14cp) and rank1/rank3 median gap (25cp) -- a candidate at the median gap gets `exp(-14/20) ≈ 0.50` to `exp(-25/20) ≈ 0.29` relative weight: a meaningful but not overwhelming chance of being chosen, so a typical near-tie alternative is genuinely reachable without swamping rank-1's own weight of 1.0. |

These three numbers are the ones recorded in preregistration (section 4) and were not changed
after seeing the pilot's output (section 8).

## 4. Preregistration (pinned before the second pilot ran)

Recorded here, in this commit, before the pilot in section 8 executed. Not altered afterward.

- **Selector**: `SeededDiversitySelector`, `mechanismName = "seeded-diversity-v1"`.
- **Eligible-set rule**: section 3.1, exactly as implemented (`engine-core/.../selfplay/SeededDiversitySelector.java`).
- **Max rank**: 3.
- **CP-loss threshold**: 40 centipawns, clamped loss >= 0.
- **Weighting formula**: `exp(-max(0, loss)/temperature)`, temperature = 20.0.
- **Mate handling**: rank-1 mate (winning or losing) => deterministic rank-1, no diversity (section 5).
- **Single-candidate handling**: deterministic, that candidate (section 5).
- **Seed derivation**: `SeedDerivation.derive(gameSeed, ply)` -- SplitMix64-style two-step mix of
  `gameSeed` (`runSeed + gameId`, unchanged from #221) and `ply`; see section 6 for why this
  replaced #221's original `gameSeed + ply`.
- **Pilot generator identity**: unchanged from #221 -- `P3A-001`,
  `sha256 8dc03caa0429bf03ca92165fd4de95ad9550d57796ba9a2f45aecfa4e05bb02d`,
  network UUID `013b548c-303b-4156-a6ba-367400de3eb2`.
- **Pilot depth/search budget**: depth 4 (unchanged from #221's own pilot, so the second pilot is
  comparable to the first).
- **Max games**: 12. Rationale (#222 section 10): #221's smoke was 3 games and proved
  duplication trivially (all 3 identical); this pilot needs enough games to observe the
  stochastic mechanism actually diverging across multiple independent draws while staying
  small -- 12 games is four times the smoke size, enough to see several distinct trajectories
  and a meaningful sample of stochastic selection events (audit section 2.2's ~85-96% of plies
  are stochastic-eligible), without approaching corpus scale. This is exploratory, not a
  strength- or data-quality-sized run.
- **Max plies**: 500 (unchanged from #221, the DR-E9 section 4.3 safety ceiling).
- **Max positions**: none (game-count bound only, matching #221).
- **Run seed**: 20260914001 (distinct from #221's `20260914`, so the two runs' VSPR outputs are
  never confusable).
- **Adjudication**: none configured (unchanged from #221 -- natural termination and the move cap
  are the only stop conditions; #222 explicitly does not add adjudication).
- **Metrics**: descriptive throughout (section 8/9) -- no manufactured pass/fail threshold.
  Per #222 section 4/11, this pilot is classified **exploratory**: there is no prior Stage-3
  diversity pilot to derive a defensible numeric diversity threshold from, so "enough diversity"
  is reported descriptively (unique trajectories, duplicate rate, CP-loss distribution), not
  scored against an invented cutoff.

## 5. Mate and forced-line policy

Decided in section 3.1 and implemented in `SeededDiversitySelector`:

- **Single legal/completed candidate**: deterministic, always that candidate. Nothing to sample.
- **Rank-1 is a winning mate**: deterministic rank-1. Diversity is never allowed to trade away a
  forced win for a non-mating CP alternative.
- **Rank-1 is a losing mate**: also deterministic rank-1 (not just winning mates). Two reasons:
  mixing a mate-distance loss into a CP-loss bound has no defined semantics (#222 section 7
  explicitly forbids inventing one), and a losing-mate position has no genuinely "better"
  alternative worth trading determinism away for -- every legal move loses eventually, and
  diversifying among them changes which particular losing line the game records without adding
  research-relevant diversity.
- **Mixed CP/mate candidates within one candidate set**: mate-kind candidates are excluded from
  the eligible set when rank-1 is CP (their loss cannot be computed against rank-1's CP score).
- **`Searcher`'s own mate-score encoding** (verified in `GameLoop.decodeScore`, unchanged from
  #221): `|scoreCp| >= MATE_SCORE - MAX_PLY` identifies a mate score; the multiPV rank ordering
  already reflects mate distance (a closer mate has larger magnitude and sorts first), so rank-1
  among mate candidates is already the fastest mate by construction -- no separate mate-distance
  comparison needed in the selector itself.

Tests: `SeededDiversitySelectorTest.winningMateRank1IsNeverAbandonedForDiversity`,
`.losingMateRank1IsAlsoDeterministic`, `.mixedScoreKindCandidateIsExcluded`,
`.singleCandidateIsAlwaysDeterministic`.

## 6. Seed derivation contract

**A real defect in the obvious approach, found before it shipped**: #221's `GameLoop` passed
`gameSeed + ply` directly as the per-selection seed. Under a fixed `runSeed`, `gameSeed` is
itself `runSeed + gameId`, so `gameSeed + ply == runSeed + gameId + ply` -- plain addition
commutes, meaning game 5 ply 3 and game 3 ply 5 produce the **identical** seed. That is exactly
the scheduling/identity-collision #222 section 5 forbids ("same tuple => same selection;
different game ordinal or run seed => independent deterministic draw" -- game-3-ply-5 and
game-5-ply-3 are a different tuple and must not collide).

**Fix**: `SeedDerivation.derive(gameSeed, ply)`
(`engine-core/.../selfplay/SeedDerivation.java`) combines the two values with two SplitMix64
avalanche steps (Steele/Lea/Flood 2014 -- a fixed, purely arithmetic mixing function, no
locale/hashCode/iteration-order dependence, stable across JVMs and platforms) instead of
addition, eliminating the collision. `GameLoop` now calls this once per ply and both records the
result on `PlayedMoveDecision.selectionSeed` (when a stochastic selector is in use) and passes it
to `MoveSelector.select()`.

**A second, related defect**, also found and fixed: `java.util.Random(seed)`'s own internal
seed-scrambling step (`seed ^ 0x5DEECE66DL`) leaves *nearby or sequential* input seeds
correlated on their very first `nextDouble()` draw -- a well-known weakness of that specific LCG,
not something `SeedDerivation.derive`'s avalanche alone protects against if a caller ever passes
raw sequential values. `SeedDerivation.remix(seed)` (one further avalanche pass) is applied
inside `SeededDiversitySelector` immediately before constructing its `Random`, as defense in
depth on top of `derive`. `SeedDerivationTest.swappingGameSeedAndPlyDoesNotCollide` and
`SeededDiversitySelectorTest.rawSequentialSeedsAreStillDecorrelatedInternally` are regression
tests for these two defects respectively.

**Contract, as implemented and tested**:

- Same `(generator identity, config, runSeed, gameOrdinal, ply, candidate set)` => same
  selection. `gameOrdinal` is `gameId` (unchanged from #221: `for gameId in 0..maxGames`);
  `gameSeed = runSeed + gameId` (unchanged addition, fine here since `gameId` is always small and
  non-negative and this sum is immediately re-mixed by `derive`, not used as the final seed).
- Different `gameOrdinal` or `runSeed` (via their distinct `gameSeed`) => independent
  deterministic draw, including no swap-collision with `ply` (the #221 defect above).
  Different `ply` alone => independent draw.
- No shared mutable `Random` -- `SeededDiversitySelector` constructs a fresh `Random` per
  `select()` call from the derived, remixed seed. Nothing about this design is scheduling-order
  or parallel-worker-count sensitive: it is a pure function of `(gameSeed, ply)`.

Tests: `SeedDerivationTest` (5 tests: same-tuple stability, ply/gameSeed sensitivity, the
swap-collision regression, adjacent-ply separation) and
`SeededDiversitySelectorTest.{sameSeedAndInputAlwaysProducesTheSameMove,
differentSeedCanAlterSelectionOnAConstructedEligibleSet, rawSequentialSeedsAreStillDecorrelatedInternally}`.

## 7. Engine-build identity hardening

Investigated per #222 section 9. This reactor (`engine-core`, `engine-uci`, `chess-engine-api`,
`engine-tuner`) has no existing build-metadata plugin (`git-commit-id-maven-plugin` or
equivalent) anywhere in any of its four `pom.xml` files -- there is no established local
precedent to extend. Adding one would be a new, first-of-its-kind build-time dependency across
every downstream module that depends on `engine-core`, introducing a first-build network fetch
requirement into a reactor whose build commands (`CLAUDE.md` section 3) are relied on elsewhere
(other sessions, potentially CI) with no such requirement today. Per #222 section 9's own
instruction ("if it requires broad build-system machinery: do not scope-creep... document the
limitation"), this is judged to cross that line -- it is a real, working solution in the abstract,
but not a small, local, reversible change to one file the way the rest of this issue's work is.

**Resolution**: `engineBuildId` remains operator-attested, exactly as #221 left it
(`EligibilitySmoke`'s own evidence string already says so explicitly:
`"engineBuildId recorded as operator-supplied ... not independently re-derived"`). This is an
explicit, disclosed provenance limitation, not a silent gap: a decision record's `engineBuildId`
field reflects what the operator typed at invocation time, unverified against the actual build.
**The network SHA-256 check remains mandatory and unchanged** (`EligibilitySmoke` continues to
verify the `.nnue` artifact hash against the operator-supplied expected value before any game
plays) -- this is the identity check that actually matters for correctness (a wrong network
produces wrong data), and it was never weakened.

If a future issue wants build-derived identity, the straightforward path is
`git-commit-id-maven-plugin` bound to `engine-core`'s `generate-resources` phase, producing a
`git.properties` resource `SelfPlayCli` reads and compares against the operator-supplied value --
scoped as its own small change, not folded into E-14.

## 8. Second pilot

Run under the exact preregistration in section 4, after the implementation in sections 3, 5, 6
was complete and tested (section 10).

*(Pilot execution, reproducibility, and different-seed results are reported in the closing issue
comment/PR description alongside this document, with the pilot's own decision-record JSON and
VSPR outputs as the primary evidence artifacts -- this document is the preregistration those
outputs are checked against, not a live results ledger that would need editing after the fact.)*

## 9. Non-scope carried forward unchanged from #222

No NNUE retraining, no SPRT, no production-scale corpus, no QuietWalk, no VSPR V1/shard V1/#210
ingestion changes, no reopening #208, no #181 work, no second diversity algorithm. `BestMoveSelector`
remains available, unmodified, as the zero-diversity control.
