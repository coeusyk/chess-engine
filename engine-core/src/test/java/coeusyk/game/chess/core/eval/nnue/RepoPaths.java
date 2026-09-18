package coeusyk.game.chess.core.eval.nnue;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared test-scope helper: locates the repository root by walking upward to the
 * nearest ancestor containing {@code .git} -- the same path-resolution strategy the
 * Python side uses ({@code feature_spec.py}'s {@code repo_root()}), documented in
 * {@code docs/architecture/NNUE_TRAINER_ARCHITECTURE.md} Section 4.1 "Path
 * resolution". Avoids the working-directory fragility of hardcoded relative-segment
 * counts across Maven's own invocation conventions.
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
