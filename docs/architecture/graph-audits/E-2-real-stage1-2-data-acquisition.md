# Architecture Graph Audit — E-2: Real Stage 1+2 Data Acquisition/Labeling Run

- Before commit: `15e021a1291dba63d8eebf1ef27d128f1656b073`
- After commit: `44dce258678ee496b580d95f4d70085e0f09b7f8`
- Before: 3303 nodes, 7100 edges
- After: 3354 nodes, 7185 edges
- Node delta: +60 / -9
- Edge delta: +97 / -12

## Architecture Split
_Scoping summary only — the detailed sections below (centrality, degree, boundary report, etc.) remain whole-graph, since those measures are not meaningful computed on a subgraph alone._

## Engine Architecture
- Before: 1584 nodes; After: 1584 nodes
- Node delta: +0 / -0

## Trainer Architecture
- Before: 432 nodes; After: 478 nodes
- Node delta: +55 / -9

## Top 10 nodes by betweenness centrality change
- `Board` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board): 0.0035 -> 0.0034 (-0.0001)
- `MovesGenerator` (engine_core_src_main_java_coeusyk_game_chess_core_movegen_movesgenerator_movesgenerator): 0.0009 -> 0.0009 (-0.0000)
- `TunerPosition` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_tunerposition_tunerposition): 0.0008 -> 0.0008 (-0.0000)
- `NNUE_PRD.md` (docs_nnue_prd): 0.0000 -> 0.0000 (-0.0000)
- `LabelledPosition` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_labelledposition_labelledposition): 0.0006 -> 0.0006 (-0.0000)
- `.iterativeDeepening()` (engine_core_src_main_java_coeusyk_game_chess_core_search_searcher_searcher_iterativedeepening): 0.0006 -> 0.0006 (-0.0000)
- `.searchRoot()` (engine_core_src_main_java_coeusyk_game_chess_core_search_searcher_searcher_searchroot): 0.0005 -> 0.0005 (-0.0000)
- `Task 14.7 WDL Self-Play Pilot` (changelog_task_14_7_wdl_selfplay_pilot): 0.0000 -> 0.0000 (-0.0000)
- `.searchDepth()` (engine_core_src_main_java_coeusyk_game_chess_core_search_searcher_searcher_searchdepth): 0.0005 -> 0.0005 (-0.0000)
- `.from()` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_tunerposition_tunerposition_from): 0.0004 -> 0.0004 (-0.0000)

## Top 10 nodes by degree change
- `NNUE_PRD.md` (docs_nnue_prd): 5 -> 2 (-3)
- `ADR-006-self-written-pytorch-trainer.md` (docs_adr_adr_006_self_written_pytorch_trainer): 2 -> 1 (-1)
- `ADR-010-trainer-repository-location.md` (docs_adr_adr_010_trainer_repository_location): 2 -> 1 (-1)
- `ADR-007-staged-training-data.md` (docs_adr_adr_007_staged_training_data): 2 -> 1 (-1)

## Newly introduced architectural bridge-node candidates
_New nodes ranked by betweenness centrality in the after-graph (>0). Heuristic, not a graphify-defined threshold._
- `acquire()` (trainer_scripts_acquire_stage1_lichess_acquire): betweenness centrality 0.0000
- `_iter_records()` (trainer_scripts_acquire_stage1_lichess_iter_records): betweenness centrality 0.0000
- `acquire_stage1_lichess.py` (trainer_scripts_acquire_stage1_lichess): betweenness centrality 0.0000
- `_to_record()` (trainer_scripts_acquire_stage1_lichess_to_record): betweenness centrality 0.0000
- `test_acquire_stage1_lichess.py` (trainer_tests_scripts_test_acquire_stage1_lichess): betweenness centrality 0.0000
- `stream_records()` (trainer_scripts_acquire_stage1_lichess_stream_records): betweenness centrality 0.0000
- `validate_dataset_manifest()` (trainer_trainer_dataset_manifest_schema_validate_dataset_manifest): betweenness centrality 0.0000
- `_open_source_stream()` (trainer_scripts_acquire_stage1_lichess_open_source_stream): betweenness centrality 0.0000
- `_write_fixture()` (trainer_tests_scripts_test_acquire_stage1_lichess_write_fixture): betweenness centrality 0.0000
- ``stockfish-label-e2-real.json` — provenance of each value` (trainer_configs_stockfish_label_e2_real_stockfish_label_e2_real_json_provenance_of_each_value): betweenness centrality 0.0000

