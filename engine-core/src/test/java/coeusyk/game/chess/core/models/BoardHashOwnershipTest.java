package coeusyk.game.chess.core.models;

import coeusyk.game.chess.core.movegen.MovesGenerator;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BoardHashOwnershipTest {

    @Test
    void ordinaryMakeUnmakeRestoresCurrentPositionHash() {
        Board board = new Board();
        board.setSearchMode(true);
        String parentFen = board.toFen();
        long parentHash = board.getZobristHash();
        Move move = legalMoves(parentFen).get(0);

        board.makeMove(move);
        board.unmakeMove();

        assertEquals(parentFen, board.toFen());
        assertEquals(parentHash, board.getZobristHash());
        assertEquals(recomputedHash(board), board.getZobristHash());
    }

    @Test
    void nullThenChildMakeUnmakeRestoresPostNullHash() {
        Board board = new Board();
        board.setSearchMode(true);
        Board.NullMoveState state = board.makeNullMove();
        String postNullFen = board.toFen();
        Move child = legalMoves(postNullFen).get(0);

        board.makeMove(child);
        board.unmakeMove();

        assertEquals(postNullFen, board.toFen());
        assertEquals(recomputedHash(board), board.getZobristHash());

        board.unmakeNullMove(state);
    }

    @Test
    void twoNullSubtreeSiblingsEachRestorePostNullHash() {
        Board board = new Board();
        board.setSearchMode(true);
        Board.NullMoveState state = board.makeNullMove();
        String postNullFen = board.toFen();
        List<Move> children = legalMoves(postNullFen).subList(0, 2);

        for (Move child : children) {
            board.makeMove(child);
            board.unmakeMove();
            assertEquals(postNullFen, board.toFen());
            assertEquals(recomputedHash(board), board.getZobristHash());
        }

        board.unmakeNullMove(state);
    }

    @Test
    void reachableEnPassantNullSubtreePreservesBothHashes() throws Exception {
        String parentFen = "8/8/8/8/3pP3/8/8/4K2k b - e3 7 42";
        Board board = new Board(parentFen);
        board.setSearchMode(true);
        long parentHash = board.getZobristHash();
        int parentActiveColor = board.getActiveColor();
        int parentEp = board.getEpTargetSquare();
        int parentHalfmove = board.getHalfmoveClock();
        int parentFullmove = board.getFullMoves();
        int parentSp = zobristSp(board);

        Board.NullMoveState state = board.makeNullMove();
        String postNullFen = board.toFen();
        long postNullHash = recomputedHash(board);
        assertEquals(-1, board.getEpTargetSquare());
        assertEquals(postNullHash, board.getZobristHash());

        Move child = legalMoves(postNullFen).get(0);
        board.makeMove(child);
        board.unmakeMove();

        assertEquals(postNullFen, board.toFen());
        assertEquals(postNullHash, board.getZobristHash());

        board.unmakeNullMove(state);

        assertEquals(parentFen, board.toFen());
        assertEquals(parentHash, board.getZobristHash());
        assertEquals(parentActiveColor, board.getActiveColor());
        assertEquals(parentEp, board.getEpTargetSquare());
        assertEquals(parentHalfmove, board.getHalfmoveClock());
        assertEquals(parentFullmove, board.getFullMoves());
        assertEquals(parentSp, zobristSp(board));
    }

    @Test
    void nullMoveDoesNotBecomeRepetitionHistoryEntry() throws Exception {
        Board board = new Board();
        board.setSearchMode(true);
        int parentSp = zobristSp(board);
        long parentHash = board.getZobristHash();

        Board.NullMoveState state = board.makeNullMove();

        assertEquals(parentSp, zobristSp(board));
        assertEquals(parentHash, stackEntry(board, parentSp - 1));
        assertFalse(board.isThreefoldRepetition());

        board.unmakeNullMove(state);
        assertEquals(parentHash, board.getZobristHash());
    }

    private static ArrayList<Move> legalMoves(String fen) {
        Board copy = new Board(fen);
        copy.setSearchMode(true);
        return new MovesGenerator(copy).getActiveMoves(copy.getActiveColor());
    }

    private static long recomputedHash(Board board) {
        return new Board(board.toFen()).getZobristHash();
    }

    private static int zobristSp(Board board) throws Exception {
        Field field = Board.class.getDeclaredField("zobristSP");
        field.setAccessible(true);
        return field.getInt(board);
    }

    private static long stackEntry(Board board, int index) throws Exception {
        Field field = Board.class.getDeclaredField("zobristStack");
        field.setAccessible(true);
        return ((long[]) field.get(board))[index];
    }
}
