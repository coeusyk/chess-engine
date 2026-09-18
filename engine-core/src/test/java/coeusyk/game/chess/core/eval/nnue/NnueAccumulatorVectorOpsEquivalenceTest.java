package coeusyk.game.chess.core.eval.nnue;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #218 Stage 1: direct scalar-vs-vector equivalence — calls {@link
 * NnueAccumulatorVectorOps} and {@link NnueEvaluator}'s scalar fallback ({@code
 * addFeatureScalar}/{@code subtractFeatureScalar}) side by side, independent of which path
 * {@link VectorCapabilities#AVAILABLE} happens to select in the running environment. This is
 * a stronger check than relying on the regression suite alone (which only exercises whichever
 * path {@code AVAILABLE} resolves to here) — it proves both paths agree, not just that
 * whichever one runs produces expected results.
 *
 * <p>Requires {@link VectorCapabilities#AVAILABLE} to run the vector side at all; skipped
 * (not failed) if the module didn't resolve, since there would be nothing to compare against.
 */
class NnueAccumulatorVectorOpsEquivalenceTest {

    private static final int[] WIDTHS = {8, 16, 31, 32, 33, 64, 128, 200, 256, 512};
    private static final int TRIALS_PER_WIDTH = 20;
    private static final long SEED = 20260719L;

    @Test
    void vectorAddFeatureMatchesScalarAcrossWidths() {
        org.junit.jupiter.api.Assumptions.assumeTrue(VectorCapabilities.AVAILABLE,
                "jdk.incubator.vector not available in this environment -- nothing to compare");
        Random random = new Random(SEED);
        for (int width : WIDTHS) {
            for (int trial = 0; trial < TRIALS_PER_WIDTH; trial++) {
                short[] weights = randomShorts(random, width * 4, 4000);
                for (int feature = 0; feature < 4; feature++) {
                    short[] scalarAcc = randomShorts(random, width, 15000);
                    short[] vectorAcc = scalarAcc.clone();

                    NnueEvaluator.addFeatureScalar(scalarAcc, feature, weights);
                    NnueAccumulatorVectorOps.addFeature(vectorAcc, feature, weights);

                    assertArrayEquals(scalarAcc, vectorAcc,
                            "width=" + width + " feature=" + feature + " trial=" + trial);
                }
            }
        }
    }

    @Test
    void vectorSubtractFeatureMatchesScalarAcrossWidths() {
        org.junit.jupiter.api.Assumptions.assumeTrue(VectorCapabilities.AVAILABLE,
                "jdk.incubator.vector not available in this environment -- nothing to compare");
        Random random = new Random(SEED + 1);
        for (int width : WIDTHS) {
            for (int trial = 0; trial < TRIALS_PER_WIDTH; trial++) {
                short[] weights = randomShorts(random, width * 4, 4000);
                for (int feature = 0; feature < 4; feature++) {
                    short[] scalarAcc = randomShorts(random, width, 15000);
                    short[] vectorAcc = scalarAcc.clone();

                    NnueEvaluator.subtractFeatureScalar(scalarAcc, feature, weights);
                    NnueAccumulatorVectorOps.subtractFeature(vectorAcc, feature, weights);

                    assertArrayEquals(scalarAcc, vectorAcc,
                            "width=" + width + " feature=" + feature + " trial=" + trial);
                }
            }
        }
    }

    @Test
    void overflowBoundaryValuesAgreeBetweenScalarAndVector() {
        org.junit.jupiter.api.Assumptions.assumeTrue(VectorCapabilities.AVAILABLE,
                "jdk.incubator.vector not available in this environment -- nothing to compare");
        int width = 256;
        short[] weights = new short[width * 4];
        java.util.Arrays.fill(weights, Short.MAX_VALUE);
        short[] scalarAcc = new short[width];
        java.util.Arrays.fill(scalarAcc, Short.MAX_VALUE);
        short[] vectorAcc = scalarAcc.clone();

        NnueEvaluator.addFeatureScalar(scalarAcc, 0, weights);
        NnueAccumulatorVectorOps.addFeature(vectorAcc, 0, weights);

        assertArrayEquals(scalarAcc, vectorAcc, "MAX_VALUE + MAX_VALUE must wrap identically");
        assertTrue(scalarAcc[0] < 0, "sanity: this case is expected to wrap negative");
    }

    private static short[] randomShorts(Random random, int count, int bound) {
        short[] values = new short[count];
        for (int i = 0; i < count; i++) {
            values[i] = (short) (random.nextInt(2 * bound + 1) - bound);
        }
        return values;
    }
}
