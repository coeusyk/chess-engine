package coeusyk.game.chess.core.eval.nnue;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Piece;

import java.util.ArrayList;
import java.util.List;

/**
 * Test/debug/CI-only float32 oracle: dequantizes an {@link NnueNetwork}'s stored
 * {@code short[]} weights via {@code qa}/{@code qb} and re-runs the exact forward pass
 * {@link NnueEvaluator#evaluate} performs, in {@code double} arithmetic instead of
 * {@code short}/{@code int}/{@code long}. Per ADR-002: this catches implementation bugs
 * (integer overflow, mis-ordered clamp, wrong shift) in the int16 path, not
 * training-time quantization loss — it starts from the same quantized weights as
 * production.
 *
 * <p>Deliberately a standalone class, not a method on {@link NnueEvaluator}: keeps the
 * int16 hot-path class free of any float-arithmetic code, so it never has to be
 * mentally filtered out when auditing {@link NnueEvaluator} for hot-path purity. Never
 * reachable from {@link NnueEvaluator#evaluate}, {@code onMake}, or any search call
 * path — constructing an {@link NnueEvaluator} here is solely to reuse its
 * {@code reset}/int16 {@code evaluate} for the comparison, not a production dependency.
 */
public final class NnueOracle {

    private NnueOracle() {
    }

    /** {@code delta} is {@code |float32Score - int16Score|}. */
    public record OracleResult(int int16Score, double float32Score, double absoluteError) {
    }

    public record BatchOracleResult(List<OracleResult> results, double maxAbsoluteError, double meanAbsoluteError) {
    }

    public static OracleResult compareInt16VsFloat32(NnueNetwork network, Board board) {
        NnueEvaluator int16Evaluator = new NnueEvaluator(network);
        int16Evaluator.reset(board);
        return compare(int16Evaluator, network, board);
    }

    /** Reuses one {@link NnueEvaluator} across the whole batch — test/CI-only, no cross-thread use. */
    public static BatchOracleResult compareBatch(NnueNetwork network, List<String> fens) {
        NnueEvaluator int16Evaluator = new NnueEvaluator(network);
        List<OracleResult> results = new ArrayList<>(fens.size());
        double maxAbsoluteError = 0;
        double sumAbsoluteError = 0;
        for (String fen : fens) {
            Board board = new Board(fen);
            int16Evaluator.reset(board);
            OracleResult result = compare(int16Evaluator, network, board);
            results.add(result);
            maxAbsoluteError = Math.max(maxAbsoluteError, result.absoluteError());
            sumAbsoluteError += result.absoluteError();
        }
        double meanAbsoluteError = fens.isEmpty() ? 0 : sumAbsoluteError / fens.size();
        return new BatchOracleResult(results, maxAbsoluteError, meanAbsoluteError);
    }

    private static OracleResult compare(NnueEvaluator int16Evaluator, NnueNetwork network, Board board) {
        int int16Score = int16Evaluator.evaluate(board);
        double float32Score = float32Evaluate(network, board);
        return new OracleResult(int16Score, float32Score, Math.abs(float32Score - int16Score));
    }

    private static double float32Evaluate(NnueNetwork network, Board board) {
        int width = network.hiddenWidth();
        double[] whiteAcc = dequantizedBiases(network, width);
        double[] blackAcc = dequantizedBiases(network, width);
        short[] ftWeights = network.ftWeights();

        FeatureExtractor.forEachPiece(board, (pieceColor, pieceType, square) -> {
            int whiteFeature = FeatureExtractor.featureIndex(Piece.White, pieceColor, pieceType, square);
            int blackFeature = FeatureExtractor.featureIndex(Piece.Black, pieceColor, pieceType, square);
            addFeature(whiteAcc, whiteFeature, ftWeights);
            addFeature(blackAcc, blackFeature, ftWeights);
        });

        boolean whiteToMove = Piece.isWhite(board.getActiveColor());
        double[] us = whiteToMove ? whiteAcc : blackAcc;
        double[] them = whiteToMove ? blackAcc : whiteAcc;
        short[] outWeights = network.outputWeights();
        double qa = network.qa();

        double sum = 0;
        for (int i = 0; i < width; i++) {
            sum += clamp(us[i], qa) * outWeights[i];
            sum += clamp(them[i], qa) * outWeights[width + i];
        }
        sum += network.outputBias();
        return sum * network.outputScale() / (qa * network.qb());
    }

    private static double[] dequantizedBiases(NnueNetwork network, int width) {
        double[] acc = new double[width];
        short[] biases = network.ftBiases();
        for (int i = 0; i < width; i++) {
            acc[i] = biases[i];
        }
        return acc;
    }

    private static void addFeature(double[] acc, int feature, short[] weights) {
        int base = feature * acc.length;
        for (int i = 0; i < acc.length; i++) {
            acc[i] += weights[base + i];
        }
    }

    private static double clamp(double value, double qa) {
        if (value < 0) {
            return 0;
        }
        return Math.min(value, qa);
    }
}
