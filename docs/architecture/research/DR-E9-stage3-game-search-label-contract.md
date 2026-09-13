# Stage-3 game/search/label contract (E-9 design record)

**Status:** design only, no implementation. This is the semantic contract issue #209 was rewritten to produce, split from that issue's original single-implementation scope. It is downstream of, and more concrete than, `DR-E1-self-play-data-generation.md`, which stays the governing research document for why Stage 3 looks the way it does; this file answers what a game and a training sample concretely mean, in enough detail that #220 (VSPR wire format), #210 (ingestion), #211 (deterministic fixtures) and #212 (generator-selection policy) can build against it without inventing their own semantics. No binary layout, no byte offsets, no framing, no endianness — that is #220's job and is out of scope here on purpose.

Every claim about current engine behavior below is verified against source at commit `214c6e0` on `phase/15-nnue`, not against old issue prose. Line numbers are cited so a later reader can re-check them against a newer HEAD.

## 1. Verdict

The contract is implementation-ready as a semantic specification. The single most consequential fact this investigation surfaced is that a self-play `Board` and a self-play `Searcher`'s own recursion share one 768-entry array, and the current source has no bound check on it at all: it throws `ArrayIndexOutOfBoundsException` the instant the combined count of real game plies plus a search's own recursive make/unmake calls exceeds 768, with no graceful degradation. Any GameLoop design has to treat this as a hard safety constraint, not a tuning knob. The recommended resolution is a conservative move-cap, derived from the search's own worst-case recursion depth, which is one of the concrete numbers this document computes below rather than leaving as "bounded."

The second most consequential fact is that today's `IterationListener` callback fires before the per-iteration abort check, and `IterationInfo` itself carries no completed/aborted flag — so a future MultiPV-consuming adapter cannot tell, from the callback alone, whether the candidate set it just received for a given depth is complete or was cut short mid-iteration. That is the concrete API seam a future generator implementation will need, defined in section 5 below, not implemented here.

QuietWalk is out of scope for the initial design, for the reason section 6 states precisely: it can attach a label to a position the actual game never reached, and that is a distinct correctness failure from anything a wire-format or ingestion bug could cause — it corrupts the semantic content of the training data itself, silently, in a way no round-trip test would ever catch.

## 2. Current engine constraints discovered

Verified directly against source, not against `DR-E1` or old issue text.

- **`Board.UNMAKE_POOL_SIZE = 768`** (`Board.java:62`) sizes three parallel, per-`Board` arrays: `zobristStack` (`Board.java:124`), `unmakePool` (`Board.java:129`), and — per `ADR-009`'s own Supporting Evidence section — `NnueEvaluator`'s accumulator stack, which is explicitly "tied to `Board.UNMAKE_POOL_SIZE` ... rather than an independent literal." All three move in lockstep with one `Board` instance's own stack pointers.
- **`zobristSP` and `unmakeSP` reset to 0 only inside `initWithFEN()`** (`Board.java:162`, and `unmakeSP` is declared alongside `unmakePool` at `Board.java:130` with the same reset-only-on-init lifecycle). There is no separate `reset()`/`newGame()` method. The only way to get a fresh 768-entry budget is to construct (or re-`initWithFEN`) a `Board`.
- **`zobristStack[zobristSP++]`** is written once at construction time for the starting position (`Board.java:279`) and once per `makeMove()` call thereafter (`Board.java:828`), with **no bounds check before the increment**. The 769th cumulative push (`makeMove` call number 768, counting the initial push at construction) throws `ArrayIndexOutOfBoundsException`. Exactly the same is true of `unmakePool[unmakeSP++]` (`Board.java:721`).
- **Search recursion and real game-move history share the exact same stack.** `Searcher`'s own `alphaBeta`/`quiescence` recursion calls `board.makeMove(move)` / `board.unmakeMove()` directly on the same `Board` object passed into `search()` (14 call sites confirmed in `Searcher.java`, e.g. lines 774/791, 1018/1046, 1341/1357, 1571/1575, 1647/1651, 1709/1713). There is no separate "search scratch board" — a self-play `GameLoop` that keeps one persistent `Board` across a whole game and calls `Searcher.search(thatBoard, ...)` at each ply is pushing the search's own recursive exploration onto the identical array that already holds every real move played so far in the game.
- **Search's own two-fold repetition check depends on that shared history being present.** `Board.isRepetitionDraw()` (`Board.java:1104`) scans backward from the current stack pointer, bounded by `min(zobristSP, halfmoveClock + 1)` — this is the check `Searcher` calls internally (`Searcher.java:848`) during `alphaBeta` to detect a repetition inside the search tree. Because it reads the same `zobristStack` a persistent `Board` already has real game history on, a self-play `GameLoop` gets correct in-search repetition detection **for free**, with no special injection API, as long as it reuses one `Board` across the whole game rather than constructing a fresh one per move. This is the resolution to the "how does game history reach search" question section 4 states as a hard requirement.
- **`Board.isThreefoldRepetition()`** (`Board.java:1082`) is a different check from `isRepetitionDraw()`: it scans the **entire** `zobristStack[0..zobristSP)` from index 0, not windowed by the halfmove clock, and counts every occurrence, not just the first repeat. This is the game-level, legally-correct threefold check. It depends on the full game history being present in the array from move 1 — which means a Board can never be safely reset or reinitialized mid-game without silently breaking any legitimate threefold claim spanning positions before the reset point. This directly shapes the safety-margin decision in section 4: the fix has to be "cap the game short of the array's limit," not "periodically compact the array," because compaction breaks correctness.
- **`Board.isFiftyMoveRuleDraw()`** (`Board.java:1118`) is `halfmoveClock >= 100` — independent of the stack arrays, always correct regardless of history length.
- **`Board.isInsufficientMaterial()`** (`Board.java:1122`) implements the standard King-only / King+minor / opposite-color-bishops-only cases. `Board.isCheckmate()`/`isStalemate()` (`Board.java:1068`, `1075`) are legal-move-based, as expected.
- **`Searcher.MAX_PLY = 128`** (`Searcher.java:34`) bounds the iterative-deepening loop itself (`effectiveMaxDepth = min(maxDepth, MAX_PLY - 1)`, `Searcher.java:426`). **`Searcher.MAX_CHECK_EXTENSIONS = 16`** (`Searcher.java:42`) bounds how many extra plies check extensions can add on top of that per root search (`getMaxCheckExtensionsForDepth`, `Searcher.java:1423`). **`Searcher.MAX_Q_DEPTH = 6`** (`Searcher.java:82`) bounds quiescence search's own additional depth. A single worst-case search call can therefore push roughly `127 + 16 + 6 = 149` additional entries onto the shared stack beyond whatever real-game-history count is already there when that search call starts — call it 150 with rounding, before any further margin for aspiration-window re-searches or singular-extension edge cases this document did not trace line-by-line.
- **`Searcher.evaluator.reset(board)` is called at the top of every `iterativeDeepening()` call** (`Searcher.java:431`, the comment there: "every public entry point funnels through here, so this is the single place to rebuild any incremental evaluator state from scratch"). A persisted, per-game `NnueEvaluator` instance therefore needs no special per-ply handling: every search call already rebuilds its accumulator state from whatever the `Board` currently looks like.
- **`ADR-009`** ("one `NnueEvaluator` instance per `Searcher`, never shared") is the binding invariant on evaluator ownership. It does not say — and nothing else in the codebase says — that a `Searcher` may only ever be used for one color. `Searcher`'s own state (killer moves indexed by ply, not color; transposition table keyed by position, not color; history heuristic keyed by move, not color) has no color-specific storage anywhere. `Board.getActiveColor()` is what actually determines whose move a given `search()` call is being asked to find, and it changes naturally as real moves are pushed onto a persistent `Board` via `makeMove()`.
- **No existing self-play-shaped orchestration was found.** `UciApplication.java` has `ucinewgame` handling (line 198, clears the shared `TranspositionTable`) and ordinary UCI `position`/`go` handling, but nothing resembling a multi-game loop, move-selection diversity, or an internal game-record type. There is no precedent inside this repository to follow for GameLoop's own shape beyond the UCI session's existing `ucinewgame`-clears-TT convention, which section 3 uses as the closest analogous precedent for what should reset between self-play games.
- Graphify was queried once (`what owns Board and NnueEvaluator lifecycle in Searcher and UciApplication`) per this task's instruction. It returned a broad node listing rather than a focused ownership answer and did not surface anything beyond what direct source inspection above already established with exact line numbers; the source citations above are the authoritative basis for this document, not the graph query.

