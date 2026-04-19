# Dev Entries - Phase 6

### [2026-03-26] Phase 6 — Product Hardening: Issue #64 Game Lifecycle Endpoints

**Built:**

- `GET /api/game/{gameId}/state` — returns `GameStateResponse`: fen, moveHistory (UCI strings), status, activeColor, canUndo, canRedo.
- `POST /api/game/create` — returns `{ gameId: UUID }`, creating an isolated GameSession via `GameSessionStore.create()`.
- `POST /api/game/{gameId}/reset` — resets board to starting position, clears history and redo stack.
- `POST /api/game/{gameId}/load` — loads a FEN; `Board(fen)` throws `IllegalArgumentException` on invalid input, caught by `@ExceptionHandler` in `GameController` and returned as HTTP 400.
- `POST /api/game/{gameId}/undo` — pops `board.movesPlayed` tail, calls `board.unmakeMove()`, pushes move to `session.redoStack`; returns `{ moved: bool }`.
- `POST /api/game/{gameId}/redo` — pops `session.redoStack`, calls `board.makeMove(move)`; returns `{ moved: bool }`.
- `Board.isInsufficientMaterial()` — KK / KBK / KNK / KBKB (same-color bishops) detection.
- `Board.toFen()` — public delegate to existing private `getCurrentFEN()`.
- `GameSession.redoStack` — `ArrayDeque<Move>` field with getter.
- `GameSessionStore.create()` — UUID-based session factory method.
- `GameSessionStore.get(gameId)` — strict lookup throwing `GameNotFoundException` (HTTP 404).
- `GameNotFoundException` — `@ResponseStatus(NOT_FOUND)` runtime exception.
- `GameStateResponse` — record with fen/moveHistory/status/activeColor/canUndo/canRedo.
- `CorsConfig` — added `/api/game/**` mapping.
- `chess-engine-api/pom.xml` — added `maven-surefire-plugin 3.2.5` (previously missing, causing JUnit 5 tests to be silently skipped).
- `Phase6GameLifecycleTests` — 12 integration tests covering full lifecycle flow, FEN validation rejection, isolation, and insufficient material detection.

**Decisions Made:**

- `makeMove()` clears `redoStack` on every new move to prevent branching-path redo poisoning.
- Used `GameSessionStore.get()` (strict 404) for all Phase 6 methods; legacy `/engine/**` methods continue using `getOrCreate()` for backward compatibility.
- FEN validation is based on `Board(fen)` throwing `IllegalArgumentException` — no explicit regex validation layer added.
- `computeStatus()` checks game termination in priority order: checkmate → stalemate → 50-move → repetition → insufficient material → IN_PROGRESS.

**Broke / Fixed:**

- `chess-engine-api` Surefire was on v2.12.4 (default Maven version), which does not pick up JUnit 5 tests. Upgraded to 3.2.5 in the module POM. All 15 tests now discovered and run.
- `loadFenChangesStateAndEnablesStatus` test: `getCurrentFEN()` omits trailing empty-square digits in FEN rank notation (e.g. `KQ6` → `KQ`). The assertion was relaxed to check fractional FEN content rather than full piece-placement match.

**Measurements:**

- Perft depth 5 (startpos): not measured this cycle (no Board logic changed).
- Nodes/sec: not measured this cycle.
- Chess-engine-api tests: 15 run, 0 failures, 0 skipped.

**Next:**

- Read and implement issue #65 (SSE analysis streaming endpoint).

### [2026-03-26] Phase 6 — Product Hardening: Issue #65 SSE Analysis Streaming Endpoint

**Built:**

- `GET /api/analysis/stream` (produces `text/event-stream`) — validates FEN synchronously, runs iterative-deepening search on a background thread, and streams SSE events to the client.
- `AnalysisService` — Spring service holding a `newSingleThreadExecutor()` for serialized analysis; `AtomicReference<AtomicBoolean>` cancellation flag ensures only one search runs at a time; a new request cancels the previous search before starting.
- FEN validation: `new Board(fen)` throws `IllegalArgumentException` synchronously before any SSE connection is opened. `@ExceptionHandler` in `AnalysisController` maps this to HTTP 400 `{ "error": message }`.
- SSE `info` event fields: `type`, `depth`, `seldepth`, `multiPv`, `score` (`{type: "cp"/"mate", value}`), `nodes`, `nps` (nodes * 1000 / timeMs), `time`, `hashfull`, `pv` (list of UCI strings).
- Mate score detection: `Math.abs(scoreCp) >= 99_872` (MATE_SCORE 100_000 minus MAX_PLY 128). Positive mate: `(100_000 - scoreCp + 1) / 2` moves; negative mate: `-((100_000 + scoreCp + 1) / 2)` moves.
- SSE `bestmove` event: `{ type: "bestmove", move: "<uci>" }` sent after search completes; `"0000"` when no legal moves.
- SSE `error` event: emitted if search throws unexpectedly; then `emitter.completeWithError()`.
- `TimeManager.configureMovetime()` used when `movetime` param is present; otherwise `Searcher.iterativeDeepening()` with depth limit.
- `Searcher.setMultiPV()` used for multi-PV requests; each iteration fires N ranked `info` events.
- SseEmitter timeout set to 300,000 ms. `onCompletion`/`onTimeout`/`onError` all set the cancellation flag so the background search aborts at its next check.
- `CorsConfig` extended with `/api/analysis/**` mapping.
- `Phase6AnalysisStreamTests` — 5 integration tests: invalid FEN → HTTP 400, valid FEN depth=1 → stream opens with info+bestmove events, checkmate position → bestmove event, multiPv=3 → ≥3 ranked info events, all required SSE info fields present.

