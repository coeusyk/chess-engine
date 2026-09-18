# Architecture Graph Audit — D-3: FeatureEncoder + Java/Python Parity CI

- Before commit: `c40114d33f39fb4cf62f2fc4bd9a731a1e020f21`
- After commit: `7954216be7c02ea641651a17df0c3d87811242e8`
- Before: 2602 nodes, 6204 edges
- After: 2663 nodes, 6293 edges
- Node delta: +61 / -0
- Edge delta: +93 / -4

## Architecture Split
_Scoping summary only — the detailed sections below (centrality, degree, boundary report, etc.) remain whole-graph, since those measures are not meaningful computed on a subgraph alone._

## Engine Architecture
- Before: 1543 nodes; After: 1566 nodes
- Node delta: +23 / -0

## Trainer Architecture
- Before: 104 nodes; After: 131 nodes
- Node delta: +27 / -0

## Top 10 nodes by betweenness centrality change
- `Board` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board): 0.0091 -> 0.0086 (-0.0005)
- `MovesGenerator` (engine_core_src_main_java_coeusyk_game_chess_core_movegen_movesgenerator_movesgenerator): 0.0031 -> 0.0029 (-0.0002)
- `TunerPosition` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_tunerposition_tunerposition): 0.0017 -> 0.0016 (-0.0001)
- `.activeFeatureIndices()` (engine_core_src_main_java_coeusyk_game_chess_core_eval_nnue_featureextractor_featureextractor_activefeatureindices): 0.0001 -> 0.0000 (-0.0001)
- `LabelledPosition` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_labelledposition_labelledposition): 0.0014 -> 0.0013 (-0.0001)
- `.isCheckmate()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_ischeckmate): 0.0010 -> 0.0009 (-0.0001)
- `.isStalemate()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_isstalemate): 0.0010 -> 0.0009 (-0.0001)
- `.from()` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_tunerposition_tunerposition_from): 0.0011 -> 0.0011 (-0.0001)
- `Board.java` (engine_core_src_main_java_coeusyk_game_chess_core_models_board): 0.0006 -> 0.0006 (-0.0000)
- `Move` (engine_core_src_main_java_coeusyk_game_chess_core_models_move_move): 0.0009 -> 0.0009 (-0.0000)

