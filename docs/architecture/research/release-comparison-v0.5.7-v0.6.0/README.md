# Vex v0.5.7 vs v0.6.0: search work at fixed depth

Status: deterministic stage complete. Two native timing runs completed on RENEGADE; qualified with limitations for this fixed-depth workload.
Date: 2026-10-12. Branch: `research/release-comparison-v0.5.7-v0.6.0`.

## Summary

At depth 13 on the 30 benchmark positions the two releases share, v0.6.0 searches **65.65% fewer main nodes** than the published v0.5.7 release (59,793,662 down to 20,540,625). Quiescence nodes drop 73.27%. Node counts are deterministic: two passes per release on a 29-position subset gave identical moves, scores, nodes, TT hits and PVs, and the 30-position numbers below come from one pass per release. This is the cumulative change in search work over the 241 commits between the two release sources. The Phase 21 figure (12.38% fewer main nodes) is one slice of it.

These node counts are search work, not speed or strength. A smaller tree can cost less, but it can also come from more aggressive pruning. In 12 of 30 positions the releases choose different best moves at depth 13. Two native runs on RENEGADE show a repeated elapsed-time reduction of about 67% for this benchmark, but both runs still cross the documented 5% variability investigation threshold. The result is qualified with limitations for this workload only. Neither result measures Elo or general gameplay speed.

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
- Node counts do not measure speed. The native runs below measure elapsed time for this fixed-depth workload; their variability limits how broadly the result can be stated.
- It does not show v0.6.0 is stronger. The only strength evidence in the repository is the Phase 21 SPRT against its own immediate control (+6.6 +/- 4.6 Elo, H1 accepted under [0,10]). There is no match between v0.5.7 and v0.6.0, and node counts cannot stand in for one.
- It does not cover Lazy SMP, fixed-time behavior or NNUE. The fixed-time comparison in the task was not run: it needs real games or timed searches under a clock, and doing it properly belongs with the native run.
- 30 positions is a small, hand-picked corpus. The per-position spread is wide (-94% to +126%).
- The deterministic node comparison ran in WSL2 (Linux 6.18.33.2, AMD Ryzen 7 7700X, OpenJDK 21.0.12.1, Maven 3.8.7 for the rebuilds). The native timing run on RENEGADE is reported separately below.

## Native timing on RENEGADE

Two native runs completed on 2026-10-12 on RENEGADE. The first used A-first measured pairs; the second used `-StartWith B`. Each run timed builds whose class listings match the published v0.5.7 and v0.6.0 JARs. The first output is in [`tools/results/release-comparison-20261012/`](../../../../tools/results/release-comparison-20261012/), and the second is in [`tools/results/release-comparison-20261012-repeat-bfirst/`](../../../../tools/results/release-comparison-20261012-repeat-bfirst/). The native build JAR hashes differ from the published whole-JAR hashes because those artifacts are not byte-reproducible; the class listings match.

### Evidence integrity and settings

Each `SHA256SUMS` verifies all 36 listed files: `environment.json`, `run.log`, `runs.csv`, `summary.json`, and 32 raw files. The manifest does not hash itself. Neither completed directory contains `FAILED.txt` or `CLEANUP-FAILED.txt`. The compiled `work/harness-classes/ReleaseComparisonHarness.class` that the runner leaves behind is a build output and is not part of the archived evidence (it is not tracked; `tools/ReleaseComparisonHarness.java` and its SHA-256 in `environment.json` identify the code). The files were written on Windows with CRLF line endings and the manifests hash those bytes, so each results directory carries a `.gitattributes` (`text eol=crlf`, except `SHA256SUMS`) that makes a checkout on any platform reproduce them. After a checkout, `sha256sum -c SHA256SUMS` run inside the directory should report every file OK.

One earlier attempt at the second run, in `tools/results/release-comparison-20261012-repeat/`, ends at the start of warm-up A (11:12 UTC), with no raw output and no `FAILED.txt`. The reason is not recorded. It has no timing data, is not committed (the directory is untracked), and is not used anywhere in this report. The completed second run is the `-repeat-bfirst` directory. Both logs record the two published-class listing matches, warm-ups A and B, all 14 measured runs, and normal completion.

