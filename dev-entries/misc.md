# Dev Entries - Miscellaneous / Cross-Phase

### [2026-03-22] Repo Hygiene — Ignore IDE/Container Metadata in Both Repositories

**Built:**

- Updated ignore rules to keep local workspace metadata out of commits for both repositories.
- Added explicit `.devcontainer/` ignore coverage in backend and frontend repositories.
- Added explicit `.idea/` and `.devcontainer/` ignore section in frontend repository for clarity.
- Removed already tracked frontend `.idea/` and `.devcontainer/` files from Git index using cached removal.

**Decisions Made:**

- Keep local IDE/container configuration as developer-local artifacts, not versioned source.
- Prefer explicit ignore entries even when partial IDEA patterns already exist to avoid ambiguity.

**Broke / Fixed:**

- Problem: `.idea` and `.devcontainer` artifacts were still entering/staying in commits, especially in frontend repository.
- Fix: normalized `.gitignore` entries plus `git rm -r --cached --ignore-unmatch` on both target folders.

**Measurements:**

- Backend repo status: only `.gitignore` modified for ignore policy extension.
- Frontend repo status: staged index removals for tracked `.idea` and `.devcontainer` files and `.gitignore` update.
- Perft depth 5 (startpos): Not measured in this cycle.
- Nodes/sec: Not measured in this cycle.
- Elo vs. baseline: Not measured in this cycle.

**Next:**

- Commit the staged deletions in frontend repo so tracked IDE/container files are fully dropped from history going forward.

### [2026-03-22] UI Interaction Upgrade — King-In-Check Indicator and Drag/Drop Moves

**Built:**

- Implemented a visible king-in-check board indicator driven by backend check-state endpoint.
- Reworked board interaction flow in React state (selected square, target squares, check square) instead of imperative DOM mutation.
- Added drag-and-drop move support for pieces using HTML5 drag events (`dragstart`, `dragover`, `drop`).
- Preserved click-to-move behavior as fallback and for parity with existing interaction.
- Refactored `Piece` component into a render-only component with click/drag callbacks supplied by `Board`.

**Decisions Made:**

- Keep backend API contract unchanged; UI computes legal targets from `possibleMoves` already returned by backend.
- Treat drag/drop as alternate input path to the same validated move execution flow (`/make-move`) to avoid duplicated rules.

**Broke / Fixed:**

- Initial refactor introduced a React hooks rule violation (`useMemo` called after conditional return).
- Fixed by removing conditional hook usage and using a render-local `Set` for target-square lookup.

**Measurements:**

- Frontend production build: passed after fixes.
- Runtime validation: pending manual board interaction verification with backend running.
- Perft depth 5 (startpos): Not measured in this cycle.
- Nodes/sec: Not measured in this cycle.
- Elo vs. baseline: Not measured in this cycle.

**Next:**

- Manual QA: verify king check highlight appears/disappears correctly after move sequences.
- Manual QA: verify drag-drop legal moves execute and illegal drops are ignored.

### [2026-03-22] UI Drag Reliability Fix — Replaced Native HTML5 Drag with Custom Pointer Drag

**Built:**

- Replaced browser-native `draggable`/`dragstart`/`drop` interaction with a custom pointer-driven drag model.
- Added board-level drag tracking using window `mousemove`/`mouseup` listeners during active drag.
- Implemented square hit detection via `elementFromPoint(...).closest('.board-square')` for robust targeting.
- Added detached drag preview piece that follows cursor while hiding source piece during drag.

**Decisions Made:**

- Native HTML5 drag ghost image was rejected for chess UX because it keeps source piece in place and introduces inconsistent start behavior.
- Click-to-move remains as fallback and shares the same backend move execution path.

**Broke / Fixed:**

- User-reported bug: dragging only worked intermittently, often requiring prior click/select and sometimes dragging a different piece.
- Root cause: mixed click-selection state and native drag lifecycle conflict.
- Fix: single custom drag state source in React (`draggingPiece`) with deterministic move/drop handling.

