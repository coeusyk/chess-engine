package coeusyk.game.chess.core.search;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.movegen.MovesGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 17 PVS experiment. Covers the control-flow this experiment adds to
 * {@code Searcher.alphaBeta()}/{@code searchRoot()}, per the preregistered scope in
 * docs/architecture/research/phase16-p16-3-intervention-preregistration.md section 6.
 *
 * Structural properties this file does not test directly, because they are guaranteed by the
 * branch guards themselves rather than by runtime behavior: the first searched move at any node
 * can never enter the new zero-window branches (both require {@code moveIndex > 0}), and a
 * non-PV node can never enter the ordinary-PV-sibling branch (it requires {@code isPvNode}).
 */
class PvsExperimentTest {

    private static final String MIDDLEGAME_FEN =
            "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/2N5/PPPP1PPP/R1BQK1NR b KQkq - 2 3";

    @ParameterizedTest
    @ValueSource(strings = {
            MIDDLEGAME_FEN,
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            "8/4k3/8/4P3/8/8/R7/4K3 w - - 0 1",
            "4k3/8/8/8/8/8/8/4KQ2 w - - 0 1"
    })
    void principalVariationMovesAreAllLegalInSequence(String fen) {
        Searcher searcher = new Searcher();
        SearchResult result = searcher.searchDepth(new Board(fen), 5);

        Board replay = new Board(fen);
        int[] legalMoves = new int[256];
        for (Move move : result.principalVariation()) {
            int legalCount = MovesGenerator.generate(replay, legalMoves);
            int packed = move.pack();
            boolean isLegalHere = false;
            for (int i = 0; i < legalCount; i++) {
                if (legalMoves[i] == packed) {
                    isLegalHere = true;
                    break;
                }
            }
            assertTrue(isLegalHere,
                    "PV move " + move + " is not legal at this point in the replay for FEN " + fen);
            replay.makeMove(move);
        }
    }

    @Test
    void laterPvSiblingsTriggerZeroWindowProbes() {
        Searcher searcher = new Searcher();
        SearchResult result = searcher.searchDepth(new Board(MIDDLEGAME_FEN), 5);

        assertTrue(result.pvsZeroWindowProbes() > 0,
                "a middlegame position with several PV-node siblings should exercise the new "
                        + "ordinary-sibling null-window probe at least once");
    }

    @Test
    void notEveryProbeTriggersAFullWindowResearch() {
        Searcher searcher = new Searcher();
        SearchResult result = searcher.searchDepth(new Board(MIDDLEGAME_FEN), 5);

        long totalProbes = result.pvsZeroWindowProbes() + result.lmrApplications();
        assertTrue(totalProbes > 0, "test position should exercise at least one probe");
        assertTrue(result.pvsFullWindowResearches() < totalProbes,
                "most null-window probes should fail low or hit a cutoff without needing the "
                        + "full-window re-search -- if every probe re-searched, the narrower window "
                        + "would save nothing");
    }

    @Test
    void someFullWindowResearchesDoOccur() {
        // A position with real tactical tension: enough candidate moves should genuinely beat
        // alpha at some PV node for the re-search path to fire at least once.
        Searcher searcher = new Searcher();
        SearchResult result = searcher.searchDepth(new Board(MIDDLEGAME_FEN), 6);

        assertTrue(result.pvsFullWindowResearches() > 0,
                "a real position searched to depth 6 should trigger at least one alpha < score < "
                        + "beta re-search somewhere in the tree");
    }

    @Test
    void fullDepthVerificationsAreBoundedByLmrApplications() {
        // The full-depth null-window verification step is only reachable from the LMR branch
        // (stage 2 of section 6), so it can never outnumber the LMR reductions that could have
        // led into it.
        Searcher searcher = new Searcher();
        SearchResult result = searcher.searchDepth(new Board(MIDDLEGAME_FEN), 6);

        assertTrue(result.pvsFullDepthVerifications() <= result.lmrApplications(),
                "full-depth PVS verification only happens after an LMR reduced-depth probe fails "
                        + "high, so it cannot exceed the number of LMR applications");
    }

    @Test
    void disablingLmrEliminatesFullDepthVerificationsButKeepsOrdinaryProbes() {
        // moveOrderingEnabled, aspirationWindowsEnabled, nullMovePruningEnabled, lmrEnabled=false.
        Searcher searcher = new Searcher(true, true, true, false);
        SearchResult result = searcher.searchDepth(new Board(MIDDLEGAME_FEN), 5);

        assertEquals(0, result.lmrApplications());
        assertEquals(0, result.pvsFullDepthVerifications(),
                "with LMR disabled, the reduced-depth-probe stage that stage 2 depends on never runs");
        assertTrue(result.pvsZeroWindowProbes() > 0,
                "the ordinary-PV-sibling PVS probe is independent of LMR and should still fire");
    }
}
