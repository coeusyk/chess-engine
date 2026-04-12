package coeusyk.game.chess.core.eval;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Piece;
import coeusyk.game.chess.core.bitboard.AttackTables;

public class Evaluator {

    private static final int TOTAL_PHASE = 24;

    // Pawn hash table: caches PawnStructure.evaluate() results keyed by pawn Zobrist hash.
    // Default 1 MB = 65536 entries. Configurable via UCI option PawnHashSize.
    // Each entry: 8 bytes (key) + 4 (mg) + 4 (eg) = 16 bytes → 1 MB ≈ 65536 entries.
    private static final int APPROX_PAWN_ENTRY_BYTES = 16;
    private static final int DEFAULT_PAWN_HASH_MB = 1;
    private int pawnTableSize;
    private int pawnTableMask;
    private long[] pawnTableKeys;
    private int[]  pawnTableMg;
    private int[]  pawnTableEg;

    private static final int[] PHASE_WEIGHTS = new int[7];
    private static final int[] MG_MATERIAL = new int[7];
    private static final int[] EG_MATERIAL = new int[7];
    // HANGING_PENALTY is now read from EvalParams.HANGING_PENALTY (overrideable at startup).
    /**
     * Enable pawn-hash hit/miss statistics collection. Off by default (zero overhead);
     * enabled in NpsBenchmarkTest and similar diagnostic callers.
     */
    private boolean pawnHashStatsEnabled = false;
    private long pawnTableHits, pawnTableMisses;

    // Temporary attacker weights set during computeMobilityAndAttack(); one per side.
    // Safe: each Searcher owns its own Evaluator instance (single-threaded per search).
    private int  tempWhiteAttackWeight;
    private int  tempBlackAttackWeight;
    // D-3: which pieces (by square bit) attack the exact enemy king ring (KING_ATTACKS).
    // Set during computeMobilityAndAttack(); consumed by hangingPenalty() to avoid
    // re-computing pieceAttacks() per hanging piece when bEscapes/wEscapes <= 1.
    private long tempWhiteKingRingAttackers;
    private long tempBlackKingRingAttackers;

    // Mobility bonus per safe square (centipawns)
    private static final int[] MG_MOBILITY = new int[7];
    private static final int[] EG_MOBILITY = new int[7];
    // Baseline: subtract this many safe squares before applying bonus
    private static final int[] MOBILITY_BASELINE = new int[7];

    /**
     * Default immutable eval configuration built from the current tuned constants.
     * After a Texel tuning run, copy the new values here and commit.
     * Note: tempo is NOT included here — it is read from EvalParams.TEMPO directly at
     * evaluation time, consistent with all other overrideable EvalParams fields.
     */
    public static final EvalConfig DEFAULT_CONFIG = new EvalConfig(
        /* bishopPairMg/Eg   */ 29, 52,
        /* rook7thMg/Eg      */ 0, 32,
        /* rookOpenMg/Eg     */ 50, 0,
        /* rookSemiMg/Eg     */ 18, 19,
        /* knightOutpostMg/Eg*/ 40, 30,
        /* connectedPawnMg/Eg*/ 9, 4,
        /* backwardPawnMg/Eg */ 0, 0,
        /* rookBehindMg/Eg   */ 12, 4
    );

    private final EvalConfig config;

    // White outpost zone: ranks 4-6 for white (rows 2-4 in a8=0: bits 16-39)
    // Black outpost zone: ranks 3-5 for black (rows 3-5 in a8=0: bits 24-47)
    private static final long WHITE_OUTPOST_ZONE = 0x000000FFFFFF0000L;
    private static final long BLACK_OUTPOST_ZONE = 0x0000FFFFFF000000L;

    // Rank 7 bitboards (a8=0 convention): rank 7 = bits 8-15, rank 2 = bits 48-55
    private static final long WHITE_RANK_7 = 0x000000000000FF00L;
    private static final long BLACK_RANK_7 = 0x00FF000000000000L;
    private static final long FILE_MASK_BASE = 0x0101010101010101L;

