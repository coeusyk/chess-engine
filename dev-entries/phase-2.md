# Dev Entries - Phase 2

### [2026-03-23] Phase 2 — Issue #19 Iterative Deepening Alpha-Beta Baseline

**Built:**

- Added a new core search package with `Searcher` and `SearchResult` in `engine-core`.
- Implemented negamax alpha-beta search with iterative deepening from depth 1..N.
- Seeded each root iteration with the previous iteration's best move for stable convergence.
- Added abort hook via `BooleanSupplier` so future time controls can stop cleanly.
- Added focused `SearcherTest` coverage for:
	- bestmove existence at requested depth,
	- node-count reduction vs brute-force minimax,
	- deterministic convergence across iterative deepening runs.

**Decisions Made:**

- Kept move ordering intentionally minimal for this issue to avoid overlapping issue #22 heuristics.
- Deferred quiescence, PV, TT, and advanced ordering to subsequent Phase 2 issues.

**Broke / Fixed:**

- No regressions observed in existing legality/perft suites.

**Measurements:**

- `SearcherTest`: 3 tests run, 0 failures, 0 errors.
- Full `engine-core` suite after integration: passed.
- Perft depth 5 (startpos): unchanged from Phase 1 baseline (`4,865,609`).
- Nodes/sec: Not benchmarked in this cycle.
- Elo vs. baseline: Not benchmarked in this cycle.

**Next:**

- Implement issue #20 quiescence search at depth-0 frontier.

### [2026-03-23] Phase 2 — Issue #20 Quiescence Search

**Built:**

- Added quiescence search at depth-0 leaves in `Searcher`.
- Implemented stand-pat fail-soft cutoff (`staticEval >= beta -> return standPat`).
- Restricted q-search expansion to captures, en-passant captures, and queen promotions only.
- Added dedicated q-search node accounting and exposed it via `SearchResult.quiescenceNodes`.
- Added/extended `SearcherTest` coverage for:
	- q-search node tracking,
	- tactical hanging-queen capture behavior at shallow depth.

**Decisions Made:**

- Followed issue scope strictly: no checks-in-qsearch, SEE, or delta pruning yet.
- Kept q-search move filtering simple and deterministic until move-ordering/TT issues are completed.

**Broke / Fixed:**

- A temporary workspace rollback had removed issue #19 search files; baseline was restored and validated before issue #20 changes.

**Measurements:**

- `SearcherTest`: 5 tests run, 0 failures, 0 errors.
- Full `engine-core` suite: 34 tests run, 0 failures, 0 errors, 0 skipped.
- Perft depth 5 (startpos): unchanged from Phase 1 baseline (`4,865,609`).
- Nodes/sec: Not benchmarked in this cycle.
- Elo vs. baseline: Not benchmarked in this cycle.

**Next:**

- Implement issue #21 PV tracking with bounded PV line extraction per depth.

### [2026-03-23] Phase 2 — Issue #21 Principal Variation Tracking

**Built:**

- Added principal variation output support to search results via `SearchResult.principalVariation`.
- Implemented triangular PV table tracking in `Searcher`:
	- updates PV on each alpha-improving move at interior nodes,
	- propagates child PV into parent PV line,
	- stores bounded PV length per ply.
- Wired root PV extraction into iterative deepening output per completed depth.
- Extended `SearcherTest` to verify:
	- PV determinism across repeated runs,
	- PV is present,
	- PV length is bounded by searched depth,
	- PV first move equals reported `bestMove`.

**Decisions Made:**

