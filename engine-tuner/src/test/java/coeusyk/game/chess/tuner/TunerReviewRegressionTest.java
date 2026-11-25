package coeusyk.game.chess.tuner;

import coeusyk.game.chess.core.models.Board;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regressions for the six findings of the independent review of #309. Each test fails on the code as it
 * stood before the fix named in its comment.
 */
class TunerReviewRegressionTest {

    private static List<LabelledPosition> corpus(int n) {
        List<LabelledPosition> out = new ArrayList<>();
        List<String> fens = CoreTunerParityTest.seededPositions(n, 309);
        for (int i = 0; i < n; i++) {
            out.add(new LabelledPosition(TunerPosition.from(new Board(fens.get(i))), (i % 3) * 0.5));
        }
        return out;
    }

    private static boolean[] only(int... indices) {
        boolean[] mask = new boolean[EvalParams.TOTAL_PARAMS];
        for (int i : indices) mask[i] = true;
        return mask;
    }

    // ---- 1. termination reasons and fail-closed gate ----

    @Test
    void coordinateDescentRecordsHowItEnded() {
        TunerRunMetrics metrics = new TunerRunMetrics();
        CoordinateDescent.tune(corpus(12), corpus(12), EvalParams.extractFromCurrentEval(), 1.0, 1, false,
                only(EvalParams.IDX_TEMPO), metrics);

        assertNotEquals("NOT_RUN", metrics.terminationReason, "coordinate descent left the run unrecorded");
        assertEquals("ITERATION_CAP".equals(metrics.terminationReason), metrics.hitIterCap);
        assertEquals(1, metrics.itersCompleted);
    }

