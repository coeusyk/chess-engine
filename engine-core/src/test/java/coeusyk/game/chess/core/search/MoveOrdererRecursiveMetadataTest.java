package coeusyk.game.chess.core.search;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.movegen.MovesGenerator;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class MoveOrdererRecursiveMetadataTest {

    /** White has a later losing capture whose metadata must survive recursion. */
    private static final String PARENT_FEN =
            "4k3/5p2/4p3/7q/5N2/8/8/4K3 w - - 0 1";

    @Test
    void childOrderingChangesTheActualParentPruningClassification() throws Exception {
        Board parent = new Board(PARENT_FEN);
        parent.setSearchMode(true);
        int[] parentMoves = new int[256];
        int parentMoveCount = MovesGenerator.generate(parent, parentMoves);

        MoveOrderer orderer = new MoveOrderer();
        int[][] killers = new int[128][2];
        int[][] history = new int[7][64];

        // A TT-preferred quiet sibling gives the child enough legal moves to overwrite
        // the parent's later capture slot, matching the Searcher ordering path.
        int preferredSibling = Move.of(60, 61, 0);
        orderer.orderMoves(parent, parentMoves, parentMoveCount, 0, preferredSibling, killers, history);

        int losingCapture = Move.of(37, 20, 0); // Nf4xe6; f7xe6 wins the knight
        int parentIndex = indexOf(parentMoves, parentMoveCount, losingCapture);
        assertTrue(parentIndex > 0, "losing capture must be a later parent move");

        int parentScore = orderer.scoreMove(parent, losingCapture, 0, Move.NONE, killers, history);
        assertTrue(parentScore < 0, "parent capture must have a losing SEE classification");
        assertTrue(orderer.scoringBufferForPly(0)[parentIndex] < 0,
                "parent ordering must initially classify the capture as losing");

        parent.makeMove(parentMoves[0]);
        int[] childMoves = new int[256];
        int childMoveCount = MovesGenerator.generate(parent, childMoves);
        assertTrue(childMoveCount > parentIndex, "child must overwrite the parent slot");
        orderer.orderMoves(parent, childMoves, childMoveCount, 1, Move.NONE, killers, history);

        boolean staleIsLosing = orderer.scoringBufferForPly(0)[parentIndex] < 0;
        assertTrue(staleIsLosing,
                "parent metadata must survive child ordering and remain losing");

        Method pruningGate = Searcher.class.getDeclaredMethod(
                "canPruneLosingCapture",
                int.class, boolean.class, boolean.class, boolean.class, boolean.class);
        pruningGate.setAccessible(true);
        assertTrue(invokePruningGate(pruningGate, true),
                "the true parent classification reaches the losing-capture pruning gate");
        assertTrue(invokePruningGate(pruningGate, staleIsLosing),
                "the parent must reach the same pruning decision after recursion");
    }

    private static boolean invokePruningGate(Method gate, boolean losing) throws Exception {
        return (boolean) gate.invoke(new Searcher(), 2, losing, false, false, false);
    }

    private static int indexOf(int[] moves, int count, int target) {
        for (int i = 0; i < count; i++) {
            if (moves[i] == target) {
                return i;
            }
        }
        fail("Expected losing capture was not generated");
        return -1;
    }
}
