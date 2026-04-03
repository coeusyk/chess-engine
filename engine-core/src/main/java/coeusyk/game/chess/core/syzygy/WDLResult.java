package coeusyk.game.chess.core.syzygy;

/**
 * Result of a Syzygy WDL (Win/Draw/Loss) tablebase probe.
 */
public record WDLResult(WDL wdl, boolean valid) {

    public enum WDL {
        WIN, DRAW, LOSS
    }

    public static final WDLResult INVALID = new WDLResult(null, false);

    public static WDLResult win() {
        return new WDLResult(WDL.WIN, true);
    }

    public static WDLResult draw() {
        return new WDLResult(WDL.DRAW, true);
    }

    public static WDLResult loss() {
        return new WDLResult(WDL.LOSS, true);
    }
}
