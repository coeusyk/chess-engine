# Architecture Graph Audit — C-2: Oracle Comparator & Eval Breakdown

- Before commit: `70376a93b2ea1f41c2b7f5dcbfbc931124e5eba5`
- After commit: `4107b3827da4ebd5c355ac5856229d885b4315c0`
- Before: 2117 nodes, 5552 edges
- After: 2162 nodes, 5605 edges
- Node delta: +45 / -0
- Edge delta: +160 / -107

## Top 10 nodes by betweenness centrality change
- `Board` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board): 0.0140 -> 0.0131 (-0.0009)
- `MovesGenerator` (engine_core_src_main_java_coeusyk_game_chess_core_movegen_movesgenerator_movesgenerator): 0.0048 -> 0.0044 (-0.0003)
- `.runSearch()` (engine_uci_src_main_java_coeusyk_game_chess_uci_uciapplication_uciapplication_runsearch): 0.0002 -> 0.0000 (-0.0002)
- `.iterativeDeepening()` (engine_core_src_main_java_coeusyk_game_chess_core_search_searcher_searcher_iterativedeepening): 0.0013 -> 0.0012 (-0.0001)
- `.searchRoot()` (engine_core_src_main_java_coeusyk_game_chess_core_search_searcher_searcher_searchroot): 0.0011 -> 0.0010 (-0.0001)
- `Move` (engine_core_src_main_java_coeusyk_game_chess_core_models_move_move): 0.0015 -> 0.0013 (-0.0001)
- `TunerPosition` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_tunerposition_tunerposition): 0.0026 -> 0.0024 (-0.0001)
- `NnueEvaluator` (engine_core_src_main_java_coeusyk_game_chess_core_eval_nnue_nnueevaluator_nnueevaluator): 0.0001 -> 0.0002 (+0.0001)
- `.searchDepth()` (engine_core_src_main_java_coeusyk_game_chess_core_search_searcher_searcher_searchdepth): 0.0012 -> 0.0011 (-0.0001)
- `LabelledPosition` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_labelledposition_labelledposition): 0.0021 -> 0.0020 (-0.0001)

