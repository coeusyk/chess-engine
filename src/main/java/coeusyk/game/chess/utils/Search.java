package coeusyk.game.chess.utils;

import coeusyk.game.chess.models.Board;
import coeusyk.game.chess.models.Move;
import coeusyk.game.chess.models.Piece;

import java.util.ArrayList;


public class Search {

    private int nodes = 0;
    private int qNodes = 0;

    public int getNodes() {
        return nodes;
    }

    public int getQNodes() {
        return qNodes;
    }

    /**
     * Finds the best move for the side to move using alpha-beta search with
     * quiescence search at the leaf nodes.
     *
     * @param board the current position
     * @param depth the search depth (quiescence search activates at depth 0)
     * @return the best move found, or null if no legal moves exist
     */
    public Move findBestMove(Board board, int depth) {
        nodes = 0;
        qNodes = 0;

        MovesGenerator gen = new MovesGenerator(board);
        ArrayList<Move> moves = gen.getActiveMoves(board.getActiveColor());

        if (moves.isEmpty()) return null;

        Move bestMove = null;
        int bestScore = Integer.MIN_VALUE + 1;

        for (Move move : moves) {
            Board child = new Board(board);
            child.makeMove(move);

            int score = -alphaBeta(child, depth - 1, Integer.MIN_VALUE + 1, Integer.MAX_VALUE);

            if (score > bestScore) {
                bestScore = score;
                bestMove = move;
            }
        }

        return bestMove;
    }

    /**
     * Alpha-beta minimax search. Calls quiescence search at depth <= 0.
     */
    private int alphaBeta(Board board, int depth, int alpha, int beta) {
        nodes++;

        if (depth <= 0) {
            return quiescenceSearch(board, alpha, beta);
        }

        MovesGenerator gen = new MovesGenerator(board);
        ArrayList<Move> moves = gen.getActiveMoves(board.getActiveColor());

        if (moves.isEmpty()) {
            return 0;
        }

        for (Move move : moves) {
            Board child = new Board(board);
            child.makeMove(move);

            int score = -alphaBeta(child, depth - 1, -beta, -alpha);

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
     * Quiescence search: extends the search at leaf nodes by only considering
     * captures and queen promotions until the position is quiet. This eliminates
     * horizon effect artifacts where the engine misses a capture just beyond its
     * search depth.
     *
     * <p>Stand-pat cutoff: if the static evaluation already beats beta, we can
     * return immediately without searching further.
     */
    private int quiescenceSearch(Board board, int alpha, int beta) {
        qNodes++;

        int standPat = Evaluator.evaluate(board);

        // Stand-pat cutoff: position is already good enough for the side to move.
        if (standPat >= beta) {
            return beta;
        }

        if (standPat > alpha) {
            alpha = standPat;
        }

        MovesGenerator gen = new MovesGenerator(board);
        ArrayList<Move> moves = gen.getActiveMoves(board.getActiveColor());
        ArrayList<Move> captureMoves = filterCapturesAndPromotions(board, moves);

        for (Move move : captureMoves) {
            Board child = new Board(board);
            child.makeMove(move);

            int score = -quiescenceSearch(child, -beta, -alpha);

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
     * Filters a move list to only captures (including en-passant) and queen
     * promotions, as required by quiescence search.
     */
    private ArrayList<Move> filterCapturesAndPromotions(Board board, ArrayList<Move> moves) {
        ArrayList<Move> result = new ArrayList<>();
        int[] grid = board.getGrid();

        for (Move move : moves) {
            // Capture: the target square is occupied by an enemy piece.
            if (grid[move.targetSquare] != Piece.None) {
                result.add(move);
                continue;
            }

            // En-passant is a capture even though the target square is empty.
            if ("en-passant".equals(move.reaction)) {
                result.add(move);
                continue;
            }

            // Queen promotion: pawn reaches the back rank.
            int movingPiece = grid[move.startSquare];
            if (Piece.type(movingPiece) == Piece.Pawn) {
                int targetRank = move.targetSquare / 8;
                if ((Piece.isWhite(movingPiece) && targetRank == 0) ||
                    (Piece.isBlack(movingPiece) && targetRank == 7)) {
                    result.add(move);
                }
            }
        }

        return result;
    }
}
