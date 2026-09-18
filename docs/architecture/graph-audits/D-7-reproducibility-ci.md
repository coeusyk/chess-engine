# Architecture Graph Audit — D-7: Trainer Reproducibility CI + Path-Scoped Workflow

- Before commit: `5dbdab6a6e5f3edffd32ee37edf6f5f083dcefab`
- After commit: `90a3f6bc059f6e28e7763b1354dac587d4d963e0`
- Before: 3030 nodes, 6811 edges
- After: 3064 nodes, 6832 edges
- Node delta: +36 / -2
- Edge delta: +49 / -28

## Architecture Split
_Scoping summary only — the detailed sections below (centrality, degree, boundary report, etc.) remain whole-graph, since those measures are not meaningful computed on a subgraph alone._

## Engine Architecture
- Before: 1573 nodes; After: 1573 nodes
- Node delta: +0 / -0

## Trainer Architecture
- Before: 329 nodes; After: 359 nodes
- Node delta: +32 / -2

## Top 10 nodes by betweenness centrality change
- `Board` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board): 0.0066 -> 0.0065 (-0.0001)
- `MovesGenerator` (engine_core_src_main_java_coeusyk_game_chess_core_movegen_movesgenerator_movesgenerator): 0.0022 -> 0.0022 (-0.0000)
- `TunerPosition` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_tunerposition_tunerposition): 0.0012 -> 0.0012 (-0.0000)
- `LabelledPosition` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_labelledposition_labelledposition): 0.0010 -> 0.0010 (-0.0000)
- `.from()` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_tunerposition_tunerposition_from): 0.0008 -> 0.0008 (-0.0000)
- `.isCheckmate()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_ischeckmate): 0.0007 -> 0.0007 (-0.0000)
- `.isStalemate()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_isstalemate): 0.0007 -> 0.0007 (-0.0000)
- `.toBoard()` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_tunerposition_tunerposition_toboard): 0.0007 -> 0.0007 (-0.0000)
- `Move` (engine_core_src_main_java_coeusyk_game_chess_core_models_move_move): 0.0007 -> 0.0007 (-0.0000)
- `.evaluate()` (engine_core_src_main_java_coeusyk_game_chess_core_eval_evaluator_evaluator_evaluate): 0.0006 -> 0.0006 (-0.0000)

## Top 10 nodes by degree change
- `export()` (trainer_trainer_export_exporter_export): 15 -> 10 (-5)
- `test_export_writes_nnue_and_manifest_with_matching_uuid()` (trainer_tests_export_test_exporter_test_export_writes_nnue_and_manifest_with_matching_uuid): 6 -> 2 (-4)
- `test_export_manifest_sha256_matches_actual_nnue_file()` (trainer_tests_export_test_exporter_test_export_manifest_sha256_matches_actual_nnue_file): 5 -> 2 (-3)
- `test_export_accepts_an_explicit_label_engine_version()` (trainer_tests_export_test_exporter_test_export_accepts_an_explicit_label_engine_version): 5 -> 2 (-3)
- `_nnue_bytes()` (trainer_trainer_export_exporter_nnue_bytes): 10 -> 7 (-3)
- `test_export_rejects_non_positive_qa_end_to_end()` (trainer_tests_export_test_exporter_test_export_rejects_non_positive_qa_end_to_end): 5 -> 2 (-3)
- `test_export_produces_a_fresh_uuid_each_call()` (trainer_tests_export_test_exporter_test_export_produces_a_fresh_uuid_each_call): 5 -> 2 (-3)
- `test_nnue_bytes_field_layout_matches_java_header_exactly()` (trainer_tests_export_test_exporter_test_nnue_bytes_field_layout_matches_java_header_exactly): 3 -> 1 (-2)
- `_write_utf()` (trainer_trainer_export_exporter_write_utf): 5 -> 3 (-2)
- `test_nnue_bytes_rejects_non_positive_qa()` (trainer_tests_export_test_exporter_test_nnue_bytes_rejects_non_positive_qa): 3 -> 1 (-2)

## Newly introduced architectural bridge-node candidates
_New nodes ranked by betweenness centrality in the after-graph (>0). Heuristic, not a graphify-defined threshold._
- `run_pipeline()` (trainer_trainer_cli_reproducibility_check_run_pipeline): betweenness centrality 0.0000
- `validate_manifest()` (trainer_trainer_export_manifest_schema_validate_manifest): betweenness centrality 0.0000
- `test_reproducibility_check.py` (trainer_tests_cli_test_reproducibility_check): betweenness centrality 0.0000
- `two_runs()` (trainer_tests_cli_test_reproducibility_check_two_runs): betweenness centrality 0.0000
- `reproducibility_check.py` (trainer_trainer_cli_reproducibility_check): betweenness centrality 0.0000
- `main()` (trainer_trainer_cli_reproducibility_check_main): betweenness centrality 0.0000
- `_fixtures.py` (trainer_tests_export_fixtures): betweenness centrality 0.0000
- `quantized_network()` (trainer_tests_export_fixtures_quantized_network): betweenness centrality 0.0000
- `test_run_b_manifest_is_schema_valid()` (trainer_tests_cli_test_reproducibility_check_test_run_b_manifest_is_schema_valid): betweenness centrality 0.0000
- `test_run_a_manifest_is_schema_valid()` (trainer_tests_cli_test_reproducibility_check_test_run_a_manifest_is_schema_valid): betweenness centrality 0.0000

## Newly introduced dependency cycles
_Detected via strongly-connected-components (existence check, not full cycle enumeration)._
- None

## Cross-module dependency changes
- None

