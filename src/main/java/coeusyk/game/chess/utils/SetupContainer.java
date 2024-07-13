package coeusyk.game.chess.utils;

import coeusyk.game.chess.models.Board;
import coeusyk.game.chess.models.Move;

import java.util.ArrayList;


public class SetupContainer {
    public Board board;
    public ArrayList<Move> possibleMoves;

    public SetupContainer(Board board, ArrayList<Move> possibleMoves) {
        this.board = board;
        this.possibleMoves = possibleMoves;
    }
}