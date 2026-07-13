package coeusyk.game.chess.core.eval.nnue;

import coeusyk.game.chess.core.models.Board;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * PR C-4 (issue #188): the six {@code bench/nnue-corpus/} category file names, shared
 * by the generator, the golden-eval test, and the benchmark-throughput harness so the
 * category list only lives in one place.
 */
final class NnueCorpusCategories {

    static final String[] FILE_NAMES = {
            "tactical.epd",
            "quiet.epd",
            "opening.epd",
            "middlegame.epd",
            "endgame.epd",
            "random-legal.epd"
    };

    private NnueCorpusCategories() {
    }

    /** Corpus files store 4-field FENs (repo-wide convention); {@link Board#Board(String)} requires 6. */
    static Board toBoard(String fourFieldFen) {
        return new Board(fourFieldFen + " 0 1");
    }

    /**
     * Extracts 4-field FENs from any {@code .epd}-style file: blank/{@code #}-comment
     * lines skipped, first four whitespace-separated tokens of every other line taken
     * (trailing {@code bm}/{@code id}/{@code c0} annotation fields ignored). PR C-5
     * (issue #189): pulled out of what was three near-identical private copies
     * ({@code NnueCorpusGenerator}, {@code NnueCorpusBenchmarkTest}, and the new
     * oracle-batch report) into one shared reader.
     */
    static List<String> readFens(Path path) throws IOException {
        List<String> fens = new ArrayList<>();
        for (String line : Files.readAllLines(path)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] tokens = trimmed.split("\\s+");
            fens.add(String.join(" ", tokens[0], tokens[1], tokens[2], tokens[3]));
        }
        return fens;
    }
}
