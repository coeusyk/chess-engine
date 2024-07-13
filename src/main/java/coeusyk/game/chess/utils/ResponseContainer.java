package coeusyk.game.chess.utils;

import coeusyk.game.chess.models.Board;
import coeusyk.game.chess.models.Move;

import java.util.ArrayList;


public class ResponseContainer {
    public boolean success;
    public Board board;
    public ArrayList<Move> possibleMoves;

    public ResponseContainer(boolean status) {
        this.success = status;
    }

    public ResponseContainer(boolean status, Board board, ArrayList<Move> possibleMoves) {
        this.success = status;
        this.possibleMoves = possibleMoves;
        this.board = board;
    }
}
