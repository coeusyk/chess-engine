package coeusyk.game.chess.engine;

/**
 * Parameters parsed from the UCI {@code go} command.
 * Fields default to zero / false, indicating the parameter was not supplied.
 */
public class GoParams {
    /** White's remaining clock time in milliseconds. */
    public long wtime = 0;

    /** Black's remaining clock time in milliseconds. */
    public long btime = 0;

    /** White's per-move increment in milliseconds. */
    public long winc = 0;

    /** Black's per-move increment in milliseconds. */
    public long binc = 0;

    /** Exact time to search in milliseconds (overrides clock-based limits). */
    public long movetime = 0;

    /** Number of moves until the next time control (0 = unknown). */
    public int movesToGo = 0;

    /** Maximum search depth (0 = unlimited). */
    public int depth = 0;

    /** When true the engine searches until an explicit {@code stop} command. */
    public boolean infinite = false;
}
