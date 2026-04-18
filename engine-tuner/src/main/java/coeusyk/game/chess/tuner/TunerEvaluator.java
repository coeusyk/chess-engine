package coeusyk.game.chess.tuner;

import coeusyk.game.chess.core.bitboard.AttackTables;
import coeusyk.game.chess.core.eval.Attacks;
import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Piece;

import java.util.List;

/**
 * Static evaluator that accepts a parameter array instead of hardcoded constants.
 * All parameters are read from the {@code double[]} supplied by the caller.
 *
 * <p>Score is always from <b>White's perspective</b> (positive = White advantage),
 * regardless of the side to move. This matches the convention expected by MSE
 * computation, where outcome is also stored as White's result (1.0 / 0.5 / 0.0).
 *
 * <p>MopUp is deliberately excluded: mop-up is a heuristic endgame trick with no
 * tunable parameters, and standard Texel datasets contain very few mop-up positions.
 */
public final class TunerEvaluator {

    /** Thread-local qsearch instances for parallel MSE computation. */
    private static final ThreadLocal<TunerQuiescence> QSEARCH =
            ThreadLocal.withInitial(TunerQuiescence::new);

    private static final int TOTAL_PHASE = 24;

    // Phase weights — architectural, not tunable
    private static final int[] PHASE_WEIGHT = new int[7];

    // Mobility baselines — not tunable (structural: counts safe squares above baseline)
    private static final int[] MOBILITY_BASELINE = new int[7];

    // --- Precomputed lookup tables (same logic as PawnStructure / KingSafety) ---
    private static final long[] WHITE_PASSED_MASKS = new long[64];
    private static final long[] BLACK_PASSED_MASKS = new long[64];
    private static final long[] WHITE_KING_ZONE    = new long[64];
    private static final long[] BLACK_KING_ZONE    = new long[64];

    private static final long NOT_A_FILE = ~0x0101010101010101L;
    private static final long NOT_H_FILE = ~0x8080808080808080L;

    // Castled-king squares (a8=0 convention, same as KingSafety)
    private static final int WHITE_G1 = 62, WHITE_H1 = 63, WHITE_C1 = 58, WHITE_B1 = 57;
    private static final int BLACK_G8 = 6,  BLACK_H8 = 7,  BLACK_C8 = 2,  BLACK_B8 = 1;

    static {
        PHASE_WEIGHT[Piece.Knight] = 1;
        PHASE_WEIGHT[Piece.Bishop] = 1;
        PHASE_WEIGHT[Piece.Rook]   = 2;
        PHASE_WEIGHT[Piece.Queen]  = 4;

        MOBILITY_BASELINE[Piece.Knight] = 4;
        MOBILITY_BASELINE[Piece.Bishop] = 7;
        MOBILITY_BASELINE[Piece.Rook]   = 7;
        MOBILITY_BASELINE[Piece.Queen]  = 14;

        // Passed pawn masks — identical logic to PawnStructure.WHITE_PASSED_MASKS
        for (int sq = 0; sq < 64; sq++) {
            int file = sq % 8;
            int row  = sq / 8;

            long wMask = 0L;
            for (int r = 0; r < row; r++) {
                for (int f = Math.max(0, file - 1); f <= Math.min(7, file + 1); f++) {
                    wMask |= 1L << (r * 8 + f);
                }
            }
            WHITE_PASSED_MASKS[sq] = wMask;

            long bMask = 0L;
            for (int r = row + 1; r < 8; r++) {
                for (int f = Math.max(0, file - 1); f <= Math.min(7, file + 1); f++) {
                    bMask |= 1L << (r * 8 + f);
                }
            }
            BLACK_PASSED_MASKS[sq] = bMask;
        }

        // King zones — identical logic to KingSafety.WHITE_KING_ZONE / BLACK_KING_ZONE
        for (int sq = 0; sq < 64; sq++) {
            int row  = sq / 8;
            int file = sq % 8;

            long zone = 0L;
            for (int dr = -1; dr <= 1; dr++) {
                for (int df = -1; df <= 1; df++) {
                    int r = row + dr, f = file + df;
                    if (r >= 0 && r < 8 && f >= 0 && f < 8) zone |= 1L << (r * 8 + f);
                }
            }

            long wZone = zone;
            int wFwd = row - 2;
            if (wFwd >= 0) {
                for (int df = -1; df <= 1; df++) {
                    int f = file + df;
                    if (f >= 0 && f < 8) wZone |= 1L << (wFwd * 8 + f);
                }
            }
            WHITE_KING_ZONE[sq] = wZone;

            long bZone = zone;
            int bFwd = row + 2;
            if (bFwd < 8) {
                for (int df = -1; df <= 1; df++) {
                    int f = file + df;
                    if (f >= 0 && f < 8) bZone |= 1L << (bFwd * 8 + f);
                }
            }
            BLACK_KING_ZONE[sq] = bZone;
        }
    }

    // Lightweight adapter so Board (engine-core) can be passed through
    // the PositionData-based helper methods without modifying Board itself.
    private static final class BoardAdapter implements PositionData {
        private final Board board;
        BoardAdapter(Board board) { this.board = board; }
        @Override public long getWhitePawns()   { return board.getWhitePawns(); }
        @Override public long getBlackPawns()   { return board.getBlackPawns(); }
        @Override public long getWhiteKnights() { return board.getWhiteKnights(); }
        @Override public long getBlackKnights() { return board.getBlackKnights(); }
        @Override public long getWhiteBishops() { return board.getWhiteBishops(); }
        @Override public long getBlackBishops() { return board.getBlackBishops(); }
        @Override public long getWhiteRooks()   { return board.getWhiteRooks(); }
        @Override public long getBlackRooks()   { return board.getBlackRooks(); }
        @Override public long getWhiteQueens()  { return board.getWhiteQueens(); }
        @Override public long getBlackQueens()  { return board.getBlackQueens(); }
        @Override public long getWhiteKing()    { return board.getWhiteKing(); }
        @Override public long getBlackKing()    { return board.getBlackKing(); }
        @Override public long getWhiteOccupancy() { return board.getWhiteOccupancy(); }
        @Override public long getBlackOccupancy() { return board.getBlackOccupancy(); }
        @Override public int  getActiveColor()  { return board.getActiveColor(); }
    }

    private TunerEvaluator() {}

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Evaluates a position from White's perspective using qsearch.
     * Reconstructs a full {@link Board} from the {@link TunerPosition}'s FEN
     * so that make/unmake is available for the capture chain.
     */
    public static int evaluate(TunerPosition pos, double[] params) {
        return QSEARCH.get().search(pos.toBoard(), params);
    }

    /**
     * Evaluates {@code board} from White's perspective using qsearch to resolve
     * captures before returning the static eval. This ensures noisy positions
     * with hanging pieces are evaluated at a quiet state.
     *
     * @param board  position to evaluate (modified via make/unmake but restored)
     * @param params parameter array of length {@link EvalParams#TOTAL_PARAMS}
     * @return centipawn score (positive = White advantage)
     */
    public static int evaluate(Board board, double[] params) {
        return QSEARCH.get().search(board, params);
    }