    static {
        PHASE_WEIGHTS[Piece.Knight] = 1;
        PHASE_WEIGHTS[Piece.Bishop] = 1;
        PHASE_WEIGHTS[Piece.Rook]   = 2;
        PHASE_WEIGHTS[Piece.Queen]  = 4;

        MG_MATERIAL[Piece.Pawn]   = 100;
        MG_MATERIAL[Piece.Knight] = 391;
        MG_MATERIAL[Piece.Bishop] = 428;
        MG_MATERIAL[Piece.Rook]   = 558;
        MG_MATERIAL[Piece.Queen]  = 1200;
        MG_MATERIAL[Piece.King]   = 0;

        EG_MATERIAL[Piece.Pawn]   = 89;
        EG_MATERIAL[Piece.Knight] = 287;
        EG_MATERIAL[Piece.Bishop] = 311;
        EG_MATERIAL[Piece.Rook]   = 555;
        EG_MATERIAL[Piece.Queen]  = 1040;
        EG_MATERIAL[Piece.King]   = 0;

        MG_MOBILITY[Piece.Knight] = 7;
        MG_MOBILITY[Piece.Bishop] = 8;
        MG_MOBILITY[Piece.Rook]   = 7;
        MG_MOBILITY[Piece.Queen]  = 2;

        EG_MOBILITY[Piece.Knight] = 1;
        EG_MOBILITY[Piece.Bishop] = 3;
        EG_MOBILITY[Piece.Rook]   = 2;
        EG_MOBILITY[Piece.Queen]  = 6;

        MOBILITY_BASELINE[Piece.Knight] = 4;
        MOBILITY_BASELINE[Piece.Bishop] = 7;
        MOBILITY_BASELINE[Piece.Rook]   = 7;
        MOBILITY_BASELINE[Piece.Queen]  = 14;
    }

    /** Creates an Evaluator using the default tuned configuration. */
    public Evaluator() {
        this(DEFAULT_CONFIG);
    }

    /** Creates an Evaluator using a custom configuration (for testing only). */
    public Evaluator(EvalConfig config) {
        this.config = config;
        setPawnHashSizeMb(DEFAULT_PAWN_HASH_MB);
    }

    /**
     * Resizes the pawn hash table. Entry count is the largest power-of-two that
     * fits in {@code mb} megabytes. Clears the table and resets all stat counters.
     *
     * @param mb size in megabytes; clamped to [1, 256]
     */
    public void setPawnHashSizeMb(int mb) {
        int clampedMb = Math.max(1, Math.min(256, mb));
        long bytes = (long) clampedMb * 1024L * 1024L;
        int desiredEntries = (int) Math.min(bytes / APPROX_PAWN_ENTRY_BYTES, 1L << 24);
        int entryCount = 1;
        while (entryCount < desiredEntries) entryCount <<= 1;
        this.pawnTableSize = entryCount;
        this.pawnTableMask = entryCount - 1;
        this.pawnTableKeys = new long[entryCount];
        this.pawnTableMg   = new int[entryCount];
        this.pawnTableEg   = new int[entryCount];
        this.pawnTableHits = 0;
        this.pawnTableMisses = 0;
    }

    /** Enable pawn-hash statistics tracking (hits and misses). Resets counters to zero. */
    public void enablePawnHashStats() {
        this.pawnHashStatsEnabled = true;
        this.pawnTableHits = 0;
        this.pawnTableMisses = 0;
    }

    /** Returns the pawn-hash hit rate [0.0, 1.0]. Meaningful only when stats are enabled. */
    public double getPawnHashHitRate() {
        long total = pawnTableHits + pawnTableMisses;
        return total == 0 ? 0.0 : (double) pawnTableHits / total;
    }

    /** Returns raw miss count. Meaningful only when stats are enabled. */
    public long getPawnHashMisses() { return pawnTableMisses; }

    /** Returns the pawn hash table capacity (entry count). */
    public int getPawnTableSize() { return pawnTableSize; }

