package coeusyk.game.chess.tuner;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Bridge between the tuner parameter array and the live evaluator constants.
 *
 * Parameter layout (823 total):
 * <pre>
 *   [0..11]    Material MG/EG for pawn, knight, bishop, rook, queen, king
 *              (indices alternate: [2n] = MG, [2n+1] = EG)
 *              Pawn MG (index 0) is hard-pinned at 100 (min==max==100).
 *              King MG/EG (indices 10,11) are pinned at 0.
 *              All other material values float freely.
 *
 *   [12..75]   pawn MG PST (64 values)
 *   [76..139]  pawn EG PST
 *   [140..203] knight MG PST
 *   [204..267] knight EG PST
 *   [268..331] bishop MG PST
 *   [332..395] bishop EG PST
 *   [396..459] rook MG PST
 *   [460..523] rook EG PST
 *   [524..587] queen MG PST
 *   [588..651] queen EG PST
 *   [652..715] king MG PST
 *   [716..779] king EG PST
 *
 *   [780..785] passed pawn MG bonus (indices 1..6 of PASSED_MG array)
 *   [786..791] passed pawn EG bonus (indices 1..6 of PASSED_EG array)
 *   [792]      isolated pawn MG penalty
 *   [793]      isolated pawn EG penalty
 *   [794]      doubled pawn MG penalty
 *   [795]      doubled pawn EG penalty
 *
 *   [796]      king safety: pawn shield rank-2 bonus
 *   [797]      king safety: pawn shield rank-3 bonus
 *   [798]      king safety: open file penalty
 *   [799]      king safety: half-open file penalty
 *   [800]      king safety: attacker weight Knight
 *   [801]      king safety: attacker weight Bishop
 *   [802]      king safety: attacker weight Rook
 *   [803]      king safety: attacker weight Queen
 *
 *   [804]      mobility MG bonus/sq Knight
 *   [805]      mobility MG bonus/sq Bishop
 *   [806]      mobility MG bonus/sq Rook
 *   [807]      mobility MG bonus/sq Queen
 *   [808]      mobility EG bonus/sq Knight
 *   [809]      mobility EG bonus/sq Bishop
 *   [810]      mobility EG bonus/sq Rook
 *   [811]      mobility EG bonus/sq Queen
 *
 *   [812]      tempo bonus (side-to-move advantage, single scalar)
 *   [813]      bishop pair MG bonus
 *   [814]      bishop pair EG bonus
 *   [815]      rook on 7th rank MG bonus
 *   [816]      rook on 7th rank EG bonus
 *
 *   [817]      rook on open file MG bonus
 *   [818]      rook on open file EG bonus
 *   [819]      rook on semi-open file MG bonus
 *   [820]      rook on semi-open file EG bonus
 *
 *   [821]      knight outpost MG bonus
 *   [822]      knight outpost EG bonus
 *   [823]      connected pawn MG bonus
 *   [824]      connected pawn EG bonus
 *   [825]      backward pawn MG penalty
 *   [826]      backward pawn EG penalty
 *   [827]      rook behind passer MG bonus
 *   [828]      rook behind passer EG bonus
 * </pre>
 */
public final class EvalParams {

    public static final int TOTAL_PARAMS = 829;

    // --- Indices for documentation / cross-referencing ---
    public static final int IDX_MATERIAL_START  = 0;   // [0..11]
    public static final int IDX_PST_START       = 12;  // [12..779]
    public static final int IDX_PASSED_MG_START = 780;
    public static final int IDX_PASSED_EG_START = 786;
    public static final int IDX_ISOLATED_MG     = 792;
    public static final int IDX_ISOLATED_EG     = 793;
    public static final int IDX_DOUBLED_MG      = 794;
    public static final int IDX_DOUBLED_EG      = 795;
    public static final int IDX_SHIELD_RANK2    = 796;
    public static final int IDX_SHIELD_RANK3    = 797;
    public static final int IDX_OPEN_FILE       = 798;
    public static final int IDX_HALF_OPEN_FILE  = 799;
    public static final int IDX_ATK_KNIGHT      = 800;
    public static final int IDX_ATK_BISHOP      = 801;
    public static final int IDX_ATK_ROOK        = 802;
    public static final int IDX_ATK_QUEEN       = 803;
    public static final int IDX_MOB_MG_START    = 804;
    public static final int IDX_MOB_EG_START    = 808;
    public static final int IDX_TEMPO           = 812;
    public static final int IDX_BISHOP_PAIR_MG  = 813;
    public static final int IDX_BISHOP_PAIR_EG  = 814;
    public static final int IDX_ROOK_7TH_MG          = 815;
    public static final int IDX_ROOK_7TH_EG          = 816;
    public static final int IDX_ROOK_OPEN_FILE_MG     = 817;
    public static final int IDX_ROOK_OPEN_FILE_EG     = 818;
    public static final int IDX_ROOK_SEMI_OPEN_MG     = 819;
    public static final int IDX_ROOK_SEMI_OPEN_EG     = 820;
    public static final int IDX_KNIGHT_OUTPOST_MG     = 821;
    public static final int IDX_KNIGHT_OUTPOST_EG     = 822;
    public static final int IDX_CONNECTED_PAWN_MG     = 823;
    public static final int IDX_CONNECTED_PAWN_EG     = 824;
    public static final int IDX_BACKWARD_PAWN_MG      = 825;
    public static final int IDX_BACKWARD_PAWN_EG      = 826;
    public static final int IDX_ROOK_BEHIND_PASSER_MG = 827;
    public static final int IDX_ROOK_BEHIND_PASSER_EG = 828;

