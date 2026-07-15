# E-4: NNUE-mode gauntlet — run record

Issue #204 (Phase E, Track B). Uses `tools/nnue-gauntlet.ps1` (new — `match.ps1`
itself is unmodified per the issue's explicit non-scope; it has no way to pass
per-engine UCI options, so this is the "or equivalent" the issue's Scope permits).

## What's verified from this (WSL) session

- **Jar build**: `mvn -pl engine-core,engine-uci -am package -DskipTests` →
  `engine-uci/target/engine-uci-0.5.8-SNAPSHOT.jar`. No source change — `EvalType`/
  `EvalFile` are plain runtime UCI `setoption`s
  (`UciApplication.java:510-523`), verified directly against source rather than
  assumed, per the issue's own instruction not to guess the flag names.
- **NNUE-mode load, confirmed via `info string`** (issue's own AC #1):
  ```
  setoption name EvalType value NNUE
  setoption name EvalFile value <path-to-dfffd3da-...-nnue>
  ...
  info string NNUE network loaded: dfffd3da-7f8f-4fc9-92dc-b3873c97fb21
  ```
  UUID matches `nets/dfffd3da-7f8f-4fc9-92dc-b3873c97fb21.json` exactly. Search to
  depth 6 completed normally (`bestmove g1f3 ponder e7e6`), confirming the NNUE
  path is fully wired into the live search, not just load-then-fallback.
- **Perft + mirror-symmetry-relevant regression**: `mvn -pl engine-core test` →
  265 tests, 0 failures, BUILD SUCCESS (confirms the jar's underlying code is
  unaffected — this issue changes no `Board`/`Evaluator`/`Searcher` code).

## What's NOT run from this session, and why

**The actual gauntlet match has not been played.** `tools/match.ps1` (and this
issue's equivalent, `tools/nnue-gauntlet.ps1`) require PowerShell + `cutechess-cli`.
Neither is available natively in this WSL2 session — `cutechess-cli.exe` exists
only under the Windows filesystem mount (`/mnt/c/Tools/cutechess-1.4.0-win64/`),
unreachable as a Linux executable. This is the same category of constraint
CLAUDE.md §5 already establishes for SPRT ("SPRTs are run on Windows... not WSL...
output the exact command and stop, never simulate") — applied here to a
gauntlet run for the identical practical reason. **No match result is fabricated
or simulated below.**

## Exact command to run on native Windows

The candidate `.nnue` and the built jar are both local, gitignored artifacts that
only exist in this WSL session's filesystem (`trainer/outputs/nets/` is
intentionally never committed — architecture doc §9). Reference them directly from
native Windows via the WSL UNC bridge (works without copying anything, since WSL2
exposes its filesystem at `\\wsl.localhost\<distro>`):

```powershell
.\tools\nnue-gauntlet.ps1 `
  -Engine   '\\wsl.localhost\Ubuntu\home\coeusyk\projects\chess-engine\engine-uci\target\engine-uci-0.5.8-SNAPSHOT.jar' `
  -NnueFile '\\wsl.localhost\Ubuntu\home\coeusyk\projects\chess-engine\trainer\outputs\nets\dfffd3da-7f8f-4fc9-92dc-b3873c97fb21.nnue' `
  -Games 100 -TC '10+0.1'
```

If UNC access is slow or restricted, an equivalent fallback: `git pull` this
branch on the native Windows checkout, run
`mvn -pl engine-core,engine-uci -am package -DskipTests` there to build the same
jar, and regenerate the identical net there via
`trainer/scripts/train_candidate_net.py` using the exact command recorded in
`trainer/configs/train-e3-real.md` (E-3's reproducibility check already confirmed
this training run is bit-identical given the same seed/config/data).

## Completion status

Everything buildable/verifiable from this session is done and green. The gauntlet
match itself is the one remaining step, and it requires running the command above
on native Windows — this issue should not be closed with "Closes #204" claiming a
match result until that command has actually been run and its PGN/result recorded
here.
