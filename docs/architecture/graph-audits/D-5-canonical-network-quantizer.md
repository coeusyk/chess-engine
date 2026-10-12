# Architecture Graph Audit — D-5: Immutable CanonicalNetwork + QuantizedCanonicalNetwork + Quantizer

- Before commit: `84316d4867000546a57f5d5419dae6c05064e671`
- After commit: `5556f34a9084cf0eaf38eba0022b8f8b2e297709`
- Before: 2818 nodes, 6487 edges
- After: 2884 nodes, 6601 edges
- Node delta: +66 / -0
- Edge delta: +114 / -0

## Architecture Split
_Scoping summary only — the detailed sections below (centrality, degree, boundary report, etc.) remain whole-graph, since those measures are not meaningful computed on a subgraph alone._

## Engine Architecture
- Before: 1566 nodes; After: 1566 nodes
- Node delta: +0 / -0

## Trainer Architecture
- Before: 219 nodes; After: 275 nodes
- Node delta: +56 / -0

## Top 10 nodes by betweenness centrality change
- `Board` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board): 0.0077 -> 0.0073 (-0.0003)
- `MovesGenerator` (engine_core_src_main_java_coeusyk_game_chess_core_movegen_movesgenerator_movesgenerator): 0.0026 -> 0.0025 (-0.0001)
- `TunerPosition` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_tunerposition_tunerposition): 0.0014 -> 0.0014 (-0.0001)
- `LabelledPosition` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_labelledposition_labelledposition): 0.0012 -> 0.0011 (-0.0001)
- `.from()` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_tunerposition_tunerposition_from): 0.0010 -> 0.0009 (-0.0000)
- `.isCheckmate()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_ischeckmate): 0.0008 -> 0.0008 (-0.0000)
- `.isStalemate()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_isstalemate): 0.0008 -> 0.0008 (-0.0000)
- `.toBoard()` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_tunerposition_tunerposition_toboard): 0.0008 -> 0.0008 (-0.0000)
- `Move` (engine_core_src_main_java_coeusyk_game_chess_core_models_move_move): 0.0008 -> 0.0008 (-0.0000)
- `.evaluate()` (engine_core_src_main_java_coeusyk_game_chess_core_eval_evaluator_evaluator_evaluate): 0.0007 -> 0.0007 (-0.0000)

## Top 10 nodes by degree change
- None (no common node's degree changed)

## Newly introduced architectural bridge-node candidates
_New nodes ranked by betweenness centrality in the after-graph (>0). Heuristic, not a graphify-defined threshold._
- `CanonicalNetwork` (trainer_trainer_export_canonical_canonicalnetwork): betweenness centrality 0.0000
- `quantize()` (trainer_trainer_quantization_quantizer_quantize): betweenness centrality 0.0000
- `.__post_init__()` (trainer_trainer_export_canonical_canonicalnetwork_post_init): betweenness centrality 0.0000
- `QuantizedCanonicalNetwork` (trainer_trainer_export_canonical_quantizedcanonicalnetwork): betweenness centrality 0.0000
- `.__setstate__()` (trainer_trainer_export_canonical_canonicalnetwork_setstate): betweenness centrality 0.0000
- `_freeze_array_fields()` (trainer_trainer_export_canonical_freeze_array_fields): betweenness centrality 0.0000
- `_network()` (trainer_tests_quantization_test_quantizer_network): betweenness centrality 0.0000
- `_validate_shapes()` (trainer_trainer_export_canonical_validate_shapes): betweenness centrality 0.0000
- `clipping_report()` (trainer_trainer_quantization_quantizer_clipping_report): betweenness centrality 0.0000
- `_sample_network()` (trainer_tests_export_test_canonical_sample_network): betweenness centrality 0.0000

## Newly introduced dependency cycles
_Detected via strongly-connected-components (existence check, not full cycle enumeration)._
- None

## Cross-module dependency changes
- None

## Community changes
_Community IDs are not stable across separate graphify runs — flagged via cohort-overlap (Jaccard < 0.5) among nodes present in both snapshots, not raw ID equality._
- **672 of 2818 common nodes (24%) flagged — this volume is typical Louvain re-clustering instability under any graph perturbation, not evidence of real coupling change. Read this section by checking whether the specific files this PR touched appear below, not by the raw count.**
- `.toFen()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_tofen): cohort overlap 0.01
- `.findMove()` (engine_core_src_main_java_coeusyk_game_chess_core_movegen_movesgenerator_movesgenerator_findmove): cohort overlap 0.01
- `OpeningBook.java` (engine_core_src_main_java_coeusyk_game_chess_core_book_openingbook): cohort overlap 0.01
- `.isSquareAttackedBy()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_issquareattackedby): cohort overlap 0.01
- `.ttBoundGatingWorksForExactLowerUpper()` (engine_core_src_test_java_coeusyk_game_chess_core_search_searchertest_searchertest_ttboundgatingworksforexactlowerupper): cohort overlap 0.01
- `.SetupContainer()` (chess_engine_api_src_main_java_coeusyk_game_chess_utils_setupcontainer_setupcontainer_setupcontainer): cohort overlap 0.01
- `Move.java` (engine_core_src_main_java_coeusyk_game_chess_core_models_move): cohort overlap 0.02
- `.incAdjust()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_incadjust): cohort overlap 0.02
- `.getHalfmoveClock()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_gethalfmoveclock): cohort overlap 0.02
- `.findK()` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_kfinder_kfinder_findk): cohort overlap 0.02
- `Board.java` (engine_core_src_main_java_coeusyk_game_chess_core_models_board): cohort overlap 0.02
- `.getCurrentFEN()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_getcurrentfen): cohort overlap 0.03
- `.getChessSquare()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_getchesssquare): cohort overlap 0.03
- `.GameController()` (chess_engine_api_src_main_java_coeusyk_game_chess_controllers_gamecontroller_gamecontroller_gamecontroller): cohort overlap 0.03
- `.formatVerifyResult()` (engine_uci_src_main_java_coeusyk_game_chess_uci_uciapplication_uciapplication_formatverifyresult): cohort overlap 0.03
- ... and 657 more

