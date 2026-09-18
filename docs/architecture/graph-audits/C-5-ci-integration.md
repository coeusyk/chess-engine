# Architecture Graph Audit — C-5: CI Integration

- Before commit: `4f6f7c90958450ef76d0e0ed9ec75364b5615895`
- After commit: `4dbc24dd84c62a50bd6331899debae8ce3839daf`
- Before: 2309 nodes, 5869 edges
- After: 2328 nodes, 5882 edges
- Node delta: +23 / -4
- Edge delta: +65 / -52

## Top 10 nodes by betweenness centrality change
- `Board` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board): 0.0116 -> 0.0114 (-0.0002)
- `MovesGenerator` (engine_core_src_main_java_coeusyk_game_chess_core_movegen_movesgenerator_movesgenerator): 0.0039 -> 0.0038 (-0.0001)
- `Searcher.java` (engine_core_src_main_java_coeusyk_game_chess_core_search_searcher): 0.0003 -> 0.0002 (-0.0001)
- `MovesGenerator.java` (engine_core_src_main_java_coeusyk_game_chess_core_movegen_movesgenerator): 0.0001 -> 0.0002 (+0.0001)
- `TunerPosition` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_tunerposition_tunerposition): 0.0021 -> 0.0021 (-0.0000)
- `.incrementalAccumulatorMatchesFullRebuildOverRandomLegalGames()` (engine_core_src_test_java_coeusyk_game_chess_core_eval_nnue_nnueincrementalvsrebuildfuzztest_nnueincrementalvsrebuildfuzztest_incrementalaccumulatormatchesfullrebuildoverrandomlegalgames): 0.0000 -> 0.0000 (-0.0000)
- `LabelledPosition` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_labelledposition_labelledposition): 0.0018 -> 0.0017 (-0.0000)
- `Searcher` (engine_core_src_main_java_coeusyk_game_chess_core_search_searcher_searcher): 0.0002 -> 0.0001 (-0.0000)
- `NnueNetwork` (engine_core_src_main_java_coeusyk_game_chess_core_eval_nnue_nnuenetwork_nnuenetwork): 0.0000 -> 0.0000 (-0.0000)
- `.from()` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_tunerposition_tunerposition_from): 0.0014 -> 0.0014 (-0.0000)

## Top 10 nodes by degree change
- `.incrementalAccumulatorMatchesFullRebuildOverRandomLegalGames()` (engine_core_src_test_java_coeusyk_game_chess_core_eval_nnue_nnueincrementalvsrebuildfuzztest_nnueincrementalvsrebuildfuzztest_incrementalaccumulatormatchesfullrebuildoverrandomlegalgames): 19 -> 6 (-13)
- `.loadReconstructsEveryFieldFromAWrittenFile()` (engine_core_src_test_java_coeusyk_game_chess_core_eval_nnue_nnuenetworkloadertest_nnuenetworkloadertest_loadreconstructseveryfieldfromawrittenfile): 15 -> 3 (-12)
- `.load()` (engine_core_src_main_java_coeusyk_game_chess_core_eval_nnue_nnuenetwork_nnuenetwork_load): 12 -> 6 (-6)
- `.synthetic()` (engine_core_src_test_java_coeusyk_game_chess_core_eval_nnue_testnetworks_testnetworks_synthetic): 4 -> 9 (+5)
- `NnueEvaluator` (nnueevaluator): 6 -> 10 (+4)
- `MethodSource` (methodsource): 3 -> 6 (+3)
- `Arguments` (arguments): 3 -> 6 (+3)
- `Board` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board): 271 -> 268 (-3)
- `NnueNetwork` (nnuenetwork): 4 -> 6 (+2)
- `MovesGenerator.java` (engine_core_src_main_java_coeusyk_game_chess_core_movegen_movesgenerator): 10 -> 12 (+2)

