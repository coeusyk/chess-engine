# Architecture Graph Audit — C-3: NnueDebug Mode & Debug UCI

- Before commit: `41891e492498268a6dcc7a31526571035fdd4773`
- After commit: `643d44ce6674fd5c9eb7b82a65506fb4d0d00ed5`
- Before: 2204 nodes, 5666 edges
- After: 2232 nodes, 5765 edges
- Node delta: +28 / -0
- Edge delta: +107 / -8

## Top 10 nodes by betweenness centrality change
- `Board` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board): 0.0126 -> 0.0123 (-0.0003)
- `MovesGenerator` (engine_core_src_main_java_coeusyk_game_chess_core_movegen_movesgenerator_movesgenerator): 0.0043 -> 0.0042 (-0.0001)
- `TunerPosition` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_tunerposition_tunerposition): 0.0024 -> 0.0023 (-0.0001)
- `LabelledPosition` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_labelledposition_labelledposition): 0.0019 -> 0.0019 (-0.0000)
- `.explainEval()` (engine_core_src_main_java_coeusyk_game_chess_core_eval_nnue_nnueevaluator_nnueevaluator_explaineval): 0.0001 -> 0.0000 (-0.0000)
- `.compareInt16VsFloat32()` (engine_core_src_main_java_coeusyk_game_chess_core_eval_nnue_nnueoracle_nnueoracle_compareint16vsfloat32): 0.0001 -> 0.0000 (-0.0000)
- `.from()` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_tunerposition_tunerposition_from): 0.0016 -> 0.0015 (-0.0000)
- `.isCheckmate()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_ischeckmate): 0.0014 -> 0.0013 (-0.0000)
- `.isStalemate()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_isstalemate): 0.0014 -> 0.0013 (-0.0000)
- `.toBoard()` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_tunerposition_tunerposition_toboard): 0.0013 -> 0.0013 (-0.0000)

## Top 10 nodes by degree change
- `UciApplicationIntegrationTest` (engine_uci_src_test_java_coeusyk_game_chess_uci_uciapplicationintegrationtest_uciapplicationintegrationtest): 24 -> 32 (+8)
- `Test` (engine_uci_src_test_java_coeusyk_game_chess_uci_uciapplicationintegrationtest_java_test): 17 -> 25 (+8)
- `NnueEvaluator` (engine_core_src_main_java_coeusyk_game_chess_core_eval_nnue_nnueevaluator_nnueevaluator): 32 -> 39 (+7)
- `.send()` (engine_uci_src_test_java_coeusyk_game_chess_uci_uciapplicationintegrationtest_uciharness_send): 18 -> 24 (+6)
- `.start()` (engine_uci_src_test_java_coeusyk_game_chess_uci_uciapplicationintegrationtest_uciharness_start): 18 -> 24 (+6)
- `.awaitLine()` (engine_uci_src_test_java_coeusyk_game_chess_uci_uciapplicationintegrationtest_uciharness_awaitline): 17 -> 23 (+6)
- `.writeTinyNnueFile()` (engine_uci_src_test_java_coeusyk_game_chess_uci_uciapplicationintegrationtest_uciapplicationintegrationtest_writetinynnuefile): 4 -> 8 (+4)
- `UciApplication` (engine_uci_src_main_java_coeusyk_game_chess_uci_uciapplication_uciapplication): 28 -> 31 (+3)
- `Piece.java` (engine_core_src_main_java_coeusyk_game_chess_core_models_piece): 28 -> 31 (+3)
- `.onMake()` (engine_core_src_main_java_coeusyk_game_chess_core_eval_nnue_nnueevaluator_nnueevaluator_onmake): 5 -> 8 (+3)

