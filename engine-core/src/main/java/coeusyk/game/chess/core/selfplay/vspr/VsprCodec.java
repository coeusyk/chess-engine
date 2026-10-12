package coeusyk.game.chess.core.selfplay.vspr;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.zip.CRC32;

/**
 * Encoder/decoder for the VSPR wire format, exactly as specified by
 * {@code docs/architecture/research/DR-220-vspr-wire-format.md} (V1, frozen). Operates purely on
 * the semantic record types in this package and a plain {@link InputStream}/{@link OutputStream}
 * -- no dependency on {@code GameLoop}, {@code Searcher}, UCI, the filesystem, self-play policy,
 * or the trainer. Every bound below is cited from DR-220 section 7; nothing here is invented.
 *
 * <p>Every {@code u32} wire field is read via {@link Integer#toUnsignedLong(int)} into a
 * {@code long} and bound-checked against DR-220's (smaller) documented maximum <em>before</em>
 * any array/list sized by it is allocated -- a raw {@code (int)} cast of an unsigned wire value
 * could otherwise become a negative Java allocation size. {@code u8}/{@code u16} fields use
 * {@link DataInputStream#readUnsignedByte()}/{@link DataInputStream#readUnsignedShort()}, which
 * already return a non-negative {@code int} incapable of overflowing on their own.
 */
public final class VsprCodec {

    private VsprCodec() {
    }

    // ---- DR-220 section 4: magic/version ----
    private static final byte[] MAGIC = {'V', 'S', 'P', 'R'};
    private static final int SUPPORTED_FORMAT_VERSION = 1;

    // ---- DR-220 section 7: bounds (decoder-allocation safety ceilings, not engine semantics) ----
    private static final long MAX_PLAYED_MOVE_COUNT = 65_536L;
    private static final int MAX_CANDIDATE_COUNT = 218;
    private static final int MAX_PV_LENGTH = 128;
    private static final int MAX_MECHANISM_NAME_BYTES = 32;
    private static final int MAX_FEN_BYTES = 100;
    private static final int MAX_NETWORK_UUID_BYTES = 128;
    private static final int NETWORK_SHA256_BYTES = 32;
    private static final int MAX_ENGINE_BUILD_ID_BYTES = 64;
    private static final int MAX_NETWORK_PATH_BYTES = 512;
    private static final int MAX_OPAQUE_PAYLOAD_BYTES = 4096;
    private static final long MAX_FRAME_LENGTH = 64L * 1024 * 1024;

    // =====================================================================================
    // Public entry points
    // =====================================================================================

    /** Decodes a full VSPR stream: the header, then every frame until clean end-of-file. */
    public static VsprFile read(InputStream rawIn) throws IOException {
        DataInputStream in = new DataInputStream(rawIn);
        VsprHeader header = readHeader(in);
        List<GameFrame> frames = new ArrayList<>();
        Optional<GameFrame> next;
        while ((next = readFrame(in, header.candidatesPersisted())).isPresent()) {
            frames.add(next.get());
        }
        return new VsprFile(header, frames);
    }

    /** Encodes a full VSPR stream: the header, then every frame, in order. */
    public static void write(VsprFile file, OutputStream rawOut) throws IOException {
        DataOutputStream out = new DataOutputStream(rawOut);
        writeHeader(file.header(), out);
        boolean candidatesPersisted = file.header().candidatesPersisted();
        for (GameFrame frame : file.frames()) {
            writeFrame(frame, candidatesPersisted, out);
        }
        out.flush();
    }

    // =====================================================================================
    // Header
    // =====================================================================================