All 16 raw TSV files contain 30 positions. Their per-position main-node sums equal the expected total for their arm: 59,793,662 for A and 20,540,625 for B. All 14 measured CSV rows also have the expected node totals, with seven rows per arm. Every stderr file has the expected depth 13, hash 16 MB, contempt 0, one thread, 30 positions and warm-up depth 8. A uses `Evaluator` with instrumentation absent; B uses `ClassicalEvaluator` with instrumentation off. The evaluator class names differ across releases, but both arms use the classical evaluator. Both use the same corpus, JVM flags, depth, hash size, contempt, thread count and harness.

The two runs have the same harness SHA-256 (`803978d418bf8c0ad349f63c18de23945e17145bc889b13c74ec072f77039b06`) and LF-normalized corpus SHA-256 (`3544a8fbb6f79e4f3c153f979a98da4b62c4380a98e0d8b7dc0259992341e560`). The runner SHA differs because the second run used the added `-StartWith` parameter: `f7256d71c3aeb576ec705662cee48b1b9357b11886ca7eef615e8bd372261cfd` for the first run and `058374c377ad7b8ce4044e38ffbcf203021b91211d85d865d78d9a69c14a8935` for the second. Each is the SHA-256 of the script bytes on disk during that run: the first matches `da81d3f`'s `tools/release-comparison.ps1` with CRLF line endings, the second matches `2d7032b`'s with LF endings (the same file with CRLF endings would hash to `7d1eca1755e1...`). Check against the committed blob with the matching line endings. The recorded repository heads are `da81d3f3232cb4a7d726a236a851112e05db1444` and `2d7032bea100449c8811327f3825f640e67383a4` respectively.

| Arm | Published asset SHA-256 | Source commit | First run build JAR SHA-256 | B-first run build JAR SHA-256 | Published class listing matched |
|---|---|---|---|---|---|
| A, published v0.5.7 | `bc41c91b0d3b4c20f6a334ff3f0cc2cf5c04e6820b817b1cfd46e62a640663d3` | `beaef361d35160d768473af7d2c349719db8508b` | `df6125bbe87d05d3d9a449fe21e162b3fc7c6db71dd1041b1ce1f2919d62f4ba` | `ccb6feedcd1a58f134273921f2e7372ab1d5b07743fcaa6dc2bb5894771f00c5` | yes, 736 classes in both runs |
| B, published v0.6.0 | `e16163b8fe6f8278161db0d9220883c5d49a4814df0d81f7a6cad901c07cf4e7` | `bc3dc380e759526e8aa19cd8385af16ab22ac502` | `367229071e44482bdb62bf6980af8f8a1cfef791e7c31947152e1381be571af6` | `88e2d2cd89a811d92c1f79c0c71ee2e68cf0ceecad4c8c7e2664e2e9e5a18833` | yes, 791 classes in both runs |

Both environment records identify RENEGADE as Windows 10.0.26300, Ryzen 7 7700X, 16 logical processors and 31.2 GB RAM, on the Balanced power scheme, with Azul Java 21.0.10 and Maven 3.9.16. The first pre-run CPU check had mean 0.65% and maximum 4.73%; the second had mean 1.65% and maximum 6.93%, with a configured mean limit of 5%. The runner fails when the mean exceeds that limit and asks the operator to confirm the host is otherwise idle; it does not fail on the maximum alone. The second run proceeded after the idle confirmation. These samples were taken before timing, not during measured runs. The harness recorded no runtime CPU or other host activity and no JVM garbage-collection events.

### Every measured run, first A-first dataset

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

### First dataset pair ratios

