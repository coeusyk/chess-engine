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
    private static final Pattern SCORE_AND_NODES = Pattern.compile(" score cp (-?\\d+) nodes (\\d+) ");

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
        // The last "info depth <depth> ... score cp <n> nodes <m>" line of this search. Node count is part of the
        // answer on purpose: a table full of another evaluator's entries can leave the final score unchanged by
        // luck of the search windows while still changing which nodes are searched; a single-thread search from
        // a cleared table is deterministic, so score and nodes must both match a cold search.
        List<String> history = h.history();
        for (int i = history.size() - 1; i >= 0; i--) {
            String line = history.get(i);
            if (line.startsWith("info depth " + depth + " ")) {
                Matcher m = SCORE_AND_NODES.matcher(line);
                if (m.find()) return m.group(1) + " cp, " + m.group(2) + " nodes";
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
        assertEquals(coldNnue, afterSwitch, "the NNUE search reused entries the classical search left in the table");
        assertEquals(1, cleared(harness));
    }

    /** A copy of the wide fixture whose embedded UUID has been replaced by the D-6 fixture's: a different network that claims the same identity. */
    private static String fixtureWithBorrowedUuid(Path dir) throws Exception {
        Path root = Path.of("..", "engine-core", "src", "test", "resources", "nnue").toAbsolutePath().normalize();
        byte[] wide = Files.readAllBytes(root.resolve("parity-w32-fixture.nnue"));
        String wideUuid = "cdfa4c5e-3774-4f07-8e4a-1c7350229b1c";
        String narrowUuid = "1ac3222e-0765-4c93-b3b3-5b2718e2d92b";
        String text = new String(wide, java.nio.charset.StandardCharsets.ISO_8859_1);
        int at = text.indexOf(wideUuid);
        Assumptions.assumeTrue(at > 0, "UUID not found in the wide fixture");
        byte[] patched = wide.clone();
        System.arraycopy(narrowUuid.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), 0, patched, at, narrowUuid.length());
        Path out = dir.resolve("borrowed-uuid.nnue");
        Files.write(out, patched);
        return out.toString();
    }

    @Test
    void twoNetworksCarryingTheSameEmbeddedUuidAreStillTwoEvaluators(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        String borrowed = fixtureWithBorrowedUuid(dir);

        UciHarness fresh = UciHarness.start();
        String coldBorrowed;
        try {
            fresh.send("setoption name EvalType value NNUE");
            fresh.send("setoption name EvalFile value " + borrowed);
            coldBorrowed = searchToDepth(fresh, 5);
        } finally {
            fresh.close();
        }

        harness = UciHarness.start();
        harness.send("setoption name EvalType value NNUE");
        harness.send("setoption name EvalFile value " + fixture());
        String narrow = searchToDepth(harness, 5);
        harness.send("setoption name EvalFile value " + borrowed);
        String afterSwitch = searchToDepth(harness, 5);

        assertTrue(!narrow.equals(coldBorrowed), "the two networks must disagree for this test to mean anything");
        assertEquals(coldBorrowed, afterSwitch, "a different network with the same embedded UUID reused the first one's entries");
        assertEquals(2, cleared(harness), "Classical to the first network, then a different file with the same UUID");
    }

    @Test
    void switchingEvaluatorWhileAnInfiniteSearchHasHelpersRunningDrainsThemFirst() throws Exception {
        Assumptions.assumeTrue(Runtime.getRuntime().availableProcessors() >= 2);
        harness = UciHarness.startWithDiagnostics();
        harness.send("setoption name Threads value 2");
        harness.send("position startpos");
        harness.send("go infinite");
        assertNotNull(harness.awaitLine(l -> l.startsWith("info depth 4 "), LONG));

        // the next commands arrive while the first search and its helper are still running
        harness.sendBatch("setoption name EvalType value NNUE", "setoption name EvalFile value " + fixture(),
                "position startpos", "go depth 3");
        assertNotNull(harness.awaitLine(l -> l.startsWith("info string transposition table cleared"), LONG));
        harness.send("setoption name Hash value 16"); // joins the second search
        harness.barrier();

        assertEquals(2, harness.bestmoveCount(), "one bestmove for the stopped infinite search, one for the second");
        assertNotNull(harness.awaitDiagnostic(
                l -> harness.diagnosticLines().stream().filter(x -> x.contains("event=drained")).count() >= 2, LONG));
        assertEquals(2, harness.diagnosticLines().stream()
                .filter(l -> l.contains("event=drained") && l.contains("helper_pending=0")).count());
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
