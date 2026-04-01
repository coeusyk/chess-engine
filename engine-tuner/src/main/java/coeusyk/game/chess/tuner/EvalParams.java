package coeusyk.game.chess.tuner;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Bridge between the tuner parameter array and the live evaluator constants.
 *
 * Parameter layout (817 total):
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
 * </pre>
 */
public final class EvalParams {

    public static final int TOTAL_PARAMS = 817;

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
    public static final int IDX_ROOK_7TH_MG     = 815;
    public static final int IDX_ROOK_7TH_EG     = 816;

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
        Arrays.fill(lo, IDX_MOB_MG_START, IDX_MOB_EG_START,   -5);  // Mobility MG may be slightly negative
        Arrays.fill(lo, IDX_MOB_EG_START, IDX_TEMPO,            0);  // Mobility EG must be >= 0
        lo[IDX_TEMPO]          = 0;   // Tempo bonus >= 0
        lo[IDX_BISHOP_PAIR_MG] = 0;   // Bishop pair MG >= 0
        lo[IDX_BISHOP_PAIR_EG] = 0;   // Bishop pair EG >= 0
        lo[IDX_ROOK_7TH_MG]   = 0;   // Rook on 7th MG >= 0
        lo[IDX_ROOK_7TH_EG]   = 0;   // Rook on 7th EG >= 0
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
        p[0]  = 100;  p[1]  = 94;    // Pawn (MG pinned at 100)
        p[2]  = 337;  p[3]  = 281;   // Knight
        p[4]  = 365;  p[5]  = 297;   // Bishop
        p[6]  = 477;  p[7]  = 512;   // Rook
        p[8]  = 1025; p[9]  = 936;   // Queen
        p[10] = 0;    p[11] = 0;     // King

