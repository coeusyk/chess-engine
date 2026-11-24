package coeusyk.game.chess.core.eval.nnue;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    // ---- Output dot product with clipped ReLU (#272) ------------------------------------------------

    private static short[] weightsFittingInt(Random random, int width, int qa) {
        // Keep qa * sum(|w|) under Integer.MAX_VALUE so the int-lane path is the one that must match.
        int limit = (int) Math.min(32767, Integer.MAX_VALUE / ((long) qa * 2 * width + 1));
        return randomShorts(random, 2 * width, Math.max(1, limit));
    }

    @Test
    void vectorDotCreluMatchesScalarAcrossWidthsAndQa() {
        org.junit.jupiter.api.Assumptions.assumeTrue(VectorCapabilities.AVAILABLE,
                "jdk.incubator.vector not available");
        Random random = new Random(272L);
        for (int width : WIDTHS) {
            for (int qa : new int[] {127, 255, 1016}) {
                for (int trial = 0; trial < TRIALS_PER_WIDTH; trial++) {
                    short[] us = randomShorts(random, width, 32767);
                    short[] them = randomShorts(random, width, 32767);
                    short[] weights = weightsFittingInt(random, width, qa);
                    assertTrue(NnueEvaluator.dotFitsInt(weights, qa), "test weights must satisfy the guard");
                    assertEquals(NnueEvaluator.dotCreluScalar(us, them, weights, qa),
                            NnueAccumulatorVectorOps.dotCrelu(us, them, weights, qa),
                            "width=" + width + " qa=" + qa + " trial=" + trial);
                }
            }
        }
    }

    @Test
    void vectorDotCreluAgreesOnTheShortBoundaryValues() {
        org.junit.jupiter.api.Assumptions.assumeTrue(VectorCapabilities.AVAILABLE,
                "jdk.incubator.vector not available");
        int width = 256;
        int qa = 255;
        short[] weights = new short[2 * width];
        for (int i = 0; i < weights.length; i++) {
            weights[i] = (short) (i % 2 == 0 ? 4000 : -4000);
        }
        assertTrue(NnueEvaluator.dotFitsInt(weights, qa));
        for (short value : new short[] {Short.MIN_VALUE, -1, 0, 1, (short) qa, (short) (qa + 1), Short.MAX_VALUE}) {
            short[] us = new short[width];
            short[] them = new short[width];
            java.util.Arrays.fill(us, value);
            java.util.Arrays.fill(them, (short) -value);
            assertEquals(NnueEvaluator.dotCreluScalar(us, them, weights, qa),
                    NnueAccumulatorVectorOps.dotCrelu(us, them, weights, qa), "value=" + value);
        }
    }

    @Test
    void theIntLanePathIsOnlyAllowedWhenTheProductsCannotOverflow() {
        short[] small = new short[512];
        java.util.Arrays.fill(small, (short) 20);
        assertTrue(NnueEvaluator.dotFitsInt(small, 127));
        assertTrue(NnueEvaluator.dotFitsInt(small, 1016));

        // 32767 * 127 * 512 = 2.13e9 still fits in an int, 32767 * 127 * 1024 does not.
        short[] justFits = new short[512];
        java.util.Arrays.fill(justFits, Short.MAX_VALUE);
        assertTrue(NnueEvaluator.dotFitsInt(justFits, 127));
        short[] huge = new short[1024];
        java.util.Arrays.fill(huge, Short.MAX_VALUE);
        assertFalse(NnueEvaluator.dotFitsInt(huge, 127), "32767 * 127 * 1024 does not fit in an int");
        assertTrue(NnueEvaluator.dotFitsInt(justFits, 128), "2,147,418,112 is the last QA that fits");
        assertFalse(NnueEvaluator.dotFitsInt(justFits, 129), "32767 * 129 * 512 = 2,164,194,816 overflows an int");
        // Extreme magnitudes at width 256 with the largest QA still decide correctly: the scalar long path is used.
        short[] us = new short[256];
        short[] them = new short[256];
        java.util.Arrays.fill(us, Short.MAX_VALUE);
        java.util.Arrays.fill(them, Short.MAX_VALUE);
        long expected = 127L * Short.MAX_VALUE * 512;
        assertEquals(expected, NnueEvaluator.dotCreluScalar(us, them, justFits, 127));
    }
}

