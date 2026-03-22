package coeusyk.game.chess.controllers;

import coeusyk.game.chess.models.*;
import coeusyk.game.chess.utils.*;
import org.springframework.lang.NonNull;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;


@RestController
@RequestMapping("/engine")
@CrossOrigin
public class BoardController {
    Board board = new Board();
    MovesGenerator movesGen;
    private final Object boardLock = new Object();

    @GetMapping("/setup")
    public SetupContainer setup() {
        synchronized (boardLock) {
            movesGen = new MovesGenerator(board);
            ArrayList<Move> possibleMoves = movesGen.getActiveMoves(board.getActiveColor());

            SetupContainer setupContainer = new SetupContainer(board, possibleMoves);

            return setupContainer;
        }
    }

    @GetMapping("/get-piece-moves")
    public PieceMovesContainer getPieceMoves(@RequestParam @NonNull int pieceSquare) {
        synchronized (boardLock) {
            if (movesGen == null) {
                movesGen = new MovesGenerator(board);
            }

            ArrayList<Move> pieceMoves = movesGen.getPieceMoves(pieceSquare);
            PieceMovesContainer pieceMovesContainer = new PieceMovesContainer(pieceMoves);

            return pieceMovesContainer;
        }
    }

    @GetMapping("/get-king-in-check")
    public KingInCheckContainer getKingSquare(@RequestParam @NonNull int activeColor) {
        synchronized (boardLock) {
            if (movesGen == null) {
                movesGen = new MovesGenerator(board);
            }

            int kingSquare = board.getKingSquare(activeColor);
            boolean[] isKingInCheck = movesGen.isKingInCheck(kingSquare, activeColor);

            KingInCheckContainer kingSquareContainer = new KingInCheckContainer(kingSquare, isKingInCheck);

            return kingSquareContainer;
        }
    }

    @PutMapping("/load-fen")
    public void loadFen(@RequestBody @NonNull FENStringHandler fenStringHandler) {
        synchronized (boardLock) {
            board = new Board(fenStringHandler.getFenString());
            movesGen = new MovesGenerator(board);
        }
    }

    @PutMapping("/make-move")
    public ResponseContainer makeMove(@RequestBody @NonNull MoveHandler moveHandler) {
        synchronized (boardLock) {
            if (movesGen == null) {
                movesGen = new MovesGenerator(board);
            }

            int[] moveDetails = moveHandler.getMoveDetails();
            Move move = movesGen.findMove(moveDetails[0], moveDetails[1]);

            if (move != null) {
                // Updating the board and the possible moves:
                board.makeMove(move);
                movesGen = new MovesGenerator(board);

                return new ResponseContainer(true, board, movesGen.getActiveMoves(board.getActiveColor()));
            }

            return new ResponseContainer(false);
        }
    }
}