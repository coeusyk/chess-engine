package coeusyk.game.chess.core.eval.nnue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Round-trips {@link NnueNetwork}'s documented binary format through a hand-written encoder. */
class NnueNetworkLoaderTest {

    @Test
    void loadReconstructsEveryFieldFromAWrittenFile() throws IOException {
        int width = 4;
        short[] ftWeights = new short[FeatureExtractor.FEATURES_PER_PERSPECTIVE * width];
        for (int i = 0; i < ftWeights.length; i++) {
            ftWeights[i] = (short) (i % 37 - 18);
        }
        short[] ftBiases = {1, -2, 3, -4};
        short[] outputWeights = {5, -6, 7, -8, 9, -10, 11, -12};
        int outputBias = 123;

        byte[] bytes = writeNetwork(width, ftWeights, ftBiases, outputWeights, outputBias,
                111, 222, 400, "uuid-1", "commit-1", 1720000000L);

        NnueNetwork loaded = NnueNetwork.load(new ByteArrayInputStream(bytes));

        assertEquals(width, loaded.hiddenWidth());
        assertArrayEquals(ftWeights, loaded.ftWeights());
        assertArrayEquals(ftBiases, loaded.ftBiases());
        assertArrayEquals(outputWeights, loaded.outputWeights());
        assertEquals(outputBias, loaded.outputBias());
        assertEquals(111, loaded.qa());
        assertEquals(222, loaded.qb());
        assertEquals(400, loaded.outputScale());
        assertEquals("uuid-1", loaded.networkUuid());
        assertEquals("commit-1", loaded.trainerCommit());
        assertEquals(1720000000L, loaded.createdAtEpochSeconds());
    }

    // Issue #203 (E-3): every other round-trip test in this class uses width 4 --
    // too small to catch an off-by-one or indexing bug that only manifests at the
    // real candidate net's actual scale (hidden_width=256, PRD Section 4 Network
    // Specification). The real .nnue trainer/scripts/train_candidate_net.py produces
    // is gitignored, never committed (architecture doc Section 9: manifests only,
    // never weight binaries except the tiny CI net), so this exercises the loader at
    // that real scale with synthetic weights instead.
    @Test
    void loadRoundTripsAtRealCandidateNetworkScale() throws IOException {
        int width = 256;
        short[] ftWeights = new short[FeatureExtractor.FEATURES_PER_PERSPECTIVE * width];
        for (int i = 0; i < ftWeights.length; i++) {
            ftWeights[i] = (short) (i % 2041 - 1020);   // within the qa=127 weight clip (issue #264)
        }
        short[] ftBiases = new short[width];
        for (int i = 0; i < ftBiases.length; i++) {
            ftBiases[i] = (short) (i % 255 - 127);      // within the qa=127 bias clip
        }
        short[] outputWeights = new short[2 * width];
        for (int i = 0; i < outputWeights.length; i++) {
            outputWeights[i] = (short) (i % 65536 - 32768);
        }
        int outputBias = 12345;

        byte[] bytes = writeNetwork(width, ftWeights, ftBiases, outputWeights, outputBias,
                127, 64, 400, "real-scale-uuid", "commit-1", 1720000000L);

        NnueNetwork loaded = NnueNetwork.load(new ByteArrayInputStream(bytes));

        assertEquals(width, loaded.hiddenWidth());
        assertArrayEquals(ftWeights, loaded.ftWeights());
        assertArrayEquals(ftBiases, loaded.ftBiases());
        assertArrayEquals(outputWeights, loaded.outputWeights());
        assertEquals(outputBias, loaded.outputBias());
        assertEquals(127, loaded.qa());
        assertEquals(64, loaded.qb());
    }

    @Test
    void loadRejectsBadMagicBytes() {
        byte[] bytes = {'X', 'X', 'X', 'X'};
        assertThrows(IOException.class, () -> NnueNetwork.load(new ByteArrayInputStream(bytes)));
    }

