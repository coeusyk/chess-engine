package coeusyk.game.chess.search;

import coeusyk.game.chess.models.Board;
import coeusyk.game.chess.models.Move;
import coeusyk.game.chess.models.Piece;
import coeusyk.game.chess.utils.MovesGenerator;

import java.util.ArrayList;


/**
 * Alpha-beta negamax search with iterative deepening.
 *
 * <p>Each iteration deepens by one ply starting from depth 1. The best move
 * found in iteration N is placed first in the move list for iteration N+1,
 * providing simple but effective move ordering.
 *
 * <p>The search can be aborted between iterations by calling {@link #stop()}.
 * The last <em>fully completed</em> iteration's best move is always returned.
 */
public class IterativeDeepeningSearch {

    private static final int NEG_INF = Integer.MIN_VALUE / 2;  // halved to avoid overflow when negating
    private static final int POS_INF = Integer.MAX_VALUE / 2;

    private volatile boolean stopped = false;
    private Move bestMoveThisIteration;
    private int nodesSearched;

    /**
     * Runs iterative-deepening alpha-beta search up to {@code maxDepth}.
     *
     * @param board    position to search (not mutated)
     * @param maxDepth maximum search depth in plies
     * @return the search result containing the best move and metadata
     */
    public SearchResult search(Board board, int maxDepth) {
        stopped = false;
        nodesSearched = 0;

        Move bestMove = null;
        int bestScore = 0;
        int completedDepth = 0;

        for (int depth = 1; depth <= maxDepth; depth++) {
            if (stopped) break;

            bestMoveThisIteration = null;

            int score = negamax(board, depth, 0, NEG_INF, POS_INF, bestMove);

            if (!stopped) {
                bestMove = bestMoveThisIteration;
                bestScore = score;
                completedDepth = depth;
            }
        }

        return new SearchResult(bestMove, bestScore, completedDepth, nodesSearched);
    }

    /**
     * Signals the search to stop cleanly after the current iteration.
     * Thread-safe; may be called from any thread.
     */
    public void stop() {
        this.stopped = true;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Negamax with alpha-beta pruning.
     *
     * @param board    current position (not mutated – copies are made per move)
     * @param depth    remaining depth
     * @param ply      distance from root (0 = root)
     * @param alpha    lower bound for current player
     * @param beta     upper bound for current player
     * @param seedMove move to try first at the root (from the previous iteration); ignored at non-root plies
     * @return score in centipawns from the current side-to-move's perspective
     */
    private int negamax(Board board, int depth, int ply, int alpha, int beta, Move seedMove) {
        if (stopped) return 0;

        nodesSearched++;

        if (depth == 0) {
            return Evaluator.evaluate(board);
        }

        ArrayList<Move> moves = new MovesGenerator(board).getActiveMoves(board.getActiveColor());

        if (moves.isEmpty()) {
            // Distinguish checkmate from stalemate:
            // - Checkmate: king is in check → return a large negative score
            //   (offset by ply so the engine prefers faster/slower mates appropriately)
            // - Stalemate: king is not in check → return 0 (draw)
            return isKingInCheck(board) ? (NEG_INF + ply) : 0;
        }

        // Move ordering: place the seed move (best from the previous iteration) first at the root.
        if (ply == 0 && seedMove != null) {
            orderSeedMoveFirst(moves, seedMove);
        }

        int bestScore = NEG_INF;

        for (Move move : moves) {
            if (stopped) break;

            Board child = new Board(board);
            child.makeMove(move);

            int score = -negamax(child, depth - 1, ply + 1, -beta, -alpha, null);

            if (score > bestScore) {
                bestScore = score;
                if (ply == 0) {
                    bestMoveThisIteration = move;
                }
            }

            alpha = Math.max(alpha, score);
            if (alpha >= beta) break; // Beta cut-off
        }

        return bestScore;
    }

    /**
     * Moves {@code seed} to the front of {@code moves} (matched by start/target square).
     * If the seed is not found in the list the order is unchanged.
     */
    private static void orderSeedMoveFirst(ArrayList<Move> moves, Move seed) {
        for (int i = 0; i < moves.size(); i++) {
            Move m = moves.get(i);
            if (m.startSquare == seed.startSquare && m.targetSquare == seed.targetSquare) {
                if (i != 0) {
                    moves.remove(i);
                    moves.add(0, m);
                }
                return;
            }
        }
    }

    /**
     * Returns {@code true} when the side to move's king is attacked by any opponent piece.
     * Uses the ray and jump data already computed by {@link MovesGenerator}.
     */
    private static boolean isKingInCheck(Board board) {
        int activeColor = board.getActiveColor();
        int opponentColor = (activeColor == Piece.White) ? Piece.Black : Piece.White;
        int[] grid = board.getGrid();

        // Find the active side's king:
        int kingSquare = -1;
        for (int sq = 0; sq < 64; sq++) {
            int piece = grid[sq];
            if (piece != Piece.None && Piece.color(piece) == activeColor && Piece.type(piece) == Piece.King) {
                kingSquare = sq;
                break;
            }
        }
        if (kingSquare == -1) return false; // King not found (shouldn't happen in a legal game)

        int[][] squaresToEdges = MovesGenerator.SquaresToEdges;
        int[] dirOffsets = MovesGenerator.DirectionOffsets;

        // Check attacks along sliding-piece rays:
        for (int dir = 0; dir < 8; dir++) {
            for (int n = 1; n <= squaresToEdges[kingSquare][dir]; n++) {
                int sq = kingSquare + dirOffsets[dir] * n;
                int piece = grid[sq];
                if (piece == Piece.None) continue;

                if (Piece.color(piece) == opponentColor) {
                    int pieceType = Piece.type(piece);
                    boolean straightRay = dir < 4;
                    if (straightRay && (pieceType == Piece.Rook  || pieceType == Piece.Queen)) return true;
                    if (!straightRay && (pieceType == Piece.Bishop || pieceType == Piece.Queen)) return true;
                }
                break; // Any piece blocks further ray travel
            }
        }

        // Check knight attacks:
        int[] knightOffsets    = {-10, -17, -15, -6, 10, 17, 15, 6};
        int[] knightMaxRowDiff = {  1,   2,   2,  1,  1,  2,  2, 1};
        for (int i = 0; i < knightOffsets.length; i++) {
            int sq = kingSquare + knightOffsets[i];
            if (sq < 0 || sq > 63) continue;
            if (Math.abs((kingSquare / 8) - (sq / 8)) > knightMaxRowDiff[i]) continue;
            int piece = grid[sq];
            if (Piece.color(piece) == opponentColor && Piece.type(piece) == Piece.Knight) return true;
        }

        // Check pawn attacks (opponent pawns attack the king from the rank they came from):
        int[] pawnSources = (opponentColor == Piece.White)
                ? new int[]{kingSquare + 9, kingSquare + 7}   // white pawns attack upward (lower index)
                : new int[]{kingSquare - 9, kingSquare - 7};  // black pawns attack downward (higher index)
        for (int src : pawnSources) {
            if (src < 0 || src > 63) continue;
            int piece = grid[src];
            if (Piece.color(piece) == opponentColor && Piece.type(piece) == Piece.Pawn) return true;
        }

        // Check king attacks (opponent king adjacent to ours):
        for (int offset : dirOffsets) {
            int sq = kingSquare + offset;
            if (sq < 0 || sq > 63) continue;
            int piece = grid[sq];
            if (Piece.color(piece) == opponentColor && Piece.type(piece) == Piece.King) return true;
        }

        return false;
    }
}
