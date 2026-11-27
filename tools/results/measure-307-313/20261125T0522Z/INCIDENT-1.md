# Incident 1: local work overlapped measurement units, and the runner stopped itself

Recorded 2026-11-25, before any candidate comparison has been drawn from these results.

## What happened

1. While the run was in progress I ran local work on the same host for unrelated PRs (Python unit tests, mutation checks and small git and gh commands). The owner's instruction was no intensive local verification while the measurements run. These runs were short, but they were not nothing.
2. At 07:16 UTC the runner stopped itself with `foreign load appeared`. Its guard (v1 and v2) searched the full command line of every process for `cutechess`, `sprt_linux`, `sprt.ps1`, so a command of mine that only mentioned those names (a unit-test module name) matched. It stopped before starting the next bench, so no bench was cut short and no record is partial. Last record: `raw:s307:classical:r2:base` at 07:16:10.

## Which units overlapped

From this run's own `pressure.csv` (WSL CPU, one sample every 10 seconds; the median over the run is 8.3 percent, which is one JVM). Classical units whose time window contains a sample above 12 percent:

- round 0: base_repeat (20.2), terminal (22.2), onmake (18.2)
- round 1: base_repeat (19.5), terminal (18.4), onmake (17.9), poll (18.6)
- round 2: none above 12 (the highest, base, is 11.1)

Seven of the 21 Classical units so far are affected, all in rounds 0 and 1. Their NPS values stay in results.jsonl, unmodified.

## How this will be handled

- No record is edited or deleted.
- Analysis uses the units not listed above. A flagged unit is excluded together with its whole round for paired differences, so rounds 0 and 1 are dropped from the Classical comparison and the remaining 8 rounds (2 to 9) are analysed. Any later unit whose window shows a CPU sample above 12 percent is treated the same way. This rule is fixed here, before the candidate-versus-base differences are looked at.
- The NNUE rounds and everything after this point are not yet started and are not affected. All local work on this host stops for the rest of the run.
- Gate 1 (node counts) does not depend on timing and is unaffected.

## Runner change

`measure.v2.py` is kept. `measure.py` (v3) differs only in `foreign_load()`: it now flags a process whose executable is `cutechess-cli`, `cutechess` or `fastchess`, or a python or pwsh process whose script is `sprt_linux.py`, `sprt.ps1` or `fixed_count_match.py`, instead of matching names anywhere in a command line. This is an operational fix, not a protocol change. The run restarts from the recorded keys with `--rounds 10 --rounds-nnue 3`, as in Amendment 1.
