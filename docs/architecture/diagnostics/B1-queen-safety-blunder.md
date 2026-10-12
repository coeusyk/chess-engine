# Diagnostic Note: B1 — Queen-Safety Blunder in E-5 SPRT Game

**Date:** 2026-07-16
**Source:** Real game from the issue #205 (E-5) SPRT run (`tools/sprt.ps1`, `-NewOptions
'EvalType=NNUE'` vs. `-OldOptions 'EvalType=Classical'`, custom opening-book start
position, TC=60+0.6). Native Windows run, provided by the user for review.
**Regression test:** `SearchRegressionTest.queenSafetyRegression_B1` / `B1_FEN`
(engine-core).
**Related issue:** #181 (SPRT PGN post-processing pipeline) — reviewed, see verdict below.

## The game

```
[White "NEW"] [Black "OLD"] [Result "0-1"]
[FEN "r1bqkbnr/ppppp2p/2n3p1/5p2/8/6PP/PPPPPPB1/RNBQK1NR w KQkq - 0 1"]

1. d4 d5 2. Bf1 e6 3. Be3 a6 4. c3 Nf6 5. Nf3 Rb8 6. a3 b6 7. Qd2 Qe7
8. Bh6 Bxh6 9. Qxh6 Qf8 10. Nbd2 {+0.60/13} Qxh6 {+11.44/14}
11. e3 Bb7 12. c4 Nd8 13. Rc1 O-O 14. c5 Kh8 15. Ne5 0-1 (adjudicated)
```

At move 9 White recaptures on h6 with the queen. At move 9...Qf8, Black's queen lands
on f8, directly attacking h6 via the open f8-g7-h6 diagonal (g7 is empty in this
opening-book line). White's queen on h6 is completely undefended — no pawn or piece
covers that square. White's move 10 (Nbd2, `NEW` = the NNUE-mode engine) is a quiet
developing move that ignores this, and Black's 10...Qxh6 wins the queen for free — the
eval swings from +0.60/13 (White's own reported score after Nbd2) to +11.44/14 (Black's
score after capturing).

Verified directly with `python-chess` (not just read off the SAN): `Qf8` (`e7f8`) does
attack `h6`, `Qxh6` (`f8h6`) is a legal capture, and no White piece defends h6 in the
resulting position.

## Reproduction attempts

Using the actual build (`engine-uci-0.5.8-SNAPSHOT.jar`, same commit range as E-4/E-5,
unchanged since `3e7558e`) and the real E-3 candidate net
(`dfffd3da-7f8f-4fc9-92dc-b3873c97fb21.nnue`):

1. **Isolated critical FEN, NNUE mode, cold process, `go depth 13`:** at every depth
   from 1 through 12, the search's PV starts with a queen-retreat move
   (`h6g5`/`h6f8`/`h6f4`/`h6h4`). `Nbd2` never appears as a candidate. The search did
   not reach depth 13 within a practical timeout in this environment (this
   environment's NPS is far below the project's native-Windows baseline — roughly
   90-110k NPS here vs. the documented 316,964 NPS native baseline).
2. **Full game replay with a per-move `go nodes 30000` before the critical position
   (simulating a warm transposition table / history / killer-move state, as in a real
   game rather than a cold one-shot search), then `go depth 13` at the critical
   position:** same result — depth 12 reached (`h6f8`), no trace of `Nbd2` at any
   depth, and still did not reach depth 13 in this environment.
3. **Same critical FEN, Classical mode (the production evaluator), `go depth 10`:**
   same pattern — `h6f4`/`h6c1`/`h6f8`/`h6e3` at every depth, never `Nbd2`.

**The blunder was not reproduced at any depth this session could practically reach
(1-12), under either evaluator, cold or with a simulated warm search state.** The real
game's own annotation claims depth 13 was reached in 2.1 seconds — a rate this
environment cannot match (its own depth-12 searches took 14-23 seconds), so a
depth-13 reproduction attempt here was not completed. This is the one open gap in this
diagnosis: the failure is only confirmed at depth 13, on faster/different hardware,
and has not been directly reproduced under controlled conditions.

## Categorization

Against the candidate causes:

- **Evaluator weakness** — **evidence against.** Both NNUE and Classical, tested
  directly and independently on the exact critical position, correctly identify the
  hanging queen and evacuate it at every depth tested. If this were a static
  evaluation blindness in either evaluator, it should show up at shallow depths too —
  it doesn't, in either evaluator.
- **Move ordering / pruning** — **plausible, unconfirmed.** The correct move (a queen
  retreat) is consistently the top candidate at every depth 1-12 in every test run,
  which argues against a simple move-ordering defect (the refutation is found, not
  buried). A depth-13-specific pruning/reduction interaction (LMR, null-move, futility)
  that only misfires one ply deeper than tested here remains a real candidate, but is
  unconfirmed.
- **Quiescence** — **unlikely.** A queen-for-nothing capture is about the simplest
  possible quiescence case (highest-value capture, no intervening tactics); if
  quiescence were missing this class of tactic broadly, it would likely show up at
  shallow depths too, which it doesn't.
- **SEE / exchange evaluation** — **unlikely but not ruled out.** No SEE miscalculation
  was directly tested; the failure mode (ignoring a hanging queen via a *non-capture*
  move) is not the class of bug SEE typically governs (SEE evaluates captures, not
  "is my own piece hanging to a future capture").
- **Time management** — **unlikely.** 2.1s at TC=60+0.6 with plenty of clock remaining
  is not a time-pressure regime; nothing in the PGN suggests the search was cut off
  early.
- **Transposition-table interaction** — **plausible, unconfirmed, leading hypothesis
  alongside pruning.** The real game's engine process ran continuously across the
  whole game, accumulating TT/killer/history state over nine prior real searches
  before this decision — a scenario this session's reproduction attempts only
  partially simulated (node-limited warm-up, not full real searches at matching
  depth). A hash-key collision or a stale/incorrectly-aged TT entry specific to this
  unusual custom opening-book position (not a standard start position, less exercised
  by existing test coverage) remains untested as a direct cause.
- **Search depth limitations** — **not really applicable as its own category here** —
  the failure needs *more* depth (13) to manifest, not less; this is closer to a search
  *instability* (a deeper search returning a worse move than every shallower one) than
  a "search isn't deep enough" problem.
- **Another engine-side issue** — see TT/pruning above; both remain open.

**Best-supported conclusion:** this looks like a genuine search-side instability
specific to depth 13 (or to the accumulated search state a real game produces),
**not an NNUE evaluator weakness** — direct, controlled testing shows both evaluators
handle this exact tactic correctly whenever it was possible to test. Root-causing
precisely (pruning/reduction vs. TT collision) requires either faster hardware to
reach depth 13 directly, or search instrumentation (logging which heuristic
eliminated the `Qxh6`-refuting line) — both are follow-up engine-side work, not
something this diagnostic pass fixes.

## Issue #181 assessment

Re-read from scratch (`gh issue view 181`). Status: **open**, unimplemented (owner's
own 2026-07-07 comment: "No implementation started on this issue"). Scope: a
PowerShell pipeline (`tools/sprt_pgn_pipeline.ps1`) to mine *all* SPRT PGNs at scale for
(a) a quiet-position Texel corpus extension, (b) a blunder corpus, (c) opening-coverage
stats — explicitly scoped to the **classical Texel corpus** consumer
(`engine-tuner`'s KFinder/tuning pipeline), and explicitly confirmed independent of the
NNUE roadmap by the owner's own alignment-check comment.

**#181 is not "this bug"** — it's a proposed automation pipeline for harvesting many
such positions at scale, not a description of any specific blunder. Today's finding is
one manually-discovered blunder from one game, handled here with a direct, targeted
regression test — building #181's full pipeline to handle a single data point would be
scope creep, not a proportionate response.

**Verdict: still open, still relevant as a concept, not actionable now.** #181 remains
a reasonable Phase-14-scoped tooling improvement whenever SPRT-PGN volume from Phase E
(or elsewhere) makes manual review impractical — this single blunder doesn't meet that
bar. No change to #181 recommended; it stays queued, unblocked by anything in this
diagnostic.

## Decision

- **Blocking vs. diagnostic artifact vs. noise:** **useful diagnostic artifact**, not a
  blocker. It does not block E-5 or any other current Phase E roadmap item.
- **Regression test:** **added** — `SearchRegressionTest.queenSafetyRegression_B1`
  (`B1_FEN`), asserting the engine moves the queen off h6 rather than leaving it
  hanging, at `DEFAULT_DEPTH` (8). This is a regression *floor* (locks in known-good
  shallow-depth behavior), not a fix verification — the depth-13 anomaly itself remains
  unreproduced and open.
- **Phase E:** **continues unchanged.** This finding does not invalidate or block E-5's
  SPRT/release-report process — if anything, it's consistent with (and gives extra
  diagnostic depth to) the already-decisive E-4 gauntlet loss for the current candidate
  net. The SPRT itself is doing exactly its job by surfacing this. No redesign, no
  retraining, no roadmap change triggered by this finding.
- **Follow-up (not started here):** a properly scoped engine-side investigation into
  the depth-13 search instability (pruning/reduction audit or TT-collision check),
  ideally reproduced directly at depth 13 on faster hardware. Worth its own issue if
  pursued — out of scope for this triage pass.
