package coeusyk.game.chess.services;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.movegen.MovesGenerator;

public class GameSession {
    private Board board;
    private MovesGenerator movesGenerator;
    private final Object lock = new Object();

    public GameSession() {
        this.board = new Board();
        this.movesGenerator = new MovesGenerator(this.board);
    }

    public Board getBoard() {
        return board;
    }

    public void setBoard(Board board) {
        this.board = board;
    }

    public MovesGenerator getMovesGenerator() {
        return movesGenerator;
    }

    public void setMovesGenerator(MovesGenerator movesGenerator) {
        this.movesGenerator = movesGenerator;
    }

    public Object getLock() {
        return lock;
    }
}
