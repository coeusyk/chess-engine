package coeusyk.game.chess.tuner;

/**
 * A chess position paired with its game outcome from White's perspective:
 *   1.0 = White win, 0.5 = draw, 0.0 = Black win (White loss).
 *
 * <p>Stores a lightweight {@link TunerPosition} instead of a full {@code Board}
 * to keep memory usage manageable when loading 700k+ positions.
 */
public record LabelledPosition(TunerPosition pos, double outcome) {}
