package coeusyk.game.chess.utils;


public class MoveHandler {
    public int startSquare;
    public int targetSquare;

    public int[] getMoveDetails() {
        return new int[] { startSquare, targetSquare };
    }
}