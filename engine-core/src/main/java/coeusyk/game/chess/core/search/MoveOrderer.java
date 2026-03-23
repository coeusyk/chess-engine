package coeusyk.game.chess.core.search;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.models.Piece;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class MoveOrderer {
    private static final int TT_MOVE_BONUS = 2_000_000;
    private static final int CAPTURE_BASE = 1_000_000;
    private static final int KILLER_1_BONUS = 900_000;
    private static final int KILLER_2_BONUS = 800_000;

    private static final int[] PIECE_VALUES = {
            0,   // None
            100, // Pawn
            320, // Knight
            330, // Bishop
            500, // Rook
            900, // Queen
            10_000 // King
    };

    public List<Move> orderMoves(
            Board board,
            List<Move> moves,
            int ply,
            Move ttMove,
            Move[][] killerMoves,
            int[][] historyHeuristic
    ) {
        List<Move> ordered = new ArrayList<>(moves);
        ordered.sort(Comparator.comparingInt((Move m) -> scoreMove(board, m, ply, ttMove, killerMoves, historyHeuristic)).reversed());
        return ordered;
    }

    int scoreMove(
            Board board,
            Move move,
            int ply,
            Move ttMove,
            Move[][] killerMoves,
            int[][] historyHeuristic
    ) {
        if (ttMove != null && sameMove(move, ttMove)) {
            return TT_MOVE_BONUS;
        }

        if (isCapture(board, move)) {
            return CAPTURE_BASE + mvvLvaScore(board, move);
        }

        if (killerMoves[ply][0] != null && sameMove(move, killerMoves[ply][0])) {
            return KILLER_1_BONUS;
        }

        if (killerMoves[ply][1] != null && sameMove(move, killerMoves[ply][1])) {
            return KILLER_2_BONUS;
        }

        int movingPiece = board.getPiece(move.startSquare);
        int pieceType = Piece.type(movingPiece);
        if (pieceType <= 0 || pieceType >= historyHeuristic.length) {
            return 0;
        }

        return historyHeuristic[pieceType][move.targetSquare];
    }

    boolean isCapture(Board board, Move move) {
        return "en-passant".equals(move.reaction) || board.getPiece(move.targetSquare) != Piece.None;
    }

    private int mvvLvaScore(Board board, Move move) {
        int attackerPiece = board.getPiece(move.startSquare);
        int attackerValue = PIECE_VALUES[Piece.type(attackerPiece)];

        int victimValue;
        if ("en-passant".equals(move.reaction)) {
            victimValue = PIECE_VALUES[Piece.Pawn];
        } else {
            int victimPiece = board.getPiece(move.targetSquare);
            victimValue = PIECE_VALUES[Piece.type(victimPiece)];
        }

        return (victimValue * 16) - attackerValue;
    }

    boolean sameMove(Move a, Move b) {
        if (a.startSquare != b.startSquare || a.targetSquare != b.targetSquare) {
            return false;
        }

        if (a.reaction == null) {
            return b.reaction == null;
        }

        return a.reaction.equals(b.reaction);
    }
}