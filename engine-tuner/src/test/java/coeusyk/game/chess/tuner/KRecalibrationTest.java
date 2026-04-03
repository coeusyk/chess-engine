package coeusyk.game.chess.tuner;

import coeusyk.game.chess.core.models.Board;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for K recalibration behavior in GradientDescent and CoordinateDescent.
 * Covers: recalibrateK=true/false paths, drift tolerance, final K in output file.
 */
class KRecalibrationTest {

    private static List<LabelledPosition> smallDataset() {
        Board startpos = new Board();
        Board whiteUp  = new Board("rnb1kbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
        Board blackUp  = new Board("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNB1KBNR b KQkq - 0 1");
        return List.of(
                new LabelledPosition(TunerPosition.from(whiteUp),  1.0),
                new LabelledPosition(TunerPosition.from(blackUp),  0.0),
                new LabelledPosition(TunerPosition.from(startpos), 0.5),
                new LabelledPosition(TunerPosition.from(startpos), 0.5)
        );
    }

    // -----------------------------------------------------------------------
    // Adam: recalibrateK=false does not break correctness
    // -----------------------------------------------------------------------

    @Test
    void adamWithRecalibrateKFalseReturnsValidParams() {
        double[] params = EvalParams.extractFromCurrentEval();
        double k = KFinder.findK(smallDataset(), params);

        double[] tuned = GradientDescent.tune(smallDataset(), params, k, 2, false);

        assertEquals(params.length, tuned.length);
        for (int i = 0; i < tuned.length; i++) {
            assertTrue(tuned[i] >= EvalParams.PARAM_MIN[i] && tuned[i] <= EvalParams.PARAM_MAX[i],
                    "Param " + i + " out of bounds: " + tuned[i]);
        }
    }

    @Test
    void adamWithRecalibrateKTrueReturnsValidParams() {
        double[] params = EvalParams.extractFromCurrentEval();
        double k = KFinder.findK(smallDataset(), params);

        double[] tuned = GradientDescent.tune(smallDataset(), params, k, 2, true);

        assertEquals(params.length, tuned.length);
        for (int i = 0; i < tuned.length; i++) {
            assertTrue(tuned[i] >= EvalParams.PARAM_MIN[i] && tuned[i] <= EvalParams.PARAM_MAX[i],
                    "Param " + i + " out of bounds: " + tuned[i]);
        }
    }

    // -----------------------------------------------------------------------
    // CoordinateDescent: recalibrateK=false does not break correctness
    // -----------------------------------------------------------------------

    @Test
    void cdWithRecalibrateKFalseReturnsValidParams() {
        double[] params = EvalParams.extractFromCurrentEval();
        double k = KFinder.findK(smallDataset(), params);

        double[] tuned = CoordinateDescent.tune(smallDataset(), params, k, 1, false);

        assertEquals(params.length, tuned.length);
        for (int i = 0; i < tuned.length; i++) {
            assertTrue(tuned[i] >= EvalParams.PARAM_MIN[i] && tuned[i] <= EvalParams.PARAM_MAX[i],
                    "Param " + i + " out of bounds: " + tuned[i]);
        }
    }

    // -----------------------------------------------------------------------
    // EvalParams.writeToFile writes final K to the output file
    // -----------------------------------------------------------------------

    @Test
    void writeToFileIncludesFinalK(@TempDir Path tempDir) throws IOException {
        double[] params = EvalParams.extractFromCurrentEval();
        Path output = tempDir.resolve("tuned_params.txt");
        double finalK = 1.627046;

        EvalParams.writeToFile(params, finalK, output);

        assertTrue(Files.exists(output), "Output file must be created");
        String content = Files.readString(output);
        assertTrue(content.contains("Final K = 1.627046"),
                "Output file must contain the final K value; got:\n" + content.substring(0, Math.min(300, content.length())));
    }

    @Test
    void writeToFileWithNaNKDoesNotWriteKLine(@TempDir Path tempDir) throws IOException {
        double[] params = EvalParams.extractFromCurrentEval();
        Path output = tempDir.resolve("tuned_params.txt");

        // Legacy overload passes NaN
        EvalParams.writeToFile(params, output);

        String content = Files.readString(output);
        assertFalse(content.contains("Final K"),
                "Legacy writeToFile(params, output) must not write a K line");
    }
}