    public int evaluate(Board board) {
        // Material + PST scores are maintained incrementally in Board; read the cached values.
        int mgScore = board.getIncMgScore();
        int egScore = board.getIncEgScore();

        long allOccupancy = board.getWhiteOccupancy() | board.getBlackOccupancy();
        long whitePawnAtk = Attacks.whitePawnAttacks(board.getWhitePawns());
        long blackPawnAtk = Attacks.blackPawnAttacks(board.getBlackPawns());

        // Compute king zones for the merged mobility + attacker-weight pass.
        int wKingSq = board.getWhiteKing() != 0L ? Long.numberOfTrailingZeros(board.getWhiteKing()) : -1;
        int bKingSq = board.getBlackKing() != 0L ? Long.numberOfTrailingZeros(board.getBlackKing()) : -1;
        long wKingZone = wKingSq >= 0 ? KingSafety.WHITE_KING_ZONE[wKingSq] : 0L;
        long bKingZone = bKingSq >= 0 ? KingSafety.BLACK_KING_ZONE[bKingSq] : 0L;
        // Exact king rings (KING_ATTACKS) for D-3 attacker bitboard accumulation.
        long wKingRingExact = wKingSq >= 0 ? AttackTables.KING_ATTACKS[wKingSq] : 0L;
        long bKingRingExact = bKingSq >= 0 ? AttackTables.KING_ATTACKS[bKingSq] : 0L;

        // Single pass over each side's pieces: computes mobility AND counts how many
        // of those pieces attack the enemy king zone — eliminating the redundant attack
        // bitboard computations that previously happened in both computeMobilityPacked
        // and KingSafety.attackerPenalty (50% of all sliding-piece magic BB lookups saved).
        long whiteMobility = computeMobilityAndAttack(board, true,  allOccupancy, blackPawnAtk, bKingZone, bKingRingExact);
        long blackMobility = computeMobilityAndAttack(board, false, allOccupancy, whitePawnAtk, wKingZone, wKingRingExact);

        mgScore += unpackMg(whiteMobility) - unpackMg(blackMobility);
        egScore += unpackEg(whiteMobility) - unpackEg(blackMobility);

        int pawnMg, pawnEg;
        long pawnKey = board.getPawnZobristHash();
        int pawnIdx = (int) (pawnKey & pawnTableMask);
        if (pawnTableKeys[pawnIdx] == pawnKey) {
            if (pawnHashStatsEnabled) pawnTableHits++;
            pawnMg = pawnTableMg[pawnIdx];
            pawnEg = pawnTableEg[pawnIdx];
        } else {
            if (pawnHashStatsEnabled) pawnTableMisses++;
            int[] pawnStructure = PawnStructure.evaluate(board.getWhitePawns(), board.getBlackPawns());
            pawnMg = pawnStructure[0];
            pawnEg = pawnStructure[1];
            pawnTableKeys[pawnIdx] = pawnKey;
            pawnTableMg[pawnIdx]   = pawnMg;
            pawnTableEg[pawnIdx]   = pawnEg;
        }
        mgScore += pawnMg;
        egScore += pawnEg;

        mgScore += KingSafety.evaluatePawnShieldAndFiles(board)
                 + (tempWhiteAttackWeight * tempWhiteAttackWeight / 4)
                 - (tempBlackAttackWeight * tempBlackAttackWeight / 4);

        // --- Bishop pair bonus ---
        if (Long.bitCount(board.getWhiteBishops()) >= 2) {
            mgScore += config.bishopPairMg();
            egScore += config.bishopPairEg();
        }
        if (Long.bitCount(board.getBlackBishops()) >= 2) {
            mgScore -= config.bishopPairMg();
            egScore -= config.bishopPairEg();
        }

        // --- Rook on 7th rank bonus ---
        int wRook7 = Long.bitCount(board.getWhiteRooks() & WHITE_RANK_7);
        int bRook7 = Long.bitCount(board.getBlackRooks() & BLACK_RANK_7);
        mgScore += (wRook7 - bRook7) * config.rook7thMg();
        egScore += (wRook7 - bRook7) * config.rook7thEg();

        // --- Rook on open / semi-open file bonus ---
        long wRookFilePacked = rookFilePacked(board.getWhiteRooks(), board.getWhitePawns(), board.getBlackPawns());
        long bRookFilePacked = rookFilePacked(board.getBlackRooks(), board.getBlackPawns(), board.getWhitePawns());
        mgScore += (int) (wRookFilePacked >> 32) - (int) (bRookFilePacked >> 32);
        egScore += (int) wRookFilePacked - (int) bRookFilePacked;

        // --- Knight outpost bonus ---
        int wOutpost = Long.bitCount(board.getWhiteKnights() & WHITE_OUTPOST_ZONE & ~blackPawnAtk);
        int bOutpost = Long.bitCount(board.getBlackKnights() & BLACK_OUTPOST_ZONE & ~whitePawnAtk);
        mgScore += (wOutpost - bOutpost) * config.knightOutpostMg();
        egScore += (wOutpost - bOutpost) * config.knightOutpostEg();

        // --- Connected pawn bonus ---
        int wConn = connectedPawnCount(board.getWhitePawns());
        int bConn = connectedPawnCount(board.getBlackPawns());
        mgScore += (wConn - bConn) * config.connectedPawnMg();
        egScore += (wConn - bConn) * config.connectedPawnEg();

        // --- Backward pawn penalty ---
        int wBack = backwardPawnCount(board.getWhitePawns(), board.getBlackPawns(), true);
        int bBack = backwardPawnCount(board.getBlackPawns(), board.getWhitePawns(), false);
        mgScore -= (wBack - bBack) * config.backwardPawnMg();
        egScore -= (wBack - bBack) * config.backwardPawnEg();

        // --- Rook behind passed pawn bonus ---
        long rookBehindPacked = rookBehindPasserPacked(board.getWhiteRooks(), board.getBlackRooks(),
                board.getWhitePawns(), board.getBlackPawns());
        mgScore += (int) (rookBehindPacked >> 32);
        egScore += (int) rookBehindPacked;

        int phase = computePhase(board);
        egScore += MopUp.evaluate(board, phase);
        int score = (mgScore * phase + egScore * (TOTAL_PHASE - phase)) / TOTAL_PHASE;

        // --- Tempo bonus (applied after phase interpolation) ---
        // Read from EvalParams.TEMPO directly so runtime --param-overrides are picked up.
        score += Piece.isWhite(board.getActiveColor()) ? EvalParams.TEMPO : -EvalParams.TEMPO;

        // --- Hanging piece penalty (undefended attacked non-king pieces) ---
        score += hangingPenalty(board);

        return Piece.isWhite(board.getActiveColor()) ? score : -score;
    }

