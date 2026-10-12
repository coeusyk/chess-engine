package coeusyk.game.chess.core.selfplay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class SeedDerivationTest {

    @Test
    void sameTupleAlwaysYieldsTheSameSeed() {
        assertEquals(SeedDerivation.derive(42L, 7), SeedDerivation.derive(42L, 7));
    }

    @Test
    void differentPlyChangesTheSeed() {
        assertNotEquals(SeedDerivation.derive(42L, 7), SeedDerivation.derive(42L, 8));
    }

    @Test
    void differentGameSeedChangesTheSeed() {
        assertNotEquals(SeedDerivation.derive(42L, 7), SeedDerivation.derive(43L, 7));
    }

    @Test
    void swappingGameSeedAndPlyDoesNotCollide() {
        // The defect this class exists to fix: gameSeed + ply collides whenever the two values
        // trade places (runSeed + 5 + 3 == runSeed + 3 + 5). SplitMix64 mixing must not have the
        // same property.
        long a = SeedDerivation.derive(5L, 3);
        long b = SeedDerivation.derive(3L, 5);
        assertNotEquals(a, b);
    }

    @Test
    void adjacentPliesProduceWellSeparatedSeeds() {
        long s0 = SeedDerivation.derive(100L, 0);
        long s1 = SeedDerivation.derive(100L, 1);
        // Not a rigorous avalanche test -- just confirms the two aren't trivially close/equal,
        // which a naive "+1" derivation would produce.
        assertNotEquals(s0, s1);
        assertNotEquals(s0 + 1, s1);
    }
}
