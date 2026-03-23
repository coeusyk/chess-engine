package coeusyk.game.chess.utils;

import coeusyk.game.chess.core.models.Board;

import java.util.ArrayList;


public class SetupContainer {
    public Board board;
    public ArrayList<MoveNotation> possibleMoves;

    public SetupContainer(Board board, ArrayList<MoveNotation> possibleMoves) {
        this.board = board;
        this.possibleMoves = possibleMoves;
    }
}