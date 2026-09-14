# First controlled Stage-3 corpus + retraining experiment (E-15 design record)

**Status:** Phase A complete (implementation + hardening); Phase B (corpus generation) not yet
started. Governs issue #223, "[P15] E-15 -- First controlled Stage-3 corpus + retraining
experiment." Downstream of `DR-E14-seeded-diversity-audit-and-preregistration.md` (the diversity
mechanism this experiment trains on) and `DR-E12-stage3-generator-selection.md` (bootstrap
generator identity, two-gate eligibility/assessment model). Nothing beyond Phase A has been
executed: no 8,000-position corpus generated, no training run, no SPRT. The design calibrations in
sections 5 (depth 4/6/8) and the seed/protocol hardening below are explicitly excluded from any
training corpus, per this document's own repeated instruction.

Baseline verified against commits `f1b4f9d`, `298e724`, `4b3dc25`, `470103e` on `phase/15-nnue`.

## 1. Provenance (pinned separately, never one ambiguous SHA)

| Artifact | Path | SHA-256 |
|---|---|---|
| **A. Training-lineage checkpoint** (P3A-001, what the trainer actually loads/produces) | `trainer/outputs/phase3/P3A-001/checkpoints/step-016999.pt` | `c2c33d1dd7af5345d8772aa0761e79d5b9bc8bff7aff409b946078684c08d0ef` |
| **B. Exported runtime NNUE artifact** (what the Java self-play generator loads, #221/#222) | `trainer/outputs/selfplay-bootstrap/p3a001-export/013b548c-303b-4156-a6ba-367400de3eb2.nnue` | `8dc03caa0429bf03ca92165fd4de95ad9550d57796ba9a2f45aecfa4e05bb02d` (network UUID `013b548c-303b-4156-a6ba-367400de3eb2`) |
| **C. Engine source** | git commit on `phase/15-nnue` at generation time | `f1b4f9d88e0c0068f2459d0ca29167c51b48bdff` as of this document; E-15 execution must re-pin whatever commit is current when it actually generates, and record it, not reuse this value blindly |
| **D. Engine binary artifact** | none exists | see below |

A and B are two different files with two different hashes -- A is the PyTorch checkpoint the
trainer produced; B is what `NnueNetwork.load()` reads at runtime, produced from A by the
existing export pipeline (checkpoint -> canonical -> quantize -> export). Never refer to "the
P3A-001 hash" without saying which of the two this means.

**D, investigated and found not practical, same category as `DR-E14` section 7's finding**: no
`engine-core` build produces a packaged JAR. `SelfPlayCli` is invoked via `mvn -pl engine-core
compile` plus a `java -cp <target/classes>:<dependency jars>` classpath, not `java -jar`. Hashing a
directory of `.class` files is not a meaningful single artifact identity, and adding a packaging
step to `engine-core`'s `pom.xml` (which has none today, being a pure-logic library module, `CLAUDE.md`
section 4: "must never depend on Spring/HTTP") would be exactly the kind of build-system
scope-creep `DR-E14` section 7 already declined for the same reactor. `engineBuildId` therefore
stays operator-attested (the git commit SHA, column C), consistent with the E-14 precedent -- not
re-litigated here, just re-applied.

## 2. The MultiPV confound (load-bearing -- read before anything else in this document)

`DR-E14` section 2.1 already found that raising `Searcher`'s `multiPV` from 1 to 3 can change
even the rank-0 move played, because the extra `pvIndex` re-searches populate the transposition
table differently (the audited game diverged from #221's original trajectory into a different,
shorter game purely from this). **#221/#222's own generated corpora used two different search
configurations for two different reasons**: #221 used multiPV=1 (rank-0-only, no candidate
diversity possible). #222 used multiPV=3 so `SeededDiversitySelector` had rank-2/3 to sample from.

**Comparing "old #221-style corpus" against "new diverse corpus" would therefore confound two
independent variables**: MultiPV-induced search/TT differences, and stochastic move selection.
A positive or negative training result under that comparison could not be attributed to either
one. This is exactly the mistake this experiment's design must not make.

**Required generator arms, identical except for the one intended variable**:

| | Control | Treatment |
|---|---|---|
| `multiPV` | 3 | 3 |
| Selector | `BestMoveSelector` | `SeededDiversitySelector(maxRank=3, cpLossBoundCentipawns=40, temperature=20.0)` |
| Network / engine build / search budget / max plies / adjudication / seed policy | identical | identical |

Only the selector policy differs. `DR-E14`'s own preregistered parameters (section 3.3 there) are
reused unchanged here, per this task's explicit "do not modify the diversity mechanism" instruction.

