package coeusyk.game.chess.core.eval.nnue;

import coeusyk.game.chess.core.models.Piece;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies {@link FeatureExtractor}'s hardcoded constants and formula agree with the
 * versioned Feature Specification ({@code docs/architecture/feature-spec/v1.json},
 * {@code docs/architecture/NNUE_TRAINER_ARCHITECTURE.md} Section 4.1) -- the shallow
 * structural half of Java/Python parity (the deep half is
 * {@link FeatureIndexParityTest}'s golden-value corpus check). {@code
 * FeatureExtractor} itself never reads this file (hot-path, allocation-free, per
 * CLAUDE.md Section 3); this test is the sole point where Java is checked against it,
 * using {@link MinimalJson} -- a purpose-built reader, not a general library.
 */
class FeatureSpecConformanceTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadSpec() throws IOException {
        Path specPath = RepoPaths.repoRoot().resolve("docs/architecture/feature-spec/v1.json");
        String text = Files.readString(specPath);
        return (Map<String, Object>) MinimalJson.parse(text);
    }

    @Test
    void constantsMatchTheVersionedFeatureSpecification() throws IOException {
        Map<String, Object> spec = loadSpec();

        assertEquals(1L, spec.get("spec_version"));
        assertEquals("plain-768", spec.get("feature_set_id"));

        @SuppressWarnings("unchecked")
        List<Object> pieceTypes = (List<Object>) spec.get("piece_types");
        assertEquals(FeatureExtractor.PIECE_TYPES, pieceTypes.size(),
                "spec's piece_types length must match FeatureExtractor.PIECE_TYPES");

        @SuppressWarnings("unchecked")
        List<Object> colors = (List<Object>) spec.get("colors");
        assertEquals(2, colors.size(), "spec must declare exactly two colors");

        assertEquals((long) FeatureExtractor.SQUARES, (long) (Long) spec.get("squares"));
        assertEquals((long) FeatureExtractor.FEATURES_PER_PERSPECTIVE,
                (long) (Long) spec.get("features_per_perspective"));
    }

    @Test
    void featureIndexFormulaAgreesWithSpecDerivedConstants() throws IOException {
        Map<String, Object> spec = loadSpec();
        @SuppressWarnings("unchecked")
        int pieceTypes = ((List<Object>) spec.get("piece_types")).size();
        int squares = ((Long) spec.get("squares")).intValue();

        for (int perspective : new int[]{Piece.White, Piece.Black}) {
            for (int pieceColor : new int[]{Piece.White, Piece.Black}) {
                for (int pieceType = Piece.Pawn; pieceType <= Piece.King; pieceType++) {
                    for (int square : new int[]{0, 7, 27, 56, 63}) {
                        int expected = specDerivedIndex(perspective, pieceColor, pieceType, square,
                                pieceTypes, squares);
                        int actual = FeatureExtractor.featureIndex(perspective, pieceColor, pieceType, square);
                        assertEquals(expected, actual,
                                "perspective=" + perspective + " pieceColor=" + pieceColor
                                        + " pieceType=" + pieceType + " square=" + square);
                    }
                }
            }
        }
    }

    /**
     * Recomputes the index using only spec-derived constants and the same rule
     * shapes documented in {@code v1.json}'s {@code relative_color_rule} /
     * {@code relative_square_rule} / {@code piece_type_index_rule} /
     * {@code index_formula} fields -- an independent structural re-derivation, not a
     * call into {@link FeatureExtractor}.
     */
    private static int specDerivedIndex(int perspectiveColor, int pieceColor, int pieceType, int square,
                                         int pieceTypes, int squares) {
        int relativeColor = (pieceColor == perspectiveColor) ? 0 : 1;
        int relativeSquare = (perspectiveColor == Piece.White) ? square : (square ^ 56);
        int pieceTypeIndex = pieceType - 1;
        return relativeColor * (pieceTypes * squares) + pieceTypeIndex * squares + relativeSquare;
    }
}
