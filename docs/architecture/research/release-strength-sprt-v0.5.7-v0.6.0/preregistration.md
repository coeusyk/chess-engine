# Preregistration: Vex v0.6.0 against v0.5.7, playing-strength SPRT

Status: configuration approved by the maintainer for preregistration on 2026-10-12. The production SPRT is NOT started and needs a separate explicit approval and a confirmation that the host is idle. A bounded smoke test is authorized, to run only after a native preflight passes (section 17). No game, pilot match or search has been played for this experiment so far.
Date: 2026-10-12. Branch: `research/v0.6.0-release-sprt-prereg`, from develop at `b5f54ab9346431bf3c79c28f4e77ce9102a8b681`.

The runner refuses to start unless the SHA-256 of this file equals the value pinned in `tools/release-strength-sprt-common.ps1`, so any later edit has to be re-approved and re-pinned. Maintainer decisions recorded on 2026-10-12: the configuration in sections 2 and 3 including the 20,000-game-start cap; Cute Chess is the authoritative stopping decision and the analyzer only reconciles and reports; reaching the cap is INCONCLUSIVE; zero tolerance for unexplained time forfeits, crashes and protocol failures; a separately recorded smoke test after a passed preflight; no production SPRT yet.

## 1. Question and scope

Does the published `engine-uci-0.6.0.jar` play stronger than the published `engine-uci-0.5.7.jar` at 5+0.05, one thread each, Classical evaluation, on one Windows host, from one frozen opening list?

This is the only claim the experiment can support. It does not test NNUE, Lazy SMP, longer or shorter time controls, other hardware, or any other pair of builds. The [release comparison](../release-comparison-v0.5.7-v0.6.0/README.md) measured fixed-depth search work and elapsed time. Those numbers are not an input to this test and are not used to predict or interpret its outcome: a smaller tree can come from stronger search or from more aggressive pruning, and the two releases chose different depth-13 best moves in 12 of 30 positions.

The baseline is the published v0.5.7 JAR (source `beaef361d35160d768473af7d2c349719db8508b`), not the build of the `v0.5.7` git tag. The tag points at older source (`7b5b3a4332193d28a1f668654806fac434f2cb8f`) that the published JAR was not built from; the release comparison documents this and the tag is immutable.

## 2. Hypotheses and decision rule

NEW is v0.6.0 (the candidate). OLD is v0.5.7 (the baseline). Elo is the logistic Elo difference NEW minus OLD.

| | |
|---|---|
| H0 | NEW minus OLD is 0 Elo or less |
| H1 | NEW minus OLD is 20 Elo or more |
| Bounds | Elo0 = 0, Elo1 = 20 |
| Error rates | alpha = beta = 0.05, no multiple-comparison correction (one hypothesis) |
| LLR boundaries | lower = ln(0.05 / 0.95) = -2.944, upper = ln(0.95 / 0.05) = +2.944 |
| Minimum games | none |
| Cap | 20,000 game starts (10,000 pairs) |
| Who decides | Cute Chess 1.5.1 alone. The runner reads its decision from the log; the analyzer cannot change it |

What each outcome does and does not mean:

- H1 accepted: the data favor a gain of at least 20 Elo over a gain of 0. It does not show the true gain is at least 20, and the point estimate may be below 20. The Phase 21 closure records the same caveat for its [0,10] result.
- H0 accepted: the data favor no gain over a gain of 20. It does not show v0.6.0 is equal or weaker, and a true gain between 0 and 20 is a likely way to end here.
- Cap reached with no boundary crossed: INCONCLUSIVE. The cap is on game starts, so games cancelled at the end count toward it (19,998 scored plus 2 cancelled is still the cap). It is reported with the Elo estimate and interval and is never read as H0, as H1 or as a lean toward either. The runner can only write H0 or H1 when Cute Chess's own log says `H0 was accepted` or `H1 was accepted`.

