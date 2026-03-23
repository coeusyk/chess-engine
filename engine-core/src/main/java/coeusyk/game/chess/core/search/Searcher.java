package coeusyk.game.chess.core.search;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.models.Piece;
import coeusyk.game.chess.core.movegen.MovesGenerator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

public class Searcher {
    private static final int INF = 1_000_000;
    private static final int MATE_SCORE = 100_000;

    private long nodesVisited;
    private long leafNodes;
    private long quiescenceNodes;

    private boolean aborted;

    public SearchResult searchDepth(Board board, int depth) {
        return iterativeDeepening(board, depth, () -> false);
    }

    public SearchResult iterativeDeepening(Board board, int maxDepth) {
        return iterativeDeepening(board, maxDepth, () -> false);
    }

    public SearchResult iterativeDeepening(Board board, int maxDepth, BooleanSupplier shouldAbort) {
        if (maxDepth < 1) {
            throw new IllegalArgumentException("depth must be >= 1");
        }

        Move previousBestMove = null;
        int bestScore = 0;
        int depthReached = 0;
        long totalNodes = 0;
        long totalLeafNodes = 0;
        long totalQuiescenceNodes = 0;

        aborted = false;

        for (int depth = 1; depth <= maxDepth; depth++) {
            if (shouldAbort.getAsBoolean()) {
                aborted = true;
                break;
            }

            nodesVisited = 0;
            leafNodes = 0;
            quiescenceNodes = 0;

            RootResult iteration = searchRoot(board, depth, previousBestMove, shouldAbort);

            totalNodes += nodesVisited;
            totalLeafNodes += leafNodes;
            totalQuiescenceNodes += quiescenceNodes;

            if (iteration.bestMove == null) {
                aborted = true;
                break;
            }

            previousBestMove = iteration.bestMove;
            bestScore = iteration.bestScore;
            depthReached = depth;

            if (iteration.aborted) {
                aborted = true;
                break;
            }
        }

        return new SearchResult(
            previousBestMove,
            bestScore,
            depthReached,
            totalNodes,
            totalLeafNodes,
            totalQuiescenceNodes,
            aborted
        );
    }

    private RootResult searchRoot(Board board, int depth, Move preferredMove, BooleanSupplier shouldAbort) {
        MovesGenerator generator = new MovesGenerator(board);
        List<Move> moves = new ArrayList<>(generator.getActiveMoves(board.getActiveColor()));

        if (moves.isEmpty()) {
            int terminalScore = evaluateTerminal(board, 0);
            return new RootResult(null, terminalScore, false);
        }

        prioritizeMove(moves, preferredMove);

        Move bestMove = null;
        int bestScore = -INF;
        int alpha = -INF;
        int beta = INF;

        for (Move move : moves) {
            if (shouldAbort.getAsBoolean()) {
                return new RootResult(bestMove, bestScore, true);
            }

            board.makeMove(move);
            int score = -alphaBeta(board, depth - 1, 1, -beta, -alpha, shouldAbort);
            board.unmakeMove();

            if (aborted) {
                return new RootResult(bestMove, bestScore, true);
            }

            if (score > bestScore || bestMove == null) {
                bestScore = score;
                bestMove = move;
            }

            if (score > alpha) {
                alpha = score;
            }
        }

        return new RootResult(bestMove, bestScore, false);
    }

    private int alphaBeta(
            Board board,
            int depth,
            int ply,
            int alpha,
            int beta,
            BooleanSupplier shouldAbort
    ) {
        if (shouldAbort.getAsBoolean()) {
            aborted = true;
            return alpha;
        }

        if (depth == 0) {
            return quiescence(board, alpha, beta, ply, shouldAbort);
        }

        nodesVisited++;

        MovesGenerator generator = new MovesGenerator(board);
        List<Move> moves = generator.getActiveMoves(board.getActiveColor());

        if (moves.isEmpty()) {
            leafNodes++;
            return evaluateTerminal(board, ply);
        }

        int bestScore = -INF;
        for (Move move : moves) {
            board.makeMove(move);
            int score = -alphaBeta(board, depth - 1, ply + 1, -beta, -alpha, shouldAbort);
            board.unmakeMove();

            if (aborted) {
                return bestScore == -INF ? alpha : bestScore;
            }

            if (score > bestScore) {
                bestScore = score;
            }

            if (score > alpha) {
                alpha = score;
            }

            if (alpha >= beta) {
                break;
            }
        }

        return bestScore;
    }

