# Architecture Graph Audit — D-1: Trainer Scaffolding & ADR Extraction

- Before commit: `7570984bdab2a41512c7ec09574b40b1c64e59f0`
- After commit: `28c247aa18f4096d08d6a0f530c485d23c06df9d`
- Before: 2395 nodes, 5948 edges
- After: 2459 nodes, 5991 edges
- Node delta: +83 / -19
- Edge delta: +74 / -31

## Architecture Split
_Scoping summary only — the detailed sections below (centrality, degree, boundary report, etc.) remain whole-graph, since those measures are not meaningful computed on a subgraph alone._

## Engine Architecture
- Before: 1543 nodes; After: 1543 nodes
- Node delta: +0 / -0

## Trainer Architecture
- Before: 0 nodes; After: 14 nodes
- Node delta: +14 / -0

## Top 10 nodes by betweenness centrality change
- `Board` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board): 0.0107 -> 0.0102 (-0.0006)
- `MovesGenerator` (engine_core_src_main_java_coeusyk_game_chess_core_movegen_movesgenerator_movesgenerator): 0.0036 -> 0.0034 (-0.0002)
- `TunerPosition` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_tunerposition_tunerposition): 0.0020 -> 0.0019 (-0.0001)
- `LabelledPosition` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_labelledposition_labelledposition): 0.0016 -> 0.0015 (-0.0001)
- `.from()` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_tunerposition_tunerposition_from): 0.0013 -> 0.0013 (-0.0001)
- `.isCheckmate()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_ischeckmate): 0.0012 -> 0.0011 (-0.0001)
- `.isStalemate()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_isstalemate): 0.0012 -> 0.0011 (-0.0001)
- `.toBoard()` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_tunerposition_tunerposition_toboard): 0.0011 -> 0.0011 (-0.0001)
- `Move` (engine_core_src_main_java_coeusyk_game_chess_core_models_move_move): 0.0011 -> 0.0010 (-0.0001)
- `.evaluate()` (engine_core_src_main_java_coeusyk_game_chess_core_eval_evaluator_evaluator_evaluate): 0.0010 -> 0.0009 (-0.0000)

## Top 10 nodes by degree change
- `NNUE_PRD.md` (docs_nnue_prd): 12 -> 5 (-7)
- `ADR-005: Pure NNUE Runtime vs Hybrid Blending` (docs_adr_005_pure_nnue_runtime): 4 -> 1 (-3)
- `Phased Rollout` (docs_nnue_prd_phased_rollout): 3 -> 1 (-2)
- `ADR-004: Evaluator Lifecycle Hooks` (docs_adr_004_evaluator_lifecycle_hooks): 3 -> 1 (-2)
- `Trainer Workflow Skill` (claude_skills_trainer_workflow_skill_trainer_workflow): 3 -> 1 (-2)
- `SPRT Testing Guidelines` (docs_sprt_guidelines): 9 -> 8 (-1)

## Newly introduced architectural bridge-node candidates
_New nodes ranked by betweenness centrality in the after-graph (>0). Heuristic, not a graphify-defined threshold._
- `PRD: NNUE Evaluation for the Chess Engine` (docs_nnue_prd_prd_nnue_evaluation_for_the_chess_engine): betweenness centrality 0.0000
- `ADR-006: Self-written PyTorch trainer vs. pure-Java trainer vs. existing framework` (docs_adr_adr_006_self_written_pytorch_trainer_adr_006_self_written_pytorch_trainer_vs_pure_java_trainer_vs_existing_framework): betweenness centrality 0.0000
- `ADR-007: Staged training data (public text → SF labeling → self-play); binpack exclusion` (docs_adr_adr_007_staged_training_data_adr_007_staged_training_data_public_text_sf_labeling_self_play_binpack_exclusion): betweenness centrality 0.0000
- `ADR-007-staged-training-data.md` (docs_adr_adr_007_staged_training_data): betweenness centrality 0.0000
- `ADR-006-self-written-pytorch-trainer.md` (docs_adr_adr_006_self_written_pytorch_trainer): betweenness centrality 0.0000
- `ADR-010: Trainer repository location — `trainer/` in-repo, not a separate repository` (docs_adr_adr_010_trainer_repository_location_adr_010_trainer_repository_location_trainer_in_repo_not_a_separate_repository): betweenness centrality 0.0000
- `ADR-010-trainer-repository-location.md` (docs_adr_adr_010_trainer_repository_location): betweenness centrality 0.0000
- `4. Technical Specifications` (docs_nnue_prd_4_technical_specifications): betweenness centrality 0.0000
- `3. AI System Requirements` (docs_nnue_prd_3_ai_system_requirements): betweenness centrality 0.0000
- `Alternatives considered` (docs_adr_adr_007_staged_training_data_alternatives_considered): betweenness centrality 0.0000

## Newly introduced dependency cycles
_Detected via strongly-connected-components (existence check, not full cycle enumeration)._
- None

## Cross-module dependency changes
**Changed edge counts:**
- .claude -> docs: 4 -> 2 (-2)

