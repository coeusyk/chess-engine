# Architecture Graph Audit — C-4: Benchmark Corpus

- Before commit: `65a75bbac33ac26f2e67c0e0f0b0db7072ec84f0`
- After commit: `42aa8b9de93cb96bf54dad915a0476c86a1a66ae`
- Before: 2253 nodes, 5785 edges
- After: 2288 nodes, 5849 edges
- Node delta: +35 / -0
- Edge delta: +79 / -15

## Top 10 nodes by betweenness centrality change
- `Board` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board): 0.0121 -> 0.0118 (-0.0003)
- `MovesGenerator` (engine_core_src_main_java_coeusyk_game_chess_core_movegen_movesgenerator_movesgenerator): 0.0041 -> 0.0040 (-0.0001)
- `TunerPosition` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_tunerposition_tunerposition): 0.0023 -> 0.0022 (-0.0001)
- `LabelledPosition` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_labelledposition_labelledposition): 0.0018 -> 0.0018 (-0.0001)
- `NnueEvaluator` (engine_core_src_main_java_coeusyk_game_chess_core_eval_nnue_nnueevaluator_nnueevaluator): 0.0002 -> 0.0001 (-0.0000)
- `.from()` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_tunerposition_tunerposition_from): 0.0015 -> 0.0015 (-0.0000)
- `Searcher.java` (engine_core_src_main_java_coeusyk_game_chess_core_search_searcher): 0.0003 -> 0.0003 (+0.0000)
- `.isCheckmate()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_ischeckmate): 0.0013 -> 0.0013 (-0.0000)
- `.isStalemate()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_isstalemate): 0.0013 -> 0.0013 (-0.0000)
- `.toBoard()` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_tunerposition_tunerposition_toboard): 0.0013 -> 0.0012 (-0.0000)

## Top 10 nodes by degree change
- `NnueNetwork` (nnuenetwork): 0 -> 4 (+4)
- `EvaluatorStrategy` (evaluatorstrategy): 2 -> 5 (+3)
- `.handleNnueDebug()` (engine_uci_src_main_java_coeusyk_game_chess_uci_uciapplication_uciapplication_handlennuedebug): 8 -> 5 (-3)
- `NnueEvaluator` (engine_core_src_main_java_coeusyk_game_chess_core_eval_nnue_nnueevaluator_nnueevaluator): 39 -> 36 (-3)
- `NnueNetwork` (engine_uci_src_main_java_coeusyk_game_chess_uci_uciapplication_java_nnuenetwork): 3 -> 0 (-3)
- `Board` (board): 8 -> 10 (+2)
- `Logger` (engine_uci_src_main_java_coeusyk_game_chess_uci_uciapplication_java_logger): 2 -> 0 (-2)
- `Logger` (logger): 0 -> 2 (+2)
- `Piece.java` (engine_core_src_main_java_coeusyk_game_chess_core_models_piece): 31 -> 32 (+1)
- `RebuildDiff` (engine_core_src_main_java_coeusyk_game_chess_core_eval_nnue_nnueevaluator_rebuilddiff): 6 -> 5 (-1)

## Newly introduced architectural bridge-node candidates
_New nodes ranked by betweenness centrality in the after-graph (>0). Heuristic, not a graphify-defined threshold._
- `.toBoard()` (engine_core_src_test_java_coeusyk_game_chess_core_eval_nnue_nnuecorpuscategories_nnuecorpuscategories_toboard): betweenness centrality 0.0000
- `NnueCorpusGenerator` (engine_core_src_test_java_coeusyk_game_chess_core_eval_nnue_nnuecorpusgenerator_nnuecorpusgenerator): betweenness centrality 0.0000
- `.generateGoldenEvals()` (engine_core_src_test_java_coeusyk_game_chess_core_eval_nnue_nnuecorpusgenerator_nnuecorpusgenerator_generategoldenevals): betweenness centrality 0.0000
- `.generateCategoryFiles()` (engine_core_src_test_java_coeusyk_game_chess_core_eval_nnue_nnuecorpusgenerator_nnuecorpusgenerator_generatecategoryfiles): betweenness centrality 0.0000
- `.everyGoldenPositionMatchesCiTestNetExactly()` (engine_core_src_test_java_coeusyk_game_chess_core_eval_nnue_nnuegoldenevaltest_nnuegoldenevaltest_everygoldenpositionmatchescitestnetexactly): betweenness centrality 0.0000
- `NnueCorpusBenchmarkTest` (engine_core_src_test_java_coeusyk_game_chess_core_eval_nnue_nnuecorpusbenchmarktest_nnuecorpusbenchmarktest): betweenness centrality 0.0000
- `.newSearchNnueEvaluator()` (engine_uci_src_main_java_coeusyk_game_chess_uci_uciapplication_uciapplication_newsearchnnueevaluator): betweenness centrality 0.0000
- `NnueGoldenEvalTest` (engine_core_src_test_java_coeusyk_game_chess_core_eval_nnue_nnuegoldenevaltest_nnuegoldenevaltest): betweenness centrality 0.0000
- `.fixedDepthNps()` (engine_core_src_test_java_coeusyk_game_chess_core_eval_nnue_nnuecorpusbenchmarktest_nnuecorpusbenchmarktest_fixeddepthnps): betweenness centrality 0.0000
- `.evalThroughput()` (engine_core_src_test_java_coeusyk_game_chess_core_eval_nnue_nnuecorpusbenchmarktest_nnuecorpusbenchmarktest_evalthroughput): betweenness centrality 0.0000