    /**
     * Pure static eval from White's perspective, operating on compact
     * {@link PositionData}. No qsearch, no Board allocation.
     * This is the hot path for gradient-based optimisers.
     */
    static int evaluateStatic(PositionData pos, double[] params) {
        int mgScore = 0;
        int egScore = 0;

        mgScore += materialAndPst(pos, true,  true,  params)
                 - materialAndPst(pos, false, true,  params);
        egScore += materialAndPst(pos, true,  false, params)
                 - materialAndPst(pos, false, false, params);

        long allOcc       = pos.getWhiteOccupancy() | pos.getBlackOccupancy();
        long whitePawnAtk = Attacks.whitePawnAttacks(pos.getWhitePawns());
        long blackPawnAtk = Attacks.blackPawnAttacks(pos.getBlackPawns());

        int[] whiteMob = mobility(pos, true,  allOcc, blackPawnAtk, params);
        int[] blackMob = mobility(pos, false, allOcc, whitePawnAtk, params);
        mgScore += whiteMob[0] - blackMob[0];
        egScore += whiteMob[1] - blackMob[1];

        int[] pawn = pawnStructure(pos.getWhitePawns(), pos.getBlackPawns(), params);
        mgScore += pawn[0];
        egScore += pawn[1];

        mgScore += kingSafety(pos, params);

        // --- Bishop pair bonus ---
        int[] bpair = bishopPair(pos, params);
        mgScore += bpair[0];
        egScore += bpair[1];

        // --- Rook on 7th rank bonus ---
        int[] r7 = rookOnSeventh(pos, params);
        mgScore += r7[0];
        egScore += r7[1];

        // --- Rook on open / semi-open file bonus ---
        int[] rookFiles = rookOpenFile(pos, params);
        mgScore += rookFiles[0];
        egScore += rookFiles[1];

        // --- Knight outpost bonus ---
        int[] outpost = knightOutpost(pos, params);
        mgScore += outpost[0];
        egScore += outpost[1];

        // --- Connected pawn bonus ---
        int[] connPawn = connectedPawn(pos, params);
        mgScore += connPawn[0];
        egScore += connPawn[1];

        // --- Backward pawn penalty ---
        int[] backPawn = backwardPawn(pos, params);
        mgScore -= backPawn[0];
        egScore -= backPawn[1];

        // --- Rook behind passed pawn bonus ---
        int[] rookBehind = rookBehindPasser(pos, params);
        mgScore += rookBehind[0];
        egScore += rookBehind[1];

        int phase = computePhase(pos);
        int score = (mgScore * phase + egScore * (TOTAL_PHASE - phase)) / TOTAL_PHASE;

        // --- Tempo bonus (applied after phase interpolation, from White's perspective) ---
        int tempo = (int) params[EvalParams.IDX_TEMPO];
        if (Piece.isWhite(pos.getActiveColor())) {
            score += tempo;
        } else {
            score -= tempo;
        }

        // --- Hanging piece penalty (applied after phase interpolation, like tempo) ---
        score += hangingPenalty(pos, params);

        return score;
    }

    /**
     * Board overload — wraps in {@link BoardAdapter} and delegates.
     * Called by {@link TunerQuiescence} at leaf nodes.
     */
    static int evaluateStatic(Board board, double[] params) {
        return evaluateStatic(new BoardAdapter(board), params);
    }

    /**
     * Mean squared error over all positions using the Texel sigmoid.
     * Runs in parallel for throughput.
     */
    public static double computeMse(List<LabelledPosition> positions, double[] params, double k) {
        return positions.parallelStream()
                .mapToDouble(lp -> {
                    double sig = sigmoid(evaluate(lp.pos(), params), k);
                    double err = lp.outcome() - sig;
                    return err * err;
                })
                .average()
                .orElse(0.0);
    }

    /**
     * Texel sigmoid: σ(s) = 1 / (1 + 10^(−K·s / 400))
     */
    public static double sigmoid(double eval, double k) {
        return 1.0 / (1.0 + Math.pow(10.0, -k * eval / 400.0));
    }

    // =========================================================================
    // Material + PST
    // =========================================================================

    private static int materialAndPst(PositionData pos, boolean white, boolean mg, double[] params) {
        int score = 0;
        score += sumPst(white ? pos.getWhitePawns()   : pos.getBlackPawns(),   Piece.Pawn,   white, mg, params);
        score += sumPst(white ? pos.getWhiteKnights() : pos.getBlackKnights(), Piece.Knight, white, mg, params);
        score += sumPst(white ? pos.getWhiteBishops() : pos.getBlackBishops(), Piece.Bishop, white, mg, params);
        score += sumPst(white ? pos.getWhiteRooks()   : pos.getBlackRooks(),   Piece.Rook,   white, mg, params);
        score += sumPst(white ? pos.getWhiteQueens()  : pos.getBlackQueens(),  Piece.Queen,  white, mg, params);
        score += sumPst(white ? pos.getWhiteKing()    : pos.getBlackKing(),    Piece.King,   white, mg, params);
        return score;
    }

    /**
     * Sums material + PST for all set bits in {@code pieces}.
     * PST layout: params[IDX_PST_START + (pt-1)*128 + sq] for MG,
     *             params[IDX_PST_START + (pt-1)*128 + 64 + sq] for EG.
     * White uses raw square index; black mirrors via sq ^ 56.
     */
    private static int sumPst(long pieces, int pt, boolean white, boolean mg, double[] params) {
        double mat    = params[EvalParams.IDX_MATERIAL_START + (pt - 1) * 2 + (mg ? 0 : 1)];
        int    pstBase = EvalParams.IDX_PST_START + (pt - 1) * 128 + (mg ? 0 : 64);
        int score = 0;
        while (pieces != 0) {
            int sq    = Long.numberOfTrailingZeros(pieces);
            int pstSq = white ? sq : (sq ^ 56);
            score += (int) (mat + params[pstBase + pstSq]);
            pieces &= pieces - 1;
        }
        return score;
    }

    // =========================================================================
    // Mobility
    // =========================================================================

    private static int[] mobility(PositionData pos, boolean white, long allOcc, long enemyPawnAtk, double[] params) {
        long friendly = white ? pos.getWhiteOccupancy() : pos.getBlackOccupancy();
        long safeMask = ~friendly & ~enemyPawnAtk;
        int mgMob = 0, egMob = 0;

        mgMob += pieceMobility(white ? pos.getWhiteKnights() : pos.getBlackKnights(), Piece.Knight, allOcc, safeMask, true,  params);
        egMob += pieceMobility(white ? pos.getWhiteKnights() : pos.getBlackKnights(), Piece.Knight, allOcc, safeMask, false, params);
        mgMob += pieceMobility(white ? pos.getWhiteBishops() : pos.getBlackBishops(), Piece.Bishop, allOcc, safeMask, true,  params);
        egMob += pieceMobility(white ? pos.getWhiteBishops() : pos.getBlackBishops(), Piece.Bishop, allOcc, safeMask, false, params);
        mgMob += pieceMobility(white ? pos.getWhiteRooks()   : pos.getBlackRooks(),   Piece.Rook,   allOcc, safeMask, true,  params);
        egMob += pieceMobility(white ? pos.getWhiteRooks()   : pos.getBlackRooks(),   Piece.Rook,   allOcc, safeMask, false, params);
        mgMob += pieceMobility(white ? pos.getWhiteQueens()  : pos.getBlackQueens(),  Piece.Queen,  allOcc, safeMask, true,  params);
        egMob += pieceMobility(white ? pos.getWhiteQueens()  : pos.getBlackQueens(),  Piece.Queen,  allOcc, safeMask, false, params);

        return new int[]{ mgMob, egMob };
    }

    private static int pieceMobility(long pieces, int pt, long allOcc, long safeMask, boolean mg, double[] params) {
        int mobBase = mg ? EvalParams.IDX_MOB_MG_START : EvalParams.IDX_MOB_EG_START;
        double bonus = params[mobBase + mobilityOffset(pt)];
        int baseline = MOBILITY_BASELINE[pt];
        int total = 0;
        while (pieces != 0) {
            int sq  = Long.numberOfTrailingZeros(pieces);
            long attacks = switch (pt) {
                case Piece.Knight -> Attacks.knightAttacks(sq);
                case Piece.Bishop -> Attacks.bishopAttacks(sq, allOcc);
                case Piece.Rook   -> Attacks.rookAttacks(sq, allOcc);
                case Piece.Queen  -> Attacks.queenAttacks(sq, allOcc);
                default           -> 0L;
            };
            total += (int) ((Long.bitCount(attacks & safeMask) - baseline) * bonus);
            pieces &= pieces - 1;
        }
        return total;
    }

