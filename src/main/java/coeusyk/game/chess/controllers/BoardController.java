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

    @GetMapping("/setup")
    public SetupContainer setup() {
        movesGen = new MovesGenerator(board);
        ArrayList<Move> possibleMoves = movesGen.getActiveMoves(board.getActiveColor());

        SetupContainer setupContainer = new SetupContainer(board, possibleMoves);

        return setupContainer;
    }

    @GetMapping("/get-piece-moves")
    public PieceMovesContainer getPieceMoves(@RequestParam @NonNull int pieceSquare) {
        ArrayList<Move> pieceMoves = movesGen.getPieceMoves(pieceSquare);

        PieceMovesContainer pieceMovesContainer = new PieceMovesContainer(pieceMoves);

        return pieceMovesContainer;
    }

    @PutMapping("/load-fen")
    public void loadFen(@RequestBody @NonNull FENStringHandler fenStringHandler) {
        board = new Board(fenStringHandler.getFenString());
    }

    @PutMapping("/make-move")
    public ResponseContainer makeMove(@RequestBody @NonNull MoveHandler moveHandler) {
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

    /**
     * Run an iterative-deepening search to the given depth and return the
     * best move together with the principal variation in UCI info format.
     *
     * @param depth maximum search depth (1..{@value Search#MAX_DEPTH}, default 4)
     */
    @GetMapping("/search")
    public SearchResult search(@RequestParam(defaultValue = "4") int depth) {
        if (depth < 1 || depth > Search.MAX_DEPTH) {
            throw new IllegalArgumentException(
                    "depth must be between 1 and " + Search.MAX_DEPTH + ", got " + depth);
        }
        Search search = new Search();
        return search.search(board, depth);
    }
}