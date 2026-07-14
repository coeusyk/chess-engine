# Architecture Graph Audit — D-6: Exporter + Provenance Manifest + Validator

- Before commit: `ce4c7da190a68c5e298e97c555eba18f33f8743b`
- After commit: `67117abfbaf0ddb549365ee4adcc218ef1b4164f`
- Before: 2908 nodes, 6624 edges
- After: 3003 nodes, 6792 edges
- Node delta: +95 / -0
- Edge delta: +179 / -11

## Architecture Split
_Scoping summary only — the detailed sections below (centrality, degree, boundary report, etc.) remain whole-graph, since those measures are not meaningful computed on a subgraph alone._

## Engine Architecture
- Before: 1566 nodes; After: 1573 nodes
- Node delta: +7 / -0

## Trainer Architecture
- Before: 275 nodes; After: 329 nodes
- Node delta: +54 / -0

## Top 10 nodes by betweenness centrality change
- `Board` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board): 0.0072 -> 0.0068 (-0.0004)
- `MovesGenerator` (engine_core_src_main_java_coeusyk_game_chess_core_movegen_movesgenerator_movesgenerator): 0.0024 -> 0.0023 (-0.0002)
- `TunerPosition` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_tunerposition_tunerposition): 0.0014 -> 0.0013 (-0.0001)
- `LabelledPosition` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_labelledposition_labelledposition): 0.0011 -> 0.0010 (-0.0001)
- `.from()` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_tunerposition_tunerposition_from): 0.0009 -> 0.0008 (-0.0001)
- `.isCheckmate()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_ischeckmate): 0.0008 -> 0.0007 (-0.0000)
- `.isStalemate()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_isstalemate): 0.0008 -> 0.0007 (-0.0000)
- `.toBoard()` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_tunerposition_tunerposition_toboard): 0.0008 -> 0.0007 (-0.0000)
- `Move` (engine_core_src_main_java_coeusyk_game_chess_core_models_move_move): 0.0007 -> 0.0007 (-0.0000)
- `.evaluate()` (engine_core_src_main_java_coeusyk_game_chess_core_eval_evaluator_evaluator_evaluate): 0.0007 -> 0.0006 (-0.0000)

## Top 10 nodes by degree change
- `train()` (trainer_trainer_model_train_train): 16 -> 14 (-2)
- `Test` (test): 6 -> 8 (+2)
- `NnueNet` (trainer_trainer_model_network_nnuenet): 10 -> 8 (-2)
- `Any` (trainer_trainer_model_train_py_any): 1 -> 0 (-1)
- `TrainingConfig` (trainer_trainer_model_train_trainingconfig): 4 -> 3 (-1)
- `seed_everything()` (trainer_trainer_reproducibility_seeding_seed_everything): 7 -> 6 (-1)
- `test_batching.py` (trainer_tests_model_test_batching): 4 -> 5 (+1)
- `capture()` (trainer_trainer_reproducibility_experiment_metadata_capture): 9 -> 8 (-1)
- `9. Provenance Chain` (docs_architecture_nnue_trainer_architecture_9_provenance_chain): 1 -> 2 (+1)
- `_record()` (trainer_tests_model_test_batching_record): 6 -> 7 (+1)

## Newly introduced architectural bridge-node candidates
_New nodes ranked by betweenness centrality in the after-graph (>0). Heuristic, not a graphify-defined threshold._
- `encode_fens()` (trainer_trainer_model_batching_encode_fens): betweenness centrality 0.0000
- `export()` (trainer_trainer_export_exporter_export): betweenness centrality 0.0000
- `evaluate_held_out()` (trainer_trainer_validation_validator_evaluate_held_out): betweenness centrality 0.0000
- `_trained_model()` (trainer_tests_validation_test_validator_trained_model): betweenness centrality 0.0000
- `_nnue_bytes()` (trainer_trainer_export_exporter_nnue_bytes): betweenness centrality 0.0000
- `eval_scale_check()` (trainer_trainer_validation_validator_eval_scale_check): betweenness centrality 0.0000
- `validator.py` (trainer_trainer_validation_validator): betweenness centrality 0.0000
- `exporter.py` (trainer_trainer_export_exporter): betweenness centrality 0.0000
- `_write_int16_array()` (trainer_trainer_export_exporter_write_int16_array): betweenness centrality 0.0000
- `_write_manifest()` (trainer_trainer_export_exporter_write_manifest): betweenness centrality 0.0000

## Newly introduced dependency cycles
_Detected via strongly-connected-components (existence check, not full cycle enumeration)._
- None

## Cross-module dependency changes
- None

