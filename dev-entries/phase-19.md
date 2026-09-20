# Dev Entries - Phase 19

---

### [2026-09-20] Phase 19 — PVS diagnostic (Issue #244)

**Built:**

- Started `phase/19-pvs-diagnostic` from `origin/develop` at `fb8179f26008612cbdbc9d54dfd16f687385c780`
  (the Phase 18 closure commit). Verified before branching: Phase 18 epic (#233) and all five
  slice issues closed; candidate `f9b152ca4f45e8e8aa5a48092b03416aba79b230` not an ancestor of
  `origin/develop`; the Phase 19 preregistration doc unchanged on `origin/develop`; no `phase/19`
  branch existing locally or remotely.
- Located the three authoritative Phase 17 Gate 4 artifacts, which are intentionally gitignored
  and absent from this repository's history: `tools/results/sprt_phase17-pvs_20260920_160513.{log,pgn}`
  and `tools/results/p17-4/20260920-103500/`, all on the Gate 4 host (RENEGADE) at
  `C:\Users\yashk\WorkDir\Projects\ChessEngine\chess-engine` (a separate checkout from this
  session's WSL working copy). Recomputed SHA-256 of every file and matched it exactly against
  `05-environment.json` and the copied `06a`/`06b` artifacts.
- Parsed the authoritative PGN (via `python-chess`, installed into a context-mode sandbox for the
  one execution) into 7 paired openings across the 14 scored games, and found the earliest
  move-sequence divergence within each pair by walking both games' shared starting FEN in
  lockstep. Selected 3 positions for deterministic replay: earliest possible divergence (Pair D,
  ply 1), the shortest decisive game and representative of a 3-pair ply-2 cluster (Pair G), and
  the deepest divergence in the set (Pair A, ply 7).
- Built git worktrees for both frozen commits (`ebe513e`, `f9b152c`) under the WSL toolchain
  (OpenJDK 21.0.12, Ubuntu build; historical build used Zulu OpenJDK 21.0.10 on Windows).
  Confirmed the rebuilt JARs reproduce the authoritative Gate 4 JARs' fixed-depth node count,
  score, and PV exactly on all three positions despite the different JDK vendor and JAR SHA-256.
- Drove all replays over UCI (`--add-modules jdk.incubator.vector`, `Threads=1`, `Hash=16 MB`,
  fixed depth 13 — the documented bench-suite default) via a small Python harness, with each
  position run twice per JAR to confirm determinism.
- Diffed `Searcher.java` between the two frozen commits and located the exact defect: the
  candidate's LMR re-search gate (`score > alpha && score < beta`) reuses the *enclosing node's
  own* `beta`, which equals `alpha + 1` at every non-PV node by construction of the PVS
  convention. `score > alpha && score < alpha + 1` cannot hold for any integer score, so the
  verification stage is architecturally unreachable at the majority of tree nodes.
- Added a purely additive diagnostic counter to an isolated worktree build of the candidate,
  confirmed it changes no search outcome (identical bestmove/nodes/PV vs. the unmodified
  authoritative candidate JAR), and measured 793/1,157/1,370 hole activations against only
  69/141/60 actual verifications across the three positions at depth 13.
- Built a third isolated worktree with the smallest possible counterfactual fix (the gate
  restored to `score > alpha` alone, matching pre-experiment baseline) and confirmed it changes
  the bestmove, score, node count, and PV at all three positions, re-enabling verification work
  by 10x to 35x.

**Decisions Made:**

- Stopped at Stop Condition A (preregistration Section 14): the hole activates at all three
  predeclared positions and a bounded counterfactual explains the decision/tree difference.
  Sections 10-12 of the bounded ladder (time-management, root/PVS bound trace, selective-search)
  were not needed.
- Did not commit any of the diagnostic-instrumented or counterfactual `Searcher.java` edits.
  They exist only in throwaway worktrees against the historical `ebe513e`/`f9b152c` commits
  (never staged into `phase/19-pvs-diagnostic`), and their evidence is fully captured as quoted
  diffs and measurements in the report and case study instead. Production `Searcher.java` at
  Phase 18 HEAD never had this defect (PVS/LMR verification was never merged), so there was
  nothing to revert in the branch itself.
- Filed the reusable methodology finding (a null-window verification gate that reuses the
  enclosing node's own beta) as a standalone case study under `docs/engineering/investigations/`
  per CLAUDE.md Section 7, separate from the phase-specific report under
  `docs/architecture/research/`.
- No Graphify refresh at phase close: no tracked source structure changed (no code committed).

**Broke / Fixed:**

- Nothing in production was broken or fixed. This phase is diagnostic-only against two historical,
  already-decided commits; Phase 17's rejection of `f9b152c` is unchanged.

**Measurements:**

- Artifact and run validation: all SHA-256 hashes matched; historical run confirmed 14 scored / 5
  cancelled, candidate 0W/13L/1D, LLR -3.1, bounds ±2.94, H0 accepted — reproducing the recorded
  Phase 17 result exactly.
- Depth-13 fixed-depth replay (baseline / candidate nodes): Pair D 1,122,769 / 434,834; Pair G
  431,284 / 452,460; Pair A 597,853 / 667,187. The aggregate ~44% Gate 1 node reduction is a
  whole-suite average, not universal per position (candidate uses more nodes than baseline at two
  of the three sampled positions).
- Counterfactual (gate fixed) vs. as-built candidate nodes: Pair D 1,017,174 vs 434,834; Pair G
  246,496 vs 452,460; Pair A 817,135 vs 667,187. Full evidence, tables, and the
  established/supported/unproven breakdown are in
  `docs/architecture/research/phase19-pvs-diagnostic-report.md`.
