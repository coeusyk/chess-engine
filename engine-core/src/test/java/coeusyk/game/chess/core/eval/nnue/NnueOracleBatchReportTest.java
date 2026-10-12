package coeusyk.game.chess.core.eval.nnue;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * PR C-5 (issue #189), nightly-only: runs {@link NnueOracle#compareBatch} — the int16
 * production path vs. a float32 dequantized re-run of the same forward pass (ADR-002) —
 * over every FEN in {@code bench/nnue-corpus/}, logging max/mean absolute error. This
 * implements ADR-002's own deferred policy directly: "numeric threshold deferred until a
 * real trained net exists to calibrate against — reporting, not gating" (the CI test net
 * is untrained seeded-random weights, so there is no meaningful threshold to gate on yet).
 *
 * <p>Excluded from the standard test suite and from PR-blocking CI — gated the same way
 * as {@code NnueCorpusBenchmarkTest}, via its own tag plus an {@code Assumptions} check,
 * so it only runs from the nightly workflow. Run with:
 * <pre>
 *   mvn -pl engine-core test -Dgroups=nnue-oracle-batch -Doracle.batch.enabled=true
 * </pre>
 */
@Tag("nnue-oracle-batch")
class NnueOracleBatchReportTest {

    private static final Path CORPUS_DIR = Path.of("..", "bench", "nnue-corpus");

    @Test
    void reportInt16VsFloat32DivergenceAcrossTheFullCorpus() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean("oracle.batch.enabled"),
                "Set -Doracle.batch.enabled=true to run the oracle-bound batch report");

        NnueNetwork network = TestNetworks.synthetic(8);
        List<String> allFens = new ArrayList<>();
        for (String category : NnueCorpusCategories.FILE_NAMES) {
            for (String fen : NnueCorpusCategories.readFens(CORPUS_DIR.resolve(category))) {
                allFens.add(fen + " 0 1");
            }
        }

        NnueOracle.BatchOracleResult result = NnueOracle.compareBatch(network, allFens);

        System.out.printf(Locale.US,
                "[NnueOracleBatch] positions=%d maxAbsoluteError=%.6f meanAbsoluteError=%.6f%n",
                allFens.size(), result.maxAbsoluteError(), result.meanAbsoluteError());
        // No assertion on the error magnitude — ADR-002's numeric threshold is explicitly
        // deferred (reporting-only) until a real trained network exists to calibrate
        // against. This test still fails if compareBatch itself throws (e.g. a malformed
        // corpus FEN), which is a real regression signal on its own.
    }
}
