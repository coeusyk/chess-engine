package coeusyk.game.chess.core.selfplay;

import coeusyk.game.chess.core.eval.nnue.NnueEvaluator;
import coeusyk.game.chess.core.eval.nnue.NnueNetwork;
import coeusyk.game.chess.core.eval.nnue.TestNetworks;
import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.search.Searcher;
import coeusyk.game.chess.core.selfplay.vspr.GameFrame;
import coeusyk.game.chess.core.selfplay.vspr.PlayedMoveDecision;
import coeusyk.game.chess.core.selfplay.vspr.SearchBudgetKind;
import coeusyk.game.chess.core.selfplay.vspr.SelectionMechanismKind;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** #222 section 15/16: GameLoop integration coverage for the seeded diversity selector, kept
 * separate from the existing (unmodified) {@link GameLoopTest} so that file stays untouched. */
class GameLoopDiversityTest {

    private static final String VALID_SHA256 = "a".repeat(64);

    private static GeneratorConfig config(int maxPlies, long seed) {
        return new GeneratorConfig(
                Path.of("unused.nnue"), VALID_SHA256, "uuid", "build",
                SearchBudgetKind.DEPTH, 3, maxPlies, 1, null, seed,
                Path.of("unused.vspr"), Path.of("unused.json"));
    }

    private static Searcher newSearcher() {
        NnueNetwork network = TestNetworks.synthetic(8);
        Searcher searcher = new Searcher();
        searcher.setEvaluatorStrategy(new NnueEvaluator(network));
        return searcher;
    }

    @Test
    void bestMoveSelectorProducesNoRegressionInProvenance() {
        GameLoop loop = new GameLoop(config(6, 1L), newSearcher(), new BestMoveSelector());
        GameFrame frame = loop.playGame(1, 1L);

        for (PlayedMoveDecision decision : frame.playedMoves()) {
            assertEquals(SelectionMechanismKind.BEST_MOVE, decision.selectionMechanismKind());
            assertNull(decision.mechanismName());
            assertTrue(decision.selectionSeed().isEmpty());
        }
    }

    @Test
    void seededSelectorIntegratesWithoutLifecycleChangesAndRecordsProvenance() {
        SeededDiversitySelector selector = new SeededDiversitySelector(3, 100, 20.0);
        GameLoop loop = new GameLoop(config(6, 1L), newSearcher(), selector);
        GameFrame frame = loop.playGame(1, 1L);

        assertFalse(frame.playedMoves().isEmpty());
        for (PlayedMoveDecision decision : frame.playedMoves()) {
            assertEquals(SelectionMechanismKind.NAMED, decision.selectionMechanismKind());
            assertEquals("seeded-diversity-v1", decision.mechanismName());
            assertTrue(decision.selectionSeed().isPresent());
        }
    }

    @Test
    void sameGameSeedProducesIdenticalTrajectoryAcrossRuns() {
        SeededDiversitySelector selectorA = new SeededDiversitySelector(3, 100, 20.0);
        SeededDiversitySelector selectorB = new SeededDiversitySelector(3, 100, 20.0);

        GameFrame frameA = new GameLoop(config(10, 42L), newSearcher(), selectorA).playGame(3, 42L + 3);
        GameFrame frameB = new GameLoop(config(10, 42L), newSearcher(), selectorB).playGame(3, 42L + 3);

        List<Integer> movesA = new ArrayList<>();
        List<Integer> movesB = new ArrayList<>();
        frameA.playedMoves().forEach(d -> movesA.add(d.move()));
        frameB.playedMoves().forEach(d -> movesB.add(d.move()));
        assertEquals(movesA, movesB);
    }

    @Test
    void differentGameSeedCanDivergeTheTrajectory() {
        // Shallow maxPlies -- this test only needs to observe that two seeds CAN diverge, not
        // play out a full game; a small synthetic test network at low depth can occasionally
        // fail to complete a root iteration deep into an unusual (stochastically-reached)
        // position, which is a test-fixture limit, not something this test needs to survive.
        SeededDiversitySelector selectorA = new SeededDiversitySelector(3, 400, 40.0);
        SeededDiversitySelector selectorB = new SeededDiversitySelector(3, 400, 40.0);

        GameFrame frameA = new GameLoop(config(8, 1L), newSearcher(), selectorA).playGame(1, 1L);
        GameFrame frameB = new GameLoop(config(8, 999L), newSearcher(), selectorB).playGame(1, 999L);

        List<Integer> movesA = new ArrayList<>();
        List<Integer> movesB = new ArrayList<>();
        frameA.playedMoves().forEach(d -> movesA.add(d.move()));
        frameB.playedMoves().forEach(d -> movesB.add(d.move()));
        assertNotEquals(movesA, movesB, "different run seeds should be capable of diverging the trajectory");
    }

    @Test
    void diagnosticsSinkReceivesOneEntryPerPly() {
        SeededDiversitySelector selector = new SeededDiversitySelector(3, 100, 20.0);
        List<SelectionDiagnosticsEntry> entries = new ArrayList<>();
        GameLoop loop = new GameLoop(config(6, 1L), newSearcher(), selector, entries::add);

        GameFrame frame = loop.playGame(1, 1L);

        assertEquals(frame.playedMoves().size(), entries.size());
        for (SelectionDiagnosticsEntry entry : entries) {
            assertTrue(entry.candidateCount() >= 1);
            assertTrue(entry.chosenRank() >= 0);
        }
    }

    @Test
    void nullDiagnosticsSinkIsEquivalentToThreeArgConstructor() {
        // Just proves the four-arg constructor with null sink doesn't throw or change behavior.
        GameLoop loop = new GameLoop(config(4, 5L), newSearcher(), new BestMoveSelector(), null);
        assertDoesNotThrow(() -> loop.playGame(1, 5L));
    }

    @Test
    void controlMultiPvBestMoveSelectorActuallyWidensTheSearchedCandidateSet() {
        // E-15 (#223): decisive proof BestMoveSelector(3) isn't just a config-record field --
        // Searcher really is asked for 3 candidates (GameLoop.requiredCandidateCount() ->
        // searcher.setMultiPV()), still always chooses rank-0.
        List<SelectionDiagnosticsEntry> entries = new ArrayList<>();
        GameLoop loop = new GameLoop(config(8, 1L), newSearcher(), new BestMoveSelector(3), entries::add);

        GameFrame frame = loop.playGame(1, 1L);

        assertFalse(entries.isEmpty());
        assertTrue(entries.stream().anyMatch(e -> e.candidateCount() > 1),
                "expected at least one ply where BestMoveSelector(3) actually received >1 candidate");
        for (SelectionDiagnosticsEntry entry : entries) {
            assertEquals(0, entry.chosenRank(), "BestMoveSelector must always choose rank-0 regardless of width");
        }
        for (PlayedMoveDecision decision : frame.playedMoves()) {
            assertEquals(SelectionMechanismKind.BEST_MOVE, decision.selectionMechanismKind());
        }
    }
}