    /** Reads the fixed, 120-byte V1 header. Throws on bad magic, unsupported version, or a
     * stream too short to contain a complete header -- there is no valid file identity to
     * recover a partial read from (DR-220 section 9). */
    public static VsprHeader readHeader(DataInputStream in) throws IOException {
        try {
            byte[] magic = new byte[MAGIC.length];
            in.readFully(magic);
            if (!java.util.Arrays.equals(magic, MAGIC)) {
                throw new VsprFormatException(VsprFormatException.Reason.BAD_MAGIC,
                        "not a VSPR file (bad magic bytes)");
            }
            int formatVersion = in.readInt();
            if (formatVersion != SUPPORTED_FORMAT_VERSION) {
                throw new VsprFormatException(VsprFormatException.Reason.UNSUPPORTED_VERSION,
                        "unsupported VSPR formatVersion " + formatVersion
                                + " (this codec only supports " + SUPPORTED_FORMAT_VERSION + ")");
            }
            byte[] runId = new byte[16];
            in.readFully(runId);
            long createdAtEpochSeconds = in.readLong();
            String generatorNetworkUuid = readBoundedString(in, MAX_NETWORK_UUID_BYTES,
                    "generatorNetworkUuid");
            byte[] generatorNetworkSha256 = new byte[NETWORK_SHA256_BYTES];
            in.readFully(generatorNetworkSha256);
            String engineBuildId = readBoundedString8(in, MAX_ENGINE_BUILD_ID_BYTES,
                    "engineBuildId");
            String generatorNetworkPath = readBoundedString(in, MAX_NETWORK_PATH_BYTES,
                    "generatorNetworkPath");
            int maxPlies = in.readInt();
            SearchBudgetKind searchBudgetKind = readEnum(in, SearchBudgetKind.class);
            long searchBudgetValue = in.readLong();
            boolean resetSearchStateBetweenGames = readBool(in);
            boolean candidatesPersisted = readBool(in);
            OpaqueConfig adjudicationConfig = readOptionalOpaqueConfig(in);
            OpaqueConfig diversityConfig = readOptionalOpaqueConfig(in);
            return new VsprHeader(formatVersion, runId, createdAtEpochSeconds,
                    generatorNetworkUuid, generatorNetworkSha256, engineBuildId,
                    generatorNetworkPath, maxPlies, searchBudgetKind, searchBudgetValue,
                    resetSearchStateBetweenGames, candidatesPersisted, adjudicationConfig,
                    diversityConfig);
        } catch (EOFException e) {
            throw new VsprFormatException(VsprFormatException.Reason.TRUNCATED,
                    "truncated VSPR header");
        }
    }

    public static void writeHeader(VsprHeader h, DataOutputStream out) throws IOException {
        out.write(MAGIC);
        out.writeInt(h.formatVersion());
        writeFixed(out, h.runId(), 16, "runId");
        out.writeLong(h.createdAtEpochSeconds());
        writeBoundedString(out, h.generatorNetworkUuid(), MAX_NETWORK_UUID_BYTES,
                "generatorNetworkUuid");
        writeFixed(out, h.generatorNetworkSha256(), NETWORK_SHA256_BYTES,
                "generatorNetworkSha256");
        writeBoundedString8(out, h.engineBuildId(), MAX_ENGINE_BUILD_ID_BYTES, "engineBuildId");
        writeBoundedString(out, h.generatorNetworkPath(), MAX_NETWORK_PATH_BYTES,
                "generatorNetworkPath");
        out.writeInt(h.maxPlies());
        out.writeByte(h.searchBudgetKind().ordinal());
        out.writeLong(h.searchBudgetValue());
        out.writeByte(h.resetSearchStateBetweenGames() ? 1 : 0);
        out.writeByte(h.candidatesPersisted() ? 1 : 0);
        writeOptionalOpaqueConfig(out, h.adjudicationConfig());
        writeOptionalOpaqueConfig(out, h.diversityConfig());
    }

    // =====================================================================================
    // Frames
    // =====================================================================================

