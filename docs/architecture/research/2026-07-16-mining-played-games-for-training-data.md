# Architecture Note: Mining Played Games (SPRT/Gauntlet) as Training Data

**Date:** 2026-07-16
**Question:** E-5's SPRT run is producing ~44% draws. A draw doesn't mean every
intermediate position was objectively equal — should Vex mine its own already-played
SPRT/gauntlet games (not just dedicated self-play) as a training-data source, with
offline Stockfish re-analysis? Research-only pass; no roadmap change unless a
compelling architectural reason emerges.

**Assumption stated up front:** this note treats "SPRT/gauntlet games" as a distinct
data source from DR-E1's Stage 3 self-play (dedicated `GameLoop` generation, not yet
built) — the games in question already exist as a side effect of validation, not of a
purpose-built generator. If that's not the intended scope, the analysis below changes.

## 1. Is this already covered?

**Not by DR-E1.** DR-E1 (`docs/architecture/research/DR-E1-self-play-data-generation.md`)
designs Stage 3 as *dedicated* self-play generation via a new `GameLoop`/`MoveSelector`,
and explicitly keeps SPRT "entirely separate" from data generation (§0 point 4, §17
"Alternatives Rejected": a match-testing harness "already redundant with Vex's existing
SPRT infrastructure for a different, already-served purpose"). DR-E1 never considers
*reusing* SPRT/gauntlet games themselves as a corpus — it assumes fresh generation.

**Partially covered by issue #181**, but narrowly. #181 ("SPRT PGN post-processing
pipeline") already proposes mining SPRT PGNs for (a) quiet draw positions → the
**classical** Texel corpus, labeled by Stockfish, and (b) a blunder corpus. The owner's
own 2026-07-07 comment confirms it's independent of the NNUE roadmap — its consumer is
`engine-tuner`, not the trainer's `DatasetProvider`/mmap-shard/manifest pipeline. #181
also doesn't address decisive/adjudicated games, storage strategy, or label schema
beyond a flat EPD file.

**Not covered anywhere:** applying #181's underlying idea (mine already-played games,
re-analyze with Stockfish) to the **NNUE trainer's own pipeline** — i.e., a
`DatasetProvider` that ingests PGNs into `PositionRecord`/`PositionLabel` shards the
same way Stage 1/2 do. `NNUE_TRAINER_ARCHITECTURE.md` §9's own docstring for the
Stockfish labeling driver states this as a known, deliberately deferred v1 gap: **"No
PGN ingestion... no Java↔Python bridge to `PgnExtractor`/`PositionLoader`"** — named as
a limitation, not a rejection. This is the actual gap the user's question falls into.

## 2. How other projects handle it

