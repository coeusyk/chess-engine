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

    /** Attacker-pressure weight for enemy knights near the king. */
    public static int ATK_WEIGHT_KNIGHT = 6;

    /** Attacker-pressure weight for enemy bishops near the king. */
    public static int ATK_WEIGHT_BISHOP = 2;

    /** Attacker-pressure weight for enemy rooks near the king. */
    public static int ATK_WEIGHT_ROOK = 12;

    /**
     * Attacker-pressure weight for enemy queens near the king.
     * Fixed from -1 (semantically inverted — queen was reducing pressure) to a
     * positive value. Two-phase CLOP (Phase A, 300 iter, TC 3+0.03) converged to 0.
     */
    public static int ATK_WEIGHT_QUEEN = 0;

    // -----------------------------------------------------------------------
    // Evaluator constants
    // -----------------------------------------------------------------------

    /** Centipawn penalty applied per undefended attacked non-king piece. */
    public static int HANGING_PENALTY = 40;

    /**
     * Side-to-move bonus in centipawns (tempo).
     * 17 cp is historically reasonable (engines typically use 12–25 cp).
     */
    public static int TEMPO = 17;

    // -----------------------------------------------------------------------

    private EvalParams() {}

    /**
     * Reads KEY=VALUE pairs from {@code path} and updates the matching static
     * field. Unknown keys are silently ignored. Malformed integer values are
     * silently ignored. Lines starting with {@code #} and blank lines are skipped.
     *
     * <p><b>Recognised keys:</b> SHIELD_RANK2, SHIELD_RANK3, OPEN_FILE_PENALTY,
     * HALF_OPEN_FILE_PENALTY, ATK_WEIGHT_KNIGHT, ATK_WEIGHT_BISHOP,
     * ATK_WEIGHT_ROOK, ATK_WEIGHT_QUEEN, HANGING_PENALTY, TEMPO.
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
                    case "ATK_WEIGHT_KNIGHT":      ATK_WEIGHT_KNIGHT      = v; break;
                    case "ATK_WEIGHT_BISHOP":      ATK_WEIGHT_BISHOP      = v; break;
                    case "ATK_WEIGHT_ROOK":        ATK_WEIGHT_ROOK        = v; break;
                    case "ATK_WEIGHT_QUEEN":       ATK_WEIGHT_QUEEN       = v; break;
                    case "HANGING_PENALTY":        HANGING_PENALTY        = v; break;
                    case "TEMPO":                  TEMPO                  = v; break;
                    default:                                                   break;
                }
            } catch (NumberFormatException ignored) {
                // Skip lines with non-integer values
            }
        }
    }
}
