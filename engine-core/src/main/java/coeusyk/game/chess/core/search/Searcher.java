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
    private static final int ASPIRATION_INITIAL_DELTA_CP = 50;
    private static final int NULL_MOVE_DEPTH_THRESHOLD = 6;
    private static final int MAX_LEGAL_MOVES = 256;
    private static final int FUTILITY_MARGIN_DEPTH_1 = 100;
    private static final int FUTILITY_MARGIN_DEPTH_2 = 300;
    private static final int RAZOR_MARGIN_DEPTH_1 = 300;
    private static final int MAX_CHECK_EXTENSIONS = 16;

    private long nodesVisited;
    private long leafNodes;
    private long quiescenceNodes;
    private long checkExtensionsApplied;

    private Move[][] pvTable;
    private int[] pvLength;

    private final boolean moveOrderingEnabled;
    private final boolean aspirationWindowsEnabled;
    private final boolean nullMovePruningEnabled;
    private final boolean lmrEnabled;
    private final boolean futilityRazoringEnabled;
    private final boolean checkExtensionsEnabled;
    private boolean seeEnabled = true;
    private final MoveOrderer moveOrderer = new MoveOrderer();
    private final StaticExchangeEvaluator staticExchangeEvaluator = new StaticExchangeEvaluator();
    private final Move[][] killerMoves = new Move[MAX_PLY][2];
    private final int[][] historyHeuristic = new int[7][64];
    private final TranspositionTable transpositionTable = new TranspositionTable();
    private final int[][] lmrReductions = precomputeLmrReductions();

    private Move rootTtMoveHint;

    private boolean aborted;

    public Searcher() {
        this(true, true, true, true, true, true);
    }

    public Searcher(boolean moveOrderingEnabled) {
        this(moveOrderingEnabled, true, true, true, true, true);
    }

    Searcher(boolean moveOrderingEnabled, boolean aspirationWindowsEnabled) {
        this(moveOrderingEnabled, aspirationWindowsEnabled, true, true, true, true);
    }

    Searcher(boolean moveOrderingEnabled, boolean aspirationWindowsEnabled, boolean nullMovePruningEnabled) {
        this(moveOrderingEnabled, aspirationWindowsEnabled, nullMovePruningEnabled, true, true, true);
    }

    Searcher(
            boolean moveOrderingEnabled,
            boolean aspirationWindowsEnabled,
            boolean nullMovePruningEnabled,
            boolean lmrEnabled
    ) {
        this(moveOrderingEnabled, aspirationWindowsEnabled, nullMovePruningEnabled, lmrEnabled, true, true);
    }

    Searcher(
            boolean moveOrderingEnabled,
            boolean aspirationWindowsEnabled,
            boolean nullMovePruningEnabled,
            boolean lmrEnabled,
            boolean futilityRazoringEnabled
    ) {
        this(moveOrderingEnabled, aspirationWindowsEnabled, nullMovePruningEnabled, lmrEnabled, futilityRazoringEnabled, true);
    }

    Searcher(
            boolean moveOrderingEnabled,
            boolean aspirationWindowsEnabled,
            boolean nullMovePruningEnabled,
            boolean lmrEnabled,
            boolean futilityRazoringEnabled,
            boolean checkExtensionsEnabled
    ) {
        this.moveOrderingEnabled = moveOrderingEnabled;
        this.aspirationWindowsEnabled = aspirationWindowsEnabled;
        this.nullMovePruningEnabled = nullMovePruningEnabled;
        this.lmrEnabled = lmrEnabled;
        this.futilityRazoringEnabled = futilityRazoringEnabled;
        this.checkExtensionsEnabled = checkExtensionsEnabled;
    }

    public void setRootTtMoveHintForTesting(Move rootTtMoveHint) {
        this.rootTtMoveHint = rootTtMoveHint;
    }

    void setSeeEnabledForTesting(boolean seeEnabled) {
        this.seeEnabled = seeEnabled;
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
        checkExtensionsApplied = 0;
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

            RootResult iteration;
            int maxCheckExtensions = getMaxCheckExtensionsForDepth(depth);
            if (aspirationWindowsEnabled && depth >= 2 && previousBestMove != null) {
                iteration = searchRootWithAspiration(board, depth, previousBestMove, bestScore, shouldStopHard, maxCheckExtensions);
            } else {
                iteration = searchRoot(board, depth, previousBestMove, shouldStopHard, -INF, INF, maxCheckExtensions);
            }

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

    private RootResult searchRootWithAspiration(
            Board board,
            int depth,
            Move preferredMove,
            int previousIterationScore,
            BooleanSupplier shouldStopHard,
            int maxCheckExtensions
    ) {
        int alpha = Math.max(-INF, previousIterationScore - ASPIRATION_INITIAL_DELTA_CP);
        int beta = Math.min(INF, previousIterationScore + ASPIRATION_INITIAL_DELTA_CP);
        int consecutiveFailures = 0;

        while (true) {
            RootResult iteration = searchRoot(board, depth, preferredMove, shouldStopHard, alpha, beta, maxCheckExtensions);
            if (iteration.aborted || iteration.bestMove == null) {
                return iteration;
            }

            boolean failLow = iteration.bestScore <= alpha;
            boolean failHigh = iteration.bestScore >= beta;
            if (!failLow && !failHigh) {
                return iteration;
            }

            consecutiveFailures++;
            if (consecutiveFailures >= 2) {
                return searchRoot(board, depth, preferredMove, shouldStopHard, -INF, INF, maxCheckExtensions);
            }

            int widenBy = ASPIRATION_INITIAL_DELTA_CP << consecutiveFailures;
            if (failLow) {
                alpha = Math.max(-INF, previousIterationScore - widenBy);
            } else {
                beta = Math.min(INF, previousIterationScore + widenBy);
            }
        }
    }

    private RootResult searchRoot(
            Board board,
            int depth,
            Move preferredMove,
            BooleanSupplier shouldStopHard,
            int rootAlpha,
            int rootBeta,
            int maxCheckExtensions
    ) {
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
        int alpha = rootAlpha;
        int beta = rootBeta;
        pvLength[0] = 0;
        boolean rootInCheck = board.isActiveColorInCheck();
        boolean rootExtensionApplied = checkExtensionsEnabled && rootInCheck && maxCheckExtensions > 0;
        int rootExtensionsUsed = rootExtensionApplied ? 1 : 0;
        int childDepth = rootExtensionApplied ? depth : depth - 1;
        childDepth = Math.max(0, childDepth);

        int rootMoveIndex = 0;
        for (Move move : moves) {
            if (shouldStopHard.getAsBoolean()) {
                return new RootResult(bestMove, bestScore, buildPrincipalVariation(), true);
            }

            board.makeMove(move);
            boolean childIsPvNode = rootMoveIndex == 0;
                int score = -alphaBeta(
                    board,
                    childDepth,
                    1,
                    -beta,
                    -alpha,
                    shouldStopHard,
                    false,
                    childIsPvNode,
                    rootExtensionsUsed,
                    maxCheckExtensions
                );
            board.unmakeMove();
            rootMoveIndex++;

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
            BooleanSupplier shouldStopHard,
            boolean previousMoveWasNull,
            boolean isPvNode,
            int extensionsUsed,
            int maxExtensions
    ) {
        pvLength[ply] = 0;

        if (shouldStopHard.getAsBoolean()) {
            aborted = true;
            return alpha;
        }

        int effectiveDepth = depth;
        boolean sideToMoveInCheck = board.isActiveColorInCheck();
        int currentExtensionsUsed = extensionsUsed;

        if (checkExtensionsEnabled && sideToMoveInCheck && currentExtensionsUsed < maxExtensions) {
            effectiveDepth = Math.min(MAX_PLY - 1, depth + 1);
            currentExtensionsUsed++;
            checkExtensionsApplied++;
        }

        if (effectiveDepth == 0) {
            return quiescence(board, alpha, beta, ply, shouldStopHard);
        }

        long zobrist = board.getZobristHash();
        int alphaOrig = alpha;
        TranspositionTable.Entry ttEntry = transpositionTable.probe(zobrist);
        Integer ttScore = applyTtBound(ttEntry, effectiveDepth, alpha, beta);
        if (ttScore != null) {
            return ttScore;
        }

        if (canApplyRazoring(board, effectiveDepth, alpha, beta, isPvNode)) {
            int qScore = quiescence(board, alpha, alpha + 1, ply, shouldStopHard);
            if (aborted) {
                return alpha;
            }

            if (qScore <= alpha) {
                return qScore;
            }
        }

        if (nullMovePruningEnabled && canApplyNullMove(board, effectiveDepth, previousMoveWasNull, beta)) {
            int nullReduction = effectiveDepth >= NULL_MOVE_DEPTH_THRESHOLD ? 3 : 2;
            Board.NullMoveState nullMoveState = board.makeNullMove();
            int nullScore = -alphaBeta(
                    board,
                effectiveDepth - nullReduction - 1,
                    ply + 1,
                    -beta,
                    -beta + 1,
                    shouldStopHard,
                    true,
                false,
                currentExtensionsUsed,
                maxExtensions
            );
            board.unmakeNullMove(nullMoveState);

            if (aborted) {
                return alpha;
            }

            if (nullScore >= beta) {
                return beta;
            }
        }

        nodesVisited++;

        MovesGenerator generator = new MovesGenerator(board);
        List<Move> moves = generator.getActiveMoves(board.getActiveColor());
        Move ttMove = ttEntry != null ? ttEntry.bestMove() : null;
        if (moveOrderingEnabled) {
            int orderPly = Math.min(ply, MAX_PLY - 1);
            moves = moveOrderer.orderMoves(board, moves, orderPly, ttMove, killerMoves, historyHeuristic);
        }

        int staticEval = (effectiveDepth <= 2) ? evaluate(board) : 0;

        if (moves.isEmpty()) {
            leafNodes++;
            return evaluateTerminal(board, ply);
        }

        int bestScore = -INF;
        Move bestMove = null;
        int moveIndex = 0;
        for (Move move : moves) {
            boolean isQuiet = isQuietMove(board, move);
            boolean isCapture = moveOrderer.isCapture(board, move);
            boolean isKiller = isKillerMove(ply, move);
            boolean isTtMove = ttMove != null && sameMove(ttMove, move);
            Integer captureSee = null;
            if (isCapture && seeEnabled) {
                captureSee = staticExchangeEvaluator.evaluate(board, move);
            }

            board.makeMove(move);

            boolean moveGivesCheck = board.isActiveColorInCheck();
            if (canPruneLosingCapture(
                    effectiveDepth,
                    captureSee,
                    isPvNode,
                    sideToMoveInCheck,
                    moveGivesCheck
            )) {
                board.unmakeMove();
                moveIndex++;
                continue;
            }

            if (canApplyFutilityPruning(
                    effectiveDepth,
                    alpha,
                    beta,
                    staticEval,
                    isPvNode,
                    sideToMoveInCheck,
                    isQuiet,
                    moveGivesCheck
            )) {
                board.unmakeMove();
                moveIndex++;
                continue;
            }

            int score;
            if (canApplyLmr(effectiveDepth, moveIndex, isQuiet, isKiller, isTtMove, sideToMoveInCheck, moveGivesCheck)) {
                int reduction = lmrReductions[Math.min(effectiveDepth, MAX_PLY - 1)][Math.min(moveIndex + 1, MAX_LEGAL_MOVES - 1)];
                int reducedDepth = Math.max(1, effectiveDepth - 1 - reduction);

                score = -alphaBeta(
                        board,
                        reducedDepth,
                        ply + 1,
                        -beta,
                        -alpha,
                        shouldStopHard,
                        false,
                        false,
                        currentExtensionsUsed,
                        maxExtensions
                );
                if (!aborted && score > alpha) {
                    boolean childIsPvNode = isPvNode && moveIndex == 0;
                    score = -alphaBeta(
                            board,
                            effectiveDepth - 1,
                            ply + 1,
                            -beta,
                            -alpha,
                            shouldStopHard,
                            false,
                            childIsPvNode,
                            currentExtensionsUsed,
                            maxExtensions
                    );
                }
            } else {
                boolean childIsPvNode = isPvNode && moveIndex == 0;
                score = -alphaBeta(
                        board,
                        effectiveDepth - 1,
                        ply + 1,
                        -beta,
                        -alpha,
                        shouldStopHard,
                        false,
                        childIsPvNode,
                        currentExtensionsUsed,
                        maxExtensions
                );
            }

            board.unmakeMove();
            moveIndex++;

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

        if (bestScore == -INF) {
            return alpha;
        }

        if (!aborted && bestScore != -INF) {
            TTBound bound = resolveBound(bestScore, alphaOrig, beta);
            transpositionTable.store(zobrist, bestMove, effectiveDepth, bestScore, bound);
        }

        return bestScore;
    }

    private boolean canApplyNullMove(Board board, int depth, boolean previousMoveWasNull, int beta) {
        int nullReduction = depth >= NULL_MOVE_DEPTH_THRESHOLD ? 3 : 2;
        boolean enoughDepthForNullMove = depth >= NULL_MOVE_DEPTH_THRESHOLD;
        boolean hasRemainingDepth = (depth - nullReduction - 1) > 0;
        int staticEval = evaluate(board);
        boolean isMateWindow = Math.abs(beta) >= (MATE_SCORE - MAX_PLY);

        return enoughDepthForNullMove
            && hasRemainingDepth
                && !previousMoveWasNull
                && !board.isActiveColorInCheck()
                && !isMateWindow
            && staticEval >= beta
                && hasNonPawnMaterial(board, board.getActiveColor());
    }

    private boolean canApplyRazoring(Board board, int depth, int alpha, int beta, boolean isPvNode) {
        if (!futilityRazoringEnabled || depth != 1 || isPvNode || board.isActiveColorInCheck()) {
            return false;
        }

        if (isMateWindow(alpha, beta)) {
            return false;
        }

        int staticEval = evaluate(board);
        return staticEval + RAZOR_MARGIN_DEPTH_1 < alpha;
    }

    private boolean canApplyFutilityPruning(
            int depth,
            int alpha,
            int beta,
            int staticEval,
            boolean isPvNode,
            boolean sideToMoveInCheck,
            boolean isQuiet,
            boolean moveGivesCheck
    ) {
        if (!futilityRazoringEnabled || isPvNode || sideToMoveInCheck || !isQuiet || moveGivesCheck) {
            return false;
        }

        if (isMateWindow(alpha, beta)) {
            return false;
        }

        int margin = getFutilityMarginForDepth(depth);
        return margin > 0 && (staticEval + margin) <= alpha;
    }

    private boolean canPruneLosingCapture(
            int depth,
            Integer captureSee,
            boolean isPvNode,
            boolean sideToMoveInCheck,
            boolean moveGivesCheck
    ) {
        if (!seeEnabled || captureSee == null || captureSee >= 0) {
            return false;
        }

        return depth <= 2
                && !isPvNode
                && !sideToMoveInCheck
                && !moveGivesCheck;
    }

    private boolean isMateWindow(int alpha, int beta) {
        return Math.abs(alpha) >= (MATE_SCORE - MAX_PLY) || Math.abs(beta) >= (MATE_SCORE - MAX_PLY);
    }

    private int getFutilityMarginForDepth(int depth) {
        return switch (depth) {
            case 1 -> FUTILITY_MARGIN_DEPTH_1;
            case 2 -> FUTILITY_MARGIN_DEPTH_2;
            default -> 0;
        };
    }

    private boolean hasNonPawnMaterial(Board board, int color) {
        int[] grid = board.getGrid();
        for (int piece : grid) {
            if (piece == Piece.None || !Piece.isColor(piece, color)) {
                continue;
            }

            int pieceType = Piece.type(piece);
            if (pieceType != Piece.Pawn && pieceType != Piece.King) {
                return true;
            }
        }
        return false;
    }

    private boolean canApplyLmr(
            int depth,
            int moveIndex,
            boolean isQuiet,
            boolean isKiller,
            boolean isTtMove,
            boolean sideToMoveInCheck,
            boolean moveGivesCheck
    ) {
        return lmrEnabled
            && depth >= 6
                && moveIndex >= 2
                && isQuiet
                && !isKiller
                && !isTtMove
                && !sideToMoveInCheck
                && !moveGivesCheck;
    }

    private int[][] precomputeLmrReductions() {
        int[][] reductions = new int[MAX_PLY][MAX_LEGAL_MOVES];
        for (int depth = 1; depth < MAX_PLY; depth++) {
            for (int moveIndex = 1; moveIndex < MAX_LEGAL_MOVES; moveIndex++) {
                int reduction = Math.max(
                        1,
                        (int) (0.75 + (Math.log(depth) * Math.log(moveIndex)) / 2.25)
                );
                reductions[depth][moveIndex] = reduction;
            }
        }
        return reductions;
    }

    private boolean isKillerMove(int ply, Move move) {
        if (ply < 0 || ply >= MAX_PLY) {
            return false;
        }

        return (killerMoves[ply][0] != null && sameMove(killerMoves[ply][0], move))
                || (killerMoves[ply][1] != null && sameMove(killerMoves[ply][1], move));
    }

    int getLmrReductionForTesting(int depth, int moveIndexOneBased) {
        int safeDepth = Math.max(0, Math.min(depth, MAX_PLY - 1));
        int safeMoveIndex = Math.max(0, Math.min(moveIndexOneBased, MAX_LEGAL_MOVES - 1));
        return lmrReductions[safeDepth][safeMoveIndex];
    }

    long getCheckExtensionsAppliedForTesting() {
        return checkExtensionsApplied;
    }

    int getMaxCheckExtensionsForTesting(int initialDepth) {
        return getMaxCheckExtensionsForDepth(initialDepth);
    }

    private int getMaxCheckExtensionsForDepth(int initialDepth) {
        if (initialDepth <= 1) {
            return 0;
        }
        return Math.min(MAX_CHECK_EXTENSIONS, Math.max(0, initialDepth / 2));
    }

    int getFutilityMarginForTesting(int depth) {
        return getFutilityMarginForDepth(depth);
    }

    int getRazorMarginForTesting() {
        return RAZOR_MARGIN_DEPTH_1;
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
            if (shouldIncludeInQuiescence(board, move)) {
                qMoves.add(move);
            }
        }
        return qMoves;
    }

    boolean shouldIncludeInQuiescenceForTesting(Board board, Move move) {
        return shouldIncludeInQuiescence(board, move);
    }

    private boolean shouldIncludeInQuiescence(Board board, Move move) {
        if (!isQuiescenceMove(board, move)) {
            return false;
        }

        return !seeEnabled || !moveOrderer.isCapture(board, move) || staticExchangeEvaluator.evaluate(board, move) >= 0;
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