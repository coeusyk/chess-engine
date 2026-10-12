# Architecture Graph Audit — E-1: Classical-Eval-Labeled Corpus Generator

- Before commit: `3e7558e0816bdea4cbd4a4e005b37c96d64c38f3`
- After commit: `3fb569a91ef973b4764388d24304ba51cb6f0082`
- Before: 3272 nodes, 7061 edges
- After: 3279 nodes, 7077 edges
- Node delta: +10 / -3
- Edge delta: +23 / -7

## Architecture Split
_Scoping summary only — the detailed sections below (centrality, degree, boundary report, etc.) remain whole-graph, since those measures are not meaningful computed on a subgraph alone._

## Engine Architecture
- Before: 1581 nodes; After: 1584 nodes
- Node delta: +3 / -0

## Trainer Architecture
- Before: 429 nodes; After: 432 nodes
- Node delta: +6 / -3

## Top 10 nodes by betweenness centrality change
- `ClassicalEvaluator.java` (engine_core_src_main_java_coeusyk_game_chess_core_eval_classicalevaluator): 0.0000 -> 0.0001 (+0.0000)
- `train()` (trainer_trainer_model_train_train): 0.0000 -> 0.0000 (-0.0000)
- `_trained_model()` (trainer_tests_validation_test_validator_trained_model): 0.0000 -> 0.0000 (-0.0000)
- `Board` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board): 0.0035 -> 0.0035 (-0.0000)
- `ClassicalEvaluator` (engine_core_src_main_java_coeusyk_game_chess_core_eval_classicalevaluator_classicalevaluator): 0.0001 -> 0.0001 (+0.0000)
- `encode_batch()` (trainer_trainer_model_batching_encode_batch): 0.0000 -> 0.0000 (-0.0000)
- `MovesGenerator` (engine_core_src_main_java_coeusyk_game_chess_core_movegen_movesgenerator_movesgenerator): 0.0009 -> 0.0009 (-0.0000)
- `Evaluator` (engine_core_src_main_java_coeusyk_game_chess_core_eval_evaluator_evaluator): 0.0001 -> 0.0001 (+0.0000)
- `TunerPosition` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_tunerposition_tunerposition): 0.0008 -> 0.0008 (-0.0000)
- `encode_fens()` (trainer_trainer_model_batching_encode_fens): 0.0000 -> 0.0000 (-0.0000)

## Top 10 nodes by degree change
- `Tag` (tag): 2 -> 4 (+2)
- `Test` (test): 27 -> 29 (+2)
- `NnueNet` (nnuenet): 2 -> 0 (-2)
- `NnueNet` (trainer_trainer_validation_validator_py_nnuenet): 0 -> 2 (+2)
- `validator.py` (trainer_trainer_validation_validator): 7 -> 8 (+1)
- `test_eval_scale_check_is_near_zero_when_classical_matches_predicted()` (trainer_tests_validation_test_validator_test_eval_scale_check_is_near_zero_when_classical_matches_predicted): 7 -> 6 (-1)
- `Board` (board): 15 -> 16 (+1)
- `ClassicalEvalRecord` (trainer_trainer_validation_validator_classicalevalrecord): 5 -> 6 (+1)
- `Path` (trainer_tests_validation_test_validator_py_path): 6 -> 7 (+1)
- `encode_fens()` (trainer_trainer_model_batching_encode_fens): 9 -> 8 (-1)

## Newly introduced architectural bridge-node candidates
_New nodes ranked by betweenness centrality in the after-graph (>0). Heuristic, not a graphify-defined threshold._
- `load_classical_eval_corpus()` (trainer_trainer_validation_validator_load_classical_eval_corpus): betweenness centrality 0.0000
- `test_eval_scale_check_runs_against_the_real_classical_corpus()` (trainer_tests_validation_test_validator_test_eval_scale_check_runs_against_the_real_classical_corpus): betweenness centrality 0.0000
- `ClassicalCorpusGenerator` (engine_core_src_test_java_coeusyk_game_chess_core_eval_nnue_classicalcorpusgenerator_classicalcorpusgenerator): betweenness centrality 0.0000
- `.generateClassicalGoldenEvals()` (engine_core_src_test_java_coeusyk_game_chess_core_eval_nnue_classicalcorpusgenerator_classicalcorpusgenerator_generateclassicalgoldenevals): betweenness centrality 0.0000

## Newly introduced dependency cycles
_Detected via strongly-connected-components (existence check, not full cycle enumeration)._
- None

## Cross-module dependency changes
- None

