package coeusyk.game.chess.uci;

import coeusyk.game.chess.uci.UciApplicationIntegrationTest.UciHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #259: the shared transposition table holds scores from whichever evaluator searched last, and
 * its key does not say which. Switching the effective evaluator (Classical to NNUE, NNUE to Classical,
 * or to another network) must clear it, after the previous search's workers have drained, or the next
 * search answers from the previous evaluator's scores.
 */
class UciEvaluatorSwitchTest {

    private static final Duration LONG = Duration.ofSeconds(30);
    private static final String CLEARED = "info string transposition table cleared: evaluator changed";
    private static final Pattern SCORE_CP = Pattern.compile(" score cp (-?\\d+) ");

    private UciHarness harness;

    @AfterEach
    void cleanup() throws Exception {
        if (harness != null) harness.close();
    }

    private static String fixture() {
        Path path = Path.of("..", "engine-core", "src", "test", "resources", "nnue", "d6-export-fixture.nnue")
                .toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.exists(path), "NNUE fixture not found at " + path);
        return path.toString();
    }

    private String searchToDepth(UciHarness h, int depth) throws Exception {
        h.send("position startpos");
        h.send("go depth " + depth);
        assertNotNull(h.awaitLine(l -> l.startsWith("bestmove "), LONG), "no bestmove");
        h.barrier();
        // the last "info depth <depth> ... score cp <n>" line of this search
        List<String> history = h.history();
        for (int i = history.size() - 1; i >= 0; i--) {
            String line = history.get(i);
            if (line.startsWith("info depth " + depth + " ")) {
                Matcher m = SCORE_CP.matcher(line);
                if (m.find()) return m.group(1);
            }
        }
        throw new AssertionError("no depth-" + depth + " score line in " + history);
    }

    private long cleared(UciHarness h) {
        return h.history().stream().filter(CLEARED::equals).count();
    }

    @Test
    void searchAfterSwitchingToNnueAnswersFromNnueNotFromTheClassicalEntries() throws Exception {
        UciHarness fresh = UciHarness.start();
        String coldNnue;
        try {
            fresh.send("setoption name EvalType value NNUE");
            fresh.send("setoption name EvalFile value " + fixture());
            coldNnue = searchToDepth(fresh, 5);
        } finally {
            fresh.close();
        }

        harness = UciHarness.start();
        String classical = searchToDepth(harness, 5);
        harness.send("setoption name EvalType value NNUE");
        harness.send("setoption name EvalFile value " + fixture());
        String afterSwitch = searchToDepth(harness, 5);

        assertTrue(!classical.equals(coldNnue), "the two evaluators must disagree for this test to mean anything");
        assertEquals(coldNnue, afterSwitch, "the NNUE search reused scores the classical search left in the table");
        assertEquals(1, cleared(harness));
    }

    @Test
    void theTableIsClearedOnlyWhenTheEffectiveEvaluatorChanges() throws Exception {
        harness = UciHarness.start();
        searchToDepth(harness, 3);
        searchToDepth(harness, 3);
        assertEquals(0, cleared(harness), "same evaluator, nothing to clear");

        // NNUE requested but no EvalFile: the engine falls back to Classical, which is what it already used.
        harness.send("setoption name EvalType value NNUE");
        searchToDepth(harness, 3);
        assertEquals(0, cleared(harness), "a fallback to the same Classical evaluator must not clear the table");

        harness.send("setoption name EvalFile value " + fixture());
        searchToDepth(harness, 3);
        assertEquals(1, cleared(harness));

        searchToDepth(harness, 3);
        assertEquals(1, cleared(harness), "same network again, nothing to clear");

        harness.send("setoption name EvalType value Classical");
        searchToDepth(harness, 3);
        assertEquals(2, cleared(harness), "NNUE back to Classical is also a change");
    }

    @Test
    void switchingWithHelpersRunningDrainsThemBeforeTheTableIsCleared() throws Exception {
        Assumptions.assumeTrue(Runtime.getRuntime().availableProcessors() >= 2);
        harness = UciHarness.startWithDiagnostics();
        harness.send("setoption name Threads value 2");
        String nnue = fixture();
        for (int round = 0; round < 6; round++) {
            harness.send("setoption name EvalType value " + (round % 2 == 0 ? "NNUE" : "Classical"));
            if (round % 2 == 0) harness.send("setoption name EvalFile value " + nnue);
            harness.send("position startpos");
            harness.send("go depth 4");
            assertNotNull(harness.awaitLine(l -> l.startsWith("bestmove "), LONG), "round " + round);
        }
        harness.send("setoption name Hash value 16"); // joins the last search
        harness.barrier();

        assertEquals(6, harness.bestmoveCount());
        assertEquals(6, cleared(harness), "every one of the six rounds switched evaluator");
        assertNotNull(harness.awaitDiagnostic(
                l -> harness.diagnosticLines().stream().filter(x -> x.contains("event=drained")).count() >= 6, LONG),
                "not every search drained");
        long clean = harness.diagnosticLines().stream()
                .filter(l -> l.contains("event=drained") && l.contains("helper_pending=0")).count();
        assertEquals(6, clean, "a search ended with helpers still pending");
    }
}