    /**
     * Per-parameter lower bounds enforced during coordinate descent.
     * Prevents the optimizer from drifting to pathological values (e.g., pawn < 65).
     */
    public static final double[] PARAM_MIN = buildMin();

    /**
     * Per-parameter upper bounds enforced during coordinate descent.
     */
    public static final double[] PARAM_MAX = buildMax();

    private static double[] buildMin() {
        double[] lo = new double[TOTAL_PARAMS];
        // Material: pawn MG hard-pinned at 100 (min==max), king MG/EG pinned at 0.
        // All other material values float freely with reasonable lower bounds.
        //           P-MG  P-EG  N-MG  N-EG  B-MG  B-EG  R-MG  R-EG  Q-MG   Q-EG  K-MG K-EG
        double[] matLo = { 100,  70,  250,  200,  250,  200,  350,  350,  800,  700,  0,   0 };
        System.arraycopy(matLo, 0, lo, 0, 12);
        Arrays.fill(lo, IDX_PST_START, IDX_PASSED_MG_START, -200);  // PST: no extreme positional skew
        Arrays.fill(lo, IDX_PASSED_MG_START, IDX_ISOLATED_MG,  0);  // Passed pawn bonuses >= 0
        Arrays.fill(lo, IDX_ISOLATED_MG, IDX_SHIELD_RANK2,     0);  // Pawn penalties >= 0
        Arrays.fill(lo, IDX_SHIELD_RANK2, IDX_MOB_MG_START,    0);  // King safety >= 0
        // Attacker weights pinned >= 2: corpus coverage gaps can zero these incorrectly.
        // King-attack positions are underrepresented in quiet-start self-play. A floor
        // of 2 prevents the optimizer absorbing this signal into PSTs when training data
        // lacks king-side attack patterns. Re-evaluate after noob_3moves.epd corpus run.
        lo[IDX_ATK_KNIGHT] = 2;
        lo[IDX_ATK_BISHOP] = 2;
        lo[IDX_ATK_ROOK]   = 2;
        lo[IDX_ATK_QUEEN]  = 3;
        Arrays.fill(lo, IDX_MOB_MG_START, IDX_MOB_EG_START,   -5);  // Mobility MG may be slightly negative
        Arrays.fill(lo, IDX_MOB_EG_START, IDX_TEMPO,            0);  // Mobility EG must be >= 0
        lo[IDX_TEMPO]          = 5;   // Tempo bonus >= 5cp (PST absorption risk)
        // Bishop pair pinned >= 15: one of the most Elo-stable terms in classical eval.
        // Zeroing indicates missing B+B vs B+N endgame positions in corpus, not uselessness.
        lo[IDX_BISHOP_PAIR_MG] = 15;  // Bishop pair MG >= 15cp
        lo[IDX_BISHOP_PAIR_EG] = 15;  // Bishop pair EG >= 15cp
        lo[IDX_ROOK_7TH_MG]   = 0;   // Rook on 7th MG >= 0
        lo[IDX_ROOK_7TH_EG]   = 0;   // Rook on 7th EG >= 0
        lo[IDX_ROOK_OPEN_FILE_MG]  = 0;   // Rook open file MG >= 0
        lo[IDX_ROOK_OPEN_FILE_EG]  = 0;   // Rook open file EG >= 0
        lo[IDX_ROOK_SEMI_OPEN_MG]  = 0;   // Rook semi-open file MG >= 0
        lo[IDX_ROOK_SEMI_OPEN_EG]  = 0;   // Rook semi-open file EG >= 0
        lo[IDX_KNIGHT_OUTPOST_MG]  = 0;   // Knight outpost MG >= 0
        lo[IDX_KNIGHT_OUTPOST_EG]  = 0;   // Knight outpost EG >= 0
        lo[IDX_CONNECTED_PAWN_MG]  = 0;   // Connected pawn MG >= 0
        lo[IDX_CONNECTED_PAWN_EG]  = 0;   // Connected pawn EG >= 0
        lo[IDX_BACKWARD_PAWN_MG]   = 0;   // Backward pawn penalty MG >= 0
        lo[IDX_BACKWARD_PAWN_EG]   = 0;   // Backward pawn penalty EG >= 0
        lo[IDX_ROOK_BEHIND_PASSER_MG] = 0;  // Rook behind passer MG >= 0
        lo[IDX_ROOK_BEHIND_PASSER_EG] = 0;  // Rook behind passer EG >= 0
        return lo;
    }

