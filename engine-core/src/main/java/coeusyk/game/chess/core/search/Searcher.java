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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Searcher {
    @FunctionalInterface
    public interface IterationListener {
        void onIteration(IterationInfo info);
    }

    private static final Logger LOG = LoggerFactory.getLogger(Searcher.class);
    private static final int INF = 1_000_000;
    private static final int MATE_SCORE = 100_000;
    private static final int MAX_PLY = 128;
    private static final int ASPIRATION_INITIAL_DELTA_CP = 50;
    private static final int NULL_MOVE_DEPTH_THRESHOLD = 3;
    private static final int MAX_LEGAL_MOVES = 256;
    private static final int FUTILITY_MARGIN_DEPTH_1 = 150;
    private static final int FUTILITY_MARGIN_DEPTH_2 = 300;
    private static final int RAZOR_MARGIN_DEPTH_1 = 300;
    private static final int RAZOR_MARGIN_DEPTH_2 = 600;
    private static final int MAX_CHECK_EXTENSIONS = 16;
    private static final int SINGULAR_DEPTH_THRESHOLD = 8;
    private static final int SINGULAR_MARGIN_PER_PLY = 8;
    private static final int TB_WIN_SCORE = MATE_SCORE - 2 * MAX_PLY;
    private static final int TB_LOSS_SCORE = -(MATE_SCORE - 2 * MAX_PLY);

    // Minimum material+PST advantage (white-positive, centipawns, MG scale) required for
    // contempt to activate.  Below this threshold the position is close enough to equal
    // that a draw score of 0 is more appropriate.  Prevents balanced middlegames from
    // being distorted by a non-zero contempt penalty.
    private static final int CONTEMPT_THRESHOLD = 150;

    // Correction history: maps pawn structure to a static-eval bias.
    // Stored at GRAIN scale; applied as: adjustedEval = rawEval + ch[color][key] / GRAIN.
    private static final int CORRECTION_HISTORY_SIZE = 1024;
    private static final int CORRECTION_HISTORY_GRAIN = 256;
    private static final int CORRECTION_HISTORY_MAX = CORRECTION_HISTORY_GRAIN * 32;

    // LMR reduction formula divisor: 2*(ln2)^2 ≈ 0.961.
    // R = max(1, floor(1 + ln(depth)*ln(moveIndex) / LMR_LOG_DIVISOR)).
    // NOTE: the experiment registry (C-2) describes this as "2*ln(2) ≈ 1.386" — that is wrong.
    // The actual value is 2*(ln(2))^2 ≈ 0.961 (more aggressive: larger R at equal depth/move).
    // C-2 SPRT candidates test LARGER divisors (1.386, 1.7, 2.0) = LESS aggressive LMR.
    private static final double LMR_LOG_DIVISOR = 2.0 * Math.log(2) * Math.log(2);

    // Delta pruning thresholds used in quiescence search.
    // Values mirror the SEE piece table to keep material reasoning consistent.
    private static final int DELTA_MARGIN = 200;
    private static final int[] DELTA_PIECE_VALUES = { 0, 100, 320, 330, 500, 900, 0 };
    // Minimum occupancy below which delta pruning is disabled (endgame safety).
    private static final int DELTA_MIN_PIECE_COUNT = 6;
    // Maximum extra plies the Q-search is allowed to recurse beyond the main-search horizon.
    // Prevents Q-search explosion on tactical positions with many long capture chains.
    private static final int MAX_Q_DEPTH = 6;
    // Additional plies allowed when in check beyond MAX_Q_DEPTH. Without this cap, check-chain
    // positions cause unbounded evasion trees (B^k nodes). The cap still gives 3 extra plies of
    // check resolution before forcing a stand-pat return.
    private static final int MAX_Q_CHECK_EXTENSION = 3;

    private long nodesVisited;
    private long leafNodes;
    private long quiescenceNodes;
    private long checkExtensionsApplied;
    private long matingThreatExtensionsApplied;
    private int seldepth;
    private long betaCutoffs;
    private long firstMoveCutoffs;
    private long ttHits;
    private long nullMoveCutoffs;
    private long lmrApplications;
    private long futilitySkips;
    private long deltaPruningSkips;

    private TimeManager timeManager;
    private long searchStartNanos;

    private final int[][] pvTable = new int[MOVE_LIST_POOL_SIZE][MOVE_LIST_POOL_SIZE];
    private final int[] pvLength = new int[MOVE_LIST_POOL_SIZE];

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
    private final int[][] killerMoves = initKillerMoves();
    private final int[][] historyHeuristic = new int[7][64];
    private final int[][] correctionHistory = new int[2][CORRECTION_HISTORY_SIZE];
    private final int[] staticEvalStack = new int[MAX_PLY + 2];
    // Not final: can be replaced with a shared instance for Lazy SMP multi-threaded search.
    // Initialised with a 1 MB placeholder so that UciApplication's call to
    // setSharedTranspositionTable() does not orphan a full 64 MB array on every
    // search (main thread + each helper).  Any code that uses Searcher standalone
    // (tests, tuner) gets a working but small private TT; the UCI path always
    // replaces it with sharedTT before searching.
    private TranspositionTable transpositionTable = new TranspositionTable(1);
    private final int[][] lmrReductions = precomputeLmrReductions();

    // Per-thread pre-allocated move lists — one slot per ply level (MAX_PLY) plus
    // headroom for Q-search plies (MAX_Q_DEPTH) and a safety margin.
    // The DFS property guarantees at most one live frame per ply, so slots are never
    // aliased. Each helper-thread Searcher has its own pool (never shared).
    private static final int MOVE_LIST_POOL_SIZE = MAX_PLY + MAX_Q_DEPTH + 14; // 148

    private static final int MOVE_BUFFER_SIZE = 256;
    private final int[][] moveListPool = new int[MOVE_LIST_POOL_SIZE][MOVE_BUFFER_SIZE];
    // Root search still uses ArrayList<Move> (called ~10x per game move; trivial cost).
    private final ArrayList<Move> rootMoveList = new ArrayList<>(128);

    private static int[][] initKillerMoves() {
        int[][] km = new int[MAX_PLY][2];
        for (int[] row : km) {
            row[0] = Move.NONE;
            row[1] = Move.NONE;
        }
        return km;
    }

    private Move rootTtMoveHint;

    private int multiPV = 1;
    private List<Move> searchMoves = List.of();

    private SyzygyProber syzygyProber = new NoOpSyzygyProber();
    private int syzygyProbeDepth = 1;
    @SuppressWarnings("unused") // setter is public API; read when Syzygy probing is wired up
    private boolean syzygy50MoveRule = true;

    private boolean aborted;

    // Penalty applied to draw-by-repetition and 50-move-rule draws when the side to move
    // has a clear material advantage (> CONTEMPT_THRESHOLD cp).  A positive value makes the
    // engine avoid draws when winning and accept them when losing.  Set via setContempt();
    // defaults to 0 (neutral) so that tests using new Searcher() are unaffected.
    private int contemptCp = 0;

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

    /**
     * Sets the draw-contempt value in centipawns.
     * When the side to move has a material+PST advantage exceeding {@code CONTEMPT_THRESHOLD},
     * repetition and 50-move-rule draws return {@code -cp} (bad for the winning side).
     * When the side to move is behind by the same margin, they return {@code +cp} (good for
     * the losing side).  Balanced positions always return 0 regardless of this setting.
     * Insufficient-material draws always return 0 (they are genuine draws).
     *
     * @param cp contempt in centipawns; clamped to [0, 200]
     */
    public void setContempt(int cp) {
        this.contemptCp = Math.max(0, Math.min(200, cp));
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

    /**
     * Replaces this searcher's private transposition table with the supplied shared
     * instance.  Used by Lazy SMP: all helper threads share one TT so each thread's
     * discoveries are immediately visible to the others.
     *
     * <p>The shared TT must already be sized appropriately before injection; calling
     * {@link #setTranspositionTableSizeMb} after this call would resize the shared
     * table for all threads simultaneously.
     *
     * @param tt shared {@link TranspositionTable} to inject; ignored if {@code null}
     */
    public void setSharedTranspositionTable(TranspositionTable tt) {
        if (tt != null) {
            this.transpositionTable = tt;
        }
    }

    public void clearTranspositionTable() {
        transpositionTable.clear();
    }

    public double getTranspositionTableHitRate() {
        return transpositionTable.getHitRate();
    }

    public TranspositionTable.TTStats getTranspositionTableStats() {
        return transpositionTable.getStats();
    }

    /** Enables TT probe/hit statistics tracking. Off by default — see {@link TranspositionTable#enableStats()}. */
    void enableTTStats() {
        transpositionTable.enableStats();
    }

    /** Enables pawn-hash statistics tracking in the evaluator. Resets counters. */
    void enablePawnHashStats() {
        evaluator.enablePawnHashStats();
    }

    /** Returns the pawn-hash hit rate [0.0, 1.0] since stats were last enabled. */
    double getPawnHashHitRate() {
        return evaluator.getPawnHashHitRate();
    }

    /** Resizes the pawn hash table in the evaluator. See {@link Evaluator#setPawnHashSizeMb}. */
    public void setPawnHashSizeMb(int mb) {
        evaluator.setPawnHashSizeMb(mb);
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
        return iterativeDeepening(board, maxDepth, 1, shouldStopSoft, shouldStopHard, listener);
    }

    /**
     * Iterative deepening with a configurable start depth.
     * Used by Lazy SMP helper threads to stagger entry depths and reduce
     * redundant shallow-depth work between threads.
     */
    public SearchResult iterativeDeepening(
            Board board,
            int maxDepth,
            int startDepth,
            BooleanSupplier shouldStopSoft,
            BooleanSupplier shouldStopHard,
            IterationListener listener
    ) {

        if (maxDepth < 1) {
            throw new IllegalArgumentException("depth must be >= 1");
        }

        int effectiveMaxDepth = Math.min(maxDepth, MAX_PLY - 1);
        int effectiveStartDepth = Math.max(1, Math.min(startDepth, effectiveMaxDepth));

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
        long totalDeltaPruningSkips = 0;
        long[] nodesPerDepth = new long[effectiveMaxDepth + 1];

        aborted = false;
        checkExtensionsApplied = 0;
        matingThreatExtensionsApplied = 0;
        searchStartNanos = System.nanoTime();
        transpositionTable.resetStats();
        java.util.Arrays.fill(staticEvalStack, 0);

        // Tracks how many consecutive completed depths produced the same best move.
        // Used to scale the soft time limit: stable positions get a discount so the
        // engine stops early; positions where the best move just changed get extra time.
        int stableDepthCount = 0;
        if (timeManager != null) {
            timeManager.setStabilityScale(1.0);
        }

        for (int depth = effectiveStartDepth; depth <= effectiveMaxDepth; depth++) {
            if (shouldStopSoft.getAsBoolean()) {
                aborted = true;
                break;
            }

            // Snapshot best move before this depth so we can detect stability after.
            Move bestMoveBeforeDepth = previousBestMove;

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
                deltaPruningSkips = 0;
                // pvTable and pvLength are pre-allocated; no per-depth allocation needed.
                RootResult iteration;
                if (pvIndex == 0 && aspirationWindowsEnabled && depth >= 4 && previousBestMove != null) {
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
                totalDeltaPruningSkips += deltaPruningSkips;

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

            // --- Stability-based soft-limit scaling ---
            // Only active when a TimeManager is present (clock-based search) and once
            // we have at least depth 5 so the best move is reliable.
            if (timeManager != null && depth >= 5 && previousBestMove != null) {
                boolean sameMove = previousBestMove.equals(bestMoveBeforeDepth);
                if (sameMove) {
                    stableDepthCount++;
                } else {
                    stableDepthCount = 0;
                }
                double scale;
                if (!sameMove) {
                    // Best move just changed — give extra time to resolve the instability.
                    scale = 1.20;
                } else if (stableDepthCount >= 3) {
                    // Stable for 3+ depths — position is clearly resolved, stop earlier.
                    scale = 0.75;
                } else if (stableDepthCount == 2) {
                    // Stable for 2 depths — apply a mild discount.
                    scale = 0.85;
                } else {
                    scale = 1.0;
                }
                timeManager.setStabilityScale(scale);
            }

            // Stop before starting the next depth if the soft time budget is
            // already exceeded after completing this one.  Prevents a cheap
            // depth-N from allowing a very expensive depth-N+1 to start when
            // the engine has already gone past the soft limit.
            if (shouldStopSoft.getAsBoolean()) {
                break;
            }

            // If a forced mate has been confirmed at this depth, there is no
            // benefit to searching deeper — the result cannot improve.
            if (Math.abs(bestScore) >= MATE_SCORE - MAX_PLY) {
                break;
            }
            long elapsedMs = timeManager != null
                    ? timeManager.elapsedMs()
                    : (System.nanoTime() - searchStartNanos) / 1_000_000L;
            long nps = elapsedMs > 0 ? totalNodes * 1000L / elapsedMs : totalNodes;
            double fmcPct = totalBetaCutoffs > 0
                    ? 100.0 * totalFirstMoveCutoffs / totalBetaCutoffs : 0.0;
            double ebfNow = (depth >= 3 && nodesPerDepth[depth - 2] > 0)
                    ? Math.sqrt((double) nodesPerDepth[depth] / nodesPerDepth[depth - 2]) : 0.0;
            LOG.debug(String.format("[BENCH] depth=%d nodes=%d qnodes=%d nps=%d cutoffs=%d firstMoveCutoff%%=%.1f tt_hits=%d ebf=%.2f nmp_cuts=%d lmr_apps=%d fut_skips=%d delta_prune=%d time=%dms",
                    depth, totalNodes, totalQuiescenceNodes, nps,
                    totalBetaCutoffs, fmcPct, totalTtHits, ebfNow,
                    totalNullMoveCutoffs, totalLmrApplications, totalFutilitySkips, totalDeltaPruningSkips, elapsedMs));
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
            totalNodes > 0 ? (double) totalTtHits / totalNodes : 0.0,
            totalBetaCutoffs,
            totalFirstMoveCutoffs,
            totalTtHits,
            ebf,
            aborted,
            totalNullMoveCutoffs,
            totalLmrApplications,
            totalFutilitySkips,
            totalDeltaPruningSkips
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
        ArrayList<Move> moves = rootMoveList;
        moves.clear();
        new MovesGenerator(board, moves);
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
                    : (rootEntry != null && rootEntry.bestMove() != Move.NONE
                        ? new Move(Move.from(rootEntry.bestMove()), Move.to(rootEntry.bestMove()), Move.reactionOf(rootEntry.bestMove()))
                        : preferredMove);
            moveOrderer.orderMoves(board, moves, 0, ttMove, killerMoves, historyHeuristic);
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

                pvTable[0][0] = move.pack();
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

        // Draw detection: return a contempt-adjusted score for avoidable draws, 0 for genuine draws.
        // (Skip at root so searchRoot always returns a bestMove, never a raw draw score.)
        if (ply > 0) {
            if (board.isInsufficientMaterial()) {
                return 0; // genuine draw — always neutral
            }
            if (board.isRepetitionDraw() || board.isFiftyMoveRuleDraw()) {
                return contemptScore(board);
            }
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
            // Mating-threat leaf extension: when alpha is already in mate territory
            // (a forced mate was found in another branch), extend 1 ply to check
            // whether this branch also resolves the position — avoids quiet-move
            // horizon blindness near a forced mating sequence.
            if (currentExtensionsUsed < maxExtensions && alpha >= MATE_SCORE - MAX_PLY) {
                effectiveDepth = 1;
                currentExtensionsUsed++;
                matingThreatExtensionsApplied++;
            } else {
                return quiescence(board, alpha, beta, ply, 0, shouldStopHard);
            }
        }

        long zobrist = board.getZobristHash();
        int alphaOrig = alpha;
        TranspositionTable.Entry ttEntry = transpositionTable.probe(zobrist);
        Integer ttScore = applyTtBound(ttEntry, effectiveDepth, alpha, beta, ply);
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

        int staticEval = evaluate(board);

        // Derive a pawn-structure key for correction history lookup.
        int colorIdx = Piece.isWhite(board.getActiveColor()) ? 0 : 1;
        int pawnKey = (int)(((board.getWhitePawns() ^ board.getBlackPawns()) * 0x9E3779B97F4A7C15L) >>> 54);
        int staticEvalRaw = staticEval;
        // Apply accumulated correction bias (capped at ±32 cp).
        staticEval += correctionHistory[colorIdx][pawnKey] / CORRECTION_HISTORY_GRAIN;

        // Improving flag: true when eval is higher than 2 plies ago (same mover).
        // Used to tighten LMR when the position is declining.
        boolean improving = ply >= 2 && !sideToMoveInCheck
                && staticEval > staticEvalStack[ply - 2];
        staticEvalStack[ply] = staticEval;

        if (canApplyRazoring(effectiveDepth, alpha, beta, isPvNode, sideToMoveInCheck, staticEval)) {
            int qScore = quiescence(board, alpha, alpha + 1, ply, 0, shouldStopHard);
            if (aborted) {
                return alpha;
            }

            if (qScore <= alpha) {
                return qScore;
            }
        }

        if (nullMovePruningEnabled && canApplyNullMove(board, effectiveDepth, previousMoveWasNull, beta,
                sideToMoveInCheck, staticEval)) {
            int nullReduction = effectiveDepth > 6 ? 3 : 2;
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

        int poolIdx = Math.min(ply, MOVE_LIST_POOL_SIZE - 1);
        int[] moves = moveListPool[poolIdx];
        int moveCount = MovesGenerator.generate(board, moves);
        int ttMoveInt = ttEntry != null ? ttEntry.bestMove() : Move.NONE;
        if (moveOrderingEnabled) {
            int orderPly = Math.min(ply, MAX_PLY - 1);
            moveOrderer.orderMoves(board, moves, moveCount, orderPly, ttMoveInt, killerMoves, historyHeuristic);
        }

        if (moveCount == 0) {
            leafNodes++;
            return evaluateTerminal(board, ply);
        }

        int singularMoveToExtend = Move.NONE;
        if (canAttemptSingularity(effectiveDepth, ttEntry, inSingularitySearch, sideToMoveInCheck, alpha, beta)) {
            SingularityOutcome singularity = runSingularitySearch(
                    board,
                    moves,
                    moveCount,
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
        int bestMove = Move.NONE;
        int moveIndex = 0;
        for (int mi = 0; mi < moveCount; mi++) {
            int move = moves[mi];
            boolean isQuiet = isQuietMove(board, move);
            boolean isCapture = moveOrderer.isCapture(board, move);
            boolean isKiller = isKillerMove(ply, move);
            boolean isTtMove = ttMoveInt != Move.NONE && move == ttMoveInt;
            // Read ordering score before makeMove (before recursion can overwrite scoringBuffer).
            // Losing captures have score < 0 (LOSING_CAPTURE_BASE + seeScore, where seeScore < 0).
            // This avoids recomputing SEE here — it was already computed during orderMoves.
            boolean isLosingCapture = isCapture && seeEnabled && moveOrderingEnabled
                    && moveOrderer.scoringBuffer[mi] < 0;

            board.makeMove(move);

            boolean moveGivesCheck = board.isActiveColorInCheck();
            
            // Check for pawn promotion extension after move is made
            int childDepth = effectiveDepth - 1;
            int childExtensionsUsed = currentExtensionsUsed;
            if (shouldApplyPawnPromotionExtension(board, move, moveGivesCheck, currentExtensionsUsed, maxExtensions)) {
                childDepth = Math.min(MAX_PLY - 1, childDepth + 1);
                childExtensionsUsed++;
            }

            if (singularMoveToExtend != Move.NONE
                    && singularMoveToExtend == move
                    && childExtensionsUsed < maxExtensions) {
                childDepth = Math.min(MAX_PLY - 1, childDepth + 1);
                childExtensionsUsed++;
            }
            
            if (canPruneLosingCapture(
                    effectiveDepth,
                    isLosingCapture,
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
                if (!improving) { reduction++; }
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
            transpositionTable.store(zobrist, bestMove, effectiveDepth, scoreToTT(bestScore, ply), bound);

            // Update correction history: accumulate how much the search
            // score diverges from the raw static eval for this pawn structure.
            if (!inSingularitySearch && !sideToMoveInCheck) {
                int corrError = bestScore - staticEvalRaw;
                int weight = CORRECTION_HISTORY_GRAIN / Math.max(1, effectiveDepth);
                correctionHistory[colorIdx][pawnKey] = Math.max(-CORRECTION_HISTORY_MAX,
                        Math.min(CORRECTION_HISTORY_MAX,
                                correctionHistory[colorIdx][pawnKey] + corrError * weight));
            }
        }

        return bestScore;
    }

    private boolean canApplyNullMove(Board board, int depth, boolean previousMoveWasNull, int beta,
                                      boolean inCheck, int staticEval) {
        boolean enoughDepthForNullMove = depth >= NULL_MOVE_DEPTH_THRESHOLD;
        boolean hasRemainingDepth = (depth - 2 - 1) > 0; // use minimum R=2 for depth gate
        boolean isMateWindow = Math.abs(beta) >= (MATE_SCORE - MAX_PLY);

        return enoughDepthForNullMove
            && hasRemainingDepth
                && !previousMoveWasNull
                && !inCheck
                && !isMateWindow
            && staticEval >= beta
                && hasNonPawnMaterial(board, board.getActiveColor());
    }

    private boolean canApplyRazoring(int depth, int alpha, int beta, boolean isPvNode,
                                      boolean inCheck, int staticEval) {
        if (!futilityRazoringEnabled || isPvNode) {
            return false;
        }

        if (inCheck || isMateWindow(alpha, beta)) {
            return false;
        }

        if (depth <= 0 || depth > 2) {
            return false;
        }

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
            boolean isLosingCapture,
            boolean isPvNode,
            boolean sideToMoveInCheck,
            boolean moveGivesCheck
    ) {
        if (!isLosingCapture) {
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
        if (Piece.isWhite(color)) {
            return (board.getWhiteRooks() | board.getWhiteKnights() | board.getWhiteBishops() | board.getWhiteQueens()) != 0L;
        } else {
            return (board.getBlackRooks() | board.getBlackKnights() | board.getBlackBishops() | board.getBlackQueens()) != 0L;
        }
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
                && moveIndex >= 4
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

        if (depth < SINGULAR_DEPTH_THRESHOLD || ttEntry == null || ttEntry.bestMove() == Move.NONE) {
            return false;
        }

        if (ttEntry.depth() < (depth - 3)) {
            return false;
        }

        return ttEntry.bound() == TTBound.EXACT || ttEntry.bound() == TTBound.LOWER_BOUND;
    }

    private SingularityOutcome runSingularitySearch(
            Board board,
            int[] moves,
            int moveCount,
            int ttMoveInt,
            int ttScore,
            int depth,
            int ply,
            BooleanSupplier shouldStopHard,
            int extensionsUsed,
            int maxExtensions
    ) {
        if (ttMoveInt == Move.NONE) {
            return new SingularityOutcome(false, false);
        }

        int singularAlpha = ttScore - getSingularMargin(depth);
        int singularBeta = singularAlpha + 1;
        int reducedDepth = Math.max(1, depth / 2);

        boolean searchedAlternative = false;
        for (int i = 0; i < moveCount; i++) {
            int move = moves[i];
            if (move == ttMoveInt) {
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
        // Formula: R = max(1, floor(1 + log2(depth) * log2(moveIndex) / 2))
        // log2(x) = ln(x) / ln(2).  Rewritten in terms of natural log:
        //   R = max(1, floor(1 + ln(depth)*ln(moveIndex) / LMR_LOG_DIVISOR))
        int[][] reductions = new int[MAX_PLY][MAX_LEGAL_MOVES];
        for (int depth = 1; depth < MAX_PLY; depth++) {
            for (int moveIndex = 1; moveIndex < MAX_LEGAL_MOVES; moveIndex++) {
                int reduction = Math.max(
                        1,
                        (int) (1.0 + (Math.log(depth) * Math.log(moveIndex)) / LMR_LOG_DIVISOR)
                );
                reductions[depth][moveIndex] = reduction;
            }
        }
        return reductions;
    }

    private boolean isKillerMove(int ply, int move) {
        if (ply < 0 || ply >= MAX_PLY) {
            return false;
        }

        return (killerMoves[ply][0] != Move.NONE && killerMoves[ply][0] == move)
                || (killerMoves[ply][1] != Move.NONE && killerMoves[ply][1] == move);
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

    long getMatingThreatExtensionsAppliedForTesting() {
        return matingThreatExtensionsApplied;
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
            int move,
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

        int targetSquare = Move.to(move);
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

    boolean shouldApplyQuiescenceDepthCapForTesting(int qPly, boolean inCheck) {
        return shouldApplyQuiescenceDepthCap(qPly, inCheck);
    }

    /** Test accessor for quiescence — runs a single Q-search call with a no-abort supplier. */
    int quiescenceForTesting(Board board, int alpha, int beta, int qPly) {
        return quiescence(board, alpha, beta, 0, qPly, () -> false);
    }

    /**
     * Convert a root-relative mate score to a node-relative score for TT storage.
     * Non-mate scores pass through unchanged.
     * Root-relative: MATE_SCORE - plyOfCheckmate (from root). After adjustment the
     * stored value encodes plies-to-mate from THIS node, making it portable across
     * different plies where the same position is revisited in a later iteration.
     */
    static int scoreToTT(int score, int ply) {
        if (score >= MATE_SCORE - MAX_PLY) return score + ply;
        if (score <= -(MATE_SCORE - MAX_PLY)) return score - ply;
        return score;
    }

    /**
     * Convert a node-relative mate score retrieved from the TT back to a root-relative
     * score. Non-mate scores pass through unchanged.
     */
    static int scoreFromTT(int score, int ply) {
        if (score >= MATE_SCORE - MAX_PLY) return score - ply;
        if (score <= -(MATE_SCORE - MAX_PLY)) return score + ply;
        return score;
    }

    Integer applyTtBound(TranspositionTable.Entry entry, int depth, int alpha, int beta, int ply) {
        if (entry == null || entry.depth() < depth) {
            return null;
        }

        int score = scoreFromTT(entry.score(), ply);
        return switch (entry.bound()) {
            case EXACT -> score;
            case LOWER_BOUND -> (score >= beta) ? score : null;
            case UPPER_BOUND -> (score <= alpha) ? score : null;
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

    private int quiescence(Board board, int alpha, int beta, int ply, int qPly, BooleanSupplier shouldStopHard) {
        quiescenceNodes++;

        if (ply > seldepth) {
            seldepth = ply;
        }

        if (shouldStopHard.getAsBoolean()) {
            aborted = true;
            return alpha;
        }

        boolean inCheck = board.isActiveColorInCheck(); // O(1) — no move generation

        // Q-search ply limit: once we've gone MAX_Q_DEPTH extra plies past the main
        // search frontier, return a static eval. Prevents explosion on tactical positions
        // with many long capture/check chains (e.g., CPW pos4).
        // Never apply this cap while in check: legal evasions must still be searched.
        if (shouldApplyQuiescenceDepthCap(qPly, inCheck)) {
            return evaluate(board);
        }

        if (inCheck) {
            // Must search all legal evasions; no stand-pat (king in check = must evade or be mated)
            int poolIdxQ = Math.min(ply, MOVE_LIST_POOL_SIZE - 1);
            int[] legalMoves = moveListPool[poolIdxQ];
            int legalCount = MovesGenerator.generate(board, legalMoves);
            if (legalCount == 0) {
                leafNodes++;
                return evaluateTerminal(board, ply); // checkmate
            }

            int bestScore = -MATE_SCORE;
            for (int qi = 0; qi < legalCount; qi++) {
                int move = legalMoves[qi];
                board.makeMove(move);
                int score = -quiescence(board, -beta, -alpha, ply + 1, qPly + 1, shouldStopHard);
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

        int standPat = evaluate(board);
        if (standPat >= beta) {
            leafNodes++;
            return standPat;
        }
        if (standPat > alpha) {
            alpha = standPat;
        }

        // Delta pruning: enabled when not in a mate window and enough pieces remain
        // to avoid endgame horizon errors (e.g. KBvKN or KBNK insufficient-material cases).
        boolean deltaPruningAllowed = !isMateWindow(alpha, beta)
                && Long.bitCount(board.getAllOccupancy()) > DELTA_MIN_PIECE_COUNT;

        // Node-level big-delta check: if even capturing the most valuable piece
        // plus the safety margin cannot raise alpha, skip move generation entirely.
        if (deltaPruningAllowed) {
            int bigDelta = DELTA_PIECE_VALUES[Piece.Queen] + DELTA_MARGIN;
            if (standPat + bigDelta < alpha) {
                return standPat;
            }
        }

        int poolIdxQq = Math.min(ply, MOVE_LIST_POOL_SIZE - 1);
        int[] allQMoves = moveListPool[poolIdxQq];

        // ---- Stage 1: non-pawn captures and knight/rook/bishop/queen captures ----
        // Pawn captures are deferred to stage 2 so we can skip them entirely if a
        // beta cutoff fires here (saving ~10% Q-node cost in piece-rich positions).
        int stage1Raw = MovesGenerator.generateNonPawnCaptures(board, allQMoves);
        // Apply SEE filter: remove losing captures; always keep queen promotions.
        int qCount = 0;
        for (int i = 0; i < stage1Raw; i++) {
            int qm = allQMoves[i];
            if (seeEnabled && moveOrderer.isCapture(board, qm)
                    && staticExchangeEvaluator.evaluate(board, qm) < 0) {
                continue;
            }
            allQMoves[qCount++] = qm;
        }

        int bestScore = standPat;
        for (int qi = 0; qi < qCount; qi++) {
            int move = allQMoves[qi];
            // Per-move delta pruning: skip non-promotion captures whose material gain
            // plus the safety margin still cannot raise alpha.
            if (deltaPruningAllowed && !Move.isPromotion(move)) {
                int captureGain = capturedPieceValueForDelta(board, move);
                if (standPat + captureGain + DELTA_MARGIN <= alpha) {
                    deltaPruningSkips++;
                    continue;
                }
            }

            board.makeMove(move);
            int score = -quiescence(board, -beta, -alpha, ply + 1, qPly + 1, shouldStopHard);
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
                // Beta cutoff from non-pawn captures: skip pawn capture generation entirely.
                return bestScore;
            }
        }

        // ---- Stage 2: pawn captures and quiet pawn promotions ----
        // Only reached when stage 1 didn't produce a beta cutoff.
        int pawnStart = qCount;
        int pawnEnd   = MovesGenerator.appendPawnCaptures(board, allQMoves, pawnStart);
        // SEE filter: pawn captures in allQMoves[pawnStart..pawnEnd) written back in-place.
        int totalCount = pawnStart;
        for (int i = pawnStart; i < pawnEnd; i++) {
            int qm = allQMoves[i];
            if (seeEnabled && moveOrderer.isCapture(board, qm)
                    && staticExchangeEvaluator.evaluate(board, qm) < 0) {
                continue;
            }
            allQMoves[totalCount++] = qm;
        }

        if (totalCount == 0) {
            // Stalemate guard: in endings with few pieces, verify that at least one quiet
            // move is available before returning stand-pat. Without this, a stalemated side
            // returns standPat (large negative) instead of 0.
            // Gate: only pay the full move-generation cost when ≤ 8 total pieces remain.
            if (Long.bitCount(board.getAllOccupancy()) <= 8) {
                int allCount = MovesGenerator.generate(board, allQMoves);
                if (allCount == 0) {
                    leafNodes++;
                    return 0; // stalemate
                }
            }
            leafNodes++;
            return standPat;
        }

        for (int qi = pawnStart; qi < totalCount; qi++) {
            int move = allQMoves[qi];
            if (deltaPruningAllowed && !Move.isPromotion(move)) {
                int captureGain = capturedPieceValueForDelta(board, move);
                if (standPat + captureGain + DELTA_MARGIN <= alpha) {
                    deltaPruningSkips++;
                    continue;
                }
            }

            board.makeMove(move);
            int score = -quiescence(board, -beta, -alpha, ply + 1, qPly + 1, shouldStopHard);
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

    private boolean shouldApplyQuiescenceDepthCap(int qPly, boolean inCheck) {
        return qPly >= (inCheck ? MAX_Q_DEPTH + MAX_Q_CHECK_EXTENSION : MAX_Q_DEPTH);
    }

    private int capturedPieceValueForDelta(Board board, int move) {
        if (Move.isEnPassant(move)) {
            return DELTA_PIECE_VALUES[Piece.Pawn];
        }
        int captured = board.getPiece(Move.to(move));
        if (captured == Piece.None) {
            return 0;
        }
        return DELTA_PIECE_VALUES[Piece.type(captured)];
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

    /**
     * Returns the draw score adjusted for contempt.
     * Uses the incrementally maintained MG material+PST score (O(1), already computed by Board).
     * <ul>
     *   <li>If the side to move is winning by &gt; CONTEMPT_THRESHOLD: returns {@code -contemptCp}
     *       (draw is bad for them — engine avoids it).</li>
     *   <li>If the side to move is losing by &gt; CONTEMPT_THRESHOLD: returns {@code +contemptCp}
     *       (draw is acceptable for them — engine accepts it).</li>
     *   <li>Otherwise: returns 0 (balanced — no contempt distortion).</li>
     * </ul>
     */
    private int contemptScore(Board board) {
        if (contemptCp == 0) return 0;
        // incMgScore is white-positive; flip sign for black to get side-to-move advantage.
        int incMg = board.getIncMgScore();
        int sideToMoveAdv = Piece.isWhite(board.getActiveColor()) ? incMg : -incMg;
        if (sideToMoveAdv >  CONTEMPT_THRESHOLD) return -contemptCp;
        if (sideToMoveAdv < -CONTEMPT_THRESHOLD) return  contemptCp;
        return 0;
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

    boolean shouldIncludeInQuiescenceForTesting(Board board, Move move) {
        return shouldIncludeInQuiescence(board, move.pack());
    }

    private boolean shouldIncludeInQuiescence(Board board, int move) {
        if (!isQuiescenceMove(board, move)) {
            return false;
        }

        return !seeEnabled || !moveOrderer.isCapture(board, move) || staticExchangeEvaluator.evaluate(board, move) >= 0;
    }

    private boolean isQuiescenceMove(Board board, int move) {
        boolean isQueenPromotion = Move.flag(move) == Move.FLAG_PROMO_Q;
        boolean isCapture = Move.isEnPassant(move) || board.getPiece(Move.to(move)) != Piece.None;
        return isCapture || isQueenPromotion;
    }

    private boolean isQuietMove(Board board, int move) {
        return !moveOrderer.isCapture(board, move) && !Move.isPromotion(move);
    }

    private void storeKillerMove(int ply, int move) {
        if (ply < 0 || ply >= MAX_PLY) {
            return;
        }

        if (killerMoves[ply][0] != Move.NONE && killerMoves[ply][0] == move) {
            return;
        }

        killerMoves[ply][1] = killerMoves[ply][0];
        killerMoves[ply][0] = move;
    }

    private void updateHistory(int move, Board board, int depth) {
        int movingPiece = board.getPiece(Move.from(move));
        int pieceType = Piece.type(movingPiece);
        if (pieceType <= 0 || pieceType >= historyHeuristic.length) {
            return;
        }

        int targetSquare = Move.to(move);
        if (targetSquare < 0 || targetSquare >= historyHeuristic[pieceType].length) {
            return;
        }

        historyHeuristic[pieceType][targetSquare] += depth * depth;
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
            int packed = pvTable[0][i];
            line.add(new Move(Move.from(packed), Move.to(packed), Move.reactionOf(packed)));
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