    /** Knight=0, Bishop=1, Rook=2, Queen=3 within the mobility param blocks. */
    private static int mobilityOffset(int pt) {
        return switch (pt) {
            case Piece.Knight -> 0;
            case Piece.Bishop -> 1;
            case Piece.Rook   -> 2;
            case Piece.Queen  -> 3;
            default           -> 0;
        };
    }

    // =========================================================================
    // Pawn structure
    // =========================================================================

    private static int[] pawnStructure(long whitePawns, long blackPawns, double[] params) {
        int mg = 0, eg = 0;

        int[] wp = passedPawnScores(whitePawns, blackPawns, true,  params);
        int[] bp = passedPawnScores(blackPawns, whitePawns, false, params);
        mg += wp[0] - bp[0];
        eg += wp[1] - bp[1];

        int wi = isolatedCount(whitePawns);
        int bi = isolatedCount(blackPawns);
        mg -= (wi - bi) * (int) params[EvalParams.IDX_ISOLATED_MG];
        eg -= (wi - bi) * (int) params[EvalParams.IDX_ISOLATED_EG];

        int wd = doubledCount(whitePawns);
        int bd = doubledCount(blackPawns);
        mg -= (wd - bd) * (int) params[EvalParams.IDX_DOUBLED_MG];
        eg -= (wd - bd) * (int) params[EvalParams.IDX_DOUBLED_EG];

        return new int[]{ mg, eg };
    }

    private static int[] passedPawnScores(long friendly, long enemy, boolean white, double[] params) {
        int mg = 0, eg = 0;
        long[] masks = white ? WHITE_PASSED_MASKS : BLACK_PASSED_MASKS;
        long temp = friendly;
        while (temp != 0) {
            int sq = Long.numberOfTrailingZeros(temp);
            if ((enemy & masks[sq]) == 0) {
                int row = sq / 8;
                int idx = white ? (7 - row) : row;
                if (idx >= 1 && idx <= 6) {
                    mg += (int) params[EvalParams.IDX_PASSED_MG_START + idx - 1];
                    eg += (int) params[EvalParams.IDX_PASSED_EG_START + idx - 1];
                }
            }
            temp &= temp - 1;
        }
        return new int[]{ mg, eg };
    }

    private static int isolatedCount(long pawns) {
        long fill = pawns;
        fill |= fill << 8;  fill |= fill << 16;  fill |= fill << 32;
        fill |= fill >>> 8; fill |= fill >>> 16; fill |= fill >>> 32;
        long adj = ((fill & NOT_A_FILE) >>> 1) | ((fill & NOT_H_FILE) << 1);
        return Long.bitCount(pawns & ~adj);
    }

    private static int doubledCount(long pawns) {
        long fill = pawns;
        fill |= fill << 8;  fill |= fill << 16;  fill |= fill << 32;
        fill |= fill >>> 8; fill |= fill >>> 16; fill |= fill >>> 32;
        return Long.bitCount(pawns) - Long.bitCount(fill & 0xFFL);
    }

    // =========================================================================
    // King safety
    // =========================================================================

    private static int kingSafety(PositionData pos, double[] params) {
        return kingSafetySide(pos, true,  params)
             - kingSafetySide(pos, false, params);
    }

    private static int kingSafetySide(PositionData pos, boolean white, double[] params) {
        long kingBb = white ? pos.getWhiteKing() : pos.getBlackKing();
        if (kingBb == 0) return 0;
        int kingSq = Long.numberOfTrailingZeros(kingBb);
        return pawnShield(pos, white, kingSq, params)
             + openFiles(pos, white, kingSq, params)
             + attackerPenalty(pos, white, kingSq, params);
    }

    private static boolean isCastled(boolean white, int kingSq) {
        return white
            ? (kingSq == WHITE_G1 || kingSq == WHITE_H1 || kingSq == WHITE_C1 || kingSq == WHITE_B1)
            : (kingSq == BLACK_G8 || kingSq == BLACK_H8 || kingSq == BLACK_C8 || kingSq == BLACK_B8);
    }

    private static int pawnShield(PositionData pos, boolean white, int kingSq, double[] params) {
        if (!isCastled(white, kingSq)) return 0;
        long friendlyPawns = white ? pos.getWhitePawns() : pos.getBlackPawns();
        int file = kingSq % 8;
        int row  = kingSq / 8;
        int r1   = white ? row - 1 : row + 1;
        int r2   = white ? row - 2 : row + 2;
        int bonus = 0;
        for (int df = -1; df <= 1; df++) {
            int f = file + df;
            if (f < 0 || f > 7) continue;
            if (r1 >= 0 && r1 < 8 && (friendlyPawns & (1L << (r1 * 8 + f))) != 0)
                bonus += (int) params[EvalParams.IDX_SHIELD_RANK2];
            if (r2 >= 0 && r2 < 8 && (friendlyPawns & (1L << (r2 * 8 + f))) != 0)
                bonus += (int) params[EvalParams.IDX_SHIELD_RANK3];
        }
        return bonus;
    }

    private static int openFiles(PositionData pos, boolean white, int kingSq, double[] params) {
        int  file     = kingSq % 8;
        long friendly = white ? pos.getWhitePawns() : pos.getBlackPawns();
        long enemy    = white ? pos.getBlackPawns() : pos.getWhitePawns();
        int penalty = 0;
        for (int df = -1; df <= 1; df++) {
            int f = file + df;
            if (f < 0 || f > 7) continue;
            long fileMask = 0x0101010101010101L << f;
            if ((friendly & fileMask) == 0) {
                penalty -= (enemy & fileMask) == 0
                    ? (int) params[EvalParams.IDX_OPEN_FILE]
                    : (int) params[EvalParams.IDX_HALF_OPEN_FILE];
            }
        }
        return penalty;
    }

    // Non-linear mapping from attacker-weight sum to centipawn penalty.
    // Must match KingSafety.SAFETY_TABLE in engine-core and PositionFeatures.SAFETY_TABLE in engine-tuner.
    static final int[] SAFETY_TABLE = {
        0, 0, 1, 2, 3, 5, 7, 9, 12, 15, 18, 22, 26, 30, 35, 40, 45, 50
    };

    private static int attackerPenalty(PositionData pos, boolean white, int kingSq, double[] params) {
        long zone   = white ? WHITE_KING_ZONE[kingSq] : BLACK_KING_ZONE[kingSq];
        long allOcc = pos.getWhiteOccupancy() | pos.getBlackOccupancy();

        long eKnights = white ? pos.getBlackKnights() : pos.getWhiteKnights();
        long eBishops = white ? pos.getBlackBishops() : pos.getWhiteBishops();
        long eRooks   = white ? pos.getBlackRooks()   : pos.getWhiteRooks();
        long eQueens  = white ? pos.getBlackQueens()  : pos.getWhiteQueens();

        int w = 0;
        w += countAttackers(eKnights, Piece.Knight, zone, allOcc) * (int) params[EvalParams.IDX_ATK_KNIGHT];
        w += countAttackers(eBishops, Piece.Bishop, zone, allOcc) * (int) params[EvalParams.IDX_ATK_BISHOP];
        w += countAttackers(eRooks,   Piece.Rook,   zone, allOcc) * (int) params[EvalParams.IDX_ATK_ROOK];
        w += countAttackers(eQueens,  Piece.Queen,  zone, allOcc) * (int) params[EvalParams.IDX_ATK_QUEEN];

        int base = w < SAFETY_TABLE.length ? SAFETY_TABLE[w] : SAFETY_TABLE[SAFETY_TABLE.length - 1];
        return -(int) (base * params[EvalParams.IDX_KING_SAFETY_SCALE] / 100);
    }