    private static double[] buildMax() {
        double[] hi = new double[TOTAL_PARAMS];
        // Material: pawn MG hard-pinned at 100 (min==max), king MG/EG pinned at 0.
        // All other material values float freely with reasonable upper bounds.
        //           P-MG  P-EG  N-MG  N-EG  B-MG  B-EG  R-MG  R-EG  Q-MG   Q-EG  K-MG K-EG
        double[] matHi = { 100, 130,  450,  400,  450,  400,  600,  650,  1200, 1100, 0,   0 };
        System.arraycopy(matHi, 0, hi, 0, 12);
        Arrays.fill(hi, IDX_PST_START, IDX_PASSED_MG_START, 200);   // PST
        Arrays.fill(hi, IDX_PASSED_MG_START, IDX_PASSED_EG_START, 150); // Passed pawn MG
        Arrays.fill(hi, IDX_PASSED_EG_START, IDX_ISOLATED_MG,  200);    // Passed pawn EG
        Arrays.fill(hi, IDX_ISOLATED_MG, IDX_SHIELD_RANK2,      60);    // Pawn penalties
        Arrays.fill(hi, IDX_SHIELD_RANK2, IDX_MOB_MG_START,     80);    // King safety
        Arrays.fill(hi, IDX_MOB_MG_START, IDX_MOB_EG_START,     15);    // Mobility MG
        Arrays.fill(hi, IDX_MOB_EG_START, IDX_TEMPO,             15);    // Mobility EG
        hi[IDX_TEMPO]          = 30;   // Tempo bonus <= 30cp
        hi[IDX_BISHOP_PAIR_MG] = 60;   // Bishop pair MG <= 60cp
        hi[IDX_BISHOP_PAIR_EG] = 80;   // Bishop pair EG <= 80cp
        hi[IDX_ROOK_7TH_MG]   = 50;   // Rook on 7th MG <= 50cp
        hi[IDX_ROOK_7TH_EG]   = 50;   // Rook on 7th EG <= 50cp
        hi[IDX_ROOK_OPEN_FILE_MG]  = 50;   // Rook open file MG <= 50cp
        hi[IDX_ROOK_OPEN_FILE_EG]  = 50;   // Rook open file EG <= 50cp
        hi[IDX_ROOK_SEMI_OPEN_MG]  = 30;   // Rook semi-open file MG <= 30cp
        hi[IDX_ROOK_SEMI_OPEN_EG]  = 30;   // Rook semi-open file EG <= 30cp
        hi[IDX_KNIGHT_OUTPOST_MG]  = 40;   // Knight outpost MG <= 40cp
        hi[IDX_KNIGHT_OUTPOST_EG]  = 30;   // Knight outpost EG <= 30cp
        hi[IDX_CONNECTED_PAWN_MG]  = 25;   // Connected pawn MG <= 25cp
        hi[IDX_CONNECTED_PAWN_EG]  = 20;   // Connected pawn EG <= 20cp
        hi[IDX_BACKWARD_PAWN_MG]   = 25;   // Backward pawn penalty MG <= 25cp
        hi[IDX_BACKWARD_PAWN_EG]   = 20;   // Backward pawn penalty EG <= 20cp
        hi[IDX_ROOK_BEHIND_PASSER_MG] = 40;  // Rook behind passer MG <= 40cp
        hi[IDX_ROOK_BEHIND_PASSER_EG] = 50;  // Rook behind passer EG <= 50cp
        return hi;
    }

    /** Clamps a single parameter value to its legal range. */
    public static double clampOne(int i, double value) {
        return Math.max(PARAM_MIN[i], Math.min(PARAM_MAX[i], value));
    }

