package coeusyk.game.chess.core.search;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.movegen.MovesGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class SearcherTest {

    @Test
    void searchReturnsBestMoveAtRequestedDepth() {
        Board board = new Board();
        Searcher searcher = new Searcher();

        SearchResult result = searcher.searchDepth(board, 3);

        assertNotNull(result.bestMove());
        assertEquals(3, result.depthReached());
        assertFalse(result.aborted());
    }

    @Test
    void alphaBetaVisitsFewerNodesThanBruteForceMinimax() {
        Board board = new Board("r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/2N5/PPPP1PPP/R1BQK1NR b KQkq - 2 3");
        int depth = 3;

        Searcher searcher = new Searcher();
        SearchResult alphaBetaResult = searcher.searchDepth(board, depth);

        NodeCounter bruteCounter = new NodeCounter();
        bruteForceNegamax(board, depth, bruteCounter);

        long alphaBetaNodes = alphaBetaResult.nodesVisited() + alphaBetaResult.leafNodes();
        assertTrue(alphaBetaNodes < bruteCounter.nodes(),
                "Expected alpha-beta to visit fewer nodes than brute force minimax");
    }

    @Test
    void iterativeDeepeningConvergesDeterministically() {
        Board boardA = new Board("r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/2N5/PPPP1PPP/R1BQK1NR b KQkq - 2 3");
        Board boardB = new Board("r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/2N5/PPPP1PPP/R1BQK1NR b KQkq - 2 3");
        Searcher searcherOne = new Searcher();
        Searcher searcherTwo = new Searcher();

        SearchResult idRunOne = searcherOne.iterativeDeepening(boardA, 4, neverAbort());
        SearchResult idRunTwo = searcherTwo.iterativeDeepening(boardB, 4, neverAbort());

        assertNotNull(idRunOne.bestMove());
        assertNotNull(idRunTwo.bestMove());
        assertEquals(idRunOne.depthReached(), idRunTwo.depthReached());
        assertMoveEquals(idRunOne.bestMove(), idRunTwo.bestMove());
        assertEquals(idRunOne.principalVariation().size(), idRunTwo.principalVariation().size());
        for (int i = 0; i < idRunOne.principalVariation().size(); i++) {
            assertMoveEquals(idRunOne.principalVariation().get(i), idRunTwo.principalVariation().get(i));
        }
    }

    @Test
    void quiescenceNodesAreTrackedSeparately() {
        Board board = new Board();
        Searcher searcher = new Searcher();

        SearchResult result = searcher.searchDepth(board, 1);

        assertTrue(result.quiescenceNodes() > 0);
        assertTrue(result.quiescenceNodes() <= 40);
    }

    @Test
    void tacticalHangingQueenIsEvaluatedCorrectly() {
        Board board = new Board("4k3/8/8/4q3/2N5/8/8/4K3 w - - 0 1");
        Searcher searcher = new Searcher();

        SearchResult result = searcher.searchDepth(board, 1);

        assertNotNull(result.bestMove());
        assertEquals(34, result.bestMove().startSquare);
        assertEquals(28, result.bestMove().targetSquare);
    }

    @Test
    void principalVariationIsPresentAndBoundedByDepth() {
        Board board = new Board("r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/2N5/PPPP1PPP/R1BQK1NR b KQkq - 2 3");
        Searcher searcher = new Searcher();

        SearchResult result = searcher.searchDepth(board, 4);

        assertNotNull(result.bestMove());
        assertFalse(result.principalVariation().isEmpty());
        assertTrue(result.principalVariation().size() <= result.depthReached());
        assertMoveEquals(result.bestMove(), result.principalVariation().get(0));
    }

    @Test
    void moveOrderingReducesNodesComparedToDisabledOrdering() {
        Board boardOrdered = new Board("r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/2N5/PPPP1PPP/R1BQK1NR b KQkq - 2 3");
        Board boardUnordered = new Board("r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/2N5/PPPP1PPP/R1BQK1NR b KQkq - 2 3");

        SearchResult ordered = new Searcher(true).searchDepth(boardOrdered, 4);
        SearchResult unordered = new Searcher(false).searchDepth(boardUnordered, 4);

        long orderedNodes = ordered.nodesVisited() + ordered.leafNodes() + ordered.quiescenceNodes();
        long unorderedNodes = unordered.nodesVisited() + unordered.leafNodes() + unordered.quiescenceNodes();

        assertTrue(orderedNodes <= unorderedNodes);
    }

    @Test
    void ttMoveHintIsTriedFirstAtRoot() {
        Board board = new Board();
        Searcher searcher = new Searcher(true);
        Move ttHint = findMove(board, 52, 36); // e2e4 (ep-target reaction)
        searcher.setRootTtMoveHintForTesting(ttHint);

        SearchResult result = searcher.searchDepth(board, 1);

        assertNotNull(result.bestMove());
        assertMoveEquals(ttHint, result.bestMove());
    }

    @Test
    void transpositionTableHitRateIsNonZeroOnRepeatedSearch() {
        Board board = new Board("r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/2N5/PPPP1PPP/R1BQK1NR b KQkq - 2 3");
        Searcher searcher = new Searcher();

        searcher.searchDepth(board, 4);
        SearchResult second = searcher.searchDepth(new Board("r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/2N5/PPPP1PPP/R1BQK1NR b KQkq - 2 3"), 4);

        assertTrue(second.ttHitRate() > 0.0);
    }

    @Test
    void ttBoundGatingWorksForExactLowerUpper() {
        Searcher searcher = new Searcher();

        TranspositionTable.Entry exact = new TranspositionTable.Entry(1L, null, 4, 50, TTBound.EXACT);
        TranspositionTable.Entry lower = new TranspositionTable.Entry(1L, null, 4, 120, TTBound.LOWER_BOUND);
        TranspositionTable.Entry upper = new TranspositionTable.Entry(1L, null, 4, -80, TTBound.UPPER_BOUND);

        assertEquals(50, searcher.applyTtBound(exact, 3, -100, 100));
        assertEquals(120, searcher.applyTtBound(lower, 3, -100, 100));
        assertEquals(-80, searcher.applyTtBound(upper, 3, -70, 100));

        assertNull(searcher.applyTtBound(lower, 3, -100, 200));
        assertNull(searcher.applyTtBound(upper, 3, -120, 100));
        assertNull(searcher.applyTtBound(exact, 5, -100, 100));
    }

    private Move findMove(Board board, int startSquare, int targetSquare) {
        List<Move> moves = new MovesGenerator(board).getActiveMoves(board.getActiveColor());
        for (Move move : moves) {
            if (move.startSquare == startSquare && move.targetSquare == targetSquare) {
                return move;
            }
        }
        fail("Expected legal move not found");
        return null;
    }

    private int bruteForceNegamax(Board board, int depth, NodeCounter counter) {
        counter.increment();

        if (depth == 0) {
            return 0;
        }

        MovesGenerator generator = new MovesGenerator(board);
        List<Move> moves = generator.getActiveMoves(board.getActiveColor());
        if (moves.isEmpty()) {
            return 0;
        }

        int best = Integer.MIN_VALUE / 2;
        for (Move move : moves) {
            board.makeMove(move);
            int score = -bruteForceNegamax(board, depth - 1, counter);
            board.unmakeMove();
            if (score > best) {
                best = score;
            }
        }

        return best;
    }

    private BooleanSupplier neverAbort() {
        return () -> false;
    }

    private void assertMoveEquals(Move expected, Move actual) {
        assertEquals(expected.startSquare, actual.startSquare);
        assertEquals(expected.targetSquare, actual.targetSquare);
        assertEquals(expected.reaction, actual.reaction);
    }

    private static class NodeCounter {
        private long nodes;

        void increment() {
            nodes++;
        }

        long nodes() {
            return nodes;
        }
    }
}