## Newly introduced architectural bridge-node candidates
_New nodes ranked by betweenness centrality in the after-graph (>0). Heuristic, not a graphify-defined threshold._
- `.buildRootNnueEvaluator()` (engine_uci_src_main_java_coeusyk_game_chess_uci_uciapplication_uciapplication_buildrootnnueevaluator): betweenness centrality 0.0000
- `NnueEvaluatorDebugModeTest` (engine_core_src_test_java_coeusyk_game_chess_core_eval_nnue_nnueevaluatordebugmodetest_nnueevaluatordebugmodetest): betweenness centrality 0.0000
- `.driveOnMakeCallsWithPersistentCorruption()` (engine_core_src_test_java_coeusyk_game_chess_core_eval_nnue_nnueevaluatordebugmodetest_nnueevaluatordebugmodetest_driveonmakecallswithpersistentcorruption): betweenness centrality 0.0000
- `.assertIncrementalMatchesRebuild()` (engine_core_src_main_java_coeusyk_game_chess_core_eval_nnue_nnueevaluator_nnueevaluator_assertincrementalmatchesrebuild): betweenness centrality 0.0000
- `.handleNnueDebug()` (engine_uci_src_main_java_coeusyk_game_chess_uci_uciapplication_uciapplication_handlennuedebug): betweenness centrality 0.0000
- `.assertionFiresOnInjectedCorruptionWithinBoundedMoveCountAndNeverThrows()` (engine_core_src_test_java_coeusyk_game_chess_core_eval_nnue_nnueevaluatordebugmodetest_nnueevaluatordebugmodetest_assertionfiresoninjectedcorruptionwithinboundedmovecountandneverthrows): betweenness centrality 0.0000
- `.debugModeDisabledNeverLogsEvenWithCorruption()` (engine_core_src_test_java_coeusyk_game_chess_core_eval_nnue_nnueevaluatordebugmodetest_nnueevaluatordebugmodetest_debugmodedisabledneverlogsevenwithcorruption): betweenness centrality 0.0000
- `.formatVerifyResult()` (engine_uci_src_main_java_coeusyk_game_chess_uci_uciapplication_uciapplication_formatverifyresult): betweenness centrality 0.0000
- `.formatVerifyResultReportsSymbolicPerspectiveOnMismatch()` (engine_uci_src_test_java_coeusyk_game_chess_uci_uciapplicationintegrationtest_uciapplicationintegrationtest_formatverifyresultreportssymbolicperspectiveonmismatch): betweenness centrality 0.0000
- `.dumpActiveFeatures()` (engine_core_src_main_java_coeusyk_game_chess_core_eval_nnue_nnueevaluator_nnueevaluator_dumpactivefeatures): betweenness centrality 0.0000

## Newly introduced dependency cycles
_Detected via strongly-connected-components (existence check, not full cycle enumeration)._
- None

## Cross-module dependency changes
**Changed edge counts:**
- engine-uci -> engine-core: 64 -> 73 (+9)

