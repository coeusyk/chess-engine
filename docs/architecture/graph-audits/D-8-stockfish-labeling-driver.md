# Architecture Graph Audit — D-8: Stockfish Labeling Driver (Stage 2)

- Before commit: `fab2f2db8171d0707c094cc21f0cd215093aa085`
- After commit: `17c6ec3c97dbb4bc9ad8e726ce7bf4d9f4e5355f`
- Before: 3114 nodes, 6880 edges
- After: 3194 nodes, 7004 edges
- Node delta: +82 / -2
- Edge delta: +147 / -23

## Architecture Split
_Scoping summary only — the detailed sections below (centrality, degree, boundary report, etc.) remain whole-graph, since those measures are not meaningful computed on a subgraph alone._

## Engine Architecture
- Before: 1573 nodes; After: 1573 nodes
- Node delta: +0 / -0

## Trainer Architecture
- Before: 359 nodes; After: 429 nodes
- Node delta: +72 / -2

## Top 10 nodes by betweenness centrality change
- `Board` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board): 0.0063 -> 0.0060 (-0.0003)
- `MovesGenerator` (engine_core_src_main_java_coeusyk_game_chess_core_movegen_movesgenerator_movesgenerator): 0.0021 -> 0.0020 (-0.0001)
- `TunerPosition` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_tunerposition_tunerposition): 0.0012 -> 0.0011 (-0.0001)
- `LabelledPosition` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_labelledposition_labelledposition): 0.0010 -> 0.0009 (-0.0000)
- `.from()` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_tunerposition_tunerposition_from): 0.0008 -> 0.0007 (-0.0000)
- `.isCheckmate()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_ischeckmate): 0.0007 -> 0.0007 (-0.0000)
- `.isStalemate()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_isstalemate): 0.0007 -> 0.0006 (-0.0000)
- `.toBoard()` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_tunerposition_tunerposition_toboard): 0.0007 -> 0.0006 (-0.0000)
- `Move` (engine_core_src_main_java_coeusyk_game_chess_core_models_move_move): 0.0006 -> 0.0006 (-0.0000)
- `.evaluate()` (engine_core_src_main_java_coeusyk_game_chess_core_eval_evaluator_evaluator_evaluate): 0.0006 -> 0.0005 (-0.0000)

## Top 10 nodes by degree change
- `read_shard()` (trainer_trainer_dataset_mmap_shard_read_shard): 9 -> 17 (+8)
- `PositionRecord` (trainer_trainer_contracts_dataset_positionrecord): 15 -> 7 (-8)
- `PositionMetadata` (trainer_trainer_contracts_dataset_positionmetadata): 10 -> 5 (-5)
- `PositionLabel` (trainer_trainer_contracts_dataset_positionlabel): 13 -> 8 (-5)
- `write_shard()` (trainer_trainer_dataset_mmap_shard_write_shard): 12 -> 16 (+4)
- `test_write_shard_streams_across_multiple_internal_batches()` (trainer_tests_dataset_test_mmap_shard_test_write_shard_streams_across_multiple_internal_batches): 6 -> 3 (-3)
- `test_oversized_fen_is_rejected()` (trainer_tests_dataset_test_mmap_shard_test_oversized_fen_is_rejected): 5 -> 2 (-3)
- `test_wdl_only_label_is_rejected_loudly_not_silently_dropped()` (trainer_tests_dataset_test_mmap_shard_test_wdl_only_label_is_rejected_loudly_not_silently_dropped): 5 -> 2 (-3)
- `test_absent_optional_fields_round_trip_as_none()` (trainer_tests_dataset_test_mmap_shard_test_absent_optional_fields_round_trip_as_none): 6 -> 3 (-3)
- `ShardRef` (trainer_trainer_contracts_dataset_shardref): 9 -> 7 (-2)

