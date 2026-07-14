package coeusyk.game.chess.core.eval.nnue;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Issue #197's "Java-side round-trip test fixture": loads a real {@code .nnue} file
 * produced by the Python {@code Exporter} (committed at
 * {@code engine-core/src/test/resources/nnue/d6-export-fixture.nnue}, provenance in
 * that directory's README) via the real, unmodified {@link NnueNetwork#load}. This is
 * the trainer-to-engine integration boundary Invariant 8 sanctions — a test-scope-only
 * dependency on a trainer-produced artifact, never a production one.
 */
class NnueExportFixtureRoundTripTest {

    private static final String EXPECTED_UUID = "1ac3222e-0765-4c93-b3b3-5b2718e2d92b";

    @Test
    void pythonExportedFixtureLoadsWithEveryFieldMatchingItsDocumentedProvenance() throws IOException {
        Path fixture = RepoPaths.repoRoot()
                .resolve("engine-core/src/test/resources/nnue/d6-export-fixture.nnue");

        NnueNetwork loaded = NnueNetwork.load(fixture);

        assertEquals(2, loaded.hiddenWidth());
        assertEquals(127, loaded.qa());
        assertEquals(64, loaded.qb());
        assertEquals(400, loaded.outputScale());
        assertEquals(EXPECTED_UUID, loaded.networkUuid());
        assertEquals("cccccccc", loaded.trainerCommit());
        assertEquals(1784014895L, loaded.createdAtEpochSeconds());
        assertEquals(777, loaded.outputBias());

        short[] ftWeights = loaded.ftWeights();
        assertEquals(-100, ftWeights[0]);
        assertEquals(75, ftWeights[1]);
        assertEquals(0, ftWeights[100 * 2]);
        assertEquals(-25, ftWeights[100 * 2 + 1]);
        assertEquals(67, ftWeights[767 * 2]);
        assertEquals(58, ftWeights[767 * 2 + 1]);

        short[] ftBiases = loaded.ftBiases();
        assertEquals(11, ftBiases[0]);
        assertEquals(-22, ftBiases[1]);

        short[] outputWeights = loaded.outputWeights();
        assertEquals(33, outputWeights[0]);
        assertEquals(-44, outputWeights[1]);
        assertEquals(55, outputWeights[2]);
        assertEquals(-66, outputWeights[3]);
    }
}
