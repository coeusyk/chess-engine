package coeusyk.game.chess.core.selfplay;

import coeusyk.game.chess.core.eval.nnue.NnueEvaluator;
import coeusyk.game.chess.core.eval.nnue.NnueNetwork;
import coeusyk.game.chess.core.search.Searcher;
import coeusyk.game.chess.core.selfplay.vspr.GameFrame;
import coeusyk.game.chess.core.selfplay.vspr.OpaqueConfig;
import coeusyk.game.chess.core.selfplay.vspr.SearchBudgetKind;
import coeusyk.game.chess.core.selfplay.vspr.VsprCodec;
import coeusyk.game.chess.core.selfplay.vspr.VsprFile;
import coeusyk.game.chess.core.selfplay.vspr.VsprHeader;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Thin argument/configuration layer, per #221 section 4G: no search/game logic lives here beyond
 * orchestrating {@link EligibilitySmoke}, {@link GameLoop}, and the existing Java
 * {@link VsprCodec} (DR-E12 section 3: the Java generator uses the Java VSPR codec directly --
 * this class never invokes Python {@code trainer.vspr} at runtime). Never selects a generator
 * automatically -- every identity/config field below is a required CLI argument (section 10:
 * "no directory scanning for automatic candidate selection").
 */
public final class SelfPlayCli {

    private SelfPlayCli() {
    }

    public static void main(String[] args) {
        try {
            int exitCode = run(args);
            if (exitCode != 0) {
                System.exit(exitCode);
            }
        } catch (Exception e) {
            System.err.println("selfplay generation failed: " + e);
            System.exit(1);
        }
    }

    static int run(String[] args) throws IOException {
        CliArgs parsed = CliArgs.parse(args);
        GeneratorConfig config = parsed.toGeneratorConfig();

        EligibilitySmoke.SmokeResult smoke = EligibilitySmoke.run(config);
        String evidenceJoined = String.join(" | ", smoke.evidence());
        if (!smoke.passed()) {
            System.err.println("ELIGIBILITY SMOKE FAILED:");
            smoke.evidence().forEach(line -> System.err.println("  " + line));
            // No decision record and no VSPR file are written on a failed smoke run -- an
            // incomplete/failed attempt must never be presentable as a successful pilot
            // (#221 section 12 / section 15's closure gate).
            return 3;
        }
        System.out.println("ELIGIBILITY SMOKE PASSED:");
        smoke.evidence().forEach(line -> System.out.println("  " + line));

        NnueNetwork network = NnueNetwork.load(config.networkPath());

        UUID runIdUuid = UUID.randomUUID();
        byte[] runId = uuidToBytes(runIdUuid);

        OpaqueConfig diversityConfig = parsed.diversity() == null ? null : parsed.diversity().toOpaqueConfig();

        VsprHeader header = new VsprHeader(
                1,
                runId,
                System.currentTimeMillis() / 1000L,
                config.expectedNetworkUuid(),
                hexToBytes(config.expectedNetworkSha256()),
                config.engineBuildId(),
                config.networkPath().toString(),
                config.maxPlies(),
                config.searchBudgetKind(),
                config.searchBudgetValue(),
                true,
                false,
                (OpaqueConfig) null,
                diversityConfig);

        // Local diagnostics artifact (#222 section 8) -- generation-time-only, never VSPR fields.
        // Only written when a stochastic selector is actually in use; absent for the
        // BestMoveSelector control path, matching DR-E9's "absent means no diversity" convention.
        BufferedWriter diagnosticsWriter = null;
        Path diagnosticsPath = null;
        if (parsed.diversity() != null) {
            diagnosticsPath = config.outputVsprPath()
                    .resolveSibling(config.outputVsprPath().getFileName() + ".diversity-diagnostics.csv");
            diagnosticsWriter = Files.newBufferedWriter(diagnosticsPath);
            diagnosticsWriter.write("gameId,ply,candidateCount,chosenRank,rank1Kind,rank1Value,"
                    + "chosenKind,chosenValue,cpLossFromRank1,selectionWeight,selectionProbability\n");
        }

        List<GameFrame> frames = new ArrayList<>();
        int gamesAttempted = 0;
        int totalSamples = 0;

        try {
            for (long gameId = 0; gameId < config.maxGames(); gameId++) {
                if (config.maxPositions() != null && totalSamples >= config.maxPositions()) {
                    break; // secondary stop -- checked at game boundaries only, never mid-game
                }
                gamesAttempted++;
                Searcher searcher = new Searcher();
                searcher.setEvaluatorStrategy(new NnueEvaluator(network));
                MoveSelector selector;
                if (parsed.diversity() != null) {
                    selector = parsed.diversity().toSelector();
                } else if (parsed.controlMultiPv() != null) {
                    selector = new BestMoveSelector(parsed.controlMultiPv());
                } else {
                    selector = new BestMoveSelector();
                }
                BufferedWriter sink = diagnosticsWriter;
                GameLoop gameLoop = sink == null
                        ? new GameLoop(config, searcher, selector)
                        : new GameLoop(config, searcher, selector, entry -> writeDiagnosticsRow(sink, entry));
                long gameSeed = config.seed() + gameId;

                // Any SelfPlayGenerationException here propagates straight out of run(): no catch,
                // no "skip this game and continue," no partial VSPR/decision-record write below --
                // a hard correctness failure aborts the whole pilot (#221 section 12).
                GameFrame frame = gameLoop.playGame(gameId, gameSeed);
                frames.add(frame);
                totalSamples += frame.samples().size();
            }
        } finally {
            if (diagnosticsWriter != null) {
                diagnosticsWriter.close();
            }
        }
        if (diagnosticsPath != null) {
            System.out.println("Diversity diagnostics: " + diagnosticsPath);
        }

        VsprFile file = new VsprFile(header, List.copyOf(frames));

        Path tmpVspr = config.outputVsprPath().resolveSibling(config.outputVsprPath().getFileName() + ".tmp");
        writeVsprAtomic(file, tmpVspr, config.outputVsprPath());
        String vsprSha256 = sha256Hex(Files.readAllBytes(config.outputVsprPath()));

        DecisionRecord record = new DecisionRecord(
                "bootstrap",
                config.expectedNetworkUuid(),
                config.expectedNetworkSha256(),
                config.engineBuildId(),
                config.searchBudgetKind().name(),
                config.searchBudgetValue(),
                config.maxPlies(),
                config.seed(),
                config.maxGames(),
                config.maxPositions(),
                true,
                evidenceJoined,
                gamesAttempted,
                frames.size(),
                0,
                (int) frames.stream()
                        .filter(f -> f.gameOutcome() == coeusyk.game.chess.core.selfplay.vspr.GameOutcome.UNRESOLVED)
                        .count(),
                config.outputVsprPath().toString(),
                vsprSha256,
                HexFormat.of().formatHex(runId));

        Path tmpRecord = config.outputDecisionRecordPath()
                .resolveSibling(config.outputDecisionRecordPath().getFileName() + ".tmp");
        Files.writeString(tmpRecord, record.toJson());
        Files.move(tmpRecord, config.outputDecisionRecordPath(), StandardCopyOption.ATOMIC_MOVE);

        System.out.println("Pilot complete: " + frames.size() + " games, " + totalSamples + " samples");
        System.out.println("VSPR: " + config.outputVsprPath() + " (sha256=" + vsprSha256 + ")");
        System.out.println("Decision record: " + config.outputDecisionRecordPath());
        return 0;
    }