    @Test
    void loadRejectsUnsupportedArchitectureId() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeBytes("VNUE");
        out.writeInt(1); // formatVersion
        out.writeInt(99); // architectureId — unsupported

        IOException thrown = assertThrows(IOException.class,
                () -> NnueNetwork.load(new ByteArrayInputStream(bytes.toByteArray())));
        assertTrue(thrown.getMessage().contains("architectureId"));
    }

    @Test
    void loadRejectsUnsupportedFeatureSetId() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeBytes("VNUE");
        out.writeInt(1); // formatVersion
        out.writeInt(1); // architectureId
        out.writeInt(99); // featureSetId — unsupported

        IOException thrown = assertThrows(IOException.class,
                () -> NnueNetwork.load(new ByteArrayInputStream(bytes.toByteArray())));
        assertTrue(thrown.getMessage().contains("featureSetId"));
    }

    @Test
    void loadRejectsOversizedHiddenWidthBeforeAllocatingAnything() throws IOException {
        // A corrupt/hostile header claiming an enormous hiddenWidth must be rejected
        // right after reading that field — never let it drive a huge allocation.
        // No body bytes follow; if the width check didn't run first, this would throw
        // an unrelated EOFException from readShorts instead.
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeBytes("VNUE");
        out.writeInt(1);
        out.writeInt(1);
        out.writeInt(1);
        out.writeInt(100_000_000); // hiddenWidth — nonsense
        out.writeInt(1);
        out.writeInt(1);
        out.writeInt(1);
        out.writeInt(1);
        out.writeUTF("uuid");
        out.writeUTF("commit");
        out.writeLong(0L);

        IOException thrown = assertThrows(IOException.class,
                () -> NnueNetwork.load(new ByteArrayInputStream(bytes.toByteArray())));
        assertTrue(thrown.getMessage().contains("hiddenWidth"));
    }

    @Test
    void loadRejectsZeroHiddenWidth() throws IOException {
        // Boundary case for the same `hiddenWidth <= 0` guard exercised above at the
        // oversized end — zero is the other edge of "<= 0" and wasn't separately covered.
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeBytes("VNUE");
        out.writeInt(1);
        out.writeInt(1);
        out.writeInt(1);
        out.writeInt(0); // hiddenWidth — zero, not just "very large"
        out.writeInt(1);
        out.writeInt(1);
        out.writeInt(1);
        out.writeInt(1);
        out.writeUTF("uuid");
        out.writeUTF("commit");
        out.writeLong(0L);

        IOException thrown = assertThrows(IOException.class,
                () -> NnueNetwork.load(new ByteArrayInputStream(bytes.toByteArray())));
        assertTrue(thrown.getMessage().contains("hiddenWidth"));
    }

    @Test
    void loadRejectsCorruptUtfString() throws IOException {
        // A 3-byte modified-UTF-8 lead (0xE0) whose continuation bytes don't match the
        // required 10xxxxxx pattern (0x00, 0x00) is malformed per DataInput.readUTF's
        // spec — confirmed empirically to throw UTFDataFormatException, unlike an
        // unpaired surrogate byte sequence, which Java's readUTF accepts without error.
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeBytes("VNUE");
        out.writeInt(1);
        out.writeInt(1);
        out.writeInt(1);
        out.writeInt(4); // hiddenWidth
        out.writeInt(1);
        out.writeInt(1);
        out.writeInt(1);
        out.writeInt(1);
        out.writeShort(3);
        out.write(0xE0);
        out.write(0x00);
        out.write(0x00);

        IOException thrown = assertThrows(IOException.class,
                () -> NnueNetwork.load(new ByteArrayInputStream(bytes.toByteArray())));
        assertTrue(thrown instanceof java.io.UTFDataFormatException,
                "expected UTFDataFormatException for a corrupt UTF string, got " + thrown.getClass());
    }

    // Issue #191, fixed: qa/qb previously loaded with no validation at all (unlike every
    // other header field in this class) — a zero or negative value would load successfully
    // and only fail later, at evaluation time, with an ArithmeticException (divide by
    // zero) or silently wrong output. Four boundary cases below, mirroring the
    // zero/oversized-hiddenWidth pair already covered above.

    @Test
    void loadRejectsZeroQa() throws IOException {
        int width = 4;
        short[] ftWeights = new short[FeatureExtractor.FEATURES_PER_PERSPECTIVE * width];
        short[] ftBiases = new short[width];
        short[] outputWeights = new short[2 * width];
        byte[] bytes = writeNetwork(width, ftWeights, ftBiases, outputWeights, 0,
                0, 64, 400, "uuid", "commit", 0L);

        IOException thrown = assertThrows(IOException.class,
                () -> NnueNetwork.load(new ByteArrayInputStream(bytes)));
        assertTrue(thrown.getMessage().contains("qa"));
    }

    @Test
    void loadRejectsNegativeQa() throws IOException {
        int width = 4;
        short[] ftWeights = new short[FeatureExtractor.FEATURES_PER_PERSPECTIVE * width];
        short[] ftBiases = new short[width];
        short[] outputWeights = new short[2 * width];
        byte[] bytes = writeNetwork(width, ftWeights, ftBiases, outputWeights, 0,
                -127, 64, 400, "uuid", "commit", 0L);

        IOException thrown = assertThrows(IOException.class,
                () -> NnueNetwork.load(new ByteArrayInputStream(bytes)));
        assertTrue(thrown.getMessage().contains("qa"));
    }

    @ParameterizedTest
    @ValueSource(ints = {32768, 65536, Integer.MAX_VALUE})
    void loadRejectsQaAboveShortMax(int badQa) throws IOException {
        int width = 4;
        short[] ftWeights = new short[FeatureExtractor.FEATURES_PER_PERSPECTIVE * width];
        short[] ftBiases = new short[width];
        short[] outputWeights = new short[2 * width];
        byte[] bytes = writeNetwork(width, ftWeights, ftBiases, outputWeights, 0,
                badQa, 64, 400, "uuid", "commit", 0L);

        IOException thrown = assertThrows(IOException.class,
                () -> NnueNetwork.load(new ByteArrayInputStream(bytes)));
        assertTrue(thrown.getMessage().contains("qa"));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 64, 127, 128, 255, 256, 512, 32767})
    void loadAcceptsSupportedQaValuesAndSafeAccumulation(int qa) throws IOException {
        int width = 2;
        short weightClip = (short) ((Short.MAX_VALUE - qa) / 32);
        short[] ftWeights = new short[FeatureExtractor.FEATURES_PER_PERSPECTIVE * width];
        for (int f = 0; f < 32; f++) {
            ftWeights[f * width] = weightClip;
            ftWeights[f * width + 1] = (short) -weightClip;
        }
        short[] ftBiases = {(short) qa, (short) -qa};
        short[] outputWeights = new short[2 * width];
        byte[] bytes = writeNetwork(width, ftWeights, ftBiases, outputWeights, 0,
                qa, 64, 400, "uuid", "commit", 0L);

        NnueNetwork loaded = NnueNetwork.load(new ByteArrayInputStream(bytes));
        assertEquals(qa, loaded.qa());

        short posAcc = loaded.ftBiases()[0];
        short negAcc = loaded.ftBiases()[1];
        for (int f = 0; f < 32; f++) {
            posAcc = (short) (posAcc + loaded.ftWeights()[f * width]);
            negAcc = (short) (negAcc + loaded.ftWeights()[f * width + 1]);
        }
        assertTrue(posAcc >= 0, "positive accumulation wrapped negative for qa=" + qa + ": " + posAcc);
        assertTrue(negAcc <= 0, "negative accumulation wrapped positive for qa=" + qa + ": " + negAcc);
    }

    @Test
    void loadRejectsZeroQb() throws IOException {
        int width = 4;
        short[] ftWeights = new short[FeatureExtractor.FEATURES_PER_PERSPECTIVE * width];
        short[] ftBiases = new short[width];
        short[] outputWeights = new short[2 * width];
        byte[] bytes = writeNetwork(width, ftWeights, ftBiases, outputWeights, 0,
                127, 0, 400, "uuid", "commit", 0L);

        IOException thrown = assertThrows(IOException.class,
                () -> NnueNetwork.load(new ByteArrayInputStream(bytes)));
        assertTrue(thrown.getMessage().contains("qb"));
    }

    @Test
    void loadRejectsNegativeQb() throws IOException {
        int width = 4;
        short[] ftWeights = new short[FeatureExtractor.FEATURES_PER_PERSPECTIVE * width];
        short[] ftBiases = new short[width];
        short[] outputWeights = new short[2 * width];
        byte[] bytes = writeNetwork(width, ftWeights, ftBiases, outputWeights, 0,
                127, -64, 400, "uuid", "commit", 0L);

        IOException thrown = assertThrows(IOException.class,
                () -> NnueNetwork.load(new ByteArrayInputStream(bytes)));
        assertTrue(thrown.getMessage().contains("qb"));
    }

    @Test
    void loadRejectsTruncatedFile(@TempDir Path tempDir) throws IOException {
        int width = 4;
        short[] ftWeights = new short[FeatureExtractor.FEATURES_PER_PERSPECTIVE * width];
        short[] ftBiases = new short[width];
        short[] outputWeights = new short[2 * width];
        byte[] fullFile = writeNetwork(width, ftWeights, ftBiases, outputWeights, 0,
                1, 1, 1, "uuid", "commit", 0L);

        Path truncated = tempDir.resolve("truncated.nnue");
        // Chop off the last 10 bytes — the file no longer matches its own declared width.
        byte[] shortBytes = new byte[fullFile.length - 10];
        System.arraycopy(fullFile, 0, shortBytes, 0, shortBytes.length);
        Files.write(truncated, shortBytes);

        IOException thrown = assertThrows(IOException.class, () -> NnueNetwork.load(truncated));
        assertTrue(thrown.getMessage().contains("does not match expected size"));
    }

    // ---- Issue #264: load-time int16 accumulator bound (<= 32 active features) ----

    /** Width-2 network where neuron 0 carries {@code weight} on {@code count} features and {@code bias}. */
    private static byte[] netWithNeuron0(int count, short weight, short bias) throws IOException {
        int width = 2;
        short[] ftWeights = new short[FeatureExtractor.FEATURES_PER_PERSPECTIVE * width];
        for (int f = 0; f < count; f++) {
            ftWeights[f * width] = weight;
        }
        short[] ftBiases = {bias, 0};
        return writeNetwork(width, ftWeights, ftBiases, new short[2 * width], 0, 127, 64, 400, "u", "c", 0L);
    }

    private static void assertRejectedAsOverflow(byte[] bytes, String direction) {
        IOException thrown = assertThrows(IOException.class, () -> NnueNetwork.load(new ByteArrayInputStream(bytes)));
        assertTrue(thrown.getMessage().contains("accumulator overflow risk"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("hidden neuron 0"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains(direction), thrown.getMessage());
    }

    @Test
    void loadAcceptsExactPositiveInt16Boundary() throws IOException {
        // 767 + 32 * 1000 == 32767 exactly.
        NnueNetwork.load(new ByteArrayInputStream(netWithNeuron0(32, (short) 1000, (short) 767)));
    }

    @Test
    void loadRejectsOneAbovePositiveInt16Boundary() throws IOException {
        // 768 + 32 * 1000 == 32768.
        assertRejectedAsOverflow(netWithNeuron0(32, (short) 1000, (short) 768), "above the int16 maximum");
    }

    @Test
    void loadAcceptsExactNegativeInt16Boundary() throws IOException {
        // -768 + 32 * -1000 == -32768, representable, so not an overflow.
        NnueNetwork.load(new ByteArrayInputStream(netWithNeuron0(32, (short) -1000, (short) -768)));
    }

    @Test
    void loadRejectsOneBelowNegativeInt16Boundary() throws IOException {
        // -769 + 32 * -1000 == -32769.
        assertRejectedAsOverflow(netWithNeuron0(32, (short) -1000, (short) -769), "below the int16 minimum");
    }

    @Test
    void loadOnlyCountsThirtyTwoFeaturesNotEveryLargeWeight() throws IOException {
        // 40 large weights exist but at most 32 features can be active, so only 32 count.
        NnueNetwork.load(new ByteArrayInputStream(netWithNeuron0(40, (short) 1000, (short) 767)));
        // The 33rd weight being large does not rescue a net whose 32 largest already overflow.
        assertRejectedAsOverflow(netWithNeuron0(40, (short) 1000, (short) 768), "above the int16 maximum");
    }

    @Test
    void loadIgnoresOppositeSignWeightsWhenBoundingEachDirection() throws IOException {
        // Negative weights cannot reduce the worst positive sum (fewer features may be active).
        int width = 2;
        short[] ftWeights = new short[FeatureExtractor.FEATURES_PER_PERSPECTIVE * width];
        for (int f = 0; f < 32; f++) {
            ftWeights[f * width] = 1000;
        }
        for (int f = 32; f < 64; f++) {
            ftWeights[f * width] = -3000;
        }
        byte[] bytes = writeNetwork(width, ftWeights, new short[] {768, 0}, new short[2 * width], 0,
                127, 64, 400, "u", "c", 0L);
        assertRejectedAsOverflow(bytes, "above the int16 maximum");
    }

    @Test
    void loadChecksEveryHiddenNeuronNotJustTheFirst() throws IOException {
        int width = 3;
        short[] ftWeights = new short[FeatureExtractor.FEATURES_PER_PERSPECTIVE * width];
        for (int f = 0; f < 32; f++) {
            ftWeights[f * width + 2] = 1000;
        }
        byte[] bytes = writeNetwork(width, ftWeights, new short[] {0, 0, 768}, new short[2 * width], 0,
                127, 64, 400, "u", "c", 0L);
        IOException thrown = assertThrows(IOException.class, () -> NnueNetwork.load(new ByteArrayInputStream(bytes)));
        assertTrue(thrown.getMessage().contains("hidden neuron 2"), thrown.getMessage());
    }

    @Test
    void loadRejectsExternallyGeneratedNetWithUnclippedExtremeWeights() throws IOException {
        // What an exporter that skipped weight clipping would produce: full-range int16 everywhere.
        int width = 2;
        short[] ftWeights = new short[FeatureExtractor.FEATURES_PER_PERSPECTIVE * width];
        java.util.Arrays.fill(ftWeights, Short.MAX_VALUE);
        byte[] high = writeNetwork(width, ftWeights, new short[] {Short.MAX_VALUE, 0}, new short[2 * width], 0,
                127, 64, 400, "u", "c", 0L);
        assertRejectedAsOverflow(high, "above the int16 maximum");

        java.util.Arrays.fill(ftWeights, Short.MIN_VALUE);
        byte[] low = writeNetwork(width, ftWeights, new short[] {Short.MIN_VALUE, 0}, new short[2 * width], 0,
                127, 64, 400, "u", "c", 0L);
        assertRejectedAsOverflow(low, "below the int16 minimum");
    }

    @Test
    void loadRejectsOverflowOnFileEntryPointToo(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("unsafe.nnue");
        Files.write(file, netWithNeuron0(32, (short) 1000, (short) 768));
        IOException thrown = assertThrows(IOException.class, () -> NnueNetwork.load(file));
        assertTrue(thrown.getMessage().contains("accumulator overflow risk"), thrown.getMessage());
    }

    @Test
    void loadStillAcceptsTheProductionQaClipEnvelope() throws IOException {
        // qa=127 production contract: bias clip 127, weight clip floor((32767-127)/32) == 1020.
        NnueNetwork.load(new ByteArrayInputStream(netWithNeuron0(32, (short) 1020, (short) 127)));
        NnueNetwork.load(new ByteArrayInputStream(netWithNeuron0(32, (short) -1020, (short) -127)));
    }

    @Test
    void committedSupportedFixtureSatisfiesTheAccumulatorBound() throws IOException {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.exists(current.resolve("engine-core/src/test/resources/nnue/d6-export-fixture.nnue"))) {
            current = current.getParent();
        }
        assertTrue(current != null, "committed fixture not found from the working directory");
        NnueNetwork loaded = NnueNetwork.load(current.resolve("engine-core/src/test/resources/nnue/d6-export-fixture.nnue"));
        assertTrue(loaded.hiddenWidth() > 0);
    }

    /**
     * Scalar coverage only (develop has no vector dot kernel or guard): an output layer whose
     * {@code qa * sum(|w|)} exceeds the int32 range must still evaluate exactly, because the scalar loop
     * sums {@code long} products. The weights are asserted to exceed the bound, and the test asserts that
     * real positions produced a raw sum outside int32, so it cannot pass vacuously.
     */
    @Test
    void scalarEvaluationIsExactWhenTheOutputSumExceedsInt32() throws IOException {
        int width = 64;
        int qa = 1016;
        int qb = 128;
        int outputScale = 400;
        java.util.Random random = new java.util.Random(1016L);
        short[] ftWeights = new short[FeatureExtractor.FEATURES_PER_PERSPECTIVE * width];
        for (int i = 0; i < ftWeights.length; i++) {
            ftWeights[i] = (short) (random.nextInt(321) - 160);
        }
        short[] ftBiases = new short[width];
        java.util.Arrays.fill(ftBiases, (short) 800);
        short[] outputWeights = new short[2 * width];
        java.util.Arrays.fill(outputWeights, (short) 32767);

        long absSum = 0;
        for (short w : outputWeights) {
            absSum += Math.abs((long) w);
        }
        assertTrue((long) qa * absSum > Integer.MAX_VALUE,
                "the fixture must exceed the int32 bound, qa * sum|w| = " + (long) qa * absSum);

        NnueNetwork network = NnueNetwork.load(new ByteArrayInputStream(writeNetwork(width, ftWeights, ftBiases,
                outputWeights, 0, qa, qb, outputScale, "overflow-uuid", "commit", 0L)));
        NnueEvaluator evaluator = new NnueEvaluator(network);

        int beyondInt32 = 0;
        java.util.List<String> fens = NnueFixtureGoldenSupport.corpusFens().subList(0, 50);
        for (String fen : fens) {
            coeusyk.game.chess.core.models.Board board = NnueCorpusCategories.toBoard(fen);
            evaluator.reset(board);
            boolean whiteToMove = coeusyk.game.chess.core.models.Piece.isWhite(board.getActiveColor());
            long sum = 0;
            int usColor = board.getActiveColor();
            int themColor = whiteToMove ? coeusyk.game.chess.core.models.Piece.Black : coeusyk.game.chess.core.models.Piece.White;
            for (int perspective = 0; perspective < 2; perspective++) {
                int color = perspective == 0 ? usColor : themColor;
                // Independent of NnueEvaluator: sum the active feature rows into an int16 accumulator by hand.
                short[] acc = ftBiases.clone();
                for (int feature : FeatureExtractor.activeFeatureIndices(board, color)) {
                    for (int j = 0; j < width; j++) {
                        acc[j] = (short) (acc[j] + ftWeights[feature * width + j]);
                    }
                }
                int weightBase = perspective == 0 ? 0 : width;
                for (int j = 0; j < width; j++) {
                    long activation = Math.max(0, Math.min(acc[j], qa));
                    sum += activation * outputWeights[weightBase + j];
                }
            }
            if (Math.abs(sum) > Integer.MAX_VALUE) {
                beyondInt32++;
            }
            int expected = (int) (sum * outputScale / ((long) qa * qb));
            assertEquals(expected, evaluator.evaluate(board), "scalar evaluate differs from the long reference for " + fen);
        }
        assertTrue(beyondInt32 > 0, "no position produced a raw output sum outside int32, the test proved nothing");
    }

    /**
     * An accumulator at the loader's own worst-case limit at qa 1016 must load and must not wrap: every
     * feature weight is the largest the production clip allows, so a start position (32 active features)
     * sits at {@code bias + 32 * weight}, just under the int16 maximum, and then clamps to qa.
     */
    @Test
    void evaluateAtTheLoaderBoundEdgeDoesNotWrap() throws IOException {
        int width = 8;
        int qa = 1016;
        short weightClip = (short) ((Short.MAX_VALUE - qa) / 32);   // 992
        short[] ftWeights = new short[FeatureExtractor.FEATURES_PER_PERSPECTIVE * width];
        java.util.Arrays.fill(ftWeights, weightClip);
        short[] ftBiases = new short[width];
        java.util.Arrays.fill(ftBiases, (short) qa);
        short[] outputWeights = new short[2 * width];
        java.util.Arrays.fill(outputWeights, 0, width, (short) 1);
        java.util.Arrays.fill(outputWeights, width, 2 * width, (short) 2);
        int outputBias = 500;

        NnueNetwork network = NnueNetwork.load(new ByteArrayInputStream(writeNetwork(width, ftWeights, ftBiases,
                outputWeights, outputBias, qa, 128, 400, "edge-uuid", "commit", 0L)));
        NnueEvaluator evaluator = new NnueEvaluator(network);
        coeusyk.game.chess.core.models.Board board = new coeusyk.game.chess.core.models.Board();
        evaluator.reset(board);

        int expectedAccumulator = qa + 32 * weightClip;
        assertTrue(expectedAccumulator <= Short.MAX_VALUE, "the edge case must sit inside int16");
        for (int color : new int[] {coeusyk.game.chess.core.models.Piece.White, coeusyk.game.chess.core.models.Piece.Black}) {
            for (short value : evaluator.currentAccumulator(color)) {
                assertEquals(expectedAccumulator, value, "accumulator wrapped or moved");
            }
        }
        // Every neuron clamps to qa on both sides: us weights 1, them weights 2.
        long sum = (long) qa * width * 1 + (long) qa * width * 2 + outputBias;
        assertEquals((int) (sum * 400 / ((long) qa * 128)), evaluator.evaluate(board));
    }

    private static byte[] writeNetwork(int width, short[] ftWeights, short[] ftBiases, short[] outputWeights,
                                        int outputBias, int qa, int qb, int outputScale,
                                        String uuid, String commit, long createdAt) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeBytes("VNUE");
        out.writeInt(1); // formatVersion
        out.writeInt(1); // architectureId
        out.writeInt(1); // featureSetId
        out.writeInt(width);
        out.writeInt(1); // quantVersion
        out.writeInt(qa);
        out.writeInt(qb);
        out.writeInt(outputScale);
        out.writeUTF(uuid);
        out.writeUTF(commit);
        out.writeLong(createdAt);
        for (short w : ftWeights) {
            out.writeShort(w);
        }
        for (short b : ftBiases) {
            out.writeShort(b);
        }
        for (short w : outputWeights) {
            out.writeShort(w);
        }
        out.writeInt(outputBias);
        return bytes.toByteArray();
    }
}
