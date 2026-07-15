# Architecture Graph Audit — E-4: NNUE-Mode Gauntlet Harness

- Before commit: `5aeff0fd42d973621136d3bb14f229ab7f1c1ea7`
- After commit: `727a7ce7b581a2f392d8102122220c62fe2b5bb6`
- Before: 3429 nodes, 7243 edges
- After: 3431 nodes, 7245 edges
- Node delta: +2 / -0
- Edge delta: +2 / -0

## Architecture Split
_Scoping summary only — the detailed sections below (centrality, degree, boundary report, etc.) remain whole-graph, since those measures are not meaningful computed on a subgraph alone._

## Engine Architecture
- Before: 1585 nodes; After: 1585 nodes
- Node delta: +0 / -0

## Trainer Architecture
- Before: 494 nodes; After: 494 nodes
- Node delta: +0 / -0

## Top 10 nodes by betweenness centrality change
- `Board` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board): 0.0032 -> 0.0032 (-0.0000)
- `MovesGenerator` (engine_core_src_main_java_coeusyk_game_chess_core_movegen_movesgenerator_movesgenerator): 0.0008 -> 0.0008 (-0.0000)
- `TunerPosition` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_tunerposition_tunerposition): 0.0007 -> 0.0007 (-0.0000)
- `LabelledPosition` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_labelledposition_labelledposition): 0.0006 -> 0.0005 (-0.0000)
- `.iterativeDeepening()` (engine_core_src_main_java_coeusyk_game_chess_core_search_searcher_searcher_iterativedeepening): 0.0005 -> 0.0005 (-0.0000)
- `.searchRoot()` (engine_core_src_main_java_coeusyk_game_chess_core_search_searcher_searcher_searchroot): 0.0005 -> 0.0005 (-0.0000)
- `.searchDepth()` (engine_core_src_main_java_coeusyk_game_chess_core_search_searcher_searcher_searchdepth): 0.0005 -> 0.0005 (-0.0000)
- `.from()` (engine_tuner_src_main_java_coeusyk_game_chess_tuner_tunerposition_tunerposition_from): 0.0004 -> 0.0004 (-0.0000)
- `GameSession` (chess_engine_api_src_main_java_coeusyk_game_chess_services_gamesession_gamesession): 0.0004 -> 0.0004 (-0.0000)
- `Board.java` (engine_core_src_main_java_coeusyk_game_chess_core_models_board): 0.0003 -> 0.0003 (-0.0000)

## Top 10 nodes by degree change
- `E-4: NNUE-mode gauntlet — run record` (tools_nnue_gauntlet_e4_e_4_nnue_mode_gauntlet_run_record): 5 -> 7 (+2)

## Newly introduced architectural bridge-node candidates
_New nodes ranked by betweenness centrality in the after-graph (>0). Heuristic, not a graphify-defined threshold._
- None

## Newly introduced dependency cycles
_Detected via strongly-connected-components (existence check, not full cycle enumeration)._
- None

## Cross-module dependency changes
- None

## Community changes
_Community IDs are not stable across separate graphify runs — flagged via cohort-overlap (Jaccard < 0.5) among nodes present in both snapshots, not raw ID equality._
- **516 of 3429 common nodes (15%) flagged — this volume is typical Louvain re-clustering instability under any graph perturbation, not evidence of real coupling change. Read this section by checking whether the specific files this PR touched appear below, not by the raw count.**
- `.isSquareAttackedBy()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_issquareattackedby): cohort overlap 0.01
- `SanConverter.java` (engine_core_src_main_java_coeusyk_game_chess_core_notation_sanconverter): cohort overlap 0.01
- `.toFen()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_tofen): cohort overlap 0.01
- `.wdlToScore()` (engine_core_src_main_java_coeusyk_game_chess_core_search_searcher_searcher_wdltoscore): cohort overlap 0.01
- `.getAllMoves()` (engine_core_src_main_java_coeusyk_game_chess_core_movegen_movesgenerator_movesgenerator_getallmoves): cohort overlap 0.01
- `.getActiveMoves()` (engine_core_src_main_java_coeusyk_game_chess_core_movegen_movesgenerator_movesgenerator_getactivemoves): cohort overlap 0.02
- `.formatVerifyResult()` (engine_uci_src_main_java_coeusyk_game_chess_uci_uciapplication_uciapplication_formatverifyresult): cohort overlap 0.02
- `RebuildDiff` (rebuilddiff): cohort overlap 0.02
- `.findMove()` (engine_core_src_main_java_coeusyk_game_chess_core_movegen_movesgenerator_movesgenerator_findmove): cohort overlap 0.02
- `.getChessSquare()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_getchesssquare): cohort overlap 0.02
- `.getCurrentFEN()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_getcurrentfen): cohort overlap 0.02
- `Board.java` (engine_core_src_main_java_coeusyk_game_chess_core_models_board): cohort overlap 0.02
- `.generateKnightMoves()` (engine_core_src_main_java_coeusyk_game_chess_core_movegen_movesgenerator_movesgenerator_generateknightmoves): cohort overlap 0.02
- `.getPiece()` (engine_core_src_main_java_coeusyk_game_chess_core_models_board_board_getpiece): cohort overlap 0.02
- `.appendCheckOrMateSuffix()` (engine_core_src_main_java_coeusyk_game_chess_core_notation_sanconverter_sanconverter_appendcheckormatesuffix): cohort overlap 0.02
- ... and 501 more

## New God Nodes (top 15 by degree)
- None

## Architectural cohesion
- maintained (modularity 0.7873 -> 0.7804, delta -0.0069)

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

E-4's graph footprint is about as minimal as a PR can be: +2 nodes, +2 edges,
zero engine or trainer architecture change (both report exactly 0/0 node delta).
This is expected and correct — graphify parses Java and Python source, and this
issue's only committed artifacts are a PowerShell script (`tools/nnue-gauntlet.ps1`,
not a language graphify indexes) and a Markdown run-record
(`tools/nnue-gauntlet-e4.md`). The only detected change is the doc node itself
gaining degree (5 -> 7) as its content grew with the actual match result —
exactly what "newly introduced bridge-node candidates: None" and "cross-module
dependency changes: None" would predict for a PR whose real content is outside
graphify's indexed language set.

The Architectural Boundary Report is unanimous "No change" across every tracked
row, frozen boundary verdict **UNCHANGED**. This directly corroborates E-4's own
Architecture Invariant claim ("this issue runs the existing, frozen `.nnue`
loading/inference path; it does not modify engine-core's evaluator internals") —
not just by having read the diff, but because the graph shows zero edges touched
anywhere in `engine-core`, `engine-uci`, `engine-tuner`, or `chess-engine-api`.

No newly introduced dependency cycles, no new God nodes, no cross-module
dependency changes. Modularity moved by a small, unremarkable amount (0.7873 ->
0.7804, delta -0.0069), within the same noise band every prior Phase D/E audit
has established. The community-churn section (516 of 3429 common nodes, 15%,
flagged) was checked against this PR's actual file list before being dismissed
as noise — the listed churned nodes (`Board.java` internals, `SanConverter.java`,
`MovesGenerator` methods, etc.) are pre-existing Java classes with no relationship
to this PR's two committed files.

Overall: a documentation/tooling-only PR (a new PowerShell gauntlet script plus
its run-record doc) whose graph footprint confirms exactly that — no code graph
change of any kind, and independent structural confirmation that the gauntlet
itself exercised only the already-frozen NNUE-mode search path without touching
any production code.
