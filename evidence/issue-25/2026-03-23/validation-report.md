# Issue #25 GUI Validation Report

## Environment
- Date: 2026-03-24
- GUI: Chess GUI (PGN export present; specify Cute Chess or Arena if needed)
- OS: Windows
- Engine command: java -cp <path-to-engine-uci-jar-or-classes> coeusyk.game.chess.uci.UciApplication
- Time control: hg600 (from PGN tag)

## Results
- Engine loaded without errors: PASS (game completed and PGN exported)
- Full legal game completed: PASS (43 plies, checkmate termination)
- Illegal moves observed: NO
- bestmove emitted consistently: PASS (inferred from uninterrupted complete game)

## Artifacts
- PGN: evidence/issue-25/2026-03-23/game1.pgn
- UCI log: evidence/issue-25/2026-03-23/uci-gui-log.txt (pending - not added yet)

## Notes
- Game metadata from PGN:
	- White: yashk
	- Black: Vex
	- Result: 1-0
	- Opening/Variation: King's pawn / Nimzowitsch defense
	- Termination: White mates (move 22)
- You noted opponent was Stockfish configured at depth=22 (not encoded directly in PGN tags).
