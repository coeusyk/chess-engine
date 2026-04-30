package coeusyk.game.chess.tuner;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Bridge between the tuner parameter array and the live evaluator constants.
 *
 * Parameter layout (830 total):
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
 *
 *   [829]      hanging piece penalty (applied after phase interpolation)
 * </pre>
 */
public final class EvalParams {

    public static final int TOTAL_PARAMS = 832;

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
    public static final int IDX_HANGING_PENALTY       = 829;
    public static final int IDX_KING_SAFETY_SCALE      = 830;
    /** MG-only penalty per minor/rook attacked by enemy pawn with ≤1 safe retreat. */
    public static final int IDX_PIECE_ATK_BY_PAWN_MG  = 831;

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
        // Run #171: pin all king-safety params except ATK_ROOK=[20,45] and ATK_KNIGHT=[25,45].
        // Only rook and knight attacker weights are free; all other king-safety params are
        // locked at their current engine-core baseline values. Restore broad ranges after
        // this targeted retune is committed.
        lo[IDX_SHIELD_RANK2]  = 12;  lo[IDX_SHIELD_RANK3]   = 7;   // shield rank-2/3 pinned
        lo[IDX_OPEN_FILE]     = 45;  lo[IDX_HALF_OPEN_FILE] = 13;  // file penalties pinned
        lo[IDX_ATK_KNIGHT]    = 25;  // run 171: lower bound for free param
        lo[IDX_ATK_BISHOP]    = 2;   // pinned at engine-core baseline
        lo[IDX_ATK_ROOK]      = 20;  // run 171: lower bound for free param
        lo[IDX_ATK_QUEEN]     = 0;   // pinned at engine-core baseline
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
        lo[IDX_ROOK_OPEN_FILE_EG]  = -5;  // Rook open file EG >= -5 (engine uses -2)
        lo[IDX_ROOK_SEMI_OPEN_MG]  = 0;   // Rook semi-open file MG >= 0
        lo[IDX_ROOK_SEMI_OPEN_EG]  = 0;   // Rook semi-open file EG >= 0
        lo[IDX_KNIGHT_OUTPOST_MG]  = 0;   // Knight outpost MG >= 0
        lo[IDX_KNIGHT_OUTPOST_EG]  = 0;   // Knight outpost EG >= 0
        lo[IDX_CONNECTED_PAWN_MG]  = 0;   // Connected pawn MG >= 0
        lo[IDX_CONNECTED_PAWN_EG]  = 0;   // Connected pawn EG >= 0
        lo[IDX_BACKWARD_PAWN_MG]   = 0;   // Backward pawn penalty MG >= 0
        lo[IDX_BACKWARD_PAWN_EG]   = -5;  // Backward pawn penalty EG >= -5 (engine uses -1)
        lo[IDX_ROOK_BEHIND_PASSER_MG] = 0;  // Rook behind passer MG >= 0
        lo[IDX_ROOK_BEHIND_PASSER_EG] = 0;  // Rook behind passer EG >= 0
        lo[IDX_HANGING_PENALTY]       = 40;   // Run #171: pinned at current engine-core value
        lo[IDX_KING_SAFETY_SCALE]      = 100;  // Run #171: pinned at current engine-core value
        lo[IDX_PIECE_ATK_BY_PAWN_MG]  = -20;  // Run #171: pinned at current engine-core value
        return lo;
    }

    private static double[] buildMax() {
        double[] hi = new double[TOTAL_PARAMS];
        // Material: pawn MG hard-pinned at 100 (min==max), king MG/EG pinned at 0.
        // All other material values float freely with reasonable upper bounds.
        //           P-MG  P-EG  N-MG  N-EG  B-MG  B-EG  R-MG  R-EG  Q-MG   Q-EG  K-MG K-EG
        double[] matHi = { 100, 130,  450,  400,  450,  400,  600,  650,  1400, 1100, 0,   0 };
        System.arraycopy(matHi, 0, hi, 0, 12);
        Arrays.fill(hi, IDX_PST_START, IDX_PASSED_MG_START, 200);   // PST
        Arrays.fill(hi, IDX_PASSED_MG_START, IDX_PASSED_EG_START, 150); // Passed pawn MG
        Arrays.fill(hi, IDX_PASSED_EG_START, IDX_ISOLATED_MG,  200);    // Passed pawn EG
        Arrays.fill(hi, IDX_ISOLATED_MG, IDX_SHIELD_RANK2,      60);    // Pawn penalties
        Arrays.fill(hi, IDX_SHIELD_RANK2, IDX_MOB_MG_START,     80);    // King safety
        // Run #171: pin all king-safety params except ATK_ROOK=[20,45] and ATK_KNIGHT=[25,45].
        hi[IDX_SHIELD_RANK2]  = 12;  hi[IDX_SHIELD_RANK3]   = 7;   // shield rank-2/3 pinned
        hi[IDX_OPEN_FILE]     = 45;  hi[IDX_HALF_OPEN_FILE] = 13;  // file penalties pinned
        hi[IDX_ATK_KNIGHT]    = 45;  // run 171: upper bound for free param
        hi[IDX_ATK_BISHOP]    = 2;   // pinned at engine-core baseline
        hi[IDX_ATK_ROOK]      = 45;  // run 171: upper bound for free param
        hi[IDX_ATK_QUEEN]     = 0;   // pinned at engine-core baseline
        Arrays.fill(hi, IDX_MOB_MG_START, IDX_MOB_EG_START,     15);    // Mobility MG
        Arrays.fill(hi, IDX_MOB_EG_START, IDX_TEMPO,             15);    // Mobility EG
        hi[IDX_TEMPO]          = 30;   // Tempo bonus <= 30cp
        hi[IDX_BISHOP_PAIR_MG] = 60;   // Bishop pair MG <= 60cp
        hi[IDX_BISHOP_PAIR_EG] = 80;   // Bishop pair EG <= 80cp
        hi[IDX_ROOK_7TH_MG]   = 50;   // Rook on 7th MG <= 50cp
        hi[IDX_ROOK_7TH_EG]   = 50;   // Rook on 7th EG <= 50cp
        hi[IDX_ROOK_OPEN_FILE_MG]  = 100;  // Rook open file MG <= 100cp (raised from 80: value=50 pushing cap)
        hi[IDX_ROOK_OPEN_FILE_EG]  = 50;   // Rook open file EG <= 50cp
        hi[IDX_ROOK_SEMI_OPEN_MG]  = 30;   // Rook semi-open file MG <= 30cp
        hi[IDX_ROOK_SEMI_OPEN_EG]  = 30;   // Rook semi-open file EG <= 30cp
        hi[IDX_KNIGHT_OUTPOST_MG]  = 80;   // Knight outpost MG <= 80cp (raised from 60: value=40 approaching cap)
        hi[IDX_KNIGHT_OUTPOST_EG]  = 50;   // Knight outpost EG <= 50cp
        hi[IDX_CONNECTED_PAWN_MG]  = 25;   // Connected pawn MG <= 25cp
        hi[IDX_CONNECTED_PAWN_EG]  = 20;   // Connected pawn EG <= 20cp
        hi[IDX_BACKWARD_PAWN_MG]   = 25;   // Backward pawn penalty MG <= 25cp
        hi[IDX_BACKWARD_PAWN_EG]   = 20;   // Backward pawn penalty EG <= 20cp
        hi[IDX_ROOK_BEHIND_PASSER_MG] = 40;  // Rook behind passer MG <= 40cp
        hi[IDX_ROOK_BEHIND_PASSER_EG] = 50;  // Rook behind passer EG <= 50cp
        hi[IDX_HANGING_PENALTY]       = 40;   // Run #171: pinned at current engine-core value
        hi[IDX_KING_SAFETY_SCALE]      = 100;  // Run #171: pinned at current engine-core value
        hi[IDX_PIECE_ATK_BY_PAWN_MG]  = -20;  // Run #171: pinned at current engine-core value
        return hi;
    }

    /** Clamps a single parameter value to its legal range. */
    public static double clampOne(int i, double value) {
        return Math.max(PARAM_MIN[i], Math.min(PARAM_MAX[i], value));
    }

    /**
     * Returns {@code true} if the parameter at {@code idx} is a scalar evaluation
     * term (material value, pawn-structure bonus, king-safety weight, or other
     * non-PST term), and {@code false} if it is a piece-square-table entry.
     *
     * <p>Scalar params occupy indices [0, IDX_PST_START) and [IDX_PASSED_MG_START, TOTAL_PARAMS).
     * PST entries occupy [IDX_PST_START, IDX_PASSED_MG_START).
     * The logarithmic barrier (Issue #134) is applied only to scalar params.
     */
    public static boolean isScalarParam(int idx) {
        return idx < IDX_PST_START || idx >= IDX_PASSED_MG_START;
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

    /**
     * Builds a boolean mask array of length {@link #TOTAL_PARAMS} where only
     * parameters belonging to the named group are {@code true}.
     *
     * <p>Valid group names:
     * <ul>
     *   <li>{@code material}       — indices [0, 12)</li>
     *   <li>{@code pst}            — indices [12, 780)</li>
     *   <li>{@code pawn-structure} — indices [780, 796) ∪ [823, 827) (passed, isolated, doubled, connected, backward)</li>
     *   <li>{@code king-safety}    — indices [796, 804) ∪ {829, 830, 831} (shield, open files, attacker weights, hanging penalty, king safety scale, piece attacked by pawn)</li>
     *   <li>{@code mobility}       — indices [804, 812)</li>
     *   <li>{@code scalars}        — indices [812, 823) ∪ [827, 829) (tempo, bishop pair, rook bonuses, knight outpost, rook behind passer)</li>
     *   <li>{@code exit-gate}       — indices [780, 796) ∪ [800, 804) ∪ [823, 827) (pawn-structure + ATK_WEIGHT_*, exit-gate combined run)</li>
     * </ul>
     *
     * @param groupName one of the six group names listed above (case-sensitive)
     * @return boolean mask; pass {@code null} instead to tune all params
     * @throws IllegalArgumentException if {@code groupName} is not one of the valid names
     */
    public static boolean[] buildGroupMask(String groupName) {
        boolean[] mask = new boolean[TOTAL_PARAMS];
        switch (groupName) {
            case "material":
                java.util.Arrays.fill(mask, IDX_MATERIAL_START, IDX_PST_START, true);
                break;
            case "pst":
                java.util.Arrays.fill(mask, IDX_PST_START, IDX_PASSED_MG_START, true);
                break;
            case "pawn-structure":
                java.util.Arrays.fill(mask, IDX_PASSED_MG_START, IDX_SHIELD_RANK2, true);
                java.util.Arrays.fill(mask, IDX_CONNECTED_PAWN_MG, IDX_ROOK_BEHIND_PASSER_MG, true);
                break;
            case "king-safety":
                java.util.Arrays.fill(mask, IDX_SHIELD_RANK2, IDX_MOB_MG_START, true);
                mask[IDX_HANGING_PENALTY]         = true;
                mask[IDX_KING_SAFETY_SCALE]       = true;
                mask[IDX_PIECE_ATK_BY_PAWN_MG]   = true;
                break;
            case "mobility":
                java.util.Arrays.fill(mask, IDX_MOB_MG_START, IDX_TEMPO, true);
                break;
            case "scalars":
                java.util.Arrays.fill(mask, IDX_TEMPO, IDX_CONNECTED_PAWN_MG, true);
                java.util.Arrays.fill(mask, IDX_ROOK_BEHIND_PASSER_MG, IDX_HANGING_PENALTY, true);
                break;
            case "exit-gate":
                // PASSED_MG/EG[1..6], ISOLATED, DOUBLED  [780..795]
                java.util.Arrays.fill(mask, IDX_PASSED_MG_START, IDX_SHIELD_RANK2, true);
                // ATK_WEIGHT_KNIGHT/BISHOP/ROOK/QUEEN     [800..803]
                java.util.Arrays.fill(mask, IDX_ATK_KNIGHT, IDX_MOB_MG_START, true);
                // CONNECTED_PAWN, BACKWARD_PAWN           [823..826]
                java.util.Arrays.fill(mask, IDX_CONNECTED_PAWN_MG, IDX_ROOK_BEHIND_PASSER_MG, true);
                break;
            default:
                throw new IllegalArgumentException(
                    "Unknown param group: \"" + groupName + "\""
                    + " (valid: material, pst, pawn-structure, king-safety, mobility, scalars, exit-gate)");
        }
        return mask;
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
            case 829: return "HANGING_PENALTY";
            case 830: return "KING_SAFETY_SCALE";
            case 831: return "PIECE_ATK_BY_PAWN_MG";
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
        // Restored 2026-04-10: eval-mode Texel params reverted (issue #141 post-mortem).
        p[0]  = 100;  p[1]  = 89;    // Pawn  (MG pinned at 100)
        p[2]  = 391;  p[3]  = 287;   // Knight
        p[4]  = 428;  p[5]  = 311;   // Bishop
        p[6]  = 558;  p[7]  = 555;   // Rook
        p[8]  = 1200; p[9]  = 1040;  // Queen
        p[10] = 0;    p[11] = 0;     // King

        // --- PST tables ---
        // White tables from PieceSquareTables (a8=0 convention, rank 8 at top)
        int[] MG_PAWN = {
              0,    0,    0,    0,    0,    0,    0,    0,
             35,   78,   21,   63,   32,  -44,  -48, -119,
              5,    4,   28,   27,   78,   85,   29,   -4,
            -17,    0,    3,   21,   23,   17,    7,  -22,
            -29,  -26,   -4,    7,   14,   10,   -9,  -31,
            -27,  -23,   -8,  -12,    3,    1,   14,  -15,
            -35,  -18,  -27,  -16,  -12,   22,   22,  -26,
              0,    0,    0,    0,    0,    0,    0,    0,
        };
        int[] EG_PAWN = {
              0,    0,    0,    0,    0,    0,    0,    0,
            134,  114,   94,   61,   77,   88,  119,  159,
             52,   46,   18,  -18,  -36,  -14,   20,   32,
             32,   18,    5,  -16,  -10,   -4,   10,   15,
             18,   13,   -3,  -10,   -9,   -9,    0,    2,
              7,    6,   -4,   -1,   -1,   -4,  -11,   -8,
             14,    4,   11,   -1,   10,   -3,  -10,  -10,
              0,    0,    0,    0,    0,    0,    0,    0,
        };
        int[] MG_KNIGHT = {
            -167,  -80,  -43,  -46,   93, -106,  -11, -108,
             -55,  -18,   65,   52,   -3,   77,   18,   -2,
             -60,   44,   22,   38,   76,   86,   58,   13,
             -11,   11,    7,   41,   19,   51,    9,   15,
              -8,   14,   14,   14,   19,   13,   18,   -8,
              16,   31,   50,   54,   70,   61,   67,   27,
              15,   -1,   33,   55,   56,   61,   40,   35,
             -82,   28,    4,   22,   48,   27,   32,   26,
        };
        int[] EG_KNIGHT = {
             -18,   -9,   23,    3,  -15,    1,  -41,  -67,
               4,   26,    4,   26,   25,   -9,    3,  -27,
             -25,  -23,    6,    2,  -20,  -19,  -31,  -49,
             -16,    2,   22,   17,   19,    6,    6,  -23,
             -19,   -9,   14,   23,   14,   16,    5,  -23,
               4,   25,   22,   38,   32,   16,    3,    5,
              -9,    7,   19,   16,   18,    6,    5,  -23,
              17,  -22,    4,   16,    2,    7,  -26,  -45,
        };
        int[] MG_BISHOP = {
              -3,   -6, -126,  -76,  -50,  -52,  -20,    8,
             -13,   30,  -10,  -42,   37,    9,   28,  -53,
              -9,   47,   31,   33,   17,   55,   25,   -1,
              17,   34,   27,   62,   47,   37,   29,   16,
              29,   36,   35,   51,   60,   21,   36,   36,
              32,   49,   50,   44,   45,   71,   48,   46,
              45,   60,   49,   42,   56,   61,   84,   43,
               4,   42,   35,   33,   40,   37,   -4,   21,
        };
        int[] EG_BISHOP = {
               6,    9,   32,   26,   28,   22,   18,   -3,
              21,   16,   30,   22,   15,   25,   14,   16,
              30,   14,   22,   17,   16,   15,   26,   28,
              20,   26,   29,   22,   23,   25,   17,   25,
              15,   19,   30,   30,   17,   27,   14,   13,
              14,   19,   26,   27,   32,   12,   18,    9,
              12,    0,   14,   16,   16,   11,    0,   -7,
              10,   17,    9,   18,   15,   14,   24,    9,
        };
        int[] MG_ROOK = {
               3,   24,  -13,   32,   20,  -25,    3,  -10,
              12,   11,   52,   69,   81,   78,  -25,   25,
             -10,    9,    4,   18,  -14,   41,   80,   -2,
             -23,  -16,   11,   18,   12,   42,   -5,  -24,
             -25,  -13,   -6,    4,   15,    6,   26,  -14,
             -25,   -8,    1,   -2,   14,   25,   17,   -6,
             -18,   -1,   -2,   14,   25,   36,   22,  -37,
              14,   16,   24,   34,   40,   43,    2,   20,
        };
        int[] EG_ROOK = {
              45,   37,   49,   35,   40,   51,   45,   47,
              12,   14,    2,   -5,  -18,   -7,   23,    8,
              44,   43,   41,   37,   39,   27,   19,   35,
              46,   43,   46,   32,   36,   30,   36,   47,
              43,   42,   42,   33,   26,   27,   20,   32,
              33,   34,   24,   28,   18,   14,   19,   17,
              28,   24,   28,   28,   15,   13,   10,   30,
              23,   23,   24,   18,   12,    9,   22,    3,
        };
        int[] MG_QUEEN = {
               7,   -3,  -18,   -7,  126,  115,  120,   49,
               8,  -14,   19,   23,  -38,   70,   33,   76,
              26,   15,   22,   12,   28,  102,   68,   85,
               0,   14,    8,   11,   29,   34,   32,   26,
              41,    0,   36,   29,   35,   36,   32,   35,
              25,   56,   41,   51,   46,   48,   54,   47,
              21,   48,   66,   67,   77,   80,   58,   68,
              58,   56,   66,   75,   60,   49,   43,   -7,
        };
        int[] EG_QUEEN = {
              34,   86,  110,   96,   33,   37,   13,   92,
              34,   66,   78,   93,  145,   59,   66,   56,
              31,   63,   52,  110,  105,   68,   74,   60,
              74,   66,   71,   79,   97,   88,  113,  103,
              23,   90,   57,   85,   68,   75,  102,   83,
              47,    0,   48,   32,   50,   63,   72,   70,
              27,   12,    4,    6,   11,   10,   -3,    2,
              -5,  -10,    0,   -1,   27,    7,   11,   13,
        };
        int[] MG_KING = {
            -131,  200,  200,  136,  -92,  -40,  100,   -4,
             198,  121,   71,  148,   74,   49,   13, -145,
              77,  105,  135,   62,   81,  130,  161,  -28,
               6,   19,   39,  -20,  -15,  -21,  -13, -120,
             -82,   49,  -33,  -69,  -91,  -66,  -72, -112,
             -18,   -7,  -17,  -48,  -49,  -50,  -18,  -55,
              -3,   13,  -16,  -60,  -41,  -26,   15,   12,
             -29,   19,    4,  -67,    4,  -31,   14,   -1,
        };
        int[] EG_KING = {
             -81,  -73,  -58,  -42,    6,   24,  -19,  -29,
             -56,   -3,    8,   -3,   11,   38,   26,   30,
             -11,    9,    8,   13,    9,   35,   25,   11,
             -19,   24,   26,   42,   37,   45,   36,   17,
             -12,   -8,   36,   47,   53,   41,   26,    2,
             -24,    2,   24,   39,   41,   31,   11,   -7,
             -38,  -11,   16,   29,   28,   16,   -8,  -34,
             -71,  -52,  -23,    3,  -22,   -7,  -43,  -71,
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
        // PASSED_MG = {0, 8, 4, 0, 8, 10, 52, 0} — indices 1..6 are tunable
        int[] PASSED_MG = {0, 47, 40, 6, 52, 62, 122, 0};
        int[] PASSED_EG = {0, 33, 24, 37, 53, 124, 152, 0};
        for (int i = 0; i < 6; i++) {
            p[IDX_PASSED_MG_START + i] = PASSED_MG[i + 1];
            p[IDX_PASSED_EG_START + i] = PASSED_EG[i + 1];
        }
        p[IDX_ISOLATED_MG] = 16;
        p[IDX_ISOLATED_EG] = 13;
        p[IDX_DOUBLED_MG]  = 6;
        p[IDX_DOUBLED_EG]  = 34;

        // --- King safety ---
        p[IDX_SHIELD_RANK2]   = 12;
        p[IDX_SHIELD_RANK3]   = 7;
        p[IDX_OPEN_FILE]      = 45;
        p[IDX_HALF_OPEN_FILE] = 13;
        p[IDX_ATK_KNIGHT]     = 6;   // Run #171: synced with engine-core baseline (clamped to [25,45])
        p[IDX_ATK_BISHOP]     = 2;   // Run #171: synced with engine-core baseline (pinned)
        p[IDX_ATK_ROOK]       = 12;  // Run #171: synced with engine-core baseline (clamped to [20,45])
        p[IDX_ATK_QUEEN]      = 0;   // Run #171: synced with engine-core baseline (pinned)

        // --- Mobility ---
        // MG: N=7, B=8, R=7, Q=2
        p[IDX_MOB_MG_START]     = 7;  // Knight
        p[IDX_MOB_MG_START + 1] = 8;  // Bishop
        p[IDX_MOB_MG_START + 2] = 7;  // Rook
        p[IDX_MOB_MG_START + 3] = 2;  // Queen
        // EG: N=1, B=3, R=2, Q=6 (sync with EG_MOBILITY constants in Evaluator.java)
        p[IDX_MOB_EG_START]     = 1;  // Knight
        p[IDX_MOB_EG_START + 1] = 3;  // Bishop
        p[IDX_MOB_EG_START + 2] = 2;  // Rook
        p[IDX_MOB_EG_START + 3] = 6;  // Queen (was -4 from a non-merged tuning run; engine baseline is 6)

        // --- Bonus eval terms ---
        p[IDX_TEMPO]          = 12;  // Tempo bonus
        p[IDX_BISHOP_PAIR_MG] = 29;   // Bishop pair MG
        p[IDX_BISHOP_PAIR_EG] = 52;   // Bishop pair EG
        p[IDX_ROOK_7TH_MG]          = 0;    // Rook on 7th rank MG
        p[IDX_ROOK_7TH_EG]          = 32;   // Rook on 7th rank EG
        p[IDX_ROOK_OPEN_FILE_MG]    = 50;   // Rook on open file MG
        p[IDX_ROOK_OPEN_FILE_EG]    = 0;    // Rook on open file EG
        p[IDX_ROOK_SEMI_OPEN_MG]    = 18;   // Rook on semi-open file MG
        p[IDX_ROOK_SEMI_OPEN_EG]    = 19;   // Rook on semi-open file EG
        p[IDX_KNIGHT_OUTPOST_MG]    = 40;   // Knight outpost MG
        p[IDX_KNIGHT_OUTPOST_EG]    = 30;   // Knight outpost EG
        p[IDX_CONNECTED_PAWN_MG]    = 7;    // Connected pawn bonus MG
        p[IDX_CONNECTED_PAWN_EG]    = 6;    // Connected pawn bonus EG
        p[IDX_BACKWARD_PAWN_MG]     = 6;    // Backward pawn penalty MG
        p[IDX_BACKWARD_PAWN_EG]     = 20;    // Backward pawn penalty EG
        p[IDX_ROOK_BEHIND_PASSER_MG] = 12; // Rook behind passer MG
        p[IDX_ROOK_BEHIND_PASSER_EG] = 4;  // Rook behind passer EG
        p[IDX_HANGING_PENALTY]       = 40;  // Hanging piece penalty
        p[IDX_KING_SAFETY_SCALE]      = 100; // King safety scale (100 = neutral)
        p[IDX_PIECE_ATK_BY_PAWN_MG]  = -20; // Piece attacked by pawn MG penalty

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
            w.write(String.format("HANGING_PENALTY=%.0f%n", params[IDX_HANGING_PENALTY]));
            w.write(String.format("KING_SAFETY_SCALE=%.0f%n", params[IDX_KING_SAFETY_SCALE]));
            w.write(String.format("PIECE_ATTACKED_BY_PAWN_MG=%.0f%n", params[IDX_PIECE_ATK_BY_PAWN_MG]));

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