    /**
     * Reads exactly one {@link GameFrame}. Returns {@link Optional#empty()} only when the
     * stream is cleanly exhausted before any byte of the next frame's {@code frameLength} field
     * is read -- that is the sole condition meaning "no more games." Any other premature
     * end-of-stream, including one where only some of {@code frameLength}'s own bytes were
     * available, is a truncated frame and throws {@link VsprFormatException} with
     * {@link VsprFormatException.Reason#TRUNCATED}: a file whose only content is one incomplete
     * frame must never decode as a valid, zero-game file (DR-220 section 9/11). This method
     * performs no resume/recovery of any kind -- that is a separate, explicitly-invoked writer
     * concern DR-220 deliberately keeps out of normal decoding.
     */
    public static Optional<GameFrame> readFrame(DataInputStream in, boolean candidatesPersisted)
            throws IOException {
        int firstByte = in.read();
        if (firstByte == -1) {
            return Optional.empty();
        }
        try {
            int b1 = in.read();
            int b2 = in.read();
            int b3 = in.read();
            if (b1 == -1 || b2 == -1 || b3 == -1) {
                throw new EOFException();
            }
            long frameLength = Integer.toUnsignedLong(
                    (firstByte << 24) | (b1 << 16) | (b2 << 8) | b3);
            if (frameLength > MAX_FRAME_LENGTH) {
                throw new VsprFormatException(VsprFormatException.Reason.BOUND_VIOLATION,
                        "frameLength " + frameLength + " exceeds max " + MAX_FRAME_LENGTH);
            }
            byte[] body = new byte[(int) frameLength];
            in.readFully(body);
            byte[] crcBytes = new byte[4];
            in.readFully(crcBytes);
            long declaredCrc = Integer.toUnsignedLong(
                    ((crcBytes[0] & 0xFF) << 24) | ((crcBytes[1] & 0xFF) << 16)
                            | ((crcBytes[2] & 0xFF) << 8) | (crcBytes[3] & 0xFF));
            CRC32 crc32 = new CRC32();
            crc32.update(body);
            if (crc32.getValue() != declaredCrc) {
                throw new VsprFormatException(VsprFormatException.Reason.CRC_MISMATCH,
                        "frame CRC-32 mismatch: declared " + declaredCrc + ", computed "
                                + crc32.getValue());
            }
            return Optional.of(decodeFrameBody(body, candidatesPersisted));
        } catch (EOFException e) {
            throw new VsprFormatException(VsprFormatException.Reason.TRUNCATED,
                    "truncated VSPR game frame");
        }
    }

    private static GameFrame decodeFrameBody(byte[] body, boolean candidatesPersisted)
            throws IOException {
        DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(body));
        long gameId = in.readLong();
        OptionalLong gameSeed = readOptionalLong(in);
        GameOutcome outcome = readEnum(in, GameOutcome.class);
        TerminationReason reason = readEnum(in, TerminationReason.class);
        OutcomePerspective perspective = readEnum(in, OutcomePerspective.class);
        validatePairing(outcome, reason);

        long playedMoveCount = readU32(in);
        if (playedMoveCount > MAX_PLAYED_MOVE_COUNT) {
            throw new VsprFormatException(VsprFormatException.Reason.BOUND_VIOLATION,
                    "playedMoveCount " + playedMoveCount + " exceeds max " + MAX_PLAYED_MOVE_COUNT);
        }
        List<PlayedMoveDecision> playedMoves = new ArrayList<>((int) playedMoveCount);
        for (long i = 0; i < playedMoveCount; i++) {
            playedMoves.add(readPlayedMoveDecision(in));
        }

        long sampleCount = readU32(in);
        if (sampleCount > playedMoveCount) {
            throw new VsprFormatException(VsprFormatException.Reason.BOUND_VIOLATION,
                    "sampleCount " + sampleCount + " exceeds playedMoveCount " + playedMoveCount);
        }
        List<TrainingSample> samples = new ArrayList<>((int) sampleCount);
        for (long i = 0; i < sampleCount; i++) {
            TrainingSample sample = readTrainingSample(in);
            if (sample.ply() >= playedMoveCount) {
                throw new VsprFormatException(VsprFormatException.Reason.STRUCTURAL,
                        "sample ply " + sample.ply() + " >= playedMoveCount " + playedMoveCount);
            }
            samples.add(sample);
        }

        List<CandidateSet> candidateSets = new ArrayList<>();
        if (candidatesPersisted) {
            long candidateSetCount = readU32(in);
            if (candidateSetCount > playedMoveCount) {
                throw new VsprFormatException(VsprFormatException.Reason.BOUND_VIOLATION,
                        "candidateSetCount " + candidateSetCount + " exceeds playedMoveCount "
                                + playedMoveCount);
            }
            for (long i = 0; i < candidateSetCount; i++) {
                CandidateSet set = readCandidateSet(in);
                if (set.ply() >= playedMoveCount) {
                    throw new VsprFormatException(VsprFormatException.Reason.STRUCTURAL,
                            "candidate set ply " + set.ply() + " >= playedMoveCount "
                                    + playedMoveCount);
                }
                candidateSets.add(set);
            }
        }

        if (in.available() != 0) {
            throw new VsprFormatException(VsprFormatException.Reason.STRUCTURAL,
                    "frame body has " + in.available() + " trailing byte(s) beyond its declared "
                            + "structure");
        }

