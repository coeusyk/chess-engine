package coeusyk.game.chess.core.models;

import coeusyk.game.chess.core.book.PolyglotKey;
import coeusyk.game.chess.core.movegen.MovesGenerator;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BoardHashOwnershipTest {

    @Test
    void cPawnDoublePushInRookEndgameDoesNotHashPinnedEnPassant() {
        Board board = new Board("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1");
        long rootHash = board.getZobristHash();
        board.makeMove(Move.of(52, 44)); // e2e3
        board.makeMove(Move.of(10, 26, Move.FLAG_EP_TARGET)); // c7c5

        // b5xc6 would remove both blockers between the rook on h5 and the king on a5.
        assertEquals("8/8/3p4/KPp4r/1R3p1k/4P3/6P1/8 w - c6 0 2", board.toFen());
        Board rebuilt = new Board(board.toFen());
        Board withoutEp = new Board("8/8/3p4/KPp4r/1R3p1k/4P3/6P1/8 w - - 0 2");
        int[] withEpMoves = new int[256];
        int[] withoutEpMoves = new int[256];
        int withEpCount = MovesGenerator.generate(board, withEpMoves);
        int withoutEpCount = MovesGenerator.generate(withoutEp, withoutEpMoves);
        assertFalse(Arrays.stream(withEpMoves, 0, withEpCount).anyMatch(Move::isEnPassant));
        assertArrayEquals(Arrays.stream(withoutEpMoves, 0, withoutEpCount).sorted().toArray(),
                Arrays.stream(withEpMoves, 0, withEpCount).sorted().toArray());
        assertEquals(rebuilt.getZobristHash(), board.getZobristHash());
        assertEquals(withoutEp.getZobristHash(), board.getZobristHash());
        assertNotEquals(PolyglotKey.compute(withoutEp), PolyglotKey.compute(board));
        board.unmakeMove();
        board.unmakeMove();
        assertEquals(rootHash, board.getZobristHash());
    }

    @Test
    void searchSnapshotsDistinguishGameHistoryFromSearchCycles() {
        Board played = new Board();
        int[] cycle = {Move.of(62, 45), Move.of(6, 21), Move.of(45, 62), Move.of(21, 6)};
        for (int i = 0; i < 3; i++) played.makeMove(cycle[i]);

        Board.SearchSnapshot snapshot = played.snapshotForSearch();
        Board worker = snapshot.newBoard();
        Board helper = snapshot.newBoard();
        worker.makeMove(cycle[3]);
        assertFalse(worker.isThreefoldRepetition());
        assertFalse(worker.isRepetitionDraw(), "one occurrence before the root is not a draw");
        assertEquals(played.toFen(), helper.toFen(), "workers own independent board state");
        for (int move : cycle) worker.makeMove(move);
        assertTrue(worker.isThreefoldRepetition());
        assertTrue(worker.isRepetitionDraw());

        helper.makeMove(cycle[3]);
        for (int i = 0; i < 3; i++) helper.makeMove(cycle[i]);
        assertFalse(helper.isThreefoldRepetition());
        assertTrue(helper.isRepetitionDraw(), "revisiting the root is a search cycle");
        var nullMove = helper.makeNullMove();
        assertFalse(helper.isRepetitionDraw());
        helper.unmakeNullMove(nullMove);
        assertTrue(helper.isRepetitionDraw());
    }

    @Test
    void doublePushOnlyHashesEnPassantWhenACaptureIsLegal() {
        verifyEnPassantHash("k3r3/3p4/8/4P3/8/8/8/4K3 b - - 0 1", 11, 27, 28, 19, false);
        verifyEnPassantHash("k7/3p4/8/4P3/8/8/8/4K3 b - - 0 1", 11, 27, 28, 19, true);
        verifyEnPassantHash("k7/3p4/8/4P3/4K3/8/8/8 b - - 0 1", 11, 27, 28, 19, true);
        verifyEnPassantHash("k7/8/8/r4pPK/8/8/8/8 w - f6 0 2");
        verifyEnPassantHash("4k3/8/8/8/4p3/8/3P4/K3R3 w - - 0 1", 51, 35, 36, 43, false);
        verifyEnPassantHash("k3r3/3p4/8/2P1P3/8/8/8/4K3 b - - 0 1", 11, 27, 26, 19, true);
    }

    private static void verifyEnPassantHash(String before, int from, int to, int capturer, int target, boolean legal) {
        Board board = new Board(before);
        board.makeMove(Move.of(from, to, Move.FLAG_EP_TARGET));
        Board rebuilt = new Board(board.toFen());
        assertEquals(rebuilt.getZobristHash(), board.getZobristHash());
        Board withoutEp = new Board(board.toFen().replaceFirst(" [a-h][36] ", " - "));
        assertEquals(legal, withoutEp.getZobristHash() != board.getZobristHash());
        assertNotEquals(PolyglotKey.compute(withoutEp), PolyglotKey.compute(board));
        if (legal) {
            long beforeCapture = board.getZobristHash();
            board.makeMove(Move.of(capturer, target, Move.FLAG_EN_PASSANT));
            assertEquals(new Board(board.toFen()).getZobristHash(), board.getZobristHash());
            board.unmakeMove();
            assertEquals(beforeCapture, board.getZobristHash());
        }
        board.unmakeMove();
        assertEquals(new Board(before).getZobristHash(), board.getZobristHash());
    }

    private static void verifyEnPassantHash(String pinned) {
        Board board = new Board(pinned);
        Board withoutEp = new Board(pinned.replaceFirst(" [a-h][36] ", " - "));
        assertEquals(withoutEp.getZobristHash(), board.getZobristHash());
        assertNotEquals(PolyglotKey.compute(withoutEp), PolyglotKey.compute(board));
    }

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
