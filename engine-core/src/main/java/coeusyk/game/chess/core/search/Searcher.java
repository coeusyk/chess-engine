package coeusyk.game.chess.core.search;

import coeusyk.game.chess.core.eval.Evaluator;
import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.models.Piece;
import coeusyk.game.chess.core.movegen.MovesGenerator;
import coeusyk.game.chess.core.syzygy.DTZResult;
import coeusyk.game.chess.core.syzygy.NoOpSyzygyProber;
import coeusyk.game.chess.core.syzygy.SyzygyProber;
import coeusyk.game.chess.core.syzygy.WDLResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

public class Searcher {
    @FunctionalInterface
    public interface IterationListener {
        void onIteration(IterationInfo info);
    }

    private static final int INF = 1_000_000;
    private static final int MATE_SCORE = 100_000;
    private static final int MAX_PLY = 128;
    private static final int ASPIRATION_INITIAL_DELTA_CP = 50;
    private static final int NULL_MOVE_DEPTH_THRESHOLD = 3;
    private static final int MAX_LEGAL_MOVES = 256;
    private static final int FUTILITY_MARGIN_DEPTH_1 = 100;
    private static final int FUTILITY_MARGIN_DEPTH_2 = 300;
    private static final int RAZOR_MARGIN_DEPTH_1 = 300;
    private static final int RAZOR_MARGIN_DEPTH_2 = 600;
    private static final int MAX_CHECK_EXTENSIONS = 16;
    private static final int SINGULAR_DEPTH_THRESHOLD = 8;
    private static final int SINGULAR_MARGIN_PER_PLY = 8;
    private static final int TB_WIN_SCORE = MATE_SCORE - 2 * MAX_PLY;
    private static final int TB_LOSS_SCORE = -(MATE_SCORE - 2 * MAX_PLY);

    private long nodesVisited;
    private long leafNodes;
    private long quiescenceNodes;
    private long checkExtensionsApplied;
    private int seldepth;
    private long betaCutoffs;
    private long firstMoveCutoffs;
    private long ttHits;
    private long nullMoveCutoffs;
    private long lmrApplications;
    private long futilitySkips;

    private TimeManager timeManager;
    private long searchStartNanos;

    private Move[][] pvTable;
    private int[] pvLength;

    private final boolean moveOrderingEnabled;
    private final boolean aspirationWindowsEnabled;
    private final boolean nullMovePruningEnabled;
    private final boolean lmrEnabled;
    private final boolean futilityRazoringEnabled;
    private final boolean checkExtensionsEnabled;
    private final boolean singularExtensionsEnabled = true;
    private boolean seeEnabled = true;
    private final Evaluator evaluator = new Evaluator();
    private final MoveOrderer moveOrderer = new MoveOrderer();
    private final StaticExchangeEvaluator staticExchangeEvaluator = new StaticExchangeEvaluator();
    private final Move[][] killerMoves = new Move[MAX_PLY][2];
    private final int[][] historyHeuristic = new int[7][64];
    private final TranspositionTable transpositionTable = new TranspositionTable();
    private final int[][] lmrReductions = precomputeLmrReductions();

    private Move rootTtMoveHint;

    private int multiPV = 1;
    private List<Move> searchMoves = List.of();

    private SyzygyProber syzygyProber = new NoOpSyzygyProber();
    private int syzygyProbeDepth = 1;
    private boolean syzygy50MoveRule = true;

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

    public void setMultiPV(int multiPV) {
        this.multiPV = Math.max(1, multiPV);
    }

