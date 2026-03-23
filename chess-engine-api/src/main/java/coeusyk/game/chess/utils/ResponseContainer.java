package coeusyk.game.chess.utils;

import coeusyk.game.chess.core.models.Board;

import java.util.ArrayList;


public class ResponseContainer {
    public boolean success;
    public Board board;
    public ArrayList<MoveNotation> possibleMoves;

    public ResponseContainer(boolean status) {
        this.success = status;
    }

    public ResponseContainer(boolean status, Board board, ArrayList<MoveNotation> possibleMoves) {
        this.success = status;
        this.possibleMoves = possibleMoves;
        this.board = board;
    }
}