Planning numbers, not commitments. These use the normal-approximation LLR and the Phase 21 draw ratio of 39.7%. The draw ratio between these two releases may differ.

| True Elo (NEW minus OLD) | P(accept H1) | Expected games to a boundary |
|---:|---:|---:|
| -20 | 0.000 | 356 |
| 0 | 0.050 | 967 |
| 5 | 0.187 | 1,346 |
| 10 | 0.501 | 1,579 |
| 15 | 0.814 | 1,342 |
| 20 | 0.950 | 961 |
| 30 | 0.997 | 529 |

A true gain near the middle of the bounds (about 10 Elo) is the case where the test is least decisive and takes longest. For scale, the Phase 21 run measured +6.6 Elo at [0,10] and needed 13,118 scored games, taking 7 hours 13 minutes of wall-clock at this concurrency and time control.

## 3. Fixed configuration

| Item | NEW (candidate) | OLD (baseline) |
|---|---|---|
| JAR | `engine-uci-0.6.0.jar` | `engine-uci-0.5.7.jar` |
| SHA-256 | `e16163b8fe6f8278161db0d9220883c5d49a4814df0d81f7a6cad901c07cf4e7` | `bc41c91b0d3b4c20f6a334ff3f0cc2cf5c04e6820b817b1cfd46e62a640663d3` |
| Source of the JAR | `bc3dc380e759526e8aa19cd8385af16ab22ac502` | `beaef361d35160d768473af7d2c349719db8508b` |
| Command | `java --add-modules jdk.incubator.vector -jar <jar>` | same |
| `Threads` | 1 | 1 |
| `Hash` | 16 | 16 |
| `PawnHashSize` | 1 | 1 |
| `MultiPV` | 1 | 1 |
| `Contempt` | 0, set explicitly | 0, set explicitly |
| `OwnBook` | false | false |
| `SyzygyOnline` | false | false |
| `SyzygyPath` | empty | empty |
| `EvalType` | `Classical`, set explicitly | not sent, option does not exist in v0.5.7 |
| `MoveOverhead`, `Ponder` | engine defaults (30, false) | engine defaults (30, false) |

The asymmetry in `EvalType` is the only difference between the two option lists. v0.5.7 has no NNUE and no `EvalType` option, so Classical is its only evaluator. v0.6.0 defaults to Classical, and the option is set anyway so the run does not depend on a default. The runner refuses to send `EvalType` to the baseline and the handshake check fails if the baseline advertises it.

`MoveOverhead` is left at the default of 30 ms for both engines. At 5+0.05 that is large against the 50 ms increment, but it is identical for both and unchanged from the Phase 21 match, so it is recorded here and not tuned.

Every other setting is the engine's own default. Option values are sent through Cute Chess as `option.Name=Value`, the same mechanism the Phase 21 match used.

Correction to the release comparison report, made in this branch: its Settings paragraph said the UCI `Contempt` default is 50 in v0.5.7 and 0 in v0.6.0. That is true of the source the `v0.5.7` git tag names (`7b5b3a4`, `contempt = 50`), and false of the published v0.5.7 JAR (source `beaef36`, `contempt = 0`), because the default was reverted in `29aafac` on 2026-07-03. Both published JARs advertise `Contempt ... default 0`, archived in `compatibility/`. The report now carries the corrected sentence and a Corrections section that keeps the original wording and the evidence. The explicit `Contempt=0` here makes the default irrelevant to this experiment.

## 4. Host, runner and tool identities