## 3. Ownership model

| Component | Owner | Lifetime |
|---|---|---|
| Immutable `NnueNetwork` | The generation run (loaded once, injected everywhere) | Whole run — many games |
| `NnueEvaluator` | One `Searcher` instance (`ADR-009`) | See resolution below |
| `Searcher` | `GameLoop`, one instance | See resolution below |
| `Board` | `GameLoop`, one instance per game | One game only — never reused across games |
| `TranspositionTable` | `Searcher`'s own default, or explicitly shared | Configurable; default recommendation below follows `ucinewgame` precedent |
| Killer moves / history heuristic | `Searcher`'s own internal arrays | Same lifetime as `Searcher` |
| RNG (for any future diversity mechanism) | `GameLoop` (or a `MoveSelector` component `GameLoop` owns) | Seeded explicitly per game; never global mutable state |
| Search budget (depth/nodes/time per move) | `GameLoop`, passed into each `search()` call | Per-ply, from run configuration |
| Per-game move/sample history | `GameLoop`'s own accumulating `GameRecord` | One game only |
| Adjudication state (consecutive-score counters, ply count) | `GameLoop` | One game only |

**Resolved: option A — one `Searcher` (and its one owned `NnueEvaluator`) plays both colors.** Nothing in `ADR-009` or in `Searcher`'s actual field layout requires or benefits from a second `Searcher`/`Evaluator` pair for the other color. `Board.getActiveColor()` already parametrizes which side a given `search()` call is answering for, and reusing one `Searcher` avoids constructing a second, redundant `NnueEvaluator` accumulator stack for the same `Board`. Option B (a separate `Searcher` per color) would still be valid under `ADR-009`'s literal wording, since nothing forbids it, but it has no identified benefit and a real cost — doubled evaluator memory for the same position — so it is rejected here on evidence, not intuition.

**What resets between games versus persists within a game, and the open question in between:**

- `Board`: never reused across games. A fresh `Board` (via `initWithFEN` on the starting position, or an opening-book FEN if one is ever added — out of scope here) is required per game, because `zobristSP`/`unmakeSP` only reset there, and reusing a `Board` across games would let one game's real-move history and repetition state bleed into the next game's threefold check.
- `Searcher`, `TranspositionTable`, killer moves, history heuristic: **within a game these persist for the whole game** (they are not per-move state). **Across games, this document recommends resetting them**, following the existing `ucinewgame`-clears-TT precedent `UciApplication.java` already establishes for the one analogous real-usage case in this codebase. This is stated as a recommendation, not a forced invariant: nothing in the engine requires it, and keeping a warm TT across many self-play games in one generation run is a legitimate throughput optimization a future implementation could choose instead. Whichever choice is made, it must be a named, recorded configuration field (see the `GameConfig.resetSearchStateBetweenGames` field in section 9), not an implicit accident of whatever the first implementation happens to do.
- `NnueEvaluator`: persists for the lifetime of its owning `Searcher` (per `ADR-009`), and needs no special per-ply reset logic beyond what `iterativeDeepening()` already does automatically (`evaluator.reset(board)` on every call, confirmed above).
- Immutable `NnueNetwork`: persists for the whole generation run, shared by reference, never mutated — this is already the existing convention everywhere else in the engine and nothing about self-play changes it.
- RNG: **must not persist as shared global state.** If any future diversity mechanism (section 7) needs randomness, its seed must be derived deterministically from a run-level seed plus a game index, so that a given `(run seed, game index)` pair always produces the same diversity decisions — the same discipline the trainer side already applies to its own seeds. This document does not choose the derivation formula; it only requires that seed derivation be explicit and recorded per game, not implicit.
- Adjudication state and per-game move/sample history: constructed fresh per game, owned entirely by `GameLoop`, never carried across games.

