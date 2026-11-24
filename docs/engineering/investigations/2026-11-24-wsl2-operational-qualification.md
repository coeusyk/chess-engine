# WSL2 operational qualification, stages 0–2

2026-11-24. This run followed the initial stages in the [throughput investigation](2026-07-17-sprt-throughput-investigation.md#qualification-protocol-staged-threads1-only). The Windows-only policy still governs strength tests and the absolute NPS gate.

## Environment and controls

RENEGADE ran Windows 11 Pro 26H2 build 26300 on a Ryzen 7 7700X (8 cores, 16 logical CPUs), with 31.16 GiB visible RAM and the Balanced power plan. The native tools were Zulu 21.0.10, Maven 3.9.16 and cutechess-cli 1.5.1 (Qt 6.8.3). WSL 2.7.11.0 ran Ubuntu 24.04.4 LTS, kernel 6.18.33.2, Ubuntu OpenJDK 21.0.13.1 and cutechess-cli 1.5.1 (Qt 6.8.3). The Linux runner came from the [official 1.5.1 AppImage](https://github.com/cutechess/cutechess/releases/tag/v1.5.1), with its published SHA-256 verified before extraction.

WSL had CPUs 0–15, 15.20 GiB RAM and 4 GiB configured swap. The effective cgroup had no CPU quota or memory/swap cap. The existing `.wslconfig` specified mirrored networking and left resource settings at their defaults. All WSL run data stayed on ext4. The native Task Scheduler worker launched Windows JVMs directly. Both sides used `-Xms256m -Xmx512m -XX:-UsePerfData --add-modules jdk.incubator.vector`.

The same native-built control JAR ran on both sides and on both arms of the A/A match. Source commit `5334636128fb166d5256d37b417db688de073822`, JAR SHA-256 `ec8cea6d2bd7439b2933321c3e81fca03c2cda2f342cccfeaac52028a0f80a1f`. Every throughput execution reproduced all 31 depth-13 node counts and the 23,222,204-node total.

## Relative throughput

Three back-to-back sessions each began with a 15-second headroom check (mean host CPU at most 15%, at least 6 GiB available RAM). Each platform had one discarded separate-process warm-up and seven measured `--bench-raw 13` runs. Platforms alternated in blocks of four and three, with the starting platform reversed for session 2.

| Session | WSL mean NPS | Native mean NPS | WSL CV (nominal 95%) | Native CV (nominal 95%) | WSL/native ratio (nominal 95%) |
|---|---:|---:|---:|---:|---:|
| 1 | 344,400 | 333,266 | 1.55% (0.78–2.00%) | 2.73% (0.72–3.33%) | 1.034 (1.002–1.066) |
| 2 | 352,152 | 333,379 | 1.19% (0.20–1.67%) | 3.26% (0.84–4.20%) | 1.057 (1.024–1.091) |
| 3 | 344,327 | 340,644 | 1.75% (0.67–2.35%) | 2.24% (1.06–2.67%) | 1.011 (0.990–1.032) |

The geometric-mean WSL/native ratio was **1.034** (between-session nominal 95% interval 0.978–1.092). The session ratios spanned 4.58 percentage points. Ratio intervals use paired log ratios by run index within a session and the three log session ratios overall. CV intervals use within-session resampling. These estimates describe this host and these JVM builds.

Windows CPU averaged 16.9% during the measurements (p95 27.0%, peak 41.4%). Available host memory reached 5,210 MiB. The third session's discarded native warm-up dropped to 271,999 NPS while host CPU averaged 23.9% over that run. All scheduled measured repetitions were retained. VM CPU pressure recorded 2.28 seconds of partial stalls over the throughput window. Memory pressure, VM steal, and swap traffic were all zero.

## Fixed-count A/A pilot

Two real-engine smoke games first verified launcher preparation and manifest enforcement, stream logging, PGN output and process exit. The unmodified launcher was used with a temporary adapter that removed the SPRT option group, added a fixed seed and recorded the effective arguments. Cute Chess printed its neutral status with infinite bounds. The effective command had no SPRT stopping criterion.

The pilot used 100 games, 50 randomized colour-reversed openings, seed 290294, Threads=1, Hash=16 MB, Classical, 5+0.05 and concurrency 2. The tracked opening book had SHA-256 `2011193b4854e9a8cfdc05312ca2dbaffa6ceae3abbdee20e2ead2a18a603347`. All games and pairs reconciled between the log and PGN. Both labels played 50 games per colour. NEW went 26 wins, 39 draws, 35 losses, for 45.5% with a paired standard error of 3.89 percentage points. As White it scored 9–21–20 and as Black 17–18–15 (wins–draws–losses). Pair totals of 0, ½, 1, 1½ and 2 points occurred 6, 13, 19, 8 and 4 times, respectively.

| Metric | WSL A/A pilot | Native #290 archive |
|---|---:|---:|
| Counted games | 100 | 3,552 |
| Time forfeits | 0, Wilson 95% 0–3.70% | 1, Wilson 95% 0.005–0.159% |
| Crashes, disconnects, stalls, illegal moves, adjudication discrepancies | 0 | 0 |
| Annotated move times, median / p95 / max | 0.14 / 0.21 / 0.28 s | 0.14 / 0.21 / 0.41 s |
| Game duration, median / p95 | 14.82 / 20.73 s | 14.31 / 19.13 s |
| Scored games/hour | 497 (2 slots) | 1,526 (6 slots) |

The pilot recorded 10,999 annotated move times. The per-annotated-move Wilson upper bound for zero observed forfeits or engine faults was 0.035%. Host CPU averaged 23.4% (p95 32.7%, peak 40.7%) with at least 9,164 MiB available. VM CPU partial stalls totalled 1.52 seconds, with zero memory pressure, swap traffic or steal.

The pilot met its pre-run operational criteria. Its game-level failure interval is too wide to establish the historical native failure rate. The pair-score sample variance was 0.07574, implying about ±16.8 Elo of nominal 95% half-width at even score for 1,000 games. A ±10 Elo target would take about 2,812 games at that variance. These are fixed-count planning estimates. Strength sensitivity, the larger A/A study, and Lazy SMP qualification require separate evidence and approval.

Raw logs, PGNs, pressure samples, argument manifests, control JAR and checksums are archived in the native checkout at `tools/results/vex-handoff/20261124T0933Z`.

All 105 archived files passed the checksum check. SHA-256 of `SHA256SUMS` is `fb40e7be1dfa5e44757b9beb5baf970c4263a6abb23496d9b4049ec90527a972`.