    private static int countAttackers(long pieces, int pt, long zone, long allOcc) {
        int count = 0;
        while (pieces != 0) {
            int sq = Long.numberOfTrailingZeros(pieces);
            long attacks = switch (pt) {
                case Piece.Knight -> Attacks.knightAttacks(sq);
                case Piece.Bishop -> Attacks.bishopAttacks(sq, allOcc);
                case Piece.Rook   -> Attacks.rookAttacks(sq, allOcc);
                case Piece.Queen  -> Attacks.queenAttacks(sq, allOcc);
                default           -> 0L;
            };
            if ((attacks & zone) != 0) count++;
            pieces &= pieces - 1;
        }
        return count;
    }

    // =========================================================================
    // Hanging penalty
    // =========================================================================

    /**
     * Computes the aggregate attack bitboard for one side (all piece types).
     * Used by {@link #hangingPenalty} to detect hanging pieces.
     */
    private static long computeAttackedBy(PositionData pos, boolean white, long allOcc) {
        long atk = white
                ? Attacks.whitePawnAttacks(pos.getWhitePawns())
                : Attacks.blackPawnAttacks(pos.getBlackPawns());
        long pieces;
        pieces = white ? pos.getWhiteKnights() : pos.getBlackKnights();
        while (pieces != 0) { atk |= Attacks.knightAttacks(Long.numberOfTrailingZeros(pieces)); pieces &= pieces - 1; }
        pieces = white ? pos.getWhiteBishops() : pos.getBlackBishops();
        while (pieces != 0) { atk |= Attacks.bishopAttacks(Long.numberOfTrailingZeros(pieces), allOcc); pieces &= pieces - 1; }
        pieces = white ? pos.getWhiteRooks() : pos.getBlackRooks();
        while (pieces != 0) { atk |= Attacks.rookAttacks(Long.numberOfTrailingZeros(pieces), allOcc); pieces &= pieces - 1; }
        pieces = white ? pos.getWhiteQueens() : pos.getBlackQueens();
        while (pieces != 0) { atk |= Attacks.queenAttacks(Long.numberOfTrailingZeros(pieces), allOcc); pieces &= pieces - 1; }
        long king = white ? pos.getWhiteKing() : pos.getBlackKing();
        if (king != 0) atk |= AttackTables.KING_ATTACKS[Long.numberOfTrailingZeros(king)];
        return atk;
    }

    /**
     * Hanging piece penalty from White's perspective (positive = Black has more
     * hanging pieces). Mirrors the engine-core {@code Evaluator.hangingPenalty()}
     * logic without the mating-net suppression (not needed for gradient signal).
     */
    private static int hangingPenalty(PositionData pos, double[] params) {
        long allOcc = pos.getWhiteOccupancy() | pos.getBlackOccupancy();
        long attackedByWhite = computeAttackedBy(pos, true,  allOcc);
        long attackedByBlack = computeAttackedBy(pos, false, allOcc);
        long whiteHanging = (pos.getWhiteOccupancy() & ~pos.getWhiteKing())
                          & attackedByBlack & ~attackedByWhite;
        long blackHanging = (pos.getBlackOccupancy() & ~pos.getBlackKing())
                          & attackedByWhite & ~attackedByBlack;
        return (Long.bitCount(blackHanging) - Long.bitCount(whiteHanging))
             * (int) params[EvalParams.IDX_HANGING_PENALTY];
    }

    // =========================================================================
    // Bishop pair
    // =========================================================================

    private static int[] bishopPair(PositionData pos, double[] params) {
        int mg = 0, eg = 0;
        if (Long.bitCount(pos.getWhiteBishops()) >= 2) {
            mg += (int) params[EvalParams.IDX_BISHOP_PAIR_MG];
            eg += (int) params[EvalParams.IDX_BISHOP_PAIR_EG];
        }
        if (Long.bitCount(pos.getBlackBishops()) >= 2) {
            mg -= (int) params[EvalParams.IDX_BISHOP_PAIR_MG];
            eg -= (int) params[EvalParams.IDX_BISHOP_PAIR_EG];
        }
        return new int[]{ mg, eg };
    }

    // =========================================================================
    // Rook on 7th rank
    // =========================================================================

    /** Rank 7 for White = row 1 (a8=0), rank 7 for Black = row 6 (a8=0). */
    private static final long WHITE_RANK_7 = 0x000000000000FF00L;
    private static final long BLACK_RANK_7 = 0x00FF000000000000L;

    private static int[] rookOnSeventh(PositionData pos, double[] params) {
        int mg = 0, eg = 0;
        int wCount = Long.bitCount(pos.getWhiteRooks() & WHITE_RANK_7);
        int bCount = Long.bitCount(pos.getBlackRooks() & BLACK_RANK_7);
        mg += (wCount - bCount) * (int) params[EvalParams.IDX_ROOK_7TH_MG];
        eg += (wCount - bCount) * (int) params[EvalParams.IDX_ROOK_7TH_EG];
        return new int[]{ mg, eg };
    }

    private static final long FILE_MASK_BASE = 0x0101010101010101L;

    private static int[] rookOpenFile(PositionData pos, double[] params) {
        int mg = 0, eg = 0;
        int bonusOpenMg   = (int) params[EvalParams.IDX_ROOK_OPEN_FILE_MG];
        int bonusOpenEg   = (int) params[EvalParams.IDX_ROOK_OPEN_FILE_EG];
        int bonusSemiMg   = (int) params[EvalParams.IDX_ROOK_SEMI_OPEN_MG];
        int bonusSemiEg   = (int) params[EvalParams.IDX_ROOK_SEMI_OPEN_EG];

        // White rooks
        long temp = pos.getWhiteRooks();
        while (temp != 0) {
            int sq = Long.numberOfTrailingZeros(temp);
            long fileMask = FILE_MASK_BASE << (sq % 8);
            if ((pos.getWhitePawns() & fileMask) == 0) {
                if ((pos.getBlackPawns() & fileMask) == 0) {
                    mg += bonusOpenMg; eg += bonusOpenEg;
                } else {
                    mg += bonusSemiMg; eg += bonusSemiEg;
                }
            }
            temp &= temp - 1;
        }
        // Black rooks (negate since score is from White's perspective)
        temp = pos.getBlackRooks();
        while (temp != 0) {
            int sq = Long.numberOfTrailingZeros(temp);
            long fileMask = FILE_MASK_BASE << (sq % 8);
            if ((pos.getBlackPawns() & fileMask) == 0) {
                if ((pos.getWhitePawns() & fileMask) == 0) {
                    mg -= bonusOpenMg; eg -= bonusOpenEg;
                } else {
                    mg -= bonusSemiMg; eg -= bonusSemiEg;
                }
            }
            temp &= temp - 1;
        }
        return new int[]{ mg, eg };
    }

    // White outpost zone: ranks 4-6 (rows 2-4 in a8=0, bits 16-39)
    // Black outpost zone: ranks 3-5 (rows 3-5 in a8=0, bits 24-47)
    private static final long WHITE_OUTPOST_ZONE = 0x000000FFFFFF0000L;
    private static final long BLACK_OUTPOST_ZONE = 0x0000FFFFFF000000L;

    private static int[] knightOutpost(PositionData pos, double[] params) {
        long whitePawnAtk = Attacks.whitePawnAttacks(pos.getWhitePawns());
        long blackPawnAtk = Attacks.blackPawnAttacks(pos.getBlackPawns());
        int wOut = Long.bitCount(pos.getWhiteKnights() & WHITE_OUTPOST_ZONE & ~blackPawnAtk);
        int bOut = Long.bitCount(pos.getBlackKnights() & BLACK_OUTPOST_ZONE & ~whitePawnAtk);
        int net = wOut - bOut;
        int mg = net * (int) params[EvalParams.IDX_KNIGHT_OUTPOST_MG];
        int eg = net * (int) params[EvalParams.IDX_KNIGHT_OUTPOST_EG];
        return new int[]{ mg, eg };
    }

