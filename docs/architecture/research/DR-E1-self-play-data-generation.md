# Deep Research Report — E-1: Self-Play Training-Data Generation (Stage 3)

**Status:** Research only. Not an implementation guide. No code. Purpose: determine the
optimal architecture for Stage 3 — self-play training-data generation using Vex's own
search — before Phase E implementation begins.

**Scope discipline:** this document does not discuss repository structure, file
layout, or PR breakdown. It determines architecture — process/driving model, move
selection, label blending, position sampling, the retraining loop, scheduling,
provenance, and validation strategy — for a system that generates games with Vex's
own engine and produces training records in the same durable format Stage 1 (text)
and Stage 2 (Stockfish labeling) already emit.

---

## 1. Executive Summary

Stage 3 is a **fundamentally different problem from Stage 2**, and the single most
important finding of this report is that the two must not be architecturally conflated
just because they share a `DatasetProvider` output contract. Stage 2 *labels
already-sourced positions* with a fixed, deterministic, single-position UCI
request/response cycle. Stage 3 *generates positions* by playing entire games — a
stateful, multi-ply, two-sided control loop, with labels that are only knowable once
a game concludes (the outcome half of the label) and with generation quality tied to
*which version of Vex's own network* produced the game, not a fixed external oracle.

The research converges on six load-bearing conclusions:

1. **Vex needs a dedicated self-play mode built into `engine-uci`/`engine-core`
   itself, not a UCI-subprocess game loop driven from the Python trainer.** This is
   the inverse of Stage 2's conclusion, and deliberately so: Stockfish's own historical
   self-play generator (`gensfen`) is in-process for exactly the reason that applies
   here too — the entity that must inject search-time diversity (root-move
   randomization) and capture search-derived value estimates *is the search itself*,
   not an external process reading `bestmove` lines after the fact. lc0 confirms the
   complementary half of this finding from the outside: even its externally-orchestrated
   client/server architecture keeps temperature and exploration-noise logic *inside*
   the engine binary (UCI-exposed options), never in the external client — the client's
   job is game-loop plumbing (start engine, relay moves, upload results), not
   search-internal decision-making. Vex should follow the same split: an in-process
   self-play mode inside the Java engine that plays full games and writes its own
   shard-shaped output; a thin, dumb external driver (or none at all) that only
   invokes it and moves files, adding zero Python dependency to `engine-core`.
2. **AlphaZero/lc0-style root-level Dirichlet noise over an MCTS visit distribution is
   not applicable to Vex without translation, and copying it verbatim would be a
   category error.** Vex is an alpha-beta engine with no visit-count distribution to
   perturb. The transferable analog already exists in the alpha-beta lineage: Stockfish
   `gensfen`'s own `random_move_minply`/`random_move_maxply`/`random_multi_pv`/
   `random_multi_pv_diff` — bounded-diversity move selection from a multi-PV window
   near the top move, at early plies only, full-strength search thereafter. This is a
   materially better-fitted precedent for Vex than the MCTS literature, precisely
   because it comes from the same search paradigm.
3. **The λ-blend formula ADR-007 already names ("search score blended with game
   outcome, λ configurable") is not a research question to re-derive — it is a
   well-established, directly citable formula already implemented in
   `official-stockfish/nnue-pytorch`'s training loss**: convert the centipawn score to
   WDL-space via `sigmoid(cp / K)` (the same `K` this project's `KFinder` already
   calibrates for Stage 1/2 labels), then blend `wdl_value = λ · wdl_space_eval + (1 −
   λ) · game_result`, with `λ = 1.0` meaning pure search score and `λ = 0.0` meaning
   pure outcome. Vex's `Labeler` stage should implement exactly this, not a
   from-scratch TD(λ)/n-step-return scheme — this is squarely in scope for the
   already-planned `Labeler` component (PRD §3 table), not something Stage 3's
   `DatasetProvider` needs to compute itself.
4. **The retraining loop is the largest single architecture question this report
   resolves, and the answer is: no mandatory network-gating step before a network is
   used to generate its own successor's data — matching AlphaZero's own documented
   departure from AlphaGo Zero's stricter 55%-win-rate gate — with a *release* gate
   (SPRT, already Vex's own established mechanism) kept entirely separate from a
   *generator-eligibility* gate.** AlphaGo Zero required a candidate to beat the
   current best generator net before being trusted to generate more data; AlphaZero
   dropped this and trained continuously from whatever the latest checkpoint was;
   lc0's live production pipeline follows AlphaZero's simplification for
   *self-play generation* (clients download "the latest network" unconditionally) while
   still running a separate elo-testing pipeline to decide which networks get
   *released* to the public. Vex should adopt the same split: generation uses the
   latest net produced by the previous training cycle, unconditionally; promotion to
   the engine's shipped default net still requires passing Vex's existing three-gate
   SPRT process, unchanged. The two gates protect different things and must not be
   merged into one.
5. **Reproducibility is qualitatively, not just quantitatively, different from Stage
   2 and this must be stated as a hard architectural fact, not softened.** Stage 2's
   provenance chain promises exact byte-identical replay given the same engine binary,
   options, and input. Self-play with any exploration randomness (even Vex's
   alpha-beta-appropriate bounded-diversity opening randomization, §3 below) is
   inherently a *statistically* reproducible process, not a *bit-exact* one — the same
   posture Section 10 of `NNUE_TRAINER_ARCHITECTURE.md` already establishes for
   *training* ("same seed → statistically equivalent net, not bit-exact"). Stage 3's
   provenance chain must record the generator network's UUID as its primary identity
   field (a genuinely new provenance requirement Stage 2 never had, since Stage 2's
   "engine identity" was a fixed external Stockfish binary, never a Vex-trained
   artifact with its own UUID), and must not claim a replay guarantee it cannot keep.
6. **Everything Stage 1/2 already built is reused, not re-invented**: the
   `DatasetProvider` contract, `PositionRecord`/`PositionLabel`/`PositionMetadata`/
   `ShardRef` shapes, the mmap shard format, the atomic-write convention, and the
   manifest-schema-versioning policy (§9.2 of the architecture doc) all apply to Stage
   3 unchanged. The only genuinely new pieces are: (a) the in-engine self-play game
   loop and its own on-disk game-record output format (Java-side, engine-owned, not
   trainer-owned); (b) a thin Python `SelfPlayProvider` that reads that output format
   into `PositionRecord`s, structurally identical in spirit to
   `StockfishLabeledProvider`; (c) the `Labeler`'s λ-blend implementation (already
   planned, not Stage-3-specific); (d) generator-network-identity provenance fields.

---

## 2. Problem Definition

**Given:** a trained, quantized, exported `.nnue` network (the "generator network" for
this cycle) plus Vex's own search (`engine-core`/`engine-uci`).

**Produce:** a corpus of positions drawn from games Vex played against itself using
that network, each position carrying a label blended from the search's own evaluation
of that position and the eventual game outcome (λ configurable per ADR-007), in the
same `PositionRecord`/shard/manifest shape Stage 1 and Stage 2 already emit, consumable
by a `SelfPlayProvider : DatasetProvider` with zero special-casing downstream.

**Constraints, stated up front because they shape every downstream decision:**

- The Java engine must never gain a Python/PyTorch dependency, at build time or
  runtime (Invariant 8, `NNUE_TRAINER_ARCHITECTURE.md` §15) — this is the same
  invariant Stage 2 was built under, and it binds Stage 3 identically. Since Stage 3's
  generator *is* Vex's own engine (unlike Stage 2, which drove an external Stockfish
  binary), this constraint interacts with the process-model decision far more directly
  than it did for Stage 2 — see §5.
- Self-play requires playing complete two-sided games, not evaluating isolated
  positions — a stateful control loop (which side moves, when the game ends: mate,
  stalemate, draw by repetition/50-move/insufficient material, or a resignation/
  adjudication policy) that Stage 2's stateless per-position UCI request/response
  model never needed.
- Label quality for the *value* target is now generator-network-dependent in a way
  Stage 2's labels never were: Stage 2's Stockfish labels are only as good as a fixed,
  versioned, externally-controlled engine; Stage 3's labels are only as good as
  whatever Vex's own net currently is, meaning a weak or regressed generator can
  produce systematically weaker or biased data — the "iterative loop" concern (§8).
- This is still, per the constraints held fixed for this report, a **single-host,
  single-maintainer-scale** problem — no cloud GPU cluster, no distributed
  coordination, matching D-8's own conclusion for Stage 2 and the environment this repo
  actually runs in (WSL2 dev machine, native Windows for SPRT per CLAUDE.md §5).