## Top 10 nodes by degree change
- `FeatureIndexParityTest` (engine_core_src_test_java_coeusyk_game_chess_core_eval_nnue_featureindexparitytest_featureindexparitytest): 3 -> 5 (+2)
- `.everyCorpusPositionProducesWellFormedIndices()` (engine_core_src_test_java_coeusyk_game_chess_core_eval_nnue_featureindexparitytest_featureindexparitytest_everycorpuspositionproduceswellformedindices): 4 -> 2 (-2)
- `.activeFeatureIndices()` (engine_core_src_main_java_coeusyk_game_chess_core_eval_nnue_featureextractor_featureextractor_activefeatureindices): 6 -> 4 (-2)
- `Test` (engine_core_src_test_java_coeusyk_game_chess_core_eval_nnue_featureindexparitytest_java_test): 3 -> 4 (+1)
- `Board` (board): 10 -> 11 (+1)
- `.getAllOccupancy()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_getalloccupancy): 11 -> 10 (-1)
- `FeatureIndexParityTest.java` (engine_core_src_test_java_coeusyk_game_chess_core_eval_nnue_featureindexparitytest): 4 -> 5 (+1)
- `Piece.java` (engine_core_src_main_java_coeusyk_game_chess_core_models_piece): 32 -> 33 (+1)
- `.startingPositionMatchesHandDerivedIndices()` (engine_core_src_test_java_coeusyk_game_chess_core_eval_nnue_featureindexparitytest_featureindexparitytest_startingpositionmatcheshandderivedindices): 3 -> 2 (-1)
- `4. Feature Encoding Pipeline & Java/Python Parity` (docs_architecture_nnue_trainer_architecture_4_feature_encoding_pipeline_java_python_parity): 1 -> 2 (+1)

## Newly introduced architectural bridge-node candidates
_New nodes ranked by betweenness centrality in the after-graph (>0). Heuristic, not a graphify-defined threshold._
- `.parse()` (engine_core_src_test_java_coeusyk_game_chess_core_eval_nnue_minimaljson_minimaljson_parse): betweenness centrality 0.0000
- `.parseValue()` (engine_core_src_test_java_coeusyk_game_chess_core_eval_nnue_minimaljson_minimaljson_parsevalue): betweenness centrality 0.0000
- `.loadSpec()` (engine_core_src_test_java_coeusyk_game_chess_core_eval_nnue_featurespecconformancetest_featurespecconformancetest_loadspec): betweenness centrality 0.0000
- `.everyCorpusPositionMatchesTheSharedCrossLanguageParityFixture()` (engine_core_src_test_java_coeusyk_game_chess_core_eval_nnue_featureindexparitytest_featureindexparitytest_everycorpuspositionmatchesthesharedcrosslanguageparityfixture): betweenness centrality 0.0000
- `FeatureSpecConformanceTest` (engine_core_src_test_java_coeusyk_game_chess_core_eval_nnue_featurespecconformancetest_featurespecconformancetest): betweenness centrality 0.0000
- `active_feature_indices()` (trainer_trainer_encoding_feature_encoder_active_feature_indices): betweenness centrality 0.0000
- `load_feature_spec()` (trainer_trainer_encoding_feature_spec_load_feature_spec): betweenness centrality 0.0000
- `MinimalJson` (engine_core_src_test_java_coeusyk_game_chess_core_eval_nnue_minimaljson_minimaljson): betweenness centrality 0.0000
- `Research: Executable Feature Specification Patterns` (docs_architecture_research_2026_07_13_executable_feature_specification_research_executable_feature_specification_patterns): betweenness centrality 0.0000
- `feature_spec.py` (trainer_trainer_encoding_feature_spec): betweenness centrality 0.0000

## Newly introduced dependency cycles
_Detected via strongly-connected-components (existence check, not full cycle enumeration)._
- None

## Cross-module dependency changes
- None

## Community changes
_Community IDs are not stable across separate graphify runs — flagged via cohort-overlap (Jaccard < 0.5) among nodes present in both snapshots, not raw ID equality._
- **644 of 2602 common nodes (25%) flagged — this volume is typical Louvain re-clustering instability under any graph perturbation, not evidence of real coupling change. Read this section by checking whether the specific files this PR touched appear below, not by the raw count.**
- `.resolveBound()` (engine_core_src_main_java_coeusyk_game_chess_core_search_searcher_searcher_resolvebound): cohort overlap 0.01
- `.canApplyNullMove()` (engine_core_src_main_java_coeusyk_game_chess_core_search_searcher_searcher_canapplynullmove): cohort overlap 0.01
- `StaticExchangeEvaluator.java` (engine_core_src_main_java_coeusyk_game_chess_core_search_staticexchangeevaluator): cohort overlap 0.01
- `.getPiece()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_getpiece): cohort overlap 0.01
- `.makeMove()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_makemove): cohort overlap 0.01
- `.getAllMoves()` (engine_core_src_main_java_coeusyk_game_chess_core_movegen_movesgenerator_movesgenerator_getallmoves): cohort overlap 0.01
- `.isCapture()` (engine_core_src_main_java_coeusyk_game_chess_core_search_moveorderer_moveorderer_iscapture): cohort overlap 0.01
- `.getLmrReductionForTesting()` (engine_core_src_main_java_coeusyk_game_chess_core_search_searcher_searcher_getlmrreductionfortesting): cohort overlap 0.01
- `MoveOrderer.java` (engine_core_src_main_java_coeusyk_game_chess_core_search_moveorderer): cohort overlap 0.01
- `.probeWDL()` (engine_core_src_main_java_coeusyk_game_chess_core_syzygy_syzygyprober_syzygyprober_probewdl): cohort overlap 0.01
- `.addPromotionMoves()` (engine_core_src_main_java_coeusyk_game_chess_core_movegen_movesgenerator_movesgenerator_addpromotionmoves): cohort overlap 0.01
- `.setRootTtMoveHintForTesting()` (engine_core_src_main_java_coeusyk_game_chess_core_search_searcher_searcher_setrootttmovehintfortesting): cohort overlap 0.02
- `.bruteForceNegamax()` (engine_core_src_test_java_coeusyk_game_chess_core_search_searchertest_searchertest_bruteforcenegamax): cohort overlap 0.02
- `.enPassantCaptureRemovesCorrectPawn()` (engine_core_src_test_java_coeusyk_game_chess_core_movegen_enpassantlegalitytest_enpassantlegalitytest_enpassantcaptureremovescorrectpawn): cohort overlap 0.02
- `.ttBoundGatingWorksForExactLowerUpper()` (engine_core_src_test_java_coeusyk_game_chess_core_search_searchertest_searchertest_ttboundgatingworksforexactlowerupper): cohort overlap 0.02
- ... and 629 more

## New God Nodes (top 15 by degree)
- None

## Architectural cohesion
- maintained (modularity 0.7445 -> 0.7504, delta +0.0059)

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
_Filled in by the engineer/agent reviewing this report — the script only computes the quantitative sections above._

D-3 is the first PR to add real content to *both* sides of the Engine/Trainer split
in one commit: Engine Architecture 1543 → 1566 nodes (+23, all Java **test** scope —
`FeatureSpecConformanceTest`, `MinimalJson`, `RepoPaths`, plus the extended
`FeatureIndexParityTest`), Trainer Architecture 104 → 131 nodes (+27,
`feature_encoder.py`/`feature_spec.py` and their functions). The remaining ~11 of the
+61 total node delta are `docs/architecture/` nodes (the new Feature Specification
files and the research note) — neither Engine nor Trainer by the classifier's module
prefixes, and correctly excluded from both counts.

The "FeatureExtractor coupling changes: No change" line in the Architectural Boundary
Report is the graph-level confirmation of this PR's most load-bearing constraint —
`FeatureExtractor.java` (production, hot-path) has zero behavior change, exactly as
required. The `.activeFeatureIndices()` betweenness/degree changes in the "Top 10"
sections above are a side effect of the graph growing around it (new test callers),
not a change to the method itself — its coupling to other **production** nodes is
unchanged, only its **test-scope** callers grew, which is exactly the shape a new
parity test should produce.

Frozen boundary verdict: **UNCHANGED**. No new dependency cycles, no cross-module
dependency changes, no new God nodes. Modularity held essentially flat (0.7445 →
0.7504, Δ+0.0059) — consistent with two small, self-contained additions (a Python
package's second module, a Java test package's three new files) landing without
blurring existing community structure.

Community-churn and cross-module sections (not reproduced in full here) were checked
against this PR's actual file list before treating them as noise, per this report's
own established convention — none traced to unexpected coupling.