    private static int[] connectedPawn(PositionData pos, double[] params) {
        int wConn = connectedPawnCount(pos.getWhitePawns());
        int bConn = connectedPawnCount(pos.getBlackPawns());
        int net = wConn - bConn;
        return new int[]{ net * (int) params[EvalParams.IDX_CONNECTED_PAWN_MG],
                          net * (int) params[EvalParams.IDX_CONNECTED_PAWN_EG] };
    }

    private static int connectedPawnCount(long pawns) {
        long attacks = ((pawns & NOT_A_FILE) >>> 1) | ((pawns & NOT_H_FILE) << 1);
        attacks |= ((pawns & NOT_A_FILE) >>> 9) | ((pawns & NOT_H_FILE) << 7)
                 | ((pawns & NOT_A_FILE) << 7)  | ((pawns & NOT_H_FILE) >>> 9);
        return Long.bitCount(pawns & attacks);
    }

    private static int[] backwardPawn(PositionData pos, double[] params) {
        int wBack = backwardPawnCount(pos.getWhitePawns(), pos.getBlackPawns(), true);
        int bBack = backwardPawnCount(pos.getBlackPawns(), pos.getWhitePawns(), false);
        // net = wBack - bBack; caller subtracts this (penalty)
        int net = wBack - bBack;
        return new int[]{ net * (int) params[EvalParams.IDX_BACKWARD_PAWN_MG],
                          net * (int) params[EvalParams.IDX_BACKWARD_PAWN_EG] };
    }

    private static final long FILE_MASK_BASE_TE = 0x0101010101010101L;

    private static int backwardPawnCount(long friendly, long enemy, boolean white) {
        long enemyAtk = white ? Attacks.blackPawnAttacks(enemy) : Attacks.whitePawnAttacks(enemy);
        long temp = friendly;
        int count = 0;
        while (temp != 0) {
            int sq = Long.numberOfTrailingZeros(temp);
            int file = sq % 8;
            long adjFiles = 0L;
            if (file > 0) adjFiles |= FILE_MASK_BASE_TE << (file - 1);
            if (file < 7) adjFiles |= FILE_MASK_BASE_TE << (file + 1);
            long adjFriendly = friendly & adjFiles;
            if (white) {
                long ahead = adjFriendly & ((1L << sq) - 1);
                int stopSq = sq - 8;
                if (stopSq >= 0 && ahead == 0 && (enemyAtk & (1L << stopSq)) != 0) count++;
            } else {
                long ahead = adjFriendly & ~((1L << (sq + 8)) - 1);
                int stopSq = sq + 8;
                if (stopSq < 64 && ahead == 0 && (enemyAtk & (1L << stopSq)) != 0) count++;
            }
            temp &= temp - 1;
        }
        return count;
    }

    private static int[] rookBehindPasser(PositionData pos, double[] params) {
        int mg = 0, eg = 0;
        // White rooks behind white passed pawns (rook on same file, lower row in a8=0 = behind)
        long wRooks = pos.getWhiteRooks();
        long wPawns = pos.getWhitePawns();
        long bPawns = pos.getBlackPawns();
        long temp = wRooks;
        while (temp != 0) {
            int sq = Long.numberOfTrailingZeros(temp);
            long fileMask = FILE_MASK_BASE_TE << (sq % 8);
            long passedOnFile = wPawns & fileMask & ((1L << sq) - 1);
            while (passedOnFile != 0) {
                int psq = Long.numberOfTrailingZeros(passedOnFile);
                if ((bPawns & fileMask & ((1L << psq) - 1)) == 0) {
                    mg += (int) params[EvalParams.IDX_ROOK_BEHIND_PASSER_MG];
                    eg += (int) params[EvalParams.IDX_ROOK_BEHIND_PASSER_EG];
                }
                passedOnFile &= passedOnFile - 1;
            }
            temp &= temp - 1;
        }
        // Black rooks behind black passed pawns
        long bRooks = pos.getBlackRooks();
        temp = bRooks;
        while (temp != 0) {
            int sq = Long.numberOfTrailingZeros(temp);
            long fileMask = FILE_MASK_BASE_TE << (sq % 8);
            long passedOnFile = bPawns & fileMask & ~((1L << sq) - 1) & ~(1L << sq);
            while (passedOnFile != 0) {
                int psq = Long.numberOfTrailingZeros(passedOnFile);
                if ((wPawns & fileMask & ~((1L << psq) - 1) & ~(1L << psq)) == 0) {
                    mg -= (int) params[EvalParams.IDX_ROOK_BEHIND_PASSER_MG];
                    eg -= (int) params[EvalParams.IDX_ROOK_BEHIND_PASSER_EG];
                }
                passedOnFile &= passedOnFile - 1;
            }
            temp &= temp - 1;
        }
        return new int[]{ mg, eg };
    }

    // =========================================================================
    // Phase
    // =========================================================================

    private static int computePhase(PositionData pos) {
        int phase = 0;
        phase += Long.bitCount(pos.getWhiteKnights() | pos.getBlackKnights()) * PHASE_WEIGHT[Piece.Knight];
        phase += Long.bitCount(pos.getWhiteBishops() | pos.getBlackBishops()) * PHASE_WEIGHT[Piece.Bishop];
        phase += Long.bitCount(pos.getWhiteRooks()   | pos.getBlackRooks())   * PHASE_WEIGHT[Piece.Rook];
        phase += Long.bitCount(pos.getWhiteQueens()  | pos.getBlackQueens())  * PHASE_WEIGHT[Piece.Queen];
        return Math.min(phase, TOTAL_PHASE);
    }

    // =========================================================================
    // Precomputed feature vector construction (for analytical gradient)
    // =========================================================================

    /**
     * Builds a {@link PositionFeatures} from a position, precomputing all
     * phase-tapered coefficients for analytical gradient computation.
     *
     * <p>Each bitboard operation runs exactly once here at load time; during
     * subsequent Adam iterations the eval is computed via a cheap dot product.
     */
    static PositionFeatures buildFeatures(PositionData pos, float outcome) {
        int phase     = computePhase(pos);
        float mgTaper = (float) phase / TOTAL_PHASE;
        float egTaper = 1.0f - mgTaper;

        float[] tmp = new float[EvalParams.TOTAL_PARAMS];

        // 1. Material + PST contributions (tapered)
        addMaterialPstFeatures(pos, tmp, mgTaper, egTaper);

        // 2. Mobility bonus per piece type (tapered)
        addMobilityFeatures(pos, tmp, mgTaper, egTaper);

        // 3. Pawn structure (tapered)
        addPawnStructureFeatures(pos, tmp, mgTaper, egTaper);

        // 4. King safety – linear parts only (MG only, then tapered by mgTaper).
        //    Returns 8 king-zone attacker counts for the non-linear penalty.
        int[] atkCounts = addKingSafetyLinearFeatures(pos, tmp, mgTaper);

        // 5. Bishop pair bonus (tapered)
        addBishopPairFeatures(pos, tmp, mgTaper, egTaper);

        // 6. Rook on 7th rank bonus (tapered)
        addRookOnSeventhFeatures(pos, tmp, mgTaper, egTaper);

        // 7. Rook on open / semi-open file bonus (tapered)
        addRookOpenFileFeatures(pos, tmp, mgTaper, egTaper);

        // 8. Knight outpost bonus (tapered)
        addKnightOutpostFeatures(pos, tmp, mgTaper, egTaper);

        // 9. Connected pawn bonus (tapered)
        addConnectedPawnFeatures(pos, tmp, mgTaper, egTaper);

        // 10. Backward pawn penalty (tapered)
        addBackwardPawnFeatures(pos, tmp, mgTaper, egTaper);

        // 11. Rook behind passed pawn bonus (tapered)
        addRookBehindPasserFeatures(pos, tmp, mgTaper, egTaper);

        // 12. Hanging piece penalty (not tapered — applied after phase interpolation)
        addHangingPenaltyFeatures(pos, tmp);

        // 13. Tempo bonus (not tapered – applied after phase interpolation)
        tmp[EvalParams.IDX_TEMPO] = Piece.isWhite(pos.getActiveColor()) ? 1.0f : -1.0f;

        // 14. Convert dense tmp[] to sparse (indices + weights), skipping zeros.
        int nnz = 0;
        for (int i = 0; i < EvalParams.TOTAL_PARAMS; i++) {
            if (tmp[i] != 0.0f) nnz++;
        }
        short[] indices = new short[nnz];
        float[] weights = new float[nnz];
        int k = 0;
        for (int i = 0; i < EvalParams.TOTAL_PARAMS; i++) {
            if (tmp[i] != 0.0f) {
                indices[k] = (short) i;
                weights[k] = tmp[i];
                k++;
            }
        }

        return new PositionFeatures(outcome, phase, indices, weights,
                (short) atkCounts[0], (short) atkCounts[1],
                (short) atkCounts[2], (short) atkCounts[3],
                (short) atkCounts[4], (short) atkCounts[5],
                (short) atkCounts[6], (short) atkCounts[7]);
    }

