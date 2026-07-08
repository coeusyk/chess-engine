package coeusyk.game.chess.core.eval.nnue;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Immutable NNUE network weights: (768 -&gt; hiddenWidth) x2 -&gt; 1, feature-transformer
 * weights shared between the two perspectives (PRD §3 Network Specification).
 * Safe to share by reference across every {@code NnueEvaluator} instance/thread —
 * nothing here is mutated after {@link #load}.
 *
 * <p>Binary format (big-endian, this engine's own versioned layout — the contract
 * a future Python exporter must produce):
 * <pre>
 * u8[4]  magic = "VNUE"
 * i32    formatVersion
 * i32    architectureId
 * i32    featureSetId
 * i32    hiddenWidth
 * i32    quantVersion
 * i32    qa, qb, outputScale
 * utf    networkUuid           (DataOutput#writeUTF-compatible)
 * utf    trainerCommit
 * i64    createdAtEpochSeconds
 * i16[FeatureExtractor.FEATURES_PER_PERSPECTIVE * hiddenWidth]  ftWeights (row-major per feature)
 * i16[hiddenWidth]                                              ftBiases
 * i16[2 * hiddenWidth]                                          outputWeights (perspective-major: "us" then "them")
 * i32                                                            outputBias
 * </pre>
 */
public final class NnueNetwork {

    private static final byte[] MAGIC = {'V', 'N', 'U', 'E'};
    private static final int FORMAT_VERSION = 1;

    private final int hiddenWidth;
    private final short[] ftWeights;
    private final short[] ftBiases;
    private final short[] outputWeights;
    private final int outputBias;
    private final int qa;
    private final int qb;
    private final int outputScale;
    private final String networkUuid;
    private final String trainerCommit;
    private final long createdAtEpochSeconds;

    public NnueNetwork(int hiddenWidth, short[] ftWeights, short[] ftBiases, short[] outputWeights,
                        int outputBias, int qa, int qb, int outputScale,
                        String networkUuid, String trainerCommit, long createdAtEpochSeconds) {
        int expectedFtWeights = FeatureExtractor.FEATURES_PER_PERSPECTIVE * hiddenWidth;
        if (ftWeights.length != expectedFtWeights) {
            throw new IllegalArgumentException(
                    "ftWeights length " + ftWeights.length + " != " + expectedFtWeights);
        }
        if (ftBiases.length != hiddenWidth) {
            throw new IllegalArgumentException("ftBiases length " + ftBiases.length + " != " + hiddenWidth);
        }
        if (outputWeights.length != 2 * hiddenWidth) {
            throw new IllegalArgumentException(
                    "outputWeights length " + outputWeights.length + " != " + 2 * hiddenWidth);
        }
        this.hiddenWidth = hiddenWidth;
        this.ftWeights = ftWeights;
        this.ftBiases = ftBiases;
        this.outputWeights = outputWeights;
        this.outputBias = outputBias;
        this.qa = qa;
        this.qb = qb;
        this.outputScale = outputScale;
        this.networkUuid = networkUuid;
        this.trainerCommit = trainerCommit;
        this.createdAtEpochSeconds = createdAtEpochSeconds;
    }

    public static NnueNetwork load(Path path) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(path))) {
            return load(in);
        }
    }

    public static NnueNetwork load(InputStream rawIn) throws IOException {
        DataInputStream in = new DataInputStream(rawIn);
        byte[] magic = new byte[4];
        in.readFully(magic);
        if (magic[0] != MAGIC[0] || magic[1] != MAGIC[1] || magic[2] != MAGIC[2] || magic[3] != MAGIC[3]) {
            throw new IOException("not a .nnue file (bad magic bytes)");
        }
        int formatVersion = in.readInt();
        if (formatVersion != FORMAT_VERSION) {
            throw new IOException("unsupported .nnue format version " + formatVersion
                    + " (expected " + FORMAT_VERSION + ")");
        }
        in.readInt(); // architectureId — reserved for a future topology change; unused until then
        in.readInt(); // featureSetId — reserved for a future feature-set change; unused until then
        int hiddenWidth = in.readInt();
        in.readInt(); // quantVersion — reserved for a future quantization scheme change
        int qa = in.readInt();
        int qb = in.readInt();
        int outputScale = in.readInt();
        String networkUuid = in.readUTF();
        String trainerCommit = in.readUTF();
        long createdAtEpochSeconds = in.readLong();

        short[] ftWeights = readShorts(in, FeatureExtractor.FEATURES_PER_PERSPECTIVE * hiddenWidth);
        short[] ftBiases = readShorts(in, hiddenWidth);
        short[] outputWeights = readShorts(in, 2 * hiddenWidth);
        int outputBias = in.readInt();

        return new NnueNetwork(hiddenWidth, ftWeights, ftBiases, outputWeights, outputBias,
                qa, qb, outputScale, networkUuid, trainerCommit, createdAtEpochSeconds);
    }

    private static short[] readShorts(DataInputStream in, int count) throws IOException {
        short[] values = new short[count];
        for (int i = 0; i < count; i++) {
            values[i] = in.readShort();
        }
        return values;
    }

    public int hiddenWidth() {
        return hiddenWidth;
    }

    /** Row-major: feature {@code f}'s weights occupy {@code [f*hiddenWidth, (f+1)*hiddenWidth)}. */
    short[] ftWeights() {
        return ftWeights;
    }

    short[] ftBiases() {
        return ftBiases;
    }

    /** Perspective-major: {@code [0, hiddenWidth)} for "us", {@code [hiddenWidth, 2*hiddenWidth)} for "them". */
    short[] outputWeights() {
        return outputWeights;
    }

    int outputBias() {
        return outputBias;
    }

    int qa() {
        return qa;
    }

    int qb() {
        return qb;
    }

    int outputScale() {
        return outputScale;
    }

    public String networkUuid() {
        return networkUuid;
    }

    public String trainerCommit() {
        return trainerCommit;
    }

    public long createdAtEpochSeconds() {
        return createdAtEpochSeconds;
    }
}
