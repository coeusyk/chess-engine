package coeusyk.game.chess.core.eval.nnue;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void loadRejectsBadMagicBytes() {
        byte[] bytes = {'X', 'X', 'X', 'X'};
        assertThrows(IOException.class, () -> NnueNetwork.load(new ByteArrayInputStream(bytes)));
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
