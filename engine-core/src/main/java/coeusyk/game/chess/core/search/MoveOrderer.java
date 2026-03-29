package coeusyk.game.chess.core.search;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.models.Piece;

import java.util.List;

public class MoveOrderer {
    private static final int TT_MOVE_BONUS = 2_000_000;
    private static final int CAPTURE_BASE = 1_000_000;
    private static final int LOSING_CAPTURE_BASE = -100_000;
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

    private final StaticExchangeEvaluator staticExchangeEvaluator = new StaticExchangeEvaluator();

    // Per-instance scoring buffer avoids allocating ScoredMove objects per orderMoves call.
    // Each Searcher owns exactly one MoveOrderer and never shares it across threads.
    private final int[] scoringBuffer = new int[256];

    public List<Move> orderMoves(
            Board board,
            List<Move> moves,
            int ply,
            Move ttMove,
            Move[][] killerMoves,
            int[][] historyHeuristic
    ) {
        int n = moves.size();
        if (n <= 1) return moves;

        // Score all moves into the pre-allocated buffer.
        for (int i = 0; i < n; i++) {
            scoringBuffer[i] = scoreMove(board, moves.get(i), ply, ttMove, killerMoves, historyHeuristic);
        }

        // Insertion sort descending by score, applied in-place on the moves list.
        // Insertion sort outperforms Arrays.sort for the typical N<40 move counts.
        for (int i = 1; i < n; i++) {
            int score = scoringBuffer[i];
            Move move  = moves.get(i);
            int j = i - 1;
            while (j >= 0 && scoringBuffer[j] < score) {
                scoringBuffer[j + 1] = scoringBuffer[j];
                moves.set(j + 1, moves.get(j));
                j--;
            }
            scoringBuffer[j + 1] = score;
            moves.set(j + 1, move);
        }

        return moves; // same reference, now sorted descending
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
            int seeScore = staticExchangeEvaluator.evaluate(board, move);
            if (seeScore < 0) {
                return LOSING_CAPTURE_BASE + seeScore;
            }
            return CAPTURE_BASE + mvvLvaScore(board, move) + Math.min(seeScore, 10_000);
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