package coeusyk.game.chess.utils;


public class MoveHandler {
    public int startSquare;
    public int targetSquare;
    /** Optional promotion piece: q, r, b or n. Absent means the generator's default (queen). */
    public String promotion;

    public int[] getMoveDetails() {
        return new int[] { startSquare, targetSquare };
    }
}