    /**
     * Penalty for non-king pieces that are attacked and not defended (bitboard form).
     * Uses the Board's cached attackedByWhite/Black bitboards — O(1) instead of ~56
     * isSquareAttackedBy() calls per evaluate() call (~10% CPU eliminated).
     * Returns a white-positive score: negative if white has hanging pieces, positive if black does.
     *
     * <p>Suppression rule (Issue #138): do not apply the penalty to an undefended piece that
     * is attacking a square in the enemy king ring (squares adjacent to the enemy king) when
     * the enemy king has ≤1 safe escape square. Such pieces are typically part of a mating net
     * (e.g. a knight on g4 covering h2 as part of a forced mate sequence).
     */
    private int hangingPenalty(Board board) {
        long allOcc       = board.getAllOccupancy();
        long whiteNonKing = board.getWhiteOccupancy() & ~board.getWhiteKing();
        long blackNonKing = board.getBlackOccupancy() & ~board.getBlackKing();
        long whiteHanging = whiteNonKing & board.getAttackedByBlack() & ~board.getAttackedByWhite();
        long blackHanging = blackNonKing & board.getAttackedByWhite() & ~board.getAttackedByBlack();

        // Suppress penalty for white pieces that are attacking a trapped black king.
        long bKingBb = board.getBlackKing();
        if (bKingBb != 0L && whiteHanging != 0L) {
            int  bKingSq   = Long.numberOfTrailingZeros(bKingBb);
            long bKingRing = AttackTables.KING_ATTACKS[bKingSq];
            int  bEscapes  = Long.bitCount(bKingRing
                    & ~board.getBlackOccupancy() & ~board.getAttackedByWhite());
            if (bEscapes <= 1) {
                // D-2: pre-filter to pieces within one extra step of the king ring;
                // a piece >2 squares away from every king-ring square cannot attack it.
                long bKingRingExp = bKingRing
                        | (bKingRing << 8) | (bKingRing >>> 8)
                        | ((bKingRing & NOT_A_FILE) >>> 1)
                        | ((bKingRing & NOT_H_FILE) << 1);
                whiteHanging &= bKingRingExp;
                // D-3: use the king-ring attacker bitboard precomputed during
                // computeMobilityAndAttack() instead of recomputing per hanging piece.
                long tmp = whiteHanging;
                while (tmp != 0L) {
                    int  sq  = Long.numberOfTrailingZeros(tmp);
                    tmp &= tmp - 1;
                    if ((tempWhiteKingRingAttackers & (1L << sq)) != 0L)
                        whiteHanging &= ~(1L << sq);
                }
            }
        }

        // Suppress penalty for black pieces that are attacking a trapped white king.
        long wKingBb = board.getWhiteKing();
        if (wKingBb != 0L && blackHanging != 0L) {
            int  wKingSq   = Long.numberOfTrailingZeros(wKingBb);
            long wKingRing = AttackTables.KING_ATTACKS[wKingSq];
            int  wEscapes  = Long.bitCount(wKingRing
                    & ~board.getWhiteOccupancy() & ~board.getAttackedByBlack());
            if (wEscapes <= 1) {
                // D-2: pre-filter (symmetric with above)
                long wKingRingExp = wKingRing
                        | (wKingRing << 8) | (wKingRing >>> 8)
                        | ((wKingRing & NOT_A_FILE) >>> 1)
                        | ((wKingRing & NOT_H_FILE) << 1);
                blackHanging &= wKingRingExp;
                // D-3: use precomputed attacker bitboard (symmetric with above)
                long tmp = blackHanging;
                while (tmp != 0L) {
                    int  sq  = Long.numberOfTrailingZeros(tmp);
                    tmp &= tmp - 1;
                    if ((tempBlackKingRingAttackers & (1L << sq)) != 0L)
                        blackHanging &= ~(1L << sq);
                }
            }
        }

        return (Long.bitCount(blackHanging) - Long.bitCount(whiteHanging)) * EvalParams.HANGING_PENALTY;
    }