    /**
     * Enforces P &lt; N &lt; B &lt; R &lt; Q material ordering for both MG and EG.
     * If a violation is found, the offending value is clamped upward to one more
     * than the preceding piece's value (i.e., the lighter piece value + 1).
     * Called after each optimizer step to prevent pathological material ratios.
     *
     * <p>Indices: Pawn MG=0, Pawn EG=1, Knight MG=2, Knight EG=3, Bishop MG=4,
     * Bishop EG=5, Rook MG=6, Rook EG=7, Queen MG=8, Queen EG=9.
     * King (10,11) is excluded — always 0.
     */
    public static void enforceMaterialOrdering(double[] params) {
        // MG ordering: P(0) < N(2) < B(4) < R(6) < Q(8)
        int[] mgIndices = { 0, 2, 4, 6, 8 };
        for (int i = 1; i < mgIndices.length; i++) {
            if (params[mgIndices[i]] <= params[mgIndices[i - 1]]) {
                params[mgIndices[i]] = params[mgIndices[i - 1]] + 1;
            }
        }
        // EG ordering: P(1) < N(3) < B(5) < R(7) < Q(9)
        int[] egIndices = { 1, 3, 5, 7, 9 };
        for (int i = 1; i < egIndices.length; i++) {
            if (params[egIndices[i]] <= params[egIndices[i - 1]]) {
                params[egIndices[i]] = params[egIndices[i - 1]] + 1;
            }
        }
    }

    private EvalParams() {}

    /**
     * Returns a human-readable name for the parameter at the given index.
     * Used by coverage-audit output and diagnostics.
     */
    public static String getParamName(int idx) {
        if (idx < IDX_PST_START) {
            String[] matNames = {
                "PAWN_MG",   "PAWN_EG",   "KNIGHT_MG",  "KNIGHT_EG",
                "BISHOP_MG", "BISHOP_EG", "ROOK_MG",    "ROOK_EG",
                "QUEEN_MG",  "QUEEN_EG",  "KING_MG",    "KING_EG"
            };
            return idx < matNames.length ? matNames[idx] : "MAT[" + idx + "]";
        }
        if (idx < IDX_PASSED_MG_START) {
            int rel     = idx - IDX_PST_START;
            int pieceIdx = rel / 128;
            int phaseIdx = (rel % 128) / 64;
            int sq       = rel % 64;
            String[] ptNames = { "PAWN", "KNIGHT", "BISHOP", "ROOK", "QUEEN", "KING" };
            String[] phases  = { "MG", "EG" };
            return ptNames[pieceIdx] + "_PST_" + phases[phaseIdx] + "[" + sq + "]";
        }
        switch (idx) {
            case 780: case 781: case 782: case 783: case 784: case 785:
                return "PASSED_MG_" + (idx - IDX_PASSED_MG_START + 1);
            case 786: case 787: case 788: case 789: case 790: case 791:
                return "PASSED_EG_" + (idx - IDX_PASSED_EG_START + 1);
            case 792: return "ISOLATED_MG";
            case 793: return "ISOLATED_EG";
            case 794: return "DOUBLED_MG";
            case 795: return "DOUBLED_EG";
            case 796: return "SHIELD_RANK2";
            case 797: return "SHIELD_RANK3";
            case 798: return "OPEN_FILE";
            case 799: return "HALF_OPEN_FILE";
            case 800: return "ATK_KNIGHT";
            case 801: return "ATK_BISHOP";
            case 802: return "ATK_ROOK";
            case 803: return "ATK_QUEEN";
            case 804: return "MOB_MG_KNIGHT";
            case 805: return "MOB_MG_BISHOP";
            case 806: return "MOB_MG_ROOK";
            case 807: return "MOB_MG_QUEEN";
            case 808: return "MOB_EG_KNIGHT";
            case 809: return "MOB_EG_BISHOP";
            case 810: return "MOB_EG_ROOK";
            case 811: return "MOB_EG_QUEEN";
            case 812: return "TEMPO";
            case 813: return "BISHOP_PAIR_MG";
            case 814: return "BISHOP_PAIR_EG";
            case 815: return "ROOK_7TH_MG";
            case 816: return "ROOK_7TH_EG";
            case 817: return "ROOK_OPEN_FILE_MG";
            case 818: return "ROOK_OPEN_FILE_EG";
            case 819: return "ROOK_SEMI_OPEN_MG";
            case 820: return "ROOK_SEMI_OPEN_EG";
            case 821: return "KNIGHT_OUTPOST_MG";
            case 822: return "KNIGHT_OUTPOST_EG";
            case 823: return "CONNECTED_PAWN_MG";
            case 824: return "CONNECTED_PAWN_EG";
            case 825: return "BACKWARD_PAWN_MG";
            case 826: return "BACKWARD_PAWN_EG";
            case 827: return "ROOK_BEHIND_PASSER_MG";
            case 828: return "ROOK_BEHIND_PASSER_EG";
            default:  return "PARAM[" + idx + "]";
        }
    }

