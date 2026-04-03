package coeusyk.game.chess.services;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.movegen.MovesGenerator;

import java.util.ArrayDeque;
import java.util.Deque;

public class GameSession {
    private Board board;
    private MovesGenerator movesGenerator;
    private final Deque<Move> redoStack = new ArrayDeque<>();
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

    public Deque<Move> getRedoStack() {
        return redoStack;
    }

    public Object getLock() {
        return lock;
    }
}
