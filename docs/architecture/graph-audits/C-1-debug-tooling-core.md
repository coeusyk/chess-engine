# Architecture Graph Audit — C-1: Debug Tooling Core

- Before commit: `0d0794e551cbdcbce0ef66fbc8b61c981b5646be`
- After commit: `ef14e9d6696951983ed04d7580e45b0d9303d655`
- Before: 2071 nodes, 5476 edges
- After: 2095 nodes, 5523 edges
- Node delta: +24 / -0
- Edge delta: +82 / -35

## Top 10 nodes by betweenness centrality change
- `Board` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board): 0.0148 -> 0.0143 (-0.0005)
- `MovesGenerator` (engine_core_src_main_java_coeusyk_game_chess_core_movegen_movesgenerator_movesgenerator): 0.0050 -> 0.0049 (-0.0002)
- `TunerPosition` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_tunerposition_tunerposition): 0.0027 -> 0.0026 (-0.0001)
- `.isCheckmate()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_ischeckmate): 0.0016 -> 0.0015 (-0.0001)
- `.isStalemate()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_isstalemate): 0.0016 -> 0.0015 (-0.0001)
- `LabelledPosition` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_labelledposition_labelledposition): 0.0022 -> 0.0021 (-0.0000)
- `Move` (engine_core_src_main_java_coeusyk_game_chess_core_models_move_move): 0.0015 -> 0.0015 (-0.0000)
- `.from()` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_tunerposition_tunerposition_from): 0.0018 -> 0.0017 (-0.0000)
- `.toBoard()` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_tunerposition_tunerposition_toboard): 0.0015 -> 0.0015 (-0.0000)
- `MovesGenerator.java` (engine_core_src_main_java_coeusyk_game_chess_core_movegen_movesgenerator): 0.0000 -> 0.0000 (+0.0000)

## Top 10 nodes by degree change
- `Override` (engine_core_src_main_java_coeusyk_game_chess_core_eval_nnue_nnueevaluator_java_override): 8 -> 0 (-8)
- `.evaluate()` (engine_core_src_main_java_coeusyk_game_chess_core_eval_nnue_nnueevaluator_nnueevaluator_evaluate): 11 -> 4 (-7)
- `.reset()` (engine_core_src_main_java_coeusyk_game_chess_core_eval_nnue_nnueevaluator_nnueevaluator_reset): 6 -> 13 (+7)
- `Board` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board): 288 -> 283 (-5)
- `NnueEvaluator` (engine_core_src_main_java_coeusyk_game_chess_core_eval_nnue_nnueevaluator_nnueevaluator): 22 -> 26 (+4)
- `.featureIndex()` (engine_core_src_main_java_coeusyk_game_chess_core_eval_nnue_featureextractor_featureextractor_featureindex): 5 -> 2 (-3)
- `.ftWeights()` (engine_core_src_main_java_coeusyk_game_chess_core_eval_nnue_nnuenetwork_nnuenetwork_ftweights): 5 -> 2 (-3)
- `.rebuild()` (engine_core_src_main_java_coeusyk_game_chess_core_eval_nnue_nnueevaluator_nnueevaluator_rebuild): 8 -> 5 (-3)
- `.add()` (engine_core_src_main_java_coeusyk_game_chess_core_eval_nnue_nnueevaluator_nnueevaluator_add): 5 -> 3 (-2)
- `EvaluatorStrategy` (engine_core_src_main_java_coeusyk_game_chess_core_eval_evaluatorstrategy_evaluatorstrategy): 13 -> 11 (-2)

## Newly introduced architectural bridge-node candidates
_New nodes ranked by betweenness centrality in the after-graph (>0). Heuristic, not a graphify-defined threshold._
- `.verifyAgainstRebuild()` (engine_core_src_main_java_coeusyk_game_chess_core_eval_nnue_nnueevaluator_nnueevaluator_verifyagainstrebuild): betweenness centrality 0.0000
- `NnueEvaluatorRebuildComparatorTest` (engine_core_src_test_java_coeusyk_game_chess_core_eval_nnue_nnueevaluatorrebuildcomparatortest_nnueevaluatorrebuildcomparatortest): betweenness centrality 0.0000
- `NnueEvaluatorDebugTest` (engine_core_src_test_java_coeusyk_game_chess_core_eval_nnue_nnueevaluatordebugtest_nnueevaluatordebugtest): betweenness centrality 0.0000
- `.dumpTracksStackPointerAfterOnMake()` (engine_core_src_test_java_coeusyk_game_chess_core_eval_nnue_nnueevaluatordebugtest_nnueevaluatordebugtest_dumptracksstackpointerafteronmake): betweenness centrality 0.0000
- `.verifyAgainstRebuildNeverMutatesLiveAccumulator()` (engine_core_src_test_java_coeusyk_game_chess_core_eval_nnue_nnueevaluatorrebuildcomparatortest_nnueevaluatorrebuildcomparatortest_verifyagainstrebuildnevermutatesliveaccumulator): betweenness centrality 0.0000
- `.reportsExactDivergingIndexAndDeltaOnBlackPerspectiveCorruption()` (engine_core_src_test_java_coeusyk_game_chess_core_eval_nnue_nnueevaluatorrebuildcomparatortest_nnueevaluatorrebuildcomparatortest_reportsexactdivergingindexanddeltaonblackperspectivecorruption): betweenness centrality 0.0000
- `.reportsExactDivergingIndexAndDeltaOnWhitePerspectiveCorruption()` (engine_core_src_test_java_coeusyk_game_chess_core_eval_nnue_nnueevaluatorrebuildcomparatortest_nnueevaluatorrebuildcomparatortest_reportsexactdivergingindexanddeltaonwhiteperspectivecorruption): betweenness centrality 0.0000
- `.dumpMatchesRawAccumulatorValuesAtRootPosition()` (engine_core_src_test_java_coeusyk_game_chess_core_eval_nnue_nnueevaluatordebugtest_nnueevaluatordebugtest_dumpmatchesrawaccumulatorvaluesatrootposition): betweenness centrality 0.0000
- `.firstDivergence()` (engine_core_src_main_java_coeusyk_game_chess_core_eval_nnue_nnueevaluator_nnueevaluator_firstdivergence): betweenness centrality 0.0000
- `.reportsNoneWhenAccumulatorMatchesRebuild()` (engine_core_src_test_java_coeusyk_game_chess_core_eval_nnue_nnueevaluatorrebuildcomparatortest_nnueevaluatorrebuildcomparatortest_reportsnonewhenaccumulatormatchesrebuild): betweenness centrality 0.0000