## Top 10 nodes by degree change
- `.runSearch()` (engine_uci_src_main_java_coeusyk_game_chess_uci_uciapplication_uciapplication_runsearch): 32 -> 11 (-21)
- `Board` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board): 283 -> 271 (-12)
- `Move` (engine_core_src_main_java_coeusyk_game_chess_core_models_move_move): 113 -> 104 (-9)
- `MovesGenerator` (engine_core_src_main_java_coeusyk_game_chess_core_movegen_movesgenerator_movesgenerator): 88 -> 80 (-8)
- `Board` (board): 15 -> 7 (-8)
- `Override` (override): 8 -> 0 (-8)
- `Override` (engine_core_src_main_java_coeusyk_game_chess_core_eval_nnue_nnueevaluator_java_override): 0 -> 8 (+8)
- `.getActiveColor()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_getactivecolor): 47 -> 40 (-7)
- `.reset()` (engine_core_src_main_java_coeusyk_game_chess_core_eval_nnue_nnueevaluator_nnueevaluator_reset): 13 -> 19 (+6)
- `NnueEvaluator` (engine_core_src_main_java_coeusyk_game_chess_core_eval_nnue_nnueevaluator_nnueevaluator): 26 -> 32 (+6)

## Newly introduced architectural bridge-node candidates
_New nodes ranked by betweenness centrality in the after-graph (>0). Heuristic, not a graphify-defined threshold._
- `.compareInt16VsFloat32()` (engine_core_src_main_java_coeusyk_game_chess_core_eval_nnue_nnueoracle_nnueoracle_compareint16vsfloat32): betweenness centrality 0.0001
- `.explainEval()` (engine_core_src_main_java_coeusyk_game_chess_core_eval_nnue_nnueevaluator_nnueevaluator_explaineval): betweenness centrality 0.0001
- `.compare()` (engine_core_src_main_java_coeusyk_game_chess_core_eval_nnue_nnueoracle_nnueoracle_compare): betweenness centrality 0.0000
- `.compareBatch()` (engine_core_src_main_java_coeusyk_game_chess_core_eval_nnue_nnueoracle_nnueoracle_comparebatch): betweenness centrality 0.0000
- `.float32Evaluate()` (engine_core_src_main_java_coeusyk_game_chess_core_eval_nnue_nnueoracle_nnueoracle_float32evaluate): betweenness centrality 0.0000
- `NnueOracleTest` (engine_core_src_test_java_coeusyk_game_chess_core_eval_nnue_nnueoracletest_nnueoracletest): betweenness centrality 0.0000
- `NnueOracle` (engine_core_src_main_java_coeusyk_game_chess_core_eval_nnue_nnueoracle_nnueoracle): betweenness centrality 0.0000
- `.explainEvalInt16ScoreMatchesEvaluate()` (engine_core_src_test_java_coeusyk_game_chess_core_eval_nnue_nnueevaluatordebugtest_nnueevaluatordebugtest_explainevalint16scorematchesevaluate): betweenness centrality 0.0000
- `.explainEvalContainsAllRequiredFields()` (engine_core_src_test_java_coeusyk_game_chess_core_eval_nnue_nnueevaluatordebugtest_nnueevaluatordebugtest_explainevalcontainsallrequiredfields): betweenness centrality 0.0000
- `.rangeAndClipCount()` (engine_core_src_main_java_coeusyk_game_chess_core_eval_nnue_nnueevaluator_nnueevaluator_rangeandclipcount): betweenness centrality 0.0000

## Newly introduced dependency cycles
_Detected via strongly-connected-components (existence check, not full cycle enumeration)._
- Cycle group (4 nodes): .compare(), .compareInt16VsFloat32(), .explainEval(), NnueEvaluator

## Cross-module dependency changes
**Changed edge counts:**
- engine-uci -> engine-core: 141 -> 64 (-77)

## Community changes
_Community IDs are not stable across separate graphify runs — flagged via cohort-overlap (Jaccard < 0.5) among nodes present in both snapshots, not raw ID equality._
- **523 of 2117 common nodes (25%) flagged — this volume is typical Louvain re-clustering instability under any graph perturbation, not evidence of real coupling change. Read this section by checking whether the specific files this PR touched appear below, not by the raw count.**
- `.setSearchMode()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_setsearchmode): cohort overlap 0.01
- `.bruteForceNegamax()` (engine_core_src_test_java_coeusyk_game_chess_core_search_searchertest_searchertest_bruteforcenegamax): cohort overlap 0.01
- `.singularityGuardRequiresDepthAndQualifiedTtEntry()` (engine_core_src_test_java_coeusyk_game_chess_core_search_searchertest_searchertest_singularityguardrequiresdepthandqualifiedttentry): cohort overlap 0.01
- `.handleSetOption()` (engine_uci_src_main_java_coeusyk_game_chess_uci_uciapplication_uciapplication_handlesetoption): cohort overlap 0.01
- `.probeWDL()` (engine_core_src_main_java_coeusyk_game_chess_core_syzygy_syzygyprober_syzygyprober_probewdl): cohort overlap 0.01
- `.toFen()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_tofen): cohort overlap 0.01
- `.setSearchMoves()` (engine_core_src_main_java_coeusyk_game_chess_core_search_searcher_searcher_setsearchmoves): cohort overlap 0.01
- `.resolveNnueNetworkForSearch()` (engine_uci_src_main_java_coeusyk_game_chess_uci_uciapplication_uciapplication_resolvennuenetworkforsearch): cohort overlap 0.01
- `.unmakeNullMove()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_unmakenullmove): cohort overlap 0.01
- `.getZobristHash()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_getzobristhash): cohort overlap 0.01
- `.incAdjust()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_incadjust): cohort overlap 0.01
- `.appendCheckOrMateSuffix()` (engine_core_src_main_java_coeusyk_game_chess_core_notation_sanconverter_sanconverter_appendcheckormatesuffix): cohort overlap 0.01
- `Logger` (engine_uci_src_main_java_coeusyk_game_chess_uci_uciapplication_java_logger): cohort overlap 0.01
- `PolyglotKey.java` (engine_core_src_main_java_coeusyk_game_chess_core_book_polyglotkey): cohort overlap 0.01
- `.isSquareAttacked()` (engine_core_src_main_java_coeusyk_game_chess_core_movegen_movesgenerator_movesgenerator_issquareattacked): cohort overlap 0.01
- ... and 508 more

## New God Nodes (top 15 by degree)
- None

## Architectural cohesion
- increased (modularity 0.7074 -> 0.7419, delta +0.0345)