    /**
     * Returns the attack bitboard for the piece on {@code sq}, dispatching by piece type.
     * Used exclusively by {@link #hangingPenalty} to check whether a hanging piece
     * covers a square in the enemy king ring.
     */
    private static long pieceAttacks(Board board, int sq, boolean white, long allOcc) {
        long bit = 1L << sq;
        if (white) {
            if ((board.getWhiteKnights() & bit) != 0L) return Attacks.knightAttacks(sq);
            if ((board.getWhiteBishops() & bit) != 0L) return Attacks.bishopAttacks(sq, allOcc);
            if ((board.getWhiteRooks()   & bit) != 0L) return Attacks.rookAttacks(sq, allOcc);
            if ((board.getWhiteQueens()  & bit) != 0L) return Attacks.queenAttacks(sq, allOcc);
            if ((board.getWhitePawns()   & bit) != 0L) return Attacks.whitePawnAttacks(bit);
        } else {
            if ((board.getBlackKnights() & bit) != 0L) return Attacks.knightAttacks(sq);
            if ((board.getBlackBishops() & bit) != 0L) return Attacks.bishopAttacks(sq, allOcc);
            if ((board.getBlackRooks()   & bit) != 0L) return Attacks.rookAttacks(sq, allOcc);
            if ((board.getBlackQueens()  & bit) != 0L) return Attacks.queenAttacks(sq, allOcc);
            if ((board.getBlackPawns()   & bit) != 0L) return Attacks.blackPawnAttacks(bit);
        }
        return 0L;
    }

