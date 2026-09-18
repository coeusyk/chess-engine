package coeusyk.game.chess.core.eval.nnue;

/**
 * Issue #218 (Vector API for NNUE accumulator/evaluator hot loops), PR 1: scaffolding only.
 *
 * <p>Reports whether {@code jdk.incubator.vector} is usable on the running JVM, and the
 * preferred {@code short}-lane width if so. Not called from {@link NnueEvaluator} or
 * {@code Searcher} in this PR — no algorithm change, no behavior change. PR 2 is where this
 * gets wired into {@code addFeature}/{@code subtractFeature}.
 *
 * <p><b>Why this degrades gracefully instead of hard-requiring the module:</b> {@code
 * jdk.incubator.vector} is an incubator module, not part of the default root-module set for a
 * classpath (unnamed-module) application. A JVM launched without {@code --add-modules
 * jdk.incubator.vector} cannot resolve {@code ShortVector}/{@code VectorSpecies} at all. This
 * class's public surface is deliberately {@code boolean}/{@code int} only — no incubator type
 * appears in a field, return type, or parameter — so the JVM never needs to resolve {@code
 * ShortVector} to load or verify *this* class. Resolution is deferred (JVMS 5.4.3, lazy
 * linking) to the first actual reference inside {@link #detectAvailable()}'s method body,
 * which is wrapped in {@code try/catch (Throwable)}: on a JVM without the module, that
 * reference throws {@link NoClassDefFoundError} (caught here, not propagated), and {@link
 * #AVAILABLE} is {@code false}. Do not add a {@code VectorSpecies}-typed field or accessor to
 * this class — that would force eager resolution at class-load time and break this fallback.
 *
 * <p><b>Runtime flag required for {@link #AVAILABLE} to be {@code true}:</b> {@code
 * --add-modules jdk.incubator.vector} must be passed to the {@code java} launch command (there
 * is no manifest attribute for this — it is a JVM command-line/{@code JDK_JAVA_OPTIONS}
 * concern, not a packaging one). Once PR 2 wires this class into the accumulator hot path,
 * omitting this flag from a benchmark invocation will silently measure the scalar fallback,
 * not the vectorized path — see the research doc's PR 3 benchmark plan.
 */
final class VectorCapabilities {

    /** {@code true} if {@code jdk.incubator.vector} resolved on this JVM. */
    static final boolean AVAILABLE;

    /** Preferred {@code short}-lane count if {@link #AVAILABLE}, else {@code 0}. */
    static final int PREFERRED_SHORT_LANES;

    static {
        int lanes = 0;
        boolean available = false;
        try {
            lanes = detectPreferredShortLanes();
            available = true;
        } catch (Throwable ignored) {
            // jdk.incubator.vector not resolvable on this JVM (module not added at launch,
            // or a JDK build without it) -- fall back to "unavailable", not a startup failure.
        }
        AVAILABLE = available;
        PREFERRED_SHORT_LANES = lanes;
    }

    private VectorCapabilities() {
    }

    private static int detectPreferredShortLanes() {
        return jdk.incubator.vector.ShortVector.SPECIES_PREFERRED.length();
    }
}
