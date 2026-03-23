# Chess Engine Backend

Backend repository for a modular chess engine platform with REST and UCI adapters.

## Current Status

Phase 1 (Correctness Foundation) is complete.

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
- `engine-uci`: UCI runner module (Phase 2 expansion target).

## Validation Snapshot

- Full reactor build/test: `mvnw clean test` passes.
- `engine-core` test suite includes:
	- legality tests,
	- draw-rule tests,
	- perft reference tests,
	- make/unmake determinism and Zobrist restoration tests.

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
.\mvnw.cmd -pl engine-core -Dtest=TacticalSuiteTest test -Dtactical.enabled=true -Dtactical.suite.file="C:\path\to\mate_2_3_50.epd" -Dtactical.movetime.ms=2000 -Dtactical.min.pass.rate=0.80 -Dtactical.expected.positions=50
```

Alternative (depth-limited instead of movetime):

```powershell
.\mvnw.cmd -pl engine-core -Dtest=TacticalSuiteTest test -Dtactical.enabled=true -Dtactical.suite.file="C:\path\to\mate_2_3_50.epd" -Dtactical.depth=8 -Dtactical.min.pass.rate=0.80 -Dtactical.expected.positions=50
```

Default suite template path:

- `engine-core/src/test/resources/tactical/mate_2_3_50.epd`

## API Module Notes

- Session-aware gameplay via optional `gameId` support.
- New game reset endpoint:
	- `PUT /engine/new-game`

## Next Phase

Phase 2 focus:

- Iterative deepening alpha-beta search.
- Quiescence search and baseline move ordering.
- Transposition table and time management.
- Minimal but practical UCI command support in `engine-uci`.