        return new GameFrame(gameId, gameSeed, outcome, reason, perspective, playedMoves,
                samples, candidateSets);
    }

    /**
     * @param candidatesPersisted must equal the owning file's
     *     {@code header.candidatesPersisted()} -- this is what a decoder keys off to know
     *     whether to expect the trailing candidate-set section at all (DR-220 section 4), so it
     *     cannot be inferred from whether this particular frame happens to have any candidate
     *     sets. Throws if {@code false} but {@code frame.candidateSets()} is non-empty: that
     *     combination cannot be represented on the wire and is a caller bug, not a case to
     *     silently drop data for.
     */
    public static void writeFrame(GameFrame frame, boolean candidatesPersisted,
            DataOutputStream out) throws IOException {
        if (!candidatesPersisted && !frame.candidateSets().isEmpty()) {
            throw new IllegalArgumentException(
                    "frame has candidate sets but the file header does not set "
                            + "candidatesPersisted -- they would be silently dropped on the wire");
        }
        ByteArrayOutputStream bodyBuf = new ByteArrayOutputStream();
        DataOutputStream body = new DataOutputStream(bodyBuf);
        body.writeLong(frame.gameId());
        writeOptionalLong(body, frame.gameSeed());
        body.writeByte(frame.gameOutcome().ordinal());
        body.writeByte(frame.terminationReason().ordinal());
        body.writeByte(frame.outcomePerspective().ordinal());

        body.writeInt(frame.playedMoves().size());
        for (PlayedMoveDecision move : frame.playedMoves()) {
            writePlayedMoveDecision(body, move);
        }

        body.writeInt(frame.samples().size());
        for (TrainingSample sample : frame.samples()) {
            writeTrainingSample(body, sample);
        }

        if (candidatesPersisted) {
            body.writeInt(frame.candidateSets().size());
            for (CandidateSet set : frame.candidateSets()) {
                writeCandidateSet(body, set);
            }
        }

        byte[] bodyBytes = bodyBuf.toByteArray();
        CRC32 crc32 = new CRC32();
        crc32.update(bodyBytes);

        out.writeInt(bodyBytes.length);
        out.write(bodyBytes);
        out.writeInt((int) crc32.getValue());
    }

    // =====================================================================================
    // PlayedMoveDecision / TrainingSample / CandidateSet / SearchCandidate
    // =====================================================================================

    private static PlayedMoveDecision readPlayedMoveDecision(DataInputStream in)
            throws IOException {
        int move = in.readUnsignedShort();
        validateMoveFlag(move);
        SelectionMechanismKind kind = readEnum(in, SelectionMechanismKind.class);
        String mechanismName = null;
        if (kind == SelectionMechanismKind.NAMED) {
            mechanismName = readBoundedString8(in, MAX_MECHANISM_NAME_BYTES, "mechanismName");
        }
        OptionalLong selectionSeed = readOptionalLong(in);
        return new PlayedMoveDecision(move, kind, mechanismName, selectionSeed);
    }

    private static void writePlayedMoveDecision(DataOutputStream out, PlayedMoveDecision move)
            throws IOException {
        out.writeShort(move.move());
        out.writeByte(move.selectionMechanismKind().ordinal());
        if (move.selectionMechanismKind() == SelectionMechanismKind.NAMED) {
            writeBoundedString8(out, move.mechanismName(), MAX_MECHANISM_NAME_BYTES,
                    "mechanismName");
        }
        writeOptionalLong(out, move.selectionSeed());
    }

    private static TrainingSample readTrainingSample(DataInputStream in) throws IOException {
        long ply = readU32(in);
        String fen = readAsciiBoundedString8(in, MAX_FEN_BYTES, "fen");
        boolean onTrajectory = readBool(in);
        ScoreKind scoreKind = readEnum(in, ScoreKind.class);
        int score = in.readInt();
        SearchBudgetKind budgetKind = readEnum(in, SearchBudgetKind.class);
        long budgetValue = in.readLong();
        return new TrainingSample(ply, fen, onTrajectory, scoreKind, score, budgetKind,
                budgetValue);
    }

    private static void writeTrainingSample(DataOutputStream out, TrainingSample sample)
            throws IOException {
        out.writeInt((int) sample.ply());
        writeBoundedString8(out, sample.fen(), MAX_FEN_BYTES, "fen");
        out.writeByte(sample.onTrajectory() ? 1 : 0);
        out.writeByte(sample.evalScoreKind().ordinal());
        out.writeInt(sample.evalScore());
        out.writeByte(sample.searchBudgetKind().ordinal());
        out.writeLong(sample.searchBudgetValue());
    }

    private static CandidateSet readCandidateSet(DataInputStream in) throws IOException {
        long ply = readU32(in);
        int depth = in.readInt();
        int candidateCount = in.readUnsignedShort();
        if (candidateCount > MAX_CANDIDATE_COUNT) {
            throw new VsprFormatException(VsprFormatException.Reason.BOUND_VIOLATION,
                    "candidateCount " + candidateCount + " exceeds max " + MAX_CANDIDATE_COUNT);
        }
        List<SearchCandidate> candidates = new ArrayList<>(candidateCount);
        for (int i = 0; i < candidateCount; i++) {
            candidates.add(readSearchCandidate(in));
        }
        return new CandidateSet(ply, depth, candidates);
    }

    private static void writeCandidateSet(DataOutputStream out, CandidateSet set)
            throws IOException {
        out.writeInt((int) set.ply());
        out.writeInt(set.depth());
        out.writeShort(set.candidates().size());
        for (SearchCandidate c : set.candidates()) {
            writeSearchCandidate(out, c);
        }
    }

    private static SearchCandidate readSearchCandidate(DataInputStream in) throws IOException {
        int move = in.readUnsignedShort();
        validateMoveFlag(move);
        int rank = in.readUnsignedShort();
        ScoreKind scoreKind = readEnum(in, ScoreKind.class);
        int score = in.readInt();
        int pvLength = in.readUnsignedShort();
        if (pvLength > MAX_PV_LENGTH) {
            throw new VsprFormatException(VsprFormatException.Reason.BOUND_VIOLATION,
                    "pvLength " + pvLength + " exceeds max " + MAX_PV_LENGTH);
        }
        List<Integer> pv = new ArrayList<>(pvLength);
        for (int i = 0; i < pvLength; i++) {
            int pvMove = in.readUnsignedShort();
            validateMoveFlag(pvMove);
            pv.add(pvMove);
        }
        boolean complete = readBool(in);
        return new SearchCandidate(move, rank, scoreKind, score, pv, complete);
    }

    private static void writeSearchCandidate(DataOutputStream out, SearchCandidate c)
            throws IOException {
        out.writeShort(c.move());
        out.writeShort(c.rank());
        out.writeByte(c.scoreKind().ordinal());
        out.writeInt(c.score());
        out.writeShort(c.pv().size());
        for (int pvMove : c.pv()) {
            out.writeShort(pvMove);
        }
        out.writeByte(c.complete() ? 1 : 0);
    }

    // =====================================================================================
    // Primitive helpers -- every u32 read goes through readU32 (unsigned, into a long) so a
    // corrupt/hostile length field can never become a negative Java array size via direct cast.
    // =====================================================================================

    private static long readU32(DataInputStream in) throws IOException {
        return Integer.toUnsignedLong(in.readInt());
    }

    private static boolean readBool(DataInputStream in) throws IOException {
        int b = in.readUnsignedByte();
        if (b != 0 && b != 1) {
            throw new VsprFormatException(VsprFormatException.Reason.INVALID_ENUM,
                    "invalid bool byte " + b + " (must be 0 or 1)");
        }
        return b == 1;
    }

    private static <E extends Enum<E>> E readEnum(DataInputStream in, Class<E> type)
            throws IOException {
        int value = in.readUnsignedByte();
        E[] constants = type.getEnumConstants();
        if (value >= constants.length) {
            throw new VsprFormatException(VsprFormatException.Reason.INVALID_ENUM,
                    "invalid " + type.getSimpleName() + " tag " + value);
        }
        return constants[value];
    }

    private static void validateMoveFlag(int packedMove) throws IOException {
        int flag = (packedMove >> 12) & 0xF;
        if (flag > 8) {
            throw new VsprFormatException(VsprFormatException.Reason.INVALID_ENUM,
                    "invalid packed Move flag nibble " + flag + " (must be 0-8)");
        }
    }

    private static void validatePairing(GameOutcome outcome, TerminationReason reason)
            throws IOException {
        boolean valid = switch (reason) {
            case CHECKMATE -> outcome == GameOutcome.WHITE_WIN || outcome == GameOutcome.BLACK_WIN;
            case STALEMATE, THREEFOLD_REPETITION, FIFTY_MOVE_RULE, INSUFFICIENT_MATERIAL ->
                    outcome == GameOutcome.DRAW;
            case ADJUDICATED_SCORE -> outcome == GameOutcome.WHITE_WIN
                    || outcome == GameOutcome.BLACK_WIN || outcome == GameOutcome.DRAW;
            case MOVE_CAP, SEARCH_ABORT_OR_FAILURE -> outcome == GameOutcome.UNRESOLVED;
        };
        if (!valid) {
            throw new VsprFormatException(VsprFormatException.Reason.INVALID_PAIRING,
                    "invalid (gameOutcome=" + outcome + ", terminationReason=" + reason
                            + ") pairing (DR-220 section 6)");
        }
    }

    private static OptionalLong readOptionalLong(DataInputStream in) throws IOException {
        boolean present = readBool(in);
        return present ? OptionalLong.of(in.readLong()) : OptionalLong.empty();
    }

    private static void writeOptionalLong(DataOutputStream out, OptionalLong value)
            throws IOException {
        out.writeByte(value.isPresent() ? 1 : 0);
        if (value.isPresent()) {
            out.writeLong(value.getAsLong());
        }
    }

    private static OpaqueConfig readOptionalOpaqueConfig(DataInputStream in) throws IOException {
        if (!readBool(in)) {
            return null;
        }
        int schemaId = in.readUnsignedByte();
        int length = in.readUnsignedShort();
        if (length > MAX_OPAQUE_PAYLOAD_BYTES) {
            throw new VsprFormatException(VsprFormatException.Reason.BOUND_VIOLATION,
                    "opaqueConfig payload length " + length + " exceeds max "
                            + MAX_OPAQUE_PAYLOAD_BYTES);
        }
        byte[] payload = new byte[length];
        in.readFully(payload);
        return new OpaqueConfig(schemaId, payload);
    }

    private static void writeOptionalOpaqueConfig(DataOutputStream out, OpaqueConfig config)
            throws IOException {
        out.writeByte(config == null ? 0 : 1);
        if (config != null) {
            out.writeByte(config.schemaId());
            out.writeShort(config.payload().length);
            out.write(config.payload());
        }
    }

    /** {@code bstr16}: {@code u16} length prefix + UTF-8 bytes, bound-checked before decoding. */
    private static String readBoundedString(DataInputStream in, int maxBytes, String field)
            throws IOException {
        int length = in.readUnsignedShort();
        if (length > maxBytes) {
            throw new VsprFormatException(VsprFormatException.Reason.BOUND_VIOLATION,
                    field + " length " + length + " exceeds max " + maxBytes);
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeBoundedString(DataOutputStream out, String value, int maxBytes,
            String field) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) {
            throw new IllegalArgumentException(field + " length " + bytes.length
                    + " exceeds max " + maxBytes);
        }
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    /** {@code bstr8}: {@code u8} length prefix + ASCII bytes, bound-checked before decoding. */
    private static String readBoundedString8(DataInputStream in, int maxBytes, String field)
            throws IOException {
        int length = in.readUnsignedByte();
        if (length > maxBytes) {
            throw new VsprFormatException(VsprFormatException.Reason.BOUND_VIOLATION,
                    field + " length " + length + " exceeds max " + maxBytes);
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeBoundedString8(DataOutputStream out, String value, int maxBytes,
            String field) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) {
            throw new IllegalArgumentException(field + " length " + bytes.length
                    + " exceeds max " + maxBytes);
        }
        out.writeByte(bytes.length);
        out.write(bytes);
    }

    /** {@code fen}: {@code bstr8}, ASCII-only per DR-220 section 6 -- rejects any byte >= 0x80. */
    private static String readAsciiBoundedString8(DataInputStream in, int maxBytes, String field)
            throws IOException {
        int length = in.readUnsignedByte();
        if (length > maxBytes) {
            throw new VsprFormatException(VsprFormatException.Reason.BOUND_VIOLATION,
                    field + " length " + length + " exceeds max " + maxBytes);
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        for (byte b : bytes) {
            if ((b & 0xFF) >= 0x80) {
                throw new VsprFormatException(VsprFormatException.Reason.STRUCTURAL,
                        field + " contains a non-ASCII byte");
            }
        }
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private static void writeFixed(DataOutputStream out, byte[] value, int exactLength,
            String field) throws IOException {
        if (value.length != exactLength) {
            throw new IllegalArgumentException(field + " must be exactly " + exactLength
                    + " bytes, was " + value.length);
        }
        out.write(value);
    }
}
