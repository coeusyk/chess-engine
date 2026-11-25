# The #296 native SPRT ended early: diagnosis

2026-11-25. The native non-inferiority SPRT for #296 (control `e97f5c4`, candidate `9445a428`) stopped at game 10,758 of a 30,000 cap with no verdict, while the launcher and the Windows task both reported success. This note records why it stopped, which games and statistics can be trusted, and whether it can be continued under the frozen protocol. Nothing was resumed or restarted, and no artifact was changed. The run archive is `tools/results/vex-followup/20261124T1102Z/` on the native Windows checkout (not in git), and every path below is relative to it.

## What happened

The task `VexFollowup296-e97f5c4` ran `native-followup.ps1`, which prepared the #260 arms without launching them and then started the #296 match (`singular-296/started.txt`, 2026-11-24 14:13:46 UTC). The match played normally until 21:15:08 UTC, which is 02:45:08 in the host's time zone (IST, UTC+5:30), then ended within about two seconds.

The Windows System log shows why:

- 02:45:08, User32 event 1074: `StartMenuExperienceHost.exe` initiated a power off of the computer on behalf of the logged-in user, reason "Other (Unplanned)", shutdown type power off.
- 02:45:09.351 to 02:45:09.353: the six games then in flight were abandoned within two milliseconds of each other. All twelve engine processes died at once, so this was not an engine failure. Cute Chess logged `Writing to engine OLD(15) failed`, `Could not initialize player OLD:` and `Finished match`.
- 02:45:16 to 02:45:18: user log-off, the event log service stopping, the kernel power manager's shutdown transition, shutdown.
- The machine came back at 08:43:52 IST (03:13:52 UTC), and the Kernel-Boot event reports the last shutdown as successful.

So the cause is a clean, user-initiated power off of the host during the run, not a crash, an out-of-memory kill or a Windows update. Memory and CPU were healthy to the end (`singular-296/pressure.csv`: 43 percent CPU and about 8 GB available in the last samples).

## Exit status

Every success signal was misleading. The launcher wrote `finished.txt` at 21:15:10 UTC and `SPRT complete`, `native.complete` contains `0`, and the scheduled task's last result is `0`. The reason is in `native-followup.ps1`: after `& $runner ...` returns it writes `finished.txt` unconditionally, and nothing looks at Cute Chess's exit code, at games abandoned or without a result, or at the games played against the cap. The shutdown killed the engines but let the launcher script reach its last lines. A zero exit status here means "the script ended", not "the SPRT finished".

## Affected games

`singular-296/match.log` and `match.pgn` agree. Game numbers 1 to 10,758 finished normally and are contiguous. The last eleven records are not normal:

- Games 10,759 to 10,764 (six games): finished with the result "disconnects" (adjudicated by Cute Chess as a win for the side that did not disconnect). The PGN marks them `abandoned`.
- Games 10,765 to 10,768 and 10,770: logged `No result`. The PGN has four `unterminated` records (no result and no end time).

Cute Chess counted the six disconnect results in its running tally, so its final line is contaminated: `NEW vs OLD: 3537 - 3620 - 3607 [0.496] 10764`.

## What can be trusted

Taking only the 10,758 normal games gives, for NEW against OLD, 3,533 wins, 3,607 draws and 3,618 losses, so Cute Chess's tally carried four extra NEW wins and two extra NEW losses from the abandoned games. The openings are paired (`-repeat`), and the 10,758 games form 5,379 complete pairs: grouping by opening position gives exactly two games per position, one with each colour, and no orphan. Pair scores were 0 for 593 pairs, 0.25 for 1,165, 0.5 for 1,892, 0.75 for 1,192 and 1 for 537.

| Over | Score | Std error | Elo, 95% interval |
|---|---|---|---|
| 5,379 complete pairs | 49.605% | 0.385 pts | -2.75 [-8.00, +2.50] |

The approximate log-likelihood ratio on the clean pairs is -0.17 (normal approximation, Elo0 -5, Elo1 0). The same method on Cute Chess's own 10,764 games gives -0.12, which is the value Cute Chess logged, so the approximation is close. The logged LLR path (1,076 samples, one every ten games) stayed between -1.12 and +0.575 against bounds of plus or minus 2.94, so the run never came close to stopping.

The 5,379 pairs are a valid interim sample. The interruption was a power off that cannot depend on game results, so dropping the abandoned games does not bias the rest. They are not a verdict: no boundary was reached, and the point estimate (about -2.75 Elo, interval including 0 and the -5 margin) says only that the test was still undecided.

## Can it be continued under the protocol?

Not as it stands. The frozen invocation has three properties that block a protocol-correct continuation:

1. `-openings ... order=random plies=4` with no `-srand`, so the opening sequence was drawn from an unseeded generator and cannot be replayed.
2. No tournament state file (`-tournamentfile`) was passed, so Cute Chess has nothing to resume from, and a new invocation starts its SPRT at LLR 0. It would stop on its own bounds and ignore the 5,379 pairs already played.
3. The protocol requires the frozen manifest to be replayed exactly, and a second run is a different run.

What would be valid, each needing the owner's decision and a written amendment to the protocol:

- **Pooled continuation.** Same JARs (both hash-checked and still on disk: `singular-296/base.jar` 8375f2d1..., `cand.jar` f973c74c...), same settings, but run in fixed blocks without Cute Chess's internal SPRT, with the pooled LLR computed offline from the pair data at pre-registered looks. The clean 5,379 pairs would count, the abandoned games would not.
- **Fresh restart** with the same manifest, discarding this run as an aborted attempt. Costs the full 10 to 20 hours again.
- **Leave it inconclusive** and decide #296 on the speed and correctness evidence alone.

## Why the #260 comparison did not launch

There is no launcher or watcher for #260 to fail. `native-followup.ps1` calls `Prepare 'history-260' $manifest.history $false`: the last argument is the launch flag, and `false` returns before any preflight or match. `provenance.json` in `history-260/` records `LaunchAuthorized: false`. After #296 the script writes `native.complete` and exits, with nothing queued behind it.

What exists and what does not:

- The prepared #260 set is intact. `sha256sum -c SHA256SUMS.prepared` passes for `base.jar`, `cand.jar`, `prepared-arguments.json`, `protocol.md`, `provenance.json`, `runner-parameters.json` and both build logs. The opening book hashes to `2011193b...` as the script expects. There is no `match.log` or PGN.
- The WSL-side `retry-serial.py` and `observe-native.py` processes named in the handoff are not running. They lived in a scratch directory under `/tmp` that the reboot cleared.
- The only scheduled task is `VexFollowup296-e97f5c4`: on demand only (no trigger), state Ready, last result 0. It cannot start by itself. If someone ran it, it would redo the benchmark comparison and then fail when `Prepare` tried to create a `singular-296` directory that exists, before launching anything.
- No Cute Chess or Java process was running on the host, and there is no cron entry or systemd timer for any of this.

So #260 is prepared and verified, but not launched, and nothing can launch it unexpectedly. Its blockers are the owner's decisions on the SPRT hypothesis, the cap and the frozen manifest, which the preparation comment already says are not approved.

## What to change in the launcher before the next native SPRT

These are observations, not changes made here. The runner should check Cute Chess's exit code and refuse to write a success marker when any game ended abandoned, disconnected or without a result before the cap or a bound. It should also pass `-srand` and a tournament file, so an interruption can be continued without breaking the paired design.