**Implementation gap, found while designing this -- now closed (Phase A)**: `BestMoveSelector`'s
`requiredCandidateCount()` (the `MoveSelector` default method `GameLoop` reads to set `Searcher`'s
`multiPV`, `SeedDerivation`/`SeededDiversitySelector`'s own sibling mechanism) defaulted to 1 with
no override -- there was no way to run `BestMoveSelector` at multiPV=3 through `SelfPlayCli`.
Verified directly: a calibration run through the existing CLI without diversity flags used
multiPV=1 (55.3s / 3 games / 423 samples), not multiPV=3.

**Closed**: `BestMoveSelector(int requiredCandidateCount)` (`engine-core/.../selfplay/BestMoveSelector.java`)
-- a second constructor overriding only `requiredCandidateCount()`; move selection is unaffected
(always rank-0, regardless of width). The existing no-arg constructor (and every existing #221/#222
call site) is untouched and behaviorally identical -- `requiredCandidateCount()` defaults to 1 via
`this(1)`. `SelfPlayCli` gained `--control-multipv <n>` (`SelfPlayCli.CliArgs`), valid only when no
`--diversity-*` flags are given (throws `IllegalArgumentException` if both are present -- one
selector per run, never inferred implicitly from flag combinations). No flag at all reproduces
#221's original behavior exactly. Decisively tested at the `GameLoop` level (not just the config
record): `GameLoopDiversityTest.controlMultiPvBestMoveSelectorActuallyWidensTheSearchedCandidateSet`
constructs `BestMoveSelector(3)`, plays a game with a diagnostics sink, and confirms `Searcher`
actually returned multi-candidate sets (`candidateCount > 1` observed) while every selection still
resolved to rank-0 (`chosenRank == 0` on every ply). See section 19 for the full Phase A test list.

## 3. Primary research question

> Does training on bounded, diverse Stage-3 self-play search labels improve the NNUE relative to
> an otherwise matched rank-0 self-play control?

One lever: Stage-3 data source (control rank-0 corpus vs. treatment diverse corpus). Not tested in
this experiment, deliberately: architecture changes, a new auxiliary head, `wdl_lambda` changes
(#208 stays closed), optimizer changes, LR-schedule changes, multiple diversity temperatures,
multiple search depths. Any of those is a separate, later experiment ID.

## 4. WDL lambda: kept fixed, evidence-consistent with the existing default

`TrainingConfig.wdl_lambda` (`trainer/trainer/model/train.py:148`) already defaults to `1.0` --
pure sigmoid-target training, `label.wdl` unused in the loss. This is not a new choice E-15
introduces; it is the trainer's own existing default, and every completed Phase 4/5 experiment in
this roadmap that didn't deliberately vary it (P4IV is the one exception, and it is the
already-closed, non-promotable #208-adjacent lever this task forbids reopening) trained under it.
Keeping `wdl_lambda=1.0` isolates the Stage-3 search-label data-distribution effect from any
outcome-blending effect, per this task's own instruction. `label.wdl` is still written by
`selfplay_ingest.py` (unchanged) on every Stage-3 record regardless -- available for a future
experiment that deliberately varies `wdl_lambda`, not discarded here, just unused by this one.

## 5. Search-budget decision, with calibration

`DR-E14`/#222 used depth 4 for bounded *mechanism* validation (multiPV plumbing, selector
correctness) -- not chosen for label quality, and this task correctly refuses to promote it into
a training corpus without evidence.

**Calibration performed** (design calibration only, excluded from any training corpus, per this
task's section 21): 28 positions drawn from the existing #222 pilot-002 VSPR output (every 60th
sample across its 1,678 records -- no new self-play games were generated for this step), each
re-searched at depth 4, depth 6, and depth 8 with the same network (artifact B, section 1).

| Comparison | Root-move agreement | Abs CP-score diff (min / median / p90 / max) |
|---|---|---|
| depth 4 vs. depth 6 | 9/28 (32.1%) | 0 / 25 / 64 / 151 |
| depth 4 vs. depth 8 | 9/28 (32.1%) | 1 / 45 / 126 / 300 |

**Depth 4 is not defensible for label quality**: the root move changes on roughly two-thirds of
positions when searched even two plies deeper, and the *same* fraction changes again at four plies
deeper -- the instability is not a smooth function that depth 4 merely approximates poorly; it
looks like depth 4 simply hasn't converged on most of these positions.

**Timing, same 28-position set, same network, wall-clock via `Searcher`'s own per-depth `[BENCH]`
instrumentation** (cumulative time for a full iterative-deepening call through each target depth,
averaged): depth 4 ~= 41ms/position, depth 6 ~= 394ms/position, depth 8 ~= 4,077ms/position.

**Direct depth-6-vs-depth-8 comparison (Phase A -- closes the gap the original calibration left
open)**: the original calibration only measured each of depth 6 and depth 8 against depth 4, not
against each other -- insufficient to support the claim "depth 8 buys no additional stability over
depth 6" that an earlier draft of this section made. Corrected: the **same 28 positions**
(identical FEN list, re-derived from the same `every-60th-sample` rule over the same #222
pilot-002 VSPR file -- no position added or removed after seeing results), same network, searched
at depth 6 and depth 8 directly against each other:

| Metric | Value |
|---|---|
| Root-move agreement (depth 6 vs. depth 8) | 11/28 (39.3%) |
| Mate-kind disagreements | 0/28 |
| Abs CP diff (both non-mate, n=28): min / median / p90 / max | 0 / 19 / 60 / 246 |
| CP sign agreement | 26/28 (92.9%) |

