package coeusyk.game.chess.core.syzygy;

import coeusyk.game.chess.core.models.Board;

/**
 * Interface for Syzygy endgame tablebase probing.
 * Implementations may use local files, online APIs, or return no-op results.
 */
public interface SyzygyProber {

    /**
     * Probe the WDL (Win/Draw/Loss) table for the given position.
     * Used during search to guide decisions.
     *
     * @param board the current board position
     * @return WDL result, or {@link WDLResult#INVALID} if probing is unavailable
     */
    WDLResult probeWDL(Board board);

    /**
     * Probe the DTZ (Distance To Zeroing) table for the given position.
     * Used at the root to select the optimal move.
     *
     * @param board the current board position
     * @return DTZ result with best move, or {@link DTZResult#INVALID} if probing is unavailable
     */
    DTZResult probeDTZ(Board board);

    /**
     * @return true if this prober is initialized and can attempt probing
     */
    boolean isAvailable();

    /**
     * @return the maximum number of pieces for which probing should be attempted
     */
    default int getPieceLimit() {
        return 5;
    }
}
