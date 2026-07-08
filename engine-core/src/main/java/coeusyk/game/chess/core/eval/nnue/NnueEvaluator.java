package coeusyk.game.chess.core.eval.nnue;

import coeusyk.game.chess.core.eval.EvaluatorStrategy;
import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Piece;

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

    // Matches Board.UNMAKE_POOL_SIZE — the accumulator stack can't need to outlive
    // Board's own per-move undo stack, since both are pushed/popped in lockstep.
    private static final int ACCUMULATOR_POOL_SIZE = 768;

    private final NnueNetwork network;
    private final int width;
    private final short[][] whiteAcc;
    private final short[][] blackAcc;
    private int sp; // next-to-read index (top of stack); mirrors Board's unmakeSP convention pre-decrement

    public NnueEvaluator(NnueNetwork network) {
        this.network = network;
        this.width = network.hiddenWidth();
        this.whiteAcc = new short[ACCUMULATOR_POOL_SIZE][width];
        this.blackAcc = new short[ACCUMULATOR_POOL_SIZE][width];
        this.sp = 0;
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
        System.arraycopy(whiteAcc[sp], 0, whiteAcc[sp + 1], 0, width);
        System.arraycopy(blackAcc[sp], 0, blackAcc[sp + 1], 0, width);
        sp++;
        FeatureExtractor.forEachChange(board, move, capturedPiece, this);
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
}
