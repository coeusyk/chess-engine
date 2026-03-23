package coeusyk.game.chess.utils;

import coeusyk.game.chess.models.Board;
import coeusyk.game.chess.models.Move;
import coeusyk.game.chess.models.Piece;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;


public class Search {

    private static final Logger log = LoggerFactory.getLogger(Search.class);

    /** Maximum search depth supported by the triangular PV table. */
    public static final int MAX_DEPTH = 64;

    private static final int INF = Integer.MAX_VALUE / 2;

    // Triangular PV table: pvTable[ply][ply..pvLength[ply]-1] holds the PV from that ply.
    private final Move[][] pvTable = new Move[MAX_DEPTH][MAX_DEPTH];
    // pvLength[ply] = one past the last valid index in pvTable[ply].
    private final int[] pvLength = new int[MAX_DEPTH];

    private int nodesSearched;

    /**
     * Run an iterative-deepening alpha-beta search and return the best move
     * together with the principal variation found at {@code maxDepth}.
     */
    public SearchResult search(Board board, int maxDepth) {
        nodesSearched = 0;
        Move bestMove = null;
        int bestScore = -INF;
        int bestDepth = 0;
        String lastUciInfo = "";

        // PV move from the previous depth iteration, used to seed move ordering.
        Move pvMoveAtRoot = null;

        for (int depth = 1; depth <= maxDepth; depth++) {
            // Save the best move from the previous depth before resetting pvLength.
            if (pvLength[0] > 0 && pvTable[0][0] != null) {
                pvMoveAtRoot = pvTable[0][0];
            }

            // Reset PV lengths for the new search.
            for (int i = 0; i < MAX_DEPTH; i++) {
                pvLength[i] = 0;
            }

            int score = alphaBeta(board, depth, 0, -INF, INF, pvMoveAtRoot);

            if (pvLength[0] > 0) {
                bestMove = pvTable[0][0];
                bestDepth = depth;
                bestScore = score;
                lastUciInfo = buildUciInfo(depth, score, nodesSearched);
                log.info(lastUciInfo);
            }
        }

        List<Move> pv = new ArrayList<>();
        for (int i = 0; i < pvLength[0]; i++) {
            pv.add(pvTable[0][i]);
        }

        return new SearchResult(bestMove, bestScore, bestDepth, pv, lastUciInfo);
    }

    /**
     * Negamax alpha-beta search with triangular PV table.
     *
     * @param board        current position (will NOT be modified)
     * @param depth        remaining depth
     * @param ply          distance from the root (0 = root)
     * @param alpha        lower bound
     * @param beta         upper bound
     * @param pvMoveAtRoot PV move from the previous ID iteration; used only at the root
     * @return score from the perspective of the side to move
     */
    private int alphaBeta(Board board, int depth, int ply, int alpha, int beta, Move pvMoveAtRoot) {
        // Mark the PV from this ply as initially empty.
        pvLength[ply] = ply;

        if (depth == 0) {
            nodesSearched++;
            return Evaluator.evaluate(board);
        }

        MovesGenerator movesGen = new MovesGenerator(board);
        ArrayList<Move> moves = movesGen.getActiveMoves(board.getActiveColor());

        if (moves.isEmpty()) {
            // No legal moves: simplified treatment as a draw (stalemate score).
            // Full checkmate detection (distinguishing check from stalemate) requires
            // attack-map generation not yet available in this codebase.
            return 0;
        }

        // Seed move ordering at the root with the PV move from the previous iteration.
        if (ply == 0 && pvMoveAtRoot != null) {
            prioritizePvMove(moves, pvMoveAtRoot);
        }

        for (Move move : moves) {
            Board copy = new Board(board);
            copy.makeMove(move);

            int score = -alphaBeta(copy, depth - 1, ply + 1, -beta, -alpha, null);

            if (score >= beta) {
                // Fail-hard beta cutoff: return the upper bound.
                return beta;
            }

            if (score > alpha) {
                alpha = score;

                // Alpha improved: update the PV for this ply.
                pvTable[ply][ply] = move;
                // Copy the child's PV into our row.
                for (int j = ply + 1; j < pvLength[ply + 1]; j++) {
                    pvTable[ply][j] = pvTable[ply + 1][j];
                }
                pvLength[ply] = pvLength[ply + 1];
            }
        }

        return alpha;
    }

    /**
     * Move the PV move to the front of the move list so it is searched first.
     */
    private void prioritizePvMove(ArrayList<Move> moves, Move pvMove) {
        for (int i = 0; i < moves.size(); i++) {
            Move m = moves.get(i);
            if (m.startSquare == pvMove.startSquare && m.targetSquare == pvMove.targetSquare) {
                moves.remove(i);
                moves.add(0, m);
                return;
            }
        }
    }

    /**
     * Build a UCI {@code info} line for the given search result.
     *
     * <p>Format: {@code info depth <d> score cp <s> nodes <n> pv <move1> <move2> ...}
     */
    private String buildUciInfo(int depth, int score, int nodes) {
        StringBuilder pvStr = new StringBuilder();
        for (int i = 0; i < pvLength[0]; i++) {
            if (i > 0) pvStr.append(' ');
            pvStr.append(moveToUci(pvTable[0][i]));
        }
        return String.format("info depth %d score cp %d nodes %d pv %s", depth, score, nodes, pvStr);
    }

    /**
     * Convert a move to UCI long-algebraic notation (e.g. {@code e2e4}).
     * Square indexing: 0 = a8, 63 = h1.
     */
    public static String moveToUci(Move move) {
        if (move == null) return "null";
        int startFile = move.startSquare % 8;
        int startRank = 8 - (move.startSquare / 8);
        int targetFile = move.targetSquare % 8;
        int targetRank = 8 - (move.targetSquare / 8);
        return String.format("%c%d%c%d",
                (char) ('a' + startFile), startRank,
                (char) ('a' + targetFile), targetRank);
    }
}
