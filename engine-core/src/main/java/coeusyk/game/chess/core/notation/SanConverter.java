package coeusyk.game.chess.core.notation;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.models.Piece;
import coeusyk.game.chess.core.movegen.MovesGenerator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SanConverter {

    public String toSan(Move move, Board board) {
        if (move == null || board == null) {
            return null;
        }

        int movingPiece = board.getPiece(move.startSquare);
        int pieceType = Piece.type(movingPiece);

        StringBuilder san = new StringBuilder();

        if ("castle-k".equals(move.reaction)) {
            san.append("O-O");
        } else if ("castle-q".equals(move.reaction)) {
            san.append("O-O-O");
        } else {
            boolean isCapture = isCapture(board, move);
            String targetSquare = squareToAlgebraic(move.targetSquare);

            if (pieceType == Piece.Pawn) {
                if (isCapture) {
                    san.append(fileChar(move.startSquare));
                    san.append('x');
                }
                san.append(targetSquare);
                appendPromotionSuffix(san, move.reaction);
            } else {
                san.append(pieceLetter(pieceType));
                san.append(buildDisambiguation(move, board, pieceType));
                if (isCapture) {
                    san.append('x');
                }
                san.append(targetSquare);
            }
        }

        appendCheckOrMateSuffix(san, board, move);
        return san.toString();
    }

    public Move fromSan(String san, Board board) {
        if (san == null || board == null) {
            return null;
        }

        String normalizedInput = normalizeSan(san);
        if (normalizedInput.isEmpty()) {
            return null;
        }

        ArrayList<Move> legalMoves = new MovesGenerator(board).getActiveMoves(board.getActiveColor());
        for (Move move : legalMoves) {
            String candidate = normalizeSan(toSan(move, board));
            if (normalizedInput.equals(candidate)) {
                return move;
            }
        }

        return null;
    }

    private boolean isCapture(Board board, Move move) {
        return "en-passant".equals(move.reaction) || board.getPiece(move.targetSquare) != Piece.None;
    }

    private String buildDisambiguation(Move move, Board board, int pieceType) {
        List<Move> candidates = new ArrayList<>();
        ArrayList<Move> legalMoves = new MovesGenerator(board).getActiveMoves(board.getActiveColor());

        for (Move legalMove : legalMoves) {
            if (legalMove.startSquare == move.startSquare && legalMove.targetSquare == move.targetSquare) {
                continue;
            }

            if (legalMove.targetSquare != move.targetSquare) {
                continue;
            }

            int candidatePiece = board.getPiece(legalMove.startSquare);
            if (Piece.type(candidatePiece) == pieceType) {
                candidates.add(legalMove);
            }
        }

        if (candidates.isEmpty()) {
            return "";
        }

        int moveFile = move.startSquare % 8;
        int moveRank = move.startSquare / 8;

        boolean sameFileExists = false;
        boolean sameRankExists = false;

        for (Move candidate : candidates) {
            int file = candidate.startSquare % 8;
            int rank = candidate.startSquare / 8;

            if (file == moveFile) {
                sameFileExists = true;
            }
            if (rank == moveRank) {
                sameRankExists = true;
            }
        }

        if (!sameFileExists) {
            return String.valueOf(fileChar(move.startSquare));
        }

        if (!sameRankExists) {
            return String.valueOf(rankChar(move.startSquare));
        }

        return "" + fileChar(move.startSquare) + rankChar(move.startSquare);
    }

    private void appendPromotionSuffix(StringBuilder san, String reaction) {
        if (reaction == null) {
            return;
        }

        switch (reaction) {
            case "promote-q" -> san.append("=Q");
            case "promote-r" -> san.append("=R");
            case "promote-b" -> san.append("=B");
            case "promote-n" -> san.append("=N");
            default -> {
            }
        }
    }

    private void appendCheckOrMateSuffix(StringBuilder san, Board board, Move move) {
        board.makeMove(move);
        try {
            if (board.isCheckmate()) {
                san.append('#');
            } else if (board.isActiveColorInCheck()) {
                san.append('+');
            }
        } finally {
            board.unmakeMove();
        }
    }

    private char pieceLetter(int pieceType) {
        return switch (pieceType) {
            case Piece.Knight -> 'N';
            case Piece.Bishop -> 'B';
            case Piece.Rook -> 'R';
            case Piece.Queen -> 'Q';
            case Piece.King -> 'K';
            default -> '\0';
        };
    }

    private String squareToAlgebraic(int square) {
        return "" + fileChar(square) + rankChar(square);
    }

    private char fileChar(int square) {
        return (char) ('a' + (square % 8));
    }

    private char rankChar(int square) {
        return (char) ('8' - (square / 8));
    }

    private String normalizeSan(String san) {
        String normalized = san.trim();
        if (normalized.isEmpty()) {
            return "";
        }

        normalized = normalized
                .replace("0-0-0", "O-O-O")
                .replace("0-0", "O-O")
                .replace("e.p.", "")
                .replace("ep", "")
                .replace("+", "")
                .replace("#", "")
                .replace("!", "")
                .replace("?", "")
                .replace(";", "")
                .trim();

        if (normalized.endsWith("+") || normalized.endsWith("#")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        return normalized.toUpperCase(Locale.ROOT).replace("O", "O");
    }
}