- **Stockfish/nnue-pytorch — the single strongest precedent for this idea.** Current
  (2022–present) Stockfish default nets are trained primarily on **converted Leela
  Chess Zero self-play data** — a completely different engine's games — not fresh
  Stockfish-generated self-play (`robotmoon.com/nnue-training-data`: "Converted from
  Leela training data into the binpack data format"). Community tooling
  (`linrock/lc0-data-converter`, `linrock/nnue-data`) converts and filters this into
  subsets for training. Older Stockfish nets (2020–2021 era) used `gensfen`
  (fixed-depth self-play from a random book), but the field's own trajectory moved
  *toward* reusing another source's played games, not away from it. This directly
  validates that mining an engine's own real games (SPRT/gauntlet, a far closer source
  than a different engine's self-play) is not a fringe idea.
- **A cited, quantitative quiet-position filter exists.** "Study of the Proper NNUE
  Dataset" (arXiv:2412.17948) filters on two margins: `|static_eval − quiescence_eval|
  ≤ 60cp` and `|static_eval − negamax_eval| ≤ 70cp`, plus excluding any position in
  check. This is a direct, literature-backed answer to "which positions to keep,"
  reusable as-is for filtering mined SPRT positions.
- **Leela Chess Zero:** training data is fresh self-play only; no evidence of reusing
  test-match/gauntlet games as a training source (`lczero.org/dev/wiki/project-history`,
  `technical-explanation-of-leela-chess-zero`). Confirms DR-E1's Stage 3 framing (fresh
  self-play, AlphaZero-style) is correctly modeled on lc0 — but lc0 is not a precedent
  *for* mining played test games.
- **Berserk, Ethereal, Koivisto:** all follow the standard `gensfen`-style approach —
  random opening book + fixed-depth self-play (talkchess "NNUE training set
  generation" thread: "the usual approach"). Koivisto 5.0 specifically bootstraps
  training data from its *own previous version's* self-play (`github.com/Luecx/Koivisto`
  releases) — iterative self-play, not gauntlet-game reuse. No evidence any of these
  three specifically mine tournament/gauntlet games as a distinct source.
- **Ceres:** primarily consumes lc0-compatible training data/networks; no distinct
  data-mining precedent found beyond what lc0 already provides.
- **KataGo — directly answers "a draw doesn't mean every position was equal."**
  KataGo's "Short-term Value and Score Targets" trains on **exponentially-weighted
  short-horizon MCTS value**, not only the final game outcome — three auxiliary
  targets at roughly 6/16/50-turn horizons (`KataGoMethods.md`). This measurably
  improves value-loss over training on final outcome alone. **This is architecturally
  the same idea as Vex's already-planned `λ`-blend** (`wdl_value = λ · sigmoid(cp/K) +
  (1−λ) · game_result`, PRD §3, DR-E1 §7): when `λ > 0`, a drawn game's positions are
  *not* uniformly labeled 0.5 — the position's own search score dominates the blend.
  **KataGo is independent validation that this already-designed mechanism is the
  right one, not evidence that a new mechanism is needed.**
- **AlphaZero:** pure fresh self-play, resignation calibrated to a false-positive
  bound; DR-E1 §0/§6.1 already cites this correctly. No new information here.
- **"TorchChess":** resolves to `torchchess-elo800`, a hobbyist ~800-Elo Python/PyTorch
  toy engine — not a meaningful precedent for this decision, noted for completeness.

## 3. Should SPRT/self-play games be mined regardless of result?

| Game ending | Mine? | Why |
|---|---|---|
| Decisive (checkmate) | Yes | Highest-confidence label; final result and Stockfish re-analysis should agree on most positions. Standard practice everywhere surveyed. |
| Adjudicated (score threshold sustained) | Yes, cautiously | The adjudication itself is Vex's own score, not ground truth — label with Stockfish re-analysis, not the adjudicated result, exactly as #181 already mandates ("Do NOT use Vex's own eval as the labeling oracle"). |
| Draw (any cause) | Yes | This is the user's core insight, and it's correct: a 50/50 final-result label would be actively wrong for a position where one side had a large missed advantage. The `λ`-blend already handles this *if* the position carries a real search-score label alongside the game result — which for mined games, must come from Stockfish re-analysis (Vex's own live-game score is not a reliable oracle for a position it may have misjudged). |
| Repetition / 50-move / insufficient material | Yes, same as any draw | No special handling beyond standard draw treatment — the game-ending mechanism doesn't change whether an intermediate position was quiet and well-labeled. |
| Perpetual check | Filter out the checking sequence itself (in-check positions are excluded by the quiet-position filter above regardless), but earlier quiet positions in the same game remain minable. |

**Net conclusion: yes, mine regardless of result** — but every position needs its own
Stockfish label; the *game result* is one input to the λ-blend, never the sole label.

## 4. Should offline Stockfish re-analysis become a pipeline stage?

- **Every position?** No — wasteful and contrary to every surveyed precedent, all of
  which filter to quiet positions first.
- **Only large evaluation swings?** This finds *blunders* (issue #181's existing
  `BlunderCorpus` mode already does this), but blunder positions are exactly the
  *tactically unstable* positions the quiet-position literature says to exclude from
  the **training** corpus (they're diagnostic material, not NNUE training material).
- **Only candidate blunders?** Same issue — valuable for `sprt_blunders.epd`-style
  diagnostics (already #181's scope), not for the trainer's quiet-position corpus.
  Keep these as two separate outputs, matching #181's own existing two-mode design.
- **Only quiet positions?** Yes — this is the correct filter for the **trainer**
  corpus, using the arXiv paper's concrete margins (§2 above) rather than inventing new
  thresholds.
- **Sampled every N plies?** Complementary, not a substitute — bounds corpus size and
  avoids near-duplicate adjacent positions from the same game; combine with the quiet
  filter, don't replace it.

**Recommended split:** quiet positions → NNUE training corpus (Stockfish-labeled);
large-swing positions → diagnostic blunder corpus (already #181's scope, unrelated to
training). Two outputs, one filtering pass — not two separate pipelines.

## 5. Label strategy

Store all of: original Vex eval (context/diagnostic value, e.g. spotting systematic
under/over-estimation), Stockfish eval (the actual training oracle — #181's own "NOT
Vex's own eval" rule applies here identically), WDL outcome (game result, one λ-blend
input), game result label. **Confidence metadata:** not literally required by any
surveyed precedent as a stored field — KataGo's uncertainty modeling is a *runtime*
MCTS mechanism, not a stored label; recommend deferring confidence metadata until a
concrete consumer needs it (YAGNI), rather than adding an unused field now.

This maps directly onto Vex's *existing* `PositionRecord`/`PositionLabel` shape
(`eval_cp` + `wdl` fields already exist, per `NNUE_TRAINER_ARCHITECTURE.md`) — no new
fields required for the core case; only a new `DatasetProvider` implementation that
populates them from PGN+Stockfish instead of from a text file or a live UCI driver.

## 6. Storage: full game first, or immediate position extraction?

**Preserve the full PGN first**, extract positions in a separate offline step.
Rationale: extraction criteria (quiet-position margins, N-ply sampling, blunder
thresholds) are exactly the kind of thing this project has already revised more than
once (`SearchRegressionTest`'s own decades-long history of threshold tuning is the
closest local precedent) — re-deriving positions from a preserved PGN is free;
re-deriving them from already-extracted, already-filtered positions is not possible if
the filter was wrong. This also matches the project's own existing storage convention
(gitignored raw artifacts + a committed summary/manifest, per E-2's and E-4's
"Storage convention" sections) — PGNs are the raw artifact, extracted shards are the
derived, regenerable output.

## Recommended Approach

A new `DatasetProvider` implementation (call it `PgnGameProvider` or similar,
name TBD at implementation) that:
1. Reads PGN files from `tools/results/` (SPRT) and gauntlet output.
2. Filters to quiet positions (arXiv margins above) plus periodic N-ply sampling,
   skipping the opening (matching #181's own "skip first 10 moves" rule) and any
   position where Stockfish reports a forced mate (label unreliable, #181's own rule).
3. Labels every retained position with Stockfish (never Vex's own eval).
4. Emits `PositionRecord`/`PositionLabel` into the existing mmap-shard format,
   `metadata().stage = "sprt-mined"` (or similar), mirroring `SelfPlayProvider`'s
   planned shape (E-10) structurally.

This reuses: `DatasetProvider` contract, `PositionRecord`/`PositionLabel`/`ShardRef`,
the mmap shard writer, the existing Stockfish-labeling driver's subprocess-management
code (Stage 2, D-8) almost unchanged — the only genuinely new code is a PGN parser
and the quiet-position filter. **No new architecture, no new format, no new invariant.**

## Tradeoffs

- **Pro:** a real, currently-flowing data source (every SPRT/gauntlet run already
  produces it) at zero additional engine-time cost — pure reuse of exhaust data,
  unlike Stage 3 self-play which spends dedicated compute to generate games.
- **Pro:** directly addresses a real labeling-quality gap (draws as false-0.5 signal)
  using a mechanism (`λ`-blend) already designed and already committed to, not a new one.
- **Con:** adds a second PGN-consuming code path alongside #181's classical-corpus
  script — some duplicated FEN/PGN-walking logic between the two, unless #181's future
  implementation and this one deliberately share a PGN-walking helper (worth flagging
  at implementation time, not solving speculatively now).
- **Con:** SPRT/gauntlet games are adversarial-but-correlated (NNUE vs. Classical, or
  candidate vs. baseline) rather than diverse self-play — position diversity is
  narrower than dedicated self-play with opening randomization (DR-E1 §3.1). This is a
  supplement to Stage 3, not a replacement for it.
- **Con:** today's SPRT games are NNUE-vs-Classical with the *known-weak* E-3 candidate
  net — mining them now would produce positions labeled against a decisively losing
  net's blunders, of uncertain training value until a stronger candidate exists.

## Where This Fits in the Roadmap

**Not an existing Phase E issue** (E-1 through E-12 don't cover it, per §1 above).
**Not urgent for Phase E** — Phase E's exit criteria (Track A–D, roadmap doc) don't
depend on this, and mining today's specific SPRT run has the "weak candidate net"
caveat above. **Recommended placement: a new, low-priority Track C-adjacent item**
(after E-11, since it reuses the same `DatasetProvider`/shard/manifest infrastructure
Track C builds) — **not urgent enough to insert into the current sequence**, and
explicitly *not* a blocker for E-6 through E-12.

## Should This Become a GitHub Issue?

**Not yet.** Per this note's own "Con" above (today's SPRT data is low-value — a
decisively-losing candidate net), and per the instruction not to expand scope
unnecessarily: file it once either (a) a stronger candidate net exists and produces
SPRT games worth mining, or (b) issue #181 is actually implemented and its PGN-walking
code becomes available to share. Recording this note now is sufficient to not lose the
idea; opening an issue today would sit blocked on a precondition for an unknown time.

## Deep Research Documents — Amendment Recommendation

**DR-E1 should get a small addition, not a rewrite.** Add a short cross-reference in
DR-E1's §17 "Alternatives Rejected" (or a new short subsection) noting: *"Mining
already-played SPRT/gauntlet games as a supplementary data source (distinct from Stage
3's dedicated self-play) was considered separately — see
`2026-07-16-mining-played-games-for-training-data.md` — and is a plausible future
Track C-adjacent addition, not a replacement for Stage 3's fresh self-play generation
(narrower position diversity, adversarial-pair structure)."* This keeps DR-E1 as the
authoritative Stage-3-self-play reference while pointing future readers at this note
instead of leaving the question un-cross-referenced. No change to DR-E1's actual
architectural conclusions is needed — this note's recommended approach doesn't
conflict with anything DR-E1 already decided.
