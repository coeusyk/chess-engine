package coeusyk.game.chess.utils;

import coeusyk.game.chess.core.models.Move;

import java.util.ArrayList;


public class PieceMovesContainer {
    public ArrayList<Move> pieceMoves;

    public PieceMovesContainer(ArrayList<Move> pieceMoves) {
        this.pieceMoves = pieceMoves;
    }
}