    /** Pieces bitboard accessor by type and color (feature extraction helper). */
    private static long getPieces(PositionData pos, int pt, boolean white) {
        return switch (pt) {
            case Piece.Pawn   -> white ? pos.getWhitePawns()   : pos.getBlackPawns();
            case Piece.Knight -> white ? pos.getWhiteKnights() : pos.getBlackKnights();
            case Piece.Bishop -> white ? pos.getWhiteBishops() : pos.getBlackBishops();
            case Piece.Rook   -> white ? pos.getWhiteRooks()   : pos.getBlackRooks();
            case Piece.Queen  -> white ? pos.getWhiteQueens()  : pos.getBlackQueens();
            case Piece.King   -> white ? pos.getWhiteKing()    : pos.getBlackKing();
            default           -> 0L;
        };
    }

    /**
     * Accumulates material and PST feature weights into {@code tmp[]}. White
     * pieces contribute positively; black pieces negatively (mirrored square).
     */
    private static void addMaterialPstFeatures(PositionData pos, float[] tmp,
                                                float mgTaper, float egTaper) {
        for (int pt = Piece.Pawn; pt <= Piece.King; pt++) {
            int matMgIdx = EvalParams.IDX_MATERIAL_START + (pt - 1) * 2;
            int matEgIdx = matMgIdx + 1;
            int pstMgBase = EvalParams.IDX_PST_START + (pt - 1) * 128;
            int pstEgBase = pstMgBase + 64;

            long wPieces = getPieces(pos, pt, true);
            while (wPieces != 0) {
                int sq = Long.numberOfTrailingZeros(wPieces);
                tmp[matMgIdx]       += mgTaper;
                tmp[matEgIdx]       += egTaper;
                tmp[pstMgBase + sq] += mgTaper;
                tmp[pstEgBase + sq] += egTaper;
                wPieces &= wPieces - 1;
            }

            long bPieces = getPieces(pos, pt, false);
            while (bPieces != 0) {
                int sq    = Long.numberOfTrailingZeros(bPieces);
                int pstSq = sq ^ 56;  // mirror rank for black
                tmp[matMgIdx]          -= mgTaper;
                tmp[matEgIdx]          -= egTaper;
                tmp[pstMgBase + pstSq] -= mgTaper;
                tmp[pstEgBase + pstSq] -= egTaper;
                bPieces &= bPieces - 1;
            }
        }
    }

    /**
     * Accumulates mobility feature weights. Safe-move count above baseline,
     * net white minus black, tapered by MG/EG.
     */
    private static void addMobilityFeatures(PositionData pos, float[] tmp,
                                             float mgTaper, float egTaper) {
        long allOcc      = pos.getWhiteOccupancy() | pos.getBlackOccupancy();
        long whitePawnAtk = Attacks.whitePawnAttacks(pos.getWhitePawns());
        long blackPawnAtk = Attacks.blackPawnAttacks(pos.getBlackPawns());
        long whiteSafe   = ~pos.getWhiteOccupancy() & ~blackPawnAtk;
        long blackSafe   = ~pos.getBlackOccupancy() & ~whitePawnAtk;

        for (int pt : new int[]{ Piece.Knight, Piece.Bishop, Piece.Rook, Piece.Queen }) {
            int baseline = MOBILITY_BASELINE[pt];
            int wMob = mobilityTotal(getPieces(pos, pt, true),  pt, allOcc, whiteSafe, baseline);
            int bMob = mobilityTotal(getPieces(pos, pt, false), pt, allOcc, blackSafe, baseline);
            int net  = wMob - bMob;
            int off  = mobilityOffset(pt);
            tmp[EvalParams.IDX_MOB_MG_START + off] += net * mgTaper;
            tmp[EvalParams.IDX_MOB_EG_START + off] += net * egTaper;
        }
    }

    /**
     * Computes total safe-move count above baseline for all pieces of a given
     * type (identical logic to {@link #pieceMobility} but returns the raw count
     * rather than multiplying by a param).
     */
    private static int mobilityTotal(long pieces, int pt, long allOcc,
                                     long safeMask, int baseline) {
        int total = 0;
        while (pieces != 0) {
            int sq = Long.numberOfTrailingZeros(pieces);
            long atk = switch (pt) {
                case Piece.Knight -> Attacks.knightAttacks(sq);
                case Piece.Bishop -> Attacks.bishopAttacks(sq, allOcc);
                case Piece.Rook   -> Attacks.rookAttacks(sq, allOcc);
                case Piece.Queen  -> Attacks.queenAttacks(sq, allOcc);
                default           -> 0L;
            };
            total += Long.bitCount(atk & safeMask) - baseline;
            pieces &= pieces - 1;
        }
        return total;
    }

    /** Accumulates pawn structure feature weights (passed, isolated, doubled). */
    private static void addPawnStructureFeatures(PositionData pos, float[] tmp,
                                                  float mgTaper, float egTaper) {
        long wPawns = pos.getWhitePawns();
        long bPawns = pos.getBlackPawns();

        // Passed pawn bonuses per rank (white and black processed separately)
        addPassedPawnFeatures(wPawns, bPawns, true,  tmp, mgTaper, egTaper);
        addPassedPawnFeatures(bPawns, wPawns, false, tmp, mgTaper, egTaper);

        // Isolated pawn penalty (net: white count minus black count)
        int netIso = isolatedCount(wPawns) - isolatedCount(bPawns);
        tmp[EvalParams.IDX_ISOLATED_MG] -= netIso * mgTaper;
        tmp[EvalParams.IDX_ISOLATED_EG] -= netIso * egTaper;

        // Doubled pawn penalty
        int netDbl = doubledCount(wPawns) - doubledCount(bPawns);
        tmp[EvalParams.IDX_DOUBLED_MG] -= netDbl * mgTaper;
        tmp[EvalParams.IDX_DOUBLED_EG] -= netDbl * egTaper;
    }

