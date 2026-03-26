# Chess Engine Backend

Backend repository for a modular chess engine platform with REST and UCI adapters.

## Current Status

Phase 5 (Full UCI Protocol + Match Tooling) is complete.

Completed Phase 5 scope includes:

- Full UCI `info` line support (depth, seldepth, score cp/mate, nodes, nps, time, hashfull, pv).
- MultiPV support (`setoption name MultiPV value N`) with independent PV lines and `multipv` reporting.
- Extended `setoption` handling for `Hash`, `MultiPV`, `MoveOverhead`, and `Threads` (single-threaded stub).
- `searchmoves` support and `ponderhit` no-op handling.
- Deterministic bench mode via `bench [depth]` and `--bench [depth]`.
- Match tooling scripts for Cute Chess (`tools/match.sh`, `tools/match.bat`) and reusable engine config (`tools/engines.json`).
- SPRT automation scripts for patch validation (`tools/sprt.sh`, `tools/sprt.bat`).

Phase 4 (Classical Evaluation) is complete.

Completed Phase 4 scope includes:

- Material baseline with MG/EG PeSTO values.
- Piece-square tables for all 6 piece types.
- Tapered evaluation with phase interpolation.
- Mobility evaluation for knights, bishops, rooks, and queens.
- Pawn structure evaluation for passed, isolated, and doubled pawns.
- King safety evaluation with pawn shield, open-file pressure, and attacker weighting.
- Endgame mop-up evaluation for technically won positions.
- Symmetry validation for all evaluation terms.
- SPRT confirmation versus the Phase 3 baseline: `+185.7 +/- 54.2 Elo`, H1 accepted.

Phase 3 (Strength Scaling) is also complete.

Completed Phase 3 scope includes:

- Iterative deepening alpha-beta search.
- Quiescence search.
- Move ordering stack (TT move, MVV-LVA, killer/history heuristics).
- Transposition table integration and measurable TT hit rate.
- Time management for depth, movetime, and clock modes.
- Minimal UCI interface with command-loop integration coverage.

Phase 1 (Correctness Foundation) remains the underlying gate and is complete.

Completed Phase 1 scope includes:

- Bitboard-based make/unmake state transitions in `engine-core`.
- Incremental Zobrist hashing integrated into make/unmake.
- Legal move constraints validated for:
	- castling restrictions,
	- en passant legality,
	- promotion handling (all 4 promotion types, both colors),
	- check/checkmate/stalemate classification.
- Draw-rule detection:
	- threefold repetition (Zobrist history based),
	- 50-move rule (halfmove clock threshold).
- Canonical perft harness and reference tests.
- CI perft gate wired into workflow.

## Repository Layout

- `engine-core`: pure Java chess domain and move-generation engine.
- `chess-engine-api`: Spring Boot REST adapter used by the UI.
- `engine-uci`: UCI runner module.

## Validation Snapshot

- Full reactor build/test: `mvnw clean test` passes.
- `engine-core` test suite includes:
	- legality tests,
	- draw-rule tests,
	- perft reference tests,
	- make/unmake determinism and Zobrist restoration tests,
	- evaluator regression and symmetry tests.
- Phase 4 SPRT result versus Phase 3 baseline:
	- `SPRT: llr 2.97 (101.0%), lbound -2.94, ubound 2.94 - H1 was accepted`
	- `Elo difference: +185.7 +/- 54.2, LOS: 100.0%, DrawRatio: 28.6%`

Perft references currently covered in tests:

- Start position: depth 1..5.
- Kiwipete (Position 2): depth 1..3.
- CPW Position 3: depth 1..5.
- CPW Position 4: depth 1..4.
- CPW Position 5: depth 1..4.

## Build And Test

From repo root (`chess-engine`):

```bash
./mvnw clean test
```

Windows PowerShell:

```powershell
.\mvnw.cmd clean test
```

Run only core tests:

```powershell
.\mvnw.cmd -pl engine-core test
```

Run perft harness gate only:

```powershell
.\mvnw.cmd -pl engine-core -Dtest=PerftHarnessTest test
```

Run tactical mate suite benchmark (>= 80% expected):

```powershell
.\mvnw.cmd --% -pl engine-core -Dtest=TacticalSuiteTest -Dtactical.enabled=true -Dtactical.movetime.ms=2000 -Dtactical.min.pass.rate=0.80 -Dtactical.expected.positions=50 test
```

Alternative (depth-limited instead of movetime):

```powershell
.\mvnw.cmd --% -pl engine-core -Dtest=TacticalSuiteTest -Dtactical.enabled=true -Dtactical.depth=5 -Dtactical.min.pass.rate=0.80 -Dtactical.expected.positions=50 test
```

Default bundled suite path:

- `engine-core/src/test/resources/tactical/mate_2_3_50.epd`

## Play Against The Engine

You can play Vex either through a chess GUI (Cute Chess) using UCI, or through your web UI (`chess-engine-ui`) backed by the REST API.

### Option A: Play In Cute Chess (UCI)

1. Build the backend modules:

```powershell
.\mvnw.cmd clean install -DskipTests
```

2. Add the UCI engine in Cute Chess:
- Open Cute Chess.
- Go to engine management and add a new UCI engine.
- Engine command:

```text
java -jar <absolute-path-to>\engine-uci\target\engine-uci-0.0.1-SNAPSHOT.jar
```

3. Start a game:
- Create a new game with Vex as one side.
- Set time control as desired (for example, `10+0.1`).

4. Optional: run automated matches from repo root:

```powershell
tools\match.bat <new-engine.jar> <old-engine.jar> 100 "tc=10+0.1"
```

5. Optional: run SPRT patch validation:

```powershell
tools\sprt.bat <new-engine.jar> <old-engine.jar>
```

Generated PGNs are written under `tools/results/`.

### Option B: Play In The Web UI (`chess-engine-ui`)

1. Start backend API (`chess-engine-api`) from the backend repo root:

```powershell
.\mvnw.cmd -pl chess-engine-api spring-boot:run
```

2. In a separate terminal, start the UI from `chess-engine-ui`:

```powershell
npm install
npm start
```

3. Open the local UI URL shown by React (typically `http://localhost:3000`).

4. Ensure the UI API base URL points to your backend (commonly `http://localhost:8080`).

If your UI supports side/orientation selection, choose your side and start playing.

## API Module Notes

- Session-aware gameplay via optional `gameId` support.
- New game reset endpoint:
	- `PUT /engine/new-game`

## Next Phase

Phase 6 focus (planned):

- Continue search-strength improvements on top of completed Phase 5 UCI and tooling foundations.
- Increase tactical reliability and time-management quality under match conditions.
- Maintain Phase 1 correctness gates and Phase 4/5 regression baselines while improving practical playing strength.