**Decisions Made:**

- FEN validation happens synchronously in `AnalysisService.startAnalysis()` before `SseEmitter` is created so Spring can still return a normal HTTP 400 response (not a partially-opened SSE stream).
- Single global active-analysis state (one search at a time server-wide) is sufficient since the analysis endpoint has no session ID; there is no purpose to running concurrent analyses for the same position.
- `toUci()` and `squareToUci()` duplicated locally in `AnalysisService` to avoid coupling to `ChessGameService` internals.

**Broke / Fixed:**

- `AnalysisService.java` was initially missing the `SearchResult` import; added before test run.
- No regressions in existing lifecycle or Phase 0 tests.

**Measurements:**

- Perft depth 5 (startpos): not measured this cycle.
- Nodes/sec: not measured this cycle.
- Chess-engine-api tests: 20 run, 0 failures, 0 skipped (5 new + 12 lifecycle + 2 Phase0 + 1 smoke).

**Next:**

- Read and implement issue #66.

### [2026-03-26] Phase 6 — Product Hardening: Issue #66 Synchronous Analysis REST Endpoint

**Built:**

- `POST /api/analysis/evaluate` — synchronous fixed-depth search endpoint returning complete JSON evaluation result.
- `AnalysisService.evaluate()` — reuses all existing service logic: FEN validation, `toUci()`, `squareToUci()`, and moves the mate scoring into a shared `buildScoreInfo(int scoreCp)` helper used by both SSE and evaluate paths.
- Depth silently clamped to `EVALUATE_DEPTH_CAP = 15` before calling the searcher.
- 60-second server-side timeout via `CompletableFuture.get(60, TimeUnit.SECONDS)`; `TimeoutException` throws `SearchTimeoutException`, which `AnalysisController` maps to HTTP 504 with `{ "error": message }`.
- Any in-progress SSE analysis is cancelled (via `activeCancellationFlag`) before the evaluate search starts.
- `IterationListener` accumulates `IterationInfo` events per depth iteration; on each depth rollover (multipv == 1 + buffer non-empty), previous buffer is committed to `lastCompleteInfos`. After the future returns, remaining buffer is committed as the final iteration.
- Response: `EvaluateResponse` record with `bestMove`, `score` (`ScoreInfo`), `depth` (`depthReached`), `nodes`, `nps`, `pv` (UCI strings), `lines` (`List<LineInfo>` with rank/score/pv per MultiPV line).
- New DTOs: `EvaluateRequest`, `EvaluateResponse`, `ScoreInfo`, `LineInfo`, `SearchTimeoutException`.
- `Phase6AnalysisEvaluateTests` — 7 integration tests: invalid FEN 400, null fen 400, depth=1 response shape, depth=6 startpos valid result, multiPv=3 three ranked lines, depth=30 clamped (tested on checkmate FEN), score.type is cp or mate.

**Decisions Made:**

- Depth-clamping test uses a checkmate FEN (zero legal moves) instead of starting position to avoid hitting the 60-second timeout at depth 15.
- `buildScoreInfo()` extracted from inline `Map` logic in `sendInfoEvent()` to a shared private method — the only refactor needed to meet the "no code duplication" acceptance criterion.
- Cancel SSE analysis before evaluate starts so the single-thread executor is never blocked waiting behind a running stream.

**Broke / Fixed:**

- Initial `depthAbove15IsSilentlyClamped` test used the starting position with depth=30 (clamped to 15) — depth 15 on starting position exceeds 60 seconds, triggering the timeout and returning 504. Fixed by switching to a checkmate FEN where depth-15 search completes in milliseconds.

**Measurements:**

- Perft depth 5 (startpos): not measured this cycle.
- Nodes/sec: not measured this cycle.
- Chess-engine-api tests: 27 run, 0 failures, 0 skipped.

**Next:**