| Pair | Order | v0.5.7 ms | v0.6.0 ms | B/A ratio |
|---:|---|---:|---:|---:|
| 1 | A then B | 165,057.7 | 55,046.4 | 0.3335 |
| 2 | B then A | 196,373.0 | 55,430.3 | 0.2823 |
| 3 | A then B | 158,974.6 | 52,416.1 | 0.3297 |
| 4 | B then A | 212,401.0 | 54,401.6 | 0.2561 |
| 5 | A then B | 195,593.7 | 56,167.7 | 0.2872 |
| 6 | B then A | 168,273.9 | 55,755.0 | 0.3313 |
| 7 | A then B | 170,818.8 | 55,799.7 | 0.3267 |

### Every measured run, second B-first dataset

The second run starts pair 1 with B and alternates thereafter. Its two warm-ups still run A then B. Timestamps are run starts from `run.log`; per-run values are from `runs.csv`.

| Pair | Order | A start UTC | v0.5.7 ms | A NPS | B start UTC | v0.6.0 ms | B NPS | B/A ratio |
|---:|---|---|---:|---:|---|---:|---:|---:|
| 1 | B then A | 11:42:27.3614192 | 215,292.4 | 277,732 | 11:41:26.1120693 | 59,189.1 | 347,033 | 0.2749 |
| 2 | A then B | 11:46:05.0878549 | 162,299.2 | 368,416 | 11:48:49.7271501 | 53,658.0 | 382,806 | 0.3306 |
| 3 | B then A | 11:50:41.4061954 | 163,030.5 | 366,763 | 11:49:45.4377195 | 53,916.4 | 380,971 | 0.3307 |
| 4 | A then B | 11:53:26.7019467 | 167,557.3 | 356,855 | 11:56:16.5878932 | 55,107.5 | 372,737 | 0.3289 |
| 5 | B then A | 11:58:12.0066302 | 172,089.6 | 347,456 | 11:57:13.8022805 | 56,140.9 | 365,876 | 0.3262 |
| 6 | A then B | 12:01:06.4081350 | 168,277.9 | 355,326 | 12:03:56.9652260 | 54,887.5 | 374,231 | 0.3262 |
| 7 | B then A | 12:05:52.6294939 | 170,622.3 | 350,444 | 12:04:53.9054127 | 56,638.8 | 362,660 | 0.3320 |

### Timing summary

MAD is the unscaled median absolute deviation. CV is the sample standard deviation divided by the mean. Max/min spread is max divided by min, minus one. All figures below were recalculated from `runs.csv`; each per-position raw TSV reconciles to its CSV elapsed time within rounding. The recorded summary values also match the raw-derived medians, minimums, maximums, MADs, median NPS and pair ratios.

| Dataset | Release | Runs | Median ms | Mean ms | Min ms | Max ms | MAD ms | CV | Max/min spread | Median NPS |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| First, A-first | v0.5.7 | 7 | 170,818.8 | 181,070.4 | 158,974.6 | 212,401.0 | 11,844.2 | 11.14% | 33.61% | 350,041 |
| First, A-first | v0.6.0 | 7 | 55,430.3 | 55,002.4 | 52,416.1 | 56,167.7 | 383.9 | 2.32% | 7.16% | 370,567 |
| Repeat, B-first | v0.5.7 | 7 | 168,277.9 | 174,167.0 | 162,299.2 | 215,292.4 | 3,811.7 | 10.62% | 32.65% | 355,326 |
| Repeat, B-first | v0.6.0 | 7 | 55,107.5 | 55,648.3 | 53,658.0 | 59,189.1 | 1,191.1 | 3.41% | 10.31% | 372,737 |
| Combined, 14 runs | v0.5.7 | 14 | 169,450.1 | 177,618.7 | 158,974.6 | 215,292.4 | 5,406.0 | 10.66% | 35.43% | 352,885 |
| Combined, 14 runs | v0.6.0 | 14 | 55,268.9 | 55,325.4 | 52,416.1 | 59,189.1 | 869.7 | 2.87% | 12.92% | 371,652 |

