# E-15 Phase B/C corpus generation and assessment report

Governs issue #223. Executes `DR-E15-stage3-first-retraining-preregistration.md` Phase B
(matched corpus generation) and Phase C (ingestion + assessment) only. No training, no SPRT.
Nothing in this document changes any preregistered parameter; it records what actually happened
when the preregistered configuration was executed.

Execution baseline: branch `phase/15-nnue`, engine source commit
`42251899518196f348710afb341d29373a576b53` (`4225189` short form), the same commit the
preregistration document itself was committed at. Generator network: runtime NNUE
`trainer/outputs/selfplay-bootstrap/p3a001-export/013b548c-303b-4156-a6ba-367400de3eb2.nnue`
(SHA-256 `8dc03caa0429bf03ca92165fd4de95ad9550d57796ba9a2f45aecfa4e05bb02d`), exported from training
checkpoint `trainer/outputs/phase3/P3A-001/checkpoints/step-016999.pt` (SHA-256
`c2c33d1dd7af5345d8772aa0761e79d5b9bc8bff7aff409b946078684c08d0ef`) -- both re-verified against
disk immediately before generation, both exact matches to the preregistered values. Runtime
environment: OpenJDK 21.0.12 (Ubuntu build), Linux 6.18.33.2-microsoft-standard-WSL2 (WSL2), AMD
Ryzen 7 7700X. `engineBuildId` is operator-attested (the git commit SHA above), per DR-E15 section
1's explicit non-cryptographic-verification caveat -- not re-derived independently here either.

## 1. Generator configs (both arms, exactly as preregistered)

| Field | Control | Treatment |
|---|---|---|
| Network UUID | `013b548c-303b-4156-a6ba-367400de3eb2` | identical |
| Network SHA-256 | `8dc03caa...5bb02d` | identical |
| Engine build ID | `42251899518196f348710afb341d29373a576b53` | identical |
| Search budget | DEPTH 6 | identical |
| `maxPlies` | 500 | identical |
| `maxGames` | 150 | identical |
| `maxPositions` | 8000 | identical |
| Generation seed | 20261501 | identical |
| Selector | `BestMoveSelector(3)` (`--control-multipv 3`) | `SeededDiversitySelector(maxRank=3, cpLossBoundCentipawns=40, temperature=20.0)` |

No parameter other than selector identity/config differs between the two invocations. Both were
launched from the same classpath (`engine-core/target/classes` plus the two runtime dependency
jars, `slf4j-api` and `logback-classic`) against the same compiled `engine-core` build.

## 2. Control-arm generation

- Wall-clock: 3,047s (~50.8 min), inside the 90-minute cap.
- Games: 58 attempted, 58 completed, 0 hard failures, 0 unresolved-infrastructure terminations.
- Samples: 8,120 (target 8,000 reached and the in-progress game finished in full, per the
  between-games-only stop rule).
- VSPR: `trainer/outputs/selfplay-e15/control/e15-control.vspr`, SHA-256
  `c9a0e8b6d172ac5f9636d77ba8be601f58a4254eb091689de5a7d25dcc40df88`.
- `vsprRunIdHex`: `f231f2e1b960499bb2bff25bdf4bd025`.

## 3. Treatment-arm generation

- Wall-clock: 3,899s (~65.0 min), inside the 90-minute cap.
- Games: 52 attempted, 52 completed, 0 hard failures, 0 unresolved-infrastructure terminations.
- Samples: 8,046.
- VSPR: `trainer/outputs/selfplay-e15/treatment/e15-treatment.vspr`, SHA-256
  `3e746f8d16e17319a9eff70eb53f8dad5c0167beffeedd8b64ba009c484f9e7a`.
- `vsprRunIdHex`: `18769914dfbc4d0a837570978b8437c5`.
- Diversity diagnostics: `trainer/outputs/selfplay-e15/treatment/e15-treatment.vspr.diversity-diagnostics.csv`.

## 4. Matched-generation sanity check

