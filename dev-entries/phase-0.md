# Dev Entries - Phase 0

### [2026-03-22] Phase 0 (In Progress) — Multi-Module Foundation and Session-Aware API

**Built:**

- Converted backend repository into multi-module Maven structure.
	- Parent aggregator module.
	- engine-core module.
	- chess-engine-api Spring adapter module.
	- engine-uci module skeleton.
- Moved legacy Spring source tree under chess-engine-api module.
- Extracted Board, Move, Piece, and MovesGenerator into engine-core with core package namespace.
- Rewired API controllers and DTO wrappers to consume engine-core classes.
- Added session-aware game model via GameSession and GameSessionStore with optional gameId endpoint parameter support.
- Added explicit backend new-game endpoint for session reset intent.
- Added initial UCI command-loop skeleton application returning placeholder bestmove values.
- Added CI workflow scaffold for build and test gate.
- Added bitboard migration placeholder model and architecture decision note under docs.

**Decisions Made:**

- Adopted dual-consumer core direction: REST adapter and UCI adapter both depend on engine-core.
- Kept functionality-compatible API surface while introducing module boundaries to reduce migration risk.
- Introduced session store now to prevent single-global-game coupling from blocking next phases.

**Broke / Fixed:**

- Source relocation created expected delete/add git diff pattern across old root src and new module paths.
- Local build execution remains partially blocked by environment tooling gaps:
	- Maven wrapper files missing in repository.
	- Global Maven not installed in current workstation environment.
- Mitigation: CI workflow added so module integrity can be validated in GitHub environment once pushed.

**Measurements:**

- UI build (after new-game endpoint wiring): passed.
- Backend IDE-level compile diagnostics on changed files: no errors reported.
- Perft depth 5 (startpos): Not measured in this cycle.
- Nodes/sec: Not measured in this cycle.
- Elo vs. baseline: Not measured in this cycle.

**Next:**

- Add service-layer facade in chess-engine-api to make controllers thin transport adapters.
- Complete initial bitboard migration slice in engine-core while preserving API compatibility.
- Add game creation endpoint returning generated gameId for explicit multi-game UI support.

### [2026-03-22] Phase 0 (In Progress) — Maven Classpath Import Repair for VS Code Java

**Built:**

- Repaired Maven parent/dependency management structure so Java source files are recognized on module classpaths.
- Updated parent `pom.xml` to provide central dependency management via `spring-boot-dependencies` BOM import.
- Added parent-level plugin management for compiler and Spring Boot Maven plugin versions.
- Updated `chess-engine-api/pom.xml` to inherit from project parent `coeusyk.spring:chess-engine` using `../pom.xml`.

**Decisions Made:**

- All backend modules should share a single reactor parent to avoid split project models in IDE import.
- Spring dependency versioning should be controlled by BOM import in parent, not by giving only one child a different parent.

**Broke / Fixed:**

- Reported issue: many files showed `not on the classpath of project chess-engine, only syntax errors are reported` at package declarations.
- Root cause: inconsistent module parent hierarchy increased risk of incorrect Java LS project modeling after the multi-module split.
- Fix: normalized parent chain and dependency management; post-fix diagnostics show `BoardController.java` without classpath/package errors.

**Measurements:**

- Backend diagnostics: `BoardController.java` reports no errors after POM normalization.
- Parent POM warning: project configuration requires update/reload in Java tooling cache.
- Perft depth 5 (startpos): Not measured in this cycle.
- Nodes/sec: Not measured in this cycle.
- Elo vs. baseline: Not measured in this cycle.

**Next:**

- Force Java project reload in VS Code to refresh stale classpath state.
- Re-run diagnostics across all modules after reload to confirm classpath warning is fully cleared.

### [2026-03-22] Phase 0 Completion — Thin API Adapter, Isolation Tests, and Bitboard Snapshot Bridge

**Built:**

- Added `ChessGameService` in backend API module and moved board/session orchestration out of controller methods.
- Refactored `BoardController` into a thin transport adapter that only maps HTTP calls to service methods.
- Added `Phase0StabilizationTests` integration tests with `MockMvc`:
	- concurrent game flows using distinct `gameId` values,
	- explicit verification that board states remain isolated between sessions.
- Upgraded bitboard placeholder into a concrete converter:
	- `BitboardPosition.fromBoard(Board)` builds per-piece and occupancy bitboards,
	- `Board.toBitboardPosition()` exposes a migration bridge API.
- Added bitboard conversion correctness assertions in test coverage (piece placement, occupancy, side-to-move, castling, EP).

**Decisions Made:**

- Keep synchronization in the service layer around per-session locks so controllers remain stateless HTTP adapters.
- Use a board-to-bitboard snapshot bridge now (Phase 0) and keep full bitboard make/unmake migration for Phase 1.

**Broke / Fixed:**

- Pre-existing architecture issue: controller still held domain orchestration despite module split.
- Fix: service-facade extraction completed without changing external endpoint signatures.
- No compile diagnostics reported on changed files after refactor.

**Measurements:**

- IDE diagnostics: no errors on changed API/core/test files.
- Parallel game isolation: covered by integration tests added in `chess-engine-api`.
- CI green state: pending GitHub workflow execution (local Maven execution still blocked by missing wrapper setup in this environment).
- Perft depth 5 (startpos): Not measured in this cycle.
- Nodes/sec: Not measured in this cycle.
- Elo vs. baseline: Not measured in this cycle.

**Next:**

- Run backend CI workflow on GitHub to confirm Phase 0 gate is green with the new tests.
- Open Phase 1 branch from `develop` and begin full bitboard make/unmake + Zobrist + Perft harness.

### [2026-03-22] Phase 0 Runtime Fix — Explicit RequestParam Names for IntelliJ Runs

**Built:**

- Updated REST controller query argument annotations to use explicit parameter names (`name = ...`) for all endpoints that accept query params.
- Covered `gameId`, `pieceSquare`, and `activeColor` bindings explicitly in `BoardController`.

**Decisions Made:**

- Prefer explicit Spring request parameter names over reflection-based inference so runtime behavior does not depend on compiler `-parameters` settings.

**Broke / Fixed:**

- Runtime error in backend: `Name for argument of type [java.lang.String] not specified ... Ensure that the compiler uses the '-parameters' flag`.
- Root cause: optional String query parameters relied on method-parameter name reflection during IntelliJ runtime compilation.
- Fix: explicit `@RequestParam(name = "...")` on controller method arguments.

**Measurements:**

- IDE diagnostics on updated controller: no errors.
- Local runtime verification: pending user restart and endpoint retest.
- Perft depth 5 (startpos): Not measured in this cycle.
- Nodes/sec: Not measured in this cycle.
- Elo vs. baseline: Not measured in this cycle.

**Next:**

- Restart backend process in IntelliJ and re-hit `/engine/setup`, `/engine/new-game`, and `/engine/make-move` endpoints to confirm no binding exceptions.