## Newly introduced dependency cycles
_Detected via strongly-connected-components (existence check, not full cycle enumeration)._
- None

## Cross-module dependency changes
- None

## Community changes
_Community IDs are not stable across separate graphify runs — flagged via cohort-overlap (Jaccard < 0.5) among nodes present in both snapshots, not raw ID equality._
- **490 of 3294 common nodes (15%) flagged — this volume is typical Louvain re-clustering instability under any graph perturbation, not evidence of real coupling change. Read this section by checking whether the specific files this PR touched appear below, not by the raw count.**
- `MoveOrderer.java` (engine_core_src_main_java_coeusyk_game_chess_core_search_moveorderer): cohort overlap 0.01
- `.getZobristHash()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_getzobristhash): cohort overlap 0.01
- `.bruteForceNegamax()` (engine_core_src_test_java_coeusyk_game_chess_core_search_searchertest_searchertest_bruteforcenegamax): cohort overlap 0.01
- `Piece.java` (engine_core_src_main_java_coeusyk_game_chess_core_models_piece): cohort overlap 0.01
- `.evaluateTerminal()` (engine_core_src_main_java_coeusyk_game_chess_core_search_searcher_searcher_evaluateterminal): cohort overlap 0.01
- `.enableTTStats()` (engine_core_src_main_java_coeusyk_game_chess_core_search_searcher_searcher_enablettstats): cohort overlap 0.01
- `ClassicalEvaluator.java` (engine_core_src_main_java_coeusyk_game_chess_core_eval_classicalevaluator): cohort overlap 0.01
- `.explainEval()` (engine_core_src_main_java_coeusyk_game_chess_core_eval_evaluator_evaluator_explaineval): cohort overlap 0.01
- `.singularityGuardRequiresDepthAndQualifiedTtEntry()` (engine_core_src_test_java_coeusyk_game_chess_core_search_searchertest_searchertest_singularityguardrequiresdepthandqualifiedttentry): cohort overlap 0.01
- `Evaluator.java` (engine_core_src_main_java_coeusyk_game_chess_core_eval_evaluator): cohort overlap 0.01
- `KingSafety.java` (engine_core_src_main_java_coeusyk_game_chess_core_eval_kingsafety): cohort overlap 0.01
- `.setEvaluatorStrategy()` (engine_core_src_main_java_coeusyk_game_chess_core_search_searcher_searcher_setevaluatorstrategy): cohort overlap 0.01
- `ClassicalEvaluator` (engine_core_src_main_java_coeusyk_game_chess_core_eval_classicalevaluator_classicalevaluator): cohort overlap 0.01
- `StaticExchangeEvaluator.java` (engine_core_src_main_java_coeusyk_game_chess_core_search_staticexchangeevaluator): cohort overlap 0.02
- `.contemptScore()` (engine_core_src_main_java_coeusyk_game_chess_core_search_searcher_searcher_contemptscore): cohort overlap 0.02
- ... and 475 more

## New God Nodes (top 15 by degree)
- None

## Architectural cohesion
- maintained (modularity 0.7850 -> 0.7869, delta +0.0019)

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

E-2's real content shows up exactly where expected and nowhere else. The graph's
independent confirmation of this issue's Non-Scope: **Engine Architecture is
unchanged (1584 -> 1584, +0/-0)** — zero Java files were touched, matching "no
changes to `TextDatasetProvider`'s or `stockfish_label.py`'s own logic" and no
Stage 3/self-play work. All real movement is in **Trainer Architecture (432 -> 478,
+55/-9, net +46)**.

The "newly introduced architectural bridge-node candidates" are precisely this
issue's new deliverables and nothing else: `acquire()`, `_iter_records()`,
`_to_record()`, `_open_source_stream()`, `stream_records()` (all
`acquire_stage1_lichess.py`, the new Stage 1 acquisition driver),
`validate_dataset_manifest()` (the new dataset-manifest validator), plus their
test-scope counterparts (`test_acquire_stage1_lichess.py`, `_write_fixture()`) and
the new `stockfish-label-e2-real.json` provenance doc node. No unexplained
additions — every new bridge-node candidate maps directly to a file this commit
actually added.

**Degree-change table (`NNUE_PRD.md`, `ADR-006`/`ADR-007`/`ADR-010`, all -1 to -3):
a known, expected side effect of the post-commit hook's own documented scope, not
a real architectural regression.** Verified directly (not inferred from the table
alone): before this commit, `docs_nnue_prd` carried 3 `references` edges to those
three ADRs, extracted by an earlier *semantic* (LLM) pass. The installed
post-commit hook rebuilds "code files only, no LLM needed" (its own header
comment) — when it detected `NNUE_PRD.md` had changed, it correctly pruned that
file's stale node data but has no LLM pass available to regenerate the semantic
`references` edges, leaving the after-graph with only the 2 structurally-derivable
edges (the inbound reference from the changelog, and the file's own `contains`
edge to its title node). The PRD's actual prose still cites all three ADRs
unchanged (`git diff` for this commit touches exactly one line, the Open Question 1
resolution) — this is the graph's semantic layer for one file going stale, not a
change in the document's real content or any real decoupling from those ADRs. A
future full or `--update` run with semantic extraction enabled (as this session did
manually, mid-review, before the shrink-guard correctly refused to merge a
scope-mismatched incremental result — see this issue's completion report) would
restore these edges; the code-only hook alone cannot and was never expected to.

No newly introduced dependency cycles, no cross-module dependency changes, no new
God nodes. The Architectural Boundary Report is unanimous "No change" across every
tracked row, including all `FROZEN_BOUNDARY_CLASSES`, and the frozen boundary
verdict is **UNCHANGED** — this is the graph's independent confirmation of
Invariant 8 (engine/trainer isolation) holding through this issue, on top of
Invariant 1 (`DatasetProvider` isolation) and Invariant 6 (provenance chain) already
verified directly (not just via the graph) elsewhere in this issue's completion
report: `acquire_stage1_lichess.py` produces a manifest via
`validate_dataset_manifest()`, never imports another `DatasetProvider`, and no edge
from any of its new nodes to `TextDatasetProvider`/`StockfishLabeledProvider`
appears anywhere in the after-graph.

Modularity moved by a small, positive amount (0.7850 -> 0.7869, delta +0.0019) —
within the same noise band every prior Phase D/E audit has established, and if
anything slightly *more* cohesive, not less. The community-churn section (490 of
3294 common nodes, 15%, flagged) was checked against this PR's actual file list
before being dismissed as noise — the listed churned nodes (`MoveOrderer.java`,
`Piece.java`, `KingSafety.java`, `StaticExchangeEvaluator.java`, etc.) are
pre-existing, unrelated engine-core classes, matching every prior audit's pattern
of Louvain re-clustering instability rather than real coupling change.

Overall: an additive-only commit whose graph footprint is exactly its stated
scope — nine new Python/doc nodes in Trainer Architecture, zero Engine Architecture
change, zero boundary changes, zero cycles, zero cross-module drift, and one
identified (not hidden) side effect of the code-only post-commit hook's own scope
on a single pre-existing doc node, fully explained above rather than left as an
unremarked anomaly in the degree-change table.