The first run's ratio of independent medians is **0.3245**, and its median of seven paired B/A ratios is **0.3267** (67.33% lower elapsed time). The B-first repeat gives **0.3275** by independent medians and **0.3289** by median of paired ratios (67.11% lower). Combining all 14 pairs gives a ratio of pooled medians of **0.3262** and median paired ratio of **0.3278** (67.22% lower). The pooled paired ratios have a mean of 0.3140 and range from 0.2561 to 0.3335. All 14 paired measurements favor v0.6.0.

The observed order effect shrank after reversing the start arm. In the first run, the B/A median ratio was 0.3282 for A-first pairs and 0.2823 for B-first pairs. In the repeat it was 0.3289 for A-first pairs and 0.3285 for B-first pairs. Across both runs, A-first pairs have a median ratio of 0.3289 and B-first pairs 0.3262. Their v0.5.7 timing medians are 167,557.3 ms and 172,089.6 ms, respectively; v0.6.0 timing medians are 55,046.4 ms and 55,755.0 ms. With seven pairs per order across the combined data, the remaining order difference in paired ratios is small, but the per-release timing spread remains high.

### Variability and possible causes

The existing rule in `native-run-instructions.md` flags a run as noisy when either arm's max is more than 5% above its min or the paired ratios vary by more than a few percent. Both datasets trigger it. The first run's max/min spread is 33.61% for v0.5.7 and 7.16% for v0.6.0. The B-first repeat is 32.65% and 10.31%. The paired ratios in each run also vary by more than a few percent.

All 14 paired ratios are retained. They range from 0.2561 to 0.3335, so every measured pair is substantially faster for v0.6.0. The first run had lower B/A ratios in B-first pairs than A-first pairs, mostly because v0.5.7 was slower on several A-second runs. The order-reversed repeat does not reproduce that split: its A-first and B-first median pair ratios are 0.3289 and 0.3285. Across both runs they are 0.3289 and 0.3262. The order reversal therefore supports the direction of the effect, while the large v0.5.7 variance remains unexplained.

Using Tukey's 1.5 times IQR rule within each seven-run arm, the B-first run's `pair01-A` time of 215,292.4 ms is a high outlier; its paired ratio is 0.2749. No other run is flagged by that rule. This is a descriptive flag only. No observations were removed. The first run's two slowest v0.5.7 times were pairs 4 and 2; the repeat's slowest was pair 1. Neither sequence shows a steady drift through all seven pairs.

- **Execution order, moderate confidence that it does not explain the replicated direction.** The first run showed an order split, but the B-first repeat did not. Across both runs, v0.5.7's median was 167,557.3 ms when A ran first and 172,089.6 ms when A ran second, a 2.7% difference. v0.6.0's corresponding medians were 55,046.4 ms and 55,755.0 ms. This does not identify what caused individual slow runs.
- **CPU contention or other host activity, low confidence as a cause.** The first pre-run idle sample had mean 0.65% and max 4.73%; the repeat had mean 1.65% and max 6.93%. The runner's hard check is on the 15-second mean, which passed both times, and the operator confirmed the host was otherwise idle. No activity was sampled during the measured runs, so these records neither establish nor rule out contention during a slow search.
- **JVM warm-up, low confidence as a cause.** Both runs use the same fresh-JVM method and untimed depth-8 in-process warm-up before each depth-13 measurement, with two additional full warm-up runs before pairs. The per-run times do not show a common monotonic warm-up trend. The logs do not show whether depth 8 settles JIT compilation equally for both versions.
- **Garbage collection, unobserved.** G1GC is enabled, but there are no GC logs to relate collection to elapsed time.

The timer sums per-position search elapsed time in the harness. It does not include process startup and teardown, and this result should not be compared directly with Phase 21 Stage 5 whole-process timings. It measures elapsed time for this 30-position, single-threaded, fixed-depth workload. The node reduction is deterministic search work; NPS is throughput per searched node; neither is playing strength.

### Qualification decision

**QUALIFIED WITH LIMITATIONS, for this 30-position, depth-13, single-threaded native workload only.** The A-first run's median paired B/A ratio was 0.3267; the order-reversed B-first run's was 0.3289. Across all 14 pairs it was 0.3278, and the ratio of pooled medians was 0.3262. Every pair favored v0.6.0. This is strong evidence for a roughly 67% elapsed-time reduction on this benchmark setup, not a general gameplay-speed claim.