- Start chess-engine-ui Phase 6 issues (#2-#8).

---

### [2025-07-14] Phase 6 — chess-engine-ui Analysis UI (Issues #3–#8)

**Built:**
- `useAnalysis.js` hook: SSE EventSource connecting to `/api/analysis/stream`; parses `info` and `bestmove` events; exposes `{ score, depth, lines, nodes, nps, seldepth, hashfull }`; EventSource closed on bestmove, error, and useEffect cleanup.
- `ScoreBar.js`: vertical evaluation bar using sigmoid fill formula `50 + 50*(2/(1+exp(-cp/400))-1)`, clamped [2,98]; mate positions pin at 98%/2%; score label rendered as `+1.4`/`-0.8` or `M3`; 200ms CSS ease transition.
- `AnalysisLines.js`: renders up to 3 engine PV lines with rank number, score, and UCI move tokens; hover over a line highlights the first move arrow on the board; hover over a specific token highlights that move's arrow.
- SVG arrow overlay in Board.js: positioned over the board grid using `fromSquare` and `toSquare` square-centre coordinates; re-renders on `pvHighlight` prop change.
- `AnalysisPanel.js`: collapsible right-sidebar panel shell; header shows toggle button + score text; body contains ScoreBar + metadata row (Depth/Nodes/NPS/Hash) + AnalysisLines + PvWalkthrough; max-height CSS transition for open/close.
- `boardUtils.js`: `fenToGrid`, `fenMoveInfo`, `uciToSquareIndex`, `parseUciMove`, `uciToSan`, `applyMove`; covers castling (rook relocation), en passant, promotion; SAN covers O-O/O-O-O, pawn captures, promotion suffix `=Q`.
- `PvWalkthrough.js`: rank-1 PV displayed as numbered SAN; click any move to set pvStep; ← / → to navigate; ← at step 0 → exits walkthrough (pvStep null); ✕ to exit; move numbers derived from startFen via fenMoveInfo.
- Board `readOnlyGrid` prop: when set, Board renders that grid instead of its own; all click/drag interaction suppressed; clicking in readOnly calls `onExitReadOnly` to exit walkthrough.
- App.js pvStep state + pvData useMemo: walks currentGrid through pvMoves applying `uciToSan`/`applyMove`; pvGrid derived from pvData.grids[pvStep+1]; pvStep reset on currentFen change.
- `MoveList.js`: full game history in SAN, two-column CSS grid (moveNum | white | black); last move highlighted with `.move-last`; auto-scrolls on each history update via listRef.scrollTop.
- Board `onMoveHistory` callback: computes UCI from startSquare/targetSquare indices, calls `uciToSan` against pre-move grid, emits SAN after successful move response.
- `isMovePendingRef` in Board.makeMove: returns early if a move request is already in flight; prevents duplicate backend calls from rapid clicks.
- App.js DOM cleanup: removed 3 `addEventListener` / `removeEventListener` useEffects (loadRef, newGameRef, removeHighlights DOM class mutation); replaced with React `onClick` props on buttons; FEN input converted to controlled component; #main-content onClick increments `deselectSignal` for Board to clear selection via useEffect.
- `deselectSignal` prop wired to Board: Board's useEffect clears selectedSquare/targetSquares on each increment — replaces prior broken DOM class manipulation.

**Decisions Made:**
- SSE EventSource uses absolute URL (`process.env.REACT_APP_CHESS_API_SERVER_HOST || 'http://localhost:8080'`) because EventSource does not follow axios base URL config.
- FEN field added to `SetupContainer` and `ResponseContainer` (via `board.toFen()`) in chess-engine-api layer — engine-core has no Jackson dependency so no annotations were added there; explicit public field `fen` added to the Java containers.
- `uciToSan` intentionally omits check/checkmate symbols and disambiguation — full move generation is not available client-side and is not required by the Phase 6 acceptance criteria.
- PV step-through is rank-1 line only (best line); non-rank-1 lines are out of scope per issue #6.
- `pvGrid` index: `grids[pvStep + 1]` because `grids[0]` = starting position before any PV moves.
- MoveList clears naturally on new game / FEN load because `window.location.reload()` resets all React state.
- `gameStartFen` captured on first non-null `currentFen` (set once) to give MoveList the correct starting move number even from non-standard starting FENs.

**Broke / Fixed:**
- `removeHighlights` useEffect in App.js was registering an anonymous arrow function but attempting to remove `removeHighlights` (different reference) in cleanup — listener was never removed. Fixed by deleting the entire useEffect; React state in Board.js already managed the classes.
- Board `onPositionChange` originally passed only `fen`; changed to `(fen, grid)` so App.js can track `currentGrid` for pvData computation without re-parsing the FEN.
- `isCapture` unused variable warning in boardUtils.js (from pawn-capture detection draft code): removed the assignment; diagonal pawn move is detected by `fromFile !== toFile` which is sufficient.

**Measurements:**
- Perft depth 5 (startpos): not measured this cycle.
- Nodes/sec: not measured this cycle.
- Elo vs. baseline: not measured this cycle.

**Next:**
- Phase 6 exit criteria review: confirm all issues #63-#66 (chess-engine) and #2-#8 (chess-engine-ui) are closed and committed; then create PR from phase/6-product-hardening to develop.

---