## Newly introduced dependency cycles
_Detected via strongly-connected-components (existence check, not full cycle enumeration)._
- None

## Cross-module dependency changes
**Changed edge counts:**
- engine-uci -> engine-core: 73 -> 63 (-10)

## Community changes
_Community IDs are not stable across separate graphify runs — flagged via cohort-overlap (Jaccard < 0.5) among nodes present in both snapshots, not raw ID equality._
- **381 of 2253 common nodes (17%) flagged — this volume is typical Louvain re-clustering instability under any graph perturbation, not evidence of real coupling change. Read this section by checking whether the specific files this PR touched appear below, not by the raw count.**
- `.toFen()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_tofen): cohort overlap 0.01
- `Move.java` (engine_core_src_main_java_coeusyk_game_chess_core_models_move): cohort overlap 0.01
- `NnueEvaluatorDebugTest.java` (engine_core_src_test_java_coeusyk_game_chess_core_eval_nnue_nnueevaluatordebugtest): cohort overlap 0.01
- `SanConverter.java` (engine_core_src_main_java_coeusyk_game_chess_core_notation_sanconverter): cohort overlap 0.01
- `.getZobristHash()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_getzobristhash): cohort overlap 0.01
- `.MovesGenerator()` (engine_core_src_main_java_coeusyk_game_chess_core_movegen_movesgenerator_movesgenerator_movesgenerator): cohort overlap 0.01
- `.findMove()` (engine_core_src_main_java_coeusyk_game_chess_core_movegen_movesgenerator_movesgenerator_findmove): cohort overlap 0.01
- `EvaluatorStrategy` (evaluatorstrategy): cohort overlap 0.02
- `.SetupContainer()` (chess_engine_api_src_main_java_coeusyk_game_chess_utils_setupcontainer_setupcontainer_setupcontainer): cohort overlap 0.02
- `.genKing()` (engine_core_src_main_java_coeusyk_game_chess_core_movegen_movesgenerator_movesgenerator_genking): cohort overlap 0.02
- `PolyglotKey.java` (engine_core_src_main_java_coeusyk_game_chess_core_book_polyglotkey): cohort overlap 0.02
- `.formatVerifyResultReportsSymbolicPerspectiveOnMismatch()` (engine_uci_src_test_java_coeusyk_game_chess_uci_uciapplicationintegrationtest_uciapplicationintegrationtest_formatverifyresultreportssymbolicperspectiveonmismatch): cohort overlap 0.02
- `.ResponseContainer()` (chess_engine_api_src_main_java_coeusyk_game_chess_utils_responsecontainer_responsecontainer_responsecontainer): cohort overlap 0.02
- `.incAdjust()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_incadjust): cohort overlap 0.02
- `OpeningBook.java` (engine_core_src_main_java_coeusyk_game_chess_core_book_openingbook): cohort overlap 0.02
- ... and 366 more

## New God Nodes (top 15 by degree)
- `.getPiece()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_getpiece): degree 39 -> 39 (existing node promoted)

## Architectural cohesion
- maintained (modularity 0.7196 -> 0.7288, delta +0.0092)

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
- - `NnueEvaluator` <- `.buildRootNnueEvaluator()` (references)
- - `NnueEvaluator` <- `.runSearch()` (calls)
- - `NnueEvaluator` <- `UciApplication.java` (imports)

### FeatureExtractor coupling changes
- No change

### NnueNetwork coupling changes
- No change

### Frozen boundary verdict
- UNCHANGED — no unapproved production -> debug edges introduced.

## Narrative

PR C-4 added pure test-scope code (`NnueCorpusGenerator`, `NnueCorpusCategories`,
`NnueGoldenEvalTest`, `NnueCorpusBenchmarkTest`, all in `engine-core`'s
`eval.nnue` test package) plus static data (`bench/nnue-corpus/`, not
source code — contributes no graph nodes). None of it touches the Oracle
boundary, the frozen `EvaluatorStrategy`/`Searcher`/`FeatureExtractor`/
`NnueNetwork` production surface, or ADR-002/003/004/005/009. The
Architectural Boundary Report's "No change" verdicts across five of six
tracked classes, and the "UNCHANGED" frozen-boundary verdict, are exactly
what's expected for a data+test PR.

`NnueEvaluator coupling changes` shows three edges (`buildRootNnueEvaluator`,
`runSearch`, `UciApplication.java` imports) — this is the same
`UciApplication.java` AST-extraction noise already documented in the C-2
and C-3 audits (both re-verified by re-running `OracleArchitecturalBoundaryTest`'s
positive control after generating those reports, still green — re-verified
again for this report). This PR's only production-code change is a small,
behavior-preserving method extraction (`newSearchNnueEvaluator`) inside
`UciApplication.java`, which shifts exactly which line ranges the AST
extractor attributes certain edges to without changing any actual call
relationship. The `engine-uci -> engine-core` cross-module edge count drop
(73 -> 63) is the same phenomenon at the module-pair level, not a real
decoupling — confirmed by inspection: the extraction only moved two
~5-line methods within the same file, it did not remove or redirect any
import, call, or reference.

The 10 newly-introduced nodes under "bridge-node candidates" are exactly
this PR's 4 new test classes and their public/package-private methods
(`NnueCorpusGenerator`, `NnueCorpusBenchmarkTest`, `NnueGoldenEvalTest`,
`NnueCorpusCategories.toBoard()`) plus `UciApplication`'s new
`newSearchNnueEvaluator()` — all betweenness-centrality 0.0000, i.e. leaf
nodes with no downstream fan-out, consistent with test/generator code
that is never called by anything else in the graph.
