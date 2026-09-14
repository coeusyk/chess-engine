package coeusyk.game.chess.core.selfplay.vspr;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Locates the repository root by walking upward to the nearest ancestor containing {@code .git}
 * -- same strategy as {@code eval.nnue.RepoPaths} (package-private there too, so duplicated
 * rather than shared) and the Python side's {@code feature_spec.repo_root()}.
 */
final class RepoPaths {

    private RepoPaths() {
    }

    static Path repoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.exists(current.resolve(".git"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException(
                    "no .git ancestor found starting from " + Path.of("").toAbsolutePath());
        }
        return current;
    }
}
