package coeusyk.game.chess.core.syzygy;

import coeusyk.game.chess.core.models.Board;

/**
 * No-op Syzygy prober that always returns invalid results.
 * Used as the default when no tablebase path is configured.
 */
public class NoOpSyzygyProber implements SyzygyProber {

    @Override
    public WDLResult probeWDL(Board board) {
        return WDLResult.INVALID;
    }

    @Override
    public DTZResult probeDTZ(Board board) {
        return DTZResult.INVALID;
    }

    @Override
    public boolean isAvailable() {
        return false;
    }
}
