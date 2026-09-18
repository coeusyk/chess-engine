package coeusyk.game.chess.core.eval.nnue;

import coeusyk.game.chess.core.models.Board;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PR C-2: {@link NnueOracle}'s dequantization math verified against hand-computed
 * values, per issue acceptance criteria (not just "doesn't throw").
 *
 * <p>Hand computation for {@link #matchesHandComputedScoreOnUniformWeightNetwork()}:
 * a bare-kings position has exactly 2 pieces. With every ftWeight = 5 and every
 * ftBias = 0, each perspective's accumulator is uniformly {@code 2 * 5 = 10} at every
 * of the 2 hidden dims (2 pieces each contributing weight 5, no clipping since
 * qa=127). With outputWeights all 64 and qb=64 (weight/qb = 1.0 exactly): sum =
 * 10*64 + 10*64 + 10*64 + 10*64 = 2560; outputBias=0; float32Score =
 * 2560 * 100 / (127 * 64) = 256000 / 8128 = 31.49606...; int16Score truncates the
 * same integer division to 31. absoluteError = |31.49606... - 31| = 0.49606...
 */
class NnueOracleTest {

    private static final String BARE_KINGS_FEN = "4k3/8/8/8/8/8/8/4K3 w - - 0 1";

    private static NnueNetwork uniformNetwork() {
        int hiddenWidth = 2;
        short[] ftWeights = new short[FeatureExtractor.FEATURES_PER_PERSPECTIVE * hiddenWidth];
        java.util.Arrays.fill(ftWeights, (short) 5);
        short[] ftBiases = new short[]{0, 0};
        short[] outputWeights = new short[]{64, 64, 64, 64};
        return new NnueNetwork(hiddenWidth, ftWeights, ftBiases, outputWeights,
                0, 127, 64, 100, "oracle-test-uuid", "test-commit", 0L);
    }

    @Test
    void matchesHandComputedScoreOnUniformWeightNetwork() {
        NnueNetwork network = uniformNetwork();
        Board board = new Board(BARE_KINGS_FEN);

        NnueOracle.OracleResult result = NnueOracle.compareInt16VsFloat32(network, board);

        assertEquals(31, result.int16Score());
        assertEquals(256000.0 / 8128.0, result.float32Score(), 1e-9);
        assertEquals(Math.abs(256000.0 / 8128.0 - 31), result.absoluteError(), 1e-9);
    }

    @Test
    void int16ScoreMatchesIndependentNnueEvaluatorForSamePosition() {
        NnueNetwork network = TestNetworks.synthetic(8);
        Board board = new Board();
        NnueEvaluator evaluator = new NnueEvaluator(network);
        evaluator.reset(board);
        int directScore = evaluator.evaluate(board);

        NnueOracle.OracleResult result = NnueOracle.compareInt16VsFloat32(network, board);

        assertEquals(directScore, result.int16Score());
    }

    @Test
    void zeroWeightNetworkHasZeroDivergence() {
        int hiddenWidth = 4;
        NnueNetwork network = new NnueNetwork(hiddenWidth,
                new short[FeatureExtractor.FEATURES_PER_PERSPECTIVE * hiddenWidth],
                new short[hiddenWidth], new short[2 * hiddenWidth],
                0, 127, 64, 100, "zero-uuid", "test-commit", 0L);

        NnueOracle.OracleResult result = NnueOracle.compareInt16VsFloat32(network, new Board());

        assertEquals(0, result.int16Score());
        assertEquals(0.0, result.float32Score(), 1e-9);
        assertEquals(0.0, result.absoluteError(), 1e-9);
    }

    @Test
    void batchAggregatesMaxAndMeanAbsoluteErrorAcrossFens() {
        NnueNetwork network = uniformNetwork();

        NnueOracle.BatchOracleResult batch = NnueOracle.compareBatch(network, List.of(BARE_KINGS_FEN, BARE_KINGS_FEN));

        assertEquals(2, batch.results().size());
        double expectedError = Math.abs(256000.0 / 8128.0 - 31);
        assertEquals(expectedError, batch.maxAbsoluteError(), 1e-9);
        assertEquals(expectedError, batch.meanAbsoluteError(), 1e-9);
    }

    @Test
    void batchOnEmptyFenListReturnsZeroAggregatesNotDivideByZero() {
        NnueNetwork network = uniformNetwork();

        NnueOracle.BatchOracleResult batch = NnueOracle.compareBatch(network, List.of());

        assertTrue(batch.results().isEmpty());
        assertEquals(0.0, batch.maxAbsoluteError());
        assertEquals(0.0, batch.meanAbsoluteError());
    }
}
