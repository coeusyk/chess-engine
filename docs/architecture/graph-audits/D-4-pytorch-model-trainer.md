# Architecture Graph Audit — D-4: PyTorch Model + Trainer (training loop) + Reproducibility Infrastructure

- Before commit: `e3524a15e236ba3c170fa218c0301e17210ddd6d`
- After commit: `a32142adb28fd8d683cfbda83e2405b9647e88aa`
- Before: 2687 nodes, 6316 edges
- After: 2794 nodes, 6464 edges
- Node delta: +107 / -0
- Edge delta: +148 / -0

## Architecture Split
_Scoping summary only — the detailed sections below (centrality, degree, boundary report, etc.) remain whole-graph, since those measures are not meaningful computed on a subgraph alone._

## Engine Architecture
- Before: 1566 nodes; After: 1566 nodes
- Node delta: +0 / -0

## Trainer Architecture
- Before: 131 nodes; After: 219 nodes
- Node delta: +88 / -0

## Top 10 nodes by betweenness centrality change
- `Board` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board): 0.0085 -> 0.0078 (-0.0006)
- `MovesGenerator` (engine_core_src_main_java_coeusyk_game_chess_core_movegen_movesgenerator_movesgenerator): 0.0029 -> 0.0026 (-0.0002)
- `TunerPosition` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_tunerposition_tunerposition): 0.0016 -> 0.0015 (-0.0001)
- `LabelledPosition` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_labelledposition_labelledposition): 0.0013 -> 0.0012 (-0.0001)
- `.from()` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_tunerposition_tunerposition_from): 0.0011 -> 0.0010 (-0.0001)
- `.isCheckmate()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_ischeckmate): 0.0009 -> 0.0009 (-0.0001)
- `.isStalemate()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_isstalemate): 0.0009 -> 0.0008 (-0.0001)
- `.toBoard()` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_tunerposition_tunerposition_toboard): 0.0009 -> 0.0008 (-0.0001)
- `Move` (engine_core_src_main_java_coeusyk_game_chess_core_models_move_move): 0.0009 -> 0.0008 (-0.0001)
- `.evaluate()` (engine_core_src_main_java_coeusyk_game_chess_core_eval_evaluator_evaluator_evaluate): 0.0008 -> 0.0007 (-0.0001)

## Top 10 nodes by degree change
- `Path` (path): 5 -> 8 (+3)
- `10. Reproducibility Guarantees` (docs_architecture_nnue_trainer_architecture_10_reproducibility_guarantees): 1 -> 2 (+1)

## Newly introduced architectural bridge-node candidates
_New nodes ranked by betweenness centrality in the after-graph (>0). Heuristic, not a graphify-defined threshold._
- `train()` (trainer_trainer_model_train_train): betweenness centrality 0.0000
- `encode_batch()` (trainer_trainer_model_batching_encode_batch): betweenness centrality 0.0000
- `NnueNet` (trainer_trainer_model_network_nnuenet): betweenness centrality 0.0000
- `capture()` (trainer_trainer_reproducibility_experiment_metadata_capture): betweenness centrality 0.0000
- `train.py` (trainer_trainer_model_train): betweenness centrality 0.0000
- `TrainingConfig` (trainer_trainer_model_train_trainingconfig): betweenness centrality 0.0000
- `_flatten()` (trainer_trainer_model_batching_flatten): betweenness centrality 0.0000
- `EncodedBatch` (trainer_trainer_model_batching_encodedbatch): betweenness centrality 0.0000
- `.clip_ft_weights_()` (trainer_trainer_model_network_nnuenet_clip_ft_weights): betweenness centrality 0.0000
- `texel_sigmoid()` (trainer_trainer_model_train_texel_sigmoid): betweenness centrality 0.0000

## Newly introduced dependency cycles
_Detected via strongly-connected-components (existence check, not full cycle enumeration)._
- None

## Cross-module dependency changes
- None

