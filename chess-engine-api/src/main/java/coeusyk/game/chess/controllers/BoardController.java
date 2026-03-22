package coeusyk.game.chess.controllers;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.movegen.MovesGenerator;
import coeusyk.game.chess.services.GameSession;
import coeusyk.game.chess.services.GameSessionStore;
import coeusyk.game.chess.utils.*;
import org.springframework.lang.NonNull;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;


@RestController
@RequestMapping("/engine")
@CrossOrigin
public class BoardController {
    private final GameSessionStore sessionStore;

    public BoardController(GameSessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    private GameSession session(String gameId) {
        return sessionStore.getOrCreate(gameId);
    }

    @GetMapping("/setup")
    public SetupContainer setup(@RequestParam(required = false) String gameId) {
        GameSession session = session(gameId);
        synchronized (session.getLock()) {
            session.setMovesGenerator(new MovesGenerator(session.getBoard()));
            ArrayList<Move> possibleMoves = session.getMovesGenerator().getActiveMoves(session.getBoard().getActiveColor());

            SetupContainer setupContainer = new SetupContainer(session.getBoard(), possibleMoves);

            return setupContainer;
        }
    }

    @GetMapping("/get-piece-moves")
    public PieceMovesContainer getPieceMoves(@RequestParam @NonNull int pieceSquare,
                                             @RequestParam(required = false) String gameId) {
        GameSession session = session(gameId);
        synchronized (session.getLock()) {
            if (session.getMovesGenerator() == null) {
                session.setMovesGenerator(new MovesGenerator(session.getBoard()));
            }

            ArrayList<Move> pieceMoves = session.getMovesGenerator().getPieceMoves(pieceSquare);
            PieceMovesContainer pieceMovesContainer = new PieceMovesContainer(pieceMoves);

            return pieceMovesContainer;
        }
    }

    @GetMapping("/get-king-in-check")
    public KingInCheckContainer getKingSquare(@RequestParam @NonNull int activeColor,
                                              @RequestParam(required = false) String gameId) {
        GameSession session = session(gameId);
        synchronized (session.getLock()) {
            if (session.getMovesGenerator() == null) {
                session.setMovesGenerator(new MovesGenerator(session.getBoard()));
            }

            int kingSquare = session.getBoard().getKingSquare(activeColor);
            boolean[] isKingInCheck = session.getMovesGenerator().isKingInCheck(kingSquare, activeColor);

            KingInCheckContainer kingSquareContainer = new KingInCheckContainer(kingSquare, isKingInCheck);

            return kingSquareContainer;
        }
    }

    @PutMapping("/load-fen")
    public void loadFen(@RequestBody @NonNull FENStringHandler fenStringHandler,
                        @RequestParam(required = false) String gameId) {
        GameSession session = session(gameId);
        synchronized (session.getLock()) {
            Board board = new Board(fenStringHandler.getFenString());
            session.setBoard(board);
            session.setMovesGenerator(new MovesGenerator(board));
        }
    }

    @PutMapping("/new-game")
    public void newGame(@RequestParam(required = false) String gameId) {
        GameSession session = session(gameId);
        synchronized (session.getLock()) {
            Board board = new Board();
            session.setBoard(board);
            session.setMovesGenerator(new MovesGenerator(board));
        }
    }

    @PutMapping("/make-move")
    public ResponseContainer makeMove(@RequestBody @NonNull MoveHandler moveHandler,
                                      @RequestParam(required = false) String gameId) {
        GameSession session = session(gameId);
        synchronized (session.getLock()) {
            if (session.getMovesGenerator() == null) {
                session.setMovesGenerator(new MovesGenerator(session.getBoard()));
            }

            int[] moveDetails = moveHandler.getMoveDetails();
            Move move = session.getMovesGenerator().findMove(moveDetails[0], moveDetails[1]);

            if (move != null) {
                // Updating the board and the possible moves:
                session.getBoard().makeMove(move);
                session.setMovesGenerator(new MovesGenerator(session.getBoard()));

                return new ResponseContainer(true, session.getBoard(),
                        session.getMovesGenerator().getActiveMoves(session.getBoard().getActiveColor()));
            }

            return new ResponseContainer(false);
        }
    }
}