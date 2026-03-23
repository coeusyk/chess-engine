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
        Searcher searcher = new Searcher();

        SearchResult idRunOne = searcher.iterativeDeepening(boardA, 4, neverAbort());
        SearchResult idRunTwo = searcher.iterativeDeepening(boardB, 4, neverAbort());

        assertNotNull(idRunOne.bestMove());
        assertNotNull(idRunTwo.bestMove());
        assertEquals(idRunOne.depthReached(), idRunTwo.depthReached());
        assertMoveEquals(idRunOne.bestMove(), idRunTwo.bestMove());
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