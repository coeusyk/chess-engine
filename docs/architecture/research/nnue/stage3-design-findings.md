# Stage-3 self-play design findings (recorded, not implemented)

Findings carried forward from the Phase-15 recovery audit and its adversarial review, both
temporary out-of-repository deliverables. Recorded here so they are not lost to chat history,
per this project's convention of preserving reusable engineering findings under
`docs/architecture/research/nnue/`. **Nothing in this file has been implemented.** No GitHub
issue was mutated to produce it; #209-#212 keep their existing state and text until an
explicitly authorized issue-hygiene pass revises them.

This is a findings record, not a design document — it exists so the next person who scopes
Stage-3 self-play (issues #209-#212 and their dependents) starts from these conclusions instead
of re-deriving or re-litigating them.

## Findings

1. **Sample on the actual played trajectory first, not QuietWalk.** A PV walked from a search
   root need not correspond to the game's real continuation, especially once any randomized
   move selection is introduced. Prefer sampling eligible positions from the trajectory the game
   actually played, storing the search score for that exact state, over a synthetic quiet-walk
   position.

2. **An off-trajectory position's game outcome is a counterfactual label, not an observed one,
   unless separately justified.** If a sampled position was not reached by the game that
   produced the outcome label (for example, a QuietWalk continuation the real game did not
   play), attaching that outcome to it is not evidence about that position. This must be an
   explicit, justified exception if it is ever done, not an implicit default.

3. **A complete MultiPV candidate-set contract is needed before any diversity/selection design.**
   `SearchResult` (`engine-core/src/main/java/coeusyk/game/chess/core/search/SearchResult.java`)
   returns one best move/score/PV; other candidates surface only through `IterationListener`,
   which is invoked before the aborted-iteration check and does not itself establish a
   completed, same-depth top-N set. A move-selection or diversity design needs an explicit "last
   complete root candidate set" contract (or a narrower fixed-depth adapter with defined
   incomplete-set behavior) — not UCI-text scraping, not mixing candidates from different depths.

4. **The GameLoop must inject prior in-game positions into each move's search-time repetition
   history, not just adjudicate at the end.** `Board.isRepetitionDraw()`
   (`engine-core/src/main/java/coeusyk/game/chess/core/models/Board.java:1104`) is a two-fold
   heuristic for use inside search (`alphaBeta`), distinct from game-level threefold
   adjudication. For a self-play GameLoop's own search calls to score repetitions correctly
   mid-game, positions already played earlier in the game need to be present in the position
   history the search consults, not just checked afterward by the game-level rule.

5. **Retained game history and search share one concrete, named bound: `Board`'s 768-entry
   history/unmake arrays.** `Board.UNMAKE_POOL_SIZE = 768` sizes both `zobristStack` and
   `unmakePool` (`Board.java:62,124,129`). A self-play game that retains its own history in the
   same `Board` instance search recurses into shares this bound with search depth — the two are
   not independent budgets. Any GameLoop design needs an explicit accounting of how much of the
   768 entries game history is expected to consume versus how much search depth needs, not an
   assumption that an indefinitely growing game `Board` is safe.

6. **The adjudication contract needs six cases enumerated, not three.** Natural terminal states
   (checkmate/stalemate), threefold repetition, the 50-move rule, and insufficient material are
   all standard chess draw/end conditions that must be checked; a configured score-adjudication
   threshold and a move-cap/abort policy are additional, non-standard policy the generator
   itself defines. A move-cap or search abort is not automatically a draw and must not be
   silently treated as one. All six must be pinned before generation, not deferred as "the
   experiment will decide."

7. **Train/validation splits must be grouped by game, not by row.** `combine_and_split()`
   (`trainer/scripts/train_candidate_net.py`) currently shuffles individual position records
   without regard to which game produced them. Zero duplicate FENs across a split does not
   prevent adjacent, highly-correlated positions from the same self-play game landing on both
   sides of the split — Stage-3 self-play data needs a grouped-by-game splitting key before this
   is safe, unlike the current Lichess/Stockfish-labeled corpus where no single "game" groups
   positions.

8. **Deterministic codec tests belong in PR CI; statistical self-play quality studies do not.**
   Fixed hand-authored records with known encoded bytes and known decoded values is a legitimate
   deterministic PR-CI contract test (Java/Python golden-vector compatibility, malformed/
   truncated/version-mismatch rejection). Regenerating random self-play games and asserting
   byte-identical or distributionally-stable output is a different guarantee with a nonzero
   false-failure rate at any fixed significance level — that belongs in an opt-in, preregistered
   statistical study (explicit sample size, seed policy, tolerance, false-positive budget), never
   in ordinary per-PR CI.

9. **Generator assessment state must not be represented by a boolean that reads as approval.**
   `generator_validated=true` currently means "a promotion decision was recorded" — including a
   **rejected** decision. A boolean named this way invites a later consumer to treat any
   recorded assessment as approval to use that generator. Represent assessment-performed and
   assessment-outcome as separate, explicitly named states (with an explicit unknown/unassessed
   default), not one boolean that conflates "we looked" with "it passed."

## Status

Descoped from every issue currently proposing to build on it (#209, #210, #211, #212 and their
dependents) until those issues are explicitly revised. This file records the findings only; the
issue text itself is unchanged, per this task's explicit "do not mutate GitHub issues" instruction.
