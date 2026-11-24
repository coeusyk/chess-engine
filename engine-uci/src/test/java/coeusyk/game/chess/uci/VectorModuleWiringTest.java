package coeusyk.game.chess.uci;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #269: engine-uci's surefire JVM used to start without {@code --add-modules
 * jdk.incubator.vector}, so every NNUE test in this module ran the scalar accumulator
 * fallback and nothing said so. {@code engine-uci/pom.xml} now passes the flag and sets
 * {@code nnue.requireVector}; this test fails if the flag is dropped from the pom.
 *
 * <p>The property keeps the engine's supported scalar fallback intact: a run without it (an
 * IDE launch without the flag, say) is not failed, only the Maven configuration that claims to
 * be vector-qualified is held to it.
 */
class VectorModuleWiringTest {

    @Test
    void vectorQualifiedBuildResolvesTheIncubatorModule() {
        if (!Boolean.getBoolean("nnue.requireVector")) {
            return; // scalar fallback is a supported configuration; nothing to enforce here
        }
        assertTrue(ModuleLayer.boot().findModule("jdk.incubator.vector").isPresent(),
                "nnue.requireVector is set but jdk.incubator.vector did not resolve; "
                        + "engine-uci surefire argLine must include --add-modules jdk.incubator.vector");
    }
}
