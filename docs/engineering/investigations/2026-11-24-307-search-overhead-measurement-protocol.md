# Measurement protocol for the #307 search overhead changes

2026-11-24. Nothing here has been measured. This is the protocol, the isolated builds and the checks to run once the native SPRTs (#296, then the #260 comparison) are finished. #307 stays unmerged until the numbers are in. The four changes in it were reviewed and tested for correctness, and their effect on speed is the open part of #270 and #271.

## The four changes, as separate variants

`tools/measure-307/patches/` has one patch per change, made against develop at 92aedb8 (the merge of #306). Each applies alone, and all four together reproduce the production files of the #307 branch byte for byte (checked with `cmp` against the branch head).

| Variant | Patches | What it changes | What a measurement could show |
|---|---|---|---|
| base | none | develop at 92aedb8 | reference |
| terminal | 01 evaluate-terminal | mate or stalemate decided from the check state, no `MovesGenerator` built through `Board.isCheckmate()` | fewer allocations, small NPS change, only in positions that reach terminal nodes |
| onmake | 02 onmake-deferral | `evaluator.onMake` after the losing-capture and futility prune decisions, so a pruned move costs no accumulator update | NPS change in NNUE mode (the accumulator does the work), almost none in Classical mode |
| poll | 03 stop-polling | hard stop polled at the first node and then every 1,024 nodes | smaller overshoot cost in timed play, nothing in fixed-depth runs |
| searchmode | 04 search-mode | `AnalysisService` and `GameLoop` search with the board in search mode (no per-move FEN history) | API analysis and self-play speed only, not visible through the UCI jar |
| all | 01 to 04 | the #307 branch | combined effect |
| all-no-poll | 01, 02, 04 | the branch without the stop-poll change | to separate polling if it turns out to be the problem |

`tools/measure-307/build-variants.sh <output-dir> [variant ...]` builds each variant from a detached worktree of the base into its own fat JAR and writes `SHA256SUMS`. It never runs the engine. I built the `terminal` variant once to check the script.

## Preconditions

- No match or other CPU-heavy job on the host. The native #296 and the #260 comparison must be finished and archived first. One measurement process at a time.
- Run the host headroom gate used for the SPRTs (CPU average at most 15 percent, at least 6,144 MiB available) and record it.
- Where the run happens is recorded with the results. Relative comparisons between variants on the same host in one session are acceptable on WSL2 under the current policy. Nothing here is compared against the 301,116 native floor, which stays native only.
- Pin the JDK and the JVM flags and record them. Build all variants from the same checkout of the tool scripts.

## Gate 1: the search must be the same

For all variants, `java -jar <variant>.jar --bench-raw 13` (Classical) and the same with `--eval-type NNUE --eval-file <net>` must report the same node counts as base, position by position. Terminal, onmake and poll are meant to preserve search results, and the exact node counts at fixed depth are the evidence. Any difference stops the measurement and is a bug to find first. The searchmode variant does not touch the UCI path, so its jar matches base here by construction.

## Raw speed

Classical and NNUE separately. Interleave the variants in a rotating order (a Latin square over the variants, not base first every round) for at least 10 rounds each, with one discarded warm-up run per round. Record the aggregate NPS from `--bench-raw 13` and, with `--bench-timing`, the per-position values. Report the median and the full range per variant and the paired difference to base within each round. No pass or fail threshold is set here, because none has been calibrated. The result is read against the spread between rounds of the same variant, which is measured by running base more than once in the schedule.

## Timed play

`python tools/measure-307/timed_uci.py --jar <variant>.jar --out <variant>.csv --movetime-ms 100 --threads 1` plays `go movetime` on 60 positions of the committed bench corpus (12 from each of opening, middlegame, tactical, endgame and quiet; the first 5 searches are warm-up) and records how long the engine took to answer, the overshoot over the movetime, and the node count. Repeat at `--movetime-ms 20` and with `--threads 2`, interleaved across variants like the raw runs. Compare mean, median and 95th percentile overshoot, and nodes per second at fixed time. The poll variant is the one expected to move overshoot, and a larger overshoot than base would be the reason to split it out.

The multithreaded runs are for Lazy SMP behaviour at a fixed time. They are not a qualification of Lazy SMP, which stays native only.

## Allocation

Run `--bench-raw 13` for base, terminal and all with the same fixed heap and a single collector, for example `java -Xms512m -Xmx512m -XX:+UseSerialGC -XX:StartFlightRecording=filename=<variant>.jfr,settings=profile -jar <variant>.jar --bench-raw 13`. Compare total allocation from `jfr print --events jdk.ObjectAllocationSample <variant>.jfr` summed by weight, and the top allocation sites by stack. Also keep `-Xlog:gc` output and compare the number of young collections. The terminal variant is expected to remove the `MovesGenerator` and move-list allocations at terminal nodes, and that is the thing to confirm or to find missing.

## Search mode

The UCI jar does not exercise search mode, so these runs cannot show it. Measuring it needs a small in-process timing of the API analysis path and of `GameLoop` with and without the flag. That harness does not exist yet. I did not write it, because nothing can run it until the hosts are free.

## What gets recorded

For every run: variant, jar SHA-256, JDK, JVM flags, host and session, the headroom gate values, start and end times, raw outputs. The archive gets a `SHA256SUMS`. A summary lists the Gate 1 result first, then the speed, timed and allocation findings per variant. The decision to merge #307, to split the poll change, or to drop a change is made afterwards and is not part of this protocol.

## What was run (added 2026-11-26)

The protocol above was run on 2026-11-25 and 2026-11-26 on the WSL2 host, with JDK 21.0.13.1, depth 13, and the allocation flags `-Xms512m -Xmx512m -XX:+UseSerialGC --add-modules jdk.incubator.vector`. Speed numbers are relative comparisons inside one session. Nothing here is compared with the native 301,116 floor. The archive is `tools/results/measure-307-313/20261125T0522Z/`. It holds `results.jsonl` (one record per finished unit), `pressure.csv` and `pressure-windows.csv` (WSL CPU sampled every 10 seconds, plus the Windows counters), the runner scripts as they were at each stage (`measure.v1.py`, `measure.v2.py`, `measure.py`), the raw bench output, the GC logs, `analyze_313.py`, the amendments and the incident note. The JFR recordings (about 126 MB) and the jars are not committed. The jar hashes are in `jars307/SHA256SUMS` and `jars313/SHA256SUMS`, and the JFR files stay on the host that ran them.

The run changed three times after it started, and each change was written down before any result it could affect:

- `AMENDMENT-1.md` cut the NNUE speed rounds from 10 to 3, because a depth-13 NNUE bench takes about 10.6 minutes and I had underestimated the host time (the original plan came to roughly 25 hours).
- `INCIDENT-1.md` records that I ran unrelated local work on the host while the Classical rounds were in progress, and that the runner's foreign-load guard then stopped itself on a command that only mentioned a match launcher by name. It fixes the exclusion rule before any candidate difference was looked at: a unit is flagged if any WSL CPU sample inside its window is above 12 percent, and a flagged unit excludes its whole round from the primary result. Classical rounds 0 and 1 are excluded under that rule. No record was edited or deleted.
- `AMENDMENT-2.md` added two confirmation rounds for the #313 comparison, and `AMENDMENT-3.md` is the terminal-only allocation comparison below.

Raw and timed records for the other #307 variants (onmake, poll, searchmode, all, all-no-poll) are in the archive. They are not analyzed in this note. Only the terminal change is, and the others stay deferred.

## Terminal change: allocation (#307)

Amendment 3 repeated the original allocation observation for the base and terminal builds only, in Classical and NNUE mode, in the order base, terminal, terminal, base. The original run had one JFR sample per variant and no per-unit contamination check. Each run here has both. Every run searched the same 23,222,204 (Classical) or 112,142,129 (NNUE) nodes as the Gate 1 count, and no run had a CPU sample above 12 percent in its window (the highest was 8.6).

| Mode | Build | Bytes per node, two runs | Young collections, two runs |
|---|---|---|---|
| Classical | base | 55.8, 55.8 | 9, 10 |
| Classical | terminal | 50.0, 49.1 | 9, 8 |
| NNUE | base | 24.8, 24.9 | 21, 21 |
| NNUE | terminal | 12.8, 12.8 | 11, 11 |

That is about 11 percent less allocation per node in Classical and about 48.5 percent less in NNUE, with about half the young collections in NNUE. The bytes are JFR allocation samples, one JVM per run, so they are an estimate of allocation and not an exact count, and the two runs per cell are a replicate of the sampling and not a variance estimate. Nothing in this table is a speed result. The driver stopped once at about 23:09 UTC on 2026-11-26, after the NNUE `base` file was written and before its record was. I do not know why. The shell that started it was closed by a graphics driver update at about that time. The driver was restarted unchanged, detached from the shell, skipped the four recorded Classical keys and re-ran NNUE from the start (`AMENDMENT-3.md`, execution note). The log of the interrupted attempt is kept as `alloc2/nnue-base-1.gc.log.0`.

## #313: the vectorized output dot product

The #313 candidate was measured against its base in NNUE mode only, at depth 13, with the net `net.nnue` (SHA-256 `fca29285758e736c99e980ba754f288ef8347eb1d0ca0e265ca70bcf4e061e59`, which the engine reports as network `dfffd3da-7f8f-4fc9-92dc-b3873c97fb21`). The frozen jars are `jars313/base.jar` (SHA-256 `7742ac23676f8c20427a2c0f2a3951b0cc012fdc86187972ff9d90479fb7d2e9`) and `jars313/candidate.jar` (SHA-256 `9e538d300f04d31a62a64c7b60858fb5101c98e6594231a834c1439408e3e5e7`). Gate 1 passed: base and candidate searched the identical per-position node counts, 112,142,129 nodes in total, in both Classical and NNUE mode. Each round also ran a repeat of the base build, and each round had one discarded warm-up run.

Aggregate nodes per second by round (`analyze_313.py` reads `results.jsonl` and `pressure.csv` and writes nothing):

| Round | Status | base | base repeat | candidate | Candidate vs base | Repeat vs base | Highest CPU sample in the round |
|---|---|---|---|---|---|---|---|
| 0 | clean | 179,034 | 175,273 | 294,595 | +64.55% | -2.10% | 7.8 |
| 1 | excluded | 169,604 | 173,066 | 281,585 | +66.02% | +2.04% | 14.9 |
| 2 | excluded | 173,145 | 173,244 | 284,530 | +64.33% | +0.06% | 14.8 |
| 3 | excluded | 177,064 | 177,530 | 299,543 | +69.17% | +0.26% | 12.3 |
| 4 | clean | 180,414 | 178,355 | 299,800 | +66.17% | -1.14% | 8.1 |

The primary evidence is the two clean rounds, 0 and 4: +64.55% and +66.17%, median +65.36%. The excluded rounds are supplementary. All five rounds point the same way, with candidate against base between +64.33% and +69.17% and a median of +66.02% over the five, so the excluded rounds do not change the picture. Round 3 was flagged because the base unit's window peaked at 12.3 percent, just over the limit. Rounds 1 and 2 were flagged at 14.8 and 14.9 percent. Round 4 repeated the unit order of round 1 and round 3 repeated the order of round 0, as `AMENDMENT-2.md` says. No further #313 speed rounds are planned.

How far to read this: the base against the repeated base differs by between -2.10% and +2.04% across the five rounds. That is the spread between two runs of the same jar in the same round, taken as a rough reference for how large a difference is visible at all. It is not a noise estimate, and I have not computed an interval or a p-value for any of this. The effect is much larger than that spread. The candidate is the same search at depth 13 (identical nodes), so the nodes per second difference is a time difference. It was measured on WSL2 only and says nothing yet about native Windows.

One allocation observation from the original run, single JFR sample per build and not replicated: base 24.81 bytes per node and candidate 25.96, with 21 and 22 young collections. I did not look into it further. It is not part of the speed claim.

Correctness of the change as rebased on develop (34e58b9, 2026-11-26): the NNUE main sources on the branch are byte-identical to the pre-rebase tip, because nothing on develop since the branch point touched them (the files that changed are `EvalParams`, `KingSafety`, the tuner, the tools and NNUE tests and fixtures). The scalar loop is still the fallback when the vector module is missing or `dotFitsInt` fails. `engine-core` passes locally with 549 tests run, 0 failures and 6 skipped (cases in `NpsBenchmarkTest`, `TacticalSuiteTest`, `NnueCorpusBenchmarkTest`, `NnueOracleBatchReportTest` and `SearchRegressionTest`, which I did not look into). The new QA 1016 fixture tests from #317 now run through the vector path as well. The deferred vector-specific fixture and fallback cases for #265 stay pending until #313 merges.
