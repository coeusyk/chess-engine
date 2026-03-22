package coeusyk.game.chess.core.bitboard;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Piece;

/**
 * Phase 0 placeholder for the bitboard-based position model.
 *
 * This class establishes the direction of travel for engine-core. The current
 * move generator still uses the legacy square-array board while Phase 1+ work
 * migrates to this representation.
 */
public class BitboardPosition {
    // White piece bitboards
    public long whitePawns;
    public long whiteKnights;
    public long whiteBishops;
    public long whiteRooks;
    public long whiteQueens;
    public long whiteKing;

    // Black piece bitboards
    public long blackPawns;
    public long blackKnights;
    public long blackBishops;
    public long blackRooks;
    public long blackQueens;
    public long blackKing;

    // Occupancy masks
    public long whiteOccupancy;
    public long blackOccupancy;
    public long allOccupancy;

    // Side to move and rule state
    public boolean whiteToMove;
    public int castlingRights;
    public int enPassantSquare;
    public int halfmoveClock;
    public int fullmoveNumber;

    public static BitboardPosition fromBoard(Board board) {
        BitboardPosition position = new BitboardPosition();

        for (int square = 0; square < 64; square++) {
            int piece = board.getPiece(square);
            if (piece == Piece.None) {
                continue;
            }

            long bit = 1L << square;
            int pieceType = Piece.type(piece);

            if (Piece.isWhite(piece)) {
                switch (pieceType) {
                    case Piece.Pawn -> position.whitePawns |= bit;
                    case Piece.Knight -> position.whiteKnights |= bit;
                    case Piece.Bishop -> position.whiteBishops |= bit;
                    case Piece.Rook -> position.whiteRooks |= bit;
                    case Piece.Queen -> position.whiteQueens |= bit;
                    case Piece.King -> position.whiteKing |= bit;
                    default -> throw new IllegalStateException("unexpected white piece type: " + pieceType);
                }
            } else {
                switch (pieceType) {
                    case Piece.Pawn -> position.blackPawns |= bit;
                    case Piece.Knight -> position.blackKnights |= bit;
                    case Piece.Bishop -> position.blackBishops |= bit;
                    case Piece.Rook -> position.blackRooks |= bit;
                    case Piece.Queen -> position.blackQueens |= bit;
                    case Piece.King -> position.blackKing |= bit;
                    default -> throw new IllegalStateException("unexpected black piece type: " + pieceType);
                }
            }
        }

        position.whiteToMove = Piece.isWhite(board.getActiveColor());
        position.castlingRights = toCastlingRights(board.getCastlingAvailability());
        position.enPassantSquare = board.getEpTargetSquare();
        position.halfmoveClock = board.getHalfmoveClock();
        position.fullmoveNumber = board.getFullMoves();
        position.recomputeOccupancy();

        return position;
    }

    public void recomputeOccupancy() {
        whiteOccupancy = whitePawns | whiteKnights | whiteBishops | whiteRooks | whiteQueens | whiteKing;
        blackOccupancy = blackPawns | blackKnights | blackBishops | blackRooks | blackQueens | blackKing;
        allOccupancy = whiteOccupancy | blackOccupancy;
    }

    private static int toCastlingRights(boolean[] castlingAvailability) {
        int rights = 0;

        if (castlingAvailability[0]) {
            rights |= 1;
        }
        if (castlingAvailability[1]) {
            rights |= 2;
        }
        if (castlingAvailability[2]) {
            rights |= 4;
        }
        if (castlingAvailability[3]) {
            rights |= 8;
        }

        return rights;
    }
}