    private static void writeVsprAtomic(VsprFile file, Path tmp, Path dest) throws IOException {
        if (Files.exists(dest)) {
            throw new IOException("refusing to overwrite existing VSPR output at " + dest);
        }
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(tmp))) {
            VsprCodec.write(file, out);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw new SelfPlayGenerationException(
                    SelfPlayGenerationException.Reason.VSPR_ENCODE_FAILURE, "failed to encode VSPR output", e);
        }
        Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE);
    }

    private static byte[] uuidToBytes(UUID uuid) {
        byte[] bytes = new byte[16];
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) (msb >>> (8 * (7 - i)));
            bytes[8 + i] = (byte) (lsb >>> (8 * (7 - i)));
        }
        return bytes;
    }

    private static byte[] hexToBytes(String hex) {
        return HexFormat.of().parseHex(hex);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static void writeDiagnosticsRow(BufferedWriter writer, SelectionDiagnosticsEntry entry) {
        try {
            writer.write(String.format(Locale.ROOT, "%d,%d,%d,%d,%s,%d,%s,%d,%s,%s,%s%n",
                    entry.gameId(), entry.ply(), entry.candidateCount(), entry.chosenRank(),
                    entry.rank1Kind(), entry.rank1Value(), entry.chosenKind(), entry.chosenValue(),
                    entry.cpLossFromRank1() == null ? "" : entry.cpLossFromRank1(),
                    entry.selectionWeight() == null ? "" : entry.selectionWeight(),
                    entry.selectionProbability() == null ? "" : entry.selectionProbability()));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write diversity diagnostics row", e);
        }
    }

    /** #222 section 4/9: the one preregistered diversity policy's explicit knobs -- absent means
     * no diversity (DR-E9's own convention), never a hidden default. Schema ID 1 in
     * {@link VsprHeader#diversityConfig()} is this exact 16-byte layout: maxRank (int32),
     * cpLossBoundCentipawns (int32), temperature (float64), all big-endian. */
    record DiversityArgs(int maxRank, int cpLossBoundCentipawns, double temperature) {

        static final int SCHEMA_ID = 1;

        MoveSelector toSelector() {
            return new SeededDiversitySelector(maxRank, cpLossBoundCentipawns, temperature);
        }

        OpaqueConfig toOpaqueConfig() {
            ByteBuffer buf = ByteBuffer.allocate(16);
            buf.putInt(maxRank).putInt(cpLossBoundCentipawns).putDouble(temperature);
            return new OpaqueConfig(SCHEMA_ID, buf.array());
        }
    }

    record CliArgs(
            Path networkPath,
            String expectedNetworkSha256,
            String expectedNetworkUuid,
            String engineBuildId,
            long searchDepth,
            int maxPlies,
            int maxGames,
            Integer maxPositions,
            long seed,
            Path outputVsprPath,
            Path outputDecisionRecordPath,
            DiversityArgs diversity,
            Integer controlMultiPv) {

        GeneratorConfig toGeneratorConfig() {
            return new GeneratorConfig(
                    networkPath, expectedNetworkSha256, expectedNetworkUuid, engineBuildId,
                    SearchBudgetKind.DEPTH, searchDepth, maxPlies, maxGames, maxPositions, seed,
                    outputVsprPath, outputDecisionRecordPath);
        }

        static CliArgs parse(String[] args) {
            java.util.Map<String, String> opts = new java.util.HashMap<>();
            for (int i = 0; i < args.length - 1; i += 2) {
                opts.put(args[i], args[i + 1]);
            }
            String maxPositionsRaw = opts.get("--max-positions");

            String maxRankRaw = opts.get("--diversity-max-rank");
            String cpLossBoundRaw = opts.get("--diversity-cp-loss-bound");
            String temperatureRaw = opts.get("--diversity-temperature");
            int presentCount = (maxRankRaw != null ? 1 : 0) + (cpLossBoundRaw != null ? 1 : 0)
                    + (temperatureRaw != null ? 1 : 0);
            if (presentCount != 0 && presentCount != 3) {
                throw new IllegalArgumentException(
                        "--diversity-max-rank, --diversity-cp-loss-bound and --diversity-temperature "
                                + "must all be given together, or none of them (BestMoveSelector control)");
            }
            DiversityArgs diversity = presentCount == 0 ? null : new DiversityArgs(
                    Integer.parseInt(maxRankRaw), Integer.parseInt(cpLossBoundRaw),
                    Double.parseDouble(temperatureRaw));

            // E-15 (#223): the matched-control-arm width, valid only when diversity mode is NOT
            // active -- there is exactly one selector per run, and its identity (BestMoveSelector
            // vs SeededDiversitySelector) must never be inferred implicitly from which other flags
            // happen to be present. No flag at all -- #221's original behavior (multiPV=1),
            // unchanged.
            String controlMultiPvRaw = opts.get("--control-multipv");
            if (controlMultiPvRaw != null && diversity != null) {
                throw new IllegalArgumentException(
                        "--control-multipv cannot be combined with --diversity-* flags -- a run is "
                                + "either the BestMoveSelector control (optionally widened via "
                                + "--control-multipv) or the SeededDiversitySelector treatment, never both");
            }
            Integer controlMultiPv = controlMultiPvRaw == null ? null : Integer.parseInt(controlMultiPvRaw);

            return new CliArgs(
                    Path.of(require(opts, "--network")),
                    require(opts, "--network-sha256"),
                    require(opts, "--network-uuid"),
                    require(opts, "--engine-build-id"),
                    Long.parseLong(require(opts, "--search-depth")),
                    Integer.parseInt(require(opts, "--max-plies")),
                    Integer.parseInt(require(opts, "--max-games")),
                    maxPositionsRaw == null ? null : Integer.parseInt(maxPositionsRaw),
                    Long.parseLong(require(opts, "--seed")),
                    Path.of(require(opts, "--output-vspr")),
                    Path.of(require(opts, "--output-decision-record")),
                    diversity,
                    controlMultiPv);
        }

        private static String require(java.util.Map<String, String> opts, String key) {
            String value = opts.get(key);
            if (value == null) {
                throw new IllegalArgumentException("missing required argument " + key);
            }
            return value;
        }
    }
}