| Item | Requirement | Recorded at run time |
|---|---|---|
| Host | `RENEGADE`, Windows, AMD Ryzen 7 7700X with 16 logical processors, native only, never WSL or interop | `environment.json` |
| One attempt at a time | machine-wide mutex `Global\VexReleaseSprt`, released by the OS if the process dies | `runner.log` |
| Power and affinity | Balanced plan, full processor affinity | `environment.json`, `launch-power-plan.log` |
| JDK | Azul 21.0.10, runtime 21.0.10+7-LTS | `java-version.log`, SHA-256 of `java.exe` |
| Match runner | `cutechess-cli` 1.5.1 | `cutechess-version.log`, SHA-256 of the executable |
| JVM environment | `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, `_JAVA_OPTIONS`, `MAVEN_OPTS` unset | `environment.json` |
| Idle host | mean CPU over a 15 s sample at most 5%, then operator confirmation | `environment.json` |
| Scripts | `release-strength-sprt.ps1`, `release-strength-sprt-common.ps1`, `release-strength-sprt-validation.ps1`, `phase21-stage6-common.ps1`, `phase21-stage6.ps1`, `sprt.ps1`, `release-sprt-analyze.py`, `release-sprt-openings.py` | SHA-256 of each in `identity.json` |
| Repository | clean checkout, HEAD recorded | `attempt-start.json`, `identity.json` |

No JVM heap or GC flags are passed. Both engines run under the same JDK defaults. The JARs are the published assets verified against the hashes above and copied byte-for-byte into the attempt directory; nothing is rebuilt.

Host qualification for six concurrent games. Concurrency 6, one thread per engine, on 16 logical processors, is the configuration the Phase 21 Stage 6 match used on this host (13,118 scored games, no fault lines). For this experiment, six-way concurrency is qualified by three things: the preflight gates in the table, which include 16 logical processors and an idle host; a clean smoke test at concurrency 6 with both JARs (section 17); and the in-run fault stop in section 9, which ends an attempt on the first time forfeit, crash or protocol failure. Measuring whether six simultaneous games change per-engine speed would need a live native benchmark, which is not authorized and is not part of this plan.

## 5. Opening corpus

File: `openings-v1.epd`, 10,000 positions, one per line, six-field FEN, all white to move, LF line endings, no duplicates.
SHA-256: `32a2dc9b6ee1fee14a44ebb915441a2401e54276f75424804fe70f7f48354e1d`.

Derivation, reproducible with `python tools/release-sprt-openings.py --check`:

1. Source book: `tools/noob_3moves.epd` as archived in `tools/results/phase21-stage6/20261011T061318503Z-b5a507c6/`, 150,932 distinct lines, SHA-256 `2011193b4854e9a8cfdc05312ca2dbaffa6ceae3abbdee20e2ead2a18a603347`. This is the book the Phase 21 match used. The book itself is gitignored in the repository, so the archived copy is the source of record.
2. Rank every line by the SHA-256 of the fixed salt `vex-release-sprt-v0.5.7-v0.6.0-openings-v1` and a newline, followed by the line.
3. Keep the 10,000 lowest ranks, in rank order.

Ranking by hash makes the choice independent of the order of the source file, of the Python version, and of any random-number generator. The Phase 21 command used `order=random` with no seed exposed, so its opening order is only known from its PGN. This run removes that: the order is fixed before the first game.

10,000 openings is exactly the number needed at the game cap (20,000 game starts, two per opening), so the list cannot be exhausted or wrapped. Checked against the cutechess-cli 1.5.1 source (`projects/cli/src/main.cpp`, `projects/lib/src/tournament.cpp`, `projects/lib/src/openingsuite.cpp`):

- `-repeat` without a value means two plays per opening. The first play reads the next opening from the suite and saves it. The second play reuses the saved opening with the sides swapped.
- With `order=sequential` the EPD reader returns the next record each time. It rewinds to the first record only when a read fails at the end of the file, which would be a 10,001st opening request. A cap of 20,000 starts makes 10,000 requests.
- Cute Chess rewrites the move counters in the PGN `FEN` tag (the archived Phase 21 PGN shows `0 1` for every game, against `0 4` in the book). Reconciliation therefore compares the first four FEN fields. All 150,932 lines of the source book, and so all 10,000 chosen, are distinct at that level, so no two openings can be merged.

The analyzer re-checks this after every attempt from the PGN: the openings used must be a gap-free prefix of the list, none used more than twice, none unknown.

## 6. Pairing and color reversal

Cute Chess runs with `-repeat` and `order=sequential`. Opening k is used for exactly two games, one with NEW as White and one with NEW as Black, and the pairs are taken from the top of the list in order. With six games in flight the two games of a pair may finish in either order and at different times.

A pair counts as complete only if both games finished with a scored result (win, draw or loss). A game cancelled with `*` is excluded from the score. If its partner finished, that partner is an unpaired game, and a pair whose second game was never started leaves one unpaired game. When Cute Chess stops at a boundary or at the cap, games still in flight can be unfinished (the Phase 21 match ended with 5 cancelled): they are recorded as `*` in the PGN and the log, kept, excluded from the score, and reported as cancelled games and unfinished pairs. That match also ended with 2 scored games whose partner was cancelled. The analyzer reports completed pairs, unpaired games and cancelled games separately, and the validation requires `scored = 2 * pairs + unpaired`.

## 7. Clock and adjudication

All settings are inherited unchanged from the Phase 21 match.

| Setting | Value |
|---|---|
| Time control | `tc=5+0.05` for both engines, no `timemargin` |
| Resign | `movecount=5 score=400` |
| Draw | `movenumber=40 movecount=8 score=10` |
| Tablebases | none: no `-tb`, empty `SyzygyPath` |
| Maximum game length | none set |
| Engine crash handling | `-recover` is on, but any crash is treated as a fault, section 9 |
| Other | `-ratinginterval 10`, `plies=4` (inherited; not expected to matter for EPD input, not verified) |

## 8. Game budget and stopping

- Maximum 20,000 game starts (10,000 pairs), the same number as the Phase 21 cap. The cap counts every game that was started, scored or cancelled.
- Stop when the LLR reaches -2.944 or +2.944, or at the cap, whichever is first. Cute Chess applies the rule.
- No minimum game count, no early stop on any other basis, no interim decision. The operator watches only for faults.
- Games in flight when a boundary is crossed finish or are cancelled by Cute Chess; cancelled games are recorded as `*`, excluded from the score and kept in the PGN.
- The match is one Cute Chess invocation. It is never resumed, extended or merged with another.

Verdicts the runner can write: `H1_ACCEPTED`, `H0_ACCEPTED`, `INCONCLUSIVE` (cap of 20,000 game starts reached with no decision), `INCOMPLETE`, `INTERRUPTED`, `SETUP_PROTOCOL_FAULT`, `NOT_STARTED`. Only the first three are results. The classification logic is the Phase 21 parser, which cross-checks log, PGN, score, game identifiers, color orientation and boundaries, plus one rule of this experiment: a clean, finished match with no decision whose scored and cancelled games add up to the cap is INCONCLUSIVE. H0 and H1 come only from Cute Chess's `H0 was accepted` or `H1 was accepted` line, and the validation checks that no cap or interruption can produce either.

## 9. Crashes, forfeits, cancellations and infrastructure faults

| Event | Handling |
|---|---|
| Engine crash, disconnect, stalled connection, illegal move, unknown or unsupported option, failure to start | Fault line detected in the live log: the owned process tree is stopped, the attempt is `SETUP_PROTOCOL_FAULT`, nothing is scored or promoted |
| Loss or win on time (a time forfeit) | Treated the same as a crash |
| Cute Chess exits non-zero, or its exit code is unavailable | `SETUP_PROTOCOL_FAULT` |
| Operator stops the run, host sleeps, power loss, session killed | `INTERRUPTED`, never a result |
| Log, PGN and score disagree, or the PGN has partial game records | `SETUP_PROTOCOL_FAULT` (audit issues listed in `summary.json`) |
| A game cancelled at a boundary (`*`) | Not a fault: excluded from the score, kept in the PGN, counted separately |

Zero tolerance applies to unexplained time forfeits, crashes and protocol failures. The runner cannot tell an explained event from an unexplained one, so it stops on every such event and the attempt is never scored. Whether the event is then explained is decided afterwards by the maintainer from the preserved evidence, and it matters only for whether a new attempt is allowed (below). An event with no established cause is treated as a finding about the engines or the setup, and the experiment is not restarted around it.

Restart rules.

- A faulted or interrupted attempt is never resumed, repaired or merged. A restart is a new attempt that starts at opening 1 with a new run directory.
- A restart is allowed only when the cause is established and does not depend on the match outcome so far (host, tooling, operator, power). The cause goes into the attempt table in the final report.
- A crash or illegal move by a published engine is a finding, not a nuisance. It is reported with its evidence, and the experiment is not simply re-rolled until it passes. Whether to restart then is the maintainer's decision.
- The primary result is the first attempt that reaches a boundary or the cap with no fault. Any other attempt that scored games is reported with its counts and LLR at the point it stopped, and is not pooled.
- Observing an interim LLR is not a reason to restart.

## 10. Attempt provenance

This section exists because the Phase 21 closure could not reconstruct its first attempt: six game starts, no preserved finished-game events, no recorded exit cause.

- Every invocation of the runner that passes mode selection, preflight or match, creates its own directory `tools/results/release-strength-sprt/<kind>-<UTC timestamp>-<random>/` and writes `attempt-start.json` (run id, kind, UTC start, repository HEAD and branch, or `unavailable` if git fails) before any other check, including the platform, dirty-tree, single-instance and host checks. A refused invocation therefore leaves a record with its failure text in `attempt-end.json`.
- After the operator confirms and before the first game, the runner rechecks the hashes of the binaries, the opening list, the parameters, Java, Cute Chess and every script recorded in `identity.json`, and refuses to launch if any changed.
- `attempt-end.json` is written in a `finally` block with the end time, whether the match started or completed, the exit code and the failure text. If it is missing, the process was killed and that is itself the record.
- The runner never deletes, overwrites or resets anything. A run id collision aborts. Failed and interrupted attempts are committed with the rest.
- The log is flushed to disk line by line as it is written, so a killed process leaves its finished-game events.
- `identity.json` lists the names of every earlier attempt directory present when the attempt started (`PriorAttempts`), so the history is reconstructible from any single attempt.
- Smoke attempts (`smoke-*`) and preflight attempts (`preflight-*`) live in the same directory and are listed like any other. They are not production results and are never pooled with one.
- `SHA256SUMS` is written last, in every case including failure. A missing `SHA256SUMS` marks an attempt whose runner was killed before cleanup.
- The final report lists every directory present under `tools/results/release-strength-sprt/`, whatever its state, and states the count. A report whose list does not match the directory is wrong.

## 11. Archived artifacts and validation

Each match attempt directory (production `run-*` and smoke `smoke-*`) contains: `attempt-start.json`, `attempt-end.json`, `approved-config.json`, `preregistration.md` (copy), `environment.json`, `power-plan.log`, `launch-power-plan.log`, `java-version.log`, `cutechess-version.log`, `openings-v1.epd` (copy; `openings-smoke-v1.epd` for a smoke attempt), `candidate-v0.6.0.jar`, `baseline-v0.5.7.jar`, `assets.json`, `uci-handshake-candidate.txt`, `uci-handshake-baseline.txt`, `runner-parameters.json`, `prepared-arguments.json`, `arguments.json`, `identity.json`, `launch-confirmation.json`, `owned-runner.json`, `runner.log`, `console.stdout.log`, `console.stderr.log`, `match.log`, `match.pgn`, `pair-audit.csv`, `summary.json`, `SHA256SUMS`. The directory has a `.gitattributes` of `* -text` so bytes survive checkout.

Validation after the run, in order. The report is not written until all pass.

1. `sha256sum -c SHA256SUMS` inside the attempt directory reports every file OK, and the listing covers every file present except itself.
2. Both JAR copies hash to the values in section 3, and `identity.json` records the script hashes and the preregistration hash.
3. `summary.json` has `AuditIssues` empty and `PartialPgnRecords` 0, and its win, loss and draw counts equal the last `Score of NEW vs OLD` line.
4. `python tools/release-sprt-analyze.py <attempt-dir> --elo1 20` (it reads the attempt's recorded kind, uses the matching opening list and labels a smoke attempt as not a result) reproduces the scored games and W/L/D from the PGN, and the `llr_reconciliation` agrees with the last `SPRT: llr` line within 0.01. The analyzer carries a port of the SPRT model in cutechess-cli 1.5.1 (Jeffreys +0.5 per class, BayesElo with an estimated draw Elo), so exact agreement is expected, with the 0.01 allowing for the log's two decimals. It reproduces the archived Phase 21 value (2.9709 against 2.97 printed) and an independent worked example (6,303-5,757-7,940 at [0,20] gives -2.819). A disagreement is reported and investigated. It does not change the verdict, which is Cute Chess's.
   The same run's `opening_reconciliation` shows `wrapped_or_repeated` false, a gap-free prefix of the opening list, and every opening used at most twice.
5. `arguments.json` equals `prepared-arguments.json` and the argument list in section 3 to 8.
6. The attempt table in the report accounts for every directory (section 10).

## 12. Reporting

Reported for the primary attempt, in this order and with these definitions:

- Counts: scored games, wins, draws, losses (NEW's point of view), cancelled games, completed pairs, unpaired games, draw ratio, and the NEW score split by color.
- Elo: logistic Elo from the score, `Elo = -400 log10(1/score - 1)`, with its nominal 95% interval from the empirical per-game variance. Reported as `Elo [low, high]`. Cute Chess's own `Elo difference` line is quoted next to it. These are fixed-sample intervals. The match stops on an LLR boundary, so their coverage after that stop is not guaranteed to be 95%, and the report says so next to them.
- Pair-level Elo and interval, from the completed color-reversed pairs, as a secondary figure. The primary figure is game-level, because that is what the stopping rule and the Phase 21 numbers use.
- LLR: Cute Chess's final value, the analyzer's reproduction of it, and the boundaries.
- Decision: one of H1 accepted, H0 accepted, inconclusive at the cap, using Cute Chess's stopping decision. The analyzer reads archived files and writes only to the terminal. It reconciles and reports and has no path to the decision. If its reproduction of the LLR disagrees with Cute Chess by more than 0.01, that is reported and investigated before any conclusion is written, and the decision stays Cute Chess's.
- The wording rules of section 2 apply. The report states that the result applies to these two binaries, this time control, one thread, Classical evaluation, this host and this opening list, and that it is not derived from or compared against the fixed-depth benchmark.
- Any early stop, fault, restart or deviation from this document is listed, with the attempt that contains it.

## 13. Assumptions and limitations

- Model assumptions of the stopping rule. Cute Chess 1.5.1 turns the win, loss and draw counts into a likelihood ratio between the Elo0 and Elo1 hypotheses with a BayesElo trinomial model: half a point is added to each class (a Jeffreys prior), the draw Elo is estimated from the same sample, and games are treated as independent. The LLR is evaluated after each game. The error rates alpha and beta are nominal: they hold under the model, and the model is an approximation of real engine games.
- The analyzer's port of that model was checked against the one archived result and one worked example, not against the C++ build. It is a reconciliation aid, and the C++ program remains the authority.
- Cute Chess's SPRT counts every game as independent, but the two games on one opening share a starting position. If their results are correlated, the nominal alpha and beta are not guaranteed. The pair-level statistics are reported afterwards and do not affect stopping, and the effect was not measured.
- One host, one time control, one thread. Six simultaneous games share 16 logical processors with Cute Chess itself, and their effect on engine speed was not measured.
- The opening list is a fixed sample of one book, all positions white to move after three moves. It is not a general opening distribution.
- I found no archived run of the published v0.5.7 JAR under Cute Chess 1.5.1 in the records read for this document. Its UCI handshake is verified, and its behavior in games is verified only by the fault monitor once games start.
- The wording of the Cute Chess warning for an unsupported option was not verified, since that needs the program. The fault matcher uses several plausible forms, and the baseline is not sent the option in the first place.
- Zero tolerance for unexplained time forfeits, crashes and protocol failures can end a long run on one event. That is the intended trade: a clean result over a resumable one.
- The handshake check confirms an option is advertised and the engine answers `readyok`. It cannot confirm that an option takes effect, since that needs a search.

## 14. Decisions

Decided by the maintainer on 2026-10-12:

1. The configuration in sections 2 and 3: bounds [0,20], alpha and beta 0.05, 5+0.05, six concurrent games subject to native qualification, Hash 16, a cap of 20,000 game starts.
2. Reaching the cap without a decision is INCONCLUSIVE.
3. Cute Chess decides when the SPRT stops. The analyzer reconciles and reports only.
4. Zero tolerance for unexplained time forfeits, crashes and protocol failures.
5. A bounded smoke test, recorded separately, after a passed native preflight. It does not lead to production games by itself.
6. The production SPRT is not started.
7. The Contempt sentence in the release comparison report is corrected, with its provenance kept.

Still open, and needed before any production game:

- Explicit approval to start the production SPRT.
- Confirmation that RENEGADE is idle at that time.
- A decision on the smoke result: SMOKE_PASSED does not authorize production; a SMOKE_FAILED needs review first.

## 15. Compatibility checks already done (no games, no searches)

- Both published JARs hash to the values in section 3.
- Each JAR answers `uci` and `isready` with `uciok` and `readyok` and exit code 0 when sent its full option list: [`compatibility/uci-handshake-candidate-v0.6.0.txt`](compatibility/uci-handshake-candidate-v0.6.0.txt) and [`compatibility/uci-handshake-baseline-v0.5.7.txt`](compatibility/uci-handshake-baseline-v0.5.7.txt), with checksums. v0.6.0 advertises 18 options including `EvalType` (Classical, NNUE). v0.5.7 advertises 15 and no `EvalType`. Every option the runner sends is advertised by the engine it is sent to. Run on Linux with OpenJDK 21.0.12.1, so this shows protocol compatibility and not native behavior.
- Both accept `--add-modules jdk.incubator.vector` on the command line.
- The runner's validation (`-ValidateOnly`), the unchanged Phase 21 validation, the Python tests and the existing release-comparison tests pass under PowerShell 7.6.6 on Linux. The runner is written to also run in Windows PowerShell 5.1; that was not tested.
- The analyzer reproduces the archived Phase 21 result: 13,118 scored games, 4,080-3,829-5,209, LLR 2.9709 against 2.97 printed, Elo 6.65 with a 95% half-width of 4.6 against 6.6 +/- 4.6.
- `python tools/release-sprt-openings.py --check` reproduces both committed opening lists.
- The Cute Chess 1.5.1 behavior in sections 5 and 13 was read from its source.

Not checked, because it would need Cute Chess or a game: that Cute Chess 1.5.1 plays both JARs without incident (the smoke test's job), the six-way concurrency speed question, and `sprt.ps1 -PrepareOnly` against the real `cutechess-cli.exe` (the native preflight's job).

## 16. Infrastructure changes in this branch

- `tools/sprt.ps1`: one new parameter, `-OpeningOrder` (`random` default, or `sequential`). With the default, the Cute Chess argument list is unchanged for every existing caller; the validation checks this.
- New: `tools/release-strength-sprt.ps1` (modes `-ValidateOnly`, `-PreflightNative`, `-SmokeNative`, `-ExecuteNative`), `tools/release-strength-sprt-common.ps1`, `tools/release-strength-sprt-validation.ps1`, `tools/release-sprt-openings.py`, `tools/release-sprt-analyze.py`, `tools/tests/test_release_sprt.py`, and the two frozen opening lists.
- Corrected, with provenance kept: the Contempt sentence in `docs/architecture/research/release-comparison-v0.5.7-v0.6.0/README.md`.
- Reused unchanged: `tools/phase21-stage6-common.ps1` (result parser and fault matcher) and, by syntax-tree extraction, the process helpers in `tools/phase21-stage6.ps1`.
- No engine, search, evaluation or UCI code is touched.

## 17. Smoke test (separately recorded)

Purpose: confirm on the production host that Cute Chess 1.5.1 plays both published JARs with the exact production options at concurrency 6, with color-reversed openings, without a time forfeit, crash or protocol failure. It is not a result and nothing from it is pooled with the production SPRT.

| Item | Value |
|---|---|
| Games | 20, which is 10 color-reversed pairs |
| Openings | `openings-smoke-v1.epd`, 10 positions, SHA-256 `51ed4dc523be6e29c28c6693ad6022478b142465b699df3397c53537cd6ca195`. They are the ranks directly after the production list in the same ranking, so the two lists share no position. Played in file order with `-repeat` |
| Everything else | The production command line: same JARs, options, time control, adjudication, concurrency 6, `-sprt` flags. Only `-games` and the opening file differ, and the runner checks the exact argument list |
| Gate | The runner refuses unless a native preflight passed on the same commit and the same script hashes, started within the last 24 hours, with a non-empty `SHA256SUMS` that lists every file in the preflight directory and still verifies |
| Confirmation | The operator types `RUN-RELEASE-SMOKE` after the idle-host check |
| Location | Its own directory `tools/results/release-strength-sprt/smoke-<UTC>-<random>/`, with the same artifacts as a production attempt |
| Not allowed | WSL, interop, or any host other than RENEGADE. The runner has no mode that starts production from the smoke test |

Cute Chess's `-sprt` flags stay on so the command line is the production one. With 20 games a boundary is not expected, but if one is crossed the match stops early and that is not a failure.

SMOKE_PASSED requires: no fault line in the log, exit code 0, `Finished match`, an empty audit list, at least one scored game and one completed color-reversed pair, and no unpaired scored games unless a game was cancelled. Anything else is SMOKE_FAILED. A pass is not an authorization: production needs its own approval and idle-host confirmation. A fail stops everything until the maintainer has read the evidence.

## 18. Execution

Run from the repository root in a native Windows PowerShell on RENEGADE, with the branch checked out and clean, and no `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, `_JAVA_OPTIONS` or `MAVEN_OPTS` set. Fetch the two published assets outside the repository:

```powershell
gh release download v0.6.0 -R coeusyk/chess-engine -p engine-uci-0.6.0.jar -D ..\release-sprt-assets
gh release download v0.5.7 -R coeusyk/chess-engine -p engine-uci-0.5.7.jar -D ..\release-sprt-assets
```

Authorized now, in this order, each a separate invocation:

```powershell
.\tools\release-strength-sprt.ps1 -ValidateOnly
.\tools\release-strength-sprt.ps1 -PreflightNative -AssetDirectory ..\release-sprt-assets
.\tools\release-strength-sprt.ps1 -SmokeNative -AssetDirectory ..\release-sprt-assets
```

`-ValidateOnly` uses synthetic fixtures only. `-PreflightNative` starts no game and leaves its own attempt directory. `-SmokeNative` refuses unless a preflight passed on this commit, then stops at a prompt and starts the first game only after `RUN-RELEASE-SMOKE` is typed.

Not authorized until the maintainer approves it explicitly and confirms RENEGADE is idle:

```powershell
.\tools\release-strength-sprt.ps1 -ExecuteNative -AssetDirectory ..\release-sprt-assets
```

That command stops at a prompt and starts the first production game only after `RUN-RELEASE-SPRT` is typed.
