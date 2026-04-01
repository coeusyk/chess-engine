package coeusyk.game.chess.tuner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates the EPD dataset loading infrastructure.
 *
 * <p>The {@code sampleFileLoadsWithNoErrors} test always runs and verifies that
 * the bundled 1000-line sample file in {@code src/test/resources} loads cleanly.
 *
 * <p>The {@code fullDatasetLoadsAtLeast100kPositions} test is gated behind the
 * {@code TUNER_DATASET} environment variable. Point it at the full
 * {@code quiet-labeled.epd} file to run the integration-level check and log the
 * starting MSE for the current eval constants:
 *
 * <pre>
 *   TUNER_DATASET=/path/to/quiet-labeled.epd mvn -pl engine-tuner test
 * </pre>
 */
class DatasetLoadingTest {

    private static final String SAMPLE_RESOURCE = "quiet-labeled-sample.epd";

    // -----------------------------------------------------------------------
    // Always-on: bundled 1 000-line sample
    // -----------------------------------------------------------------------

    @Test
    void sampleFileLoadsWithNoErrors() throws Exception {
        URL url = getClass().getClassLoader().getResource(SAMPLE_RESOURCE);
        assertNotNull(url, "Test resource '" + SAMPLE_RESOURCE + "' must be on the classpath");

        Path samplePath = Paths.get(url.toURI());
        List<LabelledPosition> positions = PositionLoader.load(samplePath);

        assertTrue(positions.size() >= 1000,
                "Expected at least 1 000 positions, got " + positions.size());

        // Every position must have a valid board and a legitimate outcome
        for (int i = 0; i < positions.size(); i++) {
            LabelledPosition lp = positions.get(i);
            assertNotNull(lp.pos(), "Position at index " + i + " must not be null");
            double outcome = lp.outcome();
            assertTrue(outcome == 0.0 || outcome == 0.5 || outcome == 1.0,
                    "Outcome at index " + i + " must be 0.0, 0.5, or 1.0 — got " + outcome);
        }

        System.out.printf("[DatasetLoadingTest] Sample loaded: %,d positions (0 errors)%n",
                positions.size());
    }

    // -----------------------------------------------------------------------
    // Conditional: full dataset (enabled only when TUNER_DATASET is set)
    // -----------------------------------------------------------------------

    @Test
    @EnabledIfEnvironmentVariable(named = "TUNER_DATASET", matches = ".+")
    void fullDatasetLoadsAtLeast100kPositionsAndLogsMse() throws Exception {
        String envPath = System.getenv("TUNER_DATASET");
        Path datasetPath = Paths.get(envPath);

        List<LabelledPosition> positions = PositionLoader.load(datasetPath);

        assertTrue(positions.size() >= 100_000,
                "Full dataset must contain at least 100 000 positions, got " + positions.size());

        // Derive starting MSE from the current hardcoded eval constants
        double[] params = EvalParams.extractFromCurrentEval();

        // Use a 10 000-position subset to find K quickly; tune on the full set
        List<LabelledPosition> kSubset = positions.size() > 10_000
                ? positions.subList(0, 10_000)
                : positions;
        double k = KFinder.findK(kSubset, params);

        double startMse = TunerEvaluator.computeMse(positions, params, k);

        System.out.printf("[DatasetLoadingTest] Full dataset: %,d positions%n", positions.size());
        System.out.printf("[DatasetLoadingTest] K           = %.6f%n", k);
        System.out.printf("[DatasetLoadingTest] Starting MSE = %.8f%n", startMse);
    }
}
