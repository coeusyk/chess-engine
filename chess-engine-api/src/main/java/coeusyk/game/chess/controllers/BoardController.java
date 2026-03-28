package coeusyk.game.chess.controllers;

import coeusyk.game.chess.services.ChessGameService;
import coeusyk.game.chess.utils.*;
import org.springframework.lang.NonNull;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/engine")
@CrossOrigin
public class BoardController {
    private final ChessGameService chessGameService;

    public BoardController(ChessGameService chessGameService) {
        this.chessGameService = chessGameService;
    }

    @GetMapping("/setup")
    public SetupContainer setup(@RequestParam(name = "gameId", required = false) String gameId) {
        return chessGameService.setup(gameId);
    }

    @GetMapping("/get-piece-moves")
    public PieceMovesContainer getPieceMoves(@RequestParam(name = "pieceSquare") int pieceSquare,
                                             @RequestParam(name = "gameId", required = false) String gameId) {
        return chessGameService.getPieceMoves(pieceSquare, gameId);
    }

    @GetMapping("/get-king-in-check")
    public KingInCheckContainer getKingSquare(@RequestParam(name = "activeColor") int activeColor,
                                              @RequestParam(name = "gameId", required = false) String gameId) {
        return chessGameService.getKingInCheck(activeColor, gameId);
    }

    @PutMapping("/load-fen")
    public void loadFen(@RequestBody @NonNull FENStringHandler fenStringHandler,
                        @RequestParam(name = "gameId", required = false) String gameId) {
        chessGameService.loadFen(fenStringHandler, gameId);
    }

    @PutMapping("/new-game")
    public void newGame(@RequestParam(name = "gameId", required = false) String gameId) {
        chessGameService.newGame(gameId);
    }

    @PutMapping("/make-move")
    public ResponseContainer makeMove(@RequestBody @NonNull MoveHandler moveHandler,
                                      @RequestParam(name = "gameId", required = false) String gameId) {
        return chessGameService.makeMove(moveHandler, gameId);
    }
}