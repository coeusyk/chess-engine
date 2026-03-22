# Chess Engine Backend (Phase 0)

This repository is now organized as a multi-module Maven project.

## Modules
- `engine-core`: pure Java engine domain module (board/move generation; bitboard target model).
- `chess-engine-api`: Spring Boot REST adapter for UI integration.
- `engine-uci`: UCI protocol runner skeleton (stdin/stdout loop).

## Current Phase 0 Progress
- Core logic extracted into `engine-core`.
- API module now depends on `engine-core` instead of in-module engine classes.
- Session-aware API state introduced using optional `gameId`.
- `new-game` endpoint added: `PUT /engine/new-game`.
- CI workflow bootstrapped in `.github/workflows/ci.yml`.
- Bitboard migration target documented in `docs/phase0-architecture.md`.

## Next Steps
- Complete migration from legacy array board to bitboards in `engine-core`.
- Add perft harness and correctness gates.
- Expand UCI module from skeleton to full command support.