## Community changes
_Community IDs are not stable across separate graphify runs — flagged via cohort-overlap (Jaccard < 0.5) among nodes present in both snapshots, not raw ID equality._
- **594 of 2204 common nodes (27%) flagged — this volume is typical Louvain re-clustering instability under any graph perturbation, not evidence of real coupling change. Read this section by checking whether the specific files this PR touched appear below, not by the raw count.**
- `Evaluator.java` (engine_core_src_main_java_coeusyk_game_chess_core_eval_evaluator): cohort overlap 0.01
- `NnueEvaluatorDebugTest.java` (engine_core_src_test_java_coeusyk_game_chess_core_eval_nnue_nnueevaluatordebugtest): cohort overlap 0.01
- `Logger` (logger): cohort overlap 0.01
- `.isSquareAttacked()` (engine_core_src_main_java_coeusyk_game_chess_core_movegen_movesgenerator_movesgenerator_issquareattacked): cohort overlap 0.01
- `.getActiveMoves()` (engine_core_src_main_java_coeusyk_game_chess_core_movegen_movesgenerator_movesgenerator_getactivemoves): cohort overlap 0.01
- `.normalCdf()` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_smoketestrunner_smoketestrunner_normalcdf): cohort overlap 0.01
- `.getHalfmoveClock()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_gethalfmoveclock): cohort overlap 0.01
- `.evaluateTerminal()` (engine_core_src_main_java_coeusyk_game_chess_core_search_searcher_searcher_evaluateterminal): cohort overlap 0.01
- `.wdlToScore()` (engine_core_src_main_java_coeusyk_game_chess_core_search_searcher_searcher_wdltoscore): cohort overlap 0.02
- `.appendPawnCaptures()` (engine_core_src_main_java_coeusyk_game_chess_core_movegen_movesgenerator_movesgenerator_appendpawncaptures): cohort overlap 0.02
- `.unmakePromotionRestoresPawnOnOriginalSquare()` (engine_core_src_test_java_coeusyk_game_chess_core_movegen_promotionhandlingtest_promotionhandlingtest_unmakepromotionrestorespawnonoriginalsquare): cohort overlap 0.02
- `IterationInfo` (engine_core_src_main_java_coeusyk_game_chess_core_search_iterationinfo_iterationinfo): cohort overlap 0.02
- `IterationInfo.java` (engine_core_src_main_java_coeusyk_game_chess_core_search_iterationinfo): cohort overlap 0.02
- `.bruteForceNegamax()` (engine_core_src_test_java_coeusyk_game_chess_core_search_searchertest_searchertest_bruteforcenegamax): cohort overlap 0.02
- `.pstTableLookupCorrect()` (engine_core_src_test_java_coeusyk_game_chess_core_eval_evaluatortest_evaluatortest_psttablelookupcorrect): cohort overlap 0.02
- ... and 579 more

## New God Nodes (top 15 by degree)
- `NnueEvaluator` (engine_core_src_main_java_coeusyk_game_chess_core_eval_nnue_nnueevaluator_nnueevaluator): degree 32 -> 39 (existing node promoted)

## Architectural cohesion
- decreased (modularity 0.7343 -> 0.7186, delta -0.0157)

## Architectural Boundary Report
_`production` = src/main/*.java outside `DEBUG_ONLY_MAIN_CLASSES`; `debug` = NnueOracle.java (ADR-002). Test files are excluded — test-to-production coupling is normal and not a boundary risk. Edges are reported at whichever node granularity graphify attaches them to (class/method), grouped by owning file._

### production -> debug dependencies
- - `.explainEval()` (NnueEvaluator.java) -> `.compareInt16VsFloat32()` (NnueOracle.java) [calls]

### debug -> production dependencies
- No change

### cross-module dependency changes
_See "Cross-module dependency changes" above._

### EvaluatorStrategy coupling changes
- No change

### Searcher coupling changes
- No change

### NnueEvaluator coupling changes
- + `NnueEvaluator` -> `.assertIncrementalMatchesRebuild()` (method)
- + `NnueEvaluator` -> `.assertStackBounds()` (method)
- + `NnueEvaluator` <- `.assertionFiresOnInjectedCorruptionWithinBoundedMoveCountAndNeverThrows()` (calls)
- + `NnueEvaluator` <- `.buildRootNnueEvaluator()` (references)
- + `NnueEvaluator` <- `.debugModeDisabledNeverLogsEvenWithCorruption()` (calls)
- + `NnueEvaluator` <- `.driveOnMakeCallsWithPersistentCorruption()` (references)
- + `NnueEvaluator` -> `.dumpActiveFeatures()` (method)
- + `NnueEvaluator` -> `Logger` (references)
- - `NnueEvaluator` <- `.handleEval()` (calls)

### FeatureExtractor coupling changes
- No change

### NnueNetwork coupling changes
- No change

### Frozen boundary verdict
- UNCHANGED — no unapproved production -> debug edges introduced.

## Narrative

Expected, with one caveat requiring ground-truth verification (done, see below).
The +28 nodes/+107 edges are C-3's own additions: `NnueEvaluatorDebugModeTest`
and its 2 test methods + `driveOnMakeCallsWithPersistentCorruption` helper,
`NnueEvaluator.assertStackBounds()`/`.assertIncrementalMatchesRebuild()`/
`.dumpActiveFeatures()`, `UciApplication.handleNnueDebug()`/
`.buildRootNnueEvaluator()`/`.formatVerifyResult()`, and 8 new
`UciApplicationIntegrationTest` cases. All correctly show ~0 betweenness
centrality (leaf-like debug code, not cross-community bridges) — same pattern
as C-1 and C-2's own new nodes.

**One line in the Architectural Boundary Report needs a ground-truth check,
not automated trust: the sole approved production -> debug edge
(`.explainEval()` -> `.compareInt16VsFloat32()`) shows as *removed*.** This is
NOT a real regression — verified directly against source by re-running
`OracleArchitecturalBoundaryTest`'s positive control
(`nnueEvaluatorExplainEvalIsTheApprovedOracleEntryPoint`, which disassembles
`NnueEvaluator.class`'s actual bytecode) immediately after generating this
report: still green, `explainEval` still calls `NnueOracle.
compareInt16VsFloat32` exactly as before. This is graphify's own AST-based
extraction being non-deterministic run-to-run on files with many methods
(`NnueEvaluator.java` grew by 3 methods this PR) — the same class of noise
already documented in the C-2 report's Narrative for `UciApplication.java`.
Frozen boundary verdict is still correctly **UNCHANGED**: the verdict logic
only flags *newly added* unapproved edges, and none were added — but a reader
skimming just the "production -> debug dependencies" section without this
note could misread a `-` line as a broken guardrail. Ground truth (bytecode,
not the AST graph) is what actually enforces the boundary; this report is a
change-review aid, not the source of truth for it.

`NnueEvaluator` is a new God Node (32 -> 39 degree, existing node promoted) —
expected and proportional: 3 new public/package methods plus every new test
calling into it. `UciApplication`'s coupling grew similarly (+3, matching the
new `NnueDebug` option plumbing and 3 new command handlers). No new
dependency cycles, no new cross-module module *pairs* (only `engine-uci ->
engine-core`'s existing edge count growing, +9 — proportional to
`UciApplication`'s 3 new methods each calling into `engine-core`'s
`NnueEvaluator`/`NnueNetwork`). Cohesion dipped slightly more than C-1/C-2
(-0.0157 vs. -0.0009/+0.0345) — plausible given this PR touches both modules'
UCI/eval boundary simultaneously (unlike C-1/C-2, which stayed inside
`engine-core`'s `eval.nnue` package alone), but still well within the
noise band this tool's Community-changes section already characterizes (27%
flagged, consistent with C-1's 34% and C-2's 25% — typical Louvain
re-clustering instability, not a real coupling verdict on its own).

