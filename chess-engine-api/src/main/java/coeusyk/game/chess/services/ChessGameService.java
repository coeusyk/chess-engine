package coeusyk.game.chess.services;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.movegen.MovesGenerator;
import coeusyk.game.chess.utils.*;
import org.springframework.stereotype.Service;

import java.util.ArrayList;

@Service
public class ChessGameService {
    private final GameSessionStore sessionStore;

    public ChessGameService(GameSessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    private GameSession session(String gameId) {
        return sessionStore.getOrCreate(gameId);
    }

    public SetupContainer setup(String gameId) {
        GameSession session = session(gameId);
        synchronized (session.getLock()) {
            session.setMovesGenerator(new MovesGenerator(session.getBoard()));
            ArrayList<Move> possibleMoves = session.getMovesGenerator().getActiveMoves(session.getBoard().getActiveColor());
            return new SetupContainer(session.getBoard(), possibleMoves);
        }
    }

    public PieceMovesContainer getPieceMoves(int pieceSquare, String gameId) {
        GameSession session = session(gameId);
        synchronized (session.getLock()) {
            if (session.getMovesGenerator() == null) {
                session.setMovesGenerator(new MovesGenerator(session.getBoard()));
            }

            ArrayList<Move> pieceMoves = session.getMovesGenerator().getPieceMoves(pieceSquare);
            return new PieceMovesContainer(pieceMoves);
        }
    }

    public KingInCheckContainer getKingInCheck(int activeColor, String gameId) {
        GameSession session = session(gameId);
        synchronized (session.getLock()) {
            if (session.getMovesGenerator() == null) {
                session.setMovesGenerator(new MovesGenerator(session.getBoard()));
            }

            int kingSquare = session.getBoard().getKingSquare(activeColor);
            boolean[] isKingInCheck = session.getMovesGenerator().isKingInCheck(kingSquare, activeColor);
            return new KingInCheckContainer(kingSquare, isKingInCheck);
        }
    }

    public void loadFen(FENStringHandler fenStringHandler, String gameId) {
        GameSession session = session(gameId);
        synchronized (session.getLock()) {
            Board board = new Board(fenStringHandler.getFenString());
            session.setBoard(board);
            session.setMovesGenerator(new MovesGenerator(board));
        }
    }

    public void newGame(String gameId) {
        GameSession session = session(gameId);
        synchronized (session.getLock()) {
            Board board = new Board();
            session.setBoard(board);
            session.setMovesGenerator(new MovesGenerator(board));
        }
    }

    public ResponseContainer makeMove(MoveHandler moveHandler, String gameId) {
        GameSession session = session(gameId);
        synchronized (session.getLock()) {
            if (session.getMovesGenerator() == null) {
                session.setMovesGenerator(new MovesGenerator(session.getBoard()));
            }

            int[] moveDetails = moveHandler.getMoveDetails();
            Move move = session.getMovesGenerator().findMove(moveDetails[0], moveDetails[1]);

            if (move != null) {
                session.getBoard().makeMove(move);
                session.setMovesGenerator(new MovesGenerator(session.getBoard()));

                return new ResponseContainer(true, session.getBoard(),
                        session.getMovesGenerator().getActiveMoves(session.getBoard().getActiveColor()));
            }

            return new ResponseContainer(false);
        }
    }
}