    /**
     * Accumulates passed-pawn rank bonus features for one side.
     * {@code sign} is +1 for white (adds), −1 for black (subtracts).
     */
    private static void addPassedPawnFeatures(long friendly, long enemy, boolean white,
                                               float[] tmp, float mgTaper, float egTaper) {
        long[] masks = white ? WHITE_PASSED_MASKS : BLACK_PASSED_MASKS;
        float sign   = white ? 1.0f : -1.0f;
        long temp = friendly;
        while (temp != 0) {
            int sq = Long.numberOfTrailingZeros(temp);
            if ((enemy & masks[sq]) == 0) {
                int row = sq / 8;
                int idx = white ? (7 - row) : row;
                if (idx >= 1 && idx <= 6) {
                    tmp[EvalParams.IDX_PASSED_MG_START + idx - 1] += sign * mgTaper;
                    tmp[EvalParams.IDX_PASSED_EG_START + idx - 1] += sign * egTaper;
                }
            }
            temp &= temp - 1;
        }
    }

    /**
     * Accumulates the LINEAR king-safety feature weights (pawn shield and open
     * files) and returns the eight king-zone attacker counts needed for the
     * non-linear attacker-penalty term.
     *
     * <p>The shield/open-file terms go into mgScore (then tapered) – multiply
     * by {@code mgTaper}. The attacker counts are stored in
     * {@link PositionFeatures#wN}…{@link PositionFeatures#bQ} for
     * per-iteration non-linear handling.
     *
     * @return int[8]: {wN, wB, wR, wQ, bN, bB, bR, bQ}
     *         where wX = black X-pieces attacking White king zone,
     *               bX = white X-pieces attacking Black king zone.
     */
    private static int[] addKingSafetyLinearFeatures(PositionData pos, float[] tmp,
                                                      float mgTaper) {
        long allOcc = pos.getWhiteOccupancy() | pos.getBlackOccupancy();

        int wShield2 = 0, wShield3 = 0, wOpen = 0, wHalfOpen = 0;
        int wN_ = 0, wB_ = 0, wR_ = 0, wQ_ = 0;

        long wKingBb = pos.getWhiteKing();
        if (wKingBb != 0) {
            int kSq = Long.numberOfTrailingZeros(wKingBb);
            if (isCastled(true, kSq)) {
                int[] sc = pawnShieldCounts(pos.getWhitePawns(), true, kSq);
                wShield2 = sc[0];
                wShield3 = sc[1];
            }
            int[] of = openFileCounts(pos.getWhitePawns(), pos.getBlackPawns(), kSq);
            wOpen     = of[0];
            wHalfOpen = of[1];
            long zone = WHITE_KING_ZONE[kSq];
            wN_ = countAttackers(pos.getBlackKnights(), Piece.Knight, zone, allOcc);
            wB_ = countAttackers(pos.getBlackBishops(), Piece.Bishop, zone, allOcc);
            wR_ = countAttackers(pos.getBlackRooks(),   Piece.Rook,   zone, allOcc);
            wQ_ = countAttackers(pos.getBlackQueens(),  Piece.Queen,  zone, allOcc);
        }

        int bShield2 = 0, bShield3 = 0, bOpen = 0, bHalfOpen = 0;
        int bN_ = 0, bB_ = 0, bR_ = 0, bQ_ = 0;

        long bKingBb = pos.getBlackKing();
        if (bKingBb != 0) {
            int kSq = Long.numberOfTrailingZeros(bKingBb);
            if (isCastled(false, kSq)) {
                int[] sc = pawnShieldCounts(pos.getBlackPawns(), false, kSq);
                bShield2 = sc[0];
                bShield3 = sc[1];
            }
            int[] of = openFileCounts(pos.getBlackPawns(), pos.getWhitePawns(), kSq);
            bOpen     = of[0];
            bHalfOpen = of[1];
            long zone = BLACK_KING_ZONE[kSq];
            bN_ = countAttackers(pos.getWhiteKnights(), Piece.Knight, zone, allOcc);
            bB_ = countAttackers(pos.getWhiteBishops(), Piece.Bishop, zone, allOcc);
            bR_ = countAttackers(pos.getWhiteRooks(),   Piece.Rook,   zone, allOcc);
            bQ_ = countAttackers(pos.getWhiteQueens(),  Piece.Queen,  zone, allOcc);
        }

        // kingSafety = white_side − black_side, goes into mgScore → scale by mgTaper.
        // Shield bonus: positive means white side better protected.
        tmp[EvalParams.IDX_SHIELD_RANK2]   += (wShield2 - bShield2) * mgTaper;
        tmp[EvalParams.IDX_SHIELD_RANK3]   += (wShield3 - bShield3) * mgTaper;
        // Open-file: openFiles(white) = -(wOpen*OPEN + wHalfOpen*HALF);
        // net kingSafety = -(wOpen-bOpen)*OPEN - (wHalfOpen-bHalfOpen)*HALF
        tmp[EvalParams.IDX_OPEN_FILE]      += -(wOpen     - bOpen)     * mgTaper;
        tmp[EvalParams.IDX_HALF_OPEN_FILE] += -(wHalfOpen - bHalfOpen) * mgTaper;

        return new int[]{ wN_, wB_, wR_, wQ_, bN_, bB_, bR_, bQ_ };
    }

    /** Counts pawns in the two rank-layer shield in front of the king (castled only). */
    private static int[] pawnShieldCounts(long friendlyPawns, boolean white, int kSq) {
        int file = kSq % 8;
        int row  = kSq / 8;
        int r1   = white ? row - 1 : row + 1;
        int r2   = white ? row - 2 : row + 2;
        int rank2 = 0, rank3 = 0;
        for (int df = -1; df <= 1; df++) {
            int f = file + df;
            if (f < 0 || f > 7) continue;
            if (r1 >= 0 && r1 < 8 && (friendlyPawns & (1L << (r1 * 8 + f))) != 0) rank2++;
            if (r2 >= 0 && r2 < 8 && (friendlyPawns & (1L << (r2 * 8 + f))) != 0) rank3++;
        }
        return new int[]{ rank2, rank3 };
    }

    /**
     * Counts open and half-open files around the given king square.
     * An "open" file has no friendly pawn; a "half-open" file has no friendly
     * pawn but has an enemy pawn.
     */
    private static int[] openFileCounts(long friendlyPawns, long enemyPawns, int kSq) {
        int file = kSq % 8;
        int openCount = 0, halfOpenCount = 0;
        for (int df = -1; df <= 1; df++) {
            int f = file + df;
            if (f < 0 || f > 7) continue;
            long fileMask = 0x0101010101010101L << f;
            if ((friendlyPawns & fileMask) == 0) {
                if ((enemyPawns & fileMask) == 0) openCount++;
                else                              halfOpenCount++;
            }
        }
        return new int[]{ openCount, halfOpenCount };
    }

    /** Accumulates bishop-pair feature weights (tapered). */
    private static void addBishopPairFeatures(PositionData pos, float[] tmp,
                                               float mgTaper, float egTaper) {
        int net = 0;
        if (Long.bitCount(pos.getWhiteBishops()) >= 2) net++;
        if (Long.bitCount(pos.getBlackBishops()) >= 2) net--;
        if (net != 0) {
            tmp[EvalParams.IDX_BISHOP_PAIR_MG] += net * mgTaper;
            tmp[EvalParams.IDX_BISHOP_PAIR_EG] += net * egTaper;
        }
    }

    /** Accumulates rook-on-7th-rank feature weights (tapered). */
    private static void addRookOnSeventhFeatures(PositionData pos, float[] tmp,
                                                  float mgTaper, float egTaper) {
        int net = Long.bitCount(pos.getWhiteRooks() & WHITE_RANK_7)
                - Long.bitCount(pos.getBlackRooks() & BLACK_RANK_7);
        if (net != 0) {
            tmp[EvalParams.IDX_ROOK_7TH_MG] += net * mgTaper;
            tmp[EvalParams.IDX_ROOK_7TH_EG] += net * egTaper;
        }
    }