    /**
     * Extracts current hardcoded evaluation constants into a flat double[] array.
     * These are the PeSTO defaults as used by the live Evaluator.
     * After a tuning run, copy the tuned values back into PieceSquareTables,
     * Evaluator, PawnStructure, and KingSafety by hand.
     */
    public static double[] extractFromCurrentEval() {
        double[] p = new double[TOTAL_PARAMS];

        // --- Material MG/EG ---
        // Piece type indices (Piece.Pawn=1, Knight=2, Bishop=3, Rook=4, Queen=5, King=6)
        // Stored as 2 per type: [2*(type-1)] = MG, [2*(type-1)+1] = EG
        // Pawn MG is hard-pinned at 100 (anchoring point for all other values).
        p[0]  = 100;  p[1]  = 86;    // Pawn (MG pinned at 100)
        p[2]  = 391;  p[3]  = 287;   // Knight
        p[4]  = 416;  p[5]  = 302;   // Bishop
        p[6]  = 564;  p[7]  = 537;   // Rook
        p[8]  = 1200; p[9]  = 991;   // Queen
        p[10] = 0;    p[11] = 0;     // King

        // --- PST tables ---
        // White tables from PieceSquareTables (a8=0 convention, rank 8 at top)
        int[] MG_PAWN = {
              0,    0,    0,    0,    0,    0,    0,    0,
             32,   90,   18,   55,   36,   80,  -42, -105,
             -1,   -2,   16,   15,   59,   74,   22,  -12,
            -23,   -6,   -1,   15,   16,   10,   -1,  -26,
            -32,  -27,   -4,    8,   12,    4,  -13,  -30,
            -26,  -22,   -5,   -9,    6,    3,   15,  -16,
            -28,  -15,  -21,  -13,  -10,   21,   22,  -19,
              0,    0,    0,    0,    0,    0,    0,    0,
        };
        int[] EG_PAWN = {
              0,    0,    0,    0,    0,    0,    0,    0,
            131,  108,   94,   62,   75,   60,  114,  153,
             45,   38,   13,  -21,  -38,  -17,   14,   26,
             26,   14,    2,  -17,  -12,   -6,    8,   12,
             16,   11,   -6,  -11,  -10,   -9,   -2,    0,
              4,    3,   -6,   -2,   -3,   -5,  -12,   -8,
             10,   -1,    7,   -3,    7,   -4,  -13,  -12,
              0,    0,    0,    0,    0,    0,    0,    0,
        };
        int[] MG_KNIGHT = {
            -182, -100,  -55,  -61,   55, -111,  -30, -112,
             -69,  -34,   87,   20,   18,   60,    1,  -15,
             -35,   64,   35,   58,   89,  123,   75,   48,
               7,   28,   24,   58,   38,   70,   26,   32,
              11,   26,   30,   30,   36,   32,   32,   13,
              -4,   11,   28,   32,   47,   39,   45,    8,
              -2,  -22,   13,   34,   36,   42,   17,   16,
            -105,   10,  -19,    4,   26,    9,   14,   10,
        };
        int[] EG_KNIGHT = {
             -38,  -24,    2,  -16,  -28,  -17,  -56,  -82,
             -11,    5,  -24,    9,   -2,  -23,  -14,  -42,
             -14,  -13,   19,   13,   -5,   -8,  -18,  -38,
              -6,   13,   33,   27,   29,   16,   15,  -11,
              -9,    3,   24,   33,   24,   25,   14,  -11,
             -15,    5,    3,   20,   13,   -2,  -16,  -12,
             -27,   -9,    0,   -4,   -1,  -12,  -13,  -41,
              -2,  -41,  -14,   -4,  -17,  -13,  -43,  -64,
        };
        int[] MG_BISHOP = {
             -31,  -16, -132,  -98,  -65,  -69,  -33,   -5,
             -20,   20,  -23,  -33,   21,   50,   20,  -52,
             -18,   35,   43,   23,   26,   44,   20,  -10,
               5,   22,   18,   53,   37,   28,   17,    5,
              17,   21,   23,   40,   46,   10,   23,   22,
              21,   37,   39,   31,   33,   56,   34,   30,
              35,   47,   37,   29,   41,   47,   68,   29,
              -4,   30,   24,   21,   27,   22,  -13,    4,
        };
        int[] EG_BISHOP = {
               0,   -3,   20,   18,   19,   12,    6,  -11,
               9,    7,   22,    9,    7,    2,    3,    5,
              20,    5,    6,    7,    4,    7,   15,   18,
              11,   16,   18,   13,   15,   14,    7,   14,
               5,   11,   19,   21,    8,   17,    4,    4,
               3,    7,   16,   19,   21,    3,    8,    0,
               0,  -10,    3,    5,    5,    2,   -9,  -17,
              -3,    7,   -4,    7,    4,    3,   14,    0,
        };
        int[] MG_ROOK = {
             14,   31,   -2,   45,   39,  -20,   -7,   -1,
              7,    9,   48,   57,   72,   67,    4,   26,
            -12,   13,   13,   27,   -6,   38,   73,    1,
            -24,  -15,   12,   22,   17,   29,  -10,  -27,
            -36,  -18,   -2,    4,   14,   -4,   11,  -32,
            -37,  -13,    2,   -2,   14,   11,    3,  -26,
            -30,   -3,    0,   13,   23,   25,   13,  -51,
             -1,    7,   26,   34,   37,   25,  -10,    2,
        };
        int[] EG_ROOK = {
             28,   21,   33,   18,   23,   33,   31,   28,
              7,    9,   -1,   -5,  -20,   -9,    9,    0,
             29,   26,   24,   21,   23,   12,    3,   17,
             31,   27,   30,   17,   19,   17,   20,   30,
             32,   29,   28,   22,   14,   15,   10,   20,
             22,   22,   11,   17,    7,    5,   10,    9,
             19,   12,   16,   16,    4,    4,    2,   20,
             10,   14,   11,    6,    2,    0,   12,   -9,
        };
        int[] MG_QUEEN = {
            -15,  -31,  -14,  -20,  111,   94,   71,   58,
            -15,  -39,  -10,  -14,  -49,   62,   39,   66,
             -1,  -12,   18,  -17,   23,   73,   48,   55,
            -27,  -14,  -21,  -19,    1,    2,    0,   -1,
             13,  -28,    6,   -2,    5,    4,    1,    5,
             -3,   26,   11,   19,   15,   17,   23,   15,
             -5,   19,   35,   37,   46,   50,   30,   37,
             29,   32,   39,   47,   31,   19,   14,  -25,
        };
        int[] EG_QUEEN = {
              4,   58,   58,   59,   -3,    7,    1,   36,
              5,   33,   44,   66,   97,   32,   35,   17,
              4,   25,    3,   76,   63,   35,   45,   35,
             43,   36,   39,   52,   63,   60,   92,   79,
             -5,   57,   23,   51,   35,   44,   69,   53,
             17,  -28,   16,    2,   19,   31,   40,   41,
             -1,  -20,  -26,  -25,  -21,  -20,  -33,  -18,
            -32,  -43,  -32,  -35,   -4,  -20,  -18,  -26,
        };
        int[] MG_KING = {
            -124,  200,  200,  140,  -78,  -30,   94,   -3,
             192,  119,   78,  147,   76,   56,   18, -138,
              64,  101,  131,   65,   94,  140,  147,  -27,
              -5,   15,   50,   -2,  -12,  -18,   -7, -104,
             -74,   42,  -27,  -65,  -79,  -60,  -66, -107,
             -19,   -4,  -15,  -38,  -46,  -48,  -15,  -53,
             -10,    7,  -13,  -59,  -41,  -27,   12,    9,
             -32,   15,    2,  -61,    4,  -29,   10,   -5,
        };
        int[] EG_KING = {
            -73,  -72,  -55,  -42,    4,   20,  -15,  -28,
            -55,   -3,    6,   -4,   10,   33,   24,   29,
             -9,    9,    7,   11,    7,   32,   24,   11,
            -15,   23,   24,   36,   35,   42,   33,   16,
            -12,   -5,   34,   45,   48,   38,   24,    2,
            -22,    2,   23,   35,   38,   30,   11,   -7,
            -33,  -10,   15,   29,   27,   16,   -8,  -31,
            -66,  -47,  -19,    3,  -21,   -8,  -40,  -67,
        };

        int[][] mgTables = { null, MG_PAWN, MG_KNIGHT, MG_BISHOP, MG_ROOK, MG_QUEEN, MG_KING };
        int[][] egTables = { null, EG_PAWN, EG_KNIGHT, EG_BISHOP, EG_ROOK, EG_QUEEN, EG_KING };

        // Piece types 1..6 → pst slice index 0..5
        for (int pt = 1; pt <= 6; pt++) {
            int sliceIdx  = pt - 1;
            int mgBase    = IDX_PST_START + sliceIdx * 128;       // MG slice
            int egBase    = IDX_PST_START + sliceIdx * 128 + 64;  // EG slice
            for (int sq = 0; sq < 64; sq++) {
                p[mgBase + sq] = mgTables[pt][sq];
                p[egBase + sq] = egTables[pt][sq];
            }
        }

        // --- Pawn structure ---
        // PASSED_MG = {0, 6, 1, 0, 8, 7, 45, 0} — indices 1..6 are tunable
        int[] PASSED_MG = {0, 6, 1, 0, 8, 7, 45, 0};
        int[] PASSED_EG = {0, 4, 9, 29, 56, 123, 116, 0};
        for (int i = 0; i < 6; i++) {
            p[IDX_PASSED_MG_START + i] = PASSED_MG[i + 1];
            p[IDX_PASSED_EG_START + i] = PASSED_EG[i + 1];
        }
        p[IDX_ISOLATED_MG] = 17;
        p[IDX_ISOLATED_EG] = 9;
        p[IDX_DOUBLED_MG]  = 0;
        p[IDX_DOUBLED_EG]  = 11;

        // --- King safety ---
        p[IDX_SHIELD_RANK2]   = 12;
        p[IDX_SHIELD_RANK3]   = 8;
        p[IDX_OPEN_FILE]      = 45;
        p[IDX_HALF_OPEN_FILE] = 15;
        p[IDX_ATK_KNIGHT]     = 6;
        p[IDX_ATK_BISHOP]     = 4;
        p[IDX_ATK_ROOK]       = 5;
        p[IDX_ATK_QUEEN]      = 7;

        // --- Mobility ---
        // MG: N=6, B=7, R=8, Q=3
        p[IDX_MOB_MG_START]     = 6;  // Knight
        p[IDX_MOB_MG_START + 1] = 7;  // Bishop
        p[IDX_MOB_MG_START + 2] = 8;  // Rook
        p[IDX_MOB_MG_START + 3] = 3;  // Queen
        // EG: N=0, B=2, R=2, Q=6
        p[IDX_MOB_EG_START]     = 0;  // Knight
        p[IDX_MOB_EG_START + 1] = 2;  // Bishop
        p[IDX_MOB_EG_START + 2] = 2;  // Rook
        p[IDX_MOB_EG_START + 3] = 6;  // Queen

        // --- Bonus eval terms ---
        p[IDX_TEMPO]          = 19;   // Tempo bonus
        p[IDX_BISHOP_PAIR_MG] = 31;   // Bishop pair MG
        p[IDX_BISHOP_PAIR_EG] = 51;   // Bishop pair EG
        p[IDX_ROOK_7TH_MG]          = 9;    // Rook on 7th rank MG
        p[IDX_ROOK_7TH_EG]          = 20;   // Rook on 7th rank EG
        p[IDX_ROOK_OPEN_FILE_MG]    = 20;   // Rook on open file MG
        p[IDX_ROOK_OPEN_FILE_EG]    = 10;   // Rook on open file EG
        p[IDX_ROOK_SEMI_OPEN_MG]    = 10;   // Rook on semi-open file MG
        p[IDX_ROOK_SEMI_OPEN_EG]    = 5;    // Rook on semi-open file EG
        p[IDX_KNIGHT_OUTPOST_MG]    = 20;   // Knight outpost MG
        p[IDX_KNIGHT_OUTPOST_EG]    = 10;   // Knight outpost EG
        p[IDX_CONNECTED_PAWN_MG]    = 10;   // Connected pawn bonus MG
        p[IDX_CONNECTED_PAWN_EG]    = 8;    // Connected pawn bonus EG
        p[IDX_BACKWARD_PAWN_MG]     = 10;   // Backward pawn penalty MG
        p[IDX_BACKWARD_PAWN_EG]     = 5;    // Backward pawn penalty EG
        p[IDX_ROOK_BEHIND_PASSER_MG] = 15;  // Rook behind passer MG
        p[IDX_ROOK_BEHIND_PASSER_EG] = 25;  // Rook behind passer EG

        return p;
    }

