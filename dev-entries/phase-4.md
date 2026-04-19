# Dev Entries - Phase 4

### [2026-03-25] Phase 4 — Classical Evaluation (#44–#49, #60)

**Built:**

- **#44 Eval framework + material baseline:** Created `Evaluator` class with MG/EG PeSTO material values (P=82/94, N=337/281, B=365/297, R=477/512, Q=1025/936). Searcher delegates to `evaluator.evaluate(board)`. Score returned from side-to-move perspective.
- **#45 Piece-square tables:** Created `PieceSquareTables` class with all 12 PeSTO MG/EG tables (6 piece types). `sumPiecePst()` accumulates material + PST per piece with bitboard iteration.
- **#46 Tapered evaluation:** Added `computePhase()` (N=1, B=1, R=2, Q=4, totalPhase=24). Blending formula: `(mg * phase + eg * (24 - phase)) / 24`. Fixed PST square mapping: white uses direct index, black uses `square ^ 56`.
- **#47 Mobility terms:** Created `Attacks` utility class with bitboard-based attack generation (mass pawn shifts, knight shifts with file masks, sliding piece ray iteration). Per-piece mobility scoring with baselines (N=4, B=7, R=7, Q=14) and safe-square counting excluding friendly pieces and enemy pawn attacks.
- **#48 Pawn structure:** Created `PawnStructure` class with three bitboard-only terms: passed pawns (precomputed per-square masks, rank-scaled bonuses 5–100cp MG / 10–165cp EG), isolated pawns (file-fill + adjacent-shift, -15/-20cp), doubled pawns (fill + popcount, -10/-20cp).
- **#49 King safety:** Created `KingSafety` class (MG-only). Pawn shield (+15cp rank 2, +10cp rank 3) for castled kings on g1/h1/c1/b1 (mirrored for black). Open/half-open file penalties near king (-25/-10cp). Attacker weight with quadratic penalty: `-(weight²/4)` using N=2, B=2, R=3, Q=5. King zone = 3×3 + one forward rank, precomputed per square.
- **#60 Endgame mop-up:** Created `MopUp` class (EG-only). Fires when phase ≤ 8, material diff ≥ 400cp, winning side has non-pawn piece. Two terms: enemy king center-Manhattan distance × 10 (corner = +60cp), friendly king proximity (14 - distance) × 4 (adjacent = +52cp).

**Decisions Made:**

- Separated evaluation components into dedicated classes (PieceSquareTables, Attacks, PawnStructure, KingSafety, MopUp) rather than one monolithic Evaluator, keeping each component independently testable.
- Used PeSTO material values and PST tables throughout for proven strength at this eval complexity level.
- King safety is MG-only and mop-up is EG-only — both rely on tapered evaluation to phase naturally.
- Pawn structure computations use pure bitboard operations (file-fill + shift) for isolation and doubling; passed pawns use precomputed per-square masks.
- Added null-king guards in KingSafety and MopUp for test FENs with missing kings.

**Broke / Fixed:**

- **PST square mapping bug (#45→#46):** Initially inverted the mapping to `white ? (square ^ 56) : square` based on incorrect assumption that Board uses a1=0 convention. Discovered via king PST behavior test in #46 — centralized MG king scored higher than safe king, opposite of expected. Used Explore subagent to trace Board FEN parser: a8=0 convention confirmed. Reverted to correct mapping `white ? square : (square ^ 56)`.
- **materialAdvantageDetectedCorrectly test:** Tapered eval blends MG/EG, making score less than pure MG queen value. Relaxed assertion from `>= mgMaterialValue` to `>= egMaterialValue`.
- **mobilityPenalizesRestrictedRook test:** Initial FENs had unequal material (hemmed position had extra pawns). Fixed by equalizing pawns in both positions.
- **ArrayIndexOutOfBoundsException in KingSafety (#49):** `Long.numberOfTrailingZeros(0)` returns 64 when king bitboard is empty (test-only FENs without both kings). Fixed with early return guard.

**Measurements:**

- All 31 evaluator tests pass (material, PST, tapered, mobility, pawn structure, king safety, mop-up, plus symmetry tests for each).
- Perft depth 5 (startpos): 4,865,609 nodes (reference-matching, no regressions).
- Nodes/sec: Not measured in this cycle.
- Elo vs. baseline: **+185.7 ±54.2 Elo** vs. Phase 3 baseline (SPRT H1 accepted — llr 2.97, 101.0%, lbound -2.94, ubound 2.94). LOS: 100%. DrawRatio: 28.6%. ~130 games at st=0.1 (100ms/move), concurrency 4, via cutechess-cli. Phase 4 dominated: 44 black mates + 22 white mates vs 1 P3 win, 38 draws by repetition.

**Next:**

- Merge `phase/4-classical-evaluation` into `develop`. All exit criteria met.