Every research-relevant parameter recorded in each arm's own decision record
(`e15-control-decision.json` / `e15-treatment-decision.json`) is identical except for
`gamesAttempted`/`gamesCompleted` (58 vs. 52, expected -- different selectors produce different
game lengths against the same 8,000-sample target), `outputVsprSha256`, and `vsprRunIdHex`
(expected, distinct output files). `networkUuid`, `networkSha256`, `engineBuildId`,
`searchBudgetKind`/`Value`, `maxPlies`, `seed`, `maxGames`, `maxPositions`, and
`eligibilitySmokePassed` are byte-identical across both records. Phase B is **not** invalid: no
unexpected parameter differs.

## 5. Java / Python VSPR codec verification

Both files decoded independently via the existing, unmodified Java `VsprCodec.read()` (invoked
through `jshell` against the compiled `engine-core` classpath, no new decoder written) and the
existing Python `trainer.vspr.read()`. Both decodes agree exactly, for both arms, on: game count,
sample count, network UUID, network SHA-256, engine build ID, search budget value, max plies, run
ID, outcome distribution, termination-reason distribution, and unique game-ID count. **Zero
Java/Python codec disagreement.**

## 6. Ingestion (unmodified `selfplay_ingest.py`, #210 path)

No ingestion code was modified. Both corpora ingested as distinct dataset identities:

| | `stage3-e15-control-001` | `stage3-e15-treatment-001` |
|---|---|---|
| Manifest path | `trainer/outputs/selfplay-e15/control/ingested/manifest.json` | `trainer/outputs/selfplay-e15/treatment/ingested/manifest.json` |
| Shard path | `.../ingested/shard-0.bin` | `.../ingested/shard-0.bin` |
| Shard SHA-256 | `bbbaff26ecbd921f11c4ab7d7f4af5196801a51edef8c212fac974b22cc24ae4` | `dadbfbd6d98923dde00699cc93adfabbf303392ab2ed16082e9519d11ea37ad0` |
| Games | 58 | 52 |
| Records | 8,120 | 8,046 |
| Local game-ID range | 0-57 | 0-51 |

Both manifests came out of ingestion with `assessment.status = "unassessed"` as required, and were
only updated to `assessed`/`approved` (evidence pointing at this document) after every Phase-C
check below completed cleanly. No dataset-identifier was reused across a regeneration.

## 7. Grouped Stage-3 split (`split_by_game`, seed 20261502, `held_out_fraction=0.10`)

| | Control | Treatment |
|---|---|---|
| Train games | 52 | 46 |
| Held-out games | 6 | 6 |
| Train records | 7,280 | 7,155 |
| Held-out records | 840 | 891 |
| Train membership hash | `51f99416dafccb18266502df7d141d5506c5eebb7845bbe7534f6e401eee3b5c` | `dca826a6109a0f21c4d57ce97a020083ea98aec426505dddf7a5b094c09dfc69` |
| Held-out membership hash | `b1f6b8a5d902bcd53eafdcd892305058df738e6c8740ede84fc1c62dd8bd411b` | `0bdfeddfe36d859c5913f80dce4fb200b218b2bed60dbf30bb9b28fb3d057940` |

No game appears on both sides of its own arm's split (checked directly: empty intersection, both
arms). Held-out row counts land close to the ~10% target given whole-game granularity (control
840/8120 = 10.3%; treatment 891/8046 = 11.1%).

## 8. Row-budget equalization (seed 20261503, training-side pool only)

Nc (control train records) = 7,280; Nt (treatment train records) = 7,155; N = min(Nc, Nt) = 7,155.
Control is the larger arm and is the one truncated.

| | Control | Treatment |
|---|---|---|
| Pre-equalization count | 7,280 | 7,155 |
| Post-equalization count | 7,155 | 7,155 |
| Removed | 125 (1.72%) | 0 |
| Selected-membership hash | `c7f7b71194d807e8d636c83dd17ae2d2a055e67f62f45e4e4cc52356259ed0bd` | `d7f217b49963de92de922a0d0a5364b3c6a9ed2b9d37545c1c48bdab7dd79e91` |

