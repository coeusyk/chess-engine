package coeusyk.game.chess.core.eval.nnue;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.models.Piece;

/**
 * Board bitboards -&gt; active feature indices, for the plain 768 dual-perspective
 * feature set (PRD §3): piece(6) x color(2) x square(64), non-king-relative — the
 * v1 feature set explicitly excludes HalfKP/HalfKA (PRD §2 Non-Goals), so no
 * king-move/castling accumulator refresh is required; every move type below is a
 * pure incremental delta.
 *
 * <p>This is the one place the index formula is defined; the future Python
 * {@code FeatureEncoder} must reproduce it exactly (see
 * {@code FeatureIndexParityTest} for the shared-corpus fixture that pins it).
 */
public final class FeatureExtractor {

    public static final int PIECE_TYPES = 6;
    public static final int SQUARES = 64;
    public static final int FEATURES_PER_PERSPECTIVE = 2 * PIECE_TYPES * SQUARES; // 768

    private FeatureExtractor() {
    }

    /**
     * Feature index for one (piece, square) fact as seen from {@code perspectiveColor}.
     * Layout: {@code relativeColor(2) * 384 + (pieceType-1)(6) * 64 + relativeSquare(64)}.
     * {@code relativeColor} is 0 for "us" (same color as the perspective), 1 for "them".
     * {@code relativeSquare} mirrors vertically ({@code square ^ 56}) for black's
     * perspective, matching PRD §3's "black view mirrors square vertically and swaps
     * piece color."
     */
    public static int featureIndex(int perspectiveColor, int pieceColor, int pieceType, int square) {
        int relativeColor = (pieceColor == perspectiveColor) ? 0 : 1;
        int relativeSquare = (perspectiveColor == Piece.White) ? square : (square ^ 56);
        int pieceTypeIndex = pieceType - 1;
        return relativeColor * (PIECE_TYPES * SQUARES) + pieceTypeIndex * SQUARES + relativeSquare;
    }

    /** Receives one (color, type, square) fact per piece currently on the board. */
    public interface PieceVisitor {
        void accept(int pieceColor, int pieceType, int square);
    }

    /** Enumerates every piece on {@code board} — the full-rebuild path for {@code reset()}. */
    public static void forEachPiece(Board board, PieceVisitor visitor) {
        visitBitboard(board.getWhitePawns(), Piece.White, Piece.Pawn, visitor);
        visitBitboard(board.getWhiteKnights(), Piece.White, Piece.Knight, visitor);
        visitBitboard(board.getWhiteBishops(), Piece.White, Piece.Bishop, visitor);
        visitBitboard(board.getWhiteRooks(), Piece.White, Piece.Rook, visitor);
        visitBitboard(board.getWhiteQueens(), Piece.White, Piece.Queen, visitor);
        visitBitboard(board.getWhiteKing(), Piece.White, Piece.King, visitor);
        visitBitboard(board.getBlackPawns(), Piece.Black, Piece.Pawn, visitor);
        visitBitboard(board.getBlackKnights(), Piece.Black, Piece.Knight, visitor);
        visitBitboard(board.getBlackBishops(), Piece.Black, Piece.Bishop, visitor);
        visitBitboard(board.getBlackRooks(), Piece.Black, Piece.Rook, visitor);
        visitBitboard(board.getBlackQueens(), Piece.Black, Piece.Queen, visitor);
        visitBitboard(board.getBlackKing(), Piece.Black, Piece.King, visitor);
    }

    /**
     * The PRD's "active feature dump" debug/test tool: every active feature index for
     * {@code perspectiveColor} on {@code board}, sorted ascending. Not on the search
     * path (allocates) — test/debug scope only, per PRD §"Developer Tooling".
     */
    public static int[] activeFeatureIndices(Board board, int perspectiveColor) {
        int[] buffer = new int[32]; // at most 32 pieces on a legal chess position
        int[] count = {0};
        forEachPiece(board, (pieceColor, pieceType, square) ->
                buffer[count[0]++] = featureIndex(perspectiveColor, pieceColor, pieceType, square));
        int[] result = java.util.Arrays.copyOf(buffer, count[0]);
        java.util.Arrays.sort(result);
        return result;
    }

    private static void visitBitboard(long bitboard, int pieceColor, int pieceType, PieceVisitor visitor) {
        long bb = bitboard;
        while (bb != 0) {
            int square = Long.numberOfTrailingZeros(bb);
            visitor.accept(pieceColor, pieceType, square);
            bb &= bb - 1;
        }
    }

    /** Receives one absolute (color, type, square) feature removal/addition for a single move. */
    public interface ChangeVisitor {
        void remove(int pieceColor, int pieceType, int square);
        void add(int pieceColor, int pieceType, int square);
    }

    /**
     * Computes the absolute (color-agnostic-perspective) feature changes for the move
     * just made on {@code board} (called immediately after {@code board.makeMove}, same
     * as {@code EvaluatorStrategy.onMake}). {@code capturedPiece} is
     * {@code board.lastCapturedPiece()} at the same call site. Covers every move type:
     * quiet, capture, en passant, castling, promotion, promotion+capture; null moves
     * never reach here (no {@code move} to decode).
     *
     * <p>Per-perspective feature indices are derived from these absolute facts via
     * {@link #featureIndex} — this method runs its move-type branch once, not once per
     * perspective, so the branching isn't duplicated for the two accumulators.
     */
    public static void forEachChange(Board board, int move, int capturedPiece, ChangeVisitor visitor) {
        int from = Move.from(move);
        int to = Move.to(move);
        int flag = Move.flag(move);
        // Post-move board already reflects the (possibly promoted) piece at `to`.
        int postMovePiece = board.getPiece(to);
        int moverColor = Piece.color(postMovePiece);

        if (Move.isCastling(move)) {
            visitor.remove(moverColor, Piece.King, from);
            visitor.add(moverColor, Piece.King, to);
            boolean kingside = flag == Move.FLAG_CASTLING_K;
            int rookFrom = kingside ? from + 3 : from - 4;
            int rookTo = kingside ? to - 1 : to + 1;
            visitor.remove(moverColor, Piece.Rook, rookFrom);
            visitor.add(moverColor, Piece.Rook, rookTo);
            return;
        }

        if (Move.isEnPassant(move)) {
            visitor.remove(moverColor, Piece.Pawn, from);
            visitor.add(moverColor, Piece.Pawn, to);
            int epCaptureSquare = (moverColor == Piece.White) ? to + 8 : to - 8;
            int opponentColor = (moverColor == Piece.White) ? Piece.Black : Piece.White;
            visitor.remove(opponentColor, Piece.Pawn, epCaptureSquare);
            return;
        }

        // Quiet, capture, promotion, and promotion+capture all share this shape —
        // the only thing that varies is whether a piece was removed at `to` beforehand
        // (capturedPiece) and whether the piece type changed in flight (isPromotion).
        int fromPieceType = Move.isPromotion(move) ? Piece.Pawn : Piece.type(postMovePiece);
        visitor.remove(moverColor, fromPieceType, from);
        visitor.add(moverColor, Piece.type(postMovePiece), to);
        if (capturedPiece != Piece.None) {
            visitor.remove(Piece.color(capturedPiece), Piece.type(capturedPiece), to);
        }
    }
}