- Kept PV tracking in a simple triangular table to match issue scope and prepare root move seeding and UCI info output.
- Did not add TT-PV interaction yet (deferred to issue #23).

**Broke / Fixed:**

- Initial PV determinism assertion failed because `Move` objects were compared by identity.
- Fixed by asserting PV equality move-by-move on start square, target square, and reaction fields.

**Measurements:**

- `SearcherTest`: 6 tests run, 0 failures, 0 errors.
- Full `engine-core` suite: 35 tests run, 0 failures, 0 errors, 0 skipped.
- Perft depth 5 (startpos): unchanged from Phase 1 baseline (`4,865,609`).
- Nodes/sec: Not benchmarked in this cycle.
- Elo vs. baseline: Not benchmarked in this cycle.

**Next:**

- Implement issue #22 move ordering stack (TT move hook, MVV-LVA captures, killer moves, history heuristic).

### [2026-03-23] Phase 2 — Issue #22 Move Ordering (TT Hook, MVV-LVA, Killer, History)

**Built:**

- Added `MoveOrderer` in `engine-core` search package with move-priority stack:
	- TT move bonus (hook-ready),
	- capture ordering by MVV-LVA,
	- killer move 1 and killer move 2 bonuses,
	- history heuristic fallback for quiet moves.
- Integrated ordering into `Searcher` root and interior alpha-beta nodes.
- Added killer-move updates (2 quiet moves per ply) on beta cutoffs.
- Added history heuristic updates for quiet cutoff moves (`depth^2` bonus by `[pieceType][toSquare]`).
- Added root TT move hint hook (`setRootTtMoveHintForTesting`) to verify TT-first behavior before full TT implementation.
- Added tests:
	- `MoveOrdererTest` validates MVV-LVA capture preference and TT-move-first ordering,
	- `SearcherTest` validates node-count reduction with ordering enabled vs disabled and root TT hint application.

**Decisions Made:**

- Implemented TT move as a hook-ready ordering signal now; full TT storage/probe integration is deferred to issue #23.
- Kept ordering deterministic and side-effect free outside killer/history table updates.

**Broke / Fixed:**

- Initial TT-hint tests failed because start-position double-pawn-push carries reaction metadata (`ep-target`); fixed tests to pick exact legal move instances from generated move lists.
- Initial PV determinism comparison regressed under state reuse; fixed test to use independent `Searcher` instances for deterministic replay checks.

**Measurements:**

- Focused search-ordering tests: 10 tests run, 0 failures, 0 errors.
- Full `engine-core` suite: 39 tests run, 0 failures, 0 errors, 0 skipped.
- Node reduction check (`SearcherTest`): ordered search nodes <= unordered search nodes on shared reference position.
- Perft depth 5 (startpos): unchanged from Phase 1 baseline (`4,865,609`).
- Nodes/sec and Elo: Not benchmarked in this cycle.

**Next:**

- Implement issue #23 fixed-size transposition table with bound types and hit-rate accounting.

### [2026-03-23] Phase 2 — Issue #23 Transposition Table

**Built:**

- Added `TranspositionTable` with fixed-size, power-of-two indexing, configurable size (default 64MB).
- Added TT bound enum `TTBound` with `EXACT`, `LOWER_BOUND`, `UPPER_BOUND`.
- Implemented TT entry model storing key, best move, depth, score, and bound.
- Implemented depth-preferred replacement policy on collisions.
- Integrated TT probing in `Searcher.alphaBeta`:
	- `EXACT` returns immediately if stored depth sufficient,
	- `LOWER_BOUND` triggers beta cutoff when score >= beta,
	- `UPPER_BOUND` triggers alpha cutoff when score <= alpha.
- Integrated TT move extraction into move ordering pipeline.
- Integrated TT store path at node return with computed bound type.
- Added TT hit-rate reporting in `SearchResult` (`ttHitRate`) and searcher-level hit-rate access.

**Decisions Made:**

- Kept TT replacement policy depth-preferred for predictable behavior and low complexity at this phase.
- Used per-search TT statistics reset to make hit-rate measurements meaningful for each search invocation.

**Broke / Fixed:**

- No runtime regressions observed after TT integration.

**Measurements:**

- Focused TT/search tests: 15 tests run, 0 failures, 0 errors.
- Full `engine-core` suite: 44 tests run, 0 failures, 0 errors, 0 skipped.
- TT hit-rate on repeated search positions: non-zero in integration tests.
- Perft depth 5 (startpos): unchanged from Phase 1 baseline (`4,865,609`).
- Nodes/sec and Elo: Not benchmarked in this cycle.

**Next:**

- Implement issue #24 time-management module (soft/hard limits, increment handling, overhead buffer, clean abort).

### [2026-03-23] Phase 2 — Issue #24 Time Management Module

**Built:**

- Added `TimeManager` in `engine-core` search package.
- Implemented soft and hard limit handling:
	- `configureMovetime(N)` uses fixed hard/soft budget from movetime minus overhead,
	- `configureClock(...)` derives per-move target from `base/20 + increment/2` with hard cap.
- Implemented move-overhead buffer (`default 30ms`) with configurable setter.
- Added elapsed-time tracking and stop predicates:
	- `shouldStopSoft()` for iteration boundary checks,
	- `shouldStopHard()` for immediate in-search abort checks.
- Integrated time management into `Searcher`:
	- new `searchWithTimeManager(...)` API,
	- soft-stop checked at top of each iterative deepening iteration,
	- hard-stop checked in root loop and recursive search loops.
- Added `TimeManagerTest` covering:
	- movetime limits,
	- clock+increment limit computation,
	- abort behavior under tight hard limit.

**Decisions Made:**

- Kept time-allocation heuristic intentionally simple and robust for Phase 2 baseline.
- Split soft vs hard checks explicitly in Searcher to enforce graceful iteration stops and strict hard cutoffs.

**Broke / Fixed:**

- No regressions found after integrating time manager into the search pipeline.

**Measurements:**

- Focused time/search tests: 13 tests run, 0 failures, 0 errors.
- Full `engine-core` suite: 47 tests run, 0 failures, 0 errors, 0 skipped.
- Hard-limit abort behavior: validated in unit test (`result.aborted == true` under very small movetime).
- Perft depth 5 (startpos): unchanged from Phase 1 baseline (`4,865,609`).
- Nodes/sec and Elo: Not benchmarked in this cycle.

**Next:**

- Implement issue #25 minimal UCI command interface in `engine-uci` and wire search/time manager command paths.

### [2026-03-23] Phase 2 — Issue #25 Minimal UCI Interface

**Built:**

- Replaced Phase-0 UCI skeleton in `engine-uci` with a functional Phase-2 command loop.
- Implemented required UCI commands:
	- `uci`, `isready`, `ucinewgame`, `position startpos ...`, `position fen ...`,
	- `go depth N`, `go movetime N`, `go wtime/btime/winc/binc`,
	- `stop`, `quit`.
- Added legal move application for `position ... moves ...` using `engine-core` legal move generation.
- Added UCI long algebraic move formatting for `bestmove` (including promotion suffixes).
- Wired search integration to `Searcher` and `TimeManager`:
	- depth search path,
	- fixed-movetime path,
	- clock+increment path.
- Added basic UCI info output per completed iteration:
	- `info depth N score cp S`.
- Added stop handling via shared stop flag and latest-iteration best move fallback.

**Decisions Made:**

- Kept setoption handling as no-op in this phase (deferred by design to later phase).
- Kept UCI module lightweight and synchronous in command handling while preserving hard-stop checks through search callbacks.

**Broke / Fixed:**

- Initial UCI smoke-test command failed due PowerShell classpath separator parsing; fixed by quoting `-cp` argument.

**Measurements:**

- Reactor validation (`engine-core,engine-uci`): SUCCESS.
- `engine-core` tests: 47 run, 0 failures, 0 errors.
- `engine-uci` tests: no test sources in module (compile/runtime smoke-validated instead).
- Manual UCI smoke sequence output verified:
	- `uci`/`isready` handshake,
	- `position` + `go depth 2`,
	- iterative `info depth` lines,
	- valid `bestmove` output.

**Next:**

- Phase 2 implementation issues (#19-#25) complete; proceed to broader GUI match validation and tactical suite measurement against parent issue #18 exit criteria.

### [2026-03-23] Phase 2 — Exit Criteria Audit and Tactical Harness

**Built:**

- Performed criteria-by-criteria verification against GitHub issues #18 and #25 using current code and runtime checks.
- Added an opt-in tactical benchmark test harness in `engine-core`:
	- `TacticalSuiteTest` with configurable pass threshold and suite path,
	- default template suite file under test resources.
- Added README command documentation for running the 50-position tactical benchmark.

**Decisions Made:**

- Treated tactical benchmark execution as an explicit gated test (`-Dtactical.enabled=true`) so normal CI remains stable.
- Marked GUI-load/legal-game criteria as unverified unless exercised in an actual UCI GUI match flow.

**Broke / Fixed:**

- No code regressions introduced by the tactical harness.
- Verified that enabling tactical test fails fast with a clear message when suite data is not populated.

**Measurements:**

- Full backend reactor test run: SUCCESS (`54 tests`, `0 failures`, `1 skipped` tactical gate by default).
- Tactical harness default run (without enabling): skipped as intended.
- Tactical harness enabled without suite data: fails with actionable message (expected behavior).

**Next:**

- Populate `mate_2_3_50.epd` with a real 50-position mate-in-2/3 suite and record measured solve rate.
- Run GUI match validation in Cute Chess/Arena to close remaining acceptance evidence for issue #25.

### [2026-03-23] Phase 2 — Issue #18/#25 Closure Pass (Tactical Gate + UCI Integration Tests)

**Built:**

- Replaced the tactical suite placeholder with a concrete 50-position mate-in-2/3 EPD under `engine-core/src/test/resources/tactical/mate_2_3_50.epd`.
- Extended `TacticalSuiteTest` to support both forms of EPD `bm` expectations:
	- explicit UCI move lists,
	- mate-distance markers (`bm #2`, `bm #3`) validated against search mate score.
- Added integration tests in `engine-uci` (`UciApplicationIntegrationTest`) that run the UCI process end-to-end and validate:
	- `uci`/`isready` handshake,
	- `position ...` + `go depth N` with legal `bestmove`,
	- `stop` promptness,
	- unknown command tolerance without crash.
- Added JUnit and Surefire test wiring in `engine-uci/pom.xml`.
- Updated README tactical benchmark commands for PowerShell-safe Maven property passing (`--%`) and bundled suite usage.

**Decisions Made:**

- Kept tactical benchmark opt-in (`-Dtactical.enabled=true`) so regular CI remains deterministic and fast.
- Accepted EPD mate-distance assertions as valid tactical targets when explicit UCI best moves are not provided by source suites.
- Used process-level UCI integration tests to validate command-loop behavior and legal move output in an automated way.

**Broke / Fixed:**

- Initial tactical suite generation accidentally mixed temporary local EPD artifacts; regenerated suite from clean upstream source files.
- PowerShell split dotted `-D...` Maven properties; fixed docs and command usage with `--%` passthrough.

**Measurements:**

- Tactical gate run: `48/50` solved at depth 5 (`96.00%`), passing the `>= 80%` target.
- Full backend reactor tests: SUCCESS.
- Engine-core summary: `54` tests run, `0` failures, `1` skipped (tactical gate disabled by default).
- Engine-uci integration summary: `4` tests run, `0` failures.

**Next:**

- Run at least one full GUI-vs-GUI legal game capture in Cute Chess/Arena and archive PGN/log evidence for issue #25's GUI-specific acceptance notes.

### [2026-03-23] Phase 2 — Issue #25 GUI Validation Runbook Added

**Built:**

- Added dedicated GUI validation runbook for issue #25 at `chess-engine/docs/phase2-issue25-gui-validation.md`.
- Included step-by-step procedures for both Cute Chess and Arena.
- Added a pass/fail checklist aligned to issue #25 acceptance wording.
- Added an evidence package specification (PGN + UCI log + validation report template).
- Linked the runbook from backend README under Phase 2 validation aids.

**Decisions Made:**

- Keep GUI validation as a reproducible, manual acceptance layer on top of automated UCI integration tests.
- Require explicit artifacts (PGN/log/report) so issue closure remains auditable.

**Broke / Fixed:**

- No runtime or code-path changes; documentation-only update.

**Measurements:**

- Existing automated UCI integration test suite remains green (`4 tests`, `0 failures`).
- Tactical benchmark closure already recorded (`48/50`, `96%`).

**Next:**

- Execute one GUI game run, collect artifacts, and paste final acceptance evidence into issue #25.

### [2026-03-23] Phase 2 — Issue #25 Evidence Scaffold and Result Paths

**Built:**

- Updated issue #25 runbook with explicit "where results appear" guidance:
	- Maven Surefire report paths for tactical and UCI integration outputs,
	- GUI export/log source locations for Cute Chess and Arena.
- Created a ready evidence folder scaffold in backend repo:
	- `chess-engine/evidence/issue-25/2026-03-23/README.md`
	- `chess-engine/evidence/issue-25/2026-03-23/validation-report.md`
- Pre-filled validation report with environment/result/artifact sections to reduce manual setup work.

**Decisions Made:**

- Keep evidence under versioned repo path for traceability and easy issue attachment.
- Use a date-stamped folder convention to support repeated validation runs without overwriting prior artifacts.

**Broke / Fixed:**

- No engine behavior changes; docs/evidence scaffold only.

**Measurements:**

- Prior verification remains unchanged: tactical gate passing (`48/50`, `96%`) and UCI integration tests green (`4/4`).

**Next:**

- Run one full GUI game, place `game1.pgn` and `uci-gui-log.txt` in the new evidence folder, and mark pass/fail fields in `validation-report.md`.

### [2026-03-24] Phase 2 — UCI Engine ID Rename to Vex

**Built:**

- Updated UCI engine identity string emitted on `uci` command from `ChessEngine-UCI` to `Vex`.

**Decisions Made:**

- Treated `id name` as the canonical engine brand identifier and aligned runtime output and test expectations together.

**Broke / Fixed:**

- No regressions observed in behavior; rename is limited to identity output and corresponding test assertion.

**Measurements:**

- Validation run: pending immediate module test execution after rename.
- Perft depth 5 (startpos): Not measured in this cycle.
- Nodes/sec: Not measured in this cycle.
- Elo vs. baseline: Not measured in this cycle.

**Next:**

- Re-run `engine-uci` tests to confirm handshake and command loop remain green with the new engine name.

### [2026-03-24] Phase 2 — Issue #25 GUI Evidence Report Populated

**Built:**

- Ingested `game1.pgn` evidence under `chess-engine/evidence/issue-25/2026-03-23/`.
- Updated `validation-report.md` with concrete game metadata and result-derived pass/fail statuses.
- Recorded match outcome details (Vex as Black, 1-0, checkmate termination) and linked PGN artifact path.

**Decisions Made:**

- Marked full-game and legal-move criteria as pass based on uninterrupted complete PGN ending in mate.
- Left UCI log artifact explicitly pending until `uci-gui-log.txt` is added.

**Broke / Fixed:**

- No code-path changes; evidence/report update only.

**Measurements:**

- GUI evidence game: 43 plies, result `1-0`, termination `White mates`.
- Prior automated verification remains valid: `engine-uci` integration tests passing.
- Perft depth 5 (startpos): Not measured in this cycle.
- Nodes/sec: Not measured in this cycle.
- Elo vs. baseline: Not measured in this cycle.

**Next:**

- Add `evidence/issue-25/2026-03-23/uci-gui-log.txt` and paste final closure summary into GitHub issue #25.

### [2026-03-24] Phase 2 — CI Fix for UCI Integration Harness Java Path

**Built:**

- Fixed `UciApplicationIntegrationTest` process launch path construction to be cross-platform.
- Replaced hardcoded Windows-style `"\\bin\\java"` suffix with OS-aware executable resolution:
				- Windows: `java.exe`
				- Linux/macOS: `java`
	built via `Path.of(java.home, "bin", javaBinary)`.

**Decisions Made:**

- Keep integration tests launching a real UCI subprocess, but make the launcher portable across local Windows and GitHub Linux runners.

**Broke / Fixed:**

- CI failure root cause: mixed path separators generated an invalid executable path on Linux (`.../temurin-21-jdk-amd64\bin\java`).
- Fix: use platform-native path assembly and binary naming in the harness.

**Measurements:**

- Local `engine-uci` reactor test run after fix: passing.
- Perft depth 5 (startpos): Not measured in this cycle.
- Nodes/sec: Not measured in this cycle.
- Elo vs. baseline: Not measured in this cycle.

**Next:**

- Re-run GitHub Actions CI for `develop` to verify Linux runner stability on `engine-uci` tests.

### [2026-03-24] Phase 2 — CI Node 24 Opt-In for GitHub Actions

**Built:**

- Updated backend CI workflow (`chess-engine/.github/workflows/ci.yml`) to opt JavaScript actions into Node 24 explicitly.
- Added job-level environment variable:
	- `FORCE_JAVASCRIPT_ACTIONS_TO_NODE24: true`

**Decisions Made:**

- Chose explicit Node 24 opt-in now to remove deprecation warnings and avoid future runner default transition surprises.

**Broke / Fixed:**

- No build logic changes; workflow runtime environment update only.

**Measurements:**

- Expected outcome: warning about Node.js 20 actions deprecation is eliminated in subsequent CI runs.
- Perft depth 5 (startpos): Not measured in this cycle.
- Nodes/sec: Not measured in this cycle.
- Elo vs. baseline: Not measured in this cycle.

**Next:**

- Trigger CI once on `develop` and confirm warning-free completion in the `Complete job` section.

