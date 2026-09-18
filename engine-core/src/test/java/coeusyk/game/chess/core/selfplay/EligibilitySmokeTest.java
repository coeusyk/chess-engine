package coeusyk.game.chess.core.selfplay;

import coeusyk.game.chess.core.eval.nnue.NnueNetwork;
import coeusyk.game.chess.core.selfplay.vspr.SearchBudgetKind;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class EligibilitySmokeTest {

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
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static GeneratorConfig configWith(String sha256, String uuid) {
        return new GeneratorConfig(
                fixturePath(), sha256, uuid, "test-build",
                SearchBudgetKind.DEPTH, 4, 500, 1, null, 1L,
                Path.of("unused.vspr"), Path.of("unused.json"));
    }

    @Test
    void correctShaAndUuidPasses() throws IOException {
        String realSha = sha256Of(fixturePath());
        String realUuid = NnueNetwork.load(fixturePath()).networkUuid();

        EligibilitySmoke.SmokeResult result = EligibilitySmoke.run(configWith(realSha, realUuid));

        assertTrue(result.passed(), String.join("\n", result.evidence()));
        assertFalse(result.evidence().isEmpty());
    }

    @Test
    void shaMismatchFailsBeforeGeneration() throws IOException {
        String realUuid = NnueNetwork.load(fixturePath()).networkUuid();
        String wrongSha = "b".repeat(64);

        EligibilitySmoke.SmokeResult result = EligibilitySmoke.run(configWith(wrongSha, realUuid));

        assertFalse(result.passed());
        assertTrue(result.evidence().stream().anyMatch(e -> e.contains("SHA-256 mismatch")));
    }

    @Test
    void uuidMismatchFailsBeforeGeneration() throws IOException {
        String realSha = sha256Of(fixturePath());

        EligibilitySmoke.SmokeResult result = EligibilitySmoke.run(configWith(realSha, "not-the-real-uuid"));

        assertFalse(result.passed());
        assertTrue(result.evidence().stream().anyMatch(e -> e.contains("UUID mismatch")));
    }
}
