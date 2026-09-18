# E-16 Phase C: ingestion, shared held-out split, and equalization report

Governs issue #224. Executes `DR-E16-shared-opening-prefix-preregistration.md` Phase C (ingestion,
shared held-out split, quality gate, row-budget equalization) only. No training, no initialization
artifacts, no Phase E metrics, no SPRT. Nothing here changes any pinned seed or split rule -- it
records what happened when the hardened split mechanism (section 4a of the preregistration,
`select_held_out_game_ids` + `split_by_fixed_game_ids`) was actually run against the two frozen
Phase B corpora.

Execution baseline: branch `phase/15-nnue`, commit `563687bd7e2b80f19a4758205d96f8698f125a43`
(`563687b` short form) -- the commit the Phase C split-design hardening itself landed on.

## 1. Frozen-hash re-verification (before touching any data)

| Artifact | Expected SHA-256 | Actual SHA-256 | Match |
|---|---|---|---|
| `e16-control.vspr` | `cd719a5608319498667d1cd6bbd46d8ab97b3e1c921d229203b0dd8bc6252af8` | same | yes |
| `e16-treatment.vspr` | `c2df1e7c46768fc16d7f26a7f9d8acfe6203054b271908a679e7da6c789a68ae` | same | yes |
| `e16-shared-opening-pool.txt` | `c8f02240f3ddbaf8043de0cf4b7fa3d96914cf91ed89f20e19ac8094566b2ce0` | same | yes |

All three match exactly. No arm's VSPR file drifted since Phase B; the opening pool used to check
game-order/opening-index pairing later in this document is the identical pool used to generate it.

## 2. Ingestion (unmodified `selfplay_ingest.py`, #210 path)

No ingestion code was touched for this experiment. Both corpora ingested as distinct dataset
identities, same convention `DR-E15-phase-bc-corpus-generation-report.md` section 6 established:

| | `stage3-e16-control-001` | `stage3-e16-treatment-001` |
|---|---|---|
| Manifest path | `trainer/outputs/selfplay-e16/control/ingested/manifest.json` | `trainer/outputs/selfplay-e16/treatment/ingested/manifest.json` |
| Shard path | `.../ingested/shard-0.bin` | `.../ingested/shard-0.bin` |
| Shard SHA-256 | `d7a79cf83cea2552ac9c1189527d5db740cae89f9532f17823a29a33f7a11b63` | `eb9af931390a7dc0784b29914f36e5b2a60b54dd7cedfb89a975d97d6e796887` |
| Games | 58 | 58 |
| Records | 8,190 | 7,605 |
| Local game-ID range | 0-57 | 0-57 |

Record counts match Phase B's own generation report exactly (control 8,190, treatment 7,605) --
ingestion introduced no sample loss or duplication in either arm. Both manifests came out of
ingestion with `assessment.status = "unassessed"`, as required, and were updated to
`assessed`/`approved` only after every check below completed cleanly (section 7).

## 3. Game-ID universe proof (both arms, before split)

Checked directly per arm, not assumed from the 58-games-completed count alone:

| | Control | Treatment |
|---|---|---|
| Distinct game-ID count | 58 | 58 |
| IDs exactly `{0..57}` | yes | yes |
| Missing IDs | none | none |
| Extra IDs | none | none |
| Source game/opening order preserved | yes | yes |

"Order preserved" means each game ID's rows appear as one contiguous, non-decreasing run in the
ingested record stream (`0,0,...,1,1,...,2,...`) -- the deterministic first-occurrence-order
remapping `_GameIdRemapper` performs on a single-run-per-arm input reproduces the VSPR file's own
`gameId` sequence exactly, since each arm here is exactly one VSPR file with `gameId`s already
`0..57`. Both arms passed; Phase C proceeded to the split.

## 4. Shared held-out game IDs

`select_held_out_game_ids(range(58), seed=20261602, held_out_count=6)` computed once, against the
shared universe, independent of either arm's ingested row counts:

**`{17, 20, 27, 31, 34, 51}`** -- reproduces the value computed and preregistered when the split
mechanism was hardened (`DR-E16-shared-opening-prefix-preregistration.md` section 4a), unchanged.

