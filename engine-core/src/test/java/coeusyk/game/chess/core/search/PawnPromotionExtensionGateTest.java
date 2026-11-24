package coeusyk.game.chess.core.search;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.models.Piece;
import coeusyk.game.chess.core.movegen.MovesGenerator;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #267 item 5. {@code shouldApplyPawnPromotionExtension} ended in
 * {@code evaluateSquareOccupation(board, targetSquare) >= 0}, documented as "the advance is not
 * immediately losing by SEE". For a pawn occupant that expression is {@code 100 - bestReplyGain} and
 * {@code bestReplyGain} is a capture gain bounded by the pawn's own value, so it can never be negative:
 * a pawn that hangs for nothing scores 0 and passes. The gate therefore never rejects anything.
 *
 * <p>These tests reproduce that (a free pawn passes, and no position gives a negative value) and pin the
 * gate's observable behaviour, so removing the dead call is shown to change nothing.
 */
class PawnPromotionExtensionGateTest {

    // g6-g7: the pawn lands attacked by the rook on g8 and defended by nothing, so it hangs for free.
    private static final String HANGING = "6r1/8/6P1/8/8/8/8/K6k w - - 0 1";
    // same push, but the bishop on h6 defends g7, so Rxg7 Bxg7 wins the exchange for White.
    private static final String DEFENDED = "6r1/8/6PB/8/8/8/8/K6k w - - 0 1";
    // colour-reversed case: the black pawn pushes b3-b2 onto its 2nd rank, attacked by the rook on b1 and undefended
    private static final String HANGING_BLACK = "k7/8/8/8/8/1p6/8/1R5K b - - 0 1";

    private static Board board(String fen) {
        Board board = new Board(fen);
        board.setSearchMode(true);
        return board;
    }

    private static int moveBetween(Board board, int from, int to) {
        int[] moves = new int[256];
        int count = MovesGenerator.generate(board, moves);
        for (int i = 0; i < count; i++) {
            if (Move.from(moves[i]) == from && Move.to(moves[i]) == to) {
                return moves[i];
            }
        }
        throw new AssertionError("no legal move " + from + "->" + to);
    }

    private static boolean gate(Searcher searcher, Board afterMove, int move) throws Exception {
        Method method = Searcher.class.getDeclaredMethod("shouldApplyPawnPromotionExtension",
                Board.class, int.class, boolean.class, int.class, int.class);
        method.setAccessible(true);
        return (boolean) method.invoke(searcher, afterMove, move, afterMove.isActiveColorInCheck(), 0, 16);
    }

    /** The gate as it was written, evaluated independently of the production method. */
    private static boolean legacyGate(boolean seeEnabled, Board afterMove, int move) {
        if (!seeEnabled) {
            return false;
        }
        return new StaticExchangeEvaluator().evaluateSquareOccupation(afterMove, Move.to(move)) >= 0;
    }

    @Test
    void aPawnThatHangsForNothingScoresZeroAndPassesTheGate() throws Exception {
        Board board = board(HANGING);
        int push = moveBetween(board, 22, 14); // g6-g7
        board.makeMove(push);

        assertEquals(0, new StaticExchangeEvaluator().evaluateSquareOccupation(board, 14),
                "a pawn captured for free is worth 100 - 100 = 0, exactly the gate's threshold");
        assertTrue(gate(new Searcher(), board, push), "the push is extended although the pawn is lost");
    }

    @Test
    void theOccupationValueOfAPawnIsNeverNegative() {
        StaticExchangeEvaluator see = new StaticExchangeEvaluator();
        String[] seeds = {
                "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1",
                "r2q1rk1/pP1p2pp/Q4n2/bbp1p3/Np6/1B3NBn/pPPP1PPP/R3K2R b KQ - 0 1",
                "8/PPP4k/8/8/8/8/4pppK/8 w - - 0 1",
                HANGING, DEFENDED, HANGING_BLACK,
        };
        Random random = new Random(20261124L);
        int checked = 0;
        for (String seed : seeds) {
            for (int game = 0; game < 40; game++) {
                Board board = board(seed);
                for (int ply = 0; ply < 40; ply++) {
                    for (int square = 0; square < 64; square++) {
                        int piece = board.getPiece(square);
                        if (Piece.type(piece) != Piece.Pawn) {
                            continue;
                        }
                        int rank = square / 8;
                        if ((Piece.isWhite(piece) && rank == 1) || (Piece.isBlack(piece) && rank == 6)) {
                            int value = see.evaluateSquareOccupation(board, square);
                            assertTrue(value >= 0 && value <= 100, "value " + value + " in " + board.toFen());
                            checked++;
                        }
                    }
                    int[] moves = new int[256];
                    int count = MovesGenerator.generate(board, moves);
                    if (count == 0) {
                        break;
                    }
                    board.makeMove(moves[random.nextInt(count)]);
                }
            }
        }
        assertTrue(checked > 200, "the scan must actually meet seventh-rank pawns, saw " + checked);
    }

    @Test
    void theGateBehavesLikeTheSeeEnabledFlagAlone() throws Exception {
        record Case(String fen, int from, int to) { }
        Case[] cases = {new Case(HANGING, 22, 14), new Case(DEFENDED, 22, 14), new Case(HANGING_BLACK, 41, 49)};
        for (boolean seeEnabled : new boolean[] {true, false}) {
            for (Case c : cases) {
                Searcher searcher = new Searcher();
                searcher.setSeeEnabledForTesting(seeEnabled);
                Board board = board(c.fen());
                int push = moveBetween(board, c.from(), c.to());
                board.makeMove(push);
                assertEquals(legacyGate(seeEnabled, board, push), gate(searcher, board, push), c.fen() + " seeEnabled=" + seeEnabled);
                assertEquals(seeEnabled, gate(searcher, board, push), c.fen() + " seeEnabled=" + seeEnabled);
            }
        }
    }

    @Test
    void theGateStillRejectsWhatItRejectedBeforeTheSeeCall() throws Exception {
        // not a seventh-rank pawn
        Board board = board("8/8/8/6P1/8/8/8/K6k w - - 0 1");
        int push = moveBetween(board, 30, 22); // g5-g6
        board.makeMove(push);
        assertFalse(gate(new Searcher(), board, push));

        // extension budget exhausted
        Board hanging = board(HANGING);
        int g7 = moveBetween(hanging, 22, 14);
        hanging.makeMove(g7);
        Method method = Searcher.class.getDeclaredMethod("shouldApplyPawnPromotionExtension",
                Board.class, int.class, boolean.class, int.class, int.class);
        method.setAccessible(true);
        assertFalse((boolean) method.invoke(new Searcher(), hanging, g7, false, 16, 16));
    }
}