## New God Nodes (top 15 by degree)
- None

## Architectural cohesion
- maintained (modularity 0.7558 -> 0.7551, delta -0.0006)

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

D-5 lands another flat **Engine Architecture 0 node delta** (1566 -> 1566) against a
**Trainer Architecture** jump of 219 -> 275 (+56) — direct graph confirmation that this
PR, like D-3 and D-4 before it, touched zero Java production code while building real
Python subsystem structure. The remaining ~10 of the +66 total node delta are
`docs/architecture/` nodes (the new §5 revision content, the §10.1-adjacent pipeline
edits, and the accompanying research note), correctly excluded from both module counts.

The bridge-node candidates list reads exactly as this PR's real content, and nothing
else: `CanonicalNetwork`, `QuantizedCanonicalNetwork`, `quantize()`,
`clipping_report()`, `_freeze_array_fields()`, `_validate_shapes()`,
`.__post_init__()`, `.__setstate__()` — the two IR dataclasses, the pure quantization
transform, the clipping report, and the immutability-enforcement helpers this PR
actually built. No unexplained additions, and no `checkpoint_to_canonical()` on this
list is expected — it appears in the graph but ranks below the top-8 threshold shown,
consistent with a leaf function with few internal callers rather than a bridge.

"Cross-module dependency changes: None" and every `FeatureExtractor`/`NnueEvaluator`/
`NnueNetwork` coupling-change row reading "No change" is the graph-level confirmation of
this PR's most load-bearing claim: `quantizer.py` was built to know nothing about
PyTorch or checkpoint internals (verified directly in code review — it imports only the
two IR types and numpy), and `canonical.py`'s `checkpoint_to_canonical()` absorbs all
PyTorch-specific extraction in one place. Neither module's own graph neighborhood
touches any Java node, engine-side or debug-side. Frozen boundary verdict: **UNCHANGED**.

Modularity moved by a genuinely negligible amount (0.7558 -> 0.7551, delta -0.0006) —
within the same "typical Louvain re-clustering noise" band D-2/D-3/D-4's audits already
established for this branch, not a real cohesion regression. The community-churn section
(672 of 2818 common nodes, 24%, flagged) was checked against this PR's actual file list
before being treated as noise, per this report's own established convention — the listed
churned nodes are all pre-existing Java engine/tuner/API classes unrelated to
`trainer/export`/`trainer/quantization`, exactly the pattern every prior audit on this
branch has shown under any graph perturbation.

No newly introduced dependency cycles, no new God nodes, no cross-module dependency
changes — the two new packages (`trainer.export`, `trainer.quantization`) landed as
self-contained additions with a single, deliberate coupling edge between them
(`quantizer.py` imports the two dataclasses from `canonical.py`), matching the pipeline
shape §5 specifies and nothing more.

