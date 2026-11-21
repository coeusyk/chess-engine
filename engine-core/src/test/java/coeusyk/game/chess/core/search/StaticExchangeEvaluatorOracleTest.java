package coeusyk.game.chess.core.search;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.models.Piece;
import coeusyk.game.chess.core.movegen.MovesGenerator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #262: differential test of {@link StaticExchangeEvaluator} against a slow oracle that
 * plays the same least-valuable-attacker exchange but takes every recapture from full
 * <em>legal</em> move generation, so pins, king safety, en passant and promotions are decided
 * by the move generator and not by SEE's own bitboard shortcuts.
 */
class StaticExchangeEvaluatorOracleTest {

    private static final int[] VALUE = {0, 100, 320, 330, 500, 900, 20_000};

    private static final String[] FENS = {
        "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
        "r3r1k1/2p2ppp/p1p1bn2/8/1q2P3/2NPQN2/PPP3PP/R4RK1 b - - 2 24",
        "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
        "r4rk1/3qppbp/6p1/2p1n3/2B1P3/2P5/P2Q1PPP/R4RK1 b - - 0 20",
        "r1bqkbnr/pppp1ppp/2n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3",   // Bb5 pins Nc6
        "rnb1kbnr/pppp1ppp/8/4p3/4P2q/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3",
        "4k3/4n3/8/5p2/8/8/2B5/4R1K1 w - - 0 1",                               // issue #262 pin
        "4kr2/4n3/8/5p2/8/8/2B5/4R1K1 w - - 0 1",
        "4k3/8/8/8/8/8/7p/4K2R b - - 0 1",                                     // issue #262 h1
        "4k3/8/8/8/8/8/6p1/4K2R b - - 0 1",
        "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1", // kiwipete
        "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 b - - 0 1",
        "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8",           // promotions
        "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1",
        "4k3/8/8/2KPp1r1/8/8/8/8 w - e6 0 1",                                  // en passant on a pinned rank
        "7k/8/8/8/8/8/6pp/R5RK w - - 0 1",
    };

    private static int pieceValue(Board board, int square) {
        return VALUE[Piece.type(board.getPiece(square))];
    }

    private static List<Move> legalMoves(Board board) {
        return new MovesGenerator(board).getActiveMoves(board.getActiveColor());
    }

    /** Material the side to move gains by starting a capture sequence on {@code target}. */
    private static int oracleGain(Board board, int target) {
        Move best = null;
        int bestValue = Integer.MAX_VALUE;
        for (Move m : legalMoves(board)) {
            if (m.targetSquare != target) {
                continue;
            }
            boolean enPassant = "en-passant".equals(m.reaction);
            if (!enPassant && board.getPiece(target) == Piece.None) {
                continue; // not a capture
            }
            if (m.reaction != null && m.reaction.startsWith("promote-") && !"promote-q".equals(m.reaction)) {
                continue; // SEE assumes a queen promotion
            }
            int value = VALUE[Piece.type(board.getPiece(m.startSquare))];
            if (value < bestValue || (value == bestValue && m.startSquare < best.startSquare)) {
                best = m;
                bestValue = value;
            }
        }
        if (best == null) {
            return 0;
        }
        int captured = "en-passant".equals(best.reaction) ? VALUE[Piece.Pawn] : pieceValue(board, target);
        int promo = "promote-q".equals(best.reaction) ? VALUE[Piece.Queen] - VALUE[Piece.Pawn] : 0;
        board.makeMove(best);
        int continuation = oracleGain(board, target);
        board.unmakeMove();
        return Math.max(0, captured + promo - continuation);
    }

    private static int oracleSee(Board board, Move move) {
        boolean enPassant = "en-passant".equals(move.reaction);
        int captured = enPassant ? VALUE[Piece.Pawn] : pieceValue(board, move.targetSquare);
        int promo = switch (move.reaction == null ? "" : move.reaction) {
            case "promote-q" -> VALUE[Piece.Queen] - VALUE[Piece.Pawn];
            case "promote-r" -> VALUE[Piece.Rook] - VALUE[Piece.Pawn];
            case "promote-b" -> VALUE[Piece.Bishop] - VALUE[Piece.Pawn];
            case "promote-n" -> VALUE[Piece.Knight] - VALUE[Piece.Pawn];
            default -> 0;
        };
        board.makeMove(move);
        int reply = oracleGain(board, move.targetSquare);
        board.unmakeMove();
        return captured + promo - reply;
    }

    private static int checkPosition(Board board, StaticExchangeEvaluator see, String context) {
        int compared = 0;
        for (Move move : legalMoves(board)) {
            boolean capture = "en-passant".equals(move.reaction) || board.getPiece(move.targetSquare) != Piece.None;
            if (!capture) {
                continue;
            }
            String fen = board.toFen();
            int expected = oracleSee(board, move);
            int actual = see.evaluate(board, move);
            assertEquals(expected, actual, "SEE differs from legal-move oracle for " + move.startSquare + "->"
                    + move.targetSquare + " in " + fen + " (" + context + ")");
            assertEquals(fen, board.toFen(), "SEE must leave the board untouched");
            compared++;
        }
        return compared;
    }

    @Test
    void seeAgreesWithLegalMoveOracleOnFixedPositions() {
        StaticExchangeEvaluator see = new StaticExchangeEvaluator();
        int compared = 0;
        for (String fen : FENS) {
            compared += checkPosition(new Board(fen), see, "fixed");
        }
        assertTrue(compared > 30, "corpus should exercise a meaningful number of captures, got " + compared);
    }

    @Test
    void seeAgreesWithLegalMoveOracleAlongRandomPlayouts() {
        StaticExchangeEvaluator see = new StaticExchangeEvaluator();
        Random random = new Random(262);
        int compared = 0;
        for (String fen : FENS) {
            Board board = new Board(fen);
            for (int ply = 0; ply < 60; ply++) {
                compared += checkPosition(board, see, "playout ply " + ply);
                List<Move> moves = new ArrayList<>(legalMoves(board));
                if (moves.isEmpty()) {
                    break;
                }
                board.makeMove(moves.get(random.nextInt(moves.size())));
            }
        }
        assertTrue(compared > 500, "playouts should exercise many captures, got " + compared);
    }
}