## Community changes
_Community IDs are not stable across separate graphify runs — flagged via cohort-overlap (Jaccard < 0.5) among nodes present in both snapshots, not raw ID equality._
- **564 of 2687 common nodes (21%) flagged — this volume is typical Louvain re-clustering instability under any graph perturbation, not evidence of real coupling change. Read this section by checking whether the specific files this PR touched appear below, not by the raw count.**
- `.bruteForceNegamax()` (engine_core_src_test_java_coeusyk_game_chess_core_search_searchertest_searchertest_bruteforcenegamax): cohort overlap 0.01
- `.incAdjust()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_incadjust): cohort overlap 0.01
- `.generateKnightMoves()` (engine_core_src_main_java_coeusyk_game_chess_core_movegen_movesgenerator_movesgenerator_generateknightmoves): cohort overlap 0.01
- `.unmakePromotionRestoresPawnOnOriginalSquare()` (engine_core_src_test_java_coeusyk_game_chess_core_movegen_promotionhandlingtest_promotionhandlingtest_unmakepromotionrestorespawnonoriginalsquare): cohort overlap 0.02
- `.appendCheckOrMateSuffix()` (engine_core_src_main_java_coeusyk_game_chess_core_notation_sanconverter_sanconverter_appendcheckormatesuffix): cohort overlap 0.02
- `.isActiveColorInCheck()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_isactivecolorincheck): cohort overlap 0.02
- `.doublePawnPushSetsEnPassantTargetSquare()` (engine_core_src_test_java_coeusyk_game_chess_core_movegen_enpassantlegalitytest_enpassantlegalitytest_doublepawnpushsetsenpassanttargetsquare): cohort overlap 0.02
- `.enPassantCaptureRemovesCorrectPawn()` (engine_core_src_test_java_coeusyk_game_chess_core_movegen_enpassantlegalitytest_enpassantlegalitytest_enpassantcaptureremovescorrectpawn): cohort overlap 0.02
- `.of()` (engine_core_src_main_java_coeusyk_game_chess_core_models_move_move_of): cohort overlap 0.02
- `.eg()` (engine_core_src_main_java_coeusyk_game_chess_core_eval_piecesquaretables_piecesquaretables_eg): cohort overlap 0.02
- `.genKingCaptures()` (engine_core_src_main_java_coeusyk_game_chess_core_movegen_movesgenerator_movesgenerator_genkingcaptures): cohort overlap 0.02
- `.genKnightCaptures()` (engine_core_src_main_java_coeusyk_game_chess_core_movegen_movesgenerator_movesgenerator_genknightcaptures): cohort overlap 0.02
- `.computePhase()` (engine_core_src_main_java_coeusyk_game_chess_core_eval_evaluator_evaluator_computephase): cohort overlap 0.02
- `.setTranspositionTableSizeMb()` (engine_core_src_main_java_coeusyk_game_chess_core_search_searcher_searcher_settranspositiontablesizemb): cohort overlap 0.02
- `.isQuietMove()` (engine_core_src_main_java_coeusyk_game_chess_core_search_searcher_searcher_isquietmove): cohort overlap 0.02
- ... and 549 more

## New God Nodes (top 15 by degree)
- None

## Architectural cohesion
- maintained (modularity 0.7476 -> 0.7526, delta +0.0050)

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

D-4 is the largest single-PR Trainer Architecture jump so far: 131 → 219 nodes (+88),
against another flat **Engine Architecture 0 node delta** — direct graph confirmation
that a PR literally named "PyTorch Model + Trainer" touched zero Java production code,
exactly matching the "No Java production behavior changes" constraint. The remaining
~19 of the +107 total node delta are `docs/architecture/` nodes (the new Section 10.1
"Reproducibility Infrastructure" content and the accompanying research note) — neither
Engine nor Trainer by the classifier's module prefixes, correctly excluded from both
counts.

The bridge-node candidates list reads exactly as expected for this PR's real content:
`NnueNet`, `train()`, `encode_batch()`, `TrainingConfig`, `texel_sigmoid()`,
`capture()` — the model, training loop, batching, config, loss, and reproducibility
metadata this PR actually built, with no unexplained additions.

"FeatureExtractor coupling changes: No change" and "NnueNetwork coupling changes: No
change" in the Architectural Boundary Report are the graph-level confirmation of two
of this PR's most load-bearing claims: the model's forward pass was built to
numerically mirror `NnueEvaluator.java`'s formula by direct inspection and hand
verification, not by importing or coupling to it — `FeatureExtractor`'s and
`NnueNetwork`'s own graph neighborhoods are untouched. Frozen boundary verdict:
**UNCHANGED**.

Modularity held essentially flat (0.7476 → 0.7526, Δ+0.0050) — consistent with two
self-contained new packages (`trainer.model`, `trainer.reproducibility`) landing
without blurring existing community structure, the same pattern D-2/D-3's audits
already established for this branch.

Community-churn and cross-module sections (not reproduced in full here) were checked
against this PR's actual file list before treating them as noise, per this report's
own established convention — none traced to unexpected coupling.