The variability threshold was not passed. Both runs remain noisy under the documented 5% rule, especially v0.5.7, and the second run contains a high A-time outlier. The instructions require a noisy run to be repeated; that repeat was completed with the opposite starting order. They do not specify that every arm's max/min spread must fall below 5% before any result can be reported. The threshold remains an explicit limitation, not a waived pass condition. The observed direction is consistent enough for a benchmark-specific finding, but the absolute timing precision and cause of the v0.5.7 variability remain unresolved.

No more benchmark or SPRT was run. The 65.65% main-node reduction is deterministic search work. Median NPS across all runs was 352,885 for v0.5.7 and 371,652 for v0.6.0, about 5.3% higher; NPS describes throughput per searched node and is distinct from elapsed time. None of these measurements tests Elo, playing strength, fixed-time play or general gameplay performance.

## Proposed supplementary GitHub Release section

The benchmark-specific result supports a concise supplement if its scope and variability are stated. Do not edit the published release description as part of this research task.

> On RENEGADE, a single-threaded depth-13 search over 30 positions shared by the published v0.5.7 and v0.6.0 JARs searched 59.79 million and 20.54 million main nodes, respectively. Across two seven-pair native runs, combined median elapsed times were 169.5 seconds for v0.5.7 and 55.3 seconds for v0.6.0; the median paired elapsed-time ratio was 0.327, about 67% lower for v0.6.0. Run-to-run variability exceeded the 5% investigation threshold, particularly for v0.5.7. This result applies to the stated fixed-depth benchmark and one Windows host. It is not a playing-strength test or a general gameplay-speed claim. Full methodology and per-run results: `docs/architecture/research/release-comparison-v0.5.7-v0.6.0/README.md`.

## Reproducing

There are two separate procedures. The first is the primary comparison. The second is a validation of the harness on a different corpus and is not part of the 30-position result.

### Primary: 30-position comparison (the numbers in this report)

The corpus is `common-corpus-30-release-vs-v0.6.0.fen` (30 positions common to both releases), depth 13, 16 MB hash, one thread, Classical evaluation.

**Node counts (deterministic, any OS with JDK 21).** Fetch the two published JARs and run the harness over the 30-position corpus. The v0.5.7 arm is the published `engine-uci-0.5.7.jar` (source `beaef361d35160d768473af7d2c349719db8508b`, not the `v0.5.7` tag). The v0.6.0 published JAR has the same class set as a rebuild of `bc3dc380e759526e8aa19cd8385af16ab22ac502`.

```bash
mkdir -p wt/jars wt/hc
gh release download v0.5.7 -R coeusyk/chess-engine -p 'engine-uci-0.5.7.jar' -D wt/jars
gh release download v0.6.0 -R coeusyk/chess-engine -p 'engine-uci-0.6.0.jar' -D wt/jars
sha256sum wt/jars/*.jar    # compare with identity/assets.txt
javac --add-modules jdk.incubator.vector -cp wt/jars/engine-uci-0.6.0.jar -d wt/hc tools/ReleaseComparisonHarness.java
C=docs/architecture/research/release-comparison-v0.5.7-v0.6.0
for V in 0.5.7 0.6.0; do
  java -Xms512m -Xmx512m -XX:+UseG1GC --add-modules jdk.incubator.vector -cp wt/hc:wt/jars/engine-uci-$V.jar \
    ReleaseComparisonHarness $C/common-corpus-30-release-vs-v0.6.0.fen 13 16 > wt/out-$V.tsv
  awk -F'\t' 'NR>1{n+=$5;q+=$6;t+=$7}END{print n, q, t}' wt/out-$V.tsv
done
```

