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
