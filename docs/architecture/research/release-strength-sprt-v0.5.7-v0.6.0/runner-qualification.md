# Release SPRT runner qualification

Status: addendum to the [preregistration](preregistration.md), written after the first native smoke attempt (`smoke-20261019T065359346Z-7b3787d4`, source commit `c9816ae1d9e25c4a5c0489b51b4692af5eb4671a`). The preregistration is frozen and unchanged. Nothing here alters the experiment: the JARs, the [0,20] hypotheses, alpha and beta, 5+0.05, concurrency 6, the engine options, the frozen opening lists and the 20,000-game cap are the same values, and the validation suite still checks each of them. This document only describes the runner's gates and how to qualify it before a production run. No production game has been played.

## Why the runner changed

The smoke attempt passed: 20 games, 10 color-reversed opening pairs, no fault, verified evidence. Auditing it turned up four gaps in how the runner protects the production run.

1. A relative `-AssetDirectory` failed. This happened three times (two preflight and one smoke attempt on 2026-10-12 and 2026-10-19). `Get-FileHash` resolves a relative path against the PowerShell location, but `Assert-Stage6Jar` calls `ZipFile.OpenRead`, and .NET resolves a relative path against the process directory, which a session can leave somewhere else. The error named `C:\Users\release-sprt-assets\engine-uci-0.6.0.jar`. Absolute paths always worked, which is why the smoke attempt succeeded.
2. `-ExecuteNative` did not check for a preflight at all. Only `-SmokeNative` did.
3. Nothing stopped production after a smoke attempt that had not been reviewed. The only thing a smoke attempt produced was the runner's own `SMOKE_PASSED` label, written by the same tooling that played the games.
4. The machine-wide lock had never been exercised by a test. The validation only checked that the source text contained the mutex name.

## What each mode now enforces

| Check | Preflight | Smoke | Production |
|---|---|---|---|
| Attempt record (`attempt-start.json`) written before any other check | yes | yes | yes |
| `-AssetDirectory` resolved once to a full drive path, inside the recorded attempt | yes | yes | yes |
| One attempt at a time (`Global\VexReleaseSprt`) | yes | yes | yes |
| Idle CPU mean at or below 5 percent, clean checkout, no inherited JVM options | yes | yes | yes |
| Passed preflight: same commit, same script hashes, same frozen experiment, started and completed within 24 hours, evidence verifies | | yes | yes |
| Audited smoke attempt: same commit, same script hashes, `AUDIT_APPROVED`, evidence manifest unchanged since the audit | | | yes |
| Operator confirmation phrase | | `RUN-RELEASE-SMOKE` | `RUN-RELEASE-SPRT` |