- Every self-play game and every derived label must be traceable to exactly what
  produced it (§9), inheriting this project's established provenance discipline, with
  the qualification in Executive Summary point 5 stated precisely rather than assumed
  to transfer unchanged from Stage 2.

---

## 3. Industry Survey

### 3.1 Chess engines and their self-play data-generation tooling

**Stockfish (`nodchip/Stockfish`'s `gensfen`, the only publicly available primary
source for this problem in the Stockfish lineage — the official mainline repository
still contains no data-generation code at all, confirmed identically to D-8's own
finding).** `gensfen` is Stockfish's actual, historical self-play generator, and its
documented design (`docs/gensfen.md`) answers several of this report's questions
directly from the alpha-beta-engine tradition, rather than the MCTS tradition:

- **Opening diversity via bounded random moves, not full-game temperature.**
  `random_move_minply` (default 1) / `random_move_maxply` (default 24) bound *where*
  in the game random moves may be injected; `random_move_count` (default 5) bounds *how
  many* random moves a single game gets. Without `random_multi_pv`, an injected "random"
  move is genuinely random among legal moves; with it, a multi-PV search is run and the
  random move is drawn from that multi-PV list, optionally restricted to moves within
  `random_multi_pv_diff` centipawns of the best move — i.e., "noise" is expressed as
  *bounded, evaluation-aware move substitution near the top of a real search's move
  list*, not as a prior-probability perturbation over a visit-count distribution (which
  does not exist in an alpha-beta searcher). Full-strength deterministic search runs for
  every other ply.
