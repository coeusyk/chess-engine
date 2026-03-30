package coeusyk.game.chess.tuner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PositionLoaderTest {

    // Starting position FEN used across several tests
    private static final String START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    // -----------------------------------------------------------------------
    // Format 1 — FEN + [outcome]
    // -----------------------------------------------------------------------

    @Test
    void format1WhiteWin(@TempDir Path tempDir) throws Exception {
        Path file = Files.writeString(tempDir.resolve("data.epd"),
                START_FEN + " [1.0]\n");

        List<LabelledPosition> positions = PositionLoader.load(file);

        assertEquals(1, positions.size());
        assertEquals(1.0, positions.get(0).outcome(), 1e-9);
    }

    @Test
    void format1Draw(@TempDir Path tempDir) throws Exception {
        Path file = Files.writeString(tempDir.resolve("data.epd"),
                START_FEN + " [0.5]\n");

        List<LabelledPosition> positions = PositionLoader.load(file);

        assertEquals(1, positions.size());
        assertEquals(0.5, positions.get(0).outcome(), 1e-9);
    }

    @Test
    void format1BlackWin(@TempDir Path tempDir) throws Exception {
        Path file = Files.writeString(tempDir.resolve("data.epd"),
                START_FEN + " [0.0]\n");

        List<LabelledPosition> positions = PositionLoader.load(file);

        assertEquals(1, positions.size());
        assertEquals(0.0, positions.get(0).outcome(), 1e-9);
    }

    @Test
    void format1AlternateResultStrings(@TempDir Path tempDir) throws Exception {
        String lines = String.join("\n",
                START_FEN + " [1-0]",     // alternative: 1.0
                START_FEN + " [0-1]",     // alternative: 0.0
                START_FEN + " [1/2-1/2]"  // alternative: 0.5
        );
        Path file = Files.writeString(tempDir.resolve("data.epd"), lines);

        List<LabelledPosition> positions = PositionLoader.load(file);

        assertEquals(3, positions.size());
        assertEquals(1.0, positions.get(0).outcome(), 1e-9);
        assertEquals(0.0, positions.get(1).outcome(), 1e-9);
        assertEquals(0.5, positions.get(2).outcome(), 1e-9);
    }

    // -----------------------------------------------------------------------
    // Format 2 — EPD c9 annotation
    // -----------------------------------------------------------------------

    @Test
    void format2WhiteWin(@TempDir Path tempDir) throws Exception {
        Path file = Files.writeString(tempDir.resolve("data.epd"),
                START_FEN + " c9 \"1-0\";\n");

        List<LabelledPosition> positions = PositionLoader.load(file);

        assertEquals(1, positions.size());
        assertEquals(1.0, positions.get(0).outcome(), 1e-9);
    }

    @Test
    void format2Draw(@TempDir Path tempDir) throws Exception {
        Path file = Files.writeString(tempDir.resolve("data.epd"),
                START_FEN + " c9 \"1/2-1/2\";\n");

        List<LabelledPosition> positions = PositionLoader.load(file);

        assertEquals(1, positions.size());
        assertEquals(0.5, positions.get(0).outcome(), 1e-9);
    }

    @Test
    void format2BlackWin(@TempDir Path tempDir) throws Exception {
        Path file = Files.writeString(tempDir.resolve("data.epd"),
                START_FEN + " c9 \"0-1\";\n");

        List<LabelledPosition> positions = PositionLoader.load(file);

        assertEquals(1, positions.size());
        assertEquals(0.0, positions.get(0).outcome(), 1e-9);
    }

    @Test
    void format2FourFieldEpdAppendsHalfFullMove(@TempDir Path tempDir) throws Exception {
        // EPD: only 4 mandatory fields — loader should append "0 1" automatically
        String epd = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq -";
        Path file = Files.writeString(tempDir.resolve("data.epd"),
                epd + " c9 \"1/2-1/2\";\n");

        List<LabelledPosition> positions = PositionLoader.load(file);

        assertEquals(1, positions.size(), "4-field EPD should be loadable after auto-padding");
        assertEquals(0.5, positions.get(0).outcome(), 1e-9);
    }

    // -----------------------------------------------------------------------
    // Malformed / skipped lines
    // -----------------------------------------------------------------------

    @Test
    void blankLinesSkipped(@TempDir Path tempDir) throws Exception {
        Path file = Files.writeString(tempDir.resolve("data.epd"),
                "\n\n" + START_FEN + " [0.5]\n\n");

        List<LabelledPosition> positions = PositionLoader.load(file);

        assertEquals(1, positions.size());
    }

    @Test
    void commentLinesSkipped(@TempDir Path tempDir) throws Exception {
        Path file = Files.writeString(tempDir.resolve("data.epd"),
                "# This is a comment\n" + START_FEN + " [0.5]\n# Another comment\n");

        List<LabelledPosition> positions = PositionLoader.load(file);

        assertEquals(1, positions.size());
    }

    @Test
    void garbledLineSkipped(@TempDir Path tempDir) throws Exception {
        Path file = Files.writeString(tempDir.resolve("data.epd"),
                "this is not a valid fen or epd line\n" + START_FEN + " [1.0]\n");

        List<LabelledPosition> positions = PositionLoader.load(file);

        // Garbled line should be silently skipped; valid line loaded
        assertEquals(1, positions.size());
        assertEquals(1.0, positions.get(0).outcome(), 1e-9);
    }

    @Test
    void mixedFormatsInSameFile(@TempDir Path tempDir) throws Exception {
        String line1 = START_FEN + " [1.0]";
        String line2 = START_FEN + " c9 \"0-1\";";
        Path file = Files.writeString(tempDir.resolve("data.epd"), line1 + "\n" + line2 + "\n");

        List<LabelledPosition> positions = PositionLoader.load(file);

        assertEquals(2, positions.size());
        assertEquals(1.0, positions.get(0).outcome(), 1e-9);
        assertEquals(0.0, positions.get(1).outcome(), 1e-9);
    }

    @Test
    void emptyFileReturnsEmptyList(@TempDir Path tempDir) throws Exception {
        Path file = Files.writeString(tempDir.resolve("data.epd"), "");

        List<LabelledPosition> positions = PositionLoader.load(file);

        assertTrue(positions.isEmpty());
    }

    @Test
    void boardIsNonNullForAllLoadedPositions(@TempDir Path tempDir) throws Exception {
        Path file = Files.writeString(tempDir.resolve("data.epd"),
                START_FEN + " [0.5]\n");

        List<LabelledPosition> positions = PositionLoader.load(file);

        positions.forEach(lp -> assertNotNull(lp.board(), "Board must not be null"));
    }

    // -----------------------------------------------------------------------
    // Streaming load with maxPositions cap
    // -----------------------------------------------------------------------

    @Test
    void loadWithMaxPositionsStopsAtCap(@TempDir Path tempDir) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append(START_FEN).append(" [0.5]\n");
        }
        Path file = Files.writeString(tempDir.resolve("data.epd"), sb.toString());

        List<LabelledPosition> positions = PositionLoader.load(file, 10);

        assertEquals(10, positions.size(), "Load should stop after maxPositions");
    }

    @Test
    void loadWithMaxPositionsLargerThanFileReturnsAll(@TempDir Path tempDir) throws Exception {
        Path file = Files.writeString(tempDir.resolve("data.epd"),
                START_FEN + " [1.0]\n" + START_FEN + " [0.0]\n");

        List<LabelledPosition> positions = PositionLoader.load(file, 1000);

        assertEquals(2, positions.size(),
                "Should return all positions when maxPositions exceeds file size");
    }
}
