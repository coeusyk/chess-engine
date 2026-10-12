# Vex v0.5.7 vs v0.6.0: search work at fixed depth

Status: deterministic stage complete. Native timing stage completed on RENEGADE; qualification is inconclusive pending a repeat run.
Date: 2026-10-12. Branch: `research/release-comparison-v0.5.7-v0.6.0` (local, not pushed).

## Summary

At depth 13 on the 30 benchmark positions the two releases share, v0.6.0 searches **65.65% fewer main nodes** than the published v0.5.7 release (59,793,662 down to 20,540,625). Quiescence nodes drop 73.27%. Node counts are deterministic: two passes per release on a 29-position subset gave identical moves, scores, nodes, TT hits and PVs, and the 30-position numbers below come from one pass per release. This is the cumulative change in search work over the 241 commits between the two release sources. The Phase 21 figure (12.38% fewer main nodes) is one slice of it.

These node counts are search work, not speed or strength. A smaller tree can cost less, but it can also come from more aggressive pruning. In 12 of 30 positions the releases choose different best moves at depth 13. Native timing on RENEGADE later showed a large elapsed-time reduction, though run-to-run variability crossed the pre-set 5% investigation threshold. The timing comparison is therefore inconclusive until repeated. Neither result measures Elo.

One problem with the release record turned up during the preflight and shapes everything below: the `v0.5.7` git tag does not point at the source of the published v0.5.7 release.

## Release identities

