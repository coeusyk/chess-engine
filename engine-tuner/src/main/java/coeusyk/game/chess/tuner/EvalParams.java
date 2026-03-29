package coeusyk.game.chess.tuner;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Bridge between the tuner parameter array and the live evaluator constants.
 *
 * Parameter layout (812 total):
 * <pre>
 *   [0..11]    Material MG/EG for pawn, knight, bishop, rook, queen, king
 *              (indices alternate: [2n] = MG, [2n+1] = EG)
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
 * </pre>
 */
public final class EvalParams {

    public static final int TOTAL_PARAMS = 812;

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
        // Material: fixed at PeSTO defaults (min==max pins the value).
        // Tuning material independently breaks piece-to-pawn ratios — e.g. pawn dropping to 74
        // while knights stay at 337 makes Knights worth 4.5 pawns (standard: 3.2x).
        // Only PSTs, pawn structure, mobility, and king safety are tunable.
        double[] matFixed = { 82, 94, 337, 281, 365, 297, 477, 512, 1025, 936, 0, 0 };
        System.arraycopy(matFixed, 0, lo, 0, 12);
        Arrays.fill(lo, IDX_PST_START, IDX_PASSED_MG_START, -200);  // PST: no extreme positional skew
        Arrays.fill(lo, IDX_PASSED_MG_START, IDX_ISOLATED_MG,  0);  // Passed pawn bonuses >= 0
        Arrays.fill(lo, IDX_ISOLATED_MG, IDX_SHIELD_RANK2,     0);  // Pawn penalties >= 0
        Arrays.fill(lo, IDX_SHIELD_RANK2, IDX_MOB_MG_START,    0);  // King safety >= 0
        Arrays.fill(lo, IDX_MOB_MG_START, IDX_MOB_EG_START,   -5);  // Mobility MG may be slightly negative
        Arrays.fill(lo, IDX_MOB_EG_START, TOTAL_PARAMS,         0);  // Mobility EG must be >= 0
        return lo;
    }

    private static double[] buildMax() {
        double[] hi = new double[TOTAL_PARAMS];
        // Material: fixed (same as min — see buildMin comment)
        double[] matFixed = { 82, 94, 337, 281, 365, 297, 477, 512, 1025, 936, 0, 0 };
        System.arraycopy(matFixed, 0, hi, 0, 12);
        Arrays.fill(hi, IDX_PST_START, IDX_PASSED_MG_START, 200);   // PST
        Arrays.fill(hi, IDX_PASSED_MG_START, IDX_PASSED_EG_START, 150); // Passed pawn MG
        Arrays.fill(hi, IDX_PASSED_EG_START, IDX_ISOLATED_MG,  200);    // Passed pawn EG
        Arrays.fill(hi, IDX_ISOLATED_MG, IDX_SHIELD_RANK2,      60);    // Pawn penalties
        Arrays.fill(hi, IDX_SHIELD_RANK2, IDX_MOB_MG_START,     80);    // King safety
        Arrays.fill(hi, IDX_MOB_MG_START, IDX_MOB_EG_START,     15);    // Mobility MG
        Arrays.fill(hi, IDX_MOB_EG_START, TOTAL_PARAMS,          15);    // Mobility EG
        return hi;
    }

    /** Clamps a single parameter value to its legal range. */
    public static double clampOne(int i, double value) {
        return Math.max(PARAM_MIN[i], Math.min(PARAM_MAX[i], value));
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
        p[0]  = 82;   p[1]  = 94;    // Pawn
        p[2]  = 337;  p[3]  = 281;   // Knight
        p[4]  = 365;  p[5]  = 297;   // Bishop
        p[6]  = 477;  p[7]  = 512;   // Rook
        p[8]  = 1025; p[9]  = 936;   // Queen
        p[10] = 0;    p[11] = 0;     // King

        // --- PST tables ---
        // White tables from PieceSquareTables (a8=0 convention, rank 8 at top)
        int[] MG_PAWN = {
              0,   0,   0,   0,   0,   0,   0,   0,
             98, 134,  61,  95,  68, 126,  34, -11,
             -6,   7,  26,  31,  65,  56,  25, -20,
            -14,  13,   6,  21,  23,  12,  17, -23,
            -27,  -2,  -5,  12,  17,   6,  10, -25,
            -26,  -4,  -4, -10,   3,   3,  33, -12,
            -35,  -1, -20, -23, -15,  24,  38, -22,
              0,   0,   0,   0,   0,   0,   0,   0,
        };
        int[] EG_PAWN = {
              0,   0,   0,   0,   0,   0,   0,   0,
            178, 173, 158, 134, 147, 132, 165, 187,
             94, 100,  85,  67,  56,  53,  82,  84,
             32,  24,  13,   5,  -2,   4,  17,  17,
             13,   9,  -3,  -7,  -7,  -8,   3,  -1,
              4,   7,  -6,   1,   0,  -5,  -1,  -8,
             13,   8,   8,  10,  13,   0,   2,  -7,
              0,   0,   0,   0,   0,   0,   0,   0,
        };
        int[] MG_KNIGHT = {
            -167, -89, -34, -49,  61, -97, -15, -107,
             -73, -41,  72,  36,  23,  62,   7,  -17,
             -47,  60,  37,  65,  84, 129,  73,   44,
              -9,  17,  19,  53,  37,  69,  18,   22,
             -13,   4,  16,  13,  28,  19,  21,   -8,
             -23,  -9,  12,  10,  19,  17,  25,  -16,
             -29, -53, -12,  -3,  -1,  18, -14,  -19,
            -105, -21, -58, -33, -17, -28, -19,  -23,
        };
        int[] EG_KNIGHT = {
            -58, -38, -13, -28, -31, -27, -63, -99,
            -25,  -8, -25,  -2,  -9, -25, -24, -52,
            -24, -20,  10,   9,  -1,  -9, -19, -41,
            -17,   3,  22,  22,  22,  11,   8, -18,
            -18,  -6,  16,  25,  16,  17,   4, -18,
            -23,  -3,  -1,  15,  10,  -3, -20, -22,
            -42, -20, -10,  -5,  -2, -20, -23, -44,
            -29, -51, -23, -15, -22, -18, -50, -64,
        };
        int[] MG_BISHOP = {
            -29,   4, -82, -37, -25, -42,   7,  -8,
            -26,  16, -18, -13,  30,  59,  18, -47,
            -16,  37,  43,  40,  35,  50,  37,  -2,
             -4,   5,  19,  50,  37,  37,   7,  -2,
             -6,  13,  13,  26,  34,  12,  10,   4,
              0,  15,  15,  15,  14,  27,  18,  10,
              4,  15,  16,   0,   7,  21,  33,   1,
            -33,  -3, -14, -21, -13, -12, -39, -21,
        };
        int[] EG_BISHOP = {
            -14, -21, -11,  -8,  -7,  -9, -17, -24,
             -8,  -4,   7, -12,  -3, -13,  -4, -14,
              2,  -8,   0,  -1,  -2,   6,   0,   4,
             -3,   9,  12,   9,  14,  10,   3,   2,
             -6,   3,  13,  19,   7,  10,  -3,  -9,
            -12,  -3,   8,  10,  13,   3,  -7, -15,
            -14, -18,  -7,  -1,   4,  -9, -15, -27,
            -23,  -9, -23,  -5,  -9, -16,  -5, -17,
        };
        int[] MG_ROOK = {
             32,  42,  32,  51,  63,   9,  31,  43,
             27,  32,  58,  62,  80,  67,  26,  44,
             -5,  19,  26,  36,  17,  45,  61,  16,
            -24, -11,   7,  26,  24,  35,  -8, -20,
            -36, -26, -12,  -1,   9,  -7,   6, -23,
            -45, -25, -16, -17,   3,   0,  -5, -33,
            -44, -16, -20,  -9,  -1,  11,  -6, -71,
            -19, -13,   1,  17,  16,   7, -37, -26,
        };
        int[] EG_ROOK = {
             13,  10,  18,  15,  12,  12,   8,   5,
             11,  13,  13,  11,  -3,   3,   8,   3,
              7,   7,   7,   5,   4,  -3,  -5,  -3,
              4,   3,  13,   1,   2,   1,  -1,   2,
              3,   5,   8,   4,  -5,  -6,  -8, -11,
             -4,   0,  -5,  -1,  -7, -12,  -8, -16,
             -6,  -6,   0,   2,  -9,  -9, -11,  -3,
             -9,   2,   3,  -1,  -5, -13,   4, -20,
        };
        int[] MG_QUEEN = {
            -28,   0,  29,  12,  59,  44,  43,  45,
            -24, -39,  -5,   1, -16,  57,  28,  54,
            -13, -17,   7,   8,  29,  56,  47,  57,
            -27, -27, -16, -16,  -1,  17,  -2,   1,
             -9, -26,  -9, -10,  -2,  -4,   3,  -3,
            -14,   2, -11,  -2,  -5,   2,  14,   5,
            -35,  -8,  11,   2,   8,  15,  -3,   1,
             -1, -18,  -9,  10, -15, -25, -31, -50,
        };
        int[] EG_QUEEN = {
             -9,  22,  22,  27,  27,  19,  10,  20,
            -17,  20,  32,  41,  58,  25,  30,   0,
            -20,   6,   9,  49,  47,  35,  19,   9,
              3,  22,  24,  45,  57,  40,  57,  36,
            -18,  28,  19,  47,  31,  34,  39,  23,
            -16, -27,  15,   6,   9,  17,  10,   5,
            -22, -23, -30, -16, -16, -23, -36, -32,
            -33, -28, -22, -43,  -5, -32, -20, -41,
        };
        int[] MG_KING = {
            -65,  23,  16, -15, -56, -34,   2,  13,
             29,  -1, -20,  -7,  -8,  -4, -38, -29,
             -9,  24,   2, -16, -20,   6,  22, -22,
            -17, -20, -12, -27, -30, -25, -14, -36,
            -49,  -1, -27, -39, -46, -44, -33, -51,
            -14, -14, -22, -46, -44, -30, -15, -27,
              1,   7,  -8, -64, -43, -16,   9,   8,
            -15,  36,  12, -54,   8, -28,  24,  14,
        };
        int[] EG_KING = {
            -74, -35, -18, -18, -11,  15,   4, -17,
            -12,  17,  14,  17,  17,  38,  23,  11,
             10,  17,  23,  15,  20,  45,  44,  13,
             -8,  22,  24,  27,  26,  33,  26,   3,
            -18,  -4,  21,  24,  27,  23,   9, -11,
            -19,  -3,  11,  21,  23,  16,   7,  -9,
            -27, -11,   4,  13,  14,   4,  -5, -17,
            -53, -34, -21, -11, -28, -14, -24, -43,
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
        // PASSED_MG = {0, 5, 10, 20, 35, 60, 100, 0} — indices 1..6 are tunable
        int[] PASSED_MG = {0, 5, 10, 20, 35, 60, 100, 0};
        int[] PASSED_EG = {0, 10, 20, 40, 65, 105, 165, 0};
        for (int i = 0; i < 6; i++) {
            p[IDX_PASSED_MG_START + i] = PASSED_MG[i + 1];
            p[IDX_PASSED_EG_START + i] = PASSED_EG[i + 1];
        }
        p[IDX_ISOLATED_MG] = 15;
        p[IDX_ISOLATED_EG] = 20;
        p[IDX_DOUBLED_MG]  = 10;
        p[IDX_DOUBLED_EG]  = 20;

        // --- King safety ---
        p[IDX_SHIELD_RANK2]   = 15;
        p[IDX_SHIELD_RANK3]   = 10;
        p[IDX_OPEN_FILE]      = 25;
        p[IDX_HALF_OPEN_FILE] = 10;
        p[IDX_ATK_KNIGHT]     = 2;
        p[IDX_ATK_BISHOP]     = 2;
        p[IDX_ATK_ROOK]       = 3;
        p[IDX_ATK_QUEEN]      = 5;

        // --- Mobility ---
        // MG: N=4, B=3, R=2, Q=1
        p[IDX_MOB_MG_START]     = 4;  // Knight
        p[IDX_MOB_MG_START + 1] = 3;  // Bishop
        p[IDX_MOB_MG_START + 2] = 2;  // Rook
        p[IDX_MOB_MG_START + 3] = 1;  // Queen
        // EG: N=4, B=3, R=1, Q=2
        p[IDX_MOB_EG_START]     = 4;  // Knight
        p[IDX_MOB_EG_START + 1] = 3;  // Bishop
        p[IDX_MOB_EG_START + 2] = 1;  // Rook
        p[IDX_MOB_EG_START + 3] = 2;  // Queen

        return p;
    }

    /**
     * Writes tuned parameters to a human-readable file with labeled sections
     * so the developer can manually copy values back into the source files.
     */
    public static void writeToFile(double[] params, Path output) throws IOException {
        try (BufferedWriter w = new BufferedWriter(new FileWriter(output.toFile()))) {
            w.write("# Tuned parameters — copy values manually into engine-core source files\n");
            w.write("# Generated by engine-tuner Texel tuning pipeline\n\n");

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