    /**
     * Returns a 2-element array: [0] = white packed MG/EG, [1] = black packed MG/EG.
     * Each element packs MG score in the upper 32 bits and EG score in the lower 32 bits.
     * @deprecated Inlined in evaluate() to eliminate heap allocation. Kept only for test helpers.
     */
    private long rookFilePacked(long rooks, long friendlyPawns, long enemyPawns) {
        int mg = 0, eg = 0;
        long temp = rooks;
        while (temp != 0) {
            int sq = Long.numberOfTrailingZeros(temp);
            int file = sq % 8;
            long fileMask = FILE_MASK_BASE << file;
            if ((friendlyPawns & fileMask) == 0) {
                if ((enemyPawns & fileMask) == 0) {
                    mg += config.rookOpenFileMg();
                    eg += config.rookOpenFileEg();
                } else {
                    mg += config.rookSemiOpenFileMg();
                    eg += config.rookSemiOpenFileEg();
                }
            }
            temp &= temp - 1;
        }
        return ((long) mg << 32) | (eg & 0xFFFFFFFFL);
    }

    /**
     * Single-pass: computes piece mobility for {@code white} AND counts how many of those
     * pieces attack {@code enemyKingZone} (for the king-safety attacker-weight penalty).
     *
     * <p>This replaces the old separate {@code computeMobilityPacked} + {@code KingSafety.attackerPenalty}
     * pair.  Previously each piece's attack bitboard was computed twice per {@code evaluate()} call —
     * once for mobility, once for king safety.  The merged loop computes it once and reuses it
     * for both purposes, eliminating ~50% of all sliding-piece magic bitboard lookups.
     *
     * <p>Side effect: stores the accumulated attacker weight in {@code tempWhiteAttackWeight}
     * (when {@code white=true}) or {@code tempBlackAttackWeight} (when {@code white=false}).
     */
    private long computeMobilityAndAttack(Board board, boolean white, long allOccupancy,
                                          long enemyPawnAttacks, long enemyKingZone,
                                          long enemyKingRing) {
        long friendly = white ? board.getWhiteOccupancy() : board.getBlackOccupancy();
        long safeMask = ~friendly & ~enemyPawnAttacks;
        int mgMob = 0, egMob = 0, atkWeight = 0;
        long kingRingAttackers = 0L;

        // Knights
        long pieces = white ? board.getWhiteKnights() : board.getBlackKnights();
        while (pieces != 0) {
            int sq = Long.numberOfTrailingZeros(pieces);
            long attacks = Attacks.knightAttacks(sq);
            int delta = Long.bitCount(attacks & safeMask) - MOBILITY_BASELINE[Piece.Knight];
            mgMob += delta * MG_MOBILITY[Piece.Knight];
            egMob += delta * EG_MOBILITY[Piece.Knight];
            if ((attacks & enemyKingZone) != 0) atkWeight += EvalParams.ATK_WEIGHT_KNIGHT;
            if ((attacks & enemyKingRing) != 0L) kingRingAttackers |= (1L << sq);
            pieces &= pieces - 1;
        }

        // Bishops
        pieces = white ? board.getWhiteBishops() : board.getBlackBishops();
        while (pieces != 0) {
            int sq = Long.numberOfTrailingZeros(pieces);
            long attacks = Attacks.bishopAttacks(sq, allOccupancy);
            int delta = Long.bitCount(attacks & safeMask) - MOBILITY_BASELINE[Piece.Bishop];
            mgMob += delta * MG_MOBILITY[Piece.Bishop];
            egMob += delta * EG_MOBILITY[Piece.Bishop];
            if ((attacks & enemyKingZone) != 0) atkWeight += EvalParams.ATK_WEIGHT_BISHOP;
            if ((attacks & enemyKingRing) != 0L) kingRingAttackers |= (1L << sq);
            pieces &= pieces - 1;
        }

        // Rooks
        pieces = white ? board.getWhiteRooks() : board.getBlackRooks();
        while (pieces != 0) {
            int sq = Long.numberOfTrailingZeros(pieces);
            long attacks = Attacks.rookAttacks(sq, allOccupancy);
            int delta = Long.bitCount(attacks & safeMask) - MOBILITY_BASELINE[Piece.Rook];
            mgMob += delta * MG_MOBILITY[Piece.Rook];
            egMob += delta * EG_MOBILITY[Piece.Rook];
            if ((attacks & enemyKingZone) != 0) atkWeight += EvalParams.ATK_WEIGHT_ROOK;
            if ((attacks & enemyKingRing) != 0L) kingRingAttackers |= (1L << sq);
            pieces &= pieces - 1;
        }

        // Queens — guard king-zone check so the short-circuit fires when ATK_WEIGHT_QUEEN = 0
        pieces = white ? board.getWhiteQueens() : board.getBlackQueens();
        while (pieces != 0) {
            int sq = Long.numberOfTrailingZeros(pieces);
            long attacks = Attacks.queenAttacks(sq, allOccupancy);
            int delta = Long.bitCount(attacks & safeMask) - MOBILITY_BASELINE[Piece.Queen];
            mgMob += delta * MG_MOBILITY[Piece.Queen];
            egMob += delta * EG_MOBILITY[Piece.Queen];
            if (EvalParams.ATK_WEIGHT_QUEEN != 0 && (attacks & enemyKingZone) != 0)
                atkWeight += EvalParams.ATK_WEIGHT_QUEEN;
            if ((attacks & enemyKingRing) != 0L) kingRingAttackers |= (1L << sq);
            pieces &= pieces - 1;
        }

        if (white) {
            tempWhiteAttackWeight      = atkWeight;
            tempWhiteKingRingAttackers = kingRingAttackers;
        } else {
            tempBlackAttackWeight      = atkWeight;
            tempBlackKingRingAttackers = kingRingAttackers;
        }
        return packMobility(mgMob, egMob);
    }

