package coeusyk.game.chess.tuner;

/**
 * A chess position paired with its WDL training label.
 *
 * <p>{@code outcome} is the training signal from White's perspective:
 * 1.0 = White win, 0.5 = draw, 0.0 = Black win (game result), or a Stockfish
 * pseudo-WDL value computed via {@code sigmoid(sf_cp / 340.0)} for CSV corpora.
 *
 * <p>Stores a lightweight {@link TunerPosition} instead of a full {@code Board}
 * to keep memory usage manageable when loading 700k+ positions.
 */
public record LabelledPosition(TunerPosition pos, double outcome) {
}
