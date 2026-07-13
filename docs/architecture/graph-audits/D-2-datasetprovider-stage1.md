# Architecture Graph Audit — D-2: DatasetProvider Contract + Stage 1 Text Pipeline

- Before commit: `8bf9fc71bb19a7799ec48206d7075c1faf8867d3`
- After commit: `30e00a8b4cdd985d55447a044aa76f0cbbb2e898`
- Before: 2483 nodes, 6014 edges
- After: 2578 nodes, 6181 edges
- Node delta: +95 / -0
- Edge delta: +167 / -0

## Architecture Split
_Scoping summary only — the detailed sections below (centrality, degree, boundary report, etc.) remain whole-graph, since those measures are not meaningful computed on a subgraph alone._

## Engine Architecture
- Before: 1543 nodes; After: 1543 nodes
- Node delta: +0 / -0

## Trainer Architecture
- Before: 14 nodes; After: 104 nodes
- Node delta: +90 / -0

## Top 10 nodes by betweenness centrality change
- `Board` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board): 0.0100 -> 0.0093 (-0.0007)
- `MovesGenerator` (engine_core_src_main_java_coeusyk_game_chess_core_movegen_movesgenerator_movesgenerator): 0.0034 -> 0.0031 (-0.0002)
- `TunerPosition` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_tunerposition_tunerposition): 0.0019 -> 0.0017 (-0.0001)
- `LabelledPosition` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_labelledposition_labelledposition): 0.0015 -> 0.0014 (-0.0001)
- `.from()` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_tunerposition_tunerposition_from): 0.0012 -> 0.0011 (-0.0001)
- `.isCheckmate()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_ischeckmate): 0.0011 -> 0.0010 (-0.0001)
- `.isStalemate()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_isstalemate): 0.0011 -> 0.0010 (-0.0001)
- `.toBoard()` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_tunerposition_tunerposition_toboard): 0.0010 -> 0.0010 (-0.0001)
- `Move` (engine_core_src_main_java_coeusyk_game_chess_core_models_move_move): 0.0010 -> 0.0010 (-0.0001)
- `.evaluate()` (engine_core_src_main_java_coeusyk_game_chess_core_eval_evaluator_evaluator_evaluate): 0.0009 -> 0.0008 (-0.0001)

