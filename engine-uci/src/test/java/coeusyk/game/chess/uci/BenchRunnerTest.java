package coeusyk.game.chess.uci;

import coeusyk.game.chess.core.eval.nnue.FeatureExtractor;
import coeusyk.game.chess.core.eval.nnue.NnueEvaluator;
import coeusyk.game.chess.core.eval.nnue.NnueNetwork;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Issue #206: evaluator selection in the benchmark path. Verifies BenchRunner's
 * new run(depth, evaluatorFactory, label) overload actually switches evaluators
 * and reports the #216 instrumentation, without requiring a real .nnue file or
 * native-Windows execution (this test runs anywhere the module's tests run;
 * the actual native-Windows NPS-gate measurement is a separate, manual step).
 */
class BenchRunnerTest {

    private static final int DEPTH = 4; // fast enough for a unit test

    @Test
    void defaultRunUsesClassicalEvaluatorAndReportsZeroAccumulatorTime() {
        String output = captureStdout(() -> new BenchRunner().run(DEPTH));

        assertTrue(output.contains("evaluator Classical"));
        assertTrue(output.contains("Accumulator-update time: 0.0%"));
    }

    @Test
    void nnueEvaluatorFactoryIsUsedAndAccumulatorTimeIsReported() {
        NnueNetwork network = syntheticNetwork(8);
        String output = captureStdout(() ->
            new BenchRunner().run(DEPTH, () -> new NnueEvaluator(network), "NNUE (test)"));

        assertTrue(output.contains("evaluator NNUE (test)"));
        assertFalse(output.contains("Accumulator-update time: 0.0%"),
            "a real NNUE evaluator should register non-zero accumulator-update time");
    }

    /** Tiny deterministic synthetic network -- engine-core's own TestNetworks is
     * test-scoped and not visible cross-module, so this mirrors it locally. */
    private static NnueNetwork syntheticNetwork(int hiddenWidth) {
        return new NnueNetwork(
            hiddenWidth,
            new short[FeatureExtractor.FEATURES_PER_PERSPECTIVE * hiddenWidth],
            new short[hiddenWidth],
            new short[2 * hiddenWidth],
            0, 127, 64, 400, "bench-test-uuid", "bench-test-commit", 0L);
    }

    private static String captureStdout(Runnable action) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            action.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
