package coeusyk.game.chess.core.search;

import coeusyk.game.chess.core.eval.EvaluatorStrategy;
import coeusyk.game.chess.core.models.Board;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #267 item 1: {@code alphaBeta} had no ply bound. Iterative deepening reaches depth 127 and check
 * extensions can add up to 16 plies, so a node can sit at ply 143, while {@code staticEvalStack}
 * holds {@code MAX_PLY + 2 = 130} entries. These tests call {@code alphaBeta} at plies past that
 * array directly (the same reflection route {@link SingularSearchBoundSemanticsTest} uses), because
 * no real search reaches them in test time.
 */
class SearcherPlyBoundsTest {

    private static final String START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    private record ConstantEvaluator(int score) implements EvaluatorStrategy {
        @Override
        public int evaluate(Board board) {
            return score;
        }
    }

    static int alphaBeta(Searcher searcher, Board board, int depth, int ply) throws Exception {
        Method alphaBeta = Searcher.class.getDeclaredMethod("alphaBeta", Board.class, int.class, int.class,
                int.class, int.class, BooleanSupplier.class, boolean.class, int.class, int.class, boolean.class);
        alphaBeta.setAccessible(true);
        try {
            return (int) alphaBeta.invoke(searcher, board, depth, ply, -1000, 1000,
                    (BooleanSupplier) () -> false, false, 0, 16, false);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    @Test
    void aNodePastTheStaticEvalStackDoesNotThrow() {
        for (int ply : new int[] {128, 129, 130, 131, 136, 143}) {
            Board board = new Board(START);
            board.setSearchMode(true);
            Searcher searcher = new Searcher();
            searcher.setEvaluatorStrategy(new ConstantEvaluator(25));
            assertDoesNotThrow(() -> alphaBeta(searcher, board, 3, ply), "alphaBeta at ply " + ply);
        }
    }

    // Searcher.MATE_SCORE and MAX_PLY are private; these mirror them.
    private static final int MATE_SCORE = 100_000;
    private static final int MAX_PLY = 128;
    private static final int QUIESCENCE_REACH = 9; // MAX_Q_DEPTH + MAX_Q_CHECK_EXTENSION
    private static final String CHECKMATE = "7k/6Q1/6K1/8/8/8/8/8 b - - 0 1";
    private static final String STALEMATE = "7k/5Q2/6K1/8/8/8/8/8 b - - 0 1";
    private static final String INSUFFICIENT = "4k3/8/8/8/8/8/8/4KB2 w - - 0 1";

    private static Searcher searcherWithEval(int eval) {
        Searcher searcher = new Searcher();
        searcher.setEvaluatorStrategy(new ConstantEvaluator(eval));
        return searcher;
    }

    private static Board board(String fen) {
        Board board = new Board(fen);
        board.setSearchMode(true);
        return board;
    }

    private static long nodesVisited(Searcher searcher) throws Exception {
        var field = Searcher.class.getDeclaredField("nodesVisited");
        field.setAccessible(true);
        return field.getLong(searcher);
    }

    @Test
    void theBoundaryLeavesRoomForTheDeepestQuiescenceBelowMaxPly() {
        // Every ply a quiescence call can reach from the last searching ply must stay within MAX_PLY, the
        // largest distance scoreToTT and isMateWindow still treat as mate.
        assertTrue(Searcher.MAX_SEARCH_PLY + QUIESCENCE_REACH - 1 < MAX_PLY, "MAX_SEARCH_PLY=" + Searcher.MAX_SEARCH_PLY);
        assertTrue(Searcher.MAX_SEARCH_PLY < 127, "the guard must sit below the old effectiveMaxDepth");
    }

    @Test
    void mateScoresAreRecognisedUpToTheDeepestReachablePly() {
        for (int ply = 1; ply <= Searcher.MAX_SEARCH_PLY + QUIESCENCE_REACH; ply++) { // ply 0 adds nothing to adjust
            int mated = -MATE_SCORE + ply;
            assertNotEquals(mated, Searcher.scoreToTT(mated, ply), "mate at ply " + ply + " must be recognised");
            assertEquals(mated, Searcher.scoreFromTT(Searcher.scoreToTT(mated, ply), ply), "round trip at ply " + ply);
        }
        // Negative control: past MAX_PLY a mate score is no longer recognised, which is why the margin exists.
        int tooDeep = -MATE_SCORE + MAX_PLY + 1;
        assertEquals(tooDeep, Searcher.scoreToTT(tooDeep, MAX_PLY + 1));
    }

    @Test
    void aNodeAtTheBoundaryIsAdjudicatedExactlyAndDoesNotSearchDeeper() throws Exception {
        int ply = Searcher.MAX_SEARCH_PLY;

        Searcher mated = searcherWithEval(25);
        assertEquals(-MATE_SCORE + ply, alphaBeta(mated, board(CHECKMATE), 5, ply), "checkmate keeps its mate score");

        Searcher stalemate = searcherWithEval(25);
        assertEquals(0, alphaBeta(stalemate, board(STALEMATE), 5, ply), "stalemate is a draw, not the static eval");

        Searcher ordinary = searcherWithEval(25);
        assertEquals(25, alphaBeta(ordinary, board(START), 5, ply), "an ordinary position gets its static eval");
        assertEquals(0, nodesVisited(ordinary), "no child is searched at the boundary");

        Searcher drawn = searcherWithEval(25);
        assertEquals(0, alphaBeta(drawn, board(INSUFFICIENT), 5, ply), "draw detection still precedes the guard");
    }

    @Test
    void theNodeJustAboveTheBoundaryStillSearches() throws Exception {
        Searcher searcher = searcherWithEval(25);
        alphaBeta(searcher, board(START), 1, Searcher.MAX_SEARCH_PLY - 1);
        assertTrue(nodesVisited(searcher) >= 1, "the guard must not cut off search below MAX_SEARCH_PLY");
    }

    @Test
    void aQuiescenceMateAtTheDeepestReachablePlyIsStillAMateScore() throws Exception {
        Method quiescence = Searcher.class.getDeclaredMethod("quiescence", Board.class, int.class, int.class,
                int.class, int.class, BooleanSupplier.class);
        quiescence.setAccessible(true);
        int ply = Searcher.MAX_SEARCH_PLY - 1 + QUIESCENCE_REACH - 1; // last searching ply plus its deepest qPly
        int score = (int) quiescence.invoke(searcherWithEval(25), board(CHECKMATE), -MATE_SCORE, MATE_SCORE, ply, 0,
                (BooleanSupplier) () -> false);
        assertEquals(-MATE_SCORE + ply, score);
        assertNotEquals(score, Searcher.scoreToTT(score, ply), "recognised as a mate score");
    }

    @Test
    void aTruncatedNodeIsStoredAtTheDepthThatActuallyFitted() throws Exception {
        // At ply 118 only one ply of search fits below MAX_SEARCH_PLY, whatever depth was requested.
        Board nearBoundary = board(START);
        TranspositionTable table = new TranspositionTable(1);
        Searcher near = searcherWithEval(25);
        near.setSharedTranspositionTable(table);
        alphaBeta(near, nearBoundary, 5, Searcher.MAX_SEARCH_PLY - 1);
        var truncated = table.probe(nearBoundary.getZobristHash());
        assertTrue(truncated != null, "the node stores its result");
        assertEquals(1, truncated.depth(), "stored depth must not exceed the room below the guard");

        // A node with room to spare keeps the full requested depth (the normal path is unchanged).
        Board shallow = board(START);
        TranspositionTable otherTable = new TranspositionTable(1);
        Searcher normal = searcherWithEval(25);
        normal.setSharedTranspositionTable(otherTable);
        alphaBeta(normal, shallow, 2, 10);
        var full = otherTable.probe(shallow.getZobristHash());
        assertTrue(full != null);
        assertEquals(2, full.depth());
    }

    @Test
    void quiescenceFromTheLastSearchingPlyNeverGoesDeeperThanTheMarginAllows() throws Exception {
        // Follows real capture and check chains (not a single call on a mated position) and reads the
        // deepest ply the recursion touched from Searcher's own seldepth bookkeeping.
        String[] tactical = {
                "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
                "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
                "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1",
                "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8",
                "2kr3r/p1ppqpb1/bn2Qnp1/3PN3/1p2P3/2N5/PPPBBPPP/R3K2R b KQ - 3 2",
        };
        Method quiescence = Searcher.class.getDeclaredMethod("quiescence", Board.class, int.class, int.class,
                int.class, int.class, BooleanSupplier.class);
        quiescence.setAccessible(true);
        var seldepth = Searcher.class.getDeclaredField("seldepth");
        seldepth.setAccessible(true);
        int entry = Searcher.MAX_SEARCH_PLY - 1;
        int deepest = entry;
        for (String fen : tactical) {
            Searcher searcher = new Searcher();
            quiescence.invoke(searcher, board(fen), -MATE_SCORE, MATE_SCORE, entry, 0, (BooleanSupplier) () -> false);
            int reached = seldepth.getInt(searcher);
            assertTrue(reached <= entry + QUIESCENCE_REACH - 1, fen + " reached ply " + reached);
            deepest = Math.max(deepest, reached);
        }
        assertTrue(deepest > entry, "the chains must actually recurse, or this test proves nothing");
    }
}
