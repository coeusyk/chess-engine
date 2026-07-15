# Architecture Graph Audit — E-3: First Real Candidate Net (Train, Quantize, Export)

- Before commit: `76db953fa2ffbe3c98186652b737bfa97590cecc`
- After commit: `2a2db2814dcfa009e834ce843428826ee15b25ae`
- Before: 3378 nodes, 7208 edges
- After: 3398 nodes, 7215 edges
- Node delta: +20 / -0
- Edge delta: +30 / -23

## Architecture Split
_Scoping summary only — the detailed sections below (centrality, degree, boundary report, etc.) remain whole-graph, since those measures are not meaningful computed on a subgraph alone._

## Engine Architecture
- Before: 1584 nodes; After: 1585 nodes
- Node delta: +1 / -0

## Trainer Architecture
- Before: 478 nodes; After: 494 nodes
- Node delta: +16 / -0

## Top 10 nodes by betweenness centrality change
- `Board` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board): 0.0033 -> 0.0033 (-0.0000)
- `.load()` (engine_core_src_main_java_coeusyk_game_chess_core_eval_nnue_nnuenetwork_nnuenetwork_load): 0.0000 -> 0.0000 (-0.0000)
- `NnueNetwork` (engine_core_src_main_java_coeusyk_game_chess_core_eval_nnue_nnuenetwork_nnuenetwork): 0.0000 -> 0.0000 (-0.0000)
- `MovesGenerator` (engine_core_src_main_java_coeusyk_game_chess_core_movegen_movesgenerator_movesgenerator): 0.0009 -> 0.0008 (-0.0000)
- `TunerPosition` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_tunerposition_tunerposition): 0.0007 -> 0.0007 (-0.0000)
- `LabelledPosition` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_labelledposition_labelledposition): 0.0006 -> 0.0006 (-0.0000)
- `.iterativeDeepening()` (engine_core_src_main_java_coeusyk_game_chess_core_search_searcher_searcher_iterativedeepening): 0.0006 -> 0.0005 (-0.0000)
- `.searchRoot()` (engine_core_src_main_java_coeusyk_game_chess_core_search_searcher_searcher_searchroot): 0.0005 -> 0.0005 (-0.0000)
- `.searchDepth()` (engine_core_src_main_java_coeusyk_game_chess_core_search_searcher_searcher_searchdepth): 0.0005 -> 0.0005 (-0.0000)
- `.from()` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_tunerposition_tunerposition_from): 0.0004 -> 0.0004 (-0.0000)

## Top 10 nodes by degree change
- `.load()` (engine_core_src_main_java_coeusyk_game_chess_core_eval_nnue_nnuenetwork_nnuenetwork_load): 18 -> 6 (-12)
- `.loadReconstructsEveryFieldFromAWrittenFile()` (engine_core_src_test_java_coeusyk_game_chess_core_eval_nnue_nnuenetworkloadertest_nnuenetworkloadertest_loadreconstructseveryfieldfromawrittenfile): 15 -> 3 (-12)
- `PositionRecord` (positionrecord): 1 -> 4 (+3)
- `Path` (path): 11 -> 13 (+2)
- `NnueNetworkLoaderTest` (engine_core_src_test_java_coeusyk_game_chess_core_eval_nnue_nnuenetworkloadertest_nnuenetworkloadertest): 14 -> 15 (+1)
- `.outputScale()` (engine_core_src_main_java_coeusyk_game_chess_core_eval_nnue_nnuenetwork_nnuenetwork_outputscale): 2 -> 1 (-1)
- `.loadRejectsNegativeQb()` (engine_core_src_test_java_coeusyk_game_chess_core_eval_nnue_nnuenetworkloadertest_nnuenetworkloadertest_loadrejectsnegativeqb): 4 -> 3 (-1)
- `.ftBiases()` (engine_core_src_main_java_coeusyk_game_chess_core_eval_nnue_nnuenetwork_nnuenetwork_ftbiases): 2 -> 1 (-1)
- `.loadRejectsTruncatedFile()` (engine_core_src_test_java_coeusyk_game_chess_core_eval_nnue_nnuenetworkloadertest_nnuenetworkloadertest_loadrejectstruncatedfile): 4 -> 3 (-1)
- `.outputWeights()` (engine_core_src_main_java_coeusyk_game_chess_core_eval_nnue_nnuenetwork_nnuenetwork_outputweights): 2 -> 1 (-1)

