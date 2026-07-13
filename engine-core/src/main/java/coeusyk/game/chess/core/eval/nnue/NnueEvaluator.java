package coeusyk.game.chess.core.eval.nnue;

import coeusyk.game.chess.core.eval.EvaluatorStrategy;
import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Piece;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * NNUE evaluator: dual-perspective accumulator maintained incrementally across the
 * search tree via {@link FeatureExtractor}, scored through a shared, immutable
 * {@link NnueNetwork}.
 *
 * <p><b>Non-negotiable per-thread ownership (Phase B plan):</b> one instance per
 * {@code Searcher}, never shared across Lazy SMP helper threads. The accumulator
 * stack below is mutable per-search state; only {@link #network} is safe to share.
 *
 * <p>The accumulator stack is a preallocated pool indexed by a stack pointer — the
 * same idiom {@code Board} uses for its own {@code unmakePool}/{@code unmakeSP} — so
 * {@link #onMake}/{@link #onUnmake} allocate nothing. {@code this} implements
 * {@link FeatureExtractor.ChangeVisitor} directly (rather than an anonymous/lambda
 * visitor per call) for the same reason: zero allocation in the hot path.
 */
public final class NnueEvaluator implements EvaluatorStrategy, FeatureExtractor.ChangeVisitor {

    private static final Logger LOG = LoggerFactory.getLogger(NnueEvaluator.class);

    // References Board's own constant (not a duplicated literal) — the accumulator
    // stack is pushed/popped in lockstep with Board's unmake stack and can't safely
    // be sized independently of it.
    private static final int ACCUMULATOR_POOL_SIZE = Board.UNMAKE_POOL_SIZE;

    // PR C-3 (issue #187): "~1-in-256" sampled rebuild assertion. Deterministic, not
    // RNG-driven — a fixed period gives a hard bound on how stale a desync can go
    // undetected, and avoids the test flakiness the plan's own risk review flagged
    // for a probabilistic firing rate.
    //
    // CLAUDE.md §3's no-hot-path-allocation rule has one sanctioned, scoped exception
    // here: the sampled call allocates via verifyAgainstRebuild (PR C-1's existing
    // public API — not reimplemented here to avoid duplicating it or widening its
    // frozen signature). Issue #187 explicitly asks for exactly this comparison.
    // Zero-overhead-when-disabled is what §3 actually protects for production use;
    // that's proven both structurally (every debug code path is behind `if
    // (debugMode)`, defaulting false) and empirically (NnueEvaluatorDebugModeTest's
    // debugModeDisabledNeverLogsEvenWithCorruption; bench with NnueDebug unset —
    // its default — reproduced the pre-PR node count exactly, see PR C-3's commit).
    private static final int DEBUG_ASSERTION_SAMPLE_PERIOD = 256;

    private final NnueNetwork network;
    private final int width;
    private final short[][] whiteAcc;
    private final short[][] blackAcc;
    private final boolean debugMode;
    private int sp; // next-to-read index (top of stack); mirrors Board's unmakeSP convention pre-decrement
    private int onMakeCount; // only read/written when debugMode — sampling cadence for the rebuild assertion

    public NnueEvaluator(NnueNetwork network) {
        this(network, false);
    }

    /**
     * @param debugMode when {@code true}, enables the sampled incremental-vs-rebuild
     * assertion and stack-depth bounds check inside {@link #onMake} (PR C-3, issue
     * #187). Both are gated entirely behind this flag — zero overhead when {@code
     * false}, since the {@code if (debugMode)} checks in {@link #onMake} are the only
     * added cost and never execute their bodies.
     */
    public NnueEvaluator(NnueNetwork network, boolean debugMode) {
        this.network = network;
        this.width = network.hiddenWidth();
        this.whiteAcc = new short[ACCUMULATOR_POOL_SIZE][width];
        this.blackAcc = new short[ACCUMULATOR_POOL_SIZE][width];
        this.sp = 0;
        this.debugMode = debugMode;
    }

    @Override
    public void reset(Board board) {
        sp = 0;
        rebuild(board, Piece.White, whiteAcc[0]);
        rebuild(board, Piece.Black, blackAcc[0]);
    }

    private void rebuild(Board board, int perspectiveColor, short[] target) {
        System.arraycopy(network.ftBiases(), 0, target, 0, width);
        short[] weights = network.ftWeights();
        FeatureExtractor.forEachPiece(board, (pieceColor, pieceType, square) -> {
            int feature = FeatureExtractor.featureIndex(perspectiveColor, pieceColor, pieceType, square);
            addFeature(target, feature, weights);
        });
    }

    @Override
    public void onMake(Board board, int move, int capturedPiece) {
        if (debugMode) {
            assertStackBounds();
        }
        System.arraycopy(whiteAcc[sp], 0, whiteAcc[sp + 1], 0, width);
        System.arraycopy(blackAcc[sp], 0, blackAcc[sp + 1], 0, width);
        sp++;
        FeatureExtractor.forEachChange(board, move, capturedPiece, this);
        if (debugMode && ++onMakeCount % DEBUG_ASSERTION_SAMPLE_PERIOD == 0) {
            assertIncrementalMatchesRebuild(board);
        }
    }

    /**
     * Debug-only stack-depth bounds check — the Phase C plan's "stack-depth==ply
     * invariant", reinterpreted as a self-contained check rather than the literal
     * {@code sp == Board.unmakeSP}: {@code Board.unmakeSP} is private (frozen API,
     * ADR-004), and isn't even a true invariant against {@link #sp} since {@code
     * Board.makeNullMove}/{@code unmakeNullMove} never touch it while {@link
     * #onMakeNull}/{@link #onUnmakeNull} do move {@link #sp}. Warns on the same
     * failure mode (unbalanced onMake/onUnmake calls) without widening Board's API
     * — advisory only: an actual overflow still throws {@link
     * ArrayIndexOutOfBoundsException} from the arraycopy immediately below (this
     * check runs first purely so that crash is preceded by a clear diagnostic
     * instead of a bare stack trace, not to prevent it). Runs every debug-mode
     * {@link #onMake} call (O(1), no allocation) rather than only on the sampled
     * calls below — only {@link #verifyAgainstRebuild} allocates.
     */
    private void assertStackBounds() {
        if (sp < 0 || sp + 1 >= ACCUMULATOR_POOL_SIZE) {
            LOG.warn("NNUE accumulator stack pointer out of bounds before onMake: sp={} (pool size {})",
                    sp, ACCUMULATOR_POOL_SIZE);
        }
    }

    /**
     * Debug-only, periodic (every {@link #DEBUG_ASSERTION_SAMPLE_PERIOD}th {@link
     * #onMake} call, not every call — {@link #verifyAgainstRebuild} allocates scratch
     * arrays and per-perspective visitor lambdas): compares the live incrementally-
     * maintained accumulator against a from-scratch rebuild (PR C-1), per issue
     * #187. Never throws — a debug assertion that crashes the live search it's
     * diagnosing is hostile to the diagnosis workflow it exists to support;
     * divergence is logged instead. A real, sustained desync logs once per sample
     * for the rest of the search rather than once — acceptable for a debug-only
     * tool that exists to surface exactly that.
     */
    private void assertIncrementalMatchesRebuild(Board board) {
        RebuildDiff diff = verifyAgainstRebuild(board);
        if (!diff.matches()) {
            LOG.warn("NNUE accumulator desync detected: perspective={} index={} delta={}",
                    diff.perspectiveColor(), diff.firstDivergingIndex(), diff.delta());
        }
    }

    @Override
    public void onUnmake() {
        sp--;
    }

    @Override
    public void onMakeNull() {
        // No feature-value delta (PRD §4: accumulators are unchanged on a null move),
        // but still push a frame so ply indexing stays aligned with search depth —
        // the same discipline onMake follows.
        System.arraycopy(whiteAcc[sp], 0, whiteAcc[sp + 1], 0, width);
        System.arraycopy(blackAcc[sp], 0, blackAcc[sp + 1], 0, width);
        sp++;
    }

    @Override
    public void onUnmakeNull() {
        sp--;
    }

    /** {@link FeatureExtractor.ChangeVisitor} — called by {@link #onMake} via {@code this}, not a per-call allocation. */
    @Override
    public void remove(int pieceColor, int pieceType, int square) {
        short[] weights = network.ftWeights();
        subtractFeature(whiteAcc[sp], FeatureExtractor.featureIndex(Piece.White, pieceColor, pieceType, square), weights);
        subtractFeature(blackAcc[sp], FeatureExtractor.featureIndex(Piece.Black, pieceColor, pieceType, square), weights);
    }

    @Override
    public void add(int pieceColor, int pieceType, int square) {
        short[] weights = network.ftWeights();
        addFeature(whiteAcc[sp], FeatureExtractor.featureIndex(Piece.White, pieceColor, pieceType, square), weights);
        addFeature(blackAcc[sp], FeatureExtractor.featureIndex(Piece.Black, pieceColor, pieceType, square), weights);
    }

    @Override
    public int evaluate(Board board) {
        boolean whiteToMove = Piece.isWhite(board.getActiveColor());
        short[] us = whiteToMove ? whiteAcc[sp] : blackAcc[sp];
        short[] them = whiteToMove ? blackAcc[sp] : whiteAcc[sp];
        short[] outWeights = network.outputWeights();
        int qa = network.qa();

        long sum = 0;
        for (int i = 0; i < width; i++) {
            sum += clamp(us[i], qa) * (long) outWeights[i];
            sum += clamp(them[i], qa) * (long) outWeights[width + i];
        }
        sum += network.outputBias();
        return (int) (sum * network.outputScale() / ((long) qa * network.qb()));
    }

    private static int clamp(short value, int qa) {
        if (value < 0) {
            return 0;
        }
        return Math.min(value, qa);
    }

    private static void addFeature(short[] acc, int feature, short[] weights) {
        int base = feature * acc.length;
        for (int i = 0; i < acc.length; i++) {
            acc[i] = (short) (acc[i] + weights[base + i]);
        }
    }

    private static void subtractFeature(short[] acc, int feature, short[] weights) {
        int base = feature * acc.length;
        for (int i = 0; i < acc.length; i++) {
            acc[i] = (short) (acc[i] - weights[base + i]);
        }
    }

    /** Test/debug hook: exposes the current top-of-stack accumulator for the fuzz test's rebuild comparison. */
    short[] currentAccumulator(int perspectiveColor) {
        return perspectiveColor == Piece.White ? whiteAcc[sp] : blackAcc[sp];
    }

    /**
     * Debug tool: dumps both perspectives' current-ply accumulator values in a stable,
     * diffable text format. {@code sp} doubles as the ply index — there is no separate
     * ply counter in this class. Read-only — never mutates {@link #whiteAcc}/{@link #blackAcc}.
     */
    public String dumpAccumulators() {
        return "sp=" + sp
                + "\nwhite=" + Arrays.toString(whiteAcc[sp])
                + "\nblack=" + Arrays.toString(blackAcc[sp]);
    }

    /**
     * Debug tool: lists every active feature index (both perspectives) for {@code
     * board}'s current pieces, via {@link FeatureExtractor#activeFeatureIndices} —
     * the PRD's own "active feature dump" tool, not reimplemented here. Same
     * white=/black= shape as {@link #dumpAccumulators}. Root-position-only, like
     * {@link #dumpAccumulators} — takes {@code board} fresh rather than reading
     * live search state.
     */
    public String dumpActiveFeatures(Board board) {
        return "active features:"
                + "\nwhite=" + Arrays.toString(FeatureExtractor.activeFeatureIndices(board, Piece.White))
                + "\nblack=" + Arrays.toString(FeatureExtractor.activeFeatureIndices(board, Piece.Black));
    }

    /**
     * Debug tool: reports pre-activation ranges, clip counts, output-layer parameters,
     * the int16 score, and (via {@link NnueOracle}) the float32 oracle score and its
     * delta from the int16 score. Same name/shape as the classical
     * {@code Evaluator.explainEval(Board)} precedent. Read-only.
     */
    public String explainEval(Board board) {
        boolean whiteToMove = Piece.isWhite(board.getActiveColor());
        short[] us = whiteToMove ? whiteAcc[sp] : blackAcc[sp];
        short[] them = whiteToMove ? blackAcc[sp] : whiteAcc[sp];
        int qa = network.qa();

        RangeAndClipCount usRange = rangeAndClipCount(us, qa);
        RangeAndClipCount themRange = rangeAndClipCount(them, qa);

        short[] outWeights = network.outputWeights();
        long usContribution = 0;
        long themContribution = 0;
        for (int i = 0; i < width; i++) {
            usContribution += clamp(us[i], qa) * (long) outWeights[i];
            themContribution += clamp(them[i], qa) * (long) outWeights[width + i];
        }

        int int16Score = evaluate(board);
        NnueOracle.OracleResult oracle = NnueOracle.compareInt16VsFloat32(network, board);

        StringBuilder sb = new StringBuilder();
        sb.append("--- nnue eval breakdown ---\n");
        sb.append(String.format("  pre-activation us    min=%d max=%d clipped=%d/%d\n",
                usRange.min(), usRange.max(), usRange.clipped(), width));
        sb.append(String.format("  pre-activation them  min=%d max=%d clipped=%d/%d\n",
                themRange.min(), themRange.max(), themRange.clipped(), width));
        sb.append(String.format("  output contribution  us=%d them=%d bias=%d qa=%d qb=%d outputScale=%d\n",
                usContribution, themContribution, network.outputBias(), qa, network.qb(), network.outputScale()));
        sb.append(String.format("  int16 score           %+d cp\n", int16Score));
        sb.append(String.format("  float32 oracle score  %+.4f cp  delta=%.4f",
                oracle.float32Score(), oracle.absoluteError()));
        return sb.toString();
    }

    private record RangeAndClipCount(int min, int max, int clipped) {
    }

    private static RangeAndClipCount rangeAndClipCount(short[] acc, int qa) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        int clipped = 0;
        for (short value : acc) {
            min = Math.min(min, value);
            max = Math.max(max, value);
            if (value < 0 || value > qa) {
                clipped++;
            }
        }
        return new RangeAndClipCount(min, max, clipped);
    }

    /**
     * Debug tool: rebuilds both perspectives from scratch into scratch arrays and compares
     * against the live top-of-stack accumulator, reporting the first diverging index (or
     * {@link RebuildDiff#NONE}). Never mutates {@link #whiteAcc}/{@link #blackAcc} — the
     * rebuild target is local, discarded after comparison.
     */
    public RebuildDiff verifyAgainstRebuild(Board board) {
        short[] whiteRebuilt = new short[width];
        short[] blackRebuilt = new short[width];
        rebuild(board, Piece.White, whiteRebuilt);
        rebuild(board, Piece.Black, blackRebuilt);

        RebuildDiff whiteDiff = firstDivergence(Piece.White, whiteAcc[sp], whiteRebuilt);
        if (!whiteDiff.matches()) {
            return whiteDiff;
        }
        return firstDivergence(Piece.Black, blackAcc[sp], blackRebuilt);
    }

    private static RebuildDiff firstDivergence(int perspectiveColor, short[] live, short[] rebuilt) {
        for (int i = 0; i < live.length; i++) {
            if (live[i] != rebuilt[i]) {
                return new RebuildDiff(perspectiveColor, i, live[i] - rebuilt[i]);
            }
        }
        return RebuildDiff.NONE;
    }

    /** Test/debug hook: perturbs one live accumulator index to exercise {@link #verifyAgainstRebuild}. */
    void corruptForTest(int perspectiveColor, int firstDivergingIndex, short delta) {
        short[] acc = perspectiveColor == Piece.White ? whiteAcc[sp] : blackAcc[sp];
        acc[firstDivergingIndex] += delta;
    }

    /**
     * Result of {@link #verifyAgainstRebuild}: the first index where the live accumulator
     * diverges from a from-scratch rebuild, or {@link #NONE} if they match exactly.
     * {@code delta} is {@code live - rebuilt} at {@code firstDivergingIndex}.
     */
    public record RebuildDiff(int perspectiveColor, int firstDivergingIndex, int delta) {
        public static final RebuildDiff NONE = new RebuildDiff(-1, -1, 0);

        public boolean matches() {
            return firstDivergingIndex == -1;
        }
    }
}