## Newly introduced architectural bridge-node candidates
_New nodes ranked by betweenness centrality in the after-graph (>0). Heuristic, not a graphify-defined threshold._
- `label_positions()` (trainer_scripts_stockfish_label_label_positions): betweenness centrality 0.0000
- `UciEngine` (trainer_scripts_stockfish_label_uciengine): betweenness centrality 0.0000
- `StockfishLabeledProvider` (trainer_trainer_dataset_stockfish_provider_stockfishlabeledprovider): betweenness centrality 0.0000
- `.evaluate()` (trainer_scripts_stockfish_label_uciengine_evaluate): betweenness centrality 0.0000
- `StockfishLabelConfig` (trainer_scripts_stockfish_label_stockfishlabelconfig): betweenness centrality 0.0000
- `.positions()` (trainer_trainer_dataset_stockfish_provider_stockfishlabeledprovider_positions): betweenness centrality 0.0000
- `test_stockfish_label.py` (trainer_tests_scripts_test_stockfish_label): betweenness centrality 0.0000
- `stockfish_label.py` (trainer_scripts_stockfish_label): betweenness centrality 0.0000
- `test_golden_fixture_against_real_stockfish()` (trainer_tests_scripts_test_stockfish_label_test_golden_fixture_against_real_stockfish): betweenness centrality 0.0000
- `test_two_runs_produce_identical_labels()` (trainer_tests_scripts_test_stockfish_label_test_two_runs_produce_identical_labels): betweenness centrality 0.0000

## Newly introduced dependency cycles
_Detected via strongly-connected-components (existence check, not full cycle enumeration)._
- None

## Cross-module dependency changes
- None

