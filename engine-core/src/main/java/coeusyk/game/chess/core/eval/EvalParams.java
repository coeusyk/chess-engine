package coeusyk.game.chess.core.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runtime-overrideable evaluation constants used by CLOP / parameter-search tuning.
 *
 * <p>All fields are {@code public static int} (not {@code final}) so that
 * {@link #loadOverrides(Path)} can update them before the engine enters its UCI loop.
 * They must be written <em>once</em>, at startup, before any search thread is spawned.
 * There is no CAS or lock: the contract is single-writer/multi-reader with a
 * happens-before established by the UCI main-thread initialization.
 *
 * <p>Default values match the hard-coded constants that previously lived in
 * {@link KingSafety} and {@link Evaluator}.
 *
 * <p>If {@link #loadOverrides(Path)} is never called the engine behaves identically
 * to the previous build.
 */
public final class EvalParams {

    // -----------------------------------------------------------------------
    // KingSafety constants
    // -----------------------------------------------------------------------

    /** Bonus per pawn on rank immediately in front of a castled king. */
    public static int SHIELD_RANK2 = 12;

    /** Bonus per pawn on the rank two steps in front of a castled king. */
    public static int SHIELD_RANK3 = 7;

    /** Penalty per open file adjacent to (or on) the king's file. */
    public static int OPEN_FILE_PENALTY = 45;

    /** Penalty per half-open file adjacent to (or on) the king's file. */
    public static int HALF_OPEN_FILE_PENALTY = 13;

    /**
     * Scale (in percent) applied to defender-side pawn-shield/open-file score when kings are
     * on opposite flanks. 100 = no discount, 75 = 0.75x.
     */
    public static int OPPOSITE_FLANK_SHIELD_SCALE = 75;

    /** Attacker-pressure weight for enemy knights near the king. */
    public static int ATK_WEIGHT_KNIGHT = 6;

    /** Attacker-pressure weight for enemy bishops near the king. */
    public static int ATK_WEIGHT_BISHOP = 2;

    /** Attacker-pressure weight for enemy rooks near the king. */
    public static int ATK_WEIGHT_ROOK = 12;

    /** Attacker-pressure weight for enemy queens near the king. */
    public static int ATK_WEIGHT_QUEEN = 0;

    /**
     * Global percentage scale applied to the attacker-pressure king safety penalty
     * (i.e., the SAFETY_TABLE lookup result). 100 = current strength; larger values
     * increase the penalty proportionally. Tunable via the engine-tuner king-safety
     * group; runtime override via EvalParams override file.
     */
    public static int KING_SAFETY_SCALE = 100;

    // -----------------------------------------------------------------------
    // Evaluator constants
    // -----------------------------------------------------------------------

    /** Centipawn penalty applied per undefended attacked non-king piece. */
    public static int HANGING_PENALTY = 40;

    /**
     * MG-only penalty (centipawns, typically negative) per minor piece or rook that is
     * attacked by an enemy pawn and has at most one safe retreat square.  Applied to
     * mgScore before phase interpolation; tapers naturally to zero in the endgame.
     */
    public static int PIECE_ATTACKED_BY_PAWN_MG = -20;

    /**
     * Side-to-move bonus in centipawns (tempo).
     * 17 cp is historically reasonable (engines typically use 12–25 cp).
     */
    public static int TEMPO = 17;

    // -----------------------------------------------------------------------
    // Search constants (contempt) — tunable via CLOP override file
    // -----------------------------------------------------------------------

    /**
     * Minimum side-to-move MG material+PST advantage (centipawns) required for contempt
     * to activate.  Positions within ±CONTEMPT_THRESHOLD of equality always return a
     * neutral draw score (0) regardless of the contempt setting.
     */
    public static int CONTEMPT_THRESHOLD = 150;

    /**
     * Default draw-contempt penalty in centipawns.  Applied as a negative score to
     * repetition/50-move draws when the side to move holds an advantage exceeding
     * {@link #CONTEMPT_THRESHOLD}.  Also the value exposed via the UCI {@code Contempt}
     * option spin default.
     */
    public static int CONTEMPT_VALUE = 50;

    // -----------------------------------------------------------------------
    // Pawn structure — passed pawn rank bonuses
    // -----------------------------------------------------------------------

    /**
     * Passed pawn MG bonus indexed 0..7 (rank index in direction of advancement).
     * Index 0 (start rank) and 7 (promotion rank) are pinned at 0; indices 1..6 are tunable.
     * Default values match the previously hardcoded {@code PawnStructure.PASSED_MG} array.
     */
    public static int[] PASSED_PAWN_RANK_BONUS_MG = {0, 8, 4, 0, 8, 10, 52, 0};

    /**
     * Passed pawn EG bonus indexed 0..7 (rank index in direction of advancement).
     * Index 0 (start rank) and 7 (promotion rank) are pinned at 0; indices 1..6 are tunable.
     * Default values match the previously hardcoded {@code PawnStructure.PASSED_EG} array.
     */
    public static int[] PASSED_PAWN_RANK_BONUS_EG = {0, 5, 11, 32, 59, 129, 129, 0};

    // -----------------------------------------------------------------------

    private EvalParams() {}

    /**
     * Reads KEY=VALUE pairs from {@code path} and updates the matching static
     * field. Unknown keys are silently ignored. Malformed integer values are
     * silently ignored. Lines starting with {@code #} and blank lines are skipped.
     *
     * <p><b>Recognised keys:</b> SHIELD_RANK2, SHIELD_RANK3, OPEN_FILE_PENALTY,
    * HALF_OPEN_FILE_PENALTY, OPPOSITE_FLANK_SHIELD_SCALE, ATK_WEIGHT_KNIGHT, ATK_WEIGHT_BISHOP,
     * ATK_WEIGHT_ROOK, ATK_WEIGHT_QUEEN, KING_SAFETY_SCALE, HANGING_PENALTY,
     * PIECE_ATTACKED_BY_PAWN_MG, TEMPO, CONTEMPT_THRESHOLD, CONTEMPT_VALUE,
     * PASSED_MG_1..PASSED_MG_6, PASSED_EG_1..PASSED_EG_6.
     *
     * @param path path to the override file; must be readable
     * @throws IOException if the file cannot be read
     */
    public static void loadOverrides(Path path) throws IOException {
        for (String raw : Files.readAllLines(path)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String key = line.substring(0, eq).trim();
            String val = line.substring(eq + 1).trim();
            try {
                int v = Integer.parseInt(val);
                switch (key) {
                    case "SHIELD_RANK2":           SHIELD_RANK2           = v; break;
                    case "SHIELD_RANK3":           SHIELD_RANK3           = v; break;
                    case "OPEN_FILE_PENALTY":      OPEN_FILE_PENALTY      = v; break;
                    case "HALF_OPEN_FILE_PENALTY": HALF_OPEN_FILE_PENALTY = v; break;
                    case "OPPOSITE_FLANK_SHIELD_SCALE": OPPOSITE_FLANK_SHIELD_SCALE = Math.max(0, Math.min(100, v)); break;
                    case "ATK_WEIGHT_KNIGHT":      ATK_WEIGHT_KNIGHT      = v; break;
                    case "ATK_WEIGHT_BISHOP":      ATK_WEIGHT_BISHOP      = v; break;
                    case "ATK_WEIGHT_ROOK":        ATK_WEIGHT_ROOK        = v; break;
                    case "ATK_WEIGHT_QUEEN":       ATK_WEIGHT_QUEEN       = v; break;
                    case "KING_SAFETY_SCALE":      KING_SAFETY_SCALE      = v; break;
                    case "HANGING_PENALTY":        HANGING_PENALTY        = v; break;
                    case "PIECE_ATTACKED_BY_PAWN_MG": PIECE_ATTACKED_BY_PAWN_MG = v; break;
                    case "TEMPO":                  TEMPO                  = v; break;
                    // Clamp to [0, 32767] — prevents -CONTEMPT_THRESHOLD negation
                    // overflow in Searcher.contemptScore() for pathological override files.
                    case "CONTEMPT_THRESHOLD":     CONTEMPT_THRESHOLD     = Math.max(0, Math.min(32767, v)); break;
                    case "CONTEMPT_VALUE":         CONTEMPT_VALUE         = Math.max(0, Math.min(32767, v)); break;
                    case "PASSED_MG_1":             PASSED_PAWN_RANK_BONUS_MG[1] = v; break;
                    case "PASSED_MG_2":             PASSED_PAWN_RANK_BONUS_MG[2] = v; break;
                    case "PASSED_MG_3":             PASSED_PAWN_RANK_BONUS_MG[3] = v; break;
                    case "PASSED_MG_4":             PASSED_PAWN_RANK_BONUS_MG[4] = v; break;
                    case "PASSED_MG_5":             PASSED_PAWN_RANK_BONUS_MG[5] = v; break;
                    case "PASSED_MG_6":             PASSED_PAWN_RANK_BONUS_MG[6] = v; break;
                    case "PASSED_EG_1":             PASSED_PAWN_RANK_BONUS_EG[1] = v; break;
                    case "PASSED_EG_2":             PASSED_PAWN_RANK_BONUS_EG[2] = v; break;
                    case "PASSED_EG_3":             PASSED_PAWN_RANK_BONUS_EG[3] = v; break;
                    case "PASSED_EG_4":             PASSED_PAWN_RANK_BONUS_EG[4] = v; break;
                    case "PASSED_EG_5":             PASSED_PAWN_RANK_BONUS_EG[5] = v; break;
                    case "PASSED_EG_6":             PASSED_PAWN_RANK_BONUS_EG[6] = v; break;
                    default:                                                   break;
                }
            } catch (NumberFormatException e) {
                System.err.println("[EvalParams] Skipping malformed override line (non-integer value): " + raw.trim());
            }
        }
    }
}
