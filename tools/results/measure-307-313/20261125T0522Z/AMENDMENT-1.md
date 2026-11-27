# Amendment 1 to the #307 / #313 measurement protocol

Approved by the owner in the session on 2026-11-25. Recorded at 2026-11-25 06:4x UTC, before any raw, timed or allocation result exists in this archive (results.jsonl holds only headroom and Gate 1 records, no `raw:` key).

## Why

A depth-13 NNUE bench takes about 10.6 minutes (112,142,129 nodes at about 176k nodes per second; Classical takes about 1.1 minutes). The protocol's 10 rounds with a discarded warm-up each, over 7 variants for #307 and 3 for #313, comes to roughly 25 hours of host time, which I had not estimated correctly when I started the run.

## What changes

- NNUE speed measurements, for #307 and #313: 10 rounds become 3 initial rounds (round indices 0, 1, 2), each with its discarded warm-up run. The rotating schedule is the same function of the round index, so an extension continues it. Extension to 5 rounds (indices 3 and 4) happens only if the variability between rounds of the same variant (base against the repeated base) is too large for an engineering decision to be drawn from the result. The decision to extend is made from the base versus base_repeat spread and is recorded, never from the candidate differences.
- A consequence to state plainly: three rounds of a seven variant rotation do not form a complete Latin square, so position-in-round effects are balanced only partly. The base_repeat arm exists to show that.

## What does not change

Depth 13, every Gate 1 correctness check, the frozen builds and their hashes, Classical repetitions (10 rounds), the rotating schedule, the timed UCI runs (10 rounds, Classical), the allocation runs, the headroom gate, the JVM flags and the net. No pass or fail threshold is introduced.

## How it is applied

The running invocation was not interrupted mid-run: at the first moment between two units (a unit is one finished bench, recorded in results.jsonl) the runner process was replaced by one started with the same script plus a per-mode round count (`--rounds-nnue 3`). Every completed measurement stays in results.jsonl and is reused, because units are keyed and a restart skips finished keys. The original runner is kept as `measure.v1.py` (SHA-256 `d3d28e3cb011ea36...`, the first 16 hex digits of the hash printed when it was copied). The only code change is the per-mode round count in `phase_raw`.

## What will be reported

Per-round results, the median and range per variant, the paired difference to base within each round, and the base versus base_repeat spread, with no more confidence stated than three rounds support. Intervals and p-values will not be reported for the NNUE comparisons.