## Community changes
_Community IDs are not stable across separate graphify runs — flagged via cohort-overlap (Jaccard < 0.5) among nodes present in both snapshots, not raw ID equality._
- **554 of 3269 common nodes (17%) flagged — this volume is typical Louvain re-clustering instability under any graph perturbation, not evidence of real coupling change. Read this section by checking whether the specific files this PR touched appear below, not by the raw count.**
- `KingSafety.java` (engine_core_src_main_java_coeusyk_game_chess_core_eval_kingsafety): cohort overlap 0.01
- `.isQuietMove()` (engine_core_src_main_java_coeusyk_game_chess_core_search_searcher_searcher_isquietmove): cohort overlap 0.01
- `.bruteForceNegamax()` (engine_core_src_test_java_coeusyk_game_chess_core_search_searchertest_searchertest_bruteforcenegamax): cohort overlap 0.01
- `.isExcludedMove()` (engine_core_src_main_java_coeusyk_game_chess_core_search_searcher_searcher_isexcludedmove): cohort overlap 0.01
- `.unmakePromotionRestoresPawnOnOriginalSquare()` (engine_core_src_test_java_coeusyk_game_chess_core_movegen_promotionhandlingtest_promotionhandlingtest_unmakepromotionrestorespawnonoriginalsquare): cohort overlap 0.01
- `Move` (move): cohort overlap 0.01
- `OpeningBook.java` (engine_core_src_main_java_coeusyk_game_chess_core_book_openingbook): cohort overlap 0.01
- `.getLmrReductionForTesting()` (engine_core_src_main_java_coeusyk_game_chess_core_search_searcher_searcher_getlmrreductionfortesting): cohort overlap 0.01
- `.computePhase()` (engine_core_src_main_java_coeusyk_game_chess_core_eval_evaluator_evaluator_computephase): cohort overlap 0.02
- `.appendCheckOrMateSuffix()` (engine_core_src_main_java_coeusyk_game_chess_core_notation_sanconverter_sanconverter_appendcheckormatesuffix): cohort overlap 0.02
- `.loadFen()` (chess_engine_api_src_main_java_coeusyk_game_chess_services_chessgameservice_chessgameservice_loadfen): cohort overlap 0.02
- `EvaluatorStrategy` (evaluatorstrategy): cohort overlap 0.02
- `UciConverter.java` (chess_engine_api_src_main_java_coeusyk_game_chess_utils_uciconverter): cohort overlap 0.03
- `.formatVerifyResult()` (engine_uci_src_main_java_coeusyk_game_chess_uci_uciapplication_uciapplication_formatverifyresult): cohort overlap 0.03
- `RebuildDiff` (rebuilddiff): cohort overlap 0.03
- ... and 539 more

## New God Nodes (top 15 by degree)
- None

## Architectural cohesion
- maintained (modularity 0.7809 -> 0.7734, delta -0.0075)

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

E-1's real content shows up exactly where expected and nowhere else. The four
"newly introduced architectural bridge-node candidates" are precisely this issue's
deliverables: `ClassicalCorpusGenerator`/`.generateClassicalGoldenEvals()` (the new
Java test-scope tool) and `load_classical_eval_corpus()`/
`test_eval_scale_check_runs_against_the_real_classical_corpus()` (the new Python
loader and its smoke test). No unexplained additions.

**Engine Architecture**: 1581 -> 1584 (+3/-0). The new `ClassicalCorpusGenerator`
class and its one method account for the bulk of this; all three added nodes are
under `engine-core/src/test/java`, none under `src/main`, confirming the issue's own
"Architecture Invariants" bullet ("this tool is test-scope only, adds no production
dependency") structurally, not just by having read the source. `ClassicalEvaluator`
gained a small betweenness-centrality bump (0.0000 -> 0.0001) from being called in a
new place — the expected, harmless consequence of a second caller (the corpus
generator) invoking an already-existing, already-stateless evaluator.

**Trainer Architecture**: 429 -> 432 (+6/-3, net +3). `load_classical_eval_corpus()`
and the new smoke test are the additions; the `-3` and the `NnueNet` node
renumbering in the degree-change table (`nnuenet: 2 -> 0`, `..._py_nnuenet: 0 -> 2`)
reflect graphify re-scoping an existing `NnueNet` reference to a more specific
per-module id once the new test imported it in a new context — a labeling
artifact of the graph builder, not a real structural change (the class itself,
`trainer/trainer/model/network.py`'s `NnueNet`, is untouched by this PR's diff).

The Architectural Boundary Report is unanimous "No change" across every tracked
row, including all `FROZEN_BOUNDARY_CLASSES`, and the frozen boundary verdict is
**UNCHANGED**. This is the graph's independent confirmation of Invariant 8
(engine/trainer isolation): the new Java class lives entirely in test scope with
zero production-code edges, and the new Python function lives in
`trainer/trainer/validation/`, never touching `trainer/trainer/dataset/`'s
`DatasetProvider` hierarchy (Invariant 1) — `load_classical_eval_corpus()` returns a
plain `List[ClassicalEvalRecord]`, not a `DatasetProvider`, and no edge from it to
`TextDatasetProvider`/`StockfishLabeledProvider` appears anywhere in the after-graph.

No newly introduced dependency cycles, no new God nodes, no cross-module dependency
changes. Modularity moved by a small, unremarkable amount (0.7809 -> 0.7734, delta
-0.0075), within the same noise band every prior Phase D/E audit has already
established. The community-churn section (554 of 3269 common nodes, 17%, flagged)
was checked against this PR's actual file list before being dismissed as noise — the
listed churned nodes (`KingSafety.java`, `Searcher` internals, `OpeningBook.java`,
`UciConverter.java`, etc.) are pre-existing, unrelated classes, matching every prior
audit's pattern.

Overall: a small, additive-only PR whose graph footprint is exactly its stated
scope — two new Java test-scope nodes, two new Python nodes, zero boundary
changes, zero cycles, zero cross-module drift.
