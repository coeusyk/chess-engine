package coeusyk.game.chess.engine;

import coeusyk.game.chess.models.Board;
import coeusyk.game.chess.models.Move;
import coeusyk.game.chess.models.Piece;
import coeusyk.game.chess.utils.MovesGenerator;

import java.util.ArrayList;

/**
 * Iterative-deepening alpha-beta search integrated with {@link TimeManager}.
 *
 * <p>The search:
 * <ul>
 *   <li>Checks the abort signal at the <em>top</em> of each iterative-deepening
 *       iteration (soft limit — finish the current depth before stopping).</li>
 *   <li>Checks the hard limit inside the recursive search loop and aborts
 *       immediately when it is reached.</li>
 * </ul>
 */
public class Search {

    private static final int MAX_DEPTH = 64;
    private static final int INF = Integer.MAX_VALUE / 2;

    private final TimeManager timeManager;
    private final Evaluator evaluator;

    /** Set to true when the hard limit fires or {@link #abort()} is called. */
    private volatile boolean aborted;

    /** Best move found in the last fully completed iteration. */
    private Move bestMove;

    public Search(TimeManager timeManager) {
        this.timeManager = timeManager;
        this.evaluator = new Evaluator();
    }

    /**
     * Finds the best move using iterative deepening within the time budget
     * specified by {@code params}.
     *
     * @param board  the position to search (not mutated)
     * @param params UCI go-command parameters that define the time budget
     * @return the best move found, or {@code null} if no legal move exists
     */
    public Move findBestMove(Board board, GoParams params) {
        aborted = false;
        bestMove = null;

        int activeColor = board.getActiveColor();
        timeManager.start(params, activeColor);

        int maxDepth = (params.depth > 0) ? params.depth : MAX_DEPTH;

        for (int depth = 1; depth <= maxDepth; depth++) {
            // Check abort signal at the top of each iterative-deepening iteration.
            if (aborted || timeManager.isHardLimitReached()) {
                break;
            }
            if (depth > 1 && timeManager.isSoftLimitReached()) {
                break;
            }

            Move iterationBest = searchRoot(board, depth);

            // Only update bestMove from a fully completed iteration.
            if (!aborted) {
                bestMove = iterationBest;
            }
        }

        return bestMove;
    }

    /**
     * Signals the search to stop as soon as possible.
     * Thread-safe: may be called from a different thread.
     */
    public void abort() {
        aborted = true;
    }

    // -------------------------------------------------------------------------
    // Internal search routines
    // -------------------------------------------------------------------------

    private Move searchRoot(Board board, int depth) {
        ArrayList<Move> moves = new MovesGenerator(board).getActiveMoves(board.getActiveColor());

        Move localBest = null;
        int bestScore = -INF;

        for (Move move : moves) {
            // Check hard limit in the search loop.
            if (aborted || timeManager.isHardLimitReached()) {
                aborted = true;
                break;
            }

            Board child = applyMove(board, move);
            if (child == null) continue;

            int score = -alphaBeta(child, depth - 1, -INF, INF);

            if (!aborted && score > bestScore) {
                bestScore = score;
                localBest = move;
            }
        }

        return localBest;
    }

    private int alphaBeta(Board board, int depth, int alpha, int beta) {
        // Check hard limit inside the search loop.
        if (aborted || timeManager.isHardLimitReached()) {
            aborted = true;
            // Return alpha (best lower bound found so far) rather than 0 so that
            // any caller that still uses this result has the most accurate partial
            // evaluation available.
            return alpha;
        }

        if (depth == 0) {
            return evaluator.evaluate(board);
        }

        ArrayList<Move> moves = new MovesGenerator(board).getActiveMoves(board.getActiveColor());

        if (moves.isEmpty()) {
            // No legal moves available. Ideally we would distinguish checkmate
            // (large negative score) from stalemate (0), but that requires a
            // check-detection routine not yet present in the codebase. Return 0
            // (draw score) as a conservative approximation.
            return 0;
        }

        for (Move move : moves) {
            if (aborted || timeManager.isHardLimitReached()) {
                aborted = true;
                // Return the best bound found before the cutoff.
                return alpha;
            }

            Board child = applyMove(board, move);
            if (child == null) continue;

            int score = -alphaBeta(child, depth - 1, -beta, -alpha);

            if (aborted) return alpha;

            if (score >= beta) {
                return beta;
            }
            if (score > alpha) {
                alpha = score;
            }
        }

        return alpha;
    }

    /**
     * Returns a new {@link Board} with {@code move} applied, or {@code null}
     * if the move cannot be applied.
     */
    private Board applyMove(Board board, Move move) {
        try {
            Board next = copyBoard(board);
            next.makeMove(move);
            return next;
        } catch (Exception e) {
            return null;
        }
    }

    /** Creates a shallow copy of the board by re-parsing the same state. */
    private Board copyBoard(Board board) {
        // Build a minimal FEN from the current board state.
        StringBuilder fen = new StringBuilder();

        for (int rank = 0; rank < 8; rank++) {
            int empty = 0;
            for (int file = 0; file < 8; file++) {
                int piece = board.getPiece(rank * 8 + file);
                if (piece == Piece.None) {
                    empty++;
                } else {
                    if (empty > 0) {
                        fen.append(empty);
                        empty = 0;
                    }
                    fen.append(pieceChar(piece));
                }
            }
            if (empty > 0) fen.append(empty);
            if (rank < 7) fen.append('/');
        }

        fen.append(' ');
        fen.append(board.getActiveColor() == Piece.White ? 'w' : 'b');

        // Castling availability.
        fen.append(' ');
        boolean[] ca = board.getCastlingAvailability();
        StringBuilder castling = new StringBuilder();
        if (ca[0]) castling.append('K');
        if (ca[1]) castling.append('Q');
        if (ca[2]) castling.append('k');
        if (ca[3]) castling.append('q');
        fen.append(castling.length() > 0 ? castling : "-");

        // En-passant target square.
        fen.append(' ');
        int ep = board.getEpTargetSquare();
        if (ep > 0) {
            char file = (char) ('a' + (ep % 8));
            int rank = 8 - (ep / 8);
            fen.append(file).append(rank);
        } else {
            fen.append('-');
        }

        fen.append(' ').append(board.getHalfmoveClock());
        fen.append(' ').append(board.getFullMoves());

        return new Board(fen.toString());
    }

    private char pieceChar(int piece) {
        int type = Piece.type(piece);
        char c = switch (type) {
            case Piece.Pawn   -> 'p';
            case Piece.Knight -> 'n';
            case Piece.Bishop -> 'b';
            case Piece.Rook   -> 'r';
            case Piece.Queen  -> 'q';
            case Piece.King   -> 'k';
            default           -> '?';
        };
        return Piece.isWhite(piece) ? Character.toUpperCase(c) : c;
    }
}
