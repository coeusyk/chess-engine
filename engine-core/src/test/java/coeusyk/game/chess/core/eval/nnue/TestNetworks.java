package coeusyk.game.chess.core.eval.nnue;

import java.util.Random;

/**
 * Deterministic synthetic {@link NnueNetwork} instances for tests. Not a trained
 * network — weights are small seeded-random values, sufficient to exercise
 * accumulator/inference arithmetic without a real {@code .nnue} file (the trainer
 * that produces those is a separate, later Python project per the PRD).
 */
public final class TestNetworks {

    private static final int SEED = 42;

    private TestNetworks() {
    }

    public static NnueNetwork synthetic(int hiddenWidth) {
        return synthetic(hiddenWidth, 127, 64, 20);
    }

    /**
     * Same generator at another scale. {@code ftBound} bounds the feature weights and output weights, and
     * half of it bounds the biases, which gives the historical 20 / 10 / 20 at {@code ftBound = 20}.
     */
    public static NnueNetwork synthetic(int hiddenWidth, int qa, int qb, int ftBound) {
        Random random = new Random(SEED);
        short[] ftWeights = randomShorts(random, FeatureExtractor.FEATURES_PER_PERSPECTIVE * hiddenWidth, ftBound);
        short[] ftBiases = randomShorts(random, hiddenWidth, ftBound / 2);
        short[] outputWeights = randomShorts(random, 2 * hiddenWidth, ftBound);
        int outputBias = 0;
        int outputScale = 400;
        return new NnueNetwork(hiddenWidth, ftWeights, ftBiases, outputWeights, outputBias,
                qa, qb, outputScale, "test-uuid", "test-commit", 0L);
    }

    private static short[] randomShorts(Random random, int count, int bound) {
        short[] values = new short[count];
        for (int i = 0; i < count; i++) {
            values[i] = (short) (random.nextInt(2 * bound + 1) - bound);
        }
        return values;
    }
}