## Community changes
_Community IDs are not stable across separate graphify runs — flagged via cohort-overlap (Jaccard < 0.5) among nodes present in both snapshots, not raw ID equality._
- **770 of 2908 common nodes (26%) flagged — this volume is typical Louvain re-clustering instability under any graph perturbation, not evidence of real coupling change. Read this section by checking whether the specific files this PR touched appear below, not by the raw count.**
- `.toFen()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_tofen): cohort overlap 0.01
- `NnueEvaluatorDebugTest.java` (engine_core_src_test_java_coeusyk_game_chess_core_eval_nnue_nnueevaluatordebugtest): cohort overlap 0.01
- `.backwardPawnCount()` (engine_core_src_main_java_coeusyk_game_chess_core_eval_evaluator_evaluator_backwardpawncount): cohort overlap 0.01
- `EvaluatorStrategy` (evaluatorstrategy): cohort overlap 0.01
- `.simulateEndgame()` (engine_uci_src_test_java_coeusyk_game_chess_uci_endgamehandlingtest_endgamehandlingtest_simulateendgame): cohort overlap 0.01
- `.setRootTtMoveHintForTesting()` (engine_core_src_main_java_coeusyk_game_chess_core_search_searcher_searcher_setrootttmovehintfortesting): cohort overlap 0.01
- `.wdlToScore()` (engine_core_src_main_java_coeusyk_game_chess_core_search_searcher_searcher_wdltoscore): cohort overlap 0.01
- `.isQuietMove()` (engine_core_src_main_java_coeusyk_game_chess_core_search_searcher_searcher_isquietmove): cohort overlap 0.01
- `.getZobristHash()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_getzobristhash): cohort overlap 0.01
- `ClassicalEvaluator.java` (engine_core_src_main_java_coeusyk_game_chess_core_eval_classicalevaluator): cohort overlap 0.01
- `.resolveBound()` (engine_core_src_main_java_coeusyk_game_chess_core_search_searcher_searcher_resolvebound): cohort overlap 0.01
- `.getState()` (chess_engine_api_src_main_java_coeusyk_game_chess_services_chessgameservice_chessgameservice_getstate): cohort overlap 0.01
- `.getChessSquare()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_getchesssquare): cohort overlap 0.01
- `.explainEval()` (engine_core_src_main_java_coeusyk_game_chess_core_eval_evaluator_evaluator_explaineval): cohort overlap 0.01
- `.genKnightCaptures()` (engine_core_src_main_java_coeusyk_game_chess_core_movegen_movesgenerator_movesgenerator_genknightcaptures): cohort overlap 0.02
- ... and 755 more

## New God Nodes (top 15 by degree)
- None

## Architectural cohesion
- maintained (modularity 0.7675 -> 0.7619, delta -0.0056)

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

D-6 breaks this branch's D-3/D-4/D-5 pattern of a flat **Engine Architecture 0 node
delta** — this PR shows **+7** (1566 -> 1573). That is expected and correct here, not a
regression: unlike the three prior Python-only PRs, D-6's own scope (issue #197)
explicitly requires "a Java-side round-trip test fixture... under
`engine-core/src/test/`, never `src/main/`". `git show --stat` confirms the +7 traces
to exactly three files, all under `src/test/`: `NnueExportFixtureRoundTripTest.java`
(one class, one test method), `README.md`, and the committed `.nnue` binary fixture —
**zero `src/main/` files touched**. The commit's own "Left out: no Java production
behavior changes (NnueNetwork.java untouched)" claim is graph-confirmed, not just
asserted: `NnueNetwork.java` appears in "NnueNetwork coupling changes: No change" below
despite the new test file calling `NnueNetwork.load()` directly, because that call
exercises an *existing* public method with no signature or behavior change — a new
caller, not a new production dependency.

**Trainer Architecture**: 275 -> 329 (+54) — `exporter.py`, `validator.py`, the
`encode_fens()` extraction in `batching.py`, and their tests, exactly this PR's
real content. The bridge-node candidates list reads correctly: `export()`,
`_nnue_bytes()`, `_write_manifest()`, `encode_fens()`, `evaluate_held_out()`,
`eval_scale_check()` — the writer, the manifest, the FEN-only encoding seam the
code-review's duplication finding produced, and Validator's two real checks. No
unexplained additions.

The Architectural Boundary Report is unanimous "No change" across every tracked row —
`production -> debug`, `debug -> production`, and all five `FROZEN_BOUNDARY_CLASSES`
(`EvaluatorStrategy`, `Searcher`, `NnueEvaluator`, `FeatureExtractor`, `NnueNetwork`).
This is the graph-level confirmation of this PR's central claim, independently earned
twice over in this PR already (a passing byte-layout unit test, a passing real-file
round-trip test) and now a third time structurally: the Python exporter and the frozen
Java loader remain exactly as coupled as they were before this PR — through the `.nnue`
file format only, never through a new code dependency in either direction. Frozen
boundary verdict: **UNCHANGED**.

Modularity moved by a small, unremarkable amount (0.7675 -> 0.7619, delta -0.0056),
within the same noise band D-2 through D-5's audits have already established for this
branch. The community-churn section (770 of 2908 common nodes, 26%, flagged) was
checked against this PR's actual file list before being dismissed as noise — the listed
churned nodes are pre-existing Java classes unrelated to `trainer/export`,
`trainer/validation`, or the new test file, matching every prior audit's pattern.

No newly introduced dependency cycles, no new God nodes, no cross-module dependency
changes. `Cross-module dependency changes: None` particularly matters here given this
PR's central architectural-conflict resolution: choosing to keep the frozen format
meant this PR could be, and was, implemented with zero new cross-module coupling beyond
the one sanctioned test-scope fixture — exactly the outcome "no Java changes" was
supposed to produce, now graph-confirmed rather than merely claimed.

