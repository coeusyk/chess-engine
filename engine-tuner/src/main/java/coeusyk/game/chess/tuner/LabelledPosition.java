package coeusyk.game.chess.tuner;

/**
 * A chess position paired with its training label.
 *
 * <p>In WDL mode ({@code --label-mode wdl}): {@code outcome} is the game result from
 * White's perspective (1.0 = White win, 0.5 = draw, 0.0 = Black win). {@code sfEvalCp}
 * is 0.0 and unused.
 *
 * <p>In eval mode ({@code --label-mode eval}): {@code sfEvalCp} is the Stockfish static
 * eval in centipawns from White's perspective (annotated by {@code tools/annotate_corpus.ps1}).
 * {@code outcome} is 0.0 and unused.
 *
 * <p>Stores a lightweight {@link TunerPosition} instead of a full {@code Board}
 * to keep memory usage manageable when loading 700k+ positions.
 */
public record LabelledPosition(TunerPosition pos, double outcome, double sfEvalCp) {

    /** WDL mode constructor — {@code sfEvalCp} set to 0.0 (not used in WDL mode). */
    public LabelledPosition(TunerPosition pos, double outcome) {
        this(pos, outcome, 0.0);
    }
}