Truncation used a seeded shuffle-and-take over the training-side row indices only (never
held-out, never moved between train/held-out); final training row counts are exactly equal (7,155
= 7,155), satisfying DR-E15 section 7's exact-equalization requirement.

## 9. Phase-C hard quality gate

All hard requirements (DR-E15 section 9) checked for both arms: zero codec/structural decode
failures, zero illegal moves (enforced at generation time, unviolated), zero provenance
mismatches, zero game-ID collisions, zero infrastructure-unresolved games, exact generator
identity confirmed (network SHA-256 + UUID + engine build, both arms), and each arm reached its
8,000-position budget (control landed at 8,120, treatment at 8,046 -- both "+the last game's
overrun," as designed). **No hard failures in either arm.**

**Assessment result: both `stage3-e15-control-001` and `stage3-e15-treatment-001` are `assessed` /
`approved`.** This means the datasets are usable for E-15's training experiment -- it does not mean
the generating network or the diversity mechanism is promoted (DR-E12 section 3's two-gate
separation, unchanged).

## 10. Descriptive corpus report

### Size

| | Control | Treatment |
|---|---|---|
| Games | 58 | 52 |
| Raw samples | 8,120 | 8,046 |
| Stage-3 training rows (post grouped-split) | 7,280 | 7,155 |
| Held-out rows | 840 | 891 |
| Final equalized training rows | 7,155 | 7,155 |

### Diversity

| | Control | Treatment |
|---|---|---|
| Unique full trajectories | **1** | 52 |
| Duplicate trajectories | 57 | 0 |
| Unique FENs | **140** | 7,850 |
| Duplicate FENs | 7,980 | 196 |
| Duplicate-position rate | **98.28%** | 2.44% |

### Game distribution

| | Control | Treatment |
|---|---|---|
| Game length min / median / p90 / max (plies) | 140 / 140 / 140 / 140 | 58 / 126.5 / 290.6 / 397 |
| White wins | 0 | 28 |
| Black wins | 0 | 20 |
| Draws | 58 | 4 |
| Termination reasons | THREEFOLD_REPETITION: 58 | CHECKMATE: 48, FIFTY_MOVE_RULE: 2, INSUFFICIENT_MATERIAL: 1, THREEFOLD_REPETITION: 1 |
| Infrastructure-unresolved | 0 | 0 |

### Label distribution

| | Control | Treatment |
|---|---|---|
| CP sample count | 8,120 | 7,679 |
| Mate sample count | 0 | 367 |
| CP score min / median / p90 / max | -120 / 0 / 95 / 126 | -1094 / 18 / 342 / 948 |

`label.wdl` is populated on every Stage-3 record by the unmodified `selfplay_ingest.py` path
regardless (DR-E15 section 4) but is not analyzed further here since `wdl_lambda=1.0` means this
experiment's training does not use it.

### Control/treatment overlap

Raw (pre-split), across the full generated corpora: control has 140 unique FENs, treatment has
7,850; 5 FENs are shared between the two full corpora. Restricted to each arm's own Stage-3
**training** split (post grouped-split, pre-equalization, the set that actually matters for the
mixing rule): control has 140 unique training FENs, treatment has 6,992; 5 FENs are shared. That
is 3.57% of control's training FENs also appearing in treatment, and 0.072% of treatment's
training FENs also appearing in control. This overlap is a by-construction property of both arms
starting every game from the identical standard starting position (DR-E15 section 7's own
disclosed expectation) -- not train/held-out leakage within either arm, and not removed.

## 11. Treatment-specific quality-cost report

From `e15-treatment.vspr.diversity-diagnostics.csv`, 8,046 selection events (one per Stage-3
sample):

| Metric | Value |
|---|---|
| Rank-1 (rank 0) selections | 4,732 (58.81%) |
| Rank-2 (rank 1) selections | 1,968 (24.46%) |
| Rank-3 (rank 2) selections | 1,346 (16.73%) |
| Changed-move fraction (rank != 0) | 3,314 / 8,046 = 41.19% |
| Single-candidate events (`candidateCount==1`, no stochastic choice possible) | 196 (2.44%) |
| Stochastic-eligible fraction | 97.56% |
| Mate-policy incidence | 367 |
| CP-loss mean / median / p90 / max | 3.19 / 0 / 14 / 40 |

Read descriptively only, per DR-E15 section 12's instruction: a low median/mean CP loss describes
the selector's bounded-cost design working as intended, it is not evidence the resulting corpus
trains a better network -- that is Phase E's question, not this document's.

## 12. Control degeneracy (data, not a defect -- DR-E15 section 13)

**The control corpus collapsed to exactly one repeated 140-ply trajectory across all 58 games.**
Every game has identical length (140 plies), identical outcome (`DRAW`), identical termination
(`THREEFOLD_REPETITION`), and the entire 8,120-sample corpus contains only 140 unique FENs (a
98.28% duplicate-position rate). This is the single-repeated-trajectory scenario DR-E15 section 13
explicitly anticipated and preregistered a response to: **report it exactly, do not deduplicate
it, do not add opening randomization, do not alter the selector or its seed handling, and do not
"repair" the control after observing it.** None of those things were done. The corpus was ingested,
split, and equalized through the exact same unmodified pipeline as the treatment arm, with no
arm-specific special-casing.

This outcome is fully explained by an already-documented, non-surprising mechanism: DR-E15 section
14 records that `BestMoveSelector` ignores its `seed` argument entirely and is deterministic
regardless of its value, and the network/engine/search configuration is otherwise identical every
game. Once the resulting move sequence hits a repetition-drawing line, every subsequent game
(different `gameId`, same deterministic engine state, same starting position) reproduces that
exact line move-for-move. This is exactly the "appropriately weak" control DR-E15 section 6
anticipated a deterministic rank-0 control would produce, not an infrastructure defect -- and it is
retained as-is, per instruction.

## 13. Frozen artifacts (this experiment's provenance, not to be regenerated under the same identity)

| Artifact | SHA-256 |
|---|---|
| `e15-control.vspr` | `c9a0e8b6d172ac5f9636d77ba8be601f58a4254eb091689de5a7d25dcc40df88` |
| `e15-treatment.vspr` | `3e746f8d16e17319a9eff70eb53f8dad5c0167beffeedd8b64ba009c484f9e7a` |
| `stage3-e15-control-001` shard | `bbbaff26ecbd921f11c4ab7d7f4af5196801a51edef8c212fac974b22cc24ae4` |
| `stage3-e15-treatment-001` shard | `dadbfbd6d98923dde00699cc93adfabbf303392ab2ed16082e9519d11ea37ad0` |
| Control train membership | `51f99416dafccb18266502df7d141d5506c5eebb7845bbe7534f6e401eee3b5c` |
| Control held-out membership | `b1f6b8a5d902bcd53eafdcd892305058df738e6c8740ede84fc1c62dd8bd411b` |
| Treatment train membership | `dca826a6109a0f21c4d57ce97a020083ea98aec426505dddf7a5b094c09dfc69` |
| Treatment held-out membership | `0bdfeddfe36d859c5913f80dce4fb200b218b2bed60dbf30bb9b28fb3d057940` |
| Control equalized-training membership | `c7f7b71194d807e8d636c83dd17ae2d2a055e67f62f45e4e4cc52356259ed0bd` |
| Treatment equalized-training membership | `d7f217b49963de92de922a0d0a5364b3c6a9ed2b9d37545c1c48bdab7dd79e91` |

Generation seed 20261501, split seed 20261502, and equalization seed 20261503 were used exactly as
preregistered; none were changed after seeing any result. If a regeneration ever becomes
necessary, it must use a new dataset-identifier/experiment identity, not overwrite the artifacts
above.

## 14. Phase D readiness

Both corpora are `assessed`/`approved`. Row-budget equalization is complete and exact (7,155 =
7,155). Held-out sets for both arms are intact and untouched by equalization. **Phase D (matched
training) is ready to begin whenever explicitly authorized -- not started by this document.** No
SPRT, no checkpoint construction, no training run occurred as part of Phase B/C.