    @Test
    void aZeroIterationCapIsRejectedByEveryOptimizer() {
        double[] p = EvalParams.extractFromCurrentEval();
        List<PositionFeatures> f = PositionFeatures.buildList(corpus(6));
        assertThrows(IllegalArgumentException.class,
                () -> CoordinateDescent.tune(corpus(6), corpus(6), p, 1.0, 0, false, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> GradientDescent.tuneWithFeatures(f, p, 1.0, 0, false, null, f, null));
        assertThrows(IllegalArgumentException.class,
                () -> GradientDescent.tuneWithFeaturesLBFGS(f, p, 1.0, 0, false, null, f, null));
    }

    @Test
    void lbfgsConvergingOnTheInitialGradientRecordsItsEvidenceAndPassesGate1() {
        List<PositionFeatures> f = PositionFeatures.buildList(corpus(12));
        TunerRunMetrics metrics = new TunerRunMetrics();

        // No active coordinate, so the active gradient norm is 0 before the first step.
        GradientDescent.tuneWithFeaturesLBFGS(f, EvalParams.extractFromCurrentEval(), 1.0, 5, false,
                new boolean[EvalParams.TOTAL_PARAMS], f, metrics);

        assertEquals(0, metrics.itersCompleted, "no iteration is fabricated");
        assertEquals("CONVERGED", metrics.terminationReason);
        assertTrue(Double.isFinite(metrics.initialMse));
        assertEquals(0.0, metrics.initialGradNorm);
        String verdict = TunerPostRunValidator.checkConvergence(metrics);
        assertTrue(verdict.contains("PASS"), verdict);
    }

    @Test
    void zeroIterationsWithAbsentOrNonFiniteEvidenceFailsGate1() {
        // An optimizer that never started.
        assertTrue(TunerPostRunValidator.checkConvergence(new TunerRunMetrics()).contains("FAIL"));

        double[][] bad = {
                {Double.NaN, 0.0},                                        // no initial MSE
                {0.02, Double.NaN},                                       // no gradient norm
                {0.02, Double.POSITIVE_INFINITY},                         // non-finite gradient norm
                {Double.POSITIVE_INFINITY, 0.0},                          // non-finite MSE
                {0.02, GradientDescent.LBFGS_GRAD_NORM_THRESHOLD * 10},   // norm above the convergence threshold
        };
        for (double[] b : bad) {
            TunerRunMetrics m = new TunerRunMetrics();
            m.recordTermination(true);
            m.initialMse = b[0];
            m.initialGradNorm = b[1];
            m.finalMse = b[0];
            m.minMse = Double.isFinite(b[0]) ? b[0] : Double.MAX_VALUE;
            assertTrue(TunerPostRunValidator.checkConvergence(m).contains("FAIL"),
                    "evidence " + java.util.Arrays.toString(b) + " must fail");
        }
    }

    // ---- 2. hanging suppression in the feature path ----

    /** Black Kh8 behind g7/h7, white Qg6 attacked by h7 and undefended: hanging, but part of a mating net (#138). */
    private static final String MATING_NET = "7k/6pp/6Q1/8/8/8/8/K7 w - - 0 1";

    /** Black Kh8 behind g7/h7, white Rg1 attacked by the bishop on b6 and undefended: hanging, but in a mating net (#138). */
    private static final String REVIEW_FEN = "7k/6pp/1b6/8/8/8/8/K5R1 w - - 0 1";

    /** The same white rook hangs to the bishop, but the black king is nowhere near: nothing is suppressed. */
    private static final String PLAIN_HANGING = "4k3/8/1b6/8/8/8/8/K5R1 w - - 0 1";

    /** The weight the feature vector gives to the hanging penalty, 0 when the position has no such feature. */
    private static float hangingCoefficient(String fen) {
        PositionFeatures f = PositionFeatures.buildList(
                List.of(new LabelledPosition(TunerPosition.from(new Board(fen)), 0.5))).get(0);
        for (int k = 0; k < f.indices.length; k++) {
            if (f.indices[k] == EvalParams.IDX_HANGING_PENALTY) return f.weights[k];
        }
        return 0.0f;
    }

    /** Score change from raising the hanging penalty from 0 to 40, on the feature path and on the static path. */
    private static double[] hangingDifferential(String fen) {
        Board board = new Board(fen);
        double[] off = EvalParams.extractFromCurrentEval();
        double[] on = off.clone();
        off[EvalParams.IDX_HANGING_PENALTY] = 0;
        on[EvalParams.IDX_HANGING_PENALTY] = 40;
        PositionFeatures f = PositionFeatures.buildList(List.of(new LabelledPosition(TunerPosition.from(board), 0.5))).get(0);
        return new double[] {
                f.eval(on) - f.eval(off),
                TunerEvaluator.evaluateStatic(board, on) - TunerEvaluator.evaluateStatic(board, off)};
    }

    @Test
    void aSuppressedHangingPieceHasNoFeatureCoefficientAndNoScoreEffect() {
        for (String fen : List.of(REVIEW_FEN, MATING_NET)) {
            assertEquals(0, TunerEvaluator.netHanging(TunerPosition.from(new Board(fen))), "part of a mating net: " + fen);
            assertEquals(0.0f, hangingCoefficient(fen), "feature coefficient must be 0 for " + fen);
            double[] d = hangingDifferential(fen);
            assertEquals(0.0, d[0], 1e-9, "feature path: the penalty must not change the score of " + fen);
            assertEquals(0.0, d[1], 1e-9, "static path: the penalty must not change the score of " + fen);
        }
    }

    @Test
    void anUnsuppressedHangingPieceCostsExactlyThePenaltyOnBothPaths() {
        assertEquals(-1, TunerEvaluator.netHanging(TunerPosition.from(new Board(PLAIN_HANGING))));
        assertEquals(-1.0f, hangingCoefficient(PLAIN_HANGING), "white has one hanging piece");
        double[] d = hangingDifferential(PLAIN_HANGING);
        assertEquals(-40.0, d[0], 1e-3, "feature path");
        assertEquals(-40.0, d[1], 1e-9, "static path");
    }

    /** Approximate consistency only. The hanging term is asserted exactly in the two tests above. */
    @Test
    void theFeatureObjectiveStaysCloseToTheStaticEvaluatorOnSeededPositions() {
        List<String> fens = new ArrayList<>(CoreTunerParityTest.seededPositions(120, 309));
        fens.addAll(List.of(CoreTunerParityTest.HAND_PICKED));
        fens.add(MATING_NET);
        fens.add(REVIEW_FEN);
        assertObjectiveMatchesStatic(fens);
    }

    /**
     * The Adam and L-BFGS objective uses {@code PositionFeatures.eval}. This is a loose consistency check, not
     * exact parity: the feature path sums float weights and the static path truncates to int per term.
     */
    private static void assertObjectiveMatchesStatic(List<String> fens) {
        double[] params = EvalParams.extractFromCurrentEval();
        for (String fen : fens) {
            Board board = new Board(fen);
            List<PositionFeatures> f = PositionFeatures.buildList(List.of(new LabelledPosition(TunerPosition.from(board), 0.5)));
            double viaFeatures = f.get(0).eval(params);
            int viaStatic = TunerEvaluator.evaluateStatic(board, params);
            // Features are float weights and the static score truncates to int per term, so the two drift by up to
            // a few tens of cp on crowded boards (15 cp seen with hanging and king safety both zeroed, so it is not
            // those terms). A missed hanging suppression moves the score by the whole penalty, 40 cp, and no
            // seeded position needs more than 25.
            assertEquals(viaStatic, viaFeatures, 25.0, "feature objective differs from the static evaluator on " + fen);
        }
    }

    // ---- 3 and 6. parameter ownership ----

    @Test
    void inactiveGradientsNeverReachTheCurvatureHistory() {
        List<PositionFeatures> f = PositionFeatures.buildList(corpus(30));
        double[] p = EvalParams.extractFromCurrentEval();
        boolean[] mask = only(EvalParams.IDX_TEMPO);

        double[] grad = GradientDescent.computeBarrierGradient(f, p, 1.0, 1, mask);

        for (int i = 0; i < grad.length; i++) {
            if (i != EvalParams.IDX_TEMPO) assertEquals(0.0, grad[i], "inactive coordinate " + i + " carries gradient");
        }
        assertNotEquals(0.0, grad[EvalParams.IDX_TEMPO]);
    }

    @Test
    void frozenAndOutOfGroupParametersKeepTheirInputValueInEveryOptimizer() {
        double[] start = EvalParams.extractFromCurrentEval();
        start[8] = 1.0;                                   // queen MG far below rook MG: clamping or ordering would move it
        start[EvalParams.IDX_HANGING_PENALTY] = 55.0;      // frozen at 40: clamping would move it
        boolean[] mask = only(EvalParams.IDX_TEMPO);
        List<LabelledPosition> positions = corpus(24);
        List<PositionFeatures> f = PositionFeatures.buildList(positions);

        List<double[]> results = List.of(
                GradientDescent.tuneWithFeatures(f, start, 1.0, 2, false, mask, f, null),
                GradientDescent.tuneWithFeaturesLBFGS(f, start, 1.0, 2, false, mask, f, null),
                CoordinateDescent.tune(positions, positions, start, 1.0, 1, false, mask, null));

        for (double[] tuned : results) {
            for (int i = 0; i < start.length; i++) {
                if (i != EvalParams.IDX_TEMPO) assertEquals(start[i], tuned[i], "parameter " + i + " was changed");
            }
        }
    }

    // ---- 4. partition isolation is covered in KCalibrationIsolationTest ----

    // ---- 5. twin identity ----

    private static List<LabelledPosition> balancedCorpus(Path dir) throws Exception {
        List<String> lines = new ArrayList<>();
        String[] results = {"1-0", "0-1", "1/2-1/2"};
        int i = 0;
        for (String fen : CoreTunerParityTest.seededPositions(80, 5)) lines.add(fen + " c9 \"" + results[i++ % 3] + "\";");
        Path epd = dir.resolve("twins.epd");
        Files.write(epd, lines);
        return PositionLoader.loadBalanced(epd, Integer.MAX_VALUE);
    }

    /** A twin-identified corpus whose labels are all 1.0, so no label could mirror: only identity can pair it. */
    private static final class UnmirroredLabels extends java.util.AbstractList<LabelledPosition>
            implements PositionLoader.TwinIdentified {
        private final List<LabelledPosition> inner;
        UnmirroredLabels(List<LabelledPosition> balanced) { this.inner = balanced; }
        @Override public LabelledPosition get(int i) { return new LabelledPosition(inner.get(i).pos(), 1.0); }
        @Override public int size() { return inner.size(); }
        @Override public int twinOf(int i) { return ((PositionLoader.TwinIdentified) inner).twinOf(i); }
    }

    @Test
    void identityPairsTwinsWithoutLookingAtLabels(@TempDir Path dir) throws Exception {
        List<LabelledPosition> balanced = balancedCorpus(dir);

        long pairs = PositionLoader.twinGroups(new UnmirroredLabels(balanced)).stream().filter(g -> g.length == 2).count();

        assertEquals(balanced.size() / 2, pairs);
    }

    @Test
    void aGeneratedCorpusPairsItsTwinsByIdentity(@TempDir Path dir) throws Exception {
        List<LabelledPosition> balanced = balancedCorpus(dir);
        assertInstanceOf(PositionLoader.TwinIdentified.class, balanced);
        PositionLoader.TwinIdentified known = (PositionLoader.TwinIdentified) balanced;

        for (int[] g : PositionLoader.twinGroups(balanced)) {
            if (g.length == 2) assertEquals(g[1], known.twinOf(g[0]));
        }
        long flips = balanced.size() / 2;
        assertEquals(flips, PositionLoader.twinGroups(balanced).stream().filter(g -> g.length == 2).count());
    }

    /** Relabels a balanced corpus with outcomes whose mirror is inexact in floating point, as a plain list. */
    private static List<LabelledPosition> relabelled(List<LabelledPosition> balanced) {
        PositionLoader.TwinIdentified known = (PositionLoader.TwinIdentified) balanced;
        double[] awkward = {0.0005, 0.0015, 0.0025, 0.1, 0.2, 0.3, 0.123456789, 0.7, 0.9995};
        int half = balanced.size() / 2;
        List<LabelledPosition> out = new ArrayList<>(balanced.size());
        for (int i = 0; i < balanced.size(); i++) {
            int base = i < half ? i : known.twinOf(i);
            double o = awkward[base % awkward.length];
            out.add(new LabelledPosition(balanced.get(i).pos(), i < half ? o : 1.0 - o));
        }
        return out;
    }

    @Test
    void inferredTwinsDoNotDependOnRoundingTheLabel(@TempDir Path dir) throws Exception {
        List<LabelledPosition> plain = relabelled(balancedCorpus(dir));

        long pairs = PositionLoader.twinGroups(plain).stream().filter(g -> g.length == 2).count();

        assertEquals(plain.size() / 2, pairs, "every twin must be found whatever its label");
    }

    @Test
    void aHashCollisionNeverPairsDifferentPositionsOrUnmirroredLabels(@TempDir Path dir) throws Exception {
        List<LabelledPosition> plain = relabelled(balancedCorpus(dir));

        List<int[]> groups = PositionLoader.twinGroups(plain, s -> 42L);   // every record in one hash run

        for (int[] g : groups) {
            if (g.length != 2) continue;
            assertEquals(PositionLoader.twinKey(plain.get(g[0]).pos().fen()), PositionLoader.twinKey(plain.get(g[1]).pos().fen()));
            assertEquals(1.0, plain.get(g[0]).outcome() + plain.get(g[1]).outcome(), 1e-9);
        }
        assertEquals(plain.size() / 2, groups.stream().filter(g -> g.length == 2).count());
    }

    @Test
    void aFlippedCopyWithTheSameLabelIsNotATwin(@TempDir Path dir) throws Exception {
        List<LabelledPosition> balanced = balancedCorpus(dir);
        List<LabelledPosition> sameLabel = new ArrayList<>();
        for (LabelledPosition lp : balanced) sameLabel.add(new LabelledPosition(lp.pos(), 1.0));

        assertTrue(PositionLoader.twinGroups(sameLabel).stream().noneMatch(g -> g.length == 2),
                "a copy whose label does not mirror the original is a different record");
    }
}