## 4. Game lifecycle

### 4.1 Repetition, fifty-move, insufficient material, checkmate, stalemate

All five are read directly from the persistent per-game `Board`, using its own existing methods, verified above: `isThreefoldRepetition()`, `isFiftyMoveRuleDraw()`, `isInsufficientMaterial()`, `isCheckmate()`, `isStalemate()`. None of these need new engine code. The game-ending check after each real move is: checkmate or stalemate first (these end the game immediately and unambiguously — see section 8's precedence rule), then threefold repetition, fifty-move rule, and insufficient material as draw conditions, checked in that order for determinism (an implementation may find more than one true simultaneously — e.g., a fifty-move-eligible insufficient-material position — and the order only matters for which termination reason gets recorded, not for the outcome, since all of these produce the same drawn-game outcome).

### 4.2 In-game history injection into search

This does not require a new API. As established in section 2, `Searcher`'s own `alphaBeta` reads `board.isRepetitionDraw()`, which scans the same `zobristStack` a persistent, game-long `Board` already carries real move history on. The requirement this places on `GameLoop` is negative, not additive: **do not construct a fresh `Board` per move, and do not unmake a real move once it has been played.** As long as `GameLoop` keeps pushing real moves via `makeMove()` onto one `Board` for the whole game, search's own internal two-fold detection sees that history automatically, with correct halfmove-clock windowing, exactly as it would in a normal UCI session receiving a `position startpos moves ...` command with a long move list.

### 4.3 The 768-entry safety rule, stated precisely

The combined count of (a) real moves already played in the current game and (b) whatever a single `search()` call at the current ply pushes recursively must never reach 768, or the `Board`'s `makeMove()` throws `ArrayIndexOutOfBoundsException` mid-search, aborting the game ungracefully.

From section 2's verified constants, a single worst-case search call can push approximately `MAX_PLY - 1 + MAX_CHECK_EXTENSIONS + MAX_Q_DEPTH = 127 + 16 + 6 = 149` entries, call it 150 with rounding for aspiration-window re-searches this document did not trace exhaustively. That leaves `768 - 150 = 618` as the raw ceiling on real-game-ply count before the very next search call is at risk, with zero margin for error or for a slightly deeper worst case than the traced constants suggest.

**Recommended safety rule:** `GameLoop`'s move-cap configuration must enforce a hard stop at a real-ply count comfortably below that ceiling — this document recommends no more than 500 total real plies (250 full moves per side) as a conservative default, leaving roughly 268 entries of headroom below the computed 618-ply raw ceiling, well beyond the ~150-entry worst case this document traced. The exact default is a configuration value (`GameConfig.maxPlies` in section 9), not hardcoded here, but it must never be configured above the computed safe ceiling, and a future implementation should re-derive that ceiling from whatever `MAX_PLY`/`MAX_CHECK_EXTENSIONS`/`MAX_Q_DEPTH` values are current at implementation time rather than copying this document's numbers forward blindly if those constants ever change.

**Rejected alternative:** periodically reinitializing `Board` mid-game (via a fresh `initWithFEN` from the current position) to free up array budget for very long games. This was considered and rejected, because `isThreefoldRepetition()` scans the whole array from index 0 — reinitializing mid-game would silently make any legitimate threefold claim spanning positions before the reinitialization point permanently undetectable. A move-cap is simpler, verifiably safe, and does not compromise the correctness of the one rule (threefold) that actually needs the full game history.

### 4.4 Adjudication precedence

See section 8 for the full three-way split (natural termination, configured adjudication, infrastructure termination). At the lifecycle level: after every real move, check natural termination first (checkmate/stalemate/threefold/fifty-move/insufficient-material, in the order given in 4.1); only if none of those apply, check configured adjudication (section 8.B); only if neither applies and the move-cap or an abort condition is hit, apply infrastructure termination (section 8.C). Natural termination always takes precedence over configured adjudication, and configured adjudication always takes precedence over the move-cap — a move-cap is a safety valve for games that would otherwise run forever, not a substitute for the other two.

## 5. Search-candidate contract

Confirmed from source (section 2): `SearchResult` (the return value of a completed `iterativeDeepening()` call) carries exactly one best move, one score, one PV, and instrumentation counters — no candidate list. `IterationListener.onIteration(IterationInfo)` fires once per `(depth, pvIndex)` pair, at `Searcher.java:555`, **before** the per-iteration abort check at `Searcher.java:558` — so a listener receives a callback for an iteration even when that same iteration was itself aborted, with no field on `IterationInfo` (`depth, seldepth, scoreCp, nodes, timeMs, hashfull, pv, multipv`) indicating completion status.

This means a future generator-facing candidate-set contract cannot be built on `IterationListener` as it exists today without an adapter. The contract this document specifies for that future adapter, not implemented here:

- **Complete root candidate set**: a candidate set for a given ply is defined as the set of `IterationInfo` values sharing the same `depth`, collected across `pvIndex` from `0` to `multiPV - 1`, **only if** the root search loop actually completed all `multiPV` iterations at that depth without the per-iteration abort flag (`RootResult.aborted`, private today, would need to be surfaced) being set on any of them. A future adapter must track this explicitly — it cannot infer it from `IterationInfo` alone.
- **Same completed search depth**: every candidate in a returned set must share the same `depth` value. A consumer must never combine candidates whose `IterationInfo.depth` differs, since that mixes moves evaluated to different search strength.
- **Aborted/incomplete iteration behavior**: if the root loop is aborted partway through a depth's `pvIndex` loop, the candidate set for that depth is discarded entirely, not partially returned. The generator falls back to the most recent complete depth's candidate set (which may be a shallower depth than the one that got interrupted).
- **Score perspective**: `IterationInfo.scoreCp` is from the side-to-move's own perspective at the root position that produced it (consistent with how `SearchResult.scoreCp` is documented and used elsewhere in this engine) — a future adapter must preserve this convention rather than reinterpreting it, and any consumer converting to an absolute (White-positive) convention must do so explicitly, once, at the point of consumption.
- **Centipawn versus mate score**: `IterationInfo.scoreCp` does not distinguish a mate score from a centipawn score by type — mate scores are encoded as large magnitude values near `MATE_SCORE` (per `Searcher.java`'s own `MATE_SCORE - MAX_PLY` window checks, e.g. lines 609, 1182, 1252). A future adapter exposing candidates to a generator must decode this distinction explicitly (mate-in-N versus a centipawn value) rather than passing the raw encoded score through unlabeled, since a training sample's label (section 6) needs to record which kind of score it is.
- **Bound/exactness status**: not currently exposed by `IterationInfo` at all. If a future generator design needs to distinguish an exact score from an alpha/beta-bound one (relevant to whether a candidate's score is trustworthy enough to sample), that is a new field the adapter would need to add — not something to implement in this document, but flagged here as a real gap rather than assumed away.
- **PV association**: `IterationInfo.pv` is the principal variation for that specific `(depth, pvIndex)` pair — a future adapter must keep a candidate's score and its own PV bound together as one unit, never mixing one candidate's score with another candidate's PV.
- **Candidate ordering**: `pvIndex` (zero-based, exposed as `multipv = pvIndex + 1`, one-based) is the search's own ranking at that depth — candidates should be consumed in that order, not re-sorted by score after the fact, since the search's own multi-PV ordering already reflects its ranking logic.
- **Deterministic tie handling**: not addressed by current search code, and out of scope for this document to invent. If two candidates report the same score at the same depth, a future generator's tie-breaking rule (e.g., prefer lower `pvIndex`, or a documented secondary criterion) is a decision for whichever issue implements the adapter, made explicitly and recorded, not left to whatever order happens to fall out of the search internals.

**The smallest seam needed later, not implemented now:** a `CompletedRootCandidateSet` adapter type wrapping `IterationListener`, which accumulates `IterationInfo` values per depth, tracks `RootResult.aborted` (requires surfacing that currently-private field, or an equivalent signal, to the listener), and only exposes a candidate set to its own caller once a full depth's `pvIndex` loop is confirmed complete. This document specifies its contract; it does not implement it.

## 6. Training-sample contract

For every emitted sample, per the task's own required field list:

- **Exact board state / FEN**: the exact position sampled, serialized as a FEN string (or an equivalent unambiguous encoding — the concrete choice is #220's job). Must be the position the search score below was actually computed for — the load-bearing invariant this whole section exists to protect.
- **Side to move**: part of the FEN already, but recorded explicitly enough that a consumer never has to re-derive it ambiguously.
- **Exact search evaluation associated with that state**: the score from a `search()` call whose **root position was this exact sampled position**, not a score computed for a different position along some walked continuation. See the key invariant below.
- **Score perspective**: side-to-move-relative, consistent with section 5's search-candidate contract — recorded explicitly, not left implicit, since #220/#210 both need to agree on this without guessing.
- **Game outcome perspective**: recorded separately from the search evaluation's perspective, and explicitly labeled as being from a fixed convention (e.g., always White's perspective, or always the perspective of whoever was to move in the sampled position — this document does not choose which; #220/#210 need one explicit choice, not an assumption that it matches the search-score perspective by coincidence).
- **Whether the position was actually played in the game**: a required boolean-equivalent field. For the initial design (see below), this is always true — every sample is on-trajectory — but the field exists so the contract does not silently break if a future design ever introduces an off-trajectory sample under a separately justified semantics.
- **Ply / game ID**: which ply within the game this sample came from, and which game (within a generation run) it belongs to — the latter is exactly the `game_id` grouping key #207 defines storage for.
- **Search budget metadata**: the depth/node/time budget the search that produced this sample's evaluation actually ran under, so a consumer can tell a shallow-budget label from a deep one.
- **Generator network identity**: which `.nnue` network (and ideally which engine build) produced this game, read once per generation run and carried onto every sample and every game record — needed by #210's provenance requirements and #212's future generator-selection policy.
- **Termination/adjudication relationship**: how this sample's position relates to how the game eventually ended — not a value that needs deciding per-sample right now, but the field must exist so a consumer can tell a sample taken near a natural checkmate from one taken near an adjudicated or capped ending, since those carry different label reliability.

**The key invariant, stated as the section itself requires:** the recorded search label must belong to the recorded position. A sample's evaluation score must come from a `search()` call whose root `Board` state is bit-for-bit the same position being recorded as the sample's FEN — never a root score attached to a different, later-walked position, and never a real game's outcome attached to a position the game did not actually reach, unless a future design explicitly and separately justifies that as a distinct, clearly-labeled kind of sample.

**On-trajectory sampling is the initial design.** For the reason the invariant above states precisely: sampling from positions the game actually played, and using the search score computed for that exact position (which `GameLoop` already has, from the move-selection search at that ply — see section 7), satisfies the invariant with no additional machinery. A future off-trajectory design (walking a PV to a different position and sampling there) is not ruled out forever, but it needs its own semantics for what evaluation and outcome mean for a position the game did not play, and that is explicitly not decided by this document.

**QuietWalk is out of scope for the initial contract, and here is why, precisely:** `DR-E1`'s original QuietWalk proposal walks a search's principal variation (or a short quiescence-only continuation) to a "tactically settled" position before recording it, because — unlike Stage 2's Stockfish-labeled corpus — self-play has no upstream guarantee that a sampled position is quiet. But a PV-walked position is, by construction, not the position the game actually played past its own next real move; the real game may go on to play something else entirely, especially once any move-selection diversity (section 7) is in play. Recording a walked position's FEN alongside the *root* search's score, or alongside the eventual *real game's* outcome, breaks the key invariant twice over: the score would belong to the root, not the walked position, and the outcome would belong to the real game's actual continuation, not the walked one. Fixing this needs a real decision — what does "quiet" mean for a position nothing in the trained network's own game reached, and whose evaluation and outcome would need their own justified definitions — not an assumption inherited from Stage 2's very different setup. Deferred, not rejected: it may return once that decision is made explicitly, in its own scoped follow-up.

## 7. Randomized move-selection semantics

This is deferred configuration, not a decision made here — `DR-E1` already rejected MCTS-style Dirichlet/visit-count noise as a category error for an alpha-beta engine (no visit-count distribution exists to perturb), and this document does not relitigate that. What follows is the contract shape a future diversity mechanism must satisfy, if and when one is built, so its output stays consistent with sections 5 and 6 above:

- **Eligible candidate set**: must be exactly the complete root candidate set section 5 defines — never a partial set from an aborted iteration, and never candidates mixed across different depths.
- **Probability/weight derivation**: not decided here. If a future mechanism derives selection weights from score margins (the `random_multi_pv`/`random_multi_pv_diff`-style approach `DR-E1` already points to as the applicable precedent, not MCTS-style noise), the exact formula and any temperature-like constant are research knobs, pinned per generation run as named configuration fields (section 9), never hardcoded — consistent with this document's own general position that empirical constants belong in configuration, not in this design record.
- **RNG ownership and seed derivation**: owned by `GameLoop` (or a `MoveSelector` component it owns), never global mutable state, seeded deterministically from a per-run seed plus the game index (per section 3) — so a given `(run seed, game index)` always makes the same diversity decisions, which is exactly the level of reproducibility `DR-E1` already commits to for self-play (statistical, not bit-exact across different seeds, but deterministic given the same one).
- **Metadata recorded**: whichever move-selection mechanism and seed actually applied at each ply where diversity was used, so a consumer or a later reproducibility check can tell a diversity-selected move from an ordinary best-move choice.
- **Fewer than N eligible candidates**: if a diversity mechanism is configured to pick among the top N candidates but a given ply's complete candidate set (per section 5's completeness rule) has fewer than N members — a shallow-searched position, a position with few legal moves, or an early-aborted MultiPV loop — a future implementation must define, explicitly, whether it falls back to the best move, samples from however many candidates actually exist, or treats that ply as ineligible for diversity entirely. Not decided here; must be a named, recorded configuration behavior, not an implicit accident.
- **Mate / forced-line handling**: a position with a forced mate, or a single legal move, or a position with no meaningful ranking among candidates (all scored at essentially the same forced outcome) should not have diversity applied — a future mechanism must define an explicit threshold or condition for "this ply is not eligible for randomized selection," not silently randomize among moves that are not meaningfully different in outcome.

## 8. Adjudication contract

**A. Natural chess termination** (always checked first, per section 4.4): checkmate, stalemate, threefold repetition, the fifty-move rule, insufficient material. All five are read directly from `Board`'s own existing, verified methods (section 4.1) — no new engine logic needed.

**B. Configured adjudication** (checked only if A does not apply): a sustained one-sided score threshold (e.g., N consecutive plies where the evaluation exceeds a configured magnitude in one side's favor), and a required minimum search-quality/budget for those observations to count (a shallow or aborted search's score should not, by itself, trigger adjudication). **This document does not pick the numeric threshold, the consecutive-observation count, or the minimum search-quality requirement.** Those are exactly the kind of empirical constants this document has consistently deferred to configuration rather than inventing without evidence — they become named fields in `GameConfig` (section 9), and their concrete values are a future, preregistered generator-run choice, not a value fixed by this design record.

**C. Infrastructure termination** (checked last, and only as a safety valve): the move-cap from section 4.3, a search abort (external stop signal, engine-level failure), or an unhandled exception during generation. **These must never be silently mapped to a drawn-game outcome.** A game that hits the move-cap, or aborts due to an infrastructure failure, has an outcome of "unknown" or "unresolved," distinct from a genuine drawn outcome under category A or B — collapsing the two would corrupt the label of every sample from that game, since a drawn-outcome label and an unresolved-outcome label carry different (and non-interchangeable) evidentiary weight for training.

**Outcome and termination reason are separate concepts, by design, not by omission**: `GameOutcome` records what result a consumer should treat this game as having (win/loss/draw/unresolved, from a fixed perspective), while `TerminationReason` records why the game actually stopped (which of A/B/C, and which specific condition within it). A `GameOutcome` of "drawn" can arise from threefold, fifty-move, or insufficient material (all category A), and a consumer needing to know which one occurred reads `TerminationReason`, not `GameOutcome`. An "unresolved" `GameOutcome` always corresponds to a category-C `TerminationReason`, and that pairing must be the only one infrastructure termination is allowed to produce.

## 9. Conceptual type/field table

Names may differ once implemented against this repository's own conventions; the fields and their meanings are the contract. No byte layout, no ordering, no framing — that is #220's job.

### `GameConfig`
Per-generation-run configuration, pinned once at the start of a run.

| Field | Meaning | Units / values | Perspective | Required | Invariant |
|---|---|---|---|---|---|
| `generatorNetworkPath` | Which `.nnue` network this run generates with | file path / identity | n/a | required | must resolve to the same network identity recorded in every game's `GameIdentity` |
| `maxPlies` | Hard move-cap per game | integer, real plies | n/a | required | must never exceed the safe ceiling section 4.3 derives from `Board.UNMAKE_POOL_SIZE`, `MAX_PLY`, `MAX_CHECK_EXTENSIONS`, `MAX_Q_DEPTH` at implementation time |
| `searchBudget` | Depth/node/time budget applied per move | one of: fixed depth, fixed nodes, fixed time | n/a | required | must be recorded on every emitted sample (section 6) |
| `resetSearchStateBetweenGames` | Whether TT/killers/history reset at each new game | boolean | n/a | required | recommended default: true, per section 3's `ucinewgame` precedent; explicit either way |
| `adjudicationConfig` | The category-B thresholds from section 8 | (deferred; see below) | n/a | required once B is used | numeric values are a future preregistered choice, not fixed here |
| `diversityConfig` | The section-7 move-selection mechanism and its constants, if enabled | (deferred; see section 7) | n/a | optional | absent means no diversity — every ply uses the search's own best move |
| `runSeed` | Root seed for all per-game RNG derivation | integer | n/a | required if `diversityConfig` is set | must be recorded so a run's diversity decisions are reproducible given the same seed |

### `GameIdentity`
Identifies one game within a run.

| Field | Meaning | Units / values | Perspective | Required | Invariant |
|---|---|---|---|---|---|
| `gameId` | Grouping key for every sample from this game | integer or equivalent, unique within the ingestion run (#207's `game_id` semantics) | n/a | required | never reused within one ingestion run; presence vs. absence must be distinguishable (#207) |
| `generatorNetworkIdentity` | Which network produced this specific game | copied from `GameConfig.generatorNetworkPath`'s resolved identity | n/a | required | must match across every sample in the game |
| `gameSeed` | This game's derived RNG seed, if diversity was used | integer, derived from `runSeed` + game index | n/a | required if diversity used | deterministic given `(runSeed, gameIndex)` |

### `SearchCandidate`
One entry in a complete root candidate set (section 5), for a given ply.

| Field | Meaning | Units / values | Perspective | Required | Invariant |
|---|---|---|---|---|---|
| `depth` | Completed search depth this candidate belongs to | integer, plies | n/a | required | every candidate in one set shares this value |
| `rank` | Ordering within the candidate set at this depth | integer, from `pvIndex`/`multipv` | n/a | required | reflects search's own multi-PV ranking, not a post-hoc re-sort |
| `move` | The candidate move | move encoding (#220's concern for wire form) | n/a | required | must be legal in the position this candidate set was generated for |
| `scoreKind` | Centipawn or mate | enum: `cp` \| `mate` | n/a | required | decoded explicitly from the raw encoded score, per section 5 |
| `score` | The evaluation | centipawns, or mate-in-N plies | side-to-move-relative | required | must not be mixed with another candidate's `pv` |
| `pv` | Principal variation for this candidate | ordered move list | n/a | required | bound to this candidate's own `score`, never another's |
| `complete` | Whether this candidate's iteration finished without abort | boolean | n/a | required | a candidate set with any `complete = false` member is discarded per section 5, not partially used |

### `PlayedMoveDecision`
The move `GameLoop` actually plays at one ply.

| Field | Meaning | Units / values | Perspective | Required | Invariant |
|---|---|---|---|---|---|
| `chosenMove` | The move actually played | move encoding | n/a | required | must be a member of the candidate set it was chosen from |
| `selectionMechanism` | Best-move, or a named diversity mechanism | enum / identifier | n/a | required | if a diversity mechanism, `selectionSeed` must also be present |
| `selectionSeed` | The RNG draw (if any) that produced this choice | integer or equivalent | n/a | required if diversity used | reproducible given `GameIdentity.gameSeed` and ply index |

### `TrainingSample`
One emitted sample (section 6).

| Field | Meaning | Units / values | Perspective | Required | Invariant |
|---|---|---|---|---|---|
| `fen` | Exact position sampled | FEN or equivalent | includes side to move | required | must be the exact root position the associated `evalScoreKind`/`evalScore` were computed for — the key invariant |
| `gameId` | Which game this sample belongs to | from `GameIdentity.gameId` | n/a | required | grouping key #207/#210 both consume |
| `ply` | Ply index within the game | integer | n/a | required | monotonically increasing within one game |
| `onTrajectory` | Whether this position was actually played in the game | boolean | n/a | required | always `true` in the initial design (section 6); exists for a future off-trajectory design |
| `evalScoreKind` | Centipawn or mate | enum: `cp` \| `mate` | n/a | required | same convention as `SearchCandidate.scoreKind` |
| `evalScore` | The search evaluation for this exact position | centipawns, or mate-in-N | side-to-move-relative | required | must come from a search whose root was this exact `fen`, never a different position |
| `searchBudget` | The budget the evaluating search actually ran under | copied from `GameConfig.searchBudget` at this ply | n/a | required | lets a consumer distinguish shallow from deep labels |
| `gameOutcome` | The eventual outcome of the game this sample came from | see `GameOutcome` below | a fixed, explicitly stated perspective, not assumed to match `evalScore`'s | required | recorded once per game, copied onto every sample from it |
| `outcomePerspective` | Which perspective `gameOutcome` is stated from | enum, e.g. `white` \| `sideToMoveAtSample` | n/a | required | must be explicit; #220/#210 must not have to guess |
| `terminationReason` | How the game this sample came from actually ended | see `TerminationReason` below | n/a | required | recorded once per game, copied onto every sample |
| `generatorNetworkIdentity` | Which network generated this sample's game | from `GameIdentity` | n/a | required | provenance for #210/#212 |

### `GameOutcome`
| Value | Meaning |
|---|---|
| `whiteWin` | White won, under a natural or configured-adjudication termination |
| `blackWin` | Black won, under a natural or configured-adjudication termination |
| `draw` | Drawn, under any category-A or category-B condition |
| `unresolved` | Category-C infrastructure termination — never conflated with `draw` |

### `TerminationReason`
| Value | Category | Meaning |
|---|---|---|
| `checkmate` | A | |
| `stalemate` | A | |
| `threefoldRepetition` | A | |
| `fiftyMoveRule` | A | |
| `insufficientMaterial` | A | |
| `adjudicatedScore` | B | sustained one-sided score threshold met |
| `moveCap` | C | `GameConfig.maxPlies` reached |
| `searchAbortOrFailure` | C | external stop signal or an unhandled failure during generation |

### `GameRecord`
The full record of one game, from which `TrainingSample`s are drawn.

| Field | Meaning | Units / values | Perspective | Required | Invariant |
|---|---|---|---|---|---|
| `identity` | `GameIdentity` for this game | see above | n/a | required | |
| `config` | The `GameConfig` this game ran under | see above | n/a | required | |
| `playedMoves` | Ordered list of `PlayedMoveDecision` | see above | n/a | required | length equals the game's real ply count, bounded by `GameConfig.maxPlies` |
| `outcome` | `GameOutcome` | see above | see `TrainingSample.outcomePerspective` | required | |
| `terminationReason` | `TerminationReason` | see above | n/a | required | must pair correctly with `outcome` (an `unresolved` outcome only ever pairs with a category-C reason) |
| `samples` | The `TrainingSample`s drawn from this game | see above | n/a | required | every sample's `onTrajectory` must be verifiable against `playedMoves` |

## 10. Cross-issue contract exported

**For #220 (wire format):** the semantic fields to encode are exactly those in section 9's type table — `GameConfig`, `GameIdentity`, `SearchCandidate` (if candidate-level data is ever persisted, not just the chosen move), `PlayedMoveDecision`, `TrainingSample`, `GameOutcome`, `TerminationReason`, `GameRecord`. In particular: `evalScoreKind`/`evalScore` must be encodable as a tagged union (centipawn or mate, never conflated), `outcomePerspective` must be an explicit encoded field, not inferred, and `GameOutcome`/`TerminationReason` must be two separate encoded fields, never collapsed into one.

**For #207 (shard/game-ID storage):** `gameId` must be preservable as an `Optional[int]`-equivalent with real presence/absence semantics (a real ID and "no ID" must be distinguishable — zero must not mean both), and must be understood as unique only within one named ingestion run, not globally, matching this document's `GameIdentity.gameId` definition exactly.

**For #210 (ingestion):** every provenance field a dataset-generation manifest needs is already named here — `generatorNetworkIdentity`, `GameConfig` (recorded once per run), and per-game `outcome`/`terminationReason` pairs. The grouped-by-game split requirement follows directly from `TrainingSample.gameId`: any train/validation split over ingested Stage-3 data must group by that field, never shuffle individual samples across it, since section 6 established that samples from one game are not independent of each other.

**For #211 (deterministic fixtures):** the type table in section 9 is directly testable with hand-authored fixtures — a fixed `GameRecord` with a small number of `playedMoves` and `samples`, exercising at minimum: a `mate`-kind score, a `cp`-kind score, an `unresolved` outcome (paired with `moveCap` or `searchAbortOrFailure`), a `draw` outcome under each of the five category-A reasons, and an `onTrajectory = true` sample whose `evalScore` is checked against the exact `fen` it claims to belong to (the key invariant, made into an assertion). None of these require a live self-play run — they are fixtures against this document's own types.

**For #212 (future generator-selection policy):** the generator-identity and evidence fields a selection policy can rely on are `GameIdentity.generatorNetworkIdentity` and `GameConfig.generatorNetworkPath`, both required on every game — a selection policy can trust that every ingested game already carries an unambiguous, non-optional record of which network produced it, and #210's separate assessment status/outcome model (from its own rewritten scope) is what a selection policy reads to decide whether a given generator's output is trusted, independent of this document's own fields.

**Explicitly, deliberately unresolved by this document:**

- The exact numeric values for `GameConfig.maxPlies` (beyond the safe-ceiling bound derived in section 4.3), `adjudicationConfig`'s thresholds, and `diversityConfig`'s weighting formula and constants. All are named configuration fields; none are pinned here.
- Whether `resetSearchStateBetweenGames` defaults to true or false in the eventual implementation — a recommendation is given (true), not a mandate.
- QuietWalk's own semantics, deferred per section 6, not designed here at all.
- Whether `SearchCandidate`-level data (not just the chosen move) is ever actually persisted to disk, or only used transiently inside `GameLoop` to make a selection — that is a cost/value tradeoff for #220 to weigh, not a decision this document makes.
- The exact adapter implementation surfacing `RootResult.aborted` (or an equivalent completion signal) to a future `IterationListener`-based candidate-set consumer — section 5 specifies its required contract, not its Java implementation.

## 11. Failure-mode review

Each scenario the task specified, checked against the contract above:

- **Repetition after long game history:** handled correctly by section 4.2's resolution — a persistent `Board` carries full history, so `isThreefoldRepetition()` (whole-array scan) and `isRepetitionDraw()` (halfmove-clock-windowed scan) both see every real move played, regardless of game length, as long as the move-cap (section 4.3) keeps the game within the 768-entry budget.
- **Game history plus deep search approaching the storage bound:** exactly the scenario section 4.3 exists to prevent — the recommended 500-ply cap leaves roughly 268 entries of headroom below the 618-ply raw ceiling this document computed from `MAX_PLY`/`MAX_CHECK_EXTENSIONS`/`MAX_Q_DEPTH`, well above the traced ~150-entry worst-case single-search push.
- **Odd/even ply perspective:** `TrainingSample.evalScore` is explicitly side-to-move-relative (section 6, section 9), and `fen` already encodes side to move — a consumer never has to infer perspective from ply parity, and `outcomePerspective` is a separate, explicit field precisely so game-outcome perspective is never assumed to track side-to-move parity either.
- **Mate scores:** `evalScoreKind`/`SearchCandidate.scoreKind` make centipawn and mate scores a tagged, never-conflated distinction (section 5, section 9) — a consumer cannot accidentally treat a mate-in-3 encoding as a centipawn value.
- **Incomplete MultiPV iteration:** section 5's `complete` field and the "discard the whole set, not part of it" rule handle this directly — a partially-completed depth's candidates never leak into a returned candidate set.
- **Search abort:** category C in section 8 (`searchAbortOrFailure`), paired only with an `unresolved` `GameOutcome`, never a `draw` — the outcome/termination-reason separation exists specifically so this case cannot be silently misrecorded as a drawn game.
- **Promotion:** not a new concern for this contract — `Board.makeMove()` already handles promotion via its existing flag encoding (confirmed in section 2's `makeMove` reading), and a promoted position's FEN correctly reflects the promoted piece with no special handling needed at the `TrainingSample`/`GameRecord` level.
- **Castling:** likewise already handled by `Board`'s existing `FLAG_CASTLING_K`/`FLAG_CASTLING_Q` machinery (confirmed in section 2); no new contract concern.
- **En passant:** likewise already tracked by `Board`'s existing `epTargetSquare` field, which is part of the FEN a `TrainingSample.fen` would record — no new contract concern.
- **Natural draw versus adjudicated draw:** both produce `GameOutcome.draw`, but `TerminationReason` distinguishes exactly which of the five category-A reasons or the one category-B reason applies (section 8) — a consumer needing to weight adjudicated draws differently from natural ones (adjudicated draws rest on a threshold choice, not a legal chess rule) can do so from `terminationReason` alone.
- **Move cap:** produces `GameOutcome.unresolved` paired with `TerminationReason.moveCap` (section 8), never `draw` — explicitly the scenario the outcome/termination-reason separation exists to get right.
- **Randomized candidate selection with fewer candidates than requested:** section 7 requires this be an explicit, named, recorded behavior (fallback to best move, sample from however many exist, or mark the ply ineligible) — not decided here, but the contract forbids silently doing any of these without recording which one happened via `PlayedMoveDecision.selectionMechanism`.
- **Same position repeated within one game:** handled by the same mechanism as ordinary repetition (first bullet above) — `zobristStack` records every occurrence regardless of how many times one exact position recurs, and `isThreefoldRepetition()`'s per-hash counting handles this correctly by construction.
- **Multiple games generated in one run:** `GameIdentity.gameId` is explicitly scoped as unique only within one ingestion run (section 9, exported to #207 in section 10), and every `GameConfig`/RNG-seed field is explicitly per-run or per-game, never accidentally shared mutable state across games in the same run (section 3's RNG-ownership rule exists specifically for this).
- **Resumed generation:** not fully solved by this document — `gameId` uniqueness "within a named ingestion run" implies a resumed run needs its own explicit collision/remapping rule across the boundary between the original run and its resumption, which section 10 already flags as #210's responsibility (game-ID preservation/remapping across multiple input files or a resumed run), not solved here. Recorded as a real, open dependency, not silently assumed away.
- **Generator network mismatch:** `GameIdentity.generatorNetworkIdentity` is required on every game and must match `GameConfig.generatorNetworkPath`'s resolved identity (section 9's invariant column) — a future ingestion or selection-policy consumer detecting a mismatch between a claimed identity and an actual loaded network's identity has an explicit field to check against, rather than having to infer network identity from file naming or timing coincidence.

## 12. Explicit non-scope

- No binary layout, byte offsets, endianness, framing, magic bytes, or version bytes — entirely #220's concern.
- No implementation of `GameLoop`, `Searcher` adapters, or any Java code. This document produces a contract other issues implement against.
- No ingestion pipeline code — #210's concern, informed by section 10's export.
- No self-play run, no dataset generation, no training, no SPRT — none of this document's claims were verified by running any of them; every claim is either read directly from existing source (cited with file and line) or explicitly marked as an open, undecided question.
- No wire-format testing or fixture creation — #211's concern, informed by section 10's export.
- No numeric values for adjudication thresholds, move-selection weighting constants, or the exact move-cap default beyond the safe upper bound this document derives — all are named configuration fields with values deferred to a future, evidenced, per-run choice.
- QuietWalk's semantics — deferred per section 6, not designed at all here.
- The `RootResult.aborted`-surfacing adapter's actual Java implementation — section 5 specifies its required contract only.
- `SearchCandidate` persistence to disk versus transient in-memory use during move selection — left as an open question for #220 to weigh (section 10).

## 13. Recommended next task

**#220 (VSPR exchange contract) only, now that this document gives it a stable semantic contract to encode against.** Per the governing task's own framing, #220 should proceed once #209's semantics are stable — they are stable as of this document, modulo the explicitly-unresolved items in section 10's closing list, none of which block #220 from starting on the parts of the wire format that do not depend on them (framing, versioning, endianness, the tagged-union encoding for `evalScoreKind`, the separate encoding of `GameOutcome` and `TerminationReason`). #220 should treat this document's section 9 type table as its required field list and should not invent additional semantic fields beyond it without a corresponding update here first, so the two documents do not drift apart the same way two independently-written VSPR decoders would.

Not recommended next: any implementation work on `GameLoop`, ingestion, or a bounded generator/CLI — all remain blocked on #220 closing first, per #209's and #210's own stated dependency order from the previous reconciliation turn.
