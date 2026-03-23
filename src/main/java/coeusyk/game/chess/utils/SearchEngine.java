package coeusyk.game.chess.utils;

import coeusyk.game.chess.models.Board;
import coeusyk.game.chess.models.Move;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;


/**
 * Alpha-beta search engine with transposition-table integration.
 * <p>
 * The engine uses a fail-hard alpha-beta search.  At each node the transposition
 * table is probed first; if a usable entry is found the score (or bound) is
 * applied before searching further.  The best move stored in a TT hit is moved
 * to the front of the move list so it is searched first (move ordering).
 * <p>
 * After each root call {@link #findBestMove} the TT hit rate is logged at INFO
 * level so repeated-position detection can be verified.
 */
public class SearchEngine {

    private static final Logger log = LoggerFactory.getLogger(SearchEngine.class);

    private static final int INF = 1_000_000;

    private final TranspositionTable tt;
    private final Evaluator evaluator;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /** Creates a search engine with a default 64 MB transposition table. */
    public SearchEngine() {
        this(new TranspositionTable());
    }

    /**
     * Creates a search engine backed by the given transposition table.
     *
     * @param tt the transposition table to use
     */
    public SearchEngine(TranspositionTable tt) {
        this.tt = tt;
        this.evaluator = new Evaluator();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Searches for the best move for the side to move in {@code board} up to
     * {@code depth} plies and returns it.
     *
     * @param board the position to search
     * @param depth search depth in plies (≥ 1)
     * @return the best move found, or {@code null} if no legal move exists
     */
    public Move findBestMove(Board board, int depth) {
        MovesGenerator movesGen = new MovesGenerator(board);
        ArrayList<Move> moves = movesGen.getActiveMoves(board.getActiveColor());

        if (moves.isEmpty()) {
            return null;
        }

        // Order moves: try the TT move first
        long hash = ZobristHasher.computeHash(board);
        TranspositionTable.TTEntry ttEntry = tt.probe(hash);
        orderMoves(moves, ttEntry != null ? ttEntry.bestMove : null);

        Move bestMove = null;
        int bestScore = -INF;

        for (Move move : moves) {
            Board child = new Board(board);
            child.makeMove(move);
            int score = -alphaBeta(child, depth - 1, -INF, INF);

            if (score > bestScore) {
                bestScore = score;
                bestMove = move;
            }
        }

        tt.store(hash, bestMove, depth, bestScore, TranspositionTable.EXACT);
        tt.logHitRate();
        return bestMove;
    }

    /** Returns the transposition table used by this engine. */
    public TranspositionTable getTranspositionTable() {
        return tt;
    }

    // -------------------------------------------------------------------------
    // Search
    // -------------------------------------------------------------------------

    /**
     * Recursive fail-hard alpha-beta search.
     *
     * @param board the position to evaluate
     * @param depth remaining plies to search
     * @param alpha lower bound (best score for the maximiser so far)
     * @param beta  upper bound (best score for the minimiser so far)
     * @return the score of the position from the current side-to-move's perspective
     */
    private int alphaBeta(Board board, int depth, int alpha, int beta) {
        long hash = ZobristHasher.computeHash(board);

        // --- Transposition table probe ---
        TranspositionTable.TTEntry entry = tt.probe(hash);
        if (entry != null && entry.depth >= depth) {
            switch (entry.flag) {
                case TranspositionTable.EXACT:
                    return entry.score;
                case TranspositionTable.LOWER_BOUND:
                    alpha = Math.max(alpha, entry.score);
                    break;
                case TranspositionTable.UPPER_BOUND:
                    beta = Math.min(beta, entry.score);
                    break;
                default:
                    break;
            }
            if (alpha >= beta) {
                return entry.score;
            }
        }

        // --- Leaf node ---
        if (depth == 0) {
            return evaluator.evaluate(board);
        }

        // --- Generate and order moves ---
        MovesGenerator movesGen = new MovesGenerator(board);
        ArrayList<Move> moves = movesGen.getActiveMoves(board.getActiveColor());

        if (moves.isEmpty()) {
            // No legal moves: stalemate / simplified terminal node
            return 0;
        }

        orderMoves(moves, entry != null ? entry.bestMove : null);

        // --- Search ---
        int originalAlpha = alpha;
        Move bestMove = null;
        int bestScore = -INF;

        for (Move move : moves) {
            Board child = new Board(board);
            child.makeMove(move);
            int score = -alphaBeta(child, depth - 1, -beta, -alpha);

            if (score > bestScore) {
                bestScore = score;
                bestMove = move;
            }

            if (score > alpha) {
                alpha = score;
            }

            if (alpha >= beta) {
                // Beta cut-off – store as lower bound
                tt.store(hash, bestMove, depth, bestScore, TranspositionTable.LOWER_BOUND);
                return bestScore;
            }
        }

        // Determine bound type and store
        int flag = (bestScore <= originalAlpha)
                ? TranspositionTable.UPPER_BOUND
                : TranspositionTable.EXACT;
        tt.store(hash, bestMove, depth, bestScore, flag);

        return bestScore;
    }

    // -------------------------------------------------------------------------
    // Move ordering
    // -------------------------------------------------------------------------

    /**
     * Moves the TT move to the front of the list if it is present, so it is
     * searched first.  All other moves retain their original order.
     *
     * @param moves  list of legal moves to reorder (modified in place)
     * @param ttMove best move from the transposition table (may be {@code null})
     */
    private void orderMoves(ArrayList<Move> moves, Move ttMove) {
        if (ttMove == null) {
            return;
        }
        for (int i = 0; i < moves.size(); i++) {
            Move m = moves.get(i);
            if (m.startSquare == ttMove.startSquare && m.targetSquare == ttMove.targetSquare) {
                moves.remove(i);
                moves.add(0, m);
                return;
            }
        }
    }
}