Both gates run inside the recorded attempt, after the lock and before the confirmation prompt, and before any engine or Cute Chess match starts (the wrapper's Java and Cute Chess version probes run earlier). They run once more after the confirmation and just before launch, because the prompt can stay open for a long time and the evidence could change under it. The launch digests (of `runner-parameters.json` and of the prepared argument manifest) are held in memory and in the launched child's command line, not re-read from files in the attempt directory. The child checks the parameter file against its digest, re-runs the frozen-parameter assertions on what it read, and checks the expected argument manifest, all before it invokes the match runner. The idle CPU reading must be a real number between 0 and 5 percent: `NaN`, infinity, a missing value and a string are refused, and so is an incomplete 15-sample counter. A refusal ends like any other failure: `runner.log`, `attempt-end.json`, `summary.json` (`NOT_STARTED`) and `SHA256SUMS` are written, and the message lists why the newest candidates were rejected.

### The preflight gate

A preflight directory qualifies only if all of these hold. Any failure rejects it, and the reasons are recorded.

- `SHA256SUMS` exists, is not empty, names plain files in the directory, lists each once, every hash verifies, and no file or subdirectory is left out.
- `attempt-start.json`, `attempt-end.json`, `summary.json` and `identity.json` are present and say `preflight`, `PREFLIGHT_PASSED` with no games, no recorded failure and no started match.
- The recorded commit equals the current `HEAD`, the start and end records carry this directory's own run id (so records from different attempts cannot be combined), and every script hash in `identity.json` equals the hash of the file now in `tools/`.
- The whole recorded configuration equals the current one, which covers the JAR hashes, both opening list hashes, the preregistration hash, the SPRT bounds, time control, concurrency, engine options and the game cap.
- The archived copies of both JARs, the production opening list and the preregistration still hash to the frozen values, so a regenerated manifest cannot launder a swapped file.
- Its timestamps carry an explicit time zone (a zone-less one would be read in the machine's local zone and shift the 24-hour check). It started and completed no more than 24 hours before now, and neither timestamp is more than five minutes in the future. Because a preflight ends after it starts, the start time is the binding one, which is the rule in section 17 of the preregistration.

### The audit gate

`SMOKE_PASSED` is not evidence of its own correctness. `tools/release-sprt-audit.py` is a separate program that re-derives the verdict from the archived raw files, against twelve explicit criteria, and keeps its own copy of the frozen identities (a Python test pins that copy to `release-strength-sprt-common.ps1`). It reads the runner's `summary.json` only as corroboration: a runner that disagrees blocks approval, and a runner that agrees grants nothing.

| Criterion | What it checks |
|---|---|
| `evidence-manifest` | `SHA256SUMS` is complete and every hash verifies |
| `attempt-completed` | a `smoke-*` attempt that started, ran, ended cleanly with exit code 0 and no failure |
| `frozen-identities` | archived JARs, smoke opening list, preregistration and the full SPRT configuration equal the frozen values |
| `script-identity` | a full commit id and a hash for each of the nine scripts, and the audit program and analyzer in use are the ones the smoke attempt recorded |
| `cutechess-arguments` | the Cute Chess argument list equals the preregistered smoke command line rebuilt from the frozen values; the four recorded paths share one directory named after this attempt under `tools\results\release-strength-sprt`, which the runner's transcript (`runner.log`) and `runner-parameters.json` also name |
| `host-and-tools` | RENEGADE, 16 logical processors, full affinity, Balanced plan, JDK 21.0.10, Cute Chess 1.5.1, idle CPU mean a real number between 0 and 5 percent (`NaN` is refused) |
| `games-complete` | games 1 to 20 each started and finished once, every start line declaring a total of 20, 20 complete PGN records, none cancelled, `Finished match` |
| `opening-pairs` | exactly the ten smoke openings, each played once as White and once as Black by NEW |
| `results-reconcile` | every game's players and result agree between log and PGN, W/L/D agrees with the last score line, recomputed LLR agrees with the logged one |
| `no-faults` | no time forfeit, crash, disconnect, illegal move, protocol or option fault in the log, console streams or PGN terminations |
| `smoke-isolation` | smoke games used only the smoke list, none of whose positions is in the production corpus, and the production list was not archived or passed to Cute Chess |
| `runner-corroboration` | the runner's own summary agrees and the idle-host confirmation is recorded |

The audit writes `audit-<UTC>-<random>/` next to the attempts, holding `smoke-audit.json` and its own `SHA256SUMS`. It never writes inside the smoke directory and refuses to. A rejected audit still leaves a record. The record names the smoke run id, the SHA-256 of the smoke `SHA256SUMS` (the exact evidence manifest it approved), the runner commit, the nine script hashes, and the SHA-256 of the audit program and of the analyzer it used.

At production time the runner does not accept the record on its word alone. It requires `AUDIT_APPROVED` and all twelve criteria present exactly once with `Passed` equal to a real boolean `true`. It requires the audit program hash and the analyzer hash in the record to equal the files in this checkout, the commit and script hashes in the record to equal the current ones, the named smoke directory to exist with the same manifest hash and still verify file by file, the smoke attempt's own identity, configuration and archived inputs to match, and the audit to be dated after the smoke ended. The audit record's own `SHA256SUMS` must verify too.

This does not make forgery impossible. Someone with write access to `tools/results` could fabricate both directories. The gate protects against mistakes and against evidence changing after the fact, and it makes a bad run hard to start by accident. It is not a security boundary.

## Qualification procedure on RENEGADE

Run from the repository root in native Windows PowerShell 7, on the commit that will be used for production, with a clean checkout, nothing else running and no `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, `_JAVA_OPTIONS` or `MAVEN_OPTS` set. The asset directory may now be relative or absolute.

```powershell
.\tools\release-strength-sprt.ps1 -ValidateOnly
.\tools\release-strength-sprt.ps1 -PreflightNative -AssetDirectory ..\release-sprt-assets
.\tools\release-strength-sprt.ps1 -SmokeNative -AssetDirectory ..\release-sprt-assets
python tools\release-sprt-audit.py tools\results\release-strength-sprt\<the smoke-* directory>
```

Run the audit from the same native Windows checkout that ran the smoke attempt: it compares its own hash and the analyzer's with the ones the attempt recorded, and those are hashes of the working-tree files, so a copy with different line endings (a WSL checkout, for example) is rejected. The audit must print `AUDIT_APPROVED`. If it prints `AUDIT_REJECTED`, read the failed criterion, do not edit anything under `tools/results`, and decide with the maintainer whether to investigate or run a fresh smoke attempt. Only after that, and only with explicit approval and an idle host:

```powershell
.\tools\release-strength-sprt.ps1 -ExecuteNative -AssetDirectory ..\release-sprt-assets
```

The preflight must have started within 24 hours of the production invocation. If the audit or the smoke is older, that is fine as long as nothing under `tools/` and no commit changed, because both are bound to the exact scripts and commit. Any edit to a hashed script (the nine listed in `identity.json`), or any change of commit, voids all three records and the sequence starts again. The scripts are hashed as they sit in the working tree, so a line-ending change counts as an edit.

## Existing evidence and migration

Nothing under `tools/results/release-strength-sprt/` is altered by this change, and nothing is reinterpreted. The existing preflight `2b0cb2fe` and smoke `7b3787d4` were produced by the runner at `c9816ae` and cannot authorize production under the hardened runner, for a plain reason: their recorded script hashes are those of the old scripts, and they do not include the audit program, which did not exist. The gate compares them to the current scripts and refuses.

They remain valid history. Running the new audit program read-only against the smoke attempt `7b3787d4` (output sent to a scratch directory, evidence checksums verified before and after) gives 11 of 12 criteria passed, including the full argument comparison, the 20 games, the 10 pairs and the results reconciliation. The twelfth, `script-identity`, fails only because that attempt never recorded the audit program's hash. This confirms the audit agrees with the earlier manual review, and it confirms the new gate does what it should with old evidence.

The older failed attempts (the 8 directories with four files and no `attempt-start.json`, `attempt-end.json` or `SHA256SUMS`) predate the first-write attempt record. They stay as they are and are listed in `PriorAttempts`. They can never qualify.

A fresh native preflight, smoke attempt and audit are needed on the commit that carries this change before the production SPRT can be authorized.

## What was verified, and what was not

- The validation suite (`-ValidateOnly`) went from 111 to 257 checks. New ones cover path resolution when the PowerShell location and the .NET directory differ, the machine-wide lock across real processes, and a matrix of refusals for both gates (stale, future, wrong commit, changed scripts, changed configuration, swapped archived inputs with a regenerated manifest, corrupted files, tampering after the audit, wrong audit program, missing or failed criteria, a run id that tries to leave the results directory).
- I checked that those tests can fail by disabling five individual checks in a scratch copy (age limit, commit comparison, criterion result, audit manifest hash, script hash comparison). Each made the suite fail.
- `tools/tests/test_release_sprt_audit.py` has 49 tests, using synthetic attempts, covering every criterion, plus a golden copy of the preregistered smoke command line taken from the native smoke attempt's `arguments.json`. A cross-language check had the Python tool write a record that the PowerShell gate then accepted, and changing one smoke file afterwards closed the gate with a named reason.
- The refusal path was run through the real wrapper on a scratch copy with no results directory, using `-SmokeNative`, which shares the preflight gate with production: asset directory missing, host not idle, and no preflight each left a complete attempt directory whose `SHA256SUMS` verified. No process other than the probes the wrapper makes before its gates was started, and no game or engine was launched. The audit gate runs only under `-ExecuteNative`; it is covered by the unit tests and a source-order check, not by running that mode.
- On the runtime used here (.NET 10.0.12), a mutex held by a process that is then killed is handed to the next waiter without an `AbandonedMutexException`. A dead attempt does not block the next one, and the test asserts that. The take-over path that reports `LockRecoveredFromAbandoned` is tested by a thread that exits holding the lock, where the runtime does signal it. Do not read `LockRecoveredFromAbandoned = false` as proof that the previous attempt ended cleanly.
- Windows PowerShell 5.1 was not tested. The new path code avoids the .NET Core-only overloads, but that is untested.