## Top 10 nodes by degree change
- None (no common node's degree changed)

## Newly introduced architectural bridge-node candidates
_New nodes ranked by betweenness centrality in the after-graph (>0). Heuristic, not a graphify-defined threshold._
- `TextDatasetProvider` (trainer_trainer_dataset_text_provider_textdatasetprovider): betweenness centrality 0.0000
- `_make_provider()` (trainer_tests_dataset_test_text_provider_make_provider): betweenness centrality 0.0000
- `DatasetProvider` (trainer_trainer_contracts_dataset_datasetprovider): betweenness centrality 0.0000
- `.positions()` (trainer_trainer_dataset_text_provider_textdatasetprovider_positions): betweenness centrality 0.0000
- `_parse_row()` (trainer_trainer_dataset_text_provider_parse_row): betweenness centrality 0.0000
- `_fixture_records()` (trainer_tests_dataset_test_mmap_shard_fixture_records): betweenness centrality 0.0000
- `PositionLabel` (trainer_trainer_contracts_dataset_positionlabel): betweenness centrality 0.0000
- `read_shard()` (trainer_trainer_dataset_mmap_shard_read_shard): betweenness centrality 0.0000
- `_decode()` (trainer_trainer_dataset_mmap_shard_decode): betweenness centrality 0.0000
- `write_shard()` (trainer_trainer_dataset_mmap_shard_write_shard): betweenness centrality 0.0000

## Newly introduced dependency cycles
_Detected via strongly-connected-components (existence check, not full cycle enumeration)._
- None

## Cross-module dependency changes
- None

## Community changes
_Community IDs are not stable across separate graphify runs — flagged via cohort-overlap (Jaccard < 0.5) among nodes present in both snapshots, not raw ID equality._
- **846 of 2483 common nodes (34%) flagged — this volume is typical Louvain re-clustering instability under any graph perturbation, not evidence of real coupling change. Read this section by checking whether the specific files this PR touched appear below, not by the raw count.**
- `EvaluatorStrategy` (evaluatorstrategy): cohort overlap 0.01
- `.getAllOccupancy()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_getalloccupancy): cohort overlap 0.01
- `.toFen()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_tofen): cohort overlap 0.01
- `.isSquareAttackedBy()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_issquareattackedby): cohort overlap 0.01
- `Evaluator.java` (engine_core_src_main_java_coeusyk_game_chess_core_eval_evaluator): cohort overlap 0.01
- `.createGame()` (chess_engine_api_src_main_java_coeusyk_game_chess_services_chessgameservice_chessgameservice_creategame): cohort overlap 0.01
- `Logger` (engine_core_src_main_java_coeusyk_game_chess_core_search_searcher_java_logger): cohort overlap 0.01
- `.computePhase()` (engine_core_src_main_java_coeusyk_game_chess_core_eval_evaluator_evaluator_computephase): cohort overlap 0.01
- `.getState()` (chess_engine_api_src_main_java_coeusyk_game_chess_services_chessgameservice_chessgameservice_getstate): cohort overlap 0.01
- `.appendCheckOrMateSuffix()` (engine_core_src_main_java_coeusyk_game_chess_core_notation_sanconverter_sanconverter_appendcheckormatesuffix): cohort overlap 0.02
- `.setSyzygyProber()` (engine_core_src_main_java_coeusyk_game_chess_core_search_searcher_searcher_setsyzygyprober): cohort overlap 0.02
- `.unmakeNullMove()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_unmakenullmove): cohort overlap 0.02
- `NullMoveState` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_nullmovestate): cohort overlap 0.02
- `.resetGame()` (chess_engine_api_src_main_java_coeusyk_game_chess_controllers_gamecontroller_gamecontroller_resetgame): cohort overlap 0.03
- `.getPiece()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_getpiece): cohort overlap 0.03
- ... and 831 more

## New God Nodes (top 15 by degree)
- None

## Architectural cohesion
- increased (modularity 0.7363 -> 0.7520, delta +0.0156)

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

D-2 is the first PR to add real logic to the Trainer Architecture side of the split:
14 → 104 nodes (+90), while Engine Architecture again shows a flat 0 node delta —
direct graph confirmation this PR touched zero Java production code, matching the
"no Java changes" constraint exactly (the same signal D-1's audit first established,
now exercised against real logic instead of empty scaffolding). The Architectural
Boundary Report's five `FROZEN_BOUNDARY_CLASSES` all show "No change" and the frozen
boundary verdict is UNCHANGED — expected, since D-2 never touches
`EvaluatorStrategy`/`Searcher`/`NnueEvaluator`/`FeatureExtractor`/`NnueNetwork`.

The +90 trainer nodes trace directly to this PR's real content: the `DatasetProvider`
contract (`DatasetMetadata`, `ShardRef`, `PositionLabel`, `PositionMetadata`,
`PositionRecord`, the `DatasetProvider` ABC itself and its three methods), the Stage 1
implementation (`TextDatasetProvider` and its methods/helpers), the mmap shard module
(`SHARD_DTYPE`, `write_shard`, `read_shard`, `_encode`, `_decode`), the Transform
module (`compose`, `deduplicate`, `filter_by_ply_range`, `phase_of`,
`balance_phases`), and 27 new test functions across four test files — no part of this
delta is unaccounted-for scaffolding.

Modularity increased (0.7363 → 0.7520, Δ +0.0156) — larger than D-1's "maintained"
delta, consistent with a genuinely new, cohesive, self-contained subtree (the
`trainer.dataset`/`trainer.contracts` packages, internally call-connected via real
imports and method calls for the first time) landing mostly independently of the rest
of the graph, which tends to raise modularity rather than blur community boundaries.

Cross-module dependency changes and community-churn sections (not reproduced in full
here) were checked against this PR's actual file list before treating them as
noise, per this report's own established convention — none traced to unexpected
coupling.

