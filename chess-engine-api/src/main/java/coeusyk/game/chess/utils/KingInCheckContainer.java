package coeusyk.game.chess.utils;


public class KingInCheckContainer {
    public int kingSquare;
    public boolean isKingInCheck;

    public KingInCheckContainer(int kingSquare, boolean[] isKingInCheck) {
        this.kingSquare = kingSquare;
        this.isKingInCheck = isKingInCheck[0] || isKingInCheck[1];
    }
}
