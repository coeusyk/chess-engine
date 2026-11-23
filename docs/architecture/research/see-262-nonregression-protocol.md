# #262 SEE correction: proposed native non-regression SPRT (awaiting approval)

Status: proposal. Nothing here has been approved, and no game has been played. Preparation provides no strength evidence.

## What is being tested

PR #283 corrects the static exchange evaluator (pin-aware recaptures, the h1 signed-shift bug, wrong-side recursion in `captureGainFor`). The correctness evidence is already in: the oracle test, the three reproduced defects, CI, and the pruning-decision count (3,713,211 capture visits, 3,239 pruning flips, 0.087%).

The open question is narrow: did the correction cost material strength? That is a non-regression question, not an improvement question. The generic `[0,50]` policy in `docs/sprt-guidelines.md` is an improvement test and is not suitable. The `[0,10]` default in `tools/sprt.ps1` is not approved for this change either, and the Phase 21 Stage 6 exception covers Phase 21 Stage 6 only. This document does not reuse either.

## Proposed statistical protocol

| Item | Value |
|---|---|
| H0 | candidate minus baseline Elo = -5 (a material regression) |
| H1 | candidate minus baseline Elo = 0 (no regression) |
| alpha, beta | 0.05 each, BonferroniM = 1 (one hypothesis) |
| LLR bounds | -2.944 and +2.944 |
| Minimum games | none. `MinGames` is not a stop barrier in this runner, and no early-stop minimum is claimed. |
| Cap | 30,000 scored games |
| Time control and parallelism | 5+0.05, concurrency 6, 1 thread per engine, same as the Phase 21 Stage 6 conditions |
| Pairing | `-repeat`: every opening is played twice with colours swapped |

The `[-5,0]` shape is the one `phase21-direction-and-preregistration.md` already named for a correctness-motivated change. The -5 margin is a choice, not a derivation, and it needs your sign-off.

**Interpretation, fixed before the run:**

- H1 accepted: the data favour "no worse than -5 Elo" over "5 Elo worse". The candidate is non-inferior at this margin. This says nothing about improvement and does not prove an exact zero difference.
- H0 accepted: the data favour a regression of about 5 Elo over none. Investigate before any merge decision. It is evidence about strength under these conditions, not a verdict on the correctness fix, which stands on its own tests.
- Cap reached without a boundary: INCONCLUSIVE. This neither shows a regression nor shows none. It must not be reported as "no regression found". Do not rerun or change bounds after the fact.
- A setup fault, crash, forfeit, illegal move or interruption is an invalid run, not H0 and not H1.
- The alpha and beta values are nominal rates under the Cute Chess W/L/D model, not posterior probabilities.

**Why this margin and cap** (Monte Carlo of the trinomial LLR walk, assumed 40% draws taken from the Phase 21 Stage 6 result of 39.7%, 400 simulated runs per row; an illustration, not a guarantee):

| True Elo | Mean games | H1 accepted | H0 accepted | Hit 30,000 cap |
|---|---|---|---|---|
| -5 | 14,331 | 6% | 87% | 7% |
| -2.5 | 19,037 | 36% | 36% | 28% |
| 0 | 14,540 | 86% | 2% | 12% |
| +3 | 7,660 | 100% | 0% | 0% |

At a 20,000 cap the same test at true 0 reaches H1 only 74% of the time, which is why the cap is 30,000. A tighter margin such as `[-3,0]` mostly ends at the cap and is not practical. Expect roughly the size of the Phase 21 Stage 6 run (13,118 games) in the typical case, and about 2.3 times that at the cap.

## Native requirements

`CLAUDE.md` section 6 and the repo's own release runner require native Windows. The Claude Code session that prepared this is Linux under WSL2, so no game, Java build or cutechess process was launched from it. Run everything below from a native Windows PowerShell on a Windows drive, not from WSL and not through interop.

