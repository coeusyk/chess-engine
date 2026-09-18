# E-16 Phase B: shared-opening-prefix corpus generation report

Governs issue #224. Executes `DR-E16-shared-opening-prefix-preregistration.md` Phase B (corpus
generation) only. No ingestion, no `split_by_game`, no row-budget equalization, no training, no
SPRT. Downstream of #223 (E-15, closed) and this repository's `DR-M1-cp-only-noise-floor-
characterization.md`.

Execution baseline: branch `phase/15-nnue`, engine source commit
`3ea385f903c596412805f24bf7980f34795a61f6` (`3ea385f` short form) -- the same commit both `Phase
B prerequisite` commits landed on, re-verified against `git rev-parse HEAD` immediately before
generation, matching the task's `engine-build-id` requirement exactly (`engineBuildId` is
operator-attested, same convention `DR-E15-phase-bc-corpus-generation-report.md` section 1
established -- no packaged JAR/build-identity utility exists in this repository).

## 1. Preflight (all reproduced/re-verified before generation, not assumed)

- Working tree clean, local `phase/15-nnue` in sync with `origin/phase/15-nnue` at `3ea385f`.
- Opening-pool SHA-256 re-verified against disk:
  `c8f02240f3ddbaf8043de0cf4b7fa3d96914cf91ed89f20e19ac8094566b2ce0` -- matches
  `DR-E16-shared-opening-prefix-preregistration.md` section 2/8 exactly.
- Generator network re-verified against disk:
  `trainer/outputs/selfplay-bootstrap/p3a001-export/013b548c-303b-4156-a6ba-367400de3eb2.nnue`,
  SHA-256 `8dc03caa0429bf03ca92165fd4de95ad9550d57796ba9a2f45aecfa4e05bb02d` -- matches E-15's own
  pinned artifact (`DR-E15-stage3-first-retraining-preregistration.md` section 1) exactly. This is
  a deliberate reuse, not a re-export -- E-16 trains on the same generator network E-15 used, so
  only the opening-prefix mechanism and selector policy differ between the two experiments.
- Engine `engine-core` compiled clean from `3ea385f` (`mvn -pl engine-core compile`, no errors).
- All 58 opening-pool FENs independently re-parsed via `Board(fen)` and confirmed valid (a
  standalone `jshell` check, separate from `SelfPlayCli`'s own eager validation) -- 58/58 valid.

## 2. Control-arm generation

- Command: `SelfPlayCli --network .../013b548c-....nnue --network-sha256 8dc03caa...
  --network-uuid 013b548c-303b-4156-a6ba-367400de3eb2 --engine-build-id 3ea385f9...
  --search-depth 6 --max-plies 500 --max-games 58 --seed 20261601 --control-multipv 3
  --start-fen-file bench/nnue-corpus/e16-shared-opening-pool.txt` (no `--max-positions`).
- Wall-clock: 4,330s (72.2 min), inside the 90-minute cap (17.8 min headroom).
- Eligibility smoke: passed (network SHA-256/UUID match, three legality probes clean).
- Games: 58 attempted, 58 completed, 0 hard failures, 0 unresolved-infrastructure terminations.
- Samples: 8,190.
- VSPR: `trainer/outputs/selfplay-e16/control/e16-control.vspr`, SHA-256
  `cd719a5608319498667d1cd6bbd46d8ab97b3e1c921d229203b0dd8bc6252af8`.
- `vsprRunIdHex`: `e3718843b8584ba0b1254ce387c5300f`.
- Decision record: `trainer/outputs/selfplay-e16/control/e16-control-decision.json`.

## 3. Treatment-arm generation

- Command: identical to section 2 except `--control-multipv 3` replaced by
  `--diversity-max-rank 3 --diversity-cp-loss-bound 40 --diversity-temperature 20.0`, and the
  output paths.
- Wall-clock: 3,696s (61.6 min), inside the 90-minute cap (28.4 min headroom).
- Eligibility smoke: passed, identical evidence to control.
- Games: 58 attempted, 58 completed, 0 hard failures, 0 unresolved-infrastructure terminations.
- Samples: 7,605.
- VSPR: `trainer/outputs/selfplay-e16/treatment/e16-treatment.vspr`, SHA-256
  `c2df1e7c46768fc16d7f26a7f9d8acfe6203054b271908a679e7da6c789a68ae`.
- `vsprRunIdHex`: `2ebaf0678a0f4d0684fd99ec17f57649`.
- Diversity diagnostics: `trainer/outputs/selfplay-e16/treatment/e16-treatment.vspr.diversity-diagnostics.csv`.
- Decision record: `trainer/outputs/selfplay-e16/treatment/e16-treatment-decision.json`.

## 4. Config-diff check (both decision records, field by field)

Every field agrees exactly between the two decision records **except** the three that are
supposed to differ: `outputVsprPath`, `outputVsprSha256`, `vsprRunIdHex`. `networkUuid`,
`networkSha256`, `engineBuildId`, `searchBudgetKind`/`Value` (`DEPTH`/`6`), `maxPlies` (`500`),
`seed` (`20261601`), `maxGames` (`58`), `maxPositions` (`null`), and the full
`eligibilityEvidence` string are byte-identical across both records. **No unintended configuration
drift between arms** -- the only real difference (selector policy) isn't itself a `DecisionRecord`
field, since it's encoded in which CLI selector flags were passed, not in the generator config
JSON; that policy difference is confirmed instead by section 6's rank-frequency/CP-loss data
(control has none, since `BestMoveSelector` writes no diagnostics file at all) and by section 5's
`SelectionMechanismKind` check below.

## 5. Post-generation verification (both arms)

All of the following were checked directly, not assumed:

- **58 completed games, both arms** -- confirmed via each arm's own decision record
  (`gamesCompleted: 58`) and independently via VSPR frame count (`len(frames) == 58`, both arms).
- **`gameId`s are exactly `0..57`, both arms, both codecs** -- Python (`trainer.vspr.read`) and
  Java (`VsprCodec.read`) decodes agree: `frames[i].game_id == i` for all `i`, both arms.
- **Java/Python VSPR decode agreement** -- both codecs report identical game counts, identical
  total sample counts, and identical `runId` bytes for both arms:

  | | Java | Python |
  |---|---|---|
  | Control games/samples/runId | 58 / 8190 / `e3718843b8584ba0b1254ce387c5300f` | 58 / 8190 / `e3718843b8584ba0b1254ce387c5300f` |
  | Treatment games/samples/runId | 58 / 7605 / `2ebaf0678a0f4d0684fd99ec17f57649` | 58 / 7605 / `2ebaf0678a0f4d0684fd99ec17f57649` |

  **Zero Java/Python codec disagreement**, matching `DR-E15-phase-bc-corpus-generation-report.md`
  section 5's own precedent.
- **Game `i` in both arms starts from opening-pool line `i`** -- checked for all 58 indices in
  both arms independently (`control.frames[i].samples[0].fen == pool[i]` and same for treatment,
  0 mismatches each) **and** cross-arm (`control.frames[i].samples[0].fen ==
  treatment.frames[i].samples[0].fen`, 0 mismatches across all 58 indices) -- the pairing this
  design exists to guarantee holds exactly, by construction, not by chance alignment.

## 6. Descriptive corpus report

### Size, outcomes, terminations

| | Control | Treatment |
|---|---|---|
| Games | 58 | 58 |
| Samples | 8,190 | 7,605 |
| Game length min / median / max (plies) | 43 / 128 / 483 | 61 / 112 / 383 |
| White wins | 20 | 36 |
| Black wins | 31 | 18 |
| Draws | 7 | 4 |
| Termination reasons | CHECKMATE: 51, THREEFOLD_REPETITION: 4, FIFTY_MOVE_RULE: 3 | CHECKMATE: 54, FIFTY_MOVE_RULE: 1, THREEFOLD_REPETITION: 2, INSUFFICIENT_MATERIAL: 1 |
| Infrastructure-unresolved | 0 | 0 |

**The degenerate-control problem `DR-E15-phase-bc-corpus-generation-report.md` section 12
disclosed is fixed, confirmed directly, not merely by design intent**: E-15's control collapsed to
one repeated 140-ply drawn trajectory across all 58 games (98.28% duplicate-position rate,
0 decisive outcomes). E-16's control, given 58 distinct real book openings instead of one fixed
starting position, produces 58 distinct games with 51 decisive checkmates and only 7 draws --
`BestMoveSelector`'s determinism no longer collapses the corpus, because it is no longer replaying
the same position from the same start every game.

### Diversity

| | Control | Treatment |
|---|---|---|
| Unique full trajectories | 58 / 58 | 58 / 58 |
| Unique FENs | 7,869 | 7,601 |
| Duplicate-position rate | 3.92% | 0.05% |

Both figures are an order of magnitude (control) or two orders of magnitude (treatment) better
than E-15's own 98.28% (control) / 2.44% (treatment) -- the shared-opening-prefix design's whole
purpose (giving the control real position diversity instead of none) is directly evidenced here,
not just architecturally plausible.

## 7. Treatment-specific quality-cost report

From `e16-treatment.vspr.diversity-diagnostics.csv`, 7,605 selection events (one per treatment
sample):

| Metric | Value |
|---|---|
| Rank-1 (rank 0) selections | 4,634 (60.93%) |
| Rank-2 (rank 1) selections | 1,800 (23.67%) |
| Rank-3 (rank 2) selections | 1,171 (15.40%) |
| Changed-move fraction (rank != 0) | 2,971 / 7,605 = 39.07% |
| Single-candidate events (`candidateCount==1`, no stochastic choice possible) | 157 (2.06%) |
| Mate-policy incidence | 400 |
| CP-loss mean / median / max (cp-labeled events, n=7,205) | 3.24 / 0 / 40 |

Read descriptively only, per `DR-E15`/`DR-E16`'s own convention: a low median/mean CP loss
describes the bounded-cost selector design working as intended, not evidence about training
outcome -- that is Phase E's question, not this document's. Broadly consistent with E-15's own
treatment-arm quality-cost profile (58.81%/24.46%/16.73% rank split, 41.19% changed-move fraction,
mean/median/max CP-loss 3.19/0/40) -- the selector's own behavior is stable across the two
experiments, as expected since `SeededDiversitySelector`'s parameters are unchanged.

## 8. Frozen artifacts (this experiment's provenance, not to be regenerated under the same identity)

| Artifact | SHA-256 |
|---|---|
| `e16-control.vspr` | `cd719a5608319498667d1cd6bbd46d8ab97b3e1c921d229203b0dd8bc6252af8` |
| `e16-treatment.vspr` | `c2df1e7c46768fc16d7f26a7f9d8acfe6203054b271908a679e7da6c789a68ae` |
| Opening pool (`bench/nnue-corpus/e16-shared-opening-pool.txt`) | `c8f02240f3ddbaf8043de0cf4b7fa3d96914cf91ed89f20e19ac8094566b2ce0` (unchanged, re-verified) |
| Generator network | `8dc03caa0429bf03ca92165fd4de95ad9550d57796ba9a2f45aecfa4e05bb02d` (unchanged, reused from E-15) |

Generation seed `20261601` was used exactly as preregistered for both arms; not changed after
seeing any result. If a regeneration ever becomes necessary, it must use a new dataset-identifier/
experiment identity, not overwrite the artifacts above.

## 9. What was deliberately not done in this phase

No ingestion (`selfplay_ingest.py`), no `split_by_game` grouped split, no row-budget equalization,
no training, no SPRT. The Stage-3 split seed (`20261602`) and row-budget-equalization seed
(`20261603`) pinned in `DR-E16-shared-opening-prefix-preregistration.md` section 4 are not yet
used -- they govern Phase C, which has not started. `#224` remains open for Phase C.

## 10. Phase C readiness

Both arms generated to completion under byte-identical configuration (network, engine build,
search budget, max plies, seed, `maxGames`/`maxPositions` stopping rule), differing only in
selector policy, and are correctly paired at the opening-index level end to end (section 5). Both
VSPR files decode identically under the Java and Python codecs. **Phase C (ingestion +
`split_by_game` + row-budget equalization, per `DR-E16` section 4/8's pinned seeds) is ready to
begin whenever explicitly authorized -- not started by this document.**