    private int quiescence(Board board, int alpha, int beta, int ply, BooleanSupplier shouldAbort) {
        quiescenceNodes++;

        if (shouldAbort.getAsBoolean()) {
            aborted = true;
            return alpha;
        }

        MovesGenerator generator = new MovesGenerator(board);
        List<Move> legalMoves = generator.getActiveMoves(board.getActiveColor());
        if (legalMoves.isEmpty()) {
            leafNodes++;
            return evaluateTerminal(board, ply);
        }

        int standPat = evaluate(board);
        if (standPat >= beta) {
            leafNodes++;
            return standPat;
        }
        if (standPat > alpha) {
            alpha = standPat;
        }

        List<Move> qMoves = extractQuiescenceMoves(board, legalMoves);
        if (qMoves.isEmpty()) {
            leafNodes++;
            return standPat;
        }

        int bestScore = standPat;
        for (Move move : qMoves) {
            board.makeMove(move);
            int score = -quiescence(board, -beta, -alpha, ply + 1, shouldAbort);
            board.unmakeMove();

            if (aborted) {
                return bestScore;
            }

            if (score > bestScore) {
                bestScore = score;
            }

            if (score > alpha) {
                alpha = score;
            }

            if (alpha >= beta) {
                break;
            }
        }

        return bestScore;
    }

    private int evaluateTerminal(Board board, int ply) {
        if (board.isCheckmate()) {
            return -MATE_SCORE + ply;
        }
        return 0;
    }

    private int evaluate(Board board) {
        int whiteMaterial = 0;
        int blackMaterial = 0;

        int[] grid = board.getGrid();
        for (int piece : grid) {
            if (piece == Piece.None) {
                continue;
            }

            int value = switch (Piece.type(piece)) {
                case Piece.Pawn -> 100;
                case Piece.Knight -> 320;
                case Piece.Bishop -> 330;
                case Piece.Rook -> 500;
                case Piece.Queen -> 900;
                case Piece.King -> 0;
                default -> 0;
            };

            if (Piece.isWhite(piece)) {
                whiteMaterial += value;
            } else {
                blackMaterial += value;
            }
        }

        int materialScore = whiteMaterial - blackMaterial;
        return Piece.isWhite(board.getActiveColor()) ? materialScore : -materialScore;
    }

    private void prioritizeMove(List<Move> moves, Move preferredMove) {
        if (preferredMove == null || moves.isEmpty()) {
            return;
        }

        int index = -1;
        for (int i = 0; i < moves.size(); i++) {
            if (sameMove(moves.get(i), preferredMove)) {
                index = i;
                break;
            }
        }

        if (index > 0) {
            Collections.swap(moves, 0, index);
        }
    }

    private List<Move> extractQuiescenceMoves(Board board, List<Move> legalMoves) {
        List<Move> qMoves = new ArrayList<>();
        for (Move move : legalMoves) {
            if (isQuiescenceMove(board, move)) {
                qMoves.add(move);
            }
        }
        return qMoves;
    }

    private boolean isQuiescenceMove(Board board, Move move) {
        boolean isQueenPromotion = "promote-q".equals(move.reaction);
        boolean isEnPassant = "en-passant".equals(move.reaction);
        boolean isCapture = isEnPassant || board.getPiece(move.targetSquare) != Piece.None;
        return isCapture || isQueenPromotion;
    }

    private boolean sameMove(Move a, Move b) {
        if (a.startSquare != b.startSquare || a.targetSquare != b.targetSquare) {
            return false;
        }

        if (a.reaction == null) {
            return b.reaction == null;
        }

        return a.reaction.equals(b.reaction);
    }

    private record RootResult(Move bestMove, int bestScore, boolean aborted) {
    }
}