**Honest reading of this, including the uncomfortable part**: root-move agreement between depth 6
and depth 8 (39.3%) is *higher* than either depth's agreement with depth 4 (32.1%, both) but still
means **60.7% of these positions pick a different best move at just two plies deeper than depth
6**. This is real, material instability that persists at every affordable depth this calibration
tested (4, 6, 8) -- there is no depth in this budget range where root-move choice has converged.
Going deeper still (depth 10+) is not a way out: at the ~10x-per-two-plies cost curve already
measured (41 -> 394 -> 4,077ms), a depth that actually converges root-move choice, if one exists
at all within reach, would cost tens of seconds per position -- incompatible with any bounded
corpus generation budget this experiment could use (section 6's resource cap).

**Why this does not invalidate depth 6, despite the honest instability above**: root-move
agreement measures which move gets *played* (a trajectory-exploration property), not the accuracy
of the *score label* recorded for a position the game actually reaches (a label-quality property)
-- the two are related but distinct, and it is the second one this section exists to evidence.
CP-score stability between depth 6 and depth 8 is meaningfully tighter than between depth 4 and
either deeper budget (median 19cp / p90 60cp here, vs. depth 4's median 45cp / p90 126cp against
depth 8) -- consistent with depth 6 being closer to whatever depth 8 would find than depth 4 is,
even though the exact chosen move still frequently differs. CP sign agreement (92.9%) confirms the
two depths agree on which side is better far more often than they agree on the single best move --
the *label* is directionally stable even where the *move* isn't. Root-move volatility at bounded
search depth is disclosed here as an intrinsic property of self-play generation at any resourced
budget this project can afford, not a defect specific to depth 6, and not something a different
depth choice within this cost envelope would meaningfully fix (depth 8 does not converge with
depth 6 either, at 10x the cost).

**Decision: search depth 6** for the E-15 corpus (both arms), retained. Rationale, made explicit
per this task's instruction rather than switched post-hoc: depth 4 is evidenced clearly worse on
every measured axis (larger CP diffs, same low root-move agreement); depth 8 costs ~10x depth 6's
generation time for a *modest* CP-stability improvement over depth 6 (median diff drops from what
depth 4 shows, but root-move volatility does not resolve) and no evidence of being meaningfully
"more converged" in the sense that would justify a 10x resource multiplier for a first, exploratory
experiment. This is a corpus-generation decision only -- it does not change #222's own depth-4
mechanism-validation pilots, which remain valid for what they were testing (selector correctness,
not label quality). **Not a STOP-triggering finding**: instability is real and disclosed, but it is
evidenced to be a property of bounded search generally in this network/position regime, not
something depth 6 specifically introduces or that a different affordable depth would avoid.

## 6. Corpus-budget rationale (resource-justified, not invented)

**Timing measurement at the actual selected configuration** (multiPV=3, depth 6, matching both
generator arms -- measured via the diversity-flags CLI path, which exercises the same multiPV=3
search cost regardless of which selector consumes the results): 3 games, 363 samples,
172.3 seconds wall-clock = **0.475 s/position**, ~57.4 s/game (~121 samples/game observed, this
run only -- #222's own pilot-002 showed game lengths ranging 54-259 plies at multiPV=3/depth 4, so
per-game sample count at depth 6 should be treated as a rough estimate, not a tight one).

**Resource cap**: <= 90 minutes generation wall-clock per arm (a bounded, explicit ceiling, not
"however long it takes"). At the measured rate, 90 minutes = 5,400 seconds / 0.475 s/position ~=
**11,368 positions** as an upper bound.

**Chosen target: 8,000 Stage-3 positions per arm** (`GeneratorConfig.maxPositions = 8000`), ~63
minutes at the measured rate -- inside the 90-minute cap with headroom for the per-position rate's
own observed variance (game-length range above), and, per this task's "nontrivial relative to the
existing training set" requirement: the base training set is 36,000 records (`combine_and_split`'s
established 36,000/4,000 split, unchanged since P1-G04). 8,000 Stage-3 positions is ~22% of that
-- a real, meaningful minority contribution to the treatment/control training mix, not a token
amount, and not large enough to make Stage-3 data dominate a first, exploratory experiment.
`GeneratorConfig.maxGames` is set generously higher (150, well above the ~66 games this budget is
expected to take at ~121 samples/game) so `maxPositions` is the actual governing stop condition,
not the game count.

## 7. Dataset mixing rule

**No existing precombine/interleave mechanism exists yet** -- `SelfPlayProvider` is a thin shard
reader (`trainer/trainer/dataset/selfplay_provider.py`, verified: no batching or mixing logic
inside it), and no script in this repository has ever combined it with a base-dataset provider.
Per this task's "choose the smallest mechanism consistent with current trainer architecture,"
E-15 uses the same pattern every existing phase-N script already uses for its own single
training-record list (`phase3_experiment_3a.py`'s `combine_and_split()` call, `train()`'s flat
`training_records: List[PositionRecord]` parameter): **precombine, once, before training** --
`training_records = base_training_records + stage3_arm_training_records` (plain list
concatenation), no per-batch/per-epoch interleaving machinery. This is the smallest mechanism that
satisfies every requirement below; a fancier weighted-sampling scheme is not justified by anything
in this experiment's own scope.

**Held-out evaluation, two distinct sets, not conflated**:
- **Primary (v1-clean)**: the same historical `combine_and_split(seed=42)` 4,000-record held-out
  set every prior Phase 3/4/5 experiment has used, unchanged, byte-identical. This is the
  established, noise-floor-characterized benchmark (`measurement-model.md` section 1's own
  variance figures) -- the primary comparison in section 12 is evaluated against it.