        // --- PST tables ---
        // White tables from PieceSquareTables (a8=0 convention, rank 8 at top)
        int[] MG_PAWN = {
              0,   0,   0,   0,   0,   0,   0,   0,
             51,  69,   7,  35,   9,  44, -16, -61,
            -26, -14,   9,   3,  49,  49,   4, -23,
            -24,   6,   8,  16,  22,  14,  17, -25,
            -28,  -7,  -2,  10,  20,  10,   1, -27,
            -23,  -9,  -1, -13,   5,   7,  30, -10,
            -30,  -7, -18, -22,  -6,  26,  29, -20,
              0,   0,   0,   0,   0,   0,   0,   0,
        };
        int[] EG_PAWN = {
              0,   0,   0,   0,   0,   0,   0,   0,
            131, 108, 104,  62,  86,  45, 108, 138,
             77,  70,  43,  24,   9,  26,  47,  61,
             29,  12,   0, -11,  -8,  -4,   6,  17,
             23,   9,  -4, -13, -13,  -9,  -4,   3,
              3,   1,  -3,  -1,   2,  -4, -13,  -8,
             22,   3,  10,  14,   5,  -6, -10, -10,
              0,   0,   0,   0,   0,   0,   0,   0,
        };
        int[] MG_KNIGHT = {
            -190, -70, -13, -43,  81, -73,  18, -93,
             -82, -44,  94,  27,  48,  32,  -5, -44,
             -52,  64,  27,  57,  67, 133,  56,  25,
             -21,   4,  19,  55,  28,  65,   6,  19,
             -19,   5,  17,   7,  22,   8,  26,   8,
             -22, -14,   8,   8,  31,  15,  20, -22,
              13, -33,  -9,   7,   4,  29,   3, -13,
             -79, -17, -55, -22,  -3,   3,  -8,  11,
        };
        int[] EG_KNIGHT = {
             -41,   0,  16,  -5, -13,  -4, -25, -117,
              -3,   9, -42, -10,   8, -41, -11, -63,
             -12, -11,   6,  12,  -7,  -5, -13, -51,
               4,   1,  21,  22,  21,  -2,   2,  -8,
             -12,  -1,  18,  25,  22,  31,  10, -17,
             -31,   1,   1,  23,   8,  -2, -27,   4,
             -71,  -4, -18,  -3,   6, -25, -16, -33,
             -23, -42, -17, -11,  -9, -33, -44, -32,
        };
        int[] MG_BISHOP = {
            -21,  12, -83, -42, -37, -36,  19,  -5,
             -4,  22,  -8, -18,  22,  79, -11, -24,
            -27,  28,  44,  18,  16,  65,   7, -14,
             12,  10,  19,  53,  31,  35,   9, -16,
             -2,   4,  13,  20,  31,   7,  22,   2,
              4,  15,  23,  13,   7,  35,  10,   7,
             23,  31,  31,  12,  21,  38,  43,  36,
            -29,  17,   5, -10, -15,   2, -16, -29,
        };
        int[] EG_BISHOP = {
             -8, -29,  12,   2,   2,   5,  -9,   5,
            -12,  11,  -8, -13,   1,  -2,   1, -16,
             14,  -6,  -9,   6,  -5,  -3,  11,   9,
              3,  12,  11,  -1,  21,   6,   0,   7,
              4,  13,  13,  25,  14,  24,   6,  10,
             -6,   6,  11,  15,  25,   4,   6,  -2,
              8,  -8,   8,   6,  11,  10, -10,  -1,
              5,   5,  -4,  12,  -8,   1,  13,  -3,
        };
        int[] MG_ROOK = {
             30,  34,  12,  46,  63, -10,  26,  34,
             20,  23,  65,  67,  65,  53,  31,  43,
             -9,   8,  33,  41,  17,  47,  46,  10,
            -28,   1,  15,  31,  25,  38,  -1, -17,
            -34, -19,   1,   7,  12,  -1,   1, -32,
            -42, -15,   2, -16,  -2,  10,  -7, -19,
            -39,  -9,  -7,   3,   3,  16,   6, -76,
            -16, -10,   4,  18,  14,   8, -30, -20,
        };
        int[] EG_ROOK = {
             10,  -1,   7,   0,  13,  -2,   2,   5,
             12,  17,   8,  14,  -6,   9,  10,   9,
              9,  14,  12,   6,   9,  -3,   4,  -6,
             18,  17,  22,  12,   2,  12,   8,  19,
             13,  15,  17,   8,   9,   3,  -6,  -4,
             15,  16,   7,   6,  -1,   9,  -2,   3,
              3,   5,  12,  17,   3,   0,   2,  14,
              2,  13,   6,  -2,  -4,  -4,  13, -12,
        };
        int[] MG_QUEEN = {
            -15, -17,  24,  29,  45,  20,  63,  48,
            -16, -33, -14,  11, -10,  63,  19,  86,
            -20, -27,   7,   6,  26,  53,  43,  59,
            -35, -45, -18,  -9, -23,  16,  35, -16,
             -9, -38, -17, -17, -25,   4,   1,  -1,
            -15,  11, -23,   9, -15,  -4,  13,  -6,
            -33,   9,  17,   4,  18,  15,  13,  -3,
             -4, -12,   2,  22,  -9,  -8, -42, -34,
        };
        int[] EG_QUEEN = {
             -3,   2,  29,  38,  16,  -4,  35,  26,
            -21,  28,  33,  45,  55,  15,   6,  19,
             -6,  11,  20,  55,  58,  40,  19,  16,
             12,  37,  16,  47,  53,  30,  66,  50,
            -28,  48,  14,  49,  18,  31,  42,  29,
             -8, -24,   4,  10,   2,  28,  27,  -3,
            -21, -18, -28, -16, -18, -26, -39, -33,
            -27, -31,  -7, -38,   5, -19, -14, -24,
        };
        int[] MG_KING = {
            -60,  56,  50,   2, -33, -34,   9,  12,
             20,  18,  -2,  29,   9,  21, -29, -12,
             -3,  23,  14,  -1, -21,  10,  41, -36,
            -24,  -7,  -5, -23, -32, -27,  -7, -41,
            -53,   2, -28, -44, -39, -43, -29, -54,
              3,  -3, -10, -32, -42, -27,  -9, -24,
              8,   3,  -9, -54, -43, -17,  13,  12,
            -12,  21,  10, -45,  11, -25,  20,  13,
        };
        int[] EG_KING = {
            -85, -45, -51, -26,  -9,   1, -26,  -9,
            -30,  24,  22,  22,  35,  17,  28,  31,
            -11,   4,   8,  21,  22,  49,  53,  11,
            -33,  23,  15,  38,  34,  35,  37,   7,
            -18,  -7,  28,  31,  35,  31,  18, -17,
            -32,  -8,  28,  39,  37,  23,   8, -16,
            -41, -13,  10,  29,  22,  10,  -3, -22,
            -66, -42, -27,   4, -22, -10, -30, -56,
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
        // PASSED_MG = {0, 0, 2, 6, 23, 43, 43, 0} — indices 1..6 are tunable
        int[] PASSED_MG = {0, 0, 2, 6, 23, 43, 43, 0};
        int[] PASSED_EG = {0, 0, 6, 26, 53, 83, 108, 0};
        for (int i = 0; i < 6; i++) {
            p[IDX_PASSED_MG_START + i] = PASSED_MG[i + 1];
            p[IDX_PASSED_EG_START + i] = PASSED_EG[i + 1];
        }
        p[IDX_ISOLATED_MG] = 9;
        p[IDX_ISOLATED_EG] = 12;
        p[IDX_DOUBLED_MG]  = 1;
        p[IDX_DOUBLED_EG]  = 19;

        // --- King safety ---
        p[IDX_SHIELD_RANK2]   = 11;
        p[IDX_SHIELD_RANK3]   = 5;
        p[IDX_OPEN_FILE]      = 31;
        p[IDX_HALF_OPEN_FILE] = 7;
        p[IDX_ATK_KNIGHT]     = 4;
        p[IDX_ATK_BISHOP]     = 5;
        p[IDX_ATK_ROOK]       = 6;
        p[IDX_ATK_QUEEN]      = 6;

        // --- Mobility ---
        // MG: N=5, B=4, R=5, Q=0
        p[IDX_MOB_MG_START]     = 5;  // Knight
        p[IDX_MOB_MG_START + 1] = 4;  // Bishop
        p[IDX_MOB_MG_START + 2] = 5;  // Rook
        p[IDX_MOB_MG_START + 3] = 0;  // Queen
        // EG: N=0, B=2, R=4, Q=8
        p[IDX_MOB_EG_START]     = 0;  // Knight
        p[IDX_MOB_EG_START + 1] = 2;  // Bishop
        p[IDX_MOB_EG_START + 2] = 4;  // Rook
        p[IDX_MOB_EG_START + 3] = 8;  // Queen

        // --- Bonus eval terms ---
        p[IDX_TEMPO]          = 15;   // Tempo bonus
        p[IDX_BISHOP_PAIR_MG] = 30;   // Bishop pair MG
        p[IDX_BISHOP_PAIR_EG] = 50;   // Bishop pair EG
        p[IDX_ROOK_7TH_MG]   = 20;   // Rook on 7th rank MG
        p[IDX_ROOK_7TH_EG]   = 30;   // Rook on 7th rank EG

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
