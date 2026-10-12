# Phase 19 diagnostic report: PVS fixed-depth versus strength divergence

Status: closed. Diagnostic complete under the preregistered bounded ladder.
Governing document: `docs/architecture/research/phase19-pvs-diagnostic-preregistration.md`.

This report does not reopen or alter the Phase 17 disposition. Candidate
`f9b152ca4f45e8e8aa5a48092b03416aba79b230` remains rejected; H0 remains
accepted; no code from this diagnostic is promoted or merged into production
search behavior.

## 1. Artifact validation

All three authoritative Gate 4 artifacts were located outside this
repository's Git history, on the Gate 4 host (RENEGADE), as recorded in
`docs/architecture/research/phase17-closure.md`:

- `tools/results/sprt_phase17-pvs_20260920_160513.{log,pgn}`
- `tools/results/p17-4/20260920-103500/` (environment record, copied
  authoritative artifacts, and the two Gate 4 match JARs)

SHA-256 of every file matched `05-environment.json` exactly: candidate JAR
`0399b3d8a33e67b77cc3d7b24c8a695f91c2152dd56461cac76b2287b7be2b9f`, baseline
JAR `ef9c791234c129ccb13d4227d86541a94684856cf564ede6c07e465fdfac9c56`,
openings file `2011193b4854e9a8cfdc05312ca2dbaffa6ceae3abbdee20e2ead2a18a603347`.
The original log/pgn and the copied `06a`/`06b` artifacts are byte-identical.
Candidate and baseline frozen commits both equal their recorded "actual"
commits.

## 2. Historical run validation

19 games total: 14 scored, 5 cancelled after the SPRT boundary (`*`,
"unterminated"). All 14 scored games ended by adjudication or 3-fold
repetition; no crash, illegal move, timeout, or forfeit appears anywhere in
the log. Candidate (NEW) scored 0 wins, 13 losses, 1 draw. LLR -3.1, bounds
[-2.94, +2.94], H0 accepted. This reproduces the recorded Phase 17 result
exactly.

The 14 scored games form exactly 7 paired openings (confirmed by grouping on
the `FEN` tag), each played twice with NEW and OLD swapping colors. The
candidate lost with both colors in 6 of 7 pairs; the 7th pair was a draw with
the candidate as White and a loss with the candidate as Black.

## 3. 14-game divergence summary

Because each pair shares an identical starting FEN, the earliest ply where
the two games' move sequences differ is the earliest position where the
candidate's decision differs from the baseline's decision at an identical
board state — no separate FEN-normalization step was needed beyond that.

| Pair | Divergence ply | Side to move | Candidate's move | Baseline's move | Capture/check |
|---|---|---|---|---|---|
| A | 7 | White | Bd3 | Be2 | no |
| B | 4 | Black | Nf6 | Be7 | no |
| C | 3 | White | e3 | e4 | no |
| D | 1 | White | a3 | f3 | no |
| E | 2 | Black | Nf6 | d5 | no |
| F | 2 | Black | d5 | e5 | no |
| G | 2 | Black | Be7 | Ba6 | no |

Divergence is always early (ply 1-7) and always a quiet, non-capture,
non-check move at the divergence point itself. Three of seven pairs (E, F, G)
diverge at exactly ply 2 on a Black developing-move choice. This is a
descriptive clustering only; it does not by itself identify a mechanism.

## 4. Selected positions

Three positions were predeclared for deterministic replay, chosen for range
and the repeated pattern rather than for loss severity:

- **Pair D, ply 1** (earliest possible divergence):
  `r1bqkbnr/1pp1pppp/n2p4/p7/2P5/1P2P3/P2P1PPP/RNBQKBNR w KQkq - 0 1`
- **Pair G, ply 2** (representative of the repeated ply-2 cluster; also the
  shortest decisive game in the set):
  `rnbqk1nr/p1pp1ppp/1p2p3/8/1b1P4/2PQP3/PP3PPP/RNB1KBNR b KQkq - 0 1`
- **Pair A, ply 7** (deepest divergence, for contrast):
  `r1bqkb1r/1p2pp1p/p1np1np1/2p5/4P3/1PN2N2/PBPP1PPP/R1Q1KB1R w KQkq - 0 4`

## 5. Deterministic replay