## 5. Split (`split_by_fixed_game_ids`, identical set, both arms)

| | Control | Treatment |
|---|---|---|
| Train games | 52 | 52 |
| Held-out games | 6 | 6 |
| Train records | 7,595 | 6,911 |
| Held-out records | 595 | 694 |
| Held-out game IDs | `{17, 20, 27, 31, 34, 51}` | `{17, 20, 27, 31, 34, 51}` |

Cross-arm checks (not merely equal counts): held-out game-ID sets are identical between arms;
training game-ID sets are identical between arms (both the same 52 IDs); zero game-ID leakage
within either arm's own train/held-out split; output row order preserved (each arm's train and
held-out record streams are exact original-order subsequences of its own ingested stream). Row
counts differ by arm as expected -- control's 6 held-out games happen to be longer on average than
treatment's, consistent with Phase B's own per-game length data (control min/median/max
43/128/483 plies, treatment 61/112/383).

## 6. Exact-FEN overlap

| Comparison | Overlap count | Fraction of first set | Fraction of second set |
|---|---|---|---|
| Control: train vs. held-out | 0 | 0.0% | 0.0% |
| Treatment: train vs. held-out | 0 | 0.0% | 0.0% |
| Cross-arm held-out (control vs. treatment) | 17 | 2.86% (of control's 595) | 2.45% (of treatment's 694) |
| Cross-arm train (control vs. treatment) | 97 | 1.33% (of control's 7,595) | 1.40% (of treatment's 6,911) |

