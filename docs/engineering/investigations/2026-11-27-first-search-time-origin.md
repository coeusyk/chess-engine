# First search of a process returns late on a fixed movetime

2026-11-27. A game against Vex in Cute Chess lost on time on the first move as White. The engine answered a `go movetime 15000` about 12 ms after the limit, and Cute Chess forfeits any overshoot unless a time margin is set.

## What I measured first

I wrote a small harness on native Windows Python that spawns the engine with direct pipes (like Cute Chess does) and timestamps every line. The number reported is go sent to bestmove received, minus the movetime. Before any change, using the jar built from b8c30fa:

| Condition | Overshoot |
|---|---|
| Fresh process, Threads=1, Hash=64, movetime 15000 (3 runs) | +12.3, +12.3, +12.6 ms |
| Fresh process, Threads=8, Hash=256 | +20 to +28 ms |
| Warm process, any later search | about -28 ms |
| Fresh process, but after one short throwaway search | about -26 ms |
| Fresh process, Black reply (`position startpos moves e2e4`) | -20 ms |
| Clock-based `go wtime/btime` | far below the limit |

The startup script (`.bat`) and a direct `java` command gave identical numbers, and so did a build from before #258 (61a3712), so it is not a recent regression and not the wrapper. A warm Threads=8 search matched Threads=1, so helper threads were not delaying the bestmove.

## Cause

`Searcher.searchWithTimeManager` called `timeManager.startNow()` as its first line. Everything before that was free: parsing `go`, generating legal moves, building the time manager, starting the worker, the transposition table check, creating the helper threads and `new Searcher()`. In a fresh JVM that list also contains loading and initializing the move generator, attack tables, evaluator and table classes for the first time. A class-load log showed all of them loading after the `go` arrived. That is roughly 45 ms (about 60 ms at Threads=8 with a larger hash), against a MoveOverhead of 30 ms, so a fixed movetime returned 12 to 28 ms late. A warm search pays 2 to 4 ms and returns early.

Black did not fail because applying `e2e4` in the `position` command already loads the move-generation classes before `go`. As White on move 1 the GUI sends a bare `position startpos`, so the cost lands on the first `go`.

## Change

The UCI loop reads `System.nanoTime()` as soon as a line arrives. For a `go` that value is stored on the `TimeManager` (`markRequestReceived`) and `startNow()` uses it as the origin instead of "now", then clears it. Allocation, MoveOverhead and the search are untouched. The first move gets about 45 ms less thinking time and no longer overshoots.

Other cases, checked and tested:

- `ponderhit` restarts the clock at the hit and throws away any pending origin, so ponder time is not charged. A bare `go ponder` has no limits until the hit.
- Depth, `nodes` and infinite searches have no time manager. (`go nodes` is parsed but not enforced; that is separate and unchanged.)
- Each `go` builds its own manager, and a manager that is reused gets a fresh clock on its next `startNow()`.
- If the budget is already spent when the search starts, `Searcher` now returns the best move of the cut-off first iteration if it had one, otherwise the first legal root move (respecting `searchmoves`), instead of none. A mated or stalemated root still returns none. `UciApplication` already had a last-resort fallback to the first legal move; this makes the search layer itself safe for the other callers.

I did not add a warm-up search at `isready` and did not raise MoveOverhead.

## Result

Same harness, native Windows, packaged jar, 5 fresh processes per row unless noted. Overshoot against movetime, negative means early.

| Condition | Before | After |
|---|---|---|
| White cold, Threads=1, Hash=64, movetime 1000 | +12.8 ms | -29.0 ms |
| White cold, Threads=1, Hash=64, movetime 15000 (3 runs) | +12.6 ms | -29.0 ms |
| White cold, Threads=8, Hash=256, movetime 1000 | +24.2 ms | -28.6 ms |
| Black cold, Threads=1 | -20.2 ms | -29.0 ms |
| Black cold, Threads=8 | -8.5 ms | -28.5 ms |
| Warm, Threads=1 | -28.3 ms | -29.6 ms |
| Warm, Threads=8 | -26.9 ms | -29.6 ms |

Clock-based searches (`go wtime 40000 btime 40000`) were within run-to-run noise of before. These are medians; per-condition minimum and maximum are within about 3 ms of the median for the fixed-movetime rows.

## Tests

`RequestOriginTimingTest` puts the origin in the past, so nothing depends on how fast the machine is: the origin is kept, consumed once, discarded by `ponderhit`, charged against an exhausted budget, and the exhausted-budget search returns a legal move. `GoRequestOriginTest` checks what each form of `go` does with the origin. The wall-clock check lives in the native harness above, because a CI runner is too noisy for a 30 ms margin.
