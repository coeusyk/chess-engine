package coeusyk.game.chess.core.eval.nnue;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #218 PR 1: proves the {@code --add-modules jdk.incubator.vector} wiring in
 * {@code engine-core/pom.xml}'s compiler/surefire configuration actually resolves the module
 * for this build+test JVM, rather than merely asserting internal consistency (which would pass
 * whether or not the wiring worked). Every JDK used by this project (build: OpenJDK 21.0.11;
 * native-Windows measurement runtime: Zulu 25.0.3) ships {@code jdk.incubator.vector} as of
 * this writing, so {@link VectorCapabilities#AVAILABLE} being {@code true} here is a direct,
 * falsifiable check of the pom wiring -- remove {@code --add-modules} from the surefire
 * {@code argLine} and this test fails.
 */
class VectorCapabilitiesTest {

    @Test
    void moduleResolvesGivenTheConfiguredAddModulesFlag() {
        assertTrue(VectorCapabilities.AVAILABLE,
                "jdk.incubator.vector should resolve given engine-core's --add-modules wiring");
        assertTrue(VectorCapabilities.PREFERRED_SHORT_LANES > 0,
                "a resolved species must report a positive lane count");
    }

    @Test
    void laneCountIsAPowerOfTwoConsistentWithKnownVectorHardware() {
        int lanes = VectorCapabilities.PREFERRED_SHORT_LANES;
        assertEquals(0, lanes & (lanes - 1),
                "short-lane species widths are powers of two (64/128/256/512-bit / 16-bit lanes)");
    }
}
