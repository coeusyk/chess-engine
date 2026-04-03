package coeusyk.game.chess.core.eval;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Piece;
import coeusyk.game.chess.core.search.StaticExchangeEvaluator;

public class Evaluator {

    private static final int TOTAL_PHASE = 24;

    // Pawn hash table: caches PawnStructure.evaluate() results keyed by pawn Zobrist hash.
    // 16K entries at 2 ints each = ~128KB. Pawn structure rarely changes between sibling nodes.
    private static final int PAWN_TABLE_SIZE = 1 << 14; // 16384 entries
    private static final int PAWN_TABLE_MASK = PAWN_TABLE_SIZE - 1;
    private final long[] pawnTableKeys   = new long[PAWN_TABLE_SIZE];
    private final int[]  pawnTableMg     = new int[PAWN_TABLE_SIZE];
    private final int[]  pawnTableEg     = new int[PAWN_TABLE_SIZE];

    private static final int[] PHASE_WEIGHTS = new int[7];
    private static final int[] MG_MATERIAL = new int[7];
    private static final int[] EG_MATERIAL = new int[7];
    /** Fixed penalty (cp) applied per undefended attacked non-king piece. */
    private static final int HANGING_PENALTY = 50;
    /**
     * Enable pawn-hash hit/miss statistics collection. Off by default (zero overhead);
     * enabled in NpsBenchmarkTest and similar diagnostic callers.
     */
    private boolean pawnHashStatsEnabled = false;
    private long pawnTableHits, pawnTableMisses;

    // Mobility bonus per safe square (centipawns)
    private static final int[] MG_MOBILITY = new int[7];
    private static final int[] EG_MOBILITY = new int[7];
    // Baseline: subtract this many safe squares before applying bonus
    private static final int[] MOBILITY_BASELINE = new int[7];

    /**
     * Default immutable eval configuration built from the current tuned constants.
     * After a Texel tuning run, copy the new values here and commit.
     * No runtime injection — this is the single live config used by all Evaluator instances.
     */
    public static final EvalConfig DEFAULT_CONFIG = new EvalConfig(
        /* tempo              */ 21,
        /* bishopPairMg/Eg   */ 33, 52,
        /* rook7thMg/Eg      */ 2, 23,
        /* rookOpenMg/Eg     */ 50, 0,
        /* rookSemiMg/Eg     */ 19, 19,
        /* knightOutpostMg/Eg*/ 40, 30,
        /* connectedPawnMg/Eg*/ 9, 4,
        /* backwardPawnMg/Eg */ 0, 0,
        /* rookBehindMg/Eg   */ 12, 4
    );

