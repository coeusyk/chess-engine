package coeusyk.game.chess.core.selfplay;

/**
 * Deterministic, collision-resistant per-ply seed derivation for {@link MoveSelector}
 * implementations that need randomness (#222 section 5). {@code gameSeed} (already
 * {@code runSeed + gameId}, computed once by {@link SelfPlayCli}) and {@code ply} are combined
 * with two SplitMix64 avalanche steps rather than plain addition: {@code gameSeed + ply} collides
 * whenever a game ordinal and a ply trade places under a fixed run seed (game 5 ply 3 and game 3
 * ply 5 produce the identical value) -- exactly the scheduling/identity-insensitive derivation
 * DR-E9 section 3 and #222 section 5 forbid. SplitMix64 (Steele/Lea/Flood 2014) is a fixed,
 * purely arithmetic mixing function with no locale/hashCode/iteration-order dependence, so this
 * is stable across JVMs and platforms, per #222's "avoid Java's platform/runtime-sensitive
 * behavior" instruction.
 */
public final class SeedDerivation {

    private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;

    private SeedDerivation() {
    }

    /** Same {@code (gameSeed, ply)} always yields the same result; different game ordinals
     * (via their distinct {@code gameSeed}) or different plies yield an independent draw. */
    public static long derive(long gameSeed, int ply) {
        long h = mix(gameSeed);
        long plySalt = mix(((long) ply + 1) * GOLDEN_GAMMA);
        return mix(h ^ plySalt);
    }

    /** One extra avalanche pass. {@code java.util.Random}'s own seed-scrambling step
     * ({@code seed ^ 0x5DEECE66DL}) leaves nearby/sequential input seeds correlated on their
     * very first {@code nextDouble()} draw -- a well-known LCG weakness, not specific to this
     * codebase. {@link SeededDiversitySelector} applies this to whatever seed it receives before
     * constructing a {@code Random}, so its output is decorrelated even if a caller ever passes
     * it a weakly-varying seed directly (defense in depth on top of {@link #derive}, which
     * already avalanches gameSeed/ply). */
    public static long remix(long seed) {
        return mix(seed);
    }

    private static long mix(long seed) {
        long z = seed + GOLDEN_GAMMA;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
