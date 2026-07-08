package coeusyk.game.chess.core.eval.nnue;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Piece;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link FeatureExtractor}'s index formula against a committed FEN corpus —
 * the acceptance criterion added to the Phase B plan: "the Java FeatureExtractor and
 * the Python FeatureEncoder produce identical feature indices for the same FEN
 * corpus." The Python {@code FeatureEncoder} doesn't exist yet (separate trainer
 * project, PRD §"Trainer Architecture" — TBD at implementation), so this test can
 * only pin the Java side today. The hardcoded starting-position indices below were
 * derived by hand against the documented formula (see {@link FeatureExtractor}) and
 * are exactly the values a future Python encoder must reproduce byte-for-byte; the
 * corpus itself (the FEN strings) is the other half of that future cross-language
 * check.
 */
class FeatureIndexParityTest {

    // Hand-derived from FeatureExtractor's formula for the starting position, against
    // Board's actual square numbering (index 0 = a8, per Board.getChessSquare: rank =
    // 8 - square/8 — top-down FEN reading order, not the a1=0 convention). Symmetric
    // starting position -> white's and black's perspectives see numerically identical
    // active-feature sets, which is itself a strong correctness cross-check (mirrors
    // CLAUDE.md's mirror-symmetry convention for the classical evaluator).
    private static final int[] STARTPOS_EXPECTED = {
            48, 49, 50, 51, 52, 53, 54, 55,
            121, 126, 186, 189, 248, 255, 315, 380,
            392, 393, 394, 395, 396, 397, 398, 399,
            449, 454, 514, 517, 576, 583, 643, 708
    };

    private static final String[] CORPUS = {
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
            "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
            "r4rk1/3qppbp/6p1/2p1n3/2B1P3/2P5/P2Q1PPP/R4RK1 b - - 0 20",
    };

    @Test
    void startingPositionMatchesHandDerivedIndices() {
        Board board = new Board();
        int[] white = FeatureExtractor.activeFeatureIndices(board, Piece.White);
        int[] black = FeatureExtractor.activeFeatureIndices(board, Piece.Black);

        assertArrayEquals(STARTPOS_EXPECTED, white, "white-perspective indices");
        assertArrayEquals(STARTPOS_EXPECTED, black, "black-perspective indices");
        assertArrayEquals(white, black,
                "symmetric starting position must look numerically identical from both perspectives");
    }

    @Test
    void everyCorpusPositionProducesWellFormedIndices() {
        for (String fen : CORPUS) {
            Board board = new Board(fen);
            int pieceCount = Long.bitCount(board.getAllOccupancy());

            for (int perspective : new int[]{Piece.White, Piece.Black}) {
                int[] indices = FeatureExtractor.activeFeatureIndices(board, perspective);
                assertEquals(pieceCount, indices.length,
                        fen + " perspective " + perspective + ": one feature per piece on the board");

                Set<Integer> seen = new HashSet<>();
                for (int index : indices) {
                    assertTrue(index >= 0 && index < FeatureExtractor.FEATURES_PER_PERSPECTIVE,
                            fen + ": index " + index + " out of [0, 768) range");
                    assertTrue(seen.add(index), fen + ": duplicate feature index " + index);
                }
            }
        }
    }
}
