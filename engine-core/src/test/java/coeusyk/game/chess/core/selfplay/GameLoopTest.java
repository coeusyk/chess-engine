package coeusyk.game.chess.core.selfplay;

import coeusyk.game.chess.core.eval.nnue.NnueEvaluator;
import coeusyk.game.chess.core.eval.nnue.NnueNetwork;
import coeusyk.game.chess.core.eval.nnue.TestNetworks;
import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.search.Searcher;
import coeusyk.game.chess.core.selfplay.vspr.GameFrame;
import coeusyk.game.chess.core.selfplay.vspr.GameOutcome;
import coeusyk.game.chess.core.selfplay.vspr.ScoreKind;
import coeusyk.game.chess.core.selfplay.vspr.SearchBudgetKind;
import coeusyk.game.chess.core.selfplay.vspr.TerminationReason;
import coeusyk.game.chess.core.selfplay.vspr.TrainingSample;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class GameLoopTest {

    private static final String VALID_SHA256 = "a".repeat(64);

    private static GeneratorConfig config(int maxPlies) {
        return new GeneratorConfig(
                Path.of("unused.nnue"), VALID_SHA256, "uuid", "build",
                SearchBudgetKind.DEPTH, 3, maxPlies, 1, null, 7L,
                Path.of("unused.vspr"), Path.of("unused.json"));
    }

    private static GameLoop newGameLoop(int maxPlies) {
        NnueNetwork network = TestNetworks.synthetic(8);
        Searcher searcher = new Searcher();
        searcher.setEvaluatorStrategy(new NnueEvaluator(network));
        return new GameLoop(config(maxPlies), searcher, new BestMoveSelector());
    }

    @Test
    void checkmatePositionTerminatesImmediatelyWithCorrectWinner() {
        // Fool's Mate final position -- White to move, checkmated by Black (DR-220's own fixture 1).
        Board board = new Board("rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3");
        GameFrame frame = newGameLoop(500).playGame(1, 1L, board);

        assertEquals(GameOutcome.BLACK_WIN, frame.gameOutcome());
        assertEquals(TerminationReason.CHECKMATE, frame.terminationReason());
        assertTrue(frame.samples().isEmpty()); // terminal on entry -- no search, no sample
        assertTrue(frame.playedMoves().isEmpty());
    }

    @Test
    void stalematePositionTerminatesAsDraw() {
        Board board = new Board("k7/8/1Q6/8/8/8/8/K7 b - - 0 1");
        GameFrame frame = newGameLoop(500).playGame(1, 1L, board);

        assertEquals(GameOutcome.DRAW, frame.gameOutcome());
        assertEquals(TerminationReason.STALEMATE, frame.terminationReason());
    }

    @Test
    void insufficientMaterialTerminatesAsDraw() {
        Board board = new Board("8/8/4k3/8/8/8/4K3/8 w - - 0 1");
        GameFrame frame = newGameLoop(500).playGame(1, 1L, board);

        assertEquals(GameOutcome.DRAW, frame.gameOutcome());
        assertEquals(TerminationReason.INSUFFICIENT_MATERIAL, frame.terminationReason());
    }

    @Test
    void fiftyMoveRuleTerminatesAsDraw() {
        Board board = new Board("4k3/8/8/8/8/8/8/4K3 w - - 100 50");
        GameFrame frame = newGameLoop(500).playGame(1, 1L, board);

        assertEquals(GameOutcome.DRAW, frame.gameOutcome());
        assertEquals(TerminationReason.FIFTY_MOVE_RULE, frame.terminationReason());
    }

    @Test
    void threefoldRepetitionTerminatesAsDraw() {
        // Same reversible knight-shuffle sequence as movegen.ThreefoldRepetitionTest.
        Board board = new Board("1n2k3/8/8/8/8/8/8/1N2K3 w - - 0 1");
        for (int i = 0; i < 2; i++) {
            board.makeMove(new Move(57, 40));
            board.makeMove(new Move(1, 16));
            board.makeMove(new Move(40, 57));
            board.makeMove(new Move(16, 1));
        }
        assertTrue(board.isThreefoldRepetition());

        GameFrame frame = newGameLoop(500).playGame(1, 1L, board);

        assertEquals(GameOutcome.DRAW, frame.gameOutcome());
        assertEquals(TerminationReason.THREEFOLD_REPETITION, frame.terminationReason());
    }

    @Test
    void moveCapProducesUnresolvedOutcomeNotDraw() {
        Board board = new Board(); // standard start, far from any natural termination
        GameFrame frame = newGameLoop(2).playGame(1, 1L, board);

        assertEquals(GameOutcome.UNRESOLVED, frame.gameOutcome());
        assertEquals(TerminationReason.MOVE_CAP, frame.terminationReason());
        assertEquals(2, frame.playedMoves().size());
        assertEquals(2, frame.samples().size());
    }

    @Test
    void onTrajectorySampleFenMatchesTheExactSearchedRootPosition() {
        Board board = new Board();
        String startFen = board.toFen();
        GameFrame frame = newGameLoop(2).playGame(1, 1L, board);

        TrainingSample first = frame.samples().get(0);
        assertEquals(startFen, first.fen());
        assertTrue(first.onTrajectory());
        assertEquals(0, first.ply());
        // ply 1's sample FEN must differ from ply 0's -- it's the position after ply 0's move,
        // not a second copy of the root.
        assertNotEquals(first.fen(), frame.samples().get(1).fen());
    }

    @Test
    void everySampleHasAScoreKindAndSearchBudgetRecorded() {
        Board board = new Board();
        GameFrame frame = newGameLoop(2).playGame(1, 1L, board);

        for (TrainingSample sample : frame.samples()) {
            assertNotNull(sample.evalScoreKind());
            assertTrue(sample.evalScoreKind() == ScoreKind.CP || sample.evalScoreKind() == ScoreKind.MATE);
            assertEquals(SearchBudgetKind.DEPTH, sample.searchBudgetKind());
            assertEquals(3L, sample.searchBudgetValue());
        }
    }

    @Test
    void nodesBudgetKindIsRejectedAtConstruction() {
        GeneratorConfig nodesConfig = new GeneratorConfig(
                Path.of("unused.nnue"), VALID_SHA256, "uuid", "build",
                SearchBudgetKind.NODES, 1000, 500, 1, null, 7L,
                Path.of("unused.vspr"), Path.of("unused.json"));
        NnueNetwork network = TestNetworks.synthetic(8);
        Searcher searcher = new Searcher();
        searcher.setEvaluatorStrategy(new NnueEvaluator(network));

        assertThrows(UnsupportedOperationException.class,
                () -> new GameLoop(nodesConfig, searcher, new BestMoveSelector()));
    }

    // Note on the runtime history-safety guard (GameLoop's own per-search check, distinct from
    // GeneratorConfig's construction-time rejection covered by GeneratorConfigTest): a config that
    // passes construction can never trip it (identical inequality), and the only way to exercise
    // the guard directly would be a live game run out to hundreds of real searches near
    // Board.UNMAKE_POOL_SIZE -- too slow for this suite. moveCapProducesUnresolvedOutcomeNotDraw
    // above already proves GameLoop actually stops at maxPlies in the ordinary case.
}
