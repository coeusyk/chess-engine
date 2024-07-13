package coeusyk.game.chess.models;


public class Move {
    public int startSquare;
    public int targetSquare;
    public String reaction;

    public Move(int start, int target) {
        this.startSquare = start;
        this.targetSquare = target;
        this.reaction = null;
    }

    public Move(int start, int target, String reaction) {
        this.startSquare = start;
        this.targetSquare = target;
        this.reaction = reaction;
    }
}