    public void setSearchMoves(List<Move> searchMoves) {
        this.searchMoves = searchMoves != null ? searchMoves : List.of();
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

    public void clearTranspositionTable() {
        transpositionTable.clear();
    }

    public double getTranspositionTableHitRate() {
        return transpositionTable.getHitRate();
    }

    public void setSyzygyProber(SyzygyProber prober) {
        this.syzygyProber = prober != null ? prober : new NoOpSyzygyProber();
    }

    public void setSyzygyProbeDepth(int depth) {
        this.syzygyProbeDepth = Math.max(0, depth);
    }

    public void setSyzygy50MoveRule(boolean respect) {
        this.syzygy50MoveRule = respect;
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
        this.timeManager = timeManager;
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
        this.timeManager = timeManager;
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
        long totalBetaCutoffs = 0;
        long totalFirstMoveCutoffs = 0;
        long totalTtHits = 0;
        long totalNullMoveCutoffs = 0;
        long totalLmrApplications = 0;
        long totalFutilitySkips = 0;
        long[] nodesPerDepth = new long[effectiveMaxDepth + 1];

        aborted = false;
        checkExtensionsApplied = 0;
        searchStartNanos = System.nanoTime();
        transpositionTable.resetStats();

        for (int depth = 1; depth <= effectiveMaxDepth; depth++) {
            if (shouldStopSoft.getAsBoolean()) {
                aborted = true;
                break;
            }

            seldepth = 0;
            int maxCheckExtensions = getMaxCheckExtensionsForDepth(depth);
            List<Move> excludedMoves = new ArrayList<>();
            boolean depthAborted = false;

            long nodesBeforeDepth = totalNodes;
            for (int pvIndex = 0; pvIndex < multiPV; pvIndex++) {
                nodesVisited = 0;
                leafNodes = 0;
                quiescenceNodes = 0;
                betaCutoffs = 0;
                firstMoveCutoffs = 0;
                ttHits = 0;
                nullMoveCutoffs = 0;
                lmrApplications = 0;
                futilitySkips = 0;
                int pvSize = depth + maxCheckExtensions + 4;
                pvTable = new Move[pvSize][pvSize];
                pvLength = new int[pvSize];

                RootResult iteration;
                if (pvIndex == 0 && aspirationWindowsEnabled && depth >= 2 && previousBestMove != null) {
                    iteration = searchRootWithAspiration(board, depth, previousBestMove, bestScore, shouldStopHard, maxCheckExtensions, excludedMoves);
                } else {
                    Move preferred = pvIndex == 0 ? previousBestMove : null;
                    iteration = searchRoot(board, depth, preferred, shouldStopHard, -INF, INF, maxCheckExtensions, excludedMoves);
                }

                totalNodes += nodesVisited;
                totalLeafNodes += leafNodes;
                totalQuiescenceNodes += quiescenceNodes;
                totalBetaCutoffs += betaCutoffs;
                totalFirstMoveCutoffs += firstMoveCutoffs;
                totalTtHits += ttHits;
                totalNullMoveCutoffs += nullMoveCutoffs;
                totalLmrApplications += lmrApplications;
                totalFutilitySkips += futilitySkips;

                if (iteration.bestMove == null) {
                    if (pvIndex == 0) {
                        depthAborted = true;
                    }
                    break;
                }

                if (pvIndex == 0 && !iteration.aborted) {
                    previousBestMove = iteration.bestMove;
                    bestScore = iteration.bestScore;
                    depthReached = depth;
                    bestPrincipalVariation = iteration.principalVariation;
                }

                excludedMoves.add(iteration.bestMove);

                if (listener != null) {
                    long elapsedMs = timeManager != null
                            ? timeManager.elapsedMs()
                            : (System.nanoTime() - searchStartNanos) / 1_000_000L;
                    IterationInfo info = new IterationInfo(
                            depth,
                            seldepth,
                            iteration.bestScore,
                            totalNodes,
                            elapsedMs,
                            transpositionTable.hashfull(),
                            iteration.principalVariation,
                            pvIndex + 1
                    );
                    listener.onIteration(info);
                }

                if (iteration.aborted) {
                    if (pvIndex == 0) {
                        depthAborted = true;
                    }
                    break;
                }
            }

            if (depthAborted) {
                aborted = true;
                break;
            }

            nodesPerDepth[depth] = totalNodes - nodesBeforeDepth;
            long elapsedMs = timeManager != null
                    ? timeManager.elapsedMs()
                    : (System.nanoTime() - searchStartNanos) / 1_000_000L;
            long nps = elapsedMs > 0 ? totalNodes * 1000L / elapsedMs : totalNodes;
            double fmcPct = totalBetaCutoffs > 0
                    ? 100.0 * totalFirstMoveCutoffs / totalBetaCutoffs : 0.0;
            double ebfNow = (depth >= 3 && nodesPerDepth[depth - 2] > 0)
                    ? Math.sqrt((double) nodesPerDepth[depth] / nodesPerDepth[depth - 2]) : 0.0;
            System.err.printf("[BENCH] depth=%d nodes=%d qnodes=%d nps=%d cutoffs=%d firstMoveCutoff%%=%.1f tt_hits=%d ebf=%.2f nmp_cuts=%d lmr_apps=%d fut_skips=%d time=%dms%n",
                    depth, totalNodes, totalQuiescenceNodes, nps,
                    totalBetaCutoffs, fmcPct, totalTtHits, ebfNow,
                    totalNullMoveCutoffs, totalLmrApplications, totalFutilitySkips, elapsedMs);
        }

        double ebf = 0.0;
        if (depthReached >= 3 && nodesPerDepth[depthReached - 2] > 0) {
            ebf = Math.sqrt((double) nodesPerDepth[depthReached] / nodesPerDepth[depthReached - 2]);
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
            totalBetaCutoffs,
            totalFirstMoveCutoffs,
            totalTtHits,
            ebf,
            aborted,
            totalNullMoveCutoffs,
            totalLmrApplications,
            totalFutilitySkips
        );
    }

    private RootResult searchRootWithAspiration(
            Board board,
            int depth,
            Move preferredMove,
            int previousIterationScore,
            BooleanSupplier shouldStopHard,
            int maxCheckExtensions,
            List<Move> excludedRootMoves
    ) {
        int alpha = Math.max(-INF, previousIterationScore - ASPIRATION_INITIAL_DELTA_CP);
        int beta = Math.min(INF, previousIterationScore + ASPIRATION_INITIAL_DELTA_CP);
        int consecutiveFailures = 0;

        while (true) {
            RootResult iteration = searchRoot(board, depth, preferredMove, shouldStopHard, alpha, beta, maxCheckExtensions, excludedRootMoves);
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
                return searchRoot(board, depth, preferredMove, shouldStopHard, -INF, INF, maxCheckExtensions, excludedRootMoves);
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
            int maxCheckExtensions,
            List<Move> excludedRootMoves
    ) {
        MovesGenerator generator = new MovesGenerator(board);
        List<Move> moves = new ArrayList<>(generator.getActiveMoves(board.getActiveColor()));
        TranspositionTable.Entry rootEntry = transpositionTable.probe(board.getZobristHash());

        if (!searchMoves.isEmpty()) {
            moves.removeIf(m -> !isExcludedMove(m, searchMoves));
        }

        if (!excludedRootMoves.isEmpty()) {
            moves.removeIf(m -> isExcludedMove(m, excludedRootMoves));
        }

        // Syzygy DTZ probe at root: if few pieces remain, ask the tablebase for the best move
        if (syzygyProber.isAvailable()
                && excludedRootMoves.isEmpty()
                && Long.bitCount(board.getAllOccupancy()) <= syzygyProber.getPieceLimit()) {
            DTZResult dtzResult = syzygyProber.probeDTZ(board);
            if (dtzResult.valid() && dtzResult.bestMoveUci() != null) {
                Move tbMove = findMoveByUci(moves, dtzResult.bestMoveUci());
                if (tbMove != null) {
                    int tbScore = wdlToScore(dtzResult.wdl());
                    return new RootResult(tbMove, tbScore, List.of(tbMove), false);
                }
            }
        }

        if (moves.isEmpty()) {
            if (!excludedRootMoves.isEmpty()) {
                return new RootResult(null, 0, List.of(), false);
            }
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
                    maxCheckExtensions,
                    false
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
            int maxExtensions,
            boolean inSingularitySearch
    ) {
        pvLength[ply] = 0;

        if (ply > seldepth) {
            seldepth = ply;
        }

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
            ttHits++;
            return ttScore;
        }

        // Syzygy WDL probe in search: return tablebase score for positions with few pieces
        if (syzygyProber.isAvailable()
                && effectiveDepth >= syzygyProbeDepth
                && Long.bitCount(board.getAllOccupancy()) <= syzygyProber.getPieceLimit()) {
            WDLResult wdlResult = syzygyProber.probeWDL(board);
            if (wdlResult.valid()) {
                int tbScore = wdlToScore(wdlResult.wdl());
                return tbScore;
            }
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
            int nullReduction = effectiveDepth >= 6 ? 3 : 2;
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
                maxExtensions,
                false
            );
            board.unmakeNullMove(nullMoveState);

            if (aborted) {
                return alpha;
            }

            if (nullScore >= beta) {
                nullMoveCutoffs++;
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

        Move singularMoveToExtend = null;
        if (canAttemptSingularity(effectiveDepth, ttEntry, inSingularitySearch, sideToMoveInCheck, alpha, beta)) {
            SingularityOutcome singularity = runSingularitySearch(
                    board,
                    moves,
                    ttEntry.bestMove(),
                    ttEntry.score(),
                    effectiveDepth,
                    ply,
                    shouldStopHard,
                    currentExtensionsUsed,
                    maxExtensions
            );

            if (aborted) {
                return alpha;
            }

            if (singularity.failHigh()) {
                return beta;
            }

            if (singularity.failLow()) {
                singularMoveToExtend = ttEntry.bestMove();
            }
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
            
            // Check for pawn promotion extension after move is made
            int childDepth = effectiveDepth - 1;
            int childExtensionsUsed = currentExtensionsUsed;
            if (shouldApplyPawnPromotionExtension(board, move, moveGivesCheck, currentExtensionsUsed, maxExtensions)) {
                childDepth = Math.min(MAX_PLY - 1, childDepth + 1);
                childExtensionsUsed++;
            }

            if (singularMoveToExtend != null
                    && sameMove(singularMoveToExtend, move)
                    && childExtensionsUsed < maxExtensions) {
                childDepth = Math.min(MAX_PLY - 1, childDepth + 1);
                childExtensionsUsed++;
            }
            
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
                futilitySkips++;
                board.unmakeMove();
                moveIndex++;
                continue;
            }

            int score;
            if (canApplyLmr(effectiveDepth, moveIndex, isQuiet, isKiller, isTtMove, sideToMoveInCheck, moveGivesCheck)) {
                lmrApplications++;
                int reduction = lmrReductions[Math.min(effectiveDepth, MAX_PLY - 1)][Math.min(moveIndex + 1, MAX_LEGAL_MOVES - 1)];
                int reducedDepth = Math.max(1, childDepth - reduction);

                score = -alphaBeta(
                        board,
                        reducedDepth,
                        ply + 1,
                        -(alpha + 1),
                        -alpha,
                        shouldStopHard,
                        false,
                        false,
                        childExtensionsUsed,
                        maxExtensions,
                        false
                );
                if (!aborted && score > alpha) {
                    boolean childIsPvNode = isPvNode && moveIndex == 0;
                    score = -alphaBeta(
                            board,
                            childDepth,
                            ply + 1,
                            -beta,
                            -alpha,
                            shouldStopHard,
                            false,
                            childIsPvNode,
                            childExtensionsUsed,
                            maxExtensions,
                            false
                    );
                }
            } else {
                boolean childIsPvNode = isPvNode && moveIndex == 0;
                score = -alphaBeta(
                        board,
                        childDepth,
                        ply + 1,
                        -beta,
                        -alpha,
                        shouldStopHard,
                        false,
                        childIsPvNode,
                        childExtensionsUsed,
                        maxExtensions,
                        false
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
                betaCutoffs++;
                if (moveIndex == 1) firstMoveCutoffs++;
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
        boolean enoughDepthForNullMove = depth >= NULL_MOVE_DEPTH_THRESHOLD;
        boolean hasRemainingDepth = (depth - 2 - 1) > 0; // use minimum R=2 for depth gate
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
        if (!futilityRazoringEnabled || isPvNode) {
            return false;
        }

        if (board.isActiveColorInCheck() || isMateWindow(alpha, beta)) {
            return false;
        }

        if (depth <= 0 || depth > 2) {
            return false;
        }

        int staticEval = evaluate(board);
        int margin = (depth == 1) ? RAZOR_MARGIN_DEPTH_1 : RAZOR_MARGIN_DEPTH_2;

        return (staticEval + margin) <= alpha;
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
            && depth >= 3
                && moveIndex >= 2
                && isQuiet
                && !isKiller
                && !isTtMove
                && !sideToMoveInCheck
                && !moveGivesCheck;
    }

    private boolean canAttemptSingularity(
            int depth,
            TranspositionTable.Entry ttEntry,
            boolean inSingularitySearch,
            boolean sideToMoveInCheck,
            int alpha,
            int beta
    ) {
        if (!singularExtensionsEnabled || inSingularitySearch || sideToMoveInCheck || isMateWindow(alpha, beta)) {
            return false;
        }

        if (depth < SINGULAR_DEPTH_THRESHOLD || ttEntry == null || ttEntry.bestMove() == null) {
            return false;
        }

        if (ttEntry.depth() < (depth - 3)) {
            return false;
        }

        return ttEntry.bound() == TTBound.EXACT || ttEntry.bound() == TTBound.LOWER_BOUND;
    }

    private SingularityOutcome runSingularitySearch(
            Board board,
            List<Move> moves,
            Move ttMove,
            int ttScore,
            int depth,
            int ply,
            BooleanSupplier shouldStopHard,
            int extensionsUsed,
            int maxExtensions
    ) {
        if (ttMove == null) {
            return new SingularityOutcome(false, false);
        }

        int singularAlpha = ttScore - getSingularMargin(depth);
        int singularBeta = singularAlpha + 1;
        int reducedDepth = Math.max(1, depth / 2);

        boolean searchedAlternative = false;
        for (Move move : moves) {
            if (sameMove(move, ttMove)) {
                continue;
            }

            searchedAlternative = true;
            board.makeMove(move);
            int score = -alphaBeta(
                    board,
                    Math.max(0, reducedDepth - 1),
                    ply + 1,
                    -singularBeta,
                    -singularAlpha,
                    shouldStopHard,
                    false,
                    false,
                    extensionsUsed,
                    maxExtensions,
                    true
            );
            board.unmakeMove();

            if (aborted) {
                return new SingularityOutcome(false, false);
            }

            if (score >= singularBeta) {
                return new SingularityOutcome(false, true);
            }
        }

        return new SingularityOutcome(searchedAlternative, false);
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

    int getSingularMarginForTesting(int depth) {
        return getSingularMargin(depth);
    }

    boolean canAttemptSingularityForTesting(int depth, TranspositionTable.Entry entry, boolean inSingularitySearch, boolean sideToMoveInCheck) {
        return canAttemptSingularity(depth, entry, inSingularitySearch, sideToMoveInCheck, -10_000, 10_000);
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

    private int getSingularMargin(int depth) {
        return depth * SINGULAR_MARGIN_PER_PLY;
    }

    private boolean shouldApplyPawnPromotionExtension(
            Board board,
            Move move,
            boolean moveGivesCheck,
            int extensionsUsed,
            int maxExtensions
    ) {
        if (extensionsUsed >= maxExtensions) {
            return false;
        }

        if (moveGivesCheck) {
            return false;
        }

        int targetSquare = move.targetSquare;
        int targetPiece = board.getPiece(targetSquare);
        
        if (Piece.type(targetPiece) != Piece.Pawn) {
            return false;
        }

        // Check if pawn is on 7th rank (white) or 2nd rank (black)
        int rankIndex = targetSquare / 8;
        boolean isWhitePawnOn7thRank = Piece.isWhite(targetPiece) && rankIndex == 1;
        boolean isBlackPawnOn2ndRank = Piece.isBlack(targetPiece) && rankIndex == 6;
        
        if (!isWhitePawnOn7thRank && !isBlackPawnOn2ndRank) {
            return false;
        }

        // Verify the advance is not immediately losing by SEE (safe >= 0)
        if (!seeEnabled) {
            return false;
        }

        return staticExchangeEvaluator.evaluateSquareOccupation(board, targetSquare) >= 0;
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

        if (ply > seldepth) {
            seldepth = ply;
        }

        if (shouldStopHard.getAsBoolean()) {
            aborted = true;
            return alpha;
        }

        boolean inCheck = board.isActiveColorInCheck(); // O(1) — no move generation

        if (inCheck) {
            // Must search all legal evasions; no stand-pat (king in check = must evade or be mated)
            MovesGenerator generator = new MovesGenerator(board);
            List<Move> legalMoves = generator.getActiveMoves(board.getActiveColor());
            if (legalMoves.isEmpty()) {
                leafNodes++;
                return evaluateTerminal(board, ply); // checkmate
            }

            int bestScore = -MATE_SCORE;
            for (Move move : legalMoves) {
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

        // Not in check: stand-pat, then captures only.
        // Terminal detection skipped — not in check means not checkmate;
        // stalemate is handled by the alpha-beta search above us.
        int standPat = evaluate(board);
        if (standPat >= beta) {
            leafNodes++;
            return standPat;
        }
        if (standPat > alpha) {
            alpha = standPat;
        }

        MovesGenerator generator = new MovesGenerator(board);
        List<Move> legalMoves = generator.getActiveMoves(board.getActiveColor());
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
        return evaluator.evaluate(board);
    }

    private int wdlToScore(WDLResult.WDL wdl) {
        if (wdl == null) {
            return 0;
        }
        return switch (wdl) {
            case WIN -> TB_WIN_SCORE;
            case LOSS -> TB_LOSS_SCORE;
            case DRAW -> 0;
        };
    }

    private Move findMoveByUci(List<Move> moves, String uci) {
        if (uci == null || uci.length() < 4) {
            return null;
        }
        int fromFile = uci.charAt(0) - 'a';
        int fromRank = uci.charAt(1) - '1';
        int toFile = uci.charAt(2) - 'a';
        int toRank = uci.charAt(3) - '1';
        // Convert a1=0 algebraic to a8=0 internal convention
        int fromSquare = (7 - fromRank) * 8 + fromFile;
        int toSquare = (7 - toRank) * 8 + toFile;

        String promoReaction = null;
        if (uci.length() > 4) {
            promoReaction = switch (uci.charAt(4)) {
                case 'q' -> "promote-q";
                case 'r' -> "promote-r";
                case 'b' -> "promote-b";
                case 'n' -> "promote-n";
                default -> null;
            };
        }

        for (Move move : moves) {
            if (move.startSquare == fromSquare && move.targetSquare == toSquare) {
                if (promoReaction == null || promoReaction.equals(move.reaction)) {
                    return move;
                }
            }
        }
        return null;
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

    private boolean isExcludedMove(Move move, List<Move> excluded) {
        for (Move ex : excluded) {
            if (move.startSquare == ex.startSquare
                    && move.targetSquare == ex.targetSquare
                    && Objects.equals(move.reaction, ex.reaction)) {
                return true;
            }
        }
        return false;
    }

    private record RootResult(Move bestMove, int bestScore, List<Move> principalVariation, boolean aborted) {
    }

    private record SingularityOutcome(boolean failLow, boolean failHigh) {
    }
}