- Same toolchain as the Phase 21 Stage 6 registration: Azul Zulu 21 (21.0.10+7), Maven 3.9.16, `cutechess-cli` 1.5.1. Set `$env:CUTECHESS` to the native executable if it is not on PATH, and `$env:JAVA` to the Zulu home so both engines and the build use the same Java.
- `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, `_JAVA_OPTIONS` and `MAVEN_OPTS` must be unset.
- Idle machine, balanced power plan, no competing CPU load.
- A clean native checkout of the PR head (it carries the fail-closed opening-book guard in `tools/sprt.ps1`).

## Binary identities

| Role | Commit | Notes |
|---|---|---|
| Baseline (OLD) | `52fee7c` | exact pre-SEE develop, parent of the SEE commit |
| Candidate (NEW) | `25a3889` | `b26569f` (SEE) plus a commit that only adds `tools/SeeDecisionDiff.java` and `dev-entries/phase-21.md`; `git diff b26569f 25a3889` touches no engine source |

Both are built with the same command and toolchain from `git archive` snapshots of the exact commits (no worktrees, no local modifications). Both snapshots build `engine-uci-0.6.1-SNAPSHOT.jar`.

## Opening book

`tools/noob_3moves.epd`, tracked, unmodified in the checkout.

- SHA-256 `2011193b4854e9a8cfdc05312ca2dbaffa6ceae3abbdee20e2ead2a18a603347`, identical to the hash frozen for Phase 21 Stage 6.
- 150,932 lines, 0 blank, 0 CRLF, 150,932 unique FENs (150,907 unique piece placements), every line six fields, all white to move.
- All 150,932 parse in the engine's own `Board` and every one has at least one legal move. 640 have the side to move in check, which is legal. This was checked with the engine's board code, so it shows the lines are loadable and playable, not that they are independently validated by a second implementation.
- Fail closed: `tools/sprt.ps1` now throws if `-OpeningsFile` is supplied and is empty or not a file. Before this change a missing explicit book silently produced startpos games. The existing behaviour for callers that pass no `-OpeningsFile` (auto-detect, otherwise none) is unchanged, so this command always passes it explicitly.
- `-repeat` plays each opening twice with colours swapped. `order=random` is the script's default and it exposes no seed, so the actual opening order is whatever lands in the PGN and must be kept.

## Runtime settings (identical for both engines)

`--add-modules jdk.incubator.vector -jar <jar>`, and UCI options `Threads=1 Hash=16 PawnHashSize=1 EvalType=Classical MultiPV=1 Contempt=0 OwnBook=false SyzygyOnline=false SyzygyPath=` on both arms, the same set the Phase 21 Stage 6 registration froze. Resign `movecount=5 score=400` and draw `movenumber=40 movecount=8 score=10` come from `tools/sprt.ps1` unchanged.

## Commands (native PowerShell, from the repository root)

These were not executed by the session that wrote them. Run step 3 first as an inexpensive check.

**1. Build both jars from the exact commits and write provenance.**

```powershell
$ErrorActionPreference = 'Stop'
if ($env:WSL_INTEROP -or $env:WSL_DISTRO_NAME) { throw 'Run from native Windows PowerShell.' }
foreach ($v in 'JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','MAVEN_OPTS') { if ([Environment]::GetEnvironmentVariable($v)) { throw "Unset $v" } }
$run = Join-Path (Get-Location).ProviderPath ('tools\results\see-262\' + (Get-Date -Format 'yyyyMMddTHHmmss'))
New-Item -ItemType Directory -Path $run | Out-Null
$arms = [ordered]@{ base = '52fee7c'; cand = '25a3889' }
$prov = [ordered]@{ Java = (cmd /c 'java -version 2>&1' | Select-Object -First 1); Maven = (mvn -v | Select-Object -First 1); Cutechess = $env:CUTECHESS; Arms = [ordered]@{} }
foreach ($name in $arms.Keys) {
    $full = (git rev-parse "$($arms[$name])^{commit}").Trim()
    $src = "$run\src-$name"; New-Item -ItemType Directory -Path $src | Out-Null
    git archive --format=tar -o "$run\$name.tar" $full
    tar -xf "$run\$name.tar" -C $src
    Push-Location $src; mvn -B -pl engine-core,engine-uci -am package -DskipTests; if ($LASTEXITCODE) { throw "build $name failed" }; Pop-Location
    Copy-Item "$src\engine-uci\target\engine-uci-0.6.1-SNAPSHOT.jar" "$run\$name.jar"
    $prov.Arms[$name] = [ordered]@{ Commit = $full; JarSha256 = (Get-FileHash "$run\$name.jar" -Algorithm SHA256).Hash.ToLower() }
}
# The source snapshots and tars are about 400 MB; the jars and provenance are what the run needs.
Remove-Item "$run\src-*", "$run\*.tar" -Recurse -Force
$prov.OpeningSha256 = (Get-FileHash tools\noob_3moves.epd -Algorithm SHA256).Hash.ToLower()
if ($prov.OpeningSha256 -ne '2011193b4854e9a8cfdc05312ca2dbaffa6ceae3abbdee20e2ead2a18a603347') { throw 'Opening book hash mismatch' }
$prov | ConvertTo-Json -Depth 5 | Set-Content "$run\provenance.json" -Encoding UTF8
```

**2. Freeze the arguments without launching games.**

```powershell
$opts = 'Hash=16','PawnHashSize=1','EvalType=Classical','MultiPV=1','Contempt=0','OwnBook=false','SyzygyOnline=false','SyzygyPath='
.\tools\sprt.ps1 -New "$run\cand.jar" -Old "$run\base.jar" -Tag see-262-nonreg -Elo0 -5 -Elo1 0 -Alpha 0.05 -Beta 0.05 -BonferroniM 1 -MinGames 0 -MaxGames 30000 -TC '5+0.05' -Concurrency 6 -EngineThreads 1 -OpeningsFile tools\noob_3moves.epd -OpeningOrder random -NewOptions $opts -OldOptions $opts -LogPath "$run\match.log" -PgnPath "$run\match.pgn" -PrepareOnly -ArgumentManifestPath "$run\prepared-arguments.json"
Get-ChildItem $run -File | Get-FileHash -Algorithm SHA256 | ForEach-Object { "$($_.Hash.ToLower())  $(Split-Path $_.Path -Leaf)" } | Set-Content "$run\SHA256SUMS" -Encoding ASCII
Get-ChildItem $run -File | ForEach-Object { $_.IsReadOnly = $true }
```

Read `prepared-arguments.json` before going on: both engines should show `option.Threads=1` and the same option list, the `-openings file=...noob_3moves.epd format=epd order=random plies=4` group must be present, and `-repeat` and `-sprt elo0=-5 elo1=0 alpha=0.05 beta=0.05` must match the table above.

**3. Optional smoke test (separate outputs, not a result).** Same binaries and options, tiny limits, its own files. It checks that the plumbing works and that colours are swapped. It must not be counted.

```powershell
.\tools\sprt.ps1 -New "$run\cand.jar" -Old "$run\base.jar" -Tag see-262-smoke -Elo0 -5 -Elo1 0 -MaxGames 8 -TC '1+0.01' -Concurrency 1 -EngineThreads 1 -OpeningsFile tools\noob_3moves.epd -NewOptions $opts -OldOptions $opts -LogPath "$run\smoke.log" -PgnPath "$run\smoke.pgn"
Select-String -Path "$run\smoke.pgn" -Pattern '^\[FEN ' | Group-Object Line | ForEach-Object Count
```

Every count printed should be 2 (each opening played with both colours).

**4. The full run. Do not start without approval.**

```powershell
.\tools\sprt.ps1 -New "$run\cand.jar" -Old "$run\base.jar" -Tag see-262-nonreg -Elo0 -5 -Elo1 0 -Alpha 0.05 -Beta 0.05 -BonferroniM 1 -MinGames 0 -MaxGames 30000 -TC '5+0.05' -Concurrency 6 -EngineThreads 1 -OpeningsFile tools\noob_3moves.epd -OpeningOrder random -NewOptions $opts -OldOptions $opts -LogPath "$run\match.log" -PgnPath "$run\match.pgn" -ExpectedArgumentManifestPath "$run\prepared-arguments.json"
```

`-ExpectedArgumentManifestPath` makes the script refuse to launch if its actual Cute Chess arguments differ from the frozen manifest.

## Lessons from the first run

- The first run used relative `-LogPath` and `-PgnPath`. `sprt.ps1` resolved them against the .NET process directory (the user profile), so the log and PGN landed outside the run folder. `sprt.ps1` now resolves them against the PowerShell location, and `$run` above is an absolute path as well. If the files ever end up elsewhere, copy them into the run folder after the run, verify the SHA-256, and write a new `SHA256SUMS.final` instead of editing the frozen `SHA256SUMS`.
- Step 1 now deletes the extracted source trees and tar files once the jars are built.

## What this tooling does not give you

- `tools/sprt.ps1` is the generic runner, not a wrapper like `phase21-stage6.ps1`. It has no operator confirmation phrase, no fault-watching child-process supervisor, and no automatic pair audit. Check the log for crashes, forfeits, timeouts and illegal moves, and the PGN for paired openings, by hand or with the report from `tools/count_pgn_stats.ps1`.
- `-recover` is still on, so a recovered engine crash counts as fault evidence and invalidates the run.
- The hardware, Java and cutechess identities in the Phase 21 registration were not re-verified by the preparing session.
