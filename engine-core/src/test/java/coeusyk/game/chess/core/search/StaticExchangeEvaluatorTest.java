package coeusyk.game.chess.core.search;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.models.Piece;
import coeusyk.game.chess.core.movegen.MovesGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class StaticExchangeEvaluatorTest {

    @Test
    void seeEvaluatesEqualPawnExchangeNearZero() {
        Board board = new Board("4k3/8/4p3/3p4/4P3/8/8/4K3 w - - 0 1");
        Move move = findMove(board, 36, 27); // e4xd5

        int see = new StaticExchangeEvaluator().evaluate(board, move);

        assertEquals(0, see);
    }

    @Test
    void seeEvaluatesWinningCaptureAsPositive() {
        Board board = new Board("4k3/8/8/3n4/4P3/8/8/4K3 w - - 0 1");
        Move move = findMove(board, 36, 27); // e4xd5 (pawn takes knight)

        int see = new StaticExchangeEvaluator().evaluate(board, move);

        assertTrue(see > 0, "Expected winning capture SEE > 0");
    }

    @Test
    void seeEvaluatesLosingCaptureAsNegative() {
        Board board = new Board("4k3/5p2/4p3/8/5N2/8/8/4K3 w - - 0 1");
        Move move = findMove(board, 37, 20); // Nf4xe6 defended by f7-pawn

        int see = new StaticExchangeEvaluator().evaluate(board, move);

        assertTrue(see < 0, "Expected defended losing capture SEE < 0");
    }

    @Test
    void seeIncludesXrayRecaptures() {
        Board board = new Board("k2r4/8/3b4/8/8/6B1/8/7K w - - 0 1");
        Move move = findMove(board, 46, 19); // Bg3xd6, rook behind bishop can recapture

        int see = new StaticExchangeEvaluator().evaluate(board, move);

        assertTrue(see <= 0, "Expected x-ray recapture to neutralize or punish the capture");
    }

    // ---- Issue #262 ----

    /** a8 = 0 ... h8 = 7, a1 = 56 ... h1 = 63. */
    private static int sq(String name) {
        return (8 - (name.charAt(1) - '0')) * 8 + (name.charAt(0) - 'a');
    }

    private int seeOf(String fen, String from, String to) {
        Board board = new Board(fen);
        return new StaticExchangeEvaluator().evaluate(board, findMove(board, sq(from), sq(to)));
    }

    // Signed right shift on h1 (bit 63) sign-extended and fabricated a pawn on h2 capturing h1.
    @Test
    void blockedPawnOnH2DoesNotAttackH1() {
        Board board = new Board("4k3/8/8/8/8/8/7p/4K2R b - - 0 1");
        assertEquals(0, new StaticExchangeEvaluator().captureGainFor(board, sq("h1"), Piece.Black),
                "a pawn on h2 cannot capture h1; only the g2 pawn can");
    }

    @Test
    void realPawnCapturePromotionOnH1StillScores() {
        Board board = new Board("4k3/8/8/8/8/8/6p1/4K2R b - - 0 1");
        // gxh1=Q wins the rook (500) and promotes (900 - 100).
        assertEquals(1300, new StaticExchangeEvaluator().captureGainFor(board, sq("h1"), Piece.Black));
    }

    @Test
    void mirroredWhitePromotionCapturesOnA8AndH8Score() {
        assertEquals(1300, new StaticExchangeEvaluator().captureGainFor(
                new Board("r3k3/1P6/8/8/8/8/8/4K3 w - - 0 1"), sq("a8"), Piece.White));
        assertEquals(1300, new StaticExchangeEvaluator().captureGainFor(
                new Board("4k2r/6P1/8/8/8/8/8/4K3 w - - 0 1"), sq("h8"), Piece.White));
    }

    @Test
    void blackPawnAttackersAreFoundOnEveryEdgeSquare() {
        // a1 (56) and h1 (63) both hold a white rook; the capturing pawn is on b2 / g2.
        assertEquals(1300, new StaticExchangeEvaluator().captureGainFor(
                new Board("4k3/8/8/8/8/8/1p6/R3K3 b - - 0 1"), sq("a1"), Piece.Black));
        assertEquals(0, new StaticExchangeEvaluator().captureGainFor(
                new Board("4k3/8/8/8/8/8/p7/R3K3 b - - 0 1"), sq("a1"), Piece.Black),
                "a pawn straight ahead of a1 on a2 does not attack it");
    }

    // The knight on e7 is pinned to its king by the rook on e1, so it cannot recapture on f5.
    @Test
    void pinnedKnightCannotRecaptureSoPawnWinIsFree() {
        assertEquals(100, seeOf("4k3/4n3/8/5p2/8/8/2B5/4R1K1 w - - 0 1", "c2", "f5"));
    }

    @Test
    void sameExchangeWithoutThePinStillLosesTheBishop() {
        // Rook off the e-file: Nxf5 is legal, so Bxf5 wins a pawn and loses a bishop.
        assertEquals(-230, seeOf("4k3/4n3/8/5p2/8/8/2B5/R5K1 w - - 0 1", "c2", "f5"));
    }

    @Test
    void pinnedRecapturerIsSkippedInFavourOfTheNextLegalAttacker() {
        // Nxf5 is illegal (pin) but Rf8xf5 is legal, so the bishop is still lost: 100 - 330.
        assertEquals(-230, seeOf("4kr2/4n3/8/5p2/8/8/2B5/4R1K1 w - - 0 1", "c2", "f5"));
    }

    @Test
    void pinMovingAlongThePinRayIsStillLegal() {
        // The black rook on e7 is pinned on the e-file but can recapture on e5 along that file.
        // Rxe5 is legal, so the exchange Rxe5 Rxe5 is evaluated normally rather than ignored.
        assertEquals(-400, seeOf("4k3/4r3/8/4p3/8/8/8/R3R1K1 w - - 0 1", "e1", "e5"),
                "Re1xe5 Rxe5: win a pawn (100), lose a rook (500)");
    }

    // The exchange is evaluated for the colour that is NOT to move: the second white attacker must
    // not recapture its own piece. Old code recursed with board.getActiveColor(), which is the
    // attacker again after makeMove flips it back.
    @Test
    void captureGainForNonActiveColourDoesNotRecaptureItsOwnPiece() {
        Board board = new Board("4k3/8/8/3n4/4P3/8/8/3QK3 b - - 0 1");
        assertEquals(320, new StaticExchangeEvaluator().captureGainFor(board, sq("d5"), Piece.White));
        assertEquals(Piece.Black, board.getActiveColor(), "the board must be left exactly as it was");
    }

    @Test
    void captureGainForActiveColourIsUnchanged() {
        Board board = new Board("4k3/8/8/3n4/4P3/8/8/3QK3 w - - 0 1");
        assertEquals(320, new StaticExchangeEvaluator().captureGainFor(board, sq("d5"), Piece.White));
    }

    private Move findMove(Board board, int startSquare, int targetSquare) {
        List<Move> moves = new MovesGenerator(board).getActiveMoves(board.getActiveColor());
        for (Move move : moves) {
            if (move.startSquare == startSquare && move.targetSquare == targetSquare) {
                return move;
            }
        }

        fail("Expected legal move not found");
        return null;
    }
}