    private final EvalConfig config;
    private final StaticExchangeEvaluator see;

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
        this.see = new StaticExchangeEvaluator();
    }

    /** Enable pawn-hash statistics tracking (hits and misses). Resets counters to zero. */
    void enablePawnHashStats() {
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

    /** Returns the pawn hash table capacity. */
    public int getPawnTableSize() { return PAWN_TABLE_SIZE; }

    public int evaluate(Board board) {
        // Material + PST scores are maintained incrementally in Board; read the cached values.
        int mgScore = board.getIncMgScore();
        int egScore = board.getIncEgScore();

        long allOccupancy = board.getWhiteOccupancy() | board.getBlackOccupancy();
        long whitePawnAtk = Attacks.whitePawnAttacks(board.getWhitePawns());
        long blackPawnAtk = Attacks.blackPawnAttacks(board.getBlackPawns());

        long whiteMobility = computeMobilityPacked(board, true, allOccupancy, blackPawnAtk);
        long blackMobility = computeMobilityPacked(board, false, allOccupancy, whitePawnAtk);

        mgScore += unpackMg(whiteMobility) - unpackMg(blackMobility);
        egScore += unpackEg(whiteMobility) - unpackEg(blackMobility);

        int pawnMg, pawnEg;
        long pawnKey = board.getPawnZobristHash();
        int pawnIdx = (int) (pawnKey & PAWN_TABLE_MASK);
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

        mgScore += KingSafety.evaluate(board);

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
        long[] rookFiles = rookFileScores(board.getWhiteRooks(), board.getBlackRooks(),
                board.getWhitePawns(), board.getBlackPawns());
        mgScore += (int) (rookFiles[0] >> 32) - (int) (rookFiles[1] >> 32);
        egScore += (int) rookFiles[0] - (int) rookFiles[1];

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
        int[] rookBehind = rookBehindPasserScores(board.getWhiteRooks(), board.getBlackRooks(),
                board.getWhitePawns(), board.getBlackPawns());
        mgScore += rookBehind[0];
        egScore += rookBehind[1];

        int phase = computePhase(board);
        egScore += MopUp.evaluate(board, phase);
        int score = (mgScore * phase + egScore * (TOTAL_PHASE - phase)) / TOTAL_PHASE;

        // --- Tempo bonus (applied after phase interpolation) ---
        score += Piece.isWhite(board.getActiveColor()) ? config.tempo() : -config.tempo();

        // --- Hanging piece penalty (undefended attacked non-king pieces) ---
        score += hangingPenalty(board);

        return Piece.isWhite(board.getActiveColor()) ? score : -score;
    }

    /**
     * Penalty for non-king pieces that are attacked and not defended (bitboard form).
     * Uses the Board's cached attackedByWhite/Black bitboards — O(1) instead of ~56
     * isSquareAttackedBy() calls per evaluate() call (~10% CPU eliminated).
     * Returns a white-positive score: negative if white has hanging pieces, positive if black does.
     */
    private int hangingPenalty(Board board) {
        long whiteNonKing = board.getWhiteOccupancy() & ~board.getWhiteKing();
        long blackNonKing = board.getBlackOccupancy() & ~board.getBlackKing();
        long whiteHanging = whiteNonKing & board.getAttackedByBlack() & ~board.getAttackedByWhite();
        long blackHanging = blackNonKing & board.getAttackedByWhite() & ~board.getAttackedByBlack();
        return (Long.bitCount(blackHanging) - Long.bitCount(whiteHanging)) * HANGING_PENALTY;
    }

    /**
     * Returns a 2-element array: [0] = white packed MG/EG, [1] = black packed MG/EG.
     * Each element packs MG score in the upper 32 bits and EG score in the lower 32 bits.
     */
    private long[] rookFileScores(long whiteRooks, long blackRooks, long whitePawns, long blackPawns) {
        long wPacked = rookFilePacked(whiteRooks, whitePawns, blackPawns);
        long bPacked = rookFilePacked(blackRooks, blackPawns, whitePawns);
        return new long[]{ wPacked, bPacked };
    }

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

    private long computeMobilityPacked(Board board, boolean white, long allOccupancy, long enemyPawnAttacks) {
        long friendly = white ? board.getWhiteOccupancy() : board.getBlackOccupancy();
        long safeMask = ~friendly & ~enemyPawnAttacks;

        int mgMob = 0;
        int egMob = 0;

        long kn = pieceMobilityPacked(white ? board.getWhiteKnights() : board.getBlackKnights(),
            Piece.Knight, allOccupancy, safeMask);
        mgMob += unpackMg(kn);
        egMob += unpackEg(kn);

        long bi = pieceMobilityPacked(white ? board.getWhiteBishops() : board.getBlackBishops(),
            Piece.Bishop, allOccupancy, safeMask);
        mgMob += unpackMg(bi);
        egMob += unpackEg(bi);

        long ro = pieceMobilityPacked(white ? board.getWhiteRooks() : board.getBlackRooks(),
            Piece.Rook, allOccupancy, safeMask);
        mgMob += unpackMg(ro);
        egMob += unpackEg(ro);

        long qu = pieceMobilityPacked(white ? board.getWhiteQueens() : board.getBlackQueens(),
            Piece.Queen, allOccupancy, safeMask);
        mgMob += unpackMg(qu);
        egMob += unpackEg(qu);

        return packMobility(mgMob, egMob);
    }

        private long pieceMobilityPacked(long pieces, int pieceType, long allOccupancy, long safeMask) {
        int mgBonus = MG_MOBILITY[pieceType];
        int egBonus = EG_MOBILITY[pieceType];
        int baseline = MOBILITY_BASELINE[pieceType];
        int mgTotal = 0;
        int egTotal = 0;
        while (pieces != 0) {
            int sq = Long.numberOfTrailingZeros(pieces);
            long attacks;
            switch (pieceType) {
                case Piece.Knight: attacks = Attacks.knightAttacks(sq); break;
                case Piece.Bishop: attacks = Attacks.bishopAttacks(sq, allOccupancy); break;
                case Piece.Rook:   attacks = Attacks.rookAttacks(sq, allOccupancy); break;
                case Piece.Queen:  attacks = Attacks.queenAttacks(sq, allOccupancy); break;
                default: attacks = 0L;
            }
            int safeSquares = Long.bitCount(attacks & safeMask);
            int delta = safeSquares - baseline;
            mgTotal += delta * mgBonus;
            egTotal += delta * egBonus;
            pieces &= pieces - 1;
        }
        return packMobility(mgTotal, egTotal);
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
            int row = sq / 8;
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
     * Score rook-behind-passed-pawn bonus. Returns [mg, eg] net score (white - black).
     */
    private int[] rookBehindPasserScores(long whiteRooks, long blackRooks,
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
        return new int[]{mg, eg};
    }
}
