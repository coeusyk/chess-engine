package coeusyk.game.chess.tuner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the feature-level 90/10 train/val split used in K calibration isolation.
 *
 * <p>The split is performed as an O(1) subList view of the full feature list so that:
 * <ul>
 *   <li>K is fitted exclusively on {@code valFeatures} (never on training data)</li>
 *   <li>The gradient is computed exclusively on {@code trainFeatures}</li>
 *   <li>The two views are non-overlapping and together cover the full feature list</li>
 * </ul>
 */
class KCalibrationIsolationTest {

    // -----------------------------------------------------------------------
    // featureValSplitSize
    // -----------------------------------------------------------------------

    @ParameterizedTest(name = "n={0} -> valSize={1}")
    @CsvSource({
        "100,  10",
        "200,  20",
        "50,    5",
        "10,    1",
        "1,     1",   // min-1 clamp
        "9,     1",   // 0.10 * 9 = 0.9, cast to int = 0, clamped to 1
        "0,     1",   // min-1 clamp even for empty list
    })
    void featureValSplitSize_isCorrect(int n, int expected) {
        assertEquals(expected, TunerMain.featureValSplitSize(n));
    }

    // -----------------------------------------------------------------------
    // Feature-level subList split properties
    // -----------------------------------------------------------------------

    @Test
    void split100_trainIs90_valIs10() {
        List<Integer> features = makeList(100);
        int valSize = TunerMain.featureValSplitSize(features.size());

        List<Integer> valFeatures   = features.subList(features.size() - valSize, features.size());
        List<Integer> trainFeatures = features.subList(0, features.size() - valSize);

        assertEquals(90, trainFeatures.size(), "train should be 90");
        assertEquals(10, valFeatures.size(),   "val should be 10");
    }

    @Test
    void splitCoversFullList() {
        List<Integer> features = makeList(100);
        int valSize = TunerMain.featureValSplitSize(features.size());

        List<Integer> valFeatures   = features.subList(features.size() - valSize, features.size());
        List<Integer> trainFeatures = features.subList(0, features.size() - valSize);

        assertEquals(features.size(), trainFeatures.size() + valFeatures.size(),
                "train + val must equal total feature count");
    }

    @Test
    void splitIsNonOverlapping() {
        int n = 100;
        List<Integer> features = makeList(n);
        int valSize = TunerMain.featureValSplitSize(features.size());

        int trainEnd = features.size() - valSize;
        List<Integer> trainFeatures = features.subList(0, trainEnd);
        List<Integer> valFeatures   = features.subList(trainEnd, features.size());

        // The last element of train must be the element immediately before the first element of val.
        int lastTrainVal = trainFeatures.get(trainFeatures.size() - 1);
        int firstValVal  = valFeatures.get(0);
        assertEquals(lastTrainVal + 1, firstValVal,
                "train and val must form a contiguous, non-overlapping partition");
    }

    @Test
    void splitWith1Element_minClampApplied() {
        List<Integer> features = makeList(1);
        int valSize = TunerMain.featureValSplitSize(features.size());

        // Min-clamp: valSize == 1 even for a single-element list
        assertEquals(1, valSize);
        // trainFeatures is empty; valFeatures holds the single element
        List<Integer> trainFeatures = features.subList(0, features.size() - valSize);
        List<Integer> valFeatures   = features.subList(features.size() - valSize, features.size());

        assertTrue(trainFeatures.isEmpty(), "train must be empty when n=1");
        assertEquals(1, valFeatures.size(), "val must hold the single element");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Builds a simple integer list 0..n-1 as a stand-in for feature objects. */
    private static List<Integer> makeList(int n) {
        List<Integer> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) list.add(i);
        return list;
    }
}