    /**
     * Writes tuned parameters to a human-readable file with labeled sections
     * so the developer can manually copy values back into the source files.
     */
    public static void writeToFile(double[] params, Path output) throws IOException {
        writeToFile(params, Double.NaN, output);
    }

    public static void writeToFile(double[] params, double k, Path output) throws IOException {
        try (BufferedWriter w = new BufferedWriter(new FileWriter(output.toFile()))) {
            w.write("# Tuned parameters — copy values manually into engine-core source files\n");
            w.write("# Generated by engine-tuner Texel tuning pipeline\n");
            if (!Double.isNaN(k)) {
                w.write(String.format("# Final K = %.6f%n", k));
            }
            w.write("\n");

            w.write("## MATERIAL (MG, EG)\n");
            String[] names = { "Pawn", "Knight", "Bishop", "Rook", "Queen", "King" };
            for (int i = 0; i < 6; i++) {
                w.write(String.format("%-8s MG=%.0f  EG=%.0f%n", names[i], params[i * 2], params[i * 2 + 1]));
            }

            w.write("\n## PST TABLES\n");
            String[] pstNames = { null, "PAWN", "KNIGHT", "BISHOP", "ROOK", "QUEEN", "KING" };
            for (int pt = 1; pt <= 6; pt++) {
                int sliceIdx = pt - 1;
                int mgBase = IDX_PST_START + sliceIdx * 128;
                int egBase = mgBase + 64;
                w.write(String.format("### %s MG PST%n", pstNames[pt]));
                writePst(w, params, mgBase);
                w.write(String.format("### %s EG PST%n", pstNames[pt]));
                writePst(w, params, egBase);
            }

            w.write("\n## PAWN STRUCTURE\n");
            w.write("PASSED_MG indices 1-6: ");
            for (int i = 0; i < 6; i++) w.write(String.format("%.0f ", params[IDX_PASSED_MG_START + i]));
            w.write("\nPASSED_EG indices 1-6: ");
            for (int i = 0; i < 6; i++) w.write(String.format("%.0f ", params[IDX_PASSED_EG_START + i]));
            w.write(String.format("%nISOLATED MG=%.0f  EG=%.0f%n", params[IDX_ISOLATED_MG], params[IDX_ISOLATED_EG]));
            w.write(String.format("DOUBLED  MG=%.0f  EG=%.0f%n", params[IDX_DOUBLED_MG], params[IDX_DOUBLED_EG]));

            w.write("\n## KING SAFETY\n");
            w.write(String.format("SHIELD_RANK2=%.0f  SHIELD_RANK3=%.0f%n", params[IDX_SHIELD_RANK2], params[IDX_SHIELD_RANK3]));
            w.write(String.format("OPEN_FILE=%.0f  HALF_OPEN_FILE=%.0f%n", params[IDX_OPEN_FILE], params[IDX_HALF_OPEN_FILE]));
            w.write(String.format("ATTACKER_WEIGHTS  N=%.0f B=%.0f R=%.0f Q=%.0f%n",
                params[IDX_ATK_KNIGHT], params[IDX_ATK_BISHOP], params[IDX_ATK_ROOK], params[IDX_ATK_QUEEN]));

            w.write("\n## MOBILITY (MG then EG)\n");
            w.write(String.format("MG  N=%.0f B=%.0f R=%.0f Q=%.0f%n",
                params[IDX_MOB_MG_START], params[IDX_MOB_MG_START+1],
                params[IDX_MOB_MG_START+2], params[IDX_MOB_MG_START+3]));
            w.write(String.format("EG  N=%.0f B=%.0f R=%.0f Q=%.0f%n",
                params[IDX_MOB_EG_START], params[IDX_MOB_EG_START+1],
                params[IDX_MOB_EG_START+2], params[IDX_MOB_EG_START+3]));

            w.write("\n## MISC TERMS\n");
            w.write(String.format("TEMPO=%.0f%n", params[IDX_TEMPO]));
            w.write(String.format("BISHOP_PAIR  MG=%.0f  EG=%.0f%n",
                params[IDX_BISHOP_PAIR_MG], params[IDX_BISHOP_PAIR_EG]));
            w.write(String.format("ROOK_ON_7TH  MG=%.0f  EG=%.0f%n",
                params[IDX_ROOK_7TH_MG], params[IDX_ROOK_7TH_EG]));
            w.write(String.format("ROOK_OPEN_FILE  MG=%.0f  EG=%.0f%n",
                params[IDX_ROOK_OPEN_FILE_MG], params[IDX_ROOK_OPEN_FILE_EG]));
            w.write(String.format("ROOK_SEMI_OPEN  MG=%.0f  EG=%.0f%n",
                params[IDX_ROOK_SEMI_OPEN_MG], params[IDX_ROOK_SEMI_OPEN_EG]));
            w.write(String.format("KNIGHT_OUTPOST  MG=%.0f  EG=%.0f%n",
                params[IDX_KNIGHT_OUTPOST_MG], params[IDX_KNIGHT_OUTPOST_EG]));
            w.write(String.format("CONNECTED_PAWN  MG=%.0f  EG=%.0f%n",
                params[IDX_CONNECTED_PAWN_MG], params[IDX_CONNECTED_PAWN_EG]));
            w.write(String.format("BACKWARD_PAWN  MG=%.0f  EG=%.0f%n",
                params[IDX_BACKWARD_PAWN_MG], params[IDX_BACKWARD_PAWN_EG]));
            w.write(String.format("ROOK_BEHIND_PASSER  MG=%.0f  EG=%.0f%n",
                params[IDX_ROOK_BEHIND_PASSER_MG], params[IDX_ROOK_BEHIND_PASSER_EG]));
        }
    }

    private static void writePst(BufferedWriter w, double[] params, int base) throws IOException {
        for (int row = 0; row < 8; row++) {
            w.write("    ");
            for (int file = 0; file < 8; file++) {
                w.write(String.format("%5.0f,", params[base + row * 8 + file]));
            }
            w.write("\n");
        }
    }
}