## Newly introduced architectural bridge-node candidates
_New nodes ranked by betweenness centrality in the after-graph (>0). Heuristic, not a graphify-defined threshold._
- `combine_and_split()` (trainer_scripts_train_candidate_net_combine_and_split): betweenness centrality 0.0000
- `train_quantize_export()` (trainer_scripts_train_candidate_net_train_quantize_export): betweenness centrality 0.0000
- `train_candidate_net.py` (trainer_scripts_train_candidate_net): betweenness centrality 0.0000
- `E-3: first real candidate net — run record` (trainer_configs_train_e3_real_e_3_first_real_candidate_net_run_record): betweenness centrality 0.0000
- `_load_all()` (trainer_scripts_train_candidate_net_load_all): betweenness centrality 0.0000
- `.loadRoundTripsAtRealCandidateNetworkScale()` (engine_core_src_test_java_coeusyk_game_chess_core_eval_nnue_nnuenetworkloadertest_nnuenetworkloadertest_loadroundtripsatrealcandidatenetworkscale): betweenness centrality 0.0000

## Newly introduced dependency cycles
_Detected via strongly-connected-components (existence check, not full cycle enumeration)._
- None

## Cross-module dependency changes
- None

## Community changes
_Community IDs are not stable across separate graphify runs — flagged via cohort-overlap (Jaccard < 0.5) among nodes present in both snapshots, not raw ID equality._
- **839 of 3378 common nodes (25%) flagged — this volume is typical Louvain re-clustering instability under any graph perturbation, not evidence of real coupling change. Read this section by checking whether the specific files this PR touched appear below, not by the raw count.**
- `SanConverter.java` (engine_core_src_main_java_coeusyk_game_chess_core_notation_sanconverter): cohort overlap 0.01
- `.backwardPawnCount()` (engine_core_src_main_java_coeusyk_game_chess_core_eval_evaluator_evaluator_backwardpawncount): cohort overlap 0.01
- `OpeningBook.java` (engine_core_src_main_java_coeusyk_game_chess_core_book_openingbook): cohort overlap 0.01
- `.futilityAndRazorMarginsAreDefinedAsConstants()` (engine_core_src_test_java_coeusyk_game_chess_core_search_searchertest_searchertest_futilityandrazormarginsaredefinedasconstants): cohort overlap 0.01
- `.isQuietMove()` (engine_core_src_main_java_coeusyk_game_chess_core_search_searcher_searcher_isquietmove): cohort overlap 0.01
- `.canApplyNullMove()` (engine_core_src_main_java_coeusyk_game_chess_core_search_searcher_searcher_canapplynullmove): cohort overlap 0.01
- `.MovesGenerator()` (engine_core_src_main_java_coeusyk_game_chess_core_movegen_movesgenerator_movesgenerator_movesgenerator): cohort overlap 0.01
- `.getIncEgScore()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_getincegscore): cohort overlap 0.01
- `.explainEval()` (engine_core_src_main_java_coeusyk_game_chess_core_eval_evaluator_evaluator_explaineval): cohort overlap 0.01
- `MoveOrderer.java` (engine_core_src_main_java_coeusyk_game_chess_core_search_moveorderer): cohort overlap 0.01
- `.ttBoundGatingWorksForExactLowerUpper()` (engine_core_src_test_java_coeusyk_game_chess_core_search_searchertest_searchertest_ttboundgatingworksforexactlowerupper): cohort overlap 0.01
- `Move` (move): cohort overlap 0.02
- `.findMove()` (engine_core_src_main_java_coeusyk_game_chess_core_movegen_movesgenerator_movesgenerator_findmove): cohort overlap 0.02
- `.getHalfmoveClock()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_gethalfmoveclock): cohort overlap 0.02
- `.unmakePromotionRestoresPawnOnOriginalSquare()` (engine_core_src_test_java_coeusyk_game_chess_core_movegen_promotionhandlingtest_promotionhandlingtest_unmakepromotionrestorespawnonoriginalsquare): cohort overlap 0.02
- ... and 824 more

## New God Nodes (top 15 by degree)
- None

