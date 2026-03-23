package coeusyk.game.chess.utils;

public class MoveNotation {
    public int startSquare;
    public int targetSquare;
    public String reaction;
    public String uci;
    public String san;

    public MoveNotation(int startSquare, int targetSquare, String reaction, String uci, String san) {
        this.startSquare = startSquare;
        this.targetSquare = targetSquare;
        this.reaction = reaction;
        this.uci = uci;
        this.san = san;
    }
}