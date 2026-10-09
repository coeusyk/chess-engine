# Phase 21 native Stage 5 evidence

The authoritative run is [20261009T142702733Z](20261009T142702733Z/). Its 25 original files were copied byte-for-byte from the user's native Windows checkout. [The independent review](../phase21-stage5-native-throughput-review.md) confirms Stage 5 PASS. Each run directory contains a `SHA256SUMS` manifest. The local `.gitattributes` disables text conversion so Git preserves the original BOMs and line endings.

[20261009T142315639Z](20261009T142315639Z/) preserves the available earlier failed-startup transcript. That directory contained only `runner.log` when archived, and no raw benchmark outputs. It is excluded from the measured set.

## Documented preparation changes

The frozen production identities remain control `d3a56ffadf0d9151a2b19fba4902a734a99bff96` and candidate `e90d3d4f90b244c46e8bbf75c33222bba7d58003`. No engine implementation or experimental gate changed during these tooling corrections.

| Record | Change and scope |
|---|---|
| `bdd488a3ea2a259ecd94a7f90802d3868be3c94e` | Completed the draft, removed its two extra benchmark preflights, and retained the frozen one discarded warm-up plus seven measured runs per arm. Hardened native checks, artifact retention and cleanup. |
| `1d95811cf33802efadff284a015e0e31188bd0dc` | The user reported build 26300 and explicitly removed the fixed Windows edition/build requirement. Machine identity became computer name `RENEGADE` plus Ryzen 7 7700X. OS details remain recorded; native execution, JDK and power-plan requirements remain. |
| `0671e1adc18ec1ff647f688c4f406ee961a3441a` | Corrected the CRLF-sensitive source hash after the failed startup. Only CRLF is normalized to LF for the source SHA-256. JAR hashes remain byte-exact. Added regression fixtures and fixed synthetic command-marker quoting. This is the invoking commit recorded by the successful run. |

The successful schedule was two discarded warm-ups followed by seven alternating control/candidate measured pairs. There were no retries or extra benchmark invocations in that directory. Both source/JAR identities, the complete environment, build logs, raw outputs, planned and actual schedules, summary and transcript are preserved.

The review independently reproduces candidate median elapsed 62.732 s below control minimum 68.156 s, candidate median NPS 346,127 and candidate aggregate NPS 342,664 against floor 301,116. The candidate/control median elapsed ratio is 0.8737655825614598. Stage 5 is throughput evidence; no games or Stage 6 execution are included.