Worktrees were built for both frozen commits under the current WSL toolchain
(OpenJDK 21.0.12, Ubuntu build) rather than the historical Zulu OpenJDK
21.0.10 on Windows. Despite the different JDK vendor and a different
resulting JAR SHA-256, each rebuilt JAR reproduced the authoritative Gate 4
JAR's fixed-depth node count, score, and PV exactly on all three sample
positions, confirming the rebuild is behaviorally equivalent. All results
below were also confirmed deterministic by two independent runs per JAR per
position (identical nodes/score/PV both times).

Fixed depth 13 (the documented bench-suite default in `CLAUDE.md`), Threads=1,
Hash=16 MB, driven over UCI (`--add-modules jdk.incubator.vector`):

| Position | Baseline nodes / score / bestmove | Candidate nodes / score / bestmove |
|---|---|---|
| Pair D, ply 1 | 1,122,769 / +28 / g2g3 | 434,834 / +56 / d2d4 |
| Pair G, ply 2 | 431,284 / -14 / c8a6 | 452,460 / -40 / b4f8 |
| Pair A, ply 7 | 597,853 / -49 / h2h3 | 667,187 / -22 / f1e2 |

The candidate uses fewer nodes than baseline only at Pair D; at Pair G and
Pair A it uses slightly more. The aggregate ~44% node reduction recorded in
Phase 17's Gate 1 is a whole-suite average, not a universal per-position
effect. Bestmove and PV differ from baseline at every position, as expected
since these are the actual historical game-divergence points.

## 6. LMR/PVS verification-hole activation

Diffing the candidate's `Searcher.java` against baseline isolates the change
precisely (`f9b152c`, engine-core/.../search/Searcher.java). The candidate
added a three-stage PVS scheme for LMR-reduced moves:

- Stage 1: reduced-depth null-window probe (unchanged from before the
  experiment).
- Stage 2 (new): if Stage 1's score exceeds `alpha`, verify at full depth,
  still under a null window, gated by `score > alpha && score < beta`.
- Stage 3 (new): if Stage 2's score is still inside `(alpha, beta)`, re-search
  at the full window.

The Stage 1-to-2 gate uses `beta` — the *enclosing node's own* window bound —
as the upper bound of the check. At any non-PV node, by construction of the
PVS/null-window convention, `beta = alpha + 1` at that node. Substituting:
`score > alpha && score < alpha + 1` cannot hold for any integer `score`,
since `score > alpha` already implies `score >= alpha + 1`. **The Stage 2
verification is therefore architecturally unreachable at every non-PV node**,
which is the majority of nodes in the tree (a PV node is, by definition, one
node per ply along the current best line; everything else is non-PV). The
identical pattern appears in the new "ordinary PV-sibling" branch
(`isPvNode && moveIndex > 0`), but that branch only executes when the current
node genuinely is a PV node — which by definition carries a wide window
(`beta - alpha > 1`) — so the degenerate case essentially does not occur
there. The root-level PVS addition uses the root's real aspiration-window
`beta`, not `alpha + 1`, and is not implicated.

When the Stage 1-to-2 gate fails, execution falls through the `if` with no
`else`, so `score` still holds Stage 1's reduced-depth value, which is then
treated as though it had been verified.

### Activation measurement

A purely additive, temporary counter (`pvsHoleActivations`, incremented
whenever a null-window probe fails high at a node where
`beta - alpha == 1`) was added to an isolated worktree build of `f9b152c` and
verified to change no search outcome: instrumented and uninstrumented builds
of the candidate produced identical bestmove, node count, and PV on all three
sample positions.

| Position | Depth-13 verifications actually taken | Hole activations (blocked) |
|---|---|---|
| Pair D, ply 1 | 69 | 793 |
| Pair G, ply 2 | 141 | 1,157 |
| Pair A, ply 7 | 60 | 1,370 |

At every sample position, the verification gate was blocked far more often
than it passed (8x to 23x). **The hole activates at all three predeclared
positions.**

## 7. Bounded counterfactual

