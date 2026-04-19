package coeusyk.game.chess.core.eval;

/**
 * Immutable configuration record holding all scalar eval constants that are
 * tunable but not part of the PST / material / king-safety arrays.
 *
 * <p>Using an immutable record (instead of 17 package-private {@code static int}
 * fields on {@code Evaluator}) provides two guarantees:
 * <ul>
 *   <li><b>SMP safety</b>: no live write path exists — the record is constructed
 *       once at class-load time and never mutated during play.</li>
 *   <li><b>Test isolation</b>: each test that needs non-default constants can
 *       construct its own {@code Evaluator(myConfig)} without polluting shared
 *       static state.</li>
 * </ul>
 *
 * <p>After a Texel tuning run, copy the new values from the tuner output file
 * into {@link Evaluator#DEFAULT_CONFIG} and commit.
 *
 * <p>Note: {@code tempo} is intentionally NOT a field here. It is read directly
 * from {@link EvalParams#TEMPO} at evaluation time, consistent with all other
 * overrideable {@link EvalParams} fields. This avoids the class-load snapshot
 * hazard that would arise from capturing it in a {@code static final} record.
 */
public record EvalConfig(
    int bishopPairMg,
    int bishopPairEg,

    int rook7thMg,
    int rook7thEg,

    int rookOpenFileMg,
    int rookOpenFileEg,
    int rookSemiOpenFileMg,
    int rookSemiOpenFileEg,

    int knightOutpostMg,
    int knightOutpostEg,

    int connectedPawnMg,
    int connectedPawnEg,

    int backwardPawnMg,
    int backwardPawnEg,

    int rookBehindPasserMg,
    int rookBehindPasserEg
) {}
