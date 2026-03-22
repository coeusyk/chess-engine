# engine-uci

Phase 0 UCI skeleton module.

Current behavior:
- Supports `uci`, `isready`, `go`, `stop`, and `quit` command loop.
- Returns placeholder `bestmove 0000` for `go`/`stop`.

Next phases will wire this module to engine-core search and full UCI info output.