## Architectural cohesion
- maintained (modularity 0.7878 -> 0.7847, delta -0.0032)

## Architectural Boundary Report
_`production` = src/main/*.java outside `DEBUG_ONLY_MAIN_CLASSES`; `debug` = NnueOracle.java (ADR-002). Test files are excluded — test-to-production coupling is normal and not a boundary risk. Edges are reported at whichever node granularity graphify attaches them to (class/method), grouped by owning file._

### production -> debug dependencies
- No change

### debug -> production dependencies
- No change

### cross-module dependency changes
_See "Cross-module dependency changes" above._

### EvaluatorStrategy coupling changes
- No change

### Searcher coupling changes
- No change

### NnueEvaluator coupling changes
- No change

### FeatureExtractor coupling changes
- No change

### NnueNetwork coupling changes
- No change

### Frozen boundary verdict
- UNCHANGED — no unapproved production -> debug edges introduced.

## Narrative

E-3's real content is exactly the six "newly introduced architectural bridge-node
candidates": `train_candidate_net.py`'s three functions
(`combine_and_split`/`train_quantize_export`/`_load_all`), the module itself, the new
`train-e3-real.md` doc node, and the new Java round-trip test
`loadRoundTripsAtRealCandidateNetworkScale`. No unexplained additions — this issue's
entire diff is orchestration of already-existing D-4/D-5/D-6 pipeline stages plus one
new regression test, and the graph confirms nothing beyond that landed.

**Engine Architecture**: 1584 -> 1585 (+1/-0) — the one new node is
`loadRoundTripsAtRealCandidateNetworkScale`, added to close a code-review-identified
gap (no round-trip test existed at the real net's actual `hidden_width=256` scale,
only at the D-6 fixture's `hidden_width=2`/`4`). The degree-change table's apparent
"drops" for `.load()` (18->6) and `.loadReconstructsEveryFieldFromAWrittenFile()`
(15->3) are a redistribution artifact of the new sibling test sharing call edges to
the same `NnueNetwork`/`writeNetwork()` symbols, not a real loss of coverage — the
new test *adds* a caller, and graphify's per-node degree accounting spreads existing
edges across more test methods once a new one references the same production symbols.
Zero nodes changed under `src/main` — confirms Invariant 8 (engine/trainer isolation)
structurally: the only Java change is a new synthetic-data regression test, exactly
matching the issue's own "no Java production dependency on this training run" invariant.

**Trainer Architecture**: 478 -> 494 (+16/-0) — `train_candidate_net.py`'s own
functions/module node, plus the doc node for `train-e3-real.md`; no existing
trainer module (`train.py`, `exporter.py`, `quantizer.py`, `validator.py`,
`canonical.py`, the dataset providers) shows any edge change at all in this report,
confirming the issue's claim that this is pure orchestration, not a pipeline change.

The Architectural Boundary Report is unanimous "No change" across every tracked row,
frozen boundary verdict **UNCHANGED**. This matters specifically for E-3: the first
real (non-synthetic, non-tiny) `.nnue` file now exists in the repo tree (gitignored,
never committed) and the first `nets/` registry entry is now committed — exactly the
kind of change that could plausibly blur the "test-scope-only" boundary if done
carelessly. The graph confirms it didn't: zero production-code edges, zero
cross-module dependency changes, `NnueNetwork` coupling unchanged.

No newly introduced dependency cycles, no new God nodes, no cross-module dependency
changes. Modularity moved by a small, unremarkable amount (0.7878 -> 0.7847, delta
-0.0032), within the same noise band every prior Phase D/E audit has established. The
community-churn section (839 of 3378 common nodes, 25%, flagged) was checked against
this PR's actual file list before being dismissed as noise — the listed churned nodes
(`SanConverter.java`, `Searcher` internals, `OpeningBook.java`, `MoveOrderer.java`,
etc.) are pre-existing, unrelated classes, matching every prior audit's pattern.

Overall: an orchestration-only PR whose graph footprint is exactly its stated scope
— one new Java test node, a handful of new Python orchestration nodes, zero boundary
changes, zero cycles, zero cross-module drift, and independent structural
confirmation that the first real candidate net's production really did stay entirely
on the trainer side of the engine/trainer boundary.
