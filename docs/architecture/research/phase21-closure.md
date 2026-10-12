# Phase 21 Closure

Date: 2026-10-11

Phase 21 qualified a search correction that ties principal variation (PV) identity to the alpha-beta window, then measured its deterministic search behavior, native throughput, and match strength. The exact production candidate is source commit [e90d3d4f90b244c46e8bbf75c33222bba7d58003](https://github.com/coeusyk/chess-engine/commit/e90d3d4f90b244c46e8bbf75c33222bba7d58003). Release preparation did not change search code.

## Search contract and implementation

Alpha-beta window width already defines whether a node is a PV node: a window wider than one centipawn is a PV window, while a width-one window is a null window. The prior Searcher accepted a separate isPvNode boolean, so callers could describe a node differently from the window it actually searched. This left PV-only re-search decisions dependent on two sources of truth.

The candidate removes that boolean from the private alpha-beta signature and derives PV identity from beta - alpha > 1 at entry. The first move searches with the incoming window. Later siblings use a full-depth null-window probe; LMR moves receive a full-depth null-window verification after a non-aborted fail-high. A full-window re-search occurs only when that verified score is strictly inside the current PV window. The root follows the same PVS sequence inside its existing aspiration window. Pruning, move ordering, transposition-table policy, evaluation, SMP policy, and time management were not changed by this correction.

Regression coverage includes window-derived instrumentation, root fail-high handling, singular-search bound semantics, LMR verification, and legal PV replay in search-regression fixtures. The final focused Stage 2 run passed 15 tests; the core module passed 407 tests with 5 configured skips, and the regression group passed 38 tests.

## Deterministic and decision-quality gates

Stage 3 used the frozen 31-position depth-13 corpus and five depth-8 reference positions. Each search used a fresh Searcher and 16 MB transposition table. The candidate harness completed every requested depth and checked returned PV legality.

| Metric | Frozen control | Candidate | Candidate delta |
|---|---:|---:|---:|
| Main nodes | 24,780,049 | 21,713,284 | -3,066,765 (-12.376%) |
| Quiescence nodes | 60,071,833 | 48,479,879 | -11,591,954 |
| Transposition-table hits | 5,726,579 | 6,134,980 | +408,401 |

Stage 3 passed. The aggregate node reduction is a deterministic search result, not a wall-clock throughput or playing-strength measurement. The [Stage 3 results table](phase21-stage3-results.tsv) has SHA-256 728c1f5cf8254606af4a61504805f88d0c350ef13a5428b4593f479f191fc56d.

Stage 4 traced the eight positions whose depth-13 best move changed; the other 23 best moves matched. All 32 fresh depth-14 and depth-15 searches completed and returned legal PVs. A separate no-search replay confirmed all 16 frozen depth-13 control and candidate PVs were legal. The trace found no illegal PV, mate-sign, unverified-score, or root-window defect. Some scores and moves varied with depth; those observations were classified as search-tree sensitivity, not correctness failures. The [Stage 4 trace](phase21-stage4-decision-trace.tsv) has SHA-256 e5470c9d6b08e86633d78db331ce3d22e68d1e4d5dd75e71f3d56ff4da31ee45.

## Native throughput

Stage 5 passed on the native Windows host. The candidate's median elapsed time was 62.732 seconds versus 71.795 seconds for control, a 12.6234% improvement. Candidate median and aggregate NPS both cleared the 301,116 gate. This is the throughput result; it is separate from the Stage 6 strength result. The [Stage 5 review](phase21-stage5-native-throughput-review.md) records the environment, all 16 invocations, identities, and calculations.

## Match-strength result

The accepted native SPRT used bounds [0,10], alpha = beta = 0.05, time control 5+0.05, and one thread per engine. The successful run was 20261011T061318503Z-b5a507c6. Its checksum manifest and 28 listed artifacts were independently reconciled in evidence commit 8085d99d5b1da7eaa652b9930a5a874fee609c74.

The result was 13,118 scored games: NEW 4,080 wins, 3,829 losses, and 5,209 draws. LLR was 2.97, above the 2.94 upper boundary; H1 was accepted. The estimated Elo difference was +6.6 +/- 4.6. **H1 was accepted under [0,10]; this does not prove a minimum +10 Elo gain.** Five cancelled games were recorded as no result and were not included in the scored-game total.

The exact candidate production source was e90d3d4f90b244c46e8bbf75c33222bba7d58003; the control source was d3a56ffadf0d9151a2b19fba4902a734a99bff96. The accepted run recorded candidate JAR SHA-256 4018a032ca64b80b588a9d0912f5fdac366424089c6750a08c2ef6723665d875 and control JAR SHA-256 b54ed03dc6960352c299927a405c89a9b550b9583079aa6bcf883e4c1dc014f2. The run's frozen opening corpus SHA-256 was 2011193b4854e9a8cfdc05312ca2dbaffa6ceae3abbdee20e2ead2a18a603347. Its full command, executable, JAR, option, and environment identities are in the preserved [identity manifest](../../../tools/results/phase21-stage6/20261011T061318503Z-b5a507c6/identity.json) and [checksum list](../../../tools/results/phase21-stage6/20261011T061318503Z-b5a507c6/SHA256SUMS).

## Provenance qualification

The original Stage 6 machine record for the earlier attempt, 20261011T061219485Z-4d354d25, shows six game starts and SETUP_PROTOCOL_FAULT. No finished-game events were preserved, and the attempt has no complete PGN, summary, or recorded process exit cause. The absence of finished-game events is not proof that zero games finished. The exact failure mechanism, process exit details, and any outcomes that were not recorded cannot be reconstructed.

Separately, the maintainer later stated: “The first SPRT attempt encountered an error, so I reran it.” This is a retrospective operator statement, not a field in the original successful-run record. The evidence cannot establish whether any unrecorded result from the earlier attempt influenced the restart decision. The maintainer explicitly accepted this documented limitation and authorized release preparation. The original Stage 6 evidence and its history remain unchanged.

## Version and release record

The release proposal is v0.6.0, with all four Maven modules kept on the same version. The next development version after release is 0.6.1-SNAPSHOT. The current release workflow builds a draft from an exact tag and records the source commit and executable JAR SHA-256; publication still requires maintainer review.
