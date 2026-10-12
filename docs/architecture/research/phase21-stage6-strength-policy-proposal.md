# Phase 21 Stage 6 frozen execution preregistration

Approved by the maintainer on **2026-10-09**, before the first game. This replaces the proposal at the same path. **Stage 6 is unstarted.** Preparation and synthetic validation provide no strength evidence.

The approved `[0,10]` comparison is a **Phase 21 exception**; the generic `[0,50]` policy remains unchanged. See the [dated amendment](phase21-direction-and-preregistration.md#15-approved-stage-6-amendment--2026-10-09) and [project policy exception](../../sprt-guidelines.md#5-phase-21-exception-approved-2026-10-09). No production code, search parameter or earlier gate changes.

## Frozen engines and host

| Identity | Required value |
|---|---|
| OLD/control | `d3a56ffadf0d9151a2b19fba4902a734a99bff96` |
| NEW/candidate | `e90d3d4f90b244c46e8bbf75c33222bba7d58003` |
| Execution | Native Windows PowerShell terminal on `RENEGADE`, AMD Ryzen 7 7700X; no WSL/interop match execution |
| Resources | Balanced power plan, full hardware CPU affinity, no competing CPU workload. Record Windows caption/version/build; routine OS updates are accepted. |
| Build/runtime | Azul Zulu JDK `21.0.10+7-LTS`, Maven `3.9.16`; same native toolchain for both isolated detached worktrees. Maven must use the verified JDK. |
| Match tool | `cutechess-cli 1.5.1`; record executable path, version and SHA-256. Set `CUTECHESS` to its native executable if absent from PATH. |

Build both exact commits in fresh native worktrees. Require clean sources before/after building and before launch; reject pre-existing module outputs. Use Maven `-B -pl engine-core,engine-uci -am package -DskipTests`. Validate JAR contents/main class, copy each executable JAR into the unique evidence directory, verify the copy, and freeze its byte SHA-256 before game 1. Never substitute the branch tip. The invoking checkout must be clean, allowing only unrelated untracked `.claude/agent-memory/`.

Stage 5 binaries were cleaned up. Their [archived hashes](phase21-stage5-native/README.md) remain provenance, not expected byte hashes for rebuilt ZIP/JAR files. Native preparation will record the new Stage 6 JAR hashes; none are claimed in this tooling commit.

## Frozen match and decision

| Setting | Exact value |
|---|---|
| SPRT | `Elo0=0`, `Elo1=10`, `Alpha=0.05`, `Beta=0.05`, `BonferroniM=1` |
| Limits | `MinGames=0`, `MaxGames=20000` scored games; no minimum-stop barrier |
| Time/parallelism | `TC=5+0.05`, `Concurrency=6`, `EngineThreads=1` |
| Both arms' UCI options | `Threads=1`, `Hash=16`, `PawnHashSize=1`, `EvalType=Classical`, `MultiPV=1`, `Contempt=0`, `OwnBook=false`, `SyzygyOnline=false`, empty `SyzygyPath`; all other defaults unchanged |
| JVM arguments | Same Java executable; `--add-modules jdk.incubator.vector -jar <arm-jar>`. No inherited `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, `_JAVA_OPTIONS` or `MAVEN_OPTS`; no instrumentation, JFR, agents or diagnostic overrides. |
| Openings | `tools/noob_3moves.epd`, SHA-256 `2011193b4854e9a8cfdc05312ca2dbaffa6ceae3abbdee20e2ead2a18a603347`; archive verified bytes before launch |
| Opening settings | `format=epd`, `order=random`, `plies=4`, `-repeat` swapped colors. Existing runner exposes no seed parameter; retain actual opening order in PGN. |
| Resignation | `-resign movecount=5 score=400` |
| Draw adjudication | `-draw movenumber=40 movecount=8 score=10` |

Let Δ be candidate minus control Elo under these conditions. H0 acceptance favors 0 over +10 and **rejects the candidate**; it does not prove exactly zero gain or a regression. H1 acceptance favors +10 over 0 and makes the candidate **eligible for promotion after evidence review**; it does not demonstrate a minimum +10 gain. Effects inside the interval have no promised classification.

Alpha is the nominal probability of accepting H1 when Δ=0; beta is the nominal probability of accepting H0 when Δ=+10 under the test model. Neither is a posterior probability that the verdict is wrong. Cute Chess 1.5.1 uses W/L/D likelihood with an estimated draw parameter, not a pentanomial pair likelihood. LLR boundaries are `ln(0.05/0.95)` and `ln(0.95/0.05)`, approximately **−2.944439 and +2.944439**; logs round them.

Stop at an accepted boundary. At **20,000 scored games without a boundary**, report **INCONCLUSIVE**, with no promotion. Setup/protocol faults stop the owned match tree for investigation. An interruption or incomplete evidence is not H0 and cannot authorize promotion. No selective discard, automatic rerun, rescue SPRT or post-hoc change of bounds, options, concurrency or cap.

The existing runner's `-recover` argument is retained. The wrapper watches the flushed authoritative log every 200 ms and stops its owned child tree on detected crashes, disconnections, timeouts, forfeits, illegal moves or option errors. Recovery may begin before observation; it remains fault evidence and cannot yield a valid promotion. Boundary cancellations are recorded separately from scored games. Color/pair audits retain all scored games, including an incomplete pair at a boundary; they do not alter Cute Chess's LLR.

## Native workflow and evidence

From a clean native Windows checkout of `phase/21-zero-window-search`, run harmless fixture validation:

```powershell
.\tools\phase21-stage6.ps1 -ValidateOnly
```

The **eventual** native execution command is:

```powershell
.\tools\phase21-stage6.ps1 -ExecuteNative
```

Execution mode verifies host/tool versions, corpus, sources and binaries, prepares generic runner arguments without launching Cute Chess, independently checks them against this registration, and freezes the input manifests. It requires the operator to type `RUN-PHASE21-STAGE6` after confirming an idle host. Input manifests, binaries and executable identities are rechecked before one match starts. Do not invoke execution mode from WSL. This preparation task stops before that command and before game 1.

Retain evidence under `tools/results/phase21-stage6/<UTC timestamp>-<unique suffix>/`:

- Approved configuration; environment/tool identity and version logs; source commits/clean-state verification; checkout/build logs; both executable JARs; Java/Maven/Cute Chess executable hashes; archived opening corpus/hash.
- `prepared-arguments.json`, `runner-parameters.json`, actual `arguments.json`, script/source identities and launch confirmation. Parameter/prepared-argument hashes are frozen before confirmation and rechecked before launch; the runner refuses an actual argument mismatch.
- Full authoritative `match.log`, `match.pgn`, console streams, runner transcript, owned-process identity and stop log when applicable.
- `summary.json`: candidate W/L/D, scored/cancelled/partial counts, final LLR/bounds, accepted endpoint, native exit code, verdict, fault lines and audit issues. `pair-audit.csv` and summary retain candidate results by color, completed opening pairs and unpaired scored games.
- `SHA256SUMS` for all available run files. Partial outputs survive setup failure, match fault or ordinary interruption. Cleanup removes only invocation-owned worktrees, without force; dirty/partial worktrees remain. Frozen binaries and run artifacts remain. Killing the terminal or an OS failure can prevent final summary/checksum generation; preserve raw evidence and investigate without an automatic restart.

No automatic promotion. Review source/JAR identities, full configuration, log/PGN agreement, color/pair balance, cancellations and any crashes, forfeits, timeouts or interruptions. `H1_ACCEPTED` requires a finished match, zero exit code, consistent evidence and no detected fault. Uncertain validity requires investigation, not score reinterpretation.

## Validation and remaining native checks

`-ValidateOnly` uses inert executable files, synthetic logs/PGNs, mocked owned-process/worktree cleanup, and harmless `cmd.exe` stdout/stderr/nonzero-exit checks. It never executes Cute Chess, Java, Maven, builds or games. Windows PowerShell 5.1 and PowerShell 7 have run fixtures through WSL interop; this validates tooling, not experimental performance or strength.

The generic runner lacked preparation, exact argument freeze, immediate log flush and native exit-code rejection; these are now supported without changing match arguments/defaults. Its defaults still differ from project policy; missing openings remain optional generically; `MinGames` is not a minimum-stop barrier. The Phase 21 wrapper supplies/verifies all approved settings, requires the opening hash, and uses approved `MinGames=0`.

Remaining checks in the maintainer's native terminal: actual host/JDK/Maven/Cute Chess identity, clean isolated builds and executable JAR hashes/contents, the prepared native manifest, and live process-tree/interrupt behavior. No installed Cute Chess executable has been launched during preparation, even for `--version`.

The earlier proposal estimated approximately 3,199 games at a true endpoint and 5,233 at the midpoint, assuming independent scores, 50% draws and a local logistic approximation. At an assumed 10–20 seconds/game and concurrency 6, that is roughly 1.5–3 or 2.4–4.9 hours; the cap corresponds to 9.3–18.5 hours. These are illustrative sequential/runtime estimates, **not measured Phase 21 game data or stopping targets**. Stage 5 throughput does not predict Elo or match duration.
