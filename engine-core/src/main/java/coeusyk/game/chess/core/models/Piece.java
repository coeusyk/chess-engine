package coeusyk.game.chess.core.models;


public class Piece {
    /*
    Each piece and color will be represented using binary form. For example:
    A black rook - 16 | 4, i.e., 10100
    A white queen - 8 | 5, i.e., 01101,
    and so on...
     */

    public final static int None = 0;
    public final static int Pawn = 1;
    public final static int Knight = 2;
    public final static int Bishop = 3;
    public final static int Rook = 4;
    public final static int Queen = 5;
    public final static int King = 6;

    public final static int White = 8;
    public final static int Black = 16;

    public static int type(int id) {
        // Checking if the piece is white:
        int piece = id & ~0b1000;

        return (piece == id) ? piece & ~0b10000 : piece;
    }

    public static int color(int id) {
        if (id >= 16) {
            return Piece.Black;
        } else if (id >= 8) {
            return Piece.White;
        }

        return 0;
    }

    /**
     * Checks if the piece is capable of having a reaction or not
     *
     * @param id The piece to check
     */
    public static boolean hasReaction(int id) {
        int pieceType = type(id);
        return pieceType == Pawn || pieceType == King;
    }

    public static boolean isSliding(int id) {
        int pieceType = type(id);
        return pieceType == Bishop || pieceType == Queen || pieceType == Rook;
    }

    /**
     * Checks if the 1st piece is of the same color as of the second, i.e., if the 2nd piece is friendly or not
     *
     * @param piece1 The piece to compare with
     * @param piece2 The piece to check
     */
    public static boolean isColor(int piece1, int piece2) {
        return Piece.color(piece1) == Piece.color(piece2);
    }

    public static boolean isWhite(int id) {
        return id >= 8 && id < 16;
    }

    public static boolean isBlack(int id) {
        return id >= 16;
    }
}