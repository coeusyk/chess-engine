package coeusyk.game.chess.core.selfplay.vspr;

/**
 * DR-220 section 5: one emitted training sample. {@code ply} is stored as {@code long} even
 * though the wire field is {@code u32} -- it is compared against {@code playedMoveCount} (also
 * read as {@code long}, DR-220 section 6/7), never used as an array size, so no narrowing to
 * {@code int} is needed here.
 */
public record TrainingSample(
        long ply,
        String fen,
        boolean onTrajectory,
        ScoreKind evalScoreKind,
        int evalScore,
        SearchBudgetKind searchBudgetKind,
        long searchBudgetValue) {
}
