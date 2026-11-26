# Amendment 2: two confirmation rounds for the #313 NNUE comparison

Recorded 2026-11-26 before any record for rounds 3 and 4 of `s313` exists in results.jsonl (the last s313 raw record is `raw:s313:nnue:r2:base_repeat`, finished 2026-11-25 16:29 UTC). Authorized by the owner in the Phase 22 continuation request of 2026-11-26.

## Why

Only one of the three original #313 NNUE rounds (round 0) is clean under the exclusion rule fixed in INCIDENT-1 (no WSL CPU sample above 12 percent in any unit window of the round). Rounds 1 and 2 are supplementary. One clean round is thin primary evidence for a large effect (candidate 294,595 against base 179,034 nodes per second in round 0).

## What is run

- Suite `s313` only, NNUE mode only, depth 13, the same frozen jars and the same net, JVM flags and runner (`measure.py`, v3) as before. Nothing in the workload, the builds or the exclusion threshold changes.
- Exactly two additional rounds, indices 3 and 4, each with its discarded base warm-up run. The schedule is the existing function `rotated(["base", "base_repeat", "candidate"], r)`, so round 3 has the order of round 0 and round 4 has the order of round 1. Each position therefore appears once per variant across rounds 0 to 4 plus the repeated order, as in the original design.
- Invocation: `python3 measure.py --repo <checkout> --phases raw --suites s313 --rounds-nnue 5`. Rounds 0 to 2 are keyed in results.jsonl and are skipped, so only rounds 3 and 4 run. The summary phase is not run, so summary.json is not rewritten. Analysis is done by `analyze_313.py`, which only reads.
- The runner's own headroom gate (Windows CPU average at most 15 percent, at least 6144 MiB free, no match launcher present) runs before the first round, as in the original run.

## Frozen identities (verified before launch)

- `jars313/base.jar` SHA-256 `7742ac23676f8c20427a2c0f2a3951b0cc012fdc86187972ff9d90479fb7d2e9`
- `jars313/candidate.jar` SHA-256 `9e538d300f04d31a62a64c7b60858fb5101c98e6594231a834c1439408e3e5e7`
- `net.nnue` SHA-256 `fca29285758e736c99e980ba754f288ef8347eb1d0ca0e265ca70bcf4e061e59`, which the engine reports as network `dfffd3da-7f8f-4fc9-92dc-b3873c97fb21`
- Fixed-depth correctness for the pair: the Gate 1 records of the original run (identical per-position node counts, 112,142,129 nodes, base against candidate). Not repeated.

## Validity and reporting rules, all unchanged

- A round is clean only if no unit window in it contains a WSL CPU sample above 12 percent in pressure.csv. A flagged unit excludes its whole round from the primary result. Excluded rounds are reported as supplementary.
- No local development, tests, builds, profiling, training, matches or progress checks run on this host while the two rounds run. The runner is started once and I do not poll it.
- If a round is contaminated or the runner stops, that is reported as it happened. No round is rerun to replace a bad one, and no third round is added.
- The two new rounds are combined with original round 0 as primary evidence only if they are clean under the same rule. Each round is reported separately: paired candidate against base difference, base_repeat against base difference, medians and ranges. No interval or p-value is reported for three rounds.