Per the preregistration, a bounded counterfactual was run since the hole
demonstrably activates. The smallest possible fix was applied in a third
isolated worktree: the Stage 1-to-2 gate was changed from
`score > alpha && score < beta` to `score > alpha` alone, matching the
pre-experiment baseline condition and the semantically correct behavior
(Stage 2 verifies against the same null window as Stage 1, and is unrelated
to the enclosing node's own window). Nothing else was changed: Stage 2-to-3,
the ordinary-PV-sibling branch, and the root-level PVS addition were left
untouched.

| Position | As-built candidate | Counterfactual (gate fixed) | Verifications taken (as-built -> fixed) |
|---|---|---|---|
| Pair D, ply 1 | 434,834 nodes / +56 / d2d4 | 1,017,174 nodes / +64 / b1c3 | 69 -> 2,438 |
| Pair G, ply 2 | 452,460 nodes / -40 / b4f8 | 246,496 nodes / -12 / b4e7 | 141 -> 666 |
| Pair A, ply 7 | 667,187 nodes / -22 / f1e2 | 817,135 nodes / -46 / f1d3 | 60 -> 1,946 |

Restoring the correct gate changes the bestmove, score, node count, and PV at
all three positions, and re-enables 10x to 35x more verification work. The
effect isn't just "more search everywhere": Pair D's node count moves much
closer to baseline's after the fix, while Pair G's moves further below it.
That's consistent with a real change in how information flows through move
ordering, not a uniform slowdown. The counterfactual candidate doesn't match
baseline at any position, which is expected — it still carries the
candidate's other changes, PVS at ordinary later PV-siblings and at the root,
neither of which baseline has. Matching baseline was never the point of this
counterfactual; the point was whether fixing the gate changes anything at
all, and it clearly does.

## 8. Stop condition

**Stop Condition A is met**: the LMR/PVS verification hole activates at all
three predeclared historical divergence positions, and a bounded deterministic
counterfactual (a single-line gate fix) materially changes the candidate's
own decision and tree at all three positions. Per the preregistration, the
diagnostic stops here. Sections 10-12 of the bounded ladder (time-management,
root/PVS bound trace, selective-search interactions) were not reached and
were not needed.

## 9. Confounding

Phase 18's four production repairs (null-subtree hash ownership, recursive
SEE metadata lifetime, singular diagnostic/caller-beta separation, root
fail-high continuation) exist in neither the historical candidate nor the
historical baseline. Nothing in this report compares the candidate to
Phase 18 production; every comparison here is strictly between the two
frozen Phase 17 commits.

## 10. Established / supported / unproven

**ESTABLISHED:**

- The candidate's Stage 1-to-2 LMR re-search gate (`score > alpha && score <
  beta`) is architecturally unreachable at any node where the enclosing
  window is null (`beta = alpha + 1`), which is the standard condition at
  every non-PV node under the PVS convention.
- This hole activates hundreds to over a thousand times per position at
  fixed depth 13, at all three predeclared historical divergence positions,
  measured via an additive instrumentation counter confirmed not to change
  the candidate's search outcome.
- A single-line fix restoring the gate to `score > alpha` alone materially
  changes the candidate's own bestmove, score, node count, and PV at all
  three positions, and re-enables the previously-blocked verification work
  by 10x to 35x.

**SUPPORTED:**

- The mechanism is consistent with both halves of the Phase 17 mystery: the
  removed verification searches plausibly explain a meaningful share of the
  ~44% node reduction, and trusting unverified reduced-depth scores at the
  majority of tree nodes plausibly degrades move ordering and score accuracy
  in a way consistent with a strength loss.
- Divergence from baseline is always early and always at a quiet, non-tactical
  move, consistent with a systemic evaluation/search-quality effect rather
  than a one-off tactical blunder.

**UNPROVEN:**

- That this mechanism is the sole or dominant cause of the specific 0-13-1
  Gate 4 match result. Establishing that would require running games or an
  SPRT with the corrected gate, which this preregistered diagnostic
  explicitly does not authorize.
- Whether the counterfactual-fixed candidate would itself pass a strength
  gate. It is not a rescue candidate; it was built and run only to test
  whether the gate's arithmetic degeneracy has a real effect on search
  decisions, and that question alone was answered.

## 11. What should happen after Phase 19

This diagnostic is closed. Phase 17's candidate remains rejected and is not
resurrected. If a future phase wants to explore a corrected PVS/LMR
implementation, it would need its own preregistration, its own Gate 1-4
sequence, and its own SPRT — this diagnostic provides motivation and a
starting hypothesis for that future work, nothing more. No SMP qualification
work is authorized by this report.