## Community changes
_Community IDs are not stable across separate graphify runs — flagged via cohort-overlap (Jaccard < 0.5) among nodes present in both snapshots, not raw ID equality._
- **574 of 2376 common nodes (24%) flagged — this volume is typical Louvain re-clustering instability under any graph perturbation, not evidence of real coupling change. Read this section by checking whether the specific files this PR touched appear below, not by the raw count.**
- `FeatureExtractor.java` (engine_core_src_main_java_coeusyk_game_chess_core_eval_nnue_featureextractor): cohort overlap 0.01
- `.singularityGuardRequiresDepthAndQualifiedTtEntry()` (engine_core_src_test_java_coeusyk_game_chess_core_search_searchertest_searchertest_singularityguardrequiresdepthandqualifiedttentry): cohort overlap 0.01
- `.quiescence()` (engine_core_src_main_java_coeusyk_game_chess_core_search_searcher_searcher_quiescence): cohort overlap 0.01
- `MovesGenerator.java` (engine_core_src_main_java_coeusyk_game_chess_core_movegen_movesgenerator): cohort overlap 0.01
- `Piece.java` (engine_core_src_main_java_coeusyk_game_chess_core_models_piece): cohort overlap 0.01
- `.getZobristHash()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_getzobristhash): cohort overlap 0.01
- `.unmakeNullMove()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_unmakenullmove): cohort overlap 0.02
- `NullMoveState` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_nullmovestate): cohort overlap 0.02
- `.getHalfmoveClock()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_gethalfmoveclock): cohort overlap 0.02
- `.lbfgsParamsStayWithinBounds()` (engine_tuner_src_test_java_coeusyk_game_chess_tuner_gradientdescenttest_gradientdescenttest_lbfgsparamsstaywithinbounds): cohort overlap 0.02
- `BenchRunner.java` (engine_uci_src_main_java_coeusyk_game_chess_uci_benchrunner): cohort overlap 0.02
- `BenchRunner` (engine_uci_src_main_java_coeusyk_game_chess_uci_benchrunner_benchrunner): cohort overlap 0.02
- `Move.java` (engine_core_src_main_java_coeusyk_game_chess_core_models_move): cohort overlap 0.02
- `.getState()` (chess_engine_api_src_main_java_coeusyk_game_chess_services_chessgameservice_chessgameservice_getstate): cohort overlap 0.02
- `.findK()` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_kfinder_kfinder_findk): cohort overlap 0.02
- ... and 559 more

## New God Nodes (top 15 by degree)
- None

## Architectural cohesion
- maintained (modularity 0.7451 -> 0.7427, delta -0.0024)

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

D-1 is the first PR in this repository's history to produce a non-empty **Trainer
Architecture** split: 0 → 14 nodes, while **Engine Architecture** shows a flat 0 node
delta — direct, graph-level confirmation that this PR touched zero Java production
code, matching its own "pure scaffolding" scope exactly. This is exactly what the
Engine/Trainer split section (added ahead of Phase D, prior commit) was built to make
visible, and this is its first real exercise: previously it could only ever report
"no trainer nodes yet" since `trainer/` didn't exist.

The **Architectural Boundary Report**'s five `FROZEN_BOUNDARY_CLASSES` all show "No
change," and the frozen-boundary verdict is UNCHANGED — expected, since none of
`EvaluatorStrategy`/`Searcher`/`NnueEvaluator`/`FeatureExtractor`/`NnueNetwork` were
touched. `docs/architecture/graph-audits/generate_report.py` itself was not modified by
this PR (it was modified and reviewed in the prior commit, 7570984); D-1 only exercises
it against real trainer content for the first time.

The 10 bridge-node candidates are exactly the new markdown structure this PR
introduced (the PRD's own document node picking up new child sections from the
Appendix B edit, and the three new ADR files' own headings) — all betweenness
centrality 0.0000, leaf nodes with no downstream fan-out, consistent with new
documentation nothing else in the graph calls into yet. `NNUE_PRD.md`'s degree drop
(12 → 5) is the Appendix B edit's own structural effect: two stub-line bullets became
links (changing how graphify parses that list into child nodes) and a new Addendum
paragraph was added — a docs-authoring artifact of the edit, not a coupling change to
investigate.

The 574-of-2376 (24%) community-churn figure is within this report's own documented
typical-noise range for any graph perturbation; none of the file names it lists trace
to this PR's changes (`trainer/`, the three ADRs, `trainer-ci.yml`, `NNUE_PRD.md`) —
confirming it is Louvain re-clustering noise from the graph's ordinary size growth
(+83/-19 nodes overall, most of that churn from the 14 new trainer nodes plus normal
whole-repo re-extraction), not evidence of real coupling introduced by this PR.

Modularity moved from 0.7451 to 0.7427 (Δ -0.0024) — within the report's own "maintained"
band (|Δ| < 0.01) — consistent with adding one new, mostly self-contained trainer
subtree rather than restructuring existing coupling.

