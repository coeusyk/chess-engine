# Chess Engine Backend (Phase 0)

This repository is now organized as a multi-module Maven project.

## Modules
- `engine-core`: pure Java engine domain module (board/move generation; bitboard target model).
- `chess-engine-api`: Spring Boot REST adapter for UI integration.
- `engine-uci`: UCI protocol runner skeleton (stdin/stdout loop).

## Phase 0 Status
- Core logic extracted into `engine-core`.
- API module now depends on `engine-core` instead of in-module engine classes.
- Session-aware API state introduced using optional `gameId`.
- `new-game` endpoint added: `PUT /engine/new-game`.
- Controllers now delegate to a service facade (`ChessGameService`) as thin transport adapters.
- Parallel game-isolation integration tests added in `chess-engine-api`.
- `Board -> BitboardPosition` snapshot conversion added in `engine-core`.
- CI workflow bootstrapped in `.github/workflows/ci.yml`.

Phase 0 is functionally complete in code. CI pass/green state depends on running the Maven workflow in GitHub.

## Next Steps
- Complete migration from legacy array board to bitboards in `engine-core`.
- Add perft harness and correctness gates.
- Expand UCI module from skeleton to full command support.
