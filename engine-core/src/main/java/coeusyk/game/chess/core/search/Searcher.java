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
    @FunctionalInterface
    public interface IterationListener {
        void onIteration(int depth, int scoreCp, Move bestMove);
    }

    private static final int INF = 1_000_000;
    private static final int MATE_SCORE = 100_000;
    private static final int MAX_PLY = 128;

    private long nodesVisited;
    private long leafNodes;
    private long quiescenceNodes;

    private Move[][] pvTable;
    private int[] pvLength;

    private final boolean moveOrderingEnabled;
    private final MoveOrderer moveOrderer = new MoveOrderer();
    private final Move[][] killerMoves = new Move[MAX_PLY][2];
    private final int[][] historyHeuristic = new int[7][64];
    private final TranspositionTable transpositionTable = new TranspositionTable();

    private Move rootTtMoveHint;

    private boolean aborted;

    public Searcher() {
        this(true);
    }

    public Searcher(boolean moveOrderingEnabled) {
        this.moveOrderingEnabled = moveOrderingEnabled;
    }

    public void setRootTtMoveHintForTesting(Move rootTtMoveHint) {
        this.rootTtMoveHint = rootTtMoveHint;
    }

    public void setTranspositionTableSizeMb(int sizeMb) {
        transpositionTable.resize(sizeMb);
    }

    public double getTranspositionTableHitRate() {
        return transpositionTable.getHitRate();
    }

    public SearchResult searchDepth(Board board, int depth) {
        return iterativeDeepening(board, depth, () -> false);
    }

    public SearchResult iterativeDeepening(Board board, int maxDepth) {
        return iterativeDeepening(board, maxDepth, () -> false);
    }

    public SearchResult iterativeDeepening(Board board, int maxDepth, BooleanSupplier shouldAbort) {
        return iterativeDeepening(board, maxDepth, shouldAbort, shouldAbort, null);
    }

    public SearchResult searchWithTimeManager(Board board, int maxDepth, TimeManager timeManager) {
        timeManager.startNow();
        return iterativeDeepening(board, maxDepth, timeManager::shouldStopSoft, timeManager::shouldStopHard, null);
    }

    public SearchResult searchWithTimeManager(
            Board board,
            int maxDepth,
            TimeManager timeManager,
            BooleanSupplier externalStop,
            IterationListener listener
    ) {
        timeManager.startNow();
        BooleanSupplier softStop = () -> externalStop.getAsBoolean() || timeManager.shouldStopSoft();
        BooleanSupplier hardStop = () -> externalStop.getAsBoolean() || timeManager.shouldStopHard();
        return iterativeDeepening(board, maxDepth, softStop, hardStop, listener);
    }

    public SearchResult iterativeDeepening(
            Board board,
            int maxDepth,
            BooleanSupplier shouldStopSoft,
            BooleanSupplier shouldStopHard,
            IterationListener listener
    ) {

        if (maxDepth < 1) {
            throw new IllegalArgumentException("depth must be >= 1");
        }

        int effectiveMaxDepth = Math.min(maxDepth, MAX_PLY - 1);

        Move previousBestMove = null;
        int bestScore = 0;
        int depthReached = 0;
        List<Move> bestPrincipalVariation = List.of();
        long totalNodes = 0;
        long totalLeafNodes = 0;
        long totalQuiescenceNodes = 0;

        aborted = false;
        transpositionTable.resetStats();

        for (int depth = 1; depth <= effectiveMaxDepth; depth++) {
            if (shouldStopSoft.getAsBoolean()) {
                aborted = true;
                break;
            }

            nodesVisited = 0;
            leafNodes = 0;
            quiescenceNodes = 0;
            pvTable = new Move[depth + 4][depth + 4];
            pvLength = new int[depth + 4];

            RootResult iteration = searchRoot(board, depth, previousBestMove, shouldStopHard);

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
            bestPrincipalVariation = iteration.principalVariation;

            if (listener != null && previousBestMove != null) {
                listener.onIteration(depth, bestScore, previousBestMove);
            }

            if (iteration.aborted) {
                aborted = true;
                break;
            }
        }

        return new SearchResult(
            previousBestMove,
            bestScore,
            depthReached,
            bestPrincipalVariation,
            totalNodes,
            totalLeafNodes,
            totalQuiescenceNodes,
            transpositionTable.getHitRate(),
            aborted
        );
    }

    private RootResult searchRoot(Board board, int depth, Move preferredMove, BooleanSupplier shouldStopHard) {
        MovesGenerator generator = new MovesGenerator(board);
        List<Move> moves = new ArrayList<>(generator.getActiveMoves(board.getActiveColor()));
        TranspositionTable.Entry rootEntry = transpositionTable.probe(board.getZobristHash());

        if (moves.isEmpty()) {
            int terminalScore = evaluateTerminal(board, 0);
            return new RootResult(null, terminalScore, List.of(), false);
        }

        prioritizeMove(moves, preferredMove);
        if (moveOrderingEnabled) {
            Move ttMove = (rootTtMoveHint != null)
                    ? rootTtMoveHint
                    : (rootEntry != null ? rootEntry.bestMove() : preferredMove);
            moves = moveOrderer.orderMoves(board, moves, 0, ttMove, killerMoves, historyHeuristic);
        }

        Move bestMove = null;
        int bestScore = -INF;
        int alpha = -INF;
        int beta = INF;
        pvLength[0] = 0;

        for (Move move : moves) {
            if (shouldStopHard.getAsBoolean()) {
                return new RootResult(bestMove, bestScore, buildPrincipalVariation(), true);
            }

            board.makeMove(move);
            int score = -alphaBeta(board, depth - 1, 1, -beta, -alpha, shouldStopHard);
            board.unmakeMove();

            if (aborted) {
                return new RootResult(bestMove, bestScore, buildPrincipalVariation(), true);
            }

            if (score > bestScore || bestMove == null) {
                bestScore = score;
                bestMove = move;

                pvTable[0][0] = move;
                int childPvLength = pvLength[1];
                for (int i = 0; i < childPvLength; i++) {
                    pvTable[0][i + 1] = pvTable[1][i];
                }
                pvLength[0] = 1 + childPvLength;
            }

            if (score > alpha) {
                alpha = score;
            }
        }

        return new RootResult(bestMove, bestScore, buildPrincipalVariation(), false);
    }

    private int alphaBeta(
            Board board,
            int depth,
            int ply,
            int alpha,
            int beta,
            BooleanSupplier shouldStopHard
    ) {
        pvLength[ply] = 0;

        if (shouldStopHard.getAsBoolean()) {
            aborted = true;
            return alpha;
        }

        if (depth == 0) {
            return quiescence(board, alpha, beta, ply, shouldStopHard);
        }

        long zobrist = board.getZobristHash();
        int alphaOrig = alpha;
        TranspositionTable.Entry ttEntry = transpositionTable.probe(zobrist);
        Integer ttScore = applyTtBound(ttEntry, depth, alpha, beta);
        if (ttScore != null) {
            return ttScore;
        }

        nodesVisited++;

        MovesGenerator generator = new MovesGenerator(board);
        List<Move> moves = generator.getActiveMoves(board.getActiveColor());
        if (moveOrderingEnabled) {
            Move ttMove = ttEntry != null ? ttEntry.bestMove() : null;
            int orderPly = Math.min(ply, MAX_PLY - 1);
            moves = moveOrderer.orderMoves(board, moves, orderPly, ttMove, killerMoves, historyHeuristic);
        }

        if (moves.isEmpty()) {
            leafNodes++;
            return evaluateTerminal(board, ply);
        }

        int bestScore = -INF;
        Move bestMove = null;
        for (Move move : moves) {
            board.makeMove(move);
            int score = -alphaBeta(board, depth - 1, ply + 1, -beta, -alpha, shouldStopHard);
            board.unmakeMove();

            if (aborted) {
                return bestScore == -INF ? alpha : bestScore;
            }

            if (score > bestScore) {
                bestScore = score;
                bestMove = move;

                pvTable[ply][0] = move;
                int childPvLength = pvLength[ply + 1];
                for (int i = 0; i < childPvLength; i++) {
                    pvTable[ply][i + 1] = pvTable[ply + 1][i];
                }
                pvLength[ply] = 1 + childPvLength;
            }

            if (score > alpha) {
                alpha = score;
            }

            if (alpha >= beta) {
                if (moveOrderingEnabled && isQuietMove(board, move)) {
                    storeKillerMove(ply, move);
                    updateHistory(move, board, depth);
                }
                break;
            }
        }

        if (!aborted && bestScore != -INF) {
            TTBound bound = resolveBound(bestScore, alphaOrig, beta);
            transpositionTable.store(zobrist, bestMove, depth, bestScore, bound);
        }

        return bestScore;
    }

    Integer applyTtBound(TranspositionTable.Entry entry, int depth, int alpha, int beta) {
        if (entry == null || entry.depth() < depth) {
            return null;
        }

        return switch (entry.bound()) {
            case EXACT -> entry.score();
            case LOWER_BOUND -> (entry.score() >= beta) ? entry.score() : null;
            case UPPER_BOUND -> (entry.score() <= alpha) ? entry.score() : null;
        };
    }

    private TTBound resolveBound(int bestScore, int alphaOrig, int beta) {
        if (bestScore <= alphaOrig) {
            return TTBound.UPPER_BOUND;
        }
        if (bestScore >= beta) {
            return TTBound.LOWER_BOUND;
        }
        return TTBound.EXACT;
    }

    private int quiescence(Board board, int alpha, int beta, int ply, BooleanSupplier shouldStopHard) {
        quiescenceNodes++;

        if (shouldStopHard.getAsBoolean()) {
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
            int score = -quiescence(board, -beta, -alpha, ply + 1, shouldStopHard);
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

    private boolean isQuietMove(Board board, Move move) {
        if (moveOrderer.isCapture(board, move)) {
            return false;
        }

        return !isPromotion(move.reaction);
    }

    private boolean isPromotion(String reaction) {
        return "promote-q".equals(reaction)
                || "promote-r".equals(reaction)
                || "promote-b".equals(reaction)
                || "promote-n".equals(reaction);
    }

    private void storeKillerMove(int ply, Move move) {
        if (ply < 0 || ply >= MAX_PLY) {
            return;
        }

        if (killerMoves[ply][0] != null && sameMove(killerMoves[ply][0], move)) {
            return;
        }

        killerMoves[ply][1] = killerMoves[ply][0];
        killerMoves[ply][0] = new Move(move.startSquare, move.targetSquare, move.reaction);
    }

    private void updateHistory(Move move, Board board, int depth) {
        int movingPiece = board.getPiece(move.startSquare);
        int pieceType = Piece.type(movingPiece);
        if (pieceType <= 0 || pieceType >= historyHeuristic.length) {
            return;
        }

        if (move.targetSquare < 0 || move.targetSquare >= historyHeuristic[pieceType].length) {
            return;
        }

        historyHeuristic[pieceType][move.targetSquare] += depth * depth;
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

    private List<Move> buildPrincipalVariation() {
        if (pvLength[0] <= 0) {
            return List.of();
        }

        List<Move> line = new ArrayList<>(pvLength[0]);
        for (int i = 0; i < pvLength[0]; i++) {
            line.add(pvTable[0][i]);
        }
        return List.copyOf(line);
    }

    private record RootResult(Move bestMove, int bestScore, List<Move> principalVariation, boolean aborted) {
    }
}