    private static long packMobility(int mg, int eg) {
        return (((long) mg) << 32) | (eg & 0xffffffffL);
    }

    private static int unpackMg(long packed) {
        return (int) (packed >> 32);
    }

    private static int unpackEg(long packed) {
        return (int) packed;
    }

    int computePhase(Board board) {
        int phase = 0;
        phase += Long.bitCount(board.getWhiteKnights() | board.getBlackKnights()) * PHASE_WEIGHTS[Piece.Knight];
        phase += Long.bitCount(board.getWhiteBishops() | board.getBlackBishops()) * PHASE_WEIGHTS[Piece.Bishop];
        phase += Long.bitCount(board.getWhiteRooks()   | board.getBlackRooks())   * PHASE_WEIGHTS[Piece.Rook];
        phase += Long.bitCount(board.getWhiteQueens()   | board.getBlackQueens())  * PHASE_WEIGHTS[Piece.Queen];
        return Math.min(phase, TOTAL_PHASE);
    }

    public static int mgMaterialValue(int pieceType) {
        return MG_MATERIAL[pieceType];
    }

    public static int egMaterialValue(int pieceType) {
        return EG_MATERIAL[pieceType];
    }

    private static final long NOT_A_FILE = ~0x0101010101010101L;
    private static final long NOT_H_FILE = ~0x8080808080808080L;

    /** Count pawns that have a friendly neighbor on an adjacent file (same row ± 1 row). */
    private static int connectedPawnCount(long pawns) {
        // A pawn is connected if it attacks a friendly pawn, or a friendly pawn attacks it
        long attacks = ((pawns & NOT_A_FILE) >>> 1) | ((pawns & NOT_H_FILE) << 1);
        // Also count supporters one rank behind (diagonal support)
        attacks |= ((pawns & NOT_A_FILE) >>> 9) | ((pawns & NOT_H_FILE) << 7)
                 | ((pawns & NOT_A_FILE) << 7)  | ((pawns & NOT_H_FILE) >>> 9);
        return Long.bitCount(pawns & attacks);
    }

