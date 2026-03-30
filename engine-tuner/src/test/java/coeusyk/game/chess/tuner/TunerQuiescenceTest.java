package coeusyk.game.chess.tuner;

import coeusyk.game.chess.core.models.Board;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TunerQuiescenceTest {

    private static double[] defaultParams;

    @BeforeAll
    static void loadParams() {
        defaultParams = EvalParams.extractFromCurrentEval();
    }

    @Test
    void quietPositionReturnsSameAsStaticEval() {
        // Starting position has no captures — qsearch should return the static eval.
        Board startpos = new Board();
        int staticEval = TunerEvaluator.evaluateStatic(startpos, defaultParams);
        int qsearchEval = TunerEvaluator.evaluate(startpos, defaultParams);
        assertEquals(staticEval, qsearchEval,
                "Quiet position should have same qsearch and static eval");
    }

    @Test
    void hangingPiecePositionResolvesCapture() {
        // White knight on e5 can capture undefended Black pawn on d7.
        // After Nxd7 the eval should change — qsearch should find a better score for White.
        // FEN: Black queen on d8 but no pawn on d7, White has knight on e5 attacking d7.
        // Use a position where White can capture for material gain:
        // White has a rook on a8 that can capture Black's rook on a7
        Board hangingPiece = new Board(
                "4k3/r7/8/8/8/8/8/R3K3 w - - 0 1");
        // White rook can capture Black rook on a7: Rxa7
        int staticEval = TunerEvaluator.evaluateStatic(hangingPiece, defaultParams);
        int qsearchEval = TunerEvaluator.evaluate(hangingPiece, defaultParams);

        // After Rxa7, White is up a rook — qsearch should find a higher score.
        assertTrue(qsearchEval > staticEval,
                "qsearch should find the capture Rxa7, scoring higher than static eval. " +
                "static=" + staticEval + ", qsearch=" + qsearchEval);
    }

    @Test
    void boardRestoredAfterQsearch() {
        // Ensure make/unmake leaves the board intact.
        Board board = new Board(
                "4k3/r7/8/8/8/8/8/R3K3 w - - 0 1");
        long hashBefore = board.getZobristHash();
        TunerEvaluator.evaluate(board, defaultParams);
        assertEquals(hashBefore, board.getZobristHash(),
                "Board Zobrist hash must be unchanged after qsearch");
    }

    @Test
    void threadSafety() throws Exception {
        // Run many evaluations in parallel to verify ThreadLocal<TunerQuiescence> works.
        Board[] boards = new Board[100];
        for (int i = 0; i < boards.length; i++) {
            boards[i] = new Board();
        }

        java.util.concurrent.atomic.AtomicBoolean failed = new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread[] threads = new Thread[8];
        for (int t = 0; t < threads.length; t++) {
            final int offset = t * (boards.length / threads.length);
            threads[t] = new Thread(() -> {
                for (int i = 0; i < boards.length / threads.length; i++) {
                    int eval = TunerEvaluator.evaluate(boards[offset + i], defaultParams);
                    if (eval != 0) failed.set(true);
                }
            });
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();

        assertFalse(failed.get(), "All startpos evaluations should return 0");
    }
}
