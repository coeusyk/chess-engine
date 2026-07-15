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

**First attempt used the `\\wsl.localhost\...` UNC bridge directly and failed**:
`Resolve-Path` returns a provider-qualified string
(`Microsoft.PowerShell.Core.FileSystem::\\wsl.localhost\...`) for UNC paths not
backed by a mapped drive letter, which `java -jar` cannot open — every engine
process crashed on launch (`Terminating process of engine ...` for all four,
zero games played). Fixed in the script (`Convert-Path`, which returns the plain
path both `java` and `cutechess-cli` expect), **and** sidestepped entirely by
copying the two artifacts onto the Windows filesystem directly, so no
UNC/provider-path resolution is needed at all:

```
tools/gauntlet-artifacts/engine-uci-0.5.8-SNAPSHOT.jar
tools/gauntlet-artifacts/dfffd3da-7f8f-4fc9-92dc-b3873c97fb21.nnue
```

Run:

```powershell
.\tools\nnue-gauntlet.ps1 `
  -Engine   '.\tools\gauntlet-artifacts\engine-uci-0.5.8-SNAPSHOT.jar' `
  -NnueFile '.\tools\gauntlet-artifacts\dfffd3da-7f8f-4fc9-92dc-b3873c97fb21.nnue' `
  -Games 100 -TC '10+0.1'
```

`tools/gauntlet-artifacts/` is not committed (binary build/training output,
matching the same convention as `.nnue` files generally) — it's a one-time local
copy for running this specific gauntlet. If regenerating from scratch is ever
preferred instead: build the jar with
`mvn -pl engine-core,engine-uci -am package -DskipTests`, and reproduce the exact
net via `trainer/scripts/train_candidate_net.py` using the command recorded in
`trainer/configs/train-e3-real.md` (E-3's reproducibility check already confirmed
this training run is bit-identical given the same seed/config/data) — though that
also needs the real Stage 1+2 datasets, themselves gitignored local artifacts.

## Gauntlet result (run on native Windows, 2026-07-15)

100 games, TC=10+0.1, `-resign movecount=5 score=600 -draw movenumber=40 movecount=8 score=10`:

```
Score of Vex-NNUE vs Vex-Classical: 0 - 95 - 5  [0.025] 100
Vex-NNUE playing White: 0 - 48 - 2  [0.020] 50
Vex-NNUE playing Black: 0 - 47 - 3  [0.030] 50
Elo difference: -636.4 +/- 224.0, LOS: 0.0 %, DrawRatio: 5.0 %
```

**0 wins, 95 losses, 5 draws for Vex-NNUE.** Full console log:
`tools/results/gauntlet_nnue_20260715_192136.log`; PGN:
`tools/results/gauntlet_nnue_20260715_192136.pgn` (both local-only — see
"Storage convention" below).

**This result is expected, not a defect, and not this issue's concern to fix**
(issue #204's own Non-Scope: "No promotion decision — this issue produces a
gauntlet result, not a promote/reject verdict"). E-3's candidate net trained for
only 2000 steps against ~36,000 real positions and itself reported a 735.6cp mean
absolute difference against classical eval (`trainer/configs/train-e3-real.md`) —
a network at this stage of undertraining losing decisively to a mature, many-times-tuned
classical evaluator is exactly what Track A's own framing predicts ("Track A
proves the pipeline can produce a real network — it does not claim... that the
network is already strong"). Judging this result, or deciding whether/how to
iterate on training scale, is Track B's (E-5's release report) or a future
retraining cycle's job, not E-4's.

**One process-bookkeeping fix during this run**: `nnue-gauntlet.ps1` originally
wrote only a `.pgn`, unlike `sprt.ps1`'s established convention of pairing every
match with a `.log` (via `Tee-Object`). Fixed to match — this issue's gauntlet
run now writes both, consistent with the rest of `tools/`.

**Code-review note on evidence strength (non-blocking)**: AC #1's `info string
NNUE network loaded: <uuid>` confirmation came from a separate, earlier manual
single-position sanity check, not from a `-debug` cutechess-cli run — the actual
100-game match log has no `info string` lines (cutechess-cli wasn't run with
`-debug`). Verified as sufficient evidence anyway by reading the raw match log
directly: all 100 games ended via real termination reasons (mates, 3-fold
repetition, adjudication) with zero crashes/disconnects/illegal-move forfeits,
and a clean 48/47 White/Black split — a silent both-sides-fell-back-to-Classical
bug would produce a near-50/50 self-play result, not a clean 636-Elo blowout.
If a future gauntlet result ever lands close to 50/50 (where "genuinely equal"
vs. "silently both Classical" would be ambiguous), re-run with `-debug` to
confirm directly rather than inferring.

## Storage convention

**Correction to this issue's own "Completion Evidence" wording** ("gauntlet
report, committed to `tools/results/`, matching this project's existing
convention"): verified directly against `.gitignore` and `git ls-files` that no
`tools/results/*.pgn` or `*.log` file has ever actually been committed in this
repo's history — every historical Phase 13/14 SPRT log/PGN present in the working
tree is gitignored (`tools/results/*.pgn`, blanket `*.log`), and only
`tools/results/.gitkeep` is tracked. The actual established convention is: local
artifacts + a committed `.md` summary recording the result (matching E-2's own
"Storage convention" precedent for its gitignored datasets). This document is
that summary for E-4.

## Completion status

Gauntlet match run, result recorded above. Network UUID confirmed matching
E-3's manifest. Perft/mirror-symmetry regression green. Issue #204's acceptance
criteria are met.
