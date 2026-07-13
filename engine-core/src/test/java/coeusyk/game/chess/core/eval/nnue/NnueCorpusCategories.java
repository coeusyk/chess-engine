package coeusyk.game.chess.core.eval.nnue;

import coeusyk.game.chess.core.models.Board;

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
}
