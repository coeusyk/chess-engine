# Dev Entries - Phase 5

### [2026-03-26] Phase 5 — Full UCI Protocol + Match Tooling (Issues #51–#57)

**Built:**

- **Issue #51**: Full UCI info line output — added IterationInfo record, TranspositionTable.hashfull(), Searcher seldepth/timing tracking, UciApplication info line formatting with depth, seldepth, score cp/mate, nodes, nps, time, hashfull, pv.
- **Issue #52**: MultiPV N support — added excluded-root-moves pattern in iterativeDeepening() with inner pvIndex loop per depth. Aspiration windows only for pvIndex==0. IterationInfo extended with multipv field. UciApplication parses setoption MultiPV and emits multipv token.
- **Issue #53**: setoption handling for Hash (TT resize, 1-65536 MB), MultiPV (1-500), MoveOverhead (0-5000ms), Threads (accepted, ignored). Config persists across ucinewgame. All four options declared in uci response.
- **Issue #54**: searchmoves filtering in Searcher.searchRoot() with parseSearchMoves() in UciApplication. Ponder stub: go ponder searches normally, ponderhit handled as no-op.
- **Issue #55**: Bench mode — 6 fixed FENs, configurable depth (default 13), 16MB hash, TT cleared between positions. --bench CLI flag and bench UCI command. Verified deterministic: 27845 nodes at depth 5 across 2 runs.
- **Issue #55 (bugfix)**: Fixed latent PV table allocation bug — pvTable/pvLength were sized [depth+4] which overflows when check extensions push ply beyond depth+3. Changed to [depth+maxCheckExtensions+4]. Bug triggered at depth 13 with maxCheckExtensions=6.
- **Issue #56**: Match runner scripts — tools/match.sh, tools/match.bat, tools/engines.json for cutechess-cli N-game matches. PGN output to tools/results/.
- **Issue #57**: SPRT workflow scripts — tools/sprt.sh, tools/sprt.bat with SPRT(0, 50, 0.05, 0.05) for automated patch validation. Includes documentation on reading H0/H1 verdicts.

**Decisions Made:**

- Used excluded-root-moves pattern for MultiPV (standard engine approach) rather than separate search instances.
- Move comparison in isExcludedMove() uses startSquare + targetSquare + Objects.equals(reaction) since Move has no equals/hashCode.
- PV table sized dynamically per depth iteration with check extension headroom rather than using MAX_PLY (avoids excessive allocation at low depths).
- Bench default depth kept at 13 per spec even though engine NPS (~250 at depth 5) makes depth 13 impractical in practice — depth is configurable.

**Broke / Fixed:**

- PV table ArrayIndexOutOfBoundsException at depth 13 — fixed by accounting for check extensions in array allocation.
- Pre-existing ttMoveHintIsTriedFirstAtRoot test failure (expected sq 52, got 51) remains on develop baseline. Not caused by Phase 5 changes.

**Measurements:**

- Perft depth 5 (startpos): 4,865,609 (5/5 positions pass, no regressions)
- Bench depth 5: 27845 nodes, deterministic (verified 2 runs)
- Bench NPS: ~250 nps at depth 5 (engine is slow; depth 13 impractical)

**Next:**

- Phase 5 exit criteria review and merge to develop.
- Tightened UCI integration handshake test to assert exact line `id name Vex` instead of generic prefix matching.

### [2026-03-26] Phase 5 — Full UCI Protocol + Match Tooling (#51–#57)

**Built:**

- **#51 UCI info completeness:** Added full `info` line output support with `depth`, `seldepth`, `score cp/mate`, `nodes`, `nps`, `time`, `hashfull`, and `pv`.
- **#52 MultiPV:** Added `setoption name MultiPV value N`, root exclusion flow per PV line, and `multipv` token emission in UCI info output.
- **#53 setoption expansion:** Implemented option handling for `Hash`, `MultiPV`, `MoveOverhead`, and `Threads` (accepted as a no-op for single-threaded engine).
- **#54 searchmoves + ponder stub:** Added `searchmoves` filtering in root search and `ponderhit` no-op command handling.
- **#55 bench mode:** Added fixed-position benchmark command via `bench [depth]` and CLI `--bench [depth]`; fixed bench hash to 16MB and clear TT between positions.
- **#56 match tooling:** Added `tools/match.sh`, `tools/match.bat`, and `tools/engines.json` for repeatable Cute Chess matches with timestamped PGN output in `tools/results/`.
- **#57 SPRT tooling:** Added `tools/sprt.sh` and `tools/sprt.bat` for automated patch validation with SPRT(elo0=0, elo1=50, alpha=0.05, beta=0.05).
- Added user-facing replication guidance in `README.md` so users can play against the engine in Cute Chess or via the web UI.

**Decisions Made:**

- Kept MultiPV implementation inside one iterative deepening pass (excluded-root pattern) to preserve existing search behavior when `MultiPV=1`.
- Fixed bench hash size at 16MB and reset TT per position for deterministic node fingerprints.
- Kept bench depth default at 13 per issue spec while allowing override for slower environments.
- Standardized match/SPRT scripts to write timestamped artifacts into `tools/results/` for reproducible result tracking.

**Broke / Fixed:**

- Found and fixed a PV table sizing bug surfaced by deeper bench runs: PV arrays were sized too tightly for extension-driven plies, causing out-of-bounds behavior at deeper depths.
- No perft correctness regressions introduced by Phase 5 changes.

**Measurements:**

- Perft depth 5 (startpos): 4,865,609 (reference match maintained).
- Bench determinism check (depth 5 sample): stable node count across repeated runs (`27845` nodes observed in consecutive runs).
- Elo vs. baseline: Not measured in this cycle (tooling delivery phase).

**Next:**

- Use `tools/match.*` and `tools/sprt.*` to run ongoing regression and patch-validation campaigns.
- Start next search-strength tranche with Phase 5 UCI/tooling baseline locked.

