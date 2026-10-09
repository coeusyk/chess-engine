# Phase 21 Stage 5 native throughput review

Reviewed on 2026-10-09. Run ID: `20261009T142702733Z`.

**Stage 5: PASS.** Independent calculations from all 16 raw outputs agree with `runs.csv`, `summary.csv` and `summary.json`. No blocking artifact or protocol discrepancy was found. Candidate median elapsed is 12.623% lower than control median elapsed and is below the fastest measured control run. Both candidate NPS floors hold.

The user executed the runner in native Windows PowerShell. This review only read saved artifacts; it did not execute the runner, build either engine, repeat benchmarks, run games or begin Stage 6.

## Evidence and provenance

The 25 original files remain in the user's Windows checkout at:

```text
C:\Users\yashk\WorkDir\Projects\ChessEngine\chess-engine\tools\results\phase21-stage5\20261009T142702733Z
```

They were read through the corresponding `/mnt/c/Users/yashk/WorkDir/Projects/ChessEngine/chess-engine/tools/results/phase21-stage5/20261009T142702733Z` path. Exact copies are now preserved in [the repository evidence archive](phase21-stage5-native/20261009T142702733Z/), together with a complete `SHA256SUMS` manifest. The Windows originals were not changed. [The archive index](phase21-stage5-native/README.md) also records the earlier failed startup and preparation amendments.

| Identity | Recorded value |
|---|---|
| Invoking runner commit | `0671e1adc18ec1ff647f688c4f406ee961a3441a` |
| Control production commit | `d3a56ffadf0d9151a2b19fba4902a734a99bff96` |
| Candidate production commit | `e90d3d4f90b244c46e8bbf75c33222bba7d58003` |
| Control JAR SHA-256 | `8b31e3bd9a44523f94158b3719b1845845505e9a07af64e1dccf3ae3f77b0222` |
| Candidate JAR SHA-256 | `64c5efab060f6c8a4ba98d6162af39ab956f8c0c75d8521639d1af9bb5705553` |
| Both benchmark source SHA-256 values | `882f5edc5159dba94bb6faefbb456f1a12e77f2798753a6a82d58755c9b1361f` |

Source hashes use UTF-8 with CRLF normalized to LF. JAR hashes are byte-exact. Every run row records its expected production commit and the same JAR hash as its arm's identity record. Both build logs end in `BUILD SUCCESS`; the runner records source trees as clean before and after each build. Temporary worktree directories are absent after completion, and the transcript contains no cleanup failure. Since cleanup removed the JARs, this review cross-checked their recorded hashes rather than independently hashing the binaries again.

The environment record identifies `RENEGADE`, an AMD Ryzen 7 7700X with 8 cores and 16 logical processors, native PowerShell 7.6.6 on Windows 11 Pro build 26300, Balanced power plan, and full affinity mask 65535. Java and Maven both use `C:\Tools\Java\zulu-21`, Azul Zulu 21.0.10+7-LTS. Maven is 3.9.16. All four checked inherited JVM option variables are unset. The recorded JVM arguments contain the fixed heap, G1GC and Vector API module options, with no JFR or agent flags.

## Protocol and raw-output checks

The planned schedule, CSV rows, raw-file names and transcript agree on exactly one discarded control warm-up, one discarded candidate warm-up, then seven control/candidate measured pairs. There are no extra benchmark invocations or retries in this run directory. Warm-up elapsed values are 69.311 s and 62.420 s; neither contributes to the decision.

All 16 raw files contain exactly one expected depth-13, 16 MB hash, 31-position, Classical, instrumentation-off header. Every file contains positions 1 through 31 in order at depth 13. Per-position nodes sum to the raw total, and each arm has one identical node vector across its warm-up and all seven measured runs:

- Control: **24,780,049 main nodes** per invocation.
- Candidate: **21,713,284 main nodes** per invocation.

This reproduces Stage 3's 12.375944% node reduction. Every raw elapsed/NPS value matches its CSV row, and integer NPS agrees with `floor(main_nodes * 1000 / elapsed_ms)`.

The operator confirmed the idle-host condition at 14:38:07.981 UTC, about 8 minutes 31 seconds after the candidate warm-up finished. This pause is outside measured elapsed. During the measured series, successive recorded completion times exceed the next run's engine elapsed by only approximately 0.196 to 0.353 s, consistent with process and logging overhead. No comparable pause appears inside the measured sequence. Host idleness is operator-reported; the artifacts do not contain an independent background-load trace.

Both builds show the same categories of Maven/compiler warnings: an unspecified `maven-jar-plugin` version, the incubating Vector API module, a deprecated item annotation, and shading/module/manifest overlap. They completed successfully. Runtime warnings only identify the expected incubator module. No failure or enabled instrumentation was found in the saved benchmark output.

## Independently recomputed results

| Measured pair | Control elapsed (s) | Candidate elapsed (s) |
|---|---:|---:|
| 1 | 68.156 | 64.642 |
| 2 | 74.308 | 62.732 |
| 3 | 71.795 | 61.268 |
| 4 | 71.439 | 61.492 |
| 5 | 74.895 | 67.600 |
| 6 | 80.462 | 61.939 |
| 7 | 69.250 | 63.889 |

| Metric | Control | Candidate |
|---|---:|---:|
| Minimum elapsed (s) | 68.156 | 61.268 |
| Median elapsed (s) | 71.795 | 62.732 |
| Maximum elapsed (s) | 80.462 | 67.600 |
| Median NPS | 345,150 | 346,127 |
| Aggregate NPS | 339,915 | 342,664 |

The candidate/control median elapsed ratio is **0.8737655825614598**, a **12.6234417% elapsed reduction**. The candidate median is **5.424 s below the control minimum**. Candidate median NPS is **346,127 >= 301,116**, and candidate aggregate NPS is **342,664 >= 301,116**. These calculations reproduce every frozen gate condition enforced by the runner.

Timing variation is visible: the control range is 17.14% of its median, and the candidate range is 10.09% of its median. Despite that variation, every candidate run is faster than every control run; the slowest candidate remains 0.556 s below the fastest control. This is a descriptive observation, not an additional gate.

Candidate median NPS rises only 0.283%, and aggregate NPS rises 0.809%. The elapsed improvement is consistent with the smaller search tree established in Stage 3. These throughput results do not establish a playing-strength improvement. Stage 6 remains unstarted.

## Artifact fingerprints

SHA-256 values recorded during this review:

| File | SHA-256 |
|---|---|
| `environment.json` | `977f84457f4bbca3ee7ddcb1f15c8fdcc340a8ac347f6bd60cec239069b4888a` |
| `identity.json` | `2644edec235e5437be5335ed37318bc70babfb716c582f8d54e9f3fd1c8224c3` |
| `runs.csv` | `02ba44fabda4b4768438deede63c2ac11feee9c7aaa5445fe9031fea836da198` |
| `summary.json` | `2e1dfee621f32dcf7f07c904bfcbdff4100dd3114c8cfb4824e3ba9265935bcd` |
| `runner.log` | `154cecffdb094d736dbffc55cf58d94b68fc356abca1efa65ddd7ed1d5c549a7` |
