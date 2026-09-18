package coeusyk.game.chess.core.eval;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.movegen.MovesGenerator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Permanent property test for issue #213: eval(pos) must equal eval(colorFlip(pos)) for
 * every reachable legal position (the score is side-to-move relative, and colorFlip swaps
 * both the pieces' colors and the side to move -- see {@link EvaluatorTest#colorFlipFen}),
 * not just the hand-picked fixtures in {@link EvaluatorTest#kingSafetyColorFlipSymmetry()}.
 * Positions are produced by random
 * legal-move walks from the start position (fixed seed -- a failure must reproduce
 * identically on every run). On the first violation, the FEN, its color-flipped
 * counterpart, and a full per-term eval breakdown for both are written to
 * target/mirror-symmetry-failure.txt and the test fails immediately.
 */
class EvalMirrorSymmetryPropertyTest {

    private static final int SAMPLE_COUNT = 100_000;
    // 0-120 plies gives broader game-phase coverage (early/mid/endgame material counts)
    // than a shorter walk, exercising endgame-specific terms (e.g. mop-up) more often.
    private static final int MAX_PLIES = 120;
    private static final long SEED = 213L;

    private final Evaluator evaluator = new Evaluator();

    @Test
    @Tag("regression")
    void evalIsColorSymmetricOverRandomLegalPositions() {
        Random rng = new Random(SEED);

        for (int sample = 0; sample < SAMPLE_COUNT; sample++) {
            Board board = randomLegalPosition(rng);
            String fen = board.toFen();

            // eval() is side-to-move relative and colorFlipFen() swaps both piece colors
            // and side to move, so a color-symmetric evaluator must score both identically.
            String mirroredFen = EvaluatorTest.colorFlipFen(fen);

            int evalOriginal = evaluator.evaluate(board);
            int evalMirrored = evaluator.evaluate(new Board(mirroredFen));

            if (evalOriginal != evalMirrored) {
                String report = buildFailureReport(sample, fen, mirroredFen, evalOriginal, evalMirrored);
                writeFailureReport(report);
                fail(report);
            }
        }
    }

    private Board randomLegalPosition(Random rng) {
        Board board = new Board();
        int targetPlies = rng.nextInt(MAX_PLIES + 1);

        ArrayList<Move> moves = new ArrayList<>();
        for (int ply = 0; ply < targetPlies; ply++) {
            new MovesGenerator(board, moves);
            if (moves.isEmpty()) {
                break; // checkmate or stalemate -- stop the walk here
            }
            Move move = moves.get(rng.nextInt(moves.size()));
            board.makeMove(move);
        }
        return board;
    }

    private String buildFailureReport(int sample, String fen, String mirroredFen,
                                       int evalOriginal, int evalMirrored) {
        return "Mirror-symmetry violation at sample " + sample + " (seed=" + SEED + "):\n"
                + "  FEN:          " + fen + "\n"
                + "  Mirrored FEN: " + mirroredFen + "\n"
                + "  eval(FEN)          = " + evalOriginal + "\n"
                + "  eval(mirrored FEN) = " + evalMirrored + " (expected " + evalOriginal + ")\n\n"
                + "--- explainEval(FEN) ---\n" + evaluator.explainEval(new Board(fen)) + "\n\n"
                + "--- explainEval(mirrored FEN) ---\n" + evaluator.explainEval(new Board(mirroredFen)) + "\n";
    }

    private void writeFailureReport(String report) {
        try {
            Path out = Path.of("target", "mirror-symmetry-failure.txt");
            Files.createDirectories(out.getParent());
            Files.writeString(out, report);
        } catch (IOException e) {
            // Diagnostics are best-effort -- the assertion failure message already carries
            // the full report, so a write failure here must not mask the real test failure.
        }
    }
}
