# Phase 2 Issue #25 GUI Validation Checklist

This runbook closes the GUI-specific acceptance points for issue #25.

Scope covered by this document:

- Engine loads in Cute Chess or Arena without errors.
- Engine plays a full legal game under standard time control.
- No illegal moves are emitted in the tested game.
- bestmove responses remain timely under normal controls.

Related automated coverage already in repository:

- UCI handshake and command loop integration tests:
  - `engine-uci/src/test/java/coeusyk/game/chess/uci/UciApplicationIntegrationTest.java`

## 1. Prerequisites

- Build binaries from repo root:

```powershell
.\mvnw.cmd clean test
```

- Confirm UCI jar exists:

```powershell
Get-ChildItem .\engine-uci\target\*.jar
```

- Recommended GUI:
  - Cute Chess (preferred) or Arena.

## 2. Cute Chess Validation Procedure

1. Open Cute Chess.
2. Add engine A:
   - Command: `java`
   - Arguments: `-cp <absolute-path-to-engine-uci-target-classes-or-jar> coeusyk.game.chess.uci.UciApplication`
3. Add engine B:
   - Use any stable reference UCI engine (for example, another local engine).
4. Confirm initialization:
   - UCI pane should show normal startup handshake and no load errors.
5. Start one game with standard control:
   - Suggested: `5+0` or `3+2`.
6. Let game finish naturally (checkmate, stalemate, repetition, or resign/timeout by GUI rules).
7. Save outputs:
   - Export PGN.
   - Save engine log / debug console output.

## 3. Arena Validation Procedure (Alternative)

1. Open Arena.
2. Install UCI engine using the same Java command and class entry point.
3. Start one full game with standard control (`5+0` recommended).
4. Save PGN and engine output log.

## 4. Pass/Fail Checklist

- [ ] Engine loads in GUI without crash, hang, or protocol error.
- [ ] At least one full game completes successfully.
- [ ] PGN contains only legal moves (no GUI illegal move termination).
- [ ] No UCI command-loop crash observed during game.
- [ ] bestmove responses appear on every move within time control expectations.

## 5. Where Results Appear

Automated test results are written to Maven Surefire reports:

- `engine-core/target/surefire-reports/`
   - tactical benchmark details in `coeusyk.game.chess.core.search.TacticalSuiteTest.txt`
- `engine-uci/target/surefire-reports/`
   - UCI integration details in `coeusyk.game.chess.uci.UciApplicationIntegrationTest.txt`

GUI game results come from the GUI itself:

- Cute Chess:
   - Save PGN from the game/match dialog.
   - Save engine output from the engine log/console window.
- Arena:
   - Save PGN from game save/export.
   - Save engine output/log from Arena's engine log pane.

## 6. Evidence Package

Create a folder for traceable evidence, for example:

- `evidence/issue-25/2026-03-23/`

Include:

- `game1.pgn`
- `uci-gui-log.txt`
- `validation-report.md`

Suggested `validation-report.md` template:

```markdown
# Issue #25 GUI Validation Report

## Environment
- Date:
- GUI: Cute Chess / Arena
- OS:
- Engine command:
- Time control:

## Results
- Engine loaded without errors: PASS/FAIL
- Full legal game completed: PASS/FAIL
- Illegal moves observed: YES/NO
- bestmove emitted consistently: PASS/FAIL

## Artifacts
- PGN: <path>
- UCI log: <path>

## Notes
- Any anomalies or retries:
```

## 7. Closure Notes

After completing this checklist, update issue #25 with:

- GUI used.
- Time control used.
- Game result summary.
- Link or attachment references to PGN/log/report artifacts.