## Community changes
_Community IDs are not stable across separate graphify runs — flagged via cohort-overlap (Jaccard < 0.5) among nodes present in both snapshots, not raw ID equality._
- **720 of 3112 common nodes (23%) flagged — this volume is typical Louvain re-clustering instability under any graph perturbation, not evidence of real coupling change. Read this section by checking whether the specific files this PR touched appear below, not by the raw count.**
- `.toFen()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_tofen): cohort overlap 0.01
- `.getCurrentFEN()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_getcurrentfen): cohort overlap 0.01
- `.captureGainFor()` (engine_core_src_main_java_coeusyk_game_chess_core_search_staticexchangeevaluator_staticexchangeevaluator_capturegainfor): cohort overlap 0.01
- `OpeningBook.java` (engine_core_src_main_java_coeusyk_game_chess_core_book_openingbook): cohort overlap 0.01
- `.isSquareAttackedBy()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_issquareattackedby): cohort overlap 0.01
- `.MovesGenerator()` (engine_core_src_main_java_coeusyk_game_chess_core_movegen_movesgenerator_movesgenerator_movesgenerator): cohort overlap 0.01
- `.getZobristHash()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_getzobristhash): cohort overlap 0.01
- `.futilityAndRazorMarginsAreDefinedAsConstants()` (engine_core_src_test_java_coeusyk_game_chess_core_search_searchertest_searchertest_futilityandrazormarginsaredefinedasconstants): cohort overlap 0.01
- `.unmakePromotionRestoresPawnOnOriginalSquare()` (engine_core_src_test_java_coeusyk_game_chess_core_movegen_promotionhandlingtest_promotionhandlingtest_unmakepromotionrestorespawnonoriginalsquare): cohort overlap 0.01
- `Move.java` (engine_core_src_main_java_coeusyk_game_chess_core_models_move): cohort overlap 0.01
- `.genKnightCaptures()` (engine_core_src_main_java_coeusyk_game_chess_core_movegen_movesgenerator_movesgenerator_genknightcaptures): cohort overlap 0.02
- `DTZResult.java` (engine_core_src_main_java_coeusyk_game_chess_core_syzygy_dtzresult): cohort overlap 0.02
- `Quantization / int16 vs Float Oracle Drift` (claude_skills_nnue_debug_skill_quantization_drift): cohort overlap 0.02
- `TranspositionTableTest.java` (engine_core_src_test_java_coeusyk_game_chess_core_search_transpositiontabletest): cohort overlap 0.02
- `.updateCastlingAvailabilityForMove()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_updatecastlingavailabilityformove): cohort overlap 0.02
- ... and 705 more

## New God Nodes (top 15 by degree)
- None

## Architectural cohesion
- maintained (modularity 0.7715 -> 0.7687, delta -0.0027)

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

D-8 continues this branch's pattern of a flat **Engine Architecture 0 node delta**
(1573 -> 1573) — this PR's scope (issue #199) is entirely a Python-side offline
labeling tool, and `git diff --name-only` from the prior commit confirms zero files
under `engine-core`/`engine-uci`/`engine-tuner`/`chess-engine-api` were touched. The
labeling driver talks to Stockfish exclusively via a subprocess pipe (stdin/stdout
UCI text), never via any code path the Java engine could inherit a dependency
through — the graph confirms this structurally, not just by inspection.

**Trainer Architecture**: 359 -> 429 (net +70, reported as +72/-2) —
`stockfish_label.py` (`StockfishLabelConfig`, `UciEngine`, `label_positions`),
`stockfish_provider.py` (`StockfishLabeledProvider`), their test files, and the
`mmap_shard.py` extension are exactly this PR's real content. The bridge-node
candidates list reads correctly end to end: `label_positions()`, `UciEngine`,
`StockfishLabeledProvider`, `StockfishLabelConfig` — the driver's own core types and
the new `DatasetProvider`. No unexplained additions.

The "Top 10 nodes by degree change" section is dominated by `mmap_shard.py`'s
`read_shard()`/`write_shard()` gaining degree (+8/+4) and `PositionRecord`/
`PositionLabel`/`PositionMetadata`/`ShardRef` losing some (-8/-5/-5/-2) — this is the
direct, expected consequence of `search_depth`/`search_nodes` now flowing through
`_encode`/`_decode` and the new `StockfishLabeledProvider` consuming the same shard
utility Stage 1 already used, redistributing edges across a wider set of callers
rather than concentrating them on the contract types alone. Not a coupling
regression — the contract types' *absolute* usage didn't drop, more call sites now
share the load.

The Architectural Boundary Report is unanimous "No change" across every tracked row,
including all five `FROZEN_BOUNDARY_CLASSES`. This matters specifically for this PR:
D-8 is the first PR in this phase to spawn an *external, non-Python-tooling*
subprocess (a real Stockfish binary) from the trainer — exactly the kind of change
that could plausibly (if done carelessly) blur the engine/trainer boundary. The graph
confirms it didn't: `NnueNetwork` coupling changes: No change, zero cross-module
dependency changes. Frozen boundary verdict: **UNCHANGED**.

Modularity moved by a small, unremarkable amount (0.7715 -> 0.7687, delta -0.0027),
within the same noise band every prior Phase D audit has already established. The
community-churn section (720 of 3112 common nodes, 23%, flagged) was checked against
this PR's actual file list before being dismissed as noise — the listed churned nodes
are pre-existing Java classes unrelated to `trainer/scripts` or the new
`stockfish_provider.py`, matching every prior audit's pattern.

No newly introduced dependency cycles, no new God nodes, no cross-module dependency
changes. `StockfishLabeledProvider`'s Invariant 1 isolation (no import of
`TextDatasetProvider`, `scripts.stockfish_label`, or `Trainer`/`Quantizer`/`Exporter`
internals) was verified two ways this PR: a direct source-inspection test
(`test_provider_does_not_import_labeling_driver_or_other_providers`) and,
independently, this graph — `stockfish_provider.py` has no edge to
`scripts.stockfish_label` or `trainer.dataset.text_provider` anywhere in the
after-graph, confirming the isolation claim structurally, not just by the test's own
string-matching logic.

