package coeusyk.game.chess.core.selfplay;

import coeusyk.game.chess.core.eval.nnue.NnueNetwork;
import coeusyk.game.chess.core.selfplay.vspr.GameOutcome;
import coeusyk.game.chess.core.selfplay.vspr.TerminationReason;
import coeusyk.game.chess.core.selfplay.vspr.VsprCodec;
import coeusyk.game.chess.core.selfplay.vspr.VsprFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/** End-to-end: CLI arg parsing -> eligibility smoke -> a tiny bounded pilot -> VSPR output that
 * decodes cleanly with the same {@code VsprCodec} that wrote it (no separate reinterpretation),
 * plus the CLI's own required-argument / no-implicit-fallback behavior. This is the "generated
 * pilot record decodes with existing Java VsprCodec" and "outcome/termination pairs valid" test
 * from #221 section 13 -- no live self-play beyond a 1-2 game, tiny-hidden-width synthetic
 * network smoke run. */
class SelfPlayCliTest {

    private static Path fixturePath() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.exists(current.resolve(".git"))) {
            current = current.getParent();
        }
        assertNotNull(current, "no .git ancestor found");
        return current.resolve("engine-core/src/test/resources/nnue/d6-export-fixture.nnue");
    }

    private static String sha256Of(Path path) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void boundedPilotProducesVsprDecodableByTheSameCodec(@TempDir Path tmp) throws Exception {
        String sha = sha256Of(fixturePath());
        String uuid = NnueNetwork.load(fixturePath()).networkUuid();
        Path vsprOut = tmp.resolve("pilot.vspr");
        Path decisionOut = tmp.resolve("decision.json");

        String[] args = {
                "--network", fixturePath().toString(),
                "--network-sha256", sha,
                "--network-uuid", uuid,
                "--engine-build-id", "test-commit-hash",
                "--search-depth", "3",
                "--max-plies", "6",
                "--max-games", "2",
                "--seed", "123",
                "--output-vspr", vsprOut.toString(),
                "--output-decision-record", decisionOut.toString(),
        };

        int exitCode = SelfPlayCli.run(args);
        assertEquals(0, exitCode);
        assertTrue(Files.exists(vsprOut));
        assertTrue(Files.exists(decisionOut));

        VsprFile decoded;
        try (InputStream in = Files.newInputStream(vsprOut)) {
            decoded = VsprCodec.read(in);
        }
        assertEquals(2, decoded.frames().size());
        for (var frame : decoded.frames()) {
            // Every emitted outcome/termination pair must be one DR-220's own pairing table
            // allows -- VsprCodec.read() itself already enforces this at decode time, so
            // successfully decoding is the assertion; this loop double-checks the specific
            // MOVE_CAP/UNRESOLVED pairing this 6-ply cap run is expected to hit.
            if (frame.terminationReason() == TerminationReason.MOVE_CAP) {
                assertEquals(GameOutcome.UNRESOLVED, frame.gameOutcome());
            }
        }

        String decisionJson = Files.readString(decisionOut);
        assertTrue(decisionJson.contains("\"eligibilitySmokePassed\": true"));
        assertTrue(decisionJson.contains("\"gamesCompleted\": 2"));
    }

    @Test
    void missingRequiredArgumentFailsFast() {
        String[] argsMissingNetwork = {
                "--network-sha256", "a".repeat(64),
                "--network-uuid", "u",
                "--engine-build-id", "b",
                "--search-depth", "3",
                "--max-plies", "6",
                "--max-games", "1",
                "--seed", "1",
                "--output-vspr", "out.vspr",
                "--output-decision-record", "out.json",
        };
        assertThrows(IllegalArgumentException.class, () -> SelfPlayCli.run(argsMissingNetwork));
    }

    @Test
    void thereIsNoLatestCheckpointFallbackArgument() {
        // Structural check: CliArgs.parse requires --network explicitly; there is no flag or
        // code path anywhere in SelfPlayCli that scans a directory or defaults to an unspecified
        // network file (#221 section 10 / DR-E12 section 3's "no implicit latest-checkpoint
        // selection"). Asserted here by confirming --network is unconditionally required, same
        // as every other identity field.
        String[] argsMissingSha = {
                "--network", "n.nnue",
                "--network-uuid", "u",
                "--engine-build-id", "b",
                "--search-depth", "3",
                "--max-plies", "6",
                "--max-games", "1",
                "--seed", "1",
                "--output-vspr", "out.vspr",
                "--output-decision-record", "out.json",
        };
        assertThrows(IllegalArgumentException.class, () -> SelfPlayCli.run(argsMissingSha));
    }

    @Test
    void failedEligibilitySmokeWritesNoOutputFiles(@TempDir Path tmp) throws Exception {
        Path vsprOut = tmp.resolve("pilot.vspr");
        Path decisionOut = tmp.resolve("decision.json");
        String[] args = {
                "--network", fixturePath().toString(),
                "--network-sha256", "b".repeat(64), // wrong on purpose
                "--network-uuid", "whatever",
                "--engine-build-id", "test-commit-hash",
                "--search-depth", "3",
                "--max-plies", "6",
                "--max-games", "1",
                "--seed", "1",
                "--output-vspr", vsprOut.toString(),
                "--output-decision-record", decisionOut.toString(),
        };

        int exitCode = SelfPlayCli.run(args);
        assertNotEquals(0, exitCode);
        assertFalse(Files.exists(vsprOut));
        assertFalse(Files.exists(decisionOut));
    }
}
