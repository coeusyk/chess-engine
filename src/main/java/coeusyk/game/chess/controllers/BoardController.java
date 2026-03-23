package coeusyk.game.chess.controllers;

import coeusyk.game.chess.models.*;
import coeusyk.game.chess.search.IterativeDeepeningSearch;
import coeusyk.game.chess.search.SearchResult;
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
     * Runs iterative-deepening alpha-beta search and applies the best move found.
     *
     * @param depth maximum search depth in plies (default: 3)
     */
    @GetMapping("/search")
    public ResponseContainer search(@RequestParam(defaultValue = "3") int depth) {
        IterativeDeepeningSearch searcher = new IterativeDeepeningSearch();
        SearchResult result = searcher.search(board, depth);

        if (result.bestMove != null) {
            board.makeMove(result.bestMove);
            movesGen = new MovesGenerator(board);
            return new ResponseContainer(true, board, movesGen.getActiveMoves(board.getActiveColor()));
        }

        return new ResponseContainer(false);
    }
}