## Newly introduced architectural bridge-node candidates
_New nodes ranked by betweenness centrality in the after-graph (>0). Heuristic, not a graphify-defined threshold._
- `.nnueSearcher()` (engine_core_src_test_java_coeusyk_game_chess_core_search_nnuemodesearchregressiontest_nnuemodesearchregressiontest_nnuesearcher): betweenness centrality 0.0000
- `.reportInt16VsFloat32DivergenceAcrossTheFullCorpus()` (engine_core_src_test_java_coeusyk_game_chess_core_eval_nnue_nnueoraclebatchreporttest_nnueoraclebatchreporttest_reportint16vsfloat32divergenceacrossthefullcorpus): betweenness centrality 0.0000
- `NnueModeSearchRegressionTest` (engine_core_src_test_java_coeusyk_game_chess_core_search_nnuemodesearchregressiontest_nnuemodesearchregressiontest): betweenness centrality 0.0000
- `NnueOracleBatchReportTest` (engine_core_src_test_java_coeusyk_game_chess_core_eval_nnue_nnueoraclebatchreporttest_nnueoraclebatchreporttest): betweenness centrality 0.0000
- `.isAmongLegalMoves()` (engine_core_src_test_java_coeusyk_game_chess_core_search_nnuemodesearchregressiontest_nnuemodesearchregressiontest_isamonglegalmoves): betweenness centrality 0.0000
- `.searchCompletesWithALegalMoveUnderNnue()` (engine_core_src_test_java_coeusyk_game_chess_core_search_nnuemodesearchregressiontest_nnuemodesearchregressiontest_searchcompleteswithalegalmoveundernnue): betweenness centrality 0.0000
- `.forcedMateInOneIsStillFoundUnderNnue()` (engine_core_src_test_java_coeusyk_game_chess_core_search_nnuemodesearchregressiontest_nnuemodesearchregressiontest_forcedmateinoneisstillfoundundernnue): betweenness centrality 0.0000
- `.allRegressionPositions()` (engine_core_src_test_java_coeusyk_game_chess_core_search_nnuemodesearchregressiontest_nnuemodesearchregressiontest_allregressionpositions): betweenness centrality 0.0000
- `.forcedMateInOnePositions()` (engine_core_src_test_java_coeusyk_game_chess_core_search_nnuemodesearchregressiontest_nnuemodesearchregressiontest_forcedmateinonepositions): betweenness centrality 0.0000
- `.loadRejectsCorruptUtfString()` (engine_core_src_test_java_coeusyk_game_chess_core_eval_nnue_nnuenetworkloadertest_nnuenetworkloadertest_loadrejectscorruptutfstring): betweenness centrality 0.0000

## Newly introduced dependency cycles
_Detected via strongly-connected-components (existence check, not full cycle enumeration)._
- None

## Cross-module dependency changes
**Removed module pairs:**
- .claude -> .github: 1 edge(s)
**Changed edge counts:**
- .github -> .claude: 2 -> 1 (-1)
- dev-entries -> .github: 5 -> 1 (-4)

## Community changes
_Community IDs are not stable across separate graphify runs — flagged via cohort-overlap (Jaccard < 0.5) among nodes present in both snapshots, not raw ID equality._
- **677 of 2305 common nodes (29%) flagged — this volume is typical Louvain re-clustering instability under any graph perturbation, not evidence of real coupling change. Read this section by checking whether the specific files this PR touched appear below, not by the raw count.**
- `.setSearchMoves()` (engine_core_src_main_java_coeusyk_game_chess_core_search_searcher_searcher_setsearchmoves): cohort overlap 0.01
- `.backwardPawnCount()` (engine_core_src_main_java_coeusyk_game_chess_core_eval_evaluator_evaluator_backwardpawncount): cohort overlap 0.01
- `.getZobristHash()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_getzobristhash): cohort overlap 0.01
- `Evaluator.java` (engine_core_src_main_java_coeusyk_game_chess_core_eval_evaluator): cohort overlap 0.01
- `.getCurrentFEN()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_getcurrentfen): cohort overlap 0.01
- `.canApplyNullMove()` (engine_core_src_main_java_coeusyk_game_chess_core_search_searcher_searcher_canapplynullmove): cohort overlap 0.01
- `.isEnPassantCapturePossible()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_isenpassantcapturepossible): cohort overlap 0.01
- `.explainEval()` (engine_core_src_main_java_coeusyk_game_chess_core_eval_evaluator_evaluator_explaineval): cohort overlap 0.01
- `.getIncEgScore()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_getincegscore): cohort overlap 0.01
- `ChessGameService.java` (chess_engine_api_src_main_java_coeusyk_game_chess_services_chessgameservice): cohort overlap 0.01
- `.iterativeDeepening()` (engine_core_src_main_java_coeusyk_game_chess_core_search_searcher_searcher_iterativedeepening): cohort overlap 0.01
- `NPS Benchmark Test + Stalemate Guard in Q-Search` (dev_entries_phase_8_nps_benchmark_stalemate_guard): cohort overlap 0.02
- `.getActiveColor()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_getactivecolor): cohort overlap 0.02
- `.toFen()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_tofen): cohort overlap 0.02
- `EndgameHandlingTest.java` (engine_uci_src_test_java_coeusyk_game_chess_uci_endgamehandlingtest): cohort overlap 0.02
- ... and 662 more

## New God Nodes (top 15 by degree)
- None

## Architectural cohesion
- maintained (modularity 0.7331 -> 0.7386, delta +0.0056)

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
- - `NnueEvaluator` <- `.assertAccumulatorMatchesRebuild()` (references)
- - `NnueEvaluator` <- `.incrementalAccumulatorMatchesFullRebuildOverRandomLegalGames()` (calls)

### FeatureExtractor coupling changes
- No change

### NnueNetwork coupling changes
- - `NnueNetwork` <- `.synthetic()` (references)

### Frozen boundary verdict
- UNCHANGED — no unapproved production -> debug edges introduced.

## Narrative
_Filled in by the engineer/agent reviewing this report — the script only computes the quantitative sections above._

PR C-5 is unique among C-1..C-5: it is the only one with **zero main-sources
(production) code changes**. Every changed file is test-scope
(`engine-core/src/test/java/...`), CI configuration (`.github/workflows/*.yml`), or
documentation — matching this PR's own "CI only; no evaluator redesign; no
production behavior changes" constraint exactly. The Architectural Boundary
Report's "No change" verdicts across five of six tracked classes, and the
UNCHANGED frozen-boundary verdict, are exactly what's expected.

`NnueEvaluator coupling changes` (`.assertAccumulatorMatchesRebuild()`,
`.incrementalAccumulatorMatchesFullRebuildOverRandomLegalGames()`) and
`NnueNetwork coupling changes` (`.synthetic()`) both trace to this PR's actual,
intended test-scope changes: `NnueIncrementalVsRebuildFuzzTest` was modified
(SEED/GAMES made overridable), re-triggering AST re-extraction of its existing
NnueEvaluator calls; `TestNetworks.synthetic()` was widened from
package-private to `public` specifically so `NnueModeSearchRegressionTest` (a
different package) could reuse it instead of duplicating the CI test net's
construction logic — the coupling delta is the direct, intended effect of
that widening, not an accidental new dependency. Both are test-to-production
references (a test calling `NnueEvaluator`/`NnueNetwork`), which the
Architectural Boundary Report's own methodology note treats as normal, not a
boundary risk.

The "Cross-module dependency changes" section's `.claude -> .github` and
`dev-entries -> .github` entries are unrelated to this PR — neither pair
touches `engine-core`, `engine-uci`, or `engine-tuner`. These are graphify's
own dev-entries/session-tracking graph nodes, not this repository's Java
module graph; noted here only to confirm they were checked and dismissed,
not overlooked.

The 10 newly-introduced bridge-node candidates are exactly this PR's two new
test classes (`NnueModeSearchRegressionTest`, `NnueOracleBatchReportTest`)
and their methods — all betweenness-centrality 0.0000, leaf nodes with no
downstream fan-out, consistent with test code nothing else in the graph
calls into.

