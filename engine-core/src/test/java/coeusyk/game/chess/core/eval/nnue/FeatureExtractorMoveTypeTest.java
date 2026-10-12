package coeusyk.game.chess.core.eval.nnue;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.models.Piece;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Direct, hand-picked assertions on {@link FeatureExtractor#forEachChange} for every
 * move-type-matrix case. The incremental-vs-rebuild fuzz test proves the accumulator
 * stays consistent, but random legal games essentially never produce castling, en
 * passant, or promotion within a short ply budget (an agentic-eval review of this
 * PR flagged that gap directly) — these fixtures pin exactly what
 * {@code forEachChange} must emit for those rare cases, independent of the RNG.
 */
class FeatureExtractorMoveTypeTest {

    @Test
    void quietMoveRemovesFromSquareAndAddsToSquare() {
        Board board = new Board("4k3/8/8/8/8/8/8/4K3 w - - 0 1");
        int e1 = 60;
        int e2 = 52;
        int move = Move.of(e1, e2);
        board.makeMove(move);
        int capturedPiece = board.lastCapturedPiece();

        assertChanges(board, move, capturedPiece,
                Set.of(entry(Piece.White, Piece.King, e1)),
                Set.of(entry(Piece.White, Piece.King, e2)));
    }

    @Test
    void captureRemovesCapturedPieceAtTargetSquare() {
        Board board = new Board("4k3/8/8/8/8/2p5/8/1N2K3 w - - 0 1");
        int b1 = 57;
        int c3 = 42;
        int move = Move.of(b1, c3);
        board.makeMove(move);
        int capturedPiece = board.lastCapturedPiece();
        assertEquals(Piece.Black | Piece.Pawn, capturedPiece);

        assertChanges(board, move, capturedPiece,
                Set.of(entry(Piece.White, Piece.Knight, b1),
                        entry(Piece.Black, Piece.Pawn, c3)),
                Set.of(entry(Piece.White, Piece.Knight, c3)));
    }

    @Test
    void enPassantRemovesMoverFromAndOpponentPawnAtCaptureSquareNotTargetSquare() {
        Board board = new Board("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1");
        int e5 = 28;
        int d6 = 19; // en passant target square (empty)
        int d5 = 27; // the actual captured black pawn's square
        int move = Move.of(e5, d6, Move.FLAG_EN_PASSANT);
        board.makeMove(move);
        int capturedPiece = board.lastCapturedPiece();
        assertEquals(Piece.None, capturedPiece, "capturedPiece field stays None for en passant — the "
                + "captured pawn is tracked separately, off the exposed accessor");

        assertChanges(board, move, capturedPiece,
                Set.of(entry(Piece.White, Piece.Pawn, e5),
                        entry(Piece.Black, Piece.Pawn, d5)),
                Set.of(entry(Piece.White, Piece.Pawn, d6)));
    }

    @Test
    void kingsideCastlingMovesBothKingAndRook() {
        Board board = new Board("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1");
        int e1 = 60;
        int g1 = 62;
        int h1 = 63;
        int f1 = 61;
        int move = Move.of(e1, g1, Move.FLAG_CASTLING_K);
        board.makeMove(move);
        int capturedPiece = board.lastCapturedPiece();
        assertEquals(Piece.None, capturedPiece);

        assertChanges(board, move, capturedPiece,
                Set.of(entry(Piece.White, Piece.King, e1),
                        entry(Piece.White, Piece.Rook, h1)),
                Set.of(entry(Piece.White, Piece.King, g1),
                        entry(Piece.White, Piece.Rook, f1)));
    }

    @Test
    void promotionRemovesPawnAndAddsPromotedPieceNoCaptureInvolved() {
        Board board = new Board("4k3/P7/8/8/8/8/8/4K3 w - - 0 1");
        int a7 = 8;
        int a8 = 0;
        int move = Move.of(a7, a8, Move.FLAG_PROMO_Q);
        board.makeMove(move);
        int capturedPiece = board.lastCapturedPiece();
        assertEquals(Piece.None, capturedPiece);

        assertChanges(board, move, capturedPiece,
                Set.of(entry(Piece.White, Piece.Pawn, a7)),
                Set.of(entry(Piece.White, Piece.Queen, a8)));
    }

    @Test
    void promotionWithCaptureRemovesPawnCapturedPieceAndAddsPromotedPiece() {
        Board board = new Board("1r2k3/P7/8/8/8/8/8/4K3 w - - 0 1");
        int a7 = 8;
        int b8 = 1;
        int move = Move.of(a7, b8, Move.FLAG_PROMO_Q);
        board.makeMove(move);
        int capturedPiece = board.lastCapturedPiece();
        assertEquals(Piece.Black | Piece.Rook, capturedPiece);

        assertChanges(board, move, capturedPiece,
                Set.of(entry(Piece.White, Piece.Pawn, a7),
                        entry(Piece.Black, Piece.Rook, b8)),
                Set.of(entry(Piece.White, Piece.Queen, b8)));
    }

    private static void assertChanges(Board board, int move, int capturedPiece,
                                       Set<String> expectedRemoves, Set<String> expectedAdds) {
        List<String> removes = new ArrayList<>();
        List<String> adds = new ArrayList<>();
        FeatureExtractor.forEachChange(board, move, capturedPiece, new FeatureExtractor.ChangeVisitor() {
            @Override
            public void remove(int pieceColor, int pieceType, int square) {
                removes.add(entry(pieceColor, pieceType, square));
            }

            @Override
            public void add(int pieceColor, int pieceType, int square) {
                adds.add(entry(pieceColor, pieceType, square));
            }
        });
        assertEquals(expectedRemoves, new HashSet<>(removes), "removed features");
        assertEquals(expectedAdds, new HashSet<>(adds), "added features");
    }

    private static String entry(int pieceColor, int pieceType, int square) {
        return pieceColor + ":" + pieceType + ":" + square;
    }
}
