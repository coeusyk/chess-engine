# Vex v0.5.7 vs v0.6.0: search work at fixed depth

Status: deterministic stage complete. Native throughput stage prepared, not run.
Date: 2026-10-12. Branch: `research/release-comparison-v0.5.7-v0.6.0` (local, not pushed).

## Summary

At depth 13 on the 30 benchmark positions the two releases share, v0.6.0 searches **65.65% fewer main nodes** than the published v0.5.7 release (59,793,662 down to 20,540,625). Quiescence nodes drop 73.27%. Node counts are deterministic: two passes per release on a 29-position subset gave identical moves, scores, nodes, TT hits and PVs, and the 30-position numbers below come from one pass per release. This is the cumulative change in search work over the 241 commits between the two release sources. The Phase 21 figure (12.38% fewer main nodes) is one slice of it.

These are node counts, not speed and not strength. A smaller tree can be cheaper or better, but it can also come from pruning more aggressively. In 12 of 30 positions the two releases choose a different best move at depth 13. This report makes no claim about Elo. Native Windows timing has not been measured, so there is no throughput number here.

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
- It does not show v0.6.0 is faster. The cost of a node may have changed between the releases, so nodes per second can move either way, and wall-clock time can only come from the native run below.
- It does not show v0.6.0 is stronger. The only strength evidence in the repository is the Phase 21 SPRT against its own immediate control (+6.6 +/- 4.6 Elo, H1 accepted under [0,10]). There is no match between v0.5.7 and v0.6.0, and node counts cannot stand in for one.
- It does not cover Lazy SMP, fixed-time behavior or NNUE. The fixed-time comparison in the task was not run: it needs real games or timed searches under a clock, and doing it properly belongs with the native run.
- 30 positions is a small, hand-picked corpus. The per-position spread is wide (-94% to +126%).
- Everything ran in WSL2 (Linux 6.18.33.2, AMD Ryzen 7 7700X, OpenJDK 21.0.12.1, Maven 3.8.7 for the rebuilds). Node counts from a single-threaded fixed-depth search are not expected to depend on the host JVM, and the published-JAR run for v0.5.7 agrees with that, but I did not repeat the v0.6.0 run on a second JVM.

## Native throughput: not run

I did not run timing. The native procedure from Phase 21 Stage 5 requires an idle Windows host confirmed by the operator, and the machine was in use (this session was running builds and benchmarks in WSL2 on the same CPU). WSL2 timing is not accepted as performance evidence in this repository, and I did not use it.

`tools/release-comparison.ps1` is ready to run on RENEGADE. Step-by-step instructions, including how to confirm the machine is idle and how to validate the output, are in [`native-run-instructions.md`](native-run-instructions.md). The script pins both source commits and refuses the stale `v0.5.7` tag (see [`v0.5.7-tag-provenance.md`](v0.5.7-tag-provenance.md)), checks that each build matches the published JAR class by class, checks the corpus hash, the engine configuration and the deterministic node totals of every run, kills a run that exceeds a timeout, alternates the order of the two engines, and writes raw per-run output, an environment record and a checksum manifest. It reports elapsed time only. It has a `-ValidateOnly` pre-flight mode. Each timed run does an untimed in-process warm-up pass first (`-WarmupDepth`, default 8), which leaves every search result unchanged but means the absolute times are not comparable with the Phase 21 Stage 5 whole-process figures. It has not yet been run natively.

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
| `tools/release-comparison.ps1` | Native Windows timing runner (not run) |
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

## Does the v0.6.0 release page need a benchmark section?

I would not add one now, and I changed nothing on the published release. A line saying "65.65% fewer nodes than v0.5.7" is accurate as a search-work statement but reads as a speed claim, and the speed number is the one that has not been measured. If you want a supplementary note, write it after the native run so it can state node reduction and measured time together, label the first as a fixed-depth search-work result on 30 positions, and say nothing about Elo. Separately, the stale `v0.5.7` tag is worth a note in the release record, since someone building from the tag gets different code than the asset.