    /** Accumulates rook on open/semi-open file feature weights (tapered). */
    private static void addRookOpenFileFeatures(PositionData pos, float[] tmp,
                                                 float mgTaper, float egTaper) {
        // White rooks
        long temp = pos.getWhiteRooks();
        while (temp != 0) {
            int sq = Long.numberOfTrailingZeros(temp);
            long fileMask = FILE_MASK_BASE << (sq % 8);
            if ((pos.getWhitePawns() & fileMask) == 0) {
                if ((pos.getBlackPawns() & fileMask) == 0) {
                    tmp[EvalParams.IDX_ROOK_OPEN_FILE_MG] += mgTaper;
                    tmp[EvalParams.IDX_ROOK_OPEN_FILE_EG] += egTaper;
                } else {
                    tmp[EvalParams.IDX_ROOK_SEMI_OPEN_MG] += mgTaper;
                    tmp[EvalParams.IDX_ROOK_SEMI_OPEN_EG] += egTaper;
                }
            }
            temp &= temp - 1;
        }
        // Black rooks (negate — score is from White's perspective)
        temp = pos.getBlackRooks();
        while (temp != 0) {
            int sq = Long.numberOfTrailingZeros(temp);
            long fileMask = FILE_MASK_BASE << (sq % 8);
            if ((pos.getBlackPawns() & fileMask) == 0) {
                if ((pos.getWhitePawns() & fileMask) == 0) {
                    tmp[EvalParams.IDX_ROOK_OPEN_FILE_MG] -= mgTaper;
                    tmp[EvalParams.IDX_ROOK_OPEN_FILE_EG] -= egTaper;
                } else {
                    tmp[EvalParams.IDX_ROOK_SEMI_OPEN_MG] -= mgTaper;
                    tmp[EvalParams.IDX_ROOK_SEMI_OPEN_EG] -= egTaper;
                }
            }
            temp &= temp - 1;
        }
    }

    /** Accumulates knight outpost feature weights (tapered). */
    private static void addKnightOutpostFeatures(PositionData pos, float[] tmp,
                                                  float mgTaper, float egTaper) {
        long whitePawnAtk = Attacks.whitePawnAttacks(pos.getWhitePawns());
        long blackPawnAtk = Attacks.blackPawnAttacks(pos.getBlackPawns());
        int wOut = Long.bitCount(pos.getWhiteKnights() & WHITE_OUTPOST_ZONE & ~blackPawnAtk);
        int bOut = Long.bitCount(pos.getBlackKnights() & BLACK_OUTPOST_ZONE & ~whitePawnAtk);
        int net = wOut - bOut;
        if (net != 0) {
            tmp[EvalParams.IDX_KNIGHT_OUTPOST_MG] += net * mgTaper;
            tmp[EvalParams.IDX_KNIGHT_OUTPOST_EG] += net * egTaper;
        }
    }

    /** Accumulates connected pawn feature weights (tapered). */
    private static void addConnectedPawnFeatures(PositionData pos, float[] tmp,
                                                  float mgTaper, float egTaper) {
        int net = connectedPawnCount(pos.getWhitePawns()) - connectedPawnCount(pos.getBlackPawns());
        if (net != 0) {
            tmp[EvalParams.IDX_CONNECTED_PAWN_MG] += net * mgTaper;
            tmp[EvalParams.IDX_CONNECTED_PAWN_EG] += net * egTaper;
        }
    }

    /** Accumulates backward pawn penalty feature weights (tapered). */
    private static void addBackwardPawnFeatures(PositionData pos, float[] tmp,
                                                 float mgTaper, float egTaper) {
        int net = backwardPawnCount(pos.getWhitePawns(), pos.getBlackPawns(), true)
                - backwardPawnCount(pos.getBlackPawns(), pos.getWhitePawns(), false);
        if (net != 0) {
            // backward pawn is a penalty — subtract from white's perspective
            tmp[EvalParams.IDX_BACKWARD_PAWN_MG] -= net * mgTaper;
            tmp[EvalParams.IDX_BACKWARD_PAWN_EG] -= net * egTaper;
        }
    }

    /** Accumulates rook-behind-passed-pawn feature weights (tapered). */
    private static void addRookBehindPasserFeatures(PositionData pos, float[] tmp,
                                                     float mgTaper, float egTaper) {
        long wRooks = pos.getWhiteRooks();
        long wPawns = pos.getWhitePawns();
        long bPawns = pos.getBlackPawns();
        long bRooks = pos.getBlackRooks();
        // White rooks behind white passed pawns
        long temp = wRooks;
        while (temp != 0) {
            int sq = Long.numberOfTrailingZeros(temp);
            long fileMask = FILE_MASK_BASE_TE << (sq % 8);
            long passedOnFile = wPawns & fileMask & ((1L << sq) - 1);
            while (passedOnFile != 0) {
                int psq = Long.numberOfTrailingZeros(passedOnFile);
                if ((bPawns & fileMask & ((1L << psq) - 1)) == 0) {
                    tmp[EvalParams.IDX_ROOK_BEHIND_PASSER_MG] += mgTaper;
                    tmp[EvalParams.IDX_ROOK_BEHIND_PASSER_EG] += egTaper;
                }
                passedOnFile &= passedOnFile - 1;
            }
            temp &= temp - 1;
        }
        // Black rooks behind black passed pawns (negate)
        temp = bRooks;
        while (temp != 0) {
            int sq = Long.numberOfTrailingZeros(temp);
            long fileMask = FILE_MASK_BASE_TE << (sq % 8);
            long passedOnFile = bPawns & fileMask & ~((1L << sq) - 1) & ~(1L << sq);
            while (passedOnFile != 0) {
                int psq = Long.numberOfTrailingZeros(passedOnFile);
                if ((wPawns & fileMask & ~((1L << psq) - 1) & ~(1L << psq)) == 0) {
                    tmp[EvalParams.IDX_ROOK_BEHIND_PASSER_MG] -= mgTaper;
                    tmp[EvalParams.IDX_ROOK_BEHIND_PASSER_EG] -= egTaper;
                }
                passedOnFile &= passedOnFile - 1;
            }
            temp &= temp - 1;
        }
    }

    /**
     * Accumulates hanging-penalty feature weight (not tapered — applied after
     * phase interpolation, like tempo). The coefficient is the net hanging count
     * (positive = Black has more hanging pieces).
     */
    private static void addHangingPenaltyFeatures(PositionData pos, float[] tmp) {
        long allOcc = pos.getWhiteOccupancy() | pos.getBlackOccupancy();
        long attackedByWhite = computeAttackedBy(pos, true,  allOcc);
        long attackedByBlack = computeAttackedBy(pos, false, allOcc);
        long whiteHanging = (pos.getWhiteOccupancy() & ~pos.getWhiteKing())
                          & attackedByBlack & ~attackedByWhite;
        long blackHanging = (pos.getBlackOccupancy() & ~pos.getBlackKing())
                          & attackedByWhite & ~attackedByBlack;
        int net = Long.bitCount(blackHanging) - Long.bitCount(whiteHanging);
        if (net != 0) {
            tmp[EvalParams.IDX_HANGING_PENALTY] = net;
        }
    }

    // =========================================================================
    // Feature-based MSE (static-eval, no qsearch; used for per-iteration monitoring)
    // =========================================================================

    /**
     * Mean squared error over all positions using precomputed feature vectors.
     *
     * <p>This is faster than {@link #computeMse(List, double[], double)} because
     * each eval is a dot product rather than a full static-eval call, and no
     * Board allocations occur. Used for per-iteration convergence monitoring
     * and K recalibration during Adam.
     */
    public static double computeMseFromFeatures(List<PositionFeatures> features, double[] params, double k) {
        return features.parallelStream()
                .mapToDouble(pf -> {
                    double sig = sigmoid(pf.eval(params), k);
                    double err = pf.outcome - sig;
                    return err * err;
                })
                .average()
                .orElse(0.0);
    }
}
