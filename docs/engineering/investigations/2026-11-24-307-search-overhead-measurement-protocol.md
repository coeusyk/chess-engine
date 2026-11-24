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

The UCI jar does not exercise search mode, so `SearchModeWorkloadTest` (engine-core test scope, on the #307 branch) is the workload. API analysis and the self-play speculative search only toggle `Board.setSearchMode` and then run the same `Searcher`, so the workload is that same fixed-depth search over six positions with the flag off and on. An always-on test checks the flag does not change nodes, score or move. The harness is opt-in: `mvn -pl engine-core test -Dtest=SearchModeWorkloadTest#searchModeWorkload -Dworkload.run=true -Dworkload.depth=7 -Dworkload.rounds=9`. It alternates the two modes inside each round in a rotating order and prints the per-round times and the ratio of normal to search-mode time, with a final median. The node counts of the two modes must be equal. It stays out of the build, and like the other measurements it runs only on an idle host. It does not go through Spring or the SSE layer, which is outside the part this change touches.

## What gets recorded

For every run: variant, jar SHA-256, JDK, JVM flags, host and session, the headroom gate values, start and end times, raw outputs. The archive gets a `SHA256SUMS`. A summary lists the Gate 1 result first, then the speed, timed and allocation findings per variant. The decision to merge #307, to split the poll change, or to drop a change is made afterwards and is not part of this protocol.
