# Dev Entries - Phase 18

---

### [2026-09-20] Phase 18 — P18-1 null-move/current-hash ownership (Issue #234)

**Built:**

- Started `phase/18-search-contract-qualification` from the exact merged
  `origin/develop` commit `090810d09f8fc25449697447e887fb6c36159afe` (PR #232).
- Refreshed the Graphify baseline at the phase boundary. The incremental scan
  found 47 changed code files and 68 changed documents; no tracked Graphify
  files changed.
- Added `BoardHashOwnershipTest` with five focused cases: ordinary
  make/unmake, null-child make/unmake, two null-subtree siblings,
  en-passant-sensitive null state, and null repetition-stack membership.
- The pre-fix focused run failed as required. The first failure was:
  `expected: <-716191127680950663> but was: <3916682029746613598>` at the
  post-null child make/unmake hash assertion. The sibling and EP-sensitive
  cases failed on the same invariant.
- Added `previousZobristHash` to the existing pooled `UnmakeInfo`, recorded it
  before ordinary move mutation, and restored it directly in `unmakeMove()`.
  `zobristStack` remains repetition history and is not advanced by null moves.
- Updated the five-position deterministic node-shape reference and the one
  equivalent KQK regression move whose deterministic TT path changed after the
  repair.

**Decisions Made:**

- Current-position hash ownership belongs to ordinary move undo state. The
  repetition stack records ordinary positions only.
- `unmakeMove()` no longer derives the current key from `zobristStack`. This
  is required because a null predecessor intentionally does not push a
  repetition entry.
- No SEE, singular, root-window, SMP, evaluator, PVS, or search-constant work
  was included.

**Broke / Fixed:**

- Fixed the source-proven post-null state mismatch. After null → ordinary
  child make/unmake, the board's incremental hash now equals the hash of its
  reconstructed current FEN.
- The initial full engine-core run correctly exposed the old node-count and
  one move regression baselines. Those deltas were recaptured only after the
  focused invariant passed and are documented below.

**Measurements:**

| Check | Result |
|---|---|
| Pre-fix `BoardHashOwnershipTest` | 3 hash assertions failed; 2 non-hash cases passed |
| Post-fix focused P18-1 test | 5 run, 0 failures |
| `mvn -pl engine-core test` | Passed after recording explained baselines |
| `mvn -pl engine-core,engine-uci,engine-tuner -am test` | 395 run, 0 failures, 5 skipped |
| `mvn -pl engine-core -Psearch-regression test` | 3 run, 0 failures; WAC 20/20, stability flips 2/20 |

Fixed-depth comparison at depth 8 with 16 MB TT:

| Position | Parent nodes/qnodes/TT | Repaired nodes/qnodes/TT | Parent → repaired move/score | Explanation |
|---|---:|---:|---|---|
| Start position | 15362 / 40517 / 4806 | 14989 / 39990 / 4909 | e2e4 / 25 → e2e4 / 25 | Null-subtree TT identities are corrected; tree shrinks 2.4% |
| K+P vs K | 1192 / 1998 / 736 | 1192 / 1998 / 736 | e2e4 / 122 → e2e4 / 122 | No null cutoff; unchanged |
| Tactical middlegame | 43425 / 100874 / 20058 | 36802 / 93698 / 14781 | b4b2 / −46 → b4b2 / −63 | Corrected null-subtree keys change TT reuse and PV continuation |
| Rook/pawn endgame | 9938 / 21365 / 2859 | 6491 / 14822 / 1952 | b4f4 / 14 → b4f4 / 14 | Corrected TT path reduces repeated work |
| Queen/king endgame | 8393 / 15385 / 3707 | 8903 / 16732 / 3982 | d7d3 / 1567 → d7d2 / 1565 | Corrected TT path changes an equivalent winning continuation |

The E1 KQK regression also changed from `f1b5` to `f1c4` at the same 1308 cp
score. Parent metrics were 32911 nodes, 73582 qnodes, 69274 TT hits, and 2
null cutoffs; repaired metrics were 38887 nodes, 84889 qnodes, 83791 TT hits,
and 12 null cutoffs. The new PV is
`f1c4 e8d7 c4d5 d7e7 d5c6`; both choices are winning KQK continuations.

The changed moves and tree shapes are expected consequences of repairing the
key used by null-subtree TT probes/stores. No unrelated source path changed.

---

### [2026-09-20] Phase 18 — P18-2 move-order / SEE metadata lifetime (Issue #236)

**Built:**

- Re-verified the post-#235 source at `f0d7369d349cfab8676ac4966d971c0f54cce55a`.
  `MoveOrderer` had one reusable `scoringBuffer`; all three ordering overloads
  wrote it, while `Searcher.alphaBeta` read a parent slot before each move's
  make. A recursive child ordering could overwrite that slot. The `<=1` early
  returns also leave the reusable metadata untouched.
- Added `MoveOrdererRecursiveMetadataTest`. It uses a parent with a later
  losing capture, orders an earlier TT-preferred sibling, recursively orders a
  child with enough moves to overwrite that slot, and exercises the actual
  `canPruneLosingCapture` gate.
- The pre-repair test failed with:
  `parent metadata must survive child ordering and remain losing ==> expected: <true> but was: <false>`.

**Decision:**

- Selected Option A: one fixed score vector per active search ply. The existing
  DFS move-list ownership model already provides the matching lifetime. At
  `128 * 256` integers this is 128 KiB per `Searcher`, with no per-node heap
  allocation and no repeated SEE evaluation. Option B would recompute SEE at
  every eligible shallow non-PV capture-pruning candidate; a temporary depth-8
  diagnostic counted 4,972 / 31 / 16,274 / 1,212 / 2,785 candidates over the
  five deterministic positions, so it was not chosen as the default design.

**Repair:**

- `MoveOrderer` now owns `scoringBuffers[ply][moveIndex]`, and all overloads
  write the vector for their supplied ply. `Searcher` reads the same bounded
  ply vector after ordering. SEE scoring, move ranking, pruning thresholds, and
  constants are unchanged.

**Validation:**

- The focused regression passes after the repair (1 run, 0 failures).
- Fixed-depth depth-8 comparison against the exact post-P18-1 parent changed
  only search shape: nodes/qnodes/TT were respectively
  `14989/39990/4909 → 14926/39927/4908`,
  `1192/1998/736 → 1192/1998/736`,
  `36802/93698/14781 → 34694/88266/14691`,
  `6491/14822/1952 → 6456/14776/1938`, and
  `8903/16732/3982 → 8902/16736/3982`.
  Move, score, and PV were unchanged for all five positions. The node-shape
  deltas are the expected removal of stale parent pruning metadata; no move or
  score delta remains unexplained.
- No P18-1, singular-search, root-window, SMP, evaluator, PVS, or search
  constant behavior was modified.

---

### [2026-09-20] Phase 18 — P18-3 singular-search bound semantics (Issue #238)

**Lineage and source check:**

- PR #237 passed CI and merged into `develop` as
  `0dd3cd89c7f9bbd5c80e737544e825518736939d`. The rejected Phase 17 branch
  remains outside the production ancestry.
- On that source, singular verification is eligible only for a sufficiently
  deep exact/lower-bound TT entry with a best move, outside singular search,
  check, and mate windows. The caller window is independent of the diagnostic
  window. Verification uses `singularAlpha = ttScore - margin`,
  `singularBeta = singularAlpha + 1`, reduced depth `depth / 2`, and searches
  alternatives with `[-singularBeta, -singularAlpha]`.
- The old `SingularityOutcome.failHigh` meant only that an alternative reached
  `singularBeta`; the caller incorrectly returned its own `beta` from that
  diagnostic result.

**Red regression:**

- The controlled position uses caller `alpha=0`, `beta=100`, TT score `50` at
  depth 8, and the existing margin of 54, giving `singularBeta=-3`. A scripted
  alternative returns `-2`, so it disproves singularity while remaining below
  caller beta. Before repair the actual `alphaBeta` path returned `100` and the
  test failed: `expected: not equal but was: <100>`.

**Repair:**

- Replaced the ambiguous boolean pair with `SingularityResult.SINGULAR`,
  `NOT_SINGULAR`, and `ABORTED`.
- `SINGULAR` is returned only when every searched alternative stays below the
  diagnostic threshold and continues to schedule the existing TT-move
  extension. `NOT_SINGULAR` continues the ordinary caller move loop. It never
  returns caller beta. `ABORTED` preserves the existing enclosing abort return
  and cannot be treated as proof of singularity.
- No singular margin, depth threshold, extension amount, TT eligibility rule,
  or other search constant changed.

**Validation:**

- Focused singular tests: 4 run, 0 failures, covering disproved singularity,
  verified singularity, and abort classification.
- Cross-slice focused tests: 49 run, 0 failures, including P18-1 and P18-2.
- `mvn -pl engine-core test`: 400 run, 0 failures, 5 skipped.
- `mvn -pl engine-core,engine-uci,engine-tuner -am test`: all reactor modules
  successful.
- Search regression profile: 3 run, 0 failures; WAC 20/20 and stability 0/20
  flips in this run. No fixture or threshold was changed.
- Against the exact post-P18-2 parent, all five depth-8 moves, scores, PVs, and
  completed depths were unchanged. Nodes/qnodes/TT were unchanged as well:
  `14926/39927/4908`, `1192/1998/736`, `34694/88266/14691`,
  `6456/14776/1938`, and `8902/16736/3982` respectively. No singular-specific
  counters existed, so no broad telemetry was added.
- P18-4 root fail-high behavior remains untouched.

---

### [2026-09-20] Phase 18 — P18-4 root fail-high termination and window validity (Issue #240)

**Lineage and source check:**

- PR #239 passed CI and merged into `develop` as
  `72284330c904f24b0015180e3eb99369a0f82874`.
- `searchRoot` constructs each child window as `[-beta, -alpha]`. It updates
  the root best score, best move, and PV before updating alpha, but previously
  continued to the next sibling after `alpha >= beta`. The next child could
  therefore receive a closed or inverted window. The aspiration wrapper already
  recognized the returned root score as fail-high and retained its widening and
  full-window fallback logic.

**Red regression:**

- `RootFailHighWindowTest` uses a controlled K+P position and a scripted
  evaluator. The first root child returns 20 against root `[0,10]`; before the
  repair, the actual `searchRoot` path evaluated the second sibling once. The
  focused assertion failed with:
  `root must not invoke another sibling after alpha reaches beta ==> expected: <0> but was: <1>`.

**Repair:**

- After the existing best-score/best-move/PV update and alpha update,
  `searchRoot` now breaks when `alpha >= beta`.
- The aspiration wrapper, retry widths, retry count, full-window fallback,
  abort handling, MultiPV exclusions, and Syzygy root path are unchanged.

**Validation:**

- Focused root/aspiration/abort tests: 4 run, 0 failures.
- Cross-slice focused tests: 53 run, 0 failures, including P18-1 through P18-3.
- `mvn -pl engine-core test`: 404 run, 0 failures, 5 skipped.
- `mvn -pl engine-core,engine-uci,engine-tuner -am test`: all reactor modules
  successful.
- Search regression profile: 3 run, 0 failures; WAC 20/20 and stability 0/20.

Fixed-depth depth-8 comparison against the exact post-P18-3 parent:

| Position | Parent move/score | Repaired move/score | Parent nodes/qnodes/TT | Repaired nodes/qnodes/TT |
|---|---|---|---:|---:|
| Start | e2e4 / 25 | e2e4 / 25 | 14926 / 39927 / 4908 | 14926 / 39927 / 4908 |
| K+P vs K | e2e4 / 122 | e1d2 / 122 | 1192 / 1998 / 736 | 1226 / 1816 / 1024 |
| Tactical middlegame | b4b2 / −63 | b4b2 / −63 | 34694 / 88266 / 14691 | 34694 / 88266 / 14691 |
| Rook/pawn | b4f4 / 14 | b4f4 / 14 | 6456 / 14776 / 1938 | 6456 / 14776 / 1938 |
| Queen/king | d7d2 / 1565 | d7d2 / 1565 | 8902 / 16736 / 3982 | 8902 / 16736 / 3982 |

The K+P divergence begins at depth 6 after an aspiration fail-high; depths 1–5
match, and the final score remains identical. E1 also diverges through a depth-4
fail-high: parent `f1c4 / 1308` with 38887 nodes becomes repaired
`f1f6 / 1303` with 27978 nodes. These are explained by removing the old invalid
window sibling searches and their TT/PV side effects; both are winning KQK
continuations. The E1 deterministic fixture was updated with that explanation.

No rollback or transactional search state was added, and no P18-5 work began.