## Community changes
_Community IDs are not stable across separate graphify runs — flagged via cohort-overlap (Jaccard < 0.5) among nodes present in both snapshots, not raw ID equality._
- **682 of 3028 common nodes (23%) flagged — this volume is typical Louvain re-clustering instability under any graph perturbation, not evidence of real coupling change. Read this section by checking whether the specific files this PR touched appear below, not by the raw count.**
- `MoveOrderer.java` (engine_core_src_main_java_coeusyk_game_chess_core_search_moveorderer): cohort overlap 0.01
- `.backwardPawnCount()` (engine_core_src_main_java_coeusyk_game_chess_core_eval_evaluator_evaluator_backwardpawncount): cohort overlap 0.01
- `KingSafety.java` (engine_core_src_main_java_coeusyk_game_chess_core_eval_kingsafety): cohort overlap 0.01
- `EvaluatorStrategy` (evaluatorstrategy): cohort overlap 0.01
- `.makeMove()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_makemove): cohort overlap 0.01
- `Evaluator.java` (engine_core_src_main_java_coeusyk_game_chess_core_eval_evaluator): cohort overlap 0.01
- `.filterLegal()` (engine_core_src_main_java_coeusyk_game_chess_core_movegen_movesgenerator_movesgenerator_filterlegal): cohort overlap 0.01
- `MovesGenerator.java` (engine_core_src_main_java_coeusyk_game_chess_core_movegen_movesgenerator): cohort overlap 0.01
- `ZobristHash.java` (engine_core_src_main_java_coeusyk_game_chess_core_bitboard_zobristhash): cohort overlap 0.01
- `.bruteForceNegamax()` (engine_core_src_test_java_coeusyk_game_chess_core_search_searchertest_searchertest_bruteforcenegamax): cohort overlap 0.01
- `.unmakePromotionRestoresPawnOnOriginalSquare()` (engine_core_src_test_java_coeusyk_game_chess_core_movegen_promotionhandlingtest_promotionhandlingtest_unmakepromotionrestorespawnonoriginalsquare): cohort overlap 0.01
- `.isSquareAttacked()` (engine_core_src_main_java_coeusyk_game_chess_core_movegen_movesgenerator_movesgenerator_issquareattacked): cohort overlap 0.01
- `.getHalfmoveClock()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_gethalfmoveclock): cohort overlap 0.01
- `.computePhase()` (engine_core_src_main_java_coeusyk_game_chess_core_eval_evaluator_evaluator_computephase): cohort overlap 0.01
- `.getLmrReductionForTesting()` (engine_core_src_main_java_coeusyk_game_chess_core_search_searcher_searcher_getlmrreductionfortesting): cohort overlap 0.01
- ... and 667 more

## New God Nodes (top 15 by degree)
- None

## Architectural cohesion
- increased (modularity 0.7666 -> 0.7768, delta +0.0102)

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

D-7 continues this branch's pattern of a flat **Engine Architecture 0 node delta**
(1573 -> 1573) — this PR's scope (issue #198) is entirely CI wiring and a Python
pipeline-runner, and `git diff --name-only` from the prior commit confirms zero files
under `engine-core`/`engine-uci`/`engine-tuner`/`chess-engine-api` were touched.

**Trainer Architecture**: 329 -> 359 (net +30, reported as +32/-2) — `run_pipeline()`,
`validate_manifest()`, their test files, and `tests/export/_fixtures.py` (the shared
test-data module extracted during code-review to remove duplication between
`test_exporter.py` and the new `test_manifest_schema.py`) are exactly this PR's real
content. The -2 and the "Top 10 nodes by degree change" section's cluster of
`test_export_*` functions losing 2-4 edges each are a direct, expected consequence of
that same extraction: those tests used to call locally-defined `_quantized_network()`/
`_metadata()` helpers living in the same file as the functions they exercised
(`export()`, `_nnue_bytes()`, `_write_utf()`); now they call into `_fixtures.py`
instead, so the graph correctly shows those call-target functions' degree shrinking —
this is the refactor working as intended, not an unexplained coupling loss.

The bridge-node candidates list reads correctly end to end: `run_pipeline()`,
`validate_manifest()`, `reproducibility_check.py`, `test_reproducibility_check.py`,
`_fixtures.py`, `quantized_network()` — the pipeline runner, the schema checker, and the
fixture-sharing module the code-review pass produced. No unexplained additions.

The Architectural Boundary Report is unanimous "No change" across every tracked row,
including all five `FROZEN_BOUNDARY_CLASSES`. This matters specifically for this PR:
D-7 touches CI workflow files and a Python-only pipeline runner, both classes of change
that could plausibly (if done carelessly) introduce an accidental Java-side dependency
via a build step or a script invoked from `engine-core`'s own build — the graph
confirms none did. Frozen boundary verdict: **UNCHANGED**.

Modularity *increased* this time (0.7666 -> 0.7768, delta +0.0102) rather than the
small decreases D-3 through D-6 each showed — consistent with the fixture-deduplication
refactor: removing a duplicated helper pair and centralizing it behind one shared
import is exactly the kind of change that tightens cohesion rather than loosening it.
The community-churn section (682 of 3028 common nodes, 23%, flagged) was checked
against this PR's actual file list before being dismissed as noise — the listed churned
nodes are pre-existing Java classes unrelated to `trainer/cli`, `trainer/export`, or the
CI workflow files, matching every prior audit's pattern.

No newly introduced dependency cycles, no new God nodes, no cross-module dependency
changes. Node/edge counts are the smallest expected delta of any Phase D PR so far
(+36/-2 nodes) — proportionate to a CI-and-glue-code PR that deliberately reuses
existing fixtures, existing pipeline stages, and an existing CI job rather than adding
new production surface area.