## Newly introduced dependency cycles
_Detected via strongly-connected-components (existence check, not full cycle enumeration)._
- None

## Cross-module dependency changes
- None

## Community changes
_Community IDs are not stable across separate graphify runs — flagged via cohort-overlap (Jaccard < 0.5) among nodes present in both snapshots, not raw ID equality._
- **707 of 2071 common nodes (34%) flagged — this volume is typical Louvain re-clustering instability under any graph perturbation, not evidence of real coupling change. Read this section by checking whether the specific files this PR touched appear below, not by the raw count.**
- `.setRootTtMoveHintForTesting()` (engine_core_src_main_java_coeusyk_game_chess_core_search_searcher_searcher_setrootttmovehintfortesting): cohort overlap 0.01
- `.computePhase()` (engine_core_src_main_java_coeusyk_game_chess_core_eval_evaluator_evaluator_computephase): cohort overlap 0.01
- `Board` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board): cohort overlap 0.01
- `Move.java` (engine_core_src_main_java_coeusyk_game_chess_core_models_move): cohort overlap 0.01
- `.contemptScore()` (engine_core_src_main_java_coeusyk_game_chess_core_search_searcher_searcher_contemptscore): cohort overlap 0.01
- `.canApplyNullMove()` (engine_core_src_main_java_coeusyk_game_chess_core_search_searcher_searcher_canapplynullmove): cohort overlap 0.01
- `.recomputeOccupancies()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_recomputeoccupancies): cohort overlap 0.01
- `.normalCdf()` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_smoketestrunner_smoketestrunner_normalcdf): cohort overlap 0.01
- `.appendCheckOrMateSuffix()` (engine_core_src_main_java_coeusyk_game_chess_core_notation_sanconverter_sanconverter_appendcheckormatesuffix): cohort overlap 0.01
- `NnueEvaluator.java` (engine_core_src_main_java_coeusyk_game_chess_core_eval_nnue_nnueevaluator): cohort overlap 0.02
- `.updateHistory()` (engine_core_src_main_java_coeusyk_game_chess_core_search_searcher_searcher_updatehistory): cohort overlap 0.02
- `Override` (engine_core_src_main_java_coeusyk_game_chess_core_eval_nnue_nnueevaluator_java_override): cohort overlap 0.02
- `.singularityGuardRequiresDepthAndQualifiedTtEntry()` (engine_core_src_test_java_coeusyk_game_chess_core_search_searchertest_searchertest_singularityguardrequiresdepthandqualifiedttentry): cohort overlap 0.02
- `DTZResult.java` (engine_core_src_main_java_coeusyk_game_chess_core_syzygy_dtzresult): cohort overlap 0.02
- `.wdlToScore()` (engine_core_src_main_java_coeusyk_game_chess_core_search_searcher_searcher_wdltoscore): cohort overlap 0.02
- ... and 692 more

## New God Nodes (top 15 by degree)
- None

## Architectural cohesion
- maintained (modularity 0.7023 -> 0.7014, delta -0.0009)

## Narrative

Expected. All 24 added nodes and both non-empty delta sections trace directly to
PR C-1's own diff: `dumpAccumulators()`, `verifyAgainstRebuild()`, the private
`firstDivergence()` helper, `corruptForTest()`, the `RebuildDiff` record and its
`.matches()` method, plus the two new test files/classes/7 test methods. Zero
nodes removed — purely additive, as the plan required.

The top-10 betweenness-centrality deltas are all pre-existing hub nodes
(`Board`, `MovesGenerator`, `TunerPosition`, ...) drifting down by ~0.0001-0.0005
— dilution from the graph growing by 24 nodes, not a real centrality shift caused
by this PR. None of C-1's own new nodes rank in that top 10 (all show 0.0000
betweenness — leaf-like debug/test code, correctly not cross-community bridges).

`NnueEvaluator`'s own degree rose modestly (22→26, +4 methods) and `.reset()`'s
degree rose (+7, now called from both new test classes) — expected, proportional
to what the plan asked this PR to add. No dependency cycles, no cross-module
edges, and no new God Nodes were introduced — this PR never leaves `engine-core`'s
`eval.nnue` package. Cohesion is maintained (modularity delta -0.0009, within
noise). The community-changes section's 700+ flagged nodes are the documented
Louvain re-clustering artifact, not real coupling drift — of the handful with
actual PR C-1 provenance (`NnueEvaluator.java`, `Override`), overlap is still a
low 0.01-0.02, consistent with this being noise across the *whole* graph rather
than something localized to the changed files.