**Measurements:**

- Frontend production build: passed after custom drag refactor.
- Runtime validation: pending final manual verification in local play session.
- Perft depth 5 (startpos): Not measured in this cycle.
- Nodes/sec: Not measured in this cycle.
- Elo vs. baseline: Not measured in this cycle.

**Next:**

- Manual QA: verify press-hold immediately starts drag on the same piece without pre-click.
- Manual QA: confirm dragged piece detaches visually and reattaches correctly on legal/illegal release.

### [2026-03-23] Cross-Repo Feature Slice — SAN Notation + Board Coordinate Labels

**Built:**

- Added SAN conversion utility in `engine-core`:
	- `SanConverter.toSan(Move, Board)` (pre-move board state),
	- `SanConverter.fromSan(String, Board)` (legal-move matching parser).
- Implemented SAN rules for:
	- pawn moves/captures,
	- piece moves,
	- disambiguation,
	- castling,
	- promotion suffix,
	- check/checkmate suffixes,
	- en passant SAN capture form.
- Added SAN unit tests (`SanConverterTest`) for generation/parsing scenarios.
- Updated REST payloads in `chess-engine-api` to return both formats per move:
	- `uci` (internal/interaction),
	- `san` (display),
	- with existing `startSquare/targetSquare/reaction` retained for compatibility.
- Added `MoveNotation` DTO and rewired setup/piece-moves/make-move responses to emit notation-enriched move arrays.
- Updated React UI board to render coordinate labels outside the square grid:
	- file labels below board,
	- rank labels to the left,
	- both derived solely from `orientation` prop.
- Replaced DOM class-toggle board flip with React orientation state in `App` and prop-driven board rendering.

**Decisions Made:**

- Kept UCI module protocol strictly unchanged (UCI long algebraic only).
- Treated SAN as display/REST concern and preserved move execution via existing square-index flow.
- Implemented coordinate layout with flex wrappers (no absolute overlay) to preserve alignment across responsive sizes.
- Deferred analysis SSE/PV SAN wiring because analysis SSE endpoint is a later-phase scope item (Phase 6 in plan).

**Broke / Fixed:**

- SAN promotion test initially used an illegal promotion position; corrected FEN to legal promotion setup.
- Updated expected SAN for promotion position to include check suffix (`e8=Q+`) after legality/evaluation verification.

**Measurements:**

- `engine-core` SAN test subset: SUCCESS (`5 tests`, `0 failures`).
- Full backend reactor run: SUCCESS (`54 tests`, `0 failures`, `1 skipped tactical`).
- `chess-engine-api` + `engine-core` compile run: SUCCESS.
- Frontend production build (`chess-engine-ui`): SUCCESS.

**Next:**

- Add REST-level API contract tests asserting `{ uci, san }` fields are present in move payloads.
- If/when Phase 6 work starts, wire SAN formatting for streamed PV lines on analysis SSE endpoint and analysis panel.

### [2026-03-24] Documentation — README Updated to Phase 2 Complete

**Built:**

- Updated backend `README.md` current-status section to mark Phase 2 complete.
- Added a concise summary of completed Phase 2 scope items (search, TT, time management, UCI).
- Updated roadmap section from "Phase 2 focus" to "Phase 3 focus (Strength Scaling)".
- Removed stale wording that described `engine-uci` as only a Phase 2 expansion target.

**Decisions Made:**

- Kept Phase 1 completion details in place while promoting Phase 2 completion to top-level status.

**Broke / Fixed:**

- Documentation-only update; no runtime or build behavior changes.

**Measurements:**

- N/A (docs update only).
- Perft depth 5 (startpos): Not measured in this cycle.
- Nodes/sec: Not measured in this cycle.
- Elo vs. baseline: Not measured in this cycle.

**Next:**

- Keep README phase status aligned as Phase 3 implementation work lands.