    /**
     * Count backward pawns: a pawn is backward if it cannot safely advance and is
     * behind all friendly pawns on adjacent files.
     */
    private static int backwardPawnCount(long friendly, long enemy, boolean white) {
        long enemyAtk = white ? Attacks.blackPawnAttacks(enemy) : Attacks.whitePawnAttacks(enemy);
        long temp = friendly;
        int count = 0;
        while (temp != 0) {
            int sq = Long.numberOfTrailingZeros(temp);
            int file = sq % 8;
            // build adjacent-file fill of friendly pawns
            long adjFiles = 0L;
            if (file > 0) adjFiles |= FILE_MASK_BASE << (file - 1);
            if (file < 7) adjFiles |= FILE_MASK_BASE << (file + 1);
            long adjFriendly = friendly & adjFiles;
            // For white (advancing toward row 0), backward = no friendly neighbor ahead (lower row),
            // and the stop square is attacked by enemy pawns
            if (white) {
                long ahead = adjFriendly & ((1L << sq) - 1);   // rows < row are lower-index in a8=0
                int stopSq = sq - 8; // one rank forward for white
                if (stopSq >= 0 && ahead == 0 && (enemyAtk & (1L << stopSq)) != 0) {
                    count++;
                }
            } else {
                long ahead = adjFriendly & ~((1L << (sq + 8)) - 1);  // rows > row (higher index)
                int stopSq = sq + 8;
                if (stopSq < 64 && ahead == 0 && (enemyAtk & (1L << stopSq)) != 0) {
                    count++;
                }
            }
            temp &= temp - 1;
        }
        return count;
    }

    /**
     * Score rook-behind-passed-pawn bonus. Returns net score (white - black) packed as
     * mg in the upper 32 bits and eg in the lower 32 bits; avoids int[] heap allocation.
     */
    private long rookBehindPasserPacked(long whiteRooks, long blackRooks,
                                        long whitePawns, long blackPawns) {
        int mg = 0, eg = 0;
        // White rooks behind white passers
        long temp = whiteRooks;
        while (temp != 0) {
            int sq = Long.numberOfTrailingZeros(temp);
            long fileMask = FILE_MASK_BASE << (sq % 8);
            // Passed white pawn: on same file, ahead of rook (lower row in a8=0 = higher rank)
            long passedOnFile = whitePawns & fileMask & ((1L << sq) - 1);
            while (passedOnFile != 0) {
                int psq = Long.numberOfTrailingZeros(passedOnFile);
                // Check if this pawn is actually passed (no enemy pawn blocking ahead)
                if ((blackPawns & fileMask & ((1L << psq) - 1)) == 0) {
                    mg += config.rookBehindPasserMg();
                    eg += config.rookBehindPasserEg();
                }
                passedOnFile &= passedOnFile - 1;
            }
            temp &= temp - 1;
        }
        // Black rooks behind black passers (black advances toward higher rows in a8=0)
        temp = blackRooks;
        while (temp != 0) {
            int sq = Long.numberOfTrailingZeros(temp);
            long fileMask = FILE_MASK_BASE << (sq % 8);
            long passedOnFile = blackPawns & fileMask & ~((1L << sq) - 1) & ~(1L << sq);
            while (passedOnFile != 0) {
                int psq = Long.numberOfTrailingZeros(passedOnFile);
                if ((whitePawns & fileMask & ~((1L << psq) - 1) & ~(1L << psq)) == 0) {
                    mg -= config.rookBehindPasserMg();
                    eg -= config.rookBehindPasserEg();
                }
                passedOnFile &= passedOnFile - 1;
            }
            temp &= temp - 1;
        }
        return ((long) mg << 32) | (eg & 0xFFFFFFFFL);
    }
}
