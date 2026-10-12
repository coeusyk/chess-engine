# Diagnostic Note: Move-Engagement Inventory

**Date:** 2026-09-14
**Trigger:** requested audit of every scenario where a `Move` object is engaged in
the codebase, as groundwork for a possible tracing mechanism.
**Scope:** inventory only, no implementation. General move-tracing was explicitly
dropped from scope — see Decision below. Codebase reviewed at commit `217158d`
(branch `phase/15-nnue`).

---

## Purpose

This is a reference map of every place a `Move` is created, generated, applied,
ordered, evaluated, stored, converted, or replayed, gathered via `graphify query`
and source grep. It exists so that if a concrete make/unmake or search-state bug
shows up later, the relevant call sites are already known instead of re-derived
under time pressure. It is not a design and nothing here is implemented.

## Inventory

**1. Generation** — `engine-core/src/main/java/coeusyk/game/chess/core/movegen/MovesGenerator.java`
`generateMoves()` dispatches to `generatePawnMoves()`, `generateSlidingMoves()`,
`generateKingMoves()`, `generateKnightMoves()`, `addPromotionMoves()`;
`generateCaptures()` / `generateNonPawnCaptures()` for quiescence search;
`Move.of()` factory calls at each site; `getAllMoves()` / `getActiveMoves(color)`
as the read-out.

**2. Application (the actual board mutation)** — `engine-core/.../models/Board.java`
`makeMove(Move)` (L695) / `makeMove(int packed)` (L708), `unmakeMove()` (L844).
`movesPlayed` (`ArrayList<Move>`) already logs the full-game move list, gated by
an existing boolean flag that skips recording (and FEN generation) during search
for performance. `UnmakeInfo` / `NullMoveState` records carry undo state for real
and null moves.

**3. Search** — `engine-core/.../search/Searcher.java`, `MoveOrderer.java`,
`StaticExchangeEvaluator.java`
`alphaBeta()`, `quiescence()`, `searchRoot()`, `iterativeDeepening()`,
`searchDepth()` all make/unmake recursively; `rootMoveList`, `searchMoves`,
`rootTtMoveHint`, `killerMoves[][]` hold move state. `MoveOrderer.orderMoves()`
(two overloads) and `mvvLvaScore()` score moves before they're tried.
`StaticExchangeEvaluator.evaluate(Board, Move)` / `evaluate(Board, int)` gates
capture moves.

**4. Transposition table** — `engine-core/.../search/TranspositionTable.java`
`store(key, bestMove, depth, score, bound)`; `Entry` record carries `bestMove`
as a packed int; probed in `Searcher` for move-ordering hints.

**5. Notation / conversion** — `engine-core/.../notation/SanConverter.java`
(`toSan()` / `fromSan()`); `chess-engine-api/.../utils/UciConverter.java`;
`chess-engine-api/.../utils/MoveNotation.java`.

**6. Opening book** — `engine-core/.../book/OpeningBook.java`
`probe(Board)` → `Move`, `decodeMove(Board, int rawMove)`.

**7. PGN tooling** — `engine-core/.../tools/PgnReplayer.java`,
`engine-tuner/.../PgnExtractor.java` — replay moves via `makeMove`.

**8. NNUE / eval** — `engine-core/.../eval/nnue/FeatureExtractor.java`,
`NnueEvaluator.java` (`reset()`) — incremental accumulator updates keyed on the
move just made/unmade.

**9. UCI boundary** — `engine-uci/.../uci/UciApplication.java`
`position` command applies a move list to `Board`; `runSearch()` emits
`bestmove`; `ponderMoveNumber` / `latestIterativeBestMove` track pondering state.

**10. REST API** — `chess-engine-api/.../services/{ChessGameService,AnalysisService,GameSession}.java`
wrap `makeMove` / `MovesGenerator` to serve legal-move lists and apply
client-submitted moves.

**11. Tuner** — `engine-tuner/.../TunerEvaluator.java` (`evaluateStatic()`),
`TunerPosition.java` (`from()`), `SmokeTestRunner.java` — replay moves to build
and label training positions.

## Key structural fact

Nearly everything above (search, UCI, PGN replay, tuner, API) already funnels
through `Board.makeMove()` / `unmakeMove()` as the single chokepoint for
board-*mutating* moves. Board already has a gated-recording mechanism
(`movesPlayed` + a skip-recording flag) built for exactly this reason: full
tracing on every search-tree node is incompatible with the no-allocation hot-path
constraint (CLAUDE.md §4) and the NPS bench floor. Generation, ordering, TT,
notation, and opening-book sites are move-*engagement* but not board-*mutation* —
they don't go through that chokepoint and would need separate, cheaper
instrumentation if ever traced.

## Decision

General move-tracing across all eleven scenarios is out of scope for now — no
implementation plan was written. If a concrete make/unmake or search-state bug
appears later, the right move is a narrowly scoped diagnostic built around that
specific failure (e.g. a bounded transition log on `Board.makeMove`/`unmakeMove`
gated the same way `movesPlayed` already is), not a general tracing layer. This
note exists so that scoping decision doesn't have to be re-derived from scratch.
