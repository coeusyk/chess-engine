package coeusyk.game.chess.tuner;

import coeusyk.game.chess.core.models.Board;

/**
 * Compact, immutable snapshot of a chess position for tuning.
 *
 * <p>A full {@link Board} instance pre-allocates a 768-element unmake pool,
 * making it far too heavy to keep 700k+ of them alive simultaneously.
 * {@code TunerPosition} stores only the 12 piece bitboards, the side to move,
 * and the original FEN string (for reconstructing a full {@code Board} when
 * qsearch is needed).
 *
 * <p>Memory per instance: ~140 bytes vs ~60 KB for a {@code Board}.
 */
public final class TunerPosition implements PositionData {

    private final long wp, bp, wn, bn, wb, bb, wr, br, wq, bq, wk, bk;
    private final int activeColor;
    private final String fen;

    private TunerPosition(long wp, long bp, long wn, long bn, long wb, long bb,
                          long wr, long br, long wq, long bq, long wk, long bk,
                          int activeColor, String fen) {
        this.wp = wp; this.bp = bp; this.wn = wn; this.bn = bn;
        this.wb = wb; this.bb = bb; this.wr = wr; this.br = br;
        this.wq = wq; this.bq = bq; this.wk = wk; this.bk = bk;
        this.activeColor = activeColor;
        this.fen = fen;
    }

    /**
     * Extracts a compact snapshot from a full {@link Board}.
     *
     * @param board the board to snapshot
     * @param fen   the FEN string that produced this board (kept for {@link #toBoard()})
     */
    public static TunerPosition from(Board board, String fen) {
        return new TunerPosition(
                board.getWhitePawns(),   board.getBlackPawns(),
                board.getWhiteKnights(), board.getBlackKnights(),
                board.getWhiteBishops(), board.getBlackBishops(),
                board.getWhiteRooks(),   board.getBlackRooks(),
                board.getWhiteQueens(),  board.getBlackQueens(),
                board.getWhiteKing(),    board.getBlackKing(),
                board.getActiveColor(),  fen);
    }

    /**
     * Convenience overload that derives the FEN from the board.
     */
    public static TunerPosition from(Board board) {
        return from(board, board.toFen());
    }

    /** Reconstructs a full {@link Board} for qsearch make/unmake. */
    public Board toBoard() {
        return new Board(fen);
    }

    /** Returns the stored FEN string. */
    public String fen() {
        return fen;
    }

    // --- PositionData implementation ---

    @Override public long getWhitePawns()   { return wp; }
    @Override public long getBlackPawns()   { return bp; }
    @Override public long getWhiteKnights() { return wn; }
    @Override public long getBlackKnights() { return bn; }
    @Override public long getWhiteBishops() { return wb; }
    @Override public long getBlackBishops() { return bb; }
    @Override public long getWhiteRooks()   { return wr; }
    @Override public long getBlackRooks()   { return br; }
    @Override public long getWhiteQueens()  { return wq; }
    @Override public long getBlackQueens()  { return bq; }
    @Override public long getWhiteKing()    { return wk; }
    @Override public long getBlackKing()    { return bk; }

    @Override
    public long getWhiteOccupancy() {
        return wp | wn | wb | wr | wq | wk;
    }

    @Override
    public long getBlackOccupancy() {
        return bp | bn | bb | br | bq | bk;
    }

    @Override
    public int getActiveColor() {
        return activeColor;
    }
}