- **Quiet-position enforcement via active PV-walking (`ensure_quiet`).** A position is
  not recorded as-is; the search's own principal variation is walked forward to a
  quiescence-search leaf before a label is captured, because self-play (unlike Stage
  2's already-quiet, already-sourced positions) has no other guarantee the recorded
  position isn't mid-tactical-sequence. This is exactly the mechanism D-8 (§10) found
  unnecessary for Stage 2 and *deferred, not rejected* — Stage 3 is precisely the
  "future data source [that] feeds arbitrary/synthetic positions with no upstream
  quietness guarantee" D-8 flagged as the trigger to revisit that decision. It now
  applies.
- **Label truncation at `eval_limit` (default 3000, capped at `mate_in(2)`).** Once a
  search score exceeds this bound, the position is treated as decided and the game
  outcome — not the raw score — becomes the operative signal for that phase of the
  game. This is a coarse, hand-tuned precursor to the same idea the λ-blend formalizes
  more precisely (§7): far from the labeling engine's evaluation ability, defer to
  outcome.
- **Output rotation (`save_every`)** bounds blast radius per file exactly as D-8's
  Writer component already does for Stage 2 — the same idea, independently confirmed
  useful for self-play generation specifically, not just labeling.
- **No use of a "previous generation vs. current generation" network distinction is
  documented anywhere in `gensfen`** — it is a single-binary tool invoked with whatever
  network the operator currently has loaded; iterative "regenerate with the newly
  trained net" is an operator-driven workflow outside `gensfen` itself, not something
  the tool models.

**Leela Chess Zero (`lc0` engine + `lczero-client` + `lczero-server` +
`lczero-training`).** The best-documented MCTS-based self-play architecture surveyed,
and the clearest confirmation that **engine-internal exploration mechanics and
external game-loop orchestration are architecturally separate concerns, even in a
system explicitly built for large-scale distributed self-play**:

- **Client/server split.** `lczero-client`'s own README states its job plainly: "the
  executable that communicates with the server to run selfplay games locally and
  upload results." The client downloads the network the server currently designates
  (a live production description confirms clients "download the latest network, start
  self-playing, and upload games to the server" continuously, not per-gated-release),
  spawns `lc0` as a subprocess, and uploads completed games via HTTP with retry —
  exactly the pattern D-8 already surveyed for a different purpose (its game-generation
  half, not its labeling half). Crucially: **the client contains no MCTS, no
  temperature schedule, no Dirichlet-noise logic** — all of that lives inside the `lc0`
  engine binary itself, exposed as UCI-extension options (a `DirichletNoise` option
  family; `Temperature`-family options controlling move-selection randomness once a
  search completes) that the client merely configures at subprocess-launch time. The
  client's entire responsibility is process lifecycle, game-state relay, and network
  upload — matching D-8's own already-established finding that a long-lived,
  reused, externally-supervised subprocess is the right shape for a driver whose
  in-process peer does the actual chess computation.
- **Move-selection mechanics (MCTS-specific, non-transferable in raw form to
  Vex).** Exploration is expressed as Dirichlet noise mixed into the *root node's prior
  move probabilities* before search begins (`P(s,a) = (1−ε)p_a + εη_a`, `η ~ Dir(α)`),
  and as a *temperature* applied to the *post-search visit-count distribution* when
  picking the actual move to play (high temperature early in a game samples
  proportionally to visit counts; temperature is annealed toward a value that always
  picks the most-visited move later in the game). Both concepts are defined in terms of
  an MCTS visit-count distribution. **Vex, as an alpha-beta engine, has no such
  distribution** — the direct transferability gap this report's Executive Summary
  point 2 already names.
- **Training-record shape (V6 format).** Confirmed directly from the format
  documentation: each recorded position carries `best_q`/`best_d`/`best_m` (the
  search's own value/draw/moves-left estimate for the best move), a `root_q`/`root_d`
  pair, `played_q`/`played_d`/`played_m` (for the move actually played, potentially
  different from best under temperature sampling), a `result_q`/`result_d` pair
  (derived from the eventual game outcome), and a full move-probability vector for
  policy training. The coexistence of a *search-derived* value estimate and a
  *game-outcome-derived* value field on the same record, left for the training
  pipeline to combine, is the same shape decision ADR-007/PRD §3's `Labeler` stage
  already assumes for Vex (`PositionLabel.eval_cp`/`eval_mate` alongside `wdl`) — this
  is independent convergent evidence the existing `PositionLabel` contract (§4 below)
  already has the right shape for Stage 3 without modification.
- **Network gating: two separate gates, not one.** The production description found
  states self-play clients receive "the latest network" without qualification — i.e.,
  generation is **not** gated behind a promotion test, matching the historical
  AlphaZero-vs-AlphaGo-Zero distinction (below). A *separate* elo-testing pipeline
  (referenced via lc0's "Best Nets for Lc0" page and test-match tooling) determines
  which networks get elevated to the publicly recommended/"best" designation — a
  release gate, not a generation-eligibility gate. These are two different questions
  answered by two different mechanisms, confirmed structurally distinct in this
  project's own primary sources.

**AlphaGo Zero / AlphaZero (DeepMind, Nature 2017 / Science 2018) — foundational RL
self-play literature, not a chess-engine codebase, but the primary source ADR-007's
own "λ configurable" language and the gating question both trace back to.**

- **AlphaGo Zero (2017) used explicit gating**: a candidate network had to beat the
  current best player by a 55% margin in a dedicated evaluation match before replacing
  it as the self-play generator. This is the "AlphaGo-Zero-style gate" this report's
  Executive Summary point 4 references as the *rejected* alternative.
- **AlphaZero (2018) explicitly dropped this gate**: self-play games were generated
  continuously from the single most recent set of network parameters, with no
  evaluation/promotion match required before those parameters were trusted to generate
  more data — a deliberate simplification the paper itself frames as sufficient once a
  large, continuously-refreshed buffer of recent self-play games smooths out any single
  bad checkpoint's influence (older games are dropped from the training window as new
  ones arrive, not retroactively invalidated).
- **Move selection**: temperature is fixed at 1 for the first 30 moves of a game
  (encouraging opening diversity by sampling proportionally to visit counts) and drops
  to an infinitesimal value thereafter (deterministically picking the most-visited
  move) — an explicit two-phase schedule, not a continuous anneal. Dirichlet noise
  (`Dir(0.3)` for chess specifically, scaled inversely with the branching factor
  relative to Go) is added only at the root of the *actual game* search, not at every
  node and not during the evaluation matches used for gating (when gating was still
  used, in AlphaGo Zero).
- **Resignation.** A resignation value threshold is calibrated automatically to keep
  false-positive resignations (games that would have been won had play continued) below
  5%, measured by disabling resignation in a held-out 10% of self-play games and
  playing them to actual conclusion for comparison. This is directly relevant to Vex's
  own "when does a self-play game end" design question (§6) even though Vex has no
  value-network resignation head in the AlphaZero sense — Vex's search score can serve
  the same role (a sufficiently one-sided search score sustained over several plies is
  the alpha-beta-engine analog of a value network crossing a resignation threshold).

**Ceres / CeresTrain.** Re-confirmed directly (via primary-source inspection, not
assumption) that Ceres does not implement its own self-play *generation* pipeline in
the way Stockfish's `gensfen` or lc0's client/server system do: Ceres is fundamentally
an inference/search engine (a from-scratch, optimized MCTS reimplementation that plays
using Lc0-format trained networks), and CeresTrain is a *training* platform, not a
self-play *generation* platform — its own documentation frames it as building on the
AlphaZero/Lc0 lineage's research and offering an API for "developers and researchers"
to "write their own experiments involving self-play and network training," which is
qualitatively different from shipping a documented, opinionated self-play generation
tool. This reconfirms D-8's own finding (§3.1, "Ceres... explicitly because... generating
labels 'on the fly' is infeasible... given implementation-language constraints") from
the self-play-generation angle specifically: no comparable open-source engine in this
survey publishes a fully general, reusable self-play driver any more than D-8 found one
for labeling — Vex is again synthesizing from documented sub-patterns, not choosing
between competing reference implementations.

### 3.2 General reinforcement-learning literature on value-target blending

**TD(λ) and n-step returns (Sutton & Barto, *Reinforcement Learning: An
Introduction*).** The general RL formalism this report's λ-blend question could in
principle invoke is TD(λ): a value target computed as a weighted average of n-step
bootstrapped returns for every n, weighted by `λ^(n-1)`, trading off bias (small n,
more bootstrap-dependent) against variance (large n, more Monte-Carlo-like). This is
the *general* answer to "how do you blend a bootstrapped estimate with an eventual
outcome," and it is the conceptual ancestor of both AlphaZero's and nnue-pytorch's
simpler blends — but neither AlphaZero nor nnue-pytorch actually implements full
TD(λ) with per-step weighting; both use a single scalar blend between exactly two
signals (search-value estimate, terminal outcome), not a weighted sum over every
intermediate n-step return. This report does not recommend introducing full TD(λ) machinery
for Vex — see §7 and §14 (Alternatives Rejected) for why the simpler, already-cited
two-term blend is the correct scope match.

**Confidence Learning / label-noise detection, already surveyed in D-8 §3.2.** No new
finding here specific to self-play; D-8's conclusion (a downstream, post-hoc validation
concern requiring a trained model to compare against, not something a generation driver
performs) applies unchanged and is not re-derived here.

---

## 4. Comparative Analysis

| Dimension | Stockfish `gensfen` (nodchip fork) | lc0 client/server + engine | AlphaGo Zero / AlphaZero (RL literature) | Ceres / CeresTrain | Applicability to Vex Stage 3 |
|---|---|---|---|---|---|
| Search paradigm | Alpha-beta, fixed depth/nodes | MCTS (PUCT), visit counts | MCTS (PUCT), visit counts | MCTS (PUCT), visit counts | Vex is alpha-beta — MCTS-specific mechanics (visit-count temperature, root Dirichlet noise) don't transfer directly |
| Process model | In-process, engine's own thread pool | Long-lived subprocess per client, spawned/reused by an external client | N/A (research system, not a public tool) | No public self-play generation tool | In-process self-play mode inside `engine-uci`, matching `gensfen`'s precedent, not lc0's client-external-orchestration precedent |
| Opening/move diversity | Bounded random moves early (ply-ranged), optional multi-PV-window substitution | Dirichlet noise at root prior + temperature over visit counts | Same as lc0 (lc0 implements this design directly) | N/A | `gensfen`'s bounded multi-PV-window substitution is the directly transferable analog for an alpha-beta engine |
| Quiet-position handling | Active PV-walk to qsearch leaf (`ensure_quiet`) | N/A (MCTS positions are already leaf-evaluated, no separate "quiet" concept) | N/A | N/A | Vex must actively walk to a quiet position before recording a label, unlike Stage 2 (D-8 §10's deferred trigger, now applicable) |
| Value label source | Fixed-depth search score; outcome only past `eval_limit` | `best_q`(search)+`result_q`(outcome), both recorded, blended downstream | Value network estimate + terminal outcome, blended via training procedure | N/A | Record both search score and outcome unblended (matches existing `PositionLabel` shape); blend in `Labeler`, per nnue-pytorch's cited formula |
| Network gating for generation | Not modeled — operator-driven | None (clients always use "latest") — separate release-test pipeline exists | AlphaGo Zero: yes (55% gate); AlphaZero: no | N/A | Adopt AlphaZero/lc0's ungated-generation model; keep Vex's existing SPRT as the separate release gate |
| Process/scheduling model | Single engine binary, internal threads | Distributed contributor network (irrelevant at Vex's scale) | N/A | N/A | Single-host; the real open question is JVM-instance model (§10), not multi-host distribution |
| Reproducibility | Not addressed as a formal guarantee | Not deterministic by design (temperature/noise) | Not deterministic by design | N/A | Statistical, not bit-exact — must be stated explicitly (§9) |
| Provenance of generator identity | Whatever binary/network the operator loaded, not automatically recorded by the tool | Network ID is a first-class field the server tracks per game | N/A (research system) | N/A | Vex must record generator network UUID as a first-class Stage 3 provenance field — genuinely new vs. Stage 2 |

**Synthesis.** No single surveyed system is a template to copy — the same conclusion
D-8 reached for Stage 2, reached independently here for a materially different
problem. The correct synthesis for Vex: `gensfen`'s in-process architecture and
alpha-beta-native diversity mechanism (bounded move substitution, active quiet-search
walk, `eval_limit`-style outcome deference) for the generation mechanics; lc0's
training-record shape (search estimate + outcome estimate coexisting on one record,
blended downstream) and its ungated-continuous-generation model for the retraining
loop; nnue-pytorch's already-implemented λ-blend formula (§7) for the label math,
cited rather than re-derived; and this project's own already-built `DatasetProvider`/
shard/manifest machinery (Stage 1/2) for everything about *how* the resulting data
joins the rest of the pipeline.

---

## 5. Recommended Architecture

```
                         ┌─────────────────────────────────────────┐
                         │   engine-uci (Java) — self-play mode     │
                         │                                           │
[.nnue generator net] ──▶│  GameLoop ──▶ MoveSelector ──▶ Searcher   │
                         │     │              │                     │
                         │     ▼              │ (bounded diversity, │
                         │  QuietWalk         │  early-ply only;    │
                         │     │              │  full-strength      │
                         │     ▼              │  thereafter)        │
                         │  GameRecordWriter                        │
                         └───────────────┬───────────────────────────┘
                                         │  game-record files
                                         │  (engine-owned format,
                                         │   not the mmap shard format)
                                         ▼
                         ┌─────────────────────────────────────────┐
                         │  trainer/scripts/selfplay_ingest.py       │
                         │  (thin: game records → PositionRecords    │
                         │   → existing mmap shard writer)           │
                         └───────────────┬───────────────────────────┘
                                         ▼
                              SelfPlayProvider(DatasetProvider)
                                         │
                                         ▼
                              Transform → FeatureEncoder → Labeler
                              (λ-blend: PRD-planned stage, not new)
```

This is deliberately **not** a linear pipeline of newly-invented components the way
D-8's eight-component Stage 2 driver was — Stage 3's genuinely new surface is small:
an in-engine game loop and its output format, plus one thin ingestion script. Every
other box in the diagram above (`Transform`, `FeatureEncoder`, `Labeler`,
`DatasetProvider`) is an existing, already-designed seam that Stage 3 simply becomes a
third caller of, matching Invariant 1's isolation guarantee (a new `DatasetProvider`
never modifies an existing one) and the PRD's own explicit design goal ("adding a
future data source... means writing one `DatasetProvider`; everything downstream is
unchanged").

**The one consequential new architectural boundary**: the line between the
**Java-owned self-play game loop** (inside `engine-uci`, produces raw game records) and
the **Python-owned ingestion/shard-writing step** (`SelfPlayProvider` plus a thin
conversion script). This boundary is exactly where Invariant 8 (no Python dependency in
`engine-core`/`engine-uci`) is enforced: the Java side never imports or calls into
Python; it only ever writes files in a documented, versioned format that the Python
side reads. This mirrors, at a smaller scale, the same "the only sanctioned integration
boundary is a file format" principle that already governs the `.nnue` contract itself
(§8 of the architecture doc) — self-play game records are a second instance of that
same pattern, not a new kind of boundary.

---

## 6. Component Responsibilities

- **GameLoop (Java, `engine-uci` or a new `engine-core` self-play package — exact
  module TBD at implementation, not this report's decision).** Owns the two-sided
  control loop: alternates `Searcher.search()` calls for White and Black using the same
  loaded `.nnue` network for both sides (self-play, not two different opponents), feeds
  each side's chosen move back into `Board` via the existing make/unmake machinery, and
  detects every game-ending condition Vex's rules engine already detects (checkmate,
  stalemate, threefold repetition, fifty-move rule, insufficient material) — no new
  rules logic, reuse of `Board`'s existing state exactly as the PRD's own "Board
  untouched" principle (§8, architecture doc) already establishes for the NNUE
  evaluator work. Also owns an optional adjudication policy (§6.1) for games that would
  otherwise run to an impractical length under fixed-depth self-play.
- **MoveSelector (Java).** The one genuinely new search-adjacent component: for plies
  within a configured early-game window, substitutes full-strength best-move selection
  with a bounded, evaluation-aware random pick among the top-N moves (or moves within a
  configured centipawn margin of the best move) that a normal search already computes —
  directly modeled on `gensfen`'s `random_multi_pv`/`random_multi_pv_diff`, not on
  MCTS-style prior-probability noise (§3.1's non-transferability finding). Outside that
  window, move selection is Vex's existing deterministic best-move choice, unchanged.
- **QuietWalk (Java).** Before a position is handed to the recorder, the search's own
  principal variation (or a short forced quiescence-only continuation) is walked
  forward to a tactically settled position — reusing `gensfen`'s `ensure_quiet`
  rationale, now applicable because Stage 3, unlike Stage 2, has no upstream guarantee
  of quietness (D-8 §10's explicitly named revisit trigger).
- **GameRecordWriter (Java).** Writes one self-contained record per completed game: the
  move list, the search-derived score at each *sampled* position (§8), the generator
  network's UUID (read from the already-loaded `.nnue` header, §9), and the final
  outcome. This is a new, engine-owned, versioned file format — not the trainer's mmap
  shard format, and not required to be. Keeping this format engine-owned (rather than
  trying to have Java write the Python-side shard format directly) is what keeps the
  Java side ignorant of the trainer's on-disk conventions, preserving Invariant 8 by
  construction rather than by discipline.
- **`selfplay_ingest.py` (Python, `trainer/scripts/`).** A thin, format-translating
  script — structurally the Stage-3 sibling of `stockfish_label.py`, but far smaller,
  since it does no searching or labeling of its own: it reads the Java-written game
  records, applies position sampling (§8) and the λ-blend (§7, delegated to the
  `Labeler`, not computed here), and writes the existing mmap shard format plus a
  manifest — reusing `mmap_shard.write_shard` and `_atomic_write` exactly as
  `stockfish_label.py` already does, not reinventing either.
- **`SelfPlayProvider` (Python, `trainer/trainer/dataset/`).** A `DatasetProvider`
  implementation structurally identical to `StockfishLabeledProvider` — a thin reader
  over the shard directory `selfplay_ingest.py` produces, setting `metadata().stage =
  "self-play"`. Imports only `trainer.contracts` and `trainer.dataset.mmap_shard`,
  satisfying Invariant 1 exactly as its Stage 1/2 siblings do.
- **`Labeler` (Python, already-planned per PRD §3, not new to this report).** Implements
  the λ-blend (§7) once, shared by every stage that supplies both a search score and an
  outcome — Stage 3 is simply this component's first real caller with genuinely
  populated `wdl` *and* `eval_cp` fields on the same record (Stage 1/2 typically supply
  only one or the other, per `PositionLabel`'s own docstring, §4 below).

### 6.1 Game-length adjudication — a genuinely new policy decision

Self-play games under a fixed search budget can run far longer than a typical
human/engine game before a natural mate, stalemate, or draw-by-rule is reached,
especially early in training when both sides play weakly. Two adjudication tools are
available and neither is novel machinery to design from scratch:

- **Move-count cap.** A hard ply limit (e.g., a few hundred plies) after which the game
  is adjudicated a draw regardless of position — the simplest possible bound, at the
  cost of occasionally truncating a game that was not actually heading to a draw.
- **Sustained one-sided score adjudication.** If the search score for one side exceeds
  a threshold and stays there for a configured number of consecutive plies, adjudicate
  a win for that side — the alpha-beta-engine analog of AlphaZero's value-based
  resignation, using Vex's own centipawn score in place of a value-network output. The
  same false-positive-rate concern AlphaZero's own resignation calibration names
  applies here identically: an adjudication threshold set too aggressively will
  occasionally end games early that would have swung back, silently biasing the outcome
  label distribution.

Recommended default: both, together — a move-count cap as an unconditional safety
valve, and score-based adjudication as the primary mechanism, with the AlphaZero
"disable it in a held-out fraction of games and measure the false-positive rate"
calibration technique applied once real self-play games exist to measure against
(explicitly deferred to an empirical follow-up, not asserted with a specific threshold
here — matching this report's own discipline of not inventing numbers no real
measurement yet supports).

---

## 7. Label Blending Strategy

**The formula, cited rather than re-derived.** `official-stockfish/nnue-pytorch`'s
training loss already implements exactly the blend ADR-007 names: the search score is
mapped into WDL-space via `wdl_space_eval = sigmoid(cp / K)` (the same functional form
and the same role `K` already plays in Vex's own `KFinder`-calibrated Stage 1/2 labels,
per the PRD's "Trainer Requirements" hard requirement that NNUE training targets land
on the classical centipawn scale), and then blended against the game outcome:

```
wdl_value = λ · wdl_space_eval + (1 − λ) · game_result
```

with `λ = 1.0` recovering pure search-score training and `λ = 0.0` recovering pure
outcome (WDL-only) training. This is a single scalar interpolation between exactly two
already-computed signals — not full TD(λ) with per-n-step weighting (§3.2) — and Vex
should implement precisely this, because it is the established, load-bearing precedent
in the exact codebase lineage (`nnue-pytorch`) this project's own trainer design
already draws from for its `Labeler` stage naming and shape (PRD §3 table: "`Labeler` —
Produce/blend targets (search score, WDL, λ blend, K scaling)" — this exact phrase was
already written into the PRD before this report existed, confirming the formula was
already the intended target, not an open question this report resolves for the first
time).

**Where this lives architecturally.** The blend belongs in the `Labeler` stage (PRD
§3), not in `SelfPlayProvider` and not in the Java `GameRecordWriter`. `PositionRecord`/
`PositionLabel` already carries both an optional `eval_cp` and an optional `wdl` field
unblended (§6 above; `trainer/trainer/contracts/dataset.py`) — Stage 3 populates both,
the same shape Stage 1/2 already established, and blending happens once, downstream,
shared across every stage that ever supplies both signals. This is a direct instance of
the same "don't duplicate policy logic across data sources" principle D-8 already
applied to Stage 2's Filter component (§6, D-8: "does not perform corpus-level
deduplication... belongs to this project's existing generic, shared post-processing
mechanism").

**Default λ and validation.** No specific numeric default is recommended here — this
report treats the exact value as an empirical hyperparameter to sweep once real
self-play data exists (nnue-pytorch's own published configs use values in the
0.6–1.0 range across different training runs and data volumes, but citing a borrowed
default without validating it against Vex's own data/K-scale would be exactly the kind
of unearned precision this project's engineering discipline elsewhere rejects). What
*is* recommended: λ should be a named, versioned field in the training config
(already true per PRD "Trainer Requirements": "seeded runs, config-file-driven
hyperparameters") and swept empirically via the already-established Validator
held-out-correlation mechanism (§11 of the architecture doc), not fixed by
architectural fiat in this report.

---

## 8. Position Sampling Strategy

**Every ply vs. a sampled subset.** `gensfen`'s own `write_minply`/`write_maxply`
window (skip very early opening plies and very late/decided-game plies) is a
directly-applicable, already-proven precedent: sample most plies within a game, but
exclude the earliest few (before any meaningful position-specific signal exists) and
the latest ones past a effectively-decided score (where the outcome, not the
per-position search score, already dominates the label per §7's blend). lc0's own
training records similarly sample a large fraction of plies per game (not every single
one, since near-duplicate consecutive positions convey little additional signal for
the marginal storage/compute cost), converging on the same idea from the MCTS
tradition.

**Avoiding near-duplicate consecutive positions.** A position immediately following a
non-forcing quiet move is highly similar to its predecessor (most features unchanged);
recording every single ply inflates the dataset with low-marginal-value near-duplicates
relative to recording, say, every other ply or applying a lightweight change-detection
skip (e.g., skip a position if fewer than N features differ from the last recorded
one in the same game). This is a genuinely new decision for Stage 3 specifically —
Stage 1/2 draw positions from already-diverse external sources (real games, or an
externally-sourced corpus) where this concern is naturally diluted; Stage 3 generates
its own sequential game trajectories, where it is not.

**Quiet-position filtering re-applying Stage 2's precedent.** D-8 §10 explicitly named
"a not-in-check check at the Reader stage is a proportionate substitute" for Stage 2's
already-quiet input distribution, and explicitly flagged that a future arbitrary/
synthetic-position source should revisit that tradeoff. Stage 3 is that source: the
`QuietWalk` component (§6) is the revisited, stronger mechanism `gensfen`'s
`ensure_quiet` already validates as necessary at this point in the tradeoff, not
over-engineering — this is D-8's own deferred decision now correctly triggered, not a
new invention.

**Game-phase balancing.** Corpus-level phase balancing (opening/middlegame/endgame
proportions) is explicitly **not** this stage's job, for the identical reason D-8 §10
gave for Stage 2's Filter component: it is a generic, format-agnostic `Transform`-stage
concern this project's pipeline already applies uniformly across every data source
(PRD §3 table: `Transform` — "filtering/deduplication/phase balancing, composable").
Stage 3 supplying its own phase-balancing logic would duplicate that mechanism for no
benefit and risk silent divergence between two implementations of the same idea — the
same don't-repeat-yourself argument D-8 already established for Stage 2's Filter
boundary, applying identically here to the ingestion script's scope.

---

## 9. Provenance and Reproducibility Strategy

**What is different from Stage 2, stated precisely rather than assumed to transfer.**

- **Generator identity is a new, first-class field.** Stage 2's "engine identity" was
  a fixed, externally-versioned Stockfish binary (self-reported `id name` plus a SHA-256
  of the binary). Stage 3's generator is *Vex's own trained network* — the artifact
  this entire pipeline exists to produce — and its identity is the network UUID already
  embedded in every `.nnue` file's header (§8, architecture doc) plus the manifest's
  existing dataset/config lineage (§9, architecture doc). Every self-play game record
  must carry this UUID, read directly from the loaded network at self-play-run start,
  not inferred from a filename or assumed constant across a long-running generation
  session — the same "capture identity at worker-startup time, treat a mismatch as a
  hard error" discipline D-8 §18 already established for Stage 2's own risk register,
  applied here to a network file instead of a Stockfish binary.
- **Reproducibility is statistical, not bit-exact, and this is inherent, not a current
  limitation to fix later.** Stage 2's provenance chain promises byte-identical replay
  given `Threads=1` and a fixed node budget — a genuine, testable guarantee (D-8 §14's
  two-run determinism check). Stage 3's `MoveSelector` (§6) deliberately introduces
  bounded randomness for opening diversity; even with a recorded RNG seed, exact replay
  would additionally require Vex's search itself to be bit-deterministic under that
  seed across runs/hardware, which this project's own reproducibility posture for
  *training* (architecture doc §10: "statistically equivalent net... not bit-exact
  across GPU hardware") already disclaims for the analogous case on the training side.
  Stage 3's provenance manifest should record the RNG seed for the *diversity
  mechanism* (useful for statistical reproducibility and debugging a specific game's
  trajectory) while explicitly documenting — in the manifest schema's own field
  descriptions, not left implicit — that this does not constitute a bit-exact replay
  guarantee the way Stage 2's engine-identity-plus-node-budget combination does.
- **What does carry over unchanged.** The dataset-identifier/stage-label shape
  (`DatasetMetadata.stage = "self-play"`), the atomic manifest-write convention, the
  manifest-schema-versioning policy (§9.2, architecture doc: an additive field with a
  sensible-absent-value convention is not a schema bump; a required-field change is),
  and the overarching principle that a labeling/generation run's full identity is
  captured once, at run start, never reconstructed after the fact.

**Manifest fields specific to Stage 3** (additive to the existing schema per §9.2's own
policy — not a schema version bump unless a *required* field is involved): generator
network UUID, generator `.nnue` SHA-256 (mirroring `nnue_sha256`'s existing role, §9.1,
architecture doc), self-play engine commit (the `engine-uci` build that played the
games — a new identity axis Stage 2 never needed, since Stage 2's "engine" was never
this project's own code), diversity-mechanism configuration (early-ply window, move
substitution margin, RNG seed), adjudication policy configuration (§6.1), and game
count / position count after sampling.

---

## 10. Process/Scheduling Model at Scale

D-8 concluded a single-host, multi-process pool of persistent Stockfish subprocesses
was correct for Stage 2, driven by the general finding that task-cost variance favors
dynamic work-stealing over static sharding. Stage 3's generator is a JVM process, not a
small native binary, and this changes the calculus in one specific, concrete way worth
stating precisely rather than assuming D-8's conclusion transfers unchanged:

- **JVM startup/warmup cost is real and non-trivial in a way a native Stockfish binary's
  startup is not.** Class loading and JIT warmup mean a freshly started `engine-uci`
  process does not reach its steady-state search throughput immediately — a real cost
  that is amortized away over one long bench run (the existing `--bench` invocation
  already pays this cost once, for the entire suite) but would be paid *repeatedly* if
  Stage 3 adopted a "one JVM process per game" model, the direct analog of D-8's
  explicitly-rejected "one Stockfish subprocess per position." The reasoning is the
  same reasoning D-8 already used to reject that pattern for Stage 2 (§17: "repeated
  process/engine startup cost paid... times over is a real, avoidable throughput
  cost"), now applied to a cost that is proportionally larger for a JVM than for a
  native binary.
- **Recommended default: one long-lived JVM process plays many games sequentially (or
  via Vex's own internal thread pool, if/when one exists), not many short-lived JVM
  processes each playing one game.** This is the direct JVM-appropriate analog of D-8's
  own "persistent, reused worker" conclusion for Stockfish subprocesses — the specific
  mechanism differs (one process looping over many games internally, rather than a
  pool of processes each reused across many positions) because the workload shape
  differs (Stage 3's unit of work is a whole game with internal search-thread
  reusability already available inside one process, not a a sequence of independent,
  parallelizable single-position searches each needing its own external process for
  isolation).
- **Multi-instance parallelism, if pursued, should be multiple independent JVM
  processes each playing many games sequentially (not one game each)** — combining the
  startup-amortization argument above with D-8's original crash-isolation argument
  for using separate OS processes over separate threads within one process (matching
  CLAUDE.md's own "no object allocation in Searcher/Evaluator hot paths" constraint,
  which argues against forcing thread-safety costs into the hot search path merely to
  support concurrent self-play games within a single JVM instance that doesn't need it
  for any other reason).
- **This remains single-host.** Nothing in this report's research found evidence that
  self-play generation at this project's scale needs multi-machine distribution any
  more than D-8 found for Stage 2 — the closest multi-machine precedent surveyed
  (lc0's contributor network) solves a crowd-sourced-compute problem at a scale and
  with a trust model (untrusted, volunteer-operated clients) that does not describe
  Vex's single-maintainer environment at all.

---

## 11. The Iterative Retraining Loop and Network Gating

This is, per the task framing, the single largest architecture question this report
resolves, and the research is unambiguous once AlphaGo Zero and AlphaZero are compared
directly against each other rather than treated as one undifferentiated "self-play RL"
precedent.

**Two distinct gates, not one, must exist — and they must not be merged.**

1. **Generator eligibility (does network N get used to generate Stage-3 games for the
   next training cycle?).** AlphaGo Zero's answer was yes-only-if-gated (55% win-rate
   match against the reigning generator). AlphaZero's answer, and lc0's live production
   answer for actual self-play generation (as distinct from its separate release-test
   pipeline), was no gate at all — every self-play worker/client is handed whatever the
   most recent checkpoint is, unconditionally, with the training buffer's own
   continuous refresh (old games aged out as new ones arrive) providing enough
   self-correction that a single weak checkpoint's data does not permanently corrupt
   training. **Recommended for Vex: no generator-eligibility gate.** Justification
   beyond the precedent itself: Vex already has a substantially more expensive
   alternative gate available and already mandatory for a different purpose (below),
   and building a second, lighter-weight gate specifically for generator eligibility
   would be exactly the kind of premature machinery this project's own engineering
   discipline (CLAUDE.md, ADR-005's "no config for a value that never changes" spirit)
   warns against building without a demonstrated failure this simpler default doesn't
   already handle.
2. **Release/promotion (does network N become the shipped default evaluator?).** This
   is already fully specified and does not need to be redesigned here: the PRD's three
   release gates (correctness, performance, strength via SPRT with fixed H0/H1/α/β
   terms) already govern this, unchanged by Stage 3's existence. **Recommended for Vex:
   this gate is untouched, and is not the same gate as (1).** A network can be a
   perfectly valid Stage-3 generator (producing useful training data for the *next*
   net) while never itself being promoted past candidate status — the two questions
   are independent, and the architecture must not conflate "good enough to generate
   data from" with "good enough to ship."

**What this implies architecturally, concretely:**

- **Network versioning across generations is provenance, not control flow.** Every
  Stage 3 manifest records which network UUID generated it (§9) — this is sufficient
  to reconstruct the full lineage (which net generated which data, which data trained
  which subsequent net) after the fact, without needing a live gating *decision* baked
  into the generation pipeline itself. This is the same "provenance chain, not a
  state machine" philosophy D-8 §5 already applied to Stage 2's checkpointing
  design ("a manifest problem, not a state-machine problem"), applied here to
  cross-generation lineage instead of within-run crash recovery.
- **A regressed generation is a data-quality event to detect after the fact via the
  existing Validator, not to prevent architecturally up front.** If a given training
  cycle's net turns out weaker than its predecessor (measured via the existing
  gauntlet/SPRT machinery, or via the Validator's held-out correlation check), the
  concrete remedy is: don't promote that net to the next cycle's generator manually,
  and don't retrain further on the data it produced without first checking whether that
  data measurably degraded the next net's own validation metrics — a human-in-the-loop
  decision informed by already-existing tooling (release reports, §4 of the PRD),
  not a new automated circuit-breaker this report needs to design. This mirrors the
  PRD's own explicit stance on staged-data quality generally: "retraining runs on
  mixed datasets are measured (SPRT/strength tests), not assumed superior" — the
  identical discipline, applied to self-play-generation quality instead of dataset-mix
  quality.
- **The loop's cadence (how often to regenerate self-play data with a freshly trained
  net) is an operational/empirical question, not an architectural one this report
  settles.** AlphaZero's own continuous-refresh buffer model assumes near-continuous
  regeneration at a scale (thousands of parallel self-play workers) wildly
  disproportionate to Vex's single-host environment; the right cadence for Vex
  (regenerate after every retrain? after every N retrains? only once Stage 1/2 data is
  measurably plateaued, per ADR-001's own revisit condition C: "a plateau in measured
  relative strength... across ≥2 retrained nets at 768") is left as an Open Question
  (§17), deliberately, because answering it requires real measured data this report
  cannot fabricate.

---

## 12. Failure Recovery Strategy

Self-play generation's failure modes are qualitatively narrower than Stage 2's, for a
structural reason worth stating rather than assuming parity: Stage 3 drives the
generation engine *in-process*, inside the same JVM whose search is being exercised —
there is no external subprocess-hang/crash-and-respawn problem in the way D-8's
UCI-subprocess model had to solve for an *externally* driven Stockfish binary. What
remains, restated for this stage's actual shape:

- **A crashed self-play JVM process (not a subprocess-under-supervision, but the
  generation process itself) loses at most the game currently in progress**, if game
  records are written to disk incrementally per completed game (matching `gensfen`'s
  own `save_every`-style rotation discipline and D-8's already-established
  atomic-shard-commit convention) rather than buffered entirely in memory until an
  entire multi-day generation run finishes. This is a direct reapplication of D-8 §12's
  "bound wasted work to whatever was in-flight at interruption" principle, scaled to
  this stage's actual unit of loss (one game, not one position).
- **A circuit breaker for a persistently broken generator** (e.g., a corrupt or
  mismatched `.nnue` file, or a search returning obviously-invalid moves) should abort
  the run loudly rather than silently producing a large volume of worthless self-play
  data — the identical discipline D-8 §12/§18 already established for Stage 2's broken-
  engine-binary case, applied here to a broken network file or a Java-side bug instead.
- **Resumability across a multi-hour/multi-day generation run** is a matter of
  resuming "play N more games," not resuming mid-game — an interrupted game is
  discarded, not reconstructed from a partial move list, matching lc0's own documented
  choice ("partial games are discarded and regenerated, not resumed mid-game," D-8
  §3.1) for exactly the same reason: a partially-played game has no well-defined
  outcome label to blend against (§7), so there is nothing coherent to resume toward.

---

## 13. Performance Strategy

- **Streaming discipline, reused unchanged.** `selfplay_ingest.py` should stream game
  records into shards exactly as `stockfish_label.py` already streams positions —
  never materializing a full generation run's output in memory (D-8 §13's already-
  established principle, unchanged by the different upstream source).
- **Shard format and atomic writes: reused, not reinvented.** Identical to D-8 §13's
  conclusion for Stage 2 — Stage 3 targets the same mmap shard format and the same
  atomic-write convention; a second format would force every downstream consumer
  (encoding, training) to branch on which format it's reading, for a benefit no
  actual requirement demands.
- **Self-play throughput is dominated by search cost, not I/O**, unlike Stage 2, where
  Stockfish search cost and I/O were more comparable in magnitude per position — a full
  game's worth of full-strength search (dozens to hundreds of plies, each at whatever
  depth/time Vex's normal search budget uses) is a substantially larger per-unit-of-
  output compute cost than Stage 2's fixed-node-budget single-position searches. This
  argues for keeping the search budget per self-play move modest relative to Vex's
  strongest configured search (a real tradeoff between data-generation throughput and
  per-game move quality) rather than assuming Stage 2's node-budget defaults transfer
  unchanged — an empirical tuning question (§17), not resolved here.

---

## 14. Validation Strategy

- **Correctness: reuse, don't reinvent.** Vex's own existing regression suites (perft,
  the tactical suite, `SearchRegressionSuite`) already validate that the search itself
  is correct; self-play generation does not need its own parallel correctness suite for
  "does the engine play legal chess" — it needs only to confirm the `MoveSelector`'s
  bounded-diversity substitution never produces an illegal move (a narrow, cheap
  addition to existing move-generation test coverage, not a new test category).
- **A golden-fixture regression test for the game-record format**, mirroring D-8 §14's
  own recommendation for Stage 2: a small, fixed self-play run (a handful of games,
  fixed seed, a tiny/synthetic network) with the resulting game records committed and
  asserted against — catching silent format or move-selection drift the same way
  D-8's own golden-fixture test catches labeling drift.
- **Statistical, not bit-exact, reproducibility check.** Given §9's honest
  reproducibility posture, the equivalent of D-8's "two-run determinism check" for
  Stage 3 is necessarily weaker: assert that the *distribution* of outcomes/move
  choices across many seeded runs is stable (e.g., win/draw/loss proportions within a
  documented statistical tolerance across repeated runs with different seeds, holding
  the generator network fixed), not that any two runs produce byte-identical output.
  This must be stated as what it actually is — a statistical stability check — not
  mislabeled as a determinism check the way that phrase is used for Stage 2.
- **What validation does not need to do here.** Confirming the *trained net that
  results* from Stage 3 data is actually good is the existing Validator/gauntlet/SPRT
  machinery's job (§11 above), not a property Stage 3's generation pipeline itself can
  or should assert in isolation before any resulting net exists to measure.

---

## 15. Security Considerations

- **Malformed or adversarial network files.** `.nnue` loading already validates magic
  bytes, format/architecture/feature-set version, and body length (§8 of the
  architecture doc, and PRD "Security & Privacy": "reject oversized or malformed
  files"). Self-play mode is one more caller of that same, already-hardened load path
  — no new validation surface, but a reminder that self-play mode must not bypass it
  (e.g., via a debug-only fast-load path that skips validation for convenience).
- **Resource exhaustion from unbounded games.** §6.1's adjudication policy is this
  stage's primary defense against a self-play game running unboundedly long (a
  buggy or pathological position causing move repetition the rules engine somehow
  fails to detect, or a genuinely very long endgame) and consuming disk/memory
  disproportionately — the move-count cap specifically exists as an unconditional
  backstop independent of the score-based adjudication heuristic, for exactly this
  reason.
- **Filesystem failures.** Identical posture to D-8 §15: a failed game-record or shard
  write must fail loudly, never silently drop a completed game's data.
- **No new external-input trust boundary.** Unlike Stage 2 (which the D-8 report flags
  as needing input validation if ever pointed at externally-sourced positions), Stage
  3 generates its own positions from its own search — there is no untrusted external
  input to this stage at all, beyond the `.nnue` file itself (already covered above).

---

## 16. Future Evolution

- **Full TD(λ)/n-step-return value-target blending** (§3.2, §7), if the simpler
  two-term nnue-pytorch-style blend is ever measurably insufficient — not built
  preemptively, matching this report's own recommendation to cite the established,
  simpler formula first.
- **A lightweight generator-eligibility gate**, if real measured evidence ever shows
  the ungated-generation default (§11) is actually producing harmful data from a
  regressed net — explicitly not built now, since neither AlphaZero's nor lc0's own
  production precedent needed one, and no Vex-specific evidence yet suggests
  otherwise.
- **Multi-JVM-instance parallel self-play**, if single-instance sequential throughput
  (§10) proves genuinely insufficient at whatever data volume ADR-001's revisit
  condition ("self-play data sustains 5–10× current volume") actually demands —
  deferred until that need is measured, not assumed.
- **Opening-book-seeded starts** (an alternative or complement to `MoveSelector`'s
  bounded-diversity substitution, §6), if pure engine-driven diversity turns out
  insufficient for opening variety at scale — PRD §5's own Open Questions item 3
  ("self-play opening variety source — existing book vs. external opening suite") is
  explicitly left open for Phase E, and this report does not resolve it further than
  noting both are legitimate, complementary (not mutually exclusive) options.
- **Resignation/adjudication threshold calibration** using AlphaZero's own
  "disable it in a held-out fraction of games and measure the false-positive rate"
  technique (§6.1), once real self-play games exist to calibrate against.

---

## 17. Alternatives Rejected

- **Driving Vex via an external UCI subprocess from the Python trainer, mirroring
  Stage 2's D-8 architecture exactly.** Rejected because the entity that must inject
  search-time diversity and read search-internal value estimates is the search itself,
  and because a two-sided, stateful game loop is a fundamentally different control
  problem than Stage 2's stateless per-position request/response cycle. An external
  driver could technically relay `position moves ...`/`go`/`bestmove` back and forth to
  simulate a game, but this pushes game-loop state, adjudication logic, and diversity
  injection into the Python side for no benefit — and worse, direct root-level search
  perturbation (the MoveSelector's bounded substitution) is not expressible through the
  standard UCI protocol surface at all without a custom UCI extension, at which point
  the "just drive it externally" simplicity Stage 2 actually had is already gone.
- **AlphaZero/lc0-style Dirichlet noise over a visit-count distribution, applied
  literally.** Rejected outright as a category error — Vex's alpha-beta search has no
  visit-count distribution to perturb. `gensfen`'s bounded multi-PV-window substitution
  is the correct, paradigm-matched analog (§3.1, §6).
- **A generator-eligibility gate modeled on AlphaGo Zero's 55%-win-rate match.**
  Rejected in favor of AlphaZero's/lc0's ungated-continuous-generation model (§11),
  because no evidence from any surveyed precedent shows the stricter gate is necessary,
  and because building it would duplicate machinery (a match-testing harness) already
  redundant with Vex's existing SPRT infrastructure for a different, already-served
  purpose (release promotion).
- **Full TD(λ) value-target blending instead of the simpler two-term formula.**
  Rejected as scope mismatch — no surveyed chess-engine precedent (Stockfish/
  nnue-pytorch, lc0) actually implements full per-n-step TD(λ) weighting for this
  purpose; both converge on a single scalar interpolation between exactly two signals,
  and Vex's own `Labeler` design (already named in the PRD before this report existed)
  already assumes that simpler shape.
- **Skipping the QuietWalk step, reusing Stage 2's "not-in-check is enough" shortcut
  unchanged.** Rejected — D-8 §10 explicitly scoped that shortcut to Stage 2's
  already-quiet input distribution and named arbitrary/synthetic position generation
  as the specific trigger to revisit it. Self-play generation is exactly that trigger.
- **Multi-host/distributed self-play generation.** Rejected for the same reason D-8
  rejected it for Stage 2: no evidence of a demonstrated single-host throughput
  ceiling, and the one surveyed multi-machine precedent (lc0's contributor network)
  solves a different problem (untrusted volunteer compute at massive scale) than
  Vex's single-maintainer environment has.
- **One JVM process per self-play game.** Rejected identically to D-8's rejection of
  "one Stockfish subprocess per position" — repeated JVM startup/warmup cost paid many
  times over, for a cost that is proportionally worse for a JVM than for the native
  binary D-8 originally reasoned about.
- **Mining already-played SPRT/gauntlet games as a Stage-3 substitute.** Considered
  separately, not rejected outright — see
  `2026-07-16-mining-played-games-for-training-data.md`. This is a plausible future
  Track C-*adjacent* supplementary data source (reusing already-produced PGNs at zero
  additional engine-time cost), but not a substitute for this report's own
  fresh-self-play design: SPRT/gauntlet games are adversarial-but-correlated
  (candidate vs. baseline) rather than diverse self-play with opening randomization
  (§3.1), so position diversity is narrower. No change to this report's architectural
  conclusions.

---

## 18. Risks

- **Category-error risk: importing MCTS-specific self-play mechanics wholesale into an
  alpha-beta engine.** The single highest-consequence risk this report identifies is
  an implementer reaching for "Dirichlet noise + temperature," the most famous
  self-play recipe in the literature, without recognizing it is defined in terms of a
  search structure (visit counts) Vex's search does not have. Mitigation: this report's
  explicit naming of `gensfen`'s bounded multi-PV substitution as the correct,
  paradigm-matched analog (§3.1, §6, §17) is intended to head this off at the design
  stage, before any implementation attempts a direct, non-transferable port.
- **Generator-quality risk compounding across generations.** Because generation is
  intentionally ungated (§11), a systematically biased or weak generator network could,
  in principle, produce data that trains its successor to inherit the same bias,
  without an automated gate to catch it. Mitigation: this is explicitly a
  human-in-the-loop, release-report-driven check (§11), not eliminated by
  architecture — the same accepted risk-and-mitigation shape the PRD's own risk table
  already uses for "training-data quality (stale labels, unbalanced phases)."
- **Reproducibility overclaiming.** If Stage 3's manifest schema is built by copying
  Stage 2's provenance fields without adjustment, a future reader could mistakenly
  believe self-play runs carry the same bit-exact replay guarantee Stage 2's
  `Threads=1`/node-budget combination genuinely provides. Mitigation: §9's explicit
  requirement that the manifest schema's own field documentation state the weaker,
  statistical guarantee honestly, not merely inherit Stage 2's stronger language by
  omission.
- **Adjudication-threshold miscalibration silently skewing outcome labels.** An
  overly aggressive score-based adjudication cutoff (§6.1) would systematically bias
  the outcome half of every blended label toward whichever side's search score
  happened to look decisive at the cutoff ply, even in games that would have swung back
  — directly degrading exactly the outcome signal §7's blend depends on. Mitigation:
  the AlphaZero-style held-out-fraction false-positive measurement (§6.1, §16),
  performed empirically once real data exists, not assumed safe by construction.

---

## 19. Open Questions

These are architecture-relevant unknowns this document does not resolve, because
resolving them requires information (a real trained net, real measured self-play
throughput, real gauntlet/SPRT results across generations) that doesn't exist yet:

1. **What default λ (search-score vs. outcome blend weight) is right for Vex's own
   K-scale and data volume?** §7 explicitly declines to prescribe a number; this is an
   empirical sweep against the Validator's held-out correlation metric, not a design
   decision.
2. **What is the right game-length adjudication policy (move-count cap value,
   score-based threshold, sustained-ply count) for Vex specifically?** §6.1 names the
   mechanism (both together) but not the numbers, pending the same AlphaZero-style
   empirical false-positive calibration real data would require.
3. **What retraining cadence (regenerate self-play data after every retrain? every N
   retrains? only once Stage 1/2 data plateaus per ADR-001's revisit condition C)
   actually makes sense at Vex's single-host scale?** §11 explicitly leaves this open —
   AlphaZero's own near-continuous cadence assumes a compute scale wildly
   disproportionate to this project's environment.
4. **Is a single long-lived JVM instance's sequential game-generation throughput
   actually sufficient at whatever position volume ADR-001's "5-10x current volume"
   revisit condition eventually demands, or will multi-instance parallelism (§10, §16)
   become a real requirement sooner than assumed?** Unknowable without a measured
   single-instance baseline, exactly the same shape of open question D-8 §19 left for
   Stage 2's single-host throughput.
5. **Opening variety: engine-internal bounded-diversity substitution alone, an
   external opening book, or both together?** Explicitly left open by the PRD itself
   (§5 Open Questions item 3) and not narrowed further by this report beyond noting
   both are legitimate and non-exclusive (§16).
6. **Should Stage 3 ever feed back into Stage 2's Stockfish-labeling driver (e.g.,
   having Stockfish additionally label self-play-generated positions, blending three
   signals instead of two)?** Not raised anywhere in ADR-007 or the PRD, and not
   evaluated by this report — flagged here only so it isn't silently assumed in-scope
   by a future reader; a genuinely new architectural question if ever pursued, not an
   extension of anything this report already designs.

---

## 20. References

**Chess engines and self-play tooling** (findings verified against primary source
files and primary project documentation via direct inspection, not secondary
summaries):
- `nodchip/Stockfish`, `docs/gensfen.md` (self-play generation: opening randomization,
  quiet-position PV-walking, eval-limit truncation, output rotation)
- `official-stockfish/Stockfish` (mainline repository — reconfirmed no data-generation
  code exists in the current tree, matching D-8's own finding)
- `official-stockfish/nnue-pytorch`, `docs/nnue.md` and the project's documented
  training-loss design (WDL-space sigmoid conversion, λ-blend between search score and
  game outcome, K-scaling)
- `LeelaChessZero/lc0` (engine: UCI-exposed `DirichletNoise`/temperature-family
  options, self-play move-selection mechanics)
- `LeelaChessZero/lczero-client` (client/server self-play orchestration: subprocess
  lifecycle, network download, game upload)
- `lczero.org` — "Technical Explanation of Leela Chess Zero," "Training data format
  versions" (V6 struct field reference: `best_q`/`best_d`/`best_m`, `root_q`/`root_d`,
  `played_q`/`played_d`/`played_m`, `result_q`/`result_d`, `plies_left`, policy vector),
  "Best Nets for Lc0," project blog ("Lc0 training")
- `dje-dev/Ceres`, `dje-dev/CeresTrain` (reconfirmed: no public self-play generation
  pipeline; CeresTrain is a training platform offering an API for self-play
  experiments, not a documented, opinionated generator)

**Foundational reinforcement-learning literature:**
- Silver et al., "Mastering the game of Go without human knowledge" (AlphaGo Zero),
  *Nature* 550, 2017 — gating mechanism (55% win-rate promotion match), Dirichlet
  noise formulation, temperature schedule
- Silver et al., "A general reinforcement learning algorithm that masters chess,
  shogi, and Go through self-play" (AlphaZero), *Science* 362, 2018 — removal of the
  gating step in favor of continuous training from the latest checkpoint;
  chess-specific Dirichlet α scaling; resignation-threshold false-positive
  calibration methodology
- Sutton & Barto, *Reinforcement Learning: An Introduction* (2nd ed.) — TD(λ) and
  n-step return formalism, cited as the general ancestor of the simpler two-term
  blend actually used by every surveyed chess-specific system

**Project-internal context** (this project's own prior architectural decisions and
established conventions, referenced for continuity, not re-derived here):
`docs/architecture/research/DR-D8-stockfish-labeling-driver.md` (structural template
and Stage 2 precedent throughout this report), `docs/architecture/
NNUE_TRAINER_ARCHITECTURE.md` (§3 DatasetProvider, §4 feature parity, §9/§9.1/§9.2
provenance and manifest-schema policy, §10/§10.1 reproducibility, §15 Invariants),
`docs/NNUE_PRD.md` (§2 US-5 staged data plan, §3 Trainer Architecture table, §5 Phased
Rollout and Risks), `docs/adr/ADR-007-staged-training-data.md`,
`trainer/scripts/stockfish_label.py`, `trainer/trainer/dataset/stockfish_provider.py`,
`trainer/trainer/contracts/dataset.py`.

---

## Recommendations for Vex

**Recommended architecture.** An in-process self-play mode inside `engine-uci`/
`engine-core` (GameLoop, MoveSelector, QuietWalk, GameRecordWriter, §5-§6), writing an
engine-owned game-record format consumed by a thin Python ingestion script
(`selfplay_ingest.py`) that produces the existing mmap shard format, read by a new
`SelfPlayProvider(DatasetProvider)` structurally identical to `StockfishLabeledProvider`.
`[Known pattern]` — directly modeled on `gensfen`'s in-process precedent and this
project's own already-built Stage 1/2 `DatasetProvider` pattern.

**Component boundaries that must not blur:**
- The Java self-play game loop must never call into Python, and the Python ingestion
  script must never re-implement chess rules or search logic — the boundary is a
  file format, exactly like the `.nnue` contract itself. `[Known pattern]` — the same
  boundary-is-a-file-format principle already governing Invariant 8 and the `.nnue`
  contract, applied to a second artifact.
- `MoveSelector`'s bounded-diversity substitution is paradigm-matched to Vex's
  alpha-beta search (bounded multi-PV-window substitution, `gensfen`-style) and must
  not be designed as a port of MCTS-specific Dirichlet-noise-over-visit-counts
  mechanics — the single highest-consequence category-error risk this report
  identifies (§18). `[First principles]`
- The λ-blend belongs in the already-planned `Labeler` stage, shared across every
  data source that ever supplies both a search score and an outcome — not
  reimplemented inside `SelfPlayProvider` or the Java `GameRecordWriter`.
  `[Context-dependent]` — specific to this project already having that shared stage
  planned; a project without one would need to make blending the ingestion script's
  own responsibility.
- Generator-eligibility (does a net get used to generate the next cycle's data) and
  release-promotion (does a net become the shipped default) are two separate gates and
  must never be merged into one mechanism. `[Known pattern]` — directly inherited from
  AlphaZero's own documented departure from AlphaGo Zero's stricter design, and from
  lc0's live production split between ungated generation and a separate release-test
  pipeline.

**Invariants that must never be violated:**
1. The Java engine must never gain a Python/PyTorch dependency, at build or runtime —
   self-play mode's entire output surface is a documented file format, and nothing in
   this design requires or implies otherwise. `[Known pattern]` — directly inherited
   from this project's own already-frozen engine/trainer isolation invariant
   (Invariant 8), unchanged by Stage 3's existence.
2. Every self-play game record carries the generating network's UUID, captured from
   the loaded `.nnue` header at self-play-run start, never assumed constant across a
   long-running session without verification. `[First principles]` — the load-bearing
   provenance fact this report adds that Stage 2 never needed, since Stage 2's
   "engine identity" was never this project's own trained artifact.
3. Self-play reproducibility claims must be stated as statistical, never as bit-exact
   — any documentation or manifest schema implying Stage 3 carries Stage 2's stronger
   replay guarantee is a documentation defect to fix immediately, not a nuance to
   gloss over. `[First principles]` — a direct consequence of `MoveSelector`'s
   intentional randomness combined with this project's own existing "statistically
   equivalent, not bit-exact" posture for training (§10, architecture doc), now
   extended honestly to generation as well.
4. No `DatasetProvider` (including the new `SelfPlayProvider`) imports or depends on
   another `DatasetProvider`, or reaches into `Trainer`/`Quantizer`/`Exporter`
   internals. `[Known pattern]` — Invariant 1, unchanged, binding Stage 3 identically
   to Stage 1/2.

**What should intentionally NOT be implemented for Stage 3's initial version:**
- A generator-eligibility gate modeled on AlphaGo Zero's win-rate match. `[Known
  pattern]` — rejected in favor of AlphaZero's/lc0's ungated-generation precedent
  (§11, §17); Vex's existing SPRT release gate already serves the adjacent, genuinely
  necessary purpose.
- Full TD(λ)/n-step value-target blending. `[First principles]` — no surveyed
  chess-specific precedent implements it for this purpose; the simpler, already-cited
  two-term blend matches every real system surveyed and the PRD's own pre-existing
  `Labeler` design.
- MCTS-style Dirichlet noise over a visit-count distribution. `[First principles]` —
  structurally inapplicable to an alpha-beta searcher; `gensfen`'s bounded multi-PV
  substitution is the correct analog.
- Multi-host/distributed self-play coordination. `[First principles]` — no evidence
  any comparable single-maintainer-scale project needs this, and no evidence Vex's
  single-host throughput is actually insufficient yet, mirroring D-8's identical
  conclusion for Stage 2.
- A new, second shard/manifest format distinct from what Stage 1/2 already use.
  `[Known pattern]` — the engine-owned game-record format is genuinely new (§6), but
  everything downstream of `selfplay_ingest.py` reuses the existing mmap shard and
  manifest machinery unchanged.

**What should be deferred to later phases:**
- Multi-JVM-instance parallel self-play, if single-instance throughput ever proves
  genuinely insufficient at real measured data-volume targets (§10, §16, §19).
  `[Context-dependent]`
- Empirical calibration of λ, adjudication thresholds, and retraining cadence — all
  three are explicitly left as Open Questions (§19) requiring real self-play data and
  real gauntlet/SPRT results this report cannot fabricate. `[Context-dependent]`
- Opening-book-seeded starts as a complement to (not replacement for) engine-internal
  diversity, per the PRD's own still-open Question 3. `[Context-dependent]`
- A generator-eligibility gate, revisited only if real measured evidence shows the
  ungated default is actually producing harmful data — not preemptively.
  `[Context-dependent]`
