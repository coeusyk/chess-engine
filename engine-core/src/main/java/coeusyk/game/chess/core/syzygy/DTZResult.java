package coeusyk.game.chess.core.syzygy;

/**
 * Result of a Syzygy DTZ (Distance To Zeroing) tablebase probe.
 * Contains the best move in UCI notation and the WDL category.
 */
public record DTZResult(String bestMoveUci, int dtz, WDLResult.WDL wdl, boolean valid) {

    public static final DTZResult INVALID = new DTZResult(null, 0, null, false);
}