| Item | v0.5.7 | v0.6.0 |
|---|---|---|
| Tag | lightweight tag `v0.5.7` | annotated tag `v0.6.0` (object `bb4810de9cb5f8e57719d284071ef909930be9a4`) |
| Tag commit | `7b5b3a4332193d28a1f668654806fac434f2cb8f` (2026-04-19, merge of PR #179, POM version 0.5.7-SNAPSHOT) | `bc3dc380e759526e8aa19cd8385af16ab22ac502` |
| Published asset | `engine-uci-0.5.7.jar`, 2,185,506 bytes, SHA-256 `bc41c91b0d3b4c20f6a334ff3f0cc2cf5c04e6820b817b1cfd46e62a640663d3` | `engine-uci-0.6.0.jar`, 2,301,950 bytes, SHA-256 `e16163b8fe6f8278161db0d9220883c5d49a4814df0d81f7a6cad901c07cf4e7` |
| Source of the published asset | `beaef361d35160d768473af7d2c349719db8508b` ("chore(release): v0.5.7", 2026-07-07) | `bc3dc380e759526e8aa19cd8385af16ab22ac502` |

How I tied each asset to a source: I rebuilt each candidate commit in its own detached worktree and compared the SHA-256 of every `.class` file inside the shaded JAR. The per-class listings and the asset hashes are archived in [`identity/`](identity/) so this can be checked without redoing the builds: unzip a JAR, run `find . -name '*.class' | LC_ALL=C sort | xargs sha256sum | diff - identity/classes-<name>.sha256`. JARs are not byte-reproducible (timestamps and the build JDK differ), so whole-file hashes do not match, but the class contents can.

| JAR | Class-set digest (first 16 hex) |
|---|---|
| published v0.6.0 | `6941e838c507f18f` |
| rebuilt from `bc3dc38` | `6941e838c507f18f` (same) |
| published v0.5.7 | `77cd88805a63d380` |
| rebuilt from `beaef36` | `77cd88805a63d380` (same) |
| rebuilt from tag commit `7b5b3a4` | `8af6956e7a689e54` (different) |

The per-class listings are identical for the published v0.5.7 JAR and the `beaef36` rebuild (736 classes), and for the published v0.6.0 JAR and the `bc3dc38` rebuild (791 classes). The tag-commit rebuild differs from the published v0.5.7 JAR in 13 classes. So the published v0.5.7 JAR was built from `beaef36`, which is 37 commits after the commit the `v0.5.7` tag names. The tagged commit is the Phase 13 state. The release includes the Phase 14 eval work merged in July. Behavior confirms it: the published JAR and the tag build produce different node counts on the same positions (54,546,076 and 58,104,689 over the same 29 positions). The tag cannot be moved (it is covered by the immutable-tag ruleset), so this is recorded here and not corrected. Anything that builds "v0.5.7" from the tag gets different code than users downloaded.

**This report uses the published v0.5.7 JAR (source `beaef36`) as the v0.5.7 arm.** The tag build is kept as a supplementary row.

## Preflight: what is and is not comparable

- **Benchmark corpus.** The tag commit ships a 33-position `BenchRunner` corpus. `beaef36` and `v0.6.0` each ship 31 positions, and those two sets are not the same: they differ in one position (slot 15). The comparison therefore uses the **30 positions common to both**, in the v0.6.0 order (`common-corpus-30-release-vs-v0.6.0.fen`). The v0.6.0 `BenchRunner` totals (21,713,284 main nodes over 31 positions) and the v0.5.7 ones (77,265,370 over its own 31) are not comparable to each other and are not compared.
- **Harness.** Each release's own `--bench` prints different things: v0.6.0 has an instrumentation switch and prints timing shares, v0.5.7 does not. I did not use them for the comparison. I adapted the existing `Phase21ControlHarness` into `tools/ReleaseComparisonHarness.java`, which takes the FEN list from a file, calls the instrumentation switch by reflection when it exists (turned off), and otherwise runs the same API calls on both: a fresh `Searcher` per position, `setTranspositionTableSizeMb(16)`, `searchDepth(board, 13)`, PV legality check. The `SearchResult` record has the same node, quiescence-node and TT-hit fields in both releases.
- **Node definition.** `nodesVisited` is incremented in the same place in both releases (after the null-move block, once per interior node). Quiescence nodes use a separate counter.
- **Settings.** Classical evaluation (v0.5.7 has no NNUE; v0.6.0 defaults to Classical), one thread, 16 MB hash, depth 13, empty transposition table for every position, JVM flags `-Xms512m -Xmx512m -XX:+UseG1GC --add-modules jdk.incubator.vector`. The UCI `Hash`, `Threads` and `Contempt` options exist in both. One behavioral difference sits outside this harness: the UCI default for `Contempt` is 50 in v0.5.7 and 0 in v0.6.0. The in-process `Searcher` default is 0 in both, so the harness runs them equally, but real games through the UCI interface at default settings would not.
- **Validation of the harness.** Run on v0.6.0's own 31-position corpus it reproduces the Phase 21 Stage 3 candidate numbers exactly: 21,713,284 main nodes, 48,479,879 quiescence nodes, 6,134,980 TT hits.
- **Determinism.** Two passes per release on the 29-position set gave identical moves, scores, node counts, TT hits and PVs for both. Elapsed times are recorded in the raw files but come from WSL2 and are not used anywhere in this report.
- **What was measured.** The v0.5.7 arm ran the published `engine-uci-0.5.7.jar` itself. The v0.6.0 arm ran a local rebuild of `bc3dc38`, whose class set matches the published JAR.

## Results (published v0.5.7 vs v0.6.0, 30 common positions, depth 13)

| Metric | v0.5.7 | v0.6.0 | Change |
|---|---:|---:|---:|
| Main nodes | 59,793,662 | 20,540,625 | -65.65% |
| Quiescence nodes | 169,860,333 | 45,406,213 | -73.27% |
| Main + quiescence | 229,653,995 | 65,946,838 | -71.28% |
| TT hits | 7,330,427 | 5,866,705 | -19.97% |
| Quiescence per main node | 2.84 | 2.21 | |
| TT hits per main node | 0.123 | 0.286 | |

Per position, the main-node ratio has a geometric mean of 0.491 and a median change of -46.7%. 25 of 30 positions search fewer nodes. Five search more: positions 26 (+9%), 2 (+12%), 12 (+14%), 24 (+41%) and 25 (+126%). The aggregate is not dominated by one position: dropping the largest v0.5.7 contributor (position 1, 18% of the total) changes the result from -65.65% to -65.85%.

TT hits fall in absolute terms but rise per node visited. I did not check that the two releases count a "hit" the same way, so I would not read more into that than the raw counts.

Supplementary, on the 29 positions that all three builds share (the tag build, the published v0.5.7 JAR, and v0.6.0): tag build 58,104,689 main nodes, published v0.5.7 54,546,076 (6.1% fewer than the tag), v0.6.0 19,475,298 (64.3% fewer than the published v0.5.7, 66.5% fewer than the tag build).

### Per position

`*` marks a different best move. Positions are numbered in the common-corpus order; `per-position-nodes.tsv` also has quiescence nodes, TT hits and the index in each release's own corpus.

| # | v0.5.7 nodes | v0.6.0 nodes | change | v0.5.7 move (score) | v0.6.0 move (score) |
|---:|---:|---:|---:|---|---|
| 0 | 816,160 | 503,502 | -38.3% | h2h4 (-17) | e2e4 (26) * |
| 1 | 10,539,826 | 3,719,319 | -64.7% | d5e6 (-155) | e2a6 (-120) * |
| 2 | 8,095 | 9,081 | +12.2% | e1d2 (165) | e1d2 (160) |
| 3 | 2,238,772 | 856,243 | -61.8% | c4c5 (-529) | c4c5 (-532) |
| 4 | 914,578 | 421,737 | -53.9% | b5c4 (-35) | b5c4 (-46) |
| 5 | 1,181,529 | 983,092 | -16.8% | b2b4 (411) | g1h1 (410) * |
| 6 | 8,578,717 | 532,753 | -93.8% | a8d8 (-71) | b4b2 (-31) * |
| 7 | 1,495,384 | 761,682 | -49.1% | g2g4 (-280) | f3d4 (-300) * |
| 8 | 5,247,586 | 1,065,327 | -79.7% | h2h3 (-7) | b3c2 (17) * |
| 9 | 931,565 | 224,527 | -75.9% | c6g6 (-153) | c6g6 (-108) |
| 10 | 2,168,248 | 371,551 | -82.9% | c8d7 (419) | c8d7 (445) |
| 11 | 148,821 | 113,241 | -23.9% | e4f3 (577) | e4f3 (584) |
| 12 | 17,361 | 19,767 | +13.9% | g6f5 (-413) | g6f5 (-375) |
| 13 | 381,589 | 189,541 | -50.3% | b4f4 (0) | b4f4 (11) |
| 14 | 1,130,945 | 777,022 | -31.3% | c3d5 (143) | c3d5 (139) |
| 15 | 3,694,776 | 639,344 | -82.7% | d5e4 (-39) | f6e4 (-38) * |
| 16 | 1,490,840 | 452,728 | -69.6% | c7c5 (-49) | a6a5 (-49) * |
| 17 | 1,750,457 | 1,206,922 | -31.1% | g1h3 (337) | e2e3 (383) * |
| 18 | 1,366,958 | 381,362 | -72.1% | a5c3 (628) | a5c3 (640) |
| 19 | 292,184 | 169,381 | -42.0% | d7d2 (1588) | d7d2 (1608) |
| 20 | 2,934,922 | 1,759,077 | -40.1% | g8h7 (-38) | g6g5 (-35) * |
| 21 | 1,260,512 | 612,390 | -51.4% | d4b5 (39) | d4b5 (23) |
| 22 | 310,298 | 172,663 | -44.4% | g5d8 (654) | g5d8 (688) |
| 23 | 79,689 | 53,667 | -32.7% | d5e3 (504) | d5e3 (509) |
| 24 | 154,324 | 216,932 | +40.6% | h5e2 (1529) | h5e2 (1594) |
| 25 | 21,463 | 48,453 | +125.8% | b5b4 (282) | b5b4 (281) |
| 26 | 59,191 | 64,657 | +9.2% | a6a5 (-73) | a6a5 (-118) |
| 27 | 1,738,540 | 1,442,772 | -17.0% | a5a6 (144) | d2b4 (267) * |
| 28 | 6,068,366 | 1,789,736 | -70.5% | c6d4 (552) | c6d4 (557) |
| 29 | 2,771,966 | 982,156 | -64.6% | f2e3 (-122) | b1a2 (-121) * |

## Best-move and search divergences

12 of 30 positions end depth 13 with a different best move. Across all 30 positions only one full PV is identical. Scores move by a median of 14 cp (maximum 123 cp), and no score changes sign when both are beyond 20 cp. Several of the differing moves carry nearly the same score in both releases (positions 5, 15, 16, 20 and 29 differ by 3 cp or less), which fits a tie between near-equal moves. The largest score changes are position 27 (144 against 267 cp, with a different move, 123 cp), position 24 (1594 against 1529 cp, same move, 65 cp) and position 17 (337 against 383 cp, different move, 46 cp). Two other divergences are worth a look: position 0, the start position (h2h4 at -17 against e2e4 at +26), and position 6 (a8d8 at -71 against b4b2 at -31), which is also the biggest node cut at 94%. Whether the new move is better is not something this data can say. That needs a strength test.

Position 25 is the one large increase (+126%). It is a candidate for a closer look in a later phase, since the new search spends more there than the old one.

## What this does and does not show

- It shows the v0.6.0 search visits about a third of the interior nodes and about a quarter of the quiescence nodes that the v0.5.7 release does at the same depth on these positions.
- The node comparison alone does not show v0.6.0 is faster. The native timing stage below measures elapsed time, but remains inconclusive because of run-to-run variability.
- It does not show v0.6.0 is stronger. The only strength evidence in the repository is the Phase 21 SPRT against its own immediate control (+6.6 +/- 4.6 Elo, H1 accepted under [0,10]). There is no match between v0.5.7 and v0.6.0, and node counts cannot stand in for one.
- It does not cover Lazy SMP, fixed-time behavior or NNUE. The fixed-time comparison in the task was not run: it needs real games or timed searches under a clock, and doing it properly belongs with the native run.
- 30 positions is a small, hand-picked corpus. The per-position spread is wide (-94% to +126%).
- The deterministic node comparison ran in WSL2 (Linux 6.18.33.2, AMD Ryzen 7 7700X, OpenJDK 21.0.12.1, Maven 3.8.7 for the rebuilds). The native timing run on RENEGADE is reported separately below.

## Native timing on RENEGADE

The native run completed on 2026-10-12 on RENEGADE, using the published v0.5.7 and v0.6.0 JAR class contents. The complete output is in [`tools/results/release-comparison-20261012/`](../../../../tools/results/release-comparison-20261012/). The native build JAR hashes and published asset hashes are both recorded below; the whole JAR hashes differ because the build artifacts are not byte-reproducible, while the class listings match the published assets.

### Evidence integrity and settings

`SHA256SUMS` verifies all 36 listed files: `environment.json`, `run.log`, `runs.csv`, `summary.json`, and the 32 raw files. The source Windows directory and the copy in this worktree both pass `sha256sum -c SHA256SUMS`. The manifest does not hash itself or `work/harness-classes/ReleaseComparisonHarness.class`; that compiled helper is not a timing output. There is no `FAILED.txt` or `CLEANUP-FAILED.txt` marker. The run log records both published-class listing matches, two pre-run warm-up passes, all 14 measured runs, and normal completion.

All 16 raw TSV files contain 30 positions. Their per-position main-node sums equal the expected total for their arm: 59,793,662 for A and 20,540,625 for B. All 14 measured CSV rows also have the expected node totals, with seven rows per arm. Every stderr file has the expected depth 13, hash 16 MB, contempt 0, one thread, 30 positions and warm-up depth 8. A uses `Evaluator` with instrumentation absent; B uses `ClassicalEvaluator` with instrumentation off. The evaluator class names differ across releases, but both arms use the classical evaluator. Both use the same corpus, JVM flags, depth, hash size, contempt, thread count and harness.

The recorded harness SHA-256 matches the repository file after Windows CRLF normalization; the script hash also matches after CRLF normalization. The corpus hash matches the LF-normalized corpus file exactly. The recorded repository head is `da81d3f3232cb4a7d726a236a851112e05db1444`, the commit used by this branch for the native runner.

| Arm | Published asset SHA-256 | Source commit | Native build JAR SHA-256 | Published class listing matched |
|---|---|---|---|---|
| A, published v0.5.7 | `bc41c91b0d3b4c20f6a334ff3f0cc2cf5c04e6820b817b1cfd46e62a640663d3` | `beaef361d35160d768473af7d2c349719db8508b` | `df6125bbe87d05d3d9a449fe21e162b3fc7c6db71dd1041b1ce1f2919d62f4ba` | yes, 736 classes |
| B, published v0.6.0 | `e16163b8fe6f8278161db0d9220883c5d49a4814df0d81f7a6cad901c07cf4e7` | `bc3dc380e759526e8aa19cd8385af16ab22ac502` | `367229071e44482bdb62bf6980af8f8a1cfef791e7c31947152e1381be571af6` | yes, 791 classes |

The environment record identifies RENEGADE as Windows 10.0.26300, Ryzen 7 7700X, 16 logical processors and 31.2 GB RAM, on the Balanced power scheme. It records Java 21.0.10 (Azul), Maven 3.9.16 and PowerShell 7.6.6. The pre-run idle check sampled CPU 15 times: mean 0.65%, maximum 4.73%, against the 5% limit. The harness did not record CPU or other host activity during the measured runs, nor JVM garbage-collection events.

### Every measured run

The order alternated A-first and B-first. The timestamps are each run's start time from `run.log`; elapsed time and NPS are from `runs.csv`. No measured run was removed.

| Pair | Order | Run start UTC | Arm | Main nodes | Elapsed ms | NPS |
|---:|---|---|---|---:|---:|---:|
| 1 | A then B | 10:05:08.8479485 | A, v0.5.7 | 59,793,662 | 165,057.7 | 362,259 |
| 1 | A then B | 10:07:56.1876283 | B, v0.6.0 | 20,540,625 | 55,046.4 | 373,150 |
| 2 | B then A | 10:08:53.4153819 | B, v0.6.0 | 20,540,625 | 55,430.3 | 370,567 |
| 2 | B then A | 10:09:50.9756060 | A, v0.5.7 | 59,793,662 | 196,373.0 | 304,490 |
| 3 | A then B | 10:13:09.7101236 | A, v0.5.7 | 59,793,662 | 158,974.6 | 376,120 |
| 3 | A then B | 10:15:50.9593848 | B, v0.6.0 | 20,540,625 | 52,416.1 | 391,876 |
| 4 | B then A | 10:16:45.3787544 | B, v0.6.0 | 20,540,625 | 54,401.6 | 377,573 |
| 4 | B then A | 10:17:41.8108736 | A, v0.5.7 | 59,793,662 | 212,401.0 | 281,513 |
| 5 | A then B | 10:21:16.5873560 | A, v0.5.7 | 59,793,662 | 195,593.7 | 305,703 |
| 5 | A then B | 10:24:34.5588440 | B, v0.6.0 | 20,540,625 | 56,167.7 | 365,701 |
| 6 | B then A | 10:25:32.8041197 | B, v0.6.0 | 20,540,625 | 55,755.0 | 368,408 |
| 6 | B then A | 10:26:30.5987706 | A, v0.5.7 | 59,793,662 | 168,273.9 | 355,335 |
| 7 | A then B | 10:29:21.1465966 | A, v0.5.7 | 59,793,662 | 170,818.8 | 350,041 |
| 7 | A then B | 10:32:14.2456716 | B, v0.6.0 | 20,540,625 | 55,799.7 | 368,113 |

### Timing summary

MAD is the unscaled median absolute deviation. CV is the sample standard deviation divided by the mean. The max/min column is the maximum divided by the minimum, minus one. All values are recalculated from `runs.csv`, rather than taken only from `summary.json`.

| Release | Median elapsed ms | Mean elapsed ms | Min ms | Max ms | MAD ms | CV | Max/min spread | Median NPS |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Published v0.5.7 | 170,818.8 | 181,070.4 | 158,974.6 | 212,401.0 | 11,844.2 | 11.14% | 33.61% | 350,041 |
| Published v0.6.0 | 55,430.3 | 55,002.4 | 52,416.1 | 56,167.7 | 383.9 | 2.32% | 7.16% | 370,567 |

The ratio of the independent medians is **0.3245** (55,430.3 / 170,818.8). The median of the seven paired B/A elapsed-time ratios is **0.3267**, a 67.33% reduction. The paired timing results are:

| Pair | Order | v0.5.7 ms | v0.6.0 ms | B/A ratio | Difference, B minus A ms |
|---:|---|---:|---:|---:|---:|
| 1 | A then B | 165,057.7 | 55,046.4 | 0.3335 | -110,011.3 |
| 2 | B then A | 196,373.0 | 55,430.3 | 0.2823 | -140,942.7 |
| 3 | A then B | 158,974.6 | 52,416.1 | 0.3297 | -106,558.5 |
| 4 | B then A | 212,401.0 | 54,401.6 | 0.2561 | -157,999.4 |
| 5 | A then B | 195,593.7 | 56,167.7 | 0.2872 | -139,426.0 |
| 6 | B then A | 168,273.9 | 55,755.0 | 0.3313 | -112,518.9 |
| 7 | A then B | 170,818.8 | 55,799.7 | 0.3267 | -115,019.1 |

### Variability and possible causes

The v0.5.7 max/min spread is 33.61%, and v0.6.0's is 7.16%. Both exceed the existing 5% investigation threshold. The paired ratios range from 0.2561 to 0.3335. All seven favor v0.6.0 by a large margin, but their spread is not within a few percent.

There is an order association in the small sample. When A ran first (pairs 1, 3, 5 and 7), its median was 167,938.3 ms. When it ran second (pairs 2, 4 and 6), its median was 196,373.0 ms, about 16.9% higher. B's median was nearly unchanged by order: 55,423.1 ms when second and 55,430.3 ms when first. Pair 5 is a slow A-first run, and pair 6 is a normal A-second run. The sequence is not a steady upward or downward drift: A's two slowest runs are pairs 4 and 5, followed by lower values in pairs 6 and 7.

- **Execution-order or temporal effect, moderate confidence as an association, low confidence as a cause.** The slower A median when second is observed, but the sample is small and the exceptions prevent attributing the difference to order itself.
- **CPU contention or other host activity, low confidence.** The pre-run idle check passed. No CPU or host-activity data was collected during timing, so contention cannot be confirmed or ruled out as the cause.
- **JVM warm-up bias, low confidence.** Each run uses a fresh JVM and the same untimed depth-8 in-process warm-up before the timed depth-13 search; two additional warm-up runs precede measured pairs. This reduces cold-start and file-cache effects, but the records do not show whether that depth settles JIT compilation equally for both versions. There is no systematic time trend across the seven pairs.
- **Garbage collection, unobserved.** G1GC is enabled, but no GC logging was collected, so there is no evidence linking collection to the slower runs.

The timer sums per-position search elapsed time in the harness. It does not include process startup and teardown, and this result should not be compared directly with Phase 21 Stage 5 whole-process timings. It measures elapsed time for this 30-position, single-threaded, fixed-depth workload. The node reduction is deterministic search work; NPS is throughput per searched node; neither is playing strength.

### Qualification and next run

**INCONCLUSIVE.** The direction is consistent and large: every pair has a B/A ratio below 0.34, and both the ratio of medians and median of paired ratios are close. The precision is limited by v0.5.7's high variation, v0.6.0 also crossing the 5% threshold, and the order association. The runner's existing instruction says to treat this threshold breach as noisy and repeat the whole procedure in a fresh output directory. One repeat is therefore warranted before calling this a qualified release-level elapsed-time comparison.

The smallest useful follow-up is one fresh seven-pair run on RENEGADE with the existing runner, same settings and integrity checks, in a fresh output directory. The runner begins A-first and alternates each pair. Do not start it until the operator approves and confirms RENEGADE is idle. Accept the repeat only if both arms' max/min spread is at most 5%, the paired B/A ratios have a max/min spread at most 5%, all 14 node totals and settings match, and all checksums and cleanup checks pass. Also require every paired ratio to remain below 0.40. If the noise criteria still fail, report the result as qualified with limitations only if the per-pair direction remains consistent; otherwise another measurement is needed.

## Does the v0.6.0 release page need a benchmark section?

I would not publish a benchmark section from this run yet. The speedup is large, but both arms cross the recorded 5% variability threshold and the paired ratios vary with execution order. After a repeat meets the acceptance criteria, a supplementary section could report both search work and elapsed time, identify the 30-position depth-13 setup, and say explicitly that this is not a playing-strength result. Suggested wording, conditional on that repeat, is:

> In a native Windows fixed-depth benchmark over 30 positions shared by the published v0.5.7 and v0.6.0 JARs, v0.6.0 searched 20.54 million main nodes versus 59.79 million and had a median elapsed time of 55.4 seconds versus 170.8 seconds across seven alternating pairs. This benchmark measures search work and elapsed time for the stated setup; it does not measure playing strength.

Do not edit the published release page from this research task. The stale `v0.5.7` tag is a separate provenance issue and should not be folded into a speed claim.

## Reproducing the deterministic stage

```bash
git worktree add --detach wt/rel0.5.7 beaef361d35160d768473af7d2c349719db8508b
git worktree add --detach wt/v0.6.0   v0.6.0
(cd wt/rel0.5.7 && mvn -B -q -pl engine-core,engine-uci -am package -DskipTests)
(cd wt/v0.6.0   && mvn -B -q -pl engine-core,engine-uci -am package -DskipTests)
mkdir hc && javac --add-modules jdk.incubator.vector \
  -cp wt/v0.6.0/engine-uci/target/engine-uci-0.6.0.jar -d hc tools/ReleaseComparisonHarness.java
C=docs/architecture/research/release-comparison-v0.5.7-v0.6.0
for J in wt/rel0.5.7/engine-uci/target/engine-uci-0.5.7.jar wt/v0.6.0/engine-uci/target/engine-uci-0.6.0.jar; do
  java -Xms512m -Xmx512m -XX:+UseG1GC --add-modules jdk.incubator.vector -cp hc:$J \
    ReleaseComparisonHarness $C/corpus-<own>.fen 13 16 > out.tsv
done
```

The `<own>` corpus for each JAR is the 31-position file in this directory (`corpus-v0.5.7-release-own31.fen`, `corpus-v0.6.0-own31.fen`). The 30 shared positions are joined by FEN. The builds were done with `-DskipTests` because the task is to measure the shipped search, not to re-verify the test suite. The v0.5.7 numbers in this report come from the downloaded GitHub release asset, run with the same command.

## Files

| File | Content |
|---|---|
| `tools/ReleaseComparisonHarness.java` | Deterministic fixed-depth driver, adapted from `Phase21ControlHarness` |
| `tools/release-comparison.ps1` | Native Windows timing runner |
| `tools/tests/test_release_comparison.py` | Contract checks for the runner, harness, corpus and recorded totals |
| `common-corpus-30-release-vs-v0.6.0.fen`, `common-corpus-29.fen` | Shared position sets |
| `corpus-v0.5.7-release-own31.fen`, `corpus-v0.6.0-own31.fen` | Each release's own `BenchRunner` corpus |
| `corpus-index-map.tsv` | Index of each 29-set position in the tag-commit and v0.6.0 corpora |
| `per-position-nodes.tsv` | Primary per-position results (published v0.5.7 vs v0.6.0, 30 positions) |
| `per-position-nodes-tag-7b5b3a4-vs-v0.6.0-common29.tsv` | Supplementary: tag build vs v0.6.0, 29 positions |
| `native-run-instructions.md` | How to run and validate the native timing run |
| `v0.5.7-tag-provenance.md` | Why the `v0.5.7` tag does not match the published JAR, with the evidence |
| `identity/` | Published asset hashes (`assets.txt`) and per-class SHA-256 listings for the published and rebuilt JARs |
| `raw-wsl-deterministic/` | Raw harness output for every run, with `SHA256SUMS` |
| `tools/results/release-comparison-20261012/` | Native Windows run metadata, all measured and warm-up outputs, and `SHA256SUMS` |

