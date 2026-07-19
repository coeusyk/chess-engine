package coeusyk.game.chess.core.eval.nnue;

import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorSpecies;

/**
 * Issue #218 Stage 1: {@code jdk.incubator.vector} implementation of the accumulator's
 * elementwise update loops. Deliberately isolated in its own class, never referenced from
 * {@link NnueEvaluator} unless {@link VectorCapabilities#AVAILABLE} is {@code true} — this
 * class's bytecode references {@code ShortVector}/{@code VectorSpecies} directly in method
 * signatures, so (unlike {@link VectorCapabilities}) it is *not* safe to load on a JVM without
 * the module. It must only ever be reached through {@code NnueEvaluator}'s {@code
 * VectorCapabilities.AVAILABLE}-gated dispatch, which by construction only takes this branch
 * when the module already resolved successfully.
 *
 * <p>Semantics: {@link #addFeature}/{@link #subtractFeature} are elementwise {@code short}
 * add/subtract over a contiguous slice — no cross-lane interaction, so a vectorized result is
 * bit-identical to the scalar loop by construction (integral {@code ADD}/{@code SUB} wrap
 * identically to Java's {@code (short)(a + b)} narrowing, per the Vector API spec). Handles any
 * {@code width} via a scalar remainder loop after the vectorized bulk, even though the current
 * production width (256) divides evenly by every common lane width.
 */
final class NnueAccumulatorVectorOps {

    private static final VectorSpecies<Short> SPECIES = ShortVector.SPECIES_PREFERRED;

    private NnueAccumulatorVectorOps() {
    }

    static void addFeature(short[] acc, int feature, short[] weights) {
        int width = acc.length;
        int base = feature * width;
        int bound = SPECIES.loopBound(width);
        int i = 0;
        for (; i < bound; i += SPECIES.length()) {
            ShortVector accVec = ShortVector.fromArray(SPECIES, acc, i);
            ShortVector weightVec = ShortVector.fromArray(SPECIES, weights, base + i);
            accVec.add(weightVec).intoArray(acc, i);
        }
        for (; i < width; i++) {
            acc[i] = (short) (acc[i] + weights[base + i]);
        }
    }

    static void subtractFeature(short[] acc, int feature, short[] weights) {
        int width = acc.length;
        int base = feature * width;
        int bound = SPECIES.loopBound(width);
        int i = 0;
        for (; i < bound; i += SPECIES.length()) {
            ShortVector accVec = ShortVector.fromArray(SPECIES, acc, i);
            ShortVector weightVec = ShortVector.fromArray(SPECIES, weights, base + i);
            accVec.sub(weightVec).intoArray(acc, i);
        }
        for (; i < width; i++) {
            acc[i] = (short) (acc[i] - weights[base + i]);
        }
    }
}