Expected totals, as main nodes, quiescence nodes and TT hits: v0.5.7 `59793662 169860333 7330427`, v0.6.0 `20540625 45406213 5866705`. The harness takes an optional fourth argument (warm-up depth); leave it out here, because the node counts do not depend on it. The per-position table in `per-position-nodes.tsv` was built from one pass of each release over its own 31-position corpus (`raw-wsl-deterministic/v0.5.7-published-jar-own31.tsv` and `validate-v0.6.0-own31.tsv`), joined on FEN to the 30 shared positions. The command above runs the shared corpus directly. Each position starts from an empty transposition table, so both routes give the same per-position counts, and the native runs below used the shared corpus and reproduced the main-node totals above in all 32 raw TSV files (checked again for this write-up; quiescence and TT totals are from `per-position-nodes.tsv`). This exact command was not re-executed when the report was finalized; no further benchmarks were started.

**Elapsed time (native Windows only).** Requires Windows, PowerShell 7, JDK 21 (`$env:JAVA_HOME`), Maven on `PATH`, and an idle machine. Not WSL. The runner builds both arms in temporary worktrees, checks each class listing against the published JAR, and times 7 alternating pairs after two warm-up runs. Run these from the repository root of a clean checkout of this branch. The full procedure, idle checks and validation steps are in [`native-run-instructions.md`](native-run-instructions.md).

```powershell
git fetch origin --tags
git fetch origin research/release-comparison-v0.5.7-v0.6.0
git worktree add ../release-comparison-run FETCH_HEAD
cd ../release-comparison-run
pwsh -NoProfile -File tools/release-comparison.ps1 -ValidateOnly
# A-first measured pairs (the archived first run used -OutDir tools/results/release-comparison-20261012)
pwsh -NoProfile -File tools/release-comparison.ps1 -OutDir tools/results/release-comparison-rerun-a-first
# B-first measured pairs (the archived second run used -OutDir tools/results/release-comparison-20261012-repeat-bfirst); needs a clean tree again
pwsh -NoProfile -File tools/release-comparison.ps1 -OutDir tools/results/release-comparison-rerun-b-first -StartWith B
```

The runner at this branch head differs from the versions that produced the archived runs in two places found in the final review: `Write-Manifest` now excludes only the `work/` directory under the output directory (it used to skip any path with a `work` component, which would empty the manifest for a checkout under a folder named `work`), and `CLEANUP-FAILED.txt` is now also written when the run itself failed. Neither changes timing, node counts or the archived output; the archived runs' own script hashes are in their `environment.json`. The runner refuses to start on a dirty tree or into a non-empty output directory, so the output names above are new ones; the archived directories already exist in this branch. The results in this report were produced from repository heads `da81d3f3232cb4a7d726a236a851112e05db1444` (first run) and `2d7032bea100449c8811327f3825f640e67383a4` (second run).

### Separate validation: v0.6.0 on its own 31-position corpus

This is not the primary comparison and does not feed any number above except the harness check in the Preflight section. It confirms the harness reproduces the Phase 21 Stage 3 candidate numbers on v0.6.0's own `BenchRunner` corpus (`corpus-v0.6.0-own31.fen`, 31 positions).

```bash
java -Xms512m -Xmx512m -XX:+UseG1GC --add-modules jdk.incubator.vector -cp wt/hc:wt/jars/engine-uci-0.6.0.jar \
  ReleaseComparisonHarness $C/corpus-v0.6.0-own31.fen 13 16 > wt/validate-v0.6.0-own31.tsv
awk -F'\t' 'NR>1{n+=$5;q+=$6;t+=$7}END{print n, q, t}' wt/validate-v0.6.0-own31.tsv
```

Expected: `21713284 48479879 6134980`, which is exactly what the archived `raw-wsl-deterministic/validate-v0.6.0-own31.tsv` sums to (checked). That archived run used a local rebuild of `bc3dc38` made with `mvn -B -q -pl engine-core,engine-uci -am package -DskipTests` (skipped tests because the task is to measure the shipped search, not to re-verify the suite); its class set matches the published JAR.

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
| `tools/results/release-comparison-20261012-repeat-bfirst/` | Second native Windows run, starting measured pairs B-first, with the same complete evidence set |

