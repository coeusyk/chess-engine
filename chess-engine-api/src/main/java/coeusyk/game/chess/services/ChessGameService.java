package coeusyk.game.chess.services;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.models.Piece;
import coeusyk.game.chess.core.movegen.MovesGenerator;
import coeusyk.game.chess.core.notation.SanConverter;
import coeusyk.game.chess.utils.*;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ChessGameService {
    private final GameSessionStore sessionStore;
    private final SanConverter sanConverter = new SanConverter();

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
            return new SetupContainer(session.getBoard(), toNotationMoves(session.getBoard(), possibleMoves));
        }
    }

    public PieceMovesContainer getPieceMoves(int pieceSquare, String gameId) {
        GameSession session = session(gameId);
        synchronized (session.getLock()) {
            if (session.getMovesGenerator() == null) {
                session.setMovesGenerator(new MovesGenerator(session.getBoard()));
            }

            ArrayList<Move> pieceMoves = session.getMovesGenerator().getPieceMoves(pieceSquare);
            return new PieceMovesContainer(toNotationMoves(session.getBoard(), pieceMoves));
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
                session.getRedoStack().clear();
                session.setMovesGenerator(new MovesGenerator(session.getBoard()));

                return new ResponseContainer(true, session.getBoard(),
                    toNotationMoves(
                        session.getBoard(),
                        session.getMovesGenerator().getActiveMoves(session.getBoard().getActiveColor())
                    ));
            }

            return new ResponseContainer(false);
        }
    }

    private ArrayList<MoveNotation> toNotationMoves(Board board, ArrayList<Move> moves) {
        ArrayList<MoveNotation> result = new ArrayList<>(moves.size());
        for (Move move : moves) {
            result.add(new MoveNotation(
                    move.startSquare,
                    move.targetSquare,
                    move.reaction,
                    UciConverter.toUci(move),
                    sanConverter.toSan(move, board)
            ));
        }
        return result;
    }

    // ── Phase 6 lifecycle methods ─────────────────────────────────────────────

    public String createGame() {
        return sessionStore.create();
    }

    public void resetGame(String gameId) {
        GameSession session = sessionStore.get(gameId);
        synchronized (session.getLock()) {
            Board board = new Board();
            session.setBoard(board);
            session.setMovesGenerator(new MovesGenerator(board));
            session.getRedoStack().clear();
        }
    }

    public void loadFenForGame(String gameId, String fen) {
        GameSession session = sessionStore.get(gameId);
        synchronized (session.getLock()) {
            Board board = new Board(fen);
            session.setBoard(board);
            session.setMovesGenerator(new MovesGenerator(board));
            session.getRedoStack().clear();
        }
    }

    public boolean undoMove(String gameId) {
        GameSession session = sessionStore.get(gameId);
        synchronized (session.getLock()) {
            Board board = session.getBoard();
            if (board.movesPlayed.isEmpty()) return false;
            Move lastMove = board.movesPlayed.get(board.movesPlayed.size() - 1);
            board.unmakeMove();
            session.getRedoStack().push(lastMove);
            session.setMovesGenerator(new MovesGenerator(board));
            return true;
        }
    }

    public boolean redoMove(String gameId) {
        GameSession session = sessionStore.get(gameId);
        synchronized (session.getLock()) {
            if (session.getRedoStack().isEmpty()) return false;
            Move move = session.getRedoStack().pop();
            session.getBoard().makeMove(move);
            session.setMovesGenerator(new MovesGenerator(session.getBoard()));
            return true;
        }
    }

    public GameStateResponse getState(String gameId) {
        GameSession session = sessionStore.get(gameId);
        synchronized (session.getLock()) {
            Board board = session.getBoard();
            List<String> moveHistory = new ArrayList<>();
            for (Move m : board.movesPlayed) {
                moveHistory.add(UciConverter.toUci(m));
            }
            String activeColor = Piece.isWhite(board.getActiveColor()) ? "WHITE" : "BLACK";
            boolean canUndo = !board.movesPlayed.isEmpty();
            boolean canRedo = !session.getRedoStack().isEmpty();
            return new GameStateResponse(
                board.toFen(), moveHistory, computeStatus(board),
                activeColor, canUndo, canRedo
            );
        }
    }

    private String computeStatus(Board board) {
        if (board.isCheckmate()) return "CHECKMATE";
        if (board.isStalemate()) return "STALEMATE";
        if (board.isFiftyMoveRuleDraw()) return "DRAW_50_MOVE";
        if (board.isThreefoldRepetition()) return "DRAW_REPETITION";
        if (board.isInsufficientMaterial()) return "DRAW_INSUFFICIENT_MATERIAL";
        return "IN_PROGRESS";
    }
}
