package coeusyk.game.chess.tuner;

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

    private TunerEvaluator() {}

    // =========================================================================
    // Public API
    // =========================================================================

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
     * Evaluates {@code board} from White's perspective using the given params.
     * Pure static eval — no qsearch. Called by {@link TunerQuiescence} at leaf nodes.
     *
     * @param board  position to evaluate
     * @param params parameter array of length {@link EvalParams#TOTAL_PARAMS}
     * @return centipawn score (positive = White advantage)
     */
    static int evaluateStatic(Board board, double[] params) {
        int mgScore = 0;
        int egScore = 0;

        mgScore += materialAndPst(board, true,  true,  params)
                 - materialAndPst(board, false, true,  params);
        egScore += materialAndPst(board, true,  false, params)
                 - materialAndPst(board, false, false, params);

        long allOcc       = board.getWhiteOccupancy() | board.getBlackOccupancy();
        long whitePawnAtk = Attacks.whitePawnAttacks(board.getWhitePawns());
        long blackPawnAtk = Attacks.blackPawnAttacks(board.getBlackPawns());

        int[] whiteMob = mobility(board, true,  allOcc, blackPawnAtk, params);
        int[] blackMob = mobility(board, false, allOcc, whitePawnAtk, params);
        mgScore += whiteMob[0] - blackMob[0];
        egScore += whiteMob[1] - blackMob[1];

        int[] pawn = pawnStructure(board.getWhitePawns(), board.getBlackPawns(), params);
        mgScore += pawn[0];
        egScore += pawn[1];

        mgScore += kingSafety(board, params);

        int phase = computePhase(board);
        return (mgScore * phase + egScore * (TOTAL_PHASE - phase)) / TOTAL_PHASE;
    }

    /**
     * Mean squared error over all positions using the Texel sigmoid.
     * Runs in parallel for throughput.
     */
    public static double computeMse(List<LabelledPosition> positions, double[] params, double k) {
        return positions.parallelStream()
                .mapToDouble(lp -> {
                    double sig = sigmoid(evaluate(lp.board(), params), k);
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

    private static int materialAndPst(Board board, boolean white, boolean mg, double[] params) {
        int score = 0;
        score += sumPst(white ? board.getWhitePawns()   : board.getBlackPawns(),   Piece.Pawn,   white, mg, params);
        score += sumPst(white ? board.getWhiteKnights() : board.getBlackKnights(), Piece.Knight, white, mg, params);
        score += sumPst(white ? board.getWhiteBishops() : board.getBlackBishops(), Piece.Bishop, white, mg, params);
        score += sumPst(white ? board.getWhiteRooks()   : board.getBlackRooks(),   Piece.Rook,   white, mg, params);
        score += sumPst(white ? board.getWhiteQueens()  : board.getBlackQueens(),  Piece.Queen,  white, mg, params);
        score += sumPst(white ? board.getWhiteKing()    : board.getBlackKing(),    Piece.King,   white, mg, params);
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

    private static int[] mobility(Board board, boolean white, long allOcc, long enemyPawnAtk, double[] params) {
        long friendly = white ? board.getWhiteOccupancy() : board.getBlackOccupancy();
        long safeMask = ~friendly & ~enemyPawnAtk;
        int mgMob = 0, egMob = 0;

        mgMob += pieceMobility(white ? board.getWhiteKnights() : board.getBlackKnights(), Piece.Knight, allOcc, safeMask, true,  params);
        egMob += pieceMobility(white ? board.getWhiteKnights() : board.getBlackKnights(), Piece.Knight, allOcc, safeMask, false, params);
        mgMob += pieceMobility(white ? board.getWhiteBishops() : board.getBlackBishops(), Piece.Bishop, allOcc, safeMask, true,  params);
        egMob += pieceMobility(white ? board.getWhiteBishops() : board.getBlackBishops(), Piece.Bishop, allOcc, safeMask, false, params);
        mgMob += pieceMobility(white ? board.getWhiteRooks()   : board.getBlackRooks(),   Piece.Rook,   allOcc, safeMask, true,  params);
        egMob += pieceMobility(white ? board.getWhiteRooks()   : board.getBlackRooks(),   Piece.Rook,   allOcc, safeMask, false, params);
        mgMob += pieceMobility(white ? board.getWhiteQueens()  : board.getBlackQueens(),  Piece.Queen,  allOcc, safeMask, true,  params);
        egMob += pieceMobility(white ? board.getWhiteQueens()  : board.getBlackQueens(),  Piece.Queen,  allOcc, safeMask, false, params);

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

    private static int kingSafety(Board board, double[] params) {
        return kingSafetySide(board, true,  params)
             - kingSafetySide(board, false, params);
    }

    private static int kingSafetySide(Board board, boolean white, double[] params) {
        long kingBb = white ? board.getWhiteKing() : board.getBlackKing();
        if (kingBb == 0) return 0;
        int kingSq = Long.numberOfTrailingZeros(kingBb);
        return pawnShield(board, white, kingSq, params)
             + openFiles(board, white, kingSq, params)
             + attackerPenalty(board, white, kingSq, params);
    }

    private static boolean isCastled(boolean white, int kingSq) {
        return white
            ? (kingSq == WHITE_G1 || kingSq == WHITE_H1 || kingSq == WHITE_C1 || kingSq == WHITE_B1)
            : (kingSq == BLACK_G8 || kingSq == BLACK_H8 || kingSq == BLACK_C8 || kingSq == BLACK_B8);
    }

    private static int pawnShield(Board board, boolean white, int kingSq, double[] params) {
        if (!isCastled(white, kingSq)) return 0;
        long friendlyPawns = white ? board.getWhitePawns() : board.getBlackPawns();
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

    private static int openFiles(Board board, boolean white, int kingSq, double[] params) {
        int  file     = kingSq % 8;
        long friendly = white ? board.getWhitePawns() : board.getBlackPawns();
        long enemy    = white ? board.getBlackPawns() : board.getWhitePawns();
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

    private static int attackerPenalty(Board board, boolean white, int kingSq, double[] params) {
        long zone   = white ? WHITE_KING_ZONE[kingSq] : BLACK_KING_ZONE[kingSq];
        long allOcc = board.getWhiteOccupancy() | board.getBlackOccupancy();

        long eKnights = white ? board.getBlackKnights() : board.getWhiteKnights();
        long eBishops = white ? board.getBlackBishops() : board.getWhiteBishops();
        long eRooks   = white ? board.getBlackRooks()   : board.getWhiteRooks();
        long eQueens  = white ? board.getBlackQueens()  : board.getWhiteQueens();

        int w = 0;
        w += countAttackers(eKnights, Piece.Knight, zone, allOcc) * (int) params[EvalParams.IDX_ATK_KNIGHT];
        w += countAttackers(eBishops, Piece.Bishop, zone, allOcc) * (int) params[EvalParams.IDX_ATK_BISHOP];
        w += countAttackers(eRooks,   Piece.Rook,   zone, allOcc) * (int) params[EvalParams.IDX_ATK_ROOK];
        w += countAttackers(eQueens,  Piece.Queen,  zone, allOcc) * (int) params[EvalParams.IDX_ATK_QUEEN];

        return -(w * w / 4);
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
    // Phase
    // =========================================================================

    private static int computePhase(Board board) {
        int phase = 0;
        phase += Long.bitCount(board.getWhiteKnights() | board.getBlackKnights()) * PHASE_WEIGHT[Piece.Knight];
        phase += Long.bitCount(board.getWhiteBishops() | board.getBlackBishops()) * PHASE_WEIGHT[Piece.Bishop];
        phase += Long.bitCount(board.getWhiteRooks()   | board.getBlackRooks())   * PHASE_WEIGHT[Piece.Rook];
        phase += Long.bitCount(board.getWhiteQueens()  | board.getBlackQueens())  * PHASE_WEIGHT[Piece.Queen];
        return Math.min(phase, TOTAL_PHASE);
    }
}
