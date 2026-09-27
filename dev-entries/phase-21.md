# Dev Entries - Phase 21

### [2026-09-27] Phase 21 Stage 0: accepted baseline and exact reproduction

**Built:**

- Applied the three bounded amendments to `docs/architecture/research/phase21-direction-and-preregistration.md` before execution. Stage 2 will derive PV identity solely from the window, preferably removing the explicit alphaBeta flag after checking auxiliary callers. Stage 1 has no population-size threshold. Project SPRT bounds remain unchanged; any alternative needs separate preregistration after Stages 1 to 5 and before Stage 6.
- Inspected the complete local and remote develop-to-Phase-20 delta: 15 commits, 116 files, 107,165 inserted lines and 6 deletions, including the preserved binary evidence. The production engine-core tree was identical. The remaining UCI changes add opt-in work/elapsed/drain diagnostics, rather than the lifecycle repair already merged in PR #247.
- Independent review found no blocking correctness or integration-scope issue. The Phase 17 closure deliberately records Git retention for audit, and the Phase 20 dev entry records committed Stage 3 and preserved Stage 6 results. Archival provenance does not designate these files as branch-only. The complete branch was therefore intentionally included in [PR #248](https://github.com/coeusyk/chess-engine/pull/248), with no omitted files or promoted Phase 17 search implementation.
- PR #248 passed Backend CI, including module tests, search regression, the CI benchmark gate and NNUE checks, before its normal merge. Verified merged develop `d3a56ffadf0d9151a2b19fba4902a734a99bff96` locally and remotely, and verified its tree equals Phase 20 tip `2ecab419e8d8e5bd8cded24d076ba0b0db80cc9a`.
- Created `phase/21-zero-window-search` from that exact accepted develop head. Control JAR was built from this base before any production instrumentation change. SHA-256: `cdf7fe59b773d55f4a79a59ccccd9b1b6d804f82332b6200cc88b9e65fa5e95b`.
- Added `tools/Phase21ControlHarness.java` and `tools/phase21-logback.xml` for repeatable semantic controls. The harness reads the canonical BenchRunner corpus directly, uses a fresh Classical Searcher and 16 MB TT per position, sets board search mode, checks every completed depth, asserts the canonical total and asserts the complete P18-5 rows. Its TSV output excludes elapsed time.

**Decisions Made:**

- Threads=1, Hash=16 MB, Classical, default 1 MB pawn hash, neutral contempt, MultiPV=1, no book or tablebase, no abort or time manager. All iterative depths and aspiration attempts contribute to reported semantic totals. WSL2 Ubuntu 24.04.4, OpenJDK `21.0.12.1+1-1-24.04.4-Ubuntu`; JVM flags `-Xms512m -Xmx512m -XX:+UseG1GC --add-modules jdk.incubator.vector`. No timing decision uses this environment.
- Source inspection found six alphaBeta call sites: root, null move, reduced LMR probe, current LMR full-depth re-search, ordinary child and singular alternative. Null-move and singular auxiliary calls use width-one windows and flag false. No auxiliary caller intentionally needing wide-window/non-PV semantics was found. This is source evidence only; the API remains unchanged in Stages 0 and 1.

**Broke / Fixed:**

- No semantic mismatch. Context-mode calls hung and were terminated; bounded local processing was used for inspection. Local git writes required sandbox escalation because `.git` is read-only under the workspace profile.

**Measurements:**

- Stage 0 instrumentation disabled: all 31 depth-13 rows completed and totaled exactly **24,780,049 main nodes**. Complete 31-position semantic vector and five depth-8 rows are preserved in [`phase21-stage0-control.tsv`](../docs/architecture/research/phase21-stage0-control.tsv). TSV SHA-256: `ad577304b36f5296642a26f550dc5ef9e0fbc668884ef22a132982e913ae2fd5`.

| Position (zero-based corpus index) | Move | Score | Nodes | Qnodes | TT hits | PV |
|---|---|---:|---:|---:|---:|---|
| Start (0) | e2e4 | 25 | 14,926 | 39,927 | 4,908 | e2e4 e7e5 b1c3 b8c6 g1f3 |
| K+P vs K (2) | e1d2 | 122 | 1,226 | 1,816 | 1,024 | e1d2 e8d7 e2e4 d7d6 d2d3 d6e5 d3e3 e5e6 |
| Tactical middlegame (6) | b4b2 | -63 | 34,694 | 88,266 | 14,691 | b4b2 e3d2 b2b6 d3d4 e6c4 f1b1 b6a5 f3e5 c4b5 |
| Rook/pawn (13) | b4f4 | 14 | 6,456 | 14,776 | 1,938 | b4f4 h4g3 f4c4 h5c5 a5b4 c5c4 b4c4 g3g2 c4d3 g2f2 |
| Queen/king (20) | d7d2 | 1,565 | 8,902 | 16,736 | 3,982 | d7d2 c4d5 a8a3 a1b1 c5c4 f1d1 d2c3 f2f4 |

| Stage 0 command | Result |
|---|---|
| `mvn -B -pl engine-core test` | PASS: 405 tests, 0 failures/errors, 5 configured skips |
| `mvn -B -pl engine-core,engine-uci,engine-tuner -am test` | PASS: core 405/5 skipped, UCI 42/8 skipped, tuner 131/1 skipped; 0 failures/errors |
| `mvn -B -pl engine-core,engine-uci,engine-tuner -am package -DskipTests` | PASS: all selected reactor modules built |

All shell commands use the required `rtk` prefix. Repeat the controls after the reactor package build:

```bash
rtk proxy javac --add-modules jdk.incubator.vector -cp engine-uci/target/engine-uci-0.6.0-SNAPSHOT.jar -d /tmp/phase21-control-classes tools/Phase21ControlHarness.java
rtk proxy java -Xms512m -Xmx512m -XX:+UseG1GC --add-modules jdk.incubator.vector -Dlogback.configurationFile=tools/phase21-logback.xml -cp /tmp/phase21-control-classes:engine-uci/target/engine-uci-0.6.0-SNAPSHOT.jar Phase21ControlHarness false
```

The frozen control run used an identical copy of the logging configuration at `/tmp/phase21-logback.xml` and the hashed control JAR at `/tmp/phase21-control.jar`. Harness output confirmed `CONTROL PASS total=24780049 reference_rows=5 instrumentation=false`.

**Stage 0: PASS.** Exact reproduction and normal tests/builds passed. Stage 1 diagnostics are the next authorized slice; Stage 2 is not authorized.
