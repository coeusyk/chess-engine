# Amendment 3: focused terminal-only allocation comparison for #307

Recorded 2026-11-26 before any `alloc2:` record exists. Authorized by the owner on 2026-11-26 (after the #313 confirmation rounds), in place of re-running the seven-variant campaign.

## Why

The original allocation runs were one JFR observation per variant, taken without per-unit contamination monitoring. They suggested the terminal-state change cuts allocation per node by about 10 to 12 percent in Classical mode and about 48 percent in NNUE mode. This repeats that comparison alone, with monitoring.

## What is run

- Suite `s307`, variants `base` (`jars307/base.jar`) and `terminal` (`jars307/terminal.jar`), whose hashes are in `jars307/SHA256SUMS` and were verified before launch. Classical and NNUE (net `net.nnue`) at depth 13, the original `ALLOC_FLAGS` (`-Xms512m -Xmx512m -XX:+UseSerialGC --add-modules jdk.incubator.vector`) plus JFR `settings=profile` and `-Xlog:gc`, JDK 21.0.13.1.
- Per mode one comparison in the order base, terminal, terminal, base. The second pair is a replicate of the sampled allocation, because JFR allocation samples are statistical. Driver: `alloc_followup.py` (imports `measure.py` unchanged). Records are keyed `alloc2:...`, the original `alloc:` records are untouched.
- Correctness: Gate 1 of the original run (identical per-position nodes, base against terminal, both modes). Each new run also records its node count, which must equal the Gate 1 count (Classical 23,222,204, NNUE 112,142,129).
- Code check made before launch: all four callers of `evaluateTerminal` (root, main search, quiescence evasions, boundary score) have just generated legal moves and found zero, so only the check state separates mate from stalemate. `Board.isCheckmate()` computes in-check and no-legal-moves, so with no legal moves it equals `isActiveColorInCheck()`.
- Contamination: pressure.csv is sampled as before. The 12 percent rule applies per run. A flagged run is reported as flagged, not replaced.
- The host is idle during the run and the runner is started once and not polled.

## Execution note and result (recorded 2026-11-26, after the run)

The driver was started at 22:59 UTC. At about 23:09 UTC it stopped after the NNUE `base:1` JFR file was written and before its record was, with nothing in `runner4.log`. The cause is not established: the shell that launched it was closed by a graphics driver update at about that time, and a kill by that closure or a failure in the JFR parse are both possible. No partial record exists. The driver was restarted unchanged at 23:10 UTC, detached from the shell, skipped the four recorded Classical keys and re-ran NNUE from `base:1` (`runner5.log`, empty). The earlier `nnue-base-1` JFR and GC files were overwritten except for `nnue-base-1.gc.log.0`, which is the GC log of the interrupted attempt and is kept.

Per-run results (all eight runs have the Gate 1 node count, and none has a CPU sample above 12 percent in its window; the highest is 8.6):

| key | nodes | sampled bytes per node | young collections |
|---|---|---|---|
| classical base 1 / 2 | 23,222,204 | 55.8 / 55.8 | 9 / 10 |
| classical terminal 1 / 2 | 23,222,204 | 50.0 / 49.1 | 9 / 8 |
| nnue base 1 / 2 | 112,142,129 | 24.8 / 24.9 | 21 / 21 |
| nnue terminal 1 / 2 | 112,142,129 | 12.8 / 12.8 | 11 / 11 |

Allocation per node falls by about 11 percent in Classical and about 48.5 percent in NNUE. The replicate pairs agree to within about 2 percent in Classical and under 1 percent in NNUE. These are JFR allocation samples of one JVM per run, not a timing result, and no statistical test is claimed.
