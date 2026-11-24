# WSL2 operational qualification, stages 0 to 4

2026-11-24. This run followed the initial stages in the [throughput investigation](2026-07-17-sprt-throughput-investigation.md#qualification-protocol-staged-threads1-only). Stages 0 to 2 are recorded first, as they were written. Stages 3 and 4 and the resulting policy are at the end. While only stages 0 to 2 existed, the Windows-only policy covered strength tests and the absolute NPS gate. The [outcome section](#outcome-and-policy) replaces that for Threads=1 SPRTs.

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

## Stage 3: 1,000-game A/A

The same control JAR as the pilot played itself, after its bytes were checked equal to the native copy. The launcher was `tools/sprt_linux.py` at develop `e97f5c4`, which records `authoritative: false` in its provenance. Settings: 1,000 games, fixed count with no SPRT option, randomized colour-reversed openings from the tracked book (SHA-256 `2011193b4854e9a8cfdc05312ca2dbaffa6ceae3abbdee20e2ead2a18a603347`, seed 2961000, 4 plies), Threads=1, Hash=16 MB, Classical, 5+0.05, concurrency 6.

The headroom gate was a mean host CPU of at most 15% over a 30-second window, at least 8 GiB of host memory available, at least 10 GiB available in the VM and no swap in use. It passed in the first window (mean CPU 9.2%, minimum 11,099 MiB host memory available).

| Metric | Result |
|---|---|
| Games, pairs | 1,000, 500 (all reconciled, none incomplete) |
| NEW wins, draws, losses | 341, 341, 318 |
| Paired score (standard error) | 51.15% (1.282 percentage points) |
| Nominal 95% interval of the score | 48.64% to 53.66%, which contains 50% |
| Elo transform | +7.99 (nominal 95% -9.47 to +25.49) |
| Time forfeits, engine faults | 0 and 0 (Wilson 95% upper bound 0.383% per game, 0.00366% per timed move) |
| Move time, median / p95 / max | 0.14 / 0.21 / 0.29 s |
| Wall time | 2,352.5 s, 1,530 games per hour |
| Host CPU | mean 48.6%, p95 66.8%, peak 97.7% |
| Host memory available, minimum | 1,908 MiB |
| VM memory available, minimum | 8,177 MiB |
| VM pressure and swap | memory stalls 0, swap pages in and out 0, steal 0, CPU partial stalls 8.4 s |

The +7.99 Elo is an A/A result and carries no strength meaning. It shows how wide the noise is: the pair-score sample variance was 0.08216, a nominal 95% half-width of about +/-17.5 Elo at 1,000 games and about 3,050 games for +/-10 Elo. That is wider than the pilot's estimate of 2,812. The host's available memory fell to 1,908 MiB at one point while the VM's available memory stayed at or above 8,177 MiB and it recorded no memory stall, so the dip did not reach the VM in any measured way, but I am recording it as seen.

## Stage 4: cross-platform replication

The pair was the Phase 21 Stage 6 match archived as run `20261011T061318503Z-b5a507c6`, control `d3a56ff` against candidate `e90d3d4`. The plan was written and hashed before any game: protocol SHA-256 `977ab557b9d70a3dd92c2f9781c460949c35052cdf8ed7cf01831358ffdafd4f`, registered 2026-11-24 11:43:36 UTC. Control JAR `b54ed03dc6960352c299927a405c89a9b550b9583079aa6bcf883e4c1dc014f2`, candidate JAR `4018a032ca64b80b588a9d0912f5fdac366424089c6750a08c2ef6723665d875`, native PGN `d864cbe5e1648c69a0c64f9fca4d3b62fbe72abb43aeb82ba21e5f08afb356d5`. Both JAR hashes match the protocol's recorded source hashes.

Openings: the complete native pairs were ranked by SHA-256 of a seed, a newline and the first four FEN fields (seed `vex-phase21-wsl-replication-v1`) and the first 2,000 were taken. Each opening was played twice with colours swapped, for 4,000 games. The match was fixed count with no SPRT option and runner seed 2962021. Settings were Threads=1, Hash=16 MB, Classical, 5+0.05, concurrency 6, adjudication as in the native run, and JVM flags `-Xms256m -Xmx512m -XX:-UsePerfData --add-modules jdk.incubator.vector`.

The first attempt did not start. All three 30-second preflight windows were below the 8 GiB host memory minimum (5,620, 7,285 and 7,152 MiB at their lowest), and the CPU condition held in each. The gate refused to run and that attempt is archived with the rest. The retry used the unchanged protocol and passed its first window at 8,462 MiB minimum and 7.0% mean CPU.

| Quantity | Estimate (nominal 95% interval) |
|---|---|
| Native, all 6,558 complete pairs | +6.60 Elo (+2.04 to +11.16) |
| Native, the 2,000 matched pairs | +13.64 Elo (+5.33 to +21.98) |
| WSL, 4,000 games | +10.77 Elo (+2.71 to +18.85) |
| WSL minus native, matched pairs | -2.87 Elo (-14.46 to +8.72) |

WSL played 1,241 wins, 1,642 draws and 1,117 losses, a pair score of 51.55% with a standard error of 0.592 percentage points. There were no incomplete games, time forfeits or engine faults (Wilson 95% upper bound 0.096% per game and 0.0010% per timed move). Median move time was 0.12 s and p95 0.21 s. The run took 7,951 s, 1,811 games per hour. Host CPU averaged 45.5% (peak 86.2%) and host memory available reached a minimum of 3,385 MiB. The VM recorded no memory stalls, no swap traffic and no steal.

The Stage 4 criteria were overlapping intervals and the same verdict. Both hold: the WSL interval overlaps both native intervals, and all three exclude zero on the positive side. The native intervals come from matches that were stopped sequentially, so their stated coverage does not apply as written, and every interval here is a nominal fixed-sample normal approximation. The paired WSL minus native interval contains zero and is about 23 Elo wide, so this run cannot rule out a platform difference of around 10 Elo either way. It shows that the positive Stage 6 effect reproduced under WSL. It does not show that the two environments are equivalent.

## Outcome and policy

On 2026-11-24 the maintainer approved conditional WSL2 qualification for new Threads=1 SPRTs, subject to this evidence review being complete. The review covered the archived A/A and replication files, their checksums, the frozen protocol hash and the JAR identities, and found them consistent. What the approval permits, and what it leaves alone:

- Permitted: Threads=1 SPRTs on this WSL2 setup, through `tools/sprt_linux.py`, with the gate, controls, monitoring and records used here, recorded as "WSL, qualified". A valid qualified run may satisfy a strength-validation gate on its own. The maintainer lifted the earlier condition that every such result needs a native confirmation. Normal merge approval and evidence review still apply to the change being tested.
- Permitted: relative NPS comparisons of two builds inside one WSL session on one host (interleaved arms, headroom check recorded). They are never compared with the native absolute floor.
- Unchanged: the 301,116 NPS floor and its native baseline, native-only qualification for anything with Lazy SMP or thread scaling, release comparisons, and any preregistration that freezes the host. Historical Windows-only preregistrations and their recorded results are not edited.
- Not claimed: formal statistical equivalence between WSL and native. The result is one replication of one pair with the interval above.
- Known limitation: the headroom gate looks at host memory for 30 seconds before the match. During the A/A, host available memory fell to 1,908 MiB mid-run, and during the replication to 3,385 MiB, while the VM kept at least 7,844 MiB available and recorded no memory stalls. The gate is therefore a start-of-run check, and not a guarantee about the run. Keep the preflight and the pressure sampling, and report the host memory minimum with every qualified result.
- Not authorized: running two matches at once. One CPU-heavy job runs on a physical host at a time, which includes a WSL job beside a native one.

Raw logs, PGNs, pressure samples, argument manifests, both JAR pairs and checksums are archived in the native checkout at `tools/results/vex-followup/20261124T1102Z`. The A/A directory has 11 files and `SHA256SUMS` hash `f02b2c2d6116661df1a1bd4f9315bb6cb36a5344549389f22ca6b64ab3305e2e`. The replication directory has 22 files and `SHA256SUMS` hash `6bea58d81feed5cc4dae22cc3a0d6c8a7e061ff741cb11020fcc6c4e6fad2405`. All files in both passed the checksum check on 2026-11-24.
