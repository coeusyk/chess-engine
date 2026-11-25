package coeusyk.game.chess.core.eval.nnue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #265: pins the exact int16 output of {@link NnueEvaluator} for each committed Python-exported
 * fixture on every corpus FEN. The trainer's {@code tests/quantization/test_java_parity.py} compares
 * its Python quantized inference with the same CSV files, so a mismatch on either side fails CI and
 * Python and Java cannot drift apart unnoticed. Exact equality, not a tolerance.
 */
@Tag("nnue-golden")
class NnueFixtureGoldenEvalTest {

    @Test
    void everyFixtureMatchesItsGoldenCsvExactly() throws IOException {
        for (NnueFixtureGoldenSupport.Fixture fixture : NnueFixtureGoldenSupport.FIXTURES) {
            NnueEvaluator evaluator = new NnueEvaluator(NnueNetwork.load(NnueFixtureGoldenSupport.fixturePath(fixture)));
            List<String> lines = Files.readAllLines(NnueFixtureGoldenSupport.goldenPath(fixture));
            List<String> fens = NnueFixtureGoldenSupport.corpusFens();

            // header lines: a '#' comment and "fen,eval"
            assertEquals("fen,eval", lines.get(1), fixture.goldenCsv());
            assertEquals(fens.size(), lines.size() - 2, "golden row count for " + fixture.goldenCsv());

            for (int i = 0; i < fens.size(); i++) {
                String row = lines.get(i + 2);
                int lastComma = row.lastIndexOf(',');
                assertEquals(fens.get(i), row.substring(0, lastComma), "FEN order in " + fixture.goldenCsv());
                int expected = Integer.parseInt(row.substring(lastComma + 1));
                assertEquals(expected, NnueFixtureGoldenSupport.evaluate(evaluator, fens.get(i)),
                        fixture.nnueFile() + " golden mismatch for FEN: " + fens.get(i));
            }
        }
    }

    /**
     * Synthetic compatibility evidence, not a real-checkpoint measurement: the QA 1016 / QB 128 fixture is a
     * seeded random network. The golden comparison above is within this one scale, Java against the CSV the
     * Python reference also matches. No test compares its scores with a QA 127 network's.
     */
    @Test
    void qa1016FixtureLoadsWithItsDocumentedHeader() throws IOException {
        NnueNetwork network = NnueNetwork.load(NnueFixtureGoldenSupport.fixturePath(NnueFixtureGoldenSupport.FIXTURES.get(2)));
        assertEquals(64, network.hiddenWidth());
        assertEquals(1016, network.qa());
        assertEquals(128, network.qb());
        assertEquals(400, network.outputScale());
        assertEquals(-123, network.outputBias());
        assertEquals("bccce5bc-0538-4c33-8350-0f15e5270496", network.networkUuid());
        assertEquals("eeeeeeee", network.trainerCommit());
        assertEquals(1795583643L, network.createdAtEpochSeconds());
    }

    @Test
    void qa1016FixtureAccumulatorsClipAtBothBoundaries() throws IOException {
        NnueNetwork network = NnueNetwork.load(NnueFixtureGoldenSupport.fixturePath(NnueFixtureGoldenSupport.FIXTURES.get(2)));
        NnueEvaluator evaluator = new NnueEvaluator(network);
        int above = 0;
        int below = 0;
        for (String fen : NnueFixtureGoldenSupport.corpusFens()) {
            coeusyk.game.chess.core.models.Board board = NnueCorpusCategories.toBoard(fen);
            evaluator.reset(board);
            for (int color : new int[] {coeusyk.game.chess.core.models.Piece.White, coeusyk.game.chess.core.models.Piece.Black}) {
                for (short value : evaluator.currentAccumulator(color)) {
                    if (value > network.qa()) {
                        above++;
                    }
                    if (value < 0) {
                        below++;
                    }
                }
            }
        }
        assertTrue(above > 0, "no accumulator value exceeds qa, the upper clamp is never reached");
        assertTrue(below > 0, "no accumulator value is below zero, the lower clamp is never reached");
    }

    @Test
    void wideFixtureLoadsWithItsDocumentedHeader() throws IOException {
        NnueNetwork network = NnueNetwork.load(NnueFixtureGoldenSupport.fixturePath(NnueFixtureGoldenSupport.FIXTURES.get(1)));
        assertEquals(32, network.hiddenWidth());
        assertEquals(127, network.qa());
        assertEquals(64, network.qb());
        assertEquals(400, network.outputScale());
        assertEquals(-123, network.outputBias());
        assertEquals("cdfa4c5e-3774-4f07-8e4a-1c7350229b1c", network.networkUuid());
    }
}
