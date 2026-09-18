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
        Random random = new Random(SEED);
        short[] ftWeights = randomShorts(random, FeatureExtractor.FEATURES_PER_PERSPECTIVE * hiddenWidth, 20);
        short[] ftBiases = randomShorts(random, hiddenWidth, 10);
        short[] outputWeights = randomShorts(random, 2 * hiddenWidth, 20);
        int outputBias = 0;
        int qa = 127;
        int qb = 64;
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