**Zero within-arm train/held-out overlap in both arms** -- the property that actually matters
(each arm's own held-out set independently tests positions its own training pool never saw) holds
exactly. The small cross-arm overlaps (shared opening prefixes plus early-game convergent lines
that both selectors can independently reach) are a by-construction consequence of both arms
starting from the identical 58-opening pool, not train/held-out leakage within either arm. Reported
descriptively, per instruction; the split is not altered in response.

## 7. Phase-C hard quality gate

Adapted from `DR-E15-stage3-first-retraining-preregistration.md` section 9 to this experiment's own
stopping rule (`maxGames=58`, not an 8,000-position budget):

- zero codec/structural decode failures -- `ingest()`'s own internal `write_shard()`
  count-agreement assertion and post-write header re-validation both passed for both arms
  (unmodified, would raise `AssertionError` on disagreement)
- zero illegal moves -- enforced at generation time (`GameLoop.validateLegal`), already confirmed
  clean in Phase B, unchanged
- zero provenance mismatches -- ingestion re-reads each source VSPR's own header per file; only one
  source file per arm here, so provenance is trivially self-consistent (Phase B's own decision-record
  field-by-field check already confirmed byte-identical config across arms)
- zero game-ID collisions -- `_GameIdRemapper`'s conflict detection raised nothing for either arm
- zero infrastructure-unresolved games -- 0 for both arms (confirmed already in Phase B; ingestion
  changes nothing about game outcomes)
- exact generator identity confirmed -- unchanged from Phase B (network SHA-256/UUID/engine build
  identical across arms)
- intended stopping rule reached: **58/58 games completed, both arms** (this experiment's own
  budget is `maxGames=58`, not a position count, per `DR-E16` section 3's hardened design)

**Result: no hard failures in either arm. Both `stage3-e16-control-001` and
`stage3-e16-treatment-001` are `assessed`/`approved`.** As with E-15, approval means these datasets
are usable for E-16's training experiment -- it does not mean the diversity mechanism is promoted
(`DR-E12` section 3's two-gate separation, unchanged).

## 8. Training-side row-budget equalization (seed 20261603)

Nc (control train records) = 7,595; Nt (treatment train records) = 6,911; N = min(Nc, Nt) = 6,911.
**Control is the larger arm and is the one truncated** -- the opposite of E-15, where control was
also larger, but for a different reason here: E-16's control produces real, varied-length games
(no degenerate collapse), and its particular 52 training-side games simply summed to more rows than
treatment's 52 this time.

| | Control | Treatment |
|---|---|---|
| Pre-equalization count | 7,595 | 6,911 |
| Post-equalization count | 6,911 | 6,911 |
| Removed | 684 (9.01%) | 0 (0%) |

Truncation used a seeded shuffle-and-take (`random.Random(20261603)`, Fisher-Yates shuffle of row
indices, keep the first N in original order) over the training-side row indices only -- held-out
sets in both arms are completely untouched, no deduplication was performed. Final training row
counts are exactly equal (6,911 = 6,911).

**Final equalized Stage-3 row count N = 6,911.**

## 9. Per-game row-count effect of truncation (control, descriptive only)

Random row sub-selection was applied uniformly across all row indices, not per-game, so the
question is whether that happened to concentrate or deplete particular openings. Across control's
52 training-side games (the 6 held-out game IDs `17, 20, 27, 31, 34, 51` are excluded from this
table since they were never subject to equalization):

| Metric | Value |
|---|---|
| Rows removed per game -- min | 4 |
| Rows removed per game -- max | 45 |
| Rows removed per game -- mean | 13.15 |
| Games fully depleted (post-count = 0) | none |

684 rows removed across 52 games at a roughly proportional ~9% rate produces a removed-per-game
range of 4-45, tracking each game's own pre-truncation length (game 18, the corpus's longest at 483
rows, lost the most at 45; game 47, one of the shortest at 43 rows, lost 5) -- consistent with a
uniform random draw across all rows rather than a systematic bias toward or against any particular
opening. No game was fully depleted. Reported descriptively; the equalization design is not
revised in response.

## 10. Frozen artifacts

| Artifact | SHA-256 / hash |
|---|---|
| `stage3-e16-control-001` ingested shard | `d7a79cf83cea2552ac9c1189527d5db740cae89f9532f17823a29a33f7a11b63` |
| `stage3-e16-treatment-001` ingested shard | `eb9af931390a7dc0784b29914f36e5b2a60b54dd7cedfb89a975d97d6e796887` |
| Shared held-out game-ID set | `{17, 20, 27, 31, 34, 51}` |
| Control train membership (pre-equalization) | `385de6daf62946655b10c630fd50bdec08b4b28045dfd647a1050aa9027716cb` |
| Treatment train membership (pre-equalization) | `cbb7246560012e3b5bb537e3f1150eca3de5ebf2faf50d0d02cfc19499454eb5` |
| Control held-out membership | `b6a4f0aa8ecde83a4b69b0e4cf9116f5014bebcb06df0a740429ec9dc3f35dd2` |
| Treatment held-out membership | `998e263734bd31fc7301e0819f03b97abcb4e8048f09cea788eb2b4af34d9bbb` |
| Control equalized-training membership | `ee1443785d21311b77e3c98261b4b6f6ede1a787b761d6adad81de07bc0df862` |
| Treatment equalized-training membership | `cbb7246560012e3b5bb537e3f1150eca3de5ebf2faf50d0d02cfc19499454eb5` |
| Final equalized Stage-3 row count N | 6,911 |

Membership hashes are SHA-256 over the ordered sequence of `(game_id, ply, fen)` for every record
in that slice -- capturing both membership and row order, not just a set of FENs. Note treatment's
train membership hash is unchanged before and after equalization (`cbb7246...`), since treatment
was the smaller arm and was never truncated. Split seed `20261602` and equalization seed
`20261603` were used exactly as pinned; neither, nor any other pinned seed (generation `20261601`,
training `42`/`43`/`44`), was changed after seeing any result. If a regeneration ever becomes
necessary, it must use a new dataset-identifier/experiment identity, not overwrite the artifacts
above.

## 11. Phase D readiness

Both corpora are `assessed`/`approved`. The shared held-out game-ID set is identical across arms
by construction (section 4/5), zero within-arm train/held-out leakage (section 6), and row-budget
equalization is complete and exact (6,911 = 6,911, section 8). Held-out sets for both arms are
intact and untouched by equalization. **Phase D (matched training under seeds 42, 43, 44) is ready
to begin whenever explicitly authorized -- not started by this document.** No training,
initialization artifact, Phase E metric, or SPRT was run as part of this phase.