## Architectural Boundary Report
_`production` = src/main/*.java outside `DEBUG_ONLY_MAIN_CLASSES`; `debug` = NnueOracle.java (ADR-002). Test files are excluded — test-to-production coupling is normal and not a boundary risk. Edges are reported at whichever node granularity graphify attaches them to (class/method), grouped by owning file._

### production -> debug dependencies
- + `.explainEval()` (NnueEvaluator.java) -> `.compareInt16VsFloat32()` (NnueOracle.java) [calls]

### debug -> production dependencies
- + `.compare()` (NnueOracle.java) -> `.evaluate()` (NnueEvaluator.java) [calls]
- + `.compare()` (NnueOracle.java) -> `NnueEvaluator` (NnueEvaluator.java) [references]
- + `.compareBatch()` (NnueOracle.java) -> `.reset()` (NnueEvaluator.java) [calls]
- + `.compareBatch()` (NnueOracle.java) -> `NnueEvaluator` (NnueEvaluator.java) [calls]
- + `.compareInt16VsFloat32()` (NnueOracle.java) -> `.reset()` (NnueEvaluator.java) [calls]
- + `.compareInt16VsFloat32()` (NnueOracle.java) -> `NnueEvaluator` (NnueEvaluator.java) [calls]
- + `NnueOracle.java` (NnueOracle.java) -> `Piece.java` (Piece.java) [imports]

### cross-module dependency changes
_See "Cross-module dependency changes" above._

### EvaluatorStrategy coupling changes
- No change

### Searcher coupling changes
- No change

### NnueEvaluator coupling changes
- + `NnueEvaluator` <- `.compare()` (references)
- + `NnueEvaluator` <- `.compareBatch()` (calls)
- + `NnueEvaluator` <- `.compareInt16VsFloat32()` (calls)
- + `NnueEvaluator` -> `.explainEval()` (method)
- + `NnueEvaluator` <- `.handleEval()` (calls)
- + `NnueEvaluator` -> `.rangeAndClipCount()` (method)

### FeatureExtractor coupling changes
- No change

### NnueNetwork coupling changes
- - `NnueNetwork` <- `.resolveNnueNetworkForSearch()` (references)
- - `NnueNetwork` <- `UciApplication` (references)
- - `NnueNetwork` <- `UciApplication.java` (imports)

### Frozen boundary verdict
- UNCHANGED — no unapproved production -> debug edges introduced.

## Narrative

Expected, with one caveat flagged below. C-2 adds `NnueOracle` (its class node,
`OracleResult`/`BatchOracleResult` records, and 6 methods) plus `explainEval()`
and the private `RangeAndClipCount` record on `NnueEvaluator` — that accounts
for the bulk of the +45 nodes and the new bridge-node candidates, all correctly
showing near-zero betweenness (leaf-like debug code, not cross-community hubs).

**Architectural Boundary Report is clean.** Exactly one production -> debug
edge exists in either snapshot: `.explainEval()` (`NnueEvaluator.java`) ->
`.compareInt16VsFloat32()` (`NnueOracle.java`) — the sole approved entry point,
matching `OracleArchitecturalBoundaryTest`'s bytecode-level guard. The seven
debug -> production edges (`NnueOracle`'s methods calling into `NnueEvaluator`/
`Piece`) are exactly what a test/debug-only oracle is expected to depend on, not
a violation. Frozen boundary verdict: **UNCHANGED**. `EvaluatorStrategy`,
`Searcher`, and `FeatureExtractor` show zero coupling change, as expected —
C-2 never touches search or the strategy interface.

**One item needs a human eye, not automated flagging:** `NnueNetwork coupling
changes` shows three *removed* edges from `UciApplication`/
`.resolveNnueNetworkForSearch()`. C-2 didn't remove that method or its
`NnueNetwork` usage — `handleEval()` still calls it (confirmed against source
in this session). This reads as graphify's AST-based extraction being
non-deterministic run-to-run on `UciApplication.java` (a large file with many
UCI command branches) rather than a real dependency removal — consistent with
the "Newly introduced dependency cycle" below, which is graphify's own
extraction connecting `.compare()`/`.compareInt16VsFloat32()`/`.explainEval()`/
`NnueEvaluator` into a 4-node SCC that's an artifact of instance-method-to-class
edges, not an actual runtime cycle (verified: `.explainEval()` calls
`NnueOracle`, `NnueOracle` calls back into `NnueEvaluator`'s instance methods
on the local `int16Evaluator` it constructs — a caller/callee relationship
graphify's node model can't distinguish from a true cycle since there's no
call-site object-identity tracking, not two classes each importing the other).

The cross-module edge drop (`engine-uci -> engine-core`: 141 -> 64) and the
`.runSearch()` degree/centrality drop are the same extraction-noise pattern on
`UciApplication.java`, not a real 77-edge severing — C-2 only adds one new
`if`-branch to `handleEval()`. Community-changes noise (25%, 523 nodes) is the
documented Louvain instability, not real coupling drift. Cohesion increased
slightly (+0.0345), within the range this tool has already characterized as
noise-adjacent for a change this size. No new dependency cycles beyond the
one addressed above, no cross-module additions, no new God Nodes — C-2 stays
entirely inside `engine-core`'s `eval.nnue` package plus one new UCI branch,
as the plan required.

