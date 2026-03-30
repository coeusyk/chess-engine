package coeusyk.game.chess.core.search;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.models.Piece;
import coeusyk.game.chess.core.movegen.MovesGenerator;

import java.util.List;

public class StaticExchangeEvaluator {
    private static final int[] PIECE_VALUES = {
            0,
            100,
            320,
            330,
            500,
            900,
            20_000
    };

    public int evaluate(Board board, Move move) {
        if (!isCapture(board, move)) {
            return 0;
        }

        int capturedValue = capturedPieceValue(board, move);
        int promotionDelta = promotionDelta(move.reaction);

        board.makeMove(move);
        int lossByRecaptures = bestReplyGain(board, move.targetSquare, board.getActiveColor());
        board.unmakeMove();

        return capturedValue + promotionDelta - lossByRecaptures;
    }

    public int evaluateSquareOccupation(Board board, int targetSquare) {
        int occupant = board.getPiece(targetSquare);
        if (occupant == Piece.None) {
            return 0;
        }

        int occupantValue = PIECE_VALUES[Piece.type(occupant)];
        int lossByRecaptures = bestReplyGain(board, targetSquare, board.getActiveColor());
        return occupantValue - lossByRecaptures;
    }

    private int bestReplyGain(Board board, int targetSquare, int sideToMove) {
        Move recapture = findLeastValuableCapture(board, targetSquare, sideToMove);
        if (recapture == null) {
            return 0;
        }

        int capturedValue = capturedValueForRecapture(board, recapture, targetSquare) + promotionDelta(recapture.reaction);
        board.makeMove(recapture);
        int continuation = bestReplyGain(board, targetSquare, board.getActiveColor());
        board.unmakeMove();

        return Math.max(0, capturedValue - continuation);
    }

    private int capturedValueForRecapture(Board board, Move recapture, int targetSquare) {
        if ("en-passant".equals(recapture.reaction)) {
            return PIECE_VALUES[Piece.Pawn];
        }
        return PIECE_VALUES[Piece.type(board.getPiece(targetSquare))];
    }

    private Move findLeastValuableCapture(Board board, int targetSquare, int sideToMove) {
        List<Move> moves = new MovesGenerator(board).getActiveMoves(sideToMove);
        Move best = null;
        int bestValue = Integer.MAX_VALUE;

        for (Move move : moves) {
            if (move.targetSquare != targetSquare || !isCapture(board, move)) {
                continue;
            }

            int movingPiece = board.getPiece(move.startSquare);
            int value = PIECE_VALUES[Piece.type(movingPiece)];
            if (value < bestValue) {
                best = move;
                bestValue = value;
            }
        }

        return best;
    }

    private int capturedPieceValue(Board board, Move move) {
        if ("en-passant".equals(move.reaction)) {
            return PIECE_VALUES[Piece.Pawn];
        }

        return PIECE_VALUES[Piece.type(board.getPiece(move.targetSquare))];
    }

    private int promotionDelta(String reaction) {
        if (reaction == null) {
            return 0;
        }

        return switch (reaction) {
            case "promote-q" -> PIECE_VALUES[Piece.Queen] - PIECE_VALUES[Piece.Pawn];
            case "promote-r" -> PIECE_VALUES[Piece.Rook] - PIECE_VALUES[Piece.Pawn];
            case "promote-b" -> PIECE_VALUES[Piece.Bishop] - PIECE_VALUES[Piece.Pawn];
            case "promote-n" -> PIECE_VALUES[Piece.Knight] - PIECE_VALUES[Piece.Pawn];
            default -> 0;
        };
    }

    private boolean isCapture(Board board, Move move) {
        return "en-passant".equals(move.reaction) || board.getPiece(move.targetSquare) != Piece.None;
    }

    // ==================== Packed-int overloads ====================

    /**
     * SEE for a packed-int move. Returns 0 for non-captures.
     */
    public int evaluate(Board board, int packedMove) {
        if (!isCapture(board, packedMove)) {
            return 0;
        }

        int capturedValue = capturedPieceValue(board, packedMove);
        int promoDelta    = promotionDelta(Move.flag(packedMove));

        board.makeMove(packedMove);
        int lossByRecaptures = bestReplyGain(board, Move.to(packedMove), board.getActiveColor());
        board.unmakeMove();

        return capturedValue + promoDelta - lossByRecaptures;
    }

    private boolean isCapture(Board board, int move) {
        return Move.isEnPassant(move) || board.getPiece(Move.to(move)) != Piece.None;
    }

    private int capturedPieceValue(Board board, int move) {
        if (Move.isEnPassant(move)) {
            return PIECE_VALUES[Piece.Pawn];
        }
        return PIECE_VALUES[Piece.type(board.getPiece(Move.to(move)))];
    }

    private int promotionDelta(int flag) {
        return switch (flag) {
            case Move.FLAG_PROMO_Q -> PIECE_VALUES[Piece.Queen]  - PIECE_VALUES[Piece.Pawn];
            case Move.FLAG_PROMO_R -> PIECE_VALUES[Piece.Rook]   - PIECE_VALUES[Piece.Pawn];
            case Move.FLAG_PROMO_B -> PIECE_VALUES[Piece.Bishop] - PIECE_VALUES[Piece.Pawn];
            case Move.FLAG_PROMO_N -> PIECE_VALUES[Piece.Knight] - PIECE_VALUES[Piece.Pawn];
            default -> 0;
        };
    }
}