- **Secondary (Stage-3 self-play held-out)**: each arm's own corpus is grouped-split via
  `split_by_game(seed=<pinned>, held_out_fraction=0.10)` (the existing #210/DR-E9 grouped-split
  requirement -- no game's positions ever split across train/held-out). This is new, is not yet
  noise-characterized, and is reported as exploratory context (section 12), never as the primary
  promotion evidence.

**Equal Stage-3 row budget, enforced exactly, not approximately**: after each arm's own
`split_by_game`, if the two arms' resulting *training* (non-held-out) row counts differ (expected,
since game lengths vary and control/treatment are different corpora), the larger arm's Stage-3
training slice is truncated (a seeded random sub-selection, not a positional truncation, to avoid
biasing toward whichever games happened to be generated first) down to the smaller arm's exact
row count, before either is concatenated with the base set. This guarantees "equal Stage-3 record
budget" holds exactly at training time, not merely "both arms requested the same budget."

**Requirements satisfied by this rule**: equal Stage-3 row budgets (above); equal total training
steps (section 8, identical `TrainingConfig.steps` for both arms, unaffected by row-count
differences since `train()`'s step count is independent of dataset size); equal base-data sampling
(the full, unfiltered 36,000-record base set for both arms, no base-side subsampling at all);
pinned split seeds (below); grouped Stage-3 split preserved; no game in both train and held-out
(enforced per-arm by `split_by_game`, which already fails loudly on ungrouped input).

**Row-level truncation vs. whole-game equalization -- made explicit, not left implicit (Phase A
hardening)**: the truncation above operates on individual rows within the already-train-side pool
of an arm's Stage-3 records, not whole games. This means a truncated arm's training set can retain
*some* of a given training-side game's samples while dropping others from that same game -- the
game's samples are never split across train and held-out (that boundary is fixed by
`split_by_game` before truncation ever runs), but a game's samples can become incomplete *within*
the training side alone. This is the deliberate choice, not whole-game equalization, because whole
games have variable sample counts (section 2.2/6's own observed game-length range), so removing
whole games can only *approximate* a target row count, never hit it exactly -- and this document's
own requirement (above) is that the Stage-3 row budget match **exactly**, not approximately.
Train/held-out group integrity (the property `split_by_game` exists to protect, and that #210/DR-E9
require) is fully preserved either way, since truncation only ever touches the training-side pool,
never held-out, and never moves a row between the two. This is stated as a conscious tradeoff, not
a silent one: the alternative (whole-game equalization, coarser-grained, approximate row counts)
was considered and rejected specifically because it cannot satisfy the "exact" requirement this
document already commits to.

**Duplicate-row policy -- preregistered explicitly (Phase A hardening)**: **no corpus-level
deduplication in E-15.** The research question (section 3) is the effect of the selector-induced
Stage-3 *data distribution* -- if `BestMoveSelector`'s deterministic rank-0 control produces
repeated trajectories (plausible; #221's own first pilot was 3/3 byte-identical games), that
repetition *is part of the control condition being measured*, not noise to clean up before
training. Both arms are ingested and transformed identically (the unmodified `selfplay_ingest.py`
path, section 9's quality gate, this section's mixing rule) -- no arm-specific post-processing step
exists or is added. Reported per section 9: within-control duplicate rate, within-treatment
duplicate rate, and cross-arm duplicate rate (this section's own note above). **No design
objection is raised here**: retaining duplicate rows is judged to make the control appropriately
*weak* (exactly what a deterministic rank-0 control should look like, and exactly the property
#222 already characterized and #221 first discovered), not scientifically *invalid* -- a control
arm that trivially collapses to fewer unique positions than the treatment arm is expected evidence
about the mechanism's own effect, not a confound that needs to be papered over before comparison.

**A property to monitor, not fix**: because both arms start every game from the same standard
starting position, and `DR-E14` section 2.2 found most treatment games diverge from a deterministic
rank-0 line by ply 0-2, the two corpora can share some identical early-game FEN positions by
construction -- this is not train/held-out leakage (each arm's own split still keeps a game
entirely on one side), but it does mean the two arms' *training sets* are not fully disjoint from
each other. Section 10's quality-gate reporting includes a control/treatment cross-corpus
duplicate-position count specifically so this is visible, not assumed away.

## 7a. Whole-game corpus boundaries (Phase A hardening -- verified, not changed)

A VSPR game must never be cut in half merely because `maxPositions` was reached mid-game. Verified
directly against `SelfPlayCli.run()`'s own generation loop (`engine-core/.../selfplay/SelfPlayCli.java`):

```java
for (long gameId = 0; gameId < config.maxGames(); gameId++) {
    if (config.maxPositions() != null && totalSamples >= config.maxPositions()) {
        break; // secondary stop -- checked at game boundaries only, never mid-game
    }
    ...
    GameFrame frame = gameLoop.playGame(gameId, gameSeed);
    ...
}
```

`maxPositions` is checked exactly once per iteration, **before** that iteration's game starts --
never inside `GameLoop.playGame()`, which has no `maxPositions` awareness at all (it only knows
`maxPlies`, its own per-game cap, DR-E9 section 4.3's safety ceiling). A game that has already
started always runs to one of its own natural/adjudicated/move-cap terminations before the outer
loop's `maxPositions` check is consulted again. **This was already true before Phase A** -- no fix
was needed, only verification and a regression test, since #221/#222's own pilots never happened to
exercise a `maxPositions` value low enough to make the distinction observable.

**Proven by test** (new, Phase A): `SelfPlayCliTest.maxPositionsStopsOnlyBetweenGamesNeverMidGame`
sets `--max-plies 6 --max-positions 1` (a budget far below one game's own sample count) and asserts
the one emitted game is still the full 6 plies/6 samples, `TerminationReason.MOVE_CAP` -- not a
1-sample fragment. The E-15 corpus generation command (Phase B, not run this turn) therefore needs
no special "whole-game stopping mode" -- `--max-positions 8000` already behaves correctly, and the
actual position count in each arm's output will be the sum of complete games' sample counts up to
and including whichever game first reaches or exceeds 8,000 (matching section 6's own "+/- the
last game's overrun" language, now confirmed to be exactly how the existing implementation
behaves, not an approximation this document was assuming).

## 8. Training arms, initialization, schedule

**Initialization -- corrected from this task's own suggested default, with evidence**: the task
text's likely-candidate suggestion ("the established P3A-001 baseline checkpoint") does not match
this project's actual lineage. Every completed retrain in this roadmap (P1-G04, P3A-001, every
Phase 4/5 arm) trains from a **fresh random initialization under a fixed seed**, never by loading
a prior checkpoint's weights and continuing training -- `NnueNet`'s constructor plus
`TrainingConfig.seed` is the entire initialization mechanism this codebase has ever used. Loading
P3A-001's weights and fine-tuning from there would be a methodologically different experiment
(continued training vs. a fresh matched retrain) that nothing in this roadmap's history does.
**E-15 both arms initialize fresh, `TrainingConfig.seed = 42`** (P3A-001/P1-G04's own frozen
schedule value, section 8's schedule below).

**Hardened beyond "same seed" alone (Phase A)**: `seed_everything(config.seed)` plus a freshly
constructed `NnueNet` *should* already be deterministic given identical code and identical seed --
verified in `train.py`, model construction happens before `records` is even touched, so it cannot
depend on which arm's data is passed. But "same seed produces the same weights" is an assumption
about `torch`/CUDA/BLAS-backend determinism across separate process invocations, not a verified
guarantee (GPU matmul/init kernels are not always bit-reproducible across runs even under a fixed
seed) -- exactly the gap this task's own instruction calls out. **Closed via `train()`'s new
`initial_state_dict` parameter** (`trainer/trainer/model/train.py`, additive, default `None`
preserves every existing caller's behavior exactly): the E-15 execution protocol (Phase D, not run
this turn) is:

1. Construct one model under the preregistered seed: `seed_everything(42); model = NnueNet(256, 127, 64, 400)`.
2. Save its `state_dict()` to a fixed path (e.g. `outputs/phase-e15/init/e15-init-seed42.pt`) and
   compute its SHA-256 -- this is the **E-15 initialization artifact**, pinned in the Phase C/D
   decision record (section 10) before either arm trains.
3. Both `train()` calls (control-trained, treatment-trained) load that exact saved `state_dict()`
   via the new `initial_state_dict` parameter, instead of relying on each call's own internal
   `seed_everything`-driven construction to happen to agree.

This converts "both arms used the same seed" (an assumption) into "both arms loaded the identical,
hash-verified parameter bytes" (an audited fact) -- the two arms begin from literally identical
parameter values, not merely from a shared recipe for producing them. Tested (Phase A, new):
`test_train_with_explicit_initial_state_dict_starts_both_arms_from_identical_weights` in
`trainer/tests/model/test_train.py` -- two `train()` calls, **different** `config.seed` values and
**different** training data per arm, `learning_rate=0.0` (neutralizes optimizer updates so the
saved checkpoint's weights are still the initial ones), both loaded from one pinned
`initial_state_dict`: both resulting checkpoints match the pinned state bit-for-bit and match each
other. `test_train_with_no_initial_state_dict_is_unaffected` confirms the default (`None`)
reproduces pre-E-15 behavior exactly, alongside the existing
`test_train_is_reproducible_given_the_same_seed` (unchanged, still passing).

**Schedule -- P3A-001's frozen values, unchanged, both arms**: `steps=20000`, `learning_rate=0.01`,
`lr_schedule="cosine"`, `warmup_steps=200`, `batch_size=256`, `k=2.773456`,
`hidden_width=256, qa=127, qb=64, output_scale=400` (architecture, unchanged per this task's
explicit instruction). Reusing the established schedule rather than inventing a new one satisfies
"identical LR schedule / step count" trivially (both arms use the literal same `TrainingConfig`,
differing only in `training_records`) and avoids adding a second, untested lever.

**Training arms**:

| | Control training arm | Treatment training arm |
|---|---|---|
| Base data | 36,000-record base set (unchanged) | same |
| Stage-3 data | control generator corpus (section 2), row-budget-matched | treatment generator corpus (section 2), row-budget-matched |
| Init | fresh, seed 42 | fresh, seed 42 (identical starting weights) |
| Schedule | P3A-001's frozen values (above) | identical |
| Held-out (primary) | v1-clean, 4,000 records | identical |

## 9. Corpus quality gate (#210's manifest assessment model, before training)

Both corpora ingested through the unmodified `selfplay_ingest.py` path (as #222 already
established works). **Hard requirements, before either corpus is used for training**:

- zero codec/structural decode failures (VSPR round-trip, Java write / Python decode)
- zero illegal moves (enforced at generation time already, `GameLoop.validateLegal`, fail-closed)
- zero provenance mismatches (recorded generator identity matches every sample)
- zero game-ID collisions (`_GameIdRemapper`'s existing conflict detection)
- zero infrastructure-unresolved games, unless a future revision of this document preregisters a
  tolerance before generation -- none is preregistered here, so any `UNRESOLVED` game in either
  arm's corpus is treated as a hard gate failure for this experiment, not silently accepted
- exact generator identity confirmed (network SHA-256 + UUID + engine build, per section 1)
- intended position budget reached (8,000 +/- the last game's overrun, section 6) for each arm

**Reported descriptively, not gated on an invented threshold** (per this task's own instruction,
consistent with `DR-E12` section 5's "no numeric threshold without a prior distributional
baseline"): duplicate-position rate (per arm, and cross-arm per section 7's note), game-result
distribution, termination-reason distribution, game-length distribution, score distribution.

**Treatment corpus, additionally**: rank-selection frequencies, CP-loss distribution (from the
diversity diagnostics CSV, `DR-E14` section 8's mechanism, unchanged), first-divergence-ply
distribution relative to the control arm's own trajectories (not #221/#222's old multiPV=1 data --
comparing against a multiPV=1 reference would reintroduce the section 2 confound).

**Assessment approval means**: this specific dataset is usable for E-15's training experiment.
It does **not** mean the generating network or the diversity mechanism is promoted -- `DR-E12`
section 3's two-gate separation applies unchanged.

## 10. Dataset identities

Recorded per arm, before training starts, in the E-15 decision record (section 16's Phase C
deliverable): VSPR run ID (header `runId`), source VSPR SHA-256, ingestion dataset manifest
identifier + its own `dataset_identifier` string (distinct per arm, e.g.
`stage3-e15-control-001` / `stage3-e15-treatment-001` -- never reused across a regeneration),
shard SHA-256, generator `GameConfig` (search budget, max plies, adjudication, multiPV, selector
config), game/position counts actually achieved, and the split-membership hash (a SHA-256 over the
sorted list of held-out `game_id`s, so a later regeneration under the same nominal identifier can
be detected as different data rather than silently assumed identical).

## 11. Primary / secondary metrics (the existing Measurement Model, unmodified)

No E-15-only metric is invented. Per `measurement-model.md` sections 1/1a/6/7/9:

- **Screening**: v1-clean pooled correlation -- must show meaningful improvement to proceed to
  interpretation, proves nothing alone.
- **Primary / decisive**: v1-clean **cp-only correlation** -- majority-population rule (section 6
  there): examined *before* any pooled number, and the deciding condition regardless of what the
  pooled number shows.
- **Secondary regression guards**: RMSE, calibration (bias, compression), both under the shared
  v1-clean rubric (no target-definition change here, so the rubric-consistency machinery `DR-E9`/
  measurement-model section 5 exists for does not add complexity in this experiment -- flagged
  explicitly so a reviewer doesn't go looking for a rubric split that isn't needed here).
- **Exploratory**: mate-only correlation, magnitude-bucket MAEs, prediction histograms, and (new
  to this experiment, same exploratory tier) the Stage-3 secondary held-out set's own correlation
  -- informative, never decisive, per section 7 above's primary/secondary held-out distinction.

**Checkpoint selection**: `_select_best_checkpoint()` unchanged (peak pooled v1-clean
correlation). Per measurement-model section 8's **mandatory** rule ("whenever an experiment
changes the effective weighting or treatment of a subset") -- Stage-3 data is a new subset being
introduced into the training mix, so this rule applies: **both the selected checkpoint and the
final checkpoint are reported and evaluated for both arms**, and the full trajectory is inspected
for non-monotonicity before trusting the selected step, exactly as P4II's instability finding
requires going forward.

## 12. Success / null / regression interpretation (pinned before training, per measurement-model §7)

The **primary causal comparison** is treatment-trained vs. control-trained (both arms as defined
in section 8) -- **not** treatment vs. historical P3A-001 alone (a historical P3A-001 row may still
be reported as context, same convention `phase3_experiment_3a.py` already uses for its own
2x2 matrix, but it answers a different question and is never the deciding comparison).

- **A. Clear positive result**: treatment-trained satisfies all four `measurement-model.md`
  section 7 promotion-rule conditions relative to control-trained (meaningful v1-clean pooled
  improvement; no meaningful RMSE regression; no meaningful calibration regression;
  majority-population/cp-only improvement or no degradation), with the pooled/cp-only deltas
  clearly exceeding the measured same-K seed noise floor (~0.0004 pooled correlation, `measurement-model.md`
  section 1 / P4I's replication) -- not merely nonzero.
- **B. Null / inconclusive**: any condition in A is unclear, mixed, or the observed delta is not
  clearly separable from the ~0.0004 noise floor. **Explicitly a valid, reportable outcome** --
  this task's own instruction forbids inventing a threshold that forces a binary call the evidence
  doesn't support.
- **C. Regression**: cp-only correlation (the decisive majority-population metric) is clearly worse
  for treatment than control, or RMSE/calibration regress meaningfully under the shared rubric --
  independent of what the pooled number shows, per the exact P4II/P4III failure mode
  `measurement-model.md` sections 4-6 already documented.

No result under A, B, or C is manufactured into a stronger claim than the numbers support. A null
result (B) is a complete, valid E-15 outcome, not a failed experiment requiring a silent rerun with
different parameters (section 15 of this task's instructions -- any parameter change requires a
new experiment ID).

## 13. SPRT boundary

E-15 is entirely an offline training-data experiment. **No SPRT is preregistered or run in this
experiment.** Only if a trained candidate clears interpretation A (section 12) does a *separate*,
later, explicitly authorized decision consider release-promotion SPRT -- offline improvement is
not itself sufficient evidence for that decision either (`measurement-model.md`'s own promotion
rule is necessary, not automatically sufficient for a ship decision; SPRT process/boundaries are
`CLAUDE.md` section 6's unchanged, native-Windows-only territory, orthogonal to this document).

## 14. Randomness control

**Pinned now (Phase A), before any Phase B corpus generation -- no longer placeholders.**
Derivation convention: explicit named constants, following this repository's own existing
practice (`phase3_experiment_3a.py`'s `SPLIT_SEED = 42`, `TRAIN_SAMPLE_SELECTION_SEED = 999`) --
not a formula, since a derivation formula adds a layer of indirection an explicit constant table
doesn't need and doesn't make auditing easier.

| Purpose | Value | Control | Treatment |
|---|---|---|---|
| Generation run seed | **20261501** | same value | same value (matched-seed design, below) |
| Seed derivation algorithm | `SeedDerivation.derive` (`DR-E14` section 6, unchanged) | -- | -- |
| Game ordinal policy | `gameId` 0..N sequential (unchanged from #221/#222) | -- | -- |
| Trainer init + data-order seed | **42** (P3A-001/P1-G04's frozen value; `train.py` verified: `TrainingConfig.seed` is the *only* seed field -- `seed_everything(config.seed)` governs both model init and the epoch-reshuffle `random.shuffle`, no separate data-order seed exists in the current trainer architecture to pin) | 42 | 42 |
| Stage-3 grouped-split seed (`split_by_game`) | **20261502** | same value | same value |
| Row-budget-equalization sub-selection seed | **20261503** | same value (applied only to whichever arm's Stage-3 training pool is larger, section 7) | same value |

**Naming convention**: `2026` (year) + `15` (experiment E-15) + a 2-digit purpose ordinal (`01`
generation, `02` split, `03` equalization) -- readable, collision-free against every other seed
constant already used elsewhere in this repository's phase-N scripts (all of which are much
smaller integers: 42, 999, 20260914), and fixed now, never re-derived from anything computed at
generation or training time.

**Evaluator seed**: not applicable -- `evaluate_held_out`/`calibration_report` (Phase E) run no
stochastic sampling of their own; they score every given record deterministically.

**Matched-seed rationale**: `BestMoveSelector` ignores its `seed` argument entirely (deterministic
regardless of its value, unchanged since #221) -- giving control and treatment the *same*
generation run seed costs nothing (control's output is identical regardless) and means if a future
revision ever swaps in a seed-sensitive control, the pairing is already in place for variance
reduction. This is not the same as sharing an RNG *stream* (each arm still derives its own
per-ply seed via `SeedDerivation.derive(gameSeed, ply)`, section 2's own per-game/per-ply
isolation, unchanged) -- it is only the *root* run seed value that is shared.

## 15. No post-hoc retuning

Once this document is committed, the following are pinned and not to be changed after seeing any
result, per this task's explicit instruction: corpus size (section 6), selector parameters
(section 2, unchanged from `DR-E14`), search budget (section 5), dataset mixing ratio/rule
(section 7), training schedule (section 8), training seed (section 8/14), checkpoint-selection
rule (section 11), primary metrics (section 11), success criteria (section 12). A null or negative
E-15 result is a complete, valid result. Any change to any of the above requires a new experiment
ID, not a revision of this one.

## 16. Phases

- **Phase A -- prerequisite + calibration + hardening -- COMPLETE**: `BestMoveSelector(int requiredCandidateCount)`
  + `SelfPlayCli --control-multipv` (section 2, closed); depth 4/6/8 calibration including the
  direct depth-6-vs-8 comparison (section 5, closed, depth 6 retained); all E-15 seeds pinned
  (section 14, closed); whole-game corpus-boundary behavior verified with a regression test
  (section 7a, closed -- no code change was needed, only verification); duplicate-row policy
  preregistered (section 7, closed); `train()`'s `initial_state_dict` parameter + execution
  protocol for byte-identical initialization (section 8, closed). No corpus generation occurred in
  this phase.
- **Phase B -- matched control/treatment corpus generation**: run both arms per sections 2/5/6,
  bounded per the resource cap, output VSPR + decision records per arm.
- **Phase C -- corpus assessment/ingestion**: section 9's quality gate, section 10's dataset
  identities, both arms, before any training starts.
- **Phase D -- matched training**: section 7's mixing rule, section 8's arms/schedule/init.
- **Phase E -- offline evaluation**: section 11's metrics, section 12's interpretation, reported
  against section 12's A/B/C classification. No SPRT phase exists in E-15 (section 13).

## 17. Adversarial review

- **What if multiPV=3 alone changes the data enough to improve training?** Isolated by the
  matched control arm (multiPV=3 + `BestMoveSelector`) -- both arms experience identical
  multiPV-induced search/TT effects; the only different lever is selector policy (section 2). A
  historical multiPV=1 comparison is deliberately not used as primary evidence for exactly this
  reason.
- **What if the diverse corpus has more unique positions but worse labels?** The search-budget
  calibration (section 5) targets label quality directly, applied identically to both arms; the
  offline cp-only correlation (section 11) is exactly the check that would surface "more positions,
  worse learning" as a regression (section 12.C), not something the design can rule out in
  advance -- it's what the experiment measures.
- **What if treatment has fewer/more usable records after filtering?** Section 7's exact-equalization
  rule (seeded truncation of the larger arm's Stage-3 slice) handles this before training, not
  after.
- **What if one corpus has more games but the same row count?** Not a training-mixing problem
  (rows are what's concatenated); reported as descriptive context (section 9), not gated.
- **What if outcomes differ strongly between control and treatment?** Expected and low-risk to the
  primary comparison specifically because `wdl_lambda=1.0` (section 4) means `label.wdl` is unused
  in the loss this experiment trains under -- game outcome differences affect a field this
  experiment doesn't train on. Still reported descriptively (section 9).
- **What if treatment improves pooled correlation but regresses cp-majority?** Exactly the
  P4II/P4III failure mode the majority-population rule exists for (section 11); classified as
  regression (section 12.C) regardless of the pooled number.
- **What if the best checkpoint occurs at a different step between arms?** Expected and allowed;
  both selected and final checkpoints are reported for both arms per measurement-model section 8's
  mandatory rule (section 11), full trajectories inspected for non-monotonicity before trusting
  either selected step.
- **What if treatment gains are smaller than run-to-run seed noise?** Classified null/inconclusive
  (section 12.B), an explicitly valid outcome, not stretched into a claim.
- **What if generation takes too long at the chosen search budget?** The 90-minute-per-arm
  resource cap (section 6) is a hard stop, backed structurally by `GeneratorConfig`'s existing
  fail-closed `maxPositions`/`maxGames` bounds (no unbounded generation code path exists at all,
  unchanged since #221) -- if real throughput undercuts the section 6 projection materially,
  Phase B re-scopes explicitly rather than silently running longer.
- **What if runtime .nnue hash and source checkpoint identity get conflated?** Pinned separately
  in section 1 as artifacts A and B, with an explicit statement that they are different files with
  different hashes -- carried through to the Phase C decision record, never merged into one field.
- **What evidence would justify proceeding to SPRT afterward?** A clear positive (section 12.A)
  with deltas clearly exceeding the measured noise floor -- and even then, SPRT is a separate,
  later, explicitly authorized decision (section 13), not an automatic next step.

## 18. Open questions carried into execution, not resolved here

- **Resolved (Phase A)**: the Stage-3 split seed and row-budget-equalization seed are no longer
  placeholders -- pinned in section 14 (20261502, 20261503).
- **Resolved (Phase A)**: `BestMoveSelector`'s constructor / `SelfPlayCli` flag addition is
  implemented (section 2), tested, and merged to `phase/15-nnue`.
- **Resolved (Phase A)**: whole-game corpus boundaries verified (section 7a) -- no implementation
  gap existed.
- **Resolved (Phase A)**: byte-identical initialization hardened via `train()`'s new
  `initial_state_dict` parameter (section 8).
- **Remaining before Phase B can start**: none identified as blocking. The E-15 initialization
  artifact itself (section 8's steps 1-2) has not yet been generated -- it is a Phase B/D
  precondition, not a Phase A one, since it depends on nothing this document doesn't already fix
  (the seed, architecture, and construction code are all pinned), and generating it now would be
  indistinguishable from starting Phase B/D's own work in a design-only turn.
- **Remaining, genuinely open**: none. This document's own non-post-hoc-retuning rule (section 15)
  applies to everything pinned above as of this Phase A update -- any future change to the pinned
  seeds, the depth-6 decision, or the duplicate-row/truncation policy requires a new experiment ID,
  not a revision of this one.

## 19. Phase A test summary

| File | New/changed tests | What they prove |
|---|---|---|
| `BestMoveSelectorTest.java` (new) | 5 | no-arg constructor still requests 1; explicit constructor requests the given width; both always choose rank-0; invalid widths (`<1`) rejected; mechanism identity unaffected by width |
| `GameLoopDiversityTest.java` | +1 (`controlMultiPvBestMoveSelectorActuallyWidensTheSearchedCandidateSet`) | `BestMoveSelector(3)` actually causes `Searcher` to return >1 candidates in practice (via the diagnostics sink), decisively proving the seam works end-to-end, not just at the config-record level |
| `SelfPlayCliTest.java` | +5 | `--control-multipv` parses correctly and leaves `diversity` absent; no new flags leaves both absent (backward compatibility); `--control-multipv` + `--diversity-*` together is rejected; a `--control-multipv 3` pilot still records `BEST_MOVE` provenance with no diagnostics file; `--max-positions 1` with `--max-plies 6` still emits one *whole* 6-sample game, not a fragment (section 7a) |
| `trainer/tests/model/test_train.py` (Python) | +2 | `initial_state_dict=None` is behaviorally identical to the pre-E-15 default; an explicit `initial_state_dict`, loaded into two `train()` calls with different seeds and different data, produces byte-identical starting weights in both saved checkpoints |

Full results: `engine-core`+`engine-uci` Java suite 374+36=410 passed (up from 363+36=399 after
#222/#223's design turn), selfplay package alone 96 (up from 85); Python trainer suite 349 passed
(up from 347). Zero regressions in any pre-existing test.
