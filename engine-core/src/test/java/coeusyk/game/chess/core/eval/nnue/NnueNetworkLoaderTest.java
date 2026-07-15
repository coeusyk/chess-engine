package coeusyk.game.chess.core.eval.nnue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
            ftWeights[i] = (short) (i % 65536 - 32768);
        }
        short[] ftBiases = new short[width];
        for (int i = 0; i < ftBiases.length; i++) {
            ftBiases[i] = (short) (i % 